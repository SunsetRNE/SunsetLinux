// SunsetLinux 启动器 App —— Gradle 设置
//
// 说明：本机（Android 手机内的 Ubuntu）以 aarch64 运行，AGP 默认从 Google Maven 取
// x86-64 版 aapt2，无法执行。构建环境已通过 ~/.gradle/gradle.properties 的
// android.aapt2FromMavenOverride 指向 aarch64 版 aapt2；此设置属于**环境相关**配置，
// 故意不写进本文件，以免污染 x86-64 开发机的构建。
pluginManagement {
    repositories {
        google()
        mavenCentral()
        gradlePluginPortal()
    }
}

dependencyResolutionManagement {
    repositoriesMode.set(RepositoriesMode.FAIL_ON_PROJECT_REPOS)
    repositories {
        google()
        mavenCentral()
        // libsu（真 root shell）只发布在 JitPack
        maven("https://jitpack.io") { content { includeGroup("com.github.topjohnwu.libsu") } }
    }
}

rootProject.name = "sunsetlinux-launcher"
include(":app")

// 内核 v2：sunsetd（纯 JVM，跑在 app_process 上；见 docs/core-v2-design.md §6 D1 定案）
include(":sunsetd")
