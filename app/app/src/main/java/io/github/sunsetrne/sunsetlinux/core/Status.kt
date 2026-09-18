package io.github.sunsetrne.sunsetlinux.core

import org.json.JSONObject

/**
 * 运行模式。见 docs/architecture.md §1。
 *
 * [wire] 必须与 `linuxctl status` 里 `mode` 的取值一致（`root` / `proot`）。
 */
enum class EnvMode(val wire: String, val label: String, val modeLabel: String) {
ROOT("root", "root", "ROOT"),
PROOT("proot", "proot", "PROOT");

companion object {
        fun from(raw: String?): EnvMode? =
            entries.firstOrNull { it.wire.equals(raw?.trim(), ignoreCase = true) }
}
}

/**
 * `linuxctl status` 的 `state` 字段。
 *
 * 契约（§3.1）：∈ `stopped | starting | running | stopping | error`。
 * 这里额外保留一个 [UNKNOWN]，用于「命令没跑起来 / JSON 缺失 / 出现未知取值」——
 * 这三种情况必须能被 App 区分并给出友好提示，绝不能让首页崩掉或空白。
 */
enum class EnvState(val wire: String, val label: String) {
STOPPED("stopped", "已停止"),
STARTING("starting", "正在启动"),
RUNNING("running", "运行中"),
STOPPING("stopping", "正在停止"),
ERROR("error", "出错"),
UNKNOWN("unknown", "状态未知");

companion object {
        fun from(raw: String?): EnvState =
            entries.firstOrNull { it.wire.equals(raw?.trim(), ignoreCase = true) } ?: UNKNOWN
}
}

/**
 * status JSON 的**附加键** `env_mode`：本次 `start` 起来的是"环境 + DSH"还是"只有环境"。
 *
 * 为什么 App 必须知道这件事（而不是只看 state）：拆开启动之后，"环境在跑"再也不能
 * 说明"DSH 在跑"。两种起法的**下一步操作完全相反** ——
 *   · [FULL]（一键启动）：DSH 归环境管，单独 `dsh stop` 会被模块拒绝，只能整体停止；
 *   · [ENV_ONLY]：DSH 是独立的一层，可以单独启停而环境继续跑。
 * 判错的后果是用户点了按钮却被脚本拒绝（或者更糟：以为能单独停 DSH 结果把环境也停了）。
 *
 * `null` 的语义是**判不了**（环境没在跑，或旧模块没报这个键），不是某一档 —— 调用方
 * 必须把 null 与 [FULL] 区分开，否则旧模块上会给出"本次是一键启动"这种编造的说法。
 */
enum class EnvRunMode(val wire: String, val label: String) {
FULL("full", "一键启动（环境 + DSH）"),
ENV_ONLY("env-only", "仅环境（未启 DSH）");

companion object {
        fun from(raw: String?): EnvRunMode? =
            entries.firstOrNull { it.wire.equals(raw?.trim(), ignoreCase = true) }
}
}

/** `status.layers.<id>`，字段全部容错（不可得为 null，而不是省略）。 */
data class LayerInfo(
val id: String,
val version: String?,
val size: Long?,
val mounted: Boolean?,
)

/**
 * docs/architecture.md §3.1 的 status JSON 的 Kotlin 视图。
 *
 * 解析原则：**任何字段缺失/类型异常都不能抛异常**，退化为 null 或 UNKNOWN，
 * 并把原始输出留在 [raw] 里供诊断页展示。
 */
data class DshStatus(
val schema: Int?,
val mode: String?,
val state: EnvState,
val pid: Long?,
val uptimeSec: Long?,
/** 带 launchToken 的登录 URL（§3.3）。每次重启都会变，**不得缓存**。 */
val dshUrl: String?,
/** 不带令牌的裸地址，用于展示与健康检查。 */
val dshBaseUrl: String?,
val dshPort: Int?,
val dshVersion: String?,
val dshHealthy: Boolean?,
val layers: List<LayerInfo>,
val upperUsed: Long?,
val upperTotal: Long?,
val lastError: String?,
/**
     * 附加键 `dsh.running`（模块新增）：DSH 进程**是否真的在跑**。
     *
     * 为什么需要它：拆开启动之后"环境 running"不再蕴含"DSH running"（`env-only` 起法下
     * 环境跑着而 DSH 没起）。缺这个键时是 null —— 此时 [dshRunning] 会退化为
     * "有带令牌 url 就算在跑"，绝不能因为缺键就崩或显示成"DSH 在跑"。
     */
val dshRunningReported: Boolean? = null,
/** 附加键 `env_mode`；null = 环境没在跑 / 旧模块没报 → **判不了**，不编造。 */
val envMode: EnvRunMode? = null,
/**
     * 非 root 模式**实际**用的运行时（`proroot` / `proot`），从 status JSON 的新键
     * `rootless:{kind,version}` 读；没启动过时是 null（不编造）。
     *
     * 为什么单列：`mode` 对外仍是 `proot`（§3.1 冻结的契约不动），
     * 但"到底跑的是 proroot 还是降级的 proot"对排障与性能预期都是关键信息。
     */
/** 本次 start 用的层模式（`loop` / `dir`）；没启动过是 null。 */
val layerMode: String? = null,
val rootlessKind: String? = null,
val rootlessVersion: String? = null,
val raw: String,
) {
val isRunning: Boolean get() = state == EnvState.RUNNING
val isBusy: Boolean get() = state == EnvState.STARTING || state == EnvState.STOPPING

/**
     * 环境是否真的在跑（**与 DSH 无关**）。
     *
     * 单开这个名字而不是到处写 `state == RUNNING`：拆开启动之后"环境在跑"是终端可用的
     * 唯一前提，而"DSH 在跑"是另一件事 —— 两个判断混用会导致 env-only 模式下终端被误判为不可用。
     */
val envRunning: Boolean get() = state == EnvState.RUNNING

/**
     * DSH 是否在跑。
     *
     * 优先用模块报的 `dsh.running`（它是权威事实）；**缺键时退化**为"有带令牌 url 就算在跑"
     * —— 那个 url 只在 DSH 起来之后才写进 status，所以"有 url"是旧模块上最接近的近似。
     * 退化的方向是明确的：没有 url 就判"没在跑"（不猜"可能在跑"），因为"点了「启动 DSH」"
     * 在模块侧是幂等的，而反过来"以为在跑 → 按钮点不动"会让用户彻底卡住。
     */
val dshRunning: Boolean get() = dshRunningReported ?: !dshUrl.isNullOrBlank()

/** 本次是按「仅启动环境」起的（环境在跑且模块报了 env-only）。 */
val isEnvOnly: Boolean get() = envRunning && envMode == EnvRunMode.ENV_ONLY

/** 本次是按「一键启动」起的（环境与 DSH 一起）。 */
val isFullMode: Boolean get() = envRunning && envMode == EnvRunMode.FULL

/**
     * 「打开 DSH」是否可用。
     *
     * 必须同时满足：环境在跑 + 拿到了带令牌的 URL（§3.3：裸地址会返回 401，
     * 所以只认带 token 的 url）。
     */
val canOpenWeb: Boolean get() = state == EnvState.RUNNING && !dshUrl.isNullOrBlank()

/** 展示用的稳定地址（永不显示令牌）。 */
val displayUrl: String? get() = dshBaseUrl ?: dshUrl?.let(::stripToken)

val modeEnum: EnvMode? get() = EnvMode.from(mode)

fun layer(id: String): LayerInfo? = layers.firstOrNull { it.id == id }

/** 运行时长的中文可读形式，例如「1 小时 02 分」。 */
val uptimeText: String? get() = uptimeSec?.let(::formatDuration)

companion object {
        /** 命令本身没跑起来（su 缺失 / linuxctl 缺失 / 超时）时的占位状态。 */
        fun unavailable(reason: String, raw: String = ""): DshStatus = DshStatus(
            schema = null,
            mode = null,
            state = EnvState.UNKNOWN,
            pid = null,
            uptimeSec = null,
            dshUrl = null,
            dshBaseUrl = null,
            dshPort = null,
            dshVersion = null,
            dshHealthy = null,
            layers = emptyList(),
            upperUsed = null,
            upperTotal = null,
            lastError = reason,
            raw = raw,
        )

        /** 解析 stdout。脏输出（su 噪声、警告）容忍：先截出最外层 JSON 对象再解析。 */
        fun parse(stdout: String): DshStatus {
            val slice = extractJsonObject(stdout)
                ?: return unavailable("linuxctl status 未输出 JSON（原始输出见诊断页）", stdout.trim())
            return try {
                fromJson(JSONObject(slice), stdout.trim())
            } catch (t: Throwable) {
                unavailable("无法解析 status JSON：${t.message ?: t.javaClass.simpleName}", stdout.trim())
            }
        }

        private fun fromJson(o: JSONObject, raw: String): DshStatus {
            val dsh = o.optJSONObject("dsh")
            val layersObj = o.optJSONObject("layers")
            val storage = o.optJSONObject("storage")
            val rootless = o.optJSONObject("rootless")

            val layers = buildList {
                if (layersObj != null) {
                    // 顺序固定为 base → runtime → dsh，界面上三个层永远同序展示
                    for (id in listOf("base", "runtime", "dsh")) {
                        val l = layersObj.optJSONObject(id) ?: continue
                        add(
                            LayerInfo(
                                id = id,
                                version = l.str("version"),
                                size = l.num("size"),
                                mounted = l.bool("mounted"),
                            )
                        )
                    }
                    // 未知层（第三方层）也带上，不丢信息
                    for (key in layersObj.keys()) {
                        if (key in listOf("base", "runtime", "dsh")) continue
                        val l = layersObj.optJSONObject(key) ?: continue
                        add(
                            LayerInfo(
                                id = key,
                                version = l.str("version"),
                                size = l.num("size"),
                                mounted = l.bool("mounted"),
                            )
                        )
                    }
                }
            }

            return DshStatus(
                layerMode = o.str("layer_mode"),
                rootlessKind = rootless?.str("kind"),
                rootlessVersion = rootless?.str("version"),
                schema = o.num("schema")?.toInt(),
                mode = o.str("mode"),
                state = EnvState.from(o.str("state")),
                pid = o.num("pid"),
                uptimeSec = o.num("uptime_sec"),
                dshUrl = dsh?.str("url"),
                dshBaseUrl = dsh?.str("base_url"),
                dshPort = dsh?.num("port")?.toInt(),
                dshVersion = dsh?.str("version"),
                dshHealthy = dsh?.bool("healthy"),
                layers = layers,
                upperUsed = storage?.num("upper_used"),
                upperTotal = storage?.num("upper_total"),
                lastError = o.str("last_error"),
                // 附加键（§3.1 冻结键名不动，这里只读新增键）：
                //   · dsh.running 缺失 → null，由 DshStatus.dshRunning 决定退化口径；
                //   · env_mode 缺失/取值未知 → null（"判不了"），绝不当成 full。
                dshRunningReported = dsh?.bool("running"),
                envMode = EnvRunMode.from(o.str("env_mode")),
                raw = raw,
            )
        }

        /** 返回第一个 `{` 到最后一个 `}` 之间的内容；找不到返回 null。 */
        fun extractJsonObject(text: String): String? {
            val start = text.indexOf('{')
            val end = text.lastIndexOf('}')
            if (start < 0 || end <= start) return null
            return text.substring(start, end + 1)
        }

        private fun JSONObject.str(key: String): String? {
            if (!has(key) || isNull(key)) return null
            return optString(key, "").trim().ifEmpty { null }
        }

        private fun JSONObject.num(key: String): Long? {
            if (!has(key) || isNull(key)) return null
            return when (val v = opt(key)) {
                is Number -> v.toLong()
                is String -> v.trim().toLongOrNull()
                else -> null
            }
        }

        private fun JSONObject.bool(key: String): Boolean? {
            if (!has(key) || isNull(key)) return null
            return when (val v = opt(key)) {
                is Boolean -> v
                is Number -> v.toInt() != 0
                is String -> when (v.trim().lowercase()) {
                    "true", "1", "yes" -> true
                    "false", "0", "no" -> false
                    else -> null
                }
                else -> null
            }
        }
}
}

/** 去掉 `?token=...`，用于展示。 */
fun stripToken(url: String): String {
val q = url.indexOf('?')
val base = if (q >= 0) url.substring(0, q) else url
return base.removeSuffix("/")
}

/** 3721 → 「1 小时 02 分」。 */
fun formatDuration(sec: Long): String {
if (sec < 0) return "—"
val d = sec / 86400
val h = (sec % 86400) / 3600
val m = (sec % 3600) / 60
val s = sec % 60
return when {
        d > 0 -> "$d 天 $h 小时"
        h > 0 -> "$h 小时 ${"%02d".format(m)} 分"
        m > 0 -> "$m 分 ${"%02d".format(s)} 秒"
        else -> "$s 秒"
}
}

/** 1536 → 「1.5 KB」。用 1024 进制，但不显示小数位到令人困惑的精度。 */
fun formatBytes(bytes: Long?): String {
if (bytes == null || bytes < 0) return "—"
val units = listOf("B", "KB", "MB", "GB", "TB")
var v = bytes.toDouble()
var i = 0
while (v >= 1024 && i < units.lastIndex) {
        v /= 1024
        i++
}
return if (i == 0) "$bytes ${units[0]}" else "%.1f %s".format(v, units[i])
}

/**
     * 「这一次**没读到**」与「读到了、环境真的是 ERROR」是两件事。
     *
     * [unavailable] 产出的状态 state=UNKNOWN（且带 lastError）；而 linuxctl 真报错时
     * state=error 是**事实**，必须照实显示。UI 的"保留上一次好状态"只对前者生效
     * （2026-09-19：轮询里任何一次 su 抖动都会把界面从"运行中"翻成"读取失败"，
     * 肉眼就是闪）。
     */
fun DshStatus.isReadFailure(): Boolean = state == EnvState.UNKNOWN

/**
     * 这一次刷新要不要**沿用上一次的好状态**（纯函数，便于单测）。
     *
     * 判据是**"读到了 vs 没读到"**，不是"好 vs 坏"：上一次成功读到的值（哪怕是
     * state=error）是**已知事实**，读不到时保留它比换成"读取失败"信息更多、也不闪。
     *
     * 规则：
     *   · 这次读到了（不是读失败）→ **不保留**（一律用新值，真实故障必须立刻显示）；
     *   · 上次也是读失败 / 没有上次 → 不保留（没有已知事实可留）；
     *   · 已连续保留满 [maxStaleTicks] 轮 → 不保留（不许永远显示过期值）。
     */
fun keepLastGoodStatus(
        prev: DshStatus?,
        fresh: DshStatus,
        staleTicks: Int,
        maxStaleTicks: Int,
): Boolean = fresh.isReadFailure() &&
        prev != null &&
        !prev.isReadFailure() &&
        staleTicks < maxStaleTicks
