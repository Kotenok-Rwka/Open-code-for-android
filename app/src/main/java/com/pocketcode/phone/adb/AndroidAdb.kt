// Основано на Mika (https://github.com/wangzan101/Mika), Apache-2.0, © 2026 wangzan101. Изменено для PocketCode.
package com.pocketcode.phone.adb

import android.content.Context
import android.os.Build
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Log
import java.io.File
import java.lang.reflect.InvocationTargetException
import java.lang.reflect.Method
import java.security.KeyStore
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.net.ssl.SSLException
import javax.net.ssl.SSLSocket

/** Платформа не отдаёт экспорт ключевого материала TLS (нужен для сопряжения) на этой версии Android. */
class KeyingMaterialUnavailableException(message: String, cause: Throwable? = null) : SSLException(message, cause)

/**
 * adb-идентичность и TLS на Android: ключ шифруется AES-ключом из Keystore и лежит в приватном каталоге без резервного копирования
 * ([Context.getNoBackupFilesDir]); экспорт ключевого материала даёт платформенный TLS.
 */
internal object AndroidAdb {
    private const val KEYSTORE = "AndroidKeyStore"
    private const val WRAP_ALIAS = "pocketcode_adb_key_wrap"
    private const val TAG = "PocketAdbKey"
    private const val KEY_FILE = "adb/adbkey.bin"

    /**
     * Публичный `android.net.ssl.SSLSockets.exportKeyingMaterial` (Android 14, API 34). В Mika он вызывался напрямую, здесь он вызывается рефлексией:
     * при compileSdk 34 нет гарантии, что метод виден компилятору, а на более старых системах прямой вызов дал бы NoSuchMethodError.
     */
    private val publicExport: Method? by lazy {
        runCatching {
            Class.forName("android.net.ssl.SSLSockets").getMethod(
                "exportKeyingMaterial", SSLSocket::class.java, String::class.java, ByteArray::class.java, Integer.TYPE,
            )
        }.getOrNull()
    }

    /** Скрытый статический `com.android.org.conscrypt.Conscrypt.exportKeyingMaterial` (запасной путь для Android 11–13). */
    private val conscryptExport: Method? by lazy {
        runCatching {
            Class.forName("com.android.org.conscrypt.Conscrypt").getMethod(
                "exportKeyingMaterial", SSLSocket::class.java, String::class.java, ByteArray::class.java, Integer.TYPE,
            )
        }.getOrNull()
    }

    /** Скрытый метод сокета Conscrypt; ищется в публичном абстрактном суперклассе, чтобы вызов не упёрся в закрытый класс реализации. */
    private val conscryptSocketExport: Method? by lazy {
        runCatching {
            Class.forName("com.android.org.conscrypt.AbstractConscryptSocket").getMethod(
                "exportKeyingMaterial", String::class.java, ByteArray::class.java, Integer.TYPE,
            )
        }.getOrNull()
    }

    // На стороне устройства экспорт идёт без контекста; в TLS 1.3 пустой контекст и отсутствие контекста дают одинаковый результат (RFC 8446 §7.5)
    val exporter = KeyingMaterialExporter { socket, label, length -> exportKeyingMaterial(socket, label, length) }

    internal fun exportKeyingMaterial(socket: SSLSocket, label: String, length: Int): ByteArray {
        val empty = ByteArray(0)
        var last: Throwable? = null
        if (Build.VERSION.SDK_INT >= 34) {
            publicExport?.let { m ->
                try {
                    (m.invoke(null, socket, label, empty, length) as? ByteArray)?.let { return it }
                } catch (e: InvocationTargetException) {
                    if (e.cause is SSLException) throw e.cause as SSLException
                    last = e.cause ?: e
                } catch (e: ReflectiveOperationException) {
                    last = e
                }
            }
        }
        // Android 11–13: публичного метода нет, пробуем внутренний Conscrypt платформы (может быть закрыт ограничением скрытых API)
        conscryptExport?.let { m ->
            try {
                (m.invoke(null, socket, label, empty, length) as? ByteArray)?.let { return it }
            } catch (e: InvocationTargetException) {
                if (e.cause is SSLException) throw e.cause as SSLException
                last = e.cause ?: e
            } catch (e: ReflectiveOperationException) {
                last = e
            } catch (e: RuntimeException) {
                last = e
            }
        }
        conscryptSocketExport?.let { m ->
            try {
                (m.invoke(socket, label, empty, length) as? ByteArray)?.let { return it }
            } catch (e: InvocationTargetException) {
                if (e.cause is SSLException) throw e.cause as SSLException
                last = e.cause ?: e
            } catch (e: ReflectiveOperationException) {
                last = e
            } catch (e: RuntimeException) {
                last = e
            }
        }
        throw KeyingMaterialUnavailableException("TLS keying material export is not available on Android ${Build.VERSION.SDK_INT}", last)
    }

    @Volatile private var source: AdbIdentitySource? = null

    /** Единственный в процессе источник идентичности: сопряжение и подключение берут отсюда один и тот же ключ. */
    fun identities(context: Context): AdbIdentitySource = source ?: synchronized(this) {
        source ?: AdbIdentitySource(keyStore(context), { "pocketcode@${Build.MODEL}" }) { msg, e -> Log.w(TAG, msg, e) }.also { source = it }
    }

    /** Идентичность для подключения; если сохранённый ключ не читается, бросает [AdbKeyUnreadableException]. */
    fun identity(context: Context): AdbIdentity = identities(context).forConnect()

    /** Идентичность для сопряжения: нечитаемый сохранённый ключ заменяется новым (см. [AdbIdentitySource.forPairing]). */
    fun pairingIdentity(context: Context): AdbIdentity = identities(context).forPairing()

    fun tls(identity: AdbIdentity) = AdbTls(identity, exporter)

    /** Файл с ключом уже есть (то есть сопряжение хотя бы раз проходило на этом устройстве). Сам ключ не читается и не создаётся. */
    fun hasKey(context: Context): Boolean = keyFile(context).isFile

    /** Удаляет ключ и кэш в процессе («Забыть сопряжение»); запись об авторизации на стороне устройства пользователь отзывает в системных настройках. */
    fun forget(context: Context) = identities(context).forget()

    private fun keyFile(context: Context) = File(context.applicationContext.noBackupFilesDir, KEY_FILE)

    /** Файл ключа плюс обёртывающий его ключ в Keystore: при очистке удаляются оба. */
    private fun keyStore(context: Context): AdbKeyStore {
        val file = WrappedKeyFile(keyFile(context)) { wrappingKey() }
        return object : AdbKeyStore by file {
            override fun clear() {
                file.clear()
                runCatching { KeyStore.getInstance(KEYSTORE).apply { load(null) }.deleteEntry(WRAP_ALIAS) }
            }
        }
    }

    private fun wrappingKey(): SecretKey {
        val ks = KeyStore.getInstance(KEYSTORE).apply { load(null) }
        (ks.getKey(WRAP_ALIAS, null) as? SecretKey)?.let { return it }
        val gen = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, KEYSTORE)
        gen.init(
            KeyGenParameterSpec.Builder(WRAP_ALIAS, KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT)
                .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                .setKeySize(256)
                .build(),
        )
        return gen.generateKey()
    }
}
