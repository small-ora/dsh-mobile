package com.smallora.dshmobile.core

import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.concurrent.CopyOnWriteArrayList

/**
 * 排队消息 / 权限 / 斜杠指令数据层单测（task-10）：
 * - inbox(next-turn + next-step) 解析与合并、空/异常项跳过；
 * - permission projection + 进程级 catalog 缓存；
 * - commands/list 数组解析与缓存；
 * - runCommand 失败回退普通 send（fake ApiClient 记录 RPC 调用）。
 */
class QueuePermissionCommandTest {

    private class FakeApi : ApiClient() {
        val calls = CopyOnWriteArrayList<String>()
        val argsByMethod = HashMap<String, JSONObject>()
        var projections: JSONObject? = null
        var catalog: JSONObject? = null
        var commandList: JSONArray = JSONArray()
        var execute: () -> JSONObject = { JSONObject().put("executed", true) }
        var updateQueueThrows = false
        var updateQueueResponse: (JSONObject) -> JSONObject = { JSONObject() }

        override suspend fun rpc(method: String, args: JSONObject): JSONObject {
            calls.add(method)
            argsByMethod[method] = args
            return when (method) {
                "session/projections" -> projections ?: JSONObject()
                "permissionPresets/catalog" -> catalog ?: JSONObject()
                "commands/execute" -> execute()
                "session/updateQueue" -> {
                    if (updateQueueThrows) throw ApiException("queue-update-failed", "更新队列失败")
                    updateQueueResponse(args)
                }
                "session/prompt" -> JSONObject().put("ok", true)
                else -> JSONObject()
            }
        }

        override suspend fun rpcArray(method: String, args: JSONObject): JSONArray {
            calls.add(method)
            argsByMethod[method] = args
            return if (method == "commands/list") commandList else JSONArray()
        }

        fun count(method: String): Int = calls.count { it == method }
    }

    private fun projectionsWithInbox(): JSONObject = JSONObject(
        """
        {
          "kind": "sequenced",
          "asOfSeq": 12,
          "values": {
            "inbox": {
              "next-turn": [
                {"id": "m1", "role": "user", "content": [{"type": "text", "text": "排队一"}]},
                {"id": "m2", "role": "user", "content": [
                  {"type": "text", "text": "看图"},
                  {"type": "image", "mediaType": "image/png", "data": "AAAA"}
                ]},
                {"id": "empty", "role": "user", "content": []}
              ],
              "next-step": [
                {"role": "user", "content": [{"type": "text", "text": "下一步"}]}
              ]
            },
            "permissions": {"currentValue": "workspace-write"}
          }
        }
        """.trimIndent(),
    )

    private fun permissionCatalog(): JSONObject = JSONObject(
        """
        {"options": [
          {"value": "read-only", "name": "只读"},
          {"value": "workspace-write", "name": "工作区可写"},
          {"value": "danger-full-access", "name": "完全访问"}
        ], "defaultPreset": "workspace-write"}
        """.trimIndent(),
    )

    private fun commandList(): JSONArray = JSONArray(
        """
        [
          {"definitionId": "d1", "name": "compact", "description": "压缩上下文", "input": {"hint": "compact"}},
          {"definitionId": "d2", "name": "permission", "description": "切换权限", "input": {"hint": "/permission <preset>"}},
          {"definitionId": "d3", "name": "goal", "description": "目标"}
        ]
        """.trimIndent(),
    )

    // ------------------------------------------------------------ JSON 解析

    @Test
    fun parsesQueuedMessagesFromInbox() {
        val queued = Json.parseQueuedMessages(projectionsWithInbox().projectionValues())
        assertEquals(3, queued.size)
        assertEquals("m1", queued[0].key)
        assertEquals("m1", queued[0].id)
        assertEquals("排队一", queued[0].text)
        assertTrue(queued[0].images.isEmpty())
        assertEquals("m2", queued[1].id)
        assertEquals("看图", queued[1].text)
        assertEquals(listOf("data:image/png;base64,AAAA"), queued[1].images)
        // next-step 在 next-turn 之后；无 id 时用 section:index 兜底，且 id 为 null（不可操作）
        assertEquals("inbox:next-step:0", queued[2].key)
        assertNull(queued[2].id)
        assertEquals("下一步", queued[2].text)
    }

    @Test
    fun parsesPermissionStateAndOptions() {
        val values = projectionsWithInbox().projectionValues()
        val state = Json.parsePermissionState(values, permissionCatalog())
        assertEquals("workspace-write", state?.current)
        assertEquals(3, state?.options?.size)
        assertEquals(PermissionOption("read-only", "只读"), state?.options?.first())
        assertNull(Json.parsePermissionState(JSONObject(), null))
    }

    @Test
    fun parsesCommandsArray() {
        val commands = Json.parseCommands(commandList())
        assertEquals(3, commands.size)
        assertEquals("compact", commands[0].name)
        assertEquals("压缩上下文", commands[0].description)
        assertEquals("compact", commands[0].hint)
        assertEquals("/permission <preset>", commands[1].hint)
        assertNull(commands[2].hint)
        assertEquals(emptyList<CommandInfo>(), Json.parseCommands(JSONObject().optJSONArray("items")))
    }

    // ------------------------------------------------------------ Repository

    private fun repo(api: FakeApi): SessionRepository = SessionRepository(api)

    @Test
    fun refreshQueueUpdatesChatStateAndRequestsProjection() {
        val api = FakeApi().apply { projections = projectionsWithInbox() }
        val repo = repo(api)
        kotlinx.coroutines.runBlocking { repo.refreshQueue("s1") }

        val state = repo.chatState("s1").value
        assertEquals(3, state.queued.size)
        assertEquals("排队一", state.queued.first().text)
        assertEquals(
            "s1",
            api.argsByMethod["session/projections"]
                ?.objOrNull("request")?.strOrNull("sessionId"),
        )
    }

    @Test
    fun refreshPermissionUsesProcessWideCatalogCache() {
        val api = FakeApi().apply {
            projections = projectionsWithInbox()
            catalog = permissionCatalog()
        }
        val repo = repo(api)
        kotlinx.coroutines.runBlocking {
            repo.refreshPermission("s1")
            repo.refreshPermission("s2")
        }
        assertEquals(1, api.count("permissionPresets/catalog"))
        assertEquals("workspace-write", repo.chatState("s1").value.permission?.current)
        assertEquals("workspace-write", repo.chatState("s2").value.permission?.current)
    }

    @Test
    fun commandsLoadsAndCaches() {
        val api = FakeApi().apply { commandList = commandList() }
        val repo = repo(api)
        val loaded = kotlinx.coroutines.runBlocking { repo.commands("s1") }
        assertEquals(3, loaded.size)
        assertEquals(3, repo.chatState("s1").value.commands.size)
        assertEquals("s1", api.argsByMethod["commands/list"]?.strOrNull("agentId"))
    }

    @Test
    fun runCommandReportsSuccessAndFallback() {
        val api = FakeApi()
        val repo = repo(api)
        kotlinx.coroutines.runBlocking {
            assertTrue(repo.runCommand("s1", "/compact"))
            assertEquals("s1", api.argsByMethod["commands/execute"]?.strOrNull("agentId"))
            assertEquals("/compact", api.argsByMethod["commands/execute"]?.strOrNull("line"))

            api.execute = { throw ApiException("command-failed", "执行失败") }
            assertFalse("ok=false/异常应返回 false 供回退", repo.runCommand("s1", "/compact"))

            api.execute = { JSONObject().put("executed", false) }
            assertFalse("executed=false 应返回 false", repo.runCommand("s1", "/compact"))
        }
    }

    @Test
    fun sendExecutesKnownCommandWithoutPrompt() {
        val api = FakeApi().apply { commandList = commandList() }
        val repo = repo(api)
        kotlinx.coroutines.runBlocking {
            repo.commands("s1")
            repo.send("s1", "/compact")
        }
        assertEquals(1, api.count("commands/execute"))
        assertEquals(0, api.count("session/prompt"))
    }

    @Test
    fun sendFallsBackToPromptWhenCommandFails() {
        val api = FakeApi().apply {
            commandList = commandList()
            execute = { throw ApiException("command-failed", "执行失败") }
        }
        val repo = repo(api)
        kotlinx.coroutines.runBlocking {
            repo.commands("s1")
            repo.send("s1", "/compact")
        }
        assertEquals(1, api.count("commands/execute"))
        assertEquals(1, api.count("session/prompt"))
        val content = api.argsByMethod["session/prompt"]
            ?.objOrNull("request")?.arrOrNull("content")
        assertEquals("/compact", content?.optJSONObject(0)?.strOrNull("text"))
    }

    @Test
    fun sendUnknownSlashCommandGoesToPrompt() {
        val api = FakeApi().apply { commandList = commandList() }
        val repo = repo(api)
        kotlinx.coroutines.runBlocking {
            repo.commands("s1")
            repo.send("s1", "/not-a-command")
        }
        assertEquals(0, api.count("commands/execute"))
        assertEquals(1, api.count("session/prompt"))
    }

    @Test
    fun steerQueuedSendsUpdateQueueAndRefreshes() {
        val api = FakeApi().apply { projections = projectionsWithInbox() }
        val repo = repo(api)
        val ok = kotlinx.coroutines.runBlocking { repo.steerQueued("s1", "m1") }
        assertTrue(ok)
        val request = api.argsByMethod["session/updateQueue"]?.objOrNull("request")
        assertEquals("s1", request?.strOrNull("sessionId"))
        assertEquals("m1", request?.strOrNull("itemId"))
        assertEquals("steer", request?.objOrNull("action")?.strOrNull("kind"))
        // 成功后刷新队列（session/projections 被调用）
        assertTrue(api.count("session/projections") >= 1)
        assertEquals(3, repo.chatState("s1").value.queued.size)
    }

    @Test
    fun removeQueuedSendsRemoveActionAndRefreshes() {
        val api = FakeApi().apply { projections = projectionsWithInbox() }
        val repo = repo(api)
        val ok = kotlinx.coroutines.runBlocking { repo.removeQueued("s1", "m2") }
        assertTrue(ok)
        val request = api.argsByMethod["session/updateQueue"]?.objOrNull("request")
        assertEquals("s1", request?.strOrNull("sessionId"))
        assertEquals("m2", request?.strOrNull("itemId"))
        assertEquals("remove", request?.objOrNull("action")?.strOrNull("kind"))
        assertTrue(api.count("session/projections") >= 1)
    }

    @Test
    fun queueActionsReturnFalseAndKeepQueueOnFailure() {
        val api = FakeApi().apply { projections = projectionsWithInbox() }
        val repo = repo(api)
        kotlinx.coroutines.runBlocking { repo.refreshQueue("s1") }
        assertEquals(3, repo.chatState("s1").value.queued.size)

        api.updateQueueThrows = true
        val failedSteer = kotlinx.coroutines.runBlocking { repo.steerQueued("s1", "m1") }
        val failedRemove = kotlinx.coroutines.runBlocking { repo.removeQueued("s1", "m2") }
        assertFalse(failedSteer)
        assertFalse(failedRemove)
        // 失败不应触发额外刷新，旧队列保持
        assertEquals("失败不刷新队列", 1, api.count("session/projections"))
        assertEquals(3, repo.chatState("s1").value.queued.size)
    }

    @Test
    fun sendPlainTextRefreshQueueAfterQueueMode() {
        val api = FakeApi()
        val repo = repo(api)
        kotlinx.coroutines.runBlocking {
            repo.send("s1", "普通消息")
            goto@ do {
                // 刷新是同步 await 的；这里直接断言 prompt + projections 各一次
                break@goto
            } while (false)
        }
        assertEquals(1, api.count("session/prompt"))
        assertEquals(1, api.count("session/projections"))
        val request = api.argsByMethod["session/projections"]?.objOrNull("request")
        assertEquals("s1", request?.strOrNull("sessionId"))
    }
}
