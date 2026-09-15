package io.dshroid.service

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import io.dshroid.core.Prefs

/**
 * 开机自启。
 *
 * 注意这里**只拉起状态观察用的前台服务**，不会 `linuxctl start`：
 * - root 模式：环境由 KernelSU 模块在 `late_start` 自行启动（§6.1），App 无权干涉；
 * - proot 模式：环境随 App 进程，等用户打开 App 或点通知再启动更符合预期。
 *
 * Android 12+ 对「从后台启动前台服务」有限制，Android 14+ 对 `specialUse` 类型在开机
 * 场景也有限制，因此这里必须容忍失败：失败时什么都不做，等用户打开 App 时再拉服务。
 */
class BootReceiver : BroadcastReceiver() {

    override fun onReceive(context: Context, intent: Intent?) {
        val action = intent?.action ?: return
        if (action != Intent.ACTION_BOOT_COMPLETED &&
            action != Intent.ACTION_LOCKED_BOOT_COMPLETED &&
            action != Intent.ACTION_MY_PACKAGE_REPLACED
        ) {
            return
        }
        val prefs = Prefs(context)
        if (!prefs.bootStartService) return
        LinuxService.ensureRunning(context)
    }
}
