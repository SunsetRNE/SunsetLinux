# SunsetLinux 启动器（Android App）

Kotlin + Jetpack Compose + Material 3 的启动器，用于在 Android 上原生运行 DSH
（Node.js CLI + Web GUI），替代旧的 proot 方案。

**与 Linux 侧的唯一接口是 `linuxctl`**（契约见 [`../docs/architecture.md`](../docs/architecture.md) §3）。
本 App 不解析 rootfs、不挂载、不注入任何东西。

---

## 构建

```bash
cd app
# 需要 JDK 17、Android SDK（platform android-35、build-tools 35.0.0）
export ANDROID_HOME=/path/to/Android

# ⚠️ 必须**两个一起跑**：
#    assembleDebug 不编译 test 源集 —— 只跑它会让单测静默失效
#    （曾经踩过：改了生产代码的 token，测试文件编译不过却毫无提示）。
./gradlew :app:assembleDebug :app:testDebugUnitTest

# 产物：app/build/outputs/apk/debug/app-debug.apk
# 测试报告：app/build/reports/tests/testDebugUnitTest/index.html
```

单测共 **40 个**（`LayerDecompressorTest` 13 / `DiagnoserTest` 8 / `FeaturesTest` 10 /
`PaletteContrastTest` 9），必须 `failures=0 errors=0`。

| 项 | 值 |
|---|---|
| Gradle | 9.1.0（wrapper 已入库） |
| AGP | 9.0.0 |
| Kotlin | 2.3.10（**AGP 9 内置 Kotlin 支持**，不要再 apply `org.jetbrains.kotlin.android`） |
| Compose BOM | 2026.01.01（material3 1.4.0 / ui 1.10.2） |
| compileSdk / targetSdk / minSdk | 35 / 35 / 26 |
| 包名 | `io.github.sunsetrne.sunsetlinux` |

### 在 aarch64 设备上构建的注意事项

AGP 默认从 Google Maven 取 **x86-64** 版 `aapt2`，在 aarch64 上无法执行。
本机通过 `~/.gradle/gradle.properties` 里的

```properties
android.aapt2FromMavenOverride=/opt/aapt2-arm64/aapt2
```

指向一个 aarch64 原生 aapt2（Termux 构建）。这属于**环境相关**配置，故意没有写进
本工程的 `gradle.properties`，以免污染 x86-64 开发机。

另外 `app/gradle.properties` 里设置了 `android.aapt2.process.daemon=false`：
在 proot 环境下 aapt2 daemon 常报 `Daemon startup failed`。

---

## 代码结构

```
app/src/main/java/io/github/sunsetrne/sunsetlinux/
  SunsetLinuxApp.kt               Application：通知渠道 + 全局崩溃处理器
  LauncherActivity.kt     外壳宿主：沉浸式 + 首启门禁 + 权限；界面在 ui/AppShell.kt
  WelcomeActivity.kt      首启引导（选模式 → 分支准备 → 部署 → 完成）
  DiagnosticsActivity.kt  一键诊断：流式 doctor + 复制 + 导出 + 常见失败对照表
  ProvisionActivity.kt    部署向导（选模式 → 选频道 → 实时进度）
  SettingsActivity.kt     模式/端口/自启/电池白名单/冻结豁免/频道管理/关于（支持分区深链）
  LogActivity.kt          完整日志（行号 + 高亮 + 自动跟随 + 导出）
  DshWebActivity.kt       独立 DSH Web 页面（只是 ui/DshWebPane 的一层壳）
  UpdateActivity.kt       独立更新页面（只是 ui/UpdatePane 的一层壳）

  core/
    Status.kt             EnvMode/EnvState/DshStatus：**容错解析** §3.1 的 status JSON
    Proc.kt               CtlResult / Proc（进程封装，全部带超时）/ SuShell（libsu 优先）
    LinuxCtl.kt           与 Linux 侧的唯一入口（root: su -c；proot: 直接执行）+ DshPaths
                         + `bin/linuxctl` / `bin/linuxctl.sh` 双路径容错
    DshRuntime.kt         su 探测（60s 缓存）与**唯一的模式解析处**（选择优先，无 su 时降级并说明）
    Diagnoser.kt          ★ 阶段判定 + 失败归因 + 「下一步该做什么」建议（唯一处）
    LayerTransport.kt     ★ 传输层：Transport/Artifact/解压（gzip 流式、zstd 纯 Java + mmap）+ 能力探测
    Update.kt             频道清单模型、UpdateChecker（拉取+验签+比对）、UpdateApplier（六步流水线）
    CrashHandler.kt       ★ 全局未捕获异常 → cause 链 + 40 帧栈落盘
    LogExport.kt          ★ 一键排障包（status 原始 JSON + 环境日志 + 崩溃栈 + 设备摘要）→ FileProvider 分享
    Prefs.kt / Net.kt / EnvFiles.kt   设置与频道 / HTTP+sha256+ed25519 / 读写环境内文件
    NpmRegistry.kt        ★ npm 源预设与校验、.npmrc 生成与写入（可写层）、生效值回读；
                          + NpmDistTags（dist-tag → 版本解析）、DshDistTagStore（落 config.json）
    DshPlugins.kt         ★ 第三方插件：目标校验、install/remove 命令、解析 profile package.json

  service/
    LinuxService.kt       前台服务（specialUse）+ PARTIAL_WAKE_LOCK + 通知 4 action
    Notifications.kt      通知渠道与常驻通知
    BootReceiver.kt       开机自启（只拉状态观察服务，绝不 linuxctl start）

  ui/
    AppShell.kt           ★ 外壳：顶栏汉堡 + 侧边栏 + 悬浮胶囊底栏（3 tab）+ 关于/恢复出厂
    LauncherHomePane.kt   ★ 启动页四段式卡片：状态 → 地址 → 日志 → 操作（含失败建议卡片）
    DshWebPane.kt         ★ DSH Web 面板（tab 与独立 Activity 共用；★ 鉴权契约见下）
    UpdatePane.kt         ★ 更新面板（tab 与独立 Activity 共用）
    WelcomeState.kt       ★ 首启引导状态机
    WelcomeScreen.kt      ★ 首启引导界面（模式能力对比 + 模块/proot 两条流程）
    LauncherViewModel.kt  状态机：status 4s / logs 5s 双轮询、动作、doctor、排障包、更新角标
    Clipboard.kt          剪贴板（用平台 API，避免 Compose ClipboardManager 的版本差异）
    components/Common.kt  DshCard / Pill / StatusDot / ActionGlyph / PrimaryActionButton / ActionTile
    components/LogPanel.kt ★ 常显日志面板：固定/填满高度 + 行号 + 关键行高亮 + 自动跟随
    NpmSourceCard.kt      npm 源切换卡片（预设 + 自定义 + 应用 + 验证）
    PluginsPane.kt        插件页（安装/卸载第三方 DSH 插件 + 原始输出）
    theme/Theme.kt        **单色板（黑白灰）**：Mono0..Mono3 / Line / LineStrong / Accent /
                          TextPrimary·Secondary·Muted / WarnTone / Danger + 状态明度语义
```

### 界面信息架构

界面采用**侧边栏 + 胶囊底栏**的信息架构，
但把它"启动失败时用户无从下手"的问题反过来解决：

- **首屏永远有**：状态、当前阶段、失败原因、下一步建议、220dp 常显日志。
- **侧边栏**（汉堡 / 边缘右滑）：重新部署·首启引导 / 一键诊断 doctor / 导出排障包 /
  完整日志 / 设置 / 频道管理 / 冻结与省电豁免 / 恢复出厂 / 关于。
- **胶囊底栏**（3 个高频 tab）：启动 / 更新 / DSH。
  DSH tab 里底栏放在内容下方，不遮挡网页。
- **局域网访问灰态占位**：`dsh web` 只绑 `127.0.0.1`，局域网要另做代理（LanProxy），
  本轮**不做**，界面上明确写"需开启（后续）"，不伪造能力。
- **登录令牌默认隐藏**：地址卡常显 `base_url`；带一次性令牌的登录链接默认打码，
  需要时点「显示」/「复制」—— 令牌不进日志、不进缓存、不写文件。

---

## 两个必须遵守的契约点

### 1. `dsh web` 的鉴权模型（architecture.md §3.3）

- 裸地址 `GET /` **返回 401**；唯一入口是 `status.dsh.url`（带 `?token=<launchToken>`）。
- 该 URL 加载后服务端 303 跳到干净的 `/` 并下发签名 Cookie —— **303 是正常流程**。
- `launchToken` 不落盘、每次重启都变 → **App 不缓存**：每次进入 WebView
  都重新读 `linuxctl status`；「重新登录」也是重新读一次。
- Cookie 绑定 Host 授权域 → 必须用与 `dsh.url` 完全一致的域，**不把 `127.0.0.1` 改写成 `localhost`**。
- 界面只展示 `base_url`（`stripToken()` 去掉令牌），令牌不进 UI、不进日志。

### 2. 环境生命周期与 App 解耦（architecture.md §6）

- root 模式：`linuxctl start` 由 **KernelSU 模块开机执行**。`LinuxService` 只轮询
  `status` 刷新通知，**绝不因为读不到状态就停掉环境**；只有用户点通知上的按钮才执行命令。
- `PARTIAL_WAKE_LOCK` 只在环境确实 `running` 时持有。

---

### 3. `linuxctl` 路径的容错（实测发现）

契约路径是 `$LINUX_HOME/bin/linuxctl`。实际部署中：

- KernelSU 模块 `module/post-fs-data.sh` 会把 `linuxctl.sh` 同步到 `/data/sunsetlinux/bin/`
  并**建立契约路径软链** `bin/linuxctl` → 一致；
- proot 运行时包（`dist/droid-proot-runtime.tar.gz`）里只有 `linuxctl.sh`。

因此 `DshPaths.linuxctlCandidates()` 返回 `[bin/linuxctl, bin/linuxctl.sh]`，
`LinuxCtl.exists()` 探测后把可用路径记到 `activeCtlPath`：**契约路径永远优先**，
只有在它不存在时才退到 `.sh`，避免部署侧只铺了脚本就让用户看到"环境尚未部署"。

---

### 3. 层产物的传输层**必须解压**（§5.2）

`linuxctl update` 只接受**解压后的裸镜像**（它做 magic 检测，erofs=`0xE0F5E1E2`），
喂 `.zst`/`.gz` 必然报"既不是 erofs 也不是 squashfs"。`UpdateApplier.apply()` 因此是六步：

1. **选产物**：支持 zstd 取 `url`，否则取 `url_gz`；两者都没有 → **明确报错**（不静默跳过）
2. 下载所选产物（进度用**所选产物**的 `size`）
3. 按**所选产物**的 `sha256` 校验
4. **解压**：`.zst`→zstd、`.gz`→gzip、无后缀→原样拷贝
5. 按 **`sha256_raw`** 校验解压结果
6. `linuxctl update <id> <解压后的裸 .erofs> [--version <toVersion>]`

失败日志会写明卡在哪个阶段（`选产物 / 下载 / 压缩产物校验 / 解压 / 镜像校验 / linuxctl update`）。

**zstd 选型（重要）**：用 `io.airlift:aircompressor`（**纯 Java** 解码器），
而不是 `com.github.luben:zstd-jni` —— 后者的 jar 里只有
`aix/darwin/freebsd/linux/win` 原生库，**没有任何 Android ABI**，
其 `linux/aarch64/libzstd-jni.so` 是 glibc 链接的，在 Android bionic 上无法 dlopen。
那会变成"能编译、桌面 JVM 测试通过、到设备上 `UnsatisfiedLinkError`"的最坏情况。

- 解压实现：gzip 用 `GZIPInputStream`（**流式，常量内存**）；zstd 用纯 Java 解码器 +
  **输入/输出都 mmap 成 direct ByteBuffer**，解码只写堆外内存，堆内仅约 128 KB 工作区，
  200 MB 级镜像不产生堆峰值。
- **运行时能力探测**：`TransportSupport.zstdUnavailableReason()` 拿内嵌的真实 zstd 帧
  走完整解码路径跑一遍。任何异常（含 `sun.misc.Unsafe` 在设备上不可用）→ 判定不可用 →
  自动改用 gzip 产物并写明原因。**不靠"编译通过"就当可用。**
- 已知限制：aircompressor 的 zstd 窗口上限 **8 MiB**。`zstd` CLI 默认（level ≤ 19）就是这个
  窗口，默认产物可用；发布侧若用 `--long`/`-22` 超出，App 会自动退回 gzip 产物。
- `size_raw` 只当**提示**：zstd 输出去向按**产物自身帧头**的 content size 分配，
  解码后再与 `sha256_raw`/`size_raw` 比对；`sha256_raw` 通过而 `size_raw` 不符时按
  "清单笔误"告警而非硬失败（否则一条元数据笔误就能让更新永远卡死）。

**测试**：`app/src/test/java/io/github/sunsetrne/sunsetlinux/core/LayerDecompressorTest.kt` 用 `dist/` 里的
**真实产物**验证：两种传输产物解压后**逐字节一致**、`sha256_raw` 匹配、喂错格式必须失败、
以及 §5.2 规则 1 的选产物矩阵。产物缺失时那几条自动跳过。

```bash
./gradlew :app:testDebugUnitTest
```

---

## 权限说明

只申请必要的权限，**不申请任何存储权限**：

| 权限 | 用途 |
|---|---|
| `INTERNET` / `ACCESS_NETWORK_STATE` | 与 127.0.0.1 的 DSH Web 通信、拉取频道清单 |
| `POST_NOTIFICATIONS` | 常驻状态通知（Android 13+ 运行时申请） |
| `FOREGROUND_SERVICE` / `FOREGROUND_SERVICE_SPECIAL_USE` | 状态观察前台服务（Android 14+ 必须声明 specialUse） |
| `WAKE_LOCK` | 环境运行时保持轮询 |
| `RECEIVE_BOOT_COMPLETED` | 「开机自启状态服务」开关 |
| `REQUEST_IGNORE_BATTERY_OPTIMIZATIONS` | 申请电池优化白名单（环境需长期存活） |

明文 HTTP 只对回环地址放行（`res/xml/network_security_config.xml`）：
`base-config cleartextTrafficPermitted=false` + `domain-config` 仅含
`127.0.0.1 / localhost / ::1`。

---

## 外部依赖

| 依赖 | 用途 | 备注 |
|---|---|---|
| `com.github.topjohnwu.libsu:core:6.0.0` | root shell（常驻，省进程创建开销） | JitPack；**不可用时自动退回 `ProcessBuilder("su","-c",…)`**（见 `core/Proc.kt` 的 `SuShell`） |
| `androidx.swiperefreshlayout:1.1.0` | WebView 下拉刷新 | Compose 的 `PullToRefreshBox` 依赖 nested scroll，而 WebView 不是 `NestedScrollingChild`，会退化成「任何时候下拉都刷新」 |
| `androidx.compose.material:material-icons-core` | 图标 | 只用 core 的 49 个图标，**不引 material-icons-extended**（debug APK 与 dex 时间会明显膨胀）；「启动/停止」图形用 Canvas 自绘 |
| `io.airlift:aircompressor:2.0.3` | zstd 解压（纯 Java，无 native） | 选它而不是 zstd-jni 的原因见上面「传输层必须解压」一节；不可用时自动退 gzip |
| `junit:4.13.2` | JVM 单测 | 仅 `testImplementation`，用真实层产物验证解压链路 |

---

## 单色主题与状态语义（用户明确要求黑白）

配色是**灰阶单色板**（`R = G = B`），由 `PaletteContrastTest` 逐 token 强校验；
全仓 UI 里除 EROFS 魔数 `0xE0F5E1E2`（不是颜色）外不允许出现任何非灰阶字面量。

| 用途 | token | 值 |
|---|---|---|
| 页面底 / 卡片 / 次级面 / 最高层 | `Mono0..Mono3` | `#000000` / `#0A0A0A` / `#141414` / `#1F1F1F` |
| 描边 / 强调描边 | `Line` / `LineStrong` | `#2A2A2A` / `#3D3D3D` |
| 主色（按钮/选中/强调） | `Accent` | `#FFFFFF`（前景 `OnAccent = #000000`） |
| 正文 / 次要 / 弱化 | `TextPrimary`/`TextSecondary`/`TextMuted` | `#F5F5F5` / `#A3A3A3` / `#6E6E6E` |
| 警告 tone | `WarnTone` | `#C4C4C4` |
| 危险/错误 | `Danger` | `#FFFFFF` |

**状态怎么表达（不再靠颜色）**：

| 状态 | 明度 | 点形状 | 文案 |
|---|---|---|---|
| 运行中 | 最高（纯白） | 实心 + 呼吸光晕 | 常规 |
| 启动/停止中 | 中灰 `#8A8A8A` | **空心环** | 常规 |
| 已停止 | 暗灰 `#5A5A5A` | 实心小点 | 常规 |
| 错误 | 纯白 | **叉号** | **加粗** + 卡片强描边 |
| 未知 | 弱灰 | 空心环 | 常规 |

日志行同理：错误行**加粗**（`logLineIsError`），而不是变红。

**已知取舍**：错误态失去红色后抢眼程度下降，补偿是"叉号 + 加粗 + 强描边"三件套
（`PaletteContrastTest.错误态在没有红色的前提下仍有三种区分手段` 把三者钉住）。
若将来要"只给错误留一点红"，只需改 `Danger` 一个常量 —— 错误语义只走
`stateColor()` / `Danger` 两个出口。

## 功能 B / C / D 的落点与限制

| 功能 | 落点 | 限制 |
|---|---|---|
| **B. npm 源切换** | 设置页 `NpmSourceCard`；写入经 `linuxctl exec` 进**可写层**的 `/root/.npmrc`，另在环境根写 `etc/npmrc`；「验证」用 `npm/pnpm config get registry` 回读 | **需重启环境**才对新进程生效；运行时若要用 `NPM_CONFIG_USERCONFIG` 注入 `etc/npmrc` 需另行支持 |
| **C. 第三方插件** | 新增「插件」tab（`PluginsPane`）；`dsh plugin --profile web add/remove`（实测该命令把参数透传给 profile 里的 pnpm），已装列表读 profile 的 `package.json` 的 `dependencies`/`bundles` | 需要环境**运行中**；安装的是第三方代码，装完需重启环境；失败时把 pnpm 原始输出全部显示出来 |
| **D. DSH dist-tag** | 更新页通道选择器；用 npm registry 元数据把 tag 解析成版本号（含 5 分钟缓存）；选择写入 `Prefs` 与环境 `etc/config.json` 的 `dsh_dist_tag` | **运行时尚未消费 `dsh_dist_tag`**（只有 `tools/channel/gen-manifest.mjs` 会写 `dsh_npm.dist_tag`）——App 侧已能解析与展示，落地执行需运行时配合 |

---

## 构建踩过的坑（照 Branchbase 的 `docs/specs/BUILD-NOTES.md` 记一份，免得再翻）

### 1. Kotlin 块注释**可嵌套**：注释里别出现 `/*`

KDoc 里写 `dist/bundles/*.bin` 会开一个**嵌套注释**，把后面的代码整段吞掉，
报错指向别处（例如 "Unclosed comment" 落在文件末尾，或 `project ':app' does not specify compileSdk`）。
→ 注释里写目录就写 `dist/bundles/ 下的 *.bin`，别带那个斜杠星号。

### 2. 中文测试方法名需要 UTF-8 locale

形如 `` fun `发布前要推进版本号`() `` 的测试会生成 `...$发布前要推进版本号.class`。
若 JVM 启动时 locale 不是 UTF-8（精简容器里常是 POSIX），写盘直接失败：

```
java.nio.file.InvalidPathException: Malformed input or input contains unmappable characters
```

`-Dsun.jnu.encoding=UTF-8` **覆盖不了**（它由启动时的 locale 决定），所以：
本地跑之前 `export LANG=C.UTF-8 LC_ALL=C.UTF-8`，并 `./gradlew --stop` 重启守护进程；
CI 里由 `ci.yml` 的 android job 显式设这两个变量。

### 3. AGP 9 的 APK 改名只能用内部实现类

`VariantOutput.outputFileName` 在 AGP 9 已移除，只能
`(output as com.android.build.api.variant.impl.VariantOutputImpl).outputFileName.set(...)`。
本项目必须改名：四个变体（两个 edition × 两个档位）不改名就全是 `app-debug.apk`。

### 4. flavor 让任务名变长

现在有**两个维度**：`edition`（root / proot，两个可共存的 App）+ `embed`（minimal / full），
四个变体：`rootMinimal` / `rootFull` / `prootMinimal` / `prootFull`。于是：
`testDebugUnitTest` 会变成**歧义任务**（AGP 直接报 ambiguous），要写
`:app:testRootMinimalDebugUnitTest`（代码相同，单测跑一个变体就够）；
`assembleDebug` 不受影响（四个一起建）。

> ★ `applicationId` 在 **edition flavor** 里设（`io.github.sunsetrne.sunsetlinux.root` /
> `…​.proot`）；`defaultConfig` 里那个占位值故意不合法的 —— 漏设会在构建期直接报错，
> 而不是悄悄产出一个包名错的 APK。组合 id（`root-minimal` 等）在 `onVariants` 里拼出来，
> 用 variant 级 `BuildConfig.EMBED_VARIANT` 注入。
