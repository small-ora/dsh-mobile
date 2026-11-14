package com.smallora.dshmobile.core

import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * ChatReducer 归一化单测：snapshot 历史、实时事件去重、流式增量、工具配对、目标/待办/标题。
 * 这些是 SessionRepository 的核心状态逻辑（纯函数，JVM 可测）。
 */
class RepositoryTest {

    private fun sessionSnapshot(): JSONObject = JSONObject(
        """
        {
          "type": "snapshot",
          "cursor": 5,
          "hasMore": true,
          "projections": {
            "kind": "sequenced",
            "values": {
              "title": "会话标题",
              "todos": [{"content": "第一步", "status": "pending"}],
              "goal": {"goal": {"id": "g1", "objective": "目标", "phase": "active", "revision": 2}}
            }
          },
          "records": [
            {"type": "event", "event": {"type": "user/message", "seq": 1, "time": 1,
              "data": {"source": {"kind": "user"}, "message": {"role": "user",
                "content": [{"type": "text", "text": "你好"}]}}}},
            {"type": "event", "event": {"type": "assistant/message", "seq": 2, "time": 2,
              "data": {"message": {"role": "assistant",
                "content": [{"type": "text", "text": "你好，有什么可以帮你？"}]}}}}
          ]
        }
        """.trimIndent(),
    )

    @Test
    fun snapshotBuildsHistoryAndProjections() {
        val state = ChatReducer.applyValue(ChatReducer.initial("s1"), sessionSnapshot())
        assertEquals("会话标题", state.title)
        assertEquals(2, state.items.size)
        assertTrue(state.hasMore)
        assertEquals("第一步", state.todos.single().content)
        assertEquals("g1", state.goal?.id)
        assertEquals(2L, state.goal?.revision)
        val first = state.items[0]
        assertTrue(first is ChatItem.User)
        assertEquals("你好", (first as ChatItem.User).text)
        val second = state.items[1]
        assertTrue(second is ChatItem.Assistant)
        assertEquals("你好，有什么可以帮你？", (second as ChatItem.Assistant).text)
        assertFalse((second as ChatItem.Assistant).streaming)
    }

    @Test
    fun liveEventsAppendAndDeduplicateBySeq() {
        var state = ChatReducer.applyValue(ChatReducer.initial("s1"), sessionSnapshot())
        val event = JSONObject(
            """{"type":"event","event":{"type":"user/message","seq":3,"time":3,
            "data":{"source":{"kind":"user"},"message":{"role":"user","content":[{"type":"text","text":"再来一句"}]}}}}""",
        )
        state = ChatReducer.applyValue(state, event)
        state = ChatReducer.applyValue(state, event) // 重复到达（重连补发）
        assertEquals(3, state.items.size)
        assertEquals("再来一句", (state.items[2] as ChatItem.User).text)
    }

    @Test
    fun injectContextIsNotRenderedAsUserBubble() {
        var state = ChatReducer.initial("s1")
        val injected = JSONObject(
            """{"type":"event","event":{"type":"user/message","seq":1,"time":1,
            "data":{"source":{"kind":"system-prompt"},"message":{"role":"user","content":[{"type":"text","text":"注入上下文"}]}}}}""",
        )
        state = ChatReducer.applyEvent(state, injected)
        assertTrue(state.items.isEmpty())
    }

    @Test
    fun assistantStreamAccumulatesAndFinalMessageReplacesIt() {
        var state = ChatReducer.initial("s1")
        state = ChatReducer.applyAssistantStream(
            state,
            JSONObject("""{"type":"start","attemptId":"a1","revision":1}"""),
        )
        state = ChatReducer.applyAssistantStream(
            state,
            JSONObject("""{"type":"chunk","attemptId":"a1","index":0,"chunk":{"type":"text-delta","text":"你"}}"""),
        )
        state = ChatReducer.applyAssistantStream(
            state,
            JSONObject("""{"type":"chunk","attemptId":"a1","index":1,"chunk":{"type":"reasoning-delta","text":"思考中"}}"""),
        )
        state = ChatReducer.applyAssistantStream(
            state,
            JSONObject("""{"type":"chunk","attemptId":"a1","index":2,"chunk":{"type":"text-delta","text":"好"}}"""),
        )
        val streaming = state.items.single() as ChatItem.Assistant
        assertEquals("你好", streaming.text)
        assertEquals("思考中", streaming.reasoning)
        assertTrue(streaming.streaming)

        state = ChatReducer.applyEvent(
            state,
            JSONObject(
                """{"type":"assistant/message","seq":4,"time":4,
                "data":{"message":{"role":"assistant","content":[{"type":"text","text":"你好"}]}}}""",
            ),
        )
        assertEquals(1, state.items.size)
        val final = state.items.single()
        assertTrue(final is ChatItem.Assistant)
        assertFalse((final as ChatItem.Assistant).streaming)
        assertEquals("e:4", final.key)
    }

    @Test
    fun abandonedStreamIsRemoved() {
        var state = ChatReducer.initial("s1")
        state = ChatReducer.applyAssistantStream(
            state,
            JSONObject("""{"type":"start","attemptId":"a1","revision":1}"""),
        )
        state = ChatReducer.applyAssistantStream(
            state,
            JSONObject("""{"type":"chunk","attemptId":"a1","index":0,"chunk":{"type":"text-delta","text":"半句"}}"""),
        )
        state = ChatReducer.applyAssistantStream(
            state,
            JSONObject("""{"type":"end","attemptId":"a1","revision":2,"index":1,"outcome":{"kind":"abandoned"}}"""),
        )
        assertTrue(state.items.isEmpty())
    }

    @Test
    fun toolCallAndResultArePairedByCallId() {
        var state = ChatReducer.initial("s1")
        state = ChatReducer.applyEvent(
            state,
            JSONObject(
                """{"type":"tool/call","seq":5,"time":5,
                "data":{"turn":1,"step":1,"callId":"call-1","name":"shell","arguments":"{\"cmd\":\"ls\"}"}}""",
            ),
        )
        val running = state.items.single() as ChatItem.Tool
        assertEquals("shell", running.name)
        assertTrue(running.running)

        state = ChatReducer.applyEvent(
            state,
            JSONObject(
                """{"type":"tool/result","seq":6,"time":6,
                "data":{"turn":1,"step":1,"message":{"role":"tool","toolCallId":"call-1","isError":true,
                "content":[{"type":"text","text":"permission denied"}]}}}""",
            ),
        )
        val done = state.items.single() as ChatItem.Tool
        assertEquals("call-1", done.key.removePrefix("tool:"))
        assertEquals("permission denied", done.result)
        assertFalse(done.running)
        assertTrue(done.isError)
    }

    @Test
    fun turnStateAndTitleAndGoalChangeAreFolded() {
        var state = ChatReducer.initial("s1")
        state = ChatReducer.applyEvent(
            state,
            JSONObject("""{"type":"turn/start","seq":1,"time":1,"data":{"turn":1}}"""),
        )
        assertTrue(state.running)
        state = ChatReducer.applyEvent(
            state,
            JSONObject("""{"type":"turn/end","seq":2,"time":2,"data":{"turn":1,"reason":"success"}}"""),
        )
        assertFalse(state.running)

        state = ChatReducer.applyEvent(
            state,
            JSONObject("""{"type":"session/title","seq":3,"time":3,"data":{"title":"新标题","source":"user"}}"""),
        )
        assertEquals("新标题", state.title)

        state = ChatReducer.applyEvent(
            state,
            JSONObject(
                """{"type":"goal/change","seq":4,"time":4,
                "data":{"kind":"goal/change","version":1,"operation":"pause","goal":{"id":"g1","objective":"目标","phase":"paused","revision":3}}}""",
            ),
        )
        assertEquals("paused", state.goal?.phase)
        assertEquals(3L, state.goal?.revision)

        state = ChatReducer.applyEvent(
            state,
            JSONObject("""{"type":"goal/change","seq":5,"time":5,"data":{"kind":"goal/change","operation":"clear"}}"""),
        )
        assertNull(state.goal)
    }

    @Test
    fun todoWriteReplacesWholeList() {
        var state = ChatReducer.initial("s1")
        state = ChatReducer.applyEvent(
            state,
            JSONObject(
                """{"type":"todo/write","seq":1,"time":1,
                "data":{"todos":[{"content":"a","status":"completed"},{"content":"b","status":"in_progress"}]}}""",
            ),
        )
        assertEquals(2, state.todos.size)
        state = ChatReducer.applyEvent(
            state,
            JSONObject("""{"type":"todo/write","seq":2,"time":2,"data":{"todos":[{"content":"c","status":"pending"}]}}"""),
        )
        assertEquals(1, state.todos.size)
        assertEquals("c", state.todos.single().content)
    }

    @Test
    fun olderPagePrependsWithoutDuplicates() {
        var state = ChatReducer.applyValue(ChatReducer.initial("s1"), sessionSnapshot())
        val olderRecords = JSONArray(
            """
            [{"type":"event","event":{"type":"user/message","seq":0,"time":0,
              "data":{"source":{"kind":"user"},"message":{"role":"user","content":[{"type":"text","text":"更早"}]}}}}]
            """.trimIndent(),
        )
        state = ChatReducer.prependRecords(state, olderRecords, hasMore = false)
        assertEquals(3, state.items.size)
        assertEquals("更早", (state.items[0] as ChatItem.User).text)
        assertFalse(state.hasMore)
        // 再插一次相同记录不应重复
        state = ChatReducer.prependRecords(state, olderRecords, hasMore = false)
        assertEquals(3, state.items.size)
    }

    @Test
    fun interruptedAssistantMessageIsMarked() {
        var state = ChatReducer.initial("s1")
        state = ChatReducer.applyEvent(
            state,
            JSONObject(
                """{"type":"assistant/message","seq":1,"time":1,
                "data":{"interrupted":true,"message":{"role":"assistant","content":[{"type":"text","text":"被中断"}]}}}""",
            ),
        )
        val item = state.items.single() as ChatItem.Assistant
        assertTrue(item.interrupted)
    }
}
