package io.github.sunsetrne.sunsetlinux.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import io.github.sunsetrne.sunsetlinux.core.DshRuntime
import io.github.sunsetrne.sunsetlinux.core.EnvMode
import io.github.sunsetrne.sunsetlinux.core.EnvState
import io.github.sunsetrne.sunsetlinux.core.LinuxCtl
import io.github.sunsetrne.sunsetlinux.core.Prefs
import io.github.sunsetrne.sunsetlinux.ui.components.CapsuleReserve
import io.github.sunsetrne.sunsetlinux.ui.components.DshCard
import io.github.sunsetrne.sunsetlinux.ui.components.SectionLabel
import io.github.sunsetrne.sunsetlinux.ui.theme.TextMuted
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * 「源与镜像」独立页。
 *
 * ## 为什么从「设置」页里拿出来
 *
 * 源（npm registry / pip index-url）与"端口、自启、外观"这类 App 自身偏好不是一回事：
 * 它改的是**环境内的配置文件**，有两个落点、需要"环境在跑"这个前置条件、还要回读验证。
 * 原先把 npm 那张卡夹在设置页中间，副作用是：
 * ① 设置页被撑得很长（真机反馈"设置页实在是太长了"）；
 * ② 侧边栏本来就有「npm 源」入口，点进去却只能滚到设置页中段 —— 入口和页面不是一对一。
 * 现在它是侧边栏的一个独立页，两张卡（npm / Python）放在一起，以后加源只加卡片。
 *
 * ## 生效方式（真机事实，别写成"立即生效"）
 *
 * **重启环境**后新起的进程才会读到新的 conf；正在跑的 `dsh web` 不受影响，
 * 但装插件 / `pip install` 是另起进程，所以装之前切一下就行。
 */
@Composable
fun SourcesPane(modifier: Modifier = Modifier) {
    Column(
        modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.background)
            .verticalScroll(rememberScrollState())
            .padding(horizontal = 16.dp),
    ) {
        Spacer(Modifier.height(8.dp))

        DshCard(Modifier.fillMaxWidth()) {
            Column(Modifier.fillMaxWidth()) {
                SectionLabel("这里改的是什么")
                Spacer(Modifier.height(6.dp))
                Text(
                    text = "下面两档设置写的是**环境内**的配置文件（npm 的 .npmrc、pip 的 pip.conf），" +
                        "不是 App 自己的偏好。两档都需要环境处于运行中才能写入，" +
                        "并且都是**重启环境**后新起的进程才会读到。",
                    style = MaterialTheme.typography.labelSmall,
                    color = TextMuted,
                )
            }
        }

        Spacer(Modifier.height(12.dp))

        NpmSourceCard(Modifier.fillMaxWidth())

        Spacer(Modifier.height(12.dp))

        PypiSourceCard(Modifier.fillMaxWidth())

        // 悬浮胶囊底栏会盖住最后一张卡片
        Spacer(Modifier.height(CapsuleReserve))
    }
}

/**
 * 环境根在文案里的占位符。
 *
 * 真实路径随 edition/模式不同（`/data/sunsetlinux` 或 App 私有目录），卡片里只写
 * 「环境根/相对路径」即可 —— 这也是两张源卡片共用的字面量，只留一份。
 */
internal const val ENV_HOME_PLACEHOLDER = "<环境根>"

/**
 * 两张源卡片共用的"落盘外壳"。
 *
 * 为什么值得抽出来：两张卡片的差别只有**写哪个文件、写什么内容**，而"先确认环境已部署
 * 且运行中、写盘期间禁用按钮、结果落成一句提示"这套判定如果各写一份，迟早漂移
 * （典型后果：一张卡允许"环境没跑"时点应用、另一张不允许，用户看到两种行为）。
 */
internal class SourceEnvSession(
    private val context: android.content.Context,
    private val scope: CoroutineScope,
) {
    var busy by mutableStateOf(false)
        private set
    var notice by mutableStateOf<String?>(null)
        private set

    fun dismissNotice() {
        notice = null
    }

    /** 还没到执行阶段的提示（地址校验失败之类）：直接落成一条提示，别静默丢弃。 */
    fun warn(text: String) {
        notice = text
    }

    /**
     * [block] 在**确认环境可用之后**执行，返回给用户看的一句话（成功或失败）。
     *
     * 注意 [busy] 的置位在协程之外：连点两次"应用"时要立即挡住第二次（而不是等
     * 协程调度回来才发现 busy 已经是 true）。
     */
    fun launch(block: suspend (LinuxCtl, EnvMode) -> String) {
        if (busy) return
        busy = true
        scope.launch {
            val choice = withContext(Dispatchers.IO) { DshRuntime.resolveMode(context, Prefs(context)) }
            val ctl = LinuxCtl(context, choice.mode)
            val problem = withContext(Dispatchers.IO) {
                if (!ctl.exists()) {
                    "环境尚未部署"
                } else {
                    val st = ctl.status()
                    if (st.state != EnvState.RUNNING) "环境未运行（${st.state.label}）" else null
                }
            }
            notice = if (problem != null) {
                "$problem：源设置需要环境运行中才能写入。"
            } else {
                try {
                    block(ctl, choice.mode)
                } catch (t: Throwable) {
                    "执行失败：${t.message ?: t.javaClass.simpleName}"
                }
            }
            busy = false
        }
    }
}

@Composable
internal fun rememberSourceEnvSession(): SourceEnvSession {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    return remember { SourceEnvSession(context, scope) }
}
