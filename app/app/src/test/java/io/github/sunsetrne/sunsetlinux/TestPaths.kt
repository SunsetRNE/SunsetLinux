package io.github.sunsetrne.sunsetlinux

import java.io.File

/**
 * 测试用的路径解析。
 *
 * ⚠️ **不要把仓库根写死成某个绝对路径**：那是开发机的路径，别人 clone 下来必然跑不了。
 * 这里从当前工作目录**逐级向上**找"仓库根标志"（`rootfs/layer-spec.sh`，它只存在于本仓库根），
 * 找不到就**明确报错**，而不是悄悄退回一个错的默认值 —— 后者会让测试"看起来跑了"其实读的是别人的文件。
 *
 * 仍可用系统属性覆盖：
 *   `-Dsunsetlinux.repo.dir=<仓库根>`、`-Dsunsetlinux.dist.dir=<产物目录>`
 *
 * Gradle 下也可在 `build.gradle.kts` 里统一传：
 *   `tasks.withType<Test> { systemProperty("sunsetlinux.repo.dir", rootProject.projectDir.absolutePath) }`
 */
object TestPaths {

    /** 仓库根标志：这个文件只在仓库根下存在，用它做锚点最不容易误判。 */
    private const val MARKER = "rootfs/layer-spec.sh"

    /** 仓库根目录。 */
    val repoRoot: File = run {
        val override = System.getProperty("sunsetlinux.repo.dir")
        if (!override.isNullOrBlank()) {
            val f = File(override)
            require(f.isDirectory) { "-Dsunsetlinux.repo.dir 指向的不是目录：$override" }
            f
        } else {
            // System.getProperty 返回 String?（Java 签名）——显式给个兜底，
            // 免得 Kotlin 报 "inferred type is String?, but String was expected"
            val cwd = System.getProperty("user.dir") ?: "."
            val start = File(cwd).absoluteFile
            var dir: File? = start
            var found: File? = null
            while (dir != null) {
                if (File(dir, MARKER).isFile) { found = dir; break }
                dir = dir.parentFile
            }
            requireNotNull(found) {
                "找不到仓库根：从 $start 向上查 $MARKER 均未命中。" +
                    "请在构建时传 -Dsunsetlinux.repo.dir=<仓库根>。"
            }
        }
    }

    /** 产物目录（默认 `<仓库根>/dist`）。 */
    val distDir: File =
        System.getProperty("sunsetlinux.dist.dir")
            ?.takeIf { it.isNotBlank() }
            ?.let { File(it) }
            ?: File(repoRoot, "dist")
}
