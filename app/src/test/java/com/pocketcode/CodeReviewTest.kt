package com.pocketcode

import com.pocketcode.tools.CodeReview
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class CodeReviewTest {

    private fun rulesOf(text: String, path: String = "src/app.py"): Set<String> =
        CodeReview.scanText(path, text).map { it.rule }.toSet()

    @Test
    fun cleanCodeHasNoFindings() {
        val code = """
            import json

            def load(path):
                with open(path, encoding="utf-8") as f:
                    return json.load(f)

            def total(items):
                return sum(i["price"] for i in items)
        """.trimIndent()
        assertEquals(emptySet<String>(), rulesOf(code))
    }

    @Test
    fun hardcodedSecretIsFound() {
        val code = "val apiKey = \"sk-live-1234567890abcd\""
        assertTrue("hardcoded-secret" in rulesOf(code, "src/Config.kt"))
    }

    @Test
    fun placeholderValuesAreNotReportedAsSecrets() {
        assertEquals(emptySet<String>(), rulesOf("val apiKey = \"your_api_key_here\"", "src/Config.kt"))
    }

    @Test
    fun sqlConcatenationIsFound() {
        assertTrue("sql-concat" in rulesOf("c.execute(\"SELECT * FROM users WHERE id = \" + uid)"))
    }

    @Test
    fun sqlFStringIsFound() {
        assertTrue("sql-fstring" in rulesOf("c.execute(f\"DELETE FROM users WHERE id = {uid}\")"))
    }

    @Test
    fun disabledTlsIsFound() {
        assertTrue("tls-disabled" in rulesOf("requests.get(url, verify=False)"))
        assertTrue("tls-disabled" in rulesOf("http.DefaultTransport.TLSClientConfig = &tls.Config{InsecureSkipVerify: true}"))
    }

    @Test
    fun emptyCatchIsFound() {
        assertTrue("swallowed-error" in rulesOf("try { work() } catch (e: Exception) {}", "src/Main.kt"))
        assertTrue("swallowed-error" in rulesOf("try:\n    work()\nexcept Exception:\n    pass"))
    }

    @Test
    fun stubsAreFoundButOrdinaryTodoIsNot() {
        assertTrue("placeholder" in rulesOf("raise NotImplementedError"))
        assertTrue("placeholder" in rulesOf("TODO()", "src/Auth.kt"))
        // Обычные заметки в комментариях не должны считаться ошибкой и гонять агента по кругу.
        assertTrue("placeholder" !in rulesOf("// TODO: implement login later", "src/Auth.kt"))
        assertTrue("placeholder" !in rulesOf("// FIXME: slow query", "src/db.py"))
    }

    @Test
    fun ordinaryCodeIsNotFlagged() {
        // Пространства имён XML, схемы и обычные ссылки в документации — не ошибка безопасности.
        assertEquals(emptySet<String>(), rulesOf("<LinearLayout xmlns:android=\"http://schemas.android.com/apk/res/android\">", "res/layout/main.xml"))
        // Логирование без значения секрета.
        assertEquals(emptySet<String>(), rulesOf("Log.d(TAG, \"token missing, re-login\")", "src/Auth.kt"))
        // Отладочный флаг в локальном конфиге разработчика.
        assertEquals(emptySet<String>(), rulesOf("DEBUG = True", "settings/dev.py"))
        // Обычный текст с SQL-словом, но без склейки.
        assertEquals(emptySet<String>(), rulesOf("print(f\"Delete {count} items\")"))
    }

    @Test
    fun privateKeyIsFound() {
        assertTrue("private-key" in rulesOf("-----BEGIN RSA PRIVATE KEY-----"))
    }

    @Test
    fun shellAndEvalAreFound() {
        assertTrue("shell-injection" in rulesOf("subprocess.run(cmd, shell=True)"))
        assertTrue("dynamic-eval" in rulesOf("eval(user_input)", "src/app.js"))
        // Безопасный разбор литерала не считается ошибкой.
        assertTrue("dynamic-eval" !in rulesOf("ast.literal_eval(raw)", "src/app.py"))
    }

    @Test
    fun weakPasswordHashNeedsPasswordContext() {
        assertTrue("weak-password-hash" in rulesOf("digest = md5(password)"))
        assertTrue("weak-password-hash" !in rulesOf("chip_id = md5(file_bytes)"))
    }

    @Test
    fun reportMentionsPathLineAndCounts() {
        val findings = CodeReview.scanText("src/db.py", "c.execute(\"SELECT * FROM t WHERE id = \" + uid)")
        val text = CodeReview.format(findings)
        assertTrue(text.contains("критичных 1"))
        assertTrue(text.contains("src/db.py:1"))
        assertTrue(text.contains("sql-concat"))
    }

    @Test
    fun highSeverityComesFirst() {
        val findings = CodeReview.scanText(
            "src/a.py",
            "x = 1  # your code here\nrequests.get(url, verify=False)",
        )
        assertEquals(2, findings.size)
        val report = CodeReview.format(findings)
        // Критичное идёт в отчёт первым, даже если в файле оно ниже.
        assertTrue(report.indexOf("[HIGH]") in 0 until report.indexOf("[MEDIUM]"))
    }
}
