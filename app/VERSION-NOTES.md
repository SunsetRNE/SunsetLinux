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
| 0.3.8 | 24 | **按真机批注改两处 UI（启动页操作置顶 + 一键/分步二选一；终端页紧凑化与身份行）** —— 批注原文：「把操作这个大块，移到最顶部。添加一个小 UI，在一键启动和分步启动两个按钮之间切换，默认一键启动按钮状态」「使终端这块紧凑一点……悬浮导航栏也有遮挡它，终端这一块得往上抬一抬……把部分内容变成自动切换的……比如终端权限行、root 身份」。① **操作卡置顶**，段落顺序变成 操作 → 状态 → 地址 → 日志；卡内加两段式切换（默认「一键启动」，选择落盘 `Prefs.start_mode`）：一键档 = 「一键启动（环境 + DSH）」+「停止环境」，分步档 = 2×2「仅启动环境 / 停止环境 / 启动 DSH / 停止 DSH」；**切换只决定显示哪一组**，每个按钮的亮/灰仍**只**来自 `StartControls` 纯函数（绝不写第二份判定）。② **运行中锁定切换**：起法在 start 那一刻定死 —— `full` 锁一键、`env-only` 锁分步、模块没报 `env_mode` 时**不编造**（停在用户选择并如实写「判不了」），提示统一是「要换方式请先『停止环境』」；纯逻辑抽在 `core/StartModeUi.kt`（`resolveStartMode` / `isStartModeLocked` / `startModeLockNote`）。③ **终端页避让悬浮胶囊**：最外层 Column 补 `CapsuleReserve` 底部留白（快捷键行与输入框原本正好被胶囊盖住），并收紧竖直留白约 1/4（顶部/横幅/空态/快捷键行/输入行）。④ **文案跟着状态自动变**（`core/TerminalStatusUi.kt`）：删掉同时讲两个 edition 的那句「root 模式会 chroot 进 rootfs，proot 模式进 proot 的 bash」，改成只讲本版事实 —— Root = `以 root 身份在环境内执行：nsenter 进环境 ns → chroot 到 /data/sunsetlinux/rootfs`，免 root = `以 proot 伪造的 root 身份执行（没有真实权限）；环境根 = App 私有目录`；顶栏身份行**连上后才出现**（`uid 0（root）` / `伪 root（无真实 capabilities）`，未连接不显示、不编造）；终端页副标题改成自成一句的短句（原来 `失败（未知，建议跑一键诊断）` 被顶栏裁成半句），单测钉住长度上限。回归：App 单测 **198 → 229/0 × 2 变体**（新增 `StartModeUiTest` 9、`TerminalStatusUiTest` 11、`StartModeContractTest` 11，并把「终端页有 vim 提示」那条断言改指向文案新家） |
| 0.3.9 | 25 | **源独立成页（npm 默认修成官方 + 新增 Python 源）+ 设置/插件页拆分 + gh-pages 合成一次提交** —— ① 真机批注「把这个 NPM 源默认成官方的」：`NpmSourceCard` 原来在 `prefs.npmRegistry` 为空时落到 `CUSTOM_ID`，所以全新安装打开就是「自定义」被选中；现在默认落在 `NpmRegistry.OFFICIAL_ID`（registry.npmjs.org）。② 「顺便多一个 Python 源」：新增 `core/PypiRegistry.kt` + 源设置页里的 Python 卡（官方 PyPI / 清华 TUNA / 阿里云 / 腾讯云 / 华为云 / 自定义 + URL 校验），落盘 `/root/.config/pip/pip.conf`（可写层）+ `<环境根>/etc/pip.conf`（系统级兜底），生效方式与 npm 一致：**重启环境**后新起的进程才读到。③ 真机批注「源独立页，然后设置内但是侧边栏有相应标签的内容统一拆成单独页面。设置页实在是太长了」：源设置从「插件」页拿出来独立成页（`ui/SourcesPane.kt`），设置页按主题拆出独立页面（如 `ui/ChannelsPane.kt`），插件页只留插件列表/安装。④ **CI：gh-pages 从一轮发布推 2~3 次改成 1 次**（`publish.yml` 一次推送同时带 `<通道>/` + `/channel/` + 站点根页；`pipeline.yml` ⑥ 改成只验签不推送、⑦ 加频道门禁）—— 每次推送 gh-pages 都会让 GitHub 起一条内置 `pages build and deployment`，两次推送就会互相取代（真机看到的 `#126 失败/#127 成功` 就是这个），现在只剩一条。回归：App 单测 **229 → 252/0 × 2 变体** |
| 0.3.10 | 26 | **让「频道签名校验失败」变成可自证的失败 + 补上信任根的行为级回归（模块 1.0.30）** —— 2026-09-17 真机上报的「签名校验失败，已拒绝该频道」此前**没有任何测试**，而拒绝理由里也不带任何证据，用户贴过来无法判断是「清单被改」「签名文件根本不是签名（404 页面/代理改写）」还是「换了钥匙」。① 新增 `SignatureVerifier.diagnose()`：把**公钥指纹**（与 `docs/HANDOFF.md` 公开的写法逐字相同，`ed25519:06:d0:c4:4d:29:1c:ef:66`）、清单字节数与 sha256、签名解出的字节数一并写进拒绝理由；② 顺手补一个诊断盲点：`availability()` 只探测 `KeyFactory("Ed25519")`，而 `verify()` 还要 `Signature.getInstance("Ed25519")` —— 平台只缺后者时会抛异常并被旧代码吞成「验签失败」（指错方向），现在把异常类型/消息也写进理由；③ 新增 `ChannelSignatureTest`：拿**真实发布过的清单快照**（`testdata/channel/`，与 gh-pages 上的 `/channel/` 逐字节一致）用真代码验签，并锁住指纹写法、篡改一个字节必须拒绝、非签名的 .sig 必须直说、公钥长度不对必须指出。此前 `SignatureVerifier` 零覆盖 |
| 0.3.11 | 27 | **「分步启动」之后的假故障文案（模块 1.0.31）** —— 真机截图：点完「仅启动环境」，副标题写着「健康检查（Web 未响应）」、卡片「打开 DSH」永远停在「正在获取登录地址…」，用户以为分步启动坏了。根因是这些文案只看 `dshHealthy`/有没有 url，**不看"这次本来就是仅环境、DSH 是按设计没起"**：① `Diagnoser.stage` 增加 env-only 分支 → 显示「环境运行中（仅环境：DSH 未启动）」（full 模式下 DSH 真挂了仍照旧报「Web 未响应」，两种模式不混为一谈）；② 「打开 DSH」副标题与 DSH 面板的失败文案抽成纯函数 `StartControls.openDshSupporting()` / `dshPaneNotOpenText()`（穷举单测）：仅环境下明确指路「点『启动 DSH』」，只有"DSH 在跑但 url 还没写出来"才说「正在获取…」 |
| 0.3.12 | 28 | **修「点了启动环境，然后就没了，点不了启动 DSH」（模块 1.0.33）：命令卡住把整个启动区锁死 + 让路端口不落盘** —— 真机截图：按「分步启动 → 仅启动环境」之后，启动区五张卡片**全置灰**、「启动 DSH」点下去**毫无反应**（也没有报错）。两处根因，**都在设备侧，App 只是受害者**：① `runtime/root/start.sh` 起守护进程时用的是 `if spawn_detached …; then uc_ok=1; fi` —— **前台**执行，父 shell 必须等它；而守护链最后的 `entry.sh` **按设计永不退出**（`--no-dsh` 是 `while :; do sleep 1; done`，full 模式 `exec supervise.sh`）⇒ `start.sh` → `linuxctl start` → App 的 `su -c …` **永不返回** ⇒ App 的 `busy` 永久为真 ⇒ 五张卡片全置灰，且 `if (_ui.value.busy) return` 把后续点击**静默丢弃**。设备实证：那条链 `sh -c → linuxctl → start.sh → start.sh --inner → entry.sh` **20 分钟后仍全部停在 `rt_sigsuspend`**，而环境 4 秒就 ready 了。修法：新增 `spawn_daemon()`，用 `( … >>"$DAEMON_LOG" 2>&1 </dev/null & )` 后台脱离（顺带去掉没有必要的 `--fork` 探测分支——那个"拿前台退出码猜选项"的写法正是祸根）；**App 侧同时加固**：`busy` 改成 `finally` 无条件复位 + 新增 `core/ActionTarget.kt`（纯函数 + 穷举单测）——状态轮询一旦观测到"设备已经达成该动作的目标状态"就立刻解锁，哪怕命令还在跑也不会锁死界面。② **端口让路没有落盘**（用户原话「端口被占，没有写偏移」）：3080 被另一个环境（免 root 的 DSHA）占着，`port_pick_free` 已经让路到 3081，但 `run/dsh.port` **只在 URL 解析成功后才写** ⇒ DSH 起得慢或起不来时，App 与用户都看不到"偏移"这件事，界面上表现成"什么都没有"。现在 `supervise.sh` 一启动就把**请求端口**写进 `run/dsh.port`（URL 就绪后仍以 URL 里的实际端口覆盖，失败/退出路径照旧删除），并把 `start.sh` 外层那句旧口气的 WARN（"dsh 可能自行换端口"）改成"环境会让路到空闲端口（实际端口看 run/dsh.port）"。回归：设备侧 `runtime/root/selftest.sh` **86 → 88/0**（新增"父脚本必须在 5s 内返回"的行为断言 + 一条"前台 spawn 确实会等"的对照样本，证明该计时能区分两种写法）、`tools/shell-compat-check.mjs` 新增**前台 spawn 闸门**（含 7 组"必须抓到/不许误报"样本，变异测试实测会红）；App 单测新增 `ActionTargetTest` **7 条**（每个目标正反两面 + 动作→目标映射 + NONE 永不达成） |

| 0.3.15 | 31 | **DSH 网页改成“覆盖整个应用”全屏渲染 + 留出回壳的路** —— 用户原话：「调整 DSH 的 web 页面渲染……原来的约束在应用内改为**覆盖应用全屏渲染显示**，允许返回壳（无论免 root 还是 root 模式都存在这类设计问题）」。原先 DSH 是外壳里的一个 tab，被夹在**顶栏与底栏之间**（`AppShell` 里甚至写着“底栏放内容下方（不遮挡网页）”），通知栏入口那个 `DshWebActivity` 还用 `safeDrawingPadding()` 内缩，网页自己再带一层头部 —— 三层 chrome 一叠，网页应用只剩屏幕中间一条。现在：① 新增 `ui/DshFullscreen.kt`（纯策略：**哪些页覆盖整窗 / 要不要收系统栏 / 要不要常驻返回出口**唯一由它判）；② 壳里把 DSH 画成**顶层浮层**（铺到屏幕四边、顶栏底栏都不画；位置在 MessageBanner **之前** ⇒ “操作失败：…”仍浮在网页之上）；③ 新增 `ui/ImmersiveBars.kt`（窗口级效果，离开自动恢复；边缘滑动可临时唤出系统栏）；④ 全屏时给一颗**常驻悬浮「返回壳」键**（画面里唯一需要避开系统栏的控件）；⑤ 返回语义不变：网页有历史先回网页历史、否则回壳（`PredictiveBackHandler` 跟手）；⑥ 通知栏入口（`DshWebActivity`）同一形状。同类项目的同款问题与借鉴见 `docs/STATUS.md` §3.10.52。回归：App 单测 **278/0**（新增 `DshFullscreenContractTest` 7 条；`UiInsetsContractTest` 的 inset 委托表补登 `DshWebActivity.kt → ui/DshWebPane.kt` 并写清“整屏刻意不内缩，只给悬浮键留 inset”） |

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
| 0.3.16 | 32 | **内置/目标 DSH 版本推到 `0.1.6-alpha.2`**（离线包由频道清单驱动，`dsh` 层随频道更新）；**免 root 模式的启动器也要带 `--expose-internals`**（DSH 0.1.6 的 HMR 硬要求，改动在 `runtime/proot/entry.sh` → 随 `assets/proot-runtime/` 进 APK）；App 侧无其它行为变化 |
| 0.3.17 | 33 | **把「CLI/WebUI 更新 dsh 时走 `.zst`」真正送到设备（模块 1.0.42）** —— 已发出的 0.3.16 里这段能力其实是**死代码**，而且引入它的 `183d8d8` 因为没升版本号**根本没发布**（Release 的 `index.json` 仍是 run 55）：① 产物选择原来写死「优先 `url_gz`」⇒ 新加的 `.zst` 解压在任何机器上都不会被走到；现在改成**跟随本机能力**（系统 `zstd`，或跑验签的 node 自带 `node:zlib` zstd ⇒ 挑 `url`；否则退 `url_gz`；`SUNSET_NO_ZSTD=1` 可强制 `.gz`）；② 模块带上 `bin/zstd-filter.mjs`（`post-fs-data.sh` 每次开机把它同步进 `$LINUX_HOME/bin/`，而 `update.sh` 找的正是那里）。设备侧收益：CLI/WebUI 更新 dsh 层 **109.5 MB → 79.1 MB**。App 侧无行为变化。<br>⚠️ 交接单里「模块改内嵌 `.zst`」这条**作废**：宿主侧既没有 `zstd`，也跑不了层里的 glibc node（要 `/lib/ld-linux-aarch64.so.1`，Android 宿主没有），而展开载荷的 `customize.sh` 正在宿主侧 —— 模块载荷必须保持 `.gz`。证据与验证矩阵见 `docs/STATUS.md` §3.10.55 |
| 0.3.18 | 34 | **修「模块带了更新的内置 DSH 却不生效」（模块 1.0.43）** —— 真机现场：装完模块 1.0.42 + 重启，环境**照旧跑 `dsh 0.1.5-rc.2`**（`/proc/mounts` 的 loop 后端、挂载层里的 `package.json` 都能证明），设备日志那句 `内置 DSH 就位 …（启用更新=false）` 就是它。根因两处：① `linuxctl dsh builtin` 只在"没有记录"或"生效层确定缺 web profile"时才切，**不比较版本号** ⇒ 模块里更新的内置 DSH 永远不生效。现在新增规则 ③：**内置比生效层新就切**（反向一律不动，保住"不把用户从频道更新的版本回退"的原意），并在 `dsh info` 暴露 `builtin.newer_than_active` 让这件事可观测；② 开机自愈与开机自启**在同一秒内并发**，抢跑时环境可能先用旧层起来（用户得多重启一次）⇒ 载荷**已在盘上**时改为**同步**跑完再自启（不做解压、毫秒级），载荷还没展开时才照旧后台。另修：`.zst` 的能力判据补全为「**引擎 + `zstd-filter.mjs`** 两个都要」——老模块（没有该文件）以前会被选走 `.zst` 却解不开，从"能更新"退化成"报错"（自测 `dsh install` 当场抓到）。App 侧无行为变化，只是跟着模块一起发 |
| 0.3.19 | 35 | **修「频道永远验签失败 ⇒ 用户自己更新不了层」——根因是原生 Android 根本没有 Ed25519 的 KeyFactory**（模块不变）：真机（OnePlus PJD110 / Android 16）上更新页写着「已是最新」，而线上清单明明有 `runtime 1.0.1` + `dsh 0.1.6-alpha.2` 两条可更新；点开「频道检查」才看到真话 —— 频道被 `REJECTED：签名校验失败…InvalidKeySpecException：To generate a key pair in Android Keystore, use KeyPairGenerator initialized with android.security.keystore.KeyGenParameterSpec`。这句文本在设备 `framework.jar` 里 grep 得到、在 AOSP `AndroidKeyStoreKeyFactorySpi.engineGeneratePublic()` 里是**一句无条件 throw**：AndroidKeyStore 只给自己生成的密钥当 KeyFactory。再往下一层：AOSP 的 Conscrypt（`OpenSSLProvider`）**只注册 `KeyFactory.RSA/EC/XDH` 三个 —— 原生没有任何 Ed25519 KeyFactory**（上游 google/conscrypt 的 `OpenSslEdDsaKeyFactory` 是 2025 年新增，还没进 AOSP；设备 `conscrypt.jar` 里 `eddsa` 0 次，对照 `25519` 12 次；Android 自带的 BC 又是裁剪版，`ed25519` 0 次），而平台的失败转移（`nextSpi`/`serviceIterator` 在设备 `core-oj.jar` 里都在）转完**仍然**抛出 AndroidKeyStore 那句 ⇒ 这台机器上没有任何 provider 能用 X.509/SPKI 造出 Ed25519 公钥。**这是原生 Android 的空缺，不是厂商魔改**，也就是说 `KeyFactory.getInstance("Ed25519")+generatePublic(SPKI)` 在任何 Android 设备上都必然失败。后果：频道永远验签失败 ⇒ `merge()` 收不到 `OK` ⇒ 界面落回「已是最新」，用户以为没更新、其实根本没查成（CLI/node 侧一直正常，所以这条链一直没被发现）。修法三层，且每层都要**用公开测试向量真验一遍**才算数：① 点名 provider（Conscrypt/AndroidOpenSSL/默认）；② 都不行 ⇒ **随包的纯 Kotlin Ed25519**（`core/Ed25519.kt`，只依赖 `MessageDigest("SHA-512")`）；③ 连自带实现都过不了自检才报"无可用实现"。顺带把「检查没成」与「没有更新」彻底分开：新增纯函数 `channelsAllFailed()`/`channelNotice()`，检查全挂时说「频道检查失败：N 个频道都没能给出可用清单 —— 这不代表已是最新」（部分失败时说明"结论可能不完整"），首页「更新」磁贴同步显示「检查失败」，诊断页多一条"更新信息不可用"；「频道检查」卡新增一行 `验签实现：…`（实际用的哪一层，真机排障一眼定性）。回归：App 单测 **278 → 297 / 0**（`Ed25519Test` 8 条钉 RFC 8032 §7.1 官方向量与全部反例、`ChannelSignatureTest` +4、`UpdateNoticeTest` 6、`DiagnoserTest` +1）；并把 `Ed25519.verify` 改成"永远返回 true"做了**变异验证** —— 反面用例如期判红 |
| 0.3.20 | 36 | **视角地图 + 交换目录 + 环境内默认全权 + 壳读取提速**（模块 **1.0.44**）：用户反馈三件事——「找不到 Root 部署的工作根 / Download 没映射进容器」「壳读 Root 环境慢、有闪烁」「虚拟环境内部默认不是 root，每条命令都要提权」。① **视角地图**：实测这台机器上同时存在**三个挂载命名空间**（全局 `4026532884` / 设备 shell `4026536055` / 环境 `4026535677`），同一个路径字符串在不同视角下指向**不同目录**——硬证据是同一个 f2fs 分区（device `65103`）上真目录 inode `1652135`、影子目录 inode `2909534`（DSHA 的 proot 壳**没有 bind `/data`**，于是 `/data/...` 落进了它自己 rootfs 里的 `data/`）。新增 `linuxctl whereami [--json]`（只读、不建任何目录）按"只有这个视角才有的东西"判定你此刻在哪、并打印路径地图；地图还**随模块发布**（`module/share/地图-视角.md` → 开机投递到 `$LINUX_HOME/share/地图-视角.md` 与 `$LINUX_HOME/README-地图.md`），环境里就是 `/share/地图-视角.md`。② **交换目录**：`start.sh` 新增 `mount_share()`，宿主 `$LINUX_HOME/share` ↔ 环境 `/share`（同一批 inode、0777、失败只 warn 不 die）——从此"塞文件"不用碰 `upper/upper`、不用猜路径（Download 其实早就通了：环境里 `/mnt/sdcard/Download`，`/storage/emulated/0` 是指向它的软链）。③ **环境内 DSH 默认全权**：DSH 的沙箱与审批是**一条变量**决定的（`dsh-base` 组合原文：`policy: (DSH_PERMISSION_MODE ?? 'workspace-write') === 'danger-full-access' ? 'never' : 'ask'`），而 `workspace-write` 在这台机器上**没有可执行的后端**（无 `bwrap`、Landlock 未暴露）⇒ 只会 fail-closed 把命令拒掉；环境本身又是"真 chroot + 真 root"（uid 0、`u:r:ksu:s0`）。现在 `supervise.sh` 在加载 `$DSH_HOME/env` **之后**兜底 `DSH_PERMISSION_MODE=danger-full-access`（用户写在 env 里的值优先，顺序有自测钉住）——审批功能没被删，是这条变量的默认值换了。④ **壳读取提速**：`exists()`+`status()` 两次 su 合并成**一次**（`statusOrNull()`），轮询 4s/5s → 8s/12s，并且**读失败不再丢掉上一次读到的值**（最多保留 3 轮；判据是"读到了 vs 没读到"，真实 `state=error` 仍立刻显示）。⑤ `doctor` 新增 §1f：影子视图检测 + **挂载回流**检测（判据 `grep -c sunsetlinux/rootfs /proc/1/mountinfo`）。回归：App 单测 **297 → 301 / 0**（新增 `StatusFreshnessTest` 4 条）、`runtime/root/selftest.sh` **127 → 140 / 0**（新增 whereami/交换目录/权限默认值/doctor 四组）|
| 0.3.21 | 37 | **宿主通道（默认关）+ 把 0.3.20 的视角/交换目录/权限默认值一起发全**（模块 **1.0.45**）：0.3.20 推出去之后才把「宿主通道」写完，同一个版本号不会再触发发布 ⇒ 提一版把它带上（0.3.20 的改动内容见上一行，两版一起装即可）。宿主通道要解决的问题是：用户要"给虚拟环境提权"，但实测**权限早就有了**——环境进程是 uid 0、SELinux 域 **`u:r:ksu:s0`**（宿主 root 域），`/proc/1/root` 通着（内核无 `CONFIG_PID_NS`），chroot 从来不是安全边界 ⇒ 缺的是**可见性、约定与审计**，不是权限。实现：环境内 `/opt/sunsetlinux/host-channel.sh status|run <cmd>`（随启动器同步进 rootfs），开关在**宿主侧** `$LINUX_HOME/etc/host-channel.json`（chroot 的 `/etc` 是环境自己的 ⇒ 环境里的进程既看不到也改不到），用 `linuxctl host-channel on|off` 控制；默认关，未启用时 `run` 退 3 且**不执行**；命令里的绝对路径 token 必须落在 `allow` 前缀下（退 4）；每次调用（含被拒的）都写一行审计到 `run/host-channel.log`。定位说清楚：它是**防手滑 + 留痕**，不是安全沙箱（变量/相对路径都能绕过白名单，真要做的人直接 `chroot /proc/1/root` 就行且不留痕）；更干净的"宿主侧常驻 worker"方案写在 `docs/host-channel.md` §5，等真机手感再定。⚠️ 本项目自己的 AI 会话（DSHA 容器）**不会**使用这条通道去执行设备 shell 守卫拒绝过的命令——那是换入口绕过设备策略。回归：`runtime/root/selftest.sh` **140 → 147 / 0**（新增宿主通道一节：默认关退 3、拒绝也留审计、白名单外退 4、白名单内不误拒、未知子命令报错、脚本确实会被同步进环境）|
| 0.3.22 | 38 | **实机核验后的五处修复**（模块 **1.0.46**）：把 0.3.21 / 模块 1.0.45 装上真机逐项核对，抓到 5 个真问题，全部修掉——其中 ① 推翻了 0.3.20 的结论。**① Download 映射从来就没通**（0.3.20 写的"其实早就通了"是**错的**）：环境内 `/mnt/sdcard` 挂的是 `/mnt/pass_through/0/emulated` —— 那是 f2fs 的 `/media`，布局为 `<user_id>/…`（`0/ 997/ 998/ 999/ obb/`），**不是用户存储根**，所以 `ls /mnt/sdcard/Download` 报 `No such file or directory`，软链 `/storage/emulated/0 -> /mnt/sdcard` 一起指错（日志写着"已挂载"、doctor 全绿，用户一个文件都看不到）。现在 `start.sh` 挂 **`<pt>/0`**（inode 与 `/storage/emulated/0` 相同 11058），启动日志多一行**自证**（Download/DCIM 在不在），doctor 新增 `sdcard_not_user_root` 判据；文档 7 处（模块地图、HANDOFF、STATUS、viewpoints、architecture、`linuxctl whereami` 文案）同步更正。**② 交付说明给的三条验证命令有两条跑不通**：`linuxctl whereami` → `bash: linuxctl: command not found`（环境 PATH 不含 `/opt/sunsetlinux`，那里只有带后缀的 `linuxctl.sh`）⇒ `start.sh` 现在在环境内建 `/usr/local/bin/linuxctl` 软链；`echo $DSH_PERMISSION_MODE` → 空值（该变量属于 **DSH 服务进程**，终端 shell 不是它的子进程）⇒ 正确取法写进交接单：`tr '\0' '\n' < /proc/$(cat /run/dsh.pid)/environ \| grep DSH_PERMISSION_MODE`。**③ doctor 三处误报 + 一处口径歧义**：`mount-leak` 的计数写成 `grep -c … \|\| printf '0'`——`grep -c` 无匹配时**自己也输出 0**（只是退出码为 1），于是变量变成 `"0\n0"`、`case 0)` 匹配不上，**隔离本来是好的却报 warn**（detail 里那句"命中 0 0 处"就是现场）；`share-not-mounted` 的判据看的是**宿主 ns** 的 `$ROOTFS_DIR/share`，而 `mount --make-rprivate /` 生效后宿主根本看不见那层 bind ⇒ 改成问**环境 ns**（`/proc/<ns_pid>/mountinfo`）；`port_busy` 在一键启动后**稳定误报**（占 3080 的就是本环境的 DSH）⇒ 新增 `port_in_use_by_env`；JSON 新增 `findings_summary{ok,warn,fail}` 并在两个口径不等时打印说明（屏幕上的"5 项 warn"是**检查项**计数，findings 是**结论条目**计数，本来就不该相等——上一轮把它当成计数 bug 报了一次）。**④ `run/ctl-status.json` 是非法 JSON**：`Protocol.ok()` 期望"字段片段"，四个调用点却都传 `Json.obj(...)` 的**完整对象** ⇒ 拼出 `{"ok":true,{"state":…}}`（缺键名），`status/ping/version/job` **四个 op 全坏**，`jq` 一类解析器直接读不了。现在 `ok()` 剥掉外层花括号再展开，并补了形状断言（旧断言只 `contains` 字段名，所以全坏也没有一条测试变红）。**⑤ App 终端"随机掉线" + 卡死**：掉线根因是 `PtySession` 写成了 `ParcelFileDescriptor.adoptFd(master).fileDescriptor` —— **只取了字段、PFD 对象没有强引用**，GC 一回收它的 finalizer 就 `close()` 掉 master fd，子进程读到 EOF 后**正常退出**（退出码 0、logcat 无任何异常，所以这个 bug 藏得很深）；现在字段持 PFD 本体、`closeQuietly` 关它。卡死根因是 `connect()` 的 `starting = false` 写在 `withContext` **之后**（异常路径漏掉），而 `connect()` 开头是 `if (running \|\| starting) return`、按钮 `enabled = !starting` ⇒ 一旦抛异常，「连接」按钮**彻底失灵**、只能切 tab 重建；现在异常路径也复位（`CancellationException` 复位后再抛）。另：终端状态条新增实时 **`列×行`**（行列算错时一眼可见）。回归：App 单测 **301 → 304 / 0**（新增 `TerminalLifecycleContractTest` 3 条，钉住 PFD 强引用、starting 复位位置、行列显示）、`sunsetd` 单测 **50 → 51 / 0**（新增"ok 响应是合法对象"1 条）、`runtime/root/selftest.sh` **147 → 158 / 0**（bash 与 mksh 各跑一遍；新增共享存储挂载目标 5 条 + doctor 误报修正 6 条）|
| 0.3.23 | 39 | **补一条：环境内的启动器脚本改为「以模块为准」同步**（模块 **1.0.51**）：0.3.22 发出后核对产物时发现——模块里明明改好了 `whereami` 的文案，环境里敲 `linuxctl whereami` 打出来的**还是旧文案**。根因：环境内 `/opt/sunsetlinux/*.sh` 来自 **runtime 层**（erofs 只读镜像），而宿主侧执行的是**模块 bin/** 里那一份 ⇒ 同一个脚本两份副本，模块更新后被层里的旧副本遮蔽（现场判据：`grep -c "Download 就是 /mnt/sdcard/Download" /proc/<dsh_pid>/root/opt/sunsetlinux/linuxctl.sh` = **0**，而模块 zip 里那份 = 1）。这正是 HANDOFF 里「多个重复入口脚本副本会静默吞掉改动」那笔老账的同源问题。修法：`install_runtime_entry` 从"只同步 entry/supervise/host-channel"扩成**同步模块 bin/ 的全部启动器脚本**（linuxctl / doctor / status / stop / start / update / oneshot-setup / selftest / device-provision / layer-spec）+ `fixtures/` + `common/`，失败只 WARN、不影响启动；这些脚本本来就是**双视角**写的（whereami 自己判 env/host），原样同步即可。回归：`runtime/root/selftest.sh` **158 → 159 / 0**（bash 与 mksh 各一遍；新增「启动器脚本按模块为准整体同步」1 条）<br>**模块 1.0.48（App 侧无行为变化，只为带上模块而提模块号）**：把 1.0.47 装上真机实测，共享存储**还是看不见** —— ① 的修复只解决了一半。现场：`run/sdcard.source` 已经是对的（`/mnt/pass_through/0/emulated/0`），启动日志却报 `WARN: /mnt/sdcard 下既没有 Download 也没有 DCIM`、`fstype=tmpfs`，环境内 `/mnt/sdcard` 是个**空的 tmpfs 目录**。根因是**时机**：开机自启发生在 **boot 早期**（实测 `13:19:17` 开机、`13:20:04` 起环境，即开机后 **47 秒**），那时 vold 还没把 `/mnt/pass_through/0/emulated` 挂上，`[ -d ]` 判据看到的是 **tmpfs 上的空占位目录** ⇒ "挂载成功"却没有任何文件（这次是 0.3.22 新加的**启动自证**当场报出来的，否则又会静默）。修法：`mount_sdcard` 改成"**候选源 + 内容校验**"——按开机可用性排序试三个源：`/data/media/0`（**DE 存储，开机即可用**；实测与 `/mnt/pass_through/0/emulated/0` 是**同一个 inode `11058`**）→ `/mnt/pass_through/0/emulated/0` → `/storage/emulated/0`（FUSE），每个都要求"挂上后能看到 `Download` 或 `DCIM`"才算数，不合格就卸载换下一个，三个都不合格才保留最后一个并明确 WARN。回归：`runtime/root/selftest.sh` **159 → 160 / 0**（bash 与 mksh 各一遍；该节改成"候选源顺序 + 内容校验"三条断言）<br>**模块 1.0.49**：真机跑 doctor 时发现口径说明那行漏成 `…findings_summary.warn=%d 是… 2 1` —— `info()` 是 `printf '…%s…' "$*"` 的包装，**不替换 `%d`**，多出来的参数还会被原样追加在行尾；改成把数字拼进字符串，并补了一条断言（同一模式全项目扫过，只此一处）。回归：`runtime/root/selftest.sh` **160 → 161 / 0**<br>**模块 1.0.50**：把 1.0.49 装上真机，在 App 终端里敲 `linuxctl whereami` —— **还是跑不起来**，而且这次是两个新根因：① 入口是**软链**，而模块脚本的 shebang 是 `#!/system/bin/sh`（Android 路径，chroot 里没有）⇒ `bash: /usr/local/bin/linuxctl: cannot exec`；改 `sh linuxctl.sh` 又死在 `set: Illegal option -o pipefail`（环境里的 `/bin/sh` 是 **dash**）。② 用 bash 跑通之后它又报「找不到 status_json.sh」—— 因为环境内 `common/` 是**空目录**：上一版那句 `cp -f "$src/common/." "$dst/common/"` 是 **toybox 不认的写法**，静默失败，而句尾的 `|| true` 把失败也一起吞了。修法：环境内入口改成**包装脚本**（`exec /bin/bash /opt/sunsetlinux/linuxctl.sh "$@"`，每次启动重写），`common/` + `fixtures/` 改**逐文件复制、失败逐个 WARN**；另把 `linuxctl.sh` 的 `set -uo pipefail` 拆成 `set -u` + `set -o pipefail 2>/dev/null \|\| true`（宿主 mksh 行为不变，换个 shell 解释不再当场死）。回归：`runtime/root/selftest.sh` **161 → 163 / 0**（bash 与 mksh 各一遍）<br>**模块 1.0.51**：按用户要求改**刷写提示** —— 移除「本模块是否需要挂载 / 需要 metamodule 吗」那套结论（它讲的是**系统分区覆盖**，写在安装日志里只会让人以为"这模块到底挂不挂东西"），改成直接陈述**实机挂载行为**：系统分区不动（module/ 下没有 system/、vendor/）；环境自身由 `linuxctl start` 在**私有 mount ns** 里按顺序挂 upper.img(ext4+loop) → 三层 erofs 只读镜像 → overlayfs 合成 rootfs → /proc /sys /dev /dev/shm /tmp → 共享存储 /mnt/sdcard → 交换目录 /share，挂载点都在 `$LINUX_HOME/…` 下、`linuxctl stop` 反向卸载，并指向 doctor 的"有没有漏进全局命名空间"自检。`detect_mount_report()`（人读报告）同步改；`detect_mount_json()` 里的 `module_needs_mount` 字段**保持不动**（那是 doctor / WebUI / 自测用的机器接口）。回归：`tools/customize-selftest.mjs` **24 → 26 / 0**（新增两条反向断言：不再出现「是否需要挂载」、必须出现「实机挂载行为」）|
| 0.3.24 | 40 | **免 root 版的「内部提权」补齐：DSH 策略兜底 + 身份说清楚**（模块沿用 **1.0.51**，本版未改模块）：用户点出「**运行进程 UID ≠ 虚拟环境内部 root 提权**」，一查发现两个形态**不一致** —— root 版有 `supervise.sh` 兜底 `DSH_PERMISSION_MODE=danger-full-access`（0.3.20 起），而 **proot 版的 supervisor 是 `entry.sh` 自己实现的，从来没有这条兜底** ⇒ 免 root 用户走 DSH 默认值 `workspace-write` ⇒ `ask` ⇒ 在这类没有沙箱后端的设备上 fail-closed ⇒ **环境里每条命令都要提权**。现在 `runtime/proot/entry.sh` 在加载 `/root/.dsh/env` **之后**做同样兜底（用户写在里面的值优先，顺序有自测钉住）。同时把"身份"讲清楚：proot **不虚拟内核身份**，进程真实 uid 始终是 App 用户；`fake_root`（`linuxctl start --fake-root`，配置在 `etc/config.json`，**默认关**）只是让 `getuid()` 返回 0 的**软件层伪造** —— **没有真 capabilities、不能 mount**；启动日志现在会明确写出当前是哪一种（"伪造 root" vs "uid = App 用户"），免得用户拿 uid 去推断能力。回归：`runtime/proot/selftest.sh` **20 → 23 / 0**（bash 与 mksh 各一遍；新增策略兜底、兜底顺序、身份说明三条）。⚠️ **Root 版用户不必重装这一版**（改动只在 proot 资产里）。|
| 0.3.25 | 41 | **修「两个 App 装不到同一台机器上」：FileProvider 的 authority 被写死了**（模块沿用 **1.0.51**）：用户装免 root 版时被安装器拦下 —— 红字 **「存在同名的 ContentProvider」**。根因：`AndroidManifest.xml` 里 FileProvider 的 `android:authorities` 写死成 `io.github.sunsetrne.sunsetlinux.files`，而 **authority 在系统里是全局唯一的** ⇒ 先装的那个占了名字，第二个 edition（包名不同）就装不上。⚠️ 0.3.0 起文档与 `variants.json` 一直写着"两个 App 不同包名、可共存"，但这条**从没在真机上验过** —— 用户的判断"root 与免 root 不兼容"在旧版上是对的。同一个写死值还让**本 App 自己**对不上：代码请求的是 `"${context.packageName}.files"`（`core/LogExport.kt`、`core/BundledModule.kt`）⇒ **导出日志 / 分享排障包在真机上本来就是坏的**（`getUriForFile` 找不到该 authority 的 provider，直接抛异常）。改成 `${applicationId}.files`，一处同时修掉"装不上"与"功能坏"。产物级验证（不是看源码）：aapt2 读两个 APK 里的 manifest —— root = `io.github.sunsetrne.sunsetlinux.root.files`、免 root = `…sunsetlinux.proot.files`；顺带核实没有别的全局名写死（无自定义 `<permission>`、无 `sharedUserId`；`androidx-startup` 的 provider 本来就带 applicationId）。回归：App 单测 **304 → 305 / 0**（`EditionSeparationTest` 新增一条：authority 必须带 applicationId 且与两个调用点一致）|
| 0.3.26 | 42 | **修「内嵌离线包从来没被用上」——装了 full 版却让你去频道下载**（模块沿用 **1.0.51**）：真机反馈原话 ——「**完整的离线内置版仍然会有这种问题？那我内置它干嘛？**」。根因在 `OfflineBundle.readHeaderOnly()`：它**只读了头部那 507 字节**（真包实测：header 507 B、部件偏移到 989 KB）却把这段字节交给 `parse()`，而 `parse` 里有"部件越界"检查（拿部件 `off+len` 与**入参字节数**比）⇒ **必然抛 `BundleFormatException`**；偏偏那个 `catch` 只抓 `IOException`，异常一路穿到调用方的 `runCatching → getOrNull()` 变成 **null** ⇒ App 认为"本包没有内嵌离线包"，于是引导页写"既没有 rootfs，也没有 base 层/种子；可从频道安装"，按钮也变成"铺环境（执行 provision）"。**所有内嵌档位（root-base / root-full / proot-base / proot-full）都中招** —— 内嵌离线包这条路**从加进来那天起就没成功过**（0.3.25 之前还被 FileProvider 的 authority 撞名挡在安装那一步，所以一直没暴露）。修法：`parse(bytes, headerOnly = false)` —— `headerOnly = true` 时跳过越界校验（那条防线在整包语义下**保留**）；`readHeaderOnly` 传 `headerOnly = true`，并把 catch 放宽到 `Throwable`（本方法的语义就是"尽力读头，读不到当没有"，不该被任何一种解析异常把整条路打死）。顺带修失败文案：`hasRootSource == false` 时**有内嵌包就说"先装内嵌包"**（不再一律"去频道下载"），并把**原来被吞掉的 `outcome.error` 显示出来**（此前只显示失败步骤名，用户看不到原因）。回归：App 单测 **305 → 305 / 0** —— `OfflineBundleTest` 里那条**名不副实**的"只读头也能拿到变体与部件清单"（它传的是完整字节，等于没测）改成**真·headerOnly**：只喂头部，并同时钉住"整包语义下只给头部必须判越界"|
| 0.3.27 | 43 | **维护决策变更：组合 6 → 2，并把「可变 DSH」落成真能力**（模块 **1.0.52**）：用户拍板收敛 —— 原来 2 edition × 3 tier = 6 个组合压到 **2 个**：**`root-minimal`**（**极简 Root + 模块挂载 Ubuntu**：APK 不带任何环境，Ubuntu 由 KernelSU 模块与频道层提供）与 **`proot-full`**（**完整 proot + Ubuntu**：内嵌 base + runtime + proot + **默认自带的 DSH**）；被砍掉的 `root-base` / `root-full` / `proot-minimal` / `proot-base` 只是"内嵌多深"的中间态 —— 收益低，而每个组合都要过一遍编译矩阵与回归。**「可变 DSH」的定义**（原文进 `variants.json` 的 `_decision.variable_dsh`）：① **允许移除** —— 新增 `linuxctl dsh remove [--dry-run]`：只删 `layers/dsh-*`，**base/runtime 一律不碰**，环境在跑时拒绝（层被 loop 挂着删除会留悬挂挂载，与卸载那条坑同源）；② **默认不变** —— 模块 full 变体 / APK 里内嵌那份装完即可用，`linuxctl dsh builtin`（内嵌）/ `dsh install`（频道）随时装回来；③ **回滚按版本** —— `linuxctl rollback dsh [<版本>]` 不受影响。工程侧：Gradle 从"edition × tier **两维笛卡尔积**（6 个 flavor）"改成"**一个组合一个 flavor**"—— 组合数不再等于维度乘积，硬撑两维会生成 4 个没有定义的组合、配置期直接报错；CI 矩阵与任务名的派生规则不变（`root-minimal` → `assembleRootMinimalDebug`）。回归：`tools/offline-bundle/selftest.mjs` **23 → 27 / 0**（改成守住收敛后的两条路线 + `_decision` 里"可变 DSH"三件事）、`runtime/root/selftest.sh` **163 → 169 / 0**（新增「可变 DSH」一节 6 条：dry-run 不删、真删只动 dsh、幂等、JSON 给装回来的路径…，bash 与 mksh 各一遍）、本地实建 `assembleRootMinimalDebug` 与 `assembleProotFullDebug` 均成功 |
> **版本比较的语义**（三处必须一致：App 的 `PURE.cmpVer`、`update.sh` 验签器的 `cmp`、`linuxctl.sh` 新增的 `dsh_ver_cmp`）：
> 主干逐段按数值比；有预发布后缀的更小（`0.1.5-rc.2 < 0.1.5`）。**不要用 `sort -V`** 判这件事 ——
> GNU 的版本序把预发布当**更大**（实测 `0.1.5-rc.2` 排在 `0.1.5` 之后），会把旧预发布当成升级。
