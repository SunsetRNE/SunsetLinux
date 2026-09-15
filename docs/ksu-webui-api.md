# KernelSU 模块 WebUI（`webroot/`）与 `ksu` JS API

> 本文的事实来自**设备上一个正在运行的模块的 `webroot/index.html`**（具体模块不宜点名），
> 不是文档推测。KernelSU 的 WebUI API 没有稳定公开文档，且随版本可能变化，**所以以实测为准**。

## 1. 形态

- 模块根放 **`webroot/index.html`**，KernelSU 管理器会在 WebView 里打开它（模块详情页的入口）。
- **Magisk 没有原生 WebUI**：需要第三方宿主（如 **MMRL**、KsuWebUI）才能显示。
  **没有宿主也不影响使用** —— 本项目的 App（"壳"）提供同样的管理能力，两边读写同一份配置。
- WebUI 运行在管理器的 WebView 里，**没有网络保证** → 必须**单文件、零外部依赖**（不要 CDN / npm）。

## 2. `ksu` JS API（★ 实测签名，与常见猜测不同）

**`ksu.exec` 不是 Promise**，天真的 `await ksu.exec(cmd)` 会失效。真实签名是：

```js
ksu.exec(command, optionsJsonString, callbackNameString)
```

第三个参数是**全局函数名的字符串**（不是函数对象），回调签名为 **`(errno, stdout, stderr)`**。

设备上实际在用的包装（可直接照抄）：

```js
function exec(command) {
  return new Promise((resolve, reject) => {
    if (typeof ksu === "undefined") { resolve({ errno: -1, stdout: "", stderr: "dev-preview" }); return; }
    const cb = "dshroid_cb_" + Date.now() + "_" + Math.floor(Math.random() * 1e6);
    window[cb] = (errno, stdout, stderr) => { delete window[cb]; resolve({ errno, stdout, stderr }); };
    try { ksu.exec(command, "{}", cb); } catch (e) { delete window[cb]; reject(e); }
  });
}
function toast(msg) { (typeof ksu !== "undefined") ? ksu.toast(msg) : console.log("toast:", msg); }
```

**要点**：
1. 回调名要**够独特**（时间戳 + 随机数），避免并发调用互相覆盖。
2. **必须** `delete window[cb]`，否则每次调用都在全局泄漏一个函数。
3. `ksu` 不存在时**优雅降级**（"dev-preview"）：这条分支让 WebUI **能在普通桌面浏览器里预览**，
   是本机唯一可行的自测手段 —— 请保留，并建议显示横幅"预览模式，命令未真正执行"。

## 3. 实现约定（本项目）

- 与 App **共用同一份状态**：`etc/config.json`（含 `autostart`）、`etc/channels.json`、`etc/state.json`。
  **禁止** WebUI 自己另存一份。
- 所有命令走 `$LINUX_HOME/bin/linuxctl`（Android 侧以 root 执行），**不要**在浏览器里重实现密码学或解压。
- **显示任何命令输出都必须 HTML 转义**，否则日志里的 `<` 会破坏页面：
  ```js
  const esc = s => String(s ?? "").replace(/[&<>]/g, c => ({ "&": "&amp;", "<": "&lt;", ">": "&gt;" }[c]));
  ```
- 建议用**底部 tab 分组**（状态 / 操作 / 日志 / 诊断 / 更新）而不是把所有东西堆一屏。
- 未部署时给**明确的下一步**（去 App 部署 / 跑 `linuxctl provision`），不要只显示错误码。
- **更新走 `.gz` 产物**：设备侧**没有 zstd**（`/system/bin/zstd` 不存在，只有 toybox `gzip`），
  而 `linuxctl update` 只接受**裸 `.erofs`** → WebUI 路径要用 `.gz` 并在浏览器侧先解压/落盘再 update。
  App 侧走 `.zst`（小 35%），界面里应注明这个差异。

## 4. 可自测性

因为存在 `typeof ksu === "undefined"` 的降级分支，WebUI 的核心逻辑是**可离线自测**的：

- 把 `<script>` 内容抽出来跑 `node --check` 做语法校验；
- 把"解析 status JSON / 渲染日志 / 比对更新版本"抽成**纯函数**，在 node 里直接单测；
- 预览模式让界面布局能在桌面浏览器里核对。

真机上仍需用户验证的部分：在 KernelSU 管理器里打开 WebUI、`ksu.exec` 的真实返回、长任务的进度表现。
