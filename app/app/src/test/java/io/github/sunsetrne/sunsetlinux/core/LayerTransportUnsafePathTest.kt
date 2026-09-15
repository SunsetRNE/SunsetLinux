package io.github.sunsetrne.sunsetlinux.core

import io.github.sunsetrne.sunsetlinux.TestPaths
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * **源码级契约**：zstd 解压不许再走 aircompressor 的 direct-ByteBuffer / mmap 快路径。
 *
 * ## 为什么需要这样一个"看着很别扭"的测试
 *
 * 2026-09-16 真机事故：`LauncherViewModel.init` 里的 zstd 能力探测把 App 变成
 * **"一打开就闪退"**，用户只能卸载重装。crash dump 指得很清楚：
 *
 * ```
 * #00 art::Unsafe_getInt(_JNIEnv*, _jobject*, _jobject*, long)
 * #06 io.airlift.compress.zstd.ZstdFrameDecompressor.verifyMagic
 * #14 io.airlift.compress.zstd.ZstdDecompressor.decompress
 * #18 io.github.sunsetrne.sunsetlinux.core.LayerDecompressor.decompressZstd
 * ```
 *
 * aircompressor 用 `UnsafeUtil.getAddress(buffer)` 取 direct buffer 的裸地址再 `Unsafe.getInt`，
 * 在 Android（ART）上拿到的地址不可用 → **SIGSEGV**。SIGSEGV 杀的是整个进程：
 * `try/catch`、`runCatching`、协程的异常处理**全都拦不住**。
 *
 * 而**桌面 JVM 上同一条路径完全正常** —— 所以 `LayerDecompressorTest`（拿真实产物跑）
 * 全绿也发现不了。能守住的只有"源码里不许出现这些符号"，也就是本测试。
 *
 * | 断言 | 守的是什么 |
 * |---|---|
 * | 没有 `MappedByteBuffer` / `inChannel.map(` | 不许再用 mmap direct buffer |
 * | 没有 `getDecompressedSize` | 不许再用"从裸地址读帧头"那条 Unsafe 路 |
 * | 没有 `ZstdDecompressor(` | 不许再用 ByteBuffer 版解码入口（要用 `ZstdInputStream`） |
 * | 确实用了 `ZstdInputStream(` | 流式路径还在（不然上面几条"通过"是因为功能被删了） |
 * | 探测与生产同构 | `TransportSupport` 探的就是 `LayerDecompressor.decompress` |
 */
class LayerTransportUnsafePathTest {

    private val src = File(
        TestPaths.repoRoot,
        "app/app/src/main/java/io/github/sunsetrne/sunsetlinux/core/LayerTransport.kt",
    )

    /**
     * **只看代码，不看注释**：文件里到处在解释"为什么不能用 direct buffer"，
     * 那些说明里必然出现 `ZstdDecompressor(` / `MappedByteBuffer` 这些字样。
     * 去掉注释行（`//` 与 `*` 开头的行）后再判。
     */
    private fun read(): String {
        assertTrue("找不到 ${src.path}（文件被改名或移动了？）", src.isFile)
        return src.readLines()
            .filterNot { line ->
                val t = line.trim()
                t.startsWith("//") || t.startsWith("*") || t.startsWith("/*")
            }
            .joinToString("\n")
    }

    private fun failIfPresent(needle: String, why: String) {
        assertTrue("LayerTransport.kt 里出现了 `$needle` —— $why", !read().contains(needle))
    }

    @Test
    fun `不许再用 mmap direct buffer`() {
        failIfPresent("MappedByteBuffer", "direct buffer 的裸地址在 Android 上会让进程 SIGSEGV")
        failIfPresent("inChannel.map(", "mmap 出来的 direct buffer 就是崩溃的入口")
    }

    @Test
    fun `不许再用从裸地址读帧头的 Unsafe 路径`() {
        failIfPresent("getDecompressedSize", "它按裸地址读帧头，Android 上会 SIGSEGV")
        failIfPresent("readHeaderBytes", "为帧头读文件头是旧 fast path 的一部分，已不需要")
    }

    @Test
    fun `zstd 必须走流式解码器`() {
        failIfPresent("ZstdDecompressor(", "ByteBuffer/数组版入口会挑 direct buffer 快路径")
        assertTrue(
            "zstd 解压应当使用 io.airlift.compress.zstd.ZstdInputStream（只用堆内数组）",
            read().contains("ZstdInputStream("),
        )
    }

    @Test
    fun `能力探测必须走与生产相同的解码入口`() {
        val text = read()
        assertTrue(
            "TransportSupport.runProbe 必须调用 LayerDecompressor.decompress（否则探测通过≠生产可用）",
            text.contains("LayerDecompressor.decompress(src, dst, LayerTransport.ZSTD"),
        )
    }
}
