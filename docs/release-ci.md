# 发布与 CI：分支式、回归门禁、不建 tag

> 本文确定发布模型并说明为什么这样设计。
> 一句话：**推送分支即发布**；`beta` 出预发布、`main` 出正式；每次发布前先跑**全量回归**，绿了才发。

---

## 一、为什么"不用 tag"要换个分发形态

**GitHub Releases 在机制上必须挂在 tag 上** —— 所以"不建 tag + 要给人下载"这两件事，
只能用别的载体。可选的有三种：

| 载体 | 需要 tag？ | 用户下载体验 | 适合 |
|---|---|---|---|
| GitHub Releases | **需要**（哪怕是 `latest` 这种移动 tag） | 最好（有页面、有说明） | 有 tag 的流程 |
| **`gh-pages` 分支（GitHub Pages）** | **不需要** | 好（固定 URL，浏览器直接下） | ★ 本方案 |
| Workflow Artifacts | 不需要 | 差（要登录、90 天过期） | 仅内部调试 |

**采用 `gh-pages` 分支**：CI 把产物推到该分支的固定目录，网页与频道都从同一处取。
这同时解决了"频道 URL 从哪来"的问题 —— **频道就是 Pages 上的一个静态文件**。

> ℹ️ **更新（2026-09-16）：现在 Releases 页有两类条目** ——
> · `v<App 版本>`（例 `v0.2.4`）= **产品发布**：`release.yml` 在 stable 通道上创建/刷新，
>   资产是 APK / 模块 zip / `index.json` / 校验和，**与 gh-pages 是同一份产物（双发布）**；
>   同一版本重复发布是幂等的（覆盖资产 + 刷新说明），所以只升模块版本不会刷出一堆重复条目。
> · `layers-<日期>` = **层文件的纯存储**（`layers-release.yml` 收集 `.erofs.zst/.gz`），
>   它不是产品版本，别当版本号用。
>
> 但**发布版本的唯一事实源仍然是 gh-pages 的 `/stable/`、`/beta/`**：
> `index.json` 里的 commit / run_number / 各文件 sha256 是校验与脚本读取的入口。
> 用户（和未来的你）第一反应是点仓库的 "Releases"，那里什么都看不到；真正的下载页是：
>
> - 站点根：`https://sunsetrne.github.io/SunsetLinux/`（**2026-09-16 起有页面**了，链到两个通道）
> - 正式：`https://sunsetrne.github.io/SunsetLinux/stable/`（`index.html` 由 CI 每次发布生成）
> - 预发布：`https://sunsetrne.github.io/SunsetLinux/beta/`
>
> 这两个 `index.html` 是 `release.yml` 的「生成下载页」步骤写的：列出 APK / 模块 zip /
> `index.json`，带版本、commit、构建时间与每个文件的 sha256，并给一段"安装顺序"。
> 站点根页**只在 stable 通道生成**（否则 beta 发布会把"当前推荐版本"覆盖成 beta 的）。
> 在它之前，`/stable/` 会被 Pages 当成 `index.json` 返回 —— 浏览器里是一坨 JSON。

```
gh-pages 分支
  /beta/           ← beta 频道
    channel.json
    channel.json.sig
    sunsetlinux-launcher-<版本>.apk
    sunsetlinux-module-<版本>.zip
    base-*.erofs.zst  runtime-*.erofs.zst  dsh-*.erofs.zst
  /stable/         ← 正式频道（结构同上）
  index.html       ← 可选：一句说明 + 两个频道链接
```

频道 URL 形如：
```
https://<用户名>.github.io/<仓库名>/beta/channel.json
https://<用户名>.github.io/<仓库名>/stable/channel.json
```

---

## 二、分支模型

> ℹ️ **2026-09-17 起（决策 3，见 [`module-variants.md`](module-variants.md) §三）**：
> **编译只在 CI 跑，发布只由 `main` 触发**；`beta` 退化成**纯存储**分支
> （要出 beta 预发布只能手动 `workflow_dispatch` 并选 `channel=beta`）。

| 分支 | 定位 | 推送后发生什么 |
|---|---|---|
| `main` | 正式版源码 + **唯一发布触发点** | 跑全量回归 → 通过则发布到 **`/stable/`** 频道 + Release |
| `beta` | **纯存储**（预发布内容） | **不编译、不发布**。要出 beta：手动跑 `pipeline.yml` 并把 `channel` 选成 `beta` |
| `channel` | **只有频道数据**（公有 key/加密私钥备份/说明 + `channel.yml` 本身） | `channel/channel.json` 变化 → 签名 + 独立验签 → 发 gh-pages `/channel/` |
| `layers` | **层产物的传送带**（orphan；只有分发产物 + `layers-release.yml`） | 建/更新 Release 并存资产，打印 `publish-channel` 用的 base-url（见 §5.3） |
| `gh-pages` | **发布产物**（`/stable/`、`/beta/`、`/channel/`） | **CI 独占写**（人或 CI 都只往里放内容） |
| `feat/*`、`fix/*` | 开发 | 只跑**回归门禁**，不发布 |

**合并方向**：`feat/*` → `main`（验收后直接进主线）；`beta` 需要内容时由人 push 上去（它不再触发任何编译）。

---

## 二·五、拓扑：**一条流水线到底 + 两条独立线路**

```
pipeline.yml（**只有推 main 或手动触发时跑**）           ← 2026-09-17 起
  ① prep      环境准备：版本 / 由 variants.json 生成编译矩阵 / 打**模块 zip（bare）** ──▶ 制品 module-zip
  ② bundles   环境准备：内嵌离线包（六个变体各一份 .bin）+ **默认模块（full，自带 DSH）** ──▶ 制品 module-zip-full
  ③ build     编译矩阵（六节点并行，每节点一个组合）──uses──▶ build-apk.yml ──▶ 制品 apk-<组合>
  ④ gates     回归门禁（shell/node/android 三节点并行）──uses──▶ ci.yml（build_apks=false）
  ⑤ publish   合并 → index.json → 下载页 → gh-pages → Release ──uses──▶ publish.yml
  ⑥ channel   频道发布：channel 分支的签名清单 ——验签——▶ gh-pages /channel/
  ⑦ report    汇总（哪一节点红、红在哪条断言）

独立线路（不随每次推送跑）：
  layers.yml（手动，arm64 自建 runner）        构建三层 erofs + gen/sign 频道清单
  layers-release.yml（layers 分支）            只"收集 + 上传"层产物到 Release
  channel.yml（channel 分支 / 手动）           私钥签名 + 公钥独立验签 + 发 /channel/
```

> **模块两变体与"谁内嵌哪一个"**：见 [`module-variants.md`](module-variants.md) §2.6。
> 一句话：**APK 内嵌 bare**（APK 的 base/full 档已有 dsh 层，别塞两遍）；
> **Release 上的默认名 `sunsetlinux-module-<版本>.zip` 只给 full**（自带 DSH）；
> 频道里没有 dsh 层时**不产出默认名**（绝不拿 bare 冒充默认版本），并在 job summary 里说明。

**为什么改成一条**（都是这次真踩到的）：

| 问题 | 以前（三条线靠**时间**对齐） | 现在（靠 `needs`） |
|---|---|---|
| 离线包挂 Release | `offline-bundle.yml` 与 `release.yml` 并行跑，前者**等 6 分钟**看 Release 出现没有，等不到就静默不挂（0.2.5 真发生过） | ⑤ publish 统一建 Release + 收 `dist/bundles/*.bin` 一起挂，顺序由 `needs` 保证 |
| 模块 zip 内嵌 | 模块 zip 是**构建 APK 之后**才打的 → 官方 APK 里没有 `assets/module/`（0.2.12 真发生过，用户点「一键刷入内置模块」看到"未内嵌"） | ① prep 先打模块 zip，③ 每个编译节点都取回来再构建 |
| APK 编译 | 一个 job 里**顺序**编四个组合（约 4 分钟） | 六个节点**并行**，单组合红不影响其它组合出包 |
| 发布逻辑 | 内联在 `release.yml` 里，无法复用 | 搬到 `publish.yml`（可复用），`pipeline.yml` 与手动兜底的 `release.yml` 共用同一份 |
| push 双发 | 推 main/beta 会同时跑 `ci.yml`(经 release) 与 `release.yml` | `release.yml`/`ci.yml`/`offline-bundle.yml` 都**不再监听 push**（保留手动/可复用） |

> ⚠️ 制品契约：编译矩阵每个组合上传 `apk-<组合 id>`，`publish.yml` 用
> `pattern: apk-*` + `merge-multiple` 收（这样 `ci.yml` 独立门禁的单个 `apk-debug`
> 也照样能被它收）。改制品名时两边必须同步。
>
> ⚠️ 手动兜底：`release.yml`（workflow_dispatch）走的是同一批可复用工作流；
> 只想重发不重编就直接手动跑 `publish.yml`。

---

## 二·六、旧拓扑（2026-09-16 之前，保留作对照）

```
线路① 回归门禁 ci.yml          每条推送/PR，三个 job 并行（各占一个 runner）
      ├─ shell   运行时脚本断言（bash + mksh）＋ 设备侧 mksh 闸门 ＋ .sh 语法
      ├─ node    WebUI 纯函数 / 版本一致性 / status 契约 / .mjs 语法
      └─ android App 构建 + 单测  ──上传制品──▶ apk-debug
                                              │
线路② 发布 release.yml（beta/main 分支）        │ 下载制品，**不再重编**
      ├─ gate    复用 ①（workflow_call；任一 job 红 → 发布被挡）
      └─ publish APK(制品) + 模块 zip + index.json → gh-pages /beta|/stable/
                                              │
线路③ 层与频道 layers.yml（手动触发）            │
      ├─ 跑在**自带 arm64 标签的自建 runner**上（244 MiB、要真 chroot）
      ├─ 增量：按输入选择 --skip-base/--skip-runtime（日常只重打 dsh 层）
      └─ gen-manifest →（有私钥时）sign + 独立验签 → 制品/gh-pages /channel/
                                              │
线路④ 层托管 layers-release.yml（layers 分支）   │ 不在 runner 上构建，只"收集 + 上传"
      ├─ staging 分支（本机构建的 .erofs.zst/.gz，构建机不需要 token）
      └─ gh release create/upload → Release 资产；打印 base-url 供 publish-channel（§5.3）
```

> 线路③ 与 ④ 是**两条不同的路，按"层从哪里来"选一条**：
> ③ 让 CI 在自建 arm64 runner 上**构建**层（需要你注册 runner）；
> ④ 让**你自己的机器**构建，CI 只负责把产物放到 Release（不需要 runner，但产物要在
> `layers` 分支过一遍 git）。两者产出的都是同一套 `<id>-<version>.erofs.{zst,gz}`。

**为什么要拆**（都是实测数字）：

| 问题 | 原来 | 现在 |
|---|---|---|
| 总时长 = 各检查**累加** | 单 job 串行：`apt` + 5 组断言 + 语法 + Android 构建 | 三个 job 并行 → **总时长≈最慢的那个** |
| APK **编了两遍** | gate 里编一次，publish 里又编一次（每次约 2m40s） | publish 直接下载 `apk-debug` 制品 |
| Gradle 依赖每次重下 | 无缓存，每次 2m40s 里大半在下 Gradle/依赖 | `actions/cache` 缓存 `~/.gradle/{caches,wrapper}` |
| 改 App 也要重建 244 MiB 的层 | 层构建塞在同一条线上 | 线路③独立，且支持增量（只重打变化的那层） |

> ⚠️ 拆 job **不等于**放松门禁：三个 job 里任何一个红，`gate` 就红，发布被挡住
> （`release.yml` 里 `gate` 用 `workflow_call` 复用 `ci.yml`，这是结构保证，不靠人记得）。
>
> ⚠️ 制品契约：`ci.yml` 的 `android` job 上传 **`apk-debug`**，`release.yml` 的 `publish`
> 下载同名制品。改名字/路径时两边必须同步（否则发布会在"找不到 APK 制品"处失败）。

---

## 三、回归门禁（发布前必过）

无论哪个分支，CI 先跑**同一套全量检查**；**任一失败即中止发布**（不发半成品）。

| job | 检查 | 命令 | 断言数 |
|---|---|---|---|
| android | Android 单测（必须两条一起跑） | `./gradlew :app:assemble$<档位> :app:test$<变体>UnitTest` | 148 |
| android | **内嵌离线包真的进了 APK**（0.3.0 新增，见 §三·一） | `tools/ci-assert-embed.sh <apk> <变体>` | 每个 APK 1 条 |
| android | **包名与桌面标签和矩阵一致**（0.3.1 新增，见 §三·一） | `tools/ci-assert-identity.sh <apk> <变体>` | 每个 APK 2 条 |
| shell | root 运行时回归（bash） | `bash runtime/root/selftest.sh` | 42 |
| shell | root 运行时回归（**mksh**，模拟设备侧） | `mksh runtime/root/selftest.sh` | 42 |
| shell | proot 运行时回归 | `bash runtime/proot/selftest.sh` | 18 |
| shell | **设备侧 shell 兼容性**（mksh 可解析 + shebang 正确） | `node tools/shell-compat-check.mjs` | 19 个脚本 |
| shell | `.sh` 语法（全套） | `bash -n` 遍历 | — |
| node | 模块 WebUI 纯函数 | `node module/webroot/selftest.mjs` | 60 |
| node | 三方版本比较一致性 | `node tools/cmp-consistency.mjs` | 16 |
| node | status JSON 契约 | `node tools/contract-check.mjs` | 冻结 schema |
| node | `.mjs` 语法 | `node --check` 遍历 | — |

> ⚠️ **`assembleDebug` 不编译 test 源集**：只跑它会**静默跳过全部单测**。
> 这在开发中真实发生过一次（单测引用已删除的符号，APK 照样出得来）。所以这两条永远一起跑。
>
> ⚠️ **设备侧脚本必须过 mksh**：Android 上没有 bash，`/system/bin/sh` 是 mksh。
> 这一关失败意味着"本地 bash 全绿、真机一行都跑不了"（本轮就是这么发现 root 模式从来没跑起来过）。
> 详见 `docs/architecture.md` §1.1 与 `docs/STATUS.md` §3.4。

### 三·一、内嵌离线包的门禁：**必须打开 APK 看**（`tools/ci-assert-embed.sh`）

0.2.6 发出去的那批 APK 是 **12 MB、里面没有 `assets/offline-bundle.bin`** —— 用户装完
照样得联网下载环境，等于"完整离线版"这个卖点当场失效。而**当时 CI 全绿**，因为三道检查
谁都没看 APK 内部：

- bundles 节点只数了 `dist/bundles/*.bin` 够不够（够的，5 个）；
- 编译节点只断言 edition 专属资源（root 的 `assets/module/`、proot 的 `assets/proot-runtime/`）；
- Gradle 的 `EmbedOfflineBundle` 找不到包时**只记一行日志就跳过**（为了本机开发方便，
  没打离线包也能编译）—— 于是"制品名对不上 ⇒ 一个都没内嵌"完全无声。

现在两边都接上 `tools/ci-assert-embed.sh <apk> <变体> [--require]`，判定**完全由
`tools/offline-bundle/variants.json` 的 `embed` 驱动**（单一事实源）：

| `embed` | 期望 | 违反时 |
|---|---|---|
| 非空（如 `root-full = [base,runtime,dsh]`） | APK 里**必须有** `assets/offline-bundle.bin`，且与本地同名 `.bin` **严格等大** | `::error::`（等大还顺带抓住"内嵌错组合的包"） |
| 空（`root-minimal`：root 层走频道） | **不该有** | `::error::`（白占体积，且与矩阵声明矛盾） |

- 接在 **`build-apk.yml` 每个编译节点**（`--require`，因为该 run 确实产出了离线包）
  与 **`ci.yml` 的独立门禁**（`dist/bundles/*.bin` 存在时才 `--require`）。

### 三·二、系统身份的门禁：包名 + 桌面标签（`tools/ci-assert-identity.sh`）

同一类教训的第二例（都是"编译期成功、真机才发现"）：

| 事故 | 后果 | 现在怎么挡 |
|---|---|---|
| 0.3.0 拆成两个 App 时只改了 `main/res` 的 `app_name`，按 flavor 覆盖的 `src/<edition>/res/` 不存在 | 真机上两个 App **桌面同名**，用户点哪个纯靠猜 | 用 `aapt2 dump badging` 读 `application-label`，必须等于 `variants.json` 的 `editions[].label` |
| 两个 App 靠 `applicationId` 共存 | flavor 接线错误导致包名撞车 → 用户装一个**覆盖**另一个，KernelSU 授权与 App 数据全对不上 | 同样读 `package: name=`，必须等于 `editions[].application_id` |

- 判定同样**只认 `variants.json`**（标签与包名都不在 CI 里抄第二份）。
- `aapt2` 从 `$ANDROID_SDK_ROOT/build-tools/*/aapt2` 取（Gradle 之后跑，build-tools 已在），
  再退化到 `command -v aapt2` 与 SDK 目录内 `find`；**找不到只告警跳过**，不让发布卡在工具缺失上。
- 接在 `build-apk.yml`（每个编译节点）与 `ci.yml` 的独立门禁两条路径上。
- ⚠️ **别用"小于 1 MiB 就是占位"这种启发式**：`proot-minimal` 的包只有 **989224 字节**
  （0.94 MiB，就是 proot 本体），一律按 MiB 也会显示成"0 MiB" —— 实测就会被误杀成假红。
  真判据是"与本地同名包严格等大"；拿不到本地包时才退化成"> 4 KiB 不是空壳"。

### 三·三、模块两变体的门禁（`tools/module-variant-selftest.mjs`）

决策 2 把模块拆成 `full`（默认，自带 DSH）与 `bare`（不含 DSH）后，最危险的失败模式是
**静默冒充**：打了不带 DSH 的包、却顶着默认名发出去 —— 用户装完才发现环境起不来。
所以这道门禁**不看文本、跑行为**（在临时目录里真的打包、真的落地、真的从验签过的频道装）：

| 断言 | 守什么 |
|---|---|
| `full` 必须给 `--dsh-layer`，`bare` 给了就拒绝 | 不许"标 bare 却夹带"、也不许"没层却产出默认名" |
| zip 里 `full` 有 `dsh/manifest.json` + 载荷、`bare` 没有 | 打包规则与载荷一致性 |
| 默认名只给 `full`（`bare` 必带 `-bare`） | 下载页/Release 上的"默认"不能是不带 DSH 的那个 |
| `dsh builtin` 落地：层文件 + `etc/dsh-builtin.json` + `state.json` | 装完重启就能起（回滚目标有据可查） |
| 载荷被改坏 → `sha256_raw` 不符即拒绝 | 内置层不是"信了就装"，要校验 |
| `dsh install`（一条指令）→ 验签 → 下载 → 装 → 可回滚到内置版本 | 决策 2 的正道；验签失败必须**拒绝**并说清原因 |
| 没有 `channels.json` 的 CLI-only 环境也能补出内置官方频道 | 只装模块、不装 App 的用户走的就是这条路 |

- 另外 **APK 内嵌的必须是 `bare` 变体**：`ci.yml` 的独立门禁与 `build-apk.yml` 每个编译
  节点都会读 `assets/module/module.json` 断言 `"variant":"bare"`（full 自带 48 MB 的 DSH
  层，而 base/full 档 APK 的离线包里已经有了 —— 内嵌 full 等于塞两遍）。
  ⚠️ **匹配前必须去掉空白**：Gradle 写出的 JSON 是 `"variant": "bare"`（冒号后带空格），
  按 `"variant":"bare"` 逐字匹配会把**正确**的包判成错 —— 0.3.6 首跑就是这么红的
  （三个 root 变体全红、三个 proot 变体全绿，报的却是"内嵌的模块不是 bare 变体"，
  而那句报错里印出来的 module.json 明明写着 `"variant": "bare"`）。
  现在 `tr -d ' \n\t'` 之后再匹配，并且 `tools/module-variant-selftest.mjs` 有一条第②b 断言
  钉住"这道闸门必须先去掉空白"，防止以后有人"顺手简化"回去。
- 接在 `ci.yml` 的 `shell` 节点（`node tools/module-variant-selftest.mjs`）。

---

## 四、层产物（base/runtime/dsh）：走**独立线路**，且支持增量

三层两式合计约 **244 MiB**（实测 base 18.6+26.5 / runtime 46.5+73.5 / dsh 31.2+47.8 MiB），
构建需要 **arm64 + 真 chroot + mmdebstrap**。所以它既不进 CI 的日常路径，也不和 App 抢时间：

| 产物 | 触发方式 | 说明 |
|---|---|---|
| APK / 模块 zip（bare + 默认 full） | 推 `main`（或手动 `workflow_dispatch`）→ 线路② | 小、快；层不在这里 |
| 三层镜像 + channel.json | **手动触发** `layers.yml` → 线路③ | 跑在**自建 arm64 runner** 上；可与①②并行 |

线路③的两个关键设计：

1. **增量重编**：`build-layers.sh` 支持 `--work-dir <保留目录>` + `--skip-base` / `--skip-runtime`。
   工作流按输入选档：改 DSH 版本选 `dsh`（复用 base/runtime 树，只重打 dsh 层），
   改基础系统才选 `base`。**默认 `dsh`** —— 因为用户日常升级也只下这一层。
2. **私钥可选**：配了 Secret `CHANNEL_SIGNING_KEY` 就地签名 + 独立验签；
   没配就只出清单，打印出"拿到有私钥的机器上怎么签"的命令（默认做法，见 `docs/updates.md` §2.9）。

> 层的**日常增量**交给频道本身：用户升级只下 `dsh` 层（约 31 MB），不需要每次重发三份。

---

## 五、需要的 Secrets（**绝不提交到仓库**）

| Secret | 用途 | 缺失时 |
|---|---|---|
| `CHANNEL_SIGNING_KEY` | Ed25519 私钥，签 `channel.json` | **`channel.yml` 用它**（仅在 `channel` 分支推送 `channel/channel.json` 时触发）：缺失时该工作流**直接失败**，绝不跳过签名发布。`release.yml` 不用它（它只发 APK + 模块 + `index.json`） |
| `ANDROID_KEYSTORE_BASE64` | 私有**发布** keystore（base64） | 未配置时用**仓库内固定的调试密钥**签名（`app/app/debug.keystore`）—— 签名仍然稳定（可覆盖安装），只是密钥是公开的调试密钥。**别在两档之间来回跳**：换密钥那次用户必须卸载重装（见 §5.4） |
| `ANDROID_KEYSTORE_PASSWORD` / `ANDROID_KEY_ALIAS` / `ANDROID_KEY_PASSWORD` | 发布密钥的 store 口令 / 别名 / key 口令 | 只在配了 `ANDROID_KEYSTORE_BASE64` 时才需要；缺了会让签名失败（不是静默降级） |

### 5.1 层产物与频道清单：**不在 CI 里，走 `publish-channel`**

三层两式合计约 **244 MB**（实测：base 18.6+26.5 / runtime 46.5+73.5 / dsh 31.2+47.8 MiB），
且构建需要 **arm64 + 真 chroot**（GitHub runner 是 x86-64，要 qemu/binfmt）。所以它们**不进 CI**，
而是在有 arm64 的机器上构建后，用一条命令发布：

```bash
# 1) 构建层（arm64 + 真 chroot；本工作容器没有 CAP_SYS_ADMIN，chroot 会报 Function not implemented）
bash rootfs/build-layers.sh --out-dir dist --runtime-dir runtime/root --dsh-dist-tag next

# 2) 生成 + 签名 + 体检 + 打印上传清单（层文件用硬链整理进发布目录，不额外占空间）
tools/channel/sunsetlinux-channel publish-channel \
    --base-url https://<你的托管前缀>/sunsetlinux \
    --key ~/.sunsetlinux-keys/channel.key \
    --pub ~/.sunsetlinux-keys/channel.pub \
    --name "SunsetLinux 官方" --dsh-dist-tag next

# 3) 把打印出来的文件清单整体上传到 <base-url>，然后把 URL + 公钥 + 指纹发给用户
```

> ⚠️ **托管选型：已定 —— GitHub Release 资产（"纯存储"）+ `layers` 分支收集**（见 §5.3）。
> 硬约束回顾：gh-pages **单文件 ≤ 100 MB、已发布站点 ≤ 1 GB**，一次全量频道 244 MB
> → 约 **4 次**就撑满，而 `runtime-*.erofs.gz` 73.5 MB 已经贴着单文件上限。
> Release 资产没有这个站点上限，所以：**层 → Release 资产；清单/APK/模块 → 仍走 gh-pages**。
> 清单里的 `url` 本来就是绝对地址，可以指向另一个域名；用户只信公钥，跟你用什么托管无关。

### 5.3 层放 Release、构建机不放 token：`layers` 分支 → Release 资产

**为什么中间要一个分支**：构建层需要 arm64 + 真 chroot，通常是你自己的机器；那台机器上不一定
想放 GitHub token。于是分工成：本机构建 → 产物推到 `layers` 分支 → CI 收集并上传到 Release。
构建机上不需要任何 token/私钥，**只有 CI 需要 `contents: write`**（用它自带的 `GITHUB_TOKEN`）。

```bash
# 1) 本机构建（arm64 + 真 chroot）。想省一半体积就加 --no-gzip-fallback：
#    清单里不会出现悬空的 url_gz（gen-manifest 只在 .gz 实际存在时才写那几个字段）。
bash rootfs/build-layers.sh --out-dir dist --runtime-dir runtime/root --dsh-dist-tag next

# 2) staging 分支：**在干净目录里另起一个仓库**，只放"分发产物 + layers-release.yml"
#    ⚠️ 两个坑（都会静默出错）：
#      · `git checkout --orphan layers` 之后工作区里**仍是整棵源码树**，`git add -A` 会把源码一起提交；
#      · 仓库根的 .gitignore 挡了 `*.erofs` / `*.erofs.zst` / `*.erofs.gz`，直接 add 会被**静默漏掉**。
#    干净目录里另起仓库同时避开这两点（新仓库没有那份 .gitignore），也天然是 orphan：
mkdir -p /tmp/sunsetlinux-layers && cd /tmp/sunsetlinux-layers
cp /path/to/dist/*.erofs.zst .                     # 需要纯 CLI 的 gzip 回退时再加 *.erofs.gz
mkdir -p .github/workflows
cp <repo>/.github/workflows/layers-release.yml .github/workflows/
printf 'layers-%s\n' "$(date -u +%Y%m%d)" > TAG       # 可选；不写就自动用 UTC 时间戳
git init -b layers && git add -A && git commit -m "layers: $(date -u +%Y-%m-%d)"
git remote add origin git@github.com:SunsetRNE/SunsetLinux.git
git push --force origin layers

# 3) CI 建/更新 Release 并上传，然后把 base-url 与每个文件的 sha256 打进 job summary

# 4) 回到有层文件的那台机器，按 summary 里的 base-url 生成清单：
tools/channel/sunsetlinux-channel publish-channel \
    --base-url https://github.com/SunsetRNE/SunsetLinux/releases/download/<tag> \
    --name "SunsetLinux 官方" --dsh-dist-tag next
#    把 dist/channel/channel.json 推到 channel 分支 → channel.yml 签名 + 用公钥独立验签 + 发 /channel/
```

> 为什么推荐 orphan：**GitHub 只跑"被推送的那个分支上存在"的工作流**，所以 staging 分支
> 只带 `layers-release.yml`（不带 `ci.yml`）就不会触发全量回归/Apk 构建。
> 另外 `ci.yml` 的 `branches-ignore` 也加了 `layers` 作为兜底（万一有人基于 main 建这个分支）。

**必须知道的三件事**（都是实测/官方文档级的事实，不是猜测）：

1. **下载端都会跟 302**（Release 地址会跳到 `release-assets.githubusercontent.com`）：
   App 用 `HttpURLConnection`（`instanceFollowRedirects = true`）；CLI 侧 `curl -fL` / `wget`；
   Node 工具用 `fetch`（默认 follow）；`runtime/proot` 的 `linuxctl update` 只吃本地文件，
   下载由 App/CLI 负责。
   ⚠️ 但 `channel.yml` 的"层 URL 必须已可下载"预检原来**没带 `-L`**：实测同一 URL 无 `-L`
   得 **302**、加 `-L` 得 **206** —— 会把完全正常的 Release 清单判成"层下载不到"而拒发。
   已修（`curl -sL`），这条是走 Release/任何重定向托管的**前提**。
2. **裸 `.erofs` 不能进分支**：runtime 裸镜像 240 MB，超过 git **单文件 100 MB 硬限**，
   而下载端从不使用它（分发物一律 `.zst`/`.gz`）。`layers-release.yml` 会把它当错误拒绝。
3. **分支只是传送带，不是"仓库外的存储"**：产物 push 进分支就进了 git 对象库，
   `git clone` 会跟着变大 —— Release 解决的是"Pages 站点 1 GB 上限"，**没有**解决"仓库变大"。
   所以 staging 分支**每次重置**（orphan 单提交 + `--force`），不要累积历史。

**保留策略**：Release 资产没有站点上限，但**被清单引用过的层不能随便删**（删了旧版本就回退不了）。
`layers-release.yml` 只负责上传，清理旧 release 由人决定。tag 形如 `layers-<日期>`，
**只是存储标识**，不要当成产品版本（本项目刻意不用 tag 做发布，见 §一）。

> 可选（未实现）：让 CI 直接生成清单也能做 —— 层文件既然到了分支，解压复算 `sha256_raw`
> 不需要 chroot/arm64，runner 完全干得了。但那会让"本机 `publish-channel` 真解压复算"这条
> 已被验证的路径变成两条，先不引入；哪天嫌第 4 步麻烦再说。

### 5.2 `CHANNEL_SIGNING_KEY` 到底怎么弄（两种模型，二选一）

它就是一个 **Ed25519 私钥（PKCS#8 PEM，即 `channel.key` 的全部内容）**，存进
仓库 Secret，让 CI 能在没有你本机参与的情况下签 `channel.json`。

**先生成一次密钥对**（在你自己的机器上，且只做一次）：

```bash
tools/channel/sunsetlinux-channel keygen --out-dir ~/.sunsetlinux-keys
#   ~/.sunsetlinux-keys/channel.key  私钥（0600）—— 离线备份好，丢了就签不出"同一个频道"
#   ~/.sunsetlinux-keys/channel.pub  公钥（base64）—— 发给用户
#   指纹 ed25519:xx:xx:…              —— 让用户核对，防中间人
```

**写进 Secret**（二选一）：

| 方式 | 命令/路径 |
|---|---|
| 网页 | 仓库 → Settings → Secrets and variables → **Actions** → New repository secret；名字 `CHANNEL_SIGNING_KEY`，值 = `channel.key` 的**全部内容**（含 `-----BEGIN/END-----` 两行） |
| 命令行 | `gh secret set CHANNEL_SIGNING_KEY < ~/.sunsetlinux-keys/channel.key` |

> ⚠️ Secret 是**只写**的：网页上再也读不出来，只能覆盖。所以私钥的**唯一权威副本是你的离线备份**，
> 不要把它当成"存在 GitHub 上了"。

**两种签名模型**（本仓库两条都实现了，按需选）：

| | A. 本机签名（默认可用） | B. CI 签名（`channel` 分支） |
|---|---|---|
| 命令 | `sunsetlinux-channel publish-channel --key ~/.sunsetlinux-keys/channel.key …` | 本机只跑 `gen-manifest`（**不需要私钥**）→ 把清单推到 `channel` 分支 → CI 用 Secret 签名 |
| 私钥在哪 | 你的机器 | GitHub Secret + 你的离线备份（**日常机器上可以没有**） |
| 需要 Secret | 不需要 | 需要 `CHANNEL_SIGNING_KEY` |
| 适合 | 一个人、一台机器，想最短路径 | 多台机器/多人协作，或不想让私钥常年待在日常机器上 |
| 风险 | 私钥在那台机器的磁盘上 | **任何能改本仓库工作流的人都能签频道** → 请给 `main`/`channel` 开分支保护 |

**为什么"清单必须在有层镜像的机器上生成"**：`sha256_raw`/`size_raw` 要真解压算（曾经手算差 28672 字节），
而层产物 244 MB 且需要 arm64 + 真 chroot。CI 拿不到层文件就生成不了清单 —— 所以 CI 只做
"签名 + 发布"，不假装能生成。B 模型正是照这个边界切的：**本机出清单（无私钥），CI 持私钥签名**。

**CI 签名做了什么**（`.github/workflows/channel.yml`，触发条件：推送到 `channel` 分支且 `channel/channel.json` 变化）：

1. 检查 `channel.json` 与 `channel.pub` 都在、且 `CHANNEL_SIGNING_KEY` 已配置（**缺失就失败，绝不跳过签名**）；
2. **逐层检查 URL 现在就能下载**（Range 请求取 1 字节）——防止"清单先发了、层还没传"；
   确需绕过时可设仓库变量 `CHANNEL_SKIP_URL_CHECK=1`；
3. 把 Secret 写成 0600 的临时文件 → `sign.mjs` 签原始字节 → 用 `verify.mjs` **另一条代码路径**独立验签；
4. 清掉私钥（`if: always()`）→ 提交 `channel.json.sig` 回 `channel` 分支 → 发到 gh-pages `/channel/`；
5. 在运行摘要里输出**清单 URL + 公钥 + 指纹**，直接复制去发给用户。

> ⚠️ **两个工作流共用并发组 `gh-pages`**（`release.yml` 与 `channel.yml`）：它们都会写 gh-pages，
> 各用各的组名就可能并发 force push 互相覆盖（`keep_files: true` 只能保住"运行开始时已存在的文件"）。

**为什么签名密钥必须是 Secret**：频道的安全模型是"npm/HTTP 只是传输，信任根是公钥验签"。
私钥一旦泄漏，任何人都能签出"你的"频道，**整个更新体系失效**（这一点已实测：篡改与冒签都会被拒，
但那是建立在私钥不外泄的前提上）。

> 本地开发用的 `channel.key` **已经**在 `.gitignore` 里；仓库里还曾遗留过一份测试私钥，已清除。
> 生成与保管方式见 `docs/updates.md`。

### 5.4 APK 签名：**必须固定**（否则用户升级不了、root 授权还会全丢）

**问题（2026-09-16 实测确认）**：`app/app/build.gradle.kts` 原来**没有任何 `signingConfig`**，
于是 AGP 用各机器自己生成的 `~/.android/debug.keystore`。CI runner 是临时的 → **每次发布都是一把新 key**：

```
CI 发布的 APK      Signer #1: CN=Android Debug  SHA-256 84be9523b5623a543f6ce517d73858c0fc2bad9b331fd40aa5a9b33ffc29a221
本机构建的 APK     Signer #1: CN=Android Debug  SHA-256 3b68b61675bd7ccb6a2d81b7edac2f2c3f005e5154b3c4d38e78e747a7bf318c
```

后果比"装不上"严重得多：

| 后果 | 说明 |
|---|---|
| `INSTALL_FAILED_UPDATE_INCOMPATIBLE` | 用户必须先卸载才能装新版 —— App 数据清空 |
| **KernelSU root 授权全部作废** | 授权按【包名 + 签名】记录（`build.gradle.kts` 里原本就写着这句），签名一变，之前授过的 root 全失效 |
| 系统不认为这是升级 | 版本更新、`index.json` 里的 sha256 都对不上实际安装的包 |

**修法（两档，已实现）**：

1. **默认：仓库内固定的调试密钥** `app/app/debug.keystore`（PKCS12，alias/pass 都是 `sunsetlinux`，
   口令刻意不用默认的 `android`）。`.gitignore` 里对它有**唯一的例外** `!app/app/debug.keystore`
   —— 否则 `*.keystore` 会把它静默忽略，CI 上 `storeFile` 找不到。调试密钥不是秘密，
   本地与 CI 天然同签名，开箱即稳。
2. **私有发布密钥（更安全）**：设四个 Secret，`ci.yml` 会把它落成一个临时文件并注入环境变量：

   | Secret | 用途 |
   |---|---|
   | `ANDROID_KEYSTORE_BASE64` | 发布 keystore 的 base64（`base64 -w0 my.jks`） |
   | `ANDROID_KEYSTORE_PASSWORD` | store 口令 |
   | `ANDROID_KEY_ALIAS` | 别名 |
   | `ANDROID_KEY_PASSWORD` | key 口令 |

   自建（只做一次）：`keytool -genkeypair -v -keystore release.jks -storetype PKCS12 -keyalg RSA \
   -keysize 4096 -validity 10950 -alias sunsetlinux -dname "CN=SunsetLinux, O=SunsetLinux, C=CN"`，
   然后 `base64 -w0 release.jks` 贴进 Secret，**并把 release.jks 离线备份**（丢了就再也升不了级）。

> ⚠️ **换密钥那一次，所有用户都必须卸载重装一遍**（签名变了做不到平滑升级，root 授权也会重授）。
> 所以要么一开始就上私有密钥，要么就老老实实用那把固定的调试密钥 —— 别在两档之间来回跳。
>
> 回归守卫：`app/app/src/test/.../SigningContractTest.kt`（密钥文件在、两个 buildType 都绑同一配置、
> 没环境变量时必须回落到仓库内那把、`.gitignore` 必须放行、版本号必须推进过）。

---

## 六、CI 环境的坑（**都实际踩过**，每条都花了时间）

首次把仓库推上去跑 CI 时发现的问题，逐条记在这里，省得以后重踩：

| 现象 | 根因 | 解法 |
|---|---|---|
| `setup-android` 退出码 1 | 该动作默认装 `packages: tools platform-tools`，而 **`tools` 包已从 Google 的 SDK 仓库移除**（`sdkmanager` 报 `Failed to find package 'tools'`） | 显式给 `packages: 'platform-tools platforms;android-35'`，build-tools 交给 AGP 按需下载（licenses 已由该动作接受） |
| root 自测"通过"但什么都没测 | 那 19 项断言依赖 3 个夹具，夹具原先只在 `build/fixtures/`，而 **`build/` 是 gitignore** → CI 找不到夹具，脚本打印 `SKIP` 后 `exit 0`（**假绿**） | 夹具移到仓库内可跟踪的 `testdata/fixtures/`，并配一个可重新生成的 `mkfixtures.sh`（带 `--check`） |
| 夹具修好后 CI 反而红了 | 断言"合法 erofs 被接受"依赖**宿主内核支持 erofs**，而 **GitHub runner 的内核没有 erofs** → `linuxctl` **正确地**拒绝了 | 新增显式测试开关 `LINUXCTL_KERNEL_FS_OVERRIDE=<格式>`（仅影响用户态预检；真挂不上时 `start.sh` 仍会失败）；`selftest.sh` 在宿主内核缺该格式时**声明**并设上它，让"落盘命名/state.json/find_layer 版本优先/rollback"这些自己的逻辑仍被覆盖 |
| 发布 run 的门禁被 cancelled | `ci.yml` 的 `concurrency: ci-<ref>` + `cancel-in-progress: true`：`main`/`beta` 的推送**同时**触发独立的 `ci.yml` 和 `release.yml`（后者 `workflow_call` 复用前者），两个实例落进同一组互相取消 | `ci.yml` 的 `push` 用 `branches-ignore: [main, beta]`（这两条分支交给 release 的门禁），并把 `cancel-in-progress` 改成 `false`（宁可排队，不可误杀发布门禁） |
| 两个工作流同时写 gh-pages 会互相覆盖 | `release.yml` 与 `channel.yml` 各用各的 concurrency 组名 → 并发 force push；`keep_files: true` 只保护"运行开始时已存在的文件" | 两者**共用**并发组 `gh-pages` |
| **三个 APK 只有 12 MB**（0.2.6 实际发生过）：`minimal` / `ubuntu-proot` / `ubuntu-proot-dsh` 里没有 `assets/offline-bundle.bin`，用户装『完整离线版』照样要联网 | 取 proot 运行时用的是**无认证** releases API，它按 IP 限 60 次/小时、runner 共享 IP 偶发 403；403 只让 `PURL` 变空串且**没有任何 warning**，`mk-bundle --available` 于是把含 proot 的三个变体『合法地』跳过，发布仍然全绿 | API 调用带 `Authorization: Bearer ${{ github.token }}`；proot 拿不到直接 `::error::` + `exit 1`；打完包校验 `dist/bundles/*.bin` 必须 4 个，缺一个就红并打印『已有的 + 频道清单里的层』 |
| **Release 上缺四份 `.bin`**（0.2.5 实际发生过） | 同一个 push 同时触发 `release.yml` 与 `offline-bundle.yml`，两者**并行**：Release 由发布那边创建，而打包这边先跑完，`gh release view v0.2.5` 失败 → 打了一行 `::warning::` 就 `exit 0`（**步骤仍显示 success**，产物只留在 workflow artifact 里） | `offline-bundle.yml` 在挂资产前**最多等 6 分钟**（每 20 秒 `gh release view` 一次），等到才挂；真的等不到才 warning 退出 |
| 单测摘要报"tests=0" | 前面某步先失败 → Android 构建被跳过 → 没有测试 XML，摘要步骤却仍在判定"必须有用例" | 摘要加 `if: always() && steps.android.conclusion == 'success'` |

> ⚠️ **CI 验证不了的东西**：宿主内核能力（erofs 挂载）、真机 SELinux 域、toybox 的
> `mount`/`unshare` 选项支持面、Android 侧无 bash 时的完整启动链路 —— 这些只有**真机**
> 能回答（见 `docs/smoke-test.md`）。CI 负责的是"我们自己的逻辑"与"语法/契约不漂移"。

---

## 七、用户侧怎么用

1. 在 App 里添加频道（URL + 公钥 + 指纹）；
2. 想尝鲜 → 订阅 **`/beta/channel.json`**；想稳 → 订阅 **`/stable/channel.json`**；
3. 更新时只下载**变化的那一层**（`dsh` 层约 31 MB，`runtime` 约 46 MB，`base` 约 19 MB）。

**公钥必须由用户手输或从可信渠道获取，绝不要从频道包里读** —— 那等于让攻击者自带信任根。
`gen-manifest` 只把公钥写进包内 README **供人核对**，不写进信任链。

---

## 八、与"署名/身份"的关系

- git 提交身份：`SunsetRNE <z100o190zgxc@163.com>`（已配置全局与仓库级）；
- 频道签名密钥：**与 git 身份无关**，是独立的 Ed25519 密钥对，私钥只存 Secret 与你的离线备份；
- 公钥指纹（形如 `ed25519:xx:xx:…`）是给用户核对用的，应写进 README 与发布说明。

---

## 九、落地状态

| 项 | 状态 |
|---|---|
| `main` / `beta` 分支 | ✅ 已建立 |
| git 身份配置 | ✅ SunsetRNE |
| 回归门禁可用的命令 | ✅ 全部就绪（见 §三） |
| `.github/workflows/` | ⏳ 待添加（`ci.yml` 门禁 + `release.yml` 分支发布） |
| GitHub Secrets | ⏳ **需要你在仓库设置里配**（我无法代配，也不该把私钥写进代码） |
| 正式签名 keystore | ⏳ 需要你生成并备份（丢了就无法给同一 App 发新版） |
