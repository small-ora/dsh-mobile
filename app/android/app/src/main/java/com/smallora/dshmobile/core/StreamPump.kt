package com.smallora.dshmobile.core

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import org.json.JSONObject

/**
 * 单条事件流的 HTTP 长轮询泵（interfaces §5.3）。
 *
 * 循环：open → poll(since,25s) → 分发 items → 更新 since；
 * 断线/closed 时按 1.5s→60s 指数退避重开；reset 时回调清空本地状态。
 * UI 不直接 poll，只订阅 SessionRepository 的 StateFlow。
 */
class StreamPump(
    private val api: ApiClient,
    private val endpoint: String,
    private val args: JSONObject,
    private val onItem: (JSONObject) -> Unit,
    private val onState: (Boolean) -> Unit = {},
    private val onReset: () -> Unit = {},
) {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    @Volatile
    private var streamId: String? = null

    @Volatile
    private var since: Long = 0L

    @Volatile
    private var stopped = false

    private var job: Job? = null

    private val _active = MutableStateFlow(false)
    val active: StateFlow<Boolean> = _active.asStateFlow()

    fun start() {
        if (job != null) return
        stopped = false
        job = scope.launch { loop() }
    }

    fun stop() {
        stopped = true
        job?.cancel()
        job = null
        val sid = streamId
        streamId = null
        if (sid != null) {
            scope.launch { runCatching { api.cancelStream(sid) } }
        }
        _active.value = false
        onState(false)
    }

    private suspend fun loop() {
        var backoffMs = 1_500L
        while (!stopped && scope.isActive) {
            try {
                val sid = ensureOpen()
                val poll = api.pollStream(sid, since, POLL_WAIT_MS)
                if (stopped) return
                if (poll.reset) {
                    since = poll.next
                    onReset()
                }
                var cursor = since
                for ((seq, value) in poll.items) {
                    if (stopped) return
                    onItem(value)
                    if (seq >= cursor) cursor = seq + 1
                }
                since = maxOf(poll.next, cursor)
                if (poll.closed) {
                    closeStream()
                    throw java.io.IOException(poll.error ?: "事件流已关闭")
                }
                if (backoffMs != 1_500L) backoffMs = 1_500L
                if (!_active.value) {
                    _active.value = true
                    onState(true)
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: UnauthorizedException) {
                // 登录失效：停止重试，交由 UI 引导重新登录
                _active.value = false
                onState(false)
                stopped = true
                return
            } catch (e: Exception) {
                if (stopped) return
                _active.value = false
                onState(false)
                closeStream()
                delay(backoffMs)
                backoffMs = (backoffMs * 2).coerceAtMost(MAX_BACKOFF_MS)
            }
        }
    }

    private suspend fun ensureOpen(): String {
        streamId?.let { return it }
        val id = api.openStream(endpoint, args)
        streamId = id
        since = 0L
        return id
    }

    private suspend fun closeStream() {
        val sid = streamId ?: return
        streamId = null
        runCatching { api.cancelStream(sid) }
    }

    companion object {
        const val POLL_WAIT_MS = 25_000L
        const val MAX_BACKOFF_MS = 60_000L
    }
}
