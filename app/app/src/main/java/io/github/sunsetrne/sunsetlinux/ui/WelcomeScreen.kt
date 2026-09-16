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
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                StepChip(1, "选模式", state.step.ordinal >= WelcomeStep.MODE.ordinal)
                StepChip(2, "准备", state.step.ordinal >= WelcomeStep.BRANCH.ordinal)
                StepChip(3, "部署", state.step.ordinal >= WelcomeStep.DEPLOY.ordinal)
                StepChip(4, "完成", state.step.ordinal >= WelcomeStep.DONE.ordinal)
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
                    // ★ 状态后面跟"下一步做什么"：root 与模块各自的建议
                    listOfNotNull(state.rootProbe?.hint, state.module?.hint).forEach { hint ->
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
                    WelcomeStep.MODE -> Button(onClick = { state.next() }) { Text("下一步") }

                    WelcomeStep.BRANCH -> {
                        val ready = state.moduleReady
                        Button(
                            onClick = { state.next() },
                            enabled = if (state.mode == EnvMode.ROOT) {
                                state.moduleAcknowledged || ready
                            } else {
                                true
                            },
                        ) {
                            Text(
                                when {
                                    state.mode != EnvMode.ROOT -> "继续"
                                    ready -> "模块已就绪，继续"
                                    else -> "已装好模块，继续"
                                },
                            )
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
    Text("选择运行方式", style = MaterialTheme.typography.titleMedium)
    Spacer(Modifier.height(4.dp))
    Text(
        text = "两者都能跑 DSH，但能力差距很大 —— 下面把差距写清楚，请按自己的设备情况选。",
        style = MaterialTheme.typography.bodySmall,
        color = TextSecondary,
    )
    Spacer(Modifier.height(14.dp))

    ModeCard(
        selected = state.mode == EnvMode.ROOT,
        onClick = { state.selectMode(EnvMode.ROOT) },
        title = "Root 模式",
        badge = "推荐 · 能力完整",
        badgeColor = Accent,
        available = "需要 KernelSU / Magisk 已 root",
        rows = listOf(
            "真 uid=0 + 真 chroot + mount/UTS 命名空间隔离" to true,
            "overlayfs 三层分层（EROFS 只读层 + ext4 可写层），可快照/回滚" to true,
            "真 capabilities：环境内可以 mount、改主机名" to true,
            "sdcard 走 /mnt/pass_through 直挂，绕开 FUSE" to true,
            "环境由 KernelSU 模块开机启动，**与 App 生命周期解耦**（杀掉 App 不影响 DSH）" to true,
            "需要先装模块并重启一次" to false,
        ),
    )

    Spacer(Modifier.height(12.dp))

    ModeCard(
        selected = state.mode == EnvMode.PROOT,
        onClick = { state.selectMode(EnvMode.PROOT) },
        title = "非 root 模式",
        badge = "兼容 · 免 root",
        badgeColor = TextSecondary,
        available = "任何设备都能用（含未 root）",
        rows = listOf(
            "PRoot（ptrace 系统调用翻译）虚拟环境，免 root 即可运行" to true,
            "环境目录在 App 私有空间，卸载 App 会一起丢掉" to true,
            "无真 capabilities：`id` 显示 0 是 PRoot 伪造的，不能 mount" to false,
            "sdcard 走 FUSE，性能与兼容性都受限" to false,
            "**环境随 App 进程存活**，App 被杀环境就停" to false,
            "不需要刷机 / 不需要重启" to true,
        ),
    )

    if (state.suAvailable == false && state.mode == EnvMode.ROOT) {
        Spacer(Modifier.height(12.dp))
        WarnBox(
            "本机当前检测不到可用的 su。你可以继续选 Root 模式（先去 KernelSU/Magisk 里授权本应用），" +
                "但如果不打算 root，请改选非 root 模式。"
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
        Text("非 root 模式：准备虚拟环境", style = MaterialTheme.typography.titleMedium)
        Spacer(Modifier.height(6.dp))
        Text(
            text = "免 root 的 PRoot 环境不需要刷模块，但**能力受限**（无真 capabilities、绕不开 FUSE、" +
                "环境随 App 进程）。下面两步铺好运行时即可。",
            style = MaterialTheme.typography.bodySmall,
            color = TextSecondary,
        )
        Spacer(Modifier.height(14.dp))

        DshCard(Modifier.fillMaxWidth()) {
            Column(Modifier.fillMaxWidth()) {
                SectionLabel("第 1 步：铺 proot 运行时")
                Spacer(Modifier.height(8.dp))
                NumberedLine(1, "**这个 APK 自带**宿主脚本与 proot 二进制（assets/proot-runtime + 内嵌包）")
                NumberedLine(2, "到「更新 → 本机包 → 离线安装」点一下，它会铺到 files/sunsetlinux/（即 \$APP_FILES/sunsetlinux）")
                NumberedLine(3, "铺完 files/sunsetlinux/bin/linuxctl 必须存在且可执行（页面上会写『契约路径已就位』）")
                Spacer(Modifier.height(10.dp))
                Text(
                    text = "proot 模式的 linuxctl 是**宿主侧脚本**：它自己会判断是否被 ptrace 包裹，" +
                        "被包裹（即在 proot 内）会直接拒绝运行 —— 所以必须由 App 从外面调用。",
                    style = MaterialTheme.typography.labelSmall,
                    color = TextMuted,
                )
            }
        }

        Spacer(Modifier.height(12.dp))

        DshCard(Modifier.fillMaxWidth()) {
            Column(Modifier.fillMaxWidth()) {
                SectionLabel("第 2 步：铺 rootfs 与层")
                Spacer(Modifier.height(8.dp))
                Text(
                    text = "进入下一步的「部署向导」：可以用频道下载层，也可以用离线种子（--seed）在设备侧完成。",
                    style = MaterialTheme.typography.bodySmall,
                    color = TextSecondary,
                )
                Spacer(Modifier.height(10.dp))
                OutlinedButton(onClick = onOpenProvision, modifier = Modifier.fillMaxWidth()) {
                    Text("直接打开部署向导")
                }
            }
        }
    }
}

// ─────────────────────────────────────────────────── ③ 部署

@Composable
private fun StepDeploy(state: WelcomeState, provisioned: Boolean?, onOpenProvision: () -> Unit) {
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
