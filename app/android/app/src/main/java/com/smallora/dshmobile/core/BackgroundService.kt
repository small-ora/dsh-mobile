package com.smallora.dshmobile.core

import android.app.Service
import android.content.Context
import android.content.Intent
import android.os.IBinder
import androidx.core.content.ContextCompat
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

/**
 * 前台服务（dataSync）：按设置间隔轮询 /api/pending，对新出现的审批/提问发系统通知。
 * 401 连续 3 次 → 停服并提示重新登录（interfaces §6）。
 */
class BackgroundService : Service() {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private var job: Job? = null
    private val notified = HashSet<String>()
    private var unauthorizedCount = 0

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        Notifier.ensureChannels(this)
        startForeground(FOREGROUND_ID, Notifier.foregroundNotification(this, "正在后台同步"))
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (job == null) {
            job = scope.launch { loop() }
        }
        return START_STICKY
    }

    private suspend fun loop() {
        while (scope.isActive) {
            val container = AppGraph.init(applicationContext)
            if (!container.hasSession()) {
                stopSelf()
                return
            }
            val intervalSec = container.store.bgIntervalSec
            if (intervalSec <= 0) {
                // 设置里关闭后台轮询：直接停服
                stopSelf()
                return
            }
            try {
                val pending = container.api.pending()
                val currentIds = pending.map { it.eventId }.toSet()
                notified.retainAll(currentIds)
                for (request in pending) {
                    if (notified.add(request.eventId)) {
                        Notifier.notifyPending(this, request)
                    }
                }
                unauthorizedCount = 0
            } catch (_: UnauthorizedException) {
                unauthorizedCount += 1
                if (unauthorizedCount >= 3) {
                    Notifier.notifyRelogin(this)
                    stopSelf()
                    return
                }
            } catch (_: CancellationException) {
                throw CancellationException()
            } catch (_: Exception) {
                // 网络错误忽略，下轮重试
            }
            delay(intervalSec.coerceAtLeast(30) * 1000L)
        }
    }

    override fun onDestroy() {
        job?.cancel()
        job = null
        scope.cancel()
        super.onDestroy()
    }

    companion object {
        const val FOREGROUND_ID = 1001

        fun start(context: Context) {
            runCatching {
                ContextCompat.startForegroundService(
                    context,
                    Intent(context, BackgroundService::class.java),
                )
            }
        }

        fun stop(context: Context) {
            context.stopService(Intent(context, BackgroundService::class.java))
        }
    }
}
