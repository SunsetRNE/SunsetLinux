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
//   · APK 文件名按 "产品-版本-组合" 生成 —— 四个内置组合必须能一眼分清，
//     否则下载下来全是 app-debug.apk，用户根本不知道哪个是哪个。
// ============================================================================
// 版本文件放在 **Gradle 根**（app/version.properties），不是模块目录（app/app/）——
// 所以这里显式用 rootDir 定位，别看 build 脚本的所在目录。
val versionProps = Properties().apply {
    File(rootDir, "version.properties").inputStream().use { load(it) }
}
val engineeringVersion = versionProps.getProperty("versionName").trim()
val engineeringVersionCode = versionProps.getProperty("versionCode").trim().toInt()

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
 * 内置组合（与 tools/offline-bundle/variants.json 的 id 一一对应）。
 *
 * ★ Gradle 的 flavor 名必须是合法标识符（不能带 `-`），所以内部用驼峰、产物用带横杠的 id；
 *   两者靠这里的映射对齐 —— 改组合只改这张表 + variants.json。
 */
data class EmbedFlavor(val gradleName: String, val id: String, val label: String, val parts: String)
val embedFlavors = listOf(
    EmbedFlavor("minimal", "minimal", "最小版", "proot"),
    EmbedFlavor("ubuntu", "ubuntu", "Ubuntu 版", "base,runtime"),
    EmbedFlavor("ubuntuProot", "ubuntu-proot", "免 root 版", "base,runtime,proot"),
    EmbedFlavor("ubuntuProotDsh", "ubuntu-proot-dsh", "完整离线版", "base,runtime,proot,dsh"),
)

android {
    namespace = "io.github.sunsetrne.sunsetlinux"
    compileSdk = 35

    defaultConfig {
        applicationId = "io.github.sunsetrne.sunsetlinux"
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
    // 四个内置组合：**同一个 App**（同 applicationId / 同签名 / 同 versionCode），
    // 差别只在 assets/ 里内嵌哪份离线包（见 tools/offline-bundle/variants.json）。
    // 换组合 = 覆盖安装另一个 APK，数据不丢；KernelSU 授权按【包名+签名】记，也不受影响。
    // ────────────────────────────────────────────────────────────────────────
    flavorDimensions += "embed"
    productFlavors {
        embedFlavors.forEach { f ->
            create(f.gradleName) {
                dimension = "embed"
                buildConfigField("String", "EMBED_VARIANT", "\"${f.id}\"")
                buildConfigField("String", "EMBED_LABEL", "\"${f.label}\"")
                buildConfigField("String", "EMBED_PARTS", "\"${f.parts}\"")
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
    }

    packaging {
        resources {
            excludes += "/META-INF/{AL2.0,LGPL2.1}"
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

androidComponents {
    onVariants { variant ->
        val flavor = embedFlavors.firstOrNull { it.gradleName == variant.flavorName }
        val variantId = flavor?.id ?: variant.flavorName

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
    }
}
