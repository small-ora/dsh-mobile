package com.smallora.dshmobile.core

import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 用**真实 DSH 帧**驱动 ChatReducer 的夹具测试。
 *
 * 夹具来源：隔离 DSH 0.2.1-rc.2 + 本项目网关实际抓取的 session/follow 流
 * （app/src/test/resources/fixtures/follow-stream.json；仅含一条 "fixture prompt"，
 * 且该会话因无 API key 以 assistant/attempt + turn/end(error) 结束，无用户隐私数据）。
 *
 * 期望：真实字段结构下 snapshot 可解析、用户消息只渲染人类输入、attempt/assistant-stream
 * 不抛异常不产生错误态、seq 去重排序正确、turn/end 错误原因可读。
 */
class ChatReducerTest {

    private fun loadValues(): List<JSONObject> {
        val loader = requireNotNull(javaClass.classLoader) { "测试运行时缺少 classloader" }
        val stream = loader.getResourceAsStream("fixtures/follow-stream.json")
            ?: error("缺少测试夹具 fixtures/follow-stream.json")
        val text = stream.bufferedReader(Charsets.UTF_8).use { it.readText() }
        val root = JSONObject(text)
        val items = root.optJSONArray("items") ?: error("夹具格式错误：缺少 items 数组")
        val values = ArrayList<JSONObject>(items.length())
        for (i in 0 until items.length()) {
            items.optJSONObject(i)?.let { values.add(it) }
        }
        return values
    }

    private fun applyAll(sessionId: String = "real-session"): ChatUiState {
        var state = ChatReducer.initial(sessionId)
        for (value in loadValues()) {
            state = ChatReducer.applyValue(state, value)
        }
        return state
    }

    @Test
    fun snapshotParsesCursorRecordsAndProjections() {
        val values = loadValues()
        assertEquals("真实夹具应包含 snapshot 首帧", "snapshot", values.first().strOrNull("type"))
        val snapshot = values.first()
        assertEquals(5L, snapshot.longOr("cursor"))
        assertEquals(6, snapshot.arrOrNull("records")?.length())
        assertFalse(snapshot.boolOr("hasMore", true))

        // snapshot 的 projections 为 {asOfSeq, values:{…}}；title 初始为 null，由后续 session/title 事件补充
        val projections = snapshot.objOrNull("projections") ?: error("缺少 projections")
        assertTrue(projections.has("values"))
        assertNull(projections.projectionValues().strOrNull("title"))

        val state = ChatReducer.applyValue(ChatReducer.initial("real-session"), snapshot)
        assertTrue("snapshot records 只有审计/占位事件，不产生聊天气泡", state.items.isEmpty())
        assertTrue("snapshot 里 turn/start 应把 running 置真", state.running)
    }

    @Test
    fun realUserMessageIsRenderedOnlyForHumanInput() {
        val state = applyAll()
        val users = state.items.filterIsInstance<ChatItem.User>()
        assertEquals("应只渲染 1 条人类输入（注入的 runtime-context/skill-catalog/time-context 必须过滤）", 1, users.size)
        assertEquals("fixture prompt", users.single().text)
        assertTrue("真实 data 直接是 {content,source,role,id}，不应有图片", users.single().images.isEmpty())
    }

    @Test
    fun sessionTitleEventSetsTitleFromRealFrame() {
        val state = applyAll()
        // session/title：{"title":"fixture prompt","messageSeqs":[8],"source":{"kind":"fallback"}}
        assertEquals("fixture prompt", state.title)
    }

    @Test
    fun assistantAttemptAndStreamFramesDoNotThrowOrSetError() {
        val state = applyAll()
        assertNull("真实帧处理不应产生错误状态", state.error)
        assertFalse("turn/end 后 running 应为 false", state.running)
        // assistant-stream 先 start 占位，随后 assistant/attempt 落盘 → 占位项应被移除
        assertTrue(
            "不应残留空的流式助手占位项",
            state.items.none { it is ChatItem.Assistant },
        )
        assertTrue(
            "assistant/attempt 应折叠为一条 attempt 提示",
            state.items.any { it is ChatItem.Notice && it.kind == "attempt" },
        )
    }

    @Test
    fun turnEndErrorReasonIsHumanReadable() {
        val state = applyAll()
        val notice = state.items.filterIsInstance<ChatItem.Notice>()
            .firstOrNull { it.kind == "turn-end" }
            ?: error("缺少 turn/end 提示")
        assertTrue("应说明是错误结束", notice.text.contains("错误"))
        assertTrue("应带出真实错误原因", notice.text.contains("no API key"))
        assertFalse("不应把 reason 对象原样 toString", notice.text.contains("{"))
    }

    @Test
    fun eventsAreOrderedAndDeduplicatedBySeq() {
        val values = loadValues()
        // 真实事件 seq 连续 6..18（assistant-stream 无 seq）
        val seqs = values.mapNotNull { value ->
            if (value.strOrNull("type") == "event") value.objOrNull("event")?.eventSeq() else null
        }
        assertEquals((6L..18L).toList(), seqs.sorted())

        var state = ChatReducer.initial("real-session")
        for (value in values) state = ChatReducer.applyValue(state, value)
        val firstPass = state.items
        // 重复投递整条流（重连补发场景）：不应产生重复项
        for (value in values) state = ChatReducer.applyValue(state, value)
        assertEquals("重复帧不应产生重复项", firstPass.size, state.items.size)
        assertEquals(
            "重复投递后用户消息仍只有一条",
            1,
            state.items.filterIsInstance<ChatItem.User>().size,
        )
        // 顺序：用户消息在 attempt/turn-end 提示之前
        val userIndex = state.items.indexOfFirst { it is ChatItem.User }
        val lastNoticeIndex = state.items.indexOfLast { it is ChatItem.Notice }
        assertTrue(userIndex in 0..<lastNoticeIndex)
    }
}
