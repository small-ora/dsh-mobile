package com.smallora.dshmobile.core

import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * 排队消息只展示人类输入：DSH inbox 会注入后台任务/子 Agent/团队/目标续跑等非人类消息，
 * App 不能把这些当成「排队 N 条」展示。
 */
class QueuedMessageSourceTest {

    private fun inbox(vararg items: JSONObject): JSONObject = JSONObject().put(
        "inbox",
        JSONObject()
            .put("next-turn", JSONArray(items.toList()))
            .put("next-step", JSONArray()),
    )

    private fun item(id: String, text: String, source: JSONObject? = null): JSONObject {
        val obj = JSONObject()
            .put("id", id)
            .put("content", JSONArray().put(JSONObject().put("type", "text").put("text", text)))
        if (source != null) obj.put("source", source)
        return obj
    }

    @Test
    fun onlyHumanQueuedMessagesAreShown() {
        val values = inbox(
            item("human", "排队一", JSONObject().put("kind", "user").put("rpcId", "r1")),
            item("job", "后台任务完成通知", JSONObject().put("kind", "tool-jobs").put("form", "notice")),
            item("subagent-settled", "子 Agent 已结束", JSONObject().put("kind", "subagent-settled")),
            item("subagent-message", "Agent s9 sent a message", JSONObject().put("kind", "agent-message")),
            item("team", "团队消息", JSONObject().put("kind", "team-message")),
            item("goal", "目标续跑", JSONObject().put("kind", "goal")),
        )

        val queued = Json.parseQueuedMessages(values)
        assertEquals(listOf("human"), queued.map { it.key })
        assertEquals("排队一", queued.single().text)
    }

    @Test
    fun legacyRowsWithoutSourceStayVisible() {
        val values = inbox(item("legacy", "没有 source 的历史排队消息"))
        assertEquals(listOf("legacy"), Json.parseQueuedMessages(values).map { it.key })
    }

    @Test
    fun nextStepHumanMessagesAreShownAndInjectedAreHidden() {
        val values = JSONObject().put(
            "inbox",
            JSONObject()
                .put("next-turn", JSONArray())
                .put(
                    "next-step",
                    JSONArray(
                        listOf(
                            item("step-human", "下一步排队", JSONObject().put("kind", "user")),
                            item("step-injected", "step injection", JSONObject().put("kind", "runtime-context")),
                        ),
                    ),
                ),
        )
        assertEquals(listOf("step-human"), Json.parseQueuedMessages(values).map { it.key })
    }

    @Test
    fun emptyOrMissingInboxYieldsNothing() {
        assertEquals(0, Json.parseQueuedMessages(null).size)
        assertEquals(0, Json.parseQueuedMessages(JSONObject()).size)
    }
}
