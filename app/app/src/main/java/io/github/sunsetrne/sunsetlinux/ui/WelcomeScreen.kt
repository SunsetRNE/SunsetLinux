package io.github.sunsetrne.sunsetlinux.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material3.Button
import androidx.compose.material3.Checkbox
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import io.github.sunsetrne.sunsetlinux.core.Edition
import io.github.sunsetrne.sunsetlinux.core.EnvMode
import io.github.sunsetrne.sunsetlinux.core.formatBytes
import io.github.sunsetrne.sunsetlinux.ui.components.DshCard
import io.github.sunsetrne.sunsetlinux.ui.components.LogPanel
import io.github.sunsetrne.sunsetlinux.ui.components.Pill
import io.github.sunsetrne.sunsetlinux.ui.components.SectionLabel
import io.github.sunsetrne.sunsetlinux.ui.theme.WarnTone
import io.github.sunsetrne.sunsetlinux.ui.theme.Accent
import io.github.sunsetrne.sunsetlinux.ui.theme.Mono2
import io.github.sunsetrne.sunsetlinux.ui.theme.MonoFamily
import io.github.sunsetrne.sunsetlinux.ui.theme.Danger
import io.github.sunsetrne.sunsetlinux.ui.theme.TextSecondary
import io.github.sunsetrne.sunsetlinux.ui.theme.StateRunning
import io.github.sunsetrne.sunsetlinux.ui.theme.TextMuted
import io.github.sunsetrne.sunsetlinux.ui.theme.TextSecondary

/**
 * 首启引导界面：**选模式 → 分支指引 → 部署 → 完成**。
 *
 * 诚实性要求：两条路径的能力差距必须写清楚，不能让用户以为 root 与非 root 等价。
 */
@Composable
fun WelcomeScreen(
    state: WelcomeState,
    onOpenProvision: () -> Unit,
    onFinish: () -> Unit,
) {
    val su = state.suAvailable
    val provisioned = state.provisioned
    val logs by state.log.collectAsState()

    Column(
        Modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.background)
            .safeDrawingPadding(),
    ) {
        // 顶部：标题 + 步骤指示
        Column(Modifier.padding(horizontal = 20.dp, vertical = 14.dp)) {
            Text("欢迎使用 SunsetLinux", style = MaterialTheme.typography.headlineSmall)
            Text(
                text = "先选一种运行方式，之后随时可以在侧边栏里改。",
                style = MaterialTheme.typography.bodySmall,
                color = TextMuted,
            )
            Spacer(Modifier.height(12.dp))
            // ★ 步骤名**按模式分开**：两条路要准备的东西完全不同
            //   （root：装 KernelSU 模块 → 建层；免 root：铺内置脚本 → 铺 rootfs + 层）。
            //   以前四个 chip 对两种模式用同一套词（"准备"），用户根本不知道免 root 该准备什么。
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                // 单模式版（0.3.0 起）：第 1 步是"这个 App 是什么"，不是"选模式"
                val labels = if (state.mode == EnvMode.PROOT) {
                    listOf("本版说明", "铺运行时", "铺环境", "完成")
                } else {
                    listOf("本版说明", "装模块", "部署", "完成")
                }
                StepChip(1, labels[0], state.step.ordinal >= WelcomeStep.MODE.ordinal)
                StepChip(2, labels[1], state.step.ordinal >= WelcomeStep.BRANCH.ordinal)
                StepChip(3, labels[2], state.step.ordinal >= WelcomeStep.DEPLOY.ordinal)
                StepChip(4, labels[3], state.step.ordinal >= WelcomeStep.DONE.ordinal)
            }
        }

        Column(
            Modifier
                .weight(1f)
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 20.dp),
        ) {
            when (state.step) {
                WelcomeStep.MODE -> StepMode(state)
                WelcomeStep.BRANCH -> StepBranch(state, onOpenProvision)
                WelcomeStep.DEPLOY -> StepDeploy(state, provisioned, onOpenProvision)
                WelcomeStep.DONE -> StepDone(state, provisioned)
            }

            Spacer(Modifier.height(16.dp))

            // 探测结果与实时反馈（用户最需要知道的就这三行）
            DshCard(Modifier.fillMaxWidth()) {
                Column(Modifier.fillMaxWidth()) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        SectionLabel("环境检测")
                        Spacer(Modifier.weight(1f))
                        TextButton(onClick = { state.probe() }, enabled = !state.probing) {
                            if (state.probing) {
                                CircularProgressIndicator(modifier = Modifier.size(14.dp), strokeWidth = 2.dp)
                            } else {
                                Text("重新检测")
                            }
                        }
                    }
                    Spacer(Modifier.height(6.dp))
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        if (Edition.needsSu) {
                            Pill(
                                text = state.rootProbe?.label ?: when (su) {
                                    true -> "su 可用"
                                    false -> "无 su"
                                    null -> "检测中…"
                                },
                                color = when {
                                    state.rootProbe?.granted == true -> StateRunning
                                    su == false -> WarnTone
                                    else -> TextMuted
                                },
                                filled = true,
                            )
                        } else {
                            // 免 root 版：不探测 su，显示"本版不需要 root"才对
                            Pill("本版不需要 root", color = StateRunning, filled = true)
                        }
                        if (state.mode == EnvMode.PROOT) {
                            // 免 root 模式：模块与它无关（装不装都一样），要看的就三件东西
                            Pill(
                                text = when {
                                    state.proot == null -> "脚本检测中…"
                                    state.proot!!.scriptsReady -> "bin/linuxctl 已就位"
                                    else -> "缺宿主脚本"
                                },
                                color = when {
                                    state.proot == null -> TextMuted
                                    state.proot!!.scriptsReady -> StateRunning
                                    else -> Danger
                                },
                                filled = true,
                            )
                            Pill(
                                text = when {
                                    state.proot == null -> "rootfs 检测中…"
                                    state.proot!!.rootfsReady -> "rootfs 已就位"
                                    state.proot!!.hasRootSource -> "可解包出 rootfs"
                                    else -> "缺 rootfs"
                                },
                                color = when {
                                    state.proot == null -> TextMuted
                                    state.proot!!.rootfsReady -> StateRunning
                                    state.proot!!.hasRootSource -> WarnTone
                                    else -> Danger
                                },
                                filled = true,
                            )
                        } else {
                            Pill(
                                text = state.module?.label ?: "模块检测中…",
                                color = when {
                                    state.module == null -> TextMuted
                                    // 读不到（su 没拿到）用中性色：把"不知道"画成"危险"会误导用户
                                    !state.module!!.readable -> TextMuted
                                    !state.module!!.installed -> Danger
                                    state.module!!.disabled || state.module!!.pendingReboot -> WarnTone
                                    else -> StateRunning
                                },
                                filled = true,
                            )
                        }
                        Pill(
                            text = when (provisioned) {
                                true -> "linuxctl 已就位"
                                false -> "需要部署"
                                null -> "检测中…"
                            },
                            color = when (provisioned) {
                                true -> StateRunning
                                false -> Danger
                                null -> TextMuted
                            },
                            filled = true,
                        )
                    }
                    // ★ 状态后面跟"下一步做什么"：两种模式各自的建议
                    listOfNotNull(
                        if (Edition.needsSu) state.rootProbe?.hint else null,
                        if (state.mode == EnvMode.PROOT) {
                            state.prootSteps.firstOrNull { !it.done }?.let { "下一步：${it.title}" }
                        } else {
                            state.module?.hint
                        },
                    ).forEach { hint ->
                        Spacer(Modifier.height(8.dp))
                        Text(
                            text = "→ $hint",
                            style = MaterialTheme.typography.bodySmall,
                            color = WarnTone,
                        )
                    }
                    Spacer(Modifier.height(10.dp))
                    LogPanel(
                        lines = logs,
                        height = 132.dp,
                        autoFollow = true,
                        emptyText = "检测结果会显示在这里",
                    )
                }
            }

            Spacer(Modifier.height(24.dp))
        }

        // 底部动作条
        Surface(color = MaterialTheme.colorScheme.surface, tonalElevation = 3.dp) {
            Row(
                Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 20.dp, vertical = 12.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                if (state.step != WelcomeStep.MODE) {
                    TextButton(onClick = { state.back() }) { Text("上一步") }
                }
                Spacer(Modifier.weight(1f))
                when (state.step) {
                    WelcomeStep.MODE -> Button(onClick = { state.next() }) {
                        Text(if (Edition.isRoot) "明白了，去装模块" else "明白了，去铺运行时")
                    }

                    WelcomeStep.BRANCH -> {
                        if (state.mode == EnvMode.PROOT) {
                            // 免 root 的下一步页**同样**有铺环境按钮，所以这里不设硬门槛：
                            // 铺好了随时能继续，没铺好也不会走进死胡同（这正是上一版的问题）。
                            val scripts = state.proot?.scriptsReady == true
                            Button(onClick = { state.next() }) {
                                Text(if (scripts) "运行时已就绪，继续" else "继续（下一步也能铺）")
                            }
                        } else {
                            val ready = state.moduleReady
                            Button(
                                onClick = { state.next() },
                                enabled = state.moduleAcknowledged || ready,
                            ) {
                                Text(if (ready) "模块已就绪，继续" else "已装好模块，继续")
                            }
                        }
                    }

                    WelcomeStep.DEPLOY -> {
                        if (provisioned == true) {
                            Button(onClick = { state.next() }) { Text("已就位，继续") }
                        } else {
                            Button(onClick = onOpenProvision) { Text("去部署") }
                        }
                    }

                    WelcomeStep.DONE -> {
                        Column(horizontalAlignment = Alignment.End) {
                            Button(
                                onClick = {
                                    state.finish()
                                    onFinish()
                                },
                            ) { Text("开始使用") }
                            TextButton(
                                onClick = {
                                    state.startEnvironment { _, _ -> }
                                },
                                enabled = !state.starting && provisioned == true,
                            ) { Text(if (state.starting) "正在启动…" else "顺便启动环境") }
                        }
                    }
                }
            }
        }
    }

    state.message?.let { text ->
        androidx.compose.material3.AlertDialog(
            onDismissRequest = { state.dismissMessage() },
            confirmButton = { TextButton(onClick = { state.dismissMessage() }) { Text("知道了") } },
            title = { Text("提示") },
            text = { Text(text) },
        )
    }
}

@Composable
private fun StepChip(index: Int, label: String, active: Boolean) {
    Surface(
        shape = RoundedCornerShape(999.dp),
        color = if (active) Accent.copy(alpha = 0.16f) else Mono2,
    ) {
        Row(
            Modifier.padding(horizontal = 10.dp, vertical = 4.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                text = index.toString(),
                style = MaterialTheme.typography.labelSmall,
                color = if (active) Accent else TextMuted,
            )
            Spacer(Modifier.width(5.dp))
            Text(
                text = label,
                style = MaterialTheme.typography.labelSmall,
                color = if (active) Accent else TextMuted,
            )
        }
    }
}

// ─────────────────────────────────────────────────── ① 选模式

@Composable
private fun StepMode(state: WelcomeState) {
    // ★ 0.3.0 起两个 App 各自锁死一条路（用户："一个纯 root 流程，一个纯免 root 流程"）。
    //   这一步不再是"选择"，而是把**本版是什么、另一个版本是什么**说清楚 ——
    //   以前那套"先选模式再分叉"的引导正是第 34 条里误导用户的根源。
    Text("这个 App 是「${Edition.labelShort}」", style = MaterialTheme.typography.titleMedium)
    Spacer(Modifier.height(6.dp))
    Text(
        text = "0.3.0 起拆成两个**可同时安装**的 App，每个只走一条路，装哪个就是哪条路；" +
            "想换一条路就装另一个（不会覆盖、数据各管各的）。",
        style = MaterialTheme.typography.bodySmall,
        color = TextSecondary,
    )
    Spacer(Modifier.height(14.dp))

    DshCard(Modifier.fillMaxWidth()) {
        Column(Modifier.fillMaxWidth()) {
            SectionLabel("本版（${Edition.applicationId}）")
            Spacer(Modifier.height(8.dp))
            if (Edition.isRoot) {
                CapabilityLine(true, "真 uid=0 + 真 chroot + mount/UTS 命名空间隔离")
                CapabilityLine(true, "overlayfs 三层（EROFS 只读层 + ext4 可写层），可快照/回滚")
                CapabilityLine(true, "环境由 KernelSU 模块开机启动，**与 App 生命周期解耦**")
                CapabilityLine(false, "需要先装 KernelSU 模块并重启一次（首启引导里可以一键刷入）")
            } else {
                CapabilityLine(true, "免 root、免刷机：铺在 App 私有目录即可运行")
                CapabilityLine(true, "宿主脚本随 APK 内置（一键铺，约 50 KB）")
                CapabilityLine(false, "无真 capabilities：`id` 显示 0 是 proot 伪造的，环境内不能 mount")
                CapabilityLine(false, "**环境随 App 进程存活**：App 被杀/被冻结，环境就停")
            }
        }
    }

    Spacer(Modifier.height(12.dp))

    DshCard(Modifier.fillMaxWidth()) {
        Column(Modifier.fillMaxWidth()) {
            SectionLabel("另一个版本")
            Spacer(Modifier.height(8.dp))
            Text(
                text = if (Edition.isRoot) {
                    "**免 root 版**（io.github.sunsetrne.sunsetlinux.proot）：给不能/不想 root 的设备；" +
                        "能力受限但没有刷机门槛。设备不能 root 时请装它。"
                } else {
                    "**Root 版**（io.github.sunsetrne.sunsetlinux.root）：给已经 root 的设备；" +
                        "真 chroot、开机自启、与 App 解耦，体验明显更好。"
                },
                style = MaterialTheme.typography.bodySmall,
                color = TextSecondary,
            )
            Spacer(Modifier.height(8.dp))
            Text(
                // ★ 这句按 edition 分岔：免 root 版里连"KernelSU"这个词都不该出现
                //   （§六 验收判据是"不出现任何「KernelSU 模块」字样"，而提 root 授权
                //    对免 root 用户也没有任何可操作的含义）。
                text = if (Edition.showsModuleUi) {
                    "两个 App 包名不同：可以同时装、各自独立（root 授权按包名记，两个 App 互不影响）。"
                } else {
                    "两个 App 包名不同：可以同时装、各自独立（免 root 版不需要任何授权）。"
                },
                style = MaterialTheme.typography.labelSmall,
                color = TextMuted,
            )
        }
    }
}

@Composable
private fun CapabilityLine(positive: Boolean, text: String) {
    Row(Modifier.padding(vertical = 2.dp), verticalAlignment = Alignment.Top) {
        Icon(
            imageVector = if (positive) Icons.Filled.CheckCircle else Icons.Filled.Warning,
            contentDescription = null,
            tint = if (positive) StateRunning else WarnTone,
            modifier = Modifier
                .padding(top = 1.dp)
                .size(14.dp),
        )
        Spacer(Modifier.width(7.dp))
        Text(
            text = text,
            style = MaterialTheme.typography.bodySmall,
            color = if (positive) TextSecondary else WarnTone,
        )
    }
}

@Composable
private fun ModeCard(
    selected: Boolean,
    onClick: () -> Unit,
    title: String,
    badge: String,
    badgeColor: Color,
    available: String,
    rows: List<Pair<String, Boolean>>,
) {
    Surface(
        shape = RoundedCornerShape(18.dp),
        color = if (selected) Accent.copy(alpha = 0.10f) else Mono2,
        border = androidx.compose.foundation.BorderStroke(
            1.dp,
            if (selected) Accent.copy(alpha = 0.55f) else MaterialTheme.colorScheme.outline,
        ),
        onClick = onClick,
        modifier = Modifier.fillMaxWidth(),
    ) {
        Column(Modifier.padding(14.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                RadioButton(selected = selected, onClick = onClick)
                Text(title, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
                Spacer(Modifier.width(8.dp))
                Pill(badge, color = badgeColor, filled = true)
            }
            Spacer(Modifier.height(6.dp))
            Text(available, style = MaterialTheme.typography.labelSmall, color = TextMuted)
            Spacer(Modifier.height(10.dp))
            rows.forEach { (text, positive) ->
                Row(Modifier.padding(vertical = 2.dp), verticalAlignment = Alignment.Top) {
                    Icon(
                        imageVector = if (positive) Icons.Filled.CheckCircle else Icons.Filled.Warning,
                        contentDescription = null,
                        tint = if (positive) StateRunning else WarnTone,
                        modifier = Modifier
                            .padding(top = 1.dp)
                            .size(14.dp),
                    )
                    Spacer(Modifier.width(7.dp))
                    Text(
                        text = text,
                        style = MaterialTheme.typography.bodySmall,
                        color = if (positive) TextSecondary else WarnTone,
                    )
                }
            }
        }
    }
}

// ─────────────────────────────────────────────────── ② 分支准备

@Composable
private fun StepBranch(state: WelcomeState, onOpenProvision: () -> Unit) {
    if (state.mode == EnvMode.ROOT) {
        Text("Root 模式：先装 KernelSU 模块", style = MaterialTheme.typography.titleMedium)
        Spacer(Modifier.height(6.dp))
        Text(
            text = "模块负责把运行时铺到 /data/sunsetlinux，并在开机时启动环境（这样环境就不依赖 App 是否存活）。",
            style = MaterialTheme.typography.bodySmall,
            color = TextSecondary,
        )
        Spacer(Modifier.height(14.dp))

        DshCard(Modifier.fillMaxWidth()) {
            Column(Modifier.fillMaxWidth()) {
                SectionLabel("第 1 步：确认 root 授权")
                Spacer(Modifier.height(6.dp))
                when (state.suAvailable) {
                    true -> OkLine("su 可用，可以继续。")
                    false -> WarnBox(
                        "检测不到 su。请打开 KernelSU / Magisk 管理器，给「SunsetLinux」授予 root 权限；" +
                            "如果设备无法 root，请回上一步改选「非 root 模式」。"
                    )

                    null -> Text("正在检测…", style = MaterialTheme.typography.bodySmall, color = TextMuted)
                }
            }
        }

        Spacer(Modifier.height(12.dp))

        DshCard(Modifier.fillMaxWidth()) {
            Column(Modifier.fillMaxWidth()) {
                SectionLabel("第 2 步：刷入模块")
                Spacer(Modifier.height(8.dp))

                val bundled = state.bundled
                if (bundled != null) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Pill(bundled.label, color = Accent, filled = true)
                        Spacer(Modifier.width(8.dp))
                        Text(
                            text = formatBytes(bundled.size),
                            style = MaterialTheme.typography.labelSmall,
                            color = TextMuted,
                        )
                    }
                    Spacer(Modifier.height(10.dp))
                    Text(
                        text = "模块包**已内置在本 App 里**，不用去别处找：点下面的按钮，App 会把它" +
                            "落到设备上并交给 `ksud module install` 刷入（与 KernelSU 管理器的" +
                            "「从本地安装」是同一件事）。",
                        style = MaterialTheme.typography.bodySmall,
                        color = TextSecondary,
                    )
                    Spacer(Modifier.height(10.dp))
                    Button(
                        onClick = { state.flashBundledModule() },
                        enabled = state.suAvailable == true && !state.flashing,
                        modifier = Modifier.fillMaxWidth(),
                    ) {
                        Text(if (state.flashing) "正在刷入…" else "一键刷入内置模块 ${bundled.shortVersion}")
                    }
                    Spacer(Modifier.height(6.dp))
                    OutlinedButton(
                        onClick = { state.exportBundledModule() },
                        enabled = !state.flashing,
                        modifier = Modifier.fillMaxWidth(),
                    ) { Text("导出模块包到 Download（手动安装用）") }
                    Spacer(Modifier.height(2.dp))
                    TextButton(
                        onClick = { state.openModuleManager() },
                        enabled = !state.flashing,
                    ) { Text("打开 KernelSU / Magisk 管理器") }
                    Spacer(Modifier.height(4.dp))
                    Text(
                        text = "刷入后**必须重启手机**（KernelSU 落的是 modules_update/，重启才生效）。" +
                            "重启后回到这里点「重新检测」；模块检测为已启用时，下面的勾选框会自动打上。",
                        style = MaterialTheme.typography.labelSmall,
                        color = WarnTone,
                    )
                } else {
                    WarnBox(
                        "这个 APK 没有内嵌模块包（干净检出或 CI 未附产物时会这样）。" +
                            "两条替代路径：① 侧边栏「关于 → 更新模块」从官方站下载并刷入；" +
                            "② 把仓库里的 dist/sunsetlinux-module-<版本>.zip 传到手机，用下面的手动步骤装。"
                    )
                    Spacer(Modifier.height(10.dp))
                }

                Spacer(Modifier.height(10.dp))
                Text("手动安装（KernelSU 管理器）：", style = MaterialTheme.typography.labelSmall, color = TextMuted)
                Spacer(Modifier.height(4.dp))
                NumberedLine(1, "KernelSU 管理器 → 「模块」→「从本地安装」")
                NumberedLine(
                    2,
                    "选择模块包：Download/" + (bundled?.fileName ?: "sunsetlinux-module-<版本>.zip") +
                        "（上一步导出的那个；也可以直接点「一键刷入」跳过这步）",
                )
                NumberedLine(3, "安装完成后**重启手机**（模块在 late_start 阶段生效）")
                NumberedLine(4, "重启后回到这里，点下面的「重新检测」，再继续部署层")
                Spacer(Modifier.height(10.dp))
                Text(
                    text = "模块包由仓库的 module/mkmodule.sh 产出（dist/sunsetlinux-module-<版本>.zip）。" +
                        "它不删除 /data/sunsetlinux，卸载模块也会保留你的数据。",
                    style = MaterialTheme.typography.labelSmall,
                    color = TextMuted,
                )
            }
        }

        Spacer(Modifier.height(12.dp))

        val ready = state.moduleReady
        Surface(
            shape = RoundedCornerShape(16.dp),
            color = if (ready) StateRunning.copy(alpha = 0.12f) else Mono2,
            modifier = Modifier.fillMaxWidth(),
        ) {
            Row(
                Modifier
                    .fillMaxWidth()
                    .padding(12.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Checkbox(
                    checked = state.moduleAcknowledged || ready,
                    onCheckedChange = { state.acknowledgeModule(it == true) },
                )
                Column(Modifier.weight(1f)) {
                    Text(
                        text = if (ready) "模块已启用（已装 + 已重启）" else "我已经装好模块并重启",
                        style = MaterialTheme.typography.bodyMedium,
                    )
                    Text(
                        text = when {
                            ready -> "设备侧检测到模块已生效，可以直接进入下一步。"
                            state.module?.pendingReboot == true ->
                                "当前：${state.module?.label}。重启手机后回到这里点「重新检测」，勾选框会自动打上。"
                            state.module?.installed == false && state.module?.readable == true ->
                                "还没检测到模块。用上面的「一键刷入内置模块」或手动安装，装完重启。"
                            else -> "勾选后才能进入下一步（层部署需要 /data/sunsetlinux 已就绪）"
                        },
                        style = MaterialTheme.typography.labelSmall,
                        color = if (ready) StateRunning else TextMuted,
                    )
                }
            }
        }
    } else {
        // ── 免 root（proot）分支：**每一步都有按钮**，不再是"去别的页面点一下" ──
        Text("免 root 模式：铺好运行时与 rootfs", style = MaterialTheme.typography.titleMedium)
        Spacer(Modifier.height(6.dp))
        Text(
            text = "免 root 环境不需要刷机、不需要 su，但要把三件东西铺到 App 私有目录：**宿主脚本**" +
                "（bin/linuxctl，APK 内置）、**proot 可执行体**、**一棵 rootfs**（从 base 层解开）。" +
                "下面按顺序点按钮即可；每步做完状态会自动刷新。",
            style = MaterialTheme.typography.bodySmall,
            color = TextSecondary,
        )
        Spacer(Modifier.height(14.dp))

        val steps = state.prootSteps
        if (steps.isEmpty()) {
            DshCard(Modifier.fillMaxWidth()) {
                Column(Modifier.fillMaxWidth()) {
                    SectionLabel("免 root 就绪度")
                    Spacer(Modifier.height(6.dp))
                    Text("正在检测…", style = MaterialTheme.typography.bodySmall, color = TextMuted)
                }
            }
        } else {
            steps.forEachIndexed { index, s ->
                DshCard(Modifier.fillMaxWidth()) {
                    Column(Modifier.fillMaxWidth()) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Icon(
                                imageVector = if (s.done) Icons.Filled.CheckCircle else Icons.Filled.Warning,
                                contentDescription = null,
                                tint = if (s.done) StateRunning else WarnTone,
                                modifier = Modifier.size(15.dp),
                            )
                            Spacer(Modifier.width(6.dp))
                            SectionLabel("第 ${index + 1} 步：${s.title}")
                        }
                        Spacer(Modifier.height(6.dp))
                        Text(s.detail, style = MaterialTheme.typography.bodySmall, color = TextSecondary)
                        if (!s.done && s.actionLabel != null) {
                            Spacer(Modifier.height(10.dp))
                            Button(
                                onClick = {
                                    when (s.step) {
                                        io.github.sunsetrne.sunsetlinux.core.ProotSetup.Step.SCRIPTS ->
                                            state.layProotScripts()
                                        else -> state.provisionProot()
                                    }
                                },
                                enabled = !state.prootBusy,
                                modifier = Modifier.fillMaxWidth(),
                            ) {
                                Text(if (state.prootBusy) "正在执行…" else s.actionLabel)
                            }
                        }
                    }
                }
                Spacer(Modifier.height(10.dp))
            }
        }

        DshCard(Modifier.fillMaxWidth()) {
            Column(Modifier.fillMaxWidth()) {
                SectionLabel("免 root 的能力边界（如实说明）")
                Spacer(Modifier.height(6.dp))
                NumberedLine(1, "没有真 capabilities：`id` 显示 0 是 proot 伪造的，环境内不能 mount")
                NumberedLine(2, "sdcard 走 FUSE，性能与兼容性都受限")
                NumberedLine(3, "**环境随 App 进程存活**：App 被杀/被冻结，环境就停")
                Spacer(Modifier.height(10.dp))
                Text(
                    text = "如果设备能 root，Root 模式的体验要好得多（真 chroot、开机自启、与 App 解耦）。" +
                        "随时可以回上一步改选。",
                    style = MaterialTheme.typography.labelSmall,
                    color = TextMuted,
                )
                Spacer(Modifier.height(10.dp))
                OutlinedButton(onClick = onOpenProvision, modifier = Modifier.fillMaxWidth()) {
                    Text("打开部署向导（频道 / 本机种子）")
                }
            }
        }
    }
}

// ─────────────────────────────────────────────────── ③ 部署

@Composable
private fun StepDeploy(state: WelcomeState, provisioned: Boolean?, onOpenProvision: () -> Unit) {
    if (state.mode == EnvMode.PROOT) {
        // 免 root 的"部署"= 把 rootfs 与层铺好（与上一步同一套动作，这里再给一次入口）
        Text("铺环境（免 root）", style = MaterialTheme.typography.titleMedium)
        Spacer(Modifier.height(6.dp))
        Text(
            text = "免 root 环境铺在 App 私有目录里，卸载 App 会一起丢掉。下面这一步会装内嵌离线包" +
                "（若有）并执行 `linuxctl provision` 把 base 层/种子解成 rootfs。",
            style = MaterialTheme.typography.bodySmall,
            color = TextSecondary,
        )
        Spacer(Modifier.height(14.dp))

        val r = state.proot
        DshCard(Modifier.fillMaxWidth()) {
            Column(Modifier.fillMaxWidth()) {
                SectionLabel("当前状态")
                Spacer(Modifier.height(8.dp))
                when {
                    r == null -> Text("检测中…", style = MaterialTheme.typography.bodySmall, color = TextMuted)
                    r.complete -> OkLine("三件都齐了（宿主脚本 / proot 运行时 / rootfs），可以启动。")
                    else -> WarnBox(
                        "还缺：${r.missingLabel.ifEmpty { "（请重新检测）" }}。" +
                            if (!r.hasRootSource) {
                                " 而且没有可用的 rootfs 来源 —— 先去「更新」页从频道安装 base 层（erofs），" +
                                    "或用本机种子 tar（部署向导里可指定目录）。"
                            } else {
                                " 点下面的按钮即可继续铺。"
                            },
                    )
                }
                Spacer(Modifier.height(12.dp))
                Button(
                    onClick = { state.provisionProot() },
                    enabled = !state.prootBusy,
                    modifier = Modifier.fillMaxWidth(),
                ) { Text(if (state.prootBusy) "正在铺…" else "铺环境（离线包 + provision）") }
                Spacer(Modifier.height(6.dp))
                OutlinedButton(onClick = onOpenProvision, modifier = Modifier.fillMaxWidth()) {
                    Text("打开部署向导（指定种子目录 / 看完整日志）")
                }
            }
        }
        return
    }

    Text("部署环境", style = MaterialTheme.typography.titleMedium)
    Spacer(Modifier.height(6.dp))
    Text(
        text = "部署会建目录树、铺层、建可写层并写入配置；界面是实时输出，卡在哪一步一眼能看到。",
        style = MaterialTheme.typography.bodySmall,
        color = TextSecondary,
    )
    Spacer(Modifier.height(14.dp))

    DshCard(Modifier.fillMaxWidth()) {
        Column(Modifier.fillMaxWidth()) {
            SectionLabel("当前状态")
            Spacer(Modifier.height(8.dp))
            when (provisioned) {
                true -> OkLine("linuxctl 已就位，环境可以启动。")
                false -> WarnBox(
                    "还没有找到 linuxctl。点下面的按钮进入部署向导；Root 模式请确认模块已装好并重启过。"
                )

                null -> Text("检测中…", style = MaterialTheme.typography.bodySmall, color = TextMuted)
            }
            Spacer(Modifier.height(12.dp))
            Button(onClick = onOpenProvision, modifier = Modifier.fillMaxWidth()) {
                Text(if (provisioned == true) "重新执行部署向导" else "打开部署向导")
            }
            Spacer(Modifier.height(6.dp))
            Text(
                text = "部署向导支持：选择频道下载层、指定离线种子目录、实时查看 provision/start 输出。",
                style = MaterialTheme.typography.labelSmall,
                color = TextMuted,
            )
        }
    }
}

// ─────────────────────────────────────────────────── ④ 完成

@Composable
private fun StepDone(state: WelcomeState, provisioned: Boolean?) {
    Text("准备就绪", style = MaterialTheme.typography.titleMedium)
    Spacer(Modifier.height(10.dp))

    DshCard(Modifier.fillMaxWidth()) {
        Column(Modifier.fillMaxWidth()) {
            SectionLabel("本次选择")
            Spacer(Modifier.height(8.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Pill(state.mode.modeLabel, color = Accent, filled = true)
                Pill(
                    text = when (provisioned) {
                        true -> "环境已部署"
                        false -> "尚未部署"
                        null -> "检测中"
                    },
                    color = when (provisioned) {
                        true -> StateRunning
                        false -> WarnTone
                        null -> TextMuted
                    },
                    filled = true,
                )
            }
            Spacer(Modifier.height(10.dp))
            Text(
                text = if (state.mode == EnvMode.ROOT) {
                    "Root 模式下环境由 KernelSU 模块在开机时启动。回到首页后点「启动环境」即可，" +
                        "之后即使杀掉 App，DSH 也会继续运行。"
                } else {
                    "非 root 模式的环境随 App 进程存活。建议回首页后到侧边栏「冻结与省电豁免」里" +
                        "申请电池白名单与墓碑模块豁免，否则后台容易被冻结。"
                },
                style = MaterialTheme.typography.bodySmall,
                color = TextSecondary,
            )
            Spacer(Modifier.height(10.dp))
            Text(
                text = "以后可以在侧边栏「重新部署 / 首启引导」里随时改回来。",
                style = MaterialTheme.typography.labelSmall,
                color = TextMuted,
            )
        }
    }

    Spacer(Modifier.height(12.dp))
    Text(
        text = "提示：如果启动失败，首页会直接告诉你**卡在哪个阶段**并给出可点的下一步；" +
            "侧边栏还能一键跑 doctor 自检、导出排障包。",
        style = MaterialTheme.typography.labelSmall,
        color = TextMuted,
    )
}

// ─────────────────────────────────────────────────── 小件

@Composable
private fun NumberedLine(index: Int, text: String) {
    Row(Modifier.padding(vertical = 3.dp), verticalAlignment = Alignment.Top) {
        Text(
            text = "$index.",
            style = MaterialTheme.typography.labelSmall.copy(fontFamily = MonoFamily),
            color = Accent,
        )
        Spacer(Modifier.width(8.dp))
        Text(text, style = MaterialTheme.typography.bodySmall, color = TextSecondary)
    }
}

@Composable
private fun OkLine(text: String) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Icon(
            Icons.Filled.CheckCircle,
            contentDescription = null,
            tint = StateRunning,
            modifier = Modifier.size(15.dp),
        )
        Spacer(Modifier.width(7.dp))
        Text(text, style = MaterialTheme.typography.bodySmall, color = StateRunning)
    }
}

@Composable
private fun WarnBox(text: String) {
    Surface(shape = RoundedCornerShape(12.dp), color = WarnTone.copy(alpha = 0.12f)) {
        Row(Modifier.padding(10.dp), verticalAlignment = Alignment.Top) {
            Icon(
                Icons.Filled.Info,
                contentDescription = null,
                tint = WarnTone,
                modifier = Modifier.size(15.dp),
            )
            Spacer(Modifier.width(7.dp))
            Text(text, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurface)
        }
    }
}
