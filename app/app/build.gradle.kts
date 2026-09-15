plugins {
    alias(libs.plugins.android.application)
    // AGP 9 起 Kotlin 支持内置，**不能**再 apply org.jetbrains.kotlin.android。
    // Compose 只需要编译器插件。
    alias(libs.plugins.kotlin.compose)
}

android {
    namespace = "io.github.sunsetrne.sunsetlinux"
    compileSdk = 35

    defaultConfig {
        applicationId = "io.github.sunsetrne.sunsetlinux"
        minSdk = 26
        targetSdk = 35
        // 版本号规则：每次对外发布都要 +1（Android 只按 versionCode 判"这是不是新版"）。
        // 0.2.0：内置终端、DSH 入口上顶栏、更新进侧边栏、内置官方频道。
        // 0.2.1：部署向导缺层时会去跑 device-provision.sh（此前只调 linuxctl provision，
        //        而那个命令**不构建层** —— 真机上向导必然以 "provision 失败" 收场）。
        // 0.2.2：把模块版本提示从 ≥1.0.5 更正为 ≥1.0.6（1.0.5 在真机上还有两个坑：
        //        mksh 没有 printf %q、profiles/ 没随包）。
        // 0.2.3：修**一打开就闪退**：zstd 能力探测走 aircompressor 的 direct-ByteBuffer/Unsafe
        //        快路径，在 Android 上 SIGSEGV（进程直接死，catch 不住）。改成流式解码。
        versionCode = 5
        versionName = "0.2.3"
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
