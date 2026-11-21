package com.smallora.dshmobile.core

import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** workspace/follow 帧解析 + 归档过滤（创建会话的工作区选择、归档会话隐藏都依赖它）。 */
class WorkspaceFramesTest {

    private fun baselineJson(): JSONObject = JSONObject(
        """
        {
          "items": [
            {"workspaceId": "w1", "title": "dsh-app", "path": "/root/dsh-app"},
            {"workspaceId": "w2", "title": "amazonProject", "path": "/root/amazonProject"}
          ],
          "archivedSessionIds": ["s-archived-1"]
        }
        """.trimIndent(),
    )

    @Test
    fun baselineParsesWorkspacesAndArchivedSet() {
        val snap = parseWorkspaceBaseline(baselineJson())!!
        assertEquals(2, snap.workspaces.size)
        assertEquals("dsh-app", snap.workspaces[0].title)
        assertEquals("/root/amazonProject", snap.workspaces[1].path)
        assertEquals(setOf("s-archived-1"), snap.archivedSessionIds)
        assertEquals("w2", snap.workspaceById("w2")!!.workspaceId)
    }

    @Test
    fun replaceFrameReplacesWholeSnapshot() {
        val current = parseWorkspaceBaseline(baselineJson())!!
        val frame = JSONObject().put("type", "replace").put("value", JSONObject().put("items", JSONArray()).put("archivedSessionIds", JSONArray()))
        val next = applyWorkspaceFrame(current, frame)!!
        assertTrue(next.workspaces.isEmpty())
        assertTrue(next.archivedSessionIds.isEmpty())
    }

    @Test
    fun archivedFrameUpdatesSetAndDrivesFiltering() {
        val current = parseWorkspaceBaseline(baselineJson())!!
        val next = applyWorkspaceFrame(
            current,
            JSONObject().put("type", "archived").put("archivedSessionIds", JSONArray(listOf("s1", "s2"))),
        )!!
        assertEquals(setOf("s1", "s2"), next.archivedSessionIds)
        assertTrue(isSessionArchived("s1", next.archivedSessionIds))
        assertFalse(isSessionArchived("s3", next.archivedSessionIds))
    }

    @Test
    fun upsertRemoveAndOrderFrames() {
        val base = parseWorkspaceBaseline(baselineJson())!!
        val upserted = applyWorkspaceFrame(
            base,
            JSONObject().put("type", "upsert").put(
                "workspace",
                JSONObject().put("workspaceId", "w3").put("title", "1").put("path", "/root/1"),
            ),
        )!!
        assertEquals(3, upserted.workspaces.size)
        assertEquals("w3", upserted.workspaces.last().workspaceId)

        val removed = applyWorkspaceFrame(upserted, JSONObject().put("type", "remove").put("workspaceId", "w1"))!!
        assertEquals(listOf("w2", "w3"), removed.workspaces.map { it.workspaceId })

        val ordered = applyWorkspaceFrame(
            removed,
            JSONObject().put("type", "order").put("workspaceIds", JSONArray(listOf("w3", "w2"))),
        )!!
        assertEquals(listOf("w3", "w2"), ordered.workspaces.map { it.workspaceId })
    }

    @Test
    fun unknownOrPinnedFramesAreIgnored() {
        val base = parseWorkspaceBaseline(baselineJson())!!
        assertNull(applyWorkspaceFrame(base, JSONObject().put("type", "pinned").put("pinnedSessionIds", JSONArray())))
        assertNull(applyWorkspaceFrame(base, JSONObject().put("type", "future-frame")))
        assertNull(applyWorkspaceFrame(base, null))
    }

    @Test
    fun archiveRpcResponseParsesArchivedSet() {
        val value = JSONObject().put("archivedSessionIds", JSONArray(listOf("a", "b")))
        assertEquals(setOf("a", "b"), parseArchivedSessionIds(value))
        assertNull(parseArchivedSessionIds(JSONObject()))
        assertNull(parseArchivedSessionIds(null))
    }

    @Test
    fun viewWithoutIdOrPathIsSkipped() {
        val value = JSONObject().put(
            "items",
            JSONArray(
                listOf(
                    JSONObject().put("workspaceId", "w1").put("path", "/root/x"),
                    JSONObject().put("workspaceId", "w2"), // 缺 path
                    JSONObject().put("path", "/root/y"), // 缺 id
                ),
            ),
        )
        val snap = parseWorkspaceBaseline(value)!!
        assertEquals(listOf("w1"), snap.workspaces.map { it.workspaceId })
        assertEquals("x", snap.workspaces.single().title) // title 缺省取目录名
    }
}
