package com.benzeneos.pushcompat

import android.os.SystemClock
import android.util.Base64
import java.io.Closeable
import java.io.DataInputStream
import java.io.EOFException
import java.io.InputStream
import java.io.OutputStream
import java.net.InetSocketAddress
import java.net.Socket
import java.net.SocketTimeoutException
import java.net.URI
import java.security.MessageDigest
import java.security.SecureRandom
import javax.net.ssl.SSLSocket
import javax.net.ssl.SSLSocketFactory

class RelayWebSocket(
    val bridgeUrl: BridgeUrl,
) : Closeable {
    private val stateLock = Object()
    private val writeLock = Object()

    @Volatile
    private var socket: Socket? = null

    @Volatile
    private var output: OutputStream? = null

    @Volatile
    private var closed = false

    fun run(
        onConnected: () -> Unit,
        onFrame: (String) -> Unit,
    ) {
        val connected = connect()
        try {
            onConnected()
            val input = DataInputStream(connected.inputStream)
            var timeoutCount = 0
            val refreshAt =
                SystemClock.elapsedRealtime() + RelayStore.REGISTRATION_INTERVAL_MILLIS
            while (!connected.isClosed) {
                if (SystemClock.elapsedRealtime() >= refreshAt) {
                    return
                }
                val frame =
                    try {
                        readFrame(input)
                    } catch (_: SocketTimeoutException) {
                        if (SystemClock.elapsedRealtime() >= refreshAt) {
                            return
                        }
                        timeoutCount++
                        if (timeoutCount >= 2) {
                            error("WebSocket heartbeat timed out")
                        }
                        writeFrame(requireOutput(), OPCODE_PING, ByteArray(0))
                        continue
                    }
                timeoutCount = 0
                when (frame.opcode) {
                    OPCODE_TEXT -> onFrame(frame.payload.toString(Charsets.UTF_8))
                    OPCODE_PING -> writeFrame(requireOutput(), OPCODE_PONG, frame.payload)
                    OPCODE_PONG -> Unit
                    OPCODE_CLOSE -> return
                    else -> error("unsupported WebSocket frame ${frame.opcode}")
                }
            }
        } finally {
            close()
        }
    }

    fun sendText(frame: String) {
        writeFrame(requireOutput(), OPCODE_TEXT, frame.toByteArray(Charsets.UTF_8))
    }

    override fun close() {
        val connected =
            synchronized(stateLock) {
                closed = true
                output = null
                socket.also { socket = null }
            }
        runCatching { connected?.close() }
    }

    private fun requireOutput(): OutputStream =
        output ?: error("WebSocket is not connected")

    private fun connect(): SSLSocket {
        val baseUri = URI(bridgeUrl.value)
        val host = baseUri.host ?: error("bridge URL has no host")
        val port = if (baseUri.port == -1) 443 else baseUri.port
        val basePath = baseUri.rawPath.orEmpty().trimEnd('/')
        val requestPath = "$basePath/socket?version=2"
        check(!closed) { "WebSocket was closed before connecting" }
        val plainSocket = Socket()
        synchronized(stateLock) {
            if (closed) {
                plainSocket.close()
                error("WebSocket was closed before connecting")
            }
            socket = plainSocket
        }
        try {
            plainSocket.connect(InetSocketAddress(host, port), CONNECT_TIMEOUT_MILLIS)
            val connected =
                (SSLSocketFactory.getDefault() as SSLSocketFactory)
                    .createSocket(plainSocket, host, port, true) as SSLSocket
            synchronized(stateLock) {
                if (closed) {
                    connected.close()
                    error("WebSocket was closed before connecting")
                }
                socket = connected
            }
            connected.soTimeout = READ_TIMEOUT_MILLIS
            connected.sslParameters =
                connected.sslParameters.apply {
                    endpointIdentificationAlgorithm = "HTTPS"
                }
            connected.startHandshake()

            val nonce = ByteArray(16).also { SecureRandom().nextBytes(it) }
            val webSocketKey = Base64.encodeToString(nonce, Base64.NO_WRAP)
            val hostHeader = if (port == 443) host else "$host:$port"
            val request =
                buildString {
                    append("GET $requestPath HTTP/1.1\r\n")
                    append("Host: $hostHeader\r\n")
                    append("Upgrade: websocket\r\n")
                    append("Connection: Upgrade\r\n")
                    append("Sec-WebSocket-Key: $webSocketKey\r\n")
                    append("Sec-WebSocket-Version: 13\r\n")
                    append("\r\n")
                }
            connected.outputStream.write(request.toByteArray(Charsets.US_ASCII))
            connected.outputStream.flush()

            val statusLine = readHttpLine(connected.inputStream)
            if (!statusLine.startsWith("HTTP/1.1 101 ") &&
                !statusLine.startsWith("HTTP/1.0 101 ")
            ) {
                error("WebSocket upgrade failed: ${statusLine.take(128)}")
            }
            val headers = mutableMapOf<String, String>()
            var headerCount = 0
            while (true) {
                check(headerCount++ < MAX_HTTP_HEADERS) {
                    "WebSocket handshake has too many headers"
                }
                val line = readHttpLine(connected.inputStream)
                if (line.isEmpty()) {
                    break
                }
                val separator = line.indexOf(':')
                if (separator > 0) {
                    headers[line.substring(0, separator).trim().lowercase()] =
                        line.substring(separator + 1).trim()
                }
            }
            check(headers["upgrade"].equals("websocket", ignoreCase = true)) {
                "WebSocket server omitted the Upgrade header"
            }
            check(
                headers["connection"]
                    ?.split(',')
                    ?.any { it.trim().equals("upgrade", ignoreCase = true) } == true,
            ) {
                "WebSocket server omitted the Connection upgrade token"
            }
            val expectedAccept =
                Base64.encodeToString(
                    MessageDigest
                        .getInstance("SHA-1")
                        .digest("$webSocketKey$WEBSOCKET_GUID".toByteArray(Charsets.US_ASCII)),
                    Base64.NO_WRAP,
                )
            check(headers["sec-websocket-accept"] == expectedAccept) {
                "WebSocket server returned an invalid accept key"
            }
            synchronized(stateLock) {
                if (closed) {
                    connected.close()
                    error("WebSocket was closed while connecting")
                }
                output = connected.outputStream
            }
            return connected
        } catch (e: Exception) {
            close()
            throw e
        }
    }

    private fun readHttpLine(input: InputStream): String {
        val bytes = ArrayList<Byte>()
        while (bytes.size <= MAX_HTTP_LINE_BYTES) {
            val value = input.read()
            if (value == -1) {
                throw EOFException("WebSocket handshake ended early")
            }
            if (value == '\n'.code) {
                if (bytes.lastOrNull() == '\r'.code.toByte()) {
                    bytes.removeAt(bytes.lastIndex)
                }
                return bytes.toByteArray().toString(Charsets.US_ASCII)
            }
            bytes.add(value.toByte())
        }
        error("WebSocket handshake line is too long")
    }

    private fun readFrame(input: DataInputStream): Frame {
        val first = input.readUnsignedByte()
        val second = input.readUnsignedByte()
        check(first and 0x80 != 0) { "fragmented WebSocket frames are unsupported" }
        check(first and 0x70 == 0) { "WebSocket extensions are unsupported" }
        check(second and 0x80 == 0) { "server WebSocket frame must not be masked" }
        val opcode = first and 0x0f
        val payloadLength =
            when (val shortLength = second and 0x7f) {
                126 -> input.readUnsignedShort().toLong()
                127 -> input.readLong()
                else -> shortLength.toLong()
            }
        check(payloadLength in 0..MAX_FRAME_BYTES.toLong()) {
            "WebSocket frame is too large"
        }
        if (opcode >= OPCODE_CLOSE) {
            check(payloadLength <= 125) { "WebSocket control frame is too large" }
        }
        val payload = ByteArray(payloadLength.toInt())
        input.readFully(payload)
        return Frame(opcode, payload)
    }

    private fun writeFrame(
        output: OutputStream,
        opcode: Int,
        payload: ByteArray,
    ) {
        synchronized(writeLock) {
            check(payload.size <= MAX_FRAME_BYTES)
            output.write(0x80 or opcode)
            when {
                payload.size <= 125 -> output.write(0x80 or payload.size)
                payload.size <= 0xffff -> {
                    output.write(0x80 or 126)
                    output.write(payload.size ushr 8)
                    output.write(payload.size and 0xff)
                }
                else -> {
                    output.write(0x80 or 127)
                    for (shift in 56 downTo 0 step 8) {
                        output.write((payload.size.toLong() ushr shift).toInt() and 0xff)
                    }
                }
            }
            val mask = ByteArray(4).also { SecureRandom().nextBytes(it) }
            output.write(mask)
            payload.forEachIndexed { index, value ->
                output.write(value.toInt() xor mask[index and 3].toInt())
            }
            output.flush()
        }
    }

    private data class Frame(
        val opcode: Int,
        val payload: ByteArray,
    )

    companion object {
        private const val OPCODE_TEXT = 0x1
        private const val OPCODE_CLOSE = 0x8
        private const val OPCODE_PING = 0x9
        private const val OPCODE_PONG = 0xa
        private const val CONNECT_TIMEOUT_MILLIS = 15_000
        private const val READ_TIMEOUT_MILLIS = 45_000
        private const val MAX_HTTP_LINE_BYTES = 8 * 1024
        private const val MAX_HTTP_HEADERS = 100
        private const val MAX_FRAME_BYTES = 1024 * 1024
        private const val WEBSOCKET_GUID = "258EAFA5-E914-47DA-95CA-C5AB0DC85B11"
    }
}
