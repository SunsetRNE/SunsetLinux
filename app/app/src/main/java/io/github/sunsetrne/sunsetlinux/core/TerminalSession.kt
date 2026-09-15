package io.github.sunsetrne.sunsetlinux.core

import java.io.BufferedWriter
import java.io.File
import java.io.OutputStreamWriter
import java.util.concurrent.TimeUnit

/**
 * 环境内的**常驻交互会话**（App「终端」面板的后端）。
 *
 * 实现走 `linuxctl attach`：
 *   · root 模式 → `run_in_env 1` → `nsenter --mount/--uts … chroot … /bin/bash`
 *   · proot 模式 → `start.sh --inner -- /bin/bash -l`
 * 两条路都是把 shell **exec 进去**（见 runtime/{root,proot}/linuxctl.sh 的 cmd_attach），
 * 所以本进程的 stdin/stdout 就是那个 shell 的：我们只要「写一行、读一批行」。
 *
 * ## ⚠️ 没有 PTY —— 这是刻意的取舍，不是漏做
 * Android 上没有随系统可用的伪终端分配接口；自己引 PTY（JNI + forkpty）代价远大于收益。
 * 因此这里只能做到**行缓冲**：
 *   · 能用：`ls` / `cat` / `apt` / `npm` / `dsh` / 交互式 y/n 提示……
 *   · 不能用：`vim` / `htop` / `top` 这类**全屏程序**（它们要 termios 与窗口大小）
 *   · 没有作业控制：`Ctrl-C` / `fg` / 后台任务交互都不可用
 * 输入每次发送**一整行**（自动补 `\n`），回显由 shell 自己产生 ——
 * 所以 App 侧**不要**再本地回显一遍，否则会重复。
 */
internal class TerminalSession {

    private val lock = Any()
    private var process: Process? = null
    private var stdin: BufferedWriter? = null

    /** 新输出行（在 IO 线程回调）。 */
    var onLine: ((String) -> Unit)? = null

    /** 会话结束（退出码）。`stop()` 主动结束也会走这里。 */
    var onExit: ((Int) -> Unit)? = null

    val isRunning: Boolean
        get() = synchronized(lock) { process?.isAlive == true }

    /**
     * 启动会话。成功返回 `null`，失败返回**可直接展示给用户**的中文原因。
     *
     * 会先 `stop()` 掉上一个会话（幂等），避免重复点「重开」时留下多个 shell。
     */
    fun start(cmd: List<String>, cwd: File? = null, env: Map<String, String> = emptyMap()): String? {
        if (cmd.isEmpty()) return "内部错误：空命令"
        stop()
        val p = try {
            ProcessBuilder(cmd)
                .apply {
                    if (cwd != null) directory(cwd)
                    if (env.isNotEmpty()) environment().putAll(env)
                    // 终端里 stdout/stderr 混排才是正常的（用户看到的是"一个屏幕"）
                    redirectErrorStream(true)
                }
                .start()
        } catch (e: Exception) {
            return "无法启动终端会话：${e.message ?: e.javaClass.simpleName}"
        }
        synchronized(lock) {
            process = p
            stdin = BufferedWriter(OutputStreamWriter(p.outputStream))
        }

        Thread({
            try {
                p.inputStream.bufferedReader().forEachLine { line -> onLine?.invoke(line) }
            } catch (_: Throwable) {
                // 进程被 destroy 时读取会抛异常，属于正常路径
            }
        }, "sunsetlinux-terminal-reader").apply { isDaemon = true }.start()

        Thread({
            val code = try { p.waitFor() } catch (_: Throwable) { -1 }
            synchronized(lock) {
                if (process === p) {
                    process = null
                    stdin = null
                }
            }
            onExit?.invoke(code)
        }, "sunsetlinux-terminal-waiter").apply { isDaemon = true }.start()

        return null
    }

    /** 发送一整行（自动补换行）。返回是否送达。 */
    fun sendLine(text: String): Boolean {
        val w = synchronized(lock) { stdin } ?: return false
        return try {
            w.write(text)
            w.newLine()
            w.flush()
            true
        } catch (_: Throwable) {
            false
        }
    }

    /** 主动结束会话：先 TERM，1.5s 内没退就 KILL（避免 shell 忽略 TERM 留下孤儿进程）。 */
    fun stop() {
        val p = synchronized(lock) {
            val cur = process
            process = null
            stdin = null
            cur
        } ?: return
        try { p.destroy() } catch (_: Throwable) { /* 已退出 */ }
        Thread({
            try {
                if (!p.waitFor(1500, TimeUnit.MILLISECONDS)) p.destroyForcibly()
            } catch (_: Throwable) {
                // 已退出
            }
        }, "sunsetlinux-terminal-reaper").apply { isDaemon = true }.start()
    }
}
