package com.benzeneos.pushcompat

import org.json.JSONArray
import org.json.JSONObject
import java.io.Closeable
import java.security.MessageDigest
import java.util.function.Consumer

interface PushTransport : Closeable {
    fun run(
        onSocketState: (@SocketState Int) -> Unit,
        onMessage: (Int, SocketEnvelope) -> Boolean,
    )
}

data class OnDeviceMcsIdentity(
    val userId: Int,
    val appId: String,
    val sessionJson: String,
    val registrationJson: String,
    val persistentIds: List<String> = emptyList(),
)

class OnDeviceMcsTransport(
    identities: List<OnDeviceMcsIdentity>,
    private val onPersistentId: (Int, String, String) -> Unit = { _, _, _ -> },
) : PushTransport {
    private val users = identities.associate { it.appId to it.userId }
    private val claims = identities.map { claim(it.userId, it.appId) }.toSet()
    private val sessionsJson: String
    private val registrationsJson: String
    private val monitor = Any()

    @Volatile
    private var closed = false

    private var handle = NO_HANDLE

    init {
        check(identities.isNotEmpty()) { "at least one on-device MCS identity is required" }
        check(users.size == identities.size) {
            "one on-device MCS transport cannot serve the same package twice"
        }
        val sessions = JSONObject()
        val registrations = JSONArray()
        identities.forEach { identity ->
            sessions.put(identity.appId, JSONObject(identity.sessionJson))
            registrations.put(
                JSONObject()
                    .put("state", JSONObject(identity.registrationJson))
                    .put("persistent_ids", JSONArray(identity.persistentIds)),
            )
        }
        sessionsJson = sessions.toString()
        registrationsJson = registrations.toString()
    }

    override fun run(
        onSocketState: (@SocketState Int) -> Unit,
        onMessage: (Int, SocketEnvelope) -> Boolean,
    ) {
        claimApps()
        try {
            onSocketState(PushCompatContract.SOCKET_CONNECTING)
            val runHandle = NativeListener.nativeCreate()
            synchronized(monitor) {
                if (closed) {
                    NativeListener.nativeDestroy(runHandle)
                    return
                }
                check(handle == NO_HANDLE) { "on-device MCS transport is already running" }
                handle = runHandle
            }
            try {
                NativeListener.nativeRun(
                    runHandle,
                    sessionsJson,
                    registrationsJson,
                    Consumer { eventJson ->
                        val event = JSONObject(eventJson)
                        when (event.getString("type")) {
                            "connected" ->
                                onSocketState(PushCompatContract.SOCKET_CONNECTED)
                            "message" -> {
                                val appId = event.getString("app_id")
                                val userId =
                                    users[appId]
                                        ?: error("on-device MCS message for an unknown package")
                                val payload =
                                    event.getJSONObject("payload").toString()
                                        .toByteArray(Charsets.UTF_8)
                                val persistentId =
                                    if (event.isNull("persistent_id")) {
                                        null
                                    } else {
                                        event.getString("persistent_id").takeIf { it.isNotEmpty() }
                                    }
                                onMessage(
                                    userId,
                                    SocketEnvelope(
                                        id = stableMessageId(appId, persistentId, payload),
                                        kind = "fcm",
                                        appId = appId,
                                        connectorToken = null,
                                        payload = payload,
                                    ),
                                )
                                persistentId?.let { onPersistentId(userId, appId, it) }
                            }
                            else -> error("unknown on-device MCS event type")
                        }
                    },
                )
            } finally {
                synchronized(monitor) {
                    if (handle == runHandle) {
                        handle = NO_HANDLE
                    }
                }
                NativeListener.nativeDestroy(runHandle)
            }
        } finally {
            releaseApps()
        }
    }

    override fun close() {
        val runHandle =
            synchronized(monitor) {
                closed = true
                handle
            }
        if (runHandle != NO_HANDLE) {
            runCatching { NativeListener.nativeStop(runHandle) }
        }
    }

    private fun stableMessageId(
        appId: String,
        persistentId: String?,
        payload: ByteArray,
    ): Long {
        val digest = MessageDigest.getInstance("SHA-256")
        digest.update(appId.toByteArray(Charsets.UTF_8))
        digest.update(0)
        if (persistentId != null) {
            digest.update(persistentId.toByteArray(Charsets.UTF_8))
        } else {
            digest.update(payload)
        }
        val bytes = digest.digest()
        var id = 0L
        repeat(Long.SIZE_BYTES) { index ->
            id = (id shl Byte.SIZE_BITS) or (bytes[index].toLong() and 0xff)
        }
        id = id and Long.MAX_VALUE
        return if (id == 0L) 1L else id
    }

    private fun claimApps() {
        synchronized(ACTIVE_APPS_LOCK) {
            check(claims.none { it in activeApps }) {
                "an on-device MCS transport is already running for this app"
            }
            activeApps.addAll(claims)
        }
    }

    private fun releaseApps() {
        synchronized(ACTIVE_APPS_LOCK) {
            activeApps.removeAll(claims)
        }
    }

    companion object {
        private const val NO_HANDLE = 0L
        private val ACTIVE_APPS_LOCK = Any()
        private val activeApps = mutableSetOf<String>()

        // The native listener derives its session key from each registration's package
        // name, so one runtime cannot host the same package in two users at once.
        fun partitionByPackage(
            identities: List<OnDeviceMcsIdentity>,
        ): List<List<OnDeviceMcsIdentity>> {
            val groups = mutableListOf<MutableList<OnDeviceMcsIdentity>>()
            for (identity in identities) {
                val group =
                    groups.firstOrNull { candidate ->
                        candidate.none { it.appId == identity.appId }
                    } ?: mutableListOf<OnDeviceMcsIdentity>().also { groups.add(it) }
                group.add(identity)
            }
            return groups
        }

        fun isRunning(
            userId: Int,
            appId: String,
        ): Boolean =
            synchronized(ACTIVE_APPS_LOCK) {
                claim(userId, appId) in activeApps
            }

        private fun claim(
            userId: Int,
            appId: String,
        ): String = "$userId:$appId"
    }
}

class BridgeSocketSession(
    val userId: Int,
    store: RelayStore,
) {
    val installId: String = store.installId
    val protocol = SocketProtocol(store)
}

class BridgeSocketTransport(
    bridgeUrl: BridgeUrl,
    sessions: List<BridgeSocketSession>,
) : PushTransport {
    private val socket = RelayWebSocket(bridgeUrl)
    private val sessions = sessions.associateBy { it.installId }
    private val lock = Object()
    private val attached = mutableSetOf<String>()

    init {
        check(sessions.isNotEmpty()) { "at least one bridge session is required" }
        check(this.sessions.size == sessions.size) { "bridge install IDs must be unique" }
    }

    override fun run(
        onSocketState: (@SocketState Int) -> Unit,
        onMessage: (Int, SocketEnvelope) -> Boolean,
    ) {
        socket.run(
            onConnected = {
                onSocketState(PushCompatContract.SOCKET_CONNECTING)
            },
            onFrame = { frame ->
                val challenge = SocketProtocol.helloNonce(frame)
                if (challenge != null) {
                    synchronized(lock) { attached.clear() }
                    sessions.values.forEach { session ->
                        socket.sendText(session.protocol.attachFrame(challenge))
                    }
                } else {
                    val installId =
                        SocketProtocol.installId(frame)
                            ?: error("WebSocket v2 frame has no install id")
                    val session =
                        sessions[installId]
                            ?: error("WebSocket v2 frame is addressed to an unknown install")
                    when (val event = session.protocol.parseServerFrame(frame)) {
                        SocketProtocolEvent.Attached -> {
                            synchronized(lock) { attached.add(installId) }
                            onSocketState(PushCompatContract.SOCKET_CONNECTED)
                        }
                        is SocketProtocolEvent.Message -> {
                            check(synchronized(lock) { installId in attached }) {
                                "message arrived before attach completed"
                            }
                            if (onMessage(session.userId, event.envelope)) {
                                socket.sendText(session.protocol.ackFrame(event.envelope.id))
                                session.protocol.advanceCursor(event.envelope.id)
                            } else {
                                socket.sendText(session.protocol.nackFrame(event.envelope.id))
                            }
                        }
                        is SocketProtocolEvent.Unavailable -> error(event.reason)
                    }
                }
            },
        )
    }

    override fun close() {
        val detaching = synchronized(lock) { attached.toList().also { attached.clear() } }
        detaching.forEach { installId ->
            runCatching {
                socket.sendText(sessions.getValue(installId).protocol.detachFrame())
            }
        }
        socket.close()
    }
}
