package com.benzeneos.pushcompat

import android.util.Base64
import android.util.Slog
import org.json.JSONObject
import javax.crypto.Mac
import javax.crypto.spec.SecretKeySpec

data class SocketEnvelope(
    val id: Long,
    val kind: String,
    val appId: String,
    val connectorToken: String?,
    val payload: ByteArray,
) {
    override fun equals(other: Any?): Boolean =
        this === other ||
            (
                other is SocketEnvelope &&
                    id == other.id &&
                    kind == other.kind &&
                    appId == other.appId &&
                    connectorToken == other.connectorToken &&
                    payload.contentEquals(other.payload)
            )

    override fun hashCode(): Int {
        var result = id.hashCode()
        result = 31 * result + kind.hashCode()
        result = 31 * result + appId.hashCode()
        result = 31 * result + (connectorToken?.hashCode() ?: 0)
        return 31 * result + payload.contentHashCode()
    }
}

sealed class SocketProtocolEvent {
    data object Attached : SocketProtocolEvent()

    data class Message(
        val envelope: SocketEnvelope,
    ) : SocketProtocolEvent()

    data class Unavailable(
        val reason: String,
    ) : SocketProtocolEvent()
}

class SocketProtocol(
    private val store: RelayStore,
) {
    private val bridgeUrl = store.bridgeUrl
    private val installId = store.installId
    private val secret = store.installSecret.toByteArray(Charsets.UTF_8)
    private val payloadKey = SocketPayloadCrypto.deriveKey(secret)

    fun attachFrame(encodedNonce: String): String {
        val nonce = Base64.decode(encodedNonce, Base64.DEFAULT)
        check(nonce.size == SERVER_NONCE_BYTES) { "invalid WebSocket challenge" }
        val proof = hmacSha256(secret, nonce + installId.toByteArray(Charsets.UTF_8))
        return JSONObject()
            .put("type", "attach")
            .put("install_id", installId)
            .put("proof", Base64.encodeToString(proof, Base64.NO_WRAP))
            .put("cursor", store.cursorFor(bridgeUrl))
            .toString()
    }

    fun detachFrame(): String =
        JSONObject()
            .put("type", "detach")
            .put("install_id", installId)
            .toString()

    fun ackFrame(id: Long): String =
        JSONObject()
            .put("type", "ack")
            .put("install_id", installId)
            .put("id", id)
            .toString()

    fun nackFrame(id: Long): String =
        JSONObject()
            .put("type", "nack")
            .put("install_id", installId)
            .put("id", id)
            .toString()

    fun advanceCursor(id: Long) {
        store.advanceCursor(bridgeUrl, id)
    }

    fun parseServerFrame(frame: String): SocketProtocolEvent {
        val json = JSONObject(frame)
        check(json.getString("install_id") == installId) {
            "WebSocket frame routed to the wrong install"
        }
        return when (json.getString("type")) {
            "attached" -> SocketProtocolEvent.Attached
            "message" -> parseMessage(json)
            "detached" -> SocketProtocolEvent.Unavailable("owner detached the profile")
            "attach_error" ->
                SocketProtocolEvent.Unavailable(
                    json.optString("error").ifEmpty { "attach rejected" },
                )
            else -> error("unsupported WebSocket v2 frame")
        }
    }

    private fun parseMessage(json: JSONObject): SocketProtocolEvent.Message {
        val id = json.getLong("id")
        val cursor = store.cursorFor(bridgeUrl)
        if (id <= cursor) {
            // The server owns the id space, so an id at or below the stored
            // cursor means it moved backwards, most likely a bridge database
            // restore. Keeping the stored cursor would strand every future
            // message behind an id the server can take years to reach again.
            Slog.w(TAG, "WebSocket cursor moved backwards from $cursor to $id, resyncing")
            store.resetCursor(bridgeUrl, id - 1)
        }
        val protected =
            JSONObject(
                SocketPayloadCrypto
                    .decrypt(payloadKey, installId, id, json.getString("ciphertext"))
                    .toString(Charsets.UTF_8),
            )
        return SocketProtocolEvent.Message(
            SocketEnvelope(
                id = id,
                kind = protected.getString("kind"),
                appId = protected.getString("app_id"),
                connectorToken =
                    protected.optString("connector_token").takeIf { it.isNotEmpty() },
                payload = Base64.decode(protected.getString("payload"), Base64.DEFAULT),
            ),
        )
    }

    companion object {
        private const val TAG = "PushCompat"
        private const val SERVER_NONCE_BYTES = 32

        fun helloNonce(frame: String): String? {
            val json = JSONObject(frame)
            return json.optString("nonce").takeIf {
                json.optString("type") == "hello" && it.isNotEmpty()
            }
        }

        fun installId(frame: String): String? =
            runCatching {
                JSONObject(frame).optString("install_id").takeIf { it.isNotEmpty() }
            }.getOrNull()

        fun frameType(frame: String): String? =
            runCatching { JSONObject(frame).optString("type").takeIf { it.isNotEmpty() } }
                .getOrNull()

        private fun hmacSha256(
            key: ByteArray,
            data: ByteArray,
        ): ByteArray =
            Mac.getInstance("HmacSHA256").run {
                init(SecretKeySpec(key, "HmacSHA256"))
                doFinal(data)
            }
    }
}
