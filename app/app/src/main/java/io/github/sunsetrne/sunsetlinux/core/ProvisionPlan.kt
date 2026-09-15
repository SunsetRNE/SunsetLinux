package io.github.sunsetrne.sunsetlinux.core

/**
 * 「首次部署向导」的**下一步决策** —— 纯函数，可单测。
 *
 * ## 为什么必须把这段独立出来（真机上踩过）
 *
 * `linuxctl provision` 的名字有歧义：它**只**建目录树 / `upper.img` / `config.json` /
 * `state.json`，**从不构建层**。层缺失时它返回 `{"ok":false,"missing_layers":"base,runtime,dsh"}`
 * 并以退出码 1 结束。
 *
 * App 的向导原先只调它，于是真机上出现的是：**"provision 失败"** ——
 * 用户以为哪里坏了，其实环境只是"还没被构建"，而构建那一步（真 chroot 里 apt + npm）
 * 只能由 `device-provision.sh` 做。所以向导必须自己会分诊：
 *
 * ```
 *   linuxctl provision  →  层齐了？ ──是──→ 可以 start
 *                              │
 *                              否
 *                              ├─ root 模式 → 跑 device-provision.sh（设备侧原生构建，幂等：已有层会跳过）
 *                              └─ proot 模式 → 没有真 chroot，只能从频道/种子目录取预构建层
 * ```
 *
 * 这里只做判断，不碰 IO —— 这样上面那张表可以被单测钉住（见 ProvisionPlanTest）。
 */
object ProvisionPlan {

    /** 向导的下一步。 */
    enum class Step {
        /** 层齐了（`provision` 成功或返回 2「已部署过」），可以直接 start。 */
        DONE,

        /** 缺层，且能构建（root 模式 + 有 su）→ 调 `device-provision.sh`。 */
        BUILD_LAYERS,

        /** 缺层但**构建不了**（proot 模式）：只能装频道里的层，别让用户白等。 */
        NEED_CHANNEL,

        /** 既不是"已部署"也不是"缺层"——例如 su 不可用、upper.img 建不出来。 */
        FAILED,
    }

    /**
     * 从 `linuxctl provision` 的 stdout 里取**还缺哪些层**。
     *
     * 字段是 `missing_layers`（逗号分隔，见 `runtime/root/linuxctl.sh` 的 `cmd_provision`）。
     * 解析失败一律返回空表：**宁可当作"不知道缺什么"，也不要凭猜测去跑一次半小时的构建**。
     */
    fun missingLayers(stdout: String): List<String> {
        val json = stdout.trim()
        if (json.isEmpty()) return emptyList()
        val obj = try {
            org.json.JSONObject(json.substring(json.indexOf('{')))
        } catch (_: Throwable) {
            return emptyList()
        }
        val raw = obj.optString("missing_layers", "").trim()
        if (raw.isEmpty()) return emptyList()
        return raw.split(',')
            .map { it.trim() }
            .filter { it in KNOWN_LAYERS }
            .distinct()
    }

    /** 语义版本（layer-spec §5 的三层 id）。 */
    val KNOWN_LAYERS = listOf("base", "runtime", "dsh")

    /**
     * 决策表本体。
     *
     * @param mode        当前模式
     * @param exitCode    `linuxctl provision` 的退出码（0 成功 / 2 已部署 / 1 失败）
     * @param missing     [missingLayers] 的结果
     * @param suAvailable 是否能拿到 su（root 模式的前提）
     */
    fun nextStep(
        mode: EnvMode,
        exitCode: Int,
        missing: List<String>,
        suAvailable: Boolean,
    ): Step = when {
        // 0 = 本次铺好了；2 = 之前就部署过（契约 §3）。两者都说明层齐全。
        exitCode == 0 || exitCode == 2 -> Step.DONE
        missing.isEmpty() -> Step.FAILED
        mode == EnvMode.ROOT && suAvailable -> Step.BUILD_LAYERS
        mode == EnvMode.ROOT -> Step.FAILED
        else -> Step.NEED_CHANNEL
    }

    /**
     * 缺层时给用户看的一句话（**要说出"provision 不建层"这件事**，
     * 否则用户会把"层缺失"理解成"部署坏了"）。
     */
    fun explain(step: Step, missing: List<String>): String = when (step) {
        Step.DONE -> "层已就绪。"
        Step.BUILD_LAYERS ->
            "还缺层：${missing.joinToString("、")}。" +
                "注意 `linuxctl provision` **不构建层**（它只建目录/可写层/配置），" +
                "接下来改用设备侧原生构建 device-provision.sh（真 chroot 里装 apt + npm，" +
                "十几分钟到半小时；已经有层的那几层会自动跳过）。"
        Step.NEED_CHANNEL ->
            "还缺层：${missing.joinToString("、")}。proot 模式没有真 chroot，不能在设备上构建层：" +
                "请在「更新」页从频道安装这三层，或把预构建的 *.erofs 放进种子目录后重试。"
        Step.FAILED ->
            "部署没有完成，但也没有可用层缺失信息——请把上面的日志发给维护者。"
    }
}
