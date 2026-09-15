package io.github.sunsetrne.sunsetlinux

import android.content.Intent
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import io.github.sunsetrne.sunsetlinux.ui.theme.applyDshSystemBars
import androidx.lifecycle.lifecycleScope
import androidx.compose.runtime.remember
import io.github.sunsetrne.sunsetlinux.ui.WelcomeScreen
import io.github.sunsetrne.sunsetlinux.ui.WelcomeState
import io.github.sunsetrne.sunsetlinux.ui.theme.SunsetLinuxTheme

/**
 * 首启引导（模式选择 → 分支准备 → 部署 → 完成）。
 *
 * 只在首次启动时自动弹出（由 [LauncherActivity] 依据 `Prefs.onboarded` 触发），
 * 之后可从侧边栏「重新部署 / 首启引导」随时重进。
 */
class WelcomeActivity : ComponentActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        applyDshSystemBars()
        setContent {
            SunsetLinuxTheme {
                val state = remember { WelcomeState(this, lifecycleScope) }
                WelcomeScreen(
                    state = state,
                    onOpenProvision = {
                        startActivity(Intent(this, ProvisionActivity::class.java))
                    },
                    onFinish = { finish() },
                )
            }
        }
    }
}
