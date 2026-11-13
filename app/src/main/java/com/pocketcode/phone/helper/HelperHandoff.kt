// Основано на Mika (https://github.com/wangzan101/Mika), Apache-2.0, © 2026 wangzan101. Изменено для PocketCode.
package com.pocketcode.phone.helper

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.security.MessageDigest
import java.security.SecureRandom

/**
 * Общие договорённости помощника и приложения: имя метода и ключи Bundle у провайдера, имя процесса помощника,
 * команды запуска и проверки, которые приложение делает перед тем, как принять Binder помощника.
 */
object HelperHandoff {
    const val SHELL_UID = 2000
    private const val PER_USER_RANGE = 100_000
    private const val FIRST_APP_UID = 10_000
    private const val LAST_APP_UID = 19_999
    const val METHOD_ATTACH = "attach"
    const val HELPER_CLASS = "com.pocketcode.phone.helper.HelperMain"

    // помощник → приложение
    const val KEY_NONCE = "nonce"
    const val KEY_BINDER = "binder"
    const val KEY_UID = "uid"
    const val KEY_PID = "pid"
    const val KEY_APK = "apk"

    // приложение → помощник
    const val KEY_ACCEPTED = "accepted"
    const val KEY_NEXT = "next"
    const val KEY_REASON = "reason"
    /** Binder-«метка» приложения: помощник следит за её смертью, чтобы понять, что приложение убито. */
    const val KEY_TOKEN = "token"

    fun authority(packageName: String) = "$packageName.helper"

    /** Имя процесса помощника (app_process --nice-name); по нему же узнаём старые копии помощника. */
    fun processName(packageName: String) = "$packageName:helper"

    /**
     * Команда для службы exec: у adbd (adbd запускает её через `sh -c`). Когда поток закрывается, adbd убивает всю
     * группу процессов sh, поэтому помощник стартует через setsid в новой сессии; sh ждёт `read`, пока приложение
     * не получит Binder и не закроет поток, иначе sh мог бы завершиться раньше, чем setsid сработает.
     *
     * Одноразовый пароль не попадает в командную строку (её могут прочитать другие процессы shell через /proc):
     * приложение пишет в поток строку [stdinLine], sh читает её в переменную и передаёт помощнику через конвейер.
     * [certPin] — отпечаток сертификата подписи приложения; это не секрет, поэтому он в аргументах.
     */
    fun command(apkPath: String, packageName: String, appUid: Int, certPin: String): String =
        "read n; (echo \"\$n\" | CLASSPATH=${quote(apkPath)} setsid app_process /system/bin " +
            "--nice-name=${quote(processName(packageName))} $HELPER_CLASS ${quote(packageName)} $appUid ${quote(certPin)} " +
            ">/dev/null 2>&1 &); read _"

    fun stdinLine(nonce: String): ByteArray = "$nonce\n".toByteArray(Charsets.UTF_8)

    /**
     * Команда для `su 2000 -c`: сначала печатает `id -u`; приложение пишет пароль только если прочитало 2000
     * (su согласился и сменил uid на shell). Дальше всё как в [command].
     */
    fun rootCommand(apkPath: String, packageName: String, appUid: Int, certPin: String): String =
        "id -u; " + command(apkPath, packageName, appUid, certPin)

    /** Отпечаток SHA-256 сертификата подписи (DER); несколько подписантов сортируются и склеиваются запятой. Нет сертификатов — null. */
    fun certPin(certs: List<ByteArray>?): String? {
        if (certs.isNullOrEmpty()) return null
        return certs.map { c -> MessageDigest.getInstance("SHA-256").digest(c).joinToString("") { "%02x".format(it) } }
            .sorted().joinToString(",")
    }

    internal fun quote(s: String) = "'" + s.replace("'", "'\\''") + "'"

    enum class CallerClass { Root, Shell, ShellOtherUser, OrdinaryApp, Other }

    /** Для диагностики оставляем только класс вызывающего, без конкретного UID. */
    fun callerClass(uid: Int): CallerClass {
        val appId = uid % PER_USER_RANGE
        return when {
            uid == 0 -> CallerClass.Root
            uid == SHELL_UID -> CallerClass.Shell
            appId == SHELL_UID -> CallerClass.ShellOtherUser
            appId in FIRST_APP_UID..LAST_APP_UID -> CallerClass.OrdinaryApp
            else -> CallerClass.Other
        }
    }

    enum class Reject {
        /** Вызывающий не shell (uid 2000). */
        NotShell,
        /** uid/pid, которые назвал помощник, не совпали с тем, что видит Binder. */
        IdMismatch,
        /** Помощник запущен из другого APK (приложение обновили): пусть выйдет, запустим новый. */
        StaleApk,
        NoBinder,
        BadNonce,
        /** Binder находится не в том процессе, который пришёл передавать. */
        BinderElsewhere,
    }

    /** Проверки, не требующие Binder; пройдены — null. Пароль проверяется отдельно, в [HandoffNonces]. */
    fun check(
        callingUid: Int, callingPid: Int, reportedUid: Int, reportedPid: Int,
        helperApk: String?, appApk: String, hasBinder: Boolean,
    ): Reject? = when {
        callingUid != SHELL_UID -> Reject.NotShell
        reportedUid != callingUid || reportedPid != callingPid -> Reject.IdMismatch
        helperApk != appApk -> Reject.StaleApk
        !hasBinder -> Reject.NoBinder
        else -> null
    }
}

/** Причина отказа при последней попытке передачи; после новой попытки, успеха или сброса не показывается. */
class HandoffDiagnostics {
    @Volatile var lastRejection: String? = null
        private set

    @Synchronized fun launchStarted() { lastRejection = null }

    @Synchronized fun clear() { lastRejection = null }

    @Synchronized fun reject(why: HelperHandoff.Reject, callingUid: Int) {
        lastRejection = if (why == HelperHandoff.Reject.NotShell) {
            "${why.name}(caller=${HelperHandoff.callerClass(callingUid).name})"
        } else why.name
    }
}

/**
 * Сохранённые пароли: [resume] — для следующей передачи уже работающего помощника, [launch] — для только что запущенного
 * из adbd, [launchAt] — время выдачи [launch] (мс с загрузки).
 */
data class NonceSlots(
    val resume: String? = null, val launch: String? = null, val launchAt: Long = 0,
)

interface NonceStore {
    fun load(): NonceSlots
    fun save(slots: NonceSlots)
}

/**
 * Одноразовые пароли передачи, которые создаёт приложение. Принятый пароль гасит все остальные, а принявшему помощнику
 * выдаётся новый [NonceSlots.resume]; пароль хранится, чтобы живой помощник мог передать Binder заново, если приложение
 * было убито и процесс новый. [clock] измеряет срок жизни стартового пароля (мс с загрузки).
 */
class HandoffNonces(
    private val store: NonceStore,
    private val clock: () -> Long,
    private val random: () -> String = ::randomNonce,
) {
    @Synchronized
    fun issueLaunch(): String = random().also { store.save(store.load().copy(launch = it, launchAt = clock())) }

    @Synchronized
    fun matches(nonce: String?): Boolean {
        if (nonce == null) return false
        val s = store.load()
        val age = clock() - s.launchAt
        // стартовый пароль действует только пока идёт ожидание передачи; неиспользованный просрочивается
        val launch = s.launch?.takeIf { age in 0..LAUNCH_TTL_MS }
        return same(nonce, s.resume) or same(nonce, launch)
    }

    /** Пароль верный — гасит его и возвращает новый выданный пароль; неверный — null. */
    @Synchronized
    fun accept(nonce: String?): String? {
        if (!matches(nonce)) return null
        return random().also { store.save(NonceSlots(resume = it)) }
    }

    private val _resets = MutableStateFlow(0)
    /** Сколько раз пароли гасились целиком ([clear]); изменилось — выданные ранее пароли недействительны. */
    val resets: StateFlow<Int> = _resets.asStateFlow()

    @Synchronized
    fun clear() {
        store.save(NonceSlots())
        _resets.value++
    }

    private fun same(a: String, b: String?) =
        b != null && MessageDigest.isEqual(a.toByteArray(Charsets.UTF_8), b.toByteArray(Charsets.UTF_8))

    companion object {
        /** Покрывает ожидание передачи, пока приложение держит поток открытым, и ещё один круг ожидания. */
        const val LAUNCH_TTL_MS = 30_000L
        private val rng = SecureRandom()
        fun randomNonce(): String = ByteArray(32).also(rng::nextBytes).joinToString("") { "%02x".format(it) }
    }
}
