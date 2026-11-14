package com.smallora.dshmobile.ui.chat

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.net.Uri
import android.provider.OpenableColumns
import android.util.Base64
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import com.smallora.dshmobile.core.AppContainer
import com.smallora.dshmobile.core.ChatItem
import com.smallora.dshmobile.core.ChatUiState
import com.smallora.dshmobile.core.ModelOption
import com.smallora.dshmobile.core.ModelSelection
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.io.ByteArrayOutputStream

/**
 * 聊天页状态机：只做「UI 动作 → SessionRepository」的转发与少量展示态（草稿/附件/思考时长/提示）。
 * 会话本身的状态（items / pending / goal / todos / running / error）全部来自 repo.chatState(sessionId)。
 */
class ChatViewModel(
    val sessionId: String,
    private val container: AppContainer,
) : ViewModel() {

    private val repo = container.repo

    /** core 归一化后的聊天状态。 */
    val state: StateFlow<ChatUiState> = repo.chatState(sessionId)

    /** 可用模型目录（顶栏模型选择用）。 */
    val models: StateFlow<List<ModelOption>> = repo.modelCatalog

    private val _draft = MutableStateFlow("")
    val draft: StateFlow<String> = _draft.asStateFlow()

    /** 待发送图片（data URL，形如 data:image/jpeg;base64,…）。 */
    private val _attachments = MutableStateFlow<List<String>>(emptyList())
    val attachments: StateFlow<List<String>> = _attachments.asStateFlow()

    /** 生成中再次发送时：false=排队，true=插队。 */
    private val _steer = MutableStateFlow(false)
    val steer: StateFlow<Boolean> = _steer.asStateFlow()

    private val _busyEventIds = MutableStateFlow<Set<String>>(emptySet())
    val busyEventIds: StateFlow<Set<String>> = _busyEventIds.asStateFlow()

    private val _toast = MutableStateFlow<String?>(null)
    val toast: StateFlow<String?> = _toast.asStateFlow()

    /** 权限切换后的本地乐观值（core 的 chatState.permission.current 更新后自动清除）。 */
    private val _permissionOverride = MutableStateFlow<String?>(null)
    val permissionOverride: StateFlow<String?> = _permissionOverride.asStateFlow()

    /** 思考时长（key → 秒），仅对本次会话内观察到从 streaming→结束的条目有值。 */
    private val _thinkSeconds = MutableStateFlow<Map<String, Int>>(emptyMap())
    val thinkSeconds: StateFlow<Map<String, Int>> = _thinkSeconds.asStateFlow()

    private val thinkStartedAt = HashMap<String, Long>()

    init {
        viewModelScope.launch { observeReasoningDurations() }
        viewModelScope.launch {
            state.collect { ui ->
                val override = _permissionOverride.value ?: return@collect
                val current = ui.permission?.current
                if (current == null || current == override) _permissionOverride.value = null
            }
        }
    }

    /** 进入聊天页时确保 session/follow 流已打开（core 幂等：重复调用不叠加订阅）。 */
    fun open() {
        viewModelScope.launch {
            runCatching { repo.openSession(sessionId) }
                .onFailure { _toast.value = "连接会话失败：${it.message ?: "未知错误"}" }
        }
    }

    /** 离开聊天页释放该会话的 follow 流（返回 / 系统返回 / 切页都会触发）。 */
    fun close() {
        viewModelScope.launch { runCatching { repo.closeSession(sessionId) } }
    }

    // ------------------------------------------------------------ 输入

    fun onDraftChange(value: String) {
        _draft.value = value
    }

    fun setSteer(value: Boolean) {
        _steer.value = value
    }

    fun removeAttachment(index: Int) {
        _attachments.update { list -> list.filterIndexed { i, _ -> i != index } }
    }

    fun clearToast() {
        _toast.value = null
    }

    fun showToast(message: String) {
        _toast.value = message
    }

    /** 从系统选择器拿到图片后压缩成 data URL；单图上限约 2.5MB base64，最多 4 张。 */
    fun attachImages(context: Context, uris: List<Uri>) {
        if (uris.isEmpty()) return
        viewModelScope.launch {
            val room = (MAX_IMAGES - _attachments.value.size).coerceAtLeast(0)
            if (room == 0) {
                _toast.value = "最多添加 $MAX_IMAGES 张图片"
                return@launch
            }
            val encoded = withContext(Dispatchers.IO) {
                uris.take(room).mapNotNull { uri -> runCatching { encodeImage(context, uri) }.getOrNull() }
            }
            if (encoded.isEmpty()) {
                _toast.value = "图片读取失败"
                return@launch
            }
            if (encoded.size < uris.take(room).size) _toast.value = "部分图片过大或格式不支持，已跳过"
            _attachments.update { it + encoded }
        }
    }

    private fun encodeImage(context: Context, uri: Uri): String? {
        val displayName = queryDisplayName(context, uri)
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        context.contentResolver.openInputStream(uri)?.use { BitmapFactory.decodeStream(it, null, bounds) }
        if (bounds.outWidth <= 0 || bounds.outHeight <= 0) return null

        var sample = 1
        while (bounds.outWidth / sample > MAX_IMAGE_EDGE || bounds.outHeight / sample > MAX_IMAGE_EDGE) {
            sample *= 2
        }
        val options = BitmapFactory.Options().apply { inSampleSize = sample }
        val bitmap = context.contentResolver.openInputStream(uri)?.use {
            BitmapFactory.decodeStream(it, null, options)
        } ?: return null
        val out = ByteArrayOutputStream()
        try {
            bitmap.compress(Bitmap.CompressFormat.JPEG, 82, out)
        } finally {
            bitmap.recycle()
        }
        val base64 = Base64.encodeToString(out.toByteArray(), Base64.NO_WRAP)
        if (base64.length > MAX_IMAGE_BASE64) return null
        // displayName 不参与提示，仅用于将来扩展；保留查询以确保无副作用
        @Suppress("UNUSED_VARIABLE") val name = displayName
        return "data:image/jpeg;base64,$base64"
    }

    private fun queryDisplayName(context: Context, uri: Uri): String? = runCatching {
        context.contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)
            ?.use { cursor -> if (cursor.moveToFirst()) cursor.getString(0) else null }
    }.getOrNull()

    // ------------------------------------------------------------ 动作

    fun send() {
        val text = _draft.value.trim()
        val images = _attachments.value
        if (text.isEmpty() && images.isEmpty()) return
        val steer = _steer.value
        viewModelScope.launch {
            runCatching { repo.send(sessionId, text, images, steer) }
                .onSuccess {
                    _draft.value = ""
                    _attachments.value = emptyList()
                    _steer.value = false
                }
                .onFailure { _toast.value = "发送失败：${it.message ?: "未知错误"}" }
        }
    }

    fun cancel() {
        viewModelScope.launch {
            runCatching { repo.cancel(sessionId) }
                .onFailure { _toast.value = "停止失败：${it.message ?: "未知错误"}" }
        }
    }

    fun loadOlder() {
        viewModelScope.launch { runCatching { repo.loadOlder(sessionId) }.onFailure { _toast.value = "加载历史失败" } }
    }

    fun fork(onForked: (String?) -> Unit) {
        viewModelScope.launch {
            val id = runCatching { repo.fork(sessionId, null) }.getOrElse {
                _toast.value = "创建分支失败：${it.message ?: "未知错误"}"
                return@launch
            }
            if (id.isNullOrBlank()) {
                _toast.value = "创建分支失败"
                return@launch
            }
            onForked(id)
        }
    }

    fun answerApproval(eventId: String, allowOnce: Boolean) {
        if (eventId in _busyEventIds.value) return
        _busyEventIds.update { it + eventId }
        viewModelScope.launch {
            val outcome = JSONObject()
                .put("kind", "approval")
                .put("value", if (allowOnce) "allowed-once" else "rejected")
            val ok = runCatching { repo.answer(eventId, outcome) }.getOrDefault(false)
            if (!ok) _toast.value = "提交失败，请求可能已被处理"
            _busyEventIds.update { it - eventId }
        }
    }

    /** 数据类：一道问题的回答草稿。 */
    data class Answer(val id: String, val selected: List<String>, val custom: String?)

    fun answerQuestion(eventId: String, answers: List<Answer>) {
        if (eventId in _busyEventIds.value) return
        _busyEventIds.update { it + eventId }
        viewModelScope.launch {
            val array = JSONArray()
            answers.forEach { answer ->
                val item = JSONObject().put("id", answer.id).put("selected", JSONArray(answer.selected))
                if (!answer.custom.isNullOrBlank()) item.put("custom", answer.custom.trim())
                array.put(item)
            }
            val value = JSONObject().put("answers", array)
            val outcome = JSONObject().put("kind", "question").put("value", value)
            val ok = runCatching { repo.answer(eventId, outcome) }.getOrDefault(false)
            if (!ok) _toast.value = "提交失败，问题可能已失效"
            _busyEventIds.update { it - eventId }
        }
    }

    /** 切换权限：执行 `/permission <value>` 命令，成功后乐观更新 chip。 */
    fun setPermission(value: String) {
        viewModelScope.launch {
            val ok = runCatching { repo.runCommand(sessionId, "/permission $value") }.getOrDefault(false)
            if (ok) {
                _permissionOverride.value = value
                _toast.value = "权限已切换"
            } else {
                _toast.value = "切换权限失败"
            }
        }
    }

    fun selectModel(selection: ModelSelection) {
        viewModelScope.launch {
            runCatching { repo.setModel(sessionId, selection) }
                .onFailure { _toast.value = "切换模型失败：${it.message ?: "未知错误"}" }
        }
    }

    fun pauseGoal() = goalAction { repo.goalPause(sessionId) }
    fun resumeGoal() = goalAction { repo.goalResume(sessionId) }
    fun completeGoal() = goalAction { repo.goalComplete(sessionId) }

    private fun goalAction(action: suspend () -> Boolean) {
        viewModelScope.launch {
            val ok = runCatching { action() }.getOrDefault(false)
            if (!ok) _toast.value = "操作未生效，请稍后重试"
        }
    }

    // ------------------------------------------------------------ 思考时长

    private suspend fun observeReasoningDurations() {
        state.collect { ui ->
            val now = System.currentTimeMillis()
            var changed = false
            val next = _thinkSeconds.value.toMutableMap()
            ui.items.forEach { item ->
                if (item !is ChatItem.Assistant) return@forEach
                val tracking = thinkStartedAt.containsKey(item.key)
                if (item.streaming && !item.reasoning.isNullOrBlank() && !tracking) {
                    thinkStartedAt[item.key] = now
                } else if (!item.streaming && tracking) {
                    val started = thinkStartedAt.remove(item.key) ?: return@forEach
                    val seconds = ((now - started) / 1000L).toInt().coerceAtLeast(0)
                    next[item.key] = seconds
                    changed = true
                }
            }
            if (changed) _thinkSeconds.value = next
        }
    }

    override fun onCleared() {
        super.onCleared()
        // 兜底：ViewModel 真正销毁时同步释放 follow 流（DisposableEffect 已覆盖普通退出；
        // Activity 销毁时它的协程可能已取消，故用非挂起的 releaseSession）
        repo.releaseSession(sessionId)
    }

    companion object {
        private const val MAX_IMAGES = 4
        private const val MAX_IMAGE_EDGE = 1600
        private const val MAX_IMAGE_BASE64 = 3_500_000

        fun factory(container: AppContainer, sessionId: String): ViewModelProvider.Factory = viewModelFactory {
            initializer { ChatViewModel(sessionId, container) }
        }
    }
}
