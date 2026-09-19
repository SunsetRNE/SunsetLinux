package io.github.sunsetrne.sunsetlinux.core

import android.content.Context
import java.io.File

/**
 * 非 root（proot）模式的**宿主侧脚本**：把 `runtime/proot/` 里的 `*.sh` 铺到 `$LINUX_HOME/bin/`。
 *
 * ## 为什么要有这个文件
 *
 * proot 模式的 `linuxctl` 是**宿主侧脚本**（见 `runtime/proot/README.md` §1）：
 * `linuxctl.sh` / `start.sh` / `entry.sh` / `selftest.sh` 必须躺在 `$LINUX_HOME/bin/`，
 * 由 App 从**外面**调用（被 ptrace 包裹时它自己会拒绝运行）。
 *
 * 以前这四个文件只有两条来路：KernelSU 模块铺（root 用户），或者用户**手动** `tar -xzf`
 * `dist/sunsetlinux-proot-runtime.tar.gz`（WelcomeScreen 里写着"第 1 步：铺 proot 运行时"）。
 * 于是「免 root 版」「完整离线版」装完其实**跑不起来** —— 内嵌包里只有 proot 二进制，
 * 没有这套脚本。现在脚本随 APK 走 assets（约 50 KB，四个变体都带），
 * 装完点一下就能铺好，不再要求用户去别处找文件。
 *
 * 脚本本身**与模块里那份同源**：`app/build.gradle.kts` 的 `syncProotRuntimeAssets`
 * 在构建时把 `runtime/proot/` 整个拷进 `assets/proot-runtime/`（单一事实源，不复制粘贴）。
 */
object ProotRuntime {

    /** assets 下的目录名（构建期由 `syncProotRuntimeAssets` 生成）。 */
    const val ASSET_DIR = "proot-runtime"

    /** `$LINUX_HOME/bin/` 下必须存在的文件（缺任何一个，proot 模式都起不来）。 */
    val REQUIRED = listOf("linuxctl.sh", "start.sh", "entry.sh")

    /** proot 部件的解包目录（相对 `$LINUX_HOME`）；[normalizeExecBits] 在这里生效。 */
    const val PROOT_DIR = "proot"

    /** 契约路径：App 与文档都按 `bin/linuxctl` 找它（`linuxctl.sh` 是允许的变体）。 */
    const val CONTRACT_NAME = "linuxctl"

    data class Result(
        /** 是否至少写下去一个文件（幂等重铺时也是 true）。 */
        val ok: Boolean,
        val written: List<String>,
        val error: String? = null,
        /** 铺完之后契约路径是否就位（`bin/linuxctl` 或 `bin/linuxctl.sh` 之一可执行）。 */
        val contractReady: Boolean,
    )

    /**
     * 把 assets 里的脚本铺到 `$home/bin/`，并保证执行位与 `linuxctl` 契约路径。
     *
     * 幂等：每次覆盖写（一共 50 KB，比重重算摘要便宜），已可执行的文件不会被反复 chmod。
     * **纯文件操作**，不碰 su / 不联网，所以 proot 模式（没有 root）也能用。
     */
    fun ensure(context: Context, home: String): Result {
        val bin = File(home, "bin")
        if (!bin.exists() && !bin.mkdirs()) {
            return Result(false, emptyList(), "建不了目录：${bin.absolutePath}（存储权限/空间不足？）", false)
        }

        val names = try {
            context.assets.list(ASSET_DIR)?.filter { it.isNotBlank() }?.sorted().orEmpty()
        } catch (t: Throwable) {
            return Result(false, emptyList(), "读不到内置脚本（assets/$ASSET_DIR）：${t.message}", false)
        }
        if (names.isEmpty()) {
            return Result(false, emptyList(), "这个 APK 里没有内置 proot 脚本（assets/$ASSET_DIR 为空）", false)
        }

        val written = mutableListOf<String>()
        for (name in names) {
            // 只铺平铺的文件：脚本之间是同目录关系，子目录（如果有）由 proot 包自己带
            if (name.contains('/')) continue
            val dst = File(bin, name)
            try {
                context.assets.open("$ASSET_DIR/$name").use { input ->
                    dst.outputStream().use { out -> input.copyTo(out) }
                }
            } catch (t: Throwable) {
                return Result(false, written, "写 $name 失败：${t.message}", false)
            }
            dst.setExecutable(true, false)
            written += name
        }

        // 契约路径：有的部署只认 `bin/linuxctl`（见 DshPaths.linuxctlCandidates），
        // 这里直接用脚本本体复制一份，不做软链 —— Android 上软链在 App 私有目录可用，
        // 但用户可能把目录搬到不支持软链的地方，复制更稳（两个文件内容一致）。
        val variant = File(bin, "linuxctl.sh")
        val contract = File(bin, CONTRACT_NAME)
        if (variant.isFile && (!contract.isFile || contract.length() != variant.length())) {
            runCatching {
                variant.copyTo(contract, overwrite = true)
                contract.setExecutable(true, false)
            }
        }

        // proot 部件的执行位：老版本（≤0.3.30）用 Android 的 tar 解包时**不还原模式**，
        // 已经装在盘上的环境会缺执行位；这里每次铺设都顺带修一遍（幂等，缺 [PROOT_DIR] 时是空操作）。
        normalizeExecBits(File(home, PROOT_DIR))

        // ⚠️ 判据是**文件在不在**，不是"有没有执行位"：App 调它时一律经 `/system/bin/sh`
        //    （见 [DshPaths.hostCtlCommand]）。私有目录里 exec 脚本在真机上会 `error=13`，
        //    拿执行位当"就绪"会在那种机器上把已铺好的环境误判成没铺。
        val ready = DshPaths.linuxctlCandidates(context, EnvMode.PROOT).any { File(it).isFile }
        val missing = REQUIRED.filterNot { File(bin, it).isFile }
        return Result(
            ok = true,
            written = written,
            error = if (missing.isEmpty()) null else "铺完了但缺少必需脚本：${missing.joinToString(", ")}（内置包不完整？）",
            contractReady = ready,
        )
    }

    /**
     * 宿主脚本是否已经就位（`bin/linuxctl` 或 `bin/linuxctl.sh` 之一**存在**）。
     *
     * 判据是"在不在"而不是"可不可执行"：App 调它时一律经 `/system/bin/sh`
     * （[DshPaths.hostCtlCommand]），所以在 noexec / 策略更严的机器上也算就位。
     */
    fun isReady(context: Context, home: String): Boolean =
        DshPaths.linuxctlCandidates(context, EnvMode.PROOT).any { File(it).isFile }

    /** 当前 `$home/bin/` 里这套脚本的状态（给"关于"/部署向导显示）。 */
    fun inspect(context: Context, home: String): String {
        val bin = File(home, "bin")
        val present = (REQUIRED + CONTRACT_NAME).filter { File(bin, it).isFile }
        val missing = (REQUIRED + CONTRACT_NAME).filterNot { File(bin, it).isFile }
        return when {
            present.isEmpty() -> "未铺（$home/bin 下没有 proot 脚本）"
            missing.isEmpty() -> "已就位（${present.size} 个文件，$home/bin）"
            else -> "不完整：缺 ${missing.joinToString(", ")}"
        }
    }

    /**
     * **把 proot 部件解出来的执行位补齐**（真机 2026-09-19 的第四处拦路虎）。
     *
     * ## 为什么必须补
     *
     * `proot-bundle-arm64.tar.gz` 里的模式是**对的**（`bin/proot` 与 `lib/ld-linux-*.so.1`
     * 都是 0755），但 App 用的是 Android 的 `tar`（toybox），它**不还原文件模式** ——
     * 解出来一律是 0600/0700。于是 `proot-launch.sh` 走到
     * `exec "$loader" --library-path "$lib" "$bin"` 时死在
     * `exec: …/lib/ld-linux-aarch64.so.1: Permission denied`
     * （启动器自己只 chmod 了 `bin/proot`，**漏了 loader**，而 loader 才是那次 exec 的主体）。
     *
     * 这个坑在"用 GNU tar 解包"的开发机上看不见，只有真机（toybox tar）会复现 ——
     * 实测复现：`tar -xzf` 后把模式改回 0600 再 `sh proot-launch.sh --version`，
     * 得到的正是上面那句。
     *
     * ## 判据
     *
     * 按 tar 里的模式还原：`bin/`、`lib/` 下的**文件**都要可执行（`proot` 本体 + loader +
     * 它要 dlopen 的 so），其余（README / license / manifest）保持普通文件；目录一律 `+x`
     * （否则连进去都不行）。**幂等**：已可执行的不重复动。
     *
     * @return 本次补了执行位的文件（相对 [root] 的路径），便于在安装日志里如实播报。
     */
    fun normalizeExecBits(root: File): List<String> {
        if (!root.isDirectory) return emptyList()
        val fixed = mutableListOf<String>()
        val queue = ArrayDeque<File>()
        queue += root
        while (queue.isNotEmpty()) {
            val dir = queue.removeFirst()
            for (c in dir.listFiles().orEmpty()) {
                if (c.isDirectory) {
                    queue += c
                    if (!c.canExecute() && c.setExecutable(true, false)) fixed += rel(root, c)
                    continue
                }
                if (!c.isFile || c.canExecute()) continue
                val r = rel(root, c)
                // 补哪几类：`bin/`、`lib/` 下的文件（proot 本体 + loader + 它 dlopen 的 so），
                // **以及根目录的 `*.sh` 启动器**。
                // ★ 后者是真机 doctor 抓到的（2026-09-19）：`proot/proot-launch.sh` 在打包里是
                //   0755，但 Android 的 tar 不还原模式 ⇒ 解出来没有执行位，而 `start.sh` 的
                //   `resolve_proot` 会把 `[[ -x "$interp" ]]` 判过（宿主的 /system/bin/sh 可执行），
                //   于是走「直接 exec 那个包装器」⇒ `proot_binary` 检查报
                //   "proot 存在但无法执行 --version"，`start` 失败。
                val executable = r.startsWith("bin/") || r.startsWith("lib/") || r.endsWith(".sh")
                if (executable && c.setExecutable(true, false)) {
                    fixed += r
                }
            }
        }
        return fixed
    }

    private fun rel(root: File, f: File): String =
        runCatching { root.toPath().relativize(f.toPath()).toString() }.getOrDefault(f.name)
}
