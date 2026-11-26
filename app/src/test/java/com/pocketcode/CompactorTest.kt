package com.pocketcode

import com.pocketcode.agent.Compactor
import com.pocketcode.llm.Block
import com.pocketcode.llm.LlmClient
import com.pocketcode.llm.LlmResponse
import com.pocketcode.llm.Message
import com.pocketcode.llm.ToolSpec
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

class CompactorTest {
    private class FakeLlm : LlmClient {
        override suspend fun complete(
            system: String,
            messages: List<Message>,
            tools: List<ToolSpec>,
            onTextDelta: (String) -> Unit,
        ): LlmResponse = LlmResponse(listOf(Block.Text("ok")), "stop")
    }

    private val big = "x".repeat(20_000)

    private fun history(): MutableList<Message> = mutableListOf<Message>().apply {
        repeat(20) { i ->
            add(
                Message(
                    "assistant",
                    listOf(Block.ToolUse("t$i", "write_file", buildJsonObject { put("path", "a.kt"); put("content", JsonPrimitive(big)) })),
                ),
            )
            add(Message("user", listOf(Block.ToolResult("t$i", "y".repeat(10_000), false), Block.Text("дальше"))))
        }
    }

    @Test
    fun oldWriteArgumentsAndOutputsAreShrunk() {
        val h = history()
        val before = Compactor.estimateTokens(h)
        Compactor(FakeLlm(), 100_000).maskOld(h, keepRecent = 4, over = 2500, keep = 500)
        val firstUse = h.first().blocks.filterIsInstance<Block.ToolUse>().first()
        // Старое содержимое файла на диске — в истории оно больше не нужно
        assertTrue((firstUse.input["content"] as JsonPrimitive).content.length < 1000)
        assertNotNull(firstUse.input["path"])
        assertTrue(h[1].blocks.filterIsInstance<Block.ToolResult>().first().content.length < 700)
        assertTrue(Compactor.estimateTokens(h) < before / 3)
    }

    @Test
    fun recentMessagesStayIntact() {
        val h = history()
        Compactor(FakeLlm(), 100_000).maskOld(h, keepRecent = 4, over = 2500, keep = 500)
        val recentUse = h[h.size - 2].blocks.filterIsInstance<Block.ToolUse>().first()
        assertEquals(big.length, (recentUse.input["content"] as JsonPrimitive).content.length)
        assertEquals(10_000, h.last().blocks.filterIsInstance<Block.ToolResult>().first().content.length)
    }
}
