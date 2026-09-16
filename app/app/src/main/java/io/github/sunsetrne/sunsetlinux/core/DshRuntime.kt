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
     *
     * ★ 0.3.0 起**两个 App 各自锁死一条路**（用户："一个纯 root 流程，一个纯免 root 流程"）：
     *   模式是**这个 APK 的身份**（包名都不同），运行期不再切换 —— 所以这里不再看
     *   `prefs.modeOverride`，也不会"偷偷降级"：
     *
     *   · **Root 版**：始终 root。没有 su 时**不降级**（降级到 proot 会去操作另一个环境，
     *     那是最坏的一种"看起来能用"），而是把原因说清楚；
     *   · **免 root 版**：始终 proot，且**根本不探测 su**（免 root 设备上探测毫无意义，
     *     还可能在部分 ROM 上弹授权框）。
     */
    suspend fun resolveMode(context: Context, prefs: Prefs): ModeChoice {
        if (!Edition.isRoot) return ModeChoice(EnvMode.PROOT, null)
        val su = suAvailable()
        return if (su) {
            ModeChoice(EnvMode.ROOT, null)
        } else {
            ModeChoice(
                EnvMode.ROOT,
                "本版是 Root 版（${Edition.applicationId}），但设备未提供可用的 su：" +
                    "请在 KernelSU / Magisk 里给本应用授权（授权按包名记）。" +
                    "不想 root 就用「免 root 版」，两个 App 可以同时安装。",
            )
        }
    }

    data class ModeChoice(val mode: EnvMode, val note: String?)

}
