package com.smallora.dshmobile.core

import org.json.JSONArray
import org.json.JSONObject

/**
 * 极简 org.json 包装 + DSH 事件/投影归一化解析。
 * 原则：宽松解析（未知字段忽略、缺失字段给默认值），未知事件不崩溃（SPEC §13 风险缓解）。
 */

// ---- 通用安全取值 ----

fun JSONObject.objOrNull(key: String): JSONObject? =
    if (has(key) && !isNull(key)) optJSONObject(key) else null

fun JSONObject.arrOrNull(key: String): JSONArray? =
    if (has(key) && !isNull(key)) optJSONArray(key) else null

fun JSONObject.strOrNull(key: String): String? {
    if (!has(key) || isNull(key)) return null
    val value = optString(key)
    return if (value.isEmpty() || value == "null") null else value
}

fun JSONObject.longOr(key: String, default: Long = 0L): Long {
    if (!has(key) || isNull(key)) return default
    return optLong(key, default)
}

fun JSONObject.doubleOr(key: String, default: Double = 0.0): Double {
    if (!has(key) || isNull(key)) return default
    return optDouble(key, default)
}

fun JSONObject.boolOr(key: String, default: Boolean = false): Boolean {
    if (!has(key) || isNull(key)) return default
    return optBoolean(key, default)
}

/** 投影块：新协议为 {kind,asOfSeq,values:{…}}，老/兼容形态直接是 values。 */
fun JSONObject.projectionValues(): JSONObject = objOrNull("values") ?: this

/** RPC 参数名兼容：DSH 现代协议多数方法叫 request，session/list 叫 _request。 */
fun JSONObject.requestArg(): JSONObject = objOrNull("request") ?: objOrNull("_request") ?: this

fun JSONArray.toObjectList(): List<JSONObject> {
    val out = ArrayList<JSONObject>(length())
    for (i in 0 until length()) {
        optJSONObject(i)?.let { out.add(it) }
    }
    return out
}

fun JSONArray.toStringList(): List<String> {
    val out = ArrayList<String>(length())
    for (i in 0 until length()) {
        if (isNull(i)) continue
        val value = optString(i)
        if (value.isNotEmpty()) out.add(value)
    }
    return out
}

object Json {

    fun obj(vararg pairs: Pair<String, Any?>): JSONObject {
        val out = JSONObject()
        for ((key, value) in pairs) out.put(key, value)
        return out
    }

    fun parseObject(text: String?): JSONObject? {
        if (text.isNullOrBlank()) return null
        return try {
            JSONObject(text)
        } catch (_: Exception) {
            null
        }
    }

    fun parseArray(text: String?): JSONArray? {
        if (text.isNullOrBlank()) return null
        return try {
            JSONArray(text)
        } catch (_: Exception) {
            null
        }
    }

    // ---- 会话列表 ----

    fun parseSessions(value: JSONObject?): List<SessionRow> {
        if (value == null) return emptyList()
        val items = value.arrOrNull("items") ?: value.arrOrNull("sessions") ?: value.arrOrNull("list")
        if (items != null) return items.toObjectList().mapNotNull { parseSessionRow(it) }
        // 某些兼容形态把 rows 直接放在 value 本身
        return if (value.has("sessionId") || value.has("id")) listOfNotNull(parseSessionRow(value)) else emptyList()
    }

    fun parseSessionRow(item: JSONObject): SessionRow? {
        val id = item.strOrNull("sessionId") ?: item.strOrNull("id") ?: return null
        val projections = item.objOrNull("projections")
        val values = projections?.projectionValues()
        val title = values?.strOrNull("title") ?: item.strOrNull("title")
        val preview = item.strOrNull("preview")
            ?: item.strOrNull("snippet")
            ?: values?.strOrNull("preview")
        val updatedAt = item.longOr("updatedAt", item.longOr("time", 0L))
        val todos = values?.let { parseTodos(it) } ?: parseTodos(item)
        val goal = values?.let { parseGoalFromProjection(it) }
        val selection = values?.let { parseSelectionFromProjection(it) }
        return SessionRow(
            id = id,
            title = title,
            preview = preview,
            updatedAt = updatedAt,
            running = item.boolOr("running", false),
            blank = item.boolOr("blank", false),
            parentSessionId = item.strOrNull("parentSessionId"),
            cwd = item.strOrNull("cwd"),
            goal = goal,
            todos = todos,
            selection = selection,
            agentAvailable = item.boolOr("agentAvailable", true),
        )
    }

    // ---- 模型目录 ----

    fun parseModelCatalog(value: JSONObject?): List<ModelOption> {
        if (value == null) return emptyList()
        val out = ArrayList<ModelOption>()
        val groups = value.arrOrNull("groups") ?: value.arrOrNull("providers")
        if (groups != null) {
            for (group in groups.toObjectList()) {
                val provider = group.strOrNull("id")
                    ?: group.strOrNull("provider")
                    ?: group.strOrNull("name")
                    ?: ""
                val models = group.arrOrNull("models") ?: group.arrOrNull("items") ?: JSONArray()
                for (model in models.toObjectList()) {
                    parseModelOption(provider, model)?.let { out.add(it) }
                }
            }
        }
        if (out.isEmpty()) {
            // 兼容扁平形态：value.items/models 每项自带 provider
            val flat = value.arrOrNull("items") ?: value.arrOrNull("models")
            if (flat != null) {
                for (model in flat.toObjectList()) {
                    val provider = model.strOrNull("provider") ?: model.strOrNull("groupId") ?: ""
                    parseModelOption(provider, model)?.let { out.add(it) }
                }
            }
        }
        return out
    }

    private fun parseModelOption(provider: String, model: JSONObject): ModelOption? {
        val id = model.strOrNull("id")
            ?: model.strOrNull("model")
            ?: model.strOrNull("name")
            ?: return null
        val reasoning = ArrayList<String>()
        val efforts = model.objOrNull("reasoning")?.arrOrNull("efforts")
            ?: model.arrOrNull("reasoning")
        if (efforts != null) {
            for (effort in efforts.toObjectList()) {
                val effortId = effort.strOrNull("id") ?: effort.strOrNull("name")
                if (!effortId.isNullOrEmpty()) reasoning.add(effortId)
            }
        }
        return ModelOption(
            provider = provider,
            model = id,
            name = model.strOrNull("name") ?: id,
            reasoning = reasoning,
        )
    }

    // ---- 审批 / 提问 ----

    fun parsePendingItems(items: JSONArray?): List<PendingRequest> {
        if (items == null) return emptyList()
        return items.toObjectList().mapNotNull { parsePendingItem(it) }
    }

    fun parsePendingItem(item: JSONObject): PendingRequest? {
        val eventId = item.strOrNull("eventId") ?: return null
        val sessionId = item.strOrNull("sessionId") ?: item.strOrNull("agentId") ?: ""
        val kind = item.strOrNull("kind") ?: if (item.has("questions")) "question" else "approval"
        return PendingRequest(
            kind = kind,
            eventId = eventId,
            sessionId = sessionId,
            toolName = item.strOrNull("toolName"),
            reason = item.strOrNull("reason"),
            questions = parseQuestions(item.arrOrNull("questions")),
            at = item.longOr("at", 0L),
        )
    }

    /** 解析 $events 的 waterfall 帧为待处理请求（approval/request | user-questions/request）。 */
    fun parseWaterfall(value: JSONObject): PendingRequest? {
        val event = value.strOrNull("event") ?: return null
        val eventId = value.strOrNull("eventId") ?: return null
        val sessionId = value.strOrNull("agentId") ?: value.strOrNull("sessionId") ?: ""
        val request = value.objOrNull("request") ?: JSONObject()
        val kind = when {
            event.contains("approval") -> "approval"
            event.contains("question") -> "question"
            else -> return null
        }
        val displayReason = request.objOrNull("displayReason")
            ?.let { it.strOrNull("zh") ?: it.strOrNull("en") }
        return PendingRequest(
            kind = kind,
            eventId = eventId,
            sessionId = sessionId,
            toolName = request.strOrNull("toolName"),
            reason = request.strOrNull("reason") ?: displayReason,
            questions = parseQuestions(request.arrOrNull("questions")),
            at = value.longOr("at", System.currentTimeMillis()),
        )
    }

    fun parseQuestions(items: JSONArray?): List<Question> {
        if (items == null) return emptyList()
        return items.toObjectList().mapNotNull { item ->
            val id = item.strOrNull("id") ?: return@mapNotNull null
            val question = item.strOrNull("question") ?: ""
            val options = item.arrOrNull("options")?.toObjectList()?.mapNotNull { option ->
                val label = option.strOrNull("label") ?: return@mapNotNull null
                QuestionOption(label = label, description = option.strOrNull("description"))
            } ?: emptyList()
            Question(
                id = id,
                question = question,
                header = item.strOrNull("header"),
                detail = item.strOrNull("detail"),
                options = options,
                multiSelect = item.boolOr("multiSelect", false),
            )
        }
    }

    // ---- 投影 ----

    fun parseGoalFromProjection(values: JSONObject?): Goal? {
        val goalBlock = values?.objOrNull("goal") ?: return null
        val snapshot = goalBlock.objOrNull("goal") ?: goalBlock
        return parseGoalObject(snapshot)
    }

    fun parseGoalObject(obj: JSONObject?): Goal? {
        if (obj == null) return null
        val id = obj.strOrNull("id") ?: return null
        val blockedReason = obj.objOrNull("blockedReason")?.let {
            it.strOrNull("message") ?: it.strOrNull("code")
        }
        return Goal(
            id = id,
            objective = obj.strOrNull("objective") ?: "",
            phase = obj.strOrNull("phase") ?: "active",
            blockedReason = blockedReason,
            revision = obj.longOr("revision", 0L),
        )
    }

    fun parseTodos(values: JSONObject?): List<Todo> {
        val items = values?.arrOrNull("todos") ?: return emptyList()
        return items.toObjectList().mapNotNull { item ->
            val content = item.strOrNull("content") ?: return@mapNotNull null
            Todo(content = content, status = item.strOrNull("status") ?: "pending")
        }
    }

    fun parseSelectionFromProjection(values: JSONObject?): ModelSelection? {
        val block = values?.objOrNull("modelSelection") ?: return null
        return parseSelection(block.objOrNull("next") ?: block.objOrNull("lastUsed"))
    }

    fun parseSelection(obj: JSONObject?): ModelSelection? {
        if (obj == null) return null
        val provider = obj.strOrNull("provider") ?: return null
        val model = obj.strOrNull("model") ?: return null
        return ModelSelection(
            provider = provider,
            model = model,
            reasoningEffort = obj.strOrNull("reasoningEffort"),
        )
    }

    // ---- 消息内容块 ----

    fun messageContent(data: JSONObject?): JSONArray? {
        if (data == null) return null
        val message = data.objOrNull("message") ?: data
        return message.arrOrNull("content")
    }

    fun contentText(content: JSONArray?): String {
        if (content == null) return ""
        val parts = ArrayList<String>()
        for (i in 0 until content.length()) {
            val block = content.optJSONObject(i) ?: continue
            when (block.strOrNull("type")) {
                "text" -> block.strOrNull("text")?.let { parts.add(it) }
                "image" -> parts.add("[图片]")
                else -> block.strOrNull("text")?.let { parts.add(it) }
            }
        }
        return parts.joinToString("\n")
    }

    fun contentImages(content: JSONArray?): List<String> {
        if (content == null) return emptyList()
        val out = ArrayList<String>()
        for (i in 0 until content.length()) {
            val block = content.optJSONObject(i) ?: continue
            if (block.strOrNull("type") != "image") continue
            val data = block.strOrNull("data") ?: continue
            if (data.startsWith("data:")) {
                out.add(data)
            } else {
                val mediaType = block.strOrNull("mediaType") ?: "image/jpeg"
                out.add("data:$mediaType;base64,$data")
            }
        }
        return out
    }
}

/** DSH 事件信封：{type, seq, time, data}。 */
fun JSONObject.eventType(): String = strOrNull("type") ?: ""

fun JSONObject.eventSeq(): Long = longOr("seq", -1L)

fun JSONObject.eventData(): JSONObject = objOrNull("data") ?: JSONObject()
