package io.dshroid.core

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
    val raw: String,
) {
    val isRunning: Boolean get() = state == EnvState.RUNNING
    val isBusy: Boolean get() = state == EnvState.STARTING || state == EnvState.STOPPING

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
