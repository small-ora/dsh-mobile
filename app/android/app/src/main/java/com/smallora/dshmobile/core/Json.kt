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

    // ---- 排队消息 / 权限 / 指令 ----

    /** 从 projections.values.inbox 解析排队消息：next-turn 在前、next-step 在后，空项跳过。 */
    fun parseQueuedMessages(values: JSONObject?): List<QueuedMessage> {
        if (values == null) return emptyList()
        val inbox = values.objOrNull("inbox") ?: return emptyList()
        val out = ArrayList<QueuedMessage>()
        for (section in listOf("next-turn", "next-step")) {
            val items = inbox.arrOrNull(section) ?: continue
            for (index in 0 until items.length()) {
                val item = items.optJSONObject(index) ?: continue
                val content = item.arrOrNull("content")
                    ?: item.objOrNull("message")?.arrOrNull("content")
                // 排队行由 UI 单独展示图片（[图片]），text 不拼占位符
                val text = contentPlainText(content)
                val images = contentImages(content)
                if (text.isBlank() && images.isEmpty()) continue
                out.add(
                    QueuedMessage(
                        key = item.strOrNull("id") ?: "inbox:$section:$index",
                        text = text,
                        images = images,
                    ),
                )
            }
        }
        return out
    }

    /** permissionPresets/catalog 的 options（value/name）。 */
    fun parsePermissionOptions(catalog: JSONObject?): List<PermissionOption> {
        val items = catalog?.arrOrNull("options") ?: return emptyList()
        val out = ArrayList<PermissionOption>()
        for (index in 0 until items.length()) {
            val item = items.optJSONObject(index) ?: continue
            val value = item.strOrNull("value") ?: item.strOrNull("id") ?: continue
            out.add(PermissionOption(value = value, name = item.strOrNull("name") ?: value))
        }
        return out
    }

    /** 从 projections.values.permissions + 进程级 catalog 组装 PermissionState。 */
    fun parsePermissionState(values: JSONObject?, catalog: JSONObject?): PermissionState? {
        if (values == null) return null
        val current = values.objOrNull("permissions")?.strOrNull("currentValue")
        val options = parsePermissionOptions(catalog)
        return if (current == null && options.isEmpty()) null else PermissionState(current, options)
    }

    /** commands/list 的 value 是数组：[{definitionId,name,description,input:{hint}}]。 */
    fun parseCommands(value: JSONArray?): List<CommandInfo> {
        if (value == null) return emptyList()
        val out = ArrayList<CommandInfo>()
        for (index in 0 until value.length()) {
            val item = value.optJSONObject(index) ?: continue
            val rawName = item.strOrNull("name") ?: item.strOrNull("definitionId") ?: continue
            val name = rawName.trim().trimStart('/')
            if (name.isEmpty()) continue
            out.add(
                CommandInfo(
                    name = name,
                    description = item.strOrNull("description") ?: "",
                    hint = item.objOrNull("input")?.strOrNull("hint"),
                ),
            )
        }
        return out
    }

    // ---- 消息内容块 ----

    fun messageContent(data: JSONObject?): JSONArray? {
        if (data == null) return null
        val message = data.objOrNull("message") ?: data
        return message.arrOrNull("content")
    }

    /** 仅可见文本块；`reasoning` 块属于思考过程，不能混入正文（否则思考会直接铺在对话里）。 */
    fun contentText(content: JSONArray?): String {
        if (content == null) return ""
        val parts = ArrayList<String>()
        for (i in 0 until content.length()) {
            val block = content.optJSONObject(i) ?: continue
            when (block.strOrNull("type")) {
                "text" -> block.strOrNull("text")?.let { parts.add(it) }
                "image" -> parts.add("[图片]")
                else -> Unit
            }
        }
        return parts.joinToString("\n")
    }

    /** 思考过程文本（`type == "reasoning"`），由 UI 默认折叠展示。 */
    fun contentReasoning(content: JSONArray?): String? {
        if (content == null) return null
        val parts = ArrayList<String>()
        for (i in 0 until content.length()) {
            val block = content.optJSONObject(i) ?: continue
            if (block.strOrNull("type") == "reasoning") {
                block.strOrNull("text")?.let { if (it.isNotBlank()) parts.add(it) }
            }
        }
        return parts.joinToString("\n").takeIf { it.isNotBlank() }
    }

    /** 仅拼接 text 块（图片由调用方单独展示，不插入 "[图片]" 占位）。 */
    fun contentPlainText(content: JSONArray?): String {
        if (content == null) return ""
        val parts = ArrayList<String>()
        for (i in 0 until content.length()) {
            val block = content.optJSONObject(i) ?: continue
            if (block.strOrNull("type") != "text") continue
            block.strOrNull("text")?.let { parts.add(it) }
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
