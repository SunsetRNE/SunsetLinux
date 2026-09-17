# 模块两版本 · 免 root 切割 · 只由 CI 编译（决策与设计）

> 本文记录三条**已拍板**的决策及其落地设计。与它配套的文档：
> `docs/release-ci.md`（分支与 CI 拓扑）、`docs/dsh-profile.md`（dsh 层内容）、
> `docs/architecture.md`（模块 / 运行时 / App 的契约）。
>
> 背景（2026-09-17 真机状态）：root 侧的挂载链**全通**（upper + 三层 erofs + overlay + entry），
> 唯一倒下的是 `supervise.sh` 的 `exit 78` —— 设备上自建的层**缺 DSH 本体与 web profile**。
> 也就是说：**root 模块只缺 DSH 这一块**。下面三条决策就是围绕"这一块怎么给"。

---

## 〇、三条决策（一句话）

| # | 决策 | 一句话 |
|---|---|---|
| 1 | **免 root 完全切割** | 免 root 版与 KernelSU 模块**彻底无关**；进入免 root 版 = 直接启用内置 Ubuntu 环境 + 终端 + DSH，不需要 su、不需要模块、不需要频道 |
| 2 | **root 模块拆两个版本** | `full`（**默认**，自带 DSH：装完就有，可更新、可一键回滚到内置版）/ `bare`（不带 DSH，**一条指令**从内置官方频道装） |
| 3 | **编译只在 CI** | 官方产物一律由 GitHub Actions 编译；**发布只由 `main` 触发**；`beta` / `channel` / `layers` / `gh-pages` 只用于**存储内容** |

---

## 一、决策 1：免 root 完全切割

### 1.1 边界（谁不碰谁）

| | Root 版（`…sunsetlinux.root`） | 免 root 版（`…sunsetlinux.proot`） |
|---|---|---|
| KernelSU 模块 | 必需（挂载 + 开机自启 + 设备侧构建入口） | **不探测、不提示、不内嵌、不安装**（APK 里连 `assets/module/` 都没有） |
| su | 必需 | **不申请** |
| 运行时脚本来源 | 模块 `post-fs-data.sh` 同步到 `/data/sunsetlinux/bin` | APK 内嵌 `assets/proot-runtime/` → `ProotRuntime.ensure()` |
| 环境位置 | `/data/sunsetlinux`（与 App 生命周期解耦） | App 私有目录 `files/sunsetlinux` |
| 内置内容 | 由档位决定（`minimal`/`base`/`full`） | **一律内置**：Ubuntu base + runtime + DSH + proot/proroot 运行时（零下载） |
| 进入 App 后 | 环境由模块在开机时拉起；App 只做状态/终端/更新 | **直接启用**：检查 → 铺运行时 → 铺环境 → 起环境 → 落到终端 |

"彻底"的含义是**代码级**：root 运行时不再保留任何 proot 降级分支（`doctor` 的 proot 段、
`status` 的 rootless 展示在 root 版里没有意义）；proot 版不再引用任何模块符号。

### 1.2 为什么（不是洁癖）

- 以前 root 版在没有 su 时会**降级到 proot** —— 那是**另一个环境**（App 私有目录）。
  用户以为在修 root 环境，实际动的是别处；这是"看起来成功、其实什么都没做"的典型。
  0.3.0 起两个 App 各自锁死一条路，本决策只是把**最后一截**（免 root 路径上的模块痕迹）切干净。
- 免 root 用户的机器**没有 KernelSU**：任何"先去装模块"的文案都是一条走不通的路（真机反馈过）。

### 1.3 进入免 root 版 = 直接可用

首启（或环境没铺好时）**一条路径**，不再让用户在两三个按钮之间猜：

```
打开免 root 版
  → ① 铺 proot 宿主脚本（assets/proot-runtime/，约 50 KB）
  → ② 解内嵌离线包（proot + Ubuntu + DSH，零下载）
  → ③ linuxctl provision（proot rootfs 展开）
  → ④ linuxctl start
  → ⑤ 落到「终端」页（DSH 面板在就绪后可用）
```

已经铺好时 ①~③ 跳过，只做 ④（可用 `etc/config.json` 的 `autostart` 关掉）。
每一步都**幂等**、都把原因写进日志；失败的原文进 `run/last-error`，界面只显示原文 + 下一步。

---

## 二、决策 2：root 模块拆两个版本

### 2.1 两个变体

| | `full`（**默认版本**） | `bare`（非内置 DSH） |
|---|---|---|
| 模块 id | `sunsetlinux`（**同一个**，不可共存） | `sunsetlinux` |
| 模块 zip 里的 `dsh/` | ✅ 内嵌官方 dsh 层镜像 | ❌ 没有 |
| 装完重启后 | DSH 已在 `layers/`，**开机自建 base/runtime 后即可启动**（DSH 这块零下载） | base/runtime/dsh 全走频道或设备侧构建 |
| DSH 更新 | `linuxctl dsh install`（一条指令，从内置官方频道） | 同左 |
| 回滚 | `linuxctl rollback dsh`（**不带 `--to`** 就回到随模块冻结的那份） | 回到次高版本（无内置版） |
| 用途 | 给人"只装模块、不装 App"的路径；也是**默认**发布名 | App 内嵌用（APK 已自带层，模块只需挂载/自启）；想自己控制 DSH 版本的人 |
| 体积 | ≈ 49 MiB（dsh `.erofs.gz` 47.8 MiB 再 zip 压缩） | ≈ 100 KB |

> **同一个模块 id** 是刻意的：两个变体是**替代关系**，不是可以并存的模块。
> 换变体 = 用管理器覆盖安装另一个 zip（用户数据 `/data/sunsetlinux` 不动）。
> `module.prop` 新增 `variant=` 行（KernelSU/Magisk 忽略未知键），
> 并在 `description` 里写清楚"本包自带 DSH" / "本包不带 DSH"。

### 2.2 模块 zip 里的 payload（`full` 变体）

```
dsh/dsh-<版本>.erofs.gz     # 官方签名频道里的 gzip 产物（设备侧无 zstd，只能 gzip）
dsh/manifest.json           # 版本 / 文件名 / sha256_gz / sha256_raw / size_raw / 来源
```

`dsh/manifest.json`（示例）：

```json
{
  "schema": 1,
  "layer": "dsh",
  "variant": "full",
  "version": "0.1.5-rc.2",
  "file": "dsh-0.1.5-rc.2.erofs.gz",
  "sha256_gz": "…",
  "sha256_raw": "…",
  "size_raw": 201326592,
  "source": {
    "kind": "channel",
    "url": "https://sunsetrne.github.io/SunsetLinux/channel/channel.json",
    "built_at": "2026-09-17T…Z"
  }
}
```

**为什么内嵌 `.gz` 而不是裸 `.erofs`**：裸镜像 201 MB，zip 压不动多少；
`.gz` 是 47.8 MB，且设备侧 toybox 自带 `gzip`，解压即用。
**为什么不是"内嵌 DSH 源码树让设备自己构建"**：设备侧构建正是当前 `exit 78` 的成因
（缺 web profile / pnpm / 软链层级），把它交给 CI 一次做好，才是"只缺 DSH 这一块"的正确补法。

### 2.3 安装与首启落地（幂等）

```
customize.sh（安装时）
  → 探测到 $MODDIR/dsh/manifest.json
  → /system/bin/sh $MODDIR/bin/linuxctl.sh dsh builtin --module-dir $MODDIR
       · gzip -dc → 校验 sha256_raw（有 raw 就核 raw，没有就核 gz）→ 落到 $LINUX_HOME/layers/dsh-<版本>.erofs
       · 写 $LINUX_HOME/etc/dsh-builtin.json（版本 / 文件名 / sha256_raw / from=module）
       · 已有同版本文件 → 直接跳过（幂等，不重复解 200 MB）
  → 打印结果（成功 / 空间不足 / 校验失败 + 下一步）
```

`etc/dsh-builtin.json` 是"内置版本"的**唯一事实源**——它必须落在 `$LINUX_HOME`（用户数据）里，
因为模块升级/卸载时 `layers/` 不删，回滚才有对象。

### 2.4 一条指令从频道装（`bare` 变体的正道）

```bash
linuxctl dsh install          # = 从内置官方频道装/更新 dsh 层，一条指令，零参数
linuxctl update dsh           # 同一件事（无文件参数时按频道安装分派）
linuxctl update dsh <file> --version <ver>   # 老语义：装本地文件（保持兼容）
```

内部链路（复用现有安全模型，**不打折**）：

```
update.sh install dsh
  → ensure_official_channel()：channels.json 里没有 official 就补上（URL + ed25519 公钥写死在脚本里，
      合并而非覆盖；App 侧的同名内置频道以代码定义为准，不会重复）
  → 用内置官方频道拉 channel.json + channel.json.sig → **Ed25519 验签**（失败即拒绝该频道）
  → 解出 dsh 层的 version / url_gz / sha256_gz / sha256_raw / size_gz
  → 下载（curl/wget）→ 校验 sha256_gz → gzip -dc → linuxctl update dsh --version <ver>
  → 现有语义：state.json 写版本 → 重启环境 → 失败自动回滚到上一个生效层
```

**为什么官方频道要写进运行时而不是只留在 App 里**：模块可以**脱离 App**使用
（KernelSU 管理器 + 终端）。没有 App 就没有 `channels.json`，"一条指令"就无从谈起。
公钥不是秘密（它是信任根，`docs/HANDOFF.md` 里有公开指纹），写死在脚本里与写死在 App 里等价。

### 2.5 更新与回滚

- **更新**：App「更新」页 / 模块 WebUI 走既有的 `update-check` → `update-apply`（参数由前端从验签结果里挑）；
  纯 CLI（没有 App 的场景）用**一条指令** `linuxctl dsh install`。三条路最终都落到同一个 `update.sh`
  （拉清单 + Ed25519 验签 + 下载 + 校验 + 解压 + 落盘 + 失败回滚，只有这一份实现）。
- **回滚**：`linuxctl rollback dsh` 不带 `--to` 时，**优先回到 `etc/dsh-builtin.json` 记录的内置版本**
  （前提是它不等于当前版本、且层文件还在）；没有内置版本时退回原来的"次高版本"逻辑。
- **退路一直在**：`update` 从不删旧层文件，所以"内置版"永远回得去（这也是模块 `full` 变体敢自称默认的底气）。

### 2.6 谁内嵌哪个变体（避免 APK 里塞两份 DSH）

| 消费者 | 用哪个变体 | 原因 |
|---|---|---|
| Root 版 APK（`assets/module/`） | **`bare`** | APK 的 `full`/`base` 档已经把 dsh 层放进离线包了；再内嵌一份 = 同样的 48 MB 塞两遍 |
| Release 上的默认模块 zip | **`full`** | "只装模块"的路径装完零下载 |
| Release 上的备用模块 zip | `bare` | 想自己控制 DSH 版本 / APK 用户手动刷模块 |

**产物命名**（`*` 是模块版本，如 `1.0.23`）：

```
sunsetlinux-module-<ver>.zip          # = full，**默认**（下载页与 Release 说明推荐它）
sunsetlinux-module-<ver>-bare.zip     # = bare
```

规则：**默认名只给 `full`**。CI 里如果 full 没打出来（例如频道里还没有 dsh 层），
就**只发 `-bare`** 并在 job summary / 下载页里明说"本次没有默认模块"——绝不把 bare 冒充默认。

---

## 三、决策 3：编译只在 CI，发布只由 `main`

### 3.1 分支职责

| 分支 | 角色 | 推送后发生什么 |
|---|---|---|
| `main` | **唯一源码主线 + 唯一发布触发点** | 跑全量：环境准备 → 矩阵编译 → 门禁 → 合并 → 发 `/stable/` + Release |
| `beta` | **纯存储**（预发布内容） | **不编译、不发布**。要出 beta 只能手动 `workflow_dispatch`（`channel=beta`），产物写 `/beta/` |
| `channel` | **纯存储**（频道数据：公钥、加密私钥备份、签名工作流） | `channel.yml` 签名 + 独立验签 → 发 gh-pages `/channel/`（不编译产品） |
| `layers` | **纯存储**（层产物传送带） | `layers-release.yml` 只收集 + 上传 Release 资产（不编译产品） |
| `gh-pages` | **纯存储**（发布产物） | **CI 独占写** |
| `feat/*`、`fix/*` | 开发 | 只跑回归门禁 |

### 3.2 "编译只在 CI" 的可判定形式

不是口号，是三条能验证的约束：

1. **发布路径只可能在 Actions 里跑**：`publish.yml` / `pipeline.yml` 都是仓库里的工作流，
   本地跑不了（`gh` / `peaceiris` 步骤需要 token 且没有本地入口）。
2. **产物打标**：`index.json` 写 `"built_in_ci": true` + `"builder": "github-actions"`；
   `mkmodule.sh` 在 `GITHUB_ACTIONS != true` 时**打印醒目警告**并写 `dist/.build-source`（`local`），
   本地产物只能用于开发/排障，**不作为发布来源**。
3. **文档措辞**：`docs/release-ci.md` 里"本机构建"只保留 `layers`（层需要 arm64 + 真 chroot，
   runner 上做不到；它本来就走"本机产物 → `layers` 分支 → Release 资产"的**存储**路径）。

---

## 四、契约（新增 / 冻结）

| 契约 | 位置 | 状态 |
|---|---|---|
| `module.prop` 的 `variant=full\|bare` | `module/` | **新增**（未知键被管理器忽略） |
| `dsh/manifest.json` | 模块 zip | **新增**，schema 1（见 §2.2） |
| `etc/dsh-builtin.json` | `$LINUX_HOME/etc/` | **新增**，内置 DSH 的唯一事实源 |
| `linuxctl dsh {info,builtin,install}` | CLI | **新增**（stdout JSON，`{"ok":…}` 约定不变） |
| `linuxctl update dsh`（无文件参数） | CLI | **新增分派**：= `dsh install`；带文件参数保持老语义 |
| `linuxctl rollback dsh`（无 `--to`） | CLI | **语义增强**：优先回内置版本 |
| status JSON（architecture §3.1） | — | **不动**（避免冻结 schema 变更；内置版本走 `linuxctl dsh info`） |
| `channels.json` 里的 `official` | `$LINUX_HOME/etc/` | 运行时也会补（App 读写时按 id 合并，不会重复） |
| `index.json` 的 `modules` 段 | gh-pages | **新增**：`{"default":…, "variants":[…]}`；旧字段 `module_version` 保留 |

---

## 五、实施清单

- [x] `module/mkmodule.sh`：`--variant full|bare`、`--dsh-layer`、payload 与 `manifest.json`
- [x] `module/customize.sh`：安装时落地内置 DSH、按变体改文案
- [x] `linuxctl.sh`：`dsh info|builtin|install`、`update dsh` 分派、`rollback dsh` 默认回内置
- [x] `update.sh`：内置官方频道 + 验签解析 + 一条指令 `install <layer>`
- [x] `tools/module-variant-selftest.mjs`：两变体产物 + 落地链路的**行为级**回归（进 CI）
- [x] CI：`pipeline.yml` 只由 `main` 触发；`offline-bundle.yml` 顺带打 `full` 模块；
      `publish.yml` 同时发两个变体 + `index.json` 的 `modules` 段
- [x] App：免 root 版去掉模块 UI、进入即启用内置环境 + 终端；模块更新读 `modules.default`
- [x] 文档：`release-ci.md` 分支表、`STATUS.md` / `HANDOFF.md` 收束

---

## 六、真机验收判据

**免 root 版**（不需要 root / 模块）：

```
1. 装 proot-full（或任一档），打开 App → 不出现任何"KernelSU 模块"字样
2. 首启自动铺好并启动 → 首页状态 running、终端可输入、DSH 面板 127.0.0.1:3080 可开
3. 环境根在 App 私有目录（不是 /data/sunsetlinux），卸载 App 即清除
```

**root 模块 `full`**（默认版本）：

```
1. 管理器装 sunsetlinux-module-<ver>.zip → 重启
2. $LINUX_HOME/layers/dsh-<版本>.erofs 已存在；etc/dsh-builtin.json 有记录
3. linuxctl doctor 里有"内置 DSH <版本>（随模块）"
4. linuxctl dsh install → 从官方频道更新（验签通过→下载→校验→安装→重启）
5. linuxctl rollback dsh → 回到内置版本（state.json 的 dsh.version 指回内置版本）
```

**root 模块 `bare`**：

```
1. 装 sunsetlinux-module-<ver>-bare.zip → 重启：layers/ 里没有 dsh 层（符合预期）
2. linuxctl dsh install（一条指令）→ 官方频道验签 → 装上 dsh 层 → 环境起来
```
