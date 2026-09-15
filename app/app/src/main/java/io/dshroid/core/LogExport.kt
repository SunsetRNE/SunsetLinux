package io.dshroid.core

import android.content.Context
import android.content.Intent
import androidx.core.content.FileProvider
import androidx.core.content.pm.PackageInfoCompat
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * 一键导出排障包。
 *
 * 打包内容（按排障优先级排序）：
 * 1. 环境与模式摘要（模式、su 可用性、环境根、包版本、当前阶段）
 * 2. `status` 的**原始 JSON**（§3.1，含 `last_error`）
 * 3. `linuxctl logs -n 800`
 * 4. 崩溃日志（含 cause 链与 40 帧栈）
 *
 * 分享走 FileProvider（`io.dshroid.files`），不需要任何存储权限。
 */
object LogExport {

    private const val EXPORT_DIR = "exports"

    fun exportDir(context: Context): File = File(context.cacheDir, EXPORT_DIR).apply { mkdirs() }

    /**
     * 生成排障包。
     * @param stage 当前阶段（授权/部署/挂载/启动/健康检查…），显示在摘要第一行，便于开发者定位
     */
    suspend fun buildBundle(
        context: Context,
        mode: EnvMode,
        stage: String?,
        includeEnvLog: Boolean = true,
        envLogLines: Int = 800,
    ): File = withContext(Dispatchers.IO) {
        val stamp = SimpleDateFormat("yyyyMMdd-HHmmss", Locale.US).format(Date())
        val file = File(exportDir(context), "dshroid-report-$stamp.txt")

        val ctl = LinuxCtl(context, mode)
        val status = ctl.status()
        val su = DshRuntime.suAvailable()
        val ctlExists = ctl.exists()

        val sb = StringBuilder()
        sb.append("===== DSHroid 排障包 =====\n")
        sb.append("生成时间   : ${SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.US).format(Date())}\n")
        sb.append("当前阶段   : ${stage ?: "（未记录）"}\n")
        sb.append("运行模式   : ${mode.modeLabel}\n")
        sb.append("su 可用    : $su\n")
        sb.append("linuxctl   : ${if (ctlExists) ctl.activeCtlPath else "缺失（未部署）"}\n")
        sb.append("契约路径   : ${ctl.ctlPath}\n")
        sb.append("环境根     : ${ctl.home}\n")
        try {
            val pi = context.packageManager.getPackageInfo(context.packageName, 0)
            sb.append("应用版本   : ${pi.versionName} (${PackageInfoCompat.getLongVersionCode(pi)})\n")
        } catch (_: Throwable) {
            // 忽略：摘要里少一行不影响排障
        }
        sb.append("设备       : ${android.os.Build.MANUFACTURER} ${android.os.Build.MODEL} / ")
            .append("Android ${android.os.Build.VERSION.RELEASE} (SDK ${android.os.Build.VERSION.SDK_INT})\n")

        sb.append("\n\n===== status JSON（§3.1 原始输出）=====\n")
        sb.append(status.raw.ifBlank { "（无输出）" }).append('\n')
        sb.append("\n解析结果：state=${status.state.wire} mode=${status.mode ?: "?"} ")
            .append("healthy=${status.dshHealthy} url=${if (status.dshUrl != null) "已就绪" else "未就绪"}\n")
        sb.append("last_error=${status.lastError ?: "null"}\n")

        if (includeEnvLog) {
            sb.append("\n\n===== linuxctl logs -n $envLogLines =====\n")
            val logs = ctl.logs(envLogLines)
            sb.append(
                when {
                    logs.ok -> logs.stdout.ifBlank { "（日志为空）" }
                    else -> "读取失败：${logs.message}"
                }
            ).append('\n')
        }

        sb.append("\n\n===== 崩溃日志（最近 3 份）=====\n")
        val crashes = CrashLogs.list(context).take(3)
        if (crashes.isEmpty()) {
            sb.append("（没有崩溃记录）\n")
        } else {
            crashes.forEach { c ->
                sb.append("\n---- ${c.name} ----\n")
                sb.append(runCatching { c.readText() }.getOrDefault("（读取失败）"))
            }
        }

        file.writeText(sb.toString())
        file
    }

    /** 分享已经生成的排障包（或任意受 FileProvider 管辖的文件）。 */
    fun share(context: Context, file: File, subject: String = "DSHroid 排障包") {
        val uri = FileProvider.getUriForFile(context, "${context.packageName}.files", file)
        val intent = Intent(Intent.ACTION_SEND).apply {
            type = "text/plain"
            putExtra(Intent.EXTRA_SUBJECT, subject)
            putExtra(Intent.EXTRA_STREAM, uri)
            putExtra(Intent.EXTRA_TEXT, "DSHroid 排障包：${file.name}")
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
        context.startActivity(Intent.createChooser(intent, "分享排障包").addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
    }

    /** 清理旧的导出文件（每次导出前调用，避免缓存目录无限增长）。 */
    fun prune(context: Context, keep: Int = 5) {
        runCatching {
            exportDir(context).listFiles()
                ?.sortedByDescending { it.name }
                ?.drop(keep)
                ?.forEach { it.delete() }
        }
    }
}
