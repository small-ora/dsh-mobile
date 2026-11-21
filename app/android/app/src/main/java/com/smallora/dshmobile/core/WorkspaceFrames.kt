package com.smallora.dshmobile.core

import org.json.JSONArray
import org.json.JSONObject

/** DSH Workspace（workspace/follow 基线里的 WorkspaceView 子集）。 */
data class WorkspaceInfo(
    val workspaceId: String,
    val title: String,
    val path: String,
)

/** 工作区注册表 + 归档会话集合（workspace/follow 的本地快照）。 */
data class WorkspaceSnapshot(
    val workspaces: List<WorkspaceInfo> = emptyList(),
    val archivedSessionIds: Set<String> = emptySet(),
) {
    fun workspaceById(id: String): WorkspaceInfo? = workspaces.firstOrNull { it.workspaceId == id }
}

private fun parseWorkspaceView(item: JSONObject): WorkspaceInfo? {
    val id = item.strOrNull("workspaceId") ?: return null
    val path = item.strOrNull("path") ?: return null
    val title = item.strOrNull("title")?.takeIf { it.isNotBlank() }
        ?: path.trimEnd('/').substringAfterLast('/').ifBlank { path }
    return WorkspaceInfo(workspaceId = id, title = title, path = path)
}

private fun parseWorkspaceViews(array: JSONArray?): List<WorkspaceInfo> =
    array?.toObjectList()?.mapNotNull { parseWorkspaceView(it) } ?: emptyList()

private fun parseIdSet(array: JSONArray?): Set<String> =
    array?.toStringList()?.filter { it.isNotBlank() }?.toSet() ?: emptySet()

/** baseline / replace 帧的 value：完整 Workspace 快照（含归档集合）。 */
fun parseWorkspaceBaseline(value: JSONObject?): WorkspaceSnapshot? {
    if (value == null) return null
    return WorkspaceSnapshot(
        workspaces = parseWorkspaceViews(value.arrOrNull("items")),
        archivedSessionIds = parseIdSet(value.arrOrNull("archivedSessionIds")),
    )
}

/**
 * 应用一帧 workspace/follow：
 * - baseline / replace → 完整替换；
 * - upsert / remove / order → 局部更新 Workspace 列表；
 * - archived → 替换归档会话集合（决定会话是否出现在列表/搜索）；
 * - pinned 等未消费帧 → 返回 null（保持原状态）。
 */
fun applyWorkspaceFrame(current: WorkspaceSnapshot, frame: JSONObject?): WorkspaceSnapshot? {
    if (frame == null) return null
    return when (frame.strOrNull("type")) {
        "baseline", "replace" -> parseWorkspaceBaseline(frame.objOrNull("value"))
        "upsert" -> {
            val view = frame.objOrNull("workspace")?.let { parseWorkspaceView(it) } ?: return null
            current.copy(
                workspaces = current.workspaces.filterNot { it.workspaceId == view.workspaceId } + view,
            )
        }
        "remove" -> {
            val id = frame.strOrNull("workspaceId") ?: return null
            current.copy(workspaces = current.workspaces.filterNot { it.workspaceId == id })
        }
        "order" -> {
            val ids = frame.arrOrNull("workspaceIds")?.toStringList() ?: return null
            val byId = current.workspaces.associateBy { it.workspaceId }
            val ordered = ids.mapNotNull { byId[it] }
            val rest = current.workspaces.filterNot { it.workspaceId in ids }
            current.copy(workspaces = ordered + rest)
        }
        "archived" -> {
            val ids = frame.arrOrNull("archivedSessionIds") ?: return null
            current.copy(archivedSessionIds = parseIdSet(ids))
        }
        else -> null
    }
}

/**
 * `workspace/archiveSession` / `unarchiveSession` 的返回值是完整归档集合；
 * 解析失败返回 null（调用方可回退到本地移除）。
 */
fun parseArchivedSessionIds(value: JSONObject?): Set<String>? {
    val ids = value?.arrOrNull("archivedSessionIds") ?: return null
    return parseIdSet(ids)
}

/** 归档会话不应出现在会话列表 / 搜索 / 工作区计数里。 */
fun isSessionArchived(sessionId: String, archivedSessionIds: Set<String>): Boolean =
    sessionId in archivedSessionIds
