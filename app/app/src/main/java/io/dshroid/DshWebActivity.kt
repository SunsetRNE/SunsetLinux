package io.dshroid

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import io.dshroid.ui.theme.applyDshSystemBars
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.ui.Modifier
import io.dshroid.ui.DshWebPane
import io.dshroid.ui.theme.DshroidTheme

/**
 * 独立的 DSH Web 界面。**只是 [DshWebPane] 的一层壳**：
 * 界面逻辑（令牌、Cookie、下拉刷新、错误态）全部在可复用的面板里，
 * 这样通知栏「打开 DSH」与首页的「DSH」tab 走的是同一份实现。
 */
class DshWebActivity : ComponentActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        applyDshSystemBars()
        setContent {
            DshroidTheme {
                DshWebPane(
                    modifier = Modifier
                        .fillMaxSize()
                        .safeDrawingPadding(),
                    showBack = true,
                    onBack = { finish() },
                    onGoHome = { finish() },
                )
            }
        }
    }
}
