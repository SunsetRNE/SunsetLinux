package io.github.sunsetrne.sunsetd

import java.io.File

/**
 * 内核的路径与"外界"（文件、进程、时钟）——**全部走这里注入**，内核本体不直接碰系统。
 *
 * 为什么这么做：内核要能在**没有真机**的情况下被穷举单测（本仓的老规矩：能变红的判定
 * 一律抽成纯函数/可注入依赖）。真机上这些接口的实现是文件系统与 `ProcessBuilder`，
 * 单测里的实现是内存里的一张表。
 */
class RuntimeEnv(
    val linuxHome: String = System.getenv("LINUX_HOME")?.takeIf { it.isNotBlank() } ?: "/data/sunsetlinux",
) {
    val runDir: String get() = "$linuxHome/run"
    val stateFile: String get() = "$runDir/state.json"
    val heartbeatFile: String get() = "$runDir/heartbeat"
    val socketFile: String get() = "$runDir/control.sock"

    /**
     * 控制面**通道**诊断（`nio-unix` / `android-local` / `none`）。
     * 不是会话状态，是"这条通道在这台设备上选到了哪条路"——给 doctor 与交付验证看。
     */
    val transportFile: String get() = "$runDir/control-transport"

    /** 控制面回环自检结论：`running` / `ok:<通道名>` / `fail:<原因>`。 */
    val selfTestFile: String get() = "$runDir/control-selftest"

    // ── v1 世界的标记（内核**只读**它们：用来认领"别人起的会话"，而不是自己再推导一遍）
    val readyFile: String get() = "$runDir/ready"
    val supervisorPidFile: String get() = "$runDir/supervisor.pid"
    val startLockFile: String get() = "$runDir/start.lock"
    val envModeFile: String get() = "$runDir/env-mode"
    val lastErrorFile: String get() = "$runDir/last-error"
    val dshPidFile: String get() = "$runDir/dsh.pid"
    val dshPortFile: String get() = "$runDir/dsh.port"
    val dshUrlFile: String get() = "$runDir/dsh.url"
    val linuxctl: String get() = "$linuxHome/bin/linuxctl"
}

/** 落盘（状态 + 心跳）。原子写由实现负责（先 `.tmp` 再 rename）。 */
interface StateSink {
    fun writeState(json: String)
    fun writeHeartbeat(nowMs: Long)
    fun log(line: String)
}

/** 真机实现：写 `run/state.json`、`run/heartbeat`，日志追加到 `run/sunsetd.log`。 */
class FileStateSink(private val env: RuntimeEnv) : StateSink {
    override fun writeState(json: String) {
        File(env.runDir).mkdirs()
        val tmp = File(env.stateFile + ".tmp")
        tmp.writeText(json + "\n")
        if (!tmp.renameTo(File(env.stateFile))) {
            // rename 失败（跨设备/权限）时退化为直接写：宁可短暂非原子，也不要状态不更新
            File(env.stateFile).writeText(json + "\n")
            tmp.delete()
        }
    }

    override fun writeHeartbeat(nowMs: Long) {
        File(env.runDir).mkdirs()
        File(env.heartbeatFile).writeText("$nowMs\n")
    }

    override fun log(line: String) {
        File(env.runDir).mkdirs()
        File("${env.runDir}/sunsetd.log").appendText("$line\n")
    }
}

/**
 * 观测 v1 世界。内核**不重新实现**这些判据的语义，只是把它们收拢成一份快照。
 * （真正的清理是后续阶段的事：P1 的目标是"状态只由内核说"，而不是"重写脚本"。）
 */
interface WorldView {
    fun snapshot(): World
}

data class World(
    val ready: Boolean = false,
    val supervisorPid: Long? = null,
    val supervisorAlive: Boolean = false,
    val startLockHeld: Boolean = false,
    val startLockHolder: Long? = null,
    val envMode: String? = null,
    val lastError: String? = null,
    val dshRunning: Boolean = false,
    val dshPort: Int? = null,
) {
    /** 环境在跑：v1 契约里"就绪 + 持有者活着"。 */
    val envUp: Boolean get() = ready && supervisorAlive
    /** 有人正在建树（v1 的启动锁；我们自己的作业也会造成它）。 */
    val starting: Boolean get() = startLockHeld && !envUp
}

/** 真机实现：读 `run/` 下的标记 + `kill -0` 判活。 */
class FileWorld(private val env: RuntimeEnv) : WorldView {

    override fun snapshot(): World {
        val pid = readLong(env.supervisorPidFile)
        val lock = readLock(env.startLockFile)
        val lockedPid = lock?.first
        val dshPid = readLong(env.dshPidFile)
        val port = readLong(env.dshPortFile)?.toInt()
        return World(
            ready = File(env.readyFile).exists(),
            supervisorPid = pid,
            supervisorAlive = pid != null && alive(pid),
            startLockHeld = lockedPid != null && alive(lockedPid),
            startLockHolder = lockedPid,
            envMode = readText(env.envModeFile)?.trim()?.takeIf { it.isNotEmpty() },
            lastError = readText(env.lastErrorFile)?.trim()?.takeIf { it.isNotEmpty() },
            dshRunning = dshPid != null && alive(dshPid),
            dshPort = port,
        )
    }

    private fun readText(path: String): String? =
        try {
            val f = File(path)
            if (f.isFile) f.readText() else null
        } catch (_: Throwable) {
            null
        }

    private fun readLong(path: String): Long? =
        readText(path)?.trim()?.takeWhile { it.isDigit() }?.takeIf { it.isNotEmpty() }?.toLongOrNull()

    /** 启动锁的格式是 `<pid> <epoch>`（见 runtime/root/start.sh）。 */
    private fun readLock(path: String): Pair<Long, Long>? {
        val t = readText(path)?.trim() ?: return null
        val parts = t.split(Regex("\\s+")).filter { it.isNotEmpty() }
        val pid = parts.getOrNull(0)?.toLongOrNull() ?: return null
        val ts = parts.getOrNull(1)?.toLongOrNull() ?: 0L
        return pid to ts
    }

    private fun alive(pid: Long): Boolean =
        try {
            File("/proc/$pid").isDirectory
        } catch (_: Throwable) {
            false
        }
}

/** 作业的执行者（真机 = 起进程；单测 = 内存里的假执行者）。 */
interface JobRunner {
    /** 起一条命令，返回 pid；进程结束时回调退出码。 */
    fun spawn(cmd: List<String>, onExit: (Int) -> Unit): Long
    fun alive(pid: Long): Boolean
    fun kill(pid: Long)
}

class ProcessJobRunner : JobRunner {
    override fun spawn(cmd: List<String>, onExit: (Int) -> Unit): Long {
        val p = ProcessBuilder(cmd).redirectErrorStream(true).start()
        // 输出丢弃（内核不解析脚本 stdout：真相在 run/ 的标记里；日志由脚本自己写）
        Thread {
            try {
                p.inputStream.use { it.readBytes() }
            } catch (_: Throwable) {
            }
        }.apply { isDaemon = true }.start()
        Thread {
            val code = try {
                p.waitFor()
            } catch (_: Throwable) {
                -1
            }
            onExit(code)
        }.apply { isDaemon = true }.start()
        return p.pid()
    }

    override fun alive(pid: Long): Boolean = File("/proc/$pid").isDirectory

    override fun kill(pid: Long) {
        try {
            Runtime.getRuntime().exec(arrayOf("/system/bin/kill", "-TERM", "$pid")).waitFor()
        } catch (_: Throwable) {
        }
    }
}
