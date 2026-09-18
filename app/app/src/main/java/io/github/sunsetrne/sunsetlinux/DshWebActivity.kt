package io.github.sunsetrne.sunsetlinux

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.ui.Modifier
import io.github.sunsetrne.sunsetlinux.ui.DshWebPane
import io.github.sunsetrne.sunsetlinux.ui.ImmersiveBarsEffect
import io.github.sunsetrne.sunsetlinux.ui.theme.SunsetLinuxTheme
import io.github.sunsetrne.sunsetlinux.ui.theme.applyDshSystemBars

/**
 * 独立的 DSH Web 界面（通知栏「打开 DSH」的落点）。**只是 [DshWebPane] 的一层壳**：
 * 界面逻辑（令牌、Cookie、下拉刷新、错误态）全部在可复用的面板里，
 * 这样通知栏入口与壳里的 DSH 页走的是同一份实现。
 *
 * 形状与壳里一致：**覆盖整窗 + 沉浸式系统栏 + 悬浮「返回壳」**（这里"壳"= 回退到 App）。
 * 原先这里是 `safeDrawingPadding()` 内缩 + 顶栏一行"返回"，全屏网页被上下两条边切掉 ——
 * 与壳里那个问题同源，见 [io.github.sunsetrne.sunsetlinux.ui.DshFullscreen] 的说明。
 */
class DshWebActivity : ComponentActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        applyDshSystemBars()
        setContent {
            SunsetLinuxTheme {
                ImmersiveBarsEffect(enabled = true)
                DshWebPane(
                    modifier = Modifier.fillMaxSize(),
                    showBack = true,
                    fullBleed = true,
                    onBack = { finish() },
                    onGoHome = { finish() },
                )
            }
        }
    }
}
