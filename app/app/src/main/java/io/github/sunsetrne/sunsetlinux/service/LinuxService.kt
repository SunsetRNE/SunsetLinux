package io.github.sunsetrne.sunsetlinux.service

import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import android.os.PowerManager
import androidx.core.app.ServiceCompat
import androidx.core.content.ContextCompat
import io.github.sunsetrne.sunsetlinux.DshWebActivity
import io.github.sunsetrne.sunsetlinux.LauncherActivity
import io.github.sunsetrne.sunsetlinux.core.CtlResult
import io.github.sunsetrne.sunsetlinux.core.DshRuntime
import io.github.sunsetrne.sunsetlinux.core.DshStatus
import io.github.sunsetrne.sunsetlinux.core.EnvMode
import io.github.sunsetrne.sunsetlinux.core.EnvState
import io.github.sunsetrne.sunsetlinux.core.LinuxCtl
import io.github.sunsetrne.sunsetlinux.core.Prefs
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/**
 * 常驻前台服务：**只负责观察与转达**。
 *
 * 设计要点（对应 docs/architecture.md §6 与需求）：
 * 1. root 模式下环境的启动/停止由 KernelSU 模块负责，与 App 进程解耦。
 *    因此本服务**只轮询 `status` 并刷新通知**，绝不因为「读不到运行状态」就停掉环境；
 *    只有用户显式点通知上的 启动/停止/重启 才会真正执行对应子命令。
 * 2. 持有 PARTIAL_WAKE_LOCK —— 但**只在环境确实 running 时持有**，
 *    否则一个常年常驻的 wakelock 只会白白耗电。
 * 3. `stopWithTask=false`：从最近任务划掉 App 不会打断状态观察。
 */
class LinuxService : Service() {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private var pollJob: Job? = null
    private var wakeLock: PowerManager.WakeLock? = null

    private lateinit var prefs: Prefs
    private var mode: EnvMode = EnvMode.ROOT
    private var lastStatus: DshStatus? = null

    /** 用户操作后的临时提示语，短暂覆盖通知标题。 */
    @Volatile
    private var transient: String? = null

    override fun onCreate() {
        super.onCreate()
        prefs = Prefs(this)
        Notifications.ensureChannels(this)
        startForegroundCompat(Notifications.build(this, mode, null, "正在读取环境状态…"))
        startPolling()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_START -> runCommand("正在启动环境…") { ctl -> ctl.start() }
            ACTION_STOP -> runCommand("正在停止环境…") { ctl -> ctl.stop() }
            ACTION_RESTART -> runCommand("正在重启环境…") { ctl -> ctl.restart() }
            ACTION_OPEN -> openWeb()
            ACTION_SYNC, null -> startPolling()
        }
        // START_STICKY：被系统回收后重建，继续做状态观察
        return START_STICKY
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onDestroy() {
        pollJob?.cancel()
        scope.cancel()
        releaseWakeLock()
        super.onDestroy()
    }

    // ------------------------------------------------------------ 轮询

    private fun startPolling() {
        if (pollJob?.isActive == true) return
        pollJob = scope.launch {
            while (true) {
                refreshOnce()
                delay(POLL_INTERVAL_MS)
            }
        }
    }

    private suspend fun refreshOnce() {
        try {
            val choice = DshRuntime.resolveMode(this, prefs)
            mode = choice.mode
            val status = LinuxCtl(this, mode).status()
            lastStatus = status

            val text = transient
            if (text != null) {
                // 临时提示只显示一轮，之后回到正常状态文案
                transient = null
                updateNotification(Notifications.build(this, mode, status, text))
                return
            }
            updateNotification(Notifications.build(this, mode, status, null))
            syncWakeLock(status.state == EnvState.RUNNING)
        } catch (_: Throwable) {
            // 轮询本身绝不能把服务搞崩；下一轮会自动重试
        }
    }

    // ------------------------------------------------------------ 命令

    private fun runCommand(progressText: String, block: suspend (LinuxCtl) -> CtlResult) {
        scope.launch {
            transient = progressText
            updateNotification(Notifications.build(this@LinuxService, mode, lastStatus, progressText))
            val result = try {
                block(LinuxCtl(this@LinuxService, mode))
            } catch (t: Throwable) {
                CtlResult.fail(t.message ?: "操作失败")
            }
            transient = if (result.ok) "操作完成" else "操作失败：${result.message}"
            refreshOnce()
        }
    }

    private fun openWeb() {
        val url = lastStatus?.dshUrl
        val intent = if (!url.isNullOrBlank()) {
            Intent(this, DshWebActivity::class.java)
        } else {
            // 还没拿到带令牌的 URL（§3.3）→ 回到首页，那里会解释原因并继续轮询
            Intent(this, LauncherActivity::class.java)
        }
        intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        startActivity(intent)
    }

    // ------------------------------------------------------------ 通知 / 锁

    private fun updateNotification(notification: android.app.Notification) {
        val manager = getSystemService(android.app.NotificationManager::class.java) ?: return
        manager.notify(Notifications.NOTIFICATION_ID, notification)
    }

    private fun startForegroundCompat(notification: android.app.Notification) {
        val type = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE
        } else {
            0
        }
        try {
            ServiceCompat.startForeground(this, Notifications.NOTIFICATION_ID, notification, type)
        } catch (t: Throwable) {
            // Android 12+ 后台启动前台服务受限时会抛 ForegroundServiceStartNotAllowedException。
            // 这时不该崩：服务本身仍可短暂存活，界面侧会提示用户手动打开一次 App。
            stopSelf()
        }
    }

    private fun syncWakeLock(needed: Boolean) {
        if (needed) {
            if (wakeLock?.isHeld == true) return
            val pm = getSystemService(PowerManager::class.java) ?: return
            wakeLock = pm.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, WAKE_LOCK_TAG).apply {
                setReferenceCounted(false)
                acquire()
            }
        } else {
            releaseWakeLock()
        }
    }

    private fun releaseWakeLock() {
        wakeLock?.let { if (it.isHeld) runCatching { it.release() } }
        wakeLock = null
    }

    companion object {
        const val ACTION_START = "io.github.sunsetrne.sunsetlinux.action.START"
        const val ACTION_STOP = "io.github.sunsetrne.sunsetlinux.action.STOP"
        const val ACTION_RESTART = "io.github.sunsetrne.sunsetlinux.action.RESTART"
        const val ACTION_OPEN = "io.github.sunsetrne.sunsetlinux.action.OPEN"
        const val ACTION_SYNC = "io.github.sunsetrne.sunsetlinux.action.SYNC"

        private const val POLL_INTERVAL_MS = 5_000L
        private const val WAKE_LOCK_TAG = "sunsetlinux:linux-status"

        fun intent(context: Context, action: String): Intent =
            Intent(context, LinuxService::class.java).setAction(action)

        /** 幂等地把服务拉起来（已在前台运行则只是刷新）。 */
        fun ensureRunning(context: Context) {
            runCatching {
                ContextCompat.startForegroundService(context, intent(context, ACTION_SYNC))
            }
        }

        fun send(context: Context, action: String) {
            runCatching { context.startService(intent(context, action)) }
        }
    }
}
