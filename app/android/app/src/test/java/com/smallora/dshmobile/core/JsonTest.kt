package com.smallora.dshmobile.core

import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Json 归一化单测（JVM）：覆盖会话列表投影、审批/提问、waterfall、模型目录、消息内容块。
 * 数据形状取自本机 @deepseek-ai/dsh@0.2.1-rc.2 的 typert.host.js / types.d.ts。
 */
class JsonTest {

    @Test
    fun parsesSessionListWithProjections() {
        val item = JSONObject(
            """
            {
              "sessionId": "s1",
              "updatedAt": 1700000000000,
              "running": true,
              "blank": false,
              "agentAvailable": true,
              "cwd": "/root/work",
              "projections": {
                "kind": "sequenced",
                "asOfSeq": 12,
                "values": {
                  "title": "修复登录",
                  "todos": [
                    {"content": "写测试", "status": "completed"},
                    {"content": "跑构建", "status": "in_progress"}
                  ],
                  "goal": {
                    "goal": {
                      "id": "g1",
                      "objective": "完成登录流程",
                      "phase": "active",
                      "revision": 3,
                      "maxGoalRounds": 5
                    },
                    "roundsStarted": 1,
                    "createdAt": 1,
                    "updatedAt": 2
                  },
                  "modelSelection": {
                    "lastUsed": {"provider": "deepseek", "model": "v4"},
                    "next": {"provider": "deepseek", "model": "v4.1", "reasoningEffort": "high"}
                  }
                }
              }
            }
            """.trimIndent(),
        )
        val rows = Json.parseSessions(JSONObject().put("items", JSONArray().put(item)))
        assertEquals(1, rows.size)
        val row = rows[0]
        assertEquals("s1", row.id)
        assertEquals("修复登录", row.title)
        assertTrue(row.running)
        assertEquals("/root/work", row.cwd)
        assertEquals(2, row.todos.size)
        assertEquals("completed", row.todos[0].status)
        assertEquals("in_progress", row.todos[1].status)
        assertEquals("g1", row.goal?.id)
        assertEquals(3L, row.goal?.revision)
        assertEquals("active", row.goal?.phase)
        assertEquals("deepseek", row.selection?.provider)
        assertEquals("v4.1", row.selection?.model)
        assertEquals("high", row.selection?.reasoningEffort)
    }

    @Test
    fun parsesBlockedGoalReason() {
        val values = JSONObject(
            """{"goal":{"goal":{"id":"g2","objective":"obj","phase":"blocked","revision":7,
            "blockedReason":{"code":"waiting-user","message":"等待用户确认"}}}}""",
        )
        val goal = Json.parseGoalFromProjection(values)
        assertEquals("blocked", goal?.phase)
        assertEquals("等待用户确认", goal?.blockedReason)
        assertEquals(7L, goal?.revision)
    }

    @Test
    fun parsesApprovalAndQuestionPending() {
        val approval = JSONObject(
            """{"kind":"approval","eventId":"ev1","sessionId":"s1","toolName":"shell","reason":"执行命令","at":100}""",
        )
        val approved = Json.parsePendingItem(approval)
        assertEquals("approval", approved?.kind)
        assertEquals("ev1", approved?.eventId)
        assertEquals("shell", approved?.toolName)
        assertEquals(1, approved?.questions?.size?.plus(1))

        val question = JSONObject(
            """
            {"kind":"question","eventId":"ev2","sessionId":"s1","at":200,"questions":[
              {"id":"q1","question":"用哪个方案？","header":"方案","detail":"详情","multiSelect":true,
               "options":[{"label":"A","description":"第一个"},{"label":"B"}]}
            ]}
            """.trimIndent(),
        )
        val parsed = Json.parsePendingItem(question)
        assertEquals("question", parsed?.kind)
        assertEquals(1, parsed?.questions?.size)
        assertEquals("q1", parsed?.questions?.get(0)?.id)
        assertEquals("方案", parsed?.questions?.get(0)?.header)
        assertTrue(parsed?.questions?.get(0)?.multiSelect == true)
        assertEquals(2, parsed?.questions?.get(0)?.options?.size)
        assertEquals("第一个", parsed?.questions?.get(0)?.options?.get(0)?.description)
    }

    @Test
    fun parsesWaterfallFrames() {
        val questionWaterfall = JSONObject(
            """
            {"type":"waterfall","event":"user-questions/request","eventId":"ev3","agentId":"s9",
             "request":{"questions":[{"id":"q9","question":"继续吗？","options":[{"label":"继续"}]}],
             "wait":{"callId":"c1","timed":true}}}
            """.trimIndent(),
        )
        val request = Json.parseWaterfall(questionWaterfall)
        assertEquals("question", request?.kind)
        assertEquals("ev3", request?.eventId)
        assertEquals("s9", request?.sessionId)
        assertEquals("继续吗？", request?.questions?.get(0)?.question)

        val approvalWaterfall = JSONObject(
            """{"type":"waterfall","event":"approval/request","eventId":"ev4","agentId":"s9",
            "request":{"toolName":"fs.write","reason":"写文件","displayReason":{"en":"write","zh":"写入文件"}}}""",
        )
        val approval = Json.parseWaterfall(approvalWaterfall)
        assertEquals("approval", approval?.kind)
        assertEquals("fs.write", approval?.toolName)
        // reason 优先；displayReason 仅在 reason 缺失时兜底
        assertEquals("写文件", approval?.reason)

        val fallback = Json.parseWaterfall(
            JSONObject(
                """{"type":"waterfall","event":"approval/request","eventId":"ev5","agentId":"s9",
                "request":{"toolName":"fs.write","displayReason":{"en":"write","zh":"写入文件"}}}""",
            ),
        )
        assertEquals("写入文件", fallback?.reason)
    }

    @Test
    fun ignoresUnknownWaterfallEvent() {
        val other = JSONObject(
            """{"type":"waterfall","event":"plugin-manager/changed","eventId":"e0","agentId":"s"}""",
        )
        assertNull(Json.parseWaterfall(other))
    }

    @Test
    fun parsesModelCatalogGroups() {
        val catalog = JSONObject(
            """
            {"default":{"provider":"deepseek","model":"deepseek-chat"},
             "routableProviders":["deepseek"],
             "groups":[{"id":"deepseek","name":"DeepSeek","models":[
               {"id":"deepseek-chat","name":"DeepSeek Chat","reasoning":{"efforts":[{"id":"low","name":"低"},{"id":"high","name":"高"}]}},
               {"id":"deepseek-reasoner","name":"DeepSeek Reasoner"}
             ]}],
             "failures":[]}
            """.trimIndent(),
        )
        val options = Json.parseModelCatalog(catalog)
        assertEquals(2, options.size)
        assertEquals("deepseek", options[0].provider)
        assertEquals("deepseek-chat", options[0].model)
        assertEquals("DeepSeek Chat", options[0].name)
        assertEquals(listOf("low", "high"), options[0].reasoning)
        assertTrue(options[1].reasoning.isEmpty())
    }

    @Test
    fun parsesUserMessageBlocks() {
        val data = JSONObject(
            """
            {"source":{"kind":"user"},"message":{"role":"user","content":[
              {"type":"text","text":"看下这张图"},
              {"type":"image","mediaType":"image/png","data":"AAAA"}
            ]}}
            """.trimIndent(),
        )
        val content = Json.messageContent(data)
        assertEquals("看下这张图\n[图片]", Json.contentText(content))
        assertEquals(listOf("data:image/png;base64,AAAA"), Json.contentImages(content))
    }

    @Test
    fun parsesSearchHitAsRowWithSnippet() {
        val value = JSONObject(
            """{"items":[{"sessionId":"s3","snippet":"匹配到的内容"}],"hasMore":false}""",
        )
        val rows = Json.parseSessions(value)
        assertEquals(1, rows.size)
        assertEquals("s3", rows[0].id)
        assertEquals("匹配到的内容", rows[0].preview)
    }

    @Test
    fun requestArgSupportsBothNamingConventions() {
        val modern = JSONObject().put("request", JSONObject().put("sessionId", "s1"))
        assertEquals("s1", modern.requestArg().strOrNull("sessionId"))
        val legacy = JSONObject().put("_request", JSONObject().put("sessionId", "s2"))
        assertEquals("s2", legacy.requestArg().strOrNull("sessionId"))
    }
}
