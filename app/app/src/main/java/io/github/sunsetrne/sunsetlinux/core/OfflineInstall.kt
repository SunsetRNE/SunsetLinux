package io.github.sunsetrne.sunsetlinux.core

import android.content.Context
import android.os.Environment
import java.io.File

/**
 * 从**内嵌离线包**安装环境 —— 不联网，装完即用。
 *
 * ## 它和 [UpdateApplier] 的关系
 *
 * 两者是**同一个契约的两条来路**，都满足"§5.4：解压 → 按裸镜像 sha256 校验 → `linuxctl update`"：
 *
 * | 步骤 | [UpdateApplier]（频道） | [OfflineApplier]（内嵌包） |
 * |---|---|---|
 * | 产物来源 | HTTP 下载 | APK 的 `assets/offline-bundle.bin` |
 * | 压缩产物校验 | 清单 `sha256` | 包头 `part.sha256` |
 * | 解压 | `LayerTransport` | 包头 `part.transport`（同一条解压路径） |
 * | 裸镜像校验 | 清单 `sha256_raw` | 包头 `part.sha256_raw` |
 * | 落盘 | `linuxctl update <id> <raw> --version <ver>` | **完全相同** |
 *
 * 也就是说：离线安装与在线更新**写的是同一份 `layers/<id>-<ver>.erofs` 与同一份 `state.json`**，
 * 不存在"离线装的环境和在线更新对不上"的问题。
 *
 * ## 为什么 proot 部件必须**先**装
 *
 * 非 root 模式下 `linuxctl` 是 proot 脚本（`$LINUX_HOME/bin/linuxctl.sh`），
 * 它自己要用 `$LINUX_HOME/proot/bin/proot`。所以顺序是**先 proot 运行时、再层**；
 * root 模式下完全跳过 proot 部件（那个模式用不到它，装了只是白占 1 MB）。
 *
 * ## 与"版本"的关系
 *
 * 包头的每个层部件都带 `version`，安装时原样透传给 `linuxctl update --version`——
 * 这正是 `needsInstallDecision` / `find_layer` 依赖的元数据；不带版本号就会退化成
 * `<id>.erofs`（回滚语义失效）。**所以本文件绝不省这一步。**
 */
object OfflineApplier {

    enum class Stage(val label: String) {
        READ("读内嵌包"),
        VERIFY_ARCHIVE("压缩产物校验"),
        DECOMPRESS("解压"),
        VERIFY_IMAGE("镜像校验"),
        APPLY("linuxctl update"),
        PROOT("铺 proot 运行时"),
    }

    data class Outcome(
        val ok: Boolean,
        val log: String,
        val failedStage: Stage? = null,
        /** 实际装下去的东西（层 id 或 `proot`）；界面用它播报"装了什么"。 */
        val installed: List<String> = emptyList(),
    )

    /** 部件顺序：`proot` → `base` → `runtime` → `dsh`（层必须自底向上铺，overlay 才能起来）。 */
    internal fun order(parts: List<OfflineBundle.Part>): List<OfflineBundle.Part> {
        val rank = mapOf("proot" to 0, "base" to 1, "runtime" to 2, "dsh" to 3)
        return parts.sortedBy { p -> if (p.isLayer) rank[p.id] ?: 9 else 0 }
    }

    /** 部件的稳定标识：层用 id，proot 用 `proot`。 */
    fun keyOf(part: OfflineBundle.Part): String = if (part.isLayer) part.id ?: "?" else "proot"

    /**
     * 装一份内嵌离线包。
     *
     * @param only 只装这些部件（[keyOf] 的集合）；**空集 = 全装**（默认行为，向导/一键就用它）。
     * @param onStage 进度回调，签名与 [UpdateApplier] 一致，界面可以复用同一套进度条。
     */
    fun apply(
        context: Context,
        mode: EnvMode,
        bundle: OfflineBundle.Bundle,
        only: Set<String> = emptySet(),
        onStage: (stage: String, done: Long, total: Long) -> Unit,
    ): Outcome {
        val log = StringBuilder()
        val all = order(bundle.parts)
        val parts = if (only.isEmpty()) all else all.filter { keyOf(it) in only }
        log.append("=== 内嵌离线包：变体 ${bundle.variant}（${all.size} 个部件，本次装 ${parts.size} 个）===\n")
        if (parts.isEmpty()) {
            return Outcome(
                false,
                log.append("✗ [${Stage.READ.label}] 这个组合里没有可安装的部件（变体 ${bundle.variant}）\n").toString(),
                Stage.READ,
            )
        }
        for (p in parts) log.append("· ${Stage.READ.label}：${p.human}，${formatBytes(p.len)}\n")

        // 磁盘预检：压缩产物 + 解压后的裸镜像都要落在 cacheDir（临时目录），额外留 128 MB 余量
        val need = parts.sumOf { it.len + (it.sizeRaw ?: 0L) } + 128L * 1024 * 1024
        val usable = runCatching { context.cacheDir.usableSpace }.getOrDefault(Long.MAX_VALUE)
        if (usable < need) {
            return Outcome(
                false,
                log.append(
                    "✗ [${Stage.READ.label}] 磁盘空间不足：可用 ${formatBytes(usable)}，" +
                        "预计需要 ${formatBytes(need)}（内嵌包解包要占临时空间）\n"
                ).toString(),
                Stage.READ,
            )
        }

        val work = File(context.cacheDir, "offline").apply { mkdirs() }
        val installed = mutableListOf<String>()

        for (part in parts) {
            val key = keyOf(part)
            val safeName = part.file.replace('/', '_').replace("..", "_")
            val archive = File(work, safeName)
            try {
                // 1. 从 assets 里**流式**取出这个部件（几百 MB，绝不整块进内存）
                onStage("${Stage.READ.label} $key", 0, part.len)
                val digest = try {
                    OfflineBundle.copyPart(context, bundle, part, archive) { done, total ->
                        onStage("${Stage.READ.label} $key", done, total)
                    }
                } catch (t: Throwable) {
                    return Outcome(
                        false,
                        log.append("✗ [${Stage.READ.label}] $key：${t.message ?: t.javaClass.simpleName}\n").toString(),
                        Stage.READ,
                        installed,
                    )
                }

                // 2. 压缩产物校验（包头 sha256）
                onStage("${Stage.VERIFY_ARCHIVE.label} $key", 1, 1)
                if (!digest.equals(part.sha256, ignoreCase = true)) {
                    return Outcome(
                        false,
                        log.append(
                            "✗ [${Stage.VERIFY_ARCHIVE.label}] $key sha256 不匹配\n" +
                                "  包头 ${part.sha256}\n  实际 $digest\n" +
                                "  （APK 内的离线包被改动或截断；请重新安装官方 APK）\n"
                        ).toString(),
                        Stage.VERIFY_ARCHIVE,
                        installed,
                    )
                }
                log.append("· [${Stage.VERIFY_ARCHIVE.label}] $key 通过（${formatBytes(archive.length())}）\n")

                if (!part.isLayer) {
                    // 3a. proot 运行时：root 模式用不到，明确跳过而不是假装装过
                    if (mode == EnvMode.ROOT) {
                        log.append(
                            "· [${Stage.PROOT.label}] root 模式不需要 proot 运行时，本次跳过" +
                                "（本机由 linuxctl 走 chroot）\n"
                        )
                        continue
                    }
                    onStage("${Stage.PROOT.label}", 0, 1)
                    val home = DshPaths.linuxHome(context, mode)
                    val prootDir = File(home, "proot")
                    if (!prootDir.exists() && !prootDir.mkdirs()) {
                        return Outcome(
                            false,
                            log.append("✗ [${Stage.PROOT.label}] 建不了目录：${prootDir.absolutePath}\n").toString(),
                            Stage.PROOT,
                            installed,
                        )
                    }
                    val untar = Proc.stream(
                        cmd = listOf("/system/bin/tar", "-xzf", archive.absolutePath, "-C", prootDir.absolutePath),
                        onLine = { line -> log.append("  $line\n") },
                    )
                    if (!untar.ok) {
                        return Outcome(
                            false,
                            log.append("✗ [${Stage.PROOT.label}] 解包失败：${untar.message}\n").toString(),
                            Stage.PROOT,
                            installed,
                        )
                    }
                    // 宿主侧脚本（linuxctl.sh / start.sh / entry.sh）随 APK 走 assets，一起铺好
                    val scripts = ProotRuntime.ensure(context, home)
                    if (!scripts.ok) {
                        return Outcome(
                            false,
                            log.append("✗ [${Stage.PROOT.label}] ${scripts.error}\n").toString(),
                            Stage.PROOT,
                            installed,
                        )
                    }
                    log.append(
                        "· [${Stage.PROOT.label}] proot 二进制 → ${prootDir.absolutePath}；" +
                            "宿主脚本 ${scripts.written.size} 个 → $home/bin" +
                            "（契约路径 ${if (scripts.contractReady) "已就位" else "未就位"}）\n"
                    )
                    // ★ Android 的 tar（toybox）**不还原文件模式**：proot 本体与它自带的 loader
                    //   解出来都没有执行位 ⇒ `proot-launch.sh` 的 `exec "$loader" …` 会死在
                    //   `Permission denied`（启动器自己只 chmod 了 bin/proot，漏了 loader）。
                    //   `ensure()` 里已经统一点过一次，这里再核一遍，并把**实际**补了哪些文件写进
                    //   日志 —— 静默修复等于下次排障还得重新推一遍。
                    val fixed = ProotRuntime.normalizeExecBits(prootDir)
                    if (fixed.isNotEmpty()) {
                        log.append(
                            "· [${Stage.PROOT.label}] 补执行位 ${fixed.size} 个" +
                                "（Android 的 tar 不还原模式）：${fixed.take(4).joinToString(", ")}" +
                                "${if (fixed.size > 4) " …" else ""}\n"
                        )
                    }
                    scripts.error?.let { log.append("  注意：$it\n") }
                    installed += "proot"
                    onStage("${Stage.PROOT.label}", 1, 1)
                    continue
                }

                // 3b. 层：解压 → 裸镜像校验 → linuxctl update（与在线更新同一条落盘路径）
                val id = part.id ?: "?"
                val transport = LayerTransport.from(part.transport, part.file)
                val raw = File(work, "$id-${part.version ?: "x"}.raw.erofs")
                onStage("${Stage.DECOMPRESS.label} $id", 0, part.sizeRaw ?: -1)
                val decompressed = try {
                    LayerDecompressor.decompress(
                        src = archive,
                        dst = raw,
                        transport = transport,
                        expectRaw = part.sizeRaw,
                    )
                } catch (t: Throwable) {
                    return Outcome(
                        false,
                        log.append("✗ [${Stage.DECOMPRESS.label}] $id：${t.message ?: t.javaClass.simpleName}\n").toString(),
                        Stage.DECOMPRESS,
                        installed,
                    )
                }
                log.append(
                    "· [${Stage.DECOMPRESS.label}] $id 完成：${formatBytes(decompressed.size)}" +
                        "（${transport.wire} → 裸镜像）\n"
                )

                onStage("${Stage.VERIFY_IMAGE.label} $id", 1, 1)
                val expectRaw = part.sha256Raw
                if (!expectRaw.isNullOrBlank()) {
                    if (!decompressed.sha256.equals(expectRaw, ignoreCase = true)) {
                        return Outcome(
                            false,
                            log.append(
                                "✗ [${Stage.VERIFY_IMAGE.label}] $id sha256_raw 不匹配\n" +
                                    "  期望 $expectRaw\n  实际 ${decompressed.sha256}\n"
                            ).toString(),
                            Stage.VERIFY_IMAGE,
                            installed,
                        )
                    }
                    log.append("· [${Stage.VERIFY_IMAGE.label}] $id 裸镜像校验通过\n")
                } else {
                    log.append("· [${Stage.VERIFY_IMAGE.label}] 包头没给 $id 的 sha256_raw，跳过（无法确认解压结果）\n")
                }
                if (part.sizeRaw != null && decompressed.size != part.sizeRaw && expectRaw.isNullOrBlank()) {
                    return Outcome(
                        false,
                        log.append(
                            "✗ [${Stage.VERIFY_IMAGE.label}] $id 大小不符：实测 ${decompressed.size} 字节，" +
                                "包头 size_raw=${part.sizeRaw} 字节（且没有 sha256_raw 可比对）\n"
                        ).toString(),
                        Stage.VERIFY_IMAGE,
                        installed,
                    )
                }

                val args = buildList {
                    add("update")
                    add(id)
                    add(raw.absolutePath)
                    val v = part.version?.trim()?.ifEmpty { null }
                    if (v != null) {
                        add("--version")
                        add(v)
                    }
                }
                log.append("· [${Stage.APPLY.label}] linuxctl ${args.joinToString(" ")}\n")
                onStage("${Stage.APPLY.label} $id", 1, 1)
                val ctl = LinuxCtl(context, mode)
                val result = ctl.stream(args) { line -> log.append("  $line\n") }
                if (!result.ok) {
                    return Outcome(
                        false,
                        log.append("✗ [${Stage.APPLY.label}] $id 失败：${result.message}\n").toString(),
                        Stage.APPLY,
                        installed,
                    )
                }
                if (part.version.isNullOrBlank()) {
                    log.append("  注意：包头没有 $id 的版本号，本次不带 --version（落盘名退化为 <id>.erofs）\n")
                }
                log.append("✓ 层 $id 已装为 ${part.version ?: "（未指定版本）"}\n")
                installed += id
            } finally {
                // 临时文件立刻删：一份内嵌包解包能占 800 MB+
                runCatching { archive.delete() }
                runCatching { File(work, "${part.id ?: "proot"}-${part.version ?: "x"}.raw.erofs").delete() }
            }
        }

        return Outcome(true, log.append("✓ 内嵌离线包安装完成：${installed.joinToString(", ")}\n").toString(), null, installed)
    }

    /** 一个部件的安装计划（给界面显示"本机 X → 装 Y"）。 */
    data class PlanItem(
        val key: String,
        val label: String,
        val part: OfflineBundle.Part,
        /** 本机已装版本（没装/读不到都是 null）。 */
        val localVersion: String?,
        /** 要不要装（[needsInstall] 的结论）。 */
        val needed: Boolean,
    )

    /**
     * 算出"这个内嵌包对**本机**意味着什么"（纯函数，便于单测）。
     *
     * [prootReady] 由调用方查文件系统给出（`ProotRuntime.isReady`）——proot 部件不是层，
     * 没有版本可比，只能看它到底铺没铺下去。
     */
    fun plan(
        local: Map<String, String?>,
        bundle: OfflineBundle.Bundle,
        prootReady: Boolean = false,
    ): List<PlanItem> = order(bundle.parts).map { p ->
        val key = keyOf(p)
        val localVer = if (p.isLayer) local[p.id] else null
        val needed = if (p.isLayer) needsInstall(local, p) else !prootReady
        PlanItem(key = key, label = p.human, part = p, localVersion = localVer, needed = needed)
    }

    /**
     * 这个内嵌包里的部件**是否值得装**（纯函数，便于单测）。
     *
     * 与 `needsInstallDecision` 同一套规则，只是"远端版本"换成了包头里的版本；
     * `local` 是 `linuxctl status` 报的已装版本。proot 部件不参与（它不是层）。
     */
    internal fun needsInstall(local: Map<String, String?>, part: OfflineBundle.Part): Boolean {
        if (!part.isLayer) return true
        val id = part.id ?: return false
        return needsInstallDecision(local[id], part.version)
    }

    /** `cacheDir` 所在卷的可用空间（界面提示用；拿不到就返回 null）。 */
    fun cacheUsable(context: Context): Long? =
        runCatching { context.cacheDir.usableSpace }.getOrNull()
            ?: runCatching { Environment.getDataDirectory().usableSpace }.getOrNull()
}
