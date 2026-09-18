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

**2026-09-18 补记（最新，先看这条）**：内核 v2（`sunsetd`，Kotlin/app_process 常驻，成为"环境在不在跑"的
唯一状态源）已经**真机闭环**：P1 的 SELinux 域/ART 通过、控制面在真机上通了（`android-local` 通道 +
开机回环自检 `ok`），`state.json` 由内核写、模块那条开机启动被认领成 `foreign-observer`。
过程里修掉两个大坑并各留了闸门：

1. **真机 ART 没有 NIO 的 unix 服务端**（SDK 桩里有）⇒ 控制面通道分层 + 能力探测 + 开机自检（§3.10.48/§3.10.51）；
   同一轮又发现**客户端半边也不通** ⇒ 客户端也用 `android.net.LocalSocket`（§3.10.50，决策 B11）。
2. **CI 发出去的模块包从来没有内核**（三个打包路径都没建 dex，每处只有一行 warning）⇒
   `mkmodule.sh` 缺 dex 直接拒绝 + CI 补齐构建与断言（§3.10.49，决策 C3）。

用户侧：**App 0.3.15** 已交付（DSH 网页改为覆盖整窗渲染 + 沉浸 + 常驻「返回壳」，§3.10.52）、
**模块 1.0.40** 已交付（宿主侧客户端 `Ctl` + 开机客户端自检，P2 的地基）。
细节与"换对话框后怎么读"见 `docs/HANDOFF.md` 最后一节（收束）。

---

## 二、交付物（`dist/`，哈希见 `dist/MANIFEST.txt`）

> ⚠️ 这一节是**早期轮次**的记录（当时的产物名是 `sunsetlinux-launcher-*`）。
> 现在的形态看 §3.10.24：**两个 App（`…​.root` / `…​.proot`）× 三档内置 = 6 个
> `SunsetLinux-<版本>-<组合>-debug.apk`**，由 CI 发到 Release `v<版本>` 与
> `gh-pages` 的 `/stable/`、`/beta/`（见 `docs/release-ci.md`）。

| 产物 | 说明 | 状态 |
|---|---|---|
| `sunsetlinux-launcher-debug.apk` | Android 启动器（Compose M3） | ⚠️ 单色化后**待重建**（源码已通过编译） |
| `sunsetlinux-module-0.1.0.zip` | KernelSU 模块（开机自启 + 挂载探测 + 模块 WebUI） | ⚠️ 单色化后**待重打** |
| `base-24.04.3-l1.erofs.{zst,gz}` | base 层 | ✅ 已产出并验证 |
| `runtime-1.0.1.erofs.{zst,gz}` | runtime 层（Node 24.21.0 + pnpm 12.4.2 + 入口脚本；1.0.1 只改 `supervise.sh`：`--expose-internals`） | ✅ 2026-09-18 重打并逐项验证 |
| `dsh-0.1.6-alpha.2.erofs.{zst,gz}` | dsh 层（DSH 0.1.6-alpha.2 + 其依赖 + profile 工作区） | ✅ 2026-09-18 重打：层内 `dsh web` 起得来、鉴权 401→303→200、页面含 `dsh-web-mobile` |
| `channel/` | **已签名、可发布的频道**（三层两式 + 清单 + 签名） | ✅ 全链路跑通 |
| `sunsetlinux-seed-*.tar.zst` | 离线种子（ubuntu-base + Node 官方包） | ✅ |
| `proot-bundle-arm64.tar.gz` | 非 root 模式的 proot（GPLv2 合规，随附许可与 SOURCE） | ✅ |

**分发体积**：全量 **141.6 MB**（zstd：base 18.6 + runtime 47.6 + dsh 75.4）；
**本轮升级要下 123.2 MB**（runtime 47.6 + dsh 75.4；base 未变）。
涨的原因单一：0.1.6 多带 `@deepseek-ai/libreoffice-kit-wasm`（186 MB），见 §3.10.53。

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

### 3.9 ★ 懒人体检 / 一键部署脚本 `oneshot-setup.sh`（2026-09-16 第四批）

用户问："有没有懒人化的检测执行脚本？在任意目录执行即可？比如 Download 目录下？
兼容 MT 使用系统或者扩展包执行环境？" —— 于是加了 `runtime/root/oneshot-setup.sh`：

- **在哪都能跑**：不依赖 cwd，也不依赖仓库（只跟设备上的模块打交道）；
  `runtime/root/*.sh` 会被 `mkmodule.sh` 自动铺进模块 `bin/`，开机再由 `post-fs-data.sh`
  同步到 `/data/sunsetlinux/bin/` —— 所以设备上直接 `sh /data/adb/modules/sunsetlinux/bin/oneshot-setup.sh`。
- **默认只读**：不带参数只做体检（身份/`su`、模块、`linuxctl` 与 `device-provision.sh`、
  环境根与**三层镜像**、工具链、`/data` 空间、网络、种子），每项都给"怎么修"，
  最后给一条可复制的下一步命令。要动手必须显式 `--run`。
- **`--run`**：缺种子就自动下（ubuntu-base + Node 官方包）→ 跑 `device-provision.sh` →
  `linuxctl start` → `linuxctl status`；不是 root 就自动 `su -c` 重跑自己。
- **MT 双模式**：只用 POSIX + `case`（不用数组 / `[[ =~ ]]` / 进程替换 / `local x=()`），
  mksh（MT「系统」）与 bash/ash（MT「扩展包」）都过；`/sdcard` 没有可执行位 →
  用 `sh 脚本` 跑；文档提醒存成 LF。闸门 `shell-compat-check` 已覆盖本文件。

**验收**：`mksh -n` / `bash -n` / 闸门通过；用**假设备布局**（`/data/adb/modules/...` +
`/data/sunsetlinux/...`）跑了四种场景（三层齐备 / 一层都没有 / 只缺 runtime /
种子只在子目录）与 `--run`（桩 provisioning + 桩 linuxctl）的控制流，输出与分支都对。
**未验证**：真机上的实际部署（本容器没有真 chroot；真机连层都还没有）。
顺带修掉一个真 bug：`device-provision.sh` 里的 `UBUNTU_BASE_URL` 含**未展开**的
`${UBUNTU_BASE_TARBALL}`，脚本"读它自己认定的 URL"会把字面量带进下载地址 → 已加 `$` 守卫。

模块版本随之推进：`v1.0.1/10001` → **`v1.0.2/10002`**（模块内容变了就得换版本号，
否则同版本不同内容，用户根本升不到）。

**真机首次运行（同一天，用户贴回输出）**：在 `/storage/emulated/0/Download/` 用 MT 管理器的
**mksh（系统模式）**跑通，`uid=0` 直接是 root；**工具链全绿** —— `chroot`/`mount`/`umount`/
`awk`/`sed`/`grep`/`find`/`xargs`/`tar`/`gzip`/`mkfs.erofs`/`fsck.erofs`/`mke2fs`/`curl` 都在，
`zstd` 确实没有（与"分发要有 .gz 回退"一致）；`/data` 剩 619 GiB、网络可达。
阻断项只有两个，都是预期的：**三层镜像一个都没有**、**种子没放**。这同时回答了
`docs/STATUS.md` §4.2 里"设备上有没有这些工具"一半的未知项（另一半是 mount 选项/SELinux，
要等真跑 provision 才知道）。

据此又补了两处（真机输出直接暴露的）：
1. 工具链清单漏了 **`truncate`**（`device-provision.sh` 建稀疏 upper.img 要用它，缺了会 die），
   一并把 `sha256sum`（种子/层校验，可选）也纳入体检；
2. 新增**模块版本 vs 官方发布**的比较（拉 `/stable/index.json` 的 `module_version`）——
   实测第一台设备模块是 `1.0.0` 而官方已 `1.0.2`，脚本会直接提示去装新模块 zip。
   紧跟这个改动，模块再推进一格：**`v1.0.3/10003`**。

### 3.10 ★ 真机第一次 `--run`：暴露一个我的 bug + 一个真正的拦路虎

**① 我的 bug（`bad()` 从未被定义）**：一次"删空行"的编辑把
`bad()  { BAD=$((BAD + 1)); c_bad "$*"; }` 并进了上一行注释 —— `mksh -n` 照样通过，
真机上只多一行 `bad: inaccessible or not found`，**而且阻断计数永远是 0**：
明明一层镜像都没有，体检却报"没有阻断项"然后继续往下跑 provisioning。
教训：**"能解析"与"函数真的存在"是两回事**（这正是本项目反复出现的那一类问题）。

已修，并新增 `tools/oneshot-selftest.mjs`（**29 条断言**，进了 CI 的 shell job）：
在临时目录里用 **mksh 与 bash 各跑一遍只读体检**，断言八个体检段落都在、
**没有任何 "not found" 类输出**、并且**缺模块/缺层时必须真的报出阻断项**。

**② 真正的拦路虎：`device-provision.sh` 要求 bash**。它显式检查 `BASH_VERSION`，
没有就打印「需要 bash」并 `exit 1` —— 而 Android 自带只有 mksh，所以"在手机上建层"这条路
在 stock 环境里**走不通**（除非借 Termux 的 bash）。

当时的对策是让 `oneshot-setup.sh` 自己找 bash（`$SUNSETLINUX_BASH` → PATH → `/system/bin/bash`
→ Termux 的 `/data/data/com.termux/files/usr/bin/bash` → `/data/local/tmp/bash`），找到就用它；
并留下了一句"待办：把 `device-provision.sh` 也改成 mksh 可跑"。模块当时推进到 **`v1.0.4/10004`**。

### 3.10.1 ★ 收官：`device-provision.sh` mksh 化（模块 **v1.0.5/10005**）

**先上真机核了一遍，发现"找 bash"这条对策在这台机器上也不成立**：`/system/bin/bash` 与
`/data/data/com.termux/files/usr/bin/bash` **都不存在**（Termux 根本没装）。所以那句"找个 bash 就行"
等于把设备侧首次部署堵死了 —— 而且堵了两层（守卫 + 找不到 bash）。

真正的 bash 专有写法只有三处，但**每一处都致命**：

| 写法 | 实测后果 | 改成 |
|---|---|---|
| `[ -z "$BASH_VERSION" ]` → `exec bash` / `exit 1` | 设备上没 bash → 立刻死 | 删掉守卫 |
| `declare -f` 转储函数生成内层脚本 | mksh 下 `declare: inaccessible or not found`；而 `unshare -m` 里跑的就是 mksh | 内层引导脚本 `exec 本文件 --inner-run` |
| 裸 `local tb` + `set -u` | **mksh 的 `local x` 是"未设置"** → `tb: parameter not set`，**每个阶段第一步**就死 | 30 处裸 `local` 补 `=""` |

> 第 3 条是这一轮最有价值的发现：它是"bash 全绿、mksh 第一步就死"的典型，
> `mksh -n` 与任何静态检查都看不见 —— 只有**真跑一次**才会露头。
> 这就是 `tools/provision-selftest.mjs`（28 条）存在的原因：它在 mksh 与 bash 下各真跑一次内层。

顺带修掉再执行的传参：内层重新执行本文件，`--seeds/--force/--skip-*` 必须能从环境继承
（原来 `SEEDS_DIR="$LH/seeds"` 是无条件赋值，会把 `--seeds` 静默吞掉）。
`oneshot-setup.sh` 的"找 bash"那节也换成**行为探测**（用 `sh` 跑一次 `--help`：旧版会打印
「需要 bash」并 exit 1，新版正常打印用法），体检因此能直接指出"你装的是旧模块"。

**同时核出来的另一件事（App 0.2.1 已修）**：真机上 `etc/state.json` 的 `generator` 是
`linuxctl provision`，而 **`linuxctl provision` 只建目录 / upper.img / 写 config，从不构建层**
—— App 的「首次部署向导」走的正是它，所以在没有预置层的机器上必然以"provision 失败"收场。

修法（App 0.2.1/3）：新增 `core/ProvisionPlan.kt` 做**分诊**（纯函数、可单测）——
`linuxctl provision` 之后读它 stdout 里的 `missing_layers`（顺带修了 `Proc.stream`
**把 stdout 整个丢掉**的老毛病，那个 JSON 正是打在 stdout）：

| 情况 | 下一步 |
|---|---|
| 退出码 0 / 2（已部署） | 直接 start |
| 缺层 + root 模式 + 有 su | 跑 `device-provision.sh --seeds …`（流式输出；已有层自动跳过），再用 `status` **复核三层真的齐了**才宣布成功 |
| 缺层 + proot 模式 | 明说"proot 没有真 chroot，去频道装层"，不让用户白等 |
| 解析不出缺什么 / 没有 su | FAILED（**不猜着跑一次半小时的构建**） |

### 3.10.2 ★ 真机第一跑：又暴露两个"本地永远测不出来"的坑（模块 **v1.0.6/10006**）

用户按文档跑了（好消息是 **bash 那道坎真的过去了**：前置检查 / 建目录 / 找种子全过），然后死在两处：

| 现象 | 根因 | 改法 |
|---|---|---|
| `printf: bad %q@20`（生成内层脚本那一步，整个部署终止） | **Android 的 mksh 没有 `printf %q`**；容器/CI 的 mksh R59 有 —— 同一段代码在本地怎么跑都是绿的 | 内层引导脚本改成**原样重放命令行参数**（`exec /system/bin/sh <脚本> --inner-run <原参数…>`，8 行），彻底不用 `%q`；参数用 `squote()`（POSIX 单引号转义，sed 实现） |
| `素材：base.packages=未找到`（还有 runtime / web-profile） | **模块从来没打包 `rootfs/profiles/`** —— `device-provision.sh` 按 `$SELF_DIR/../profiles/` 找，而 mkmodule 只铺了 bin/lib/webroot。后果是 base 层退化成内置最小集，跑到 dsh 阶段才 die（白等 20 分钟） | ① mkmodule 打包 `profiles/` + **必需项断言**；② `preflight` 对素材 **fail-fast**（缺了当场 die 并指明重装 ≥1.0.6）；③ `sync_scripts` 把 profiles 一并落到 `$LINUX_HOME/profiles`，候选路径补上模块根与 `$LH` |

顺带加固：文件清单的 `find -exec … {} +` 改成**先探测再降级**（toybox 版本差异；不认 `+` 就会
产出空清单 → 三层全空，再白等半小时）。

回归从 28 条涨到 **35 条** —— 新增的包括：
「代码里不许出现 `%q`」（静态，因为本地跑不出来）、「引导脚本里没有 `export` 一堆变量」、
以及**把含空格与单引号的种子目录送进内层、断言它拿到的路径与命令行逐字一致**。

### 3.10.3 ★ 真机第二跑：mksh 的空数组（模块 **v1.0.7/10007**）

模块 1.0.6 把 profiles 与 `%q` 两个坑堵住后，真机一路跑到 **base 层装完 40 个包、
裁剪完、5224 个变更条目都算出来**，然后在打包那一步死掉：

```
[05:54:12] mkfs.erofs：/system/bin/mkfs.erofs（compress=none，block=4096）
[05:54:13] ERROR: 构建失败
```

日志里连"尝试 #N"都没有 → 第一次调用都没走到。复现（容器里的 **Android `/system/bin/sh`**）：

```sh
set -u; z=(); printf '%s' "${z[@]}"    # z[@]: parameter not set
```

**mksh 的 `x=()` 不是空数组**（就是不存在的变量），而 `make_erofs` 默认分支
（`EROFS_COMPRESS=none`）让 `zargs` 保持空 → `attempts=( "${zargs[@]}" … )` 炸 → `set -e` 退出，
stderr 不在日志里，外层只报"构建失败"。**这类"每层都会踩、且静默"的坑，只有把函数单测到才能防。**

改法：`make_erofs` 去掉数组，参数组合改成纯函数 `erofs_args_for <n> <comp> <bs>`，
`while` 逐级降级；回归里把该函数抽出来 + 桩 mkfs.erofs（第一次失败、第二次成功）跑完整链条，
**变异测试**过（换回旧写法立刻红，报的正是真机那句 `zargs[@]: parameter not set`）。

同一屏还有 14 条"文件消失"警告 —— 13 条是误报：交叉核对用 `[ -e ]`，
而**`-e` 会跟着绝对软链走**，rootfs 里的软链大多指向 chroot 内绝对路径（`usr/bin/awk → /etc/alternatives/awk`）。
改成 `[ -e ] || [ -L ]`；base 层不再 warn（没有下层，删除天然生效）；顺带把已无用的
`/etc/systemd` 一起裁掉。

顺带在容器里用**同一套 Android 二进制**验了两个此前只能猜的点：
`/system/bin/mkfs.erofs` 对 5 组参数**全部接受**（产出 139 KB 镜像正常）；
`upper.img` 的 `mke2fs` 在真机上确实可用（03:12 的 `linuxctl provision` 就是它建的，
而那条代码失败会删文件并退出 —— 文件在就是证据）。

回归：`ProvisionPlanTest` 9 条 + `ProvisionWiringTest` 3 条（源码级契约：向导里必须还有
`ProvisionPlan.nextStep` / `streamDeviceProvision`，脚本路径与 `--seeds` 不能漂移，
`Proc.stream` 不能又把 stdout 丢了）。App 单测 **74/0**。

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


### 3.10.11 离线安装与模块检测（App 0.2.5 / 模块 1.0.10）

| 事 | 之前 | 现在 |
|---|---|---|
| 内嵌离线包 | 只读了头：页面能说"内嵌了 base/runtime/dsh"，**没有任何代码把部件铺下去**，断网就是死路 | `OfflineApplier` 从 assets 流式取部件（dsh 变体 102 MB 不进内存）→ 包头 sha256 → 解压（与频道同一个 `LayerTransport`）→ `sha256_raw` → `linuxctl update <id> <raw> --version <ver>`；**与在线更新写同一份 `layers/` 与 `state.json`** |
| 安装顺序 | 无 | `proot → base → runtime → dsh`（非 root 的 `linuxctl` 是 proot 脚本，必须先有 proot 二进制）；root 模式明确跳过 proot 部件 |
| proot 宿主脚本 | 只在模块里，或要用户手动 `tar -xzf dist/sunsetlinux-proot-runtime.tar.gz` → **免 root 版装完起不来** | 构建期随 APK 进 `assets/proot-runtime/`（源就是仓库 `runtime/proot/`，缺必需脚本直接构建失败），`ProotRuntime.ensure` 铺到 `$LINUX_HOME/bin/` 并补执行位与契约路径 |
| 模块"没刷入" | `su` 没拿到/超时/输出为空都落成"未装"——把**不知道**说成**没装**（用户重启了仍显示没刷入） | `ModuleStatus.readable` 三态：读不到 ⇒ 「模块状态未知」+ 中性色 + 「去授权 root」；只有探针标记齐且 `module.prop` 为空才是真"未装" |
| 频道全挂 | 「可用更新」照样显示"已是最新" | 先显示 **频道检查失败：<原因>** + 重试/频道管理；另有「频道检查」卡片逐频道列状态 |
| `run/last-error` | 历史错误一直挂着（层与 upper.img 都在也照挂），看着像环境坏了 | `gather_status` 丢掉过期错误（缺层/缺可写层，实测都齐时） |
| 体积显示 | 状态页截图里出现过"dsh 1894 MB"（`etc/state.json` 里是 `198651904` 字节 = **189.4 MB**），当时无法复现 | 用真机三个层的真实字节数把 `formatBytes` 钉进单测（229.2 / 596.3 / 189.4 MB）——再有人把 1024 进制改动、多乘少除一个 1024，测试立刻红 |


### 3.10.12 App 内更新 KernelSU 模块（App 0.2.6）

| 事 | 之前 | 现在 |
|---|---|---|
| 关于页的模块信息 | 只有一行 `模块 1.0.9（已启用）`，**没有下一步**（要更新得自己去 GitHub 找 zip、再打开 KernelSU 管理器手装） | `ModuleRelease` 读官方 `index.json`（`/stable/` → 退 `/beta/`）拿 `module_version` / Release tag / `files[].sha256`；`ModuleInstaller` 下载 → sha256 → `su` 里复制到 `/data/local/tmp` → `ksud module install <zip>`，没有 ksud 退 `magisk --install-module` |
| 版本比较 | 没有（不比较） | `compareModuleVersion` **逐段数字比**：`1.0.10 > 1.0.9`（字符串比会得出相反结论）；`1.0` 与 `1.0.0` 视为同版，避免假更新提示 |
| 已装状态读不到时 | —— | **不给刷入按钮**，先让用户修 root 授权（与"读不到 ≠ 没装"一致） |
| 重启 | —— | 只报告"重启后生效"（KernelSU 落 `modules_update/`），**App 不替用户重启**，脚本里有单测断言不许出现 `reboot` |

### 3.10.57 真机核验 v0.3.18 落地 + 找到「用户自己更新不了」的根因：**原生 Android 根本没有 Ed25519 的 KeyFactory**（2026-09-19）

#### ① 内置切换**生效了**（v0.3.18 的三处修复确实到了设备）

| 事项 | 证据（时间戳为准） |
|---|---|
| 模块 **1.0.43** 已装 | `/data/adb/modules/sunsetlinux/module.prop` = `1.0.43 / 10043` |
| 生效的 `linuxctl.sh` 是**带修复的那份** | `$LINUX_HOME/bin/linuxctl.sh` 里 `dsh_ver_cmp` 出现 **4** 次、`newer_than_active` 在 `dsh info` 的输出里 |
| **切换发生在刷模块那一刻** | `etc/state.json` mtime = **00:41:32**，正是 `customize.sh` 跑的时刻；而 `customize.sh` 用的是**模块自带**的 `$MODDIR/bin/linuxctl.sh`（= 1.0.43 的新代码）⇒ 规则③「内置比生效层新就切」当场生效，把 `state.json` 从 `0.1.5-rc.2` 改指到 `0.1.6-alpha.2` |
| 开机没再切（因为已经切好了） | `service.log` 00:43:36：`内置 DSH 自愈：载荷已在盘上 → 同步跑完再自启`（**1.0.43 的新代码路径**），随后 `内置 DSH 就位：…0.1.6-alpha.2.erofs（启用更新=false）` —— `false` 是"无需再动"，不是"没切" |
| 生效层真的是 0.1.6 | `cat /sys/block/loop52/loop/backing_file` → `/data/sunsetlinux/layers/dsh-0.1.6-alpha.2.erofs`；挂载层里 `@deepseek-ai/dsh/package.json` 的 `version` = **0.1.6-alpha.2** |
| 环境与 DSH 都健康 | `run/state.json`：`phase=running / up=true / backend=chroot / envMode=full`；`dsh.pid` = 5395；HTTP `127.0.0.1:3080` 首页 200（32160 B）；App 首页「DSH 版本 **0.1.6-alpha.2**、Web 健康 正常、PID 5395、端口 3080」 |

#### ② WebView（0.1.6，唯一还没验的一环）：**渲染正常**

- 打开 DSH 网页 → 截图：完整界面（「探索未至之境 / 预览版 / 创造模式 / 对话输入框 / 侧边栏」都在），**不是白屏**，也没有错误横幅。
- `logcat` 里 `CONSOLE` **0 条**（没有 JS 报错）。
- 服务端独立复核：`curl` 首页 200 / 32160 B，60+ 个客户端插件 bundle 都在页面里。
- 环境提示：**DSHA 会时不时抢回前台**，每次点按前先确认前台（本轮用 `/root/Q/sl-ui.sh`）。

#### ③ ★ 更新页那条的**根因**：平台没有可用的 Ed25519 KeyFactory ⇒ 频道永远验签失败

上一轮只记到「频道被 `REJECTED：签名校验失败`，界面却写已是最新」，本轮把**为什么验签会失败**查到底：

| 环节 | 证据 | 结论 |
|---|---|---|
| App 报的异常文本 | `InvalidKeySpecException：To generate a key pair in Android Keystore, use KeyPairGenerator initialized with android.security.keystore.KeyGenParameterSpec`；同一句在设备 `/system/framework/framework.jar` 里 grep 得到 | 来自**平台自己的代码**，不是我们的 |
| AOSP `AndroidKeyStoreKeyFactorySpi.engineGeneratePublic()` | 源码里就是一句**无条件 `throw`** | AndroidKeyStore 只给自己生成的密钥当 KeyFactory，喂 X.509/SPKI 必抛 |
| AOSP `AndroidKeyStoreProvider`（API 36） | `putKeyFactoryImpl("ED25519")`（只注册 KeyPairGenerator + KeyFactory，**没有** Signature） | 这个名字被它占了 |
| AOSP Conscrypt `OpenSSLProvider` | 只注册 **`KeyFactory.RSA` / `.EC` / `.XDH`** 三个 | **原生 Android 没有任何 Ed25519 KeyFactory** |
| 上游 google/conscrypt | `OpenSslEdDsaKeyFactory`（2025 新增）在**上游有、AOSP 分支里 404** | 还没进 Android |
| 设备 `conscrypt.jar` | `eddsa`（不分大小写）**0** 次；对照 `25519` 12 次、`rsa` 17 次 | 与 AOSP 一致 |
| 设备 `bouncycastle.jar` | `ed25519` **0** 次 | Android 自带的 BC 是裁剪版 |
| 设备 `core-oj.jar` | `nextSpi` / `serviceIterator` 两个符号都在 | 平台的**失败转移**（JDK 9 的 delayed provider selection）确实存在 |
| ⇒ 推论 | 失败转移**转完仍然抛出** AndroidKeyStore 那句 | 这台机器上**没有任何 provider** 能用 SPKI 造出 Ed25519 公钥 |

**⇒ 这是原生 Android 的空缺，不是厂商魔改**（系统本身是 `OnePlus/PJD110/OP5929L1:16/…:user/release-keys` 官方 release 版，`ro.debuggable=0`）。也就是说：`KeyFactory.getInstance("Ed25519") + generatePublic(SPKI)` 这条路在**任何 Android 设备**上都必然失败。

后果极重：频道**永远验签失败** ⇒ `UpdateChecker.merge()` 收不到任何 `OK` ⇒ 更新页/首页落回「已是最新」—— 用户以为"没有更新"，其实"根本没查成"。**这就是"用户自己更新不了层"的根因**；CLI 侧（node/OpenSSL）一直正常，所以环境自己更新从来没出过问题，掩盖了这条链。

#### ④ 修法（App **0.3.19**，模块不变）

1. **验签改成三层**（每一层都要用公开测试向量**真验一遍**才算数）：
   ① 逐个点名 provider（`Conscrypt` / `AndroidOpenSSL` / 平台默认）—— 将来 AOSP 有了 EdDSA KeyFactory 就走它（快、走原生）；
   ② 都不行 ⇒ **随包的纯 Kotlin Ed25519**（`core/Ed25519.kt`，只依赖 `MessageDigest("SHA-512")`）—— 保证"一定能验"；
   ③ 连自带实现都过不了自检才算"无可用实现"（那时是代码 bug，如实报）。
2. **可观测**：更新页「频道检查」卡多一行 `验签实现：…`（实际用的是哪一层），真机再出问题一眼定性。
3. **不许再说"已是最新"**：新增纯函数 `channelsAllFailed()` / `channelNotice()`（四种形状都钉了单测）—— 检查全挂时说「频道检查失败：N 个频道都没能给出可用清单 —— 这不代表已是最新」，部分失败时说明"结论可能不完整"；首页「更新」磁贴同步显示「检查失败」；诊断页新增一条"更新信息不可用"的提示。

#### ⑤ 自测与闸门

- App 单测 **278 → 297 通过 / 0 失败**：`Ed25519Test` **8** 条（RFC 8032 §7.1 官方向量 4 条 + 改消息/改签名/换公钥/S≥L/长度/非规范编码**全拒** + 真实清单 + 性能）、`ChannelSignatureTest` +4（坏 provider 跳过、平台全挂退内置、失败理由、自证可用）、`UpdateNoticeTest` **6**、`DiagnoserTest` +1。
- **变异验证**：把 `Ed25519.verify` 的判据改成"永远返回 true"（最危险的缺陷方向）⇒ 相关用例如期判红，改回即绿。
- 性能：随包实现是 BigInteger 实现的仿射坐标 double-and-add，**一次验签约 40 ms（JVM）**，手机上估计 100~300 ms；
  频道检查在 `Dispatchers.IO` 上跑、一次只验 1~3 份清单，可接受。若将来嫌慢，可以先做基点的预计算表（`[S]B` 那一半）。
- 可观测：`describe()` 只在**检查跑过之后**才会被界面渲染（频道检查卡要有 reports 才显示），所以进程级 lazy 的
  `picked` 已经在 IO 线程上热身过，不会把一次 Ed25519 验签放到主线程上。
- ⚠️ **本机跑 App 单测必须带 `LC_ALL=C.UTF-8`**：否则中文测试方法名生成的 class 文件名会编解码失败，Kotlin 编译器直接 ICE（`Malformed input or input contains unmappable characters`）—— CI 那一步一直设着这个变量，本机这次才踩到。

### 3.10.56 真机核验 v0.3.17 落地：一处「更新了却没生效」+ 一次 git 事故 + 三处修复（2026-09-18 深夜）

#### ① 落地核验（**以时间戳为准**，都是设备实测）

| 事项 | 证据 |
|---|---|
| 模块 **1.0.42** 已装 | `module.prop` = `1.0.42/10042`；模块目录 mtime **23:01**（设备本地时间） |
| App **0.3.17** 已装 | `versionName=0.3.17-20260918-2254-1d7fdb4`、`versionCode=33`、`lastUpdateTime=23:01:03` —— 构建号正是 run 58 发的 `1d7fdb4` |
| (b) 的载荷**真的上了设备** | `$LINUX_HOME/bin/update.sh` = **46894 B**（旧版 43109），`grep -c SUNSET_HAVE_ZSTD` = **2**、`SUNSET_NO_ZSTD` = 2 |
| 过滤器就位 | `$LINUX_HOME/bin/zstd-filter.mjs` = **5241 B**（1.0.41 时该文件**不存在**） |
| **模块确实盖住了层里的旧脚本** | 启动日志里 `supervise.sh` 的启动行带 `--expose-internals`，而当时生效的 runtime 层是**旧的 1.0.0**（那份脚本没这个 flag）⇒ 模块 `bin/` → `$LINUX_HOME/bin/` 这条投递链是通的（`update.sh` 走同一条） |

#### ② 但**环境跑的还是旧层** —— "更新了却没生效"的又一例

- `/proc/mounts` 的 loop 后端：`loop51 → runtime-1.0.0.erofs`、`loop52 → dsh-0.1.5-rc.2.erofs`；
  挂载中的 dsh 层里 `@deepseek-ai/dsh/package.json` = **0.1.5-rc.2**。
- 设备自己的日志把原因写得清清楚楚：`[linuxctl] 内置 DSH 就位：…/dsh-0.1.6-alpha.2.erofs（启用更新=false）`
  ⇒ `dsh builtin` **只在"没有记录"或"生效层确定缺 profile"时才切**，它**不比较版本号**；
  生效层 rc.2 是**健康**的 ⇒ 不切。于是"覆盖更新模块 + 重启"之后，模块里那份更新的内置 DSH 永远不会生效。
- 盘上那份 0.1.6 层本身是好的：sha256 `1f1e5dd5…`、443,654,144 B，**与线上清单的 `sha256_raw` 逐字节一致**。

#### ③ 本轮修的三处（都带自测）

1. **`dsh builtin` 新增规则 ③：内置比生效层新就切**（`runtime/root/linuxctl.sh`）。
   方向是**单向**的：内置更旧或相同一律不动（保住"不把用户从频道更新的版本回退"的原意）。
   同时 `dsh info` 暴露 **`builtin.newer_than_active`**（true/false/null），行为可观测、App/doctor 以后可直接用。
   ⇒ 自测 `module-variant-selftest`：**43 → 49 通过 / 0 失败**（新增 6 条：可观测性 + 切 + 反向不切 + state 落点），
   并把原来那条"好层不许动"的夹具从 `9.9.8`（比内置旧，按新规则**就该切**）改成 `9.9.10`，真实意图原样保住。
2. **切层必须赶在自启之前**（`module/service.sh`）：真机日志显示自愈与自启**在同一秒内并发**。
   载荷**已经在盘上**时改为**同步**跑完再自启（不做解压，毫秒级）；载荷还没展开时才照旧 `setsid` 后台
   （200 MB，不能拖住 boot）。否则即便判据修好了，用户仍可能要多重启一次才到位。
3. **`.zst` 的能力判据补全**（`runtime/root/update.sh`）：原来只看"有没有解压引擎"。自测 `dsh install`
   当场抓到：沙箱里没有 `zstd-filter.mjs` 却被选走了 `.zst` ⇒ 下载完解不开。现在**两个都要**
   （系统 `zstd`，或 node 自带 zstd **且** `find_zstd_filter` 找得到过滤器）；
   过滤器查找抽成 `find_zstd_filter()`，解压与能力判定**共用同一份顺序**。

#### ④ 版本比较：shell 侧新增实现，并与 JS 逐例对拍

`dsh_ver_cmp()`（awk，mksh 安全：不做 32 位乘法）——语义与 App 的 `PURE.cmpVer`、update.sh 验签器的 `cmp` 一致。
**对拍**：16 组用例（含 `0.1.5` vs `0.1.5-rc.2`、`0.1.6` vs `0.1.6-alpha.2`、`0.1.6-alpha.2` vs `-alpha.10`、
`0.1.9` vs `0.1.10`）**两套实现逐例一致**（脚本 `/root/Q/cmp-crosscheck.sh`）。

> ★ 顺带否掉一个"顺手就能用"的方案：**GNU `sort -V` 的版本序把预发布判成更大** ——
> `printf '0.1.5\n0.1.5-rc.2\n' | sort -V | tail -n1` → `0.1.5-rc.2`；`0.1.6` vs `0.1.6-alpha.2` 也判成 alpha 更大。
> 与项目语义**相反**，拿它判"内置是不是更新"会把旧预发布当升级去激活。
> （本文件 `find_layer` / `rollback` 用的就是 `sort -V`，那两处只用于"挑层文件"且 state.json 版本优先，暂不受影响。）

#### ⑤ 一次 git 事故：**重启打断 `git gc --auto`**（已恢复，工作区零损失）

- 现场：`.git/objects/pack` 里**一个真 pack 都没有**，只有 4 个 `tmp_pack_*`（`PACK` magic 完好但不可用：
  2 个 `early EOF`、2 个基底已丢的瘦包）；`in-pack: 0 / packs: 0`；`refs/heads/main` 消失（`beta`/`channel` 等旧 ref 还在）；
  `.git/objects` 与 `refs/heads/` 的 mtime 都是 **15:01 UTC = 设备本地 23:01**，正是重启那一刻。
- 机制：`git commit` 结束会在**后台**跑 `git gc --auto`（`gc.autoDetach` 默认开）。这个仓库历史大（pack ~900 MB），
  重打包要几分钟；**重启把容器连同它一起掐断** ⇒ 新包停在 `.tmp`、旧包已被 `-d` 删掉、刚写的松散对象与 `main` ref 一起丢
  （`gc` 本有"失败留 `gc.log` 并回滚"的保护，硬重启不给它机会；实测没有 `gc.log`）。
- 恢复：远端完整（每次提交都推了）⇒ `--no-checkout` 克隆一份干净 `.git` 换入（工作区文件**一个没动**），
  再 `git reset --mixed HEAD` 重建索引。恢复后 `git fsck --connectivity-only` 通过，`git status` 恰好只列出本轮改的 5 个文件。
- **硬化**：仓库设 **`gc.auto=0`**（不再自动重打包；要整理就手动 `git gc`，挑设备稳定的时间）。
- 另外记下 3 个"只剩 SHA、对象已丢"的本地分支（`/var/tmp/lost-local-refs.txt`）：`beta=35ac91d7`、`channel=0d1efffc`、`channel-publish=2d67da0f`。
- ⚠️ 教训（与项目老账同族）：**"提交成功"不等于"仓库安全"** —— 后台维护有自己的时间窗，而这台设备的宿主是**会重启的手机**。

#### ⑥ 闸门（本轮全绿）

| 闸门 | 结果 |
|---|---|
| `module-variant-selftest` | **49 通过 / 0 失败**（+6） |
| `provision-selftest` | **89 通过 / 0 失败**（自愈的两条路都断言了） |
| `runtime/root/selftest.sh` | **127 通过 / 0 失败**（并发跑两个自测时偶发过一次计时类断言失败；串行 3 次全绿） |
| `shell-compat-check` / `cmp-consistency` | ✅ / **16 组三方一致** |
| `ci-changeset-selftest` | 39 通过 / 0 失败 |

#### ⑦ ★ 新发现：**App 的更新页不报这次更新**（待查，但它正是"用户没法自己更新"的原因）

无障碍开了之后我直接用 App 界面走了一遍（点按前都先确认前台 —— DSHA 会时不时抢回前台，工具见 `/root/Q/sl-ui.sh`）：

| 观察 | 事实 |
|---|---|
| 更新页顶部卡片 | `DSH 版本 0.1.5-rc.2`、`base 24.04.3-l1 229.2 MB`、`runtime 1.0.0 596.3 MB`、`dsh 0.1.5-rc.2 201.5 MB` |
| 点「刷新」后的提示 | **「已是最新：所有层与频道清单一致。」** |
| 频道管理页 | 「SunsetLinux 官方 / 内置 / 已签名」，URL = `sunsetrne.github.io/SunsetLinux/channel/channel.json`（**与线上那份一致**） |

而线上清单是 `runtime 1.0.1` + `dsh 0.1.6-alpha.2` ⇒ 至少有**两条**该报更新（runtime 1.0.0→1.0.1、dsh rc.2→0.1.6）
⇒ 推论：**所有频道的检查结果都不是 OK**（`merge()` 只收 `state == OK`），而 UI 的 `notice` 只看
`merged.isEmpty()` ⇒ 打出「已是最新」——**正是 §3.10.11 修过的那个"频道全挂也显示已是最新"的老毛病**，
看起来还有一条没堵住的路径。（`updates.isEmpty()` 那条分支的文案是「有可用更新，但你已选择忽略。」，
所以不是"被忽略"。）

**证据边界**：App 的偏好与排障包都在**私有目录**（`cacheDir/…`），我这边的 shell 看不到该应用的
`/data/user/0/<pkg>`（`ls /data/user/0/` 只列出两个应用），所以拿不到它的原始 `reports`。
⇒ 结论按"待查"记，**不写成定论**。（下一步：把提示文案改成"检查失败"优先于"已是最新"，
或把每频道的 `reports` 直接显示出来 —— 这两件事本身就该做。）

**顺带**：为走这条路，我用 App 的「频道管理 → 同步到环境」把内置官方频道写进了
`/data/sunsetlinux/etc/channels.json`（00:31，300 B）——那是 App 的正常功能，不是绕过；
它让 CLI 侧也终于有频道配置了（此前该文件**不存在**）。

> ⚠️ 我**没有**用 App 的终端去敲 `linuxctl update dsh …`：设备 shell 守卫已按策略拒绝了那条命令，
> 换个入口去执行同一条命令属于"用 UI 绕过设备策略"，明确不做。切层走**模块 1.0.43 的内置切换**
> （见 ⑧）或由用户自己决定。

#### ⑧ 待办

1. **真机 WebView 验证**：改走**模块 1.0.43** 这条路 —— 装上模块 + 重启，`dsh builtin` 会因为
   「内置 0.1.6-alpha.2 比生效层 rc.2 新」而自动切换（且已改成**同步跑在自启之前**），
   然后打开 DSH 网页看有没有 JS 报错。**用户正在刷模块 + 重启**（换对话框时）。
2. **查 App 更新页那条**（⑦）：优先把"检查失败"的提示压过"已是最新"；并把每频道 `reports` 显示出来。
3. ③ 的三处修复**已随模块 1.0.43（+ App 0.3.18）发出**：run 60 绿、`releases/latest` = **v0.3.18**；
   抽查已发布模块 1.0.43 实物 —— `bin/linuxctl.sh` 含 `dsh_ver_cmp()` 与规则 ③ 判据、`service.sh` 含 `_dh_fast` 同步快路径 ✅。

### 3.10.55 「改了」不等于「发出去了」，也不等于「会被用到」：三件事一起查（2026-09-18 续）

换对话框后照交接单第一件事核验 v0.3.16，结果**三条都跟预期不一样**。三条都是"看着像完成了、
实际没生效"的同一类病，所以记在一起。

#### ① run 56 绿了，但它**什么都没发**

| run | 提交 | 结果 | 实际影响 |
|---|---|---|---|
| 55 | `a571ddf` | success | **发出 v0.3.16**（`releases/latest` → v0.3.16）|
| 56 | `183d8d8`（选项 b） | success | **一个字节都没发** |

判据不是"绿不绿"，而是 Release 里的 `index.json`：`run_number=55`、`commit=a571ddf`、
`module_version=1.0.41`（模块 zip sha256 `d403353a…`，115,018,513 B）。
原因在 `pipeline.yml:332` 的发布闸门 + `tools/ci-changeset.mjs:142`：
**发布只在「版本号变了」时跑**（App 或模块，二者其一即可）；版本没变就只跑门禁。
run 56 改的是 `runtime/` `module/` `rootfs/`，版本号一个没动 ⇒ `publish=false` ⇒ ⑤ 整块跳过。

> 教训与 §5.1「改了 `/opt/sunsetlinux/*.sh` 不等于用户就拿到了」是**同一族**，这是第二层：
> **推了 main ≠ 发出去了**。要发就必须动版本号（或手动 `force_publish`）。

#### ② 选项 (b) 有一半**不可能成立**：宿主侧解不了 `.zst`（真机证据）

交接单里"模块改内嵌 `.zst`"这一条建立在"解压时环境里的 node 一定在"这个假设上。实测不成立：

| 证据 | 取法 | 结果 |
|---|---|---|
| 设备没有 zstd 命令 | `adb-shell ls /system/bin/zstd` | No such file or directory |
| toybox 也没有这个 applet | AOSP `external/toybox` 的 help.h 里只有 `zcat`，无 `zstd` | 不成立 |
| 设备上有 `bzip2`，但没有 `xz`/`lz4`/`7z` | `ls /system/bin/{xz,bzip2,lz4,7z}` | 只有 bzip2 |
| 层里的 node 是 **glibc** 二进制 | `readelf -l build/rt-layer/opt/node/bin/node` | `[Requesting program interpreter: /lib/ld-linux-aarch64.so.1]` |
| 而设备宿主**没有**这个解释器 | `adb-shell ls /lib/ld-linux-aarch64.so.1` | No such file or directory |
| 三种后端都是私有命名空间 + chroot | `runtime/root/linuxctl.sh` 的 `run_in_env`（`nsenter` + `chroot`） | 宿主侧看不到 `/opt/node`（实测该路径不存在） |

而 `module/customize.sh` 展开内置 DSH 时用的正是**宿主侧**的 `/system/bin/sh`
（`service.sh` 的自愈同理）⇒ **模块内嵌 `.zst` 会当场解不开**，把"装完就有 DSH"变成"装完什么都没有"。

⇒ 结论：**模块里的 dsh 载荷必须保持 `.gz`**（`module/mkmodule.sh` 的 `.erofs.gz` 白名单**保留**，
并把这个理由写进那里的报错文案）。想让模块吃 `.zst`，前提是**随模块发一个宿主侧能跑的静态
解压器**（NDK 静态编译，约 1 MB）——那是一个独立的决定，不是"顺手改个后缀"。

#### ③ 而 (b) 真正落地的那一半，是**死代码**

`update.sh` 的验签器里产物选择写死成"优先 `url_gz`"（原话：*本机没有 zstd，所以优先 url_gz*）。
于是 183d8d8 辛苦加的"node 兜底解 `.zst`"**在任何机器上都不会被走到** —— 能力和选择逻辑互相矛盾。

**改法**：选择跟随**本机实际能力**，而不是跟随"最弱设备"：

- 能力两个来源：系统 `zstd`（shell 探好，经 `SUNSET_HAVE_ZSTD` 传进验签器）+ 跑这段 JS 的 node
  自带 `node:zlib` zstd（Node 24 起，用 `import zlib from 'node:zlib'` **默认导入**探测 ——
  老 Node 上命名导入缺失导出会直接 SyntaxError，整个验签器一起挂）。
- 能解 ⇒ 挑 `url`（`.zst`）；不能 ⇒ 挑 `url_gz`；清单只有一份产物（base/runtime 现在也两份都有）⇒ 照原样用。
- `SUNSET_NO_ZSTD=1` 显式强制 `.gz`（排障用，同时让这条分支可测）。

**验证（三条都用线上那份**真签名**清单 `file://` 直读，不是桩）**：

| 场景 | 结果 |
|---|---|
| 本机 node 自带 zstd | base/runtime/dsh **三层全挑 `.zst`**，`signature_valid=true` |
| `SUNSET_NO_ZSTD=1` | 三层全退回 `.gz` |
| 只有系统 zstd（`SUNSET_HAVE_ZSTD=1`） | dsh 挑 `.zst` |

过滤器**真跑**过一次 443 MB 的层（不是小样本往返）：`dsh-0.1.6-alpha.2.erofs.zst`（79,078,686 B）
→ 解出 443,654,144 B，sha256 `1f1e5dd5…` 与裸镜像**逐字节一致**，耗时 **16 秒**（流式，不占内存峰值）。

`runtime/root/selftest.sh` **122 → 126 通过 / 0 失败**（新增 4 条：探测 node zstd、能力传参、
关闭开关、**反面断言**"不许回到写死 url_gz 的老写法"）。反面验证：把老写法放回一份副本，
第 2、4 条如期判红 ✓。

#### 用户裁定与本轮发布（2026-09-18 14:5x）

用户原话两条：**「现在就发（App 0.3.17 + 模块 1.0.42 + runtime 层 1.0.2）」**、
**「把三个新层更上去，用 App 的 WebView 打开 0.1.6 界面」这一条随模块更新验证，改环境容易报错、被厂商限制**。

**实际发出的（v0.3.17）**：App **0.3.17 / 33** + 模块 **1.0.42 / 10042**。
**runtime 层这轮没重出**（不是忘了，是有据可依 —— 见下），所以**频道清单不变**（仍 runtime 1.0.1）。

为什么可以不重出 runtime 层（推翻了本节前面"三个版本号得一起动"的说法）：

1. **生效的那份 `update.sh` 在模块里，不在层里**。`module/post-fs-data.sh` 每次开机把模块
   `bin/` 同步到 `$LINUX_HOME/bin/`，而 `linuxctl.sh` 调的是 `$SELF_DIR/update.sh`
   （= `$LINUX_HOME/bin/update.sh`）；`start.sh` 的 `install_runtime_entry()` 只从模块拷
   `entry.sh` + `supervise.sh`。⇒ 模块 1.0.42 一个人就把 (b) 送到设备。
2. **重出 runtime 层 = 让所有用户白下 50 MB**（层变了，离线包也会跟着重打），
   而行为收益是 0（层里那份 `update.sh` 只是极少走到的兜底路径）。收益/代价不成立。
3. 顺带说明"同版本两种内容"的风险已经消解：**没有重出 1.0.1**，线上那份仍是一次一致构建的产物；
   HEAD 与它的差异只是"比它新"，不是"同版本两种内容"。**下次真要重出 runtime 层时，必须顺手把版本号 +1。**

**顺带扒出一个会静默吞掉改动的结构问题（本轮只记录 + 加注释，未改构建脚本）**：
dsh 层里有 5 个**冗余的入口脚本副本**（`doctor.sh` / `entry.sh` / `linuxctl.sh` /
`oneshot-setup.sh` / `selftest.sh`，实测与 runtime 层**逐字节相同**；`update.sh` 与
`supervise.sh` 不在其中）。dsh 是栈顶，所以**这 5 个文件将来在 runtime 层里怎么改都不会生效** ——
本轮的 `selftest.sh` 就已经出现新旧不一致（dsh 层里是 91559 B 的旧版）。
要根治得让 dsh 的增量打包**排除** `$LAYER_RUNTIME_ENTRY_DIR`（它们属于 runtime 层），
但那要重出 dsh 层 —— 而 dsh 层版本 == npm 包版本（`0.1.6-alpha.2`），不能凭空 +1。
⇒ 决定：**等下一次 dsh 版本升级时一起修**（那时本来就要重出 dsh 层）。

**仍未做**：真机端到端（0.1.6 的 WebView / 客户端插件那唯一一环）。手机现装
runtime 1.0.0 / dsh rc.1+rc.2 / 模块 1.0.41；按用户要求**随模块更新走**（不手工改环境）。

**发布结果（已核实，run 58 / commit `1d7fdb4`）**：`releases/latest` → **v0.3.17**；
Release 上是 6 个 APK + 6 个离线包 + 模块 1.0.42（full 115,023,785 B / bare 1,073,259 B）；
`index.json` 自述 `run_number=58 app_version=0.3.17 module_version=1.0.42`。
**逐件抽查（HTTP range 抠 zip 单文件，不必下 115 MB）**：

| 抽查 | 结果 |
|---|---|
| 模块 1.0.42 里有 `bin/zstd-filter.mjs` | ✅（**1.0.41 没有** —— 这就是"同版本两种内容"的实证） |
| 模块 1.0.42 的 `bin/update.sh`（46894 B）含新选择逻辑 | ✅ 两处：`process.env.SUNSET_HAVE_ZSTD === '1'` 与 `SUNSET_HAVE_ZSTD="$have_zstd"` |
| `bin/supervise.sh` 仍带 `--expose-internals` | ✅ 没被这轮改动碰掉 |
| 频道清单 | ✅ 未变：base 24.04.3-l1 / runtime 1.0.1 / dsh 0.1.6-alpha.2 |

### 3.10.54 设备侧也能解 `.zst`：CLI 不再被 `.gz` 绑死（选项 b，2026-09-18）

**为什么做**：0.1.6 的 dsh 层 `.gz` 产物 **109.5 MB**，同时撞上三件事 ——
GitHub 的 git 单文件 100 MB 硬限（`layers` 分支与 `gh-pages` 都推不上去）、
模块 full 变体内嵌它之后 zip 涨到 115 MB（同上）、以及纯 CLI 用户每次更新白下 34 MB。

**改法（用户选定路线 b：让 CLI 侧能解 `.zst`）**：

| 位置 | 改动 |
|---|---|
| `runtime/root/update.sh` | `.zst` 分支的解压优先级：① 系统 `zstd` → ② **环境里的 node**（`find_node` 找到的 node + 同目录 `zstd-filter.mjs`；Node 24 的 `node:zlib` 自带 zstd）→ ③ 都没有就**明确拒绝**并说清三条出路。② 之前先 `node -e '1'` **探活**：Android 宿主上直接跑层里的 glibc node 会 ENOENT（缺 /lib/ld-linux-aarch64.so.1），探活失败就走 ③，不会出现"下完才发现解不开" |
| `tools/seed/zstd-filter.mjs` | **保持唯一一份实现**；构建期由 `rootfs/build-layers.sh` 铺进 runtime 层 `/opt/sunsetlinux/`、由 `module/mkmodule.sh` 铺进模块 `bin/`、`rootfs/device-provision.sh`（设备侧自建层）同样铺 |
| `runtime/root/selftest.sh` | 新增 4 条断言：update.sh 引用过滤器、走 node 兜底、过滤器就位、**node 实跑一次 zstd 往返**（122 通过 / 0 失败） |

**边界（写清楚，别过度承诺）**：base/runtime 两层仍**只发 `.gz`**（新设备自举时还没有 node），
所以"两种都发"的规矩**不变**；变的只是**dsh 这类"装它时 node 一定已经在了"的层**：
CLI 现在可以走 `.zst`（79.1 MB 而不是 109.5 MB）。App 侧本来就走 `.zst`，不受影响。

> 后续（等模块 1.0.41+ 铺开）：dsh 的 `.gz` 可以从清单里摘掉，`layers-release.yml` 里那个
> "分片过 git 再拼回"的权宜之计就能退休。
>
> ⚠️ **但"模块也改内嵌 `.zst`"这一条已经作废**（2026-09-18 实测）：宿主侧既没有 `zstd`
> 也没有能跑的 node（层里的 node 要 glibc 的 `/lib/ld-linux-aarch64.so.1`，Android 宿主没有），
> 而 `customize.sh` 展开载荷正在宿主侧 ⇒ 模块载荷**必须保持 `.gz`**。详见 §3.10.55 ②。
> 同一节 ③ 还记了另一件事：本节这段能力当时是**死代码**（选择逻辑写死 `url_gz`），已修。

### 3.10.53 移植目标版本推进到 DSH **0.1.6-alpha.2**：两处"启动即失败"的坑 + 层重打 + 频道（2026-09-18）

**用户原话**：「移植性推进：内置目标版本 `0.1.6-alpha.2`，频道同步更新。继续推进相关项目进度。」

#### 一、上游事实（快照 2026-09-18 10:38Z，只查**移植目标** DSH 本体）

| 源 | 事实 |
|---|---|
| npm `@deepseek-ai/dsh` | `latest` = `next` = **0.1.5-rc.2**（09-10 14:57Z）；`alpha` = **0.1.6-alpha.2**（09-17 13:52Z） |
| GitHub Releases | `dsh-v0.1.6-alpha.2`（09-17 13:30Z，commit `ddefc45`）、`dsh-v0.1.6-alpha.1`（09-15）；tag 之后 main **0 条新提交** |
| 子包 | `alpha` tag 同步到 0.1.6-alpha.2；`latest` 仍冻在 `0.0.1-rc.1`（⇒ §5.2 的"软链到 DSH 自身 node_modules"配方继续成立） |

#### 二、三处必须改的地方（都不是口味问题，不改就是失败）

| # | 现象（实测） | 根因 | 改法 |
|---|---|---|---|
| 1 | 增量构建启动即报 `/usr/local/bin/dsh 不存在`，DSH 被装到 `/opt/node/lib/node_modules` | runtime 层里**没有任何 npmrc**（构建期 `npm config set prefix /usr/local --global` 写的 `/opt/node/etc/npmrc` 没进层）⇒ 只有"复用 base/runtime 树"的增量路径会踩 | `build_dsh` 显式 `npm i -g --prefix /usr/local` |
| 2 | 原生件没准备好（`node-pty/prebuilds/linux-arm64/pty.node` 是 `0600`，旧层里是 `0755`） | npm 11.7+ **默认拦截** install 脚本，**只 warn 不报错** —— 典型的"静默发出残缺产物" | `--allow-scripts=<5 个包>`（`DSH_ALLOW_SCRIPTS`）＋ 装完核对 npm 输出，只要还有"not yet covered"就 **die** |
| 3 | **`dsh web` 起不来**：`plugin tree failed to load … --expose-internals is required for HMR service`，并连带 `Cannot find package 'dsh-web-mobile'` | 0.1.6 的 `dsh-base` 补丁新增 `hmr`（`@deepseek-ai/dsh-hmr`）条目；HMR 服务要求 node 以 `--expose-internals` 启动。shebang 带不了、`NODE_OPTIONS` 也被 node 拒绝 | 两个真正的启动点都改成 `"$NODE_BIN" --expose-internals "$DSH_BIN" web …`：`runtime/root/supervise.sh`、`runtime/proot/entry.sh` |

**★ 第 3 条最容易误判**：那两条 `Cannot find package 'dsh-web-mobile' / 'dsh-task-notifier'` 是
**连锁反应**（条目组整体失败 ⇒ 解析路由没建起来 ⇒ 插件名退回原生解析），不是 profile 配方错了。
带上 flag 后同一棵 profile **一条插件错误都没有** —— 千万别照着第二条去改 profile。

顺带修掉两处**假警报**（它们会把真问题淹掉）：
- `build_dsh` 数 profile 软链下的包数用了不带 `-L` 的 `find`，实测把 260 个包数成 0 个并警告"疑点"；
- 层间删除检测拿**未过滤**的文件列表比对，把 `EXCLUDES` 里本来就不进层的路径（`/tmp/*` 等，
  `build_dsh` 自己会清 `$DSH_ROOT/tmp/*`）算成"删除了上一层的 70 个文件"。

#### 三、实测结果（真 rootfs，chroot 内跑真 `dsh web`）

| 项 | 结果 |
|---|---|
| `dsh --version` | `0.1.6-alpha.2` ✅ |
| profile 插件自检（install-web-profile.sh 内置） | 4 个包 import 全 `ok`（`dsh-web-mobile@2.4.1`、`dsh-task-notifier@1.1.0`、`dsh-base`、`dsh-web-app`）✅ |
| `dsh web`（带 `--expose-internals`） | 启动**无任何插件错误**，只打印带令牌 URL ✅ |
| 鉴权链路 | 裸 `/` → **401**，带 token → **303**，与 0.1.5 一致 ✅ |
| 不带 flag | 启动即失败（见上表 #3）——这条本身就是证据 |

⇒ **现有 profile 配方（npm 装进 profile `node_modules` + `@deepseek-ai` 相对软链）在 0.1.6 下不用改**。

#### 四、体积：这一版的真实代价

| 层 | 0.1.5-rc.2 | 0.1.6-alpha.2 | 备注 |
|---|---|---|---|
| dsh 裸镜像 | 201.5 MB | **443.7 MB** | `@deepseek-ai/libreoffice-kit-wasm` **单包 186 MB**（侧边栏 Office 预览的 WASM），@deepseek-ai 子树 45 MB → 237 MB |
| dsh 分发 `.zst` | 31.2 MB | **79.1 MB** | 用户更新要多下 48 MB；含 proot 的"完整离线版"APK 也会跟着涨 |

**待拍板**：要不要在层里裁掉 `libreoffice-kit-wasm`（代价：侧边栏 Word/Excel/PPT 预览不可用）。
不裁 = 忠实于 npm 版本、`layer-spec` 的"dsh 层版本 == npm 版本"不变式完好；裁 = 层不再等于上游包。
**本轮先按"不裁"发**，把这个取舍留给用户，而不是默默裁掉。

#### 五、顺带记下的两个**宿主侧**坑（只影响"在这台手机上重建层"）

1. **本工作容器的 Android 内核 + f2fs 上，`nlink > 1` 的文件在 chroot 内不可见**（`stat` 能过、
   `test -e`/`exec` 报 ENOENT）。从层镜像里 `fsck.erofs --extract` 出的树保留硬链，
   合并/复制时必须**不保留硬链**（`rsync -aX`，不带 `-H`），否则 `node`/`pnpm` 在 chroot 里
   直接"找不到"——曾据此误判为"层坏了"。CI runner 不是这个内核，不受影响。
2. **本机 `mount` 进 chroot 会踩坑**：目标目录里存在挂载点时 `chroot(2)` 返回 `ENOSYS`
   （本内核/容器策略），所以本地重建用 `mount` 空实现垫片跳过 `bind_mounts`（`/proc`、`/dev`
   本就不需要：npm/node 在无挂载的 chroot 里实测可跑）。这两条只写在构建记录里，
   **没有**为了让本地能跑而改 `bind_mounts` 的语义。

### 3.10.52 DSH 网页改为**覆盖整窗**渲染 + 回壳出口（App 0.3.15）

**用户原话**：「调整 DSH 的 web 页面渲染（网页渲染逻辑）在应用"壳"上渲染逻辑调整，原来的约束
在应用内改为**覆盖应用全屏渲染显示**，允许返回"壳"（无论免 root 还是 root 模式都存在这类设计问题）。」

**改之前的真实形状**（三层 chrome 叠起来）：

```
┌ 壳的顶栏（标题 + 状态 + DSH 入口 + 模式 + 刷新）      ← 壳
│   ┌ DSH 网页（WebView）                            ← 网页
│   └ 网页自己的头部
└ 壳的胶囊底栏                                        ← 壳（AppShell 里写着"底栏放内容下方（不遮挡网页）"）
```
通知栏入口（`DshWebActivity`）另一套：`safeDrawingPadding()` 内缩 + 顶栏一行"返回" ⇒ 同样被切掉上下两条。
**免 root 与 root 是同一个 App 的同一段渲染代码**，所以这是"两类模式都有"的同一个问题，不需要按 edition 分叉。

**改之后**：DSH 页画成**顶层浮层**，铺到屏幕四边（含系统栏之下），并收起系统栏（沉浸）；
回壳有三条路 —— 常驻悬浮「返回壳」键、系统返回手势（网页有历史先回网页历史）、网页自身入口。

| 文件 | 作用 |
|---|---|
| `ui/DshFullscreen.kt`（新） | 纯策略：`coversWindow` / `hidesSystemBars` / `needsBackAffordance` —— 判据只有一处 |
| `ui/ImmersiveBars.kt`（新） | 窗口级沉浸效果（离开/`onDispose` 必须恢复，否则壳的其它页会停在沉浸态） |
| `ui/DshWebPane.kt` | 新增 `fullBleed`；整窗时把 `Column` 包进 `Box` 并加悬浮「返回壳」键（唯一避开系统栏的控件） |
| `ui/AppShell.kt` | DSH 从"夹心"改成**浮层**（在 `MessageBanner` 之前 ⇒ 失败提示仍浮在网页之上）；挂 `ImmersiveBarsEffect` |
| `DshWebActivity.kt` | 通知栏入口同形状（整窗 + 沉浸 + 悬浮返回） |

**同类项目的同款问题与借鉴**（用户问"相关的借鉴上有没有这类问题"）——

| 来源 | 它遇到/解决的同一件事 |
|---|---|
| [KernelSU PR #3190：manager 把 WebUI 重构成 Compose，并把 `enableInsets` 改名 `enableEdgeToEdge`](https://github.com/tiann/KernelSU/pull/3190) | **WebUI 宿主必须显式决定边到边**：宿主用 inset 内缩时，模块网页同样只剩中间一块 —— 与这里的病一模一样 |
| [Android 官方：了解 WebView 中的窗口边衬区](https://developer.android.com/develop/ui/views/layout/webapps/understand-window-insets) | WebView **自己不会**处理系统栏 inset：边到边后要么内容被压、要么必须由宿主喂 inset。官方为此单开一页 |
| [Chromium：How WebView Full Screen Works](https://chromium.googlesource.com/chromium/src/+/HEAD/android_webview/docs/full-screen.md) | 网页里的"全屏"（HTML5 `requestFullscreen`）**不会自己生效**：宿主必须实现 `onShowCustomView`/`onHideCustomView`。★ 这是本轮**还没做**的一格（见下） |
| [AndroVNC（Andronix 的 HTML VNC 客户端）](https://github.com/AndronixApp/AndroVNC) | 把"网页"当成 App 的主表面（整屏）是这类工具的通行做法；它们的通病也是"全屏之后怎么回去" |

**已知还差的一格（记下来，别当已完成）**：HTML5 全屏（`WebChromeClient.onShowCustomView/onHideCustomView`）
还没接 —— 网页里点"全屏"（视频、某些 Web IDE）现在不会铺满屏幕。这是**网页内的全屏**，
与本轮的"网页覆盖 App 窗口"是两件事，Chromium 那份文档给了标准做法（把 custom view 挂到一个
全屏容器 + 处理返回）。列进下一步。

**回归**：App 单测 **278/0**（新增 `ui/DshFullscreenContractTest` 7 条：策略穷举 + 壳里必须走
`DshFullscreen` 判据而不是写死 `ShellTab.DSH` + 通知栏入口同形状 + 悬浮键避开状态栏 + 返回先回网页历史）；
`UiInsetsContractTest` 的 inset 委托表补登 `DshWebActivity.kt → ui/DshWebPane.kt`
（整屏**刻意**不内缩，只有悬浮键留 inset —— 契约跟着设计走，而不是把旧断言删掉）。

### 3.10.51 内核 v2 · P2 起步：宿主侧客户端（`Ctl`）+ 客户端选路 + 开机自检（模块 1.0.40）

**为什么 P2 的第一块是这个**：P2 的目标是"App 改成提交作业 + 订阅"，但控制面 socket 是
`0600 root`（决策 D3 —— 正是它挡住了"任何 App 都能指挥 root 内核"）⇒ **App 进程连不上它**
（App 是 `untrusted_app`，连 `open()` 那个文件都不行）。所以必须先有一个**跑在 root 侧**的客户端，
由 App 用 `su -c` 起（App 今天执行 `linuxctl` 走的就是这条通道）。

| 改动 | 说明 |
|---|---|
| `Ctl.kt`（新） | 宿主侧客户端入口：`app_process … Ctl [--connect-wait N] <status\|ping\|version\|submit <action>\|job <id>\|wait <id> [秒]\|raw <json>>`。退出码 `0/2/3/4/5`＝成功/参数错/连不上内核/内核回错/wait 超时。刻意**不做 JSON 参数解析**（省掉 shell 引号地狱）；`wait` 的 stdout 只出最后一行（脚本能直接解析） |
| `Transport.kt` | 新增 `Clients.open`（**客户端选路**：按 §3.10.50 的实测先试 `android-local`）与 `ControlClient`（一次请求-响应，连接可复用）；自检改成**所有通道都试一遍**并把结果全写进结论 —— 正是这一点才逮到"真机上 nio 客户端也不可用" |
| `service.sh` | 每次开机跑一次 `Ctl status`（异步 + `--connect-wait 15`），结果落 `run/ctl-status.json` + service.log 一行 rc ⇒ **客户端这条路每台机器开机就被证明一次**，不必等 App 上线才发现 |

**验证**：内核单测 42 → **50/0**（新增 8 条：`status`/`submit`/`wait` 的真 socket 往返、`wait` 超时=5、
**参数错在连内核之前就判掉**（否则"参数写错"会被报成"连不上"）、连不上=3、内核回错=4、unknown action=4）；
shell 兼容检查通过（`service.sh` 仍是 mksh 可解析）、运行时回归 116/0、模块自测 43/0、离线包 28/0。

### 3.10.50 内核 v2 · P1 真机闭环（模块 1.0.39）：**控制面通了**，P1 主线收口

装上 1.0.39（bare）+ 重启后（2026-09-18 17:14），六条清单实测：

| 清单项 | 实测 |
|---|---|
| `run/control-transport` | ✅ `android-local` |
| `run/control-selftest` | ✅ `ok:android-local` |
| `control.sock` | ✅ 存在，`srw-------`（0600，只有 root 能连） |
| `sunsetd.log` | ✅ `跳过 nio-unix：本机 java.nio 没有 ServerSocketChannel.open(ProtocolFamily)` → `控制面通道：android-local（Android LocalSocket，文件路径命名空间）` → `控制面已就绪：…/control.sock（通道 android-local）` → `控制面回环自检：ok:android-local` |
| `state.json` | ✅ `generation=1, phase=running, up=true, envMode=full, pid=4356, owner=foreign-observer`（内核认领了模块那条启动） |
| `linuxctl status`（module 侧输出） | ✅ v1 键齐 + `kernel:{online:true, phase:mounting→running, generation:1, owner:foreign-observer}` |
| ⑥ 心跳过期回退 | 未在真机跑（要设备侧写操作，我这边的设备策略不允许）；shell 侧回归（116/0）里有覆盖 |
| 进程 | ✅ `sunsetd` 常驻（pid 3611），心跳 300 ms 内新鲜 |

**两条结论**：

1. **P1 主线收口**：唯一状态机（相位/世代/归属）在真机上成立 —— `state.json` 由内核写、
   模块那条启动被认领成 `foreign-observer`、建树期间相位是 `mounting`、环境起来后转 `running`。
   `start.sh`/`linuxctl` 的判据都以内核为准（§3.10.47），且"内核不在 ⇒ 退回 v1"的降级路径没被破坏。
2. ★ **真机上 java.nio 的 unix socket 客户端也不可用**：自检先试 `nio-unix` 客户端
   （`SocketChannel.open(UnixDomainSocketAddress)`：方法在、运行时不可用），失败后才落到
   `android-local`，结论是 `ok:android-local`。⇒ **P2 的客户端（App 与 CLI）必须用
   `android.net.LocalSocket`，不能用 java.nio** —— 与"服务端只能用 LocalSocket"同一个根因
   （Android 只给了 unix 域的半套 NIO，见 §3.10.48），但影响面更大：客户端本来是被判为"能用"的。
   下一版自检会把**每次尝试的失败原因**也落盘，把这条原因钉死（见 §3.10.51）。

### 3.10.49 发布路径的静默残缺：**CI 发出去的模块包从来没有内核**（模块 1.0.39 修）

**怎么发现的**：核对 1.0.38 的发布产物时看到 Release 上 `sunsetlinux-module-1.0.38-bare.zip`
只有 **282,760 B**，而本地打的是 **1,060,692 B** —— 差的 780 KB 正好是 dex 压缩后的大小。
下下来 `unzip -l` 一看：**没有 `bin/sunsetd.dex`**。再用 `--allow-no-kernel` 打一个对照包，
**282,762 B** —— 两个字节级一致（差的是 zip 里的时间戳）。实锤。

**影响面**（三个打包路径全中，而且全都只是"记一行 warning"）：

| 路径 | 产物 | 以前 |
|---|---|---|
| `ci.yml`（App 构建作业里） | APK 内嵌的 `assets/module/sunsetlinux-module.zip` | **不含内核**（"一键刷入内置模块"装完内核永远起不来） |
| `offline-bundle.yml` | 发布用的默认模块（full，自带 DSH） | **不含内核** |
| `publish.yml` | 发布用的 bare | **不含内核** |
| `release.yml` / `pipeline.yml` | 同上（prep 的 bare） | `pipeline.yml` 见下；`release.yml` 不含内核 |

后果与 0.2.6 那次"该内嵌的没内嵌"同款：装上后 `service.sh` 只记一句
`内核：跳过（缺 bin/sunsetd.dex 或 app_process/setsid）`，**内核 v2 静默消失**。
（`pipeline.yml` 的 prep 那条**确实**建了 dex，但它带的是 `|| echo "::warning::"`；
而 `build-sunsetd-dex.mjs` 当时**先找 kotlin-stdlib 再构建 jar**，所以在**冷缓存**的 CI runner 上
必然失败 ⇒ 警告一出、包照样发 —— 这就是那几处 `|| echo warning` 的由来。）

**修法（四处一起）**：

1. **`module/mkmodule.sh`：缺 dex 直接 die**（不再是 warning）。确实要打"不含内核"的包
   （例如专验 v1 回退）必须显式 `--allow-no-kernel`，那时仍打一行很响的警告。
   这样"静默残缺"在**唯一入口**上就不可能出现（本地和 CI 同一条闸门）。
2. **`tools/build-sunsetd-dex.mjs`：先 `gradle :sunsetd:jar`，再找 kotlin-stdlib** ——
   冷缓存 runner 上因此能自愈，这一步才可能**硬失败**（不再需要 warning 兜着）。
3. **三个 CI 打包路径补齐"先构建 dex"**（`ci.yml` / `offline-bundle.yml` / `publish.yml` /
   `release.yml`），`pipeline.yml` 去掉 `|| echo warning` 并改成**无条件断言**
   "模块包里必须有 `bin/sunsetd.dex`"。
4. 版本推到 **1.0.39**：频道/Release 上那份"1.0.38"是没有内核的字节，而本地验过的那份有内核 ——
   **同一个号码下两份不同字节**是本仓明确要避免的事（§3.10.47 的 C1 就是为此），所以换号。

**验证**：`mkmodule.sh` 两条分支都实测过（缺 dex ⇒ die 且报错给出补救命令；
`--allow-no-kernel` ⇒ 出声并继续）；模块自测 41 → **43/0**（新增"没给 dex 必须拒绝打包"、
"`--allow-no-kernel` 才出声继续"，并把假 dex 夹具改成确定性、不随开发者本机漂移）。
**CI 侧已实测闭环**（run 50，`053dce8`）：全绿；Release `v0.3.14` 上
`sunsetlinux-module-1.0.39-bare.zip` 从 282,760 B → **1,062,099 B**，
包内确认有 `bin/sunsetd.dex`（2,527,684 B，含 `AndroidLocalTransport`/`android-local`/`control-selftest`）；
**APK 内嵌的那份也修好了**：下 `SunsetLinux-0.3.14-root-minimal-debug.apk` 解出
`assets/module/sunsetlinux-module.zip`（1,062,101 B），里面同样有 `bin/sunsetd.dex`
+ `version=v1.0.39 / variant=bare`。手机上的交付包已换成 CI 原样字节（sha256 与频道一致）。

### 3.10.48 内核 v2 · P1 首次真机验证：**风险点排除，但控制面撞上"ART 运行时与 SDK 桩不一致"**（模块 1.0.38）

**一、真实结果（1.0.37 装上、重启后，2026-09-18 16:03）**

| 清单项 | 实测 |
|---|---|
| ① `state.json` | ✅ 在（`phase=idle`、`generation=0`、`owner=none`） |
| ① `control.sock` | ❌ **不在** —— 上一轮 P1 欠的就是这条 |
| ③ `sunsetd.log` | ❌ `控制面启动失败：No static method open(Ljava/net/ProtocolFamily;)Ljava/nio/channels/ServerSocketChannel; …` |
| ④ `service.log` | ✅ `内核：已在后台发起 sunsetd`（新路径生效） |
| ⑤ `linuxctl status` | ✅ v1 键一个不少 + `kernel:{online:true,phase:"idle",generation:0,owner:"none",job:null}`（P1-d 真机通过） |
| ⑥ 心跳过期回退 | 未测（被 ①③ 挡住） |

**结论（重要）**：P1 唯一没验证过的风险点 —— **`app_process` 起内核的 SELinux 域 / ART 兼容性 —— 通过了**。
内核真的跑起来了、Kotlin 代码执行了、状态与心跳都在写。失败点只在**控制面 socket**。

**二、根因：这台设备的 ART 运行时**没有** `ServerSocketChannel.open(ProtocolFamily)`**

不是猜的：把真机 `/apex/com.android.art/javalib/core-oj.jar` 里的 `classes.dex` 抽出来，
按 dex 的 `method_ids`/`class_data` 逐条 dump（本机 python 解析，`dexdump` 是 x86_64 静态二进制、在设备上跑不了）：

| 类/方法 | 真机 core-oj.jar | SDK 桩 `android-35/android.jar` |
|---|---|---|
| `ServerSocketChannel.open()` | ✅ | ✅ |
| `ServerSocketChannel.open(ProtocolFamily)` | ❌ **没有** | ✅ **有** |
| `SocketChannel.open(SocketAddress)` | ✅ | ✅ |
| `SocketChannel.open(ProtocolFamily)` | ❌ | ✅ |
| `java.net.UnixDomainSocketAddress` / `StandardProtocolFamily` | ✅ | ✅ |
| `sun.nio.ch.UnixDomainSockets`（connect/bind/accept/socket/…22 个方法） | ✅ **实现是齐的** | — |
| `sun.nio.ch.ServerSocketChannelImpl` | ✅（有 `(SelectorProvider, FileDescriptor, boolean)` 构造器） | — |

⇒ Android 的 NIO 只给了 unix 域的**客户端**半边，**服务端入口被拿掉了**。

**为什么"34 条内核单测全绿"没挡住**：单测跑在桌面 JVM（JDK 17）上，那里**有** `open(ProtocolFamily)`；
而 `android.jar` 是**平台桩**，与设备实际安装的 **ART apex 模块**可以不一致（这台就撞上了）。
**通用教训**：*"照 SDK 桩写代码 + 在桌面跑单测"对"设备运行时 API 面"是没有发言权的* ——
要么真机自检，要么按设备 dex 静态核对。

**三、修法（模块 1.0.38）**

1. 控制面**通道**与协议分层（新增 `app/sunsetd/.../Transport.kt`）：
   `Duplex`（一条连接的两端）+ `ControlListener`（能 accept 的监听端），协议代码两边**同一份**。
2. **能力探测选通道**（可注入 ⇒ 真机情形能在 JVM 单测里断言）：
   有 `ServerSocketChannel.open(ProtocolFamily)` ⇒ `nio-unix`（桌面单测）；
   否则 ⇒ **`android-local`**：`LocalSocket.bind(FILESYSTEM 路径)` + `LocalServerSocket(fd)`（内部 `listen`）
   + `Os.chmod 0600`，accept 出来的是带 Java 流的 `LocalSocket`。
   依据按 AOSP 源码逐条核对（`LocalSocketImpl.bind → bindLocal`、`LocalServerSocket(fd) → listen(50)`、
   对外部 fd **不负责关闭** ⇒ 关 fd 的是创建它的 `LocalSocket`，`close` 才能解开阻塞的 `accept`）。
   全部是**公开 SDK API**，没有 hidden API 风险。
3. **开机回环自检**：内核起来后连自己、发一帧 `ping`、读回一帧，结论落盘：
   `run/control-transport`（选中哪条）+ `run/control-selftest`（`running` / `ok:<通道>` / `fail:<原因>`）。
   异步跑（主循环一秒都不被挡）。两条通道都起不来时，诊断文件与日志写清**每条各自的失败原因**。
4. 真机 dex 里**没有任何 `android.net.*` 引用**（纯 `Class.forName` 字符串）⇒ 不会再有"链接期才发现缺方法"。

**四、这一轮的验证（都在本机做完，不靠 CI 不靠真机）**

- 内核单测 **34 → 42 / 0**：新增"真机情形（nio 缺服务端）⇒ 选择器必须走 android-local 并说清原因"、
  "两条都缺 ⇒ 原因两条都在"、"nio 被占用 ⇒ 自动退下一条"、"探测口径就是 `open(ProtocolFamily)`"、
  "回环自检在真 socket 上得到 `ok:nio-unix`"。
- **`android.net` 测试替身**（`src/test/kotlin/android/net/*`，按 AOSP 源码语义写）：
  让 android-local 通道在 JVM 上**整条跑通** —— 反射管道（类名/方法/构造器/字段/静态性）、
  bind→listen→accept、收发帧、`close` 解开阻塞中的 `accept`、socket 是 `0600`。
  替身**不进产物**（已核对 dex 里没有该类，也没有 android.net 的 method_id）。
- 模块自测 41/0；两个变体的 zip 里 `bin/sunsetd.dex` 与本地构建 sha256 严格一致
  （`99a7833b…`，2,508,864 B）。

**五、交付与下一轮第一件事**

交付：`/sdcard/Download/sunsetlinux-module-1.0.38-bare.zip`（1,060,692 B）/ `…-1.0.38.zip`（50,885,375 B）
+ 《本轮交付说明-模块1.0.38-控制面修复.md》。

下一轮第一件事：**收 1.0.38 的真机结果** —— `cat run/control-transport`（期望 `android-local`）、
`cat run/control-selftest`（期望 `ok:*`）。两条都对 ⇒ P1 主线收口，进 P2（`submit` 作业模型接到 App）。
若 `control-selftest` 是 `fail:`，日志里已有每条通道的失败原话（含 SELinux/权限），照原话继续。

### 3.10.47 收束（P1 收尾 + 把"在不在跑"的第 N 份实现删掉 + 修 CI 不变式）

**一、`start.sh` 的幂等判断也改成"内核在线时以内核为准"**（本轮删掉的又一份实现）：

- 内核状态读取抽成 **`runtime/common/kernel-state.sh`**（`linuxctl` 与 `start.sh` **共用一份** ——
  这正是要治的病：v1 里同一件事四五处各自推导）。打包清单 `BIN_COMMON` 同步加了它
  （上一轮新加的"common 脚本必须全部进包"闸门自动盯着这件事）。
- `start.sh` 在 `running_ns_pid` 之前先问内核：内核说 `running` ⇒ 直接 `exit 0`（不重复启动）；
  心跳过期/内核不在 ⇒ 照旧走 v1 判据。
- 回归：`selftest.sh` **114 → 116/0**（bash 与 mksh）：新增"内核说 running ⇒ start 直接返回"。

**二、修 CI 的一处不变式（这轮实跑踩到，六个编译节点全红）**：

| 现象 | 根因 | 修法 |
|---|---|---|
| `③ 编译 *` 六个节点全红在"取回内嵌离线包（bundles）"，而 `② 内嵌离线包` 被跳过 | 编译节点是否去取 `offline-bundles` 制品看 `embed_bundles`（默认 true），而 ② 是否产出它看变更集的 `build_bundles`（"没改离线包输入就不重打"的优化）。**两者不一致** ⇒ 去下载一个不存在的制品 | prep 里加不变式：**要编 APK 且要内嵌离线包 ⇒ 强制 `build_bundles=true`**。代价约 40 秒，比整轮红或"发出不带环境的 APK"（0.2.6 事故）都便宜。注意这里**回读 `$GITHUB_OUTPUT`**，不用 `steps.<当前步>.outputs`（同一步读自己的 outputs 是空串，条件永远为假 —— 第一版就是这么写的） |

**二·补：这处修复的第一版自己又踩了一个坑（已修，并留档）**

第一版在 plan 步里用 `_out_get embed_bundles` 读上一步写的输出 —— **读不到**：
`GITHUB_OUTPUT` 是**每步一个文件**，同一步里只能看到**自己这一步**写进去的东西。
`grep` 返 1，而该步是 `set -euo pipefail` ⇒ 整步退出 1，① 环境准备直接红。

修法：`embed_bundles` 走上一步的 outputs 表达式（`steps.norm.outputs.embed_bundles`），
读自己 outputs 的地方一律 `|| true` 容错。**本地已用真实 changeset + 真实 git diff 端到端复现过**：
输出里能看到 `build_bundles=false` → `::notice::…强制重打离线包` → 最终 `build_bundles=true`。

> 留档一条通用事实：**Actions 的 `GITHUB_OUTPUT` 是每步独立的文件**，别在同一步里回读上一步的输出。

**三、决策记录成文件**：新增 **`docs/decisions-core-v2.md`** —— 产品级（D1–D5，含 D1 的复核过程）、
架构级（A1–A9）、实现级（B1–B9）、CI 侧（C1–C2）、被否决选项、留给下一轮的开放问题。

**四、本轮交付**：`/sdcard/Download/sunsetlinux-module-1.0.37.zip`（50,875,146 B，含 `bin/sunsetd.dex`）。
**App 不用换**（v1 契约兼容）。

### 3.10.46 内核 v2 · P1 后半：`status` 的相位以内核为准（模块 1.0.36）+ 修 CI 的保守重发兜底

#### 一、`linuxctl status` 变成瘦客户端（App 无感）

`gather_status` 里加了一层**覆盖**而不是"再来一份判定"：

| 内核相位 | v1 `state` |
|---|---|
| `preparing` / `mounting` / `starting` | `starting` |
| `running` / `degraded` | `running` |
| `stopping` | `stopping` |
| `failed` | `error` |
| `idle` | `stopped` |

- **内核在线**（`run/state.json` + `run/heartbeat` 在 **15 秒**内）⇒ 相位以内核为准；
- **内核不在或心跳过期** ⇒ 自动退回原来的 v1 标记判据（**绝不拿过期状态冒充**）；
- 新增附加键 `kernel:{online,phase,generation,owner,job}`（`contract-check.mjs` 已确认"附加键允许"，
  既有键一个没动 ⇒ App 不需要更新）。

心跳是**毫秒**、而 mksh 的算术是 32 位（真机踩过 §3.10.25）⇒ 取秒用**字符串截断**（`${ts%???}`），
不做乘除。

#### 二、回归

- `runtime/root/selftest.sh` **108 → 114/0**（bash 与 mksh 都跑）：新增一组"内核覆盖"断言 ——
  内核说 `mounting` 时 v1 `state=starting`（**压过**标记的 running）、`failed → error`、
  `idle → stopped`、`kernel` 块的 online/generation/owner 来自内核、v1 六个契约键一个不少；
  "退回 v1 判据 + 心跳过期不冒充"两条依赖 `/proc/<pid>/ns/mnt`（proot 沙箱读不到 → 本机 SKIP，
  **CI 与真机会跑**）。
- `tools/contract-check.mjs` 通过，并把 `kernel` 识别为允许的附加键。
- 模块自测新增两条静态闸门：**入口类名一致**（`service.sh` 写的 `…sunsetd.Main` 必须真的是
  jar/dex 里的那个类 —— Kotlin 的 `object Main` 编译出来是 `Main` 而**不是** `MainKt`，
  写错一个字母真机表现只是"内核没起来"，要多花一轮）+ "给 dex 必进包 / 没给要出声"。

#### 三、顺手修的 CI 隐患：站点取不到时的"保守重发"

§3.10.45 记的那次"同 tag 的 16 个资产被改写"**已查实原因**：prep 从**站点** `stable/index.json`
取"上次发布的版本"，取不到就"保守当作全都变了"（`ci-changeset.mjs` 的设计选择）。
那次站点 curl 失败 ⇒ `versionBumped=true` ⇒ 整轮重发（1.3 GB）。修法：
站点取不到时先退到 `https://github.com/<repo>/releases/latest/download/index.json`
（同一次发布写出去的等价文件），再不行才保守。**核版本请看 `index.json.commit`，别假设 tag 不可变。**

#### 四、本轮交付与"还没验的"

- `/sdcard/Download/sunsetlinux-module-1.0.36.zip`（50,873,368 B，含 `bin/sunsetd.dex`）。
  **App 不用换**（v1 契约兼容）。
- **真机未验**（P1 唯一风险点）：`app_process` 起内核的 SELinux 域、socket 能否建。
  验证清单在交付说明里（6 条），失败时的两种日志（"缺 dex" vs "没起来"）已经写清；
  **两种情况下行为都完全按 v1**（回退是设计的一部分）。

### 3.10.45 内核 v2 · P1 前半：`sunsetd`（Kotlin/app_process）成为唯一状态机 + 控制面 + 打包管道

按 `docs/core-v2-design.md` 的 P1 开工。**本轮不发布**（内核还没在真机上跑过；改动没升版本 ⇒
CI 只会跑门禁并给出"改了产物相关文件却没升版本"的警告，不会静默发半个东西 —— 这正是我们要的）。

#### 一、这轮落了什么（都可复核）

| 产物 | 证据 |
|---|---|
| `app/sunsetd/`（新的 `:sunsetd` 纯 JVM 子项目） | `KernelModel.kt`（Phase/Owner/Backend/SessionState + 迁移表）、`Json.kt`（零依赖小 JSON）、`World.kt`（v1 标记只读快照 + 真机/桩两套）、`Kernel.kt`（**唯一状态机** + 作业）、`ControlServer.kt`（`run/control.sock`）、`Main.kt` |
| 内核单测 | **34 条全过**（`KernelModelTest` 14 / `KernelTest` 14 / `ControlServerTest` 6）。控制面测试跑的是**真 unix socket** |
| 打包 | `tools/build-sunsetd-dex.mjs` → `build/sunsetd/classes.dex`（**2,482,240 B**，含 kotlin-stdlib；`--min-api 26`）；缺 d8/stdlib/gradle 一律**报错**，不静默 |
| 模块 | `mkmodule.sh` 内嵌 `bin/sunsetd.dex`（有则进包、无则**出声**"本包不含内核 v2"）；`service.sh` **先起内核再发起 `linuxctl start`**（带 dex/app_process/setsid 三重存在性检查；起不来只记一行日志，行为回退 v1，不阻塞 boot） |
| CI | `ci.yml` 的 App 门禁加 `:sunsetd:test`；`pipeline.yml` 打包模块前构建 dex 并断言"有 dex 就必须在包里" |

#### 二、这轮改掉的**语义**问题（都是写单测时才暴露的）

| 现象 | 根因 | 定案 |
|---|---|---|
| 建树期间相位不可见 | v1 里"正在启动"没有一等状态 | 内核相位 `preparing/mounting/starting/stopping` 全是一等；App 的启动卡按它置灰（不再靠 `run/start.lock` 这种补丁） |
| 连点两下建两棵树 | 没有互斥、没有幂等 | 内核内建：同一动作重复提交返回同一 `jobId`（**只起一条命令**），不同动作 → `busy` + 在跑的作业 id |
| "环境已 running 但命令还在收尾"被判成非法 | 不变量写得过死（`busy ⟺ jobId`） | 改成单向：忙相位必须有归属；反向只允许"收尾"（这正是真机那个 20 分钟窗口的形状） |
| 一次失败被下一次 tick 擦掉 | 世界驱动迁移无条件执行 | FAILED 变**粘的**：一次失败的启动不许自己回 idle；同一次 tick 里也不许被世界覆盖 |
| 模块开机自启的会话被当成"没在跑" | 判据只认内核自己的作业 | 新增 `Owner.FOREIGN`：内核**认领**观察到的会话（`running` + owner=foreign-observer，世代 ≥1） |

#### 二·补：本轮推上去之后 CI 暴露的两件事（**都不是内核代码问题**）

| 现象 | 证据 | 性质 |
|---|---|---|
| **v0.3.14 的 16 个资产被同一 tag 全部改写** | `dad3512` 那次 run（`35308217461`）全绿，④ 含 `:sunsetd:test`；随后 release 与站点的 16 个资产/manifest 都变成 `commit=dad3512`，而 `version.properties`/`module.prop` 一字未动 | **已查实**：不是"版本规则被绕过"，而是 §3.10.39 自己写的兜底 —— prep 步骤从**站点** `stable/index.json` 取"上次发布的版本"，取不到就"保守当作全都变了"。那次站点 curl 失败 ⇒ `versionBumped=true` ⇒ 整轮重发（1.3 GB + 同 tag 字节改写）。**已修**：站点取不到时先退到 `releases/latest/download/index.json`（同一次发布写出去的等价文件），再不行才保守。副作用与可追溯性：`index.json.commit` 一直是对的，按 tag 核 sha256 的人应该改看这个字段 |
| **我加的 dex 构建步骤把失败吞了** | 发布出去的 `sunsetlinux-module-1.0.35-bare.zip`（277,804 B）里**没有** `bin/sunsetd.dex`；而 CI 里那一步是 `node tools/build-sunsetd-dex.mjs \|\| echo "::warning::…"` | ② 那个 job 没有 Android SDK/d8（d8 在 App 构建 job 那边），脚本按设计 `exit 1`，却被 `\|\|` 吞成警告 —— **又是一次"静默降级"**，正是我这两轮一直在骂的毛病。碰巧的结果是"未验证的内核没被发出去"，但这是运气不是设计。**下一轮要修**：把 dex 构建放到有 SDK 的 job（或装 SDK），并让"应当带内核"成为**硬断言**（内核真机验证通过之后） |

#### 三、还没做的（P1 剩余，按优先级）

1. **真机首次实跑**：`app_process` 起内核的 SELinux 域、socket 能否建、状态文件是否按预期刷 —— 这是 P1 **唯一未验证**的风险点（失败可回退 v1）；
2. `linuxctl status` 变瘦客户端（v1 JSON 逐键一致，`contract-check.mjs` 两路都跑）；
3. 删掉"在不在跑"的重复判据（`start.sh:running_ns_pid`、`linuxctl:ns_pid_alive/env_ready`、App 推导）；
4. 版本号与交付（模块/APK 直送 `/sdcard/Download`）。

### 3.10.44 泄漏到宿主 ns 的挂载：`make-rprivate` **从来没生效过** + 残留清理 + 打包清单漏文件（模块 1.0.35 / App 0.3.14）

接 §3.10.43 的第四条（用户："已经有挂载了呀"）。这一轮把它拆到底。

#### 一、实测到的三个硬事实

| 事实 | 证据 |
|---|---|
| 我们的挂载**出现在宿主 init 的挂载表里**（33 条） | `grep -c sunsetlinux /proc/1/mountinfo` = **33**；条目形如 `40219 40210 7:392 / /data/sunsetlinux/upper … ext4 /dev/block/loop49`（父 id 40210 = init ns 里的 `/data`） |
| 环境与宿主是**两个 mount ns** | `ls -l /proc/self/ns/mnt` = `mnt:[4026536053]`（root shell 那层）≠ `/proc/1/ns/mnt` = `mnt:[4026532884]` |
| `make-rprivate` **一次都没成功过** | 外层唯一的那次尝试（`start.sh` 旧第 1464 行）在挂载树之前，而能执行它的 util-linux 在 `detect_util_mount`（挂载树里、旧第 1072 行）**之后**才取到 ⇒ `UTIL_MNT_BIN` 那时必空，兜底那一支根本没执行（日志只有一句"未成功"，没有"尝试过 util-linux"的痕迹） |

> ⚠️ **诚实标注**：为什么在 root shell 那层 ns 里看到 `/` 是 `master:1`、`/data` 是 `master:60`
> （看着像 slave、不该往上传播）的情况下，我们的挂载仍旧落在 init 的挂载表里 —— 这一点我**还没定论**。
> 两个候选：(a) 传播链上仍有一层是 shared（root shell 那层 ns 由 KernelSU 建，属性可能与看到的这份不同）；
> (b) 某次运行的守护进程其实没有成功进入新 ns。**本轮的修复不依赖这个结论**：make-rprivate 现在真的会跑
> （若原因 (a) 成立，泄漏就此止住），而残留清理对两种原因都有效。

#### 二、泄漏带来的两个真实后果（都是用户看得见的）

1. `stop` 之后宿主里仍留着我们的挂载 ⇒ 看上去像"已经有挂载了"（但那是**已死守护进程的私有 ns** 留下的壳，不可复用 ⇒ 下次 start 重建是**对的**）；
2. `cleanup_stale_loops`（`start.sh:740`）扫描 `/proc/[0-9]*/mountinfo`，见到 loop 还挂在**别的 ns** 里就 `跳过 detach` ⇒ **loop 残留永远清不掉**，日志一直 WARN（用户看到的"loop 49~57 残留"）。

#### 三、修法（两条腿）

| 改动 | 说明 |
|---|---|
| `start.sh` 新增 `ensure_ns_private()`，**在 `detect_util_mount` 之后立刻调用**（挂载树里、overlay/proc/sys/dev 之前） | 顺序：toybox `--make-rprivate /`（实测不支持，保留"万一将来支持"）→ **base 层 util-linux** `--make-rprivate /` → 退一步只把 `/data` 设私有。并且新增 `ns_is_private` / `ns_private_evidence`：**用 mountinfo 打一行可核对的结论**（`shared:` = 会回流、`master:` = 安全），不再只说一句"未成功"。外层那次保留但改成"等 base 层挂上后再试"的提示，不再谎报"永远失败" |
| 新增 `runtime/common/host-residue.sh` | 把泄漏到**别的 ns** 的挂载清掉：当前 ns 直接 `umount -l`；init ns 走 `nsenter --mount=/proc/1/ns/mnt -- umount -l`（只碰 `/data/sunsetlinux` 下我们自己的已知路径，子路径在前）。调用点两处：`stop.sh` 正常卸载失败后的兜底（`verify_clean` 带参数防重入）、`start.sh` 开工前（在"确认没有活着的环境 + 已拿到启动锁"之后 —— 否则会拆掉正在用的环境） |

#### 四、顺带抓到的第三个 bug：**模块包漏文件**

`module/mkmodule.sh` 的 `BIN_COMMON` 是**硬编码清单**，新增 `runtime/common/host-residue.sh` 后没人改它
⇒ 打出来的包里 `bin/common/` 只有 5 个文件，而这个新文件被 `start.sh`/`stop.sh` source
⇒ 真机上"宿主残留清理"**整块静默失效**。已在 `tools/module-variant-selftest.mjs` 加闸门：
`runtime/common/*.sh` 里每一个都必须出现在包的 `bin/common/` 下；变异测试实测（把清单里那项删掉）**会红**。

#### 五、回归

- `runtime/root/selftest.sh` **101 → 108/0**（bash 与 mksh）：新增"宿主残留清理"一组 ——
  用**桩**替 `umount`/`nsenter`（自测绝不真动宿主挂载表），断言"该卸的卸（当前 ns + init ns 两条路）/
  不碰不属于本项目的路径（`/data/other-thing`）/ 动作有日志"，外加两条接线断言
  （start 与 stop 都接了清理）与一条**顺序**断言（`ensure_ns_private` 必须在 `detect_util_mount` 之后）。
- `tools/module-variant-selftest.mjs` **36 → 37/0**（新增打包清单完整性一条）。
- `tools/shell-compat-check.mjs` 绿（这批探针里的关键词一律走拼接，见文件内注释）。

### 3.10.43 「脚本似乎在尝试重复挂载？」—— 建树期间**没有"正在启动"这个中间态**，第二次 start 各建一棵树（模块 1.0.34）

用户看完 §3.10.42 的修复后继续追问（原话）：

> 「很奇怪，挂载不是正常状态吗？脚本似乎在尝试重复挂载？」
> 「启动的时候，默认重建挂载？已经有挂载了呀……挂载判定也没有接上？挂载上来就直接启动环境啊？
> 为什么在重新启动环境的时候重新挂载呢？」

#### 一、正常设计确实是"有就不重建"，判定链只有一条

| 判据 | 位置 | 含义 |
|---|---|---|
| `running_ns_pid` | `start.sh:434` | `supervisor.pid` 里的 pid 活着 + `/proc/<pid>/ns/mnt` 可读 |
| `ready` | `run/ready` | 守护进程已把挂载树建完、交给 `entry.sh` |
| 结果 | `start.sh` 默认分支 | 两者都在 → 打印「环境已在运行（ns_pid=…），无需重复启动」并 `exit 0`，**一个 mount 都不做** |

所以"有挂载就重建"不是设计。**但这条判据只认识"已就绪"**，见下。

#### 二、真机那两棵树是怎么来的（03:39 现场）

```
03:37:33  模块 service.sh 开机自启（full，pid 4190，DSH 都起来了）—— 这条 start 已能按时返回（§3.10.42 的修复生效）
03:39:02  一次 stop.sh：停止完成，但 loop 49~57 残留（日志自己写了 WARN）
03:39:16  start --no-dsh → inner pid=31547 开始建树 …… 到 03:40:08 才 ready（≈50 秒）
03:39:36  又一次 start --no-dsh → inner pid=4672：此刻 31547 还没写 supervisor.pid/ready
          ⇒ running_ns_pid 返假、status 报 stopped ⇒ **又完整建了一棵树**
          ⇒ run/mounts 13 → 26 条；两条守护链同时活着；dsh start 报"进不去环境"
```

结论：**缺的不是"挂载判定"，是"正在启动"这个中间态**。建树要 30~50 秒（loop + erofs +
overlay + chroot），这期间 `supervisor.pid`／`ready` 都还不存在，而 `gather_status` 的
`starting` 也要求 `ns_pid_alive`（同一个 pid 文件）⇒ 状态被报成 `stopped` ⇒ App 的启动卡
看着"没在跑"（`transitional` 置灰没生效），用户再点一次就各建一棵树。

#### 三、修法：一把启动锁 + 让 `starting` 真的可见（模块 1.0.34，App 不用改）

| 位置 | 改动 |
|---|---|
| `start.sh` | 新增 `run/start.lock`（内容 `<pid> <epoch>`，`set -C`/noclobber **原子**占有）：`start_lock_acquire/start_lock_holder/start_lock_release`。拿不到锁时：持锁进程活着 → **只等它就绪（≤90s，`START_WAIT`），绝不重复建树**；锁陈旧（进程已死）→ 接管。占有后挂 `EXIT/INT/TERM/HUP` trap 释放 |
| `start.sh`（inner） | 写完 `supervisor.pid` + `ready` 后立刻删锁 —— 之后由 `running_ns_pid` 正常挡住重复启动（也盖住"外层已超时退出、建树还在继续"） |
| `linuxctl.sh` | `gather_status` 新增 `start_lock_holder`：没就绪但**有人正在建树** → `state=starting`。App 按 `transitional` 把启动卡置灰，点不动 = 不会再触发第二个 daemon |
| `stop.sh` | 两条清理路径都删 `run/start.lock`（"停止"必须是那个能解开的出口） |
| `selftest.sh` | 原语做**行为**断言（锁被占时第二次拿不到 / holder 指向真正的持锁进程 / 持锁进程死后 holder 失效 / 释放后可重新占有）+ 真跑 `status` 验"只有锁时 `state=starting`" + 4 条静态接线断言。**88 → 101/0**（bash 与 mksh） |

#### 四、仍然存在、且是"看着像已有挂载"来源的一件事（未修，需你定优先级）

设备日志里那句 `make-rprivate 未成功（toybox 无此选项）` 是**真的没生效**：
这台机 `/` 或 `/data` 是 `shared:`，而 toybox 没有 `--make-rprivate`，base 层那份 util-linux
`mount` 那次回退也没成功 ⇒ **守护进程的挂载会回流宿主 ns**。后果：

- `stop` 之后宿主这边仍能看到 loop/挂载残留（日志里的 WARN 与"跳过 detach"就是这么来的），
  看上去像"已经有挂载了"；
- 但那棵树**已经不可用**：它属于已死守护进程的私有 mount ns，而 `dsh start` 需要的是
  "活的 ns + ready" ⇒ 下次 start 重建是**必要**的，不是浪费。

要真正消掉这种观感，得做（按性价比排序，**下一轮候选**）：
1. 修 `make-rprivate`（查 base 层 util-linux `mount` 为什么没接上；不行就显式
   `mount --make-rprivate /data` 兜底）——宿主残留的根；
2. `stop` 时对宿主侧残留挂载/loop 做一次显式清理（现在只 WARN + 让人手动 `losetup -d`）；
3. `start` 前若发现**宿主 ns 里有本项目残留挂载**，先清干净再建（防叠加）。

### 3.10.42 「点了启动环境，然后就没了，点不了启动 DSH」：**命令卡住把整个启动区锁死** + 让路端口不落盘（模块 1.0.33 / App 0.3.12）

**真机现场**（2026-09-18 02:49，App 0.3.11 + 模块 1.0.32）：按「分步启动 → 仅启动环境」之后，
操作卡里**五张卡片全部 0.4 alpha 置灰**、「启动 DSH」点下去**毫无反应、也无报错**。
用户原话两句：「点了启动环境，然后就没了，点不了启动 DSH」「找到问题了，端口被占，没有写偏移」。

#### 一、先证伪了"按钮矩阵错了"

截图里 `StopControls` 的矩阵**是对的**（`env-only + DSH 没跑` ⇒ 「启动 DSH」该亮、note 也该是
"点它接上"）。两个反证：

| 观察 | 结论 |
|---|---|
| 同一屏里「打开 DSH」的 label 亮度 p99=**42**（灰），「更新」p99=**255**（亮）—— 一张 ActionTile 对照组证明"灰/亮"在像素上可分 | 四张启动卡片的值**完全一致**（p99≈103~113），不是"各有各的对错"，是**整块被同一个开关关掉** |
| 四张里「停止环境」在矩阵里**任何 running 分支都为真**（`envStopEnabled=true`） | 它也灰 ⇒ 排除"矩阵判错"，只剩 `busy` 这一个闸门 |

#### 二、真正的根因：`linuxctl start` **永不返回**（设备实证）

App 的 `runAction` 把 `busy` 置真，**等 `su -c '… linuxctl start --no-dsh'` 返回**才复位。
而那条命令在设备上根本没返回过。证据（全部只读取得，`ps` + `/proc`）：

| PID | 命令行 | 20 分钟后 |
|---|---|---|
| 3172 | `sh -c 'PATH=…; linuxctl start --no-dsh'`（App 的 su 进程） | 活着，`rt_sigsuspend` |
| 3175 | `sh /data/sunsetlinux/bin/linuxctl start --no-dsh` | 活着，`rt_sigsuspend` |
| 3224 | `sh …/start.sh --layer-mode loop --no-dsh` | 活着，`rt_sigsuspend` |
| 3455 | `sh …/start.sh --inner --make-private` | 活着，`rt_sigsuspend` |
| 4624 | `/bin/bash /opt/sunsetlinux/entry.sh 3080 --no-dsh` | 活着，`do_wait` + 一个 `sleep` |

`rt_sigsuspend` = shell 在**等子进程**；链底是 `entry.sh --no-dsh`，而它**按设计永不退出**
（`while :; do sleep 1; done`，full 模式是 `exec supervise.sh`）。也就是说：
**`start.sh` 起守护进程时用了前台调用，父 shell 只好一直等这个"永不退出的孩子"**，
于是 `start.sh` → `linuxctl` → App 的 `su` 全不退，`busy` 永久为真，
`if (_ui.value.busy) return` 把之后每一次点击**静默丢弃**（这就是"点了没反应"）。

> 顺带钉住一条：**环境本身是好的** —— `run/ready` 在 4 秒时就已写出、`supervisor.pid=3455`、
> 挂载树 13 项齐全。"环境好了、按钮却全灰"是这个 bug 的典型脸。

**修法（两层，缺一不可）**：

| 层 | 改法 |
|---|---|
| 设备侧 `runtime/root/start.sh` | 新增 `spawn_daemon()`：`( spawn_detached … >>"$DAEMON_LOG" 2>&1 </dev/null & )` —— 后台子 shell 让守护进程被 init 收养，父脚本立刻返回；`</dev/null` 还回继承来的 stdin（真机上那条 fd0 就是 App 的 su 管道）。同时**删掉 `--fork` 那一支探测**：它存在的唯一理由是"拿前台 spawn 的退出码猜 unshare 认不认这组选项"，正是祸根；现在按 `unshare_propagation` 探测二选一，成功判据只剩 `run/ready` |
| App 侧 `ui/LauncherViewModel` + `core/ActionTarget.kt` | ① `busy` 改成 `finally` **无条件复位**（以前只在成功/失败分支复位，抛异常或协程取消就永久为真）；② 新增纯函数 `ActionTarget`：每次动作带一个目标状态（环境 running / 已停止 / DSH running / 已停止），**状态轮询一旦观测到它达成，就立刻解锁** —— 哪怕命令还在跑也不再锁死界面（解锁后按钮矩阵按设备真实状态重算，不会放出不该点的按钮） |

#### 三、第二件事：让路的端口**没有落盘**（用户说的"没有写偏移"，成立）

设备上 `127.0.0.1:3080` 是 **DSHA**（另一个环境）在 LISTEN（`/proc/net/tcp` 里
`0100007F:0C08 st=0A uid=10497`），3081 空着。代码里"让路"是有的（`port_pick_free`），
但**落盘时机错了**：`run/dsh.port` 只在 `parse_url_stream` 抓到带令牌 URL **之后**才写
（`supervise.sh` 原第 201 行）。DSH 起得慢或起不来时 ⇒ 端口文件不存在 ⇒ App 与用户都看不到
"偏移到 3081"这件事，界面表现成"什么都没有"。

改法：`supervise.sh` **一启动就把请求端口写进 `run/dsh.port`**（URL 就绪后仍以 URL 里的实际端口覆盖；
`shutdown`/正常退出路径照旧删除，不留过期值）。顺带把 `start.sh` 外层那句旧口气的 WARN
（"dsh 可能自行换端口"）改成"环境会让路到空闲端口（实际端口看 run/dsh.port）"——
第 43 轮记的那条文案欠债一并还掉。

#### 四、回归（都能在容器里跑）

| 门禁 | 结果 |
|---|---|
| `tools/shell-compat-check.mjs` 新增**前台 spawn 闸门** | 7 组"必须抓到 / 不许误报"样本 + 自检；**变异测试实测**：把调用退回前台写法 → 判红并指出 `start.sh:1528`；改回 → 绿 |
| `runtime/root/selftest.sh` 新增**行为级**断言 | 从真实脚本抽出 `spawn_daemon`，配一个"永不退出的守护"计时：**必须在 5s 内返回**；另加一条**对照样本**证明"前台 spawn 确实会等"（否则这条断言可能是空转）。总计 **86 → 88/0** |
| App 单测 | 新增 `ActionTargetTest`（7 条：每个目标正反两面 + 动作→目标映射穷举 + NONE 永不达成）；`busy` 的 `finally` 复位无法用 JVM 单测覆盖（AndroidViewModel），因此判定逻辑全部推给了纯函数 |

**没做但值得知道的**：`run/dsh.port` 里现在写的端口，在 DSH 真的起来之前是"**请求值**"
（可能是让路后的 3081）—— 这是有意的：让"让路"可见比"等到 URL 才敢写"更有用；
真正的权威值仍是 URL 里的端口，URL 一到就覆盖。

### 3.10.41 修完推上去才暴露的阻塞：⑤ 的 Release 上传挂住 1 小时+ ⇒ 改走「本地打包 → 直送手机 Download」（2026-09-18）

**现象**：`454ed75`（模块 1.0.32）推上 main 后按并发组排队，前面 `d7a2795`（App 0.3.11 / 模块 1.0.31）那次
从 **17:50:37Z** 起停在 ⑤「发布到 GitHub Releases」，一小时多没有变化。`githubstatus.com` 全绿 ⇒ 不是平台故障；
**也不是仓库的问题**（删库重建照样会在下一次 push 遇到同一个动作）。判据：

| 观察 | 说明 |
|---|---|
| `git ls-remote --tags` 只有 `v0.3.10`(→`8fe892e`)，**没有 `v0.3.11`** | publish.yml 是"先建草稿 → 逐个传资产 → 最后 `--draft=false`"，草稿对匿名 API 不可见 ⇒ 它确实在传 v0.3.11 的草稿，人看不到进度 |
| 该步骤开始时间一直不变、Release 对象无变化 | 不是"跑得快慢"，是这一步在长时间上传/重试 |
| publish.yml 自己的注释 | 一轮 **1.3 GB**（12 个 APK ≈500 MB + 6 个 `.bin` ≈430 MB）；串行实测 33 分钟，3 路并发压到约 1/3 |

**三条必须记住的 Actions 语义**（这次全踩到）：

- 并发组 `pipeline-<ref>-<event>` + `cancel-in-progress: false`：**组内"还在排队"的旧 run 会被新 run 取消**
  （`8fe892e` 那次 pending run 在 18:20:58 我推 `454ed75` 的那一刻被自动取消）。
- **`workflow_dispatch` 是独立队列**（组名带 `event_name`）⇒ 手动 Run workflow 不会被卡住的 push run 挡住。
  ⚠️ 但它和卡住那条写**同一个 Release**：卡住那条若复活跑完，末尾"撤掉非当前版本的模块资产"会删掉新发的
  **1.0.32 模块 zip** ⇒ **先强杀，再手动跑**。
- UI 的 Cancel 是**协作式**的：runner 卡在网络调用里时不响应，点多少次都没用；仓库没有 `timeout-minutes`
  覆盖 ⇒ **默认 6 小时**上限兜底（约 23:50Z 被强杀）。要立刻清掉只能用 API
  `POST /repos/<o>/<r>/actions/runs/<id>/force-cancel`（本机实测端点存在：未鉴权返回 401），需 `Actions: write` 的 PAT。

**解困路径（本轮实际用了，不依赖 Release）**：容器能直接写设备共享存储（实测 `/sdcard/Download/` 可写、
设备侧 `ls` 也看得见）⇒ 本地打包 + 直送：

```bash
bash module/mkmodule.sh --version 1.0.32 --variant full \
  --dsh-layer dist/dist-backup/dsh-0.1.5-rc.2.erofs.gz \
  --dsh-sums  dist/dist-backup/SHA256SUMS.dsh-layer.txt --out /tmp/sl/sunsetlinux-module-1.0.32.zip
bash module/mkmodule.sh --version 1.0.32 --variant bare --out /tmp/sl/sunsetlinux-module-1.0.32-bare.zip
cp /tmp/sl/sunsetlinux-module-1.0.32*.zip* /sdcard/Download/
```

- 产物：full **50,089,501 B**（官方 1.0.30 是 50,079,737 B，同形，层版本 `0.1.5-rc.2` 与设备现存一致）、
  bare **264,815 B**；sha256 `00b9d13cc29ac5c95b04ad11071faa4c3feb5e5e1faaa8cb2cb50ad4eda4617c` /
  `4fcaacb4b79c45b90c040832216cb8fb3d8ec5fcbb8898e717efb7541b00a2a0`。
- 已核：包内 `bin/{linuxctl.sh,start.sh,stop.sh,supervise.sh,common/port-probe.sh}` 与仓库 working tree
  **逐字节 diff 一致**；9 处 nsenter 调用全部带 `--`；`--wd=` 残留 0 处。
- **装完必须重启一次**：`bin/` 是 `module/post-fs-data.sh` 在开机时同步到 `/data/sunsetlinux/bin` 的，
  而 App 调的就是这个路径（`core/LinuxCtl.kt` 的契约路径）。

**★ 不要「删库重建」**（用户当时正在考虑）：`CHANNEL_SIGNING_KEY` 是 Actions secret，而 GitHub 的 secret
**只写不可读** ⇒ 删库等于这把 Ed25519 私钥永久消失，App 内嵌公钥之后再也验不过（把 §3.10.36 那条"签名校验失败"
从"待查"变永久）；`app/app/debug.keystore` 是仓库内的固定签名，丢了已装 App 无法覆盖升级；再加上 Releases /
gh-pages / Actions 历史全没，而**已删仓库的 run 照样会跑完** —— 既没解决"上传慢/挂"，又是一次不可逆损失。
（不放心的话本地 `git clone --mirror` 留镜像即可；`docs/**`、`**/*.md` 在 `paths-ignore` 里，
**纯文档提交不触发流水线**。）

**待改进（下一轮值得做）**：Release 的"已一致就跳过"按 `name + size + sha256` 比对，而 APK 每次重建字节都不同
⇒ 跳过逻辑形同虚设、每轮全量重传 ~1.3 GB。方向：按变体输入哈希决定"重建 + 上传"，或把 `.bin` 挪到
`layers-*` 静态托管、Release 只传 `index.json`，把一轮发布的上传量压到几十 MB。

### 3.10.40 toybox nsenter 会吃掉**命令之后**的选项 ⇒ `dsh start` / 终端 / stop 的卸载全坏（模块 1.0.32）

**真机（2026-09-18 02:00）**：环境按「仅环境」起来了、状态卡也承认"DSH 未启动"，但点「启动 DSH」永远起不来；
`run/last-error` 是那句**误导性**的「DSH 进程已拉起，但 20s 内没有打印带令牌的登录 URL」，
而 `run/linux.log` 末尾只有一行：

```
[entry.sh] entry.sh 就绪：--no-dsh（…要起 DSH 用 linuxctl dsh start）
nsenter: Unknown option 'port' (see "nsenter --help")
```

**根因**（同二进制实测，不是推测）：设备上的 nsenter 是 **toybox 0.8.12-android**，它会把
**命令之后的每一个选项也当成自己的**：

| 写法（同一台机、同一个二进制） | 结果 |
|---|---|
| `nsenter --mount=X /bin/echo --port 3080` | `Unknown option 'port'` ← 真机那条 |
| `nsenter --mount=X /bin/echo -l` | `Unknown option 'l'` ← **短选项同样中招** |
| `nsenter --mount=X -- /bin/echo --port 3080` | ✅ 不再报选项错（`--` 是终止符） |

所以这不是 DSH 的问题，而是**所有把选项写在命令后面**的调用都"整条不执行"。真机上连坏三处：

| 受害点 | 症状 |
|---|---|
| `linuxctl dsh start` → `spawn_dsh_in_env` 的 `supervise.sh --port N` | DSH 从来没被拉起来，用户只看到"没 URL" |
| 终端 `attach` / `exec`（`run_in_env`）里用户命令带 `-l`/`-c`/`--xx` | 第 41 轮删掉 `--wd=` 只是清了**我们自己的**选项；用户命令的选项照样被吞 |
| `stop.sh` / `linuxctl` 清理里的 `umount -l` / `-f` | **卸载根本没执行**（于是"残留挂载"反复出现、loop 一直被占） |

**修法**：

- **9 处调用全部在命令前补 `--`**（`linuxctl` 7 处 = spawn×2、`run_in_env`×2、ns 内卸载×3；`stop.sh` 2 处 =
  `umount -l`/`-f`），并在 `NSENTER=` 附近写清 toybox 行为与实测表；`stop.sh` 里**教用户敲的那句**
  `nsenter … umount -l` 也补上 `--`（原文案本身就是坏的）。
- **端口被占自动让路**（真机现场：免 root 的 DSHA 占着 `127.0.0.1:3080`）：**两个入口**都让路 ——
  一键启动在 `start.sh` 传给 `entry.sh` 之前选端口（这条路上端口是在那里定死的），
  分步启动在 `linuxctl dsh start`；两者共用 `port_pick_free`（在 `port-probe.sh` 里，找不到空闲端口就
  原样返回首选值，**不编造**）。实际端口由 `supervise.sh` 写进 `run/dsh.port`（登录 URL 里也带真实端口，
  App 读 URL）⇒ 换端口对上层透明。**"端口被占"不再等于"起不来"**。
- **失败文案分家**：新增 `wait_dsh_pid`（6s）——连 `dsh.pid` 都没出现 = "进不去环境/supervise 立刻退出"，
  只有 pid 出现了才可能是"20s 没 URL"。以前两者共用后一句，把"命令压根没执行"报成了"DSH 启动慢"。
- **端口判据抽成 `runtime/common/port-probe.sh`**（`tcp_listen_local` / `port_busy`）：`start.sh` 与
  `linuxctl` 共用一份（原来只存在于 `start.sh`，`linuxctl` 里没有 → 又一次"同一判断两处各写一遍"的前夜）；
  `module/mkmodule.sh` 的 `BIN_COMMON` 已带上它；proot 自测的抽取目标同步改指新库。

**回归（两道闸门，都会红）**：

- `tools/shell-compat-check.mjs`：新增"**命令前必须有 `--`**"规则（只认字面量选项，`"$@"` 这种不透明参数不猜；
  `-t PID` 的取值不再被误当成命令）；**闸门自检夹具**里原来标着"真实用法（不能误报）"的两条
  （`chroot … "$@"`、`"$UMOUNT" -l`）现在都是 `bad: true` —— 它们正是真机上必然失败的老写法，
  另加两条修好后的形状做反例。
- `runtime/root/selftest.sh`：设备上有 `/system/bin/nsenter` 时**行为级**跑实测三条（吞 `-l` / `--` 解药），
  外加**静态**断言"`linuxctl.sh` 与 `stop.sh` 里每处 nsenter 调用都写了 `--`"。本机 86/0。
- `runtime/proot/selftest-funcs.sh`：`port_pick_free` 三条**桩测**（3080~3082 全占 → 让到 3083；空闲 → 原样；
  全占且次数用尽 → 原样返回首选值）—— 用桩替换 `port_busy`，判据与端口可见性无关，CI/沙箱/真机结果一致（81/0）。

### 3.10.39 流水线优化：把 40 分钟里那 35 分钟的"发布"压下来（1+2+3 + 参数校验 + 没改的不重发）

**先实测，再动手**（用户要求"给选择 + 说清后果"，所以先把每一步的真实耗时拉出来）：

| 阶段 | 耗时 | 说明 |
|---|---|---|
| ① 环境准备 / ② 离线包 / ④ 三路门禁 | 0.1 / 0.7 / 2.0 min | ②④ 与编译并行 |
| ③ 编译（6 节点并行） | 每个 2.2~2.9 min | 墙钟约 3 min |
| ⑤ **发布** | **35 min（占 85%）** | 内部：gh-pages 推送 **101 s**；**Release 上传 1998 s 还没结束** |

上传量：6 个 APK（≈590 MB）+ 6 个 `.bin`（≈660 MB）+ 模块 zip（50 MB）≈ **1.3 GB**。
慢的放大器是 GitHub 资产服务的 500（`Error creating asset temp dir`）—— `gh` 失败要**整包重传**。

三条改造（都按用户选的 1+2+3）：

| # | 改法 | 省什么 | 依据 |
|---|---|---|---|
| 1 | **`.bin` 不再进 gh-pages**（只挂 Release） | 每次少推 660 MB（~80 s），更关键是**止住分支膨胀**（`keep_files` 只增不删 ⇒ 每个版本往 gh-pages 再添 660 MB，早晚撞 Pages 的 1 GB 限制） | 先核查消费方：下载页的"离线包"链接全部指向 `releases/download/<tag>/…`；App 用的是 APK 内嵌 `assets/offline-bundle.bin`；`index.json` 只存文件名 ⇒ **站点上那份没有任何消费方** |
| 2 | Release 上传**最多 3 路并发**（每个资产仍 4 次退避重试；已存在且 name+size+sha256 一致的**跳过**） | 上传墙钟约压到 1/3；重跑不再重传 660 MB | 2026-09-18 那次 500 已经证明了"重试"必要、"逐个串行"太慢 |
| 3 | **变更集判定**驱动编译与发布：`tools/ci-changeset.mjs`（纯函数 + 39 条回归），`push` 只跑门禁，**版本号 = 发布意图** | 文档/CI-only 的 push 从 40 min → **约 3 min**；只改模块时不再重打离线包（`build_bundles=false`） | 版本没变却要求发布 ⇒ **参数非法直接拒**（别白跑 35 分钟）；改了产物相关文件却没升版本 ⇒ 警告但不发布 |

新增/保留的手动参数（都进 `workflow_dispatch`，且都**被校验**）：
`channel`、`embed_bundles`、`force_build`、`force_publish`、`cleanup_pages`。

**两条"实跑才发现"的坑**（都写进了注释/回归）：

1. **离线包文件名带 App 版本**（`index.json` 的 `bundle` = `SunsetLinux-<appver>-<组合>.bin`）⇒
   **App 版本一变就必须重打离线包**。第一版判定只按"离线包输入有没有变"来决定，实跑 CLI 时才发现
   会把新版本的 `.bin` 整个漏掉（新 Release 上一个都没有）。回归里专门钉了这条（③b）。
2. **YAML 块标量里内联 python 的续行不能顶格**：`python3 -c "…
try: …"` 那种写法里，
   顶格的 `try:` 会**提前结束块标量**，YAML 直接 `mapping values are not allowed here`。
   改成单行 `-c`（注释里留了原因）。

> **gh-pages 历史 `.bin` 已经清掉了**（2026-09-18，一次性，不需要再点）：分支 tip 上原有
> **83 个 `.bin`**（跨十几个版本，约 5 GB —— `keep_files` 只增不删的后果），已用一个"只删文件、
> 不下载任何 blob"的提交清空（tip `98fd9a2`，203 → 120 个文件）。顺带保留了 `cleanup_pages` 输入，
> 以后要是又有人往里塞大文件，勾一下就能清。
>
> ★ 清理过程本身有两个坑，都写进了 `publish.yml` 的注释：
> ① 部分克隆里 **`git commit` 会刷新工作区**（而 `--no-checkout` 下文件都不在）⇒ 触发**懒拉取**，
>    实测 `fatal: could not fetch <blob> from promisor remote`；
> ② **`git write-tree` 默认要求对象齐全** ⇒ 同样会去拉那几个 GB。
>    正确姿势是纯 plumbing：`read-tree` → `update-index --force-remove` → **`write-tree --missing-ok`**
>    → `commit-tree` → `push <commit>:refs/heads/gh-pages`。

回归：`tools/ci-changeset-selftest.mjs` **39/0**（含"没改就不发/不编"、"参数非法要拒"、"拿不到基线就保守"）；
`pipeline.yml` 7 段 shell、`publish.yml` 12 段 shell 全部过 `bash -n`，两份 YAML 过 `yaml.safe_load`。

### 3.10.38 「分步启动」之后的**假故障文案**：把按设计的正常状态报成了故障（App 0.3.11）

真机截图（2026-09-18 01:05）：按「分步启动 → 仅启动环境」起来之后，屏幕上同时出现三处"像坏了"的显示 ——
副标题「健康检查（Web 未响应）」、卡片「打开 DSH」永恒停在「正在获取登录地址…」、DSH 面板报
「环境已在运行，但登录地址还没写出来（run/dsh.url 尚未就绪）。稍后点「重新登录」重试」。
**可这三处其实都没坏**：这次是「仅启动环境」，DSH 按设计就没起，`run/dsh.url` 自然不存在，
"等一等就好"永远等不到 —— 用户因此以为分步启动是坏的。

根因一句话：这些文案只看 `dshHealthy` / 有没有 url，**不看"这次本来就是仅环境"**。

| 位置 | 之前 | 现在 |
|---|---|---|
| `Diagnoser.stage`（副标题/阶段） | `RUNNING -> if (dshHealthy == false) "健康检查（Web 未响应）"` | 先判 env-only：`isEnvOnly && !dshRunning` → 「环境运行中（仅环境：DSH 未启动）」；**full 模式下 DSH 真挂了仍照旧报「Web 未响应」**（两种模式不混为一谈） |
| 「打开 DSH」卡片副标题 | 环境在跑就写「正在获取登录地址…」 | 抽成纯函数 `StartControls.openDshSupporting()`：环境没跑 → 环境未运行；仅环境且 DSH 没跑 → 「DSH 未启动：点『启动 DSH』」；**只有 DSH 在跑而 url 还没写出来**才说「正在获取…」 |
| DSH 面板打不开时的文案 | 一律「…登录地址还没写出来…稍后点『重新登录』重试」 | 抽成纯函数 `StartControls.dshPaneNotOpenText()`：仅环境方式明确说"这次是「仅启动环境」，回启动页点「启动 DSH」"；环境没跑时照旧带状态与 `last_error` |

> 顺带把一条更硬的事实钉住：**这两个纯函数只负责措辞，判定仍由 `StartControls.of()` 的矩阵唯一决定** ——
> 矩阵本身没错（真机上「启动 DSH」确实是可点的、提示也确实是"点它接上"），错的是**别处**对它状态的转述。
> 回归：`StartControlsTest` 12 → 15 条、`DiagnoserTest` 新增 3 条（含"仅环境不许报未响应"与"full 模式挂了仍要报"）。

### 3.10.37 模块 WebUI 的三个真机问题：白屏 2~3 秒 / 点停止后越用越卡 / 「开机自启」文案在说谎（模块 1.0.31）

用户真机反馈三条（原话见下），都是**只有我们这个页面会犯**的：

| 反馈 | 真因 | 改法 |
|---|---|---|
| 「打开 WebUI 先**空白 2~3 秒**，没有任何页面；其他模块不会」 | 旧 `boot()` 在 `DOMContentLoaded` 里**同时**发 4 条桥接命令（`status` / `cat config.json` / `update-module-info` / 回滚列表）。manager 的 `ksu.exec` 是**串行**的，而单是 `linuxctl status` 就要 1~3 秒 ⇒ 首帧被这一串顶住（页面本身 66 KB、零外链、无重特效，白屏只可能来自这里） | **先出画面、再逐条取数**：`afterFirstPaint()`（双 rAF，老 WebView 退化成 `setTimeout`）→ `runBootPlan()` 按 `PURE.bootPlan()` 的顺序**一条返回再发下一条**；状态卡加「正在读取状态…」占位，拿到就收掉 |
| 「点了一下停止，后面切页面越来越卡」（最后强制重启手机才恢复） | `stop.sh` 要等 supervisor 最多 15s 再 KILL、再逐项卸载（实测 20~30s）；页面同时挂着「状态每 5s」「日志每 3s」两个定时器，**不等上一次返回就发下一次** ⇒ 在串行桥上排队，队列越排越长 | 新增 `GATE` 闸门：同一时刻**只允许一个轮询在飞**（在飞就丢掉这一拍，绝不排队）；`start/stop/restart` 期间 `GATE.op` **暂停轮询**，且在 `finally` 里复位（抛异常也不会让轮询永久停摆）；操作期间把"要等 10~30 秒"写在输出里 |
| 「在模块页没有开启自启的选项，关了它还是自启」（并预告"后面有重启就是代码 bug"） | 两件事：① WebUI 的提示写着「与 App 设置页**共用同一份 config.json**」—— **这是错的**，App 那个开关（「开机自启**状态服务**」）写的是 App 自己的 SharedPreferences，与 `etc/config.json` 无关 ⇒ 用户在 App 里关，`autostart` 仍是 `true`；② 旧实现遇到"文件在但没有 `autostart` 键"就把开关**置灰并显示关**，而设备侧 `service.sh` 的判据是「默认开、只有读到 `false` 才关」⇒ 显示与真实行为相反 | ① 文案改对，并明说两个开关不是一回事；② 语义抽成 `PURE.autostartView()`（纯函数 + 单测）：**没有键 = 显示"开（默认）"且开关可用**；③ 写 `autostart` 改成"存在就 sed 替换、不存在就插键"，并**回读核实**（`sed` 没匹配到时 errno 仍是 0，只回读能发现"其实没写进去"）；④ `停止` 完成后若自启仍开着，明确提示"**下次重启还会自动启动**，要它别起就去关那个开关" |

> 判据留档：`service.sh` 先 `AUTOSTART=1`，只有 `grep -q '"autostart"[[:space:]]*:[[:space:]]*false'` 命中才置 0 ——
> 所以"缺键 = 开"是**设备侧事实**，WebUI 的显示必须与它一致（这次就是不一致才让用户以为没这个设置）。
> 回归：`module/webroot/selftest.mjs` **64 → 89/0**（新增 `autostartView` 语义 6 条、轮询闸门静态断言 9 条、
> 首屏双 rAF/逐条取数 10 条）。

### 3.10.36 把上一轮留下的两条真机遗留做完：`nsenter --wd=`（终端进不去环境）+ 频道验签拒绝理由带证据（模块 1.0.30 / App 0.3.10）

**一、`nsenter: Unknown option 'wd=/data/sunsetlinux/rootfs'` —— handoff 里给的修法是错的，实测重写**

第 40 轮交接写的修法是「`--wd "$ROOTFS_DIR"`（或 `-w`）」，真机上**同样不成立**：

| 写法 | 设备实测（`/system/bin/nsenter` → **Toybox 0.8.12-android**） |
|---|---|
| `--wd=/path` | `nsenter: Unknown option 'wd=/path'`（真机原话） |
| `--wd /path` | `nsenter: Unknown option 'wd'` |
| `-w /path` | `nsenter: Unknown option 'w'` |
| `--help` 的选项表 | 只有 `-a -F -t -C -i -m -n -p -u -U` —— **没有 cwd 选项** |

也就是说设备这个 nsenter **根本不支持指定工作目录**，而 cwd 本来也不需要它：
`chroot NEWROOT` 自己会 chdir 进新根（实测：从 `/tmp` 起 `chroot <rootfs> /usr/bin/env -i /bin/pwd` 打印 `/`），
而"chroot 后 cwd 必须在环境内"正是当初加 `--wd` 的唯一目的。所以修法是**四处统统删掉 `--wd=`**（`linuxctl.sh`），
并在原地留下这段实测结论。另一条被测出来的约束：ns 参数必须写成**等号**形式（`--mount=/proc/<pid>/ns/mnt`），
空格分隔会被判 `need -t or =filename`。

**闸门（这次做成了"会红"的）**：`tools/shell-compat-check.mjs` 新增**nsenter 选项面检查** —— 按设备 `--help`
的选项表逐条比对脚本里真正传给 nsenter 的选项（跨 `\` 续行合并成逻辑行；给了 `-t PID` 时才允许裸 `-m/-u`）。
写它时踩到一个必须记住的坑：**第一版扫描器在真实代码上一直空转**（`"$NSENTER"` 的收尾引号把 token 解析卡死），
变异测试全绿 —— 于是给它加了 `nsenterSelfCheck()` 自检（10 组"必须抓到 / 不得误报"的固定样本，含真机原样、
续行拆开、`stop.sh` 里 `umount -l` 不得误报），闸门自己失效就直接判红。
`runtime/root/selftest.sh` 另加一条**行为级**对照：拿设备上真实的 nsenter 验一遍我们实际用的写法
（判据只看选项解析，`No such file` / `Operation not permitted` 都算通过，非 root 也不假红）。

**二、顺手修掉一条"在设备上必然假红"的门禁**（`e2fsck -p`）

`runtime/root/selftest.sh` 的"可写层自愈顺序"断言在真机上**永远红**：`/system/bin/e2fsck` 真实存在，
而 `e2fsck_preen` 的绝对路径候选排在 PATH 前面 ⇒ 自测放的桩一次都轮不到，真 e2fsck 去跑那个 0 字节的
`upper.img`（rc=8）⇒ 断言失败（基线复核：HEAD = `81 通过 / 1 失败`）。修法：候选表第一位加**覆盖点**
`$SUNSETLINUX_E2FSCK`（与 `SUNSETLINUX_EROFS_EXTRACT` 同类，留空行为不变），自测用它指向桩。
修后 `83 通过 / 0 失败`（bash + mksh）。

**三、频道「签名校验失败」：先把"发布侧没问题"独立复核，再让 App 的失败能自证**

- 复核（本机 curl 走的**就是这台手机的网络**）：线上 `/channel/channel.json` + `.sig` 与 **gh-pages 分支**里的副本
  逐字节一致（sha256 `f28669bb…` / `a550ae0d…`），用 App 内嵌公钥按 App 的算法（base64 → SPKI → Ed25519）验签 **通过**；
  `channel.json.pub` 是 404，但 App 从不取它（内嵌公钥、无缓存）。
- 代码侧：`Update.kt` 最后一次改动是 **0.2.4**，`Net.kt` 未动 —— "0.3.9 搬 UI 时把公钥/签名获取路径动过"这一嫌疑**排除**。
- 所以这轮做的是**让失败可自证**：`SignatureVerifier.diagnose()` 把公钥指纹（写法与 HANDOFF 公开的那行逐字相同）、
  清单字节数 + sha256、签名解出的字节数写进拒绝理由；异常也不再被吞成"验签失败"（`availability()` 只查了
  `KeyFactory`，而 `verify()` 还要 `Signature.getInstance("Ed25519")`）。新增 `ChannelSignatureTest`
  用**真实发布快照**（`testdata/channel/`）做行为级回归 —— `SignatureVerifier` 此前零覆盖。

> 真机仍未验证：① 装 1.0.30 后 `linuxctl exec/attach` 是否真能进环境（选项面已实测通过，缺端到端）；
> ② 频道失败**发生在哪个频道、什么现象**（需要用户侧现场：现在是否仍复现、界面上是哪个频道名）。

**四、顺带修掉发布路径的一个真坑**（`publish.yml`，本轮发布时当场撞上）

第一次跑 0.3.10 的流水线：①~④ 全绿（六节点编译、门禁、离线包、频道验签），**卡在 ⑤ 的
「发布到 GitHub Releases」**。日志原文（三段连起来就是真因）：

```
16:28:10  新建 Release v0.3.10
16:45:52  HTTP 422: Validation Failed (…/releases/390880951/assets?…name=SunsetLinux-0.3.10-proot-base.bin)
16:45:52  ReleaseAsset.name already exists
```

即：`gh release create` 在"一次调用带 15 个资产"时**自己重传了同一个资产**（第一次其实已在服务端成功、
客户端超时后重试）⇒ 撞名 422 ⇒ 整条发布红掉。文件本身没问题（同一次生成的 `index.json` 里
`SunsetLinux-0.3.10-proot-base.bin` 大小 69,205,422 B、sha256 正常，与 0.3.9 一致）。

三个真问题（都已修）：

| 问题 | 后果 | 改法 |
|---|---|---|
| 15 个资产一次性交给 `gh release create`；gh 内部重传 → `already exists` | 一个资产撞名就让**整条发布红掉**（重跑代价 = 20+ 分钟编译 + 一次全量发布） | 拆成：先 `gh release create --draft` **只建条目** → **逐个 `gh release upload --clobber` 带 4 次退避重试** → 仍不吞错（四次都失败照旧报红并打出 gh 原文） |
| 重跑会把 ~660 MB 资产再传一遍（那次光这一项就 17 分钟） | 慢，且再踩同一个坑的机会 | 上传前先取 `--json assets`（name/size/digest），**名字+大小+sha256 三者都一致的跳过**；拿不到 digest 的一律重传（不比大小就跳过） |
| gh 在有资产时是"**先建草稿 → 传资产 → 最后才发布**"，而原来的重跑路径**从不 `--draft=false`** | 上一次失败会把 Release 留在**草稿**态，用户看不到它；而 gh-pages（上一步已成功）已经挂着 0.3.10 的下载链接 ⇒ APK/.bin 链接当场 404 —— **重跑也永远修不好** | 资产齐了显式 `gh release edit --draft=false`（幂等），顺手修复历史草稿 |

> 判据留档：`gh release view` 能看到草稿，所以"已存在"分支要区分草稿/已发布（日志里打印）；
> 资产逐个上传后仍要**逐个核对**（失败清单非空即报红），别让"重试"退化成"静默少发"。

### 3.10.35 源独立成页（npm 默认修成官方 + Python 源）+ 设置页拆分 + gh-pages 合成一次提交（App 0.3.9）

真机批注三条：「把这个 NPM 源默认成官方的」/「顺便多一个 Python 源」/「源独立页，然后设置内但是侧边栏有相应标签的内容
统一拆成单独页面。设置页实在是太长了」。

| 项 | 之前 | 现在 |
|---|---|---|
| npm 源默认 | `prefs.npmRegistry` 为空时落到 `CUSTOM_ID` → **全新安装打开就是「自定义」被选中**（真机截图） | 默认 `NpmRegistry.OFFICIAL_ID`（`registry.npmjs.org`） |
| Python 源 | 没有 | 新 `core/PypiRegistry.kt` + 源页里的 Python 卡（官方 / 清华 TUNA / 阿里云 / 腾讯云 / 华为云 / 自定义 + 校验），落盘 `/root/.config/pip/pip.conf` + `<环境根>/etc/pip.conf`；生效方式与 npm 同口径（**重启环境**后新进程才读到） |
| 页面 | 源卡片挤在「插件」页底部；设置页 986 行一条长滚动 | 源独立成 `ui/SourcesPane.kt`（侧边栏入口）；设置页按主题拆出独立页面（如 `ui/ChannelsPane.kt`）；插件页只留插件列表 |
| gh-pages | 一轮发布推 **2~3 次**（`<通道>/`、站点根页、`/channel/`）→ GitHub 起 2~3 条 `pages build and deployment`，相邻两条互相取代（`#114 取消/#115 成功`、`#126 失败/#127 成功`） | **1 次推送**：`publish.yml` 本地拼出站点根那一层（`<stable\|beta>/` + `channel/` + 仅 stable 的 `index.html`）后一次 `peaceiris` 推送；`pipeline.yml` ⑥ 改成**只验签不推送**、⑦ 加频道门禁。验签硬约束不变（未签名/签坏的清单绝不发出去），手动路径 `channel.yml` / `layers.yml` 仍各自推（注释已点明） |

> ⚠️ 一处必须记住的约束：`/channel/channel.json` 在**站点根**，消费方是硬编码的（`Prefs.kt`、`OfficialChannelContractTest`、
> `ci.yml`、`offline-bundle.yml`、下载页）——合并推送时**不能**用 `destination_dir` 把它塞进 `<通道>/channel/`。
> 回归：App 单测 **229 → 252/0 × 2 变体**；9 个 workflow 全部 `yaml.safe_load` 通过。

### 3.10.34 按真机批注改 UI：启动页操作置顶 + 一键/分步二选一；终端紧凑化与"自动切换"的身份行（App 0.3.8）

**批注原文**（真机截图，红字）：
> 「把操作这个大块，移到最顶部。添加一个小 UI，在一键启动和分步启动两个按钮之间切换，默认一键启动按钮状态。」
> 「使终端这块紧凑一点，太散了，悬浮导航栏也有遮挡它，终端这一块得往上抬一抬。注意部分文案也有问题，
> 需要修正/调整一下，把部分内容变成自动切换的，优化一下终端实际状态：比如终端权限行、root 身份」

**启动页**：段落顺序由「状态 → 地址 → 日志 → 操作」改成 **操作 → 状态 → 地址 → 日志**；操作卡内加两段式
切换（默认「一键启动」，落盘 `Prefs.start_mode`）—— 一键档只显示「一键启动（环境 + DSH）」+「停止环境」，
分步档显示 2×2「仅启动环境 / 停止环境 / 启动 DSH / 停止 DSH」。**切换只决定显示哪一组**：每个按钮的亮/灰
仍**只**来自 `StartControls`（契约测试反向断言操作卡里没有 `enabled = true` 这类第二份判定）。
**运行中锁定**（§3.10.32 的互斥在 UI 上的正面表达）：`full` 锁一键、`env-only` 锁分步、`env_mode=null`
（旧模块）**不编造**——停在用户选择并如实写"判不了"，提示统一为「要换方式请先『停止环境』」；纯逻辑在
`core/StartModeUi.kt`。

**终端页**：① 最外层 Column 补 `CapsuleReserve` 底部留白 —— 之前快捷键行与输入框正好被悬浮胶囊盖住
（`CapsuleReserve` 此前只有 MessageBanner 在用）；② 竖直留白收紧约 1/4（顶部 8→4、未运行横幅 14→10、
空态 16→12 且间距/行距收紧、快捷键行 6→2、输入行 10→6）；③ **文案跟着状态自动变**（`core/TerminalStatusUi.kt`）：
删掉同时讲两个 edition 的「root 模式会 chroot 进 rootfs，proot 模式进 proot 的 bash」，改成只讲本版事实
（Root = `以 root 身份在环境内执行：nsenter 进环境 ns → chroot 到 /data/sunsetlinux/rootfs`；免 root =
`以 proot 伪造的 root 身份执行（没有真实权限）；环境根 = App 私有目录`）；顶栏身份行**连上后才出现**
（`uid 0（root）` / `伪 root（无真实 capabilities）`，未连接**不显示、不编造**）；终端页副标题改成自成一句的
短句（原来 `失败（未知，建议跑一键诊断）` 被顶栏裁成「失败（未知，建议跑一键诊…」）。

**回归**：App 单测 **198 → 229/0 × 2 个变体**（新增 `StartModeUiTest` 9、`TerminalStatusUiTest` 11、
`StartModeContractTest` 11；`ShellLayoutContractTest` 里"终端页要有 vim 提示"的断言改指文案新家——文案搬了家，
旧断言会假失败）。

> 未验证项（需要真机看一眼）：切换控件的观感、收紧后是否"够紧凑"、顶栏两行是否真的不截断。身份行是按
> edition/mode **推导**的（ROOT 会话链路 = `su -c linuxctl attach` → nsenter + chroot，连上即真 uid 0），
> 不是运行时 `id -u` 探测 —— 若要求"实测到的 uid"，得另立方案（多一次 su 往返、还会污染用户滚屏）。

### 3.10.33 安装器文案修正（用户反馈「文案不对」）+ 模块版本撞车（1.0.28 → 1.0.29）

**用户反馈（2026-09-17，装 1.0.28 时看安装日志）**：三处文案与事实不符或会把人带偏 ——

| 原句 | 问题 | 现在 |
|---|---|---|
| `  内置 DSH 已就位（重启后开机自建 base/runtime 时直接用它）` | 层**早就在磁盘上**了，开机不会"自建"任何层（只有缺层时 `service.sh` 才零点击自建） | `  内置 DSH 已就位：这一版已在 $LINUX_HOME/layers/（本步幂等：已有就不重复解压）` |
| `- 已检测到 DSH 层，开机将由 service.sh 自动启动` | 没说是"一键启动（环境 + DSH）"、没提 `autostart` 开关、没说"只起环境"那条路、也没说模块装在 `modules_update/` 要重启才生效 | 四行：`service.sh` 调 `linuxctl start`＝一键启动 / 开关在 `etc/config.json` 的 `autostart` / 只起环境用 `start --no-dsh`（之后 `dsh start|stop` 单独控）/ 生效时机（`modules_update` → 重启后迁到 `modules`，看后者 `module.prop` 的 version） |
| `★ 本模块不挂载任何系统路径，无需 metamodule 支持。` + 一整段重复 | ① 与 `detect-mount.sh` 自己打印的结论**重复了一遍**；② 被读成"这模块不挂载任何东西"—— 而它**确实要挂**（overlayfs + 三层 erofs + 可写层 ext4(loop)），真机上那些挂载还因为 `--make-rprivate` 不支持而泄漏到**全局**挂载表（doctor §1d 报的 33 条） | 明确切成两件事：`不向系统分区放文件`（所以不需要 metamodule、不与它冲突）**与** `环境自己的挂载在私有 mount ns 里、挂载点都在 $LINUX_HOME/… 下；私有化失败时会出现在全局表里，见 doctor §1d` |

同一口径也改了 `module/lib/detect-mount.sh`（报告结论 + 给 App/WebUI 的 `conclusion` 字段）与
`runtime/root/doctor.sh` §1c 那句 `[ok]`（原来写的是"不挂载任何系统路径"，同样容易被读成"不挂载"）。

**回归**：`tools/customize-selftest.mjs` **16 → 21**（新增 5 条，全部按**用户可见的输出**断言）：
`autostart` 出现在自启文案里 / `--no-dsh` 那条路在 / 生效时机（`modules_update` + 重启后）/ 挂载结论限定在
"系统分区" / 区分了"环境自己的挂载在私有 ns 里"。这几条正是这次被指出的原文案会漏掉的东西。

**顺带修的版本撞车**：设备上 `/data/adb/modules/sunsetlinux/module.prop` 已经是 **1.0.28**（那是本机自建的一版，
`bin/common/` 里**没有** `env-procs.sh`），而这次功能也打的 1.0.28 → App 的模块更新比较是**逐段数字比**，
`1.0.28 == 1.0.28` 会判"已是最新"，用户收不到；Release 上同名资产也会被覆盖。改成 **1.0.29**（versionCode
10029）重发：`sunsetlinux-module-1.0.29.zip`（full）/ `-bare.zip`。

### 3.10.32 「环境」与「DSH」拆开：两种启动方法 + 互斥判定（模块 1.0.29 / App 0.3.7）

> 本次功能先以 1.0.28 发布，因与设备上自建的那版撞号，改版号为 **1.0.29** 重发（见 §3.10.33）。

**需求（用户原话）**：「能不能拆开？启动环境不等于启动 DSH …… 再补两个按钮是两者拆开分别启动，
且开启判定，使用一键时则不能使用后者，使用后者时则不能使用前者，用于在虚拟环境上更新或者其他
操作，这样的话也能补上『终端』功能的缺陷，把终端接入虚拟环境」。

**为什么以前做不到**：`linuxctl start` 是一条龙（挂载树 + 私有 ns + `entry.sh` → `exec supervise.sh`
→ DSH）。想进环境里更新层/装包/用终端，就**必须**把 DSH 一起拉起来占着 `127.0.0.1:3080`；
`status` 里"环境在跑但 DSH 没跑"这种状态也**没有任何字段**能表达（`state=running` + `url=null`
与"环境没跑"在 JSON 上分不开）。

**做法**：

| 层 | 改动 |
|---|---|
| `start.sh` | 新增 `--no-dsh`（内层靠 `SUNSETLINUX_NO_DSH` 传下去，与 layer-mode 同一招）；写 `run/env-mode`；chroot 时把 flag 交给 entry.sh |
| `entry.sh` | 认 `--no-dsh`：照样准备 DNS/时区/HOME/DSH_HOME，然后**把进程挂住**（带 TERM trap）—— 它既是宿主侧"环境还活着"的锚点（内层 start.sh 在等它），也是终端与 `dsh start` 的落脚点 |
| `linuxctl` | 新增 `dsh start [--port N]` / `dsh stop`；`start` 认 `--no-dsh`；两条**互斥拒绝**；status 增加 `dsh.running` / `env_mode` 两个附加键 |
| `supervise.sh` | 自己加载 `$DSH_HOME/env`：`dsh start` 是宿主侧 nsenter+chroot 拉起的，**不经过 entry.sh** —— 少了这一步，DSH 会"起来了却拿不到 API key" |
| `linuxctl` 的重启路径 | `resume_start_like_before`：层更新 / 版本回滚 / 快照打包 / 快照恢复都是 stop→start，**必须保持原来的起法**，否则用户在「仅环境」里更新层，重启后凭空多出一个占 3080 的 DSH（维护模式被悄悄踢掉）。`env-mode` 必须在 stop **之前**读（stop 会清它） |
| `runtime/common/env-procs.sh`（新） | 环境内进程的识别/清理，判据 `/proc/<pid>/root == $LINUX_HOME/rootfs`；`stop` 清**全部**（含 `entry.sh` 的挂住进程、用户开着的终端会话、跑着的 apt）、`dsh stop` 只清 `*dsh*web*`；**`ENV_ROOTFS` 为空就一个都不动** |
| `stop.sh` | 改用共用库（原来是自己写的一份 `ps \| while`）；清理 `env-mode` |
| `post-fs-data.sh` | 开机也清 `run/env-mode`（它是"本次启动"的属性，跨开机留着只会误导） |
| `doctor` §8 | 认出 `env-mode=env-only`：**不再**把它误报成"环境在运行却拿不到 dsh.url"（§3.10.31 的那条 fail 判据只对 full 成立），并显示本次是哪种起法 |
| proot 侧 | status 也报 `dsh.running`（= DSH 活着）、`env_mode=null`；**没有**拆开启动这条路 —— 它的"环境"就是一个解开的 rootfs 目录，没有挂载树要在没有 DSH 的情况下维持 |
| App 0.3.7 | 首页五个按钮 + 纯函数判定（`core/StartControls.kt`）；状态卡显示「启动方式」；env-only 且 DSH 没跑时"Web 健康"显示「DSH 未启动（仅环境方式）」而不是红字假故障；终端页只要求**环境**在跑，并给「仅启动环境」入口；诊断页不再对 env-only 提示"端口没写对" |

**互斥规则**（App 的按钮判定与模块**必须**一致，完整表见 `docs/architecture.md` §3.4）：
full 环境不许单独停 DSH；env-only 环境不许被"一键启动"顺手塞进 DSH（**拒绝**，不是幂等无动作）；
环境没跑时 `dsh start` 失败、`dsh stop` 幂等成功。

**回归**：

- `runtime/root/selftest.sh` **73 → 81**（本机 proot 沙箱：81 通过 / 0 失败 / **1 skip**）× bash+mksh：
  - `env-procs`：正例（root 匹配 → 必杀）、**反例**（别的环境的同名 dsh 一根汗毛都不许动）、
    安全闸（`ENV_ROOTFS` 为空 → 一个都不动）、`stop.sh` 确实调用了共用库；
  - 环境/DSH 分离：`dsh start`（环境没跑 → 失败）、`dsh stop`（幂等）、`--no-dsh` 整条链路
    （linuxctl → start.sh → env-mode → entry.sh）、`supervise.sh` 自加载 env、
    重启路径保持起法、doctor 的 env-only 话术；
- App 单测 **198/0 × 2 个变体**（`testRootFullDebugUnitTest` / `testProotFullDebugUnitTest`，
  6 条既有 skip）；新增 `StartControlsTest`（矩阵穷举）、`EnvModeStatusTest`（附加键解析与缺键退化）、
  `EnvOnlyHintTest`（env-only 不报假故障）、`EditionSeparationTest`（只有 Root 版有这三个动作）。

> 「环境在跑」那几条（status 的 `running`/`env_mode`、两条互斥拒绝、doctor 的 env-only 话术）
> 在 proot 沙箱里是 **skip**：那里 `stat /proc/<pid>/ns/mnt` 会被 ptrace 拦掉，`ns_pid_alive`
> 恒 false；CI 与真机会跑满（**88** 条）。
> 逻辑本身用"只把 ns 可见性打桩、其余走真代码"的临时副本在本机跑通了：`state=running` /
> `dsh.running=false` / `env_mode=env-only`、两条拒绝都按预期、`dsh stop` 真把进程停掉而环境
> 仍保持 running、doctor 在 env-only 下不再报 fail（而在 full 缺 dsh.url 时**仍然**报 fail）。


### 3.10.31 环境内 dsh.url 写错目录 → App 永远「登录地址还没写出来」（模块 1.0.27）

**真机现场（2026-09-17 19:06，模块 1.0.26 + 内置 DSH 0.1.5-rc.2）**：App 状态页一切正常 ——
「运行中 / Web 健康 正常 / 监听端口 3080 / DSH 0.1.5-rc.2」，点进 DSH Web 却是：

```
⚠ 环境已在运行，但登录地址还没写出来（run/dsh.url 尚未就绪）。稍后点「重新登录」重试。
```

`doctor` 里同时出现两句看起来矛盾的话，两句也都是真的：

```
[ok]   环境运行中（ns_pid=4106）
[info] run/dsh.url 不存在（环境未运行或尚未打印 URL）
```

**环境在跑、dsh 也在跑，只是带令牌的登录 URL 落错了目录**（App 读的是宿主
`$LINUX_HOME/run/dsh.url`，见 `linuxctl.sh:140/354`）。

**证据链**：

1. 环境内日志：`[19:06:36] dsh 已后台启动，pid=4898` → `dsh web: http://127.0.0.1:3080/?token=…`
   → `[19:06:40] 已捕获带令牌 URL 并写入 /data/sunsetlinux/run/dsh.url`（**它以为自己写对了**）。
2. 宿主 `/data/sunsetlinux/run/` 里没有 `dsh.url` / `dsh.port` / `dsh.pid`；它们在**可写层**里：
   `/data/sunsetlinux/upper/upper/data/sunsetlinux/run/`。
3. 3080 的占用者就是环境自己的 `node /usr/local/bin/dsh web …`（pid 4898）—— App 那句
   「Web 健康 正常」是连它探到的真响应，属于"端口通、凭证读不到"；也就是说 `doctor` 的
   「3080 已被占用」在环境运行中是**自己占自己**的误报。

**根因（一行）**：`start.sh` 的 `rbind_host_run` 把**宿主 `$LINUX_HOME/run`** rbind 到**环境内 `/run`**，
契约就是"环境内写 `/run`"。但 `entry.sh` / `supervise.sh` 里写的是 `RUN_DIR="$LINUX_HOME/run"` ——
这两个脚本在 **chroot 内**运行，根已经换成 overlay，那个路径既不是宿主目录、也不是 rbind 进来的
`/run`，而是可写层里**凭空 mkdir 出来的假目录**。注释与实现自相矛盾（`entry.sh` 的注释写着"环境内写
`/run/dsh.url` 等于宿主写 `$LINUX_HOME/run/dsh.url`"，下一行却把 `RUN_DIR` 拼成了后者）。

**第二半（同一根因，症状完全不同）**：`dsh.pid` 也写进了那个假目录 → 宿主 `stop.sh` 的
`kill_stray_dsh` 拿着不存在的 pid 文件 `return 0` → 残留的 node/dsh **继续占着 3080 与三个 loop 设备**
→ **下一次 start 直接失败**（真机 19:18 的 `start.sh 失败` 就是这么来的；19:27 的 stop 日志里只剩
一句"loop 设备仍被占用"）。这正是"和现有使用冲突了怎么办"的现场。

**修法**：

| 位置 | 改动 |
|---|---|
| `entry.sh` / `supervise.sh` | `RUN_DIR="${SUNSETLINUX_RUN_DIR:-/run}"` —— 环境内只写 `/run` |
| `stop.sh` | 新增 `kill_env_leftovers`：判据 = 命令像 `dsh web` **且** `/proc/<pid>/root` == `$LINUX_HOME/rootfs`（别的环境的 dsh 同名同参、root 是 `/`，**一根汗毛都不许动**） |
| `doctor §8` | 环境在跑却没有 `run/dsh.url` → **直接报 fail**（以前只说一句 info，用户会去重启一个本来就好的环境），并在可写层里找出错位副本、把修复路径直接写出来；启动 30s 内不报（给 dsh 打印 URL 的窗口） |

**回归**：`runtime/root/selftest.sh` **66 → 73** × bash+mksh（环境内 `RUN_DIR` 必须是 `/run`；两个脚本
不许再出现 `RUN_DIR="$LINUX_HOME/run"`；宿主侧 rbind 落点仍是 `rootfs/run`；`kill_env_leftovers` 的
正例必须杀、**反例（别的环境的同名 dsh）不许动**）。反例做过变异测试：删掉 `/proc/<pid>/root` 判据，
那条断言立刻红。

> **已经踩在 1.0.26 上的设备怎么救**（一次性）：
> 1. 先干掉残留的 dsh（它占着 3080 与 loop）：`ps -A -o PID,ARGS` 里找 `node /usr/local/bin/dsh web`，
>    `kill <pid>`；或看 `losetup -a` 里还挂着 `layers/*.erofs` / `upper.img` 的那个 pid；
> 2. 装 1.0.27（或把 `entry.sh` / `supervise.sh` 里那行 `RUN_DIR` 手改成 `/run`）后 `linuxctl start`；
> 3. 想立刻就登录（暂不升级）：把可写层 `upper/upper/data/sunsetlinux/run/` 下的
>    `dsh.url` / `dsh.port` / `dsh.pid` 拷到 `/data/sunsetlinux/run/`，再点 App 的「重新登录」。

### 3.10.30 内置 DSH「已就位却从没被启用」→ 永远 rc=78（模块 1.0.26）

**真机现场（2026-09-17，装完 full 模块 1.0.24/1.0.25 后）**：doctor 里同时出现两行，
看起来互相矛盾，用户完全无从下手：

```
[ok]   内置 DSH：0.1.5-rc.2（随模块冻结 → /data/sunsetlinux/layers/dsh-0.1.5-rc.2.erofs）
[fail] **当前生效**的 dsh 层内没有 /root/.dsh/profiles/** …（supervise.sh 会退 78）
```

而 `run/linux.log` 里每次 start 都是：`层 dsh = …/dsh-0.1.5-rc.1.erofs` → `rc=78`。

**根因（两处叠加，都在我这边）**：

1. `linuxctl dsh builtin` 当时只在「**还没有** dsh 记录」时才写 `state.json`（原意是
   "别把用户从频道更新的层悄悄退回去"）。而这台机早有记录 = 设备侧自建的 `rc.1`（坏的），
   于是一路跳过 → 内置 `rc.2` 只落盘、**从未被启用**，`find_layer` 永远返回 rc.1。
2. 幂等快路径（"同版本已落地 → return already"）在**启用判断之前**就返回了 ——
   于是即使后来补了规则，`dsh builtin` 也只会一直答 "already"，不会纠正指向。

**修法**：把「确保载荷在磁盘上」与「确保启用的是它」**拆成两步**，后一步两条路径都要走：

| 生效层状态 | 行为 |
|---|---|
| 没有 dsh 记录 | 切到内置 |
| 生效层**确定**缺 `/root/.dsh/profiles/web/package.json` | 切到内置（这份层起不来） |
| 生效层是好的 | **不动**（用户的频道更新不会被模块退回） |
| 判不了（没有 dump.erofs / 格式不认识） | 不动（保守） |

配套：
- `layer_has_path` 从 `doctor.sh` 抽到 **`runtime/common/layer-inspect.sh`**（doctor 与 linuxctl
  共用一份；mkmodule 的 `BIN_COMMON` 已带上它）—— 同一判断两处各写一遍必然漂移。
- `doctor §7` 的 profile fail 现在会**点名**："但内置那份是好的 → 一条命令切过去：
  `linuxctl rollback dsh`"（把"有好的却没用上"直接说破）。
- `module/service.sh` 的开机自愈条件从「有载荷 **且** 没有内置记录」放宽为「**有载荷**」：
  真机现场恰恰是"记录在、指向错"，只在缺记录时补永远治不好这一种。

**回归**：`tools/module-variant-selftest.mjs` **33 → 36**（新增三条，含**反方向**"生效层是好的
时绝不抢"，以及"**already 快路径也必须纠正指向**"——真机现场的正门）；`tools/provision-selftest.mjs`
**86 → 87**（断言触发条件是"有载荷"，且"还有记录就不跑"这个条件确实没了）。

> **不用等新模块的现场解法**（1.0.25 上就有效）：
> `linuxctl rollback dsh` —— 不带 `--to` 时优先回**内置版本**，只改 `state.json` 指向、不删层文件；
> 然后 `linuxctl start`。1.0.26 起这件事在开机自愈里自动做掉。

### 3.10.29 **32 位算术**事故：空闲 603 GB 被判成"空间不足"（模块 1.0.25）

**真机实测（装 full 模块时，customize.sh 的输出）**：

```
- 本包含内置 DSH（变体 full）：展开到 /data/sunsetlinux/layers/
! 内置 DSH 没展开成功（原因见下）
  {"ok":false,"error":"空间不足，无法展开内置 DSH 层",
   "available_kb":632669468,"needed_kb":634880}
```

空闲 **603 GiB**、需要 **620 MB**，却报空间不足。

**根因**：设备侧是 `mksh`（`/system/bin/sh`），它的 `$(( ))` 是 **32 位有符号**整数。
`[ $(( avail_kb * 1024 )) -lt "$need_b" ]` 把 KB 乘成字节时直接环绕：

```
632669468 * 1024 = 647853535232  →  32 位环绕 →  -686526464  →  小于 need_b  →  "空间不足"
```

本机 `mksh -c 'echo $((632669468 * 1024))'` 同样给 `-686526464`（**bash 是 64 位，本地用 bash 跑永远测不出来**）。

**修法（全仓库同类一次改干净）**：

| 位置 | 原来的写法 | 现在 |
|---|---|---|
| `linuxctl dsh builtin` 空间检查 | `avail_kb * 1024` 与字节比较 | **只在 KB 档比较**（`avail_kb` vs `need_kb`） |
| `update.sh cmd_apply` 空间检查（`dsh install` 必经） | 同上（**预存的老 bug**，会把"一条指令装层"也挡掉） | 同上；对外改报 `available_kb`/`needed_kb`（无消费方，安全） |
| `doctor §3` 表观大小 | `usize / 1024 / 1024 / 1024` → 8 GiB 显示成 **0 GiB**（真机输出里就是它） | 用 `awk` 算（双精度） |
| `shrink_image` 的 `cur_mb` | `字节 / 1048576`（8 GiB 会环绕成 0 → 误判"已是最小"） | 用 `awk` 算 |
| `footprint` 的 `_fp_size` | `k * 1024`（>2 GiB 的目录会变负数） | 用 `awk` 算 |

**回归**：`runtime/root/selftest.sh` **64 → 66**（新增"KB→字节换算"与"表观 8.0 GiB"，两处都
在 mksh 下才会红）；`tools/module-variant-selftest.mjs` **31 → 33**（用桩 `df` 复现"空闲 603 GB"，
**显式用 mksh 跑**并断言装得下去，另含"空闲 1 MB 必须如实拒绝"的反例）；
`tools/provision-selftest.mjs` **83 → 86**（新断言：service.sh 必须能开机补一次内置 DSH 展开、
走 `linuxctl dsh builtin`、且脱离 boot 进程）。

**顺带补的自愈**：`module/service.sh` 现在会在"模块里有 DSH 载荷、但 `etc/dsh-builtin.json` 还不存在"
时，于开机后用 `setsid` 补跑一次 `linuxctl dsh builtin` —— 安装时那次失败不再等于"永久失败"。

### 3.10.28 doctor §3 假警报：只读挂不上被当成 fail（模块 1.0.24）

**真机实测（用户手机，装完 1.0.23 后跑 doctor）**：§3 同时打出

```
[fail] upper.img 挂不上（可能不是 ext4，或 loop 设备不可用，或已坏）
[ok]   upper.img 可**读写**挂载（ext4, loop, rw）—— 与 start.sh 的第一步一致
```

**根因**：§3 把"只读试挂（`-o loop,ro`）"和"读写试挂（`-o loop,rw`）"做成了**两条并列结论**。
这台机上只读那条失败、读写那条成功（真正决定环境能不能起来的只有后者 —— `start.sh` 走的就是它），
于是报告自相矛盾，还把整份自检判成"未通过"（3 项 fail 里这一项是纯噪声）。

**修法**：§3 只留**一个**结论 —— 与 `start.sh` 的 `mount_upper_rw` 同口径的读写探针
（① `-o loop,rw,noatime` → ② 显式 `losetup` 兜底）；只读探针降级为**诊断信息**
（只在读写也失败时才跑，报 info，不产生 finding）。doctor 仍然只读：不跑 `e2fsck -p`、
不清残留 loop，探针自己建的 loop 自己拔掉。新增两个测试接缝：`SUNSETLINUX_LOOP_DEV_OK=1`（CI 容器没有 loop 设备，用它强制走探针）
与 `SUNSETLINUX_MOUNT/UMOUNT`（doctor 默认用 `/system/bin/mount` 绝对路径，桩必须能替进来）。
回归 **62 → 64 条**（bash + mksh 各 64/0；含反例：**读写也挂不上时必须仍然 fail**，
不能为了"不误报"把检查做成永远通过）。

> 同一轮真机的另外两项 fail（`dsh 层内没有 /root/.dsh/profiles/**`、`rc=78`）是**同一个真问题**：
> 层是设备侧自建的那份（`dsh-0.1.5-rc.1.erofs`，缺 web profile）。修法是装官方层的 dsh
> —— 一条指令 `linuxctl dsh install`（或装自带 DSH 的 `full` 模块）。

### 3.10.27 模块拆两变体 + 免 root 彻底切割 + 编译只在 CI（模块 1.0.23 / App 0.3.6）

**背景（真机）**：root 侧挂载链**全通**（upper + 三层 erofs + overlay + entry 被拉起），
唯一倒下的是 `supervise.sh` 的 **`exit 78`** —— 设备上自建的层缺 DSH 本体与 web profile。
即：**root 模块只缺 DSH 这一块**。这一轮就是补它，并把边界与发布模型一次定清。

**决策与设计**：[`docs/module-variants.md`](module-variants.md)（三条决策 + 契约 + 验收判据）。

| 决策 | 落地 |
|---|---|
| ① **免 root 完全切割** | 免 root 版不再有任何 KernelSU 模块痕迹（模块卡/模块检测/一键刷模块全部短路）；打开即自动"铺运行时 → 铺内置环境 → 启动 → 落到终端"。模块侧也不再牵扯 proot |
| ② **模块拆两变体** | `mkmodule.sh --variant full\|bare`：`full`（**默认名**，内嵌 `dsh/dsh-<版本>.erofs.gz` + `dsh/manifest.json`）装完重启即可用；`bare` 不含 DSH。硬规则：`full` 不给 `--dsh-layer` **拒绝打包**、`bare` 给了也拒绝 —— 不许"不带 DSH 却顶着默认名" |
| ② 的落地与更新 | `customize.sh` 安装时调 `linuxctl dsh builtin`（gzip → 校验 `sha256_raw` → 落 `layers/` → 写 `etc/dsh-builtin.json`，幂等）；设备侧 `device-provision.sh` 认这个事实源并**跳过 dsh 构建**（那正是 `exit 78` 的成因）；一条指令 `linuxctl dsh install`（= `update dsh` 无文件参数）走**内置官方频道 + Ed25519 验签**；`rollback dsh` 不带 `--to` 时**优先回内置版本** |
| ③ **编译只在 CI** | `pipeline.yml` 只由 `main` 触发（`beta` 退化成纯存储，要出 beta 只能手动 dispatch）；`index.json` 加 `built_in_ci`/`builder` 与 `modules` 段；`mkmodule.sh` 在非 CI 环境打印"本地产物不作为发布来源" |

**CI/发布**：① prep 打 `bare`（APK 内嵌它；base/full 档 APK 的离线包已有 dsh，塞两遍没意义）；
② bundles 用频道里的 dsh 层打**默认模块**（`module-zip-full` 制品）；⑤ publish 两个变体一起发，
`index.json` 的 `modules.default` 指向 full，下载页两张模块卡片；频道里没有 dsh 层时
**不产出默认名**，只发 `-bare` 并在 job summary/下载页说明。

**App（免 root 切割 + 进入即启用）**：判定收进 `core/EditionPolicy.kt`（纯函数，两个方向都测），
`core/ProotBootstrap.kt` 决定"该不该自动启用"，`core/ProotProvisioner.kt` 是**唯一一份**
"宿主脚本 → 只补缺的内嵌包 → provision"流水线（手动入口与自动入口共用，避免漂移）；
自动入口在首启路径上跑到 `start` 后直接落「终端」页，失败显示**原始错误** + 重试 + 手动路径。
单测：`testProotFullDebugUnitTest` / `testRootFullDebugUnitTest` 各 **175/0**（新增 27 条：
`EditionPolicyTest` 4 / `ProotBootstrapTest` 6 / `EditionSeparationTest` 13 / `ModuleUpdateTest` +4）。
⚠️ 本机跑 gradle 需要 UTF-8 locale（`LC_ALL=C.utf8 LANG=C.utf8`）：POSIX locale 下中文测试方法名
会让 kotlinc 报 `InvalidPathException` —— 这是环境问题，CI 已经显式设了 C.UTF-8。

**回归**：新增 `tools/module-variant-selftest.mjs`（**31 条**，进 CI 的 shell 节点）——
真的打包两个变体、真的落地内置 DSH、真的用一个临时 Ed25519 频道跑通"一条指令装层 + 回滚到内置版"，
并含四个负例（无层打 full / bare 夹带 / 载荷被改坏 / 清单被改过）。既有门禁全绿：
root selftest 62/0（bash + mksh）、provision 83/0、oneshot 29/0、customize 16/0、
shell-compat / contract / cmp-consistency / webroot 64/0 全过。

**下一步（真机验收）**：见 `docs/module-variants.md` §六 —— 装 `full` 模块 → 重启 →
`layers/dsh-<版本>.erofs` 与 `etc/dsh-builtin.json` 就位 → `linuxctl doctor` 报内置版本 →
`linuxctl dsh install` 更新 → `linuxctl rollback dsh` 回内置。

### 3.10.26 内层禁止调用安卓系统工具（真机整机卡死事故；模块 1.0.20 / App 0.3.3）

**现象**：真机跑 start 后**整机卡死**，只能重启。

**证据**：`01:44:08 /system/bin/ndc resolver getresolvers`（uid 0）SIGABRT —— `Binder driver '/dev/binder' could not be opened. Error: 2`；同秒 5 个 tombstone，`01:45 system_app_anr`，`01:47 system_server_crash`。那 5 次 `ndc` 就是 `start.sh` 的 `gather_android_facts` 在内层拉起的（日志"完全取不到 Android DNS（ndc/getprop/resolv.conf/route 全部失败）"）。

**根因**：函数注释写的"宿主侧先取好"没错，但**调用点在内层**（`build_mount_tree`）——私有 mount/UTS ns 里 `ndc`/`getprop` 打不开 binder 与 `/dev/__properties__`。

**修法**：抓取移到父进程（`main()`、spawn 前）；内层新增 `install_android_facts()` 只做拷贝到 `rootfs/etc/`；`selftest.sh` 加两条文本回归 ⇒ **62/0（bash+mksh）**。另记：`ksud sepolicy` 的写法是 `allow <src> <tgt> <class> <perm>;`（**class 不带冒号**）；改设备脚本要先 `cp` 保权限位。

### 3.10.25 真机挂载归因：三处 bug + "这台机为什么起不来"的三条限制（App 0.3.2 / 模块 1.0.19）

用户贴出真机启动日志（loop 可写层 `I/O error` → 降级 dir 模式又 `EINVAL`），随后问："我这台机子起步困难，
后面适配其他安卓岂不是也困难？能不能做公共挂载适配，总不能源码级编译内核刷写？"

**① 修掉三处 bug**（都直接相关）：

| # | bug | 后果 | 修法 |
|---|---|---|---|
| ① | `mount_upper_rw` 的 `dst="$ROOTFS_DIR/upper"`，而 `architecture.md`/`stop.sh`/`linuxctl`/`doctor` 全按 `$LINUX_HOME/upper` | overlay 的 `upperdir`（`$UPPER_DIR/upper`）看到的是 f2fs 普通目录、**不是那块 ext4** ⇒ `upper.img` 白挂；而且它还被随后挂在 `$ROOTFS_DIR` 的 overlay 遮住 | `dst="$UPPER_DIR"`；`run/mounts` 支持绝对路径项、`stop.sh` 跳过绝对项（保留老版本 `rootfs/upper` 兜底） |
| ② | 探测判定表认不出 toybox 的 `Unknown option 'propagation'`（大写 U）与 `mount: bad /etc/fstab` | 真机 `cmdprobe` 谎报 `unshare_propagation=yes` / `mount_make_rslave=long` ⇒ 每次启动先调注定失败的 `--propagation`/`--make-rprivate`（日志里那些行就是它） | 统一 `probe_says_unsupported()`；并注明**不能用 `-o rprivate / /` 顶替**（toybox 判成 bind 且清 `MS_REC` ⇒ 非递归 bind 压住 `/`，`/data` 之类子挂载被遮） |
| ③ | overlay 失败既没记入参，`kernel_hint_log` 的 grep 又只含 `loop|ext4|jbd2`（把 `overlayfs:` 过滤掉） | 只剩 `Invalid argument`，无法归因 | 打"实际入参 + 内核原文"，摘要进 `last-error` |

**② 新增本机能力探测**（"公共挂载适配"的第一块砖）：`overlay_fs_usable()`（同 fs 两空目录只读试挂 ⇒ 实测这个
fs 能不能当层，接在两个 dir 入口**解包之前**，不再白等 1.6 GB）、`probe_loop_io`/`probe_fuse_mount` 进
`cmdprobe`、`PROBE_VERSION=2` 让老缓存失效重探、`loop_read=no` 时跳过 loop 自愈仪式。
只测**读**：读是同步的、可信；写会进页缓存（真机的异步失败是 `lost async page write`）。

**③ 真机三条限制（都有内核原文）**：

1. `overlayfs: filesystem on '/data/sunsetlinux/dirs-upper' not supported` —— `fs/overlayfs/util.c` 的
   `ovl_dentry_weird()` 命中 f2fs 的 `DCACHE_OP_HASH|DCACHE_OP_COMPARE`（casefold）。**只读 lowerdir 也不收**
   ⇒ dir 模式在这台机上不可能（内核规则，策略改不了）。
2. loop 的 I/O 在 `kernel` 域被 SELinux 拒：厂商只读 loop 背 `/system_ext` 的 `system_file` 正常；
   换成 `$LINUX_HOME` 下的文件后**连读都 FAIL**（`I/O error, dev loopN, sector 0 op 0x0:(READ)`）；
   `chcon system_file` 后读通、写仍被拒（`loop: Write error at byte offset 0`）⇒ 要 loop 只能补一条
   `allow kernel <type>:file { read write }`（**用户态文本规则、可撤销、不需要编/刷内核**）。
3. toybox 选项面差异：纯用户态（见 ①②）。

**④ 测试**：`runtime/root/selftest.sh` **42 → 58 条**，bash 与 mksh 双跑 0 失败；`shell-compat-check`、
`contract-check`（root/proot）、`cmp-consistency`（16 组）、oneshot/provision/detect-mount/customize/webroot
全绿。proot `selftest-funcs` 那 3 条端口用例在本容器因 `/proc/net/tcp` 受限失败（HEAD 上同样 3 条，与本次改动无关）。

### 3.10.24 两个可共存的 App + 数据驱动的内置矩阵（6 变体）+ 内置 DSH 与运行时 DSH 解耦（App 0.3.0）

用户三句话："拆"（两个 App）、"各种内置感觉不止 4 种吧"、"内置 DSH 的解耦（内置一个版本，
运行时一个版本的情况判定，避免更新导致崩了）"。

**① 两个 App**（不同包名、可同时安装，模式由 edition 锁死）：

| | Root 版 | 免 root 版 |
|---|---|---|
| 包名 | `io.github.sunsetrne.sunsetlinux.root` | `io.github.sunsetrne.sunsetlinux.proot` |
| 带什么 | KernelSU 模块包（一键刷入） | proot 宿主脚本 + proroot `.so` |
| 探测 | 需要 su（没有就报原因，**绝不偷偷降级 proot**） | **不探测 su**（免 root 设备上探测只会弹框） |

界面不再提供"切换模式"（设置/引导/部署向导都改成只读的"本版说明"）；Root 版不带 proot 脚本与
proroot（省 ~830 KB 且不牵扯专有许可），免 root 版不带模块包。

**② 内置矩阵数据驱动**：`tools/offline-bundle/variants.json` 加 `editions` / `tiers` /
每个变体的 `edition`+`tier`；现在是 **2 edition × 3 档 = 6 个变体**
（`root-minimal|root-base|root-full|proot-minimal|proot-base|proot-full`）。
**Gradle 的 flavor、每个变体的部件与标签全部从 JSON 读**，pipeline 的编译矩阵按
`"<edition>-<tier>"` 推 Gradle 任务名 → **加一档只改 JSON 一行**（构建脚本与 CI 都不用动）。
`SigningContractTest` 的断言改成"构建脚本必须真的在读 JSON" + "两个 edition 包名必须不同、
不能退回拆分前的老包名"（防身份漂移）。

**②·补记（0.3.1）**：①的"两个 App"在真机上**桌面同名** —— 0.3.0 只改了 `main/res` 的
`app_name`，按 flavor 覆盖的 `src/<edition>/res/` 不存在。已改为按 edition 设
`resValue("string","app_name", e.label)`（SunsetLinux Root / SunsetLinux 免root）并打开
AGP 9 默认关闭的 `buildFeatures.resValues`；契约测试补三条。同轮补了**"该内嵌的离线包真的
进了 APK"**的门禁（`tools/ci-assert-embed.sh`，0.2.6 就漏在这里，见 §3.10.23 与
`docs/release-ci.md` §三·一），并修 Release 说明里那个从 0.2.x 起就不存在的
`sunsetlinux-launcher-debug.apk`。

**③ 内置 DSH ↔ 运行时 DSH 解耦**：`core/DshPin.kt`（纯函数 + 9 条单测）把
"内置（随 APK 冻结）"与"运行时（可被频道更新）"对账成 6 种结论；「更新」页给两个版本号 +
一句人话 + **「回滚到内置版本 X」**（`linuxctl rollback dsh --to <版本>`）；「关于」页一行。
退路的保证是既有语义：装离线包时 dsh 层落到 `layers/`，而 `linuxctl update` **不删旧文件**。

### 3.10.23 CI 合成一条流水线（环境准备 → 矩阵编译 → 门禁 → 合并发布 → 频道 → 汇总）

用户拿了 KernelSU `build-manager.yml` 的截图作参照："环境准备，然后多个节点编译，
最后合并打包、多重发布（正式发布、pages发布、频道发布），一个工作流到底"。

| 节点 | 做什么 | 实现 |
|---|---|---|
| ① prep | 版本 + 由 `variants.json` 生成编译矩阵 + 打模块 zip | `pipeline.yml` |
| ② bundles | 内嵌离线包（四个组合各一份 `.bin`） | `uses: offline-bundle.yml`（`attach_release=false`） |
| ③ build | **编译矩阵：一个组合一个节点**（并行） | `uses: build-apk.yml` × 4 |
| ④ gates | shell(bash+mksh) / node / App 单测 | `uses: ci.yml`（`build_apks=false`） |
| ⑤ publish | 合并 → index.json → 下载页 → gh-pages → Release | `uses: publish.yml` |
| ⑥ channel | channel 分支的签名清单：公钥独立验签 → gh-pages `/channel/` | `pipeline.yml` |
| ⑦ report | 汇总（编译/门禁/发布任一红即红） | `pipeline.yml` |

- `release.yml` 变成**手动兜底**（同构，复用同一批工作流）；`ci.yml`/`offline-bundle.yml`/`release.yml`
  都**不再监听 push**（以前 push main/beta 会同时触发两条会写 gh-pages 的线）。
- 证据：main/beta 各跑一遍，**12 个节点全 success**；发布物 = 4 APK + 4 `.bin` + 模块 zip
  （同一 Release + gh-pages `/stable` `/beta`）；抽查官方 APK：`assets/module/`、`assets/offline-bundle.bin`、
  `libsunsetlinux_pty.so` 都在。
- 落地踩的 4 个坑（都留在文件注释里）：可复用工作流里的**工作流级 `concurrency`** 会被拒（调用方 job 0 秒失败且无日志）；
  搬文件时残留 `needs: gate` 会让整个文件加载失败；被调用方声明 `contents: write` 时调用方必须显式允许；
  GitHub 的宽松相等 `'' == false` 为真（push 事件下 `inputs.x != false` 会误判为假）。
- 配套：`tools/ci-shell-gate.sh`（把红的断言带成 `::error::` 注释）、`tools/ci-step-tee.sh`（每步留日志，
  失败诊断步骤把日志尾部带成注释）—— 步骤日志要仓库权限，注释匿名可读。

> **下一步（用户已拍板）**：拆成两个可共存的 App —— root 版 `io.github.sunsetrne.sunsetlinux.root`、
> 免 root 版 `…linux.proot`，各出"最小 + 完整"两档（共 4 个 APK）。pipeline 的矩阵是
> **数据驱动**（读 `variants.json`），所以拆分只需改变量表与 App 侧，workflow 不用动。详见 HANDOFF 第 35 条。

### 3.10.22 免 root 引导从「指路」到「真的能铺」+ proot 支持 erofs 层（App 0.2.12 / 模块 1.0.18）

用户贴的 App 诊断页输出（模式 **PROOT**）：`✗ 没有找到 linuxctl：…/files/sunsetlinux/bin/linuxctl /
请先在侧边栏「重新部署 / 首启引导」里完成部署。` 用户的判断是对的："部署引导不完整，
Root 和非 Root 流程需要不一致，进一步从 UI 上切割区分开来"。

**两条根因**（都属于"引导把用户指向一条不存在或走不通的路"）：

| # | 引导里写的 | 真相 |
|---|---|---|
| 1 | "到「更新 → 本机包 → 离线安装」点一下" | 免 root 的宿主脚本（`linuxctl.sh`/`start.sh`/`entry.sh`）是 **APK 内置资产**（`assets/proot-runtime/`，50 KB），`ProotRuntime` 会铺，但**界面上没有这个动作**；离线安装页装的是层与 proot 二进制，不铺 `bin/` |
| 2 | "从频道安装三层，再回来启动" | 频道/离线包给的是 **erofs** 层，而 proot 的 `linuxctl` 只认 tar（`archive_test` 判损坏、`extract_archive` 用 tar 解）→ 免 root 模式下**不可能成功** |

**App 0.2.12**：

- 新增 `core/ProotSetup.kt`：`inspect()`（纯文件检查，不需要 root）查"宿主脚本 / proot 运行时 /
  rootfs / 层 / 种子 / 内嵌包"，`plan()` 给出有序步骤与每步的动作文案（**纯函数 + 5 条单测**）；
- 首启引导**按模式给不同的步骤名与动作**：root `选模式/装模块/部署/完成`，
  免 root `选模式/铺运行时/铺环境/完成`；
- 免 root 分支三张卡片三个真按钮：「铺 proot 运行时（内置脚本）」= `ProotRuntime.ensure`；
  「铺环境」= 内嵌离线包（若有）+ `linuxctl provision`；「打开部署向导」兜底；
- 「环境检测」在免 root 下显示 **脚本 / rootfs** 两枚 Pill（模块那枚与该模式无关）；
- 诊断页缺 linuxctl 时按模式给不同的下一步；部署向导在 proot 下**先自动铺脚本**再往下走，
  `NEED_CHANNEL` 的提示改成现在真的可执行的路径。

**模块 1.0.18（运行时）**：

- `is_erofs`（magic 0xE0F5E1E2 在**偏移 1024**）、`archive_test` 按 magic 校验 erofs
  （以前落到默认分支 = 什么都不查就放行）、`extract_archive` 走 `fsck.erofs --extract=<dir>`
  （与 root 模式 dir 模式同一工具，`SUNSETLINUX_EROFS_EXTRACT` 可覆盖）→ **proot 与 root 用同一份层**；
- `find_base_layer` + `provision` 回退：没有 tar 种子时，**已就位的 base 层直接当 rootfs 解出来**
  → 免 root 用户有了零手工来路（频道/离线包装 base 层 → `provision` → 启动）；
- `doctor` 新增**部署就绪度**：`bin/linuxctl` / proot 运行时 / rootfs（+ 有层但无 `fsck.erofs` 的告警），
  每条都写"点哪里补"。

**证据**：`runtime/proot/selftest-funcs.sh` 新增 10 条（erofs magic 正反例 / `archive_test` 不再放行损坏镜像 /
解包器收到 `--extract=<dir>` / 解包器失败必须显式失败 / tar 路径回归 / `find_base_layer` 正反例），
bash+mksh **78 通过**（各有 3 条**既有**失败：本容器 `/proc/net/tcp` 受限导致 `port_open_local` 那组跑不过，
已在 HEAD 上复现同样 3 条）；App 单测 **139/0**（含新增 `ProotSetupTest` 5 条）；
真实 `fsck.erofs --extract=/tmp/out dsh-rc2.erofs` 在容器里跑通（211 MB 层约 73 秒，符合"解包是分钟级 IO"的预期）。

### 3.10.21 引导第 2 步的模块包内嵌 + doctor 三处假警报 + upper 读写挂载自愈（App 0.2.11 / 模块 1.0.17）

用户给了首启引导第 2 步的截图（"在勾选的地方做 Root 模块刷写引导，内置模块包"）与整段真机
doctor 输出（"顺便解决一下日志问题"）。三件事：

**① 引导第 2 步原来不可执行。** 文案写"选择模块包：`dist/sunsetlinux-module-0.1.0.zip`" ——
那个路径在开发者机器上；于是「我已经装好模块并重启」勾选框前面没有任何可点的东西。
现在 `dist/sunsetlinux-module-*.zip` 在构建期被内嵌成
`assets/module/sunsetlinux-module.zip` + `assets/module/module.json`（版本/文件名/大小/sha256，
由 `SyncBundledModule` 算出；`dist/` 为空时输出"未内嵌"并如实说明）。卡片上：

| 动作 | 实现 | 失败时 |
|---|---|---|
| 一键刷入内置模块 | assets 落盘（**校验构建期 sha256**）→ `ksud module install`（Magisk 走 `magisk --install-module`） | 明说失败原因，并指向导出/手动路径 |
| 导出模块包到 Download | `su cp` 到 `/sdcard/Download/` | 退到 FileProvider 分享，让用户自己存 |
| 打开管理器 | 已知包名 4 个（KernelSU / KernelSU-Next / MMRL / Magisk） | 一个都没装就明说，不弹空 chooser |

勾选框的启用条件 = **用户断言 或 设备侧事实**（模块已装 + 已启用 + 已重启生效）—— 事实成立时
不再逼用户复述"我装好了"。App **不替用户重启**（KernelSU 落 `modules_update/`，重启才生效）。

**② 诊断页的"原因"是章节名。** `✗ 自检未通过：== 0. 运行环境 ==` —— 流式 doctor 的
`CtlResult.message` 取的是 stdout 第一行非空内容，而 doctor 第一行非空内容就是第一个小节标题。
改为解析 JSON 尾行的 `fails`/`warns`，并附上报告里带 `[fail]` 的原文（最多 6 条）。

**③ 真机一次自检报 4 项 fail，其中 3 项是假的**（逐条核过）：

| 原 fail | 根因 | 现在 |
|---|---|---|
| `CONFIG_SQUASHFS 未启用` / `内核不支持 squashfs` | 本项目三层只读镜像用 erofs；squashfs 只是另一种可选格式，缺它不影响任何东西 | 降 info/ok；只有"两种格式都不可用"时 §2 才真 fail |
| `dsh 层内没有 /root/.dsh/profiles/**` | 拿 `dump.erofs --ls --path=/` 的输出 grep 深层路径，而它**不递归**（根目录只有 `root`/`usr`）→ 永远匹配不上 | 新增 `layer_has_path()`：对着目标路径问（erofs `--path=`、squashfs `unsquashfs -l`） |
| `runtime 层里没有 pnpm` | 同一处假阴性 | 同上 |
| （顺带）loop 计数 | `grep -c ":'"` 恒为 0 → "在用 1 个"却列出 4 个 | 按 `/dev/` 行前缀统计；不指向我们的 loop 只报 info |
| （顺带）avc denial | 人类可读说"与本项目无关"，JSON 却给 `warn` | 相关性作为唯一判据：无关 → `ok` |

**④ 唯一真 fail：`挂载 upper 失败：… -o loop,rw,noatime … I/O error`。** 证据链（全部只读核对）：
`upper.img` 的 mtime 停在创建时刻（**从未成功读写挂载过**）、`-o loop,ro` 能挂、`e2fsck -fn` 通过、
ext4 特性全在内核支持面内、且存在**指向该镜像的残留 loop**。ext4 只在 rw 挂载时写超级块/恢复日志，
那条路径上的写失败被内核统一报成 EIO，光看 errno 推不出原因。`start.sh` 因此改成逐级自愈：
清残留 loop（只清没被任何进程挂载的）→ `e2fsck -p` → 显式 `losetup -f --show` + `mount` 两步走 →
抓 `dmesg | grep -iE 'loop|ext4|jbd2'` 原文进 `run/start.log` 并把摘要压进 `last-error`；
四次都失败则**自动降级 dir 层模式**（全程不碰 loop/upper.img），并把原因与代价写进日志。

- doctor 也补了对应检查：新增 `SUNSETLINUX_KCONFIG_FILE` 接缝（指一份内核配置文本，让
  "内核没开 squashfs 不得报 fail"这条闸门在 CI 容器里也跑得到）、**§3 新增读写试挂**（只读能挂 ≠ 读写能挂，这正是 §3 与 §8 自相矛盾的那次）、
  §8 区分"历史记录 / 当前故障"（环境在跑时 last-error 是历史，不再当 fail）、e2fsck 结论带原文。
- 测试：`runtime/root/selftest.sh` 新增 6 条（squashfs 不得 fail、层内路径正/反例、
  `mount_upper_rw` 在 toybox `-o loop` 失败后靠显式 losetup 挂上、失败后确实先清 loop 且跑过 `e2fsck -p`），
  bash+mksh **42/0**；App 单测 **134/0**（本机 locale 为 POSIX 时 Kotlin 增量编译会因中文测试名
  写出不可映射的文件名而 ICE：`gradlew --stop` 后以 `LANG=C.UTF-8` 重跑即过，与本次改动无关）；
  模块 zip `dist/sunsetlinux-module-v1.0.17.zip`（209116 B，
  sha256 `88c00d8e762bec43c4d2c4b3dcedee064ba1436940760c2b953c62a06af27dd9`）；
  APK `SunsetLinux-0.2.11-minimal-debug.apk` 内含同一份模块包（`module.json` 的 sha256 与包一致），
  签名 `5d5fa724…69f7`（仓库内固定密钥 ⇒ 可覆盖安装、KSU 授权不失效）。

### 3.10.20 无 loop 层模式（dir）+ doctor §1e（App 0.2.10 / 模块 1.0.15）

用户选了「A. 加"无 loop 目录模式"（作兼容开关）」。真机上最容易出问题的不是 overlayfs，
而是它上游那条链（losetup → erofs → upper.img(ext4 loop) → overlay）；"目录 + overlayfs"
是内核确认支持、依赖最少的基本用法。

| | loop（默认） | dir（新） |
|---|---|---|
| 只读层 | losetup + erofs 挂载 ×3 | `fsck.erofs --extract` 解成 `dirs/{base,runtime,dsh}` |
| 可写层 | upper.img（ext4+loop） | `dirs-upper/`（真目录，**不需要 upper.img**） |
| 内核资源 | 4 loop + 3 erofs + 1 ext4 | **0 loop、0 镜像挂载** |
| 代价 | 省磁盘（550 MB） | +1.6 GB、首次解包几分钟（有戳，之后幂等） |

- 开关优先级：`--layer-mode` > `SUNSETLINUX_LAYER_MODE` > `etc/config.json` 的 `layer_mode` > 默认 loop；
  非法值在参数解析后**立刻报错**。
- App：「设置 → 层模式」可切（透传环境变量），「关于」与 `status` 显示**本次 start 实际用的**（`run/layer-mode` → `layer_mode` 字段）。
- 安全边界：解包前校验 erofs；解包器缺失**明确失败**（`SUNSETLINUX_EROFS_EXTRACT` 可覆盖）；解包按"文件名+字节数"打戳，幂等，换层只重解那一层。
- doctor 新增 **§1e 层模式**：当前模式、解包器可用性、三层戳是否与层文件一致、解包占用；loop 模式缺 `upper.img` 时提示可切 dir。
- 测试：`runtime/root/selftest.sh` 新增 14 条（解析优先级 6 条 + 目录模式解包 5 条 + doctor 结论等），
  bash+mksh 双跑 **34/0**。

### 3.10.19 挂载冲突检查（§1d，模块 1.0.14）

用户的问题：「我主要是怕挂载冲突的问题，模块本身就有自动挂载机制，项目的模块再写个挂载，怕不是会冲突哟。」

结论：**不会冲突**，依据（写进 `docs/mount-conflict.md`）：

| 维度 | KernelSU 的模块挂载（metamodule） | 我们的环境挂载 |
|---|---|---|
| 目标 | `/system`、`/vendor`、`/product`…（Android 系统路径） | `$LINUX_HOME/**`（我们的目录）+ rootfs 内部 bind |
| 实现 | overlayfs（meta-overlayfs）或**内核级路径重定向**（magic_mount_rs），不产生 mount 条目 | 真 mount：erofs/ext4/overlay/bind |
| 作用域 | 全局 | 我们 `unshare -m` 出来的**私有 namespace** |
| 约定 | 必须 `source=KSU` | 不标 KSU → KSU 不认也不管 |

三条硬理由：① 目标路径不相交；② 命名空间不同（我们的挂载不出现在 PID 1 的 mountinfo）；
③ 我们的模块**没有 `system/` 目录** → metamodule 对本模块无事可做。

**为什么不能"交给 metamodule"**：它的钩子契约是"模块目录 → Android 系统路径"，没有通用挂载服务；
且 metamodule **单实例**，自带一个会顶掉用户的 `magic_mount_rs`，其它模块全部失效。

新增 doctor **§1d 挂载冲突检查**（五条判据：模块自身是否含 system/、私有命名空间隔离、
全局挂载表泄漏、loop 占用、运行期挂载点存续），环境未运行时降级为 info 不硬断言；
`runtime/root/selftest.sh` 加断言"doctor 必须给出 mount_conflict 结论"。

### 3.10.18 真机「start.sh 失败 / 层永远挂不上」第二起：探测被 `set -e` 带走（模块 1.0.13）

用户贴的 `linuxctl doctor` 里，唯一真 blocker 是 `== 8` 的 `start.sh 失败`。设备日志
`run/linux.log` 停在「开始探测 toybox 的 mount/unshare 选项支持」，`run/cmdprobe` 是
**0 字节** —— 说明脚本在探测第一行就没了。本地 1:1 复现（`start.sh --probe-only` 退出 1、
cmdprobe 0 字节），根因：

```
set -euo pipefail
out="$(mount --rbind /nonexistent …)"; rc=$?     # ← 这次"预期失败"直接终止整个脚本
```

探测命令**本来就是设计成失败的**（拿不存在的源去 mount，只看它是否把参数判成语法错误）。
而 `set -e` 下"只含赋值的简单命令"以命令替换的退出码为准 → 脚本当场退出。

后果极重：`linuxctl start` **从来没走到真正的挂载步骤**，用户看到的就是「层都在、却永远未挂载」。

| 事 | 之前 | 现在 |
|---|---|---|
| 探测里的 4 处 `out="$(mount …)"; rc=$?` | 预期失败 → 脚本退出 | `rc=0; out="$(…)" \|\| rc=$?` |
| `probe_get` 里的 `sed … \| head` | `pipefail` 下读失败也会带走脚本 | 加 `\|\| true` |
| `run_probes` 整体 | 裸跑在 `set -e` 下 | 整段 `set +e` … `set -e`（未来新增探测也不会再踩） |
| 回归 | 没有 | `runtime/root/selftest.sh` 新增"`--probe-only` 必须跑完并落 `probed=`"，bash+mksh 双跑 |

同一份 doctor 里还暴露一个**误报**：`本模块是否需要挂载: 是`（模块是纯脚本模块，安装时
同一段逻辑说"否"）。根因：doctor 用 `sh -c '. <detect>; detect_mount_json'` 跑探测，
`$0` 是 `sh` → detect-mount.sh 的"默认取脚本上一级"退化成**当前工作目录**；用户在 `/` 下
跑时 `/system` 必然存在 → 误判。现在：调用方必须显式给 `MODULE_ROOT`（doctor 用已安装模块
路径、customize.sh 用 `$MODDIR`），没给则只认 `/data/adb/modules[._update]/sunsetlinux`，
两条都不成立返回 2 = **未知**（报告写"未知"，不硬断言）；新增
`tools/detect-mount-selftest.mjs`（5 条，含"cwd 里有 system/ 也不许误报"）并接入 CI。

顺带把 avc 噪声分级：只有 denial 里**确实提到 `/data/sunsetlinux`** 才 warn，否则降为 info
（真机上那 242 条全是厂商 HAL 扫 ksu 进程，与本项目无关）。

模块 **1.0.13**（start.sh / doctor.sh / detect-mount.sh / customize.sh 都随模块走；
开机 `post-fs-data.sh` 会把它们同步到 `$LINUX_HOME/bin`）。

### 3.10.17 终端接上原生 PTY（App 0.2.9）

| 事 | 之前 | 现在 |
|---|---|---|
| 连接方式 | `ProcessBuilder` 普通管道（没有 termios） | **原生 PTY**：`/dev/ptmx` → grantpt/unlockpt/ptsname_r → termios（IUTF8；关 IXON/IOFF；保留 ISIG/ICANON）→ TIOCSWINSZ → fork → 子进程 setsid+打开从设备+dup2+execve |
| Ctrl-C | **无效**（0x03 只是普通字节；本地实测：无 PTY 时进程毫无反应） | 有效 —— 行规程把它变成 SIGINT（本地实测：有 PTY 时子进程的 INT trap 触发） |
| 全屏程序 | `vim`/`htop`/`top` 不可用 | 能跑；窗口大小按控件尺寸发 `TIOCSWINSZ` |
| 渲染 | 按行拼接，`\r`/`ESC[K` 全当正文 → 花屏 | 最小 VT 模拟器（纯 Kotlin，12 条单测）：CR 覆盖（CRLF 例外）、`\b`、`\t`、`ESC[K/J/H/A-D/G`、OSC 标题、SGR 剥离、滚屏、增量 UTF-8 |
| 原生库缺失时 | —— | **优雅降级**回行缓冲 + 界面明说原因（`PtyNative.loadError`） |
| 构建 | 无原生代码 | `tools/ndk-build-pty.sh` 自己驱动 clang（官方 NDK 只有 x86_64 宿主工具链，aarch64 环境跑不了 AGP 的 externalNativeBuild）；产物 11.6 KB 进 `jniLibs/arm64-v8a` |

顺带纠错：`TerminalSession.kt` 原注释"Android 上没有随系统可用的伪终端分配接口"是**错的**
（`/dev/ptmx` 可用、bionic 有 grantpt/unlockpt/ptsname_r，Termux 走的就是这条路），已在文件头更正。

### 3.10.16 免 root 运行时：proroot 首选 + proot 降级（App 0.2.8）

| 事 | 之前 | 现在 |
|---|---|---|
| 非 root 模式的运行时 | 只有 proot（ptrace：每条系统调用一次上下文切换） | 首选 **proroot**（LD_PRELOAD，无 ptrace 往返），proot 仍随包**降级** |
| 选择 | 无（写死 proot） | `SUNSETLINUX_ROOTLESS=auto\|proroot\|proot`（App「设置 → 免 root 运行时」），也读 `etc/config.json` 的 `rootless_runtime`；`auto` 缺件降级并**记原因**，显式 `proroot` 缺件**明确失败**（不静默降级） |
| 实际用了谁 | 看不出来 | `linuxctl status` 新增 `rootless:{kind,version}`（`mode` 契约仍是 `proot`）；`doctor` 有 `rootless_runtime` 检查项；App 设置页与关于页都显示；`start.sh` 写 `run/rootless` 供另一个进程读 |
| 打包 | proot bundle 解到 `$LINUX_HOME/proot/` | proroot 5 个 `.so` 随 APK 进 `jniLibs/arm64-v8a`（四个组合都带，+0.2 MB）；App 透传 `SUNSETLINUX_NATIVE_LIB_DIR` |
| 许可 | proot 是 GPLv2（可自由分发） | proroot 是**专有**：只随完整 APK 分发、不得再分发修改版 → 二进制不进仓库/不进 Release 资产/不 strip；APK 内带许可原文 + 关于页 attribution；`tools/proroot/compliance-test.mjs` 每次构建做闸门 |

测试：`runtime/proot/selftest-funcs.sh` 新增 5 条（auto 选 proroot、缺件降级并说明原因、
显式 proot 不被抢、显式 proroot 缺件必须失败、参数前缀与 proot 同一套），bash 与 mksh 双跑；
Kotlin 侧新增 `RootlessStatusTest`（3 条：proroot 在用时 mode 仍是 proot、降级时如实报、
老脚本没有该键时是 null）。

### 3.10.15 模块安装文案（模块 1.0.12）

用户刷完模块贴出安装日志，指出两件事：文案该更新，而且有一段**被截断**了。

| 事 | 之前 | 现在 |
|---|---|---|
| 那段句子 | `ui_print "   只在"没有任何 base 层"时触发…"` —— 双引号串里又写了 ASCII 双引号，shell 把句子切断，后半句被当成**额外参数**吞掉（真机上就是"只在没有任何"后面没了）。`mksh -n`/`bash -n` 都认为合法，语法闸门抓不到 | 整段重写；并新增 `tools/customize-selftest.mjs`：沙箱里真跑一遍 `customize.sh`，按**内容**断言关键句子完整打出，另有静态判据"每个 ui_print 的参数里只能有一对双引号"。已用注入缺陷验证过它会变红 |
| 推荐路径的顺序 | 开头就是「还需要做一次首次部署（provision）」+ 半小时的手动命令，末尾才提"更省事：装完重启就行" | **先说"装完重启一次就行"**（1.0.9 起开机自建层，零点击），手动 `device-provision.sh` 与 App「更新」页从频道装层作为两条可选路径 |
| 命令写法 | 文案里还有裸 `sh` 的示例 | 一律 `/system/bin/sh`（模块环境里裸 `sh` 可能是 busybox ash），并解释为什么 |
| 版本可见性 | 刷完不知道装的是哪版 | 打印 `本次安装的模块版本：v1.0.12` |

### 3.10.14 真机「层都在、却永远未挂载」（模块 1.0.11，2026-09-16 实测）

用户截图：`layers/` 三个文件都在（240 MB + 625 MB + 198 MB）、`etc/state.json` 也齐，
App 却永远显示「未挂载 / 失败（文件/层缺失）」。设备 `run/service.log` 里只有一行：

```
/data/sunsetlinux/bin/linuxctl: line 34: syntax error: bad substitution
```

| 事 | 之前 | 现在 |
|---|---|---|
| 开机自启的发起方式 | `service.sh` 写 `sh "$CTL" start` —— 模块 PATH 前面是 KernelSU 的 busybox，`sh` 就是 **busybox ash**；ash 对 `${BASH_SOURCE[0]:-$0}` 直接 `syntax error: bad substitution`，脚本一行没跑 | 优先**直接执行**（内核按 shebang 用 `/system/bin/sh`＝mksh），退路也显式 `/system/bin/sh`；`uninstall.sh` 同样处理 |
| 挂载的调用方 | `linuxctl start` 用 `bash start.sh`（`/system/bin/bash` **实测不存在**）；`stop.sh` / `update.sh` / `doctor.sh` / `status.sh` 同样 | 新增 `pick_shell()` / `$SH_BIN`：宿主/CI 用 bash，Android 用 `/system/bin/sh`，可用 `SUNSETLINUX_SH` 覆盖 |
| 设备侧脚本 | 9 个脚本用 `${BASH_SOURCE[0]:-$0}`（ash 下致命） | 一律改 `$0`；需要判断"是否被 source"的地方继续用显式 `SUNSETLINUX_SOURCED=1` |
| 为什么本地/CI 查不出来 | `bash -n` 与 mksh 都能过；只有"被 ash 解析"或"设备上没有 bash"时才炸 | `tools/shell-compat-check.mjs` 加三条闸门：`BASH_SOURCE[...]`、`bash <脚本>`、裸 `sh <脚本>`；`runtime/root/selftest.sh` 加行为回归：**剥掉 bash 的 PATH** 跑 `linuxctl start`，必须是业务错误（缺少层文件），不许 `bad substitution` |

证据链：`service.log` 的那一行（真机）→ 本地用 `busybox ash` 复现出**逐字相同**的
`<file>: line N: syntax error: bad substitution` → `ls /system/bin/bash` 不存在 →
`linuxctl start` 在无 bash 的 PATH 下现在给出「缺少层文件 base.erofs」。

### 3.10.13 发布完整性：APK 少内嵌 = 静默失效（2026-09-16 补）

0.2.6 首次发布时四个 APK 里三个只有 12 MB —— `assets/offline-bundle.bin` 没进去。
根因不是打包工具，而是 release 路径取 proot 运行时用了**无认证**的 releases API：
它按 IP 限 60 次/小时，runner 共享 IP 偶发 403；403 在这里只让变量变空串、
**一行 warning 都没有**，`mk-bundle --available` 于是把含 proot 的三个变体"合法地"跳过。
现在：API 带 token、拿不到 proot 直接 `::error::` 退出、打包后强制 4 个 `.bin` 齐全，
缺一个就红并打印已有的与频道清单里的层。本地用桩干跑了两条失败路径。

**这条的教训值得单独记**：`|| true` + `--available` 这种"优雅降级"组合，
在**发布路径**上等于"静默发出残缺产物"；发布路径宁可红。

真机依据：设备上 `/data/adb/ksu/bin/ksud`（指向 `/data/adb/ksud`，5.6 MB）确实存在；
`ksud module install <ZIP>` 的语法取自上游 `userspace/ksud/src/cli.rs` 的 `Module::Install`。
线上 `/stable/index.json` 已含 `module_version=1.0.10` 与模块 zip 的 sha256，App 端能直接对上。

### 3.11 ★ proot「能力缺失」审计：哪些是固有限制、哪些能补（2026-09-16 第五批）

用户："解决后续的 proot 能力部分支持缺失问题（感觉这个坎绕不过去了）"。
先把差距按**能不能补**分清（完整表见 `runtime/proot/README.md` §2.2），再动手补能补的：

**固有（没有 root 就是没有，别当待办）**：无 mount、无命名空间隔离、无分层（overlay/squashfs）、
环境根只能在 App 私有目录、sdcard 走 FUSE、生命周期随 App、没有真 capabilities。

**能补的三项（本轮补了两项）**：

| 项 | 之前 | 现在 |
|---|---|---|
| 随包 proot 版本 | **5.1.0（2014 年）**，选项只有 14 个 | **5.4.0**（`--link2symlink`、`--kill-on-exit`、`--port`、`--netcoop`、`--mixed-mode` + 十年 ptrace 修复）。bundle 重建：988,717 字节（0.94 MiB），`sha256 3d72e0c3…`，GPLv2 全文 + Debian copyright + SOURCE 齐全，`--verify` 通过 |
| `--link2symlink` 开关 | 一开配置就 `proot error: unknown option` + fatal —— **整个 start 起不来**（实测 5.1.0） | `start.sh` 先问一次 `--help` 再决定加不加；不支持则跳过 + 提醒（用真实 5.1.0/5.4.0 两个二进制验过两条分支） |
| proot 运行时 / rootfs 的分发 | **App 没有 `assets/`**，doctor 里"App 应随包携带"一直是空话 → 非 root 用户开箱即用不了 | 发布页现在带 `proot-bundle-arm64.tar.gz`（~1 MiB）与离线种子（~59 MiB），至少有了稳定 URL；**建议下一步塞进 APK assets**（1 MiB 成本） |

顺带修了 `mkproot-bundle.sh` 的自检判据：它原来要求 `--version` 能解析出 `x.y.z`，
而 Debian 的 5.4.0 构建时没塞版本号、banner 末尾打印的是 `-`——于是**完全可用的 bundle 被判失败**。
现在判据是"退出码 0 + 输出非空 + `--help` 里有 `--rootfs`"（比版本号更接近"它真的能当 proot 用"）。
