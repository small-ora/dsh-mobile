package com.smallora.dshmobile.core

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import org.json.JSONArray
import org.json.JSONObject
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicBoolean

/**
 * 会话状态仓库（core 唯一持有网络/流状态的地方；UI 只订阅 StateFlow）。
 *
 * 事件通道：$events 一个 StreamPump + 每个打开的会话一个 session/follow StreamPump，
 * 均为 HTTP 长轮询（SPEC v3，不依赖 WebSocket）。
 */
class SessionRepository(
    private val api: ApiClient,
    // store 当前未被仓库使用；保留契约构造签名（可为 null 以便 JVM 单测注入假 ApiClient）
    @Suppress("UNUSED_PARAMETER") private val store: SettingsStore? = null,
) {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    private val _sessions = MutableStateFlow<List<SessionRow>>(emptyList())
    val sessions: StateFlow<List<SessionRow>> = _sessions.asStateFlow()

    private val _connection = MutableStateFlow(ConnState(false, null, null))
    val connection: StateFlow<ConnState> = _connection.asStateFlow()

    private val _pending = MutableStateFlow<List<PendingRequest>>(emptyList())
    val pending: StateFlow<List<PendingRequest>> = _pending.asStateFlow()

    private val _modelCatalog = MutableStateFlow<List<ModelOption>>(emptyList())
    val modelCatalog: StateFlow<List<ModelOption>> = _modelCatalog.asStateFlow()

    private val chatStates = ConcurrentHashMap<String, MutableStateFlow<ChatUiState>>()
    private val followers = ConcurrentHashMap<String, StreamPump>()
    private val startedSessions = ConcurrentHashMap.newKeySet<String>()
    private val cursors = ConcurrentHashMap<String, Long>()
    private val minimumSeqs = ConcurrentHashMap<String, Long>()
    private val refreshing = AtomicBoolean(false)

    /** $events 单例泵的并发门闩：并发 ensureEvents 只创建/启动一次（避免开两条流，审查 P2）。 */
    private val eventsGate = SingleFlightGate { createEventsPump() }

    // ---- 对外：会话列表 / 连接 / 待处理 / 模型 ----

    suspend fun refresh() {
        ensureEvents()
        try {
            val value = api.rpc("session/list", JSONObject().put("_request", JSONObject()))
            _sessions.value = Json.parseSessions(value).sortedByDescending { it.updatedAt }
            _connection.value = ConnState(true, api.currentServer, null)
        } catch (e: UnauthorizedException) {
            _connection.value = ConnState(false, api.currentServer, e.message)
            throw e
        } catch (e: Exception) {
            _connection.value = ConnState(false, api.currentServer, e.message ?: "网络错误")
            throw e
        }
        runCatching {
            _modelCatalog.value = Json.parseModelCatalog(api.rpc("session/modelCatalog", JSONObject()))
        }
        runCatching {
            _pending.value = api.pending().sortedBy { it.at }
            syncPendingToChats()
        }
    }

    private suspend fun refreshSessionsOnly() {
        if (!refreshing.compareAndSet(false, true)) return
        try {
            val value = api.rpc("session/list", JSONObject().put("_request", JSONObject()))
            _sessions.value = Json.parseSessions(value).sortedByDescending { it.updatedAt }
        } catch (_: Exception) {
            // 后台静默刷新失败不打扰 UI
        } finally {
            refreshing.set(false)
        }
    }

    // ---- 会话操作 ----

    suspend fun createSession(): String? {
        return try {
            val value = api.rpc("session/create", JSONObject().put("request", JSONObject()))
            val id = value.strOrNull("sessionId") ?: value.strOrNull("id")
            if (id.isNullOrEmpty()) null else {
                runCatching { refreshSessionsOnly() }
                id
            }
        } catch (_: Exception) {
            null
        }
    }

    suspend fun rename(id: String, title: String): Boolean {
        return try {
            api.rpc(
                "session/rename",
                JSONObject().put(
                    "request",
                    JSONObject().put("sessionId", id).put("title", title),
                ),
            )
            updateRow(id) { it.copy(title = title) }
            updateChat(id) { it.copy(title = title) }
            true
        } catch (_: Exception) {
            false
        }
    }

    /** 归档：现代 DSH 无 session/archive，走 workspace/archiveSession（interfaces §4）。 */
    suspend fun archive(id: String): Boolean {
        return try {
            api.rpc(
                "workspace/archiveSession",
                JSONObject().put("request", JSONObject().put("sessionId", id)),
            )
            closeSession(id)
            _sessions.update { rows -> rows.filterNot { it.id == id } }
            true
        } catch (_: Exception) {
            false
        }
    }

    suspend fun search(q: String): List<SessionRow> {
        if (q.isBlank()) return emptyList()
        val value = api.rpc(
            "session/search",
            JSONObject().put("request", JSONObject().put("query", q)),
        )
        val items = value.arrOrNull("items") ?: return emptyList()
        val known = _sessions.value.associateBy { it.id }
        return items.toObjectList().mapNotNull { item ->
            val sessionId = item.strOrNull("sessionId") ?: return@mapNotNull null
            val snippet = item.strOrNull("snippet")
            val base = known[sessionId]
            if (base != null) {
                base.copy(preview = snippet ?: base.preview)
            } else {
                SessionRow(
                    id = sessionId,
                    title = item.strOrNull("title"),
                    preview = snippet,
                    updatedAt = item.longOr("updatedAt", 0L),
                    running = false,
                    blank = false,
                    parentSessionId = null,
                    cwd = null,
                    goal = null,
                    todos = emptyList(),
                    selection = null,
                    agentAvailable = false,
                )
            }
        }
    }

    // ---- 会话事件流 ----

    fun chatState(id: String): StateFlow<ChatUiState> {
        val flow = chatStates.computeIfAbsent(id) {
            MutableStateFlow(ChatReducer.initial(id))
        }
        val mine = _pending.value.filter { it.sessionId == id }
        if (mine.isNotEmpty()) flow.update { it.copy(pending = mine) }
        return flow
    }

    /** 打开会话：启动 $events（一次）+ session/follow（幂等，重复调用不叠加订阅）。 */
    suspend fun openSession(id: String) {
        ensureEvents()
        val row = _sessions.value.firstOrNull { it.id == id }
        if (row != null) {
            updateChat(id) {
                it.copy(
                    title = row.title ?: it.title,
                    goal = row.goal ?: it.goal,
                    todos = if (row.todos.isNotEmpty()) row.todos else it.todos,
                    selection = row.selection ?: it.selection,
                    running = row.running,
                )
            }
        }
        if (followers.containsKey(id) || !startedSessions.add(id)) return
        val args = JSONObject().put(
            "request",
            JSONObject()
                .put("address", JSONObject().put("kind", "session").put("sessionId", id))
                .put("assistantStream", true),
        )
        val pump = StreamPump(
            api = api,
            endpoint = "session/follow",
            args = args,
            onItem = { value -> handleFollow(id, value) },
            onState = { active ->
                if (!active) {
                    // 网络抖动仅记录，不打断已有内容
                    updateChat(id) { it.copy(error = it.error) }
                }
            },
        )
        followers[id] = pump
        pump.start()
    }

    /**
     * 释放会话 follow 流（非挂起，供 ChatViewModel.onCleared / DisposableEffect 同步调用）。
     * 幂等：重复调用、未打开过的会话都安全；不影响已缓存的 chatState（重进可立即显示）。
     */
    fun releaseSession(id: String) {
        startedSessions.remove(id)
        followers.remove(id)?.stop()
        cursors.remove(id)
        minimumSeqs.remove(id)
    }

    suspend fun closeSession(id: String) {
        releaseSession(id)
    }

    /** 测试/诊断：当前活跃的 session/follow 流数量（审查 P1：关闭后必须为 0）。 */
    internal fun activeFollowCount(): Int = followers.size

    /** 测试/诊断：$events 泵是否已创建。 */
    internal fun eventsStreamOpen(): Boolean = eventsGate.peek() != null

    /** 向上翻页（session/page；throughSeq 用 follow snapshot 的 cursor）。 */
    suspend fun loadOlder(id: String) {
        val state = chatState(id).value
        if (!state.hasMore || state.loadingOlder) return
        val cursor = cursors[id] ?: return
        updateChat(id) { it.copy(loadingOlder = true) }
        try {
            val request = JSONObject()
                .put("address", JSONObject().put("kind", "session").put("sessionId", id))
                .put("throughSeq", cursor)
                .put("maxMessages", 30)
            minimumSeqs[id]?.let { request.put("beforeSeq", it) }
            val value = api.rpc("session/page", JSONObject().put("request", request))
            val records = value.arrOrNull("records")
            updateChat(id) {
                ChatReducer.prependRecords(it, records, value.boolOr("hasMore", false))
            }
        } catch (e: Exception) {
            updateChat(id) { it.copy(loadingOlder = false, error = friendly(e)) }
        }
    }

    // ---- 发送 / 停止 / 回答 ----

    suspend fun send(id: String, text: String, images: List<String> = emptyList(), steer: Boolean = false) {
        val content = JSONArray()
        if (text.isNotEmpty()) {
            content.put(JSONObject().put("type", "text").put("text", text))
        }
        for (image in images) {
            decodeDataUrl(image)?.let { content.put(it) }
        }
        if (content.length() == 0) return
        val request = JSONObject()
            .put("requestId", UUID.randomUUID().toString())
            .put("sessionId", id)
            .put("mode", if (steer) "steer" else "queue")
            .put("content", content)
        api.rpc("session/prompt", JSONObject().put("request", request))
    }

    suspend fun cancel(id: String) {
        api.rpc("session/cancel", JSONObject().put("request", JSONObject().put("sessionId", id)))
    }

    suspend fun answer(eventId: String, payload: JSONObject): Boolean {
        return try {
            val accepted = api.respond(eventId, payload)
            removePending(eventId)
            accepted
        } catch (_: Exception) {
            false
        }
    }

    // ---- 目标（goals/*，需要 goal.id + revision） ----

    suspend fun goalPause(sessionId: String): Boolean = goalAction(sessionId, "pause", "paused")

    suspend fun goalResume(sessionId: String): Boolean = goalAction(sessionId, "resume", "active")

    suspend fun goalComplete(sessionId: String): Boolean = goalAction(sessionId, "complete", "complete")

    private suspend fun goalAction(sessionId: String, verb: String, phase: String): Boolean {
        val goal = chatState(sessionId).value.goal ?: return false
        return try {
            api.rpc(
                "goals/$verb",
                JSONObject()
                    .put("agentId", sessionId)
                    .put("ref", JSONObject().put("id", goal.id).put("revision", goal.revision)),
            )
            updateChat(sessionId) {
                it.copy(goal = goal.copy(phase = phase))
            }
            true
        } catch (_: Exception) {
            false
        }
    }

    // ---- 模型 / 分支 ----

    suspend fun setModel(id: String, selection: ModelSelection) {
        val request = JSONObject()
            .put("sessionId", id)
            .put("provider", selection.provider)
            .put("model", selection.model)
        selection.reasoningEffort?.let { request.put("reasoningEffort", it) }
        api.rpc("session/selectModel", JSONObject().put("request", request))
        updateChat(id) { it.copy(selection = selection) }
        updateRow(id) { it.copy(selection = selection) }
    }

    suspend fun fork(id: String, atSeq: Long? = null): String? {
        return try {
            val request = JSONObject().put("sessionId", id)
            atSeq?.let { request.put("atSeq", it) }
            val value = api.rpc("session/fork", JSONObject().put("request", request))
            val newId = value.strOrNull("sessionId") ?: value.strOrNull("id")
            runCatching { refreshSessionsOnly() }
            newId
        } catch (_: Exception) {
            null
        }
    }

    /** 退出登录/切换服务器时停止所有流并清空内存状态。 */
    fun stop() {
        eventsGate.peek()?.stop()
        eventsGate.clear()
        startedSessions.clear()
        for (pump in followers.values) pump.stop()
        followers.clear()
        cursors.clear()
        minimumSeqs.clear()
        _sessions.value = emptyList()
        _pending.value = emptyList()
        _modelCatalog.value = emptyList()
        _connection.value = ConnState(false, null, null)
        chatStates.clear()
    }

    // ---- $events ----

    private fun ensureEvents() {
        // 并发调用只创建一条 $events 流；重复调用是幂等的（审查 P2）
        eventsGate.get()
    }

    private fun createEventsPump(): StreamPump {
        val pump = StreamPump(
            api = api,
            endpoint = "\$events",
            args = JSONObject(),
            onItem = { value -> handleEvents(value) },
            onState = { active ->
                _connection.update {
                    it.copy(
                        online = active,
                        server = api.currentServer,
                        error = if (active) null else it.error,
                    )
                }
            },
            onReset = {
                // 重连后 pending 基线由上游 waterfall 重放，先清空避免残留
                _pending.value = emptyList()
                syncPendingToChats()
            },
        )
        pump.start()
        return pump
    }

    private fun handleEvents(value: JSONObject) {
        when (value.strOrNull("type")) {
            "ready" -> {
                _connection.value = ConnState(true, api.currentServer, null)
            }

            "waterfall" -> {
                Json.parseWaterfall(value)?.let { addPending(it) }
            }

            "cancel" -> {
                value.strOrNull("eventId")?.let { removePending(it) }
            }

            "emit" -> handleEmit(value.strOrNull("event") ?: "", value.arrOrNull("args"))

            "error" -> {
                _connection.update {
                    it.copy(
                        online = false,
                        error = value.objOrNull("error")?.strOrNull("message") ?: "事件流错误",
                    )
                }
            }
        }
    }

    private fun handleEmit(event: String, args: JSONArray?) {
        val id = args?.optString(0, "") ?: ""
        when (event) {
            "api-session/status" -> {
                val running = args?.optBoolean(1, false) ?: false
                updateRow(id) { it.copy(running = running) }
                updateChat(id) { it.copy(running = running) }
            }

            "api-session/added" -> {
                val summary = args?.optJSONObject(0)
                if (summary != null) {
                    Json.parseSessionRow(summary)?.let { upsertRow(it) }
                } else {
                    scope.launch { refreshSessionsOnly() }
                }
            }

            "api-session/removed" -> {
                _sessions.update { rows -> rows.filterNot { it.id == id } }
                startedSessions.remove(id)
                followers.remove(id)?.stop()
            }

            "api-session/activity" -> {
                val at = args?.optLong(1, 0L) ?: 0L
                if (at > 0) {
                    updateRow(id) { it.copy(updatedAt = at) }
                    resortSessions()
                }
            }

            "api-session/error" -> {
                val message = args?.optString(1, "") ?: ""
                if (message.isNotEmpty()) updateChat(id) { it.copy(error = message) }
            }
        }
    }

    // ---- session/follow ----

    private fun handleFollow(sessionId: String, value: JSONObject) {
        when (value.strOrNull("type")) {
            "snapshot" -> {
                val cursor = value.longOr("cursor", 0L)
                cursors[sessionId] = cursor
                var minSeq = cursor
                val records = value.arrOrNull("records")
                if (records != null) {
                    for (i in 0 until records.length()) {
                        val seq = records.optJSONObject(i)?.objOrNull("event")?.eventSeq() ?: -1L
                        if (seq >= 0 && (minSeq == cursor || seq < minSeq)) minSeq = seq
                    }
                }
                minimumSeqs[sessionId] = minSeq
                updateChat(sessionId) { ChatReducer.applySnapshot(it, value) }
                val title = chatState(sessionId).value.title
                if (title.isNotEmpty()) updateRow(sessionId) { it.copy(title = title) }
            }

            "event" -> {
                val event = value.objOrNull("event") ?: return
                val seq = event.eventSeq()
                if (seq >= 0) {
                    minimumSeqs.merge(sessionId, seq) { a, b -> minOf(a, b) }
                }
                updateChat(sessionId) { ChatReducer.applyEvent(it, event) }
                when (event.eventType()) {
                    "session/title" -> event.eventData().strOrNull("title")?.let { title ->
                        updateRow(sessionId) { it.copy(title = title) }
                    }

                    "turn/start" -> updateRow(sessionId) { it.copy(running = true) }

                    "turn/end" -> updateRow(sessionId) {
                        it.copy(running = false, updatedAt = System.currentTimeMillis())
                    }

                    "goal/change" -> {
                        val goal = chatState(sessionId).value.goal
                        updateRow(sessionId) { it.copy(goal = goal) }
                    }
                }
            }

            "assistant-stream" -> {
                value.objOrNull("frame")?.let { frame ->
                    updateChat(sessionId) { ChatReducer.applyAssistantStream(it, frame) }
                }
            }
        }
    }

    // ---- pending ----

    private fun addPending(request: PendingRequest) {
        _pending.update { list ->
            (list.filterNot { it.eventId == request.eventId } + request).sortedBy { it.at }
        }
        syncPendingToChats()
    }

    private fun removePending(eventId: String) {
        _pending.update { list -> list.filterNot { it.eventId == eventId } }
        syncPendingToChats()
    }

    private fun syncPendingToChats() {
        val all = _pending.value
        for ((id, flow) in chatStates) {
            val mine = all.filter { it.sessionId == id }
            flow.update { it.copy(pending = mine) }
        }
    }

    // ---- 内部工具 ----

    private fun updateChat(id: String, transform: (ChatUiState) -> ChatUiState) {
        chatStates.computeIfAbsent(id) { MutableStateFlow(ChatReducer.initial(id)) }
            .update(transform)
    }

    private fun updateRow(id: String, transform: (SessionRow) -> SessionRow) {
        _sessions.update { rows ->
            rows.map { row -> if (row.id == id) transform(row) else row }
        }
    }

    private fun upsertRow(row: SessionRow) {
        _sessions.update { rows ->
            val filtered = rows.filterNot { it.id == row.id }
            (filtered + row).sortedByDescending { it.updatedAt }
        }
    }

    private fun resortSessions() {
        _sessions.update { rows -> rows.sortedByDescending { it.updatedAt } }
    }

    private fun friendly(e: Exception): String = when (e) {
        is UnauthorizedException -> e.message ?: "登录已过期"
        is ApiException -> e.message ?: e.code
        else -> e.message ?: "操作失败"
    }

    private fun decodeDataUrl(url: String): JSONObject? {
        if (!url.startsWith("data:")) return null
        val comma = url.indexOf(',')
        if (comma <= 5) return null
        val header = url.substring(5, comma)
        val mediaType = header.substringBefore(';')
        val data = url.substring(comma + 1)
        if (mediaType.isEmpty() || data.isEmpty()) return null
        return JSONObject()
            .put("type", "image")
            .put("mediaType", mediaType)
            .put("data", data)
    }
}

/**
 * 线程安全单飞门闩：并发调用 [get] 只执行一次 [create]，且所有调用者拿到同一实例。
 * 用于保证 `$events` 泵在并发 refresh/openSession 下只创建一条流（独立审查 P2）。
 * `peek()` 用于停止时读取当前实例；`clear()` 用于退出登录/切换服务器后允许重建。
 */
internal class SingleFlightGate<T>(private val create: () -> T) {

    private val lock = Any()

    @Volatile
    private var value: T? = null

    fun get(): T = synchronized(lock) {
        value ?: create().also { value = it }
    }

    fun peek(): T? = value

    fun clear() {
        synchronized(lock) { value = null }
    }
}
