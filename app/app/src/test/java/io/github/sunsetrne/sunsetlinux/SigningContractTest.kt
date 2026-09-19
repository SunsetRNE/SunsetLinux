package io.github.sunsetrne.sunsetlinux

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
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
        // ★ 0.3.0：内置矩阵（两个 App × 若干档位）的**单一事实源**是
        //   tools/offline-bundle/variants.json —— Gradle 的 flavor、offline-bundle 工具、
        //   pipeline 的编译矩阵、下载页全都读它。所以这里断言的是"构建脚本真的在读它"
        //   （而不是把 id 抄一份），以及**身份约束**（包名不能改错 —— 改错会让用户装成
        //   另一个 App，KernelSU 授权与 App 数据全都对不上）。
        val variantsJson = File(repo, "tools/offline-bundle/variants.json")
        assertTrue("找不到 ${variantsJson.path}（内置矩阵的唯一事实源）", variantsJson.isFile)
        assertTrue(
            "build.gradle.kts 必须从 variants.json 读内置矩阵（否则就是又抄了一份，会漂移）",
            buildText.contains("offline-bundle/variants.json") && buildText.contains("variantSpecs"),
        )
        assertTrue(
            "flavor 必须是**单维度、一个组合一个 flavor**（2026-09-19 决策变更：6 个组合 → 2 个 —— " +
                "组合数不再等于 edition × tier 的乘积，硬撑两维会生成没有定义的组合、配置期报错）",
            buildText.contains("flavorDimensions += \"variant\"") && buildText.contains("variantList"),
        )
        val spec = org.json.JSONObject(variantsJson.readText())
        val editions = spec.getJSONObject("editions")
        val variants = spec.getJSONObject("variants")
        val tiers = spec.getJSONObject("tiers")
        assertEquals("必须是两个 App（root / proot）", 2, editions.length())
        assertTrue("editions 里必须有 root 与 proot", editions.has("root") && editions.has("proot"))
        // ★ 2026-09-19 维护决策变更：组合收敛到 2 个（root-minimal / proot-full）。
        //   这里钉住**收敛的结果**与"可变 DSH"的定义，而不是旧矩阵的"至少三档"。
        assertEquals("组合必须恰好两个（决策变更后不再有中间档）", 2, variants.length())
        assertTrue("root 侧保留 root-minimal", variants.has("root-minimal"))
        assertTrue("proot 侧保留 proot-full", variants.has("proot-full"))
        assertEquals("档位只剩 minimal 与 full（base 已随收敛归档）", 2, tiers.length())
        val dec = spec.optJSONObject("_decision")?.optJSONObject("variable_dsh")
        assertTrue(
            "variants.json 必须留下「可变 DSH」的定义（允许移除 / 默认不变 / 回滚按版本）",
            dec != null && dec.has("允许移除") && dec.has("默认不变") && dec.has("回滚按版本"),
        )
        val appIds = mutableListOf<String>()
        for (ed in editions.keys()) {
            val o = editions.getJSONObject(ed)
            val id = o.getString("application_id")
            appIds += id
            assertTrue("包名必须以 io.github.sunsetrne.sunsetlinux. 开头（$ed=$id）",
                id.startsWith("io.github.sunsetrne.sunsetlinux."))
            assertFalse("不能再用拆分前的包名（会和已装的旧 App 撞车）：$id",
                id == "io.github.sunsetrne.sunsetlinux")
            assertTrue("edition $ed 必须声明 mode（root/proot）", o.getString("mode") in listOf("root", "proot"))
        }
        assertEquals("两个 App 的包名必须不同（否则装一个覆盖另一个）", appIds.size, appIds.distinct().size)
        for (id in variants.keys()) {
            val v = variants.getJSONObject(id)
            val ed = v.getString("edition")
            val tier = v.getString("tier")
            assertTrue("variants.json 里的 $id 指向未知 edition：$ed", editions.has(ed))
            assertTrue("variants.json 里的 $id 指向未知 tier：$tier", tiers.has(tier))
            assertEquals("组合 id 必须等于 <edition>-<tier>（$id）", "$ed-$tier", id)
            assertTrue("$id 缺 embed 数组", v.has("embed"))
        }
        // APK 名必须带组合名，否则下载下来全是 app-debug.apk，用户分不清哪个是哪个
        assertTrue(
            "APK 命名必须带组合名（AGP 9 只能用 VariantOutputImpl.outputFileName）",
            buildText.contains("VariantOutputImpl") && buildText.contains("outputFileName"),
        )
        // ★ 两个 App 装在同一台手机上，**桌面标签必须能分辨** —— 0.3.0 拆版时真的漏了：
        //   只改了 main/res 的 app_name，而 `src/<edition>/res/` 根本不存在，
        //   实测两个图标都叫 "SunsetLinux"（用户点哪个纯靠猜）。
        //   标签来源必须是 variants.json 的 editions[].label（不在构建脚本里抄第二份）；
        //   另外 AGP 9 的 `resValues` 特性默认**关闭**，不开就是配置阶段直接失败。
        assertTrue(
            "桌面标签必须按 edition 设：resValue(\"string\", \"app_name\", e.label)",
            buildText.contains("resValue(\"string\", \"app_name\", e.label)"),
        )
        assertTrue(
            "AGP 9 的 resValues 特性默认关闭，必须显式打开（否则配置阶段就报 feature is disabled）",
            buildText.contains("resValues = true"),
        )
        // ⚠️ org.json 的 keys() 返回的是 **Iterator**（不是 Iterable）：要么 for-in
        //   （Kotlin 有 `Iterator.iterator()` 扩展），要么先 `asSequence()`——
        //   直接 `.map {}` 是编不过的（实测踩过）。
        val editionLabels = editions.keys().asSequence()
            .map { editions.getJSONObject(it).getString("label") }
            .toList()
        assertEquals("两个 App 的桌面标签必须不同（否则用户分不清点哪个）", editionLabels.size, editionLabels.distinct().size)
    }
}
