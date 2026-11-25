package com.pocketcode

import com.pocketcode.agent.isPermanentRequestError
import com.pocketcode.agent.newDeclarationsUnwired
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.nio.file.Files

class RetryErrorTest {
    @Test
    fun geminiRequestErrorsAreNotRetried() {
        assertTrue(isPermanentRequestError("Function call is missing a thought_signature in functionCall parts"))
        assertTrue(isPermanentRequestError("INVALID_ARGUMENT: Request contains an invalid argument"))
        assertTrue(isPermanentRequestError("Request contains an Invalid Argument."))
    }

    @Test
    fun networkAndServerErrorsStillRetry() {
        assertFalse(isPermanentRequestError("HTTP 503: service unavailable"))
        assertFalse(isPermanentRequestError("Unexpected end of stream"))
        assertFalse(isPermanentRequestError("timeout"))
        assertFalse(isPermanentRequestError(""))
        assertFalse(isPermanentRequestError(null))
    }

    @Test
    fun unwiredNewDeclarationIsDetected() {
        val root = Files.createTempDirectory("wiring").toFile()
        try {
            val src = File(root, "src").apply { mkdirs() }
            // Фича объявлена, но не вызывается нигде вне своего файла — должно быть обнаружено.
            File(src, "NewFeature.kt").writeText("object NewFeature { fun go() = 1 }\n")
            assertTrue(newDeclarationsUnwired(root, listOf("src/NewFeature.kt")))
            // После подключения к экрану (вызов в другом файле) предупреждение снимается.
            File(src, "Screens.kt").writeText("fun render() = NewFeature.go()\n")
            assertFalse(newDeclarationsUnwired(root, listOf("src/NewFeature.kt")))
            // Файл без объявлений или без правок — проверки нет.
            File(src, "Plain.kt").writeText("private val x = 1\n")
            assertFalse(newDeclarationsUnwired(root, listOf("src/Plain.kt")))
            assertFalse(newDeclarationsUnwired(root, emptyList()))
            // Несуществующий путь не должен ронять проверку.
            assertFalse(newDeclarationsUnwired(root, listOf("src/Missing.kt")))
        } finally {
            root.deleteRecursively()
        }
    }
}
