// Основано на Mika (https://github.com/wangzan101/Mika), Apache-2.0, © 2026 wangzan101. Изменено для PocketCode.
package com.pocketcode.phone.adb

import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.File
import java.math.BigInteger
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.security.KeyFactory
import java.security.KeyPair
import java.security.KeyPairGenerator
import java.security.Signature
import java.security.cert.CertificateFactory
import java.security.cert.X509Certificate
import java.security.interfaces.RSAPrivateCrtKey
import java.security.interfaces.RSAPrivateKey
import java.security.interfaces.RSAPublicKey
import java.security.spec.PKCS8EncodedKeySpec
import java.security.spec.RSAKeyGenParameterSpec
import java.security.spec.RSAPublicKeySpec
import java.util.Base64
import javax.crypto.Cipher
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

/**
 * Собственная adb-идентичность PocketCode: один ключ RSA-2048 и самоподписанный сертификат. При сопряжении устройству отдаётся [adbPublicKey],
 * затем при каждом TLS-подключении предъявляется [certificate] (устройство сверяет только открытый ключ из сертификата).
 */
class AdbIdentity(val keyPair: KeyPair, val name: String) {
    val publicKey: RSAPublicKey get() = keyPair.public as RSAPublicKey

    val certificate: X509Certificate by lazy { SelfSignedCertificate.create(keyPair) }

    /**
     * Строка для keystore устройства: base64(524 байта структуры открытого ключа) + пробел + имя.
     * В имени не должно быть пробельных символов: системные настройки режут строку по пробелам, и устройство с пробелом в имени потом не удалить из «сопряжённых».
     */
    val adbPublicKey: String by lazy {
        Base64.getEncoder().encodeToString(AdbPublicKey.encode(publicKey)) + " " + name.replace(Regex("\\s"), "_")
    }

    companion object {
        /** Открытая экспонента нового ключа: F4 (65537). Если при чтении ключа экспонента недоступна, подставляется она, и подстановка проверяется. */
        val PUBLIC_EXPONENT: BigInteger = RSAKeyGenParameterSpec.F4

        fun generate(name: String): AdbIdentity {
            val g = KeyPairGenerator.getInstance("RSA")
            g.initialize(RSAKeyGenParameterSpec(2048, PUBLIC_EXPONENT))
            return AdbIdentity(g.generateKeyPair(), name)
        }

        /**
         * Восстанавливает идентичность из сохранённого PKCS#8. Платформа не обязана отдавать закрытый ключ с CRT-параметрами:
         * достаточно любого закрытого ключа RSA, модуль берётся из него, открытая экспонента ищется через [publicExponent]. Если не прочитать, бросает [AdbKeyUnreadableException].
         */
        fun fromPkcs8(pkcs8: ByteArray, name: String): AdbIdentity {
            val kf = KeyFactory.getInstance("RSA")
            val priv = try {
                kf.generatePrivate(PKCS8EncodedKeySpec(pkcs8))
            } catch (e: Exception) {
                throw AdbKeyUnreadableException("stored key is not valid PKCS#8", e)
            }
            val rsa = priv as? RSAPrivateKey ?: throw AdbKeyUnreadableException("stored key is not RSA (${priv.javaClass.simpleName})")
            val pub = kf.generatePublic(RSAPublicKeySpec(rsa.modulus, publicExponent(rsa, pkcs8))) as RSAPublicKey
            return AdbIdentity(KeyPair(pub, rsa), name)
        }

        /**
         * Открытая экспонента закрытого ключа: если платформа дала CRT-форму, берём из неё; иначе читаем из структуры RSAPrivateKey в PKCS#8;
         * только если и там 0, подставляем [PUBLIC_EXPONENT], предварительно проверив её подписью; при несовпадении бросаем исключение и не идём с неверным открытым ключом.
         */
        internal fun publicExponent(key: RSAPrivateKey, pkcs8: ByteArray): BigInteger {
            (key as? RSAPrivateCrtKey)?.publicExponent?.takeIf { it.signum() > 0 }?.let { return it }
            Pkcs8Rsa.publicExponent(pkcs8)?.takeIf { it.signum() > 0 }?.let { return it }
            if (!signsWith(key, PUBLIC_EXPONENT)) throw AdbKeyUnreadableException("cannot recover the public exponent of the stored key")
            return PUBLIC_EXPONENT
        }

        private fun signsWith(key: RSAPrivateKey, e: BigInteger): Boolean = runCatching {
            val pub = KeyFactory.getInstance("RSA").generatePublic(RSAPublicKeySpec(key.modulus, e))
            val data = "pocketcode-adb-key-check".toByteArray()
            val sig = Signature.getInstance("SHA256withRSA").run { initSign(key); update(data); sign() }
            Signature.getInstance("SHA256withRSA").run { initVerify(pub); update(data); verify(sig) }
        }.getOrDefault(false)
    }
}

/** Сохранённый ключ есть, но не читается (не расшифровывается или не разбирается). Тихо заменять его нельзя: устройство признаёт только тот ключ, что был при сопряжении. */
class AdbKeyUnreadableException(message: String, cause: Throwable? = null) : Exception(message, cause)

/** Текущее состояние ключа (для диагностики). */
enum class AdbKeyState {
    /** В этом процессе ключ ещё не использовался. */
    NotLoaded,
    /** Прочитан сохранённый ключ. */
    Loaded,
    /** Ключа не было (или нечитаемый заменили при сопряжении), создан и сохранён новый. */
    Created,
    /** Ключ сохранён, но не читается: нужно новое сопряжение. */
    Unreadable,
}

/**
 * В процессе существует одна adb-идентичность: сопряжение и подключение всегда получают один и тот же ключ, а не каждый свой после какого-то сбоя чтения.
 * Только сопряжение ([forPairing]) при нечитаемом сохранённом ключе заменяет его новым (оно всё равно отдаёт новый ключ устройству);
 * подключение ([forConnect]) при нечитаемом ключе бросает [AdbKeyUnreadableException], и интерфейс просит пользователя сопрячься заново.
 */
class AdbIdentitySource(
    private val store: AdbKeyStore,
    private val name: () -> String,
    private val log: (String, Throwable?) -> Unit = { _, _ -> },
) {
    private var cached: AdbIdentity? = null

    @Volatile var state: AdbKeyState = AdbKeyState.NotLoaded
        private set

    /** Причина последнего сбоя чтения (для диагностики); null, если читается. */
    @Volatile var problem: String? = null
        private set

    /** Для подключения: сохранённый ключ; если его нет — создать и сохранить; если есть, но не читается — бросить исключение, не перезаписывая. */
    @Synchronized
    fun forConnect(): AdbIdentity = cached ?: load()

    /** Для сопряжения: тот же ключ, что и в [forConnect]; только при нечитаемом сохранённом ключе заменяет его новым (его следом отдаст устройству само сопряжение). */
    @Synchronized
    fun forPairing(): AdbIdentity = cached ?: try {
        load()
    } catch (e: AdbKeyUnreadableException) {
        log("replacing the unreadable adb key for a new pairing", e)
        store.clear()
        create()
    }

    /** Снятие сопряжения: ключ и кэш удаляются вместе, следующее сопряжение пойдёт с новым ключом. */
    @Synchronized
    fun forget() {
        cached = null
        store.clear()
        state = AdbKeyState.NotLoaded
        problem = null
    }

    private fun load(): AdbIdentity {
        val bytes = try {
            store.read()
        } catch (e: AdbKeyUnreadableException) {
            throw unreadable(e)
        } ?: return create()
        val id = try {
            AdbIdentity.fromPkcs8(bytes, name())
        } catch (e: AdbKeyUnreadableException) {
            throw unreadable(e)
        }
        state = AdbKeyState.Loaded
        problem = null
        return id.also { cached = it }
    }

    private fun create(): AdbIdentity {
        val id = AdbIdentity.generate(name())
        store.write(id.keyPair.private.encoded)
        state = AdbKeyState.Created
        problem = null
        return id.also { cached = it }
    }

    private fun unreadable(e: AdbKeyUnreadableException): AdbKeyUnreadableException {
        state = AdbKeyState.Unreadable
        problem = generateSequence<Throwable>(e) { it.cause }.joinToString(" <- ") { it.message ?: it.javaClass.simpleName }.take(200)
        log("stored adb key is unreadable; pair again to replace it", e)
        return e
    }
}

/**
 * Хранение ключа (PKCS#8). В приложении он шифруется ключом из Android Keystore.
 * [read] возвращает null, если ничего не сохраняли; если сохранён, но не читается — бросает [AdbKeyUnreadableException] (эти случаи нельзя смешивать, второй нельзя считать «ничего нет»).
 */
interface AdbKeyStore {
    fun read(): ByteArray?
    fun write(pkcs8: ByteArray)
    fun clear()
}

/**
 * Ключ, зашифрованный в файле: содержимое = 1 байт длины IV + IV + шифртекст AES-GCM.
 * IV создаёт сам Cipher (Android Keystore не позволяет вызывающему задавать IV).
 */
class WrappedKeyFile(private val file: File, private val wrappingKey: () -> SecretKey) : AdbKeyStore {
    override fun read(): ByteArray? {
        if (!file.isFile) return null
        return try {
            val b = file.readBytes()
            require(b.isNotEmpty()) { "key file is empty" }
            val ivLen = b[0].toInt() and 0xff
            require(b.size > 1 + ivLen) { "key file is truncated" }
            val c = Cipher.getInstance("AES/GCM/NoPadding")
            c.init(Cipher.DECRYPT_MODE, wrappingKey(), GCMParameterSpec(128, b.copyOfRange(1, 1 + ivLen)))
            c.doFinal(b, 1 + ivLen, b.size - 1 - ivLen)
        } catch (e: Exception) {
            throw AdbKeyUnreadableException("cannot unwrap ${file.name}", e)
        }
    }

    override fun write(pkcs8: ByteArray) {
        val c = Cipher.getInstance("AES/GCM/NoPadding")
        c.init(Cipher.ENCRYPT_MODE, wrappingKey())
        val iv = c.iv
        val out = byteArrayOf(iv.size.toByte()) + iv + c.doFinal(pkcs8)
        file.parentFile?.mkdirs()
        val tmp = File(file.path + ".tmp")
        tmp.writeBytes(out)
        check(tmp.renameTo(file)) { "cannot write ${file.name}" }
    }

    override fun clear() {
        file.delete()
    }
}

/**
 * Читает открытую экспоненту RSA из PKCS#8: PrivateKeyInfo ::= SEQUENCE { version, algorithm, privateKey OCTET STRING },
 * внутри privateKey лежит RSAPrivateKey ::= SEQUENCE { version, modulus, publicExponent, ... }. При неверной структуре возвращает null.
 */
internal object Pkcs8Rsa {
    fun publicExponent(pkcs8: ByteArray): BigInteger? = runCatching {
        val info = Der(pkcs8).read(0x30)
        val inner = Der(info).apply { read(0x02); read(0x30) }.read(0x04)
        val rsa = Der(Der(inner).read(0x30))
        rsa.read(0x02)
        rsa.read(0x02)
        BigInteger(rsa.read(0x02))
    }.getOrNull()

    /** Ровно столько DER, чтобы прочитать эти уровни: последовательно берёт содержимое элементов с указанным тегом. */
    private class Der(private val b: ByteArray) {
        private var pos = 0

        fun read(tag: Int): ByteArray {
            require((b[pos++].toInt() and 0xff) == tag) { "unexpected tag" }
            var len = b[pos++].toInt() and 0xff
            if (len >= 0x80) {
                val n = len and 0x7f
                require(n in 1..3) { "unsupported length" }
                len = 0
                repeat(n) { len = (len shl 8) or (b[pos++].toInt() and 0xff) }
            }
            require(pos + len <= b.size) { "truncated" }
            return b.copyOfRange(pos, pos + len).also { pos += len }
        }
    }
}

/**
 * Двоичный формат открытого ключа RSA в adb (всё little-endian, всего 524 байта):
 * число слов модуля (64), n0inv = −1/n mod 2^32, модуль n, rr = (2^2048)^2 mod n, открытая экспонента.
 */
object AdbPublicKey {
    private const val WORDS = 64
    const val SIZE = 4 + 4 + WORDS * 4 * 2 + 4

    fun encode(key: RSAPublicKey): ByteArray {
        val n = key.modulus
        require(n.bitLength() == 2048) { "adb keys are RSA-2048" }
        val r32 = BigInteger.ONE.shiftLeft(32)
        val n0inv = r32 - n.mod(r32).modInverse(r32)
        val rr = BigInteger.ONE.shiftLeft(2048 * 2).mod(n)
        val buf = ByteBuffer.allocate(SIZE).order(ByteOrder.LITTLE_ENDIAN)
        buf.putInt(WORDS)
        buf.putInt(n0inv.toInt())
        buf.put(EdPoint.toLe(n, 256))
        buf.put(EdPoint.toLe(rr, 256))
        buf.putInt(key.publicExponent.toInt())
        return buf.array()
    }
}

/** Минимальный самоподписанный сертификат X.509 v3 (без расширений): CN=PocketCode, с 1970-01-01 по 2099-12-31, SHA256withRSA. */
internal object SelfSignedCertificate {
    private val SHA256_WITH_RSA = byteArrayOf(0x2a, 0x86.toByte(), 0x48, 0x86.toByte(), 0xf7.toByte(), 0x0d, 0x01, 0x01, 0x0b)
    private val COMMON_NAME = byteArrayOf(0x55, 0x04, 0x03)

    fun create(keyPair: KeyPair): X509Certificate {
        val algId = seq(tlv(0x06, SHA256_WITH_RSA), tlv(0x05, ByteArray(0)))
        val name = seq(tlv(0x31, seq(tlv(0x06, COMMON_NAME), tlv(0x0c, "PocketCode".toByteArray()))))
        val validity = seq(tlv(0x17, "700101000000Z".toByteArray()), tlv(0x18, "20991231235959Z".toByteArray()))
        val tbs = seq(
            tlv(0xa0, tlv(0x02, byteArrayOf(2))),
            tlv(0x02, byteArrayOf(1)),
            algId, name, validity, name,
            keyPair.public.encoded,
        )
        val sig = Signature.getInstance("SHA256withRSA").run {
            initSign(keyPair.private)
            update(tbs)
            sign()
        }
        val der = seq(tbs, algId, tlv(0x03, byteArrayOf(0) + sig))
        return CertificateFactory.getInstance("X.509").generateCertificate(ByteArrayInputStream(der)) as X509Certificate
    }

    private fun seq(vararg parts: ByteArray) = tlv(0x30, parts.fold(ByteArray(0)) { a, b -> a + b })

    internal fun tlv(tag: Int, content: ByteArray): ByteArray {
        val out = ByteArrayOutputStream()
        out.write(tag)
        val n = content.size
        when {
            n < 0x80 -> out.write(n)
            n < 0x100 -> { out.write(0x81); out.write(n) }
            n < 0x10000 -> { out.write(0x82); out.write(n ushr 8); out.write(n and 0xff) }
            else -> { out.write(0x83); out.write(n ushr 16); out.write((n ushr 8) and 0xff); out.write(n and 0xff) }
        }
        out.write(content)
        return out.toByteArray()
    }
}
