package io.github.sunsetrne.sunsetlinux.core

import android.content.Context
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File

/**
 * 直接读写环境内的文件（`$LINUX_HOME/...`）。
 *
 * 只在**没有对应 linuxctl 子命令**时才用：
 * - 频道列表 `etc/channels.json`：契约里没有 `linuxctl channels` 子命令，而它属于
 *   「App 的配置面」（§2.1 明确列在环境根下），所以由 App 负责落盘；
 * - 日志一律走 `linuxctl logs`，**不**直接读 `run/linux.log`，更不读 `run/dsh.url`
 *   （那是 0600 的登录凭据，不进入 App 的界面层）。
 *
 * root 模式下写入用 `su -c 'cat > <file>'` 并把内容喂给 stdin —— 绝不把内容拼进命令行，
 * 这样既不触发参数长度限制，也不会有任何引号转义问题（内容里全是 JSON 引号）。
 */
object EnvFiles {

    /** 读环境内的文本文件（写 config.json 前要先读出来做合并，不能整体覆盖）。 */
    suspend fun readText(
        context: Context,
        mode: EnvMode,
        relativePath: String,
    ): CtlResult = withContext(Dispatchers.IO) {
        val path = File(DshPaths.linuxHome(context, mode), relativePath).absolutePath
        when (mode) {
            EnvMode.ROOT -> SuShell.exec("cat ${shQuote(path)}", 15_000L)
            EnvMode.PROOT -> {
                val f = File(path)
                if (!f.isFile) CtlResult.fail("文件不存在：$path") else CtlResult(0, f.readText(), "")
            }
        }
    }

    /**
     * 在环境内的 JSON 文件里**合并**一个顶层键（文件不存在就新建）。
     *
     * 为什么要合并而不是整体覆盖：`etc/config.json` 里还有运行时的端口、模式、自启等设置，
     * 整体覆盖会把它们抹掉。
     */
    suspend fun mergeJsonKey(
        context: Context,
        mode: EnvMode,
        relativePath: String,
        key: String,
        value: String?,
    ): CtlResult {
        val existing = readText(context, mode, relativePath)
        val root = if (existing.ok && existing.stdout.isNotBlank()) {
            runCatching { org.json.JSONObject(existing.stdout) }.getOrElse { org.json.JSONObject() }
        } else {
            org.json.JSONObject()
        }
        if (root.optInt("schema", 0) == 0) root.put("schema", 1)
        if (value == null) root.remove(key) else root.put(key, value)
        return writeText(context, mode, relativePath, root.toString(2))
    }

    suspend fun writeText(
        context: Context,
        mode: EnvMode,
        relativePath: String,
        content: String,
    ): CtlResult = withContext(Dispatchers.IO) {
        val file = File(DshPaths.linuxHome(context, mode), relativePath)
        when (mode) {
            EnvMode.ROOT -> {
                // 先确保父目录存在，再写；内容全部走 stdin
                val script = "mkdir -p ${shQuote(file.parent ?: "/data/sunsetlinux")} && cat > ${shQuote(file.absolutePath)}"
                Proc.execWithInput(listOf("su", "-c", script), content.toByteArray(), 20_000L)
            }

            EnvMode.PROOT -> {
                try {
                    file.parentFile?.mkdirs()
                    file.writeText(content)
                    // 0600：频道配置可能包含第三方公钥，没必要让别的应用读
                    runCatching { file.setReadable(false, false); file.setReadable(true, true) }
                    CtlResult(0, "", "")
                } catch (t: Throwable) {
                    CtlResult.fail("写入失败：${t.message ?: t.javaClass.simpleName}")
                }
            }
        }
    }

    /** 环境根的绝对路径，界面上展示用。 */
    fun home(context: Context, mode: EnvMode): String = DshPaths.linuxHome(context, mode)
}
