package com.smallora.dshmobile.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 工作区列表/筛选单测（task-7）：
 * - 从会话 cwd 聚合去重计数、按 count 降序（同数按 name）；
 * - cwd 为空不计入；路径归一化（尾斜杠/根目录）；
 * - 选择工作区过滤 filteredSessions；
 * - 刷新后选择保持；所选 path 消失时回退「全部」。
 */
class WorkspaceSelectionTest {

    private fun row(id: String, cwd: String?, updatedAt: Long = id.hashCode().toLong()) = SessionRow(
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

    private fun repo(): SessionRepository = SessionRepository(ApiClient())

    @Test
    fun buildWorkspacesAggregatesAndSortsByCount() {
        val rows = listOf(
            row("s1", "/root/dsh-app"),
            row("s2", "/root/dsh-app"),
            row("s3", "/root/other"),
            row("s4", null),
            row("s5", ""),
        )
        val workspaces = buildWorkspaces(rows)
        assertEquals(2, workspaces.size)
        assertEquals(WorkspaceRow("/root/dsh-app", "dsh-app", 2), workspaces[0])
        assertEquals(WorkspaceRow("/root/other", "other", 1), workspaces[1])
    }

    @Test
    fun equalCountsSortedByName() {
        val workspaces = buildWorkspaces(
            listOf(row("1", "/z"), row("2", "/a"), row("3", "/m")),
        )
        assertEquals(listOf("a", "m", "z"), workspaces.map { it.name })
    }

    @Test
    fun pathNormalizationAndRootName() {
        assertEquals("/root/work", normalizeWorkspacePath("/root/work/"))
        assertEquals("/root/work", normalizeWorkspacePath("  /root/work  "))
        assertEquals("/", normalizeWorkspacePath("/"))
        assertEquals("", normalizeWorkspacePath(null))
        assertEquals("/", workspaceName("/"))
        assertEquals("work", workspaceName("/root/work"))

        val rows = listOf(row("1", "/root/work/"), row("2", "/root/work"))
        assertEquals(listOf(WorkspaceRow("/root/work", "work", 2)), buildWorkspaces(rows))
    }

    @Test
    fun repositoryExposesWorkspacesAndFiltersBySelection() {
        val repo = repo()
        repo.applySessionRows(
            listOf(
                row("a1", "/root/a", 3),
                row("a2", "/root/a", 2),
                row("b1", "/root/b", 1),
            ),
        )
        assertEquals(listOf("/root/a", "/root/b"), repo.workspaces.value.map { it.path })
        assertNull("初始为全部", repo.selectedWorkspace.value)
        assertEquals(3, repo.filteredSessions.value.size)

        repo.selectWorkspace("/root/a")
        assertEquals("/root/a", repo.selectedWorkspace.value)
        assertEquals(setOf("a1", "a2"), repo.filteredSessions.value.map { it.id }.toSet())

        repo.selectWorkspace(null)
        assertNull(repo.selectedWorkspace.value)
        assertEquals(3, repo.filteredSessions.value.size)
    }

    @Test
    fun selectionPersistsAcrossRefreshWhilePathExists() {
        val repo = repo()
        repo.applySessionRows(listOf(row("a1", "/root/a"), row("b1", "/root/b")))
        repo.selectWorkspace("/root/a")
        assertEquals(setOf("a1"), repo.filteredSessions.value.map { it.id }.toSet())

        // 刷新后仍有 /root/a：选择保持
        repo.applySessionRows(listOf(row("a1", "/root/a"), row("a2", "/root/a"), row("b1", "/root/b")))
        assertEquals("/root/a", repo.selectedWorkspace.value)
        assertEquals(setOf("a1", "a2"), repo.filteredSessions.value.map { it.id }.toSet())
    }

    @Test
    fun selectionFallsBackToAllWhenPathDisappears() {
        val repo = repo()
        repo.applySessionRows(listOf(row("a1", "/root/a"), row("b1", "/root/b")))
        repo.selectWorkspace("/root/b")
        assertEquals(setOf("b1"), repo.filteredSessions.value.map { it.id }.toSet())

        // 切换服务器/刷新后 /root/b 不存在 → 回退全部
        repo.applySessionRows(listOf(row("c1", "/root/c"), row("c2", "/root/c")))
        assertNull("所选工作区消失后应回退全部", repo.selectedWorkspace.value)
        assertEquals(listOf("/root/c"), repo.workspaces.value.map { it.path })
        assertEquals(setOf("c1", "c2"), repo.filteredSessions.value.map { it.id }.toSet())

        // 回退后再次选择也能工作
        repo.selectWorkspace("/root/c")
        assertEquals(setOf("c1", "c2"), repo.filteredSessions.value.map { it.id }.toSet())
    }

    @Test
    fun selectWithTrailingSlashMatchesNormalizedPath() {
        val repo = repo()
        repo.applySessionRows(listOf(row("w1", "/root/work"), row("o1", "/root/other")))
        repo.selectWorkspace("/root/work/")
        assertEquals("/root/work", repo.selectedWorkspace.value)
        assertEquals(setOf("w1"), repo.filteredSessions.value.map { it.id }.toSet())
    }

    @Test
    fun rowsWithoutCwdAreNeverWorkspaceMembers() {
        val repo = repo()
        repo.applySessionRows(listOf(row("n1", null), row("n2", "  "), row("w1", "/w")))
        assertEquals(1, repo.workspaces.value.size)
        assertNull(repo.selectedWorkspace.value)
        assertEquals(3, repo.filteredSessions.value.size)
        assertTrue(repo.workspaces.value.none { it.path.isEmpty() })
    }

    @Test
    fun filteredSessionsKeepsSessionOrder() {
        val repo = repo()
        repo.applySessionRows(
            listOf(
                row("old", "/w", updatedAt = 1),
                row("new", "/w", updatedAt = 3),
                row("mid", "/w", updatedAt = 2),
            ),
        )
        repo.selectWorkspace("/w")
        assertEquals(listOf("new", "mid", "old"), repo.filteredSessions.value.map { it.id })
    }
}
