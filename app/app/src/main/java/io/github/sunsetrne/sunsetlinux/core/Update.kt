package io.github.sunsetrne.sunsetlinux.core

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.io.File
import java.net.URI

/**
 * 频道清单里的一层（docs/architecture.md §5.2，**schema 已冻结**）。
 *
 * 字段语义（别搞混）：
 * - [primary] / [fallback] 的 `sha256`、`size` 都是**压缩文件本身**的；
 * - [sha256Raw] / [sizeRaw] 是**解压后裸镜像**的。
 *
 * `linuxctl update` 只接受解压后的裸镜像：它会做 magic 检测（erofs=`0xE0F5E1E2`），
 * 喂压缩产物必然被判为"既不是 erofs 也不是 squashfs"。
 */
data class ChannelLayer(
    val id: String,
    val version: String?,
    val primary: LayerArtifact?,
    val fallback: LayerArtifact?,
    val sha256Raw: String?,
    val sizeRaw: Long?,
)

/** 选中的产物 + 选择理由（理由会写进更新日志，便于排障）。 */
data class ArtifactChoice(val artifact: LayerArtifact, val note: String?)

/** 本地版本 → 频道版本 的差异项，只列出真正变化的层。 */
data class LayerUpdate(
    val id: String,
    val fromVersion: String?,
    val toVersion: String?,
    val sha256Raw: String?,
    val sizeRaw: Long?,
    val primary: LayerArtifact?,
    val fallback: LayerArtifact?,
) {
    val key: String get() = "$id:${toVersion ?: sha256Raw ?: primary?.url ?: fallback?.url ?: "?"}"


    /**
     * §5.2 消费方规则 1：**支持 zstd 就取 `url`，否则取 `url_gz`**。
     * 两者都不可得 → null，由调用方明确报错（绝不静默跳过）。
     *
     * 三种"想用 zstd 但用不了"的情形都要留下 [ArtifactChoice.note]：
     * 本机不支持 zstd、频道没给 gzip 回退、主产物本身就不是 zstd。
     */
    fun choose(zstdUsable: Boolean): ArtifactChoice? {
        val p = primary
        val f = fallback
        if (p == null && f == null) return null

        if (p != null) {
            if (p.transport != LayerTransport.ZSTD) return ArtifactChoice(p, null)
            if (zstdUsable) return ArtifactChoice(p, null)
            return if (f != null) {
                ArtifactChoice(f, "未启用 zstd，将使用 gzip 产物")
            } else {
                ArtifactChoice(p, "本机不支持 zstd 且频道未提供 gzip 回退产物，仍尝试 zstd")
            }
        }
        return ArtifactChoice(f!!, "频道未提供主产物，使用 gzip 回退产物")
    }
}

enum class ReportState { OK, REJECTED, ERROR }

/**
 * 单个频道的检查结果。
 *
 * [REJECTED] 专指**验签失败**：契约要求「不得静默降级」，所以这时不仅不能用它的层，
 * 还要在界面上明确告诉用户是哪个频道、为什么被拒。
 */
data class ChannelReport(
    val channel: Channel,
    val state: ReportState,
    val reason: String? = null,
    val dshVersion: String? = null,
    val dshDistTag: String? = null,
    val updates: List<LayerUpdate> = emptyList(),
)

/**
 * 频道与更新检查（§5.4 步骤 1~3 的前半段：拉取、验签、比对）。
 * 真正的「下载 → 校验 → 解压 → 再校验 → 应用」由 [UpdateApplier] 完成。
 */
object UpdateChecker {

    suspend fun check(channels: List<Channel>, current: DshStatus): List<ChannelReport> =
        withContext(Dispatchers.IO) {
            val verifierProblem = SignatureVerifier.availability()
            channels.filter { it.enabled }.map { channel ->
                if (verifierProblem != null && channel.pubkey.isNotBlank()) {
                    return@map ChannelReport(channel, ReportState.REJECTED, verifierProblem)
                }
                checkOne(channel, current)
            }
        }

    /**
     * 这一层到底要不要装？**纯函数**，便于单测（真机上这个判断错一次就是"看起来没更新"）。
     *
     * 规则（每条都有真机依据）：
     *   · 远端没有版本号 → 不装（清单里缺版本说明它自己就没准备好）
     *   · 本地**没有**这层（本地版本为 null 且 `status` 里也没有这条）→ 装
     *   · ★ 本地**有**这层但**版本号读不出来**（例：老版本 `linuxctl provision` 写的
     *     `state.json` 里三个层都是 `"version": null`）→ **也要装**。
     *     以前这里返回 false（"无需更新"），结果是：一个"文件在、但元数据是空的"环境
     *     永远等不到修复 —— 用户在「更新」页看不到任何可装的层，只能靠自己发现。
     *     装完 `linuxctl update … --version X` 会把版本号写进 state.json，下一次就正常比较了，
     *     所以这条**不会**变成"永远提示有更新"。
     *   · 两边都有版本 → 不同才装。
     */
    private fun checkOne(channel: Channel, current: DshStatus): ChannelReport {
        return try {
            val body = Http.getBytes(channel.url)
            if (channel.pubkey.isNotBlank()) {
                val sigText = Http.getText(sigUrl(channel.url), 64 * 1024)
                val ok = try {
                    SignatureVerifier.verify(channel.pubkey, body, sigText)
                } catch (t: Throwable) {
                    false
                }
                if (!ok) {
                    return ChannelReport(
                        channel,
                        ReportState.REJECTED,
                        "签名校验失败，已拒绝该频道（不静默降级）",
                    )
                }
            }

            val root = JSONObject(String(body, Charsets.UTF_8))
            val layersArr = root.optJSONArray("layers")
            val remote = buildList {
                if (layersArr != null) {
                    for (i in 0 until layersArr.length()) {
                        val o = layersArr.optJSONObject(i) ?: continue
                        val id = o.optString("id", "").trim()
                        if (id.isEmpty()) continue

                        val primaryUrl = o.str("url")
                        val fallbackUrl = o.str("url_gz")
                        val primary = primaryUrl?.let {
                            LayerArtifact(
                                transport = LayerTransport.from(o.str("transport"), it),
                                url = resolve(channel.url, it),
                                sha256 = o.str("sha256"),
                                size = o.num("size"),
                            )
                        }
                        val fallback = fallbackUrl?.let {
                            LayerArtifact(
                                transport = LayerTransport.from(o.str("transport_gz") ?: "gzip", it),
                                url = resolve(channel.url, it),
                                sha256 = o.str("sha256_gz"),
                                size = o.num("size_gz"),
                            )
                        }
                        if (primary == null && fallback == null) continue

                        add(
                            ChannelLayer(
                                id = id,
                                version = o.str("version"),
                                primary = primary,
                                fallback = fallback,
                                sha256Raw = o.str("sha256_raw"),
                                sizeRaw = o.num("size_raw"),
                            )
                        )
                    }
                }
            }

            val updates = remote.mapNotNull { layer ->
                val local = current.layer(layer.id)
                val changed = needsInstallDecision(local?.version, layer.version)
                if (!changed) {
                    null
                } else {
                    LayerUpdate(
                        id = layer.id,
                        fromVersion = local?.version,
                        toVersion = layer.version,
                        sha256Raw = layer.sha256Raw,
                        sizeRaw = layer.sizeRaw,
                        primary = layer.primary,
                        fallback = layer.fallback,
                    )
                }
            }

            val npm = root.optJSONObject("dsh_npm")
            ChannelReport(
                channel = channel,
                state = ReportState.OK,
                dshVersion = updates.firstOrNull { it.id == "dsh" }?.toVersion
                    ?: current.layer("dsh")?.version,
                dshDistTag = npm?.str("dist_tag"),
                updates = updates,
            )
        } catch (e: Throwable) {
            ChannelReport(channel, ReportState.ERROR, e.message ?: e.javaClass.simpleName)
        }
    }

    /** 按频道优先级汇总待更新层，同一层以**优先级高**的频道为准。 */
    fun merge(reports: List<ChannelReport>): List<LayerUpdate> {
        val ordered = reports
            .filter { it.state == ReportState.OK }
            .sortedByDescending { it.channel.priority }
        val merged = LinkedHashMap<String, LayerUpdate>()
        ordered.forEach { report ->
            report.updates.forEach { update ->
                merged.putIfAbsent(update.id, update)
            }
        }
        // base → runtime → dsh 的固定顺序，避免界面上跳来跳去
        val order = listOf("base", "runtime", "dsh")
        return merged.values.sortedBy { order.indexOf(it.id).let { i -> if (i < 0) order.size else i } }
    }

    private fun sigUrl(url: String): String = "$url.sig"

    private fun resolve(base: String, relative: String): String =
        if (relative.startsWith("http://") || relative.startsWith("https://")) relative
        else runCatching { URI(base).resolve(relative).toString() }.getOrDefault(relative)

    private fun JSONObject.str(key: String): String? {
        if (!has(key) || isNull(key)) return null
        return optString(key, "").trim().ifEmpty { null }
    }

    private fun JSONObject.num(key: String): Long? {
        if (!has(key) || isNull(key)) return null
        return when (val v = opt(key)) {
            is Number -> v.toLong()
            is String -> v.trim().toLongOrNull()
            else -> null
        }
    }
}

/** 更新流水线的阶段，用于把"卡在哪一步"写清楚。 */
enum class ApplyStage(val label: String) {
    SELECT("选产物"),
    DOWNLOAD("下载"),
    VERIFY_ARCHIVE("压缩产物校验"),
    DECOMPRESS("解压"),
    VERIFY_IMAGE("镜像校验"),
    APPLY("linuxctl update"),
}

/**
 * 下载并应用更新（§5.2 消费方规则 1~4 + §5.4 第 3~4 步）。
 *
 * 六步，**一步都不能少**：
 * 1. 选产物（支持 zstd 取 `url`，否则取 `url_gz`；都没有 → 明确报错）
 * 2. 下载所选产物（进度用**所选产物**的 size）
 * 3. 按**所选产物**的 sha256 校验（传输完整性）
 * 4. **解压**（`.zst`→zstd，`.gz`→gzip，无后缀→原样拷贝）
 * 5. 按 **`sha256_raw`** 校验解压结果
 * 6. 把**解压后的裸镜像**交给 `linuxctl update <id> <raw> [--version <ver>]`
 *
 * 任何一步失败都会记下阶段名；若失败发生在 zstd 路径且频道提供了 gzip 回退产物，
 * 会自动改用 gzip 重跑一遍（并把原因写进日志），而不是直接失败。
 */
object UpdateApplier {

    data class ApplyOutcome(
        val ok: Boolean,
        val log: String,
        /** 失败时卡在哪个阶段；成功为 null */
        val failedStage: ApplyStage? = null,
    )

    fun apply(
        context: android.content.Context,
        mode: EnvMode,
        update: LayerUpdate,
        onStage: (stage: String, done: Long, total: Long) -> Unit,
    ): ApplyOutcome {
        val log = StringBuilder()
        val zstdReason = TransportSupport.zstdUnavailableReason()
        if (zstdReason != null) log.append("· $zstdReason\n")

        val choice = update.choose(zstdUsable = zstdReason == null)
            ?: return ApplyOutcome(
                ok = false,
                log = log.append(
                    "✗ [${ApplyStage.SELECT.label}] 频道既没提供 `url` 也没提供 `url_gz`，" +
                        "无法更新「${update.id}」层\n"
                ).toString(),
                failedStage = ApplyStage.SELECT,
            )
        choice.note?.let { log.append("· $it\n") }

        // 磁盘空间预检：下载产物 + 解压后镜像都要落地
        val needBytes = (choice.artifact.size ?: 0L) + (update.sizeRaw ?: 0L) + 256L * 1024 * 1024
        val usable = runCatching { context.cacheDir.usableSpace }.getOrDefault(Long.MAX_VALUE)
        if (usable < needBytes) {
            return ApplyOutcome(
                ok = false,
                log = log.append(
                    "✗ [${ApplyStage.DOWNLOAD.label}] 磁盘空间不足：可用 ${formatBytes(usable)}，" +
                        "预计需要 ${formatBytes(needBytes)}\n"
                ).toString(),
                failedStage = ApplyStage.DOWNLOAD,
            )
        }

        var outcome = runPipeline(context, mode, update, choice, log, onStage)

        // zstd 路径失败 → 有 gzip 回退产物就自动换 gzip 重试（每一步都不静默）
        val failedInArtifact = outcome.failedStage in setOf(
            ApplyStage.DOWNLOAD,
            ApplyStage.VERIFY_ARCHIVE,
            ApplyStage.DECOMPRESS,
            ApplyStage.VERIFY_IMAGE,
        )
        val fallback = update.fallback
        if (!outcome.ok && failedInArtifact &&
            choice.artifact.transport == LayerTransport.ZSTD &&
            fallback != null && fallback != choice.artifact
        ) {
            log.append("· zstd 路径在「${outcome.failedStage?.label}」失败，自动改用 gzip 回退产物重试\n")
            outcome = runPipeline(context, mode, update, ArtifactChoice(fallback, null), log, onStage)
        }

        return ApplyOutcome(outcome.ok, log.toString(), outcome.failedStage)
    }

    /** 单次流水线（1~6 步）。 */
    private fun runPipeline(
        context: android.content.Context,
        mode: EnvMode,
        update: LayerUpdate,
        choice: ArtifactChoice,
        log: StringBuilder,
        onStage: (stage: String, done: Long, total: Long) -> Unit,
    ): ApplyOutcome {
        val artifact = choice.artifact
        val target = update.toVersion ?: update.fromVersion ?: "?"
        log.append("=== ${update.id}: ${update.fromVersion ?: "未安装"} → $target ===\n")
        log.append("· [${ApplyStage.SELECT.label}] ${artifact.url}\n")
        log.append(
            "  transport=${artifact.transport.wire}  size=${formatBytes(artifact.size)}" +
                "  sha256=${artifact.sha256 ?: "（清单未提供）"}\n"
        )

        val workDir = File(context.cacheDir, "layers").apply { mkdirs() }
        val stem = "${update.id}-${artifact.sha256?.take(12) ?: System.currentTimeMillis()}"
        val archive = File(workDir, "$stem${artifact.transport.suffix.ifEmpty { ".bin" }}")
        // 解压产物：绝不能让 linuxctl 误以为这还是压缩包
        val rawImage = File(workDir, "$stem.raw.erofs")

        return try {
            // 2. 下载
            onStage("下载 ${update.id}", 0, artifact.size ?: -1)
            val archiveDigest = Http.download(artifact.url, archive) { done, total ->
                onStage("下载 ${update.id}", done, total)
            }
            log.append("· [${ApplyStage.DOWNLOAD.label}] 完成：${formatBytes(archive.length())}\n")

            // 3. 压缩产物校验
            if (!artifact.sha256.isNullOrBlank()) {
                onStage("校验 ${update.id} 产物", 1, 1)
                if (!archiveDigest.equals(artifact.sha256, ignoreCase = true)) {
                    return ApplyOutcome(
                        false,
                        log.append(
                            "✗ [${ApplyStage.VERIFY_ARCHIVE.label}] sha256 不匹配\n" +
                                "  期望 ${artifact.sha256}\n  实际 $archiveDigest\n"
                        ).toString(),
                        ApplyStage.VERIFY_ARCHIVE,
                    )
                }
                log.append("· [${ApplyStage.VERIFY_ARCHIVE.label}] 压缩产物 sha256 校验通过\n")
            } else {
                log.append("· [${ApplyStage.VERIFY_ARCHIVE.label}] 清单未提供 sha256，跳过（有断链风险）\n")
            }

            // 4. 解压 —— 绝不能把压缩产物交给 linuxctl
            onStage("解压 ${update.id}", 0, update.sizeRaw ?: -1)
            val decompressed = try {
                LayerDecompressor.decompress(
                    src = archive,
                    dst = rawImage,
                    transport = artifact.transport,
                    expectRaw = update.sizeRaw,
                )
            } catch (t: Throwable) {
                return ApplyOutcome(
                    false,
                    log.append("✗ [${ApplyStage.DECOMPRESS.label}] ${t.message ?: t.javaClass.simpleName}\n").toString(),
                    ApplyStage.DECOMPRESS,
                )
            }
            log.append(
                "· [${ApplyStage.DECOMPRESS.label}] 完成：${formatBytes(decompressed.size)}" +
                    "（${artifact.transport.wire} → 裸镜像）\n"
            )

            // 5. 镜像校验
            onStage("校验 ${update.id} 镜像", 1, 1)
            val expectRaw = update.sha256Raw
            if (!expectRaw.isNullOrBlank()) {
                if (!decompressed.sha256.equals(expectRaw, ignoreCase = true)) {
                    return ApplyOutcome(
                        false,
                        log.append(
                            "✗ [${ApplyStage.VERIFY_IMAGE.label}] sha256_raw 不匹配\n" +
                                "  期望 $expectRaw\n  实际 ${decompressed.sha256}\n"
                        ).toString(),
                        ApplyStage.VERIFY_IMAGE,
                    )
                }
                log.append("· [${ApplyStage.VERIFY_IMAGE.label}] 裸镜像 sha256 校验通过\n")
            } else {
                log.append("· [${ApplyStage.VERIFY_IMAGE.label}] 清单未提供 sha256_raw，跳过（无法确认解压结果）\n")
            }
            // size_raw 与实测不符：sha256_raw 已校验通过时按"清单笔误"处理（只告警），
            // 没有 sha256_raw 可比对时才当作硬失败 —— 否则一条元数据笔误就能让更新永远卡死。
            if (update.sizeRaw != null && decompressed.size != update.sizeRaw) {
                val detail = "大小不符：实测 ${decompressed.size} 字节，清单 size_raw=${update.sizeRaw} 字节"
                if (expectRaw.isNullOrBlank()) {
                    return ApplyOutcome(
                        false,
                        log.append("✗ [${ApplyStage.VERIFY_IMAGE.label}] $detail（且清单未提供 sha256_raw，无法确认镜像）\n").toString(),
                        ApplyStage.VERIFY_IMAGE,
                    )
                }
                log.append("· [${ApplyStage.VERIFY_IMAGE.label}] 警告：$detail（sha256_raw 已通过，按清单笔误处理）\n")
            }

            // 6. linuxctl update <layer> <raw> [--version <ver>]
            val version = update.toVersion?.trim()?.ifEmpty { null }
            val args = buildList {
                add("update")
                add(update.id)
                add(rawImage.absolutePath)
                if (version != null) {
                    add("--version")
                    add(version)
                }
            }
            log.append("· [${ApplyStage.APPLY.label}] linuxctl ${args.joinToString(" ")}\n")
            if (version == null) {
                log.append("  注意：频道清单未提供 version，本次不带 --version（由运行时决定落盘命名）\n")
            }
            onStage("应用 ${update.id}", 1, 1)

            val ctl = LinuxCtl(context, mode)
            val result = ctl.stream(args) { line -> log.append("  $line\n") }
            if (!result.ok) {
                return ApplyOutcome(
                    false,
                    log.append("✗ [${ApplyStage.APPLY.label}] 失败：${result.message}\n").toString(),
                    ApplyStage.APPLY,
                )
            }
            log.append("✓ ${update.id} 层已更新为 ${version ?: "（未指定版本）"}\n")
            ApplyOutcome(true, log.toString(), null)
        } catch (t: Throwable) {
            ApplyOutcome(
                false,
                log.append("✗ [${ApplyStage.DOWNLOAD.label}] ${t.message ?: t.javaClass.simpleName}\n").toString(),
                ApplyStage.DOWNLOAD,
            )
        } finally {
            // linuxctl 已经把镜像落到 layers/ 里了；缓存里的两份都清掉（合计 200 MB+）
            runCatching { archive.delete() }
            runCatching { rawImage.delete() }
        }
    }
}

/**
 * 这一层到底要不要装？**纯函数**，便于单测（真机上这个判断错一次就是"看起来没更新"）。
 *
 * 规则（每条都有真机依据）：
 *   · 远端没有版本号 → 不装（清单里缺版本说明它自己就没准备好）
 *   · 本地**没有**这层的记录（版本为 null）→ 装
 *   · ★ 本地**有**层、但**版本号读不出来**（例：老版本 `linuxctl provision` 写的
 *     `state.json` 里三个层都是 `"version": null`）→ **也要装**。
 *     以前这里返回 false（= "无需更新"），结果是：一个"文件在、元数据是空的"环境
 *     **永远等不到修复** —— 用户在「更新」页看不到任何可装的层，只能自己发现要先删层。
 *   · 两边都有版本 → 不同才装。
 *
 * 不会变成"永远提示有更新"：装完 `linuxctl update … --version X` 会把版本号写进
 * `state.json`，下一次就是正常的版本比较。
 */
internal fun needsInstallDecision(localVersion: String?, remoteVersion: String?): Boolean = when {
    remoteVersion == null -> false
    localVersion == null -> true
    localVersion != remoteVersion -> true
    else -> false
}
