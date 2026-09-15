package io.github.sunsetrne.sunsetlinux.core

/**
 * 失败归因。**用类型而不是字符串**：v1 时 `classify()` 返回中文串、`hints()` 再用字符串去
 * `when` 匹配，任何一侧改一个词就会静默退化成"启动失败"——这类耦合不该存在。
 */
enum class FailureKind(val label: String) {
    /** 挂载/分层/镜像格式相关 */
    MOUNT("挂载"),

    /** 端口被占用 */
    PORT("端口占用"),

    /** 权限/能力不足 */
    PERMISSION("权限不足"),

    /** 层或文件缺失 */
    MISSING("文件/层缺失"),

    /** 环境内的 Node / DSH 运行时问题 */
    RUNTIME("运行环境（Node/DSH）"),

    /** 兜底：不建议硬猜，直接引导用户去跑一键诊断 */
    UNKNOWN("未知"),

    /** `last_error` 为空 */
    NONE("原因未知"),
}

/** 建议动作的目标。**不要把 mode 判断散落各处**——诊断与建议只在 [Diagnoser] 里做。 */
enum class HintTarget {
    PROVISION,
    ROOT_GRANT,
    DOCTOR,
    SETTINGS_PORT,
    SETTINGS_POWER,
    UPDATES,
    START,
    LOGS,
}

/**
 * 一条"下一步该做什么"。
 *
 * 这是针对参考实现"老是启动失败、用户无从下手"的核心对策：
 * 失败时不能只说"启动失败"，必须给出**当前阶段 + 可能原因 + 一个可点的动作**。
 */
data class Hint(
    val title: String,
    val detail: String,
    val action: String,
    val target: HintTarget,
)

/**
 * 阶段判定与失败归因。
 *
 * 输入只有三样东西：`status`（§3.1 冻结 schema）、`su` 可用性、是否已部署。
 * 输出是"当前阶段"字符串 + 一组可执行建议。
 */
object Diagnoser {

    /** 当前阶段。失败时把这段和 `last_error` 一起展示，用户/开发者一眼知道卡在哪。 */
    fun stage(
        status: DshStatus?,
        provisioned: Boolean?,
        suAvailable: Boolean,
        mode: EnvMode,
    ): String {
        if (mode == EnvMode.ROOT && !suAvailable) return "授权（未获得 root）"
        if (provisioned == false) return "部署（缺少 linuxctl）"
        val st = status ?: return "读取状态"
        return when (st.state) {
            EnvState.STOPPED -> "已停止（可启动）"
            EnvState.STARTING -> "启动（正在拉起 Node 与 DSH Web）"
            EnvState.RUNNING -> if (st.dshHealthy == false) "健康检查（Web 未响应）" else "运行中"
            EnvState.STOPPING -> "停止"
            EnvState.ERROR -> failureStage(st.lastError)
            EnvState.UNKNOWN -> if (st.lastError == null) "读取状态" else failureStage(st.lastError)
        }
    }

    /**
     * 把 `last_error` 归到一个人话环节。
     *
     * **必须中英双语**：报错文案来自 Linux 侧脚本，实测大量是中文
     * （`无法创建挂载点 …`、`缺少层文件 …`、`sdcard 两种路径都挂载失败` …），
     * 但 errno / 系统调用 / node 的报错仍是英文。只匹配英文会让一整类挂载失败
     * 退化成笼统的"启动失败"——正是要避免的体验。
     *
     * 匹配顺序＝具体优先：端口 → 权限 → 缺失 → 运行环境 → 挂载。
     * 刻意**不**用裸 `node`/`dsh`：层名里就有 "dsh"（`层 dsh 挂载失败`），会误判成运行时问题。
     *
     * 关键词表与真实文案的对照见 `core/DiagnoserTest.kt`（会直接扫描 runtime 下各 .sh 的 die 文案）。
     */
    fun classify(lastError: String?): FailureKind {
        val raw = lastError?.trim().orEmpty()
        if (raw.isEmpty()) return FailureKind.NONE
        val e = raw.lowercase()

        // 端口（最先判：动作最明确，且 "占用" 等词不会被别的类抢走）
        if (any(e, "端口", "被占用", "占用", "eaddrinuse", "address already in use", "bind: ")) {
            return FailureKind.PORT
        }

        // 权限 / 能力
        if (any(
                e,
                "权限", "拒绝", "不允许", "permission denied", "permission", "denied",
                "capabilit", "eacces", "eperm", "operation not permitted",
            )
        ) {
            return FailureKind.PERMISSION
        }

        // 层 / 文件缺失（注意：不要用裸 "没有"，它常出现在否定义里：
        // "内核 … 里没有）"、"module.prop 里没有 version="）
        if (any(
                e,
                "缺少", "缺失", "不存在", "找不到", "没有找到", "未找到",
                "系统里没有", "未安装", "完整性校验失败", "校验失败",
                "no such file", "not found", "missing",
            )
        ) {
            return FailureKind.MISSING
        }

        // 环境内的 Node / DSH：用具体短语，避免与层名 "dsh" 冲突
        if (any(e, "启动超时", "健康检查", "未就绪", "npm", "node_modules", "dsh web", "无法启动", "启动失败")) {
            return FailureKind.RUNTIME
        }

        // 挂载 / 分层 / 镜像格式：中文挂载词 + 英文术语 + 运行时挂载准备的固定说法
        if (any(
                e,
                "挂载", "挂载点", "无法创建", "无法卸载", "overlay", "erofs", "squashfs",
                "loop", "union", "upper.img", "可写层", "层文件", "只读", "devpts", "sdcard",
                "bind ", "内核不支持", "格式不可识别", "magic", "mount",
            )
        ) {
            return FailureKind.MOUNT
        }

        // 兜底不给"启动"：宁可靠一键诊断，也不要给一个可能误导的分类
        return FailureKind.UNKNOWN
    }

    private fun any(haystack: String, vararg needles: String): Boolean =
        needles.any { haystack.contains(it) }

    /** 失败阶段文案：未知归因时明确引导去跑一键诊断，而不是含糊说"启动失败"。 */
    private fun failureStage(lastError: String?): String {
        val kind = classify(lastError)
        return if (kind == FailureKind.UNKNOWN) {
            "失败（未知，建议跑一键诊断）"
        } else {
            "失败（${kind.label}）"
        }
    }

    /**
     * 下一步建议，**最相关的排在最前**。
     * 界面按顺序渲染成可点的卡片，点一下就跳到对应入口。
     */
    fun hints(
        status: DshStatus?,
        provisioned: Boolean?,
        suAvailable: Boolean,
        mode: EnvMode,
        zstdUnavailableReason: String? = null,
        updateCount: Int = 0,
    ): List<Hint> = buildList {
        // 1) 未授权 root（选择了 root 模式但没有 su）
        if (mode == EnvMode.ROOT && !suAvailable) {
            add(
                Hint(
                    title = "未获得 root 权限",
                    detail = "你选择了 Root 模式，但设备上没有可用的 su。请在 KernelSU/Magisk 管理器里" +
                        "给「SunsetLinux」授权；如果设备无法 root，可在引导里改选「非 root 模式」。",
                    action = "查看引导",
                    target = HintTarget.ROOT_GRANT,
                )
            )
        }

        // 2) 未部署
        if (provisioned == false) {
            add(
                Hint(
                    title = "环境尚未部署",
                    detail = if (mode == EnvMode.ROOT) {
                        "没有找到 linuxctl。Root 模式需要先安装 KernelSU 模块（模块会把运行时铺到 /data/sunsetlinux），" +
                            "重启后再回来执行部署。"
                    } else {
                        "没有找到 linuxctl。非 root 模式需要先把 proot 运行时铺到 App 私有目录。"
                    },
                    action = "去部署",
                    target = HintTarget.PROVISION,
                )
            )
        }

        val state = status?.state
        val err = status?.lastError

        // 3) 出错：按归因给建议
        if (state == EnvState.ERROR || (state == EnvState.UNKNOWN && err != null)) {
            when (classify(err)) {
                FailureKind.MOUNT -> add(
                    Hint(
                        title = "挂载失败",
                        detail = "环境根的分层挂载没成功（overlay/erofs/loop 设备）。先跑一次自检，" +
                            "它会报告内核能力、层完整性与 SELinux denials。",
                        action = "运行诊断",
                        target = HintTarget.DOCTOR,
                    )
                )

                FailureKind.PORT -> add(
                    Hint(
                        title = "端口被占用",
                        detail = "DSH 监听的端口已被别的进程占用。可以到设置里换一个端口，然后重启环境。",
                        action = "改端口",
                        target = HintTarget.SETTINGS_PORT,
                    )
                )

                FailureKind.PERMISSION -> add(
                    Hint(
                        title = "权限不足",
                        detail = "运行时报了权限/能力错误。请确认 root 授权仍然有效（有时会被系统回收），" +
                            "必要时重新授权后再启动。",
                        action = "检查授权",
                        target = HintTarget.ROOT_GRANT,
                    )
                )

                FailureKind.MISSING -> add(
                    Hint(
                        title = "层或文件缺失",
                        detail = "镜像文件/层不完整。可以到更新页比对频道清单（只下载变化的那一层），" +
                            "或重新执行一次部署。",
                        action = "检查更新",
                        target = HintTarget.UPDATES,
                    )
                )

                FailureKind.RUNTIME -> add(
                    Hint(
                        title = "环境内的 Node / DSH 有问题",
                        detail = "环境起来了但里面的 Node 或 DSH 跑不起来（层不完整最常见）。" +
                            "到更新页比对频道清单，只下载变化的那一层；或重新部署一次。",
                        action = "检查更新",
                        target = HintTarget.UPDATES,
                    )
                )

                // 兜底：**不猜**。直接引导用户跑一键诊断，并把输出发出来。
                FailureKind.UNKNOWN, FailureKind.NONE -> add(
                    Hint(
                        title = "启动失败（原因未知）",
                        detail = "环境报了错，但按文案无法归因。**先跑一次一键诊断**：它会检查内核能力、" +
                            "层完整性、端口占用与 SELinux denials，输出可直接复制/导出给开发者。",
                        action = "运行诊断",
                        target = HintTarget.DOCTOR,
                    )
                )
            }
        }

        // 4) 在跑但 Web 不健康
        if (state == EnvState.RUNNING && status.dshHealthy == false) {
            add(
                Hint(
                    title = "Web 端口无响应",
                    detail = "环境进程在跑，但 DSH Web 没有响应。可能还在初始化（首次启动会慢），" +
                        "也可能是端口没写对。稍等几秒刷新，仍不行就跑诊断。",
                    action = "运行诊断",
                    target = HintTarget.DOCTOR,
                )
            )
        }

        // 5) 有可用更新
        if (updateCount > 0) {
            add(
                Hint(
                    title = "有 $updateCount 个层可更新",
                    detail = "频道里已有新版本。更新只下载变化的那一层，装完会自动重启环境。",
                    action = "去更新",
                    target = HintTarget.UPDATES,
                )
            )
        }

        // 6) zstd 不可用（只影响下载体积，不影响功能）
        if (zstdUnavailableReason != null) {
            add(
                Hint(
                    title = "本机未启用 zstd",
                    detail = "$zstdUnavailableReason；如果频道提供了 gzip 回退产物，更新会自动用它（体积略大）。",
                    action = "详见更新页",
                    target = HintTarget.UPDATES,
                )
            )
        }

        // 7) 兜底：无论什么状态，都留一个"看日志/跑自检"的口子
        add(
            Hint(
                title = "还是不对？",
                detail = "侧边栏可以「导出排障包」：里面含 status 原始 JSON、环境日志、崩溃堆栈与设备信息，" +
                    "把它发给开发者基本能一次定位。",
                action = "导出排障包",
                target = HintTarget.LOGS,
            )
        )
    }
}
