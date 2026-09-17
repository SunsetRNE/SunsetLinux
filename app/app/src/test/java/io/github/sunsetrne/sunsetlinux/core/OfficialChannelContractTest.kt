package io.github.sunsetrne.sunsetlinux.core

import io.github.sunsetrne.sunsetlinux.TestPaths
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * **内置官方频道**的源码级契约。
 *
 * 这条频道是 App 自带的"官方发布源"（默认启用、不可删除）。它的 URL 与 ed25519 公钥
 * 就是整条信任链的根 —— 一旦被人悄悄改掉，App 会忠诚地去验一把**别人的**签名。
 * 所以这里把三件事钉死：
 *
 * | 断言 | 防的是什么 |
 * |---|---|
 * | URL/公钥写死在 Prefs.kt 且 **https** | 被改成 http（可中间人）/被指到别的域名 |
 * | 公钥必须与 `docs/` 里公开的一致 | 代码与文档漂移 → 用户拿文档核对时对不上 |
 * | 内置项不落盘、读到列表最前、UI 不给删除键 | 用户数据里躺着一份可被改掉的"官方频道" |
 */
class OfficialChannelContractTest {

    private val repo = TestPaths.repoRoot
    private val prefs = File(repo, "app/app/src/main/java/io/github/sunsetrne/sunsetlinux/core/Prefs.kt")

    /**
     * 频道列表 UI 的落点。
     *
     * ⚠️ 2026-09 从 `SettingsActivity.kt` 搬到了 `ui/ChannelsPane.kt`（侧边栏
     * 「频道管理」独立页，见 `ui/PageSplitContractTest`）。契约跟着搬，而不是留一条
     * "因为重构就假失败"的旧断言 —— 假失败最后总会被删掉，契约也就没了。
     */
    private val settings = File(repo, "app/app/src/main/java/io/github/sunsetrne/sunsetlinux/ui/ChannelsPane.kt")
    private val handoff = File(repo, "docs/HANDOFF.md")

    private fun read(f: File): String {
        assertTrue("找不到 ${f.path}", f.isFile)
        return f.readText()
    }

    /** 与 docs/HANDOFF.md 公开的是同一把公钥（用户核对指纹用的就是它）。 */
    private val officialPubkey = "YXoAcwa3clZCRd7+DlIHMS3cQ40HlyXVSKblvX5Ye1M="
    private val officialUrl = "https://sunsetrne.github.io/SunsetLinux/channel/channel.json"

    @Test
    fun `官方频道 URL 与公钥写死在代码里，且是 https`() {
        val text = read(prefs)
        assertTrue("Prefs.kt 里应有官方频道 URL：$officialUrl", text.contains(officialUrl))
        assertTrue("Prefs.kt 里应有官方频道公钥：$officialPubkey", text.contains(officialPubkey))
        assertTrue("官方频道 id 固定为 official", text.contains("const val OFFICIAL_ID = \"official\""))
        assertFalse("官方频道 URL 不得是 http（明文可被中间人改）", text.contains("\"http://sunsetrne.github.io"))
    }

    @Test
    fun `代码里的公钥必须与文档公开的一致`() {
        val doc = read(handoff)
        assertTrue(
            "docs/HANDOFF.md 里没有公开同一把公钥 —— 代码与文档漂移，用户核对指纹会对不上",
            doc.contains(officialPubkey),
        )
    }

    @Test
    fun `内置频道不落盘、读到列表最前（用户数据不能覆盖它）`() {
        val text = read(prefs)
        assertTrue("channels 的读必须经过 withBuiltin（否则老数据里没有官方频道）", text.contains("Channel.withBuiltin("))
        assertTrue("channels 的写必须经过 withoutBuiltin（否则用户数据里会留一份可被改掉的官方频道）", text.contains("Channel.withoutBuiltin("))
        assertTrue("withBuiltin 的实现应该把内置项放在最前", text.contains("listOf(OFFICIAL.copy("))
    }

    @Test
    fun `内置频道在频道管理页不给删除键与改键`() {
        val text = read(settings)
        assertTrue("ChannelRow 要能识别内置频道", text.contains("Channel.isBuiltin(ch.id)"))
        assertTrue("内置频道不应显示「改」与「删除」", text.contains("if (!builtin) {"))
        assertTrue("删除回调里也要拒绝内置频道（兜底）", text.contains("Channel.isBuiltin(ch.id)"))
    }
}
