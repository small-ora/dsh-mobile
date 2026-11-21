package com.smallora.dshmobile.core

import kotlinx.coroutines.runBlocking
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Test

/** 新建会话携带工作区 + 归档会话过滤（workspace/follow / archiveSession 联动）。 */
class WorkspaceRepositoryTest {

    private class FakeApi : ApiClient() {
        var lastCreateRequest: JSONObject? = null
        override suspend fun rpc(method: String, args: JSONObject): JSONObject {
            if (method == "session/create") {
                lastCreateRequest = args.objOrNull("request")
                return JSONObject().put("sessionId", "new-1")
            }
            return JSONObject()
        }
    }

    private fun row(id: String, cwd: String? = null, updatedAt: Long = 0L) = SessionRow(
        id = id,
        title = "会话-$id",
        preview = null,
        updatedAt = updatedAt,
        running = false,
        blank = false,
        parentSessionId = null,
        cwd = cwd,
        goal = null,
        todos = emptyList(),
        selection = null,
        agentAvailable = true,
    )

    @Test
    fun createSessionCarriesChosenWorkspaceId() = runBlocking {
        val api = FakeApi()
        val repo = SessionRepository(api)
        assertEquals("new-1", repo.createSession("w2"))
        assertEquals("w2", api.lastCreateRequest?.optString("workspaceId"))
    }

    @Test
    fun createSessionWithoutWorkspaceSendsNoWorkspaceId() = runBlocking {
        val api = FakeApi()
        val repo = SessionRepository(api)
        assertEquals("new-1", repo.createSession(null))
        assertEquals(false, api.lastCreateRequest?.has("workspaceId") ?: true)
    }

    @Test
    fun archivedSessionsAreHiddenFromListAndWorkspaces() {
        val repo = SessionRepository(ApiClient())
        repo.applySessionRows(
            listOf(
                row("a", "/root/w", updatedAt = 2),
                row("b", "/root/w", updatedAt = 1),
            ),
        )
        repo.applyArchivedSessionIds(setOf("a"))
        assertEquals(listOf("b"), repo.sessions.value.map { it.id })
        assertEquals(listOf("b"), repo.filteredSessions.value.map { it.id })
        assertEquals(listOf(WorkspaceRow("/root/w", "w", 1)), repo.workspaces.value)

        // 其他设备取消归档（workspace/follow archived 帧）后恢复展示
        repo.applyArchivedSessionIds(emptySet())
        assertEquals(listOf("a", "b"), repo.sessions.value.map { it.id })
        assertEquals(listOf(WorkspaceRow("/root/w", "w", 2)), repo.workspaces.value)
    }

    @Test
    fun archiveKeepsSessionHiddenAfterRefresh() {
        val repo = SessionRepository(ApiClient())
        repo.applySessionRows(listOf(row("a", "/root/w"), row("b", "/root/w")))
        repo.applyArchivedSessionIds(setOf("a"))
        // 模拟刷新：session/list 仍会返回已归档会话，归档集合持续生效
        repo.applySessionRows(listOf(row("a", "/root/w"), row("b", "/root/w")))
        assertEquals(listOf("b"), repo.sessions.value.map { it.id })
    }
}
