package com.smallora.dshmobile.core

import kotlinx.coroutines.runBlocking
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 「对话」列表隐藏子 Agent 会话的回归测试（SPEC: .ai/hide-subagent-sessions/SPEC.md）。
 *
 * DSH `session/list` 会把子 Agent 的 child session 与人类根会话一起返回，
 * 子会话带 `origin: "subagent"`（且 `parentSessionId` 指向父会话）。
 * App 的会话列表 / 工作区计数 / 搜索都只能消费人类会话。
 *
 * 注意：`session/fork` 产生的分支会话同样带 `parentSessionId` 但**没有 origin**，
 * 它是人类要用的分支对话，必须继续展示（SPEC AC2）——不能用 parentSessionId 判定子 Agent。
 */
class SubagentSessionFilterTest {

    private fun row(
        id: String,
        parentSessionId: String? = null,
        origin: String? = null,
        cwd: String? = null,
        updatedAt: Long = 0L,
    ) = SessionRow(
        id = id,
        title = "会话-$id",
        preview = null,
        updatedAt = updatedAt,
        running = false,
        blank = false,
        parentSessionId = parentSessionId,
        cwd = cwd,
        goal = null,
        todos = emptyList(),
        selection = null,
        agentAvailable = true,
        origin = origin,
    )

    private fun subagent(id: String, parent: String = "root", cwd: String? = null, updatedAt: Long = 0L) =
        row(id, parentSessionId = parent, origin = "subagent", cwd = cwd, updatedAt = updatedAt)

    /** 模拟 session/fork 的分支会话：有父会话 id，但没有 origin。 */
    private fun forked(id: String, parent: String = "root", cwd: String? = null, updatedAt: Long = 0L) =
        row(id, parentSessionId = parent, cwd = cwd, updatedAt = updatedAt)

    private fun assertOnlyRoots(ids: List<String>, rows: List<SessionRow>) {
        assertEquals("子 Agent 会话不应出现在会话列表", ids, rows.map { it.id })
    }

    @Test
    fun subagentRowsAreHiddenFromConversationList() {
        val repo = SessionRepository(ApiClient())
        repo.applySessionRows(
            listOf(
                row("root", updatedAt = 3),
                subagent("child-a", updatedAt = 9),
                subagent("child-b", updatedAt = 8),
            ),
        )
        assertOnlyRoots(listOf("root"), repo.sessions.value)
        assertOnlyRoots(listOf("root"), repo.filteredSessions.value)
    }

    @Test
    fun forkedRowsRemainVisible() {
        // fork 分支会话（parentSessionId 非空、origin 缺失）是人类对话，不能被误隐藏
        val repo = SessionRepository(ApiClient())
        repo.applySessionRows(
            listOf(
                row("root", cwd = "/root/w", updatedAt = 1),
                forked("fork-1", cwd = "/root/w", updatedAt = 9),
                subagent("child", cwd = "/root/w", updatedAt = 8),
            ),
        )
        assertEquals(listOf("fork-1", "root"), repo.sessions.value.map { it.id })
        assertEquals(listOf("fork-1", "root"), repo.filteredSessions.value.map { it.id })
    }

    @Test
    fun subagentSessionsDoNotInflateWorkspaceCounts() {
        val repo = SessionRepository(ApiClient())
        repo.applySessionRows(
            listOf(
                row("root", cwd = "/root/w", updatedAt = 2),
                forked("fork", cwd = "/root/w", updatedAt = 6),
                subagent("child-a", cwd = "/root/w", updatedAt = 5),
                subagent("child-b", cwd = "/root/w", updatedAt = 4),
                subagent("child-c", updatedAt = 3),
            ),
        )
        // 工作区计数 = 人类会话（根 + fork），不含子 Agent 会话
        assertEquals(listOf(WorkspaceRow("/root/w", "w", 2)), repo.workspaces.value)
    }

    @Test
    fun rootSessionsKeepSortingAndWorkspaceFilter() {
        val repo = SessionRepository(ApiClient())
        repo.applySessionRows(
            listOf(
                row("root-a", cwd = "/root/a", updatedAt = 1),
                row("root-b", cwd = "/root/b", updatedAt = 3),
                subagent("child", parent = "root-a", cwd = "/root/a", updatedAt = 9),
            ),
        )
        // 子会话的 updatedAt 最大也不能排到列表最前
        assertOnlyRoots(listOf("root-b", "root-a"), repo.filteredSessions.value)

        repo.selectWorkspace("/root/a")
        assertOnlyRoots(listOf("root-a"), repo.filteredSessions.value)
    }

    @Test
    fun originSubagentIsParsedAndHiddenEvenWithoutParent() {
        // 防御上游字段演进：即使 parentSessionId 缺失，origin="subagent" 也必须隐藏（SPEC AC1）
        val value = JSONObject(
            """
            {"items": [
              {"sessionId": "root", "cwd": "/root/w", "updatedAt": 2},
              {"sessionId": "child", "origin": "subagent", "updatedAt": 9}
            ]}
            """.trimIndent(),
        )
        val rows = Json.parseSessions(value)
        assertEquals(2, rows.size)
        val root = rows.first { it.id == "root" }
        val child = rows.first { it.id == "child" }
        assertEquals("subagent", child.origin)
        assertTrue("origin=subagent 必须判定为子 Agent 会话", child.isSubagent)
        assertFalse("根会话不应判定为子 Agent 会话", root.isSubagent)
        assertNull("根会话 origin 缺失应为 null", root.origin)
        assertNull("根会话无父会话", root.parentSessionId)

        val repo = SessionRepository(ApiClient())
        repo.applySessionRows(rows)
        assertOnlyRoots(listOf("root"), repo.sessions.value)
        assertOnlyRoots(listOf("root"), repo.filteredSessions.value)
    }

    /** fake 上游：session/search 返回「根 + 子 Agent + 列表外冷会话」混合结果。 */
    private class SearchApi : ApiClient() {
        override suspend fun rpc(method: String, args: JSONObject): JSONObject {
            if (method == "session/search") {
                return JSONObject().put(
                    "items",
                    JSONArray(
                        """
                        [
                          {"sessionId": "root", "snippet": "命中根会话"},
                          {"sessionId": "child", "snippet": "命中子 Agent 会话"},
                          {"sessionId": "cold-root", "snippet": "列表外冷会话（保留）"}
                        ]
                        """.trimIndent(),
                    ),
                )
            }
            return JSONObject()
        }
    }

    @Test
    fun searchResultsExcludeSubagentSessions() = runBlocking {
        val repo = SessionRepository(SearchApi())
        repo.applySessionRows(
            listOf(
                row("root", updatedAt = 2),
                subagent("child", updatedAt = 3),
            ),
        )
        val hits = repo.search("命中")
        assertEquals(
            "搜索结果只保留人类会话（未知 id 的既有行为保留）",
            listOf("root", "cold-root"),
            hits.map { it.id },
        )
        assertTrue("根会话命中不能被误删", hits.first().preview == "命中根会话")
    }
}
