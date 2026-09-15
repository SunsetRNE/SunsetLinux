package io.dshroid.service

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import androidx.core.app.NotificationCompat
import io.dshroid.DshWebActivity
import io.dshroid.LauncherActivity
import io.dshroid.R
import io.dshroid.core.DshStatus
import io.dshroid.core.EnvMode
import io.dshroid.core.EnvState
import io.dshroid.core.formatBytes

/**
 * 常驻通知与通知渠道。
 *
 * 通知的四个 action（启动 / 停止 / 重启 / 打开 DSH）都通过 [LinuxService] 派发，
 * 这样即便 App 界面进程被杀，用户也能从通知栏控制环境。
 */
object Notifications {

    const val CHANNEL_STATUS = "dshroid.status"
    const val NOTIFICATION_ID = 0x4453 // "DS"

    fun ensureChannels(context: Context) {
        val manager = context.getSystemService(NotificationManager::class.java) ?: return
        val existing = manager.getNotificationChannel(CHANNEL_STATUS)
        if (existing != null) return
        val channel = NotificationChannel(
            CHANNEL_STATUS,
            context.getString(R.string.notif_channel_name),
            // LOW：状态栏常驻但不出声、不弹横幅，符合"常驻状态"的语义
            NotificationManager.IMPORTANCE_LOW,
        ).apply {
            description = context.getString(R.string.notif_channel_desc)
            setShowBadge(false)
            enableVibration(false)
            enableLights(false)
        }
        manager.createNotificationChannel(channel)
    }

    fun build(
        context: Context,
        mode: EnvMode,
        status: DshStatus?,
        transient: String? = null,
    ): Notification {
        val state = status?.state ?: EnvState.UNKNOWN

        val title = when {
            transient != null -> transient
            state == EnvState.RUNNING -> "DSH 环境运行中"
            state == EnvState.STARTING -> "DSH 环境正在启动"
            state == EnvState.STOPPING -> "DSH 环境正在停止"
            state == EnvState.ERROR -> "DSH 环境出错"
            state == EnvState.STOPPED -> "DSH 环境已停止"
            else -> "DSH 环境状态未知"
        }

        // transient 非空时，标题已经是那句临时提示，正文退化为"正在处理…"
        val detail: String? = if (transient != null) {
            null
        } else {
            buildList {
                add("模式 ${mode.modeLabel}")
                status?.displayUrl?.let { add(it) }
                status?.uptimeText?.let { add("已运行 $it") }
                status?.dshVersion?.let { add("v$it") }
                status?.upperUsed?.let { add("可写层 ${formatBytes(it)}") }
                status?.lastError?.takeIf { state == EnvState.ERROR || state == EnvState.UNKNOWN }?.let { add(it) }
            }.joinToString(" · ").takeIf { it.isNotEmpty() }
        }

        val contentIntent = PendingIntent.getActivity(
            context,
            1,
            Intent(context, LauncherActivity::class.java)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP),
            pendingFlags(),
        )

        val builder = NotificationCompat.Builder(context, CHANNEL_STATUS)
            .setSmallIcon(R.drawable.ic_notification)
            .setContentTitle(title)
            .setContentText(detail ?: "正在处理…")
            .setStyle(NotificationCompat.BigTextStyle().bigText(detail ?: "正在处理…"))
            .setContentIntent(contentIntent)
            .setOngoing(true)
            .setShowWhen(false)
            .setOnlyAlertOnce(true)
            .setCategory(NotificationCompat.CATEGORY_SERVICE)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .setForegroundServiceBehavior(NotificationCompat.FOREGROUND_SERVICE_IMMEDIATE)

        // 四个 action：与环境状态无关，用户随时可用（幂等）
        builder.addAction(
            R.drawable.ic_action_start,
            context.getString(R.string.notif_action_start),
            serviceIntent(context, LinuxService.ACTION_START, 10),
        )
        builder.addAction(
            R.drawable.ic_action_stop,
            context.getString(R.string.notif_action_stop),
            serviceIntent(context, LinuxService.ACTION_STOP, 11),
        )
        builder.addAction(
            R.drawable.ic_action_restart,
            context.getString(R.string.notif_action_restart),
            serviceIntent(context, LinuxService.ACTION_RESTART, 12),
        )
        builder.addAction(
            R.drawable.ic_action_open,
            context.getString(R.string.notif_action_open),
            PendingIntent.getActivity(
                context,
                13,
                Intent(context, DshWebActivity::class.java)
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
                pendingFlags(),
            ),
        )

        return builder.build()
    }

    private fun serviceIntent(context: Context, action: String, requestCode: Int): PendingIntent =
        PendingIntent.getService(
            context,
            requestCode,
            Intent(context, LinuxService::class.java).setAction(action),
            pendingFlags(),
        )

    private fun pendingFlags(): Int =
        PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
}
