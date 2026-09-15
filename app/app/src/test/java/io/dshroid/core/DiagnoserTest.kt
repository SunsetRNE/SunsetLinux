package io.dshroid.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test
import java.io.File

/**
 * 失败归因的回归测试。
 *
 * 存在的理由：Linux 侧的报错文案**是中文**（`无法创建挂载点 …`、`缺少层文件 …`、
 * `sdcard 两种路径都挂载失败` …），而 v1 的 `classify()` 只匹配英文关键词，
 * 于是一大半挂载类失败被归成笼统的"启动失败" —— 用户看到的就是"启动失败"加一堆
 * 没法操作的提示。这个测试用**运行时脚本里的真实文案**把关键词表钉住：
 * 谁改了文案、或谁只维护了英文关键词，测试都会提醒。
 *
 * 覆盖三件事：
 * 1. 真实中文文案 → 期望归因（表驱动）；
 * 2. **覆盖率**：直接扫描 `runtime/` 下各脚本里的 `die/err` 文案，逐条断言不会落到"未知"；
 * 3. 归因未知时，首要建议必须是「运行诊断」，不能给一个可能误导的"启动"分类。
 */
class DiagnoserTest {

    // 仓库根由 TestPaths 向上查找解析，**不写死开发机绝对路径**（否则别人 clone 跑不了）。
    private val repoDir = io.dshroid.TestPaths.repoRoot

    // ────────────────────────────────────────────── ① 真实文案 → 归因

    /** 这些字符串**逐字**来自 Linux 侧脚本（`start.sh` 等的 `die "..."`）。 */
    private val realWorldCases: List<Triple<String?, FailureKind, String>> = listOf(
        // —— 挂载类（v1 全部漏判，退化成"启动"）——
        Triple("无法创建挂载点 /data/linux/rootfs/mnt/sdcard", FailureKind.MOUNT, "创建挂载点失败"),
        Triple("无法创建层挂载点 /data/linux/layers-mnt/base", FailureKind.MOUNT, "创建层挂载点失败"),
        Triple("无法卸载残留的层挂载点 /data/linux/layers-mnt/dsh", FailureKind.MOUNT, "残留挂载点卸不掉"),
        Triple("sdcard 两种路径都挂载失败（/mnt/pass_through/0/emulated / /storage/emulated/0）", FailureKind.MOUNT, "sdcard 直挂与回退都失败"),
        Triple("sdcard 回退路径 /storage/emulated/0 也不存在", FailureKind.MISSING, "回退路径不存在"),
        Triple("devpts 挂载失败（node-pty 需要伪终端）", FailureKind.MOUNT, "devpts 挂载失败"),
        Triple("bind 挂载 /system -> /system 失败（toybox mount 不支持，且无 util-linux 回退）", FailureKind.MOUNT, "bind 挂载失败"),
        Triple("overlay 挂载失败（lowerdir/upperdir/workdir 见 /data/linux/run/linux.log）", FailureKind.MOUNT, "overlay 挂载失败"),
        Triple("无法创建 overlay upper/work 目录", FailureKind.MOUNT, "overlay 目录建不出来"),
        Triple("无法创建 /data/linux/rootfs", FailureKind.MOUNT, "rootfs 目录建不出来"),
        Triple("无法创建 rootfs 内部目录骨架", FailureKind.MOUNT, "rootfs 骨架建不出来"),
        Triple(
            "内核不支持 erofs（/proc/filesystems 里没有）。层 base=base.erofs 无法挂载。" +
                "若是 squashfs，本机内核 CONFIG_SQUASHFS 未启用，请改用 erofs 层（见 doctor 第 1 节）",
            FailureKind.MOUNT,
            "内核不支持该格式",
        ),
        Triple("层 dsh 既不是 erofs 也不是 squashfs（magic=28b52ffd）：dsh.erofs 可能没下完或已损坏", FailureKind.MOUNT, "magic 不识别"),
        Triple("层 dsh 格式不可识别（magic=0）：dsh.erofs", FailureKind.MOUNT, "magic 不可识别"),
        Triple("层 runtime 挂载失败（erofs, runtime.erofs）：内核可能不支持该格式或文件损坏（doctor 可诊断）", FailureKind.MOUNT, "层挂载失败"),
        Triple("无法卸载残留的 /data/linux/layers-mnt/runtime", FailureKind.MOUNT, "残留挂载点"),

        // —— 缺失类 ——
        Triple("缺少层文件 /data/linux/layers/base.erofs（也没有 base.squashfs）", FailureKind.MISSING, "层文件缺失"),
        Triple("缺少可写层镜像 /data/linux/upper.img", FailureKind.MISSING, "可写层镜像缺失"),
        Triple("环境内找不到 node。rootfs 的 runtime 层不完整：请 linuxctl update runtime <file> 或 provision --seed 重新部署。", FailureKind.MISSING, "node 缺失"),
        Triple(
            "环境内找不到 DSH：既没有 /usr/local/bin/dsh，也没有 /usr/local/lib/node_modules/@deepseek-ai/dsh/lib/bin.js。请 linuxctl update dsh <dsh层tar> 安装。",
            FailureKind.MISSING,
            "DSH 缺失",
        ),

        // —— 端口 / 权限（英文与中文都可能出现）——
        Triple("端口 3080 被占用，无法启动 dsh web", FailureKind.PORT, "端口占用（中文）"),
        Triple("java.net.BindException: Address already in use", FailureKind.PORT, "端口占用（英文）"),
        Triple("EADDRINUSE: address already in use 127.0.0.1:3080", FailureKind.PORT, "EADDRINUSE"),
        Triple("挂载失败：Permission denied", FailureKind.PERMISSION, "权限优先于挂载（更具体）"),
        Triple("不允许挂载：SELinux 拒绝了该操作", FailureKind.PERMISSION, "SELinux 拒绝"),
        Triple("环境内操作权限不足（CapEff 为 0）", FailureKind.PERMISSION, "能力不足"),

        // —— 运行环境 ——
        Triple("启动超时：dsh web 在 60 秒内没有就绪", FailureKind.RUNTIME, "启动超时"),
        Triple("健康检查失败：GET http://127.0.0.1:3080/ 无响应", FailureKind.RUNTIME, "健康检查"),

        // —— 兜底：不许猜成"启动" ——
        Triple("啊哦，出了点问题", FailureKind.UNKNOWN, "无法归因"),
        Triple("", FailureKind.NONE, "空 last_error"),
        Triple(null, FailureKind.NONE, "null last_error"),
    )

    @Test
    fun `真实中文与英文文案都能正确归因`() {
        val failures = StringBuilder()
        println("真实文案 → 归因（%d 条）".format(realWorldCases.size))
        realWorldCases.forEach { (err, expect, why) ->
            val actual = Diagnoser.classify(err)
            val mark = if (actual == expect) "✓" else "✗"
            println("  $mark ${(err ?: "null").take(58)}  →  ${actual.label}")
            if (actual != expect) {
                failures.append("\n  ✗ 「${err}」预期 ${expect.label}，实际 ${actual.label}（$why）")
            }
        }
        assertEquals("归因不符：$failures", 0, failures.length)
    }

    /** v1 的回归点：这些**绝不能**再被判成"启动"。 */
    @Test
    fun `挂载类中文文案不再退化成笼统的启动失败`() {
        val v1Broken = listOf(
            "无法创建挂载点 /data/linux/rootfs/proc",
            "无法创建层挂载点 /data/linux/layers-mnt/base",
            "无法卸载残留的层挂载点 /data/linux/layers-mnt/dsh",
            "sdcard 两种路径都挂载失败（/mnt/pass_through/0/emulated / /storage/emulated/0）",
            "无法创建 rootfs 内部目录骨架",
        )
        v1Broken.forEach { err ->
            val kind = Diagnoser.classify(err)
            assertEquals("「$err」应归为挂载", FailureKind.MOUNT, kind)
            assertNotEquals("「$err」不能落到未知", FailureKind.UNKNOWN, kind)
        }
    }

    // ────────────────────────────────────────────── ② 覆盖率：扫描真实脚本

    /** 打包脚本（mkmodule.sh）的报错不会进 `last_error`，只统计不参与断言。 */
    private val runtimeScriptDirs = listOf("runtime/root", "runtime/proot", "runtime/common")

    @Test
    fun `运行时脚本里的报错文案不会落到未知`() {
        val scripts = runtimeScriptDirs
            .map { File(repoDir, it) }
            .filter { it.isDirectory }
            .flatMap { dir -> dir.listFiles { f -> f.isFile && f.name.endsWith(".sh") }?.toList() ?: emptyList() }
        assumeTrue("找不到 runtime 脚本（${repoDir.path}），跳过覆盖率检查", scripts.isNotEmpty())

        val messages = scripts.flatMap { script -> extractDieMessages(script).map { script.name to it } }
        assumeTrue("runtime 脚本里没有解析到 die/err 文案", messages.isNotEmpty())

        val unknown = mutableListOf<String>()
        println("runtime 报错文案归因覆盖（%d 条 / %d 个脚本）".format(messages.size, scripts.size))
        messages.forEach { (file, raw) ->
            val expanded = expandShellVars(raw)
            val kind = Diagnoser.classify(expanded)
            println("  ${if (kind == FailureKind.UNKNOWN) "✗" else "✓"} [$file] ${expanded.take(56)}  →  ${kind.label}")
            if (kind == FailureKind.UNKNOWN) unknown.add("[$file] $expanded")
        }

        assertEquals(
            "以下运行时文案无法归因（请给 Diagnoser.classify 补中文关键词，或在本测试里显式列入豁免）：\n" +
                unknown.joinToString("\n"),
            0,
            unknown.size,
        )
    }

    /**
     * 解析 `die "..."` / `err "..."` / `fail "..."`。
     *
     * 关键：要求关键字出现在**语句起始**（行首，或 `|`/`&`/`;`/`{`/`(` 之后）。
     * 否则会把 doctor.sh 里的 `add_finding fail "layer_x_sha256" "..."` 当成报错文案 ——
     * 那里的字符串是**检查项 ID**（机器标识），不是给用户看的句子。
     */
    private fun extractDieMessages(script: File): List<String> {
        val re = Regex("""(?m)(?:^|[|&;{(])\s*(?:die|err|fail)\s+"([^"]+)"""")
        return re.findAll(script.readText())
            .map { it.groupValues[1].trim() }
            .filter { it.isNotEmpty() && it != "\$msg" && it != "\$1" }
            .toList()
    }

    /** 把 `$VAR` / `${VAR}` 换成一个像样的路径，让判定接近真实运行时。 */
    private fun expandShellVars(text: String): String =
        text.replace(Regex("""\$\{[A-Za-z_][A-Za-z0-9_]*\}"""), "/data/linux/x")
            .replace(Regex("""\$[A-Za-z_][A-Za-z0-9_]*"""), "/data/linux/x")

    // ────────────────────────────────────────────── ③ 未知时的首要建议

    @Test
    fun `归因未知时首要建议是运行诊断`() {
        val hints = Diagnoser.hints(
            status = DshStatus.unavailable("啊哦，出了点问题").copy(state = EnvState.ERROR),
            provisioned = true,
            suAvailable = true,
            mode = EnvMode.ROOT,
        )
        val doctorHint = hints.firstOrNull { it.target == HintTarget.DOCTOR }
        assertTrue("未知归因必须给出「运行诊断」建议", doctorHint != null)
        assertEquals("首要建议应当是运行诊断", "运行诊断", doctorHint!!.action)
        assertTrue(
            "未知归因时不要给可能误导的分类文案，实际：${hints.first().title}",
            hints.any { it.title.contains("未知") },
        )
    }

    @Test
    fun `阶段文案在未知归因时提示跑一键诊断`() {
        val status = DshStatus.unavailable("啊哦，出了点问题").copy(state = EnvState.ERROR)
        val stage = Diagnoser.stage(status, provisioned = true, suAvailable = true, mode = EnvMode.ROOT)
        assertTrue("阶段应提示跑诊断，实际：$stage", stage.contains("一键诊断"))
    }

    @Test
    fun `挂载失败时给出挂载归因与诊断建议`() {
        val status = DshStatus.unavailable("无法创建挂载点 /data/linux/rootfs/proc").copy(state = EnvState.ERROR)
        assertEquals("失败（挂载）", Diagnoser.stage(status, true, true, EnvMode.ROOT))
        val hints = Diagnoser.hints(status, provisioned = true, suAvailable = true, mode = EnvMode.ROOT)
        assertEquals("挂载失败应首要建议运行诊断", HintTarget.DOCTOR, hints.first().target)
    }

    @Test
    fun `端口占用时给出改端口建议`() {
        val status = DshStatus.unavailable("端口 3080 被占用，无法启动 dsh web").copy(state = EnvState.ERROR)
        val hints = Diagnoser.hints(status, provisioned = true, suAvailable = true, mode = EnvMode.ROOT)
        assertEquals(HintTarget.SETTINGS_PORT, hints.first().target)
    }

    /** 层名里带 "dsh" 不能被误判成"Node/DSH 运行环境问题"。 */
    @Test
    fun `层名里的 dsh 不会误判成运行时问题`() {
        assertEquals(
            FailureKind.MOUNT,
            Diagnoser.classify("层 dsh 挂载失败（erofs, dsh.erofs）：内核可能不支持该格式或文件损坏"),
        )
        assertEquals(
            FailureKind.MISSING,
            Diagnoser.classify("缺少层文件 /data/linux/layers/dsh.erofs"),
        )
    }
}
