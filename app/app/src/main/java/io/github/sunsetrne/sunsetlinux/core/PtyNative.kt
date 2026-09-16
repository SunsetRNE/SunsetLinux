package io.github.sunsetrne.sunsetlinux.core

/**
 * 原生 PTY（终端底座）的 JNI 桥。
 *
 * 实现在 `app/app/src/main/cpp/pty.c`（为什么必须原生、与 Termux 的对应关系都写在那里）。
 * 编译由 `tools/ndk-build-pty.sh` 驱动（AGP 的 externalNativeBuild 在 aarch64 宿主上跑不了，
 * 因为官方 NDK 只发布 x86_64 宿主工具链）。
 *
 * **可用性是运行期判定的**：[available] 为 false 时（比如某个自编译的 APK 没带 .so、
 * 或 ABI 不匹配）终端会退回到 `TerminalSession` 的行缓冲实现 —— 终端不能因为
 * 缺一个 .so 就完全不可用，这一点在 `TerminalPane` 里有明确分支。
 */
internal object PtyNative {

    /** 库名 → `libsunsetlinux_pty.so`。 */
    private const val LIB = "sunsetlinux_pty"

    /** 加载失败的原因（给用户看的一句话）；成功则为 null。 */
    val loadError: String? = try {
        System.loadLibrary(LIB)
        null
    } catch (t: Throwable) {
        "原生 PTY 不可用（${t.javaClass.simpleName}: ${t.message ?: "加载 $LIB 失败"}）"
    }

    val available: Boolean get() = loadError == null

    /**
     * 分配 PTY 并在从设备上 exec [cmd]。
     *
     * @return master fd（>=0）；失败会抛 RuntimeException（消息可直接展示）
     * @param pidOut 单元素数组，成功后写入子进程 pid
     */
    external fun open(
        cmd: String,
        cwd: String?,
        args: Array<String>,
        env: Array<String>,
        pidOut: IntArray,
        rows: Int,
        cols: Int,
    ): Int

    /** 窗口大小变化（vim/htop 会跟着重排）。 */
    external fun resize(fd: Int, rows: Int, cols: Int)

    /** 等子进程结束，返回退出码（被信号杀死 → 128+signo）。 */
    external fun waitFor(pid: Int): Int

    /** 关掉 master fd（内核会向前台进程组发 SIGHUP，这就是"正常结束会话"）。 */
    external fun closeFd(fd: Int)

    /** 兜底发信号（SIGHUP 之后还不退就 SIGKILL）。 */
    external fun signal(pid: Int, sig: Int)
}
