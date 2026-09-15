// 根工程：只声明插件，不在这里配置 Android。
plugins {
    alias(libs.plugins.android.application) apply false
    alias(libs.plugins.kotlin.compose) apply false
}
