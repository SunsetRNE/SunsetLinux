package io.github.sunsetrne.sunsetlinux.core

import android.content.Context
import android.os.Build
import androidx.core.content.pm.PackageInfoCompat
import java.io.File
import java.io.PrintWriter
import java.io.StringWriter
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * 崩溃日志：把**异常消息 + 完整 cause 链 + 40 帧调用栈**落到文件。
 *
 * 为什么要有：同类启动器最大的痛点就是"启动失败但用户无从下手"。
 * 崩溃信息只留在 logcat 里等于没有 —— 用户不可能去看 logcat。
 * 这里落盘后，侧边栏「日志导出」可以一键把它连同环境日志打包分享给开发者。
 */
class CrashHandler(private val context: Context) : Thread.UncaughtExceptionHandler {

    private val default = Thread.getDefaultUncaughtExceptionHandler()

    override fun uncaughtException(thread: Thread, throwable: Throwable) {
        // 绝不能因为写崩溃日志本身出错而吞掉原始崩溃
        runCatching { CrashLogs.write(context, thread, throwable) }
        default?.uncaughtException(thread, throwable)
    }

    companion object {
        const val MAX_FRAMES = 40

        fun install(context: Context) {
            Thread.setDefaultUncaughtExceptionHandler(CrashHandler(context.applicationContext))
        }
    }
}

/** 崩溃日志的读写。 */
object CrashLogs {

    private const val MAX_KEPT = 10

    fun dir(context: Context): File = File(context.filesDir, "crash").apply { mkdirs() }

    fun write(context: Context, thread: Thread, throwable: Throwable): File {
        val stamp = SimpleDateFormat("yyyyMMdd-HHmmss", Locale.US).format(Date())
        val file = File(dir(context), "crash-$stamp.txt")
        file.writeText(render(context, thread, throwable))
        prune(context)
        return file
    }

    /** 新的在前。 */
    fun list(context: Context): List<File> =
        dir(context).listFiles()?.filter { it.isFile }?.sortedByDescending { it.name } ?: emptyList()

    fun latest(context: Context): File? = list(context).firstOrNull()

    fun clear(context: Context) {
        list(context).forEach { runCatching { it.delete() } }
    }

    private fun prune(context: Context) {
        list(context).drop(MAX_KEPT).forEach { runCatching { it.delete() } }
    }

    /** 四十帧调用栈 + 完整 cause 链。 */
    fun render(context: Context, thread: Thread, throwable: Throwable): String = buildString {
        val time = SimpleDateFormat("yyyy-MM-dd HH:mm:ss.SSS", Locale.US).format(Date())
        append("===== SunsetLinux 崩溃报告 =====\n")
        append("时间      : $time\n")
        append("线程      : ${thread.name} (id=${thread.id})\n")
        append("设备      : ${Build.MANUFACTURER} ${Build.MODEL}\n")
        append("Android   : ${Build.VERSION.RELEASE} (SDK ${Build.VERSION.SDK_INT})\n")
        append("ABI       : ${Build.SUPPORTED_ABIS.joinToString(", ")}\n")
        try {
            val pi = context.packageManager.getPackageInfo(context.packageName, 0)
            append("应用      : ${context.packageName} ${pi.versionName} (${PackageInfoCompat.getLongVersionCode(pi)})\n")
        } catch (_: Throwable) {
            append("应用      : ${context.packageName}\n")
        }
        append("\n----- 异常链 -----\n")

        var t: Throwable? = throwable
        var depth = 0
        while (t != null && depth < 10) {
            if (depth > 0) append("\nCaused by: ")
            append(t.javaClass.name)
            t.message?.let { append(": ").append(it) }
            append('\n')
            frames(t).forEach { frame -> append("    at ").append(frame).append('\n') }
            val next = t.cause
            if (next === t) break
            t = next
            depth++
        }

        append("\n----- 完整 stacktrace（最多 ${CrashHandler.MAX_FRAMES} 帧/异常）-----\n")
        val sw = StringWriter()
        throwable.printStackTrace(PrintWriter(sw))
        append(sw.toString())
    }

    private fun frames(t: Throwable): List<StackTraceElement> {
        val all = t.stackTrace ?: return emptyList()
        return if (all.size <= CrashHandler.MAX_FRAMES) all.toList() else all.take(CrashHandler.MAX_FRAMES)
    }
}
