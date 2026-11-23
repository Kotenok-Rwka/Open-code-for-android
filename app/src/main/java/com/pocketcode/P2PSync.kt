package com.pocketcode

import android.content.Context
import android.content.pm.PackageManager
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import java.io.BufferedReader
import java.io.InputStreamReader
import java.net.Inet4Address
import java.net.InetAddress
import java.net.NetworkInterface
import java.net.ServerSocket
import java.util.concurrent.TimeUnit

/** Обмен снимками между двумя телефонами в одной Wi-Fi сети без ПК. */
object P2PSync {
    const val PORT = 8877
    @Volatile private var server: ServerSocket? = null
    @Volatile var lastPacket: String = ""
        private set

    fun localIp(): String = runCatching {
        NetworkInterface.getNetworkInterfaces().toList()
            .flatMap { it.inetAddresses.toList() }
            .firstOrNull { it is Inet4Address && !it.isLoopbackAddress && it.isSiteLocalAddress }
            ?.hostAddress.orEmpty()
    }.getOrDefault("")

    fun isRunning(): Boolean = server?.isClosed == false

    fun start() {
        if (isRunning()) return
        server = ServerSocket(PORT, 4, InetAddress.getByName("0.0.0.0")).apply { soTimeout = 500 }
        Thread {
            while (!Thread.currentThread().isInterrupted) {
                val s = try { server?.accept() } catch (_: java.net.SocketTimeoutException) { continue } catch (_: Exception) { break } ?: break
                s.use { sock ->
                    runCatching {
                        sock.soTimeout = 5000
                        val input = java.io.BufferedInputStream(sock.getInputStream())
                        fun line(): String {
                            val sb = StringBuilder()
                            while (true) {
                                val c = input.read()
                                if (c < 0 || c == '\n'.code) break
                                if (c != '\r'.code) sb.append(c.toChar())
                            }
                            return sb.toString()
                        }
                        line() // строка запроса
                        var len = 0
                        while (true) {
                            val h = line()
                            if (h.isEmpty()) break
                            if (h.startsWith("Content-Length:", ignoreCase = true)) len = h.substringAfter(':').trim().toIntOrNull() ?: 0
                        }
                        len = len.coerceIn(0, 2_000_000)
                        val buf = ByteArray(len)
                        var got = 0
                        while (got < len) {
                            val n = input.read(buf, got, len - got)
                            if (n < 0) break
                            got += n
                        }
                        if (got > 0) lastPacket = String(buf, 0, got, Charsets.UTF_8)
                        val body = "{\"ok\":true}".toByteArray()
                        sock.getOutputStream().write(
                            "HTTP/1.1 200 OK\r\nContent-Type: application/json\r\nContent-Length: ${body.size}\r\nConnection: close\r\n\r\n".toByteArray() + body,
                        )
                    }
                }
            }
        }.apply { isDaemon = true; name = "pocketcode-p2p" }.start()
    }

    fun stop() { runCatching { server?.close() }; server = null }

    suspend fun send(host: String, payload: String): Result<String> = withContext(Dispatchers.IO) {
        runCatching {
            require(host.matches(Regex("""[0-9.]+"""))) { "Некорректный IP" }
            val client = OkHttpClient.Builder().callTimeout(20, TimeUnit.SECONDS).build()
            val req = Request.Builder().url("http://$host:$PORT/sync")
                .post(payload.toByteArray().toRequestBody("application/json".toMediaType())).build()
            client.newCall(req).execute().use { if (it.isSuccessful) "Отправлено" else error("HTTP ${it.code}") }
        }
    }
}
