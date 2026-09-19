import javax.inject.Inject
import org.gradle.process.ExecOperations
import java.security.MessageDigest
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.Properties
import java.util.TimeZone

plugins {
    alias(libs.plugins.android.application)
    // AGP 9 起 Kotlin 支持内置，**不能**再 apply org.jetbrains.kotlin.android。
    // Compose 只需要编译器插件。
    alias(libs.plugins.kotlin.compose)
}

// ============================================================================
// 版本与命名（每次发版只改 app/version.properties；说明写在 app/VERSION-NOTES.md）
//
// 这里的做法参照另一份构建笔记（Branchbase 的 docs/specs/BUILD-NOTES.md）：
//   · 版本号放 version.properties（唯一事实源），build 脚本只读键值行；
//   · 构建时算一个**标准版本号** = <versionName>-<yyyyMMdd-HHmm>-<7 位 git hash>，
//     固定 Asia/Shanghai（本地与 CI 一致），注入 BuildConfig；
//   · APK 文件名按 "产品-版本-组合" 生成 —— 六个内置组合必须能一眼分清
//     （0.3.0 起是 2 个 App × 3 档），否则下载下来全是 app-debug.apk，
//     用户根本不知道哪个是哪个。
// ============================================================================
// 版本文件放在 **Gradle 根**（app/version.properties），不是模块目录（app/app/）——
// 所以这里显式用 rootDir 定位，别看 build 脚本的所在目录。
val versionProps = Properties().apply {
    File(rootDir, "version.properties").inputStream().use { load(it) }
}
val engineeringVersion = versionProps.getProperty("versionName").trim()
val engineeringVersionCode = versionProps.getProperty("versionCode").trim().toInt()

// ── proroot（免 root 首选运行时）──────────────────────────────────────────────
// 版本与 sha256 的唯一事实源是 tools/proroot/VENDOR.json；**二进制不进仓库**
// （专有许可：只能在完整 APK 里再分发，禁止作为独立资产/仓库文件分发）。
// 这里只读它的版本号进 BuildConfig，界面「关于」页要显示并给出 attribution。
val prorootVendor = groovy.json.JsonSlurper().parse(
    File(rootDir.parentFile, "tools/proroot/VENDOR.json"),
) as Map<*, *>
val prorootVersion = prorootVendor["version"].toString()
val prorootLicenseName = "proroot ${prorootVersion}"

/** 构建时间（固定 Asia/Shanghai，避免本地与 CI 差 8 小时导致文件名对不上）。 */
val buildTime: String = SimpleDateFormat("yyyyMMdd-HHmm", Locale.US)
    .apply { timeZone = TimeZone.getTimeZone("Asia/Shanghai") }
    .format(Date())

/** 7 位 git hash；不在 git 仓库里（例如导出源码包）时退化为 unknown。 */
val gitHash: String = runCatching {
    val proc = ProcessBuilder("git", "rev-parse", "--short=7", "HEAD")
        .directory(project.rootDir.parentFile)   // Gradle root 是 app/，仓库根在上一级
        .redirectErrorStream(true)
        .start()
    val out = proc.inputStream.bufferedReader().readText().trim()
    proc.waitFor()
    out.ifBlank { "unknown" }
}.getOrDefault("unknown")

val standardVersion = "$engineeringVersion-$buildTime-$gitHash"

/**
 * 内置矩阵：**全部从 `tools/offline-bundle/variants.json` 读**（单一事实源）。
 *
 * ## 为什么不再在这里写死 flavor 表
 *
 * 0.3.0 第一版把"edition × 档位"写死在构建脚本里，加一档就要改两处（JSON + Gradle），
 * 而用户明确说了"纯 Root、免 Root、然后各种内置感觉不止 4 种"—— 矩阵还会继续长
 * （例如再加"只内置 DSH 层"这种组合）。所以现在：
 *   · editions / tiers / 每个组合的内嵌部件与标签，**全部来自 variants.json**；
 *   · 加一档 = 改 JSON 一行 + 在 App 里不需要任何改动；
 *   · pipeline 的编译矩阵也读同一个文件（它按 "<edition>-<tier>" 推 Gradle 任务名），
 *     所以连 CI 都不用改。
 *
 * 构建期只做两件事的推导（都无法从 JSON 直接拿）：
 *   ① flavor 名必须是合法标识符 → edition/tier 直接用作 flavor 名（root/proot、minimal/base/full）；
 *   ② 组合 id = "<edition>-<tier>"（与 JSON 的键逐字一致）。
 */
val variantsJsonFile = File(rootDir.parentFile, "tools/offline-bundle/variants.json")
require(variantsJsonFile.isFile) { "找不到 ${variantsJsonFile.path}（内置矩阵的唯一事实源）" }
@Suppress("UNCHECKED_CAST")
val variantsSpec = groovy.json.JsonSlurper().parse(variantsJsonFile) as Map<String, Any>

data class Edition(val gradleName: String, val id: String, val label: String, val labelShort: String, val applicationId: String, val mode: String)

@Suppress("UNCHECKED_CAST")
val editions: List<Edition> = (variantsSpec["editions"] as Map<String, Map<String, Any>>).map { (id, e) ->
    Edition(
        gradleName = id,
        id = id,
        label = e["label"] as String,
        labelShort = e["label_short"] as String,
        applicationId = e["application_id"] as String,
        mode = e["mode"] as String,
    )
}

/** 档位：按 JSON 里的顺序（下载页/说明的顺序也跟着它走）。 */
@Suppress("UNCHECKED_CAST")
val tierLabels: Map<String, String> =
    (variantsSpec["tiers"] as? Map<String, Map<String, Any>>)?.mapValues { it.value["label"] as String } ?: emptyMap()

@Suppress("UNCHECKED_CAST")
val variantSpecs: Map<String, Map<String, Any>> = variantsSpec["variants"] as Map<String, Map<String, Any>>

/**
 * 组合清单：**每个组合就是一个 flavor**（单维度）。
 *
 * ★ 2026-09-19 维护决策变更：组合从 6 个（edition × tier 两维笛卡尔积）压到 **2 个**。
 *   组合数从此**不再等于维度乘积**，所以不能再靠两维 flavor 去拼 —— 那样会生成
 *   root-full / proot-minimal 这类"没有定义"的组合，配置期直接报错。
 *   flavor 名取 JSON 里的 `gradle_name`（缺省时由 id 派生：root-minimal → rootMinimal），
 *   任务名与 CI 矩阵的派生规则一致（`assembleRootMinimalDebug`）。
 */
data class VariantSpec(
    val id: String,
    val gradleName: String,
    val editionId: String,
    val tierId: String,
    val label: String,
    val parts: String,
)

@Suppress("UNCHECKED_CAST")
val variantList: List<VariantSpec> = variantSpecs.map { (id, v) ->
    VariantSpec(
        id = id,
        gradleName = (v["gradle_name"] as? String)
            ?: id.split('-').mapIndexed { i, p -> if (i == 0) p else p.replaceFirstChar { c -> c.uppercase() } }
                .joinToString(""),
        editionId = v["edition"] as String,
        tierId = v["tier"] as String,
        label = (v["label"] as? String) ?: id,
        parts = (v["embed"] as List<String>).joinToString(","),
    )
}
require(variantList.isNotEmpty()) { "variants.json 里没有任何组合" }

/** 组合 id → 内嵌部件（逗号分隔，BuildConfig 用）。 */
val variantParts: Map<String, String> = variantList.associate { it.id to it.parts }

android {
    namespace = "io.github.sunsetrne.sunsetlinux"
    compileSdk = 35

    defaultConfig {
        // ★ 真正的 applicationId 在 **edition flavor** 里设（两个 App 不同包名）。
        //   这里给一个绝不可能被用到的占位值：任何 flavor 漏设都会在构建期直接报错，
        //   而不是悄悄产出一个包名错误的 APK（真机装上去才发现是另一回事）。
        applicationId = "io.github.sunsetrne.sunsetlinux.EDITION-NOT-SET"
        minSdk = 26
        targetSdk = 35
        // 版本号来自 app/version.properties；逐版变更说明在 app/VERSION-NOTES.md
        // （APP 名仍是 "SunsetLinux"，versionName 用标准版本号：<语义版本>-<时间>-<hash>）
        versionCode = engineeringVersionCode
        versionName = standardVersion

        // 构建信息进 BuildConfig：界面上"关于/诊断"能直接看到，排障时不用猜是哪个包
        buildConfigField("String", "ENGINEERING_VERSION", "\"$engineeringVersion\"")
        buildConfigField("String", "STANDARD_VERSION", "\"$standardVersion\"")
        buildConfigField("String", "BUILD_TIME", "\"$buildTime\"")
        buildConfigField("String", "GIT_HASH", "\"$gitHash\"")
        // 免 root 运行时：proroot 是首选的 LD_PRELOAD 实现，proot 是降级实现（两者都随 APK）
        buildConfigField("String", "PROROOT_VERSION", "\"$prorootVersion\"")
    }

    // ────────────────────────────────────────────────────────────────────────
    // 签名：**必须固定**，否则每次构建换一把 key，后果不是"小毛病"：
    //   · 用户装新版 → INSTALL_FAILED_UPDATE_INCOMPATIBLE（必须先卸载，App 数据清空）
    //   · KernelSU 的 root 授权按【包名 + 签名】记录 → 已授予的授权**全部作废**
    //   · 系统不把新版当成同一应用的升级
    //
    // 实测证据（2026-09-16）：CI 发布的 APK 与本机构建的 APK 都是 `CN=Android Debug` 自签，
    // 但证书 SHA-256 一个是 84be9523…、一个是 3b68b616… —— 因为 AGP 在缺少配置时会用
    // 各机器自己生成的 ~/.android/debug.keystore，而 CI runner 是临时的（每次都新建一把）。
    //
    // 策略（两档，见 docs/release-ci.md §5.4）：
    //   1) **默认**：用随仓库提交的固定调试密钥 `app/app/debug.keystore`
    //      （调试密钥不是秘密；口令见下）。本地与 CI 天然同签名，开箱即稳。
    //   2) **要私有发布密钥**时设环境变量（CI 从 Secret 注入，见 .github/workflows/ci.yml）：
    //      SUNSETLINUX_KEYSTORE / SUNSETLINUX_KEYSTORE_PASSWORD /
    //      SUNSETLINUX_KEY_ALIAS / SUNSETLINUX_KEY_PASSWORD
    //      ⚠️ 换密钥的那一次，用户必须卸载重装一次（签名变了做不到平滑升级）。
    // ────────────────────────────────────────────────────────────────────────
    signingConfigs {
        create("sunsetlinux") {
            val envStore = System.getenv("SUNSETLINUX_KEYSTORE")
            if (!envStore.isNullOrBlank()) {
                storeFile = file(envStore)
                storePassword = System.getenv("SUNSETLINUX_KEYSTORE_PASSWORD")
                keyAlias = System.getenv("SUNSETLINUX_KEY_ALIAS")
                keyPassword = System.getenv("SUNSETLINUX_KEY_PASSWORD")
            } else {
                // 仓库内固定调试密钥：口令刻意不用默认的 "android"，避免与别的项目撞签名
                storeFile = file("debug.keystore")
                storePassword = "sunsetlinux"
                keyAlias = "sunsetlinux"
                keyPassword = "sunsetlinux"
            }
        }
    }

    // ────────────────────────────────────────────────────────────────────────
    // **两个组合**（2026-09-19 维护决策变更：6 → 2）——**全部读 tools/offline-bundle/variants.json**：
    //   root-minimal：**极简 Root + 模块挂载 Ubuntu**（APK 不带环境；层由模块与频道提供）
    //   proot-full  ：**完整 proot + Ubuntu**（内嵌 base + runtime + proot + 默认自带的 DSH）
    //   DSH 是**可变部件**：可移除 / 可替换 / 可回滚（见 variants.json 的 `_decision.variable_dsh`）。
    // ★ 组合 = **一个 flavor**（单维度）。以前是 edition × embed 两维笛卡尔积（3 档 → 6 个包），
    //   收敛之后组合数不再等于维度乘积，硬撑两维会生成 root-full / proot-minimal 这类
    //   没有定义的组合（配置期直接报错）。加/减组合只改 JSON，这里不写死数量。
    // ────────────────────────────────────────────────────────────────────────
    flavorDimensions += "variant"
    productFlavors {
        variantList.forEach { v ->
            create(v.gradleName) {
                dimension = "variant"
                val e = editions.firstOrNull { it.id == v.editionId }
                    ?: error("组合 ${v.id} 的 edition=${v.editionId} 在 editions 里不存在")
                // 两个可共存的 App：包名不同（KernelSU 授权、App 数据、卸载互不影响）
                applicationId = e.applicationId
                // ★ 桌面标签也必须按 edition 分开：两个 App 装在同一台手机上、都叫
                //   "SunsetLinux" 的话，用户点哪一个纯靠猜 —— 0.3.0 拆版时**真的**是
                //   这样（只改了 main/res 的 app_name，`src/<edition>/res/` 根本不存在，
                //   实测桌面两个图标同名）。标签取自 variants.json 的
                //   `editions[].label`，改 JSON 就跟着变，这里不抄第二份。
                resValue("string", "app_name", e.label)
                buildConfigField("String", "EDITION", "\"${e.id}\"")
                buildConfigField("String", "EDITION_LABEL", "\"${e.label}\"")
                buildConfigField("String", "EDITION_LABEL_SHORT", "\"${e.labelShort}\"")
                buildConfigField("String", "EDITION_MODE", "\"${e.mode}\"")
                // 本 edition 锁定的模式：界面据此隐藏"切换模式"（单模式 App）
                buildConfigField("String", "EDITION_ID", "\"${e.id}\"")
                // 档位标签（"极简" / "完整"）：界面上用来说明本包含什么
                buildConfigField("String", "EMBED_TIER", "\"${v.tierId}\"")
                buildConfigField("String", "EMBED_LABEL", "\"${tierLabels[v.tierId] ?: v.tierId}\"")
            }
        }
    }

    buildTypes {
        debug {
            // 刻意不加 applicationIdSuffix：包名必须保持 io.github.sunsetrne.sunsetlinux，
            // 否则 KernelSU 里已授予的 root 授权会失效（授权按包名 + 签名记录）。
            isMinifyEnabled = false
            signingConfig = signingConfigs.getByName("sunsetlinux")
        }
        release {
            isMinifyEnabled = false
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
            signingConfig = signingConfigs.getByName("sunsetlinux")
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    buildFeatures {
        compose = true
        buildConfig = true
        // ★ AGP 9 起 `resValues` 默认**关闭**：不开的话 `productFlavors { resValue(...) }`
        //   会在配置阶段直接失败 —— "Product Flavor root contains custom resource values,
        //   but the feature is disabled."（要的是按 edition 改 app_name 桌面标签）
        resValues = true
    }

    packaging {
        resources {
            excludes += "/META-INF/{AL2.0,LGPL2.1}"
        }
        jniLibs {
            // ★ 两个都不能少：
            //   · keepDebugSymbols：**不要 strip** 供应商的 proroot 二进制 ——
            //     许可第 2 条禁止再分发"修改过的版本"，strip 就是修改。
            //   · useLegacyPackaging：把 .so **解包**到 nativeLibraryDir。默认的
            //     "不落盘、直接从 APK 页对齐加载"对 dlopen 足够，但 proroot 的启动器
            //     （libproroot.so 本身是个 PIE 可执行文件）需要我们**按路径 exec** 它，
            //     所以必须在磁盘上有真文件。
            keepDebugSymbols += "**/libproroot*.so"
            useLegacyPackaging = true
        }
    }

    androidResources {
        // 离线包（.bin）本身已是压缩产物，再压一遍纯属浪费构建时间（体积也不会变小）
        noCompress += "bin"
    }

    lint {
        // 本机为一次性构建，lint 失败不应阻断 APK 产出（真正的问题由编译器暴露）。
        abortOnError = false
        checkReleaseBuilds = false
    }
}

dependencies {
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.lifecycle.runtime.ktx)
    implementation(libs.androidx.lifecycle.viewmodel.compose)
    implementation(libs.androidx.activity.compose)

    implementation(platform(libs.androidx.compose.bom))
    implementation(libs.androidx.ui)
    implementation(libs.androidx.ui.graphics)
    implementation(libs.androidx.ui.tooling.preview)
    implementation(libs.androidx.material3)
    implementation(libs.androidx.material.icons.core)

    implementation(libs.androidx.swiperefreshlayout)
    implementation(libs.libsu.core)
    // 层产物的传输层解压：gzip 用 JDK，zstd 用这个纯 Java 解码器（见 libs.versions.toml 注释）
    implementation(libs.aircompressor)

    debugImplementation(libs.androidx.ui.tooling)

    // JVM 单测：用真实层产物验证解压链路（src/test/.../LayerDecompressorTest.kt）
    testImplementation(libs.junit)
    // android.jar 里的 org.json 在单测中是空壳，补一个真实实现
    testImplementation(libs.org.json)
}

// ============================================================================
// 单测要读仓库里的源码与产物（TestPaths），所以显式把仓库根传进去。
//
// 为什么不靠默认路径：TestPaths 会从工作目录向上找标志文件，通常能命中；
// 但显式传参更稳（CI 的工作目录未必固定），也避免有人误以为"必须放在固定路径"。
// Gradle root 是 app/，仓库根是它的上一级。
// ============================================================================
tasks.withType<Test>().configureEach {
    val repoRoot = rootProject.projectDir.parentFile
    systemProperty("sunsetlinux.repo.dir", repoRoot.absolutePath)
    systemProperty("sunsetlinux.dist.dir", File(repoRoot, "dist").absolutePath)
}

// ============================================================================
// 内嵌离线包 + APK 命名
//
// ★ 离线包**不入库**（`dist/` 是 gitignore 的，CI 上只有源码）：由
//   .github/workflows/offline-bundle.yml 按组合生成、挂到 Release，构建前放到
//   `<仓库根>/dist/bundles/` 即可被打进对应组合的 assets/。
//   没放也不失败：那个组合退化成"未内嵌"（App 里会显示，并走频道下载）。
//
// ★ APK 命名必须带组合名：四个组合产出的都是同一个 App，如果不改名，
//   下载下来全是 `app-debug.apk`，用户根本分不清哪个是哪个（AGP 默认命名就是这样）。
//   AGP 9 已移除 `VariantOutput.outputFileName`，只能用内部实现类 VariantOutputImpl
//   （见另一份构建笔记 Branchbase docs/specs/BUILD-NOTES.md 第一节）。
// ============================================================================
abstract class EmbedOfflineBundle : DefaultTask() {
    /** 候选离线包（可能为空 = 本次没放包，那就"未内嵌"）。 */
    @get:InputFiles
    @get:PathSensitive(PathSensitivity.NAME_ONLY)
    abstract val bundles: ConfigurableFileCollection

    /**
     * 期望的文件名，**两种都认**：
     *   · `SunsetLinux-<版本>-<组合>.bin` —— 发布/下载用的正式名（见 app/VERSION-NOTES.md）
     *   · `<组合>.bin`                     —— 打包工具 `tools/offline-bundle` 的本地产物名
     * 这样"CI 下载回来的离线包"和"本机刚打出来的"都能直接内嵌，不用先改名。
     */
    @get:Input
    abstract val wanted: Property<String>

    @get:Input
    abstract val wantedShort: Property<String>

    @get:OutputDirectory
    abstract val outputDir: DirectoryProperty

    @TaskAction
    fun embed() {
        val out = outputDir.get().asFile
        out.deleteRecursively()
        out.mkdirs()
        val hit = bundles.files.firstOrNull { it.name == wanted.get() || it.name == wantedShort.get() }
            ?: run {
                logger.lifecycle("未内嵌离线包（没找到 ${wanted.get()} 或 ${wantedShort.get()}）—— 该组合按「未内嵌」构建")
                return
            }
        val to = File(out, ASSET_NAME)
        hit.copyTo(to, overwrite = true)
        logger.lifecycle("已内嵌离线包：${hit.name} → assets/$ASSET_NAME（${hit.length() / 1048576} MiB）")
    }

    companion object {
        /** assets 里的固定名字：组合由 BuildConfig.EMBED_VARIANT 决定，文件名不必带版本。 */
        const val ASSET_NAME = "offline-bundle.bin"
    }
}


abstract class SyncBundledModule : DefaultTask() {
    /** 版本事实源：`module/module.prop`。 */
    @get:InputFile
    @get:PathSensitive(PathSensitivity.RELATIVE)
    abstract val moduleProp: RegularFileProperty

    /** 候选产物：`dist/sunsetlinux-module-*.zip`。 */
    @get:InputFiles
    @get:PathSensitive(PathSensitivity.NAME_ONLY)
    abstract val candidates: ConfigurableFileCollection

    @get:OutputDirectory
    abstract val outputDir: DirectoryProperty

    @TaskAction
    fun sync() {
        val out = File(outputDir.get().asFile, "module").apply {
            deleteRecursively()
            mkdirs()
        }
        val wanted = moduleProp.get().asFile.readLines()
            .map { it.trim() }
            .firstOrNull { it.startsWith("version=") }
            ?.removePrefix("version=")
            ?.trim()
            .orEmpty()

        fun versionOf(zip: File): String =
            zip.name.removePrefix("sunsetlinux-module-").removeSuffix(".zip")
                .removeSuffix("-bare").removeSuffix("-full")
                .trim().removePrefix("v")

        /** 变体名（docs/module-variants.md §2.6）：默认名 = full（自带 DSH），`-bare` = 不带 DSH。 */
        fun variantOf(zip: File): String = if (zip.name.endsWith("-bare.zip")) "bare" else "full"

        fun key(v: String): List<Int> =
            v.split('.', '-', '_', '+').map { seg -> seg.takeWhile { it.isDigit() }.toIntOrNull() ?: 0 }

        // ★ APK 内嵌的是 **bare 变体**：APK 的 base/full 档已经把 dsh 层放进离线包了，
        //   模块再内嵌一份 = 同样的 48 MB 塞两遍。默认名（不带后缀）只给 full，所以这里
        //   **优先挑 -bare**；确实没有 bare 时才退回（老仓库产物/单手包场景）。
        val allZips = candidates.files.filter { it.isFile && it.name.endsWith(".zip") }
        val zips = allZips.filter { it.name.endsWith("-bare.zip") }.ifEmpty { allZips }
        val pick = zips.firstOrNull { versionOf(it) == wanted.removePrefix("v") }
            ?: zips.maxWithOrNull(
                Comparator { a, b ->
                    val x = key(versionOf(a))
                    val y = key(versionOf(b))
                    (0 until maxOf(x.size, y.size)).firstNotNullOfOrNull { i ->
                        val c = (x.getOrElse(i) { 0 }).compareTo(y.getOrElse(i) { 0 })
                        if (c != 0) c else null
                    } ?: 0
                },
            )

        if (pick == null) {
            logger.lifecycle(
                "未内嵌模块包（dist/ 下没有 sunsetlinux-module-*.zip）—— " +
                    "首启引导第 2 步会显示「未内嵌」并给出手动路径；要内嵌先跑 module/mkmodule.sh",
            )
            return
        }

        val to = File(out, "sunsetlinux-module.zip")
        pick.copyTo(to, overwrite = true)
        val digest = MessageDigest.getInstance("SHA-256")
        to.inputStream().use { input ->
            val buf = ByteArray(64 * 1024)
            while (true) {
                val n = input.read(buf)
                if (n <= 0) break
                digest.update(buf, 0, n)
            }
        }
        val sha = digest.digest().joinToString("") { "%02x".format(it) }
        val version = versionOf(pick).ifEmpty { wanted }
        File(out, "module.json").writeText(
            buildString {
                append("{\n")
                append("  \"schema\": 1,\n")
                append("  \"version\": \"").append(wanted.ifEmpty { "v$version" }).append("\",\n")
                append("  \"variant\": \"").append(variantOf(pick)).append("\",\n")
                append("  \"file\": \"sunsetlinux-module-").append(version).append(".zip\",\n")
                append("  \"asset\": \"module/sunsetlinux-module.zip\",\n")
                append("  \"size\": ").append(to.length()).append(",\n")
                append("  \"sha256\": \"").append(sha).append("\",\n")
                append("  \"source\": \"").append(pick.name).append("\"\n")
                append("}\n")
            },
        )
        logger.lifecycle("已内嵌模块包：${pick.name}（变体 ${variantOf(pick)}）→ assets/module/（${to.length() / 1024} KB，sha256 ${sha.take(12)}…）")
    }
}


/**
 * 把 `runtime/proot/` 里的脚本铺成 `assets/proot-runtime/`。
 *
 * 为什么非做不可：非 root（proot）模式的 `linuxctl` 是**宿主侧脚本**
 * （`linuxctl.sh` / `start.sh` / `entry.sh`），必须躺在 `$LINUX_HOME/bin/` 里。
 * 以前它只有两条来路 —— 模块铺（root 用户）或用户手动 `tar -xzf` 那个 50 KB 的
 * `dist/sunsetlinux-proot-runtime.tar.gz`。于是「免 root 版」「完整离线版」装完其实
 * 起不来：内嵌包里有 proot 二进制，却没有这套脚本。现在脚本随 APK 走 assets，
 * App 里点一下「铺 proot 运行时」即可（见 core/ProotRuntime.kt）。
 *
 * 单一事实源：源就是仓库里的 `runtime/proot/`，构建时拷贝，**不做任何改写**，
 * 与模块里那份必然一致（改脚本只需改一处）。
 */
abstract class SyncProotRuntimeAssets : DefaultTask() {
    @get:InputFiles
    @get:PathSensitive(PathSensitivity.NAME_ONLY)
    abstract val sources: ConfigurableFileCollection

    @get:OutputDirectory
    abstract val outputDir: DirectoryProperty

    @TaskAction
    fun sync() {
        val out = File(outputDir.get().asFile, "proot-runtime")
        out.deleteRecursively()
        out.mkdirs()
        val files = sources.files.filter { it.isFile }.sortedBy { it.name }
        if (files.isEmpty()) {
            throw GradleException(
                "runtime/proot 里没有可内嵌的脚本 —— 非 root 模式会起不来。" +
                    "确认在仓库根下的 app/ 目录里构建（源目录：${sources.files.joinToString()}）",
            )
        }
        files.forEach { it.copyTo(File(out, it.name), overwrite = true) }
        val missing = listOf("linuxctl.sh", "start.sh", "entry.sh").filterNot { File(out, it).isFile }
        if (missing.isNotEmpty()) {
            throw GradleException("runtime/proot 缺必需脚本：${missing.joinToString(", ")}（proot 模式起不来）")
        }
        logger.lifecycle("proot 宿主脚本 ${files.size} 个 → assets/proot-runtime/（${files.sumOf { it.length() } / 1024} KB）")
    }
}


/**
 * 把 proroot 的 5 个 `.so` 铺成 AGP 认的 **jniLibs** 目录（`arm64-v8a/`）。
 *
 * ## 为什么"现取现用"而不是提交进仓库
 *
 * proroot 是**专有许可**（`tools/proroot/LICENSE.proroot`）：允许把未修改的二进制
 * 作为**完整应用包（APK）**的一部分再分发，但禁止再分发修改版、也禁止独立于应用包的
 * 分发（公开仓库里的文件、Release 资产、CI artifact 都算）。所以：
 *   · 仓库里只有版本账本 `tools/proroot/VENDOR.json`（含 5 个 sha256）与许可原文；
 *   · 二进制在构建时从上游 Release 取到 `dist/proroot/`（gitignore），校验 sha256 后
 *     直接进 jniLibs —— 最终只以 APK 形式对外。
 *
 * 因此 **每一个** Android 构建都需要网络（CI 有）。取不到就**失败**，绝不静默产出
 * 一个"免 root 模式其实用不了 proroot"的 APK（那种"看起来成功"最坑人）。
 */
abstract class SyncProrootLibs : DefaultTask() {
    @get:Inject
    abstract val execOps: ExecOperations

    /** 仓库根（`app/` 的上一级）。 */
    @get:Input
    abstract val repoRoot: Property<String>

    /** 版本账本：内容变了就该重跑取用与校验。 */
    @get:InputFile
    @get:PathSensitive(PathSensitivity.RELATIVE)
    abstract val vendorJson: RegularFileProperty

    /** 取到哪儿（默认 `dist/proroot`）；内容不进 Gradle 输入快照（由 vendorJson 的 sha256 覆盖）。 */
    @get:Internal
    abstract val sourceDir: DirectoryProperty

    @get:OutputDirectory
    abstract val outputDir: DirectoryProperty

    @TaskAction
    fun sync() {
        val repo = File(repoRoot.get())
        val src = sourceDir.get().asFile
        logger.lifecycle("proroot：取用/校验 → ${src.path}（专有许可：只随 APK 分发）")
        val result = execOps.exec {
            workingDir = repo
            commandLine("node", "tools/proroot/fetch.mjs", "--out", src.absolutePath)
        }
        if (result.exitValue != 0) {
            throw GradleException(
                "proroot 取用失败（exit ${result.exitValue}）。\n" +
                    "  免 root 模式的 proroot 需要构建期从上游 Release 下载（专有许可不允许我们" +
                    "把二进制提交进仓库或单独分发）。\n" +
                    "  手动排查：node tools/proroot/fetch.mjs",
            )
        }

        val out = File(outputDir.get().asFile, "arm64-v8a")
        out.deleteRecursively()
        out.mkdirs()
        val files = src.listFiles { f -> f.isFile && f.name.startsWith("libproroot") && f.name.endsWith(".so") }
            ?.sortedBy { it.name }
            ?: emptyList()
        if (files.size != 5) {
            throw GradleException("proroot 需要 5 个 .so，实际拿到 ${files.size} 个（${src.path}）")
        }
        files.forEach { it.copyTo(File(out, it.name), overwrite = true) }
        logger.lifecycle("proroot：${files.size} 个 .so → jniLibs/arm64-v8a（${files.sumOf { it.length() } / 1024} KB）")
    }
}

/**
 * 把 proroot 的许可原文 + attribution 放进 `assets/licenses/`。
 *
 * 许可第 4/5 条要求：APK 内必须带许可原文，且应用内要有 attribution。
 * 这里生成的文件随 APK 走，用户/审计在设备上就能查。
 */
abstract class SyncProrootLicense : DefaultTask() {
    @get:InputFile
    @get:PathSensitive(PathSensitivity.RELATIVE)
    abstract val licenseFile: RegularFileProperty

    @get:Input
    abstract val version: Property<String>

    @get:OutputDirectory
    abstract val outputDir: DirectoryProperty

    @TaskAction
    fun sync() {
        val out = File(outputDir.get().asFile, "licenses").apply { mkdirs() }
        val text = buildString {
            append(licenseFile.get().asFile.readText().trimEnd())
            append("\n\n")
            append("------------------------------------------------------------------------\n")
            append("打包信息（由 SunsetLinux 构建生成）\n")
            append("  组件：proroot ${version.get()}（https://github.com/coderredlab/proroot）\n")
            append("  用途：SunsetLinux 免 root（非 root）模式的**首选**运行时；proot 作为降级实现。\n")
            append("  声明：二进制取自上游 Release，**未做任何修改**（也未 strip），仅作为本 APK 的一部分分发。\n")
            append("------------------------------------------------------------------------\n")
        }
        File(out, "proroot-LICENSE.txt").writeText(text)
    }
}


/**
 * 编译终端用的原生 PTY 库（`src/main/jniLibs/arm64-v8a/libsunsetlinux_pty.so`）。
 *
 * 为什么**不**用 AGP 的 `externalNativeBuild`：官方 NDK 只发布 **x86_64 宿主的工具链**，
 * 而本项目有 aarch64 的构建环境 —— AGP 会去调那个 x86_64 的 clang，直接 `error=2`。
 * 产物本身与宿主无关（bionic 头/库都在 `sysroot/` 里，是纯数据），所以由
 * `tools/ndk-build-pty.sh` 自己驱动 clang：x86_64 宿主用 NDK 自带 clang，
 * aarch64 宿主用系统 clang + NDK sysroot + `-resource-dir`/`-rtlib=compiler-rt`。
 *
 * 没有 NDK 时**明确失败**（而不是静默出一个"终端退回行缓冲"的 APK）：
 * 那种"看起来成功"最坑人 —— 用户会以为终端坏了。
 */
abstract class BuildPtySo : DefaultTask() {
    @get:Inject
    abstract val execOps: ExecOperations

    @get:InputDirectory
    @get:PathSensitive(PathSensitivity.RELATIVE)
    abstract val cppDir: DirectoryProperty

    @get:InputFile
    @get:PathSensitive(PathSensitivity.RELATIVE)
    abstract val script: RegularFileProperty

    @get:Internal
    abstract val repoRoot: Property<String>

    @get:OutputDirectory
    abstract val outputDir: DirectoryProperty

    @TaskAction
    fun build() {
        val repo = File(repoRoot.get())
        val res = execOps.exec {
            workingDir = repo
            commandLine("bash", "tools/ndk-build-pty.sh")
        }
        if (res.exitValue != 0) {
            throw GradleException(
                "原生 PTY 编译失败（exit ${res.exitValue}）。\n" +
                    "  终端需要它来拿真 PTY（Ctrl-C / vim / htop / 窗口大小）。\n" +
                    "  装了 NDK 再试：sdkmanager --install \"ndk;26.1.10909125\"（或设 NDK=/path/to/ndk）",
            )
        }
        val so = File(outputDir.get().asFile, "arm64-v8a/libsunsetlinux_pty.so")
        if (!so.isFile) throw GradleException("脚本跑完了但没看到产物：${so.path}")
    }
}

// 任何构建都先编 PTY（源在 src/main/cpp/pty.c，产物落在 src/main/jniLibs，随 APK 打包）
val buildPty = tasks.register<BuildPtySo>("buildPtySo") {
    group = "build"
    description = "编译原生 PTY 库（终端底座，arm64-v8a）"
    repoRoot.set(rootDir.parentFile.absolutePath)
    cppDir.set(File(rootDir.parentFile, "app/app/src/main/cpp"))
    script.set(File(rootDir.parentFile, "tools/ndk-build-pty.sh"))
    outputDir.set(File(rootDir.parentFile, "app/app/src/main/jniLibs"))
}
tasks.matching { it.name == "preBuild" }.configureEach { dependsOn(buildPty) }

androidComponents {
    onVariants { variant ->
        // 两个维度拼出组合 id（与 tools/offline-bundle/variants.json 的键逐字一致）：
        //   edition=root/proot，embed=minimal/full → "root-minimal" / "proot-full" …
        // 单维度：flavor 名 → variants.json 里的组合（决策变更后一个组合就是一个 flavor）
        val flavorName = variant.productFlavors.firstOrNull { it.first == "variant" }?.second
        val spec = variantList.firstOrNull { it.gradleName == flavorName }
            ?: error("变体 ${variant.name} 找不到对应组合（flavor=$flavorName）—— variants.json 与 flavor 名脱节了")
        val edition = editions.firstOrNull { it.id == spec.editionId }
            ?: error("组合 ${spec.id} 的 edition=${spec.editionId} 在 editions 里不存在")
        val variantId = spec.id
        val parts = spec.parts

        // 组合 id / 部件只在**运行时**才知道 → 用 variant 级 BuildConfig 注入
        val isRootEdition = edition.id == "root"
        variant.buildConfigFields?.put("EMBED_VARIANT", com.android.build.api.variant.BuildConfigField("String", "\"$variantId\"", null))
        variant.buildConfigFields?.put("EMBED_PARTS", com.android.build.api.variant.BuildConfigField("String", "\"$parts\"", null))
        variant.buildConfigFields?.put("APP_ID", com.android.build.api.variant.BuildConfigField("String", "\"${edition.applicationId}\"", null))

        // ① APK 名：SunsetLinux-<版本>-<组合>.apk（debug 构建类型再带 -debug，
        //    与 Branchbase 的产物命名规则一致）
        val buildTypeSuffix = if (variant.buildType == "debug") "-debug" else ""
        variant.outputs.forEach { output ->
            (output as com.android.build.api.variant.impl.VariantOutputImpl)
                .outputFileName.set("SunsetLinux-$engineeringVersion-$variantId$buildTypeSuffix.apk")
        }

        // ② 内嵌离线包：生成一个 assets 目录交给 AGP（不往 src/ 里写文件）
        val taskName = "embedOfflineBundle" + variant.name.replaceFirstChar { it.uppercase() }
        val embed = tasks.register<EmbedOfflineBundle>(taskName) {
            group = "build"
            description = "把 $variantId 组合的离线包内嵌进 assets/（没有则跳过）"
            wanted.set("SunsetLinux-$engineeringVersion-$variantId.bin")
            wantedShort.set("$variantId.bin")
            bundles.from(
                fileTree(File(rootDir.parentFile, "dist/bundles")) { include("*.bin") },
            )
        }
        variant.sources.assets?.addGeneratedSourceDirectory(embed, EmbedOfflineBundle::outputDir)

        // ②b 内置 KernelSU 模块包：**只有 root 版带** —— 免 root 版与 KernelSU 模块无关，
        //     装了也没用（首启引导那一步在免 root 分支根本不会出现）。
        if (isRootEdition) {
            val moduleTaskName = "syncBundledModule" + variant.name.replaceFirstChar { it.uppercase() }
            val bundledModule = tasks.register<SyncBundledModule>(moduleTaskName) {
                group = "build"
                description = "把 dist/sunsetlinux-module-*.zip 内嵌进 assets/module/（没有则跳过）"
                moduleProp.set(File(rootDir.parentFile, "module/module.prop"))
                candidates.from(
                    fileTree(File(rootDir.parentFile, "dist")) { include("sunsetlinux-module-*.zip") },
                )
            }
            variant.sources.assets?.addGeneratedSourceDirectory(bundledModule, SyncBundledModule::outputDir)
        }

        // ③ proot 宿主脚本：**只有免 root 版带** —— root 模式的 linuxctl 由 KernelSU 模块铺，
        //    把这套脚本塞进 root 版只会白占 ~180 KB 且容易让人误以为它有用。
        if (!isRootEdition) {
            val prootTaskName = "syncProotRuntime" + variant.name.replaceFirstChar { it.uppercase() }
            val prootAssets = tasks.register<SyncProotRuntimeAssets>(prootTaskName) {
                group = "build"
                description = "把 runtime/proot 的宿主脚本放进 assets/proot-runtime/"
                sources.from(
                    fileTree(File(rootDir.parentFile, "runtime/proot")) {
                        include("*.sh", "*.md")
                    },
                )
            }
            variant.sources.assets?.addGeneratedSourceDirectory(prootAssets, SyncProotRuntimeAssets::outputDir)
        }

        // ④ proroot（免 root 首选运行时）：二进制进 jniLibs，许可进 assets。
        //    **只有免 root 版带** —— 0.3.0 起模式由 edition 锁定，Root 版永远不会用到它
        //    （省 ~650 KB，也让 root 版不牵扯 proroot 的专有许可）。
        // proroot：只有**免 root 版**需要（root 模式从不用 LD_PRELOAD 运行时）。
        // 少这个 .so 能省 ~650 KB，也让 root 版不牵扯 proroot 的专有许可。
        if (!isRootEdition) {
            val prorootTaskName = "syncProrootLibs" + variant.name.replaceFirstChar { it.uppercase() }
            val prorootLibs = tasks.register<SyncProrootLibs>(prorootTaskName) {
                group = "build"
                description = "取用 proroot（专有许可，只随 APK 分发）并铺成 jniLibs/arm64-v8a"
                repoRoot.set(rootDir.parentFile.absolutePath)
                vendorJson.set(File(rootDir.parentFile, "tools/proroot/VENDOR.json"))
                sourceDir.set(File(rootDir.parentFile, "dist/proroot"))
            }
            variant.sources.jniLibs?.addGeneratedSourceDirectory(prorootLibs, SyncProrootLibs::outputDir)
        }

        if (!isRootEdition) {
            val prorootLicenseTaskName = "syncProrootLicense" + variant.name.replaceFirstChar { it.uppercase() }
            val prorootLicense = tasks.register<SyncProrootLicense>(prorootLicenseTaskName) {
                group = "build"
                description = "把 proroot 许可原文放进 assets/licenses/（许可第 4 条：只在带它的包里）"
                licenseFile.set(File(rootDir.parentFile, "tools/proroot/LICENSE.proroot"))
                version.set(prorootVersion)
            }
            variant.sources.assets?.addGeneratedSourceDirectory(prorootLicense, SyncProrootLicense::outputDir)
        }
    }
}
