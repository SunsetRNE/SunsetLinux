package io.dshroid

import android.app.Application
import io.dshroid.core.CrashHandler
import io.dshroid.service.Notifications

/**
 * 应用入口。
 *
 * 1. 建立通知渠道（前台服务要用）；
 * 2. 装全局崩溃处理器 —— 把**异常消息 + cause 链 + 40 帧栈**落到
 *    `filesDir/crash/` 下的 txt 文件，侧边栏「导出排障包」可以一键分享给开发者。
 * 刻意**不**在这里启动任何环境或服务：环境生命周期由 KernelSU 模块（root 模式）
 * 或用户显式操作（proot 模式）决定，进程启动不该有副作用。
 */
class DshApp : Application() {

    override fun onCreate() {
        super.onCreate()
        Notifications.ensureChannels(this)
        CrashHandler.install(this)
    }
}
