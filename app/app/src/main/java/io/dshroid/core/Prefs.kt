package io.dshroid.core

import android.content.Context
import androidx.core.content.edit
import org.json.JSONArray
import org.json.JSONObject

/**
 * 一个更新频道 = 一个清单 URL + 一个 ed25519 公钥（docs/architecture.md §5.1）。
 *
 * 第三方开发者可自签自建频道，App 不做任何中心审核；因此**验签失败必须明确拒绝**。
 */
data class Channel(
    val id: String,
    val name: String,
    val url: String,
    val pubkey: String,
    val enabled: Boolean = true,
    val priority: Int = 100,
) {
    fun toJson(): JSONObject = JSONObject().apply {
        put("id", id)
        put("name", name)
        put("url", url)
        put("pubkey", pubkey)
        put("enabled", enabled)
        put("priority", priority)
    }

    /** 简洁展示：去掉协议头，太长时截断。 */
    val shortUrl: String
        get() = url.removePrefix("https://").removePrefix("http://").let {
            if (it.length > 42) it.take(39) + "…" else it
        }

    companion object {
        fun fromJson(o: JSONObject): Channel? {
            val id = o.optString("id", "").trim()
            val url = o.optString("url", "").trim()
            if (id.isEmpty() || url.isEmpty()) return null
            return Channel(
                id = id,
                name = o.optString("name", id).trim().ifEmpty { id },
                url = url,
                pubkey = o.optString("pubkey", "").trim(),
                enabled = if (o.has("enabled")) o.optBoolean("enabled", true) else true,
                priority = o.optInt("priority", 100),
            )
        }

        fun listFrom(raw: String?): List<Channel> {
            if (raw.isNullOrBlank()) return emptyList()
            return try {
                val arr = JSONArray(raw)
                (0 until arr.length()).mapNotNull { i ->
                    arr.optJSONObject(i)?.let { fromJson(it) }
                }
            } catch (_: Throwable) {
                emptyList()
            }
        }

        fun listToJson(list: List<Channel>): String {
            val arr = JSONArray()
            list.forEach { arr.put(it.toJson()) }
            return arr.toString()
        }

        /** 把频道列表序列化成 §5.1 的 `channels.json`（写入环境时用）。 */
        fun toChannelsFile(list: List<Channel>): String {
            val root = JSONObject().apply {
                put("schema", 1)
                put("channels", JSONArray().also { arr -> list.forEach { arr.put(it.toJson()) } })
            }
            return root.toString(2)
        }
    }
}

/**
 * App 侧设置。**这不是** Linux 侧的 `etc/config.json`；
 * App 只把其中的频道列表在需要时同步进环境（见 SettingsActivity 的「同步到环境」）。
 */
class Prefs(context: Context) {

    private val sp = context.getSharedPreferences("dshroid", Context.MODE_PRIVATE)

    /** 用户强制指定的模式；null = 自动（能 su 就 root，否则 proot）。 */
    var modeOverride: EnvMode?
        get() = EnvMode.from(sp.getString(KEY_MODE, null))
        set(value) = sp.edit { putString(KEY_MODE, value?.wire) }

    /** 首页服务端口（仅作展示/预填，实际端口以 status 为准）。 */
    var port: Int
        get() = sp.getInt(KEY_PORT, 3080)
        set(value) = sp.edit { putInt(KEY_PORT, value) }

    /** 开机自启前台服务（环境本身在 root 模式下由 KernelSU 模块启动）。 */
    var bootStartService: Boolean
        get() = sp.getBoolean(KEY_BOOT_SERVICE, false)
        set(value) = sp.edit { putBoolean(KEY_BOOT_SERVICE, value) }

    /** 进入 App 时自动 start（仅在已部署且当前 stopped 时生效）。 */
    var autoStartEnv: Boolean
        get() = sp.getBoolean(KEY_AUTO_START, false)
        set(value) = sp.edit { putBoolean(KEY_AUTO_START, value) }

    var channels: List<Channel>
        get() = Channel.listFrom(sp.getString(KEY_CHANNELS, null))
        set(value) = sp.edit { putString(KEY_CHANNELS, Channel.listToJson(value)) }

    /** 用户选择忽略的更新（形如 `dsh:0.1.5-rc.2`），避免一直顶着角标。 */
    var ignoredUpdate: String?
        get() = sp.getString(KEY_IGNORED_UPDATE, null)
        set(value) = sp.edit { putString(KEY_IGNORED_UPDATE, value) }

    /** 日志面板是否自动跟随尾部（排障时经常要暂停下来看） */
    var logAutoFollow: Boolean
        get() = sp.getBoolean(KEY_LOG_FOLLOW, true)
        set(value) = sp.edit { putBoolean(KEY_LOG_FOLLOW, value) }

    /**
     * 是否已完成首启引导（模式选择）。
     * false 时启动器会拉起引导页；之后可从侧边栏「重新部署/引导」重进。
     */
    var onboarded: Boolean
        get() = sp.getBoolean(KEY_ONBOARDED, false)
        set(value) = sp.edit { putBoolean(KEY_ONBOARDED, value) }

    /** 用户是否已看过「Root 模式需要先装 KernelSU 模块并重启」这一步的确认。 */
    var moduleStepAcknowledged: Boolean
        get() = sp.getBoolean(KEY_MODULE_STEP, false)
        set(value) = sp.edit { putBoolean(KEY_MODULE_STEP, value) }

    /**
     * DSH 的 npm dist-tag（latest / next / alpha / 自定义版本号）。
     * 为空表示"跟随频道清单里声明的 dsh_npm.dist_tag"。
     */
    var dshDistTag: String?
        get() = sp.getString(KEY_DSH_DIST_TAG, null)
        set(value) = sp.edit { putString(KEY_DSH_DIST_TAG, value) }

    /** 最近一次使用的 npm 源（registry）。 */
    var npmRegistry: String?
        get() = sp.getString(KEY_NPM_REGISTRY, null)
        set(value) = sp.edit { putString(KEY_NPM_REGISTRY, value) }

    /** 最近一次使用的离线种子目录（provision 用）。 */
    var seedDir: String?
        get() = sp.getString(KEY_SEED_DIR, null)
        set(value) = sp.edit { putString(KEY_SEED_DIR, value) }

    private companion object {
        const val KEY_MODE = "mode_override"
        const val KEY_PORT = "port"
        const val KEY_BOOT_SERVICE = "boot_start_service"
        const val KEY_AUTO_START = "auto_start_env"
        const val KEY_CHANNELS = "channels_json"
        const val KEY_IGNORED_UPDATE = "ignored_update"
        const val KEY_SEED_DIR = "seed_dir"
        const val KEY_DSH_DIST_TAG = "dsh_dist_tag"
        const val KEY_NPM_REGISTRY = "npm_registry"
        const val KEY_LOG_FOLLOW = "log_auto_follow"
        const val KEY_ONBOARDED = "onboarded"
        const val KEY_MODULE_STEP = "module_step_ack"
    }
}
