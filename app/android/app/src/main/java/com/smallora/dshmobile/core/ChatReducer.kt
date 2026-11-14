package com.smallora.dshmobile.core

import org.json.JSONArray
import org.json.JSONObject

/**
 * 聊天状态归一化（纯函数，可 JVM 单测）。
 * 输入：session/follow 的 snapshot / event / assistant-stream 帧；
 * 输出：新的 ChatUiState（不可变）。
 *
 * 去重策略：
 * - 事件项 key = "e:<seq>"（同一 seq 重复到达时跳过）；
 * - 工具项 key = "tool:<callId>"（tool/result 按 callId 回填）；
 * - 流式助手项 key = "stream"（assistant/message 定稿时移除）。
 */
object ChatReducer {

    const val STREAMING_KEY = "stream"

    fun initial(sessionId: String): ChatUiState = ChatUiState(
        sessionId = sessionId,
        title = "",
        running = false,
        items = emptyList(),
        pending = emptyList(),
        goal = null,
        todos = emptyList(),
        selection = null,
        hasMore = false,
        loadingOlder = false,
        error = null,
    )

    /** 分发 follow 流的 value（snapshot/event/assistant-stream）。 */
    fun applyValue(state: ChatUiState, value: JSONObject): ChatUiState {
        return when (value.strOrNull("type")) {
            "snapshot" -> applySnapshot(state, value)
            "event" -> value.objOrNull("event")?.let { applyEvent(state, it) } ?: state
            "assistant-stream" -> value.objOrNull("frame")?.let { applyAssistantStream(state, it) } ?: state
            else -> state
        }
    }

    fun applySnapshot(state: ChatUiState, snapshot: JSONObject): ChatUiState {
        val records = snapshot.arrOrNull("records")
        var next = state.copy(
            items = emptyList(),
            hasMore = snapshot.boolOr("hasMore", false),
        )
        // 投影状态（新协议在 projections.values，老形态直接在 projections）
        val values = snapshot.objOrNull("projections")?.projectionValues()
        if (values != null) {
            next = next.copy(
                title = values.strOrNull("title") ?: next.title,
                goal = Json.parseGoalFromProjection(values) ?: next.goal,
                todos = Json.parseTodos(values),
                selection = Json.parseSelectionFromProjection(values) ?: next.selection,
            )
        }
        if (records != null) {
            for (i in 0 until records.length()) {
                val record = records.optJSONObject(i) ?: continue
                val event = record.objOrNull("event")
                if (event != null) {
                    next = applyEvent(next, event)
                } else if (record.has("type") && !record.has("event")) {
                    // 少数实现把 event 内容直接放在 record 里
                    next = applyEvent(next, record)
                }
            }
        }
        // 重连时若仍有活跃的助手流，恢复其已累积文本（紧凑基线）
        val baseline = snapshot.objOrNull("assistantStream")
        val attempt = baseline?.objOrNull("activeAttempt")
        val stream = attempt?.arrOrNull("stream")
        if (stream != null) {
            for (i in 0 until stream.length()) {
                val chunkFrame = stream.optJSONObject(i) ?: continue
                val chunk = chunkFrame.objOrNull("chunk") ?: chunkFrame
                next = applyChunk(next, chunk)
            }
            next = next.markStreaming(true)
        }
        return next
    }

    fun applyEvent(state: ChatUiState, event: JSONObject): ChatUiState {
        val type = event.eventType()
        val seq = event.eventSeq()
        val data = event.eventData()
        val eventKey = if (seq >= 0) "e:$seq" else "e:${event.strOrNull("type")}:${event.longOr("time", 0L)}"

        return when {
            type == "user/message" -> {
                val source = data.objOrNull("source")
                val sourceKind = source?.strOrNull("kind")
                // 仅展示人类输入（注入上下文 source.kind != user 时忽略）
                if (sourceKind != null && sourceKind != "user") {
                    state
                } else {
                    val content = Json.messageContent(data)
                    val text = Json.contentText(content)
                    val images = Json.contentImages(content)
                    if (text.isBlank() && images.isEmpty()) {
                        state
                    } else {
                        state.withItem(ChatItem.User(eventKey, text, images))
                    }
                }
            }

            type == "assistant/message" -> {
                val content = Json.messageContent(data)
                var text = Json.contentText(content)
                var reasoning: String? = Json.contentReasoning(content)
                val rawStream = data.arrOrNull("stream")
                if (rawStream != null) {
                    val textBuilder = StringBuilder()
                    val reasoningBuilder = StringBuilder()
                    for (i in 0 until rawStream.length()) {
                        val record = rawStream.optJSONObject(i) ?: continue
                        val chunk = record.objOrNull("chunk") ?: record
                        when (chunk.strOrNull("type")) {
                            "text-delta" -> chunk.strOrNull("text")?.let { textBuilder.append(it) }
                            "reasoning-delta" -> chunk.strOrNull("text")?.let { reasoningBuilder.append(it) }
                        }
                    }
                    if (text.isBlank()) text = textBuilder.toString()
                    if (reasoningBuilder.isNotEmpty()) reasoning = reasoningBuilder.toString()
                }
                val finalItem = ChatItem.Assistant(
                    key = eventKey,
                    text = text,
                    reasoning = reasoning,
                    streaming = false,
                    interrupted = data.boolOr("interrupted", false),
                )
                state.removeStreaming().withItem(finalItem)
            }

            type == "assistant/attempt" -> {
                state.removeStreaming().withItem(
                    ChatItem.Notice(eventKey, "本次尝试未产生可见回复", "attempt"),
                )
            }

            type == "tool/call" -> {
                val callId = data.strOrNull("callId")
                val key = if (callId != null) "tool:$callId" else eventKey
                state.withItem(
                    ChatItem.Tool(
                        key = key,
                        name = data.strOrNull("name") ?: "tool",
                        args = data.strOrNull("arguments") ?: "",
                        result = null,
                        isError = false,
                        running = true,
                    ),
                )
            }

            type == "tool/result" -> {
                val message = data.objOrNull("message") ?: data
                val callId = message.strOrNull("toolCallId") ?: data.strOrNull("callId")
                val key = if (callId != null) "tool:$callId" else "tool:unknown:${eventKey}"
                val resultText = Json.contentText(Json.messageContent(data))
                val isError = message.boolOr("isError", false) || data.objOrNull("error") != null
                val existing = state.items.firstOrNull { it is ChatItem.Tool && it.key == key } as? ChatItem.Tool
                val updated = ChatItem.Tool(
                    key = key,
                    name = existing?.name ?: (data.strOrNull("name") ?: "tool"),
                    args = existing?.args ?: "",
                    result = resultText.ifBlank { existing?.result },
                    isError = isError,
                    running = false,
                )
                state.withItem(updated)
            }

            type == "turn/start" -> state.copy(running = true).removeStreaming()

            type == "turn/end" -> {
                // 真实帧 reason 是对象 {kind, error:{message,code}}，兼容旧的字符串形态
                val reasonObj = data.objOrNull("reason")
                val reasonKind = reasonObj?.strOrNull("kind") ?: data.strOrNull("reason") ?: "success"
                var next = state.copy(running = false).markStreaming(false)
                if (reasonKind != "success" && reasonKind != "completed") {
                    val detail = reasonObj?.objOrNull("error")?.strOrNull("message")
                        ?: reasonObj?.objOrNull("failure")?.strOrNull("message")
                        ?: reasonObj?.strOrNull("message")
                    val text = if (reasonKind == "error") {
                        "回合结束：错误" + (detail?.let { " — $it" } ?: "")
                    } else {
                        "回合结束：$reasonKind"
                    }
                    next = next.withItem(ChatItem.Notice(eventKey, text, "turn-end"))
                }
                next
            }

            type == "session/title" -> {
                val title = data.strOrNull("title") ?: state.title
                state.copy(title = title)
            }

            type == "goal/change" -> {
                val operation = data.strOrNull("operation")
                val goal = if (operation == "clear") null else Json.parseGoalObject(data.objOrNull("goal"))
                state.copy(goal = goal)
            }

            type == "todo/write" -> state.copy(todos = Json.parseTodos(data))

            type.startsWith("compaction/") -> state.withItem(
                ChatItem.Notice(eventKey, "整理上下文…", "compaction"),
            )

            type == "approval/asked" || type == "approval/decided" -> state

            type.startsWith("subagent/") -> state

            else -> state
        }
    }

    fun applyAssistantStream(state: ChatUiState, frame: JSONObject): ChatUiState {
        return when (frame.strOrNull("type")) {
            "start" -> state.removeStreaming().withItem(
                ChatItem.Assistant(
                    key = STREAMING_KEY,
                    text = "",
                    reasoning = null,
                    streaming = true,
                    interrupted = false,
                ),
            )

            "chunk" -> {
                val chunk = frame.objOrNull("chunk") ?: return state
                var next = applyChunk(state, chunk)
                // 若 chunk-start 之前没有 start 帧（重连等），补一个流式项
                if (next.items.none { it is ChatItem.Assistant && it.key == STREAMING_KEY }) {
                    next = next.withItem(
                        ChatItem.Assistant(STREAMING_KEY, "", null, true, false),
                    )
                    next = applyChunk(next, chunk)
                }
                next
            }

            "end" -> {
                // committed 后若落盘的是 assistant/attempt（无可见消息），移除流式占位项；
                // assistant/message 则保留到消息事件定稿。
                val outcome = frame.objOrNull("outcome")
                when (outcome?.strOrNull("kind")) {
                    "committed" -> {
                        if (outcome.strOrNull("eventType") == "assistant/attempt") {
                            state.removeStreaming()
                        } else {
                            state.markStreaming(false)
                        }
                    }

                    "abandoned" -> state.removeStreaming()
                    else -> state.markStreaming(false)
                }
            }

            else -> state
        }
    }

    private fun applyChunk(state: ChatUiState, chunk: JSONObject): ChatUiState {
        val type = chunk.strOrNull("type") ?: return state
        val text = chunk.strOrNull("text") ?: ""
        if (text.isEmpty() && type != "block-end") return state
        val item = state.items.firstOrNull { it is ChatItem.Assistant && it.key == STREAMING_KEY }
            as? ChatItem.Assistant
            ?: ChatItem.Assistant(STREAMING_KEY, "", null, true, false)
        val updated = when (type) {
            "text-delta" -> item.copy(text = item.text + text, streaming = true)
            "reasoning-delta" -> item.copy(reasoning = (item.reasoning ?: "") + text, streaming = true)
            else -> item
        }
        return state.withItem(updated)
    }

    fun ChatUiState.markStreaming(streaming: Boolean): ChatUiState {
        val item = items.firstOrNull { it is ChatItem.Assistant && it.key == STREAMING_KEY }
            as? ChatItem.Assistant ?: return this
        return copy(items = items.map { if (it === item) item.copy(streaming = streaming) else it })
    }

    private fun ChatUiState.removeStreaming(): ChatUiState {
        if (items.none { it is ChatItem.Assistant && it.key == STREAMING_KEY }) return this
        return copy(items = items.filterNot { it is ChatItem.Assistant && it.key == STREAMING_KEY })
    }

    /** 历史分页：把更早的记录插到最前面（按 seq 去重）。 */
    fun prependRecords(state: ChatUiState, records: JSONArray?, hasMore: Boolean): ChatUiState {
        if (records == null) return state.copy(hasMore = false, loadingOlder = false)
        var older = state.copy(items = emptyList())
        val parsed = ArrayList<ChatItem>()
        for (i in 0 until records.length()) {
            val record = records.optJSONObject(i) ?: continue
            val event = record.objOrNull("event") ?: continue
            val before = older
            older = applyEvent(older, event)
            // applyEvent 会重建列表；收集新增项（跳过 Notice 之外的重复）
            for (item in older.items) {
                if (before.items.none { it.key == item.key } && parsed.none { it.key == item.key }) {
                    parsed.add(item)
                }
            }
        }
        val existingKeys = state.items.map { it.key }.toSet()
        val merged = parsed.filterNot { it.key in existingKeys } + state.items
        return state.copy(items = merged, hasMore = hasMore, loadingOlder = false)
    }

    fun withPending(state: ChatUiState, pending: List<PendingRequest>): ChatUiState =
        state.copy(pending = pending)

    private fun ChatUiState.withItem(item: ChatItem): ChatUiState {
        val index = items.indexOfFirst { it.key == item.key }
        return if (index >= 0) {
            copy(items = items.toMutableList().also { it[index] = item })
        } else {
            copy(items = items + item)
        }
    }

    private fun ChatUiState.removeNotices(kind: String): ChatUiState {
        val filtered = items.filterNot { it is ChatItem.Notice && it.kind == kind }
        return if (filtered.size == items.size) this else copy(items = filtered)
    }
}
