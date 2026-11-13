// Основано на Mika (https://github.com/wangzan101/Mika), Apache-2.0, © 2026 wangzan101. Изменено для PocketCode.
package com.pocketcode.phone.adb

import java.io.DataInputStream
import java.io.EOFException
import java.io.IOException
import java.io.OutputStream
import java.nio.ByteBuffer
import javax.crypto.AEADBadTagException
import javax.net.ssl.SSLException
import javax.net.ssl.SSLSocket

/** Типы служб, которые adbd объявляет через mDNS. */
object AdbServiceTypes {
    /** Объявляется, только пока открыт диалог «Сопряжение по коду». */
    const val PAIRING = "_adb-tls-pairing._tcp"
    /** Объявляется при включённой беспроводной отладке; порт меняется при каждом включении/выключении. */
    const val CONNECT = "_adb-tls-connect._tcp"
}

/** Ищет в локальной сети порт службы, объявленной adbd; если не нашёл (таймаут), возвращает пустой результат. */
/**
 * Ищет порты служб, которые объявляет adbd этого телефона. Одна служба может найтись несколько раз (после выключения и включения беспроводной отладки
 * старая запись может остаться в mDNS, а новая получает имя с номером); возвращаются все, найденные раньше идут первыми, перебирает вызывающий.
 */
fun interface AdbServiceFinder {
    suspend fun findPorts(serviceType: String, timeoutMs: Long): List<Int>
}

class AdbPairingException(val reason: Reason, message: String, cause: Throwable? = null) : IOException(message, cause) {
    enum class Reason {
        /** Цель — не адрес самого телефона: отказ до создания Socket, повторять как сетевую ошибку нельзя. */
        NOT_LOCAL,
        CONNECT_FAILED, TLS_FAILED, WRONG_CODE, PROTOCOL,
    }
}

/** Идентичность, которую устройство сообщает при успешном сопряжении (обычно GUID устройства с type=1). */
class AdbPeerInfo(val type: Int, val data: String)

/**
 * Сопряжение по коду для беспроводной отладки (клиентская сторона). Ход:
 * TLS 1.3 → экспорт 64 байт ключевого материала ("adb-label\0") → пароль = код сопряжения + экспортированный материал → обмен сообщениями SPAKE2 →
 * по согласованному AES-GCM обмен PeerInfo по 8192 байта (мы шлём открытый ключ adb, устройство отвечает GUID).
 * Перед каждым пакетом 6-байтовый заголовок: версия (1), тип (0=SPAKE2, 1=PEER_INFO), длина данных (big-endian uint32).
 */
class AdbPairing(private val tls: AdbTls) {
    /** Сопрягается с этим же телефоном; проверенный адрес и есть реальный адрес соединения, исходный host повторно не разбирается. */
    fun pair(host: String, port: Int, code: String, timeoutMs: Int = 15_000): AdbPeerInfo {
        val target = AdbHosts.ownSocketAddress(host, port) ?: throw AdbPairingException(
            AdbPairingException.Reason.NOT_LOCAL,
            "refusing to pair with $host: adb is only reachable on this device",
        )
        val socket = try {
            tls.connect(target, timeoutMs)
        } catch (e: SSLException) {
            throw AdbPairingException(AdbPairingException.Reason.TLS_FAILED, "TLS handshake failed", e)
        } catch (e: IOException) {
            throw AdbPairingException(AdbPairingException.Reason.CONNECT_FAILED, "cannot connect to $host:$port", e)
        }
        socket.use { return exchange(it, code) }
    }

    private fun exchange(socket: SSLSocket, code: String): AdbPeerInfo {
        val exported = tls.exportKeyingMaterial(socket, EXPORT_LABEL, EXPORT_LENGTH)
        val auth = PairingAuth.client(code.toByteArray(Charsets.UTF_8) + exported)
        val input = DataInputStream(socket.inputStream)
        val output = socket.outputStream

        writePacket(output, TYPE_SPAKE2, auth.message)
        val theirMsg = readPacket(input, TYPE_SPAKE2) ?: throw protocol("connection closed before key exchange")
        try {
            auth.initCipher(theirMsg)
        } catch (e: IllegalArgumentException) {
            throw protocol("bad key exchange message: ${e.message}")
        }

        val info = ByteArray(PEER_INFO_SIZE)
        info[0] = PEER_RSA_PUB_KEY
        val key = tls.identity.adbPublicKey.toByteArray(Charsets.UTF_8)
        check(key.size < PEER_INFO_SIZE - 1)
        System.arraycopy(key, 0, info, 1, key.size)
        writePacket(output, TYPE_PEER_INFO, auth.encrypt(info))

        // При неверном коде устройство не может расшифровать наш PeerInfo и просто обрывает соединение
        val sealed = try {
            readPacket(input, TYPE_PEER_INFO)
        } catch (e: IOException) {
            if (e is AdbPairingException) throw e
            null
        } ?: throw AdbPairingException(AdbPairingException.Reason.WRONG_CODE, "device closed the connection (wrong pairing code?)")
        val plain = try {
            auth.decrypt(sealed)
        } catch (e: AEADBadTagException) {
            throw AdbPairingException(AdbPairingException.Reason.WRONG_CODE, "cannot decrypt device info (wrong pairing code?)", e)
        }
        if (plain.size != PEER_INFO_SIZE) throw protocol("peer info has ${plain.size} bytes")
        var end = 1
        while (end < plain.size && plain[end] != 0.toByte()) end++
        return AdbPeerInfo(plain[0].toInt() and 0xff, String(plain, 1, end - 1, Charsets.UTF_8))
    }

    private fun protocol(msg: String) = AdbPairingException(AdbPairingException.Reason.PROTOCOL, msg)

    companion object {
        const val EXPORT_LABEL = "adb-label\u0000"
        const val EXPORT_LENGTH = 64
        const val PEER_INFO_SIZE = 8192
        const val MAX_PAYLOAD = PEER_INFO_SIZE * 2
        const val VERSION: Byte = 1
        const val TYPE_SPAKE2: Byte = 0
        const val TYPE_PEER_INFO: Byte = 1
        const val PEER_RSA_PUB_KEY: Byte = 0
        const val PEER_DEVICE_GUID: Byte = 1

        fun writePacket(out: OutputStream, type: Byte, payload: ByteArray) {
            val header = ByteBuffer.allocate(6).put(VERSION).put(type).putInt(payload.size).array()
            out.write(header + payload)
            out.flush()
        }

        /** Читает пакет и проверяет тип; если другая сторона отключилась до заголовка, возвращает null. */
        fun readPacket(input: DataInputStream, expectedType: Byte): ByteArray? {
            val header = ByteArray(6)
            try {
                input.readFully(header)
            } catch (e: EOFException) {
                return null
            }
            val bb = ByteBuffer.wrap(header)
            val version = bb.get()
            val type = bb.get()
            val len = bb.getInt()
            if (version != VERSION) throw AdbPairingException(AdbPairingException.Reason.PROTOCOL, "unsupported pairing version $version")
            if (type != expectedType) throw AdbPairingException(AdbPairingException.Reason.PROTOCOL, "unexpected packet type $type")
            if (len <= 0 || len > MAX_PAYLOAD) throw AdbPairingException(AdbPairingException.Reason.PROTOCOL, "bad payload size $len")
            return ByteArray(len).also { input.readFully(it) }
        }
    }
}
