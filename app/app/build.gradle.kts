plugins {
    alias(libs.plugins.android.application)
    // AGP 9 起 Kotlin 支持内置，**不能**再 apply org.jetbrains.kotlin.android。
    // Compose 只需要编译器插件。
    alias(libs.plugins.kotlin.compose)
}

android {
    namespace = "io.dshroid"
    compileSdk = 35

    defaultConfig {
        applicationId = "io.dshroid"
        minSdk = 26
        targetSdk = 35
        versionCode = 1
        versionName = "0.1.0"
    }

    buildTypes {
        debug {
            // 刻意不加 applicationIdSuffix：包名必须保持 io.dshroid，
            // 否则 KernelSU 里已授予的 root 授权会失效（授权按包名 + 签名记录）。
            isMinifyEnabled = false
        }
        release {
            isMinifyEnabled = false
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
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
    systemProperty("dshroid.repo.dir", repoRoot.absolutePath)
    systemProperty("dshroid.dist.dir", File(repoRoot, "dist").absolutePath)
}
