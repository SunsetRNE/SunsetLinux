# 交接：当前状态与续做清单

> 用途：**对话记录可能被删/会被换掉**，所以把"现在到哪了、还剩什么、怎么接着做"落进仓库。
> 和 `docs/STATUS.md`（项目总账）配合看：STATUS 讲**项目本身**做到什么程度，本文讲**这次协作的落点**。
> 最后更新：**2026-09-18（第 43 轮）**（本轮两段：**§3.10.40** toybox nsenter 吃掉**命令之后**的选项 →
> `dsh start` / 终端 attach·exec / stop 的 `umount -l` **三处全坏**，已修（`454ed75`，模块 **1.0.32**）；
> **§3.10.41** 修完推上去才发现**流水线卡在 ⑤ 的 Release 上传**（`d7a2795` 那次从 17:50Z 起挂了 1 小时+），
> 于是改走**本地打包直送手机**：1.0.32 的 full/bare 两个 zip 已在 `/sdcard/Download/`。
> **★ 不建议删库重建**（会永久毁掉 `CHANNEL_SIGNING_KEY` 这把只写不可读的签名私钥），理由见 §3.10.41。）
>
> **★ 下一轮第一件事（真机交接，按优先级）**：
> 0. **拿 `/sdcard/Download/sunsetlinux-module-1.0.32.zip` 装上、重启一次**，然后验本轮修的三处：
>    ① `linuxctl dsh start` 能起来（3080 被免 root 的 DSHA 占着时应落到 **3081**，看 `run/dsh.port`）
>    ② 终端 `attach` 能进、`linuxctl exec -- ls -l /`（带选项)能跑 ③ `stop` 之后挂载/loop 干净了。
>    不重启的话，`bin/` 不会同步到 `/data/sunsetlinux/bin`（那是 `post-fs-data.sh` 开机干的活）。
>    当前环境**还在跑**（`env-mode=env-only`、`run/supervisor.pid=26586`），也可先在终端里
>    `setsid /opt/sunsetlinux/supervise.sh --port 3081 >/root/supervise.log 2>&1 &` 立刻把 DSH 接上。
> 0b. **那条卡住的 run 的收尾**：`35254264922`（`d7a2795`，App 0.3.11 / 模块 1.0.31）默认 6 小时上限约
>    **23:50Z（北京 07:50）**被强杀；我推的 `454ed75` 排队中，它一死自动开跑。要立刻清掉就用 API
>    `force-cancel`（UI 的 Cancel 对不响应的 runner 无效）；**手动 `workflow_dispatch` 是另一条队列、能立刻跑，
>    但它和卡住那条写同一个 Release，若卡住那条后来复活会删掉 1.0.32 的模块资产 ⇒ 先强杀再手动跑**。
> 1. **App 报「频道 签名校验失败，已拒绝该频道」**（0.3.9，22:5x 之后；App 0.3.8 时还是"已是最新"）。
>    第 41 轮**已独立复核发布侧**（本机 curl 走的就是这台手机的网络）：线上 `/channel/channel.json` + `.sig`
>    与 **gh-pages 分支**里的副本**逐字节一致**（sha256 `f28669bb…` / `a550ae0d…`），用 App 内嵌公钥按 App 的
>    算法（base64 → SPKI → Ed25519）**验签通过**；`.pub` 是 404 但 App 从不取它（内嵌公钥、无缓存）。
>    代码侧也排除了嫌疑：`Update.kt` 最后一次改动是 **0.2.4**、`Net.kt` 未动。
>    ⇒ **现在要的是现场**：① 此刻是否仍复现（重开「更新」页点一次检查）② 界面上是**哪个频道名**（官方？自建的？）。
>    第 41 轮已把拒绝理由改成**自带证据**（公钥指纹 / 清单字节数 + sha256 / 签名解出的字节数），装了 0.3.10 之后
>    那张截图就能直接定位（指纹与文档那行 `ed25519:06:d0:c4:4d:29:1c:ef:66` 对不上 = 换了钥匙；对得上 = 这次取到的
>    清单/签名有问题，多半是 CDN 两份文件不同步）。
> 2. ~~`nsenter: Unknown option 'wd=…'`~~ → **第 41 轮删 `--wd=` + 第 42 轮补 `--`，两段合起来才算修完**。
>    第 41 轮只清掉了**我们自己的** cwd 选项；本轮实测发现 toybox 还会把**命令之后**的选项（无论长短）
>    一并当成自己的：`… /bin/echo -l` → `Unknown option 'l'`、`… --port 3080` → `Unknown option 'port'`
>    （真机 02:00 的 `dsh start` 就是这条）。修法是 **9 处调用**（`linuxctl` 7 = spawn×2 + `run_in_env`×2 +
>    ns 内卸载×3，`stop.sh` 2 = `umount -l`/`-f`）在命令前写 `--`（`--` 之后才真正交给命令）。
>    **仍需真机端到端验**：① `linuxctl exec -- ls -l /`（带选项的用户命令）② 终端 `attach` 能进 ③
>    `dsh start` 能起来（若 3080 被免 root 的 DSHA 占着，新逻辑会自动换端口，看 `run/dsh.port`）。
> 3. **「root 已授权 + linuxctl 已就位，但模块状态未知（经 su 读取失败）」**（欢迎页/环境检测）：自相矛盾，
>    要拉一次 su 那步的原始 stderr 再定位（可能只是刚更新 App/模块后授权需重新确认）。
> 4. **DSH Web 是桌面版落地页**（"探索未至之境 / 选择工作区"）：移动版界面来自**第三方插件 `dsh-web-mobile`**
>    （见 `rootfs/profiles/web-profile/package.json` 的 bundles：`@deepseek-ai/dsh-base` + `@deepseek-ai/dsh-web-app`
>    + `dsh-web-mobile` + `dsh-task-notifier`）。所以要么是环境里 `$DSH_HOME/profiles/web` 或它的 `node_modules`
>    没就位（`supervise.sh` 会先检查并在缺失时退 78），要么是离线环境下装不上这些 bundle 而退化成桌面版。
>    查法：`linuxctl exec -- sh -c 'cat /root/.dsh/profiles/web/package.json; ls /root/.dsh/profiles/web'`。
>
> （另：设备侧已核过 —— 模块 **1.0.30** 在跑、`env-mode=env-only`、DSH 没起；`127.0.0.1:3080` 与
> `[::1]:3090` 都归 uid 10497（**免 root 那个 DSHA App 自己**，不是残留 dsh），`3081` 空闲。
> 老那条"残留 pid 4898 占着 3080"已作废：那个进程早没了。）

---

## 〇、一分钟版

- 分支：`main` = `beta` = **本次那个提交**；`channel` = `0d1efff`；`gh-pages` 由 CI 独占。
- 发布：`/stable/` **run 44**、`/beta/` **run 45**，都是 **`4d53d02`**：模块 **1.0.5** + App **0.2.1**。
  再往前一格 `abb321f` 是 run 42/43（模块 1.0.5 + App 0.2.0）。
  **真机第一跑（05:36）→ run 46/47（`327001c`）：模块 `1.0.6` + App `0.2.2`**（两个坑，见 §一 第 14 条）。
  **真机第二跑（05:52）→ run 48（`60c4206`）/ 49·50（`c8c84e7`）：模块 `1.0.7`**（空数组那个坑 + 删除误报，见 §一 第 15 条）。
- **下载页**（与 Releases 页**双发布**，产物是同一份）：
  <https://sunsetrne.github.io/SunsetLinux/> → `/stable/`（正式）· `/beta/`（预发布）。
- **内置官方频道的公开指纹**（与 `core/Prefs.kt` 里写死的那把是同一把，App 自带的
  `OfficialChannelContractTest` 会**逐字比对**这段文本，改这里必须同步改代码）：
  - URL：`https://sunsetrne.github.io/SunsetLinux/channel/channel.json`
  - ed25519 公钥：`YXoAcwa3clZCRd7+DlIHMS3cQ40HlyXVSKblvX5Ye1M=`
  - 指纹：`ed25519:06:d0:c4:4d:29:1c:ef:66`
- **你自己那台机器（2026-09-16 05:10~05:30 实测，root shell 逐条核过）**：
  - 模块 **1.0.3**、App **0.2.0/2**（都该升：1.0.5 + 0.2.1）；
  - `/data/sunsetlinux/layers/` **是空的**；`upper.img`（8 GiB 稀疏）与 `etc/state.json`
    是 `linuxctl provision` 建的（那个命令**不构建层**）；
  - `seeds/` 里两个种子**都在、且与官方校验和逐字节一致**：
    `ubuntu-base-24.04.3-base-arm64.tar.gz` = `7b2dced6…b048`、`node-v24.21.0-linux-arm64.tar.xz`
    = `6ad1325e…9ad2`（对着 cdimage.ubuntu.com / nodejs.org 的 SHA256SUMS 核的）；
  - 构建要用的设备侧工具**齐全**：`unshare`/`chroot`/`mount`（toybox）、`mkfs.erofs`、`mke2fs`、
    `tar`、`sha256sum`、`awk` 都在；**只有 zstd 没有**（所以分发走 `.gz` 回退，这是预期）；
  - → **下一步就是 §二·1**：装模块 1.0.5 → 重启 → 装 App 0.2.1 → 点「首次部署向导」
    （或自己敲那条 `sh …/device-provision.sh --seeds /data/sunsetlinux/seeds`）。

**分支与 CI 拓扑**（细节见 `docs/release-ci.md` §二·五）：

| 分支 | 内容 | 谁维护 |
|---|---|---|
| `main` / `beta` | 源码主线 / 预发布（同内容） | 人（`git push origin main:beta` 快进） |
| `channel` | 只有频道数据（公钥、加密私钥备份、签名工作流） | 人 + 该分支 CI 补 `.sig` |
| `layers` | **层产物传送带**（orphan：`.erofs.zst/.gz` + `layers-release.yml` + 可选 `TAG`） | 人（每次 `--force` 重置） |
| `gh-pages` | 发布产物 `/stable/`、`/beta/`、`/channel/` | **CI 独占** |

CI 四条线路：① `ci.yml` 回归门禁（shell / node / android 三并行）② `release.yml` 发布（复用①的 APK 制品）
③ `layers.yml` 层与频道（手动、arm64 自建 runner）④ `layers-release.yml` 层托管（`layers` 分支 → Release 资产）。

---

## 一、本轮做完的（都有证据，不是"应该能行"）

| # | 提交 | 做了什么 | 证据 |
|---|---|---|---|
| 1 | `2a9512d` | **proot 宿主侧脚本 mksh 化**（`=~`/`for ((`/进程替换/`local x=()`/shebang），并修掉 `mksh -n` 抓不到的运行时缺陷：**bash 的 `/dev/tcp` 在 mksh 里不存在**（端口探测改三层回退）；`runtime/root/start.sh` 的 `port_busy` 一并修 | 欠债名单清零；`selftest-funcs.sh` 65/65（bash+mksh）；`mksh proot/selftest.sh` 18/18；契约 4 组合通过 |
| 2 | `ae8987b` | **层托管管线**（`layers` 分支 → Release 资产）；修 `channel.yml` 预检**缺 `-L`**（Release 地址 302，会把正常清单判成"下载不到"） | 实测同一 URL 无 `-L`=302 / 加 `-L`=206；产物守卫模拟 4 情况；YAML+28 run 块语法通过 |
| 3 | `dcef953` | **App 外壳重排**：DSH 上顶栏、底栏改 启动/插件/**终端**、更新进侧边栏；**新增内置终端**（`linuxctl attach` 常驻会话，明确无 PTY 的限制） | App 单测 62/0；`ShellLayoutContractTest` 7 条 |
| 4 | `ea0a7eb` | **模块 WebUI 去 emoji → 内联 SVG 图标**；**内置官方频道**（URL+公钥写死在代码、读时并入写时剔除、UI 只留启用开关） | WebUI 自测 64/64（+3 条图标守卫）；`OfficialChannelContractTest` 4 条 |
| 5 | `840c75a` | **修 APK 签名随机漂移**（原先无 `signingConfig` → 每次 CI 一把新 debug key）；**App 0.1.0/1 → 0.2.0/2** | 发布包签名 = `5d5fa724…69f7` = 仓库内固定密钥（改前是 `84be9523…`）；`assembleRelease` 也过 |
| 6 | `cd054a1` | **懒人体检/一键部署 `oneshot-setup.sh`**（任意目录、MT 系统/扩展包双模式、默认只读、`--run` 才动手） | 假设备布局 4 场景 + `--run` 桩流程；进 mksh 闸门 |
| 7 | `8a88ddd` | oneshot 补 `truncate`/`sha256sum` 体检 + **模块版本 vs 官方发布**比对 | 真机输出驱动；本地复现"设备 1.0.0 / 官方 1.0.2"提示 |
| 8 | `890d54a` | **下载页**：站点根 + `/stable/` + `/beta/` 由 CI 每次生成 `index.html` | `/stable/` 从 `application/json` 变 `text/html`；站点根 404 → 200；本地跑生成器 + HTML 闭合检查 |
| 9 | `cd537c2` | **修我自己的 bug**：`bad()` 定义被"删空行"编辑并进注释 → `mksh -n` 照过、真机只多一行 `bad: inaccessible…`，**且阻断计数永远 0**；新增 `tools/oneshot-selftest.mjs`（29 条，进 CI） | 冒烟在 mksh+bash 下断言"八段都在、无 not found、缺模块/缺层必须报阻断" |
| 10 | `2113217` | **proot 能力审计 + 随包 proot 5.1.0(2014) → 5.4.0**；修 `--link2symlink` 在老 proot 上直接 fatal；修 bundle 工具自检判据 | bundle 988,717 B / `3d72e0c3…`；两版 proot 实测两条分支；`--verify` 通过 |
| 11 | `bfabbc8` | 把"CI 不产出 proot bundle"**说出来**（不再静默跳过） | job summary 有 ⚠️/✅ 分支 |
| 12 | *(本次)* | **`device-provision.sh` mksh 化 —— 拆掉设备侧首次部署的 bash 依赖**（下一节详述） | 新增 `tools/provision-selftest.mjs`（28 条，进 CI）；mksh+bash 下真跑内层到「准备 base 阶段」；模块 **1.0.5** |
| 13 | *(本次)* | **修 App「首次部署向导」：缺层时真的去建层** —— 它原先只调 `linuxctl provision`，而那个命令只建目录/可写层/配置、**从不构建层**，于是真机上向导必然以"provision 失败"收场；现在缺层时改调 `device-provision.sh`（root 模式），proot 模式明确指向频道 | 新增 `ProvisionPlanTest`（9 条）+ `ProvisionWiringTest`（3 条）；顺带修 `Proc.stream` 把 stdout 丢掉的老毛病（`missing_layers` 就在 stdout）；App 单测 **74/0**；App 0.2.1/3 |
| 14 | *(本次)* | **真机第一跑（05:36）暴露的两个坑**：① **Android 的 mksh 没有 `printf %q`** → 生成内层引导脚本那一段当场 `printf: bad %q@20`，整个部署就此终止（容器里的 mksh R59 **有** %q，所以本地测不出来）；② **模块从来没打包 `rootfs/profiles/`** → 真机日志 `素材：base.packages=未找到`，会跑到 dsh 阶段才 die（白等 20 分钟） | 改法：内层改成**原样重放命令行参数**（彻底不依赖 %q）；mkmodule 打包 profiles/ 并加必需项断言；preflight 对素材做 **fail-fast**；顺带加 `find -exec … {} +` 的**能力探测+降级**；新增 `squote()`（POSIX 单引号转义，sed 实现）。provision 冒烟 **35 条**；模块 **1.0.6**、App **0.2.2/4** |
| 15 | *(本次)* | **真机第二跑（05:52）又逮到两个**：① **mksh 里 `zargs=()` 不是空数组** —— 随后 `"${zargs[@]}"` 在 `set -u` 下 `zargs[@]: parameter not set`，而默认 EROFS_COMPRESS=none 正好走这条分支，**每个阶段都在打包前静默死掉**（日志里只有一行 stderr）；② 删除检测把 13 个「软链还在、只是绝对目标要从 chroot 里才解析得到」误报成删除 | ① `make_erofs` 去掉数组，改`erofs_args_for <n>` 纯函数 + while；② 交叉核对改 `-e || -L`，并把 `/etc/systemd` 一起裁掉；新增 **make_erofs 行为回归**（抽函数出来用桩 mkfs 跑降级链，mksh+bash 各一遍；把旧写法换回去会立刻红）。provision 冒烟 **51 条**；模块 **1.0.7** |
| 16 | *(本次)* | **修「一打开就闪退」**：`LauncherViewModel.init` 的 zstd 能力探测走 aircompressor 的 direct-ByteBuffer/Unsafe 快路径，在 Android 上 **SIGSEGV**（crash dump: `art::Unsafe_getInt` ← `ZstdFrameDecompressor.verifyMagic`）；SIGSEGV 杀整个进程，`try/catch` 拦不住 → 用户只能卸载重装 | zstd 改成**流式解码**（`ZstdInputStream`，只用堆内 1 MiB 缓冲，与 gzip 路径同构、内存更低）；新增源码级契约 `LayerTransportUnsafePathTest`（不许 direct buffer/mmap 回来）。App **0.2.3/5**；App 单测 **78/0** |
| 17 | *(本次)* | **runtime 阶段顺序错**：裸 ubuntu-base **不带 xz-utils**，而 `runtime.packages`（含 xz-utils/curl）原先是**最后**装的 → `xz -t` 把 sha256 与 nodejs.org 完全一致的种子判成"不是完整 xz 包"（真机 06:09 死在这，base 已建好） | 把 runtime.packages 安装（含补上的 `apt-get update`）挪到**阶段最前**；
| 18 | *(本次)* | **层口径错**（更要命，是推理+对照宿主侧发现的）：设备侧原先**每层都重新解包 base**，于是 base 层成了"与裸 base 的差集"→ 漏掉 3400 多个未改动文件（bash/coreutils…），而 `start.sh` 的 lowerdir 只有 base:runtime:dsh、没有裸 base 兜底 → 合并视图里它们直接消失；dsh 阶段更是要跑 npm（来自 runtime 阶段的 /opt/node）却拿不到 → 必死 | 改成**累积构建**：base = 与"空层"比（完整层）、runtime/dsh = 与上一阶段状态比（增量），与宿主侧 `make_layer_stage` 同口径；跳层显式拒绝。provision 冒烟 **51 → 62 条**；模块 **1.0.8** |
| 19 | *(本次)* | **零点击部署**（用户：还得点开 App 再点「执行部署」，有点麻烦）：模块 `service.sh` 原先只在「有层」时自启，没层就只记一行日志 | 开机发现没层就**自己建**（`autoprovision_decision` 纯函数定触发条件：已尝试过 / 种子不全 / 空间不足 / 开关关 → 都不跑，且**先落标记再启动**，绝不每次开机重来半小时）；另加 device-provision.sh 的**部署锁**（三条发起路径互斥）。provision 冒烟 **62 → 82 条**；模块 **1.0.9** |
| 20 | *(本次)* | **root / 模块检测升级**（用户：对 root 授权的检测和模块的检测）：以前只有一个 `suAvailable: Boolean`，界面只能写「su 不可用」；模块状态 App 完全不知道 | 新增 `core/DeviceStatus.kt`：root 细分为 已授权 / 被拒 / 无 su / 超时（各带**下一步**），模块读出装没装 + 版本 + 停用 + **装了没重启**，首启引导与部署向导都显示；`needsInstallDecision` 修好「本地版本读不出来 ⇒ 永远看不到可更新层」（老 state.json 就是这情况）。App 单测 **78 → 94/0**；App **0.2.4** |
| 21 | *(本次)* | **官方频道首次上线**：三层预构建镜像（base 27.8 MB / runtime 77.1 MB / dsh 50.1 MB，gzip 合计 155 MB / zstd 96 MB）推 `layers` 分支 → Release 资产 → `channel.json` 签名发布 | 签名用 App **内置公钥**验过（指纹 `ed25519:06:d0:c4:4d:29:1c:ef:66`）；层文件逐层**真解压复算** sha256_raw/size_raw，且 .zst/.gz 两条路结果一致 → **App「更新」页点两下装完，不用再等 30 分钟构建** |
| 22 | *(本次)* | **内置（离线）包工具链落地**（用户：「后面开始做 ubuntu 内嵌等多个版本」）：`tools/offline-bundle/`（`variants.json` 是组合的**唯一事实源** + `mk-bundle.mjs` 容器打包/校验/解包 + `selftest.mjs` 26 条）＋ `.github/workflows/offline-bundle.yml`（从**签名频道清单**拉层、逐层校验、`--available` 打齐的组合、挂到产品 Release） | 实测：minimal **0.9 MiB** / ubuntu **65.1** / ubuntu-proot **66.0** / ubuntu-proot-dsh **97.2 MiB**；容器 `SLB1`+头 JSON+载荷，层部件带 `sha256_raw/size_raw`；回归含三类负例与**真产物逐字节回验**；CI 跑通后四个 `.bin` 已挂在 `v0.2.4` 上。顺带修掉新工作流的站点地址拼接 bug（owner/repo 当成了两层路径）|
| 23 | *(本次)* | **内置组合命名定案 + App 变体/真内嵌 + 下载页重画**（用户：先把组合命名定下来，然后改 App、命名与 pages）。定案：`minimal` 最小版 / `ubuntu` Ubuntu 版 / `ubuntu-proot` 免 root 版 / `ubuntu-proot-dsh` 完整离线版；APK `SunsetLinux-<版本>-<组合>[-debug].apk`、离线包 `SunsetLinux-<版本>-<组合>.bin` | App：版本号移到 `app/version.properties`（+ 标准版本号 `<ver>-<时间>-<hash>` 注入 BuildConfig）、四个 flavor（同包名/同签名/同 versionCode，换组合=覆盖安装）、`EmbedOfflineBundle` 把离线包作为 generated assets 内嵌、APK 改名走 AGP 9 的 `VariantOutputImpl`；新增 `core/OfflineBundle.kt` 容器读取（纯函数 parse + 范围取部件 + sha256 校验，单测 101/0）。发布：门禁加 `embed_bundles` 输入（**只有发布路径**先拉层打包再构建，于是 APK 真的自带环境：13.7 / 80.9 / 81.9 / 114.7 MB）；**APK 改挂 Release**（内嵌后单文件 114 MB 超 git/Pages 的 100 MB 硬限），gh-pages 只留 index.json/模块/页；下载页重画成"四个组合各一张卡（内嵌什么+体积+sha256+按钮）"|
| 24 | *(本次)* | **离线安装真正可用 + 模块检测三态 + 更新页结论不再撒谎**（用户：更新流程各个包的流程 UI 与检测流程；强化模块检测——"我本人已经重启的情况下，它显示没刷入"；更新「关于」页使其更新实际可用）| App：新增 `core/OfflineInstall.kt`（`OfflineApplier`：assets 流式取部件 → 包头 sha256 → 解压 → `sha256_raw` → `linuxctl update --version`，与在线更新**同一条落盘路径**；顺序 proot → base → runtime → dsh，root 模式跳过 proot）、`core/ProotRuntime.kt` + 构建期 `syncProotRuntimeAssets` 把 `runtime/proot/` 随 APK 走 assets（缺 `linuxctl.sh|start.sh|entry.sh` 即**构建失败**）、模块检测改三态（`ModuleStatus.readable`，读不到 ⇒ 「模块状态未知」+ 中性色 + 去授权提示，三处胶囊同步）、「更新 → 本机包」显示每部件**本机版本 → 包内版本**与缺项数并给「离线安装（N 项）」/「只装缺的」按钮、频道全失败时先报 **频道检查失败：原因** 而不是"已是最新"、`linuxctl.sh` 增加过期 `run/last-error` 自愈（模块 **1.0.10**）。单测 109/0（新增内嵌计划/顺序/proot 脚本齐全 6 条；模块三态 2 条）|
| 25 | *(本次)* | **App 内更新 KernelSU 模块**（用户：「更新模块『关于』页面，使其更新实际可用」，并在追问里选了"要 App 内一键下载并刷入模块 zip"）| 关于页新增模块卡片：`ModuleRelease` 读官方 `index.json`（stable → beta）取 `module_version`/Release tag/`files[].sha256`，`compareModuleVersion` **逐段数字比**（`1.0.10 > 1.0.9`），`ModuleInstaller` 下载 → sha256 → `/data/local/tmp` → `ksud module install`（无 ksud 退 `magisk --install-module`）；刷完只提示"重启后生效"，**不代为重启**（单测断言脚本里无 `reboot`）。App **0.2.6**；单测 116/0（新增模块更新 4 条 + 真机 module.prop 1 条）|
| 26 | *(本次)* | **修"层都在、却永远挂不上"**（用户截图：三层文件与 state.json 都齐，App 一直显示「未挂载 / 失败（文件/层缺失）」）| 真机 `run/service.log` 只有一行 `linuxctl: line 34: syntax error: bad substitution`：① `service.sh` 用裸 `sh "$CTL" start`，模块 PATH 里 `sh` 是 **busybox ash**，它解析不了 `${BASH_SOURCE[0]:-$0}`；② 就算跑到 `linuxctl start`，它又用 `bash start.sh` 去挂载，而设备上**没有 bash**（`/system/bin/bash` 实测不存在），同样的模式还有 stop/update/doctor/status/uninstall。修法：`linuxctl.sh` 新增 `pick_shell()`/`$SH_BIN`（宿主/CI→bash，Android→`/system/bin/sh`），`service.sh`/`uninstall.sh` 直接执行或显式 `/system/bin/sh`，9 个脚本的 `${BASH_SOURCE[0]:-$0}` → `$0`；`shell-compat-check.mjs` 加三条闸门（BASH_SOURCE 下标 / `bash <脚本>` / 裸 `sh <脚本>`），`runtime/root/selftest.sh` 加"剥掉 bash 的 PATH 也能 start（必须是业务错误）"的行为回归。模块 **1.0.11**、App **0.2.7**；根 selftest 20/0（bash+mksh）、provision 冒烟 83/0 |
| 27 | *(本次)* | **模块安装文案重排 + 修被截断的句子**（用户贴出安装日志：「安装执行脚本我觉得该更新了，尤其是这文案」）| 真机那段 `★ 更省事：…（零点击，` / `只在没有任何` 是 `ui_print "…"没有任何…"…"` 造成的句子截断（后半句被当额外参数吞掉，`mksh -n`/`bash -n` 看不见）。整段重写为**先推荐"装完重启一次就行"**（1.0.9 起开机自建层），手动 provision 与 App 频道装层作为备选；命令一律 `/system/bin/sh` 并解释原因；打印本次模块版本。新增 `tools/customize-selftest.mjs`（沙箱真跑 + 内容断言 + "ui_print 参数只能有一对双引号"静态判据，注入缺陷验证过会红）并接入 CI。模块 **1.0.12** |
| 28 | *(本次)* | **引入 proroot 作免 root 首选运行时，proot 降级**（用户：「我打算引入 proroot 进一步强化免 root 方案实现。已有的 proot 在相应的安装包上作为降级实现」；三个决定：接受专有许可"只随 APK 分发"、保留现有变体 id、最小版也带 proroot）| proot 走 ptrace（每条系统调用一次上下文切换），proroot 是 LD_PRELOAD（无 ptrace 往返）、CLI 同一套。5 个 .so（~650 KB）随 APK 进 `jniLibs/arm64-v8a`（四组合都带），构建期从上游 Release 取并校验 sha256（账本 `tools/proroot/VENDOR.json`）；`resolve_rootless()`：auto/proroot/proot，auto 缺件降级**记原因**、显式 proroot 缺件**明确失败**；`status` 新增 `rootless:{kind,version}`（`mode` 仍是 `proot`，不改契约）、`doctor` 有 `rootless_runtime`、`run/rootless` 供另进程读；App 透传 `SUNSETLINUX_NATIVE_LIB_DIR`+版本、「设置」页可选、「关于」页 attribution。**许可**：专有 → 不进仓库/不进 Release 资产/不 strip（keepDebugSymbols）+ 许可原文进 assets + `compliance-test.mjs` 闸门（11/0）。App **0.2.8**；selftest-funcs 68/0（bash+mksh）、App 单测 119/0、四个 APK 均 +0.2 MB |
| 29 | *(本次)* | **终端接上原生 PTY**（用户："帮我检索一下终端对 Ubuntu 命令行操作接入实现"，并在四条路线里选了 jniLibs + forkpty）| 老实现是普通管道：Ctrl-C 无效（本地实测无 PTY 时 0x03 毫无反应）、vim/htop 不可用、apt 进度条花屏。现在 `pty.c`（NDK 编译，11.6 KB 随 APK）按 Termux 的同一套顺序分配 PTY（保留 ISIG/ICANON、关 IXON/IOFF），`PtySession` 做字节流 I/O，`LinuxCtl.ptySpec()` 统一 `/system/bin/sh -c "exec …"`（execve 不查 PATH，su 路径各 ROM 不同）；尺寸由 `BoxWithConstraints` 算行列 → TIOCSWINSZ；输出交给新增的纯 Kotlin VT 模拟器（12 条单测：CR 覆盖/CRLF 例外/ESC[K/定位/SGR/滚屏/UTF-8 分片）；原生库缺失时**降级**回行缓冲并明说。构建走 `tools/ndk-build-pty.sh`（官方 NDK 只有 x86_64 宿主工具链，aarch64 上 AGP externalNativeBuild 跑不了）。App **0.2.9** |
| 30 | *(本次)* | **真机 doctor 定位：start.sh 被 set -e 带走 + 挂载探测误报**（用户贴出完整 doctor 输出）| ① 唯一真 blocker：`start.sh` 的 mount 探测命令**本来就设计成失败**，而 `out="$(mount --rbind …)"; rc=$?` 在 `set -e` 下让脚本在探测第一行退出（真机 cmdprobe 0 字节、linux.log 停在"开始探测…"），**真正的挂载步骤一行没跑到** —— 本地 1:1 复现（--probe-only 退出 1）；修法：4 处改 `rc=0; out="$(…)" || rc=$?`、probe_get 加 || true、整段探测 set +e；并加回归「--probe-only 必须落 probed=」。② 误报 `本模块需要挂载: 是`：doctor 用 `sh -c` 跑探测 → `$0`=`sh` → detect-mount 的"取脚本上一级"退化成 cwd（用户在 `/` 跑 → `/system` 必在）→ 现在必须显式 MODULE_ROOT，否则三态"未知"；新增 tools/detect-mount-selftest.mjs（5 条）接入 CI。③ avc 242 条全是厂商 HAL 扫 ksu，分级为 info。模块 **1.0.13** |
| 31 | *(本次)* | **挂载冲突检查（§1d）+ 机制澄清**（用户："我主要是怕挂载冲突的问题，模块本身就有自动挂载机制，项目的模块再写个挂载，怕不是会冲突哟"）| 查清 KernelSU 侧：挂载已交给 **metamodule**（单实例、`metamount.sh` 把常规模块的 `system/` overlay 到 Android 系统路径、必须 `source=KSU`；`magic_mount_rs` 是内核级路径重定向、不产生 mount 条目）。我们的挂载目标全在 `$LINUX_HOME/**`、在 `unshare -m` 的私有 namespace 里、模块本身**没有 `system/`** → 三条硬理由说明**不冲突**；且"交给 metamodule 挂"不可行（钩子契约 + 单实例会顶掉用户的 magic_mount_rs）。新增 `docs/mount-conflict.md` 与 doctor **§1d**（五条判据：模块自身、私有 ns 隔离、全局泄漏、loop 占用、运行期存续），selftest 加一条断言。模块 **1.0.14** |
| 32 | *(本次)* | **无 loop 层模式（dir）+ doctor §1e**（用户选了"加无 loop 目录模式作兼容开关"）| `start.sh --layer-mode loop|dir`：dir = `fsck.erofs --extract` 把三层解成目录 + `dirs-upper/` 真目录 + 目录 overlayfs，**不碰 loop/erofs/upper.img**；优先级 命令行>env>config.json>loop，非法值立刻报错；App「设置 → 层模式」可切、「关于」/`status.layer_mode` 显示实际用的；doctor §1e 报模式+解包器+三层戳+占用。selftest 新增 14 条（解析优先级/幂等/换层只重解/解包器缺失明确失败），bash+mksh 34/0。App **0.2.10** / 模块 **1.0.15** |
| 33 | *(本次)* | **首启引导第 2 步改成真正的刷模块引导（模块包内嵌进 APK）+ 修掉 doctor 的 3 处假警报与"只读能挂、读写挂不上"**（用户给了引导页截图："在勾选的地方做 Root 模块刷写引导，内置模块包"；并把真机 doctor 输出整段贴过来："顺便解决一下日志问题"）| ① 引导第 2 步原来写"选择模块包：`dist/sunsetlinux-module-0.1.0.zip`" —— 那个路径**在用户手机上不存在**，勾选框因此卡死。现在构建期把 `dist/sunsetlinux-module-*.zip` 内嵌成 `assets/module/sunsetlinux-module.zip` + `module.json`（版本/sha256/大小，`SyncBundledModule`），卡片上「一键刷入内置模块」= assets 落盘（校验 sha256）→ `ksud module install`，「导出模块包到 Download」「打开 KernelSU/Magisk 管理器」为手动路径，勾选框 = 用户断言 **或** 设备侧事实（已装+已启用+已重启）。② 诊断页 `✗ 自检未通过：== 0. 运行环境 ==` —— `CtlResult.message` 取的是 stdout 第一行，而 doctor 第一行非空内容就是第一个小节标题；改成解析 JSON 尾行的 fails/warns + 带 `[fail]` 的原文。③ doctor 的假警报：`CONFIG_SQUASHFS 未启用`/`内核不支持 squashfs` 判成 fail（本项目只用 erofs）、`dsh 层内没有 /root/.dsh/profiles/**` 与 `runtime 层里没有 pnpm`（检查拿 `dump.erofs --ls --path=/` 的**不递归**输出 grep 深层路径，永远匹配不上）、loop 计数（`grep -c ":'"` 恒为 0，输出"在用 1 个"却列 4 个）、与本项目无关的 avc denial 在 JSON 里被打成 warn。④ 唯一真 fail（`挂载 upper 失败…I/O error`）：`start.sh` 改逐级自愈（清残留 loop → `e2fsck -p` → 显式 `losetup`+`mount` → 抓 dmesg 原文进 last-error），四次都失败自动降级 dir 层模式。证据：selftest bash+mksh **42/0**（新增 6 条：squashfs 不得 fail、层内路径正/反例、`mount_upper_rw` 在 toybox `-o loop` 失败后靠显式 losetup 挂上、失败后确实先清 loop 并跑过 e2fsck -p）；App 单测 **134/0**（注意：本机 locale 是 POSIX，Kotlin 增量编译会因中文测试名写出不可映射的文件名而 ICE——`gradlew --stop` 后用 `LANG=C.UTF-8` 重跑即过，与本次改动无关）；模块 zip `dist/sunsetlinux-module-v1.0.17.zip`（209116 B，sha256 `88c00d8e…7dd9`）；APK `SunsetLinux-0.2.11-minimal-debug.apk` 内含 `assets/module/sunsetlinux-module.zip`（sha256 与 `module.json` 一致），签名 `5d5fa724…69f7`（仓库内固定密钥 ⇒ 可覆盖安装、KSU 授权不失效）。App **0.2.11** / 模块 **1.0.17** |

| 34 | *(本次)* | **免 root 模式的引导从"指路"改成"真的能铺"，两个模式的流程从 UI 上彻底分开**（用户："疑似部署引导不完整，Root 和非 Root 模式流程需要不一致，进一步从 UI 上切割区分开来，从根源上完善引导流程"）| 用户贴的是 App 诊断页输出（模式 **PROOT**）：`✗ 没有找到 linuxctl：…/files/sunsetlinux/bin/linuxctl / 请先在侧边栏「重新部署 / 首启引导」里完成部署`。两条根因：**①** 免 root 的 `bin/linuxctl` 是随 APK 内置的（`assets/proot-runtime/`，50 KB，`ProotRuntime` 早就会铺），而引导只写"到「更新 → 本机包 → 离线安装」点一下"——那个页面装层/运行时、**不铺 `bin/`**，于是用户照做后 doctor 永远报"没有找到 linuxctl"；**②** 频道/离线包给的层是 **erofs**，而 proot 的 `linuxctl` 只认 tar（`archive_test` 判损坏、`extract_archive` 用 tar 解）→ 引导里"从频道装三层再回来启动"在免 root 下**不可能成功**。改法：App 新增 `core/ProotSetup.kt`（就绪度 + 有序步骤 + 每步"点哪里"，`plan()` 纯函数 + 5 条单测），引导**按模式给不同步骤名与动作**（root `选模式/装模块/部署/完成`；免 root `选模式/铺运行时/铺环境/完成`），免 root 分支三张卡片三个真按钮（铺内置脚本 / 铺环境=内嵌离线包+provision / 打开部署向导），诊断页缺 linuxctl 按模式给不同下一步，部署向导在 proot 下**先自动铺脚本**；模块 **1.0.18**：proot 认得并解得开 **erofs 层**（`is_erofs` magic 在偏移 1024 / `archive_test` 按 magic 校验 / `extract_archive` 走 `fsck.erofs --extract=`，同 `SUNSETLINUX_EROFS_EXTRACT` 覆盖点）、`find_base_layer` + `provision` **没种子时自动拿已就位的 base 层当 rootfs**、`doctor` 新增"部署就绪度"三件套（bin/linuxctl / proot 运行时 / rootfs，+ 有层但无 fsck.erofs 的告警）。证据：`runtime/proot/selftest-funcs.sh` 新增 10 条、bash+mksh **78 通过**（各含 3 条**既有**失败：本容器 `/proc/net/tcp` 受限，`port_open_local` 那组跑不过，已在 HEAD 上复现同样 3 条）；App 单测 +5（`ProotSetupTest`）。App **0.2.12** / 模块 **1.0.18** |

### 第 34 条：免 root 引导的两个根因与改法

用户的原话："疑似部署引导不完整，Root 和非 Root 模式流程需要不一致，进一步从 UI 上切割区分开来，
从根源上完善引导流程。"

| # | 引导里写的 | 真相 |
|---|---|---|
| 1 | "到「更新 → 本机包 → 离线安装」点一下"（铺 proot 运行时） | 那个页面装的是**层与 proot 二进制**，而 `bin/linuxctl / start.sh / entry.sh` 这套**宿主脚本**是 APK 内置资产（`ProotRuntime` 会铺）——**界面上没有这个动作**，所以用户照做后 doctor 依旧报"没有找到 linuxctl" |
| 2 | "从频道安装三层，再回来启动" | 频道/离线包给的是 **erofs** 层，而 proot 的 `linuxctl` 只认 tar：`archive_test` 把 erofs 判成损坏、`extract_archive` 拿 tar 去解 → 免 root 模式下**永远装不进去** |

改法分两层（App 管"能不能点到"，运行时管"点了真能成"）：

- **App 0.2.12**：`core/ProotSetup.kt` 把"缺什么"与"下一步点哪里"变成可测的纯函数；
  步骤名与动作按模式分开（免 root：`选模式/铺运行时/铺环境/完成`）；免 root 分支三个真按钮；
  诊断页与部署向导按模式给不同的下一步（部署向导在 proot 下先自动铺脚本）。
- **模块 1.0.18**：proot 的 `linuxctl` 支持 erofs 层（`fsck.erofs --extract`）、`provision` 自动用
  base 层当 rootfs、`doctor` 报"部署就绪度"。

| 35 | *(本次)* | **CI 从"四条线路靠时间对齐"改成"一条流水线到底"**（用户给了 KernelSU `build-manager.yml` 的截图："我想拆成 Ksu工作流这种，环境准备，然后多个节点编译，最后合并打包、多重发布（正式发布、pages发布、频道发布），一个工作流到底"）| 新拓扑：`pipeline.yml`（推 main/beta 时**只有它跑**）① prep 环境准备（版本 / 由 `variants.json` 生成编译矩阵 / 打模块 zip）② bundles 内嵌离线包 ③ build **编译矩阵：一个组合一个节点**（`uses: build-apk.yml`）④ gates 门禁（`uses: ci.yml`，`build_apks=false`，与编译并行）⑤ publish 合并 + index.json + 下载页 + gh-pages + Release（`uses: publish.yml`）⑥ channel 频道发布（channel 分支的签名清单 —— 公钥独立验签 —— gh-pages `/channel/`）⑦ report 汇总。`release.yml` 变手动同构兜底；`ci.yml`/`offline-bundle.yml`/`release.yml` 都**不再监听 push**。证据：main/beta 双通道各跑一遍 **12 个节点全 success**（run 5 / run 6），发布物 = 4 个 APK + 4 个 `.bin` + 模块 zip（同一 Release + gh-pages `/stable` `/beta`），且抽查官方 APK 里 `assets/module/`（209611 B）、`assets/offline-bundle.bin`、`libsunsetlinux_pty.so` 都在。落地踩的 4 个坑都有注释留在文件里：**（a）** 可复用工作流里放**工作流级 `concurrency`** → GitHub 拒收，表现是"调用方那个 job 0 秒失败、没起 runner、日志空白"（改成 job 级）；**（b）** `needs: gate` 残留（搬出来后那个 job 不存在）→ 整个文件被拒加载，只报 "Invalid workflow file"；**（c）** 被调用的 `offline-bundle/publish` 声明了 `contents: write`，调用方必须显式允许，否则计划阶段就失败；**（d）** GitHub 宽松相等 `'' == false` 为真 → push 事件下 `inputs.embed_bundles != false` 为假，"默认该内嵌却整条 ② 被跳过"（改成 prep 里归一化成输出）。另加 `tools/ci-shell-gate.sh` + `tools/ci-step-tee.sh`：失败时用 `::error::` 注释带出"红的是哪几条/日志尾部"（步骤日志要仓库权限，注释匿名可读 —— 手机上排查 CI 就靠它） |

### 第 36 条：两个 App、一条路各一个；矩阵能继续长；内置 DSH 与运行时 DSH 解耦

用户三句话定了这轮的三件事：

| 用户 | 落地 |
|---|---|
| "拆" | **两个可共存的 App**（不同包名）：Root 版 `io.github.sunsetrne.sunsetlinux.root`、免 root 版 `…​.proot`；模式由 edition **锁死**，界面不再有"切换模式" |
| "纯 Root、免 Root、然后各种内置感觉不止 4 种吧" | 内置矩阵**数据驱动**：`variants.json` 里 `editions` × `tiers`，现在是 6 个变体（minimal / base / full 三档 × 两个 edition）；**Gradle flavor 与部件表全部从 JSON 读**，加档只改一行 JSON |
| "内置 DSH 的解耦（内置一个版本，运行时一个版本的情况判定，避免更新导致崩了）" | `core/DshPin.kt` 对账（6 种结论、纯函数 + 9 条单测）+「更新」页两个版本号与结论 + **一键回滚到内置版本** +「关于」页一行 |

**补记（0.3.1，真机实测 + 发布路径复核）**：

1. **两个 App 在桌面同名。** 0.3.0 只改了 `main/res` 的 `app_name`，而按 flavor 覆盖的
   `src/<edition>/res/` **根本不存在**（我上一轮以为有）→ 真机上 root 版与免 root 版
   图标都叫 "SunsetLinux"。修法：`resValue("string", "app_name", e.label)`（标签取自
   `variants.json` 的 `editions[].label`）＋ 打开 **AGP 9 默认关闭**的
   `buildFeatures.resValues`（不开是配置阶段直接失败）。`SigningContractTest` 补三条契约。
2. **"该内嵌的离线包真的进了 APK"以前没人验。** `EmbedOfflineBundle` 找不到包只是记一行
   日志就跳过（为了本机开发方便），而 bundles 节点只数 `dist/bundles/*.bin` 够不够、
   编译节点只看 edition 专属资源 → "制品名对不上 ⇒ 一个都没内嵌"完全无声（0.2.6 就这么发的）。
   新增 `tools/ci-assert-embed.sh`：判定由 `variants.json` 的 `embed` 驱动（非空必须有
   `assets/offline-bundle.bin` 且与本地同名 `.bin` 严格等大；为空则不许有），接在
   `build-apk.yml` 与 `ci.yml` 两条路径上。已用 v0.3.0 的**真实发布产物**验过四种情形。
   ⚠️ 别用"小于 1 MiB 就是占位"的启发式：`proot-minimal` 的包只有 989224 字节（0.94 MiB）。
3. **Release 说明第 1 步指向一个不存在的文件**（`sunsetlinux-launcher-debug.apk`，0.2.x 起
   就没有了）。现在推荐包**由 `variants.json` 推**（每个 edition 取 `tier=full`）。
   踩到的坑：label 带空格（"Root 版"），`read` 默认按空白分词会把 APK 名切坏 → `IFS=$'\t'`。
4. **CI 里两处"拆版后写死旧值"**：单测步骤读 `variants.json` 时用相对路径而该步骤
   `working-directory: app`（`FileNotFoundError`）；Gradle 任务名的 `test` 前缀漏了就是
   "Task not found"（看着像 flavor 配错）。

**为什么"锁死模式"比"保留切换"更安全**：Root 版没有 su 时若降级到 proot，它会去操作
**另一个环境**（App 私有的 `files/sunsetlinux`）—— 用户以为在修 root 环境，其实动的是别处。
现在只报原因 + 指向另一个 App（两个包名不同，装哪个就是哪条路）。

**矩阵为什么必须数据驱动**：0.3.0 第一版把 edition × 档位写死在构建脚本里，
"再加一档"要同时改 JSON 与 Gradle，而用户已经明说矩阵不止两档。现在：

```
variants.json ──┬─▶ Gradle：flavor(edition/embed)、每个变体的部件与标签、APK 名
                ├─▶ offline-bundle：打哪几个 .bin（embed 为空的档位不打）
                ├─▶ pipeline：编译矩阵（由 "<edition>-<tier>" 推 Gradle 任务名）
                └─▶ 下载页 / index.json / App 的「本机包」显示
```

**DSH 解耦的判定表**（`DshPin.reconcile`）：

| 内置 | 运行时 | 结论 | 界面 |
|---|---|---|---|
| 无 | 任意 | NONE | 只说运行时版本（本包没内置 DSH） |
| 有 | 无 | RUNTIME_MISSING | "还没装 DSH 层，先去部署" |
| 同 | 同 | SAME | 绿 Pill |
| 旧 | 新 | RUNTIME_NEWER | 黄 Pill + **「回滚到内置版本 X」**（最主要的场景） |
| 新 | 旧 | EMBEDDED_NEWER | 黄 Pill + 回滚按钮 + "或从频道更新到最新" |
| 读不出 | — | UNKNOWN | 如实说"判不了" |

> 有意**不做**的事：不替用户自动选版本、也不因版本不同拒绝启动 —— 那会把"运行时更新"
> 变成"必须重装 APK"，而运行时更新的全部意义就是不必重装。

### 第 35 条：一条流水线到底（形状、代价、以及**下一步要拆的两个 App**）

用户给的参照是 KernelSU 的 `build-manager.yml`：环境准备 → 多节点编译 → 合并 → 多重发布。

```
pipeline.yml（推 main/beta，**只有它跑**）
  ① prep  ── 版本 / 由 tools/offline-bundle/variants.json 生成编译矩阵 / 打模块 zip（制品 module-zip）
  ② bundles ─ 内嵌离线包（uses offline-bundle.yml, attach_release=false）──▶ offline-bundles
  ③ build ── 编译矩阵：一个组合一个节点（uses build-apk.yml；四节点并行）──▶ apk-<组合>
  ④ gates ── 回归门禁 shell/node/单测（uses ci.yml, build_apks=false；与编译并行）
  ⑤ publish ─ 合并产物 → index.json → 下载页 → gh-pages → Release（uses publish.yml）
  ⑥ channel ─ channel 分支的签名清单：公钥独立验签 → gh-pages /channel/
  ⑦ report ── 汇总（编译/门禁/发布任一红就红，注释里带出具体原因）

独立线路（不随每次推送）：layers.yml（手动，arm64 自建 runner）、
layers-release.yml（layers 分支，只收集+上传）、channel.yml（channel 分支/手动，私钥签名）
```

**为什么值得**（每条都是这次实测出来的）：顺序由 `needs` 保证（不再靠"等 6 分钟看 Release 出现"）；
内嵌离线包只打一次；模块 zip 在编译**之前**产出（否则官方 APK 没有 `assets/module/`，见第 34 条补记）；
四个组合并行编译（单组合红不影响其它组合出包）；发布逻辑只有一份（`publish.yml`）。

**下一步（用户已拍板；已在第 36 条完成）**：拆成两个 App

| 决定 | 选择 |
|---|---|
| 包名 | **不同、可共存**：root 版 `io.github.sunsetrne.sunsetlinux.root`、免 root 版 `io.github.sunsetrne.sunsetlinux.proot` |
| 每个 App 出几个包 | **各 2 档：最小 + 完整**（共 4 个 APK；root-完整 = base+runtime+dsh，proot-完整 = base+runtime+proot+dsh） |
| 旧工作流 | 保留但**只手动触发**（已在本轮做完：release/ci/offline-bundle 都拿掉了 push） |

拆分要做的事（按依赖顺序，避免半成品）：
1. `tools/offline-bundle/variants.json` 引入 `edition`（root/proot）+ 四个变体 id（`root-minimal` / `root-full` / `proot-minimal` / `proot-full`），
   `mk-bundle.mjs` 与它的 selftest 跟着改（**它是单一事实源**，pipeline 的矩阵已经读它）；
2. `app/app/build.gradle.kts`：加 `edition` flavor 维度（不同 `applicationId`、不同 `EDITION` BuildConfig），
   proot 版才带 proot 脚本/proroot `.so`，root 版才带「一键刷模块」那套；APK 名 `SunsetLinux-<ver>-<edition>-<档>-debug.apk`；
3. App 里按 `EDITION` **锁死模式**（引导跳过"选模式"、设置页不显示另一个模式、`DshRuntime` 强制本版模式）；
4. pipeline 的矩阵自动跟着 variants.json 走（**不用改 workflow**）——这正是这次把它做成数据驱动的原因。

| 36 | *(本次)* | **拆成两个可共存的 App（各锁一条路）+ 内置矩阵数据驱动（2 edition × 3 档 = 6 个变体）+ 内置 DSH 与运行时 DSH 解耦**（用户："拆"；"纯 Root、免 Root、然后各种内置感觉不止 4 种吧"；"后续还要考虑到内置 DSH 的解耦（内置一个版本，运行时一个版本的情况判定，避免更新导致崩了）"）| ① **两个 App**：`io.github.sunsetrne.sunsetlinux.root` / `…​.proot`（不同包名、可同时安装），模式由 `core/Edition.kt` 按 BuildConfig **锁死**；`DshRuntime.resolveMode` 不再看 `prefs.modeOverride`、Root 版没 su 时**不偷偷降级到 proot**（那会去操作另一个环境），免 root 版**根本不探测 su**；设置页/引导页/部署向导都换成只读的"本版说明"；root 版才带 KernelSU 模块包，免 root 版才带 proot 脚本 + proroot `.so`。② **矩阵数据驱动**：`tools/offline-bundle/variants.json` 加 `editions`/`tiers`/每个变体的 `edition`+`tier`，现在是 `root-minimal/root-base/root-full/proot-minimal/proot-base/proot-full` 六个；**Gradle 的 flavor 与部件表全部从 JSON 读**（加一档只改 JSON 一行，构建脚本与 CI 都不用动），pipeline 的编译矩阵本来就按 `"<edition>-<tier>"` 推任务名，自动变成 6 个节点；`SigningContractTest` 改成防漂移断言（构建脚本必须真的在读 JSON、两个 edition 包名不同且不能退回拆分前的老包名）。③ **DSH 解耦**：新增 `core/DshPin.kt`（`reconcile(内置, 运行时)` → NONE/SAME/运行时更新过/运行时比内置旧/运行时还没装/判不了，**纯函数 + 9 条单测**，`rc.10 > rc.9` 逐段数字比），「更新」页新增卡片给两个版本号 + 一句人话结论 + **「回滚到内置版本 X」**（走 `linuxctl rollback dsh --to <版本>`；`linuxctl update` 不删旧层文件 ⇒ 退路一直在），「关于」页加一行；`LinuxCtl.rollback()` 新增。App **0.3.0** / 模块 **1.0.18**（运行时本轮无改动） |
| 37 | *(本次)* | **真机挂载归因：三处 bug + "这台机为什么起不来"的三条限制；"公共挂载适配"的第一块砖（本机能力探测）**（用户贴出启动失败日志 —— loop 可写层 `I/O error` → 自动降级 dir 模式又 `mount: 'overlay'->…: Invalid argument`；并追问："我在想我这台机子上起步比较困难，那后面适配其他安卓系统岂不是也困难？我在想能不能做公共挂载适配，总不能源码级编译内核刷写？这样的话容易死机，得不偿失。"）| **① 三处 bug**：**(a)** `mount_upper_rw` 挂到 `$ROOTFS_DIR/upper`，而 `architecture.md`/`stop.sh`/`linuxctl`/`doctor` 全按 `$LINUX_HOME/upper` 找 ⇒ overlay 的 `upperdir`（`$UPPER_DIR/upper`）看到的是 f2fs 普通目录、那块 ext4 白挂还被 overlay 遮住（`run/mounts` 因此支持绝对路径项、`stop.sh` 跳过绝对项并给老版本 `rootfs/upper` 兜底）；**(b)** 探测判定表认不出 toybox 的 `Unknown option 'propagation'`（大写 U）与 `mount: bad /etc/fstab` ⇒ 真机 `cmdprobe` 谎报 `unshare_propagation=yes`/`mount_make_rslave=long`，每次启动先调注定失败的写法（统一成 `probe_says_unsupported()`，并写明**不能**用 `-o rprivate / /` 顶替：toybox 会判成 bind 且清 `MS_REC` ⇒ 非递归 bind 压住 `/`，`/data` 之类子挂载被遮）；**(c)** overlay 失败不记入参、`kernel_hint_log` 的 grep 又把 `overlayfs:` 过滤掉 ⇒ 只剩 `Invalid argument`（现在带"实际入参 + 内核原文"并进 `last-error`）。**② 新探测**：`overlay_fs_usable()`（同 fs 两空目录只读试挂，实测该 fs 能不能当层；接在两个 dir 入口**解包之前**，不再白等 1.6 GB）、`probe_loop_io`/`probe_fuse_mount` 进 `cmdprobe`、`PROBE_VERSION=2` 让老缓存失效、`loop_read=no` 跳过 loop 自愈仪式。**③ 真机三条限制（内核原文）**：`overlayfs: filesystem on '/data/sunsetlinux/dirs-upper' not supported`（`ovl_dentry_weird` 命中 f2fs 的 `DCACHE_OP_HASH|COMPARE`，**只读 lowerdir 也不收** ⇒ dir 模式在这台机不可能）；loop 的 I/O 在 `kernel` 域被 SELinux 拒（厂商只读 loop 背 `system_file` 正常；我们的文件连**读**都 FAIL —— `I/O error, dev loopN, sector 0 op 0x0:(READ)`；`chcon system_file` 后读通、写仍被拒 `loop: Write error at byte offset 0`）⇒ 要 loop 只能补一条 `allow kernel <type>:file { read write }`（**用户态文本规则、可撤销、不需要编/刷内核**）；toybox 选项面差异属纯用户态。证据：`runtime/root/selftest.sh` **42 → 58 条**，bash+mksh 双跑 0 失败；`shell-compat-check` / `contract-check`(root+proot) / `cmp-consistency`(16 组) / oneshot / provision / detect-mount / customize / webroot 全绿。App **0.3.2** / 模块 **1.0.19** |

### 第 35 条：一条流水线到底（形状、代价、以及**下一步要拆的两个 App**）

1. **CI 出的 APK 没内嵌模块包。** 第 33 条给 App 加了「一键刷入内置模块」（读
   `assets/module/sunsetlinux-module.zip`），但 `release.yml` 里**模块 zip 是构建 APK 之后**才打的
   → 官方 0.2.12 APK 里没有 `assets/module/`，用户点那个按钮只会看到"这个 APK 没有内嵌模块包"
   （本机自测有、官方包没有 —— 最坏的一类不一致）。修法：`ci.yml` 的 android 关在 Gradle 构建**之前**
   加一步 `bash module/mkmodule.sh`，并在构建后加断言「四个 APK 都必须含
   `assets/module/sunsetlinux-module.zip`」（内嵌不完整就不许发，与离线包那条同规则）。
2. **自测里的 erofs 假层是用 `awk 'BEGIN{printf "%c", 226}'` 写的** —— gawk 在 UTF-8 locale 下把
   226 当码位 U+00E2 编成**两个字节**，假层 magic 写错 → `layer_format=unknown` →
   `layer_has_path` 返回"判不了" → 三条断言在 CI 上红，而本地（mawk + POSIX）全绿。
   改用 `printf '%b' '\342\341\365\340'`（bash/mksh/dash 都写单字节），并把
   `magic/size/dump 路径/finding 摘要`塞进断言消息。
   同时给 CI 加了 `tools/ci-shell-gate.sh`：失败时用 `::error::` 注释带出"红的是哪几条断言"
   （步骤日志要仓库权限才看得到，而 check-run 的 annotations 匿名可读 —— 这次就是靠它拿到的现场）。

### 第 33 条的两个现场（截图与 doctor 输出）

**现场 A**：引导页第 2 步的「我已经装好模块并重启」前面没有任何可点的东西 ——
文案指向的 zip 在开发者机器上。**现场 B**：真机 doctor 报 `4 项 fail, 2 项 warn`，
其中 3 项是假警报（squashfs、profile、pnpm），唯一的真 fail 是
`run/last-error：挂载 upper 失败：mount /data/sunsetlinux/upper.img -t ext4 -o loop,rw,noatime …`。

那条真 fail 的证据链（真机 read-only 核对，**没有任何一步改动设备**）：

| 事实 | 命令/来源 | 结论 |
|---|---|---|
| `upper.img` 的 mtime 停在 03:12（创建时刻） | `adb-shell stat /data/sunsetlinux/upper.img` | **从未成功读写挂载过**（rw 挂载会写超级块 → mtime 会变） |
| `mount -t ext4 -o loop,ro` 成功、`e2fsck -fn` 通过 | doctor §3 | 镜像本身没问题，失败是 **rw 专属路径** |
| `mount: '…/loop49'->'…/rootfs/upper': I/O error` | `run/start.log` | ext4 只在 rw 挂载时写超级块/恢复日志，那条路径上的写失败被内核统一报成 EIO |
| `loop49: […]:2924912 (/data/sunsetlinux/upper.img)` 且环境没在跑 | doctor §1d | 上次失败的**残留 loop**（新 start 现在会先清掉它） |
| `/system/etc/mke2fs.conf` 的 ext4 features 全在内核支持面内（`has_journal,extent,huge_file,dir_nlink,extra_isize,uninit_bg`） | `adb-shell cat /system/etc/mke2fs.conf` | 排除"不支持的 ext4 特性"这一常见解释 |

**没拿到的东西**：`dmesg` 被 DSHA 守卫拦（`/proc/kmsg`、`logcat -b kernel` 都是空的）——
所以内核那句 ext4/jbd2 原文还没看到，新 `start.sh` 会把它抓进 `run/start.log` 与 `last-error`；
下一次真机复现（不管成没成）都能直接读到。**兜底**：四次自愈都失败会自动切 dir 层模式
（那条路完全不碰 loop/upper.img），用户不会再被卡在"环境起不来"。


### 第 12 条到底修了什么 —— 一句话：**真机上根本跑不了首次部署**

起因：上一轮真机 `--run` 只把两个种子下下来了，层始终是空的。上真机核了一遍（root shell），
发现两件事：

1. **`/data/adb/modules/sunsetlinux` 还是 1.0.3，`/data/sunsetlinux/layers/` 是空的**；
   `etc/state.json` 的 `generator` 写着 `linuxctl provision` —— 而 **`linuxctl provision` 只会
   建目录 / upper.img / 写 config，它从来不构建层**（层缺失时它直接 `ok:false`，让你去跑
   `device-provision.sh`）。也就是说：**App 的「首次部署向导（推荐）」在这台机器上必然做不成事** ——
   不是用户操作问题，是这条路缺一环。已写进 §三·0 与 docs/install.md。
2. **设备上既没有 Termux，也没有 `/system/bin/bash`**（两个路径都实测 `ls` 过），而
   `device-provision.sh` 第一件事就是"没有 `BASH_VERSION` → 找 `/system/bin/bash` → 找不到 `exit 1`"。
   → **"在手机上建层"这条路是被自己的守卫堵死的**；上一轮给的那条 Termux 命令在这台机器上敲不通。

修法（三处 bash 专有写法，全部有实测依据）：

| 问题 | 为什么必须改 | 改成 |
|---|---|---|
| `[ -z "$BASH_VERSION" ]` → `exec bash` / `exit 1` | 设备上没有 bash → 直接死 | **删掉守卫**，脚本本身只用 mksh 也支持的东西 |
| `declare -f` 转储函数生成内层构建脚本 | `declare` 是 bash 内建；mksh 下 `declare: inaccessible or not found`，而 `unshare -m` 里跑的正是 mksh | 内层引导脚本改成 **`exec 本文件 --inner-run`**（函数永远来自同一份源码；29 行，不再有引号拼装风险） |
| 裸 `local tb` + `set -u` | **mksh 的 `local x` 是"未设置"，bash 的是"空"** → `[ -n "$tb" ]` 在 mksh 下 `tb: parameter not set`，**每个阶段第一步就死** | 30 处裸 `local` 全部补 `=""`（这类问题只在真跑时暴露，语法闸门看不见） |

### 真机第一跑（2026-09-16 05:36，模块 1.0.5）暴露的第三个坑：**Android 的 mksh 没有 `printf %q`**

用户按 §二·1 跑了（好消息：**bash 那道坎确实过去了** —— 前置检查、建目录树、找种子全过），
然后死在：

```
说明：每个层阶段都会重新解包 base，保证起点干净（共 3 次，约 1-2 分钟）
printf: bad %q@20
```

`@20` 正好是 `printf 'export LINUX_HOME=%q\n'` 里 `%q` 的位置 —— **生成内层引导脚本那一段**。
也就是说：容器/CI 里的 mksh（R59）**有** `%q`，Android 自带的 mksh **没有**。这类"宿主有、设备没有"
的差异，`mksh -n` 和本地跑全部测不出来（本地跑得好好的）。

改法：**不再导出任何变量，改成"原样重放命令行参数"** ——
引导脚本只有 8 行：`exec '/system/bin/sh' '<脚本>' --inner-run <原始参数…>`。
- 不需要 `%q`：参数用 `squote()`（POSIX 单引号转义，sed 实现）逐个别起来；
- 不需要猜"哪些值要传过去"：命令行给的值由参数重放覆盖，环境变量给的值本来就会被继承；
- `--dump-inner`/`--inner-run` 两个只对外层有意义的开关不重放。

回归里加了**含空格 + 单引号的种子目录**来回跑一遍，断言内层拿到的路径与命令行**逐字一致**
（"转义在设备上不存在"这类问题，只有把怪值真的送过去一次才能钉住）。

### 真机第二跑（05:52，模块 1.0.6）：profiles 与 %q 都过了，又栽在 mksh 的空数组上

好消息：**`素材齐全：base.packages / runtime.packages / web-profile / install-web-profile.sh`** 出现了，
`文件清单：find -exec … {} +（批量，快）` 也出现了 —— 上一轮两个坑确实堵住了。
然后 base 层**装完 40 个包、裁剪完、5224 个变更条目都算出来了**，却在最后一步打包时死掉：

```
[05:54:12] mkfs.erofs：/system/bin/mkfs.erofs（compress=none，block=4096）
[05:54:13] ERROR: 构建失败（详见 /data/sunsetlinux/cache/provision.log）
```

日志里**没有**任何"尝试 #N" —— 说明它连第一次调用都没走到。原因（本地用同一个 Android
`/system/bin/sh` 复现，一行就够）：

```sh
set -u; z=(); printf '%s' "${z[@]}"      # z[@]: parameter not set
```

**mksh 里 `zargs=()` 并不生成"空数组"**，它就是未设置；而 `make_erofs` 的默认分支
（`EROFS_COMPRESS=none`）恰好让 `zargs` 保持空，紧接着 `attempts=( "${zargs[@]}" … )` 就炸了。
`set -u` 把它变成致命错误 → `set -e` 让脚本退出 → 一行 stderr（不在日志里）→ 外层只报"构建失败"。

**改法**：`make_erofs` 彻底不用数组 —— 参数组合改成纯函数 `erofs_args_for <n> <comp> <bs>`
（1 完整 → 2 去 xattr → 3 去 --all-root → 4 只留压缩 → 5 什么都不加），`while` 循环逐级降级。
纯函数是**为了能被单测**：`tools/provision-selftest.mjs` 现在把这个函数抽出来，配一个
"第一次故意失败、第二次成功"的桩 `mkfs.erofs`，在 mksh 与 bash 下各跑一遍完整降级链。
（写完做了**变异测试**：把旧数组写法换回去，测试立刻红，报的就是真机那句
`zargs[@]: parameter not set`。）

顺手验了两件"这次不用再猜"的事（在容器里用**同一套 Android 二进制**跑的）：
`/system/bin/mkfs.erofs` 对 5 组参数**全都接受**（139 KB 的镜像产出正常）；
`upper.img` 那条路也有证据 —— 设备上 03:12 的 `linuxctl provision` 就是用它建的
（那条代码在失败时会 `rm -f` 并退出，所以文件存在即证明 `mke2fs` 在真机上可用）。

### 同一屏里那 14 条"文件消失"警告：13 条是误报

真机日志列了 14 条 `少了: …`，肉眼一看全是软链。交叉核对用的是 `[ -e "$BUILD_DIR/$p" ]` ——
**`-e` 会跟着绝对软链走**，而 rootfs 里的软链大多指向 **chroot 内的绝对路径**：

```
usr/bin/awk                 -> /etc/alternatives/awk      （从 chroot 外面解析 → 找不到）
etc/systemd/…/apt-daily.timer -> /lib/systemd/system/…    （同上；而且 /usr/lib/systemd 是故意裁掉的）
```

于是"软链明明还在"却被判成删除（14 条里只有 `etc/apt/sources.list` 是真被脚本挪走的）。
改法：`[ -e … ] || [ -L … ]`（软链本身在就算在），并且 base 层不再用 warn（**base 没有下层，
删除天然生效**），只在 runtime/dsh 层才警告"会残留在 lower 层"。顺带把 `/etc/systemd` 一起裁掉
（systemd 已经不可用，那一堆 `*.wants/*.timer` 本来就是指向已删目录的悬空链）。

### 同一个日志里还有第二个坑：模块**从来没打包 `profiles/`**

```
素材：base.packages=未找到
素材：runtime.packages=未找到
素材：web-profile=未找到
```

`device-provision.sh` 的素材（包清单 / web profile 模板 / 安装脚本）在 `rootfs/profiles/`，
它按 `$SELF_DIR/../profiles/` 找 —— 而 `mkmodule.sh` **只铺了 bin/、lib/、webroot/**，
`profiles/` 从来没进过模块包。后果：base 层退化成"内置最小集"、runtime 层只装 Node+pnpm，
最后在 dsh 阶段因为缺 web-profile 才 `die`（那时已经跑了 20 分钟）。

改法三件：
1. `mkmodule.sh` 打包 `profiles/`（并像 bin/ 一样做**必需项断言**，残包不许流出去）；
2. `preflight` 对素材做 **fail-fast**：缺了当场 die，并写明"重装 ≥1.0.6 的模块 zip"；
3. `sync_scripts` 顺手把 `profiles/` 也落到 `$LINUX_HOME/profiles`，
   这样"已安装副本"（`$LH/bin/device-provision.sh`）也自足；`locate_assets` 的候选路径补上
   `$LH`、`$LH/profiles`、模块根。

顺带加固一处同类风险：文件清单用的 `find -exec … {} +` 在 toybox 上**版本差异**，
真不认 `+` 的话清单会是空的（三层全空 → 再白等半小时）。现在启动时**探测一次**，
不认就降级成逐个 `stat`（慢但正确），并把选择打进日志。

顺带修掉一个**再执行传参**缺陷：内层是重新执行本文件，`--seeds/--force/--skip-*` 必须能从环境继承
（原来 `SEEDS_DIR="$LH/seeds"` 是**无条件赋值**，会把 `--seeds` 静默吞掉）。

回归 `tools/provision-selftest.mjs`（**28 条**，进 CI 的 shell job）：静态断言没有 bash 专有写法；
并在 mksh 与 bash 下**各真跑一次内层**（临时 LINUX_HOME、故意不给种子），断言它走到「准备 base 阶段」、
以可读的缺种子错误收场、**没有任何 shell 级错误**（not found / parameter not set / syntax error）。
`oneshot-setup.sh` 那节"找 bash"也换成**行为探测**：用 `sh` 跑一次 `--help`，旧版会打印「需要 bash」
并 exit 1、新版正常打印用法 —— 体检能直接指出"你装的是旧模块"。

**测试总账**：App **62/0** · WebUI **64/64** · proot 纯函数 **62/65**（本容器里端口探测 3 条受 /proc/net/tcp 限制会红，
基线同样如此，CI 上为 65/65）· proot entry 解析 **18/18**（mksh）· root selftest **19/19**（bash+mksh，本容器）·
版本一致性 **16/16** · 契约 root/proot × bash/mksh 全通过 · oneshot 冒烟 **29/29** · **provision 冒烟 28/28**。

---

## 二、需要你操作的（按优先级）

1. **真机把层建出来**（唯一挡着 root 模式 + 终端/DSH 全链路验收的事）：
   - 先装**模块 1.0.6**（KernelSU 管理器）：`…/stable/sunsetlinux-module-1.0.6.zip`，装完**重启一次**；
     （设备上 05:39 跑的是 **1.0.5** —— 它能过前置检查，但会死在下面第 14 条那两个坑上；**1.0.6 才是真能跑完的**）
   - 然后**在 root 终端里**跑（KernelSU 管理器的终端 / MT 管理器「以 root 执行」/ `adb shell su`）：
     ```bash
     sh /data/adb/modules/sunsetlinux/bin/device-provision.sh --seeds /data/sunsetlinux/seeds
     ```
     ★ **不需要 bash、不需要 Termux**（1.0.6 起是 mksh 原生 + profiles/ 随包；设备上实测 bash/Termux 都没有）。
     种子**已经下好了**（ubuntu-base 29.9 MB + Node 29.8 MB 都在 `/data/sunsetlinux/seeds/`）；
     跑完 `linuxctl start`，再 `linuxctl doctor`。
   - 或者用 `/system/bin/sh $MODDIR/bin/oneshot-setup.sh --run`（体检 → 缺种子就下 → 再跑上面那条）。
   - 失败就把最后 ~40 行、或 `linuxctl doctor` 的输出贴回来。
   - **别用 App 的「首次部署向导」**：它调的是 `linuxctl provision`，那个命令只建目录/upper.img，
     不会构建层（真机上就是这么白跑一趟的）；向导要能真正建层得改 App（见 §三·0）。
2. **arm64 上重打层 → 发布**（"官方频道有货"的前置）：
   ```bash
   sudo bash rootfs/build-layers.sh --out-dir dist --runtime-dir runtime/root --dsh-dist-tag next
   ```
   然后按 `docs/release-ci.md` **§5.3**：把 `dist/*.erofs.gz`（建议带 gz，设备侧优先它）推到 `layers` 分支 →
   CI 上传 Release 并打印 base-url → 本机 `publish-channel --base-url …` → 推 `channel/channel.json` → `/channel/` 上线。
3. **决定内置形态**：见 §三·2（建议照 DSHA 的 `split-runtime-v1` 分包）。

---

## 三、下一步技术清单（方向已定，尚未开工）

0. ~~让 App 的「首次部署向导」真的能建层~~ → **本次已做**（见 §一 第 13 条）：
   `ProvisionPlan` 分诊（`linuxctl provision` 只铺目录/可写层/配置 → 缺层则 root 模式跑
   `device-provision.sh`、proot 模式指向频道），并用 `status` 复核三层是否真的齐了。
   **仍需真机验收**：装 App 0.2.1 → 点向导 → 看它是否走到"设备侧原生构建"并产出三层。

1. **proot 运行时进 `jniLibs` + App 自动解压**（建议先做）
   - 现状：App 里**没有 `assets/`**，doctor 那句"App 应随包携带 proot"一直是空的 → 非 root 模式开箱即用不了；
   - 做法：CI 从 Debian 的 proot arm64 deb 重建 bundle（命令已验证可复现）→ 放进 `jniLibs/arm64-v8a/`
     （**Android 10+ 不能 execve App 私有目录里的文件，但 `nativeLibraryDir` 里的可以**）→ App 首启解压/校验。
2. **内置裁剪版 Ubuntu（分包形态）**——用户明确要，上游 DSHA 就是这么做的：
   - `offline-rootfs.bin`（Ubuntu = base+runtime）与 `dsh-runtime.bin`（dsh）**分包**；
   - **CI 生成资产、不入库**；**冷装两份都解、局部更新只读 dsh 包**（不必每次重下整套）；
   - 参考上游脚本：`scripts/ci-make-offline-bundle.sh`、`tools/prepare-standard-assets.py`、`scripts/offline-provision.sh`。
3. **PTY 终端**（解掉"vim/htop 不可用"）：自研 `forkpty` JNI（约百行，避开 `termux-jni` 的 GPL 传染风险）。
   上游留下的硬性细节：fork/exec 握手登记出生身份、16 KB 页对齐、关闭标签必须核验会话真的退出。
4. **待查**：
   - **App 的 zstd 解压 bug**：设备报过 `Invalid magic prefix: 0: offset=542314549248`（505 GiB 偏移，明显不对），
     它决定内置包用 `.zst` 还是 `.gz`；
   - ~~`device-provision.sh` 的 bash 依赖~~ → **本次已根治**（mksh 原生，模块 1.0.5；
     见 §一 第 12 条）。**仍需真机验收**：装 1.0.5 后用设备自带 `sh` 跑一次，
     确认三层 erofs 真的产出（这是唯一还没被真机证明的一步）。
5. **性能路线（可选）**：自己编译 proot 打开 **seccomp 加速器**（`proot --version` 会打印
   `built-in accelerators: … seccomp_filter = yes/no`；现在的 Debian 5.4.0 是 `no`）。
   **不采用 proroot**（见 §四）。

---

## 四、上游 DSHA 的关键数据（本轮查证，可直接引用）

仓库：<https://github.com/DSH-APP/DSHA>（MIT；**本容器就跑在它的 App 里**，`com.dsh.client`）

| 项 | 数据 |
|---|---|
| APK | 标准版 **176.69 MiB**、兼容版 253.23 MiB（`standard` minSdk 30 / `low` minSdk 23 + Gecko 143）——**两个 flavor 都内置离线 rootfs** |
| 内置形态 | `assets/offline-rootfs.bin`（**不入库，CI 生成**，标记 `offline-rootfs.layout=split-runtime-v1`）+ `dsh-runtime.bin` 分包；冷装两份都解，**局部更新只读 dsh 包**；**不为普通 dsh 更新递增 Ubuntu 基础环境版本** |
| 环境身份 | 版本码 + Ubuntu 基础环境版本 + dsh 版本；基础版本相同则事务替换受管运行时，不同才保护数据重建 |
| proot 打包 | 作为 `jniLibs/arm64-v8a/*.so`（`libproroot*.so`、低版 `libproot_legacy.so`）→ 直接可执行 |
| 终端 | **有 PTY**：`tools/termux-jni/dsha-pty.c`（2.9 KB）+ `PtySession.java` + `PtyTerminalFragment.java` |
| proroot | LD_PRELOAD + 二进制补丁做进程内路径翻译，**零 ptrace 开销**；但**闭源**、单人维护、作者已转向、Termux 拒绝打包；l2s 结构与 proot 不兼容（它 21 处脚本依赖）；**上游自己默认关闭** |
| 踩坑 | `--link2symlink` 下要 `NARB_DISABLE_NATIVE_CACHE=1`（默认 link+unlink 缓存首次悬空）；DNS 默认 `auto`（`EAI_AGAIN`/`EAI_FAIL` 才重试一次 IPv4，**不重放 HTTP**）；随包 CA/npm 入口不依赖 apt |
| 其他 | 383 项单测（382 过/1 跳过）、Lint 无错；发布走 **GitHub Releases** + `.apk.sha256`；发布说明明确"沿用原发布签名" |

---

## 五、删掉对话后，恢复上下文的最短路径

```bash
# 1) 看这两份就够：项目总账 + 本次落点
less docs/STATUS.md docs/HANDOFF.md

# 2) 最近做了什么（本轮 12 个提交）
git log --oneline 35ac91d..HEAD

# 3) 本地全量门禁（秒级～分钟级）
node tools/shell-compat-check.mjs --verbose
node module/webroot/selftest.mjs
node tools/oneshot-selftest.mjs
node tools/provision-selftest.mjs
node tools/cmp-consistency.mjs
bash runtime/proot/selftest-funcs.sh && mksh runtime/proot/selftest-funcs.sh
bash runtime/root/selftest.sh && mksh runtime/root/selftest.sh
cd app && ./gradlew :app:assembleDebug :app:testDebugUnitTest

# 4) 当前发布版本（无需 token）
curl -s https://sunsetrne.github.io/SunsetLinux/stable/index.json
```

---

## 六、本轮踩过、别再犯的坑

- **"能解析"≠"函数真的存在"**：一次删空行的编辑把 `bad() {}` 并进注释，`mksh -n` 全绿，
  真机只多一行 `bad: inaccessible or not found`，**而且阻断计数永远是 0**（该拦的不拦，用户是唯一发现者）。
  → 规矩：**任何体检/闸门脚本都要有"行为级冒烟"**（`tools/oneshot-selftest.mjs`、
  `tools/provision-selftest.mjs` 都是这么来的）。
- **mksh 的 `x=()` 不是"空数组"**（真机第二次失败的原因）：`set -u` 下 `"${x[@]}"` 直接
  `parameter not set`。设备侧脚本里**别用数组**；要用就用"取第 N 个"的纯函数，或者确认它一定非空
  （`+=` 追加过、或从别处赋了非空值）。判据同前：本地 mksh 和 Android mksh 在这一点上行为一致，
  所以**能被单测覆盖** —— 前提是那段代码真的被跑到（当时内层在 chroot_mounts 就死了，够不着 make_erofs）。
- **`[ -e ]` 会跟着绝对软链走**：判断 rootfs 里的文件在不在，必须 `[ -e ] || [ -L ]`，
  否则每个指向 chroot 内绝对路径的软链都会被误判成"已删除"。
- **mksh 的 `local x` 是"未设置"，bash 的是"空"**：配 `set -u` 就是"bash 全绿、mksh 第一步就死"
  （`tb: parameter not set`）。**别写裸 `local`**，一律 `local x=""`。
- **`declare -f` / `declare -a` 是 bash 专有**：设备上（mksh）报 `declare: inaccessible or not found`。
  要"把函数交给另一个 shell"就别转储函数 —— **重新执行同一个文件**（`--inner-run` 那种）。
- **别再假设设备上有 bash 或 Termux**：这台机器两个都实测没有。设备侧脚本一律按
  `/system/bin/sh`（mksh）写，`mksh -n` 只是最低门槛，**必须真跑一次**（`tools/provision-selftest.mjs` 就是干这个的）。
- **只看 `mksh -n` 不够**：`/dev/tcp`、`local x` + `set -u` 都属于"语法过、运行时废"。
- **别信"本地 mksh 能跑"**：容器/CI 的 mksh（R59）**有** `printf %q`，**Android 的没有**
  （真机 `printf: bad %q@20`）。凡是"宿主有、设备没有"的能力，一律**别用**，或先探测再降级
  （`find -exec … {} +` 就是这么处理的）。判据永远是真机日志，不是本地绿灯。
- **模块包缺素材 = 20 分钟白等**：`device-provision.sh` 要的 `profiles/` 是模块的一部分，
  漏打包时不要"警告一下继续跑" —— 要么打进包（`mkmodule.sh` 的必需项断言），
  要么在 **preflight 就 die**。真机实测：1.0.5 缺 profiles，用户跑到 dsh 阶段才失败。
- **"已安装副本"要自足**：脚本被拷到 `$LINUX_HOME/bin` 后再跑一次时，`$SELF_DIR/..` 已经不是
  模块根了 —— 素材要么一起同步过去，要么把模块根也列进候选路径（本次两条都做了）。
- **`linuxctl provision` 不会构建层**：它只建目录 / upper.img / 写 config，层要么来自频道、
  要么来自 `device-provision.sh`。App 的「首次部署向导」现在走的正是它 → 见 §三·0。
- **gh-pages 是 git 分支**：单文件 100 MB、站点 1 GB 两道硬限。内嵌离线包后的 APK 会到 114 MB，
  推上去直接失败 —— **大产物一律走 Release 资产**（APK / 层 / 离线包），gh-pages 只放 index.json、
  模块 zip 和下载页。判据永远是"这文件多大、以后还要不要回滚"。
- **`${{ github.repository }}` 是 `owner/repo`**：拼进 `https://<owner>.github.io/<repo>` 时会多一段 →
  404。要 `owner` 转小写 + 只取仓库名（channel.yml 早就是这么写的，这次新工作流忘了）。
- **upload-artifact 保留目录结构**：上传 `apk/*/debug/*.apk`，解出来是 `apk-artifact/<组合>/debug/*.apk`；
  平铺通配 `apk-artifact/*.apk` 一个都匹配不到（而制品其实有 46 MB）→ 用 `find` 递归收。
- **`dist/` 是 gitignore 的** → CI 拿不到本地产物；发布步骤里依赖本地构建物的，必须显式说"本次没有"，
  **不许静默跳过**（`release.yml` 的 job summary 已这么做）。
- **编辑时别在空行边界动手**：删空行会把下一行粘上来（本轮那个 bug 就是这么来的）。
- **别用正则批量改代码**：用脚本给裸 `local` 补 `=""` 时，朴素按空格分词会把
  `local files="a b c"` 切成 `local files="a b="" c"` —— 引号内的空格必须按 token 处理；
  改完一定要 `git diff` 逐行看一遍（本次差点把这个残次品提交上去）。
- **GitHub Release 资产地址会 302**：任何"能不能下载"的预检都要 `curl -L`（`channel.yml` 已修）。
- **Releases 页现在有两类 tag，别混**：`v<App 版本>`（例 `v0.2.4`）= **产品发布**（APK / 模块 zip / index.json，由 `release.yml` 在 stable 上幂等创建或刷新）；`layers-<日期>` = **层文件的纯存储**（由 `layers-release.yml` 收集）。下载页仍在 gh-pages（`/stable/`、`/beta/`），两边指向同一份产物。
- **签名必须固定**：授权按【包名+签名】记（KernelSU），换 key 会让已授的 root 全失效。
- **`--link2symlink` 只有 proot ≥5.3 有**，喂给老 proot 会 fatal；启用后注意
  `NARB_DISABLE_NATIVE_CACHE=1` 那类缓存悬空问题（上游踩过）。

---

## 本轮收束（2026-09-16 晚）——现状、未验证项、下一步

> 这一段是**交接用**的：下一次对话从这里开始读，不用翻上面的表。

### 已经发布并验证过的

| 交付 | 版本 | 验证方式 |
|---|---|---|
| 免 root 运行时换成 **proroot 主 / proot 降级** | App 0.2.8+ | 发布 APK 内 5 个 `.so` sha256 与上游一致；合规闸门 11/0（不得独立分发） |
| **终端接原生 PTY** | App 0.2.9 | APK 内含 `libsunsetlinux_pty.so`；VT 模拟器 12 条单测；CI 断言每个 APK 都带它 |
| 模块安装文案重排 + 截断句子修复 | 模块 1.0.12 | `tools/customize-selftest.mjs`（含"ui_print 参数只能有一对引号"静态判据） |
| `start.sh` 被 `set -e` 带走（层永远挂不上） | 模块 1.0.13 | 本地 1:1 复现（cmdprobe 0 字节 → 六行齐全）；selftest 断言"探测必须跑完" |
| 挂载碰撞检查 doctor §1d | 模块 1.0.14 | selftest 断言 doctor 给出 `mount_conflict` 结论 |
| **无 loop 层模式（dir）** + doctor §1e | App 0.2.10 / 模块 1.0.16 | selftest 36/0（bash+mksh）：解析优先级、解包幂等/换层只重解/解包器缺失明确失败、`linuxctl start --layer-mode` 透传 |

### 还没在真机上验证的一件事（**唯一的关键缺口**）

root 模式的**挂载路径从未在真机上成功跑完过一次**。已修掉两个"shell 时代"的根因：

1. 模块自启用裸 `sh` → busybox ash 在 `${BASH_SOURCE[0]:-$0}` 上语法错（1.0.11 修，改成 `$SH_BIN`/直接执行）；
2. **探测命令（设计成失败）被 `set -e` 当致命错误** → `start.sh` 在第一步就退出（1.0.13 修，探测段 `set +e`）。

下次真机验证要看的三个地方（按顺序）：

```bash
cat /data/sunsetlinux/run/cmdprobe        # 应有多行 + probed=  ← 证明探测跑完
cat /data/sunsetlinux/run/layer-mode      # loop 或 dir         ← 本次用的哪条路径
tail -40 /data/sunsetlinux/run/linux.log  # 现在会有内容了：卡在 loop/erofs/overlay 哪一步
/data/sunsetlinux/bin/linuxctl doctor     # §1b 探测结果、§1d 冲突结论、§1e 层模式
```

- 若卡在 `losetup`/`mount -t erofs`/`upper.img`：**切 dir 模式**
  （App「设置 → 层模式 → dir」，或 `linuxctl start --layer-mode dir`，或 `etc/config.json` 的
  `"layer_mode":"dir"`）——它完全不碰 loop 与镜像挂载，只做"解包 + 目录 overlay + bind + chroot"。
- 若卡在 `mount -t overlay`：说明内核/权限层面的 overlay 问题，把 linux.log 那几行发回来（这会是新问题）。

### 其他已知未完成项（都不阻塞启动）

- 设备上自我构建的层缺 **DSH web profile** 与 **pnpm**（doctor §7）→ 用 App「更新」页从**频道**装
  dsh/runtime 层即可补齐，顺带升到 `0.1.5-rc.2`。
- `doctor §6 proot 未找到`：root 模式下 proot bundle 不落地是正常的；proroot 在 APK 的
  `nativeLibraryDir` 里（App 会透传），纯 root shell 看不到 —— 文案已说明，未再改。
- `linuxctl start --layer-mode` 目前**不转发其它未知参数**（只认 `--layer-mode`，其余忽略并告警）。
- 频道清单里的层仍按 erofs 分发（dir 模式在设备侧自己解包，无需改清单）。

---

## 第 37 轮（2026-09-17）：真机挂载的三条限制、三处修复、以及"不需要编内核"的适配方向

**起点**：真机启动日志两个失败 —— loop 可写层 `mount: '/dev/block/loop49'->'…/rootfs/upper': I/O error`，
自动降级 dir 模式后 `mount: 'overlay'->'/data/sunsetlinux/rootfs': Invalid argument`。用户的追问见上表第 37 条。

**这一轮的取舍**：设备侧我（DSHA）**不能挂载**（策略禁止）、`dmesg`/`lsattr`/`dd` 也被白名单拦，
旧那次失败的内核原文还随重启丢了（pstore 空、`console=ttynull`）⇒ 只能"我在代码/内核源码侧归因 +
给用户 4 个一次性诊断脚本"，最终拿到内核原文。

**结论（三条限制，全部有原文）**：
1. f2fs（带 casefold）被 overlayfs 一律拒绝 ⇒ **dir 模式在这台机上不可能**，与我们的选项无关；
2. loop 的 I/O 在 `kernel` 域被 SELinux 拒（厂商只读 loop 背 `system_file` 正常）⇒ **loop 快路需要一条
   可选的 `kernel` 域规则**，不是内核缺功能；
3. toybox 的选项面/报错文本与 util-linux 不同 ⇒ 纯用户态适配（探测 + 多写法）。

**下一轮该做什么（方向已定）**：
- **策略层**：探针（`overlay_fs_usable` / `loop_read` / `fuse_mount`，前两个已进 `cmdprobe`）+ 选路结果落
  `etc/mount-plan.json` + doctor 显示"本机可用路线 + 其余被否的原因"；
- **loop 快路（可选）**：若用户愿意加一条 sepolicy 规则 ⇒ 规则写入模块 `sepolicy.rule`，
  **先用宽规则证明概念，再收紧成专用 type**；探针会自动从 `loop_read=no` 变 `yes`（`PROBE_VERSION` 已加）；
- **跨 ROM 兜底**：用户态 union（`/dev/fuse` 在；挂载权限待实测）→ 无 union（合并目录 + bind 出可写点）；
  proot 版本来就是无依赖兜底；
- **别忘**：`--layer-mode loop` 会尊重显式选择（不因探针跳过）；`linuxctl status` 是 `contract-check` 的入参
  （我这一轮先用错了子命令，门禁要用 `status`）。

**诊断脚本（开发工作区，未入库）**：`真机诊断-挂载.sh`（现场+三种 overlay 写法）、`真机诊断2-loop与overlay.sh`
（loop 在 f2fs/tmpfs、关 DIO 对照）、`真机诊断3-loop归因与fuse.sh`（厂商文件/标签/API 三种对照 + fuse）、
`真机诊断4-判定表.sh`（只打结论的判定表）。要入库的话，建议合并成一个 `tools/device-mount-diag.sh`。

## 第 37 轮补记（2026-09-17）：一次**整机卡死**事故与修法

**现象**：跑 A 路线验证（start）后手机卡死，只能重启。

**证据**（`/data/system/dropbox` + `/data/tombstones`）：
- `01:44:08 /system/bin/ndc resolver getresolvers`（uid 0）**SIGABRT**：`Binder driver '/dev/binder' could not be opened. Error: 2` —— 就是 `start.sh` 的 `gather_android_facts` 在内层反复拉起的那几次（日志那句"完全取不到 Android DNS"）；
- `01:44` 5 个 tombstone_08..12 → `01:45 system_app_anr` → `01:47 system_server_crash` + tombstone_13 ⇒ 卡死。

**根因**：`gather_android_facts` 的注释写着"在宿主侧先取好是唯一可靠做法"，但**调用点在内层**（`build_mount_tree` 里）。内层处在私有 mount/UTS ns，`ndc`/`getprop` 依赖 binder 与 `/dev/__properties__`，在那里拿不到 → abort → 连锁把 `system_server` 带崩。

**修法（模块 1.0.20 / App 0.3.3）**：
- 抓取只在**父进程**（`main()` 里、spawn unshare 之前）做；
- 内层改调 `install_android_facts()`：只把 `$LINUX_HOME/etc/android-*.txt` **拷贝**到 `rootfs/etc/`（`entry.sh` 读的就是 `/etc/android-resolv.txt` —— 顺带把这条一直没通的管道接上了）；
- 回归：`selftest.sh` 新增两条文本断言（gather 只在 `main()` 调用一次；内层只用 install）⇒ **62/0（bash + mksh）**。

**本轮踩过、别再犯的坑（合并清单）**：
1. **内层（私有 ns）严禁调用安卓系统工具**（`ndc`/`getprop`/`ip route`…）——会 abort 并带崩 `system_server`；要宿主事实就让**父进程**或 App 采集、落文件。
2. `ksud sepolicy check/patch` 的 tclass **不带冒号**：`allow kernel system_data_file file write;`。
3. 改设备上的脚本：**先 `cp`（继承权限位）再原地重写**（`awk > 新文件` + `mv` 会丢 +x → `unshare: exec … Permission denied`）。
4. 改文件的检测别只看单行（拆开后 `local dst=` 会跑到下一行）。
5. `chroot` 前要自己给 `PATH`：否则 `#!/usr/bin/env bash` 用的是安卓 PATH → `env: 'bash': No such file or directory`（rc=127）。
6. 同一条 `local` 里不能自引用：`local a=1 b=$a` → `a: parameter not set`（mksh/bash 一样）。
7. **A 路线的技术验证已成功**（写权限放开后：upper 挂上、三层 erofs 挂上、overlay 成型、`entry.sh` 跑起来 rc=78）—— 剩下的是"环境内不许碰安卓系统服务"。

## 第 37 轮再补（2026-09-17 02:30）：0.3.3 真机验证通过 + 最后一关

- **0.3.3 在真机上确认安全**：开机自启那次 start 里，DNS/时区是在**父进程**（"启动守护进程"之前）
  采集的（见 `run/linux.log`），内层不再出现 `ndc`；整机**没有再次卡死**，只有"loop 写不进 + dir 不可用"两句人话。
- **最后一关 = `supervise.sh` 的前置检查退 `exit 78`**（4 处：缺 `/usr/local/bin/dsh`、缺 `node`、
  缺 `$DSH_HOME/profiles/web/package.json`、profile bundle 解析不了）——原因会写进 **`run/last-error`**。
  与 `docs/HANDOFF.md` 早先记的一致：**设备上自建的层缺 DSH web profile 与 pnpm**（doctor §7），
  需要从频道/Release 装官方 `dsh`/`runtime` 层。
- 顺带修：`stop.sh` 引用未定义的 `UPPER_IMG`（我的登记表改动暴露的）、`gather_android_facts` 里
  `ndc` 改为**默认不用**（`SUNSETLINUX_ALLOW_NDC=1` 才试）。

### 第 37 轮三补（2026-09-17 11:20）：规则改成**开机自动应用**

用户反馈"不太会操作，只能这样"——那就别让他每次手打 `ksud sepolicy patch`：
`module/service.sh` 在调用 `linuxctl start` **之前**自动 `ksud sepolicy apply` 那条唯一的规则
（`allow kernel system_data_file file write;`，写进 `/data/sunsetlinux/etc/sepolicy-loop.rule`），
开关文件 `/data/sunsetlinux/etc/sepolicy-loop.disabled`（存在则不打），结果写进 `run/service.log`。
规则是运行时会话级的、重启失效，所以必须每次开机打 —— 这就是放在 service.sh 的原因。

真机状态（11:08 日志）：0.3.3 已确认**不再卡死**（DNS 在父进程采集）；仍倒在 `mount upper` 的
I/O error 上 —— 因为重启后规则失效；本次改动正是为了消除这个手动步骤。剩下的一关是
`supervise.sh` 的 `exit 78`（缺 DSH web profile），需要装官方 dsh/runtime 层（App「更新」页最省事）。

## 本轮收束（2026-09-17，换对话框前）——真机三条限制 + 一次卡死事故的收尾

**代码/发布**：`main` = `beta` = `22df066`；Release **v0.3.5**（14 个资产，含 `sunsetlinux-module-1.0.22.zip`
与 `SunsetLinux-0.3.5-root-full-debug.apk`），模块 **1.0.22**、App **0.3.5（versionCode 20）**。
门禁：`runtime/root/selftest.sh` **62/0（bash+mksh）**；shell-compat / contract(root+proot) / cmp-consistency / oneshot / provision / detect-mount / customize / webroot 全绿。

**真机状态（2026-09-17 12:39 日志，已逐行核对）**：
- `mount upper <- … -> /data/sunsetlinux/upper` **成功** —— 模块 1.0.22 的 `service.sh` 在调用 `linuxctl start`
  前自动 `ksud sepolicy apply` 生效了（**用户不用再手打命令**）。这条规则只放开 kernel 域对
  `system_data_file` 的**写**（读本来就允许）；
- 三层 erofs 用 loop 挂上、overlay 参数正确（upper=ext4、lower=erofs）、`install_android_facts` 拷入
  3 个 Android 事实文件、挂载树 13 项、`entry.sh` 被拉起 ⇒ **挂载链全通**；
- **唯一剩余**：`entry.sh` 退出 **78** = `supervise.sh` 的前置检查（缺 `/usr/local/bin/dsh` / `node` /
  **`$DSH_HOME/profiles/web/package.json`**）⇒ **层内容问题**：本机的层是**设备上自建**的，缺 DSH web
  profile（doctor §7 早已记录）。

**下一轮第一件事（用户侧，已交代）**：装官方 **base / runtime / dsh** 三层（App「更新」页官方频道最省事，
或 `下一步-真机部署.md` 的 curl + `linuxctl update`）→ `linuxctl start`（规则、挂载、诊断都已自动）。
判据：`run/ready` 出现、`linuxctl status` 的 `"state":"running"`、DSH 在 `http://127.0.0.1:3080`；
若仍 78，**`run/last-error` 就是 `supervise.sh` 写下的原文原因**。

**本轮事故与坑（合并清单，别再犯）**：
1. **内层（私有 mount/UTS ns）严禁调用安卓系统工具**（`ndc`/`getprop`）—— 2026-09-17 造成**整机卡死**：
   `ndc` SIGABRT（打不开 `/dev/binder`）→ 5 个 tombstone + `system_app_anr` + `system_server_crash`。
   抓取只能在**父进程**；内层用 `install_android_facts` 只拷贝。回归：selftest 两条文本断言锁死。
2. `ksud sepolicy check/patch/apply` 的规则语法：**class 不带冒号** —— `allow kernel system_data_file file write;`
   （带冒号的 `.te` 写法全都 parse 失败）；且它是**运行时会话级**的（重启失效）⇒ 必须由模块每次开机打
   （开关：`/data/sunsetlinux/etc/sepolicy-loop.disabled`）。
3. 改设备上的脚本要**先 `cp` 保权限位**再原地重写（`awk > 新文件` + `mv` 会丢 +x → `exec: Permission denied`）。
4. `chroot` 前必须自己给 `PATH`（否则 `#!/usr/bin/env bash` 用安卓 PATH → `env: 'bash': No such file`，rc=127）。
5. 同一条 `local` 里不能自引用（`local a=1 b=$a` → `parameter not set`，mksh/bash 一样）。
6. overlayfs 拒绝 f2fs（`ovl_dentry_weird` 命中 `DCACHE_OP_HASH/COMPARE`）⇒ **dir 模式在这台机上不可能**；
   这是内核规则，不是策略能改的。
7. 设备侧**只读**核对优先：`run/{cmdprobe,start.log,linux.log,last-error,service.log}`、`/proc/mounts`、
   `/data/system/dropbox`、`/data/tombstones` —— 本轮所有结论都出自这些。
8. 诊断脚本（开发工作区，未入库）：`真机诊断-挂载.sh`、`真机诊断2-loop与overlay.sh`、
   `真机诊断3-loop归因与fuse.sh`、`真机诊断4-判定表.sh`。

---

## 第 38 轮（2026-09-17）：三条决策落地 —— 模块两变体 / 免 root 切割 / 编译只在 CI

**这一轮补的正是"root 模块只缺 DSH 这一块"**（第 37 轮真机结论：挂载链全通，倒在
`supervise.sh exit 78` = 设备自建层缺 DSH 本体与 web profile）。

### 决策与设计（全文见 `docs/module-variants.md`）

| # | 决策 | 一句话 |
|---|---|---|
| 1 | 免 root 完全切割 | 免 root 版与 KernelSU 模块**彻底无关**；打开即自动启用内置 Ubuntu + 终端 + DSH |
| 2 | root 模块拆两变体 | `full`（**默认名**，自带 DSH：装完重启即可用、可更新、可一键回滚到内置版）/ `bare`（不含 DSH，`linuxctl dsh install` **一条指令**从内置官方频道装） |
| 3 | 编译只在 CI | 官方产物一律 GitHub Actions 编译；**发布只由 `main` 触发**；`beta`/`channel`/`layers`/`gh-pages` 只存内容 |

### 接口（改代码前先看这张表）

- 模块 zip：`full` → `sunsetlinux-module-<ver>.zip`（含 `dsh/dsh-<版本>.erofs.gz` + `dsh/manifest.json`）；
  `bare` → `sunsetlinux-module-<ver>-bare.zip`。**默认名只给 full**。
- `module.prop` 新增 `variant=full|bare`（管理器忽略未知键；描述里也写明）。
- 内置版本事实源：`$LINUX_HOME/etc/dsh-builtin.json`（`linuxctl dsh builtin` 写，回滚/doctor 读）。
- 新命令：`linuxctl dsh {info|builtin|install}`；`linuxctl update dsh`（无文件）= `dsh install`；
  `linuxctl rollback dsh`（不带 `--to`）优先回内置版本。
- `update.sh` 新子命令 `install <base|runtime|dsh>`：自己补内置官方频道 → 验签 → 解析（TSV）→ 下载 → 校验 → 安装。
- `index.json` 新增 `modules`（`default` + `variants`）与 `built_in_ci`/`builder`。

### 这一轮的坑（都已写进代码注释，别再犯）

1. **"静默冒充"必须被硬拦**：`full` 不给 `--dsh-layer`、`bare` 给了 `--dsh-layer` —— 两者都
   `die`。否则会出现"不带 DSH 的包顶着默认名发出去"，用户装完发现起不来且**没有任何一处报错**。
2. **默认名与字典序**：`-bare.zip` 在字典序里排在 `.zip` **前面**，所以"取第一个 .zip"会拿到
   bare —— App 的模块更新改读 `index.json` 的 `modules.default`，下载页也按它标注"默认"。
3. **APK 内嵌的是 bare**：base/full 档 APK 的离线包已有 dsh 层，模块再内嵌 full = 同样 48 MB 塞两遍；
   `ci.yml` 与 `build-apk.yml` 都读 `assets/module/module.json` 断言 `"variant":"bare"`。
4. **设备侧不要再建 dsh 层**：`device-provision.sh` 认 `etc/dsh-builtin.json` 并跳过 dsh 构建
   （设备侧建出来的那份正是 `exit 78` 的成因）；`state.json` 的 `dsh_version` 改用 `layer_version dsh`
   取值（否则内置场景下写 null，对账失效）。
5. **判定要认带版本名的层**：`layers/dsh-<版本>.erofs` 是常态，`customize.sh` 的"有没有层"
   判定必须同时认 `dsh.erofs` 与 `dsh-*.erofs`（老写法会把已装好的内置层当成没有）。
6. **CLI-only 也要能用**：模块可以脱离 App 使用，所以"内置官方频道"（URL+公钥）也写进了
   `runtime/root/update.sh`；`ensure_official_channel` 是**合并**（App 侧同名内置项以代码定义为准，不会重复）。
7. **`dsh install` 的失败要能分辨**：resolve 现在输出 `RESOLVE<TAB>NONE<TAB>原因`
   （全频道验签失败 vs 已是最新是两件事），`linuxctl dsh install` 的退出码也跟着 `ok` 走。

### 回归与门禁

模块 **1.0.29**（`STATUS.md` §3.10.28 doctor §3 假警报 + §3.10.29 **32 位算术** + §3.10.30 内置 DSH 未被启用
+ §3.10.31 环境内 **dsh.url 写错目录** + §3.10.32 **环境/DSH 拆开启动**（`--no-dsh` · `dsh start|stop` · 互斥判定 · 终端可用）；
回归：root selftest 81/0 × bash+mksh（本机 proot 沙箱 +1 skip，CI/真机 88/0）、模块变体 36/0、provision 87/0）；
App 单测 **229/0 × 2 个变体**（`LC_ALL=C.utf8 LANG=C.utf8 ./gradlew --no-daemon --offline `
`:app:testProotFullDebugUnitTest` / `:app:testRootFullDebugUnitTest`；POSIX locale 下中文测试名会让
kotlinc 报 `InvalidPathException`，那是环境问题、CI 已设 C.UTF-8）；App **0.3.8**（启动区操作置顶 + 一键/分步二选一；终端紧凑化与身份行）；
`node tools/module-variant-selftest.mjs` **31/0**（真打包、真落地、真跑一个临时 Ed25519 频道，
含 4 个负例）；`tools/customize-selftest.mjs` 16/0；root selftest 62/0（bash+mksh）；
provision 83/0；oneshot 29/0；shell-compat / contract / cmp-consistency / webroot 64/0 全绿。

### 下一轮第一件事（真机）

1. 装 **`full`** 模块（`sunsetlinux-module-1.0.23.zip`）→ 重启 →
   看 `layers/dsh-<版本>.erofs` 与 `etc/dsh-builtin.json` 是否就位（`run/provision.log` 里会有
   "DSH 已由模块内置提供 → 跳过设备侧 dsh 构建"）；
2. `linuxctl doctor` 应能看到内置 DSH 版本；`linuxctl dsh install` 从官方频道更新；
   `linuxctl rollback dsh` 回内置；
3. 免 root：装 `proot-full`，打开 App 应**自动**铺好并落到终端（全流程不出现"模块"两个字）。

---

## 第 41 轮（2026-09-17）：把第 40 轮留下的两条真机遗留推进到可验收状态

**结论先行**：第 40 轮交接里第 2 条的**修法本身是错的**（照它改会原样失败），已按设备实测重写并加闸门；
第 1 条（频道验签）**发布侧由我独立复核通过**、App 侧嫌疑排除，剩下的必须靠现场信息，因此这轮把
"拒绝理由"改成**自带证据**，并给此前零覆盖的验签代码补上**真实发布快照**的行为级回归。

### 一、`nsenter --wd=`：不是"换个写法"，是这台设备的 nsenter 没有 cwd 选项

用容器里**同一套 Android 二进制**（`/system/bin/nsenter` → Toybox 0.8.12-android）逐条实测：

| 写法 | 实测结果 |
|---|---|
| `--wd=/data/…` | `nsenter: Unknown option 'wd=/data/…'` ← 真机原话 |
| `--wd /data/…` | `nsenter: Unknown option 'wd'` |
| `-w /data/…` | `nsenter: Unknown option 'w'` |
| `--mount=/proc/1/ns/mnt` | ✅ 认（`-m=/path` 也认） |
| `--mount /proc/1/ns/mnt` | `need -t or =filename`（**空格分隔不认**） |
| `chroot <rootfs> /usr/bin/env -i /bin/pwd`（从 `/tmp` 起） | 打印 `/` ⇒ **chroot 自己会 chdir 进新根** |

⇒ 修法：`linuxctl.sh` 四处 `--wd="$ROOTFS_DIR"` **全部删掉**（cwd 由 chroot 保证，本来就是加 `--wd` 的目的），
原地留实测结论。**闸门两层**：① `tools/shell-compat-check.mjs` 新增「nsenter 选项面」逐选项比对
（跨续行合并；有 `-t PID` 才允许裸 `-m/-u`），并带 `nsenterSelfCheck()` 自检 —— 因为第一版扫描器
在真实代码上**一直空转**（`"$NSENTER"` 的收尾引号把 token 解析卡死），变异测试全绿，是自检把它揪出来的；
② `runtime/root/selftest.sh` 加一条**行为级**对照（拿设备真实二进制验我们用的写法，判据只看选项解析）。

### 二、顺手：一条**在真机上必然假红**的门禁（`e2fsck -p`）

`工具/selftest.sh` 的"可写层自愈顺序"在设备上永远红：`/system/bin/e2fsck` 真实存在且排在最前，
自测放的桩轮不到，真 e2fsck 去跑 0 字节 `upper.img`（rc=8）。基线复核：**HEAD = 81 通过 / 1 失败**。
修法：`e2fsck_preen` 候选表首位加覆盖点 `$SUNSETLINUX_E2FSCK`（留空行为不变）。修后 **83/0（bash + mksh）**。

### 三、频道「签名校验失败」：复核 + 可自证（仍未定位到具体频道，需现场）

- 线上 `/channel/channel.json` + `.sig` 与 **gh-pages 分支**副本逐字节一致（`f28669bb…` / `a550ae0d…`），
  用 App 内嵌公钥 + App 的算法（base64 → SPKI → Ed25519）**验签通过**（本机 curl 走的就是这台手机的网络）；
- `Update.kt` 最后一次改动 **0.2.4**、`Net.kt` 未动 ⇒「搬 UI 动坏了公钥/签名路径」**排除**；
- 新增 `SignatureVerifier.diagnose()`（指纹 / 清单字节数 + sha256 / 签名字节数进拒绝理由）+
  `ChannelSignatureTest`（真实发布快照 `testdata/channel/` 行为级回归，此前零覆盖）。
  **测试当场抓到我自己写错的指纹定义**：文档/发布工具（`tools/channel/common.mjs:285`）用的是
  `sha256(公钥)` 前 8 字节，我第一版写成"公钥前 8 字节"（会算成 `ed25519:61:7a:00:73:…`）——
  这种"看起来像证据、其实指错方向"的输出比不给证据更糟。

### 四、顺带修掉发布路径的真坑（发布 0.3.10 时当场撞上）

第一次流水线 ①~④ 全绿、卡在 ⑤「发布到 GitHub Releases」。日志原文：`新建 Release v0.3.10` →（17 分钟后）
`HTTP 422 … name=SunsetLinux-0.3.10-proot-base.bin` → `ReleaseAsset.name already exists` ——
即 `gh release create` 一次带 15 个资产时**自己重传了同一个**（首次已在服务端成功、客户端超时后重试）⇒ 撞名。
文件没问题（同一次 `index.json` 里它 69,205,422 B、sha256 正常）。三个问题都修了：
① 改成"先建草稿条目 → **逐个上传 + 4 次退避重试** → 仍不吞错"；
② gh 传资产是"先建草稿、最后才发布"，而重跑路径从不 `--draft=false` ⇒ 失败会把 Release **永远留在草稿**
（gh-pages 已挂 0.3.10 链接 ⇒ APK 当场 404，且重跑也修不好）→ 资产齐了显式发布（幂等）；
③ 上传前比对 `--json assets` 的 name/size/digest，**一致的跳过**（重跑少传 ~660 MB）。

### 本轮数字

模块 **1.0.30**（versionCode 10030）/ App **0.3.10**（versionCode 26）。门禁：root selftest **83/0 × bash+mksh**、
proot selftest-funcs **81/0 × bash+mksh**、webroot 64/0、module-variant 36/0、provision 87/0、oneshot 29/0、
customize 24/0、detect-mount 6/0、cmp-consistency 16/16、contract-check（root+proot）✅、shell-compat ✅、
App 单测（`ChannelSignatureTest` 6 条新增，`testRootFullDebugUnitTest` 全绿）。

### 下一轮第一件事（真机）

1. 装 **模块 1.0.30** + **App 0.3.10**（重启一次），验 `linuxctl exec -- pwd`（应为 `/`）与终端 `attach`；
2. 关于频道：重开「更新」页点检查，把**那张截图**（含频道名与新加的指纹）发回来 ——
   指纹对不上文档那行 = 换了钥匙；对得上 = 这次取到的清单/签名有问题（多半 CDN 两份不同步）；
3. 第 3、4 条（模块状态未知 / DSH Web 桌面版）照旧，需要 `linuxctl exec` 通了之后才好查。

## 第 41 轮补记（2026-09-18 凌晨）：用户真机反馈的四条 —— 三条模块 WebUI + 一条分步启动

用户一句话交代了这轮的现场：**"我在 Root 模块那个 web UI 页面点停止测试，然后切换页面越来越卡，
由于有第一次崩的经历，我就强制重启了手机"**，以及**"Root 管理器渲染 web 页面有 2~3 秒迟钝，就空白，
其他模块不会"**、**"开机之后发现 DSH 会开机自启，模块页没有开启自启的选项设置，就给它关掉，
如果后面有重启那就是代码 bug 了"**。

**与模块版本的关系（验收前必读）**：设备上装的是 **1.0.29**，这轮的三条 WebUI 修复都在 **1.0.31**；
而且 **1.0.29 上「启动 DSH」必然失败** —— `cmd_dsh_start` → `spawn_dsh_in_env` 里带着
`--wd="$ROOTFS_DIR"`（toybox nsenter 不认），命令瞬间失败、`wait_dsh_url 20` 白等 20 秒后报
「DSH 进程已拉起，但 20s 内没有打印带令牌的登录 URL」。**装了 1.0.30+ 这条才通**。

| # | 现象 | 真因 | 改法（本轮） |
|---|---|---|---|
| 1 | 打开 WebUI 先**空白 2~3 秒**，其他模块不会 | `boot()` 在 `DOMContentLoaded` 里**同时**发 4 条桥接命令（`status`/`cat`/`update-module-info`/回滚），而 manager 的 `ksu.exec` 是**串行**的；光 `linuxctl status` 就要 1~3 秒 ⇒ 首帧被这一串顶住（页面 66 KB、零外链、无重特效） | `afterFirstPaint()`（双 rAF）+ `runBootPlan()` 按 `PURE.bootPlan()` **逐条**取数；状态卡加「正在读取状态…」占位 |
| 2 | 点一次「停止」后**越用越卡**（最后强制重启） | `stop.sh` 要等 supervisor 15s + 逐项卸载（20~30s），而「状态每 5s」「日志每 3s」两个定时器不等返回就发下一条 ⇒ 在串行桥上排队 | `GATE` 闸门：同一时刻只允许一个轮询在飞（丢拍不排队）；`start/stop/restart` 期间暂停轮询，`finally` 复位；操作期间把"要等 10~30 秒"写出来 |
| 3 | 「模块页没有自启设置，关了还是自启」 | ① 旧提示写着「与 App 设置页**共用同一份 config.json**」—— 假的：App 那个开关（「开机自启**状态服务**」）写的是自己的 SharedPreferences；② 旧实现遇到"文件在但没有 `autostart` 键"就把开关**置灰并显示关**，而设备侧是"默认开、只有读到 `false` 才关" ⇒ 显示与事实相反 | 文案改真并点明两个开关不同；语义抽成 `PURE.autostartView()`（**缺键 = 开且开关可用**）；写入支持"插键"并**回读核实**；「停止」后若自启仍开，明确写"下次重启还会自动启动" |
| 4 | 分步启动后界面像坏了：副标题「健康检查（Web 未响应）」+「打开 DSH」永恒「正在获取登录地址…」 | 这三处文案只看 `dshHealthy`/有没有 url，**不看"这次本来就是仅环境、DSH 按设计没起"** | `Diagnoser.stage` 先判 env-only；两处文案抽成纯函数 `StartControls.openDshSupporting()` / `dshPaneNotOpenText()`（穷举单测）；full 模式 DSH 真挂了仍照旧报「Web 未响应」 |

> 判据留档：**"停止"不改开机自启**（那是两件事），所以「停止」之后必须把"下次重启还会自动启动"说出来 ——
> 用户就是照着"没有设置 ⇒ 我关掉了"的错觉等下一次重启验证的。
> 回归：WebUI 自测 **64 → 89/0**；App 单测 `StartControlsTest` 15 条、`DiagnoserTest` 新增 3 条。

## 第 41 轮再补（2026-09-18 凌晨 · 用户：「优化 GitHub 编译工作流，给几个选择 + 后果」）

用户选 **1+2+3**，并加了一条诉求：「参数验证化，没有改的就不重复塞」。已全部落地（详见 `docs/STATUS.md` §3.10.39）。

**实测基线（先量再改）**：一轮 40 分钟里，**⑤ 发布占 35 分钟**（Release 上传 1998 s 还没完 / 1.3 GB；
gh-pages 推送 101 s），而编译 6 个节点各 2~3 分钟、并行墙钟约 3 分钟 —— **瓶颈全在发布的上传量**。

| 改动 | 效果 | 不能不知道的后果 |
|---|---|---|
| `.bin` 不再进 gh-pages（只挂 Release） | 每次少推 660 MB；**止住 gh-pages 分支每版 +660 MB**（Pages 1 GB 限制是硬伤） | 无消费方受影响（下载页/`index.json` 都指向 Release，App 用 APK 内嵌包）——已逐条核查 |
| Release 上传 3 路并发 + 4 次退避重试 + 「已一致就跳过」 | 上传墙钟约 1/3；重跑不重传 | 并发会多碰几次 500（有重试兜）；**仍不吞错**：某个资产 4 次都失败照旧报红 |
| 变更集判定（`tools/ci-changeset.mjs`，39 条回归） | 文档/CI-only push：40 min → **约 3 min**；只改模块：不重打离线包 | **版本号 = 发布意图**：改了产物相关文件却没升版本 ⇒ 只跑门禁 + 警告（不会静默发半个东西） |
| 手动参数 `force_build` / `force_publish` / `cleanup_pages` | 兜底入口 | `force_publish` 在"版本确实没变"时会被**拒绝**（别把 1.3 GB 原样再传一遍） |

**还差一步（需要你点一次）**：清掉 gh-pages 上**历史**的 `.bin`（新发布不再放，但老版本推进去的还在，
`keep_files` 不会删）。做法：Actions → 流水线 → Run workflow → 勾 **cleanup_pages** 跑一次。
（或者我用部分克隆手动推一次也行，说一声即可。）

**已顺手做掉的一件事**：gh-pages 上**历史 83 个 `.bin`（约 5 GB）已经清掉**（分支 tip 203 → 120 个文件）。
新发布不再往站点放 `.bin`，但老版本推进去的还在（`keep_files` 只增不删）——所以直接清了一次，
不需要你再点 `cleanup_pages`。清理用的提交只删文件、**不下载任何 blob**（部分克隆 + `write-tree --missing-ok`）。

**顺带发现（值得你决定）**：站点上还躺着 **54 个历史模块 zip**（每个 full ≈50 MB，`stable/` 与 `beta/` 都有，
从 1.0.3 到 1.0.30）。它们**有消费方**（下载页的"下载模块 zip"是站内相对链接），但只有**当前版本**需要；
而且项目本来就在 Release 上主动撤掉旧模块包（"下错会得到一份看起来能装的旧包"）。
要不要把"每个通道只保留当前模块版本"也做成 `cleanup_pages` 的一部分？说一声我就加。

## 第 43 轮（2026-09-18）：nsenter 修完 → 新阻塞是流水线自己 → 改走本地送达（+ 为什么不能删库重建）

第 42 轮的诊断与修法已落进 `docs/STATUS.md` §3.10.40（toybox nsenter 吃掉**命令之后**的选项）。
本节只记 43 轮**新增**的部分 —— 这些是"修完推上去之后"才暴露出来的。

### 1. 真正卡住你的不是代码，是 ⑤ 的 Release 上传

- `454ed75`（模块 **1.0.32**）已推 main 并排队，但前面 `d7a2795`（App 0.3.11 / 模块 1.0.31）那次从
  **17:50:37Z** 起停在 ⑤「发布到 GitHub Releases」，一小时多没有任何变化；`githubstatus.com` 全绿，
  不是平台故障。证据：`git ls-remote --tags` 里只有 `v0.3.10`(→`8fe892e`)，**没有 `v0.3.11`**；
  而 publish.yml 是"先建草稿 → 逐个传资产 → 最后才 `--draft=false`"，且**草稿匿名 API 看不见** ——
  所以那一步确实是在往 v0.3.11 的草稿里传东西，人是看不到进度的。
- 量级（§3.10.39 自己量过）：一轮发布要上传 **1.3 GB**（12 个 APK ≈500 MB + 6 个 `.bin` ≈430 MB），
  3 路并发墙钟仍以十分钟计。而"已一致就跳过"是按 `name + size + sha256` 比对的，**APK 每次重建字节都不同**
  ⇒ 每轮都全量重传。**这是结构性的，不是这次偶发**（改进方向见 §4）。

### 2. 三条必须记住的 GitHub Actions 语义（这次踩全了）

| 语义 | 具体表现 | 后果 |
|---|---|---|
| 并发组里**排队的**旧 run 会被新 run 取消 | 组名 `pipeline-<ref>-<event>`、`cancel-in-progress: false`；`8fe892e` 那次 pending 的 run 在 **18:20:58**（我推 `454ed75` 的那一刻）被自动取消 | 别把"我没取消它"当异常 |
| **`workflow_dispatch` 是另一条队列** | 组名带 `event_name` | 手动 Run workflow **不会被卡住的 push run 挡住**（但要先看下面那条警告） |
| UI 的 Cancel 是**协作式**的 | runner 卡在网络调用里时不响应，点十几次也没用；仓库没有 `timeout-minutes` 覆盖 ⇒ 默认 **6 小时**上限兜底 | 唯一能立刻强杀的是 API `POST /repos/<o>/<r>/actions/runs/<id>/force-cancel`（本机实测端点存在：未鉴权返回 401），需要 `Actions: write` 的 PAT |

⚠️ **手动 `workflow_dispatch` 的次序警告**：它和卡住那条会写**同一个 Release**（都是 v0.3.11）。
卡住那条若之后复活跑完，它末尾"撤掉非当前版本的模块资产"会把你刚发的 **1.0.32 模块 zip 删掉**。
⇒ **先强杀 → 再手动跑**；或者已经装了本地包的话，就别急。

### 3. 本轮的解困路径：本地打包 → 直接写进手机 `/sdcard/Download/`

容器能直接写设备共享存储（实测可写、设备侧 `ls` 也看得见），于是不依赖 Release 也能交付：

```bash
# full（内置 DSH，和你设备现存层同版本 0.1.5-rc.2）
bash module/mkmodule.sh --version 1.0.32 --variant full \
  --dsh-layer dist/dist-backup/dsh-0.1.5-rc.2.erofs.gz \
  --dsh-sums  dist/dist-backup/SHA256SUMS.dsh-layer.txt \
  --out /tmp/sl/sunsetlinux-module-1.0.32.zip          # 50,089,501 B（官方 1.0.30 是 50,079,737 B，同形）
bash module/mkmodule.sh --version 1.0.32 --variant bare \
  --out /tmp/sl/sunsetlinux-module-1.0.32-bare.zip     # 264,815 B
cp /tmp/sl/sunsetlinux-module-1.0.32*.zip* /sdcard/Download/
```

- 已核：包内 `bin/{linuxctl.sh,start.sh,stop.sh,supervise.sh,common/port-probe.sh}` 与仓库 working tree
  **逐字节 diff 一致**；9 处 nsenter 调用**全部**带 `--`（含 `umount -l`/`-f`）；`--wd=` 残留 0 处。
- sha256：full `00b9d13cc29ac5c95b04ad11071faa4c3feb5e5e1faaa8cb2cb50ad4eda4617c`、
  bare `4fcaacb4b79c45b90c040832216cb8fb3d8ec5fcbb8898e717efb7541b00a2a0`（`.sha256` 也在同目录）。
- **装完必须重启一次**：`bin/` 是 `module/post-fs-data.sh` 在**开机时**同步到 `/data/sunsetlinux/bin` 的，
  而 App 调的正是 `/data/sunsetlinux/bin/linuxctl`（`core/LinuxCtl.kt` 的契约路径）。
- 不想重启也想立刻用：当前环境还活着（`env-mode=env-only`、`run/supervisor.pid=26586`），
  在 App 的**终端**里 `setsid /opt/sunsetlinux/supervise.sh --port 3081 >/root/supervise.log 2>&1 &`
  （交互终端那条 nsenter 命令尾部只有 `-i`，是 nsenter 自己的 IPC 短选项、被无害吞掉 ⇒ 终端本身没坏）。

### 4. ★ 为什么"强制删库重建"是错的（用户当时正在考虑）

| 会永久丢的东西 | 为什么补不回来 |
|---|---|
| **`CHANNEL_SIGNING_KEY`（Actions secret）** | GitHub 的 secret **只写不可读** —— 删库=这把 Ed25519 私钥永久消失；App 里内嵌的是对应公钥，之后频道清单再也签不成已装 App 认的样子 ⇒ HANDOFF 第 1 条"频道签名校验失败"会从"待查"变成**永久** |
| `app/app/debug.keystore`（仓库内固定签名） | 丢了以后两个 App 的签名就换了 ⇒ 手机上**已装的 App 无法覆盖升级**，只能卸载重装（数据全没） |
| 全部 Release / gh-pages / Actions 历史 | 下载页历史包、`/channel/channel.json`（站点根）、构建记录；重建后路径虽同名，但内容与签名链要重做 |
| 而且**卡住的那条 run 不会因此停** | 它已经在 GitHub 的 runner 上，属已删仓库的 run 照样跑完 |

**结论**：重建仓库既不解决"上传慢/挂"，又是一次不可逆的损失。真要不放心，本地 `git clone --mirror`
留一份镜像就够了（`.git` 约 809 MB，就在 `/root/Q/SunsetLinux`）。另外 `docs/**`、`**/*.md` 在
`paths-ignore` 里 —— **纯文档提交不会触发流水线**，所以写交接不会往那条队列里再加一次运行。

### 5. 本轮之后的第一件事（按优先级）

1. 装 `/sdcard/Download/sunsetlinux-module-1.0.32.zip` → **重启** → 验三处：
   `linuxctl dsh start`（3080 被占时应落到 **3081**，看 `run/dsh.port`）/ 终端 `attach` 与
   `linuxctl exec -- ls -l /`（带选项的命令）/ `stop` 之后挂载与 loop 干净。
2. 清掉卡住那条 run（先强杀再手动，理由见 §2 的警告）。
3. 流水线改进候选（**下一轮值得做**）：让"没改的变体不必重传"真正生效 —— 现在 APK 每次重建字节都不同，
   跳过逻辑形同虚设；可行方向是"按变体源码/输入哈希决定重建与上传"，或把 `.bin` 挪到 `layers-*` 静态托管
   只传 `index.json`，把一轮发布的上传量从 ~1.3 GB 压到几十 MB。
4. 一处**小文案**（不值得单独发版，下次顺手改）：`runtime/root/start.sh` 外层那段端口预检还是旧口气
   ——「WARN: 端口 X 已被占用；dsh 可能自行换端口，实际端口以 run/dsh.port 为准」，而现在的行为是
   **环境主动让路**（环境内 `port_pick_free` 直接挑空闲端口）。改成"环境会让路到空闲端口（实际端口看
   run/dsh.port）"即可；外层只报信息、不参与选端口，别让它和里面的判定各说一套。

## 第 44 轮（2026-09-18 凌晨）：用户截图「点了启动环境，然后就没了，点不了启动 DSH」——根因是**命令永不返回**，不是按钮矩阵

用户两条原话：**「点了启动环境，然后就没了，点不了启动 DSH」**、**「找到问题了，端口被占，没有写偏移」**。
两条都成立，但**主因是前者**：命令卡住 ⇒ `busy` 永久为真 ⇒ 启动区全灰 + 点击被静默丢弃。
完整归因与实证表见 `docs/STATUS.md` §3.10.42；这里只留"下一轮必须知道"的部分。

### 1. 这一轮改了什么（模块 **1.0.33** / App **0.3.12**）

| 文件 | 改动 |
|---|---|
| `runtime/root/start.sh` | 新增 `spawn_daemon()`：`( spawn_detached … >>"$DAEMON_LOG" 2>&1 </dev/null & )`；删掉 `--fork` 那一支探测与 `uc_ok` 退出码判定（前台 spawn 是祸根）；端口预检 WARN 改真口气 |
| `runtime/root/supervise.sh` | 起 DSH 后**立刻**把请求端口写进 `run/dsh.port`（URL 就绪后以 URL 内端口覆盖；退出路径照旧删） |
| `app/.../ui/LauncherViewModel.kt` | `busy` 改 `finally` 无条件复位；新增 `actionTarget`，`refresh()` 观测到状态到位即解锁 |
| `app/.../core/ActionTarget.kt`（新） | 动作 → 目标状态的**纯函数**判据 + 穷举单测 `ActionTargetTest`（7 条） |
| `tools/shell-compat-check.mjs` | 新增**前台 spawn 闸门** + 自检（7 组样本）；变异测试实测会红 |
| `runtime/root/selftest.sh` | 新增行为断言：`spawn_daemon` 必须在 5s 内返回 + 一条"前台确实会等"的对照样本 |

回归：`selftest.sh` **86 → 88/0**、`shell-compat-check.mjs` 绿（含变异测试）、App 单测 **264 → 271/0 × 2 变体**
（新增 `ActionTargetTest` 7 条；跑法见下面第 3 条的 locale 坑）。

### 2. 装完这个模块，验的顺序（**先验"能不能点"再验"起不起得来"**）

1. 装 `/sdcard/Download/sunsetlinux-module-1.0.33*.zip` → **重启**（`bin/` 是开机同步到 `/data/sunsetlinux/bin` 的）；
2. 开 App → 「分步启动 → 仅启动环境」：**环境起来之后几秒内五张卡片就该恢复可点**
   （以前会一直灰到 App 进程被杀）。若仍是灰的：看 `ps -A | grep sh` 里那条
   `linuxctl start --no-dsh` 链是否还活着 —— 活着说明本修复没生效，把 `/proc/<pid>/wchan` 发我；
3. 点「启动 DSH」：3080（DSHA 占着）应让路到 **3081**，`run/dsh.port` 里立刻有值，
   URL 出现后 App 的「打开 DSH」可点；
4. `stop` 之后挂载与 loop 干净（第 43 轮那条一直没验完）。

### 3. 本轮踩到的构建环境坑（**不是代码问题，但会挡住你**）

`app/` 的单测任务在本容器里会以 `Internal compiler error` 失败，根因是 **locale 是 ASCII**：

```
$ java -XshowSettings:properties -version | grep sun.jnu
    sun.jnu.encoding = ANSI_X3.4-1968
```

于是 Kotlin 写不出名字带中文的测试类文件（本仓测试函数名全是反引号中文）：

```
e: java.nio.file.InvalidPathException: Malformed input or input contains unmappable characters:
   .../OfflineBundleTest$?????dist-bundles ???????????????$$inlined$sortedBy$1.class
```

修法（跑构建时带上即可，别改代码）：

```bash
cd app && LANG=C.UTF-8 LC_ALL=C.UTF-8 ./gradlew --offline :app:testRootFullDebugUnitTest
```

## 第 44 轮再补（同日 03:5x · 用户追问「脚本似乎在尝试重复挂载？」）

完整归因见 `docs/STATUS.md` §3.10.43，这里只留结论与下一轮入口。

### 1. 用户四个疑问的答案（一句话版）

| 疑问 | 答案 |
|---|---|
| 「挂载不是正常状态吗？」 | 建树是正常动作，但**同一个环境建两棵树不正常**：`run/mounts` 13 → 26 条 |
| 「已经有挂载了呀，为什么会重建？」 | 你看得见的那些"已有挂载"多半是**回流到宿主 ns 的残留**（`make-rprivate` 在这台机没生效），它们属于**已经死掉的守护进程的私有 ns**，不可复用；`start` 复用环境的判据是"**活着的守护进程 + ready**"，不是"宿主里有没有挂载" |
| 「挂载判定也没有接上？」 | 接上了，但它**只认识"已就绪"**：建树那 30~50 秒里 `supervisor.pid`/`ready` 都还没写，于是被判成 `stopped` ⇒ 第二次 start 又完整建一棵 |
| 「挂载上来就直接启动环境啊？为什么重启环境时重新挂载？」 | 正常路径就是这样（树在就直接 `exit 0` 不 mount）。这次是**两次并发 start**，两条链各自"建树 → 起环境" |

### 2. 本轮改动（模块 **1.0.34** / App **0.3.13**）

- `start.sh`：`run/start.lock`（`set -C` 原子占有；拿不到锁就等它就绪 ≤90s，绝不重复建树；
  陈旧锁自动接管；EXIT trap 释放）；inner 写完 `supervisor.pid`+`ready` 时释放。
- `linuxctl.sh`：`gather_status` 把"有人正在建树"报成 `state=starting`（App 的启动卡据此置灰）。
- `stop.sh`：两条清理路径都删 `run/start.lock`。
- App **只升版本**（0.3.13 / code 29），代码无改动 —— 升它是因为内嵌的模块包换成了 1.0.34。

回归：`runtime/root/selftest.sh` **88 → 101/0**（bash + mksh）；`shell-compat-check.mjs` 的前台
spawn 闸门收紧（新增两组**真实误报**样本：诊断消息里提到函数名、函数名走拼接的探针），
变异测试仍会红。

### 3. 手机上怎么收拾现在这堆（**装 1.0.34 之前**）

现在设备上是"两棵树 + 三条守护链"的状态（03:39 那次并发），`dsh start` 会报"进不去环境"。
最干净的顺序：

1. App 里点「停止环境」（若还报 loop 残留，再点一次）——`stop` 现在会清掉启动锁；
2. 装 `/sdcard/Download/sunsetlinux-module-1.0.34.zip` → **重启一次**（`bin/` 开机同步）；
3. 重启后**只点一次**启动；建树期间界面应显示"正在启动"（`state=starting`），启动卡置灰；
4. 卡住的话看 `run/start.lock` 是否存在、里面的 pid 是谁 —— 有它就说明有人在建树，别重复点。

### 4. 下一轮第一件事（我认为优先级最高）

**`make-rprivate` 为什么没生效**：这台机 `/` 或 `/data` 是 `shared:`，toybox 没有
`--make-rprivate`，base 层 util-linux `mount` 的回退也没成功 ⇒ 环境挂载回流宿主 ⇒
`stop` 之后宿主里留挂载/loop 残留（用户"看着像已经有挂载"的根源）。修好它，"重复挂载"
这个话题才会真正结束。其次：`stop` 时显式清理宿主侧残留；`start` 前先清残留再建树。

（`org.gradle.jvmargs` 里已经有 `-Dfile.encoding=UTF-8`，但 `sun.jnu.encoding` 是 JVM 启动时
按 locale 定的，`-D` 覆盖不了 —— 所以必须给 `LANG`。下一轮可以把它写进 CI/构建脚本的注释里。）

## 第 45 轮（2026-09-18 04:0x）：用户追问的那三个问题落到地上 —— 泄漏、清理、打包漏文件（模块 1.0.35 / App 0.3.14）

详细归因见 `docs/STATUS.md` §3.10.44。这一轮只留"下一轮必须知道"的。

### 1. 已查实的三件事

| # | 事实 | 影响 |
|---|---|---|
| 1 | 我们的挂载**在宿主 init 的挂载表里**（`grep -c sunsetlinux /proc/1/mountinfo` = **33**） | `stop` 之后像"已经有挂载"；`cleanup_stale_loops` 见别的 ns 里还挂着就 `跳过 detach` ⇒ **loop 残留永远清不掉** |
| 2 | **`make-rprivate` 从来没成功过**：唯一那次尝试在外层（挂载树之前），而能执行它的 util-linux 在挂载树里 `detect_util_mount` 之后才取到 ⇒ 兜底那支从未执行 | 泄漏没有任何东西挡着 |
| 3 | `module/mkmodule.sh` 的 `BIN_COMMON` 是**硬编码清单**，新增 common 脚本不会被自动收进包 | 这次真踩了：`host-residue.sh` 没进 1.0.35 的首个包（已补 + 加闸门） |

> ⚠️ 一个**还没定论**的点（写在 STATUS 里了，别当结论用）：root shell 那层 ns 里看到 `/`=master:1、
> `/data`=master:60（像 slave，本不该往上传播），但挂载确实落进了 init 的挂载表。
> 候选原因是"链上还有一层 shared"或"某次守护进程没成功 unshare"。本轮修复**不依赖**这个判断。

### 2. 本轮改动

- `start.sh`：`ensure_ns_private()`（toybox → base 层 util-linux `--make-rprivate /` → 退一步 `/data`），
  **在 `detect_util_mount` 之后调用**；`ns_is_private` / `ns_private_evidence` 用 mountinfo 打
  **可核对**的结论行；开工前先 `host_residue_clean`（清上次泄漏）。
- 新增 `runtime/common/host-residue.sh`：当前 ns 直接 `umount -l`；init ns 走
  `nsenter --mount=/proc/1/ns/mnt -- umount -l`；只碰 `/data/sunsetlinux` 下的已知路径。
- `stop.sh`：正常卸载后仍有残留 → 先 `host_residue_clean` 再判定（`verify_clean` 加参数防重入）。
- `module/mkmodule.sh`：`BIN_COMMON` 补 `host-residue.sh`。
- `tools/module-variant-selftest.mjs`：新增"打包清单完整性"闸门（变异测试会红）。

回归：`selftest.sh` **101 → 108/0**（bash + mksh）、`module-variant-selftest.mjs` **36 → 37/0**、
`shell-compat-check.mjs` 绿、App 单测 271/0 × 2 变体。

### 3. 装 1.0.35 之后，专门验这三条（**都是这一轮的直接验收点**）

1. **泄漏有没有止住**：起环境后 `grep -c sunsetlinux /proc/1/mountinfo` —— 期望 **0**；
   若仍 >0，看 `run/linux.log` 里那行 `传播属性现状：…（shared: = 会回流宿主；master: = 安全）`
   与 `已把 mount ns 设为私有：…`，把这两行发我（那是判定下一步的唯一依据）；
2. **残留能不能清**：`stop` 之后再 `grep -c sunsetlinux /proc/1/mountinfo`（期望 0），
   并看日志里有没有 `已从宿主 init ns 卸下残留挂载：…`；
3. **loop 不再残留**：`stop` 之后 `losetup -a | grep sunsetlinux` 应为空（以前会因为"别的 ns 还挂着"而跳过 detach）。

### 4. 下一轮候选（按优先级）

1. 若第 3 条仍不干净：查 `make-rprivate` 到底有没有生效（`ns_private_evidence` 的输出说话），
   必要时把 util-linux 的 `mount`/`unshare` **提前**取到（例如从 erofs 里抽出来，避开"必须先挂 base 层"的循环）；
2. `stop` 的清理做成"先清宿主残留、再报结果"（现在已经是这个顺序，但只在"有残留"时进入）；
3. 把 `BIN_COMMON` 这类清单**全部改成目录派生**（本轮只加了闸门，没有消灭清单本身）。

## 第 46 轮（2026-09-18 04:xx）：内核 v2 · P1 前半落地（`sunsetd` 唯一状态机）

设计见 `docs/core-v2-design.md`，逐条证据见 `docs/STATUS.md` §3.10.45。这里只留"下一轮接手要用的"。

### 1. 现在的形状

```
App/CLI/WebUI ──(读 run/state.json / 将来走 run/control.sock)──> sunsetd（Kotlin，跑在 app_process）
                                                                   ├── 唯一状态机（Phase/Owner/世代）
                                                                   ├── 作业（互斥 + 幂等，提交即返回）
                                                                   └── 观察 v1 标记（ready/supervisor.pid/start.lock/env-mode）
```

- 内核**不重写**挂载/命名空间：它驱动现有 `linuxctl start|stop|dsh *` 并观察结果（P2/P3 才把挂载表变成数据）。
- `service.sh` **先起内核，再发起 `linuxctl start`** ⇒ 模块那条启动会被内核认领成 `owner=foreign-observer`。
- 内核起不来（缺 dex / 无 app_process / SELinux 域不允许）⇒ 只记一行日志，**行为与今天完全一致**。

### 2. 下一轮第一件事：真机首次实跑（P1 的唯一未验证风险点）

```bash
# 1) 本机构建 dex（缺 d8/stdlib 会明确报错，不静默）
node tools/build-sunsetd-dex.mjs && node tools/build-sunsetd-dex.mjs --check
# 2) 打模块（会内嵌 bin/sunsetd.dex；日志里应看到"已内嵌 sunsetd.dex"）
bash module/mkmodule.sh --version <下个版本> --variant full --dsh-layer dist/dist-backup/dsh-0.1.5-rc.2.erofs.gz \
  --dsh-sums dist/dist-backup/SHA256SUMS.dsh-layer.txt --out /tmp/sl/module.zip
# 3) 装模块 → 重启 → 看内核有没有起来
adb-shell ls -l /data/sunsetlinux/run/control.sock /data/sunsetlinux/run/state.json
adb-shell cat /data/sunsetlinux/run/state.json      # phase/generation/owner 都该有
adb-shell tail -20 /data/sunsetlinux/run/sunsetd.log
adb-shell tail -20 /data/sunsetlinux/run/service.log # 找"内核：已在后台发起 sunsetd"
```

**判据**：`state.json` 里 `phase=running`、`owner=foreign-observer`、`generation>=1`（认领了模块那条启动）；
`control.sock` 存在。若内核没起来：先看 `service.log` 那行是"缺 dex"还是"没起来"，
后者大概率是 SELinux 域（那就先在 App 进程内起内核，或退 v1 —— 回退路径本来就在）。

### 3. 已知的坑（这轮踩到的，写下来省下一轮的时间）

| 坑 | 现象 | 处理 |
|---|---|---|
| Kotlin/JVM 模块离线构建 | `:sunsetd` 首次 `--offline` 会因缺 `error_prone_annotations` 失败 | 允许联网解析一次（之后进缓存）；插件用 buildscript classpath + `apply`（缓存里没有 `org.jetbrains.kotlin.jvm` 的 marker），`dependencies` 用字符串写法（`testImplementation` 访问器只在 `plugins {}` 里生成） |
| 中文测试函数名 | JVM 方法名不许含 `/`（`空/垃圾输入…` 直接编译失败） | 名字里别用 `/`、`.` |
| 测试互相污染 | 内核会读回上一个测试写的 `state.json` | 每个测试用**独立临时目录**（`freshHome()`） |
| 打包闸门随环境漂移 | 本机构建过 dex ⇒ "不含内核"那条断言永远不成立 | 套件顶部把 `SUNSETLINUX_SUNSETD_DEX` 指向**不存在**的路径，要验"进包"时显式传 |

### 4. 本轮 CI 暴露的两件事（下一轮先修，**都不是内核代码问题**）

1. **同 tag 的 16 个资产被重发**（`dad3512` 那次 run 全绿后，release 与站点的 manifest 都变成
   `commit=dad3512`）。**已查实原因**：不是版本规则被绕过，而是 prep 从**站点**取"上次发布版本"、
   取不到就"保守当作全都变了"（§3.10.39 自己写的兜底）——那次站点 curl 失败 ⇒ 整轮重发 1.3 GB
   并改写同 tag 的字节。**已修**：站点取不到时先退 `releases/latest/download/index.json`。
   核版本请看 `index.json.commit`，别假设 tag 不可变。
2. **我加的 dex 构建步骤被 `|| echo "::warning::…"` 吞掉了**：② 那个 job 没有 d8（SDK 在 App 构建 job），
   脚本按设计 `exit 1`，于是发布出的模块**不含** `bin/sunsetd.dex`。
   碰巧"未验证的内核没被发出去"，但这是运气。修法：dex 构建挪到有 SDK 的 job（或装 SDK），
   并在内核真机验证通过之后把"必须带内核"变成**硬断言**。

> 教训（与本仓老规矩同源）：**任何"失败就警告"的写法都等于静默降级**。本轮我自己又犯了一次。

## 第 47 轮（2026-09-18 05:xx）：内核 P1 后半 —— `status` 以内核为准（模块 1.0.36，待真机验）

细节见 `docs/STATUS.md` §3.10.46。下一轮接手要用的：

### 1. 现在的形状（P1 已完成的边界）

```
linuxctl status ──┬── 内核在线（state.json + 15s 内心跳）⇒ 相位以内核为准 + kernel 附加键
                  └── 内核不在/心跳过期 ⇒ 退回 v1 标记判据（兼容降级，不拿过期状态冒充）
service.sh ──────── 先起 sunsetd（dex + app_process），再发起 linuxctl start ⇒ 内核认领为 foreign-observer
```

**App 不用改**：既有键一个没动，`kernel` 是附加键（`contract-check.mjs` 已确认允许）。

### 2. 下一轮第一件事：真机验证（清单已写进 `/sdcard/Download/本轮交付说明-模块1.0.36-内核P1验证.md`）

六条：`control.sock`/`state.json` 在不在 → `state.json` 内容 → `sunsetd.log` 有没有"控制面已就绪"
→ `service.log` 有没有"内核：已在后台发起 sunsetd" → `linuxctl status` 的 v1 键 + `kernel` 块
→ 挪走心跳后 status 是否退回 v1。

失败时的两种日志已经区分好：`跳过（缺 …）` = 打包问题；`sunsetd 没起来（SELinux 域/ART 问题？）`
= **P1 唯一没验证过的风险点**，把 `sunsetd.log` 尾部原话发出来即可。两种情况行为都按 v1。

### 3. P1 还剩（按优先级）

1. 真机验证结果回来之后的收尾；
2. **删掉"在不在跑"剩下的重复实现**：`start.sh:running_ns_pid`、App 里那份推导
   （`linuxctl` 那份已经改成"以内核为准 + `start.lock` 兜底"）；
3. `submit` 作业模型接到 App（P2 的第一件事；本轮 App 仍走 v1 命令，内核只观察）；
4. 内核单测进 CI **已经接了**（`:sunsetd:test`），但"内核在线"路径的端到端测试目前只有
   shell 侧那 6 条 —— 真机验证回来之后可考虑加一条"假内核 + 真 sunsettl status"的 CI 用例。

### 4. 踩到并已修的

- **入口类名**：Kotlin `object Main` + `@JvmStatic main` 编译出来是 `…sunsetd.Main`，不是 `MainKt`。
  已在模块自测里加静态闸门（比对 `service.sh` 与 jar 内容）。
- **CI 保守重发**：站点 `index.json` 取不到时会重发整轮（1.3 GB）并改写同 tag 的字节。
  现在先退 `releases/latest/download/index.json`。

## 第 48 轮（2026-09-18 05:xx）：**收束** —— P1 收尾 + 决策记录 + 修 CI 不变式

用户要求："开始收束，把所有相关决策记录文档，准备更换对话框。"

### 0. 换对话框后从这里开始读

1. **决策记录**：`docs/decisions-core-v2.md`（产品级 D1–D5 / 架构级 A1–A9 / 实现级 B1–B9 / CI C1–C2 /
   被否决选项 / 开放问题）—— 这是"为什么这么做"的单一事实源；
2. **设计边界**：`docs/core-v2-design.md`（§6 已定案、§8 P1 工作项与完成判据）；
3. **进度与证据**：`docs/STATUS.md` §3.10.45–§3.10.47；
4. **真机验证清单**：`/sdcard/Download/本轮交付说明-模块1.0.36-内核P1验证.md`（六条）。

### 1. P1 现在的状态

| 项 | 状态 |
|---|---|
| P1-a 状态模型 + 契约 JSON + 单测 | ✅ 内核单测 34/0 |
| P1-b 控制 socket + `state.json` + 心跳/看护 | ✅ |
| P1-c 相位驱动（驱动现有脚本 + 观察 v1 标记） | ✅ |
| P1-d `status` 以内核为准（v1 键不变 + 降级） | ✅ `selftest` 116/0；`contract-check` 认 `kernel` 为允许的附加键 |
| P1-e dex 进模块 + `service.sh` 起内核 | ✅ 代码与打包都通；**真机 SELinux 域未验证**（唯一风险点，失败按 v1） |
| P1-f 回归/CI 接线 | ✅ `:sunsetd:test` 进 CI；模块包"有 dex 必进包 / 没给要出声"；入口类名闸门 |
| "在不在跑"删到只剩一份 | 🟡 `linuxctl`（以内核为准）与 `start.sh`（本轮）都改了；**App 那份是"读契约"，不算重复实现**；剩 `start.lock` 作为内核不在时的兜底 |

### 2. 本轮改动

- `runtime/common/kernel-state.sh`（新）：内核状态的**唯一读取实现**，`linuxctl` 与 `start.sh` 共用。
- `start.sh`：幂等判断加"内核说 running ⇒ 直接返回"。
- `module/mkmodule.sh`：`BIN_COMMON` 加 `kernel-state.sh`（打包闸门会盯着）。
- `.github/workflows/pipeline.yml`：prep 加不变式"要编 APK 且要内嵌离线包 ⇒ 强制重打离线包"
  （**回读 `$GITHUB_OUTPUT`**，别在同一步引用自己的 outputs）。
- `module/module.prop` → **1.0.37**；交付 `/sdcard/Download/sunsetlinux-module-1.0.37.zip`。

### 3. 下一轮第一件事

1. **收真机验证结果**（六条清单）。若 `sunsetd.log` 有"控制面已就绪"而 `state.json` 的
   `owner=foreign-observer` ⇒ P1 主线完成，可以进 P2；
   若是"SELinux 域/ART 问题" ⇒ 先解决启动方式（退路：App 进程内起内核）。
2. 之后按 `docs/decisions-core-v2.md` §六的开放问题推进（`start.lock` 去留、App 何时切 socket、
   免 root 前台服务、P3 的挂载表数据化）。

### 收束补记（同日）：CI 不变式那处修复自己踩的坑

第一版在 plan 步里**回读上一步**写的 `embed_bundles` —— `GITHUB_OUTPUT` 是**每步一个文件**，
读不到 ⇒ `grep` 返 1 ⇒ `set -euo pipefail` 把整步干掉（① 环境准备红）。
修法：`embed_bundles` 用 `steps.norm.outputs.embed_bundles`，读自己 outputs 的地方一律 `|| true`。
**本地用真实 changeset + 真实 git diff 端到端复现过**（`build_bundles=false → 强制 true`）。


## 第 49 轮（2026-09-18 08:xx）：P1 真机结果回来了 —— 风险点排除，控制面撞上"ART 与 SDK 桩不一致"

细节见 `docs/STATUS.md` §3.10.48。这一轮**没有靠猜**：真机结果、根因、修法都有证据。

### 0. 上一轮（1.0.37）的真机结果

| 清单项 | 实测 |
|---|---|
| `state.json` | ✅ 在 |
| `control.sock` | ❌ **不在** |
| `sunsetd.log` | ❌ `控制面启动失败：No static method open(Ljava/net/ProtocolFamily;)…ServerSocketChannel;` |
| `service.log` | ✅ `内核：已在后台发起 sunsetd` |
| `linuxctl status` | ✅ v1 键齐 + `kernel:{online:true,…}`（P1-d 真机通过） |

**P1 唯一没验证过的风险点（`app_process` 起内核的 SELinux 域 / ART）通过了** —— 内核确实跑起来了。
坏的是控制面 socket。

### 1. 根因：真机 ART **没有** `ServerSocketChannel.open(ProtocolFamily)`

从真机 `/apex/com.android.art/javalib/core-oj.jar` 抽 `classes.dex`，按 `method_ids`/`class_data`
逐条 dump（本机 python 解析；`dexdump` 是 x86_64 静态二进制，在设备上跑不了）：

- 真机：`ServerSocketChannel` **只有 `open()`**（没有 `open(ProtocolFamily)`）；
- SDK 桩 `android-35/android.jar`：**有** `open(ProtocolFamily)`；
- 真机 `sun.nio.ch.UnixDomainSockets`（connect/bind/accept/socket…22 个方法）**实现是齐的**，
  `SocketChannel.open(SocketAddress)` 也在 ⇒ **unix 域只给了客户端半边**。

**为什么 34 条单测全绿也没挡住**：单测在桌面 JVM（JDK 17）上跑，那里有这个静态方法；
`android.jar` 是**平台桩**，与设备实际装的 **ART apex 模块**可以不一致。这条差异**只有真机或设备 dex 能看见**。

### 2. 修法（模块 1.0.38，全在公开 SDK API 上）

| 改动 | 说明 |
|---|---|
| `app/sunsetd/.../Transport.kt`（新） | `Duplex` + `ControlListener` 把"什么 socket"与"协议"分开；选择器按**可注入的能力**选通道；回环自检 |
| `app/sunsetd/.../AndroidLocalTransport.kt`（新） | 真机通道：`LocalSocket.bind(FILESYSTEM)` → `LocalServerSocket(fd)`（内部 listen）→ `Os.chmod 0600`；纯字符串反射，dex 里**没有** `android.net.*` 引用 |
| `ControlServer.kt` / `World.kt` / `Main` | 接上通道选择；落 `run/control-transport` 与 `run/control-selftest`；失败时打印每条通道的失败原因 + 栈前 12 行 |
| `src/test/kotlin/android/net/*`（新） | **android.net 测试替身**：让 android-local 通道在 JVM 上整条跑通（不进产物） |

判据（AOSP 源码逐条核对，不是猜）：`LocalSocketImpl.bind → bindLocal(fd,name,namespace)`（支持 FILESYSTEM）；
`LocalServerSocket(FileDescriptor) → impl.listen(50)`；`LocalSocketImpl.close()` 对**外部传入的 fd 不关**
⇒ 关 fd 的是创建它的 `LocalSocket`，只有关它才能解开阻塞中的 `accept`。

### 3. 这一轮的验证（本机完成）

- 内核单测 **42 / 0**（原 34）：真机情形选择器、两条都缺、nio 被占用退让、探测口径、回环自检，
  以及 android 替身上的完整通道测试（收发 + 0600 + close 解开 accept）。
- 模块自测 41/0；两个 zip 里的 `bin/sunsetd.dex` 与本地构建 **sha256 严格一致**（`99a7833b…`）。
- 跑单测要注意：**中文方法名 + Gradle 守护进程的 ASCII locale** 会炸（`InvalidPathException`），
  必须 `LANG=C.UTF-8 LC_ALL=C.UTF-8`（`tools/build-sunsetd-dex.mjs` 一直是这么做的，手敲别忘）。

### 4. 交付

`/sdcard/Download/sunsetlinux-module-1.0.38-bare.zip`（1,060,692 B，**你装的这个变体**）
/ `sunsetlinux-module-1.0.38.zip`（50,885,375 B）+ 《本轮交付说明-模块1.0.38-控制面修复.md》。

### 5. 下一轮第一件事

收 1.0.38 真机结果，只要两行：

```bash
cat /data/sunsetlinux/run/control-transport   # 期望 android-local
cat /data/sunsetlinux/run/control-selftest    # 期望 ok:nio-unix（或 ok:android-local）
ls -l /data/sunsetlinux/run/control.sock      # 期望存在
tail -20 /data/sunsetlinux/run/sunsetd.log    # 找"控制面已就绪"+"回环自检"
```

`ok:*` ⇒ **P1 主线收口**，进 P2（`submit` 作业模型接到 App；`docs/decisions-core-v2.md` §六 的开放问题）。
`fail:*` ⇒ 日志里已有每条通道的失败原话（SELinux/权限那种），照原话修，别换判据。

### 6. 留给下一轮的坑（这轮学到的）

- **别拿 SDK 桩当设备事实**：`android.jar` 只保证"能编译"，设备上装的是**某个 ART apex 模块**。
  凡是要落到真机的能力，要么做**能力探测**（本轮这么做的），要么**按设备 dex 静态核对**。
- **真机不能跑临时程序**：`app_process` 会被设备策略判成"不认识的命令"（`POLICY_BLOCKED`）。
  所以"探针"要么进模块/进 dex，要么别做 —— **自检必须内建在交付物里**（`run/control-selftest` 就是这么来的）。
- 容器里 `/apex`、`/system/framework/*.jar` **可以直接读**（`/system/bin` 不行）；dex 解析用 python
  自己的小解析器（本机 `dexdump` 是 x86_64，跑不了）。

### 7. 收尾时抓到的第二个问题（同一轮，模块推到 1.0.39）：**发出去的模块包从来没有内核**

对发布产物做最后核对的习惯救了一次：Release 上的 `sunsetlinux-module-1.0.38-bare.zip`
**282,760 B**，而本地打的是 **1,060,692 B** —— 差的正是 dex 压缩后的大小。下下来一看，
**没有 `bin/sunsetd.dex`**；用 `--allow-no-kernel` 打对照包是 **282,762 B**（差时间戳），实锤。

- 三个打包路径（`ci.yml` 的 APK 内嵌包 / `offline-bundle.yml` 的 full / `publish.yml` 的 bare）
  都**没先构建 dex**，而且每处都只是 `|| echo "::warning::"`；
  `pipeline.yml` 的 prep 倒是建了，但同一句 warning 兜着 —— 而 `build-sunsetd-dex.mjs`
  当时**先找 kotlin-stdlib 再构建 jar**，冷缓存 CI 上必然失败 ⇒ warning 一出、包照样发。
- 后果同 0.2.6：装上后 `service.sh` 只记一句"内核：跳过（缺 bin/sunsetd.dex…）"，**内核静默消失**。

**修法**：`mkmodule.sh` 缺 dex **直接 die**（要无内核包必须显式 `--allow-no-kernel`，且大声警告）；
`build-sunsetd-dex.mjs` 调整顺序（先 jar 后 stdlib）使这一步可以硬失败；三个 CI 路径补齐构建 dex；
`pipeline.yml` 改成无条件断言"包里必须有 `bin/sunsetd.dex`"。版本推 **1.0.39**（同一个号码下不许有两份不同字节）。

交付：`/sdcard/Download/sunsetlinux-module-1.0.39{,-bare}.zip` + 《本轮交付说明-模块1.0.39-控制面修复.md》
（1.0.38 的文件已从手机上撤掉，避免装错那份没内核的）。**内核 dex 与 1.0.38 完全相同**（`99a7833b…`），
所以已经装了 1.0.38 的话，直接按 §5 验即可。

**闭环（同日，CI run 50 / `053dce8`）**：流水线全绿。Release `v0.3.14` 上
`sunsetlinux-module-1.0.39-bare.zip` 从 **282,760 B → 1,062,099 B**，包内确认有
`bin/sunsetd.dex`（2,527,684 B，含 `AndroidLocalTransport`/`android-local`/`control-selftest`）；
**APK 内嵌的那份也修好了**（`assets/module/sunsetlinux-module.zip` 1,062,101 B，里面有 dex）。
手机上的交付包已换成 **CI 原样字节**（sha256 `db8e085f…`，与频道清单一致）——
所以用户验的就是"别人从频道装到的那一份"。模块自测 41 → 43/0。

## 第 50 轮（2026-09-18 09:xx）：P1 真机闭环 ⇒ 进 P2（宿主侧客户端 `Ctl`，模块 1.0.40）

### 0. P1 收口（真机证据）

装上 1.0.39 重启后：`run/control-transport = android-local`、`run/control-selftest = ok:android-local`、
`control.sock` 存在且 `srw-------`、`state.json` 是 `generation=1 / phase=running / owner=foreign-observer`、
`sunsetd` 常驻（pid 3611）、心跳新鲜。完整表见 `docs/STATUS.md` §3.10.50。
**P1 主线收口**：唯一状态机在真机成立，且"内核不在 ⇒ 退回 v1"没被破坏。

★ **顺带逮到一条对 P2 影响更大的事实**：真机上 **`java.nio` 的 unix 客户端也不可用**
（自检先试 nio 客户端失败、落到 android 才 ok）。⇒ **P2 的客户端（App 与 CLI）必须用
`android.net.LocalSocket`**，不能想当然用 java.nio。§3.10.48 的教训（SDK 桩 ≠ 设备运行时）
在客户端半边重演了一遍 —— 这也是把自检改成"所有通道都试并记录原因"的直接理由。

### 1. P2 的第一块：为什么是"宿主侧客户端"而不是先改 App

P2 = "App 改成提交作业 + 订阅"，但 **socket 是 `0600 root`**（D3，正是为了不让任何 App 连上来
指挥 root 内核）⇒ **App 进程连不上**。所以顺序必须是：

1. **（本轮）** root 侧的客户端 `Ctl` + 客户端选路 + 开机自检；
2. 再让 App 用 `su -c … Ctl submit start` / `Ctl wait <id>` 换掉现在的"阻塞跑 linuxctl"。

### 2. 本轮改动

| 文件 | 说明 |
|---|---|
| `app/sunsetd/.../Ctl.kt`（新） | 子命令 `status/ping/version/submit/job/wait/raw`；退出码 0/2/3/4/5；`wait` 只打印最后一行 |
| `app/sunsetd/.../Transport.kt` | `Clients.open`（客户端选路）+ `ControlClient`；自检改成"所有通道都试、结果全写进结论" |
| `module/service.sh` | 开机异步跑一次 `Ctl status` → `run/ctl-status.json` + service.log 一行 rc |
| `app/sunsetd/src/test/.../CtlTest.kt`（新） | 8 条：真 socket 往返、超时、参数错先判、连不上、内核回错、unknown action |

### 3. 验证（本机全绿）

内核单测 **42 → 50/0**；shell 兼容通过；运行时回归 116/0；模块自测 43/0；离线包 28/0。
交付：模块 **1.0.40**（`/sdcard/Download/sunsetlinux-module-1.0.40-bare.zip`，装完重启后
`cat /data/sunsetlinux/run/ctl-status.json` 应当是一行 `{"ok":true,..."state":...}`）。

### 4. 下一轮（P2 主线）

1. 收 1.0.40 的开机自检结果（`run/ctl-status.json` + service.log 里那行 rc）；
2. **App 切到 socket submit**：`LinuxCtl.start()/stop()` 在"内核在线"时走 `su -c … Ctl submit …`
   + `Ctl wait <id>`，否则退回 v1 命令（D4 的降级路径必须留着）；
3. 事件流（`subscribe`）—— App 不再轮询；
4. 免 root 版（D2 前台服务）让 proot 也有常驻内核，App 的 socket 路径才能对它也生效。

## 第 51 轮（2026-09-18 10:xx）：DSH 网页改为**覆盖整窗** + 沉浸 + 悬浮「返回壳」（App 0.3.15）

**用户诉求（原话）**：「调整 DSH 的 web 页面渲染（网页渲染逻辑）在应用"壳"上渲染逻辑调整，
原来的约束在应用内改为**覆盖应用全屏渲染显示**，允许返回"壳"（无论免 root 还是 root 模式都存在这类设计问题）。」

### 1. 改之前是什么形状（三层 chrome）

DSH 是外壳里的一个 tab，**夹在顶栏与底栏之间**（`AppShell` 里还专门写着"底栏放内容下方
（不遮挡网页）"），通知栏入口的 `DshWebActivity` 又用 `safeDrawingPadding()` 内缩 + 顶栏一行"返回"。
加上网页自带的头部，真机上整块网页应用只剩屏幕中间一条。**root / 免 root 是同一个 App 的
同一段渲染代码** —— 所以这是"两类模式都有"的同一个问题，不需要按 edition 分叉。

### 2. 改之后

浮层（铺到屏幕四边、含系统栏之下）+ 收起系统栏 + 常驻悬浮「返回壳」键；
返回语义不变（网页有历史先回网页历史，否则回壳，跟手）。

| 文件 | 作用 |
|---|---|
| `ui/DshFullscreen.kt`（新） | 纯策略：`coversWindow` / `hidesSystemBars` / `needsBackAffordance`（判据只有一处） |
| `ui/ImmersiveBars.kt`（新） | 窗口级沉浸（离开必须恢复，否则壳其它页停在沉浸态） |
| `ui/DshWebPane.kt` | `fullBleed` 形参 + 悬浮「返回壳」键（唯一避开系统栏的控件） |
| `ui/AppShell.kt` | DSH 改浮层（在 `MessageBanner` **之前** ⇒ 失败提示仍浮在网页之上） |
| `DshWebActivity.kt` | 通知栏入口同形状 |

### 3. 同类项目的借鉴（用户问了"有没有这类问题"）

- **KernelSU 自己也踩过**：PR #3190 把 manager 的 WebUI 宿主重构成 Compose，并把
  `enableInsets` 改名 `enableEdgeToEdge` —— 宿主一旦用 inset 内缩，模块网页同样只剩中间一块。
- **Android 官方为 WebView 的窗口边衬区单开一页**：WebView 不会自己处理系统栏 inset，
  边到边之后要么内容被压、要么宿主必须喂 inset。
- **Chromium 的 WebView 全屏文档**：网页里的 HTML5 `requestFullscreen` **不会自己生效**，
  宿主必须实现 `onShowCustomView/onHideCustomView`。
- 通病都是"全屏之后怎么回去"（Andronix 的 AndroVNC 这类 Web-as-app 也一样）。

★ **还没做的一格**：HTML5 全屏（`onShowCustomView/onHideCustomView`）没接 —— 网页里点"全屏"
现在不会铺满。这与"网页覆盖 App 窗口"是两件事，做法有 Chromium 的标准路径，列进下一步。

### 4. 回归与交付

App 单测 **278/0**（新增 `ui/DshFullscreenContractTest` 7 条；`UiInsetsContractTest` 的 inset
委托表补登 `DshWebActivity.kt → ui/DshWebPane.kt`，并写清"整屏刻意不内缩，只给悬浮键留 inset"）。
App 版本 **0.3.15（versionCode 31）**；模块不变（1.0.40）。

### 5. 下一步

1. HTML5 全屏（`onShowCustomView`）+ 「全屏时隐藏悬浮键、滑动唤出」这类细节；
2. P2 主线：App 的 `start/stop` 切到 `su -c … Ctl submit …` + `Ctl wait`（内核在线时），
   否则退回 v1 命令 —— 客户端地基（模块 1.0.40 的 `Ctl`）已经就位；
3. 事件流 `subscribe`；免 root 前台服务（D2）。

## 收束（2026-09-18 10:3x，换对话框前）—— 现状、待你反馈、下一步

### 0. 换对话框后，按这个顺序读（5 分钟恢复上下文）

1. **本节**（当前落点 + 待办）；
2. `docs/STATUS.md` **§3.10.50 → §3.10.52**（最近三件事：P1 真机闭环 / 发布路径静默残缺 / DSH 网页全屏渲染）；
3. `docs/decisions-core-v2.md`（这一轮新增：**A10** 设备运行时事实优先于 SDK 桩、**B1** 通道分层、
   **B11** 客户端也必须走 LocalSocket、**C3** 发布路径缺东西一律红）；
4. `app/VERSION-NOTES.md` 最后一行（App 0.3.15 改了什么）。

### 1. 现在的事实（都有证据，别凭印象）

| 项 | 状态 |
|---|---|
| 内核 v2 · P1 | ✅ **真机闭环**（模块 1.0.39 实测）：`run/control-transport=android-local`、`run/control-selftest=ok:android-local`、`control.sock` 存在且 `srw-------`、`state.json`=`running/foreign-observer/generation=1`、`sunsetd` 常驻 |
| 发布路径 | ✅ 修好（1.0.39 起，CI 发出去的模块包真的带内核 dex；`mkmodule.sh` 缺 dex **直接拒绝**；APK 内嵌那份也验过） |
| P2 地基 | ✅ 模块 **1.0.40**：宿主侧客户端 `Ctl`（status/ping/version/submit/job/wait/raw，退出码 0/2/3/4/5）+ 开机客户端自检；内核单测 **50/0** |
| DSH 网页渲染 | ✅ App **0.3.15**：覆盖整窗 + 沉浸系统栏 + 常驻悬浮「返回壳」；App 单测 **278/0**；Release v0.3.15（6 APK）已发 |
| 设备现状 | App **0.3.15 已装**（versionCode 31，18:16）；模块仍 **1.0.39**（1.0.40 未装）；`run/ctl-status.json` 尚不存在（那是 1.0.40 才写的） |

### 2. 待你做的两件事（都不阻塞后续开发）

1. （推荐）装模块 **1.0.40** + 重启，回两行：
   `cat /data/sunsetlinux/run/ctl-status.json`（期望 `{"ok":true,…,"protocol":1}`）
   `tail -3 /data/sunsetlinux/run/service.log`（找 `内核客户端自检（Ctl status）：rc=0`）
   —— 验的是 **P2 要走的那条客户端路**（App 以后用 `su` 起同一个客户端提交作业）。
2. 试 App 0.3.15 的 DSH 全屏：顶栏 DSH 图标 → 网页铺满（含系统栏之下）、左上角「返回壳」、系统返回也能回。
   有问题就把现象告诉我（截图/描述均可）。

### 3. 下一步（P2 主线，地基已就位）

1. **App 的 `start/stop` 切到 socket**（`app/app/.../core/LinuxCtl.kt`）：
   内核在线 ⇒ `su -c "…app_process … io.github.sunsetrne.sunsetd.Ctl submit <action>"` 拿 jobId，
   再 `Ctl wait <jobId> <秒>`；**连不上（rc=3）或任何异常 ⇒ 退回 v1 命令**（D4 的降级路径必须留着）。
   `status` 继续走 `linuxctl`（内核的 `status` op 是内核形态，UI 要的是 v1 形态 —— 这两者别混）。
   目的：把"命令永不返回"那类事故与 App 解耦（作业归内核所有，App 挂了作业照样跑完）。
2. HTML5 **网页内**全屏（`WebChromeClient.onShowCustomView/onHideCustomView`）—— 见 §3.10.52 末段。
3. 事件流 `subscribe`（App 不再轮询）；免 root 前台服务（D2，让 proot 也有常驻内核）。
4. 遗留小项（不影响功能，但"我验的就是发出去的"这条不成立）：
   `tools/build-sunsetd-dex.mjs` 找 kotlin-stdlib 是"取 Gradle 缓存里最新的" ⇒ 本机与 CI 可能选到
   不同版本，同一个 commit 打出的 dex 字节不同（1.0.39 实测：本机 2,508,864 B / CI 2,527,684 B）。
   修法：优先取 `KOTLIN_VERSION` 那个版本，取不到再退"最新"。

### 4. 这一轮的坑（别再犯，都写进 §3.10.4x 了）

- **SDK 桩 ≠ 设备运行时**：设备 ART 里 `ServerSocketChannel.open(ProtocolFamily)` **没有**、
  `SocketChannel.open(UnixDomainSocketAddress)` **也不通** ⇒ 服务端与客户端都只能 `android.net.LocalSocket`。
  桌面单测看不见这条差异（34 条全绿照样在真机崩）。
- **发布路径的"优雅降级" = 静默发残缺产物**：`|| echo "::warning::"` 让三个打包路径都没带内核 dex，
  发出去的模块装上后内核静默消失（与 0.2.6 同款）。
- 跑 App / 内核单测必须 `LANG=C.UTF-8 LC_ALL=C.UTF-8`（中文测试方法名 + Gradle 守护进程 ASCII locale 会炸）。
- 动 UI inset 前先看 `UiInsetsContractTest` / `ShellLayoutContractTest` / `DshFullscreenContractTest`
  （源码级契约，CI 会拦；不是形式主义，每条都对应一次用户看得见的问题）。


---

## ★★ 收束 · 换对话框前的最终落点（2026-09-18 14:2x）

### 一句话

**代码侧全部完成并已推送**（main 到 `183d8d8`，工作区干净）；**只差流水线跑完**——
`v0.3.16`（6 个 APK + 模块 1.0.41 + 5 个离线包）还没落地，两轮流水线正在排队/运行中。

### 已经**上线可验证**的（不必等我）

| 事项 | 怎么验 |
|---|---|
| 官方频道 = runtime 1.0.1 + dsh 0.1.6-alpha.2 | `curl -s https://sunsetrne.github.io/SunsetLinux/channel/channel.json \| grep -o '"version":"[^"]*"'`（应见 `1.0.1` 与 `0.1.6-alpha.2`） |
| 6 个层 URL 都能下 | 上面清单里每个 `url`/`url_gz`，`curl -sL -o /dev/null -w '%{http_code}' -r 0-0 <url>` 应为 **206** |
| 清单签名 | `bash tools/channel/sunsetlinux-channel verify --pub <公钥> --in channel.json --no-layers`（指纹 `ed25519:06:d0:c4:4d:29:1c:ef:66`） |
| 层托管 Release | `layers-20260916` 里 `dsh-0.1.6-alpha.2.erofs.{zst,gz}`、`runtime-1.0.1.erofs.{zst,gz}` |
| 仓库闸门 | App 单测 **278/0**（含 `LayerDecompressorTest` 13/13 真解 443 MB 层）、`cmp-consistency` 16/16、`shell-compat` ✓、`ci-changeset-selftest` 39/0、`offline-bundle selftest` 28/0、`runtime/root/selftest.sh` **122/0**、`runtime/proot/selftest.sh` 20/0 |

### 换对话框后**第一件事**

看这两条流水线（14:02 与 14:06，后者排在队列里）——`⑤ 合并 + 发布 / publish` 是否绿：

- 绿 ⇒ 抽查 v0.3.16 的离线包头：`curl -sL -r 0-4095 <SunsetLinux-0.3.16-root-full.bin 的 Release URL> | strings | grep dsh_version`
  **应为 `0.1.6-alpha.2`**（这就是"内置目标版本"的最终证据）。
- 红 ⇒ 先看**失败注解**（`check-runs/<job>/annotations` 里有"日志尾部"）：
  本轮已修掉两个同类 100 MB 硬限问题（层 `.gz` 走分片；模块 zip 只挂 Release），
  若还有第三个，照 §"发布路径"那条思路处理：**先问"谁在读它"，没有读者的就别往 git 分支里放**。

### 待办（按优先级，都不阻塞已上线的东西）

1. ~~**B 的第二半**（用户已选 B）：模块改内嵌 **`.zst`**（115 MB → ~79 MB）~~ ⇒
   **此条已作废**（2026-09-18 真机实测）：展开模块载荷的是**宿主侧** shell，那里没有 `zstd`、
   也跑不了层里的 glibc node（要 `/lib/ld-linux-aarch64.so.1`，Android 宿主没有）
   ⇒ 模块载荷**必须保持 `.gz`**。真想让模块吃 `.zst`，前提是随模块发一个**静态**解压器
   （NDK 静态编译，约 1 MB）——那是个独立决定。见 `docs/STATUS.md` §3.10.55 ②。
   （(b) 的另一半"CLI 能解 `.zst`"保留，但当时是**死代码**，已在本轮修好：§3.10.55 ③）
2. **真机端到端**：把两个新层更到手机、用 App 的 WebView 打开 0.1.6 的界面
   （移动端是**客户端**插件：服务端起得来 ≠ 浏览器里没 JS 报错；已验的是 import 4/4、
   页面模块清单含 `dsh-web-mobile/client.js`、注入的 client 包全是 0.1.6-alpha.2）。
3. `libreoffice-kit-wasm`（186 MB）**按用户裁定不裁**；它现在也跑不了（环境里 0 个字体），
   要真开 Office 预览得先补字体 —— 那是另一个数量级的决定。
4. `docs/findings.md` 的层体积表还停在 0.1.5-rc.2 那一行（历史实测，未改；新数在 STATUS §3.10.53）。

---

# 本轮：DSH 目标版本 → **0.1.6-alpha.2**（2026-09-18 11:xx 起，同一对话框）

**用户原话**：「移植性推进：内置目标版本 `0.1.6-alpha.2`，频道同步更新。继续推进相关项目进度。」

## 1. 一句话落点

上游 `alpha` 通道的 `@deepseek-ai/dsh@0.1.6-alpha.2` 已**在本机真 rootfs 里跑通**
（`dsh --version` = 0.1.6-alpha.2、插件自检 4/4 ok、`dsh web` 无插件错误、鉴权链路 401→303）。
三处"不改就失败"的坑已修进脚本（详见 `docs/STATUS.md` **§3.10.53** 与 `docs/dsh-profile.md` **§7**）：

1. `build_dsh` 的 `npm i -g` **必须显式 `--prefix /usr/local`**（runtime 层里根本没有 npmrc，
   只有"只重出 dsh 层"的增量路径会踩，报错点还离原因很远）；
2. `--allow-scripts=<5 个包>` + 装完核对"npm 是否还有未放行的 install 脚本"，
   否则 npm 11.7+ 会**静默跳过** node-pty 等原生件的安装脚本；
3. ★ `dsh web` 必须用 **`node --expose-internals`** 启动（0.1.6 起 `dsh-base` 补丁里多了
   `hmr` 条目，HMR 服务硬要求这个 flag）。两个启动点都改了：
   `runtime/root/supervise.sh`（真 root）与 `runtime/proot/entry.sh`（免 root）。

顺带修了两个把自己人气死的假警报：profile 软链包数（`find` 缺 `-L`，260 → 报 0）、
层间删除检测把 `EXCLUDES` 里本来就不进层的路径算成删除（每次构建必误报一次）。

## 2. 产物与频道（本轮交付）

| 产物 | 内容 | 说明 |
|---|---|---|
| `runtime-1.0.1.erofs.{zst,gz}` | **只改了 `/opt/sunsetlinux/supervise.sh`** | 版本必须 +1：内容变了而版本不变，会造成"有人更新了、有人没有" |
| `dsh-0.1.6-alpha.2.erofs.{zst,gz}` | DSH 0.1.6-alpha.2 + profile 工作区 | 现有 profile 配方**不用改**（实测） |
| `channel.json` / `.sig` | base 不变、runtime 1.0.1、dsh 0.1.6-alpha.2 | 层文件仍在同一个 `layers-20260916` Release（单一 base-url，用户只下变了的层） |

用户侧这次的更新量：**runtime ≈ 47 MB + dsh ≈ 79 MB**（base 不变）。

## 3. 必须让你拍板的两件事（我没有替你决定）

1. **dsh 层从 201.5 MB 涨到 443.7 MB**（下载 `.zst` 31.2 → **79.1 MB**）。
   原因单一：上游 0.1.6 新增 `@deepseek-ai/libreoffice-kit-wasm`，**一个包 186 MB**
   （侧边栏 Office 预览的 WASM）。**本轮按"不裁"发**——裁了它，dsh 层就不再等于 npm 上那个版本，
   `layer-spec.sh` 的"层版本 == 包版本"不变式要另立条款。要裁就说一声，我加一个显式开关
   （并让层版本带后缀、文档写清"Office 预览不可用"）。
2. **要不要顺带发一版 App**（内置目标版本要进 APK 才叫"内置"）：频道路径已经让**已装的用户**
   能更新到 0.1.6-alpha.2；但"完整离线版"APK 里内嵌的那份要等下一次 App 发布
   （`pipeline.yml` 的②节点会**从频道**拉层重打离线包，所以只要频道对了，下一次发布自动就是新版本）。

## 4. 还没做/待验证（别当成已完成）

- 真机端到端：把两个新层 `linuxctl update` 到手机上、用 App 的 WebView 打开新界面
  （移动端插件是**客户端**插件，服务端起得来 ≠ 浏览器里没 JS 报错；
  0.1.6 改过客户端 Session/slot API，这是**唯一**还没验的一环）。
- `testdata/channel/` 的真实清单快照要等频道发出去之后用线上那份替换（含 `.sig`）。
- `LayerDecompressorTest` 里钉的 dsh 层文件名/sha256/size 要换成新的产物（不换就整类跳过）。

## 5. 补充（同轮发现的两件"交付链"事实，别漏）

1. **改了 `/opt/sunsetlinux/*.sh` 不等于用户就拿到了**：`runtime/root/start.sh` 的
   `install_runtime_entry()` 每次启动都会把 **KernelSU 模块 `bin/` 里的 entry.sh + supervise.sh
   拷进 rootfs**（优先级：模块目录 → 脚本同目录 → `$LINUX_HOME/bin`），
   而这份拷贝落在**可写上层**、会盖住只读层里的同名文件。
   ⇒ `--expose-internals` 这个修复**必须同时出模块 1.0.41**（本轮已做：`module/module.prop`
   → `v1.0.41`/`10041`，本地已打出 bare 包并核对 `bin/supervise.sh` 带 flag）。
2. **免 root 模式的启动器走的是另一条分发路径**：`runtime/proot/entry.sh` 随
   `assets/proot-runtime/` **进 APK**（不在模块里、也不在 proot bundle 里）。
   ⇒ proot 用户要跑 0.1.6 必须有**一次 App 发布**（本轮已 bump 到 0.3.16/32）。
   proot bundle（`proot-bundle-arm64.tar.gz`）里只有 proot 二进制与许可证，**不含这些脚本**，
   而且本机的 `/usr/bin/proot` 是 5.1.0（已发布的是 5.4.0）——**不要用本机重打这个包**，
   会把 proot 降级。

## 6. 用户本轮明确的选择

- **不裁剪 `@deepseek-ai/libreoffice-kit-wasm`**（用户 2026-09-18 原话：「不裁剪」）：
  dsh 层保持 443.7 MB / 下载 79.1 MB，与 npm 上的 `0.1.6-alpha.2` 逐包一致。
  ⇒ 那个"要不要裁"的开关**不做**；后续若要省体积，先补字体（否则 Office 预览本来就不可用，
  见 §3.10.53 与本文件上一节的实测）。

---

## ★ 换对话框前 · 当前落点（2026-09-18 13:3x）

**一句话**：`0.1.6-alpha.2` 的三件产物（runtime 1.0.1 / dsh 0.1.6-alpha.2 / 模块 1.0.41）都已产出并逐项验证，
层已经上了 Release，**频道清单正在上线**；App 尚未发布（0.3.16 已 bump，等推 main 触发流水线）。

### 已经落地（可复查）

| 事项 | 证据 |
|---|---|
| dsh 层 0.1.6-alpha.2 | `/var/tmp/layers-out-v2/dsh-0.1.6-alpha.2.erofs`（443,654,144 B，sha256 `1f1e5dd5…`）；`.zst` 79,078,686（窗口 8 MiB ✓）／`.gz` 114,833,160；三者解压 sha256 一致 |
| 层内真跑 | 走**设备同一条** `supervise.sh`：`run/dsh.url` 有令牌 → 裸 `/` **401** → token **303** → cookie **200**（32160 B），页面里出现 `dsh-web-mobile` |
| runtime 1.0.1 | `/var/tmp/layers-out-clean/runtime-1.0.1.erofs`（245,313,536 B，sha256 `d0977ce6…`）；只改了 `supervise.sh`（`--expose-internals`） |
| 频道清单（本地已签） | `/var/tmp/publish-channel/channel.json` + `.sig`；`verify --strict` 与 `publish-check` 全绿；`gen-manifest` 已联网核对 `alpha → 0.1.6-alpha.2` |
| 层托管 | `layers` 分支已推（`5e31099`），CI run「层托管」**success**；Release `layers-20260916` 里 `dsh-0.1.6-alpha.2.erofs.{zst,gz}` 与 `runtime-1.0.1.erofs.{zst,gz}` 都可下载（206），大小与本地逐字节一致 |
| 模块 1.0.41 | 本地打出 bare 包并核对 `bin/supervise.sh` 带 `--expose-internals`；`module/module.prop` → `v1.0.41`/`10041` |
| 仓库闸门 | `cmp-consistency` 16/16、`shell-compat-check` ✓、`ci-changeset-selftest` 39/0、`offline-bundle selftest` 28/0、`runtime/root/selftest.sh` **116/0**（2 skip） |

### 正在飞（换对话框后第一件事就是看它）

1. `channel` 分支已推（`d733966`）→ `channel.yml` 正在签名 + 发 gh-pages `/channel/`。
   **验收**：`curl -s https://sunsetrne.github.io/SunsetLinux/channel/channel.json | grep -o '"version":"[^"]*"'`
   应出现 `0.1.6-alpha.2` 与 `1.0.1`；再用 `tools/channel/verify.mjs --pub … --url` 复核签名。
2. **main 还没推**：推 main 会跑流水线（APK 0.3.16 + 模块 1.0.41 + 离线包），
   而离线包是**从频道拉层**的 ⇒ **必须等第 1 步线上生效后再推**（否则新 APK 里会嵌旧 dsh）。
3. `testdata/channel/{channel.json,channel.json.sig}` 还没换成线上那份（等第 1 步完成后照抄）。

### 本轮踩到并已修进脚本的 5 条（细节见 STATUS §3.10.53 / dsh-profile §7）

1. `build_dsh` 的 npm 必须显式 `--prefix /usr/local`（runtime 层里没有 npmrc）；
2. npm 11.7+ 静默拦截 install 脚本 ⇒ `--allow-scripts=<5 包>` + 装完核对，有漏就红；
3. `dsh web` 必须 `node --expose-internals`（0.1.6 的 HMR 硬要求）——真 root 与 proot 两个启动点都改了；
4. 两处假警报（`find` 缺 `-L` 的包数、删除检测没按 EXCLUDES 过滤）；
5. `verify_erofs_profile` 在 `--skip-dsh`（只重出 runtime 层）时会**无声中止整条构建**——已加"没有 profile 就跳过"。

### 本轮新发现的结构性问题（待你拍板，我没动）

- **GitHub 单文件 100 MB 硬限撞上了**：0.1.6 的 dsh `.gz` = 109.5 MB，直接推 `layers` 分支被拒
  （`GH001: Large files detected`）。绕法已实现并跑通：把 `.gz` 分片（`.part-aa/ab`）过 git，
  **在 CI 里拼回整份再当 Release 资产上传**（`layers-release.yml` 新增一步；
  实测线上资产 114,833,160 B 与本地一致）。**这是权宜之计**——正解二选一：
  (a) 把 `libreoffice-kit-wasm` 拆成独立层（每层 `.gz` 都回到 100 MB 以下）；
  (b) 让 CLI 侧能解 `.zst`（runtime 层有 node，仓库已自带 Node 版 zstd 过滤器），
      这样 `.gz` 只留给"node 还不存在"的 base/runtime。

### 补（同轮）：两条**启动器契约**回归断言

`--expose-internals` 这个修复太容易被"顺手简化"掉，而症状是"环境起来后 DSH 立刻退出"。
所以在两个自测里各钉了一条静态断言（真 root 的 `supervise.sh`、免 root 的 `entry.sh`）：

- `runtime/root/selftest.sh` → **118 通过 / 0 失败**（新增 2 条）
- `runtime/proot/selftest.sh` → **20 通过 / 0 失败**（新增 2 条）
- 反面验证：把 `--expose-internals` 从脚本里删掉，断言会判红 ✓

### 补（同轮）：发布路径的第二个 100 MB 硬限 —— **模块 zip**

13:46 那轮流水线**红在最后一步**（`gh-pages` 推送被拒），实测原文：

```
remote: error: File stable/sunsetlinux-module-1.0.41.zip is 109.69 MB;
              this exceeds GitHub's file size limit of 100.00 MB
 ! [remote rejected] gh-pages -> gh-pages (pre-receive hook declined)
```

根因链：模块 **full 变体内嵌 dsh 层** → dsh 层 0.1.6 涨到 109.5 MB →
模块 zip 变成 **115.0 MB** → 站点（gh-pages，git 分支）拒收 → **整条发布失败**
（连 `发布到 GitHub Releases` 那一步都被跳过 ⇒ **0.3.16 根本没发出去**）。

**修法（本轮已改，未依赖任何新机制）**：站点上那份模块 zip **本来就没人读** ——
App 的 `ModuleUpdate` 拼的是 `releases/download/<tag>/<name>`（退 `releases/latest/download`），
只有下载页的链接还指着站点。所以：

1. `publish.yml` 合成站点树时**也排除 `*.zip`**（与 `.apk` / `.bin` 同样只挂 Release）；
2. 下载页的模块链接改为 Release 绝对地址（与 APK 用 `apk_url` 同理）；
3. 加一条**发布前自检**：站点树里出现 >99 MB 的文件就直接报红并列出尺寸，
   而不是等 `git push` 用 `GH001` 拒收（那时只有一个文件名，看不出"该挂 Release"）。

> 这与用户选的 **(b) 让 CLI 侧能解 `.zst`** 是两件事，但方向一致：
> (b) 做完之后模块可以内嵌 **`.zst`（79 MB）** 而不是 `.gz`（109.5 MB），
> zip 自然回落到 ~79 MB，连"排除"都不再必要 —— 那一步留到 (b) 落地时一起做。

### 补（同轮）：选项 (b) 已落地 —— 设备侧能解 `.zst`

用户 2026-09-18 明确选择 **(b) 让 CLI 侧能解 `.zst`**。已实现并自测：

- `runtime/root/update.sh`：`.zst` 解压优先级 = ①系统 `zstd` → ②**环境里的 node**
  （`find_node` + 同目录 `zstd-filter.mjs`；Node 24 的 `node:zlib` 自带 zstd；用前先 `node -e '1'` 探活，
  Android 宿主上跑不了层里那个 glibc node 就走 ③，绝不"下完才发现解不开"）→ ③明确拒绝 + 三条出路。
- 过滤器**唯一一份**在 `tools/seed/zstd-filter.mjs`，三条交付路径都铺它：
  `rootfs/build-layers.sh`（→ runtime 层 `/opt/sunsetlinux/`）、`module/mkmodule.sh`（→ 模块 `bin/`）、
  `rootfs/device-provision.sh`（设备侧自建层）。
- `runtime/root/selftest.sh` 新增 4 条断言（含 **node 实跑一次 zstd 往返**）：**122 通过 / 0 失败**。

边界：base/runtime 仍只发 `.gz`（自举期没有 node ⇒ "两种都发"不变）；
dsh 这类"装它时 node 一定在"的层，CLI 现在可以走 `.zst`（79.1 MB 而不是 109.5 MB）。

---

# ★★ 第 52 轮（2026-09-18 14:xx，换对话框后）：核验 v0.3.16 ⇒ 三条「看着完成了、其实没生效」

## 一句话

**换对话框前那两条流水线都绿了、v0.3.16 确实发出去了**（离线包实测内置 `dsh 0.1.6-alpha.2` +
`runtime 1.0.1`）；但顺着核验扒出**三条"以为完成了、实际没生效"**，其中两条已修，
第三条（要不要发一版把 (b) 真正推出去）**等你拍板**。

## 1. 核验结果（都有实物证据）

| 事项 | 结论 |
|---|---|
| run 55（`a571ddf`）/ run 56（`183d8d8`） | **都 success**；`releases/latest` = **v0.3.16** |
| 6 个 APK + 6 个离线包 + 模块 1.0.41（full/bare） | 都在 Release 上 |
| 离线包头里的内置版本 | `root-full` / `proot-full` 实测 `dsh_version=0.1.6-alpha.2`、`runtime_version=1.0.1`、`base=24.04.3-l1` ✓ |
| **已发布**模块 zip 里的启动器 | 用 HTTP range 抠出 `bin/supervise.sh` 单独 inflate：启动行确实是 `node --expose-internals <dsh> web`（9 处提及）✓ |

## 2. 三条「没生效」

1. **run 56 绿了但一个字节都没发**：Release 的 `index.json` 写着 `run_number=55`、`commit=a571ddf`。
   发布闸门是"**版本号变了才发**"（`tools/ci-changeset.mjs:142`），183d8d8 一个版本号都没动。
   ⇒ **选项 (b) 当时根本没到设备**。（教训第二层：推 main ≠ 发出去了。）
2. **模块内嵌 `.zst` 这条路不成立**（已作废，见上面待办 1）：宿主侧没有 `zstd`（toybox 也没有）、
   层里的 node 是 glibc 二进制而 Android 宿主没有 `/lib/ld-linux-aarch64.so.1`，
   而展开载荷的 `customize.sh` 正在宿主侧。
3. **而 (b) 真落地的那一半是死代码**：验签器的产物选择写死"优先 `url_gz`" ⇒ 新加的
   `.zst` 解压能力在任何机器上都不会被走到。

## 3. 本轮改了什么（都在工作区，未推）

| 文件 | 改动 |
|---|---|
| `runtime/root/update.sh` | 产物选择**跟随本机能力**：系统 `zstd`（`SUNSET_HAVE_ZSTD` 传入）或 node 自带 `node:zlib` zstd ⇒ 挑 `.zst`，否则退 `.gz`；`SUNSET_NO_ZSTD=1` 可强制 `.gz`（排障 + 可测）。`node:zlib` 用**默认导入**探测（老 Node 上命名导入会 SyntaxError，把整个验签器带走） |
| `runtime/root/selftest.sh` | 新增 4 条断言（含**反面断言**：不许回到写死 `url_gz` 的老写法）⇒ **126 通过 / 0 失败** |
| `module/mkmodule.sh` | `.erofs.gz` 白名单保留，但把"为什么不能收 `.zst`"写进报错与注释（含真机证据） |
| `docs/STATUS.md` | 新增 §3.10.55（三条发现 + 验证矩阵）；§3.10.54 的"模块也可改内嵌 .zst"标注作废 |

**验证力度**（不是桩）：用线上那份**真签名**清单（`file://` 直读）跑真验签器，三条分支各自对拍；
再用**真产物**跑过滤器：79,078,686 B 的 `.zst` → 443,654,144 B，sha256 `1f1e5dd5…` 与裸镜像
逐字节一致，**16 秒**（流式）。闸门：`shell-compat` ✓、`cmp-consistency` 16/16 ✓、
`module-variant-selftest` 43/0 ✓、`runtime/root/selftest.sh` 126/0 ✓。

## 4. ~~待你拍板~~ ⇒ 已裁定（见文末「## 5」）

**要不要发一轮把 (b) 真推出去？** 三个版本号得一起动（只动一个会造成"App 版本没变但 APK 字节变了"）：

- **App 0.3.17**（内嵌的 bare 模块 zip 变了 ⇒ APK 字节变，必须跟着动）
- **模块 1.0.42**（`bin/update.sh` + `bin/zstd-filter.mjs`；现装的 1.0.41 实测**没有**后者）
- **runtime 层 1.0.2**（`update.sh` 就在 runtime 层里，它改了而层版本还是 1.0.1 = 同版本两种内容）

发出去后用户侧能拿到：CLI/WebUI 更新 dsh 时走 `.zst`（109.5 MB → 79.1 MB）。

**另一件（随时可做，需要你在手机边上）**：真机端到端 —— 手机现在还装着 runtime 1.0.0 /
dsh rc.1+rc.2 / 模块 1.0.41，把三个新层更上去，用 App 的 WebView 打开 0.1.6 界面
（移动端是**客户端**插件，服务端起得来 ≠ 浏览器里没 JS 报错，这是唯一还没验的一环）。

## 5. 用户裁定与本轮发布（2026-09-18 14:5x）

用户两条原话：**「现在就发（App 0.3.17 + 模块 1.0.42 + runtime 层 1.0.2）」**；
关于真机：**「随着模块更新验证，改环境容易报错，被厂商限制」**（⇒ 不手工改环境，走正常更新路径）。

**实际发的是 v0.3.17：App 0.3.17/33 + 模块 1.0.42/10042；runtime 层没重出、频道清单不变（仍 1.0.1）。**
偏离"三层一起动"的理由（有证据，不是图省事）：

1. **生效的 `update.sh` 在模块里**：`module/post-fs-data.sh` 每次开机把模块 `bin/` 同步到
   `$LINUX_HOME/bin/`，而 `linuxctl.sh` 调的就是 `$SELF_DIR/update.sh`；`start.sh` 的
   `install_runtime_entry()` 只从模块拷 `entry.sh` + `supervise.sh`。⇒ 模块 1.0.42 一个人就把 (b) 送到设备。
2. **重出 runtime 层 = 让所有用户白下 50 MB**（层变了离线包也得跟着重打），而层里那份 `update.sh`
   只是极少走到的兜底路径 ⇒ 行为收益 0、代价实打实。
   （另：本容器**没有 chroot**，重出层只能复用 `/var/tmp/sl-runtime-clean` 的树重打包；
   树还在，真要发随时能重打 —— 但**发之前必须把 runtime 版本号 +1**。）
3. "同版本两种内容"的风险本轮消解：**没有重出 1.0.1**，线上仍是那次一致构建的产物。

**顺带记录一个"静默吞改动"的结构问题（只记录，未改构建脚本）**：dsh 层里有 5 个**冗余的入口脚本副本**
（`doctor.sh`/`entry.sh`/`linuxctl.sh`/`oneshot-setup.sh`/`selftest.sh`，与 runtime 层实测**逐字节相同**；
`update.sh`、`supervise.sh` 不在其中）。dsh 在栈顶 ⇒ **这 5 个文件以后在 runtime 层里怎么改都不会生效**
（本轮 `selftest.sh` 已经出现新旧不一致：dsh 层里是 91559 B 的旧版）。根治要让 dsh 的增量打包**排除**
`$LAYER_RUNTIME_ENTRY_DIR`，而 dsh 层版本 == npm 包版本（不能凭空 +1）⇒ **留到下一次 dsh 升级时一起修**。

**下一步（按优先级）**：

1. 收 v0.3.17 的发布结果：`releases/latest` 应指向 v0.3.17；并抽查
   `sunsetlinux-module-1.0.42.zip` 里**确实有** `bin/zstd-filter.mjs`（上一版 1.0.41 就没有）。
   办法不必下 115 MB：HTTP range 抠 zip 中央目录 + 单文件 inflate（`/root/Q/verify-module-zip.mjs`）。
2. 真机：按用户要求**随模块更新走** —— 在 App 里把模块更到 1.0.42、重启，再看 0.1.6 的 WebView
   （移动端是**客户端**插件，服务端起得来 ≠ 浏览器里没 JS 报错）。
3. runtime 层若以后要重出：记得版本 +1，并顺手修上面那条 dsh 层重复副本的问题。
