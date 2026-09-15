package io.dshroid

import android.content.Intent
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import io.dshroid.ui.theme.applyDshSystemBars
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.ui.Modifier
import io.dshroid.ui.UpdatePane
import io.dshroid.ui.rememberUpdatePaneState
import io.dshroid.ui.theme.DshroidTheme

/**
 * 独立的更新界面。**只是 [UpdatePane] 的一层壳** —— 首页的「更新」tab 与这里共用
 * 同一份状态机与界面，避免两处实现漂移。
 */
class UpdateActivity : ComponentActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        applyDshSystemBars()
        setContent {
            DshroidTheme {
                val state = rememberUpdatePaneState()
                androidx.compose.runtime.LaunchedEffect(Unit) { state.check() }
                UpdatePane(
                    state = state,
                    modifier = Modifier
                        .fillMaxSize()
                        .safeDrawingPadding(),
                    onOpenSettings = { startActivity(Intent(this, SettingsActivity::class.java)) },
                )
            }
        }
    }
}
