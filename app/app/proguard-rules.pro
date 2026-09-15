# 纯 Java zstd 解码器（io.airlift:aircompressor）通过 sun.misc.Unsafe 取 direct ByteBuffer
# 的裸地址。sun.misc.Unsafe 不在 android.jar 的公开 API 里（但真机上由平台提供），
# D8/R8 会因为"看不到这个类"而告警，这里显式静音。
-dontwarn sun.misc.**
-dontwarn io.airlift.compress.**

# libsu 的授权相关类由它自己的 aar 规则处理；保留其 JNI 入口
-keep class com.topjohnwu.superuser.** { *; }
