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

> ⚠️ 这一节是**早期轮次**的记录（当时的产物名是 `sunsetlinux-launcher-*`）。
> 现在的形态看 §3.10.24：**两个 App（`…​.root` / `…​.proot`）× 三档内置 = 6 个
> `SunsetLinux-<版本>-<组合>-debug.apk`**，由 CI 发到 Release `v<版本>` 与
> `gh-pages` 的 `/stable/`、`/beta/`（见 `docs/release-ci.md`）。

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
