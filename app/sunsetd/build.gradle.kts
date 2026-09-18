// 内核 v2（sunsetd）—— 纯 JVM 模块。
//
// 产物：`sunsetd.jar` → 交给 `d8` 变成 dex → 随 KernelSU 模块分发，由 `service.sh`
// 用 `app_process` 起动（决策见 docs/core-v2-design.md §6，D1 定案：Kotlin/app_process）。
//
// 为什么用 buildscript classpath + apply，而不是 `plugins { kotlin("jvm") }`：
// 本机（手机内的 Ubuntu）**离线**构建，Gradle 缓存里只有 kotlin-gradle-plugin 本体，
// 没有插件 marker（`org.jetbrains.kotlin.jvm.gradle.plugin`）—— 走 classpath 能直接用缓存里的实现。
buildscript {
    repositories {
        google()
        mavenCentral()
    }
    dependencies {
        classpath("org.jetbrains.kotlin:kotlin-gradle-plugin:2.3.10")
    }
}
apply(plugin = "org.jetbrains.kotlin.jvm")

dependencies {
    // 字符串写法：`testImplementation` 这类**类型安全访问器**只在 `plugins {}` 块里才生成，
    // 而本模块为了离线用 buildscript classpath + apply（见文件头注释）。
    "testImplementation"("junit:junit:4.13.2")
}

tasks.withType<org.jetbrains.kotlin.gradle.tasks.KotlinCompile>().configureEach {
    compilerOptions {
        // 与 App 保持一致；d8 能直接吃 17 的 class 文件（真机打包路径见 module/mkmodule.sh）
        jvmTarget.set(org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_17)
    }
}

tasks.withType<Test>().configureEach {
    testLogging { events("passed", "failed", "skipped") }
}
