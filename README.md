# SunsetLinux

在 Android 手机上**原生**运行 **DSH（DeepSeek Harness）**：真 root + 真 chroot + EROFS 分层 rootfs。

DSH 本体就是官方的 `@deepseek-ai/dsh`（当前层内版本 **0.1.6-alpha.2**），本项目**不 fork 它**，
只负责给它一个**真实的 Linux 环境**、把它的 **profile 工作区**按契约铺好、并把"版本与更新"做成
**可分发、可自建频道**的分层产物。

> 工作名 `sunsetlinux`；两个 App 包名 `io.github.sunsetrne.sunsetlinux.root` / `….proot`。

---

## 一、与 DSH 上游的关系（内置的就是官方包）

| 事实 | 内容 | 依据 |
|---|---|---|
| 上游包 | `@deepseek-ai/dsh`，入口 `lib/bin.js`（ESM，`bin: {"dsh": "lib/bin.js"}`） | 实测 |
| 启动模型 | `dsh web` **不是**"跑个 CLI"，而是 `--profile web` 的别名：由 `@deepseek-ai/dsh-app-boot` 的 `loadLayeredEnv` 加载**profile 工作区** `$DSH_HOME/profiles/web/` | [`docs/dsh-profile.md`](docs/dsh-profile.md) §1 |
| profile 组成 | 一份 `package.json` 的 `dsh.profile.bundles`（`@deepseek-ai/dsh-base` + `@deepseek-ai/dsh-web-app` + 移动端插件）；每个 bundle 用自带的 `cordis.patch.yml` 以 **patch** 形式插入插件行；我们的 `cordis.yml` **刻意留空数组**（别往里写插件行） | 同上 |
| 硬要求 | node 必须带 **`--expose-internals`**（DSH 0.1.6 起 HMR 需要）；`dsh` 的 shebang 带不了它，`NODE_OPTIONS` 也会被 node 拒绝 | [`docs/dsh-profile.md`](docs/dsh-profile.md) §7.3 |
| 权限模型 | **一条变量**决定沙箱与审批：`(DSH_PERMISSION_MODE ?? 'workspace-write') === 'danger-full-access' ? 'never' : 'ask'`（`dsh-base` 组合原文） | [`docs/viewpoints.md`](docs/viewpoints.md) §4 |
| 凭据 | 走 `$DSH_HOME/env`（0600）或 `.credentials.yaml`，**不进命令行** | `runtime/root/supervise.sh` |

**本项目做的事**：给它一个真环境（chroot + overlayfs + 真 `/proc` `/dev` `/tmp`）、按上表把
profile 铺成可直接 `dsh web` 的状态、并把 base/runtime/dsh **分成三层**以便增量更新。

## 二、关键设计

```
┌─ L2 Android App（Kotlin + Compose M3，两个 edition）─────────┐
│  状态卡 / 一键启动 / 内嵌 WebView 载 DSH GUI / 频道与更新管理    │
└───────────────┬─────────────────────────────────────────────┘
                │ 只通过 linuxctl + status JSON 交互
┌───────────────▼─────────────────────────────────────────────┐
│  L1 运行时（root 侧，与 App 生命周期解耦）                     │
│  unshare -m + overlayfs + chroot；真 /proc /dev /tmp           │
│  KernelSU 模块开机自启；sunsetd 内核（唯一状态源）              │
├──────────────────────────────────────────────────────────────┤
│  L0 rootfs（三层 EROFS，增量更新）                             │
│  base / runtime / dsh + ext4 可写上层（upper.img）             │
└──────────────────────────────────────────────────────────────┘
```

**分层是更新提速的核心**：升级只下**变化的那一层**。当前三层（`dist/SHA256SUMS.layers.txt` 有全部哈希）：

| 层 | 裸 erofs | 分发 `.zst` | 分发 `.gz` |
|---|---|---|---|
| `base-24.04.3-l1` | 95.3 MB | **18.6 MB** | 26.5 MB |
| `runtime-1.0.1` | 234.0 MB | **47.6 MB** | 74.9 MB |
| `dsh-0.1.6-alpha.2` | 423.1 MB | **75.4 MB** | 109.5 MB |

> dsh 层从 201.5 MB 涨到 423.1 MB 的原因：上游这一版多带了 `@deepseek-ai/libreoffice-kit-wasm`（186 MB）。
> 我们的环境里没有字体、该功能本来就不可用；**用户 2026-09-18 决定不裁剪**（记在 `docs/HANDOFF.md`）。

> ⚠️ 一个会让人返工的坑：本项目原定用 squashfs 做只读下层，实测本机内核 **`# CONFIG_SQUASHFS is not set`**，
> 最终改用 Android 原生的 **EROFS**。见 [`docs/findings.md`](docs/findings.md) §2.1。

## 三、环境约束（都有实测依据）

- **root 模式**需要真 root（本项目在 **KernelSU** 上开发验证）；同时提供**免 root 版**（proroot 首选、proot 降级，各自锁死，见 [`docs/module-variants.md`](docs/module-variants.md)）。
- 目标设备内核（Android GKI 常态）**没有** `CONFIG_PID_NS` / `CONFIG_USER_NS` / `CONFIG_SYSVIPC`：
  → 做的是「**真 chroot + mount/UTS 命名空间的监狱**」，不是完整 PID 命名空间容器；→ **systemd 不可用**，用轻量 supervisor。
- **chroot 不是安全边界**：环境进程 uid 0、SELinux 域 `u:r:ksu:s0`，`/proc/1/root` 通着。详见 [`docs/viewpoints.md`](docs/viewpoints.md)。

## 四、视角、路径与权限（先读这一节再动手）

同一台机器上同时存在**三个挂载命名空间**，同一个路径字符串在不同视角下指向**不同目录**：

| 视角 | `/data/sunsetlinux` 是 |
|---|---|
| MT / 全局 ns | 真的那棵（含 `upper.img`） |
| 设备 shell（ksu su） | 同一棵树，但看不到环境私有挂载 |
| 环境内部（chroot） | **没有这个路径**；`/` 是 overlay 合成视图 |
| 某个 proot 容器 | 可能是**影子目录**（`/data` 没被 bind 时落到它自己 rootfs 里） |

- 判"我现在在哪"：`linuxctl whereami [--json]`（只读）。地图也随模块发布：环境里 `/share/地图-视角.md`。
- 交换文件：环境 `/share` ↔ 宿主 `$LINUX_HOME/share`（同一批 inode）；共享存储是环境里的 `/mnt/sdcard`（`/storage/emulated/0` 是它的软链）。
- 环境内 DSH **默认 `danger-full-access`**（无沙箱、不询问）：环境本来就是 uid 0，而沙箱在这类内核上**没有可用后端**（无 `bwrap`、Landlock 未暴露）。改回去：在 `$DSH_HOME/env` 里写 `DSH_PERMISSION_MODE=workspace-write`。
- 宿主通道（**默认关**）：`linuxctl host-channel on|off`；环境内 `/opt/sunsetlinux/host-channel.sh status|run <cmd>`，白名单 + 全量审计。见 [`docs/host-channel.md`](docs/host-channel.md)。

## 五、文档

| 文档 | 内容 |
|---|---|
| [`docs/STATUS.md`](docs/STATUS.md) | **状态总账**：交付了什么、验证到什么程度、**做不到什么**、下一步。先看这个 |
| [`docs/HANDOFF.md`](docs/HANDOFF.md) | **交接**：仓库/CI/密钥落到哪、需要你操作什么、删掉对话后怎么恢复上下文 |
| [`docs/viewpoints.md`](docs/viewpoints.md) | **视角地图**：三个命名空间、路径映射、权限模型、怎么验 |
| [`docs/host-channel.md`](docs/host-channel.md) | **宿主通道**：默认关的宿主访问通道、边界与审计 |
| [`docs/findings.md`](docs/findings.md) | 设备/内核**实测事实**：内核能力、EROFS 约束、体积实测。**排错先看** |
| [`docs/architecture.md`](docs/architecture.md) | 架构与**接口契约**（`linuxctl` 命令、status JSON、频道清单、挂载树） |
| [`docs/dsh-profile.md`](docs/dsh-profile.md) | DSH profile/插件机制与**已验证的 profile 配方** |
| [`docs/install.md`](docs/install.md) | 部署步骤 |
| [`docs/updates.md`](docs/updates.md) | 频道/更新/签名，面向用户与第三方发布者 |
| [`docs/uninstall.md`](docs/uninstall.md) | 卸载与清理：默认保留什么、怎么真正清干净 |
| [`docs/release-ci.md`](docs/release-ci.md) | 分支式发布、回归门禁、层产物为什么不每次重建 |
| [`docs/module-variants.md`](docs/module-variants.md) | 模块变体（full/bare）与两个 App、三个档位的矩阵 |

## 六、仓库结构

```
sunsetlinux/
  docs/          # STATUS / HANDOFF / viewpoints / architecture / dsh-profile / findings …
  rootfs/        # layer-spec.sh（唯一事实源）+ 分层构建 + 包列表 + profile 模板
  runtime/       # root 与 proot 两套运行时（linuxctl / start / supervise / doctor / selftest…）
  module/        # KernelSU 模块（开机自启、仓库不依赖 App；含 WebUI 与视角地图）
  app/           # Android 启动器（Compose，两个 edition × 三个档位）
  tools/         # 频道签名/清单、离线包、proot bundle、各类 CI 闸门
  dist/          # 构建产物
```

## 七、怎么装（当前发布）

产物在 GitHub Releases（`releases/latest`，当前 **v0.3.21**）：

| 你的情况 | 装什么 |
|---|---|
| 有 root（KernelSU） | `SunsetLinux-0.3.21-root-minimal-debug.apk`（桌面名 **SunsetLinux Root**） |
| 没有 root | `SunsetLinux-0.3.21-proot-minimal-debug.apk`（桌面名 **SunsetLinux 免root**） |
| 想要装完零下载 | 上面两个换成 `-full-`（内嵌离线包，体积大） |
| 模块（root 版必需） | `sunsetlinux-module-1.0.45.zip`（KernelSU 管理器刷入 → **重启一次**） |

装完在 App 里按引导走；状态与更新都在 App 内。层可以从**频道**装（`更新` 页），
也可以用 `linuxctl update <层> <文件>` 手动装。

> 开发/排错时跑回归：`bash runtime/root/selftest.sh`（当前 **147 项断言**；装到设备后在
> `$LINUX_HOME/bin/` 下也能跑）。App 侧：`cd app && ./gradlew :app:testRootMinimalDebugUnitTest`
> （需要注意本机要 `LC_ALL=C.UTF-8`）。

## 八、许可

本项目采用 **MIT**，见 [`LICENSE`](LICENSE)。

- 第三方组件、上游致谢与**分发义务**（proot 的 GPLv2 合规、DSH 侧插件许可、明确不复用的组件）
  统一见 [`THIRD_PARTY_NOTICES.md`](THIRD_PARTY_NOTICES.md)。
- 我们**只用公开 npm 包**做 profile，零私有代码依赖；`rootfs/layer-spec.sh` 的
  `layer_spec_assert_no_forbidden` 会在构建时拦截违规路径。
- **提交前注意**：[`.gitignore`](.gitignore) 已排除构建产物、下载缓存与**所有私钥**
  （`channel.key` 一旦进仓库，等于把频道签名体系交出去）。
