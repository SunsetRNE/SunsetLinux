package io.github.sunsetrne.sunsetlinux.core

import io.github.sunsetrne.sunsetlinux.TestPaths
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * 「首次部署向导真的会建层」的**源码级契约**（跑不了真机 su，所以能守的是这些结构事实）。
 *
 * 背景（真机踩过）：`linuxctl provision` 只建目录 / `upper.img` / 配置，**从不构建层**；
 * 而向导原先只调它 —— 于是没有预置层的机器上，向导必然以"provision 失败"收场。
 * 下面每条断言都在守"那条路还接着"：
 *
 * | 断言 | 守的是什么 |
 * |---|---|
 * | 向导里有 `ProvisionPlan.nextStep` | 分诊逻辑还在（不是又变回"失败就结束"） |
 * | 向导里有 `streamDeviceProvision` | 缺层时会真的去跑设备侧原生构建 |
 * | `LinuxCtl` 里有模块路径 `/data/adb/modules/sunsetlinux/bin/device-provision.sh` | 脚本路径没写错/漂移 |
 * | 构建命令带 `--seeds` | 种子目录要传给脚本，否则它在默认目录找不到种子 |
 * | `Proc.stream` 收 stdout | `linuxctl provision` 的 JSON 打在 stdout；丢了它就没法分诊 |
 */
class ProvisionWiringTest {

    private val srcDir = File(
        TestPaths.repoRoot,
        "app/app/src/main/java/io/github/sunsetrne/sunsetlinux",
    )

    private fun read(rel: String): String {
        val f = File(srcDir, rel)
        assertTrue("找不到 ${f.path}（文件被改名或移动了？）", f.isFile)
        return f.readText()
    }

    @Test
    fun `向导会用 ProvisionPlan 分诊并且缺层时真去构建`() {
        val activity = read("ProvisionActivity.kt")
        assertTrue("向导里没有 ProvisionPlan.nextStep（分诊被删了？）", activity.contains("ProvisionPlan.nextStep"))
        assertTrue("向导里没有 streamDeviceProvision（缺层时不会去构建）", activity.contains("streamDeviceProvision"))
        assertTrue(
            "向导没有用 provision 的 stdout 判缺层（stdout 里的 missing_layers 是唯一依据）",
            activity.contains("ProvisionPlan.missingLayers(provision.stdout)"),
        )
    }

    @Test
    fun `构建脚本路径与参数写对了`() {
        val ctl = read("core/LinuxCtl.kt")
        assertTrue(
            "LinuxCtl 里没有模块里的 device-provision.sh 路径",
            ctl.contains("/data/adb/modules/sunsetlinux/bin/device-provision.sh"),
        )
        assertTrue("LinuxCtl 没有把 --seeds 传给构建脚本", ctl.contains("--seeds"))
        assertTrue(
            "构建脚本必须用 /system/bin/sh 跑（模块目录不一定带执行位，且设备上没有 bash）",
            ctl.contains("exec /system/bin/sh"),
        )
    }

    @Test
    fun `Proc_stream 必须把 stdout 带回来`() {
        val proc = read("core/Proc.kt")
        assertTrue(
            "Proc.stream 又丢掉了 stdout —— 向导将无法读出 missing_layers",
            proc.contains("stdout.append(line)"),
        )
    }

    @Test
    fun `两个向导都要显示 root 与模块的可解释状态`() {
        for (rel in listOf("ProvisionActivity.kt", "ui/WelcomeState.kt")) {
            val text = read(rel)
            assertTrue("$rel 没读 root 状态（DeviceStatus.root）", text.contains("DeviceStatus.root("))
            assertTrue("$rel 没读模块状态（DeviceStatus.module）", text.contains("DeviceStatus.module("))
        }
        val provision = read("ProvisionActivity.kt")
        val welcome = read("ui/WelcomeScreen.kt")
        for ((name, text) in listOf("ProvisionActivity.kt" to provision, "ui/WelcomeScreen.kt" to welcome)) {
            assertTrue("$name 没有把「下一步」提示渲染出来（hint）", text.contains(".hint"))
        }
    }
}
