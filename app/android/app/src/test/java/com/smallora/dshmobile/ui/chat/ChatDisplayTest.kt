package com.smallora.dshmobile.ui.chat

import com.smallora.dshmobile.core.ChatItem
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * [buildDisplayItems] 的纯 JVM 单测：中间过程合并、最终回答保留、流式不吞、跨用户不合并、计数正确。
 */
class ChatDisplayTest {

    private fun user(key: String, text: String = "问题") =
        ChatItem.User(key = key, text = text, images = emptyList())

    private fun assistant(
        key: String,
        text: String = "",
        reasoning: String? = null,
        streaming: Boolean = false,
        interrupted: Boolean = false,
    ) = ChatItem.Assistant(
        key = key,
        text = text,
        reasoning = reasoning,
        streaming = streaming,
        interrupted = interrupted,
    )

    private fun tool(key: String, running: Boolean = false, isError: Boolean = false) =
        ChatItem.Tool(
            key = key,
            name = "bash",
            args = "{\"cmd\":\"ls\"}",
            result = if (running) null else "ok",
            isError = isError,
            running = running,
        )

    private fun notice(key: String, kind: String = "info") =
        ChatItem.Notice(key = key, text = "提示", kind = kind)

    @Test
    fun middleStepsGrouped_finalAnswerKeptAtSegmentEnd() {
        val items = listOf(
            user("u1"),
            assistant("a1", text = "", reasoning = "先想一下"),
            tool("t1"),
            notice("n1"),
            assistant("a2", text = "最终答案"),
        )

        val display = buildDisplayItems(items)

        assertEquals(3, display.size)
        assertTrue(display[0] is DisplayItem.Single)
        assertEquals("u1", (display[0] as DisplayItem.Single).item.key)

        val group = display[1] as DisplayItem.Group
        assertEquals(listOf("a1", "t1", "n1"), group.items.map { it.key })
        assertEquals("a1", group.key)
        assertFalse(group.running)

        val final = display[2] as DisplayItem.Single
        assertEquals("a2", final.item.key)
    }

    @Test
    fun earlierAssistantTextGrouped_onlyLastAnswerIsFinal() {
        val items = listOf(
            user("u1"),
            assistant("a1", text = "中间步骤"),
            tool("t1"),
            assistant("a2", text = "最终答案"),
        )

        val display = buildDisplayItems(items)

        assertEquals(3, display.size)
        assertEquals(listOf("a1", "t1"), (display[1] as DisplayItem.Group).items.map { it.key })
        assertEquals("a2", (display[2] as DisplayItem.Single).item.key)
    }

    @Test
    fun streamingAssistantKept_whileRunning() {
        val items = listOf(
            user("u1"),
            assistant("a1", text = "部分输出", streaming = true),
            tool("t1", running = true),
        )

        val display = buildDisplayItems(items)

        assertEquals(3, display.size)
        val group = display[1] as DisplayItem.Group
        assertEquals(listOf("t1"), group.items.map { it.key })
        assertTrue("流式回合中 Group 应显示运行中", group.running)

        val streaming = display[2] as DisplayItem.Single
        assertEquals("a1", streaming.item.key)
        assertTrue((streaming.item as ChatItem.Assistant).streaming)
    }

    @Test
    fun noFinalNoStreaming_allNonUserItemsGrouped() {
        val items = listOf(
            user("u1"),
            tool("t1"),
            notice("n1", kind = "turn-error"),
        )

        val display = buildDisplayItems(items)

        assertEquals(2, display.size)
        val group = display[1] as DisplayItem.Group
        assertEquals(listOf("t1", "n1"), group.items.map { it.key })
        assertFalse(group.running)
    }

    @Test
    fun groupRunning_whenToolRunning() {
        val items = listOf(user("u1"), tool("t1", running = true))

        val group = buildDisplayItems(items).single { it is DisplayItem.Group } as DisplayItem.Group

        assertTrue(group.running)
    }

    @Test
    fun groupNotRunning_afterTurnFinished() {
        val items = listOf(
            user("u1"),
            assistant("a1", text = "", reasoning = "思考"),
            tool("t1"),
            assistant("a2", text = "答案"),
        )

        val group = buildDisplayItems(items).single { it is DisplayItem.Group } as DisplayItem.Group

        assertFalse(group.running)
    }

    @Test
    fun crossUserSegments_areNotMerged() {
        val items = listOf(
            user("u1"),
            tool("t1"),
            user("u2"),
            tool("t2"),
        )

        val display = buildDisplayItems(items)

        assertEquals(4, display.size)
        val groups = display.filterIsInstance<DisplayItem.Group>()
        assertEquals(2, groups.size)
        assertEquals(listOf("t1"), groups[0].items.map { it.key })
        assertEquals(listOf("t2"), groups[1].items.map { it.key })
    }

    @Test
    fun groupCountMatchesMergedItems() {
        val items = listOf(
            user("u1"),
            assistant("a1", reasoning = "想"),
            tool("t1"),
            tool("t2"),
            notice("n1"),
            assistant("a2", text = "答案"),
        )

        val group = buildDisplayItems(items).single { it is DisplayItem.Group } as DisplayItem.Group

        assertEquals(4, group.items.size)
    }

    @Test
    fun finalAnswerWithReasoning_staysSingle() {
        val items = listOf(
            user("u1"),
            assistant("a1", text = "答案", reasoning = "推理过程"),
        )

        val display = buildDisplayItems(items)

        assertEquals(2, display.size)
        val single = display[1] as DisplayItem.Single
        assertEquals("a1", single.item.key)
        assertEquals("推理过程", (single.item as ChatItem.Assistant).reasoning)
    }

    @Test
    fun onlyUsers_produceNoGroup() {
        val display = buildDisplayItems(listOf(user("u1"), user("u2")))

        assertEquals(2, display.size)
        assertTrue(display.all { it is DisplayItem.Single })
        assertTrue(display.none { it is DisplayItem.Group })
    }

    @Test
    fun emptySegmentAfterUser_producesNoGroup() {
        val display = buildDisplayItems(listOf(user("u1")))

        assertEquals(1, display.size)
        assertTrue(display.single() is DisplayItem.Single)
    }

    @Test
    fun emptyInput_returnsEmpty() {
        assertTrue(buildDisplayItems(emptyList()).isEmpty())
    }
}
