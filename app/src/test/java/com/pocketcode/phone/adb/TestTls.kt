// Основано на Mika (https://github.com/wangzan101/Mika), Apache-2.0, © 2026 wangzan101. Изменено для PocketCode.
package com.pocketcode.phone.adb

import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.ServerSocket
import java.security.MessageDigest
import kotlin.concurrent.thread

/**
 * TLS в JVM-тестах: стандартный JSSE (TLS 1.3), без Conscrypt. В JDK нет экспорта ключевого материала, поэтому [exporter] — подставная замена:
 * хеш от метки и обоих сертификатов (клиентского и серверного), одинаковый на обеих сторонах соединения и различающийся по метке и по паре сертификатов.
 * Настоящий экспорт TLS (RFC 8446 §7.5) в этих тестах не проверяется: он есть только на Android.
 */
object TestTls {
    val exporter = KeyingMaterialExporter { s, label, len ->
        val local = s.session.localCertificates[0].encoded
        val peer = s.session.peerCertificates[0].encoded
        val (clientCert, serverCert) = if (s.useClientMode) local to peer else peer to local
        val md = MessageDigest.getInstance("SHA-512")
        md.update(label.toByteArray(Charsets.UTF_8))
        md.update(clientCert)
        md.update(serverCert)
        md.digest().copyOf(len)
    }

    /** Ключи, общие для тестов (генерация RSA-2048 медленная, достаточно одного раза). */
    val client: AdbIdentity by lazy { AdbIdentity.generate("pocketcode@test") }
    val device: AdbIdentity by lazy { AdbIdentity.generate("device") }

    fun tls(identity: AdbIdentity) = AdbTls(identity, exporter)
}

/** Поддельный сервер сопряжения в процессе: проходит все шаги, как устройство. [behavior] создаёт разные нештатные ситуации. */
class FakePairingServer(
    private val code: String,
    private val behavior: Behavior = Behavior.NORMAL,
    private val guid: String = "adb-FAKE0001-abc123",
) : AutoCloseable {
    enum class Behavior { NORMAL, BAD_HEADER_VERSION, WRONG_TYPE, CLOSE_AFTER_SPAKE }

    private val server = ServerSocket(0, 1, InetAddress.getByAddress(byteArrayOf(127, 0, 0, 1)))
    val port: Int get() = server.localPort
    @Volatile var receivedKey: String? = null
    @Volatile var clientCertKey: ByteArray? = null
    @Volatile var error: Throwable? = null
    private val tls = TestTls.tls(TestTls.device)

    private val worker = thread(isDaemon = true, name = "fake-pairing") {
        try {
            server.accept().use { plain -> serve(plain) }
        } catch (t: Throwable) {
            error = t
        }
    }

    private fun serve(plain: java.net.Socket) {
        val s = tls.wrap(plain, InetSocketAddress(plain.localAddress, port), client = false)
        s.use {
            clientCertKey = (s.session.peerCertificates[0] as java.security.cert.X509Certificate).publicKey.encoded
            val exported = tls.exportKeyingMaterial(s, AdbPairing.EXPORT_LABEL, AdbPairing.EXPORT_LENGTH)
            val auth = PairingAuth.server(code.toByteArray() + exported)
            val input = java.io.DataInputStream(s.inputStream)
            val out = s.outputStream
            val clientMsg = AdbPairing.readPacket(input, AdbPairing.TYPE_SPAKE2)!!
            when (behavior) {
                Behavior.BAD_HEADER_VERSION -> {
                    out.write(byteArrayOf(2, 0, 0, 0, 0, 32) + ByteArray(32)); out.flush(); return
                }
                Behavior.WRONG_TYPE -> {
                    AdbPairing.writePacket(out, AdbPairing.TYPE_PEER_INFO, auth.message); return
                }
                else -> AdbPairing.writePacket(out, AdbPairing.TYPE_SPAKE2, auth.message)
            }
            auth.initCipher(clientMsg)
            val sealed = AdbPairing.readPacket(input, AdbPairing.TYPE_PEER_INFO)!!
            if (behavior == Behavior.CLOSE_AFTER_SPAKE) return
            val info = try {
                auth.decrypt(sealed)
            } catch (e: javax.crypto.AEADBadTagException) {
                return // как устройство: если не расшифровалось, обрыв соединения
            }
            check(info.size == AdbPairing.PEER_INFO_SIZE && info[0] == AdbPairing.PEER_RSA_PUB_KEY)
            receivedKey = String(info, 1, info.indexOfFirst(1) - 1, Charsets.UTF_8)
            val mine = ByteArray(AdbPairing.PEER_INFO_SIZE)
            mine[0] = AdbPairing.PEER_DEVICE_GUID
            guid.toByteArray().copyInto(mine, 1)
            AdbPairing.writePacket(out, AdbPairing.TYPE_PEER_INFO, auth.encrypt(mine))
            s.inputStream.read() // ждём, пока клиент закроет соединение
        }
    }

    private fun ByteArray.indexOfFirst(from: Int): Int {
        for (i in from until size) if (this[i] == 0.toByte()) return i
        return size
    }

    fun join() = worker.join(10_000)

    override fun close() {
        server.close()
    }
}
