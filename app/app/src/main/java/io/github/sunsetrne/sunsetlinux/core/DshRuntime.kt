package io.github.sunsetrne.sunsetlinux.core

import android.content.Context
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * 「当前该用哪个模式、环境现在什么状态」的唯一判定处。
 *
 * 所有调用方（首页、服务、通知、日志页…）都走这里，避免每个界面各自探测 su 与路径。
 */
object DshRuntime {

    @Volatile
    private var suProbe: Boolean? = null

    @Volatile
    private var suProbeAt: Long = 0L

    private const val SU_PROBE_TTL_MS = 60_000L

    /** 探测 su 是否可用（带 60 秒缓存，避免每次轮询都拉起一个 su 进程）。 */
    suspend fun suAvailable(force: Boolean = false): Boolean = withContext(Dispatchers.IO) {
        val cached = suProbe
        if (!force && cached != null && System.currentTimeMillis() - suProbeAt < SU_PROBE_TTL_MS) {
            return@withContext cached
        }
        val result = SuShell.probe()
        suProbe = result
        suProbeAt = System.currentTimeMillis()
        result
    }

    fun invalidateSuProbe() {
        suProbe = null
        suProbeAt = 0L
    }

    /**
     * 解析当前模式。
     * 用户强制指定优先；但若强制 root 而设备没有 su，会**降级到 proot 并记录原因**，
     * 而不是让每一步操作都失败。
     */
    suspend fun resolveMode(context: Context, prefs: Prefs): ModeChoice {
        val forced = prefs.modeOverride
        val su = suAvailable()
        return when {
            forced == EnvMode.PROOT -> ModeChoice(EnvMode.PROOT, null)
            forced == EnvMode.ROOT && su -> ModeChoice(EnvMode.ROOT, null)
            forced == EnvMode.ROOT && !su ->
                ModeChoice(EnvMode.PROOT, "已强制 root 模式但设备未提供可用的 su，本次按 proot 模式运行")
            su -> ModeChoice(EnvMode.ROOT, null)
            else -> ModeChoice(EnvMode.PROOT, "未获得 root（su 不可用），已按 proot 模式运行")
        }
    }

    data class ModeChoice(val mode: EnvMode, val note: String?)

}
