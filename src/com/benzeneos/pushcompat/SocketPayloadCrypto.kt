package com.benzeneos.pushcompat

import java.nio.ByteBuffer
import java.util.Base64
import javax.crypto.Cipher
import javax.crypto.Mac
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.SecretKeySpec

internal object SocketPayloadCrypto {
    private val KEY_CONTEXT = "pushcompat websocket payload v2".toByteArray(Charsets.UTF_8)
    private const val NONCE_BYTES = 12
    private const val TAG_BYTES = 16
    private const val TAG_BITS = 128

    fun deriveKey(secret: ByteArray): ByteArray {
        val extracted = hmacSha256(ByteArray(32), secret)
        return hmacSha256(extracted, KEY_CONTEXT + byteArrayOf(1))
    }

    fun decrypt(
        key: ByteArray,
        installId: String,
        id: Long,
        ciphertext: String,
    ): ByteArray {
        val encoded = Base64.getDecoder().decode(ciphertext)
        require(encoded.size > NONCE_BYTES + TAG_BYTES) {
            "encrypted WebSocket payload is too short"
        }
        val nonce = encoded.copyOfRange(0, NONCE_BYTES)
        val protected = encoded.copyOfRange(NONCE_BYTES, encoded.size)
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(
            Cipher.DECRYPT_MODE,
            SecretKeySpec(key, "AES"),
            GCMParameterSpec(TAG_BITS, nonce),
        )
        cipher.updateAAD(messageAad(installId, id))
        return cipher.doFinal(protected)
    }

    private fun hmacSha256(
        key: ByteArray,
        data: ByteArray,
    ): ByteArray =
        Mac.getInstance("HmacSHA256").run {
            init(SecretKeySpec(key, "HmacSHA256"))
            doFinal(data)
        }

    private fun messageAad(
        installId: String,
        id: Long,
    ): ByteArray =
        installId.toByteArray(Charsets.UTF_8) +
            byteArrayOf(0) +
            ByteBuffer.allocate(Long.SIZE_BYTES).putLong(id).array()
}
