package kittoku.osc.terminal

import android.os.Build
import android.util.Base64
import kittoku.osc.ControlMessage
import kittoku.osc.Result
import kittoku.osc.SharedBridge
import kittoku.osc.Where
import kittoku.osc.extension.capacityAfterLimit
import kittoku.osc.extension.slide
import kittoku.osc.extension.toIntAsUByte
import kittoku.osc.preference.OscPrefKey
import kittoku.osc.preference.accessor.getBooleanPrefValue
import kittoku.osc.preference.accessor.getIntPrefValue
import kittoku.osc.preference.accessor.getSetPrefValue
import kittoku.osc.preference.accessor.getStringPrefValue
import kittoku.osc.preference.accessor.getURIPrefValue
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.yield
import java.io.BufferedInputStream
import java.io.InputStream
import java.io.OutputStream
import java.net.Socket
import java.net.SocketTimeoutException
import java.nio.ByteBuffer
import java.security.KeyStore
import java.security.cert.CertPathValidatorException
import java.security.cert.Certificate
import java.security.cert.CertificateException
import java.security.cert.CertificateFactory
import java.security.cert.X509Certificate
import javax.net.ssl.HttpsURLConnection
import javax.net.ssl.SNIHostName
import javax.net.ssl.SSLContext
import javax.net.ssl.SSLEngine
import javax.net.ssl.SSLEngineResult
import javax.net.ssl.SSLSession
import javax.net.ssl.TrustManager
import javax.net.ssl.TrustManagerFactory


private const val HTTP_DELIMITER = "\r\n"
private const val HTTP_SUFFIX = "\r\n\r\n"

internal const val SSL_REQUEST_INTERVAL = 10_000L

internal class SSLTerminal(private val bridge: SharedBridge) {
    private val mutex = Mutex()

    private var socket: Socket? = null
    private var closed = false
    private lateinit var socketInputStream: InputStream
    private lateinit var socketOutputStream: OutputStream
    private lateinit var inboundBuffer: ByteBuffer
    private lateinit var outboundBuffer: ByteBuffer

    private lateinit var engine: SSLEngine

    private var jobInitialize: Job? = null

    private val doUseProxy = getBooleanPrefValue(OscPrefKey.PROXY_DO_USE_PROXY, bridge.prefs)
    private val sslHostname = getStringPrefValue(OscPrefKey.HOME_HOSTNAME, bridge.prefs)
    private val sslPort = getIntPrefValue(OscPrefKey.SSL_PORT, bridge.prefs)
    private val selectedVersion = getStringPrefValue(OscPrefKey.SSL_VERSION, bridge.prefs)
    private val enabledSuites = getSetPrefValue(OscPrefKey.SSL_SUITES, bridge.prefs)

    internal fun initialize() {
        jobInitialize = bridge.service.scope.launch(bridge.handler) {
            if (!establishSSL()) return@launch

            bridge.service.stage(bridge.token, "درخواست SSTP")
            if (!establishHttp()) return@launch

            bridge.controlMailbox.send(ControlMessage(Where.SSL, Result.PROCEEDED))
        }
    }

    private suspend fun establishSSL(): Boolean {
        val sslContext = SSLContext.getDefault()
        engine = sslContext.createSSLEngine(sslHostname, sslPort)
        engine.useClientMode = true

        if (selectedVersion != "DEFAULT") {
            engine.enabledProtocols = arrayOf(selectedVersion)
        }

        if (getBooleanPrefValue(OscPrefKey.SSL_DO_SELECT_SUITES, bridge.prefs)) {
            val sortedSuites = engine.supportedCipherSuites.filter {
                enabledSuites.contains(it)
            }

            engine.enabledCipherSuites = sortedSuites.toTypedArray()
        }

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N && getBooleanPrefValue(OscPrefKey.SSL_DO_USE_CUSTOM_SNI, bridge.prefs)) {
            engine.sslParameters = engine.sslParameters.also {
                it.serverNames = listOf(SNIHostName(getStringPrefValue(OscPrefKey.SSL_CUSTOM_SNI, bridge.prefs)))
            }
        }

        val socketHostname = if (doUseProxy) getStringPrefValue(OscPrefKey.PROXY_HOSTNAME, bridge.prefs) else sslHostname
        val socketPort = if (doUseProxy) getIntPrefValue(OscPrefKey.PROXY_PORT, bridge.prefs) else sslPort
        bridge.service.stage(bridge.token, "باز کردن TCP")
        val transport = createSocket()
        // Android may allocate the native descriptor lazily. Bind locally
        // before protecting it, then connect only after protection succeeds.
        transport.bind(java.net.InetSocketAddress(0))
        if (!bridge.service.protect(transport)) {
            bridge.service.notifyError(bridge.token, "اندروید نتوانست سوکت را از VPN جدا کند")
            throw java.io.IOException("VPN socket protection failed")
        }
        transport.soTimeout = 8000
        transport.connect(java.net.InetSocketAddress(socketHostname, socketPort), 5000)
        socketInputStream = transport.getInputStream()
        socketOutputStream = transport.getOutputStream()

        if (doUseProxy) {
            if (!establishProxy()) {
                return false
            }
        }

        inboundBuffer = ByteBuffer.allocate(engine.session.packetBufferSize).also { it.limit(0) }
        outboundBuffer = ByteBuffer.allocate(engine.session.packetBufferSize)

        try {
            bridge.service.stage(bridge.token, "مذاکره TLS")
            startSSLHandshake()
        } catch (e: CertificateException) {
            val cause = e.cause
            if (cause is CertPathValidatorException) {

                bridge.controlMailbox.send(ControlMessage(Where.CERT_PATH, Result.ERR_VERIFICATION_FAILED, generateCertPathLog(cause)))

                return false
            } else {
                throw e
            }
        }

        if (getBooleanPrefValue(OscPrefKey.SSL_DO_VERIFY, bridge.prefs)) {
            HttpsURLConnection.getDefaultHostnameVerifier().also {
                if (!it.verify(sslHostname, engine.session)) {
                    bridge.controlMailbox.send(ControlMessage(Where.SSL, Result.ERR_VERIFICATION_FAILED))
                    return false
                }
            }
        }

        return true
    }

    private suspend fun startSSLHandshake() {
        val tempBuffer = ByteBuffer.allocate(0)

        engine.beginHandshake()

        while (true) {
            yield()

            when (engine.handshakeStatus) {
                SSLEngineResult.HandshakeStatus.NEED_WRAP -> {
                    val result = send(tempBuffer)
                    if (result.handshakeStatus == SSLEngineResult.HandshakeStatus.FINISHED) {
                        break
                    }
                }

                SSLEngineResult.HandshakeStatus.NEED_UNWRAP -> {
                    val result = receive(tempBuffer)
                    if (result.handshakeStatus == SSLEngineResult.HandshakeStatus.FINISHED) {
                        break
                    }
                }

                else -> {
                    throw NotImplementedError(engine.handshakeStatus.name)
                }
            }
        }
    }

    private suspend fun establishProxy(): Boolean {
        val username = getStringPrefValue(OscPrefKey.PROXY_USERNAME, bridge.prefs)
        val password = getStringPrefValue(OscPrefKey.PROXY_PASSWORD, bridge.prefs)

        val request = mutableListOf(
            "CONNECT ${sslHostname}:${sslPort} HTTP/1.1",
            "Host: ${sslHostname}:${sslPort}",
            "SSTPVERSION: 1.0"
        ).also {
            if (username.isNotEmpty() || password.isNotEmpty()) {
                val encoded = Base64.encode(
                    "$username:$password".toByteArray(Charsets.US_ASCII),
                    Base64.NO_WRAP
                ).toString(Charsets.US_ASCII)

                it.add("Proxy-Authorization: Basic $encoded")
            }
        }.joinToString(separator = HTTP_DELIMITER, postfix = HTTP_SUFFIX).toByteArray(Charsets.US_ASCII)

        socketOutputStream.write(request)
        socketOutputStream.flush()

        var response = ""
        while (true) {
            response += socketInputStream.read().toChar()

            if (response.endsWith(HTTP_SUFFIX)) {
                break
            }
        }

        val responseHeader = response.split(HTTP_DELIMITER)[0]

        if (responseHeader.contains("403")) {
            bridge.controlMailbox.send(ControlMessage(Where.PROXY, Result.ERR_AUTHENTICATION_FAILED))
            return false
        }

        if (!responseHeader.contains("200")) {
            bridge.controlMailbox.send(ControlMessage(Where.PROXY, Result.ERR_UNEXPECTED_MESSAGE))
            return false
        }

        return true
    }

    private suspend fun establishHttp(): Boolean {
        val buffer = ByteBuffer.allocate(getApplicationBufferSize())

        val authority = if (sslPort == 443) sslHostname else "$sslHostname:$sslPort"
        val request = arrayOf(
            "SSTP_DUPLEX_POST /sra_{BA195980-CD49-458b-9E23-C84EE0ADCD75}/ HTTP/1.1",
            "Content-Length: 18446744073709551615",
            "Host: $authority",
            "SSTPCORRELATIONID: {${bridge.guid}}"
        ).joinToString(separator = HTTP_DELIMITER, postfix = HTTP_SUFFIX).toByteArray(Charsets.US_ASCII)

        buffer.put(request)
        buffer.flip()

        send(buffer)

        buffer.position(0)
        buffer.limit(0)

        var response = ""
        outer@ while (true) {
            receive(buffer)

            for (i in 0 until buffer.remaining()) {
                response += buffer.get().toIntAsUByte().toChar()

                if (response.endsWith(HTTP_SUFFIX)) {
                    break@outer
                }
            }
        }

        if (!response.split(HTTP_DELIMITER)[0].contains("200")) {
            bridge.controlMailbox.send(ControlMessage(Where.SSL, Result.ERR_UNEXPECTED_MESSAGE))
            return false
        }


        socket!!.soTimeout = 1_000
        return true
    }

    private fun generateParsingCertLog(filename: String?, exception: CertificateException): String {
        var log = "[FAILED FILE]\n$filename\n\n"

        log += "[STACK TRACE]\n${exception.stackTraceToString()}"

        return log
    }

    private fun generateCertPathLog(exception: CertPathValidatorException): String {
        var log = "[MESSAGE]\n${exception.message}\n\n"

        if (Build.VERSION.SDK_INT >= 24) {
            log += "[REASON]\n${exception.reason}\n\n"
        }

        log += "[CERT PATH]\n"
        exception.certPath.certificates.forEachIndexed { i, cert ->
            log += "-----CERT at $i-----\n"
            log += "$cert\n"
            log += "-----END CERT-----\n\n"
        }

        log += "[FAILED CERT INDEX]\n"
        log += if (exception.index == -1) "NOT DEFINED" else exception.index.toString()
        log += "\n\n"

        log += "[STACK TRACE]\n${exception.stackTraceToString()}"

        return log
    }

    internal fun getSession(): SSLSession {
        return engine.session
    }

    internal fun getServerCertificate(): ByteArray {
        return engine.session.peerCertificates[0].encoded
    }

    internal fun getApplicationBufferSize(): Int {
        return engine.session.applicationBufferSize
    }

    internal fun receive(buffer: ByteBuffer): SSLEngineResult {
        var startPayload: Int
        var result: SSLEngineResult

        while (true) {
            startPayload = buffer.position()
            buffer.position(buffer.limit())
            buffer.limit(buffer.capacity())

            result = engine.unwrap(inboundBuffer, buffer)

            when (result.status) {
                SSLEngineResult.Status.OK -> {
                    break
                }

                SSLEngineResult.Status.BUFFER_OVERFLOW -> {
                    buffer.limit(buffer.position())
                    buffer.position(startPayload)

                    buffer.slide()
                }

                SSLEngineResult.Status.BUFFER_UNDERFLOW -> {
                    buffer.limit(buffer.position())
                    buffer.position(startPayload)

                    inboundBuffer.slide()
                    val available = inboundBuffer.capacityAfterLimit
                    if (available <= 0) {
                        // A TLS record larger than the packet buffer cannot be
                        // completed. Stop with a typed I/O error instead of
                        // passing an invalid ByteBuffer range to the engine.
                        throw java.io.IOException("SSTP_TLS_PACKET_BUFFER_EXHAUSTED")
                    }
                    try {
                        val readSize = socketInputStream.read(
                            inboundBuffer.array(),
                            inboundBuffer.limit(),
                            available
                        )
                        // read() returns -1 on normal remote EOF, *not* a byte
                        // count. Decrementing limit by -1 could previously
                        // throw IllegalArgumentException on an empty buffer.
                        if (readSize < 0) {
                            throw java.io.EOFException("SSTP_TLS_REMOTE_EOF")
                        }
                        if (readSize > 0) {
                            inboundBuffer.limit(inboundBuffer.limit() + readSize)
                        }
                    } catch (_: SocketTimeoutException) {
                        // No complete TLS record yet; retry until the
                        // controller's normal liveness guard expires.
                    }
                }

                else -> {
                    throw NotImplementedError(result.status.name)
                }
            }
        }

        buffer.limit(buffer.position())
        buffer.position(startPayload)

        return result
    }

    internal suspend fun send(buffer: ByteBuffer): SSLEngineResult {
        mutex.withLock {
            var result: SSLEngineResult

            while (true) {
                outboundBuffer.clear()

                result = engine.wrap(buffer, outboundBuffer)
                if (result.status != SSLEngineResult.Status.OK) {
                    throw NotImplementedError(result.status.name)
                }

                socketOutputStream.write(
                    outboundBuffer.array(),
                    0,
                    outboundBuffer.position()
                )

                if (!buffer.hasRemaining()) {
                    socketOutputStream.flush()

                    break
                }
            }

            return result
        }
    }

    @Synchronized
    private fun createSocket(): Socket {
        check(!closed)
        return Socket().also { socket = it }
    }

    @Synchronized
    internal fun close() {
        closed = true
        jobInitialize?.cancel()
        socket?.close()
    }
}
