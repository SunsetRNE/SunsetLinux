package io.dshroid.ui

import android.annotation.SuppressLint
import android.content.Intent
import android.graphics.Bitmap
import android.net.Uri
import android.view.ViewGroup
import android.webkit.CookieManager
import android.webkit.WebChromeClient
import android.webkit.WebResourceError
import android.webkit.WebResourceRequest
import android.webkit.WebResourceResponse
import android.webkit.WebSettings
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.swiperefreshlayout.widget.SwipeRefreshLayout
import io.dshroid.core.DshRuntime
import io.dshroid.core.EnvState
import io.dshroid.core.LinuxCtl
import io.dshroid.core.Prefs
import io.dshroid.core.stripToken
import io.dshroid.ui.theme.MonoFamily
import io.dshroid.ui.theme.TextMuted
import io.dshroid.ui.theme.TextSecondary
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch

private enum class WebLoadState { LOADING, READY, ERROR }

/** WebView 与它的容器在重组之间保持同一个实例。 */
private class WebHolder {
    var webView: WebView? = null
    var swipe: SwipeRefreshLayout? = null
    var loadedUrl: String? = null
}

/**
 * 内嵌 DSH Web GUI（可复用面板：既是首页的「DSH」tab，也是独立的 [io.dshroid.DshWebActivity]）。
 *
 * ── 鉴权契约（docs/architecture.md §3.3，必须严格遵守）──
 * 1. `dsh web` 对裸地址 `GET /` **返回 401**，绝不能加载 `base_url`。
 * 2. 唯一入口是 **`status.dsh.url`（带 `?token=<launchToken>`）**；加载后服务端 303 到干净的 `/`
 *    并下发签名 Cookie —— 303 是正常流程，不拦截。
 * 3. `launchToken` 不落盘、每次重启都变 → **本面板不缓存它**：每次进入、每次「重新登录」
 *    都重新向 `linuxctl status` 取一次。
 * 4. Cookie 绑定 Host 授权域 → 必须用与 `dsh.url` 完全一致的授权域，绝不把 127.0.0.1
 *    改写成 localhost。
 * 5. 界面只展示去掉令牌的地址。
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun DshWebPane(
    modifier: Modifier = Modifier,
    /** 独立 Activity 里显示返回键；作为 tab 时不显示 */
    showBack: Boolean = false,
    onBack: () -> Unit = {},
    /** 「返回首页」的去处（tab 里通常切回启动页） */
    onGoHome: () -> Unit = {},
    /** 顶部是否自带一行标题栏（tab 模式关掉，改由外层 Shell 提供） */
    showHeader: Boolean = true,
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val holder = remember { WebHolder() }

    var tokenUrl by remember { mutableStateOf<String?>(null) }
    var displayUrl by remember { mutableStateOf<String?>(null) }
    var state by remember { mutableStateOf(WebLoadState.LOADING) }
    var progress by remember { mutableStateOf(0) }
    var errorText by remember { mutableStateOf<String?>(null) }
    var authExpired by remember { mutableStateOf(false) }
    var menuOpen by remember { mutableStateOf(false) }

    // 每次进入都重新取带令牌的 URL（**不缓存**）
    fun fetchToken(force: Boolean) {
        tokenUrl = null
        state = WebLoadState.LOADING
        errorText = null
        scope.launch(Dispatchers.IO) {
            val prefs = Prefs(context)
            val choice = DshRuntime.resolveMode(context, prefs)
            val status = LinuxCtl(context, choice.mode).status()
            val base = status.displayUrl
            val token = status.dshUrl
            val st = status.state
            val err = status.lastError
            launch(Dispatchers.Main) {
                displayUrl = base
                when {
                    st == EnvState.RUNNING && !token.isNullOrBlank() -> {
                        if (force) holder.loadedUrl = null
                        tokenUrl = token
                    }

                    st == EnvState.RUNNING -> {
                        state = WebLoadState.ERROR
                        errorText = "环境已在运行，但登录地址还没写出来（run/dsh.url 尚未就绪）。稍后点「重新登录」重试。"
                    }

                    else -> {
                        state = WebLoadState.ERROR
                        errorText = buildString {
                            append("环境当前不是运行状态（").append(st.label).append("），无法打开 DSH 界面。")
                            if (!err.isNullOrBlank()) append("\n").append(err)
                        }
                    }
                }
            }
        }
    }

    // API 21+ 默认接受 Cookie，但这是鉴权关键路径，显式设置
    LaunchedEffect(Unit) {
        CookieManager.getInstance().setAcceptCookie(true)
        fetchToken(force = false)
    }

    DisposableEffect(Unit) {
        onDispose {
            CookieManager.getInstance().flush()
            holder.webView?.apply {
                stopLoading()
                loadUrl("about:blank")
                destroy()
            }
            holder.webView = null
            holder.swipe = null
        }
    }

    // 系统返回：先回网页历史，再交给宿主（独立 Activity=finish；tab=切回启动页）
    BackHandler {
        val wv = holder.webView
        if (wv?.canGoBack() == true) wv.goBack() else onBack()
    }

    Column(modifier.fillMaxSize().background(MaterialTheme.colorScheme.background)) {
        if (showHeader) {
            Surface(color = MaterialTheme.colorScheme.surface, tonalElevation = 2.dp) {
                Row(
                    Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 4.dp, vertical = 6.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    if (showBack) {
                        TextButton(onClick = onBack) { Text("返回") }
                    }
                    Column(Modifier.weight(1f)) {
                        Text("DSH Web", style = MaterialTheme.typography.titleMedium)
                        Text(
                            text = displayUrl ?: "正在获取登录地址…",
                            style = MaterialTheme.typography.labelSmall.copy(fontFamily = MonoFamily),
                            color = TextMuted,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                    }
                    IconButton(onClick = { holder.webView?.reload() }) {
                        Icon(Icons.Filled.Refresh, contentDescription = "刷新")
                    }
                    Box {
                        IconButton(onClick = { menuOpen = true }) {
                            Icon(Icons.Filled.MoreVert, contentDescription = "更多")
                        }
                        DropdownMenu(expanded = menuOpen, onDismissRequest = { menuOpen = false }) {
                            DropdownMenuItem(
                                text = { Text("刷新") },
                                onClick = { menuOpen = false; holder.webView?.reload() },
                            )
                            DropdownMenuItem(
                                text = { Text("用系统浏览器打开") },
                                onClick = {
                                    menuOpen = false
                                    displayUrl?.let { url ->
                                        runCatching { context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(url))) }
                                    }
                                },
                            )
                            DropdownMenuItem(
                                text = { Text("复制地址") },
                                onClick = {
                                    menuOpen = false
                                    displayUrl?.let { copyToClipboard(context, "DSH 地址", it) }
                                },
                            )
                            DropdownMenuItem(
                                text = { Text("重新登录（刷新令牌）") },
                                onClick = { menuOpen = false; fetchToken(force = true) },
                            )
                        }
                    }
                }
            }
        }

        if (state == WebLoadState.LOADING) {
            LinearProgressIndicator(
                progress = { progress.coerceIn(0, 100) / 100f },
                modifier = Modifier
                    .fillMaxWidth()
                    .height(2.dp),
            )
        }

        if (authExpired) {
            Surface(color = MaterialTheme.colorScheme.errorContainer) {
                Row(
                    Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 16.dp, vertical = 8.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Icon(
                        Icons.Filled.Warning,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.onErrorContainer,
                        modifier = Modifier.size(18.dp),
                    )
                    Spacer(Modifier.width(8.dp))
                    Text(
                        text = "登录令牌已失效",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onErrorContainer,
                        modifier = Modifier.weight(1f),
                    )
                    TextButton(onClick = { fetchToken(force = true) }) { Text("重新登录") }
                }
            }
        }

        Box(Modifier.weight(1f)) {
            val url = tokenUrl
            when {
                url != null -> AndroidView(
                    factory = { ctx -> createWebView(ctx, holder, onProgress = { progress = it }, onState = { state = it }, onAuthExpired = { authExpired = it }, onError = { errorText = it }, onDisplayUrl = { displayUrl = it }) },
                    update = { container ->
                        holder.swipe = container
                        if (holder.loadedUrl != url) {
                            holder.loadedUrl = url
                            authExpired = false
                            errorText = null
                            state = WebLoadState.LOADING
                            holder.webView?.loadUrl(url)
                        }
                    },
                    modifier = Modifier.fillMaxSize(),
                )

                state == WebLoadState.ERROR -> Column(
                    Modifier
                        .fillMaxSize()
                        .padding(28.dp),
                    verticalArrangement = Arrangement.Center,
                    horizontalAlignment = Alignment.CenterHorizontally,
                ) {
                    Icon(
                        Icons.Filled.Warning,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.error,
                        modifier = Modifier.size(36.dp),
                    )
                    Spacer(Modifier.height(14.dp))
                    Text(
                        text = errorText ?: "无法打开 DSH 界面",
                        style = MaterialTheme.typography.bodyMedium,
                        color = TextSecondary,
                    )
                    Spacer(Modifier.height(18.dp))
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        TextButton(onClick = { fetchToken(force = true) }) { Text("重新登录") }
                        TextButton(onClick = onGoHome) { Text("返回启动页") }
                    }
                }

                else -> Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                    Column(horizontalAlignment = Alignment.CenterHorizontally) {
                        CircularProgressIndicator(modifier = Modifier.size(28.dp), strokeWidth = 3.dp)
                        Spacer(Modifier.height(12.dp))
                        Text(
                            text = "正在获取登录地址…",
                            style = MaterialTheme.typography.bodySmall,
                            color = TextMuted,
                        )
                    }
                }
            }
        }
    }
}

/** 建 WebView 容器：SwipeRefreshLayout 包 WebView（下拉刷新）。 */
@SuppressLint("SetJavaScriptEnabled")
private fun createWebView(
    context: android.content.Context,
    holder: WebHolder,
    onProgress: (Int) -> Unit,
    onState: (WebLoadState) -> Unit,
    onAuthExpired: (Boolean) -> Unit,
    onError: (String?) -> Unit,
    onDisplayUrl: (String?) -> Unit,
): SwipeRefreshLayout {
    val container = SwipeRefreshLayout(context).apply {
        // 单色板：下拉刷新用白色指示器 + 近黑底（原来是青/蓝/墨蓝，与单色板不一致）。
        // 这里是原生 View，拿不到 Compose 的 MaterialTheme，所以用与 Theme.kt 对应的灰阶字面量。
        setColorSchemeColors(0xFFFFFFFF.toInt(), 0xFFA3A3A3.toInt())
        setProgressBackgroundColorSchemeColor(0xFF0A0A0A.toInt())
        setOnRefreshListener { holder.webView?.reload() }
    }
    val view = WebView(context).apply {
        layoutParams = ViewGroup.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT,
            ViewGroup.LayoutParams.MATCH_PARENT,
        )
        settings.apply {
            javaScriptEnabled = true
            domStorageEnabled = true
            // 只访问 127.0.0.1 上的本地服务：本地文件与 content:// 一律关闭
            allowFileAccess = false
            allowContentAccess = false
            mixedContentMode = WebSettings.MIXED_CONTENT_NEVER_ALLOW
            cacheMode = WebSettings.LOAD_DEFAULT
            useWideViewPort = true
        }
        // 第三方 Cookie 无必要，显式关闭
        CookieManager.getInstance().setAcceptThirdPartyCookies(this, false)

        webViewClient = object : WebViewClient() {
            override fun shouldOverrideUrlLoading(v: WebView, request: WebResourceRequest): Boolean {
                val target = request.url.toString()
                // 回环地址留在 WebView 内（令牌与 Cookie 都绑定在这里）；
                // 其它链接交给系统浏览器，避免在没有地址栏的 WebView 里迷路。
                return if (isLoopback(target)) {
                    false
                } else {
                    runCatching { context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(target))) }
                    true
                }
            }

            override fun onPageStarted(v: WebView?, url: String?, favicon: Bitmap?) {
                onState(WebLoadState.LOADING)
            }

            override fun onPageFinished(v: WebView?, url: String?) {
                onState(WebLoadState.READY)
                holder.swipe?.isRefreshing = false
                onProgress(100)
                // 303 之后停在干净的 `/`：把展示地址同步为实际回环地址（不含令牌）
                url?.takeIf { isLoopback(it) && !it.contains("token=") }?.let { onDisplayUrl(stripToken(it)) }
            }

            override fun onReceivedError(v: WebView?, request: WebResourceRequest?, error: WebResourceError?) {
                if (request?.isForMainFrame != true) return
                onState(WebLoadState.ERROR)
                holder.swipe?.isRefreshing = false
                onError("无法连接 DSH Web：${error?.description ?: "网络错误"}\n请回启动页确认环境仍在运行。")
            }

            override fun onReceivedHttpError(
                v: WebView?,
                request: WebResourceRequest?,
                errorResponse: WebResourceResponse?,
            ) {
                if (request?.isForMainFrame != true) return
                if (errorResponse?.statusCode == 401) {
                    onState(WebLoadState.ERROR)
                    onAuthExpired(true)
                    onError("登录令牌已失效（HTTP 401）。点「重新登录」重新取一次带令牌的地址。")
                }
            }
        }

        webChromeClient = object : WebChromeClient() {
            override fun onProgressChanged(v: WebView?, newProgress: Int) {
                onProgress(newProgress)
                if (newProgress >= 100) holder.swipe?.isRefreshing = false
            }
        }
    }
    container.addView(view)
    holder.webView = view
    holder.swipe = container
    return container
}

private fun isLoopback(url: String): Boolean {
    val host = runCatching { Uri.parse(url).host ?: "" }.getOrDefault("")
    return host == "127.0.0.1" || host == "localhost" || host == "::1"
}
