package io.github.sunsetrne.sunsetlinux

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * **签名与版本号的源码级契约**。
 *
 * 背景（真机上会咬人，不是洁癖）：
 *   `build.gradle.kts` 原来**没有任何 signingConfig** → AGP 用各机器自己生成的
 *   `~/.android/debug.keystore`。CI runner 是临时的，于是**每次发布都是一把新 key**：
 *     · 用户装新版 → `INSTALL_FAILED_UPDATE_INCOMPATIBLE`（必须先卸载，App 数据清空）
 *     · KernelSU 的 root 授权按【包名 + 签名】记录 → 已授予的授权**全部作废**
 *     · 系统不把新版当成同一应用的升级
 *   实测（2026-09-16）：CI 发布的 APK 与本机构建的 APK 都是 `CN=Android Debug` 自签，
 *   但证书 SHA-256 一个是 `84be9523…`、一个是 `3b68b616…`。
 *
 * 这几条断言就是把"签字钥匙必须固定"钉在源码上 —— 谁再把 signingConfig 删了，测试会红。
 */
class SigningContractTest {

    private val repo = TestPaths.repoRoot
    private val gradle = File(repo, "app/app/build.gradle.kts")

    /** 版本号的**唯一事实源**（放在 Gradle 根，不是模块目录）。 */
    private val versionProps = File(repo, "app/version.properties")
    private val keystore = File(repo, "app/app/debug.keystore")
    private val gitignore = File(repo, ".gitignore")

    private fun read(f: File): String {
        assertTrue("找不到 ${f.path}", f.isFile)
        return f.readText()
    }

    @Test
    fun `仓库里必须有一把固定的调试密钥`() {
        assertTrue("app/app/debug.keystore 不存在 —— 没有它，每次构建都会用各机器随机生成的 debug key", keystore.isFile)
        assertTrue("app/app/debug.keystore 是空文件？", keystore.length() > 0)
    }

    @Test
    fun `debug 与 release 都必须绑定同一把固定签名配置`() {
        val text = read(gradle)
        assertTrue("build.gradle.kts 里没有 signingConfigs —— 会退回各机器随机生成的 debug key", text.contains("signingConfigs {"))
        assertTrue(
            "两个 buildType 都要绑到 named(\"sunsetlinux\") 这份配置上（漏一个就会出现两把签名）",
            Regex("""signingConfig = signingConfigs\.getByName\("sunsetlinux"\)""").findAll(text).count() >= 2,
        )
        assertTrue(
            "没有环境变量时必须回落到仓库内那把固定密钥（storeFile = file(\"debug.keystore\")）",
            text.contains("file(\"debug.keystore\")"),
        )
        assertTrue(
            "要支持用 Secret 换成私有发布密钥（环境变量 SUNSETLINUX_KEYSTORE）",
            text.contains("SUNSETLINUX_KEYSTORE"),
        )
    }

    @Test
    fun `固定密钥不能被 gitignore 静默忽略`() {
        val text = read(gitignore)
        assertTrue(
            ".gitignore 里有 *.keystore，必须显式放行 app/app/debug.keystore —— " +
                "否则它进不了仓库，CI 上 storeFile 找不到（或悄悄回落到随机 key）",
            text.contains("!app/app/debug.keystore"),
        )
    }

    @Test
    fun `发布前要推进版本号`() {
        // 版本号的唯一事实源是 app/version.properties（build.gradle.kts 只读它）——
        // 所以这里读那份，而不是去 grep 构建脚本里的字面量。
        val text = read(versionProps)
        val code = Regex("""versionCode\s*=\s*(\d+)""").find(text)?.groupValues?.get(1)?.toIntOrNull()
        val name = Regex("""versionName\s*=\s*([0-9.]+)""").find(text)?.groupValues?.get(1)
        assertTrue("解析不出 versionCode（app/version.properties）", code != null)
        assertTrue("解析不出 versionName（app/version.properties）", name != null)
        assertTrue("versionCode 必须 ≥ 2（0.1.0/1 是首个版本，早就过时了）", (code ?: 0) >= 2)
        assertFalse("versionName 还停在 0.1.0：发布前请推进版本号", name == "0.1.0")

        // 构建脚本必须**真的在读**那份文件，否则"唯一事实源"是假的（改了不生效最坑）
        val buildText = read(gradle)
        assertTrue(
            "app/build.gradle.kts 必须从 version.properties 读版本",
            buildText.contains("version.properties") && buildText.contains("engineeringVersionCode"),
        )
        // 四个内置组合都要建得出来
        for (id in listOf("minimal", "ubuntu", "ubuntu-proot", "ubuntu-proot-dsh")) {
            assertTrue("构建脚本里没有组合 $id", buildText.contains("\"$id\""))
        }
        // APK 名必须带组合名，否则下载下来全是 app-debug.apk，用户分不清哪个是哪个
        assertTrue(
            "APK 命名必须带组合名（AGP 9 只能用 VariantOutputImpl.outputFileName）",
            buildText.contains("VariantOutputImpl") && buildText.contains("outputFileName"),
        )
    }
}
