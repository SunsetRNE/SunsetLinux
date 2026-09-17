# App 版本变更说明

> `app/version.properties` 只放 `versionName` / `versionCode` 两个键值；
> **每个版本的来龙去脉写在这里**（新增一版时先加条目，再改 version.properties）。
>
> 版本号规则：Android 只按 `versionCode` 判"是不是新版"，所以**每次对外发布都要 +1**。
> 0.3.0 起是 **2 个 App（root / proot，包名不同、可共存）× 3 个内置档位**，清单见
> `tools/offline-bundle/variants.json`。同一个 App 内换档位 = 覆盖安装另一个 APK（数据不丢）；
> **跨 App 不能覆盖安装**，是两个独立包。两个 App 共用同一组 versionName/versionCode。

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
| 0.2.8 | 10 | **免 root 模式换 proroot 首选 + proot 降级**：proroot（LD_PRELOAD，无 ptrace 开销）随 APK 的 jniLibs 打包，proot 仍随包内嵌；`SUNSETLINUX_ROOTLESS=auto|proroot|proot`（App 设置里可选），`status`/`doctor`/App 都如实报实际用了谁。proroot 是专有许可 —— 只随 APK 分发、不得再分发修改版，带许可原文 + 关于页 attribution，并有合规闸门 |
| 0.2.9 | 11 | **终端接上原生 PTY**：`/dev/ptmx` + forkpty 路线（NDK 编译的小 `.so` 随 APK），Ctrl-C/Ctrl-D/Tab/方向键真的生效，`vim`/`htop` 这类全屏程序能跑，窗口大小按控件尺寸发 `TIOCSWINSZ`；输出用最小 VT 模拟器渲染（`\r` 覆盖、`ESC[K`、定位、SGR 剥离）；原生库不可用时**优雅降级**回行缓冲并明说 |
| 0.2.10 | 12 | **加"无 loop 层模式"开关（dir）**：把三层解包成目录 + 目录 overlayfs，**完全不碰 loop / erofs / upper.img**；「设置 → 层模式」可切、也读 `SUNSETLINUX_LAYER_MODE` 与 `etc/config.json` 的 `layer_mode`（默认 loop 省磁盘）。模块 **1.0.15**：dir 模式实现 + doctor §1e 层模式检查 |
| 0.3.0 | 15 | **拆成两个可共存的 App（各锁一条路）+ 内置矩阵数据驱动（6 个变体）+ 内置 DSH 与运行时 DSH 解耦**：root 版 `…​.root`、免 root 版 `…​.proot`（不同包名、可同时安装、界面不再有"切换模式"）；内置档位从 2 档扩到 **3 档**（最小 / Ubuntu / 完整离线），矩阵与标签**全部读 `tools/offline-bundle/variants.json`**（加档只改 JSON，Gradle flavor 与 CI 矩阵自动跟上）；新增 `core/DshPin.kt` —— 「内置 DSH（随 APK 冻结）↔ 运行时 DSH（可被频道更新）」对账（纯函数 + 9 条单测），「更新」页给两个版本号与结论、并给**一键回滚到内置版本**（`linuxctl rollback dsh --to <版本>`；`update` 不删旧层文件，所以退路一直在），「关于」页也有一行。模块 **1.0.18**（本轮运行时无改动） |
| 0.3.1 | 16 | **修「两个 App 桌面同名」**（真机实测）：0.3.0 只改了 `main/res` 的 app_name，`src/<edition>/res/` 根本不存在，于是 root 版与免 root 版在桌面上都叫 "SunsetLinux"，用户点哪个纯靠猜。现在按 edition 设 `app_name`（`resValue` 取 `variants.json` 的 `editions[].label`）：**SunsetLinux Root** / **SunsetLinux 免root**；并打开 AGP 9 默认关闭的 `buildFeatures.resValues`。同时修一批拆版后过时的文案（"四个组合同一个 App、换组合就是覆盖安装"→ "同 App 内换档位＝覆盖安装；root / 免 root 是两个不同的 App"），Release 说明里那个从 0.2.x 起就不存在的 `sunsetlinux-launcher-debug.apk` 也换成了按 `variants.json` 推出来的真实文件名 |
| 0.3.2 | 17 | **真机挂载归因 + 修掉三处 bug + 本机能力探测**（模块 **1.0.19**）：① **可写层挂载点不一致** —— `mount_upper_rw` 把 `upper.img` 挂到 `$ROOTFS_DIR/upper`，而 `architecture.md`/`stop.sh`/`linuxctl` 全都按 `$LINUX_HOME/upper` 找它 ⇒ overlay 的 `upperdir`（`$UPPER_DIR/upper`）根本看不到那块 ext4，8 GiB 镜像白挂、还被随后挂在 `$ROOTFS_DIR` 的 overlay 遮住；② **toybox 能力探测谎报** —— 判定表认不出 `Unknown option 'propagation'`（大写 U）与 `bad /etc/fstab`，于是每次启动都先按"支持"去调 `--propagation`/`--make-rprivate`（注定失败，日志里那两行就是这么来的）；③ **overlay 失败没诊断** —— 既不记实际入参，`kernel_hint_log` 的 grep 又把 `overlayfs:` 过滤掉了。新增 `overlay_fs_usable()`（同 fs 两个空目录只读试挂，实测该 fs 能不能当 overlay 的层）+ `probe_loop_io`/`probe_fuse_mount`（`PROBE_VERSION=2` 让老 cmdprobe 失效重探）。**真机结论（都有内核原文）**：这台机 `/data` 的 f2fs 被 overlayfs 一律拒绝（`ovl_dentry_weird` 命中 `DCACHE_OP_HASH/COMPARE`）⇒ dir 模式在这台机不可能；loop 的 I/O 在 `kernel` 域被 SELinux 拒（厂商只读 loop 背 `system_file` 正常、我们的文件连**读**都 FAIL、`chcon system_file` 后读通写仍被拒）⇒ **loop 快路需要一条可选的 `kernel` 域 sepolicy 规则，依旧不需要编/刷内核** |
| 0.3.3 | 18 | **修整机卡死事故（模块 1.0.20）**：`gather_android_facts`（调 `ndc`/`getprop` 抓 DNS/时区）原本被**内层**（私有 mount/UTS ns）调用 —— 真机实测 `ndc` 打不开 `/dev/binder` 而 SIGABRT，连锁出 5 个 tombstone + `system_app_anr` + `system_server_crash` ⇒ **整机卡死只能重启**。现在抓取只在**父进程**做（未 unshare），内层改调 `install_android_facts()` 只把宿主预抓的文件**拷进** `rootfs/etc/`（`entry.sh` 读的 `/etc/android-resolv.txt` 顺带接通）。另含：`mount_layer` 同条 `local` 自引用修复、`chroot` 前显式给 `PATH`、`entry.sh`/`supervise.sh` shebang 改 `/bin/bash`、`ksud sepolicy` 写法（class 不带冒号）与回归 62 条 |
| 0.3.6 | 21 | **免 root 版与 KernelSU 模块彻底切割 + 进入即启用内置环境；模块拆成两变体（模块 1.0.23）**。三条决策与设计见 [`docs/module-variants.md`](../docs/module-variants.md)：① 免 root 版不再出现任何模块痕迹（模块卡/模块检测/一键刷模块全短路），打开即自动铺内置环境并落到终端；② root 模块拆 `full`（**默认名**，自带 DSH 层，更新 + 一键回滚到内置版本）与 `bare`（不含 DSH，`linuxctl dsh install` 一条指令从内置官方频道装，验签失败即拒绝），APK 一律内嵌 `bare`；③ 编译只在 CI、发布只由 `main` 触发，`beta` 等分支只存内容 |
| 0.3.7 | 23 | **把「启动环境」与「启动 DSH」拆开（模块 1.0.29）** —— 需求原话："启动环境不等于启动 DSH……补两个按钮是两者拆开分别启动，且开启判定，用了一键时不能再用拆开的，反之亦然，用于在虚拟环境上更新或者其它操作；这样也能补上终端功能的缺陷，把终端接入虚拟环境"。① **首页五个按钮**（仅 Root 版）：主按钮「一键启动（环境 + DSH）」+「仅启动环境 / 停止环境 / 启动 DSH / 停止 DSH」，启用矩阵由 `core/StartControls.kt` 的**纯函数**唯一决定（`state` × `env_mode` × `dsh.running`），UI 只负责画 —— 判定错一格在界面上完全看不出来，所以它必须能被 JVM 单测穷举；② **互斥说清理由**：`env_mode=full` 时只留「停止环境」（要单独控制 DSH 就先停环境再选「仅启动环境」）；`env-only` 时 DSH 两个按钮按 `dsh.running` 互斥；模块没报 `env_mode`（旧模块）时**不编造**，只留一条一定能走通的「停止环境」并说明原因；③ **终端不再被 DSH 绑架**：只要环境在跑（`env_mode=env-only`、DSH 没起）就能 `attach`；环境没跑时终端页常显横幅 + 一键「仅启动环境」（不再逼用户"先一键启动把 DSH 也拉起来"）；④ **不再假报故障**：env-only 且 DSH 没跑时，"Web 健康"显示「DSH 未启动（仅环境方式）」而不是红字「无响应」，诊断页也不再提示"端口没写对"。<br>⚠️ versionCode 从 21 直接跳到 **23**：这一次发布前误出了一版「name 0.3.6 / code 22」（只改了 code、没改 name），已发布过的号不能复用，否则装了那版的人收不到这次更新 |

## 0.3.3 —— 整机卡死事故：内层绝不能调用安卓系统工具


`gather_android_facts` 要调 `/system/bin/ndc`、`getprop` 才能拿到 DNS/时区；它自己的注释
写着"在宿主侧先取好是唯一可靠做法"，但**调用点却在 `build_mount_tree` 里**（内层）。
真机结果：`ndc` 在私有 ns 里打不开 `/dev/binder`（`Error: 2`）→ SIGABRT → 5 个 tombstone
→ `system_app_anr` → `system_server_crash` ⇒ 手机卡死，只能重启。

改法：抓取移到**父进程**（`main()` 里、spawn unshare 之前）；内层新增 `install_android_facts()`
只做**拷贝**（`$LINUX_HOME/etc/android-*.txt` → `rootfs/etc/`）。教训清单见
`docs/HANDOFF.md` 第 37 轮补记（含 `ksud sepolicy` 写法、权限位、单行检测、`chroot PATH`、
`local` 自引用）。

## 0.3.2 —— 真机挂载归因；三处 bug；"这台机起不来"背后的三条限制

**用户的两句话**：

1. "上一轮真机启动失败（loop 层 I/O error → 降级 dir 模式又 EINVAL）"；
2. "我在想我这台机子上起步比较困难，那后面适配其他安卓系统岂不是也困难？能不能做公共挂载适配，
   总不能源码级编译内核刷写？"

### 1）修掉的三处 bug（都与这一轮的两个失败直接相关）

| # | bug | 证据 / 后果 |
|---|---|---|
| ① | `mount_upper_rw` 把 `upper.img` 挂到 `$ROOTFS_DIR/upper`，而 `docs/architecture.md`、`stop.sh`、`linuxctl.sh`、`doctor` 全按 `$LINUX_HOME/upper` 找它 | overlay 的 `upperdir` 是 `$UPPER_DIR/upper` ⇒ **看到的是 f2fs 上的普通目录，不是那块 ext4**；8 GiB 镜像白挂，而且它还被随后挂在 `$ROOTFS_DIR` 的 overlay 遮住（不可达的死挂载）。已对齐到 `$UPPER_DIR`，`run/mounts` 登记表支持绝对路径、`stop.sh` 跳过绝对项（并给老版本的 `rootfs/upper` 留兜底） |
| ② | toybox 能力探测谎报：判定表认不出 `Unknown option 'propagation'`（大写 U）与 `mount: bad /etc/fstab` | 真机 `cmdprobe` 记的是 `unshare_propagation=yes` + `mount_make_rslave=long` ⇒ 每次启动都先按"支持"去调 `--propagation private` / `--make-rprivate`，**注定失败**后才回退（日志里两行 `bad /etc/fstab`、两行 `unshare: Unknown option` 就是这么来的）。已换统一的 `probe_says_unsupported`；并写明**为什么不能用 `-o rprivate / /` 顶替**：toybox 见到两个目录参数会自行判成 bind 并清掉 `MS_REC` ⇒ 在 `/` 上压一层非递归 bind，`/data` 这类子挂载会被遮住（比不设更糟） |
| ③ | overlay 挂载失败没有诊断：不记实际入参；`kernel_hint_log` 的 grep 只含 `loop|ext4|jbd2`，**恰好把 `overlayfs:` 过滤掉** | 上一轮的日志里只剩一句 `Invalid argument`，查不动。现在失败会带上"实际传下去的选项 + 内核原文"，并写进 `last-error`（App 诊断页读它） |

### 2）新增：本机能力探测（"公共挂载适配"的第一块砖）

- `overlay_fs_usable()`：拿**同文件系统上的两个空目录**做一次只读 overlay 试挂，实测这个 fs 能不能当层
  （不能靠 fs 名字硬编码 —— 有的 f2fs 能用、有的不能）。接在两个 dir 模式入口**解包之前**：
  不能就立刻带原因退出，不再白等 1.6 GB / 几分钟。
- `probe_loop_io` / `probe_fuse_mount` 进 `cmdprobe`，并加 `PROBE_VERSION`（=2）让老设备的缓存失效重探；
  `loop_read=no` 时直接跳过"清残留 loop → e2fsck -p → 显式 losetup"那套仪式（那三步救不了"域没权限"）。
- 只测**读**：读是同步的、结果可信；写会进页缓存，"dd 成功"不等于写到了文件
  （真机上就是 `Buffer I/O error … lost async page write` 这种异步失败）。

### 3）这台机的三条限制（都有内核原文，也解释了"为什么不能靠编内核解决"）

1. **overlayfs 拒绝 f2fs**：`overlayfs: filesystem on '/data/sunsetlinux/dirs-upper' not supported` →
   `fs/overlayfs/util.c` 的 `ovl_dentry_weird()` 命中 f2fs 的 `DCACHE_OP_HASH|DCACHE_OP_COMPARE`
   （casefold）。**连只读 lowerdir 都不收** ⇒ dir 模式在这台机上不可能（这是内核规则，策略改不了）。
2. **loop 的 I/O 在 `kernel` 域被 SELinux 拒**：厂商那些只读 loop（backing file 是 `/system_ext` 的
   `system_file`）正常；换成 `$LINUX_HOME` 下的文件后**连读都 FAIL**（块层 `I/O error, dev loopN,
   sector 0 op 0x0:(READ)`），`chcon system_file` 后**读通了、写仍被拒**（内核原文仍有
   `loop: Write error at byte offset 0`）。⇒ 没有"改标签就能当可写层"的捷径；要 loop 就得补一条
   `allow kernel <type>:file { read write }` —— **用户态一条文本规则，可撤销，不需要编/刷内核**。
3. **toybox 选项面/能力差异**：纯用户态问题（探测 + 多写法），已在 ② 里修。

### 4）结论与下一步

- 方向不变：**策略池 + 每台机实测选路 + 每条被否的原因进 doctor**，loop 只是其中一格的"快路"；
- 这台机的当务之急是**不依赖 loop 的路线**（用户态 union（`/dev/fuse` 在，挂载权限待实测）或"无 union：
  合并目录 + bind 出可写点"），proot 版本来就是无依赖兜底；
- 真机诊断脚本（4 个，在开发工作区、不入库）：`真机诊断-挂载.sh` / `诊断2-loop与overlay` /
  `诊断3-loop归因与fuse` / `诊断4-判定表`，逐步把"哪个文件系统能当层 / loop 能不能读写 / 能不能挂 fuse"问清楚。

## 0.3.1 —— 两个 App 的桌面标签分开（拆版漏掉的那一步）

0.3.0 拆了两个 App，但**桌面上分不出来**：真机实测 root 版与免 root 版的图标都叫
"SunsetLinux"。原因是只改了 `main/res/values/strings.xml` 的 `app_name`，而
`src/<edition>/res/` 这个按 flavor 覆盖的目录**根本不存在**（我上一轮以为有）。

- 按 edition 设 `app_name`：`resValue("string", "app_name", e.label)`，
  标签取自 `tools/offline-bundle/variants.json` 的 `editions[].label`
  → **SunsetLinux Root** / **SunsetLinux 免root**（改 JSON 就跟着变，不抄第二份）。
- 同时打开 `buildFeatures { resValues = true }`：**AGP 9 起 `resValues` 默认关闭**，
  不开的话 `productFlavors { resValue(...) }` 在配置阶段就失败
  （`Product Flavor root contains custom resource values, but the feature is disabled.`）。
- `SigningContractTest` 补三条契约：`resValue` 的写法、`resValues` 开关、
  两个 edition 的 `label` 必须不同 —— 让"桌面同名"不能再静默回来。
- 修一批拆版后过时的文案与注释（`version.properties` 头部、`build.gradle.kts` 的 flavor
  注释、`OfflineBundle.kt` 类文档、`UpdatePane`/`AppShell` 注释，以及**给用户看的那句**
  "四个内置组合是同一个 App"）。
- `publish` 的 Release 说明：第 1 步从 `sunsetlinux-launcher-debug.apk`
  （这个资产从 0.2.x 起就不存在了）换成**按 `variants.json` 推出来的真实文件名**，
  并说清"两个可共存的 App / 模块只有 Root 版需要"。
  （踩到的坑：`read` 按空格分词会切坏带空格的 label，必须 `IFS=$'\t'`。）

> 同 `versionCode` 的 APK 覆盖更新不会被 Android 认作新版，所以这类**用户可见**的修复
> 一定要 +1 —— 0.3.0 装过的用户这次能在 App 里直接看到 0.3.1。

## 0.3.0 —— 两个 App、一条路各一个；内置矩阵能继续长；内置 DSH 与运行时 DSH 解耦

**用户的三句话**：

1. "一个纯 root 流程，一个纯免 root 流程" → 拆成两个 App；
2. "纯 Root、免 Root、然后各种内置感觉不止 4 种吧" → 内置矩阵要**能长**，不是两档封顶；
3. "后续还要考虑到内置 DSH 的解耦（内置一个版本，运行时一个版本的情况判定，避免更新导致崩了）"。

### 1）两个 App（不同包名、可共存）

| | Root 版 | 免 root 版 |
|---|---|---|
| 包名 | `io.github.sunsetrne.sunsetlinux.root` | `io.github.sunsetrne.sunsetlinux.proot` |
| 模式 | 锁死 root（真 chroot + overlayfs） | 锁死 proot/proroot |
| 带什么 | KernelSU 模块包（一键刷入） | proot 宿主脚本 + proroot `.so`（root 版不带这些） |
| 引导 | 本版说明 → 装模块 → 部署 → 完成 | 本版说明 → 铺运行时 → 铺环境 → 完成 |

两个 App 都**不再提供"切换模式"**（`DshRuntime.resolveMode` 直接按 edition 返回，
设置页只显示"本版固定"）。Root 版没有 su 时**不再偷偷降级到 proot** —— 那会去操作另一个环境，
是最坏的一种"看起来能用"；现在只把原因说清并指向另一个 App。

### 2）内置矩阵：数据驱动、能继续长

`tools/offline-bundle/variants.json` 是唯一事实源，现在是 **edition（2）× tier（3）= 6 个变体**：

| 变体 | 内嵌 |
|---|---|
| `root-minimal` | （无 —— 层全走频道） |
| `root-base` | base, runtime |
| `root-full` | base, runtime, dsh |
| `proot-minimal` | proot |
| `proot-base` | base, runtime, proot |
| `proot-full` | base, runtime, proot, dsh |

Gradle 的 flavor、每个变体的部件与标签、**pipeline 的编译矩阵**、下载页、App 的「本机包」显示
全都读这个文件：**加一档只改 JSON 一行**（`tiers` 加一条 + 两个 edition 各加一个变体），
CI 与构建脚本都不用动。`SigningContractTest` 里加了防漂移断言（构建脚本必须真的在读它、
两个 edition 包名必须不同且不能退回拆分前的老包名）。

### 3）内置 DSH ↔ 运行时 DSH 解耦

完整离线版把 DSH **冻在 APK 里**，而运行时那份会被频道更新 —— 两者随时可能不一致。
要防的不是"不一致"（更新就是让它不一致），而是**更新完不知道在跑哪个、也回不去**。所以：

- `core/DshPin.kt`：`reconcile(内置, 运行时)` → NONE / SAME / 运行时更新过 / 运行时比内置旧 /
  运行时还没装 / 判不了（**纯函数 + 9 条单测**，比较用逐段数字，`rc.10 > rc.9`）；
- 「更新」页新增一张卡：两个版本号 + 一句人话结论 + **「回滚到内置版本 X」**（只在需要时出现）；
- 回滚走 `linuxctl rollback dsh --to <内置版本>` —— 装离线包时那一层的文件就在 `layers/` 下，
  而 `linuxctl update` **不删旧文件**（回滚语义本来就有），所以这条退路一直在；
- 「关于」页加一行"DSH（内置/运行时）"。


## 0.2.12 —— 免 root 模式的引导：从"指路"到"真的能铺"

**用户的原话**（贴的是 App 诊断页的输出）：

```
# linuxctl doctor  ·  模式 PROOT  ·  /data/user/0/…/files/sunsetlinux
✗ 没有找到 linuxctl：…/files/sunsetlinux/bin/linuxctl
  请先在侧边栏「重新部署 / 首启引导」里完成部署。
```

**两个根因**（都在"引导把用户指向了一条不存在或走不通的路"）：

1. **`bin/linuxctl` 在界面上根本没有能铺它的动作。** 免 root 的宿主脚本（`linuxctl.sh` /
   `start.sh` / `entry.sh`）是**随 APK 内置**的（`assets/proot-runtime/`，50 KB）——`ProotRuntime`
   早就会铺，但引导分支只写"到「更新 → 本机包 → 离线安装」点一下"，而那个页面装的是层和运行时，
   **不铺 `bin/`**。于是用户照做之后 doctor 依然报"没有找到 linuxctl"。
2. **频道/离线包给的层在免 root 模式装不进去。** 那些层是 **erofs**，而 proot 的 `linuxctl`
   只认 tar：`archive_test` 判成损坏、`extract_archive` 拿 tar 去解。也就是说引导里
   "去更新页装三层"这句话在免 root 模式下**永远不可能成功**（模块 1.0.18 修好了这件事）。

**这一版做了什么**：

| 层 | 改动 |
|---|---|
| 引导 UI | 步骤名与动作**按模式分开**；免 root 分支给出三张卡片（宿主脚本 / proot 运行时 / rootfs），每张都有按钮与"缺什么"的说明 |
| 就绪度 | `ProotSetup`（纯文件检查，不需要 root）一次查清三件东西 + 层/种子/内嵌包，`plan()` 给出有序步骤（**纯函数，有单测**） |
| 一键动作 | 「铺 proot 运行时」= 内置资产落盘；「铺环境」= 内嵌离线包（若有）+ `linuxctl provision`，并把结果写成一句人话 |
| 诊断页 | 缺 linuxctl 时按模式给不同的下一步（proot：去引导第 2 步铺脚本；root：去第 2 步刷模块） |
| 部署向导 | proot 模式下**先自动铺脚本**再往下走；`NEED_CHANNEL` 的提示改成现在真的可执行的路径 |

**模块 1.0.18（一起发的运行时修复）**：

- `is_erofs` / `archive_test` / `extract_archive`：proot 模式**认得并解得开 erofs 层**
  （`fsck.erofs --extract=<dir>`，可用 `SUNSETLINUX_EROFS_EXTRACT` 指定；与 root 模式 dir 模式同一工具）。
  以前 erofs 文件会落到 `archive_test` 的默认分支 —— 什么都不查就放行，然后被 tar 解包炸掉。
- `find_base_layer` + `provision` 回退：没有 tar 种子时，**已就位的 base 层直接当 rootfs 解出来**。
  于是免 root 用户有了一条零手工的来路：频道/离线包装 base 层 → `linuxctl provision` → 启动。
- `doctor` 新增 **部署就绪度**：`bin/linuxctl` / proot 运行时 / rootfs（+ 有层但没 `fsck.erofs` 的告警），
  每条都写"点哪里补"。
- 自测新增 10 条（erofs magic / 校验 / 解包 / 解包器失败要显式失败 / tar 回归 / find_base_layer 正反例），
  bash+mksh 各 78 通过。


## 0.2.11 —— 让"装模块"这一步在 App 里真的能做（+ doctor 不再误报）

**问题一：引导第 2 步是不可执行的。** 原文写"选择模块包：`dist/sunsetlinux-module-0.1.0.zip`" ——
那个路径在开发者机器/仓库里，用户手机上不存在；截图里那个「我已经装好模块并重启」勾选框
卡住的正是这一步。现在：

- 模块 zip **内嵌进 APK**（`SyncBundledModule`，四个组合都带；`dist/` 为空时输出"未内嵌"并如实说明，
  不给一个点了必然失败的按钮）；
- 「一键刷入内置模块」→ assets 落盘（**落盘时校验构建期记下的 sha256**）→ `ksud module install`
  （Magisk 走 `magisk --install-module`）；刷完只提示重启，**绝不替用户重启**；
- 「导出模块包到 Download」（`su cp`；失败退到 FileProvider 分享）给"从本地安装"用；
- 「打开 KernelSU / Magisk 管理器」（已知包名 4 个，一个都没有就明说）；
- 勾选框的启用条件 = 用户断言 **或** 设备侧事实（模块已装 + 已启用 + 已重启生效）。

**问题二：诊断页的"原因"是章节名。** 流式 doctor 的 `CtlResult.message` 取的是 stdout 第一行
非空内容 = `== 0. 运行环境 ==`，于是用户看到 `✗ 自检未通过：== 0. 运行环境 ==`。
现在解析 JSON 尾行的 `fails`/`warns` 并附上报告里带 `[fail]` 的原文（最多 6 条）。

**模块 1.0.17 一起修掉的 doctor 误报**（真机一次自检报了 4 项 fail，其中 3 项是假的）：

| 误报 | 根因 | 现在 |
|---|---|---|
| `CONFIG_SQUASHFS 未启用` / `内核不支持 squashfs` | 本项目三层只读镜像用 **erofs**（Android 原生格式）；squashfs 只是"另一种可选格式"，缺它不影响任何东西 | 降为 info/ok；**只有当"两种格式都不可用"时** §2 才真报 fail |
| `dsh 层内没有 /root/.dsh/profiles/**` | 检查拿 `dump.erofs --ls --path=/` 的输出 grep 深层路径，而那个列表**不递归**（根目录只有 `root`/`usr`）→ 层是好的也永远匹配不上 | 新增 `layer_has_path()`：对着**目标路径**问（erofs `--path=`、squashfs `unsquashfs -l`），自测里加了正反两例 |
| `runtime 层里没有 pnpm` | 同上（同一个假阴性） | 同上 |
| `run/last-error：挂载 upper 失败…` | 这条**是真的**，但只报一句 `I/O error` 无从下手 | §3 新增**读写试挂**（只读能挂 ≠ 读写能挂），§8 区分"历史记录 / 当前故障"并给三条例行下一步 |

顺带：loop 设备计数（旧代码 `grep -c ":'"` 恒为 0，输出"在用 1 个"却列出 4 个）、
与本项目无关的 avc denial 在 JSON 里被打成 warn（与人类可读那行"都与本项目无关"自相矛盾）、
e2fsck 结论只说"报问题"不带原文 —— 都一并修了。

### 模块 1.0.17 —— 可写层 ext4 读写挂载失败不再让环境起不来

真机（6.1.141-android14，`u:r:ksu:s0`）实测：`mount -t ext4 -o loop,rw,noatime upper.img` 只回
`mount: '/dev/block/loop49'->'…/rootfs/upper': I/O error`，而**同一镜像 `-o loop,ro` 能挂、
`e2fsck -fn` 说"文件系统一致"**，`upper.img` 的 mtime 还停在创建时刻（说明从未成功读写挂载过）。
ext4 只在读写挂载时才写超级块 / 恢复日志，那条路径上的任何写失败都被内核统一报成 EIO ——
光看 errno 什么都推不出来。所以 `start.sh` 改成**逐级自愈**，每一步都留在 `run/start.log`：

1. 清掉**指向我们镜像的残留 loop**（上次启动失败留下的；只清没被任何进程挂载的）；
2. `e2fsck -p`（自动修"没干净卸载"留下的 needs_recovery / 孤立 inode；不做更激进的 `-fy`）；
3. **显式 `losetup -f --show` + `mount`** 两步走（错误可归因，绕开 toybox 的 `-o loop` 合并路径）；
4. 仍失败 → 把 `dmesg | grep -iE 'loop|ext4|jbd2'` 的原文写进日志，并把摘要压成一行进 `last-error`。

**最后一道**：四次都失败就**自动降级到 dir 层模式**（解包成目录 + 目录 overlay，全程不碰 loop /
upper.img）并把原因与代价（约 1.6 GB 磁盘）写进日志 —— 用户要的是"环境能起来"，不是"必须用 loop"。
想固定这个选择：`linuxctl start --layer-mode dir` 或「设置 → 层模式」。

自测新增 6 条：squashfs 不得打成 fail、层内路径检查的正例/反例、
`mount_upper_rw` 在"toybox `-o loop` 失败"时靠显式 losetup 挂上、失败后**确实**先清了残留 loop 并跑过 `e2fsck -p`。


## 0.2.10 —— 加一条"不碰 loop"的层模式（兼容开关）

**动机**：真机上最容易出问题的不是 overlayfs，而是它上游那条链 —— `losetup` → erofs 挂载 →
`upper.img`(ext4 loop) → 再 overlay。每一步都依赖 toybox 的选项面/loop 设备数量/挂载权限。
而"目录 + overlayfs"是内核确认支持（`CONFIG_OVERLAY_FS=y`）、也最少依赖的基本用法。

**做法**：`runtime/root/start.sh` 新增 `--layer-mode loop|dir`：

| | loop（默认） | dir（新） |
|---|---|---|
| 只读层 | `losetup` + `mount -t erofs` 三层 | `fsck.erofs --extract=DIR`（Android 自带）解成 `$LINUX_HOME/dirs/{base,runtime,dsh}` |
| 可写层 | `upper.img`（ext4 + loop） | `$LINUX_HOME/dirs-upper`（真目录，**不需要 upper.img**） |
| 合并 | overlayfs（镜像挂出来的目录） | overlayfs（目录） |
| 内核资源 | 4 个 loop + 3 个 erofs 挂载 + 1 个 ext4 挂载 | **0 个 loop、0 个镜像挂载** |
| 磁盘 | 550 MB（层镜像） | 550 MB（层镜像）+ 约 1.6 GB（解开的目录） |
| 首次启动 | 秒级 | 解包几分钟（有戳文件，之后幂等跳过） |

开关三处（优先级：命令行 > 环境变量 > config.json > 默认 loop）：
`--layer-mode dir`、`SUNSETLINUX_LAYER_MODE=dir`、`etc/config.json` 的 `"layer_mode": "dir"`。
App「设置 → 层模式」可切换（透传环境变量），「关于」页与 `linuxctl status` 的 `layer_mode` 字段
会显示**本次 start 实际用的是哪个**（start.sh 写 `run/layer-mode`，status/doctor 是另一个进程只能读文件）。

**为什么不是"把挂载交给 KernelSU"**：KernelSU 的模块挂载由 metamodule 负责，契约是"模块目录 →
Android 系统路径"的 overlay/重定向，没有通用挂载服务，且 metamodule 单实例（自带一个会顶掉用户的
magic_mount_rs）。详见 `docs/mount-conflict.md`。

**安全边界**：dir 模式解包前会校验是 erofs 层；解包器缺失时**明确失败**（`SUNSETLINUX_EROFS_EXTRACT`
可覆盖路径），不静默降级；解包按"文件名+字节数"打戳，层没变就跳过（幂等），换层只重解那一层。


## 0.2.9 —— 终端：从「按行管道」换成「原生 PTY」

**问题**（老实现）：终端走 `ProcessBuilder` 的普通管道，只能"写一行、读一批行"。
管道没有 termios，于是 Ctrl-C 无效（`0x03` 只是个普通字节）、`vim`/`htop`/`top` 不能跑、
没有作业控制、`apt` 的进度条与 `python` 的 `>>> ` 提示全乱。

**先纠一个错**：老代码注释写着"Android 上没有随系统可用的伪终端分配接口"——**不对**。
设备上 `/dev/ptmx` 存在且 `crw-rw-rw-`，bionic 也提供 `grantpt/unlockpt/ptsname_r`；
Termux 用的就是这条路。真正的原因是当时没写原生库。

**做法**（与 Termux 同一套顺序，见 `app/app/src/main/cpp/pty.c`）：

```
open("/dev/ptmx") → grantpt → unlockpt → ptsname_r
  → tcgetattr/tcsetattr（IUTF8；关 IXON/IOFF，否则 Ctrl-S 锁屏；**保留 ISIG/ICANON**）
  → ioctl(TIOCSWINSZ) 设初始行列 → fork
     子：setsid() → open(从设备) → dup2 到 0/1/2 → execve(/system/bin/sh -c "exec su -c …" / "exec linuxctl attach")
     父：master fd 交给 Kotlin（ParcelFileDescriptor.adoptFd）
```

- **构建**：不走 AGP 的 `externalNativeBuild` —— 官方 NDK 只发布 **x86_64 宿主**工具链，
  而本项目有 aarch64 构建环境（AGP 会去调那个 clang 直接 `error=2`）。改成
  `tools/ndk-build-pty.sh` 自己驱动 clang：x86_64 宿主用 NDK 自带 clang，
  aarch64 宿主用系统 clang + NDK sysroot（`-resource-dir` + `-rtlib=compiler-rt`）。
  两条路同一份源码、同一组选项，产物落 `src/main/jniLibs/arm64-v8a/`（随 APK 打包）。
- **尺寸**：App 用 `BoxWithConstraints` 按控件真实尺寸算行列 → `TIOCSWINSZ`，
  旋转/分屏都会重算（这是比"层内 script 包一层"更强的地方：`script` 的 stdin 是管道，永远学不到尺寸）。
- **渲染**：新增纯 Kotlin 的最小 VT 模拟器（`TerminalEmulator`，12 条单测）：
  `\r` 覆盖（CRLF 例外，否则会把上一行擦掉）、`\b`、`\t`、`ESC[K/J/H/A-D/G`、OSC 标题、
  SGR 剥离、滚屏、增量 UTF-8（一个汉字分两次 read 也正确）。
- **降级**：原生库加载失败（自编译漏 .so / ABI 不匹配）→ 退回老的 `TerminalSession` 行缓冲，
  并在界面上**明说**原因与能力差异。终端不能因为缺一个 .so 就不可用。
- **回显**：PTY 模式**不再本地回显**（行规程会回显，本地再补一次就重复了）；降级模式仍本地补。

体积代价：`.so` 11.6 KB。


## 0.2.8 —— 免 root 模式：proroot 首选，proot 降级

**动机**：免 root（非 root）模式原先只有 proot —— 它靠 ptrace 拦截每条系统调用，
每次调用一次上下文切换；`npm install`、Node 启动这类系统调用密集的负载会明显变慢。
proroot 是同一套 CLI 的 **LD_PRELOAD** 实现（无 ptrace 往返），在 arm64 上省掉那一跳。

**做法**：

- proroot 的 5 个 `.so`（共 ~650 KB）进 APK 的 `jniLibs/arm64-v8a/`，**四个组合都带**；
- `runtime/proot/start.sh` 新增 `resolve_rootless()`：`auto`（默认，proroot 可用就用，
  不可用记原因降级 proot）/ `proroot`（缺件**明确失败**，不静默降级）/ `proot`（只用 proot）。
  App「设置 → 免 root 运行时」可选，也能用 `SUNSETLINUX_ROOTLESS` 或 `etc/config.json` 覆盖；
- **对外契约不动**：`linuxctl status` 的 `mode` 仍是 `proot`，实际运行时放在新增的
  `rootless:{kind,version}` 里；`start.sh` 把结果写进 `run/rootless`，`status`/`doctor`
  是另一个进程，靠它知道"这次用的是谁"；
- App 侧：透传 `SUNSETLINUX_NATIVE_LIB_DIR`（= `applicationInfo.nativeLibraryDir`，
  proroot 启动器就在那里；脚本猜不到这个路径）与 `SUNSETLINUX_PROROOT_VERSION`；
  「设置」页加运行时选择卡片，「关于」页显示版本 + attribution。

**许可（这条比功能更重要）**：proroot 是**专有**许可 —— 允许把未修改的二进制作为
**完整 APK** 的一部分再分发，禁止再分发修改版、禁止独立于应用包的分发。所以：

- 二进制**不进仓库**（公开仓库的文件就是独立分发），构建期从上游 Release 取、校验 sha256
  （账本 `tools/proroot/VENDOR.json`）；
- **不 strip**（strip 就是修改）：`packaging.jniLibs.keepDebugSymbols`；同时
  `useLegacyPackaging = true`，让 `.so` 真的落到 `nativeLibraryDir`（要按路径 exec 启动器）；
- APK 内带许可原文 `assets/licenses/proroot-LICENSE.txt`，「关于」页给 attribution；
- 新增 `tools/proroot/compliance-test.mjs` 并接入 CI：仓库/工作流里不得出现裸 `.so`、
  APK 内 5 个 `.so` 必须与上游 sha256 一致、许可与 attribution 必须在。

APK 体积代价：每个组合 +0.2 MB 左右（13.2→13.4 / 77.3→77.5 / 78.2→78.5 / 109.5→109.7 MB）。


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

> 同一 App 版本（0.2.9）之后又单独发了模块 **1.0.13**：修 `start.sh` 的探测被 `set -e`
> 带走（真机上 cmdprobe 0 字节、层永远挂不上的直接原因），以及 `detect-mount.sh`
> 拿当前工作目录当模块根导致的"本模块需要挂载系统路径"误报。App 侧无改动。

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
| KernelSU 模块（**默认**，自带 DSH） | `sunsetlinux-module-<模块版本>.zip` | `sunsetlinux-module-1.0.23.zip` |
| KernelSU 模块（不含 DSH；APK 内嵌用的就是它） | `sunsetlinux-module-<模块版本>-bare.zip` | `sunsetlinux-module-1.0.23-bare.zip` |

> **默认名只给 `full`**（自带 DSH）。频道里没有 dsh 层时 CI **不产出默认名**，
> 只发 `-bare` 并在下载页/Release 说明里写明 —— 绝不把不带 DSH 的包冒充默认版本。

组合名（`<组合>`，与 `tools/offline-bundle/variants.json` 里的 id 一一对应）：

| id | 中文名 | 内嵌 | 体积增量 |
|---|---|---|---|
| `minimal` | 最小版 | proot 运行时（非 root 必需件） | +0.9 MB |
| `ubuntu` | Ubuntu 版 | base + runtime | +68 MB |
| `ubuntu-proot` | 免 root 版 | base + runtime + proot | +69 MB |
| `ubuntu-proot-dsh` | 完整离线版 | base + runtime + proot + dsh | +102 MB |
