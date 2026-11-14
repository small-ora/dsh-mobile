package com.smallora.dshmobile.core

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.os.Build
import androidx.core.app.NotificationCompat
import com.smallora.dshmobile.MainActivity
import com.smallora.dshmobile.R

/**
 * 通知（interfaces §6）：dsh_attention（审批/提问，HIGH+震动）、dsh_task（任务/后台，DEFAULT）。
 * 点击通知跳转到对应会话（MainActivity EXTRA_SESSION_ID）。
 */
object Notifier {

    const val CHANNEL_ATTENTION = "dsh_attention"
    const val CHANNEL_TASK = "dsh_task"
    const val EXTRA_SESSION_ID = "sessionId"

    private const val FOREGROUND_ID = 1001
    private const val RELOGIN_ID = 1002

    fun ensureChannels(context: Context) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return
        val manager = context.getSystemService(NotificationManager::class.java) ?: return
        manager.createNotificationChannel(
            NotificationChannel(
                CHANNEL_ATTENTION,
                "审批与提问",
                NotificationManager.IMPORTANCE_HIGH,
            ).apply {
                description = "需要你确认或回答时提醒"
                enableVibration(true)
            },
        )
        manager.createNotificationChannel(
            NotificationChannel(
                CHANNEL_TASK,
                "任务与后台",
                NotificationManager.IMPORTANCE_DEFAULT,
            ).apply {
                description = "任务结束与后台轮询状态"
            },
        )
    }

    fun notifyPending(context: Context, request: PendingRequest, sessionTitle: String? = null) {
        ensureChannels(context)
        val title = when (request.kind) {
            "approval" -> "需要审批"
            else -> "需要回答"
        }
        val subject = when (request.kind) {
            "approval" -> request.toolName?.let { "工具：$it" } ?: (request.reason ?: "有新的审批请求")
            else -> request.questions.firstOrNull()?.question ?: "有新的提问"
        }
        val text = sessionTitle?.let { "$it · $subject" } ?: subject
        val builder = NotificationCompat.Builder(context, CHANNEL_ATTENTION)
            .setSmallIcon(R.drawable.ic_notification)
            .setContentTitle(title)
            .setContentText(text)
            .setStyle(NotificationCompat.BigTextStyle().bigText(text))
            .setAutoCancel(true)
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .setCategory(NotificationCompat.CATEGORY_MESSAGE)
            .setContentIntent(contentIntent(context, request.sessionId, request.eventId.hashCode()))
        notify(context, request.eventId.hashCode(), builder.build())
    }

    fun notifyTaskDone(context: Context, sessionId: String, title: String?) {
        ensureChannels(context)
        val builder = NotificationCompat.Builder(context, CHANNEL_TASK)
            .setSmallIcon(R.drawable.ic_notification)
            .setContentTitle("任务完成")
            .setContentText(title ?: "会话已完成本轮任务")
            .setAutoCancel(true)
            .setPriority(NotificationCompat.PRIORITY_DEFAULT)
            .setContentIntent(contentIntent(context, sessionId, sessionId.hashCode()))
        notify(context, sessionId.hashCode(), builder.build())
    }

    fun notifyRelogin(context: Context) {
        ensureChannels(context)
        val builder = NotificationCompat.Builder(context, CHANNEL_ATTENTION)
            .setSmallIcon(R.drawable.ic_notification)
            .setContentTitle("登录已过期")
            .setContentText("请打开 DSH Mobile 重新登录")
            .setAutoCancel(true)
            .setContentIntent(contentIntent(context, null, RELOGIN_ID))
        notify(context, RELOGIN_ID, builder.build())
    }

    fun foregroundNotification(context: Context, text: String): Notification {
        ensureChannels(context)
        return NotificationCompat.Builder(context, CHANNEL_TASK)
            .setSmallIcon(R.drawable.ic_notification)
            .setContentTitle("DSH Mobile")
            .setContentText(text)
            .setOngoing(true)
            .setSilent(true)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .setContentIntent(contentIntent(context, null, FOREGROUND_ID))
            .build()
    }

    fun cancel(context: Context, id: Int) {
        context.getSystemService(NotificationManager::class.java)?.cancel(id)
    }

    private fun notify(context: Context, id: Int, notification: Notification) {
        context.getSystemService(NotificationManager::class.java)?.notify(id, notification)
    }

    private fun contentIntent(context: Context, sessionId: String?, requestCode: Int): PendingIntent {
        val intent = Intent(context, MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_CLEAR_TOP
            sessionId?.let { putExtra(EXTRA_SESSION_ID, it) }
        }
        return PendingIntent.getActivity(
            context,
            requestCode,
            intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
    }
}
