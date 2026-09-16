# App 版本变更说明

> `app/version.properties` 只放 `versionName` / `versionCode` 两个键值；
> **每个版本的来龙去脉写在这里**（新增一版时先加条目，再改 version.properties）。
>
> 版本号规则：Android 只按 `versionCode` 判"是不是新版"，所以**每次对外发布都要 +1**。
> 四个内置组合（minimal / ubuntu / ubuntu-proot / ubuntu-proot-dsh）是同一个 App，
> **共用同一组 versionName/versionCode** —— 换组合就是覆盖安装另一个 APK，数据不丢。

| versionName | versionCode | 内容 |
|---|---|---|
| 0.1.0 | 1 | 首个可用外壳：模式选择、部署向导、状态页 |
| 0.2.0 | 2 | 内置终端、DSH 入口上顶栏、更新进侧边栏、内置官方频道 |
| 0.2.1 | 3 | 部署向导缺层时改调 `device-provision.sh`（`linuxctl provision` 不建层） |
| 0.2.2 | 4 | 模块版本提示更正为 ≥1.0.6（1.0.5 有 `%q` 与 profiles 两个坑） |
| 0.2.3 | 5 | **修"一打开就闪退"**：zstd 能力探测走 aircompressor 的 direct-buffer/Unsafe 快路径，在 Android 上 SIGSEGV；改成流式解码 |
| 0.2.4 | 6 | root 检测升级为可解释状态（无 su / 被拒 / 超时 / 已授权）+ 模块检测（装没装、版本、停用、装了没重启）；「更新」页修好"本地版本读不出来 ⇒ 看不到可更新层" |
| 0.2.5 | 7 | **离线安装真正可用**：内嵌离线包一键装（读包 → 校验 → 解压 → 镜像校验 → `linuxctl update`，全程不联网）；proot 宿主脚本随 APK 走 assets（免 root 版不再要求用户手动解包）；模块检测改三态（**读不到 ≠ 没装**）；「更新」页把频道检查失败与"已是最新"分开说，并显示本机包/内嵌包对照 |
| 0.2.6 | 8 | **App 内更新 KernelSU 模块**：关于页读官方 `index.json` 拿到最新模块版本/sha256/Release 地址，一键「下载 → 校验 → `ksud module install`」（重启由用户决定）；模块版本比较改**逐段数字**（`1.0.10 > 1.0.9`，字符串比会反） |
| 0.2.7 | 9 | **修"层都在、却永远挂不上"**（模块 **1.0.11**）：开机自启用裸 `sh` 发起 → 撞上 busybox ash（`${BASH_SOURCE[0]}` 直接 `syntax error: bad substitution`），而且 `linuxctl` 用 `bash start.sh` 去挂载 —— 而 Android 上没有 bash。现在统一走 `$SH_BIN`（宿主/CI 用 bash，设备用 `/system/bin/sh`），并加了三条回归闸门。App 本身无代码改动，只是与模块 1.0.11 一起发 |

## 0.2.7 —— 挂载修好了：**层都在，却永远"未挂载"**

用户截图：三个层文件都在（`layers/` 里 240 MB + 625 MB + 198 MB，`state.json` 也齐），
App 却一直显示「未挂载 / 失败（文件/层缺失）」。查设备 `run/service.log`，只有一行：

```
/data/sunsetlinux/bin/linuxctl: line 34: syntax error: bad substitution
```

两个原因叠在一起，都是"以为设备上有 bash"：

1. **模块自启用裸 `sh` 发起**：`module/service.sh` 里是 `sh "$CTL" start`。模块环境里
   PATH 前面是 KernelSU 的 busybox，`sh` 解析成 **busybox ash**（不是 mksh）——
   而 ash 见到设备侧脚本里的 `${BASH_SOURCE[0]:-$0}` 直接 `syntax error: bad substitution`，
   脚本**一行都没跑**（这也解释了为什么 App 里的 `linuxctl status` 是好的：App 经
   `su -c` 直接执行文件，内核按 shebang 用 `/system/bin/sh`＝mksh）。
2. **`linuxctl start` 用 `bash start.sh` 去挂载**：设备上 `/system/bin/bash` **不存在**
   （实测 `ls: No such file or directory`），所以就算跑到这一步也必然失败。
   同一个模式还在 `stop.sh`／`update.sh`／`doctor.sh`／`status.sh`／`uninstall.sh` 里。

### 修法

- `linuxctl.sh` 新增 `pick_shell()` / `$SH_BIN`：**宿主/CI 用 bash，Android 用
  `/system/bin/sh`（mksh）**，可用 `SUNSETLINUX_SH` 覆盖；`start/stop/update/doctor`
  一律走它。
- `service.sh`／`uninstall.sh` 不再写裸 `sh`：能直接执行就**直接执行**（走 shebang），
  否则显式 `/system/bin/sh`；`update.sh`／`selftest.sh` 内部子壳也改 `$SH_BIN`。
- 设备侧脚本里所有 `${BASH_SOURCE[0]:-$0}` → `$0`（busybox ash 也解析得了；
  真要判断"是否被 source"的地方仍用显式的 `SUNSETLINUX_SOURCED=1`）。
- `tools/shell-compat-check.mjs` 加三条闸门（都对应这次事故）：
  `BASH_SOURCE[...]`、`bash <脚本>` 调用、裸 `sh <脚本>` 调用 —— 都是"本地 bash 跑得通、
  真机一行都跑不了"的类型；并加一条行为级回归：用**剥掉 bash 的 PATH** 跑
  `linuxctl start`，必须给出业务错误（缺少层文件），不许出现 `bad substitution`。
- `tools/provision-selftest.mjs` 同步断言"发起必须是 `/system/bin/sh` 且不许裸 `sh`"。

### 怎么拿到

刷 **模块 1.0.11**（模块带 `service.sh` 与全部脚本）。设备上装 0.2.6+ 的 App 可以直接在
「关于 → KernelSU 模块 → 下载并刷入 1.0.11」，然后**重启**：`post-fs-data.sh` 会把新脚本
铺到 `$LINUX_HOME/bin`，`service.sh` 用新方式发起 `linuxctl start`，层才会真正挂上。

> 同一 App 版本（0.2.7）之后又单独发了模块 **1.0.12**：只改安装文案（修被截断的句子、
> 把"装完重启就行"提到最前、命令统一 `/system/bin/sh`、打印模块版本），App 侧无改动。

> 最小版 APK 不受影响也不特殊：它只是"内嵌包只带 proot"，层要么等模块设备侧自建、
> 要么从频道装；挂载路径与其它组合完全一样。


## 0.2.6 —— 「关于」页把模块更新做到"点一下就能刷"

上一版把"关于"页要说清的东西补齐了（本机包、内嵌内容、模块状态），但**没有下一步**：
用户看到 `模块 1.0.9（已启用）`，要更新还得自己去 GitHub 找 zip、再打开 KernelSU 管理器
手动安装。用户的原话是"更新模块『关于』页面，使其更新实际可用"——所以这一版把链路接上。

### 能做什么

「关于」页新增 **KernelSU 模块** 卡片：

1. 「检查模块更新」→ 读官方站 `index.json`（先 `/stable/`，失败退 `/beta/`），
   从 `module_version` + `github_release_tag` + `files[]` 拿到**最新版本、下载地址、sha256、大小**。
   这些字段是发布流水线同步生成的，所以 App 不用猜版本号，也不用自己拼 URL。
2. 已装版本与最新版本用**逐段数字比较**（`1.0.10 > 1.0.9`）——
   字符串比较会得出 `1.0.9 > 1.0.10`，那会"永远不提示更新"，是个典型陷阱。
3. 「下载并刷入 1.0.10」→ 下载到缓存（带进度）→ 与索引里的 sha256 比对 →
   `su` 里把 zip 复制到 `/data/local/tmp/` → `ksud module install <zip>`。
   没有 `ksud` 时退到 `magisk --install-module`；两者都没有就明确说"先在管理器里装一次模块"。
4. 刷完只报告"**重启后生效**"（KernelSU 落的是 `modules_update/`），
   **App 绝不替用户重启**（脚本里有单测断言不许出现 `reboot`）。

### 几个刻意的选择

- **先复制到 `/data/local/tmp` 再刷**：缓存目录在 App 私有路径下，SELinux 上下文与
  用户手动放进去的文件不同；`ksud` 以 root 跑多数情况读得到，但"多数情况"不够。
- **读不到已装模块状态时不给刷入按钮**：上一版刚把"读不到 ≠ 没装"改对，
  这里保持一致 —— 先说清"先修授权"，而不是让用户在状态不明时刷模块。
- **官网站的坐标（`sunsetrne.github.io/SunsetLinux` + 仓库 owner/repo）写死在代码里**，
  与内置官方频道的公钥同类：它是信任根，不是用户数据。


## 0.2.5 —— 「更新」页从"看得懂"到"真的能装"

用户的原始反馈有两句：**"更新流程各个包的流程 UI 和检测流程"**（要看得出每个包在干什么、
卡在哪一步），以及 **"我本人已经重启的情况下，它显示没刷入"**（检测在撒谎）。
两条都在这一版里落到了代码，而不是只改文案。

### 1. 离线安装（内嵌包）真的能装环境了

以前 `assets/offline-bundle.bin` 只被**读了个头**——页面能告诉你"内嵌了 base/runtime/dsh"，
但没有任何代码把部件铺下去，「更新」页只有"从频道下载"一条路。断网、墙外、频道挂了就是死路。

现在新增 `core/OfflineInstall.kt`（`OfflineApplier`）：从 assets 里**流式**取部件
（dsh 变体的包 102 MB，绝不整块进内存）→ 按包头 `sha256` 校验 → 用包头 `transport`
走**和频道完全相同的解压路径** → 按 `sha256_raw` 校验裸镜像 → `linuxctl update <id> <raw> --version <ver>`。
也就是说：离线装出来的层与在线更新**写的是同一份 `layers/<id>-<ver>.erofs` 与 `state.json`**，
不存在"离线环境对不上在线更新"。

顺序上 `proot` 部件**必须最先**（非 root 模式的 `linuxctl` 是 proot 脚本，它要用 proot 二进制），
然后 `base → runtime → dsh`；root 模式明确**跳过** proot 部件（那个模式走 chroot，装了只是白占空间）。

界面（「更新 → 本机包」）现在给每个部件一行对照 **本机版本 → 包内版本**，并算出"缺几项"，
按钮是「离线安装（N 项）」+「只装缺的」；没有可装的部件时按钮直接写"内嵌包已全部就位"。

### 2. proot 宿主脚本随 APK 走（免 root 版第一次真能离线起步）

`runtime/proot/` 下的四个脚本（`linuxctl.sh` / `start.sh` / `entry.sh` / `selftest.sh`）
以前只有两条来路：模块铺（root 用户），或者用户手动 `tar -xzf` 那个 50 KB 的
`dist/sunsetlinux-proot-runtime.tar.gz`。内嵌包里只有 proot **二进制**——
所以「免 root 版」「完整离线版」装完其实**起不来**，用户在欢迎页看到的是"请自己去找运行时包"。

现在 `app/build.gradle.kts` 的 `syncProotRuntimeAssets` 在构建时把 `runtime/proot/`
拷进 `assets/proot-runtime/`（单一事实源，不做改写；缺 `linuxctl.sh|start.sh|entry.sh`
直接**构建失败**，不允许再产出起不来的 APK）。`core/ProotRuntime.kt` 负责铺到
`$LINUX_HOME/bin/`、补执行位、并把契约路径 `bin/linuxctl` 就位。

### 3. 模块检测改三态：**读不到 ≠ 没装**

用户说"我已经重启了，它显示没刷入"。根因是 `ModuleStatus` 只有"装了/没装"两种输出：
`su` 没拿到、探针超时、输出为空时都落到了"未装"——把**不知道**说成了**确定没装**。

现在 `ModuleStatus.readable` 明确区分"探到但没装"与"根本没读到"：
读不到时标签是「模块状态未知」、提示去授权 root，颜色用**中性色**（不再是红色 Danger）。
「关于」「欢迎页」「部署向导」三处的模块胶囊都按这个规则着色。

### 4. 「更新」页的结论不再撒谎

- 所有启用频道都检查失败时，「可用更新」不再落到"已是最新"，而是先显示
  **频道检查失败：<原因>** + 「重试」/「频道管理」（真机踩过：以为是"最新"，其实是没查成）。
- 新增「频道检查」卡片：每个频道一行"正常"或"失败状态 + 原因"。
- 新增「本机包」卡片：本 APK 是哪个组合、内嵌了什么、构建号（`STANDARD_VERSION`）。

### 5. 设备侧自愈：过期的 `run/last-error`

`runtime/root/linuxctl.sh` 的 `gather_status` 现在会丢掉**已经过期**的
"缺少层文件 / 缺少可写层"错误（三层与 `upper.img` 都在时），否则状态页会一直挂着
一条历史错误，看上去就像"环境坏了"。探测脚本的标记协议也补了单测（`###PROP/###DISABLE/
###PENDING/###PROV/###END`）。

> 模块侧同步发 **1.0.10**（就是这条 stale-error 自愈）。


## 构建信息（不写进 version.properties）

`app/build.gradle.kts` 构建时算一个**标准版本号**（参照 Branchbase 的做法，见
docs/specs/BUILD-NOTES.md）：

```
标准版本号 = <versionName>-<yyyyMMdd-HHmm>-<7 位 git hash>     例：0.2.4-20260916-0040-8d4920a
```

它注入 `BuildConfig`（`ENGINEERING_VERSION` / `STANDARD_VERSION` / `BUILD_TIME` / `GIT_HASH`
/ `EMBED_VARIANT` / `EMBED_PARTS`），并写进 gh-pages 的 `index.json` 与下载页；
**不进 APK 文件名**（下载页每版只指向最新一份，不存在"堆多代 APK"的问题）。

## 产物命名（定了就不要随手改）

| 产物 | 命名 | 例 |
|---|---|---|
| App（四个组合各一份） | `SunsetLinux-<versionName>-<组合>.apk` | `SunsetLinux-0.2.4-ubuntu-proot-dsh.apk` |
| 离线包（可单独下载/导入） | `SunsetLinux-<versionName>-<组合>.bin` | `SunsetLinux-0.2.4-minimal.bin` |
| KernelSU 模块 | `sunsetlinux-module-<模块版本>.zip` | `sunsetlinux-module-1.0.9.zip` |

组合名（`<组合>`，与 `tools/offline-bundle/variants.json` 里的 id 一一对应）：

| id | 中文名 | 内嵌 | 体积增量 |
|---|---|---|---|
| `minimal` | 最小版 | proot 运行时（非 root 必需件） | +0.9 MB |
| `ubuntu` | Ubuntu 版 | base + runtime | +68 MB |
| `ubuntu-proot` | 免 root 版 | base + runtime + proot | +69 MB |
| `ubuntu-proot-dsh` | 完整离线版 | base + runtime + proot + dsh | +102 MB |
