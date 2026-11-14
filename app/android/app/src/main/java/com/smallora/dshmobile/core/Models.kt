package com.smallora.dshmobile.core

/**
 * App 核心数据模型（与 docs/interfaces.md §5.2 一致；UI 只读消费）。
 * 字段命名用英文，展示文案由 UI 层负责中文。
 */

data class ServerProfile(val id: String, val name: String, val base: String)

data class DeviceInfo(val id: String, val name: String)

data class HostInfo(val version: String?, val gatewayVersion: String?)

data class LoginResult(
    val token: String,
    val device: DeviceInfo,
    val mustChangePassword: Boolean,
    val host: HostInfo,
)

data class Goal(
    val id: String,
    val objective: String,
    val phase: String,
    val blockedReason: String?,
    val revision: Long,
)

data class Todo(val content: String, val status: String) // completed|pending|in_progress

data class ModelSelection(val provider: String, val model: String, val reasoningEffort: String?)

data class ModelOption(
    val provider: String,
    val model: String,
    val name: String,
    val reasoning: List<String>,
)

data class SessionRow(
    val id: String,
    val title: String?,
    val preview: String?,
    val updatedAt: Long,
    val running: Boolean,
    val blank: Boolean,
    val parentSessionId: String?,
    val cwd: String?,
    val goal: Goal?,
    val todos: List<Todo>,
    val selection: ModelSelection?,
    val agentAvailable: Boolean,
)

data class QuestionOption(val label: String, val description: String?)

data class Question(
    val id: String,
    val question: String,
    val header: String?,
    val detail: String?,
    val options: List<QuestionOption>,
    val multiSelect: Boolean,
)

data class PendingRequest(
    val kind: String, // "approval" | "question"
    val eventId: String,
    val sessionId: String,
    val toolName: String?,
    val reason: String?,
    val questions: List<Question>,
    val at: Long,
)

data class FileEntry(val name: String, val path: String, val dir: Boolean, val size: Long, val mtimeMs: Long)

data class FileListing(val path: String, val roots: List<String>, val entries: List<FileEntry>)

data class ConnState(val online: Boolean, val server: String?, val error: String?)

/** 工作区（= 会话 cwd 的不同目录）：App 会话列表按工作区筛选。 */
data class WorkspaceRow(val path: String, val name: String, val count: Int)

/**
 * 排队中的用户消息（inbox next-turn / next-step 合并；key 用作稳定列表 key）。
 * `id` 是 inbox UserMessage 的真实 id，用于插队/删除（可能缺失，缺失时不可操作）。
 */
data class QueuedMessage(
    val key: String,
    val text: String,
    val images: List<String>,
    val id: String? = null,
)

/** 权限预设选项（permissionPresets/catalog）。 */
data class PermissionOption(val value: String, val name: String)

/** 会话当前权限 + 可选项。 */
data class PermissionState(val current: String?, val options: List<PermissionOption>)

/** 斜杠指令（commands/list）。 */
data class CommandInfo(val name: String, val description: String, val hint: String?)

sealed interface ChatItem {
    val key: String

    data class User(
        override val key: String,
        val text: String,
        val images: List<String>,
    ) : ChatItem

    data class Assistant(
        override val key: String,
        val text: String,
        val reasoning: String?,
        val streaming: Boolean,
        val interrupted: Boolean,
    ) : ChatItem

    data class Tool(
        override val key: String,
        val name: String,
        val args: String,
        val result: String?,
        val isError: Boolean,
        val running: Boolean,
    ) : ChatItem

    data class Notice(
        override val key: String,
        val text: String,
        val kind: String,
    ) : ChatItem
}

data class ChatUiState(
    val sessionId: String,
    val title: String,
    val running: Boolean,
    val items: List<ChatItem>,
    val pending: List<PendingRequest>,
    val goal: Goal?,
    val todos: List<Todo>,
    val selection: ModelSelection?,
    val hasMore: Boolean,
    val loadingOlder: Boolean,
    val error: String?,
    /** 排队中的用户消息（inbox next-turn + next-step）。 */
    val queued: List<QueuedMessage> = emptyList(),
    /** 当前权限与可选项（permissions projection + permissionPresets/catalog）。 */
    val permission: PermissionState? = null,
    /** 可用斜杠指令（commands/list）。 */
    val commands: List<CommandInfo> = emptyList(),
)

/** 一次长轮询结果（interfaces §5.3）。items 为 (seq, value) 对。 */
data class StreamPoll(
    val items: List<Pair<Long, org.json.JSONObject>>,
    val next: Long,
    val closed: Boolean,
    val reset: Boolean,
    val error: String?,
)
