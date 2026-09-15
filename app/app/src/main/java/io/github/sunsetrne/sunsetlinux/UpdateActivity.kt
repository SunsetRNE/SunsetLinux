package io.github.sunsetrne.sunsetlinux

import android.content.Intent
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import io.github.sunsetrne.sunsetlinux.ui.theme.applyDshSystemBars
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.ui.Modifier
import io.github.sunsetrne.sunsetlinux.ui.UpdatePane
import io.github.sunsetrne.sunsetlinux.ui.rememberUpdatePaneState
import io.github.sunsetrne.sunsetlinux.ui.theme.SunsetLinuxTheme

/**
 * 独立的更新界面。**只是 [UpdatePane] 的一层壳** —— 首页的「更新」tab 与这里共用
 * 同一份状态机与界面，避免两处实现漂移。
 */
class UpdateActivity : ComponentActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        applyDshSystemBars()
        setContent {
            SunsetLinuxTheme {
                val state = rememberUpdatePaneState()
                androidx.compose.runtime.LaunchedEffect(Unit) { state.check() }
                UpdatePane(
                    state = state,
                    modifier = Modifier
                        .fillMaxSize()
                        .safeDrawingPadding()
            .imePadding(),
                    onOpenSettings = { startActivity(Intent(this, SettingsActivity::class.java)) },
                )
            }
        }
    }
}
