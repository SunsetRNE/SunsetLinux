# 项目状态总账（整理用）

> 用途：一眼看清**已经有什么、验证到什么程度、哪些做不到、下一步该干什么**。
> 更新的原则：**只写有证据的**。凡未实测的都标"未验证"，不写成已完成。

最后整理时间：2026-09-15

---

> **刚接手？先看 [`docs/HANDOFF.md`](HANDOFF.md)** —— 那是"当前协作落点 + 续做清单 + 删掉对话后怎么恢复上下文"。本文件讲项目本身做到什么程度。

## 一、一句话现状

**所有可构建的产物都已产出并逐项验证**；**唯一没做的是真机端到端**——
而真机验证需要你执行（见 §五：我这条设备通道被策略禁止安装与挂载）。
新一批需求（单色界面 / npm 频道 / 插件 UI / dist-tag 选择 / 模块更新）**代码已落地，产物待重打**。

**2026-09-15 补记**：**proot 模式的宿主侧脚本已 mksh 化**（§4.4）—— 原先"proot 模式在真机上
跑不起来"是唯一一处**已确定的不可用功能**，现在两个脚本都能被 mksh 解析、并在 mksh 下通过
纯函数回归；顺带修掉一个语法闸门抓不到的运行时缺陷（bash `/dev/tcp` 在 mksh 里不存在）。

---

## 二、交付物（`dist/`，哈希见 `dist/MANIFEST.txt`）

| 产物 | 说明 | 状态 |
|---|---|---|
| `sunsetlinux-launcher-debug.apk` | Android 启动器（Compose M3） | ⚠️ 单色化后**待重建**（源码已通过编译） |
| `sunsetlinux-module-0.1.0.zip` | KernelSU 模块（开机自启 + 挂载探测 + 模块 WebUI） | ⚠️ 单色化后**待重打** |
| `base-24.04.3-l1.erofs.{zst,gz}` | base 层 | ✅ 已产出并验证 |
| `runtime-1.0.0.erofs.{zst,gz}` | runtime 层（Node 24.21.0 + pnpm 12.4.2 + 入口脚本） | ✅ |
| `dsh-0.1.5-rc.2.erofs.{zst,gz}` | dsh 层（DSH + 191 依赖 + profile 工作区） | ✅ |
| `channel/` | **已签名、可发布的频道**（三层两式 + 清单 + 签名） | ✅ 全链路跑通 |
| `sunsetlinux-seed-*.tar.zst` | 离线种子（ubuntu-base + Node 官方包） | ✅ |
| `proot-bundle-arm64.tar.gz` | 非 root 模式的 proot（GPLv2 合规，随附许可与 SOURCE） | ✅ |

**分发体积**：全量 **96.3 MB**（zstd）；**DSH 单层升级 31.2 MB**。

---

## 三、已验证的部分（都有实测证据）

### 3.1 自动化测试（全部可复跑）

| 测试 | 断言数 | 结果 |
|---|---|---|
| `runtime/root/selftest.sh` | 20 | ✅ |
| `runtime/proot/selftest.sh` | 18 | ✅ |
| `module/webroot/selftest.mjs`（WebUI 纯函数 + 图标守卫） | 64 | ✅ |
| `tools/cmp-consistency.mjs`（三方版本比较） | 16 | ✅ |
| `tools/contract-check.mjs`（status JSON 契约，root+proot） | — | ✅ 双通过 |
| App 单测（解压 13 + 归因 8 + UI 契约 14 + 调色对比 9 + 功能 10 + 官方频道 4 + 签名 4） | 62 | ✅（`./gradlew :app:testDebugUnitTest`） |

### 3.2 端到端实测过的链路

- **dsh 层自足可启动**：EROFS 镜像 → 解出 → 相对软链存活 → **用层内自带的 `dsh` 启动成功** → 鉴权链路 `401 → 303 → 200`（149 KB GUI）。
- **发布链路**：真实三层 `gen-manifest → sign → verify --deep → publish-check` 全 rc=0，
  `verify --deep` 会**整体解压复算** `sha256_raw`/`size_raw` 并与清单、回退产物、裸镜像三方比对。
- **多版本选层**：同目录放 `l9`/`l10`/`l100` 时**正确选中最新**（旧实现会按字典序选 `l9`）。
- **官方 dist-tag**：`latest → 0.1.5-rc.1`、`next → 0.1.5-rc.2`、`alpha → 0.1.6-alpha.1` 均可解析。
- **错误可见性**：给 `linuxctl update` 喂压缩产物会被正确拒绝（`offset0=28b52ffd`），提示去解压。
- **模块安装布局**：解开 zip → 模拟 `post-fs-data.sh` 同步 → `linuxctl status` 输出合法 JSON、契约检查通过。

### 3.3 关键设计已被证明的几点

- **不需要 metamodule**：我们的模块是纯脚本模块（无 `system/` 等 overlay 目录），
  因此**不受 KernelSU 删除内置挂载实现的影响**；自有 overlay 挂载在 `unshare -m` 的私有命名空间内，
  与 metamodule 的全局 overlay 互不可见。
- **权限边界**：`doctor` 能准确识别 `CapEff=0`（伪造 root）与真 root 的区别。

### 3.4 ★ 设备侧 shell 可移植性（这轮修掉的一类真问题）

**背景**：Android 上**没有 bash**（`/system/bin/bash` 不存在，`/system/bin/sh` 是 mksh）。
而这套运行时脚本最早是按"宿主上有 bash"写的 —— 后果是**本地 bash 全绿、真机一行都跑不了**。

实测证据（本轮）：
```
$ mksh -n runtime/root/start.sh
E: runtime/root/start.sh[458]: syntax error: unexpected '('      ← 真机上这就是最终结果
$ mksh runtime/root/linuxctl.sh status
E: .../status_json.sh[64]: syntax error: unexpected '(('
E: .../layer-spec.sh[167]: syntax error: unexpected '('
E: linuxctl.sh[1578]: dsh_status_json: inaccessible or not found   ← 状态 JSON 根本产不出来
```

已修（全部有回归证据）：

| 问题 | 位置 | 修法 |
|---|---|---|
| `declare -a` / `local -a` 是 mksh 语法错误 | `doctor.sh`、`stop.sh`、`layer-spec.sh`、`device-provision.sh` | 去掉数组声明，改 POSIX 字符串 / 先声明再赋值 |
| C 式 `for (( … ))` 不被 mksh 支持 | `start.sh`、`stop.sh`、`linuxctl.sh`、`status_json.sh` | 改 `while read` + 前插构造逆序；JSON 转义改 awk `gsub` |
| `a=( … )` 当函数局部量声明 | 同上 | 拆分声明与赋值 |
| **被 source 的库自动执行"自检"** | `runtime/common/{status_json,http_health}.sh` | 经典 `[ "${BASH_SOURCE[0]:-$0}" = "$0" ]` 在 mksh 下**恒真** → 改成 source 方显式打标记 `SUNSETLINUX_SOURCED=1`（`http_health.sh` 里那个自检带 `exit`，会让 linuxctl 一启动就退出） |
| `BASH_SOURCE` 定不到自身路径 | `stop/start/status/selftest.sh` | 改 `${BASH_SOURCE[0]:-$0}`（mksh 下退回 `$0`） |
| shebang 写着 `#!/usr/bin/env bash`，但设备上要**直接执行**它 | 设备侧全部脚本 | 统一 `#!/system/bin/sh`；只有环境内（chroot/proot 后）的 `entry.sh`/`supervise.sh` 保留 bash |

**验收证据**：`mksh runtime/root/selftest.sh` → **20/20 通过**；
`mksh runtime/root/linuxctl.sh status` 与 `bash` 版输出**逐字节一致**，并通过 §3.1 冻结契约校验。
新增闸门 `tools/shell-compat-check.mjs` 已进 CI：设备侧脚本过不了 mksh 就**直接失败**。

> ⚠️ **这个闸门只管"能不能解析"，管不了"运行时能不能用"** —— 2026-09-15 修 proot 时
> 就撞上第二类问题：`/dev/tcp` 是 bash 专有特性，mksh **语法上照收**（它只是重定向到一个
> 不存在的路径），于是 `mksh -n` 全绿、真机却永远连不上端口。教训是：
> **换 shell 时要按"用到的特性"逐个查，不能只看语法闸门**；能抽出纯函数做双解释器回归的，
> 就抽出来测（`runtime/proot/selftest-funcs.sh` 就是为此加的）。详见 §4.4。

### 3.5 ★ App 冷启动与系统栏交互（用户实测的三个问题，已修）

| 用户看到的现象 | 根因（源码级） | 修法 | 回归 |
|---|---|---|---|
| 首装"很大的黑屏页面，过一会才有模式引导" | ①主题 `windowBackground` 是纯黑；②`LauncherActivity` 在**没走完引导时也组合整套外壳**（顶栏+3 面板+日志轮询），引导页再盖上去 —— 首帧要等这套重活；③`ViewModel` 构造器里同步跑 zstd 能力探测（写临时文件 + 解 zstd 帧，磁盘 IO） | 门禁下沉到 `setContent` 内部：未完成引导时**只画轻量占位屏**（`ui/BootPlaceholder.kt`，有标题/进度/出路），不组合外壳；`onResume` 重新读 `onboarded` 以便引导完成后立刻切换；zstd 探测移到 IO 协程 | `UiInsetsContractTest` ×2 |
| 键盘盖住输入框（插件包名 / 频道 URL / 本地源 / DSH Web 输入） | 边到边（`enableEdgeToEdge`）后窗口不再为键盘让位，而全 App **没有任何 `imePadding`**；且 Activity 未声明 `adjustResize`，部分版本连 IME inset 都不派发 | `AppShell` 统一消费一次 + DSH WebView 自行消费；6 个 Activity 加 `imePadding`；manifest 全部 activity 加 `windowSoftInputMode="adjustResize"` | `UiInsetsContractTest` ×2 |
| **系统返回不跟手、像没被消费** | ①全 App 只用传统 `BackHandler` —— 它**只在抬手时回调一次**，拿不到手势进度，拖动过程毫无反馈；②`AppShell` 切了 tab 却没登记任何返回回调（只有 DSH Web 面板有），非首页按返回直接退出 App | 两处都改成 `PredictiveBackHandler`：把进度流式映射成水平位移（网页/整块内容**跟着手指走**），抬手才提交（网页历史后退 / 回首页）；取消则动画弹回。侧边栏打开时仍由抽屉自己的回调优先（material3 源码里 `enabled = drawerState.targetValue == Open`，仅在打开时启用） | `UiInsetsContractTest` ×1 |

顺带清掉的一处隐患：悬浮胶囊底栏的留白 `96.dp` 原先在 4 个地方各写一遍，
现统一为 `ui/components/Common.kt` 的 `CapsuleReserve`（漏改一处就会让最后一个面板被胶囊压住）。

> **关于「系统虚拟导航」的追问与结论**：你补充的是"返回不跟手、对系统返回无任何消费"。
> 据此把返回链路整条查了一遍：navigation bar 的 inset 各屏都消费了（有测试守着）；真正的缺陷是
> **预测性返回从来没被用起来**（见上表最后一行）。已改用 `PredictiveBackHandler` 做跟手位移。
> 真机验证要点见 `docs/smoke-test.md` §7.1 的"返回手势"一行。

### 3.6 ★ App 外壳重排 + 内置终端（真机使用反馈，2026-09-16）

用户原话：「**把 DSH 启动挪到上面**，然后**给应用加个终端**」「更新移到侧边栏，操作更顺手」。
截图证据：底栏胶囊塞了 4 格（启动/更新/插件/DSH），**DSH 标签被挤到换行**成两行。

| 改动 | 做法 | 回归 |
|---|---|---|
| DSH 上顶栏 | 顶栏加 DSH 图标按钮；点开是 DSH Web，返回手势/返回键回主界面（`PredictiveBackHandler` 本来就实现了这条） | `ShellLayoutContractTest` |
| 更新进侧边栏 | 侧边栏第一项「更新」；启动页那个「更新」按钮仍然可用（同一目标，两条入口） | `ShellLayoutContractTest` |
| 底栏只留 3 格 | 启动 / 插件 / **终端**；底栏改为只渲染 `ShellTab.inCapsule` 为真的页 —— 以后加页不会再自动挤进底栏 | `ShellLayoutContractTest` |
| **内置终端**（新） | `ui/TerminalPane.kt` + `core/TerminalSession.kt`：常驻会话走 `linuxctl attach`（root = `nsenter … chroot … bash`，proot = `start.sh --inner -- bash -l`），输出区 + 输入行 + 清屏/重开/停止；**命令回显由面板补**（管道里的 shell 是非交互的，自己不打提示符也不回显） | `ShellLayoutContractTest` ×7 中的 3 条 |

**终端刻意没做的**（写进了界面提示与代码注释，免得后人当 bug 修）：**没有 PTY**。
Android 上没有随系统可用的伪终端分配接口，自己引（JNI + forkpty）代价远大于收益。后果两条：
`vim`/`htop`/`top` 这类**全屏程序不可用**、没有作业控制（`Ctrl-C`/`fg`）；输出**按行刷新**
（`python3` 的 `>>> ` 这种不换行的提示不会及时出现）。`ls`/`cat`/`apt`/`npm`/`dsh` 这类正常。

会话**挂在外壳上**（不在 tab 分支里）：切 tab 再切回来会话与滚动都还在；外壳销毁（退出 App）
才断开 —— 避免留下没人管的 shell 进程。起会话前先判一次环境是否 `RUNNING`（`attach` 进的是
已存在的 mount namespace，没起来时只会甩一行报错）。

**验收**：`:app:assembleDebug` + `:app:testDebugUnitTest` → **62 用例全过**（新增
`ShellLayoutContractTest` 7 条，守着"底栏只放 3 格 / DSH 在顶栏 / 更新在侧边栏 /
终端走 attach 且先判环境 / 会话随外壳销毁"）。

### 3.8 ★ 版本号推进 + **APK 签名漂移**修复（2026-09-16 第三批）

用户点名："别忘了更新版本号（模块和 APP），以及解决 APK 签名随机漂移的问题"。

**① 版本号**

| 组件 | 之前 | 现在 | 说明 |
|---|---|---|---|
| 模块 | `v1.0.0` / 10000 | **`v1.0.1` / 10001** | 见 §3.7（随 WebUI 图标化一起发） |
| **App** | `versionCode = 1` / `versionName = "0.1.0"`（**一直没动过**） | **`2` / `"0.2.0"`** | 本次含：内置终端、DSH 上顶栏、更新进侧边栏、内置官方频道 |

**② 签名漂移（真机上会咬人，已实测复现并修掉）**

根因：`build.gradle.kts` **完全没有 `signingConfig`** → AGP 用各机器自建的
`~/.android/debug.keystore`；CI runner 是临时的 → **每次发布一把新 key**。实测两个包都是
`CN=Android Debug` 自签，但证书 SHA-256 一个是 `84be9523…`（CI）、一个是 `3b68b616…`（本机）。

后果（比"装不上"严重）：用户装新版报 `INSTALL_FAILED_UPDATE_INCOMPATIBLE`（必须先卸载、
App 数据清空）；**KernelSU 已授予的 root 授权全部作废**（授权按【包名+签名】记录，
`build.gradle.kts` 里原本就写着这句）；系统也不把新版当升级。

修法（两档，详见 `docs/release-ci.md` §5.4）：
1. **默认**：仓库内固定调试密钥 `app/app/debug.keystore`（PKCS12；`.gitignore` 里加了
   **唯一例外** `!app/app/debug.keystore`，否则会被 `*.keystore` 静默忽略 → CI 上找不到文件）；
2. **要私有发布密钥**：设 `ANDROID_KEYSTORE_BASE64` 等 4 个 Secret，`ci.yml` 落成临时文件并注入
   `SUNSETLINUX_KEYSTORE*` 环境变量 —— 这条通道以前**文档写了、代码没接**（`ci.yml` 里搜不到
   `ANDROID_KEYSTORE`），这次一并接上。

**验收证据（可复跑）**：

```
keytool -list -v -keystore app/app/debug.keystore   → SHA256 5D:5F:A7:24:…:69:F7
apksigner verify --print-certs app-debug.apk         → 5d5fa724612e1df6c017cd1b88a3c47b0b4c80b078c1b7b4c403295a5a7d69f7   ← 逐位一致
apksigner verify --print-certs <旧 CI 包>             → 84be9523…  ← 与上面不同＝漂移确实存在
```

`assembleDebug` 与 `assembleRelease` 都通过；新增 `SigningContractTest` 4 条
（密钥文件在、两个 buildType 绑同一配置、无环境变量时必须回落到仓库内那把、
`.gitignore` 必须放行、版本号必须推进过）。

### 3.7 ★ 模块 WebUI 图标化 + 内置官方频道 + 模块 1.0.1（2026-09-16 第二批）

| 改动 | 做法 | 回归 |
|---|---|---|
| **WebUI 不再用 emoji** | `module/webroot/index.html` 的 5 个 tab（📊⚙️📜🩺⬆️）、预览横幅 ⚠、更新页 🔒/ℹ️ 全部换成**内联 SVG sprite**（`<symbol>` + `<use href="#i-…">`，`stroke: currentColor`）。理由：emoji 是**彩色**的，与单色板冲突；而且同一串码点在各 ROM 的 emoji 字体下渲染差别很大。spite 全部内联，**不引任何外部资源**（WebUI 可能没网） | `module/webroot/selftest.mjs` 新增 3 条：不许出现 emoji 码点、图标走 sprite、旧 emoji 字号规则已移除 |
| **模块按"一次更新"发布** | `module/module.prop`：`v1.0.0` → `v1.0.1`、`versionCode` `10000` → `10001`。CI 从 `module.prop` 取版本产出 `sunsetlinux-module-1.0.1.zip`；`post-fs-data.sh` 开机写 `etc/bin-version` 也随之为 1.0.1（不会报"脚本/模块漂移"） | CI 出 zip + `cmp-consistency` |
| **内置官方频道**（"自己的发布作为默认与内置发布"） | `core/Prefs.kt` 新增 `Channel.OFFICIAL`：URL `https://sunsetrne.github.io/SunsetLinux/channel/channel.json` + 公钥 `YXoAcwa3clZCRd7+DlIHMS3cQ40HlyXVSKblvX5Ye1M=`（指纹 `ed25519:06:d0:c4:4d:29:1c:ef:66`）**写死在代码里**。`Prefs.channels` **读时并入、写时剔除** —— 用户数据里不存这一项，所以改不掉 URL/公钥、删不掉、升级也不留旧副本；设置页给它打「内置」标记、只有启用/停用开关 | `OfficialChannelContractTest` 4 条（https 强制、公钥必须与 `docs/` 公开的一致、读走 `withBuiltin`/写走 `withoutBuiltin`、UI 不给改删键） |

> 为什么"内置"必须写死在代码里而不是塞进默认数据：官方频道的 URL + 公钥就是**信任根**。
> 若它躺在可写的用户数据里，一次静默篡改就能让 App 忠诚地去验**别人的**签名。
> 内置只是"默认从哪拿更新"，**信任仍然只来自公钥验签**（用户可随时停用或换成别的频道）。

**仍未做**：把 `module` 段写进频道清单（`gen-manifest.mjs` 目前只出 `layers` + `dsh_npm`，
而 `update.sh` 读的是 `manifest.module`）—— 不补这一段，**模块更新在 App/WebUI 里拿不到**，
内置频道只能送层、送不了模块；以及层/频道的实际发布（要定层从哪来，见 §七）。

---

## 四、⚠️ 做不到 / 已知限制（不粉饰）

### 4.1 **高温下被系统强杀 —— 挡不住**

你刚遇到的：手机温度到一定程度，进程被直接抹杀。这是 **Android 系统级行为**（热降频 + LMK / thermal daemon），
任何应用层设计都无法阻止。要如实说清：

- **root 模式的价值在于"降低被误杀的次数"**（环境由 KernelSU 模块在开机时拉起、**不依附 App 进程**，
  App 被冻结/被杀不影响它），**但它不能免疫系统级强杀**。
- 被强杀后：`run/dsh.pid` 会变成陈旧 PID，`linuxctl status` 据此判为 `error` 并给出"进程已退出"的原因 ——
  **可见性是有的，自动恢复没有**。要做自动重启就得有守护进程，而守护进程同样会被杀。
- **现实建议**：不要在手机上跑长时间高负载任务（例如大型 `npm install`、编译），
  这类负载正是触发降频→强杀的主因；把重活放到电脑上。

> 这一条我会写进 `docs/install.md` 的"已知限制"，不藏在文档角落。

### 4.2 真机端到端**尚未验证**（本机无法代劳）

我这条设备通道被策略禁止两件事，且明确要求不得绕过：
- **安装应用**（`pm install` → 「pm 只允许查询；安装、卸载、清数据和授权不开放给设备命令」）
- **挂载类操作**（直接 `[POLICY_BLOCKED]`）

因此以下是**纯未知**，必须你在 root 终端跑过才知道：
1. **toybox `mount` 的选项支持面**（`--rbind` / `--make-rslave`）——这是头号未知项；
2. `unshare` / `chroot` / `losetup` 在 `ksu` 域下的实际可用性（含 SELinux）；
3. EROFS 作 overlay lowerdir 的真实挂载；
4. Android 版 `mkfs.erofs` 的参数兼容性；
5. App 的 UI 观感、抽屉手势、崩溃处理器、首启门禁全流程。

### 4.3 其它已知取舍

- **接口未做鉴权**：`/app/*` 类桥接能力（若有）只监听回环，但同机其它应用理论上可访问 —— 由 App 侧 token 约束。
- **单色界面的代价**：错误态失去红色后显眼度下降，改用「✕/! 图标 + 白色粗描边 + 加粗文案」补偿。
- **局域网访问未做**：`dsh web` 只监听 `127.0.0.1`，界面里是灰态占位，没有伪造能力。

### 4.4 ✅ **proot 模式的宿主侧脚本已 mksh 化（2026-09-15 修完）**

原来是**已确定的缺陷**（不是"待验证"）：`runtime/proot/linuxctl.sh` 与 `start.sh` 重度依赖
bash（`[[ =~ ]]` 正则、C 式 `for`、进程替换），而设备上只有 `/system/bin/sh` = mksh；
Android 10+ 又禁止 `execve` App 私有目录里的文件，App 只能用 `/system/bin/sh <脚本>` 跑它
→ 直接撞语法错，**proot 模式在真机上跑不起来**（root 模式不受影响）。

现在两个文件都能被 mksh 解析并在 mksh 下跑回归。**实际改动比原估的小一个数量级**：
不是"约 1800 行机械改造"，`mksh -n` 只报第一处，把已知构造列全之后要动的只有几类：

| 类别 | 处数 | 改法 |
|---|---|---|
| `[[ x =~ 正则 ]]` | 12 | → `case` 字符类 + `is_uint`/`is_int`/`valid_name`/`octet_ok` 小函数（`printf '%04X'` 也够用） |
| C 式 `for (( … ))` | 1 | → `i=0; while [ … ]; do …; i=$((i+2)); done` |
| 进程替换 | 5 | `< <(cmd)` → 变量 + here-doc（**不能**用管道：循环会掉进子 shell，`found` 带不出来）；`2> >(tee …)` → stderr 直接追加进日志 + 结束后 `replay_log_delta` 回放增量 |
| `local x=()` | 8 | → 先 `local x` 再 `x=()`（mksh 里 `local x=(…)` 是语法错误） |
| `${BASH_SOURCE[0]}` | 3 | → `${BASH_SOURCE[0]:-$0}`（mksh 下未定义，`set -u` 时直接报错） |
| shebang `#!/usr/bin/env bash` | 2 | → `#!/system/bin/sh` |

★ **额外抓到一个 `mksh -n` 抓不到的运行时缺陷**：bash 的 `/dev/tcp` 在 mksh 里**不存在**
（实测同一端口 bash 连得上、mksh 恒失败）。它被用来做端口探测/健康兜底 ——
真机表现会是"服务其实起来了，`start` 却一直等到超时判未就绪"，而语法闸门全绿。
已改成三层回退：`/proc/net/tcp[6]`（纯 awk，零依赖）→ `nc -z` → `ss -ltn`；
`runtime/root/start.sh` 的 `port_busy` 是同一个毛病，一并修了。

**验收证据（都可复跑）**：

```bash
node tools/shell-compat-check.mjs --verbose      # 通过，欠债名单已清零
bash runtime/proot/selftest-funcs.sh             # 65 通过 / 0 失败
mksh runtime/proot/selftest-funcs.sh             # 65 通过 / 0 失败（与 bash 同结果）
mksh runtime/proot/selftest.sh                   # 18 通过 / 0 失败（以前只有 bash 能跑）
mksh runtime/proot/linuxctl.sh status            # 与 bash 版输出逐字节一致，契约检查双通过
```

`runtime/proot/selftest-funcs.sh` 是新增的回归：它**从真实脚本里抽取函数**来测
（与 `runtime/root/selftest.sh` 同一手法，不测复制品），钉住的正是上面这些改写的语义 ——
整数/IPv4/快照名判定、端口探测（自己起一个监听端口来测，不靠环境里恰好有服务）、
日志增量回放、DNS 多路回退（多行输出不能丢最后一行）、status 附加键。

**仍未验证**：真机上真正跑一次 proot 模式的 `provision` / `start`（需要设备，见 §五）。

---

## 五、需要你做的（唯一的阻塞点）

按 `docs/smoke-test.md` 走，**第一条最关键**：

```bash
su -c '/data/sunsetlinux/bin/linuxctl doctor'
```

它一次性能回答 §4.2 里的第 1 项（`§1b` 命令能力表）、挂载实现探测（`§1c`）、
以及内核能力与 avc denial。把输出发我即可继续。

---

## 六、仓库卫生（开源前）

- ✅ `LICENSE`（MIT）、`THIRD_PARTY_NOTICES.md`（含上游署名与 proot GPLv2 合规）、`.gitignore` 已就位；
- ✅ **已清除一处私钥泄漏风险**：`tools/channel/` 下曾遗留测试生成的 `channel.key`
  （私钥进仓库 = 频道签名体系失效），已删除并在 `.gitignore` 里加了 `*.key` / `channel.key`；
- ✅ 全仓复扫：**无私钥残留**；
- ✅ 已按你的要求**去掉对第三方模块/作者的点名**（13 处，复查 0 残留）。

---

## 七、下一步（按优先级）

1. **你在真机跑 `doctor`** → 我据此修真机问题（最可能是 toybox mount 的降级分支）。
   现在 root 侧脚本已经是 mksh 可解析的，这一步**第一次真的有可能跑出结果**（以前会直接语法错）。
   顺带值得一起跑：`su -c '/data/sunsetlinux/bin/linuxctl provision --seed …'` 之后
   `linuxctl start`，把 proot 模式也过一遍（§4.4 已 mksh 化，但这台真机上是第一次真跑）。
2. **重打三层层镜像**：现有 `dist/*.erofs` 里还留着改名前的死文件 `/opt/dshroid/*.sh`
   （不影响功能：`start.sh` 启动时会把模块里的新版同步到 `/opt/sunsetlinux`）。
   重建必须在**有 CAP_SYS_ADMIN 的宿主 / CI** 上跑 `rootfs/build-layers.sh`（本工作容器
   没有该能力：实测 `CapEff=0`、`mount` 是假的（`/proc/mounts` 不变），只能跑到 mmdebstrap 报错为止）。
3. App 侧「彻底卸载」入口（先备份 → 确认 → `purge --yes` → 展示 `footprint`）：
   **机制已就绪**（`linuxctl purge [--yes|--arm|--disarm]` + `footprint`），只差 UI。
4. 真机通过后再考虑：局域网访问、脚本自更新、更多频道的实测。

> ✔ 已完成（原第 2 项）：**proot 模式的宿主侧脚本 mksh 化**，见 §4.4。

