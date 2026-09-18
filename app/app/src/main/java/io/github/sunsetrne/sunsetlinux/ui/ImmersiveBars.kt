package io.github.sunsetrne.sunsetlinux.ui

import android.app.Activity
import android.content.Context
import android.content.ContextWrapper
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.platform.LocalView
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat

/**
 * 沉浸式系统栏：覆盖整窗时把状态栏/导航栏收起来，离开时恢复。
 *
 * 为什么需要它：DSH 是"网页应用"，覆盖整窗时若还留着系统栏，可用高度就少了两条
 * （真机上表现为网页被导航栏切掉一截）。收起之后：
 *  - 仍可从屏幕边缘**临时**唤出系统栏（[WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE]）；
 *  - 回壳另有可见出口（悬浮「返回壳」键，见 `DshFullscreen.needsBackAffordance`），
 *    所以不会出现"收起来就找不到路回去"。
 *
 * ⚠️ 这是**窗口级**状态：必须在离开该界面时显式恢复（`onDispose` 里做了），
 * 否则壳的其它页面会一直停在沉浸态（真机上就是"顶栏跑进状态栏里"）。
 */
@Composable
fun ImmersiveBarsEffect(enabled: Boolean) {
    val view = LocalView.current
    val activity = remember(view) { view.context.findActivity() }
    DisposableEffect(activity, enabled) {
        val window = activity?.window
        if (window == null) return@DisposableEffect onDispose { }
        val controller = WindowCompat.getInsetsController(window, view)
        if (enabled) {
            controller.systemBarsBehavior =
                WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
            controller.hide(WindowInsetsCompat.Type.systemBars())
        } else {
            controller.show(WindowInsetsCompat.Type.systemBars())
        }
        onDispose { controller.show(WindowInsetsCompat.Type.systemBars()) }
    }
}

/** 从任意包裹层里找出承载窗口的 Activity（Compose 的 context 常常是 ContextWrapper）。 */
private tailrec fun Context.findActivity(): Activity? = when (this) {
    is Activity -> this
    is ContextWrapper -> baseContext.findActivity()
    else -> null
}
