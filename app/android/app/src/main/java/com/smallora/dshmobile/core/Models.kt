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
    /** DSH `session/list` item.origin：子 Agent 会话为 "subagent"；人类根会话为 null。 */
    val origin: String? = null,
) {
    /**
     * 子 Agent 会话（`origin = "subagent"`）。
     *
     * 这类会话属于某个父会话的内部执行单元，不是人类对话：会话列表、工作区计数、
     * 搜索结果都必须排除它们（SPEC `.ai/hide-subagent-sessions/SPEC.md` AC1）。
     *
     * 注意：**不能用 `parentSessionId` 判定**——`session/fork` 产生的分支会话同样带父会话 id
     * 但没有 `origin`，它是人类要用的分支对话，必须继续展示（SPEC AC2）。
     */
    val isSubagent: Boolean
        get() = origin == "subagent"
}

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

/**
 * 聊天图片引用（真实 DSH 结构里图片不是内联 data URL，而是附件引用）：
 * - [DataUrl]：内联 base64 / data URL（旧数据/本地图片）；
 * - [Attachment]：网关附件（attachmentId 已去 `sha256:` 前缀），需调用
 *   [ApiClient.fetchAttachment] 按 sessionId 拉字节。
 */
sealed interface ImageRef {
    data class DataUrl(val url: String) : ImageRef

    data class Attachment(
        val sessionId: String,
        val attachmentId: String,
        val mediaType: String?,
        val name: String?,
    ) : ImageRef
}

sealed interface ChatItem {
    val key: String

    data class User(
        override val key: String,
        val text: String,
        val images: List<ImageRef>,
    ) : ChatItem

    data class Assistant(
        override val key: String,
        val text: String,
        val reasoning: String?,
        val streaming: Boolean,
        val interrupted: Boolean,
        val images: List<ImageRef> = emptyList(),
    ) : ChatItem

    data class Tool(
        override val key: String,
        val name: String,
        val args: String,
        val result: String?,
        val isError: Boolean,
        val running: Boolean,
        /** 工具结果里的图片（如截图/看图工具返回 image 块）；UI 在结果区渲染。 */
        val images: List<ImageRef> = emptyList(),
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
    /** 会话工作目录（snapshot.header.cwd 或 sessions 行 cwd）；Markdown 相对图片路径解析用。 */
    val cwd: String? = null,
)

/**
 * Markdown 图片 `![alt](src)` 解析结果（纯函数 [resolveMarkdownImageSrc] 产出）：
 * - [Remote]：http(s) 外链，直接交给图片加载器；
 * - [DataUrl]：内联 data URL，直接解码；
 * - [WorkspacePath]：工作区文件路径，可用 [ApiClient.fetchFile] 拉字节。
 */
sealed interface MarkdownImageSource {
    data class Remote(val url: String) : MarkdownImageSource
    data class DataUrl(val url: String) : MarkdownImageSource
    data class WorkspacePath(val path: String) : MarkdownImageSource
}

/** 一次长轮询结果（interfaces §5.3）。items 为 (seq, value) 对。 */
data class StreamPoll(
    val items: List<Pair<Long, org.json.JSONObject>>,
    val next: Long,
    val closed: Boolean,
    val reset: Boolean,
    val error: String?,
)
