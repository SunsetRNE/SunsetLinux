# DSHroid

在 Android 手机上**原生**运行 [DSH](https://www.npmjs.com/package/@deepseek-ai/dsh)（DeepSeek Harness）的移植方案。

用**真 root + 真 chroot + overlayfs 分层 rootfs** 取代现有的 PRoot 方案（如 DSHA），
目标是：真 capabilities、环境不被 App 杀死、更新只下变化的那一层、任何人都能自建更新频道。

> 工作名 `dshroid` / 包名 `io.dshroid`，都可随意改。

## 为什么不用现成方案

我们对现有 PRoot 方案（`com.dsh.client`）做了实测，主要问题：

| 问题 | 实测证据 |
|---|---|
| 伪 root | 进程真实 uid 是普通应用 10497，`id` 里的 `uid=0` 是 proot `-0` 伪造 → 无真 capabilities |
| 数据易失 | rootfs 是 **16 GB**，放在 App 私有目录，卸载/清除数据即全毁 |
| 文件系统视图不可信 | proot 内写的 `/tmp/x` 原生看不见；proot 内 `du` rootfs 报 7 K，原生报 16 G |
| 环境随 App 死 | 启动参数带 `--kill-on-exit` |
| 结构脆弱 | 靠 `NODE_OPTIONS --import` 注入启动观察器 + 检查点 + 自修复脚本维持可用 |
| 密钥卫生差 | API key 明文出现在进程命令行，`ps` 可见 |
| 更新慢且闭源 | 只能等上游发版，拿不到第三方内测内容 |

**一个诚实的澄清**：我们实测发现，本机 proot 的元数据遍历开销**并不夸张**
（热缓存遍历 3643 个文件：proot 0.13 s vs 原生净 0.17 s）。
所以"卡"的主因不是 ptrace 翻译，而是 App 生命周期（后台被杀/被冻结）、小文件写、以及补丁层自身开销。
本方案针对的是这些**真实**成因，而不是把 proot 当替罪羊。

## 关键设计

```
┌─ L2 Android App (Kotlin + Compose M3) ──┐
│  状态卡 / 一键启停 / WebView 载 DSH GUI  │
│  前台服务 + 通知控制 + 频道与更新管理      │
└───────────────┬─────────────────────────┘
                │ 只通过 linuxctl + status JSON 交互
┌───────────────▼─────────────────────────┐
│  L1 运行时（root 侧，与 App 生命周期解耦） │
│  unshare -m + overlayfs + chroot         │
│  真 /proc /dev /tmp；sdcard 绕开 FUSE     │
│  KernelSU 模块开机自启                     │
├──────────────────────────────────────────┤
│  L0 rootfs（三层 EROFS，增量更新）        │
│  base / runtime / dsh + ext4 可写上层     │
│  reset = 秒级恢复出厂；全量下载 96.3 MB（实测）│
└──────────────────────────────────────────┘
```

**分层是更新提速的核心**：DSH 自身只占一层，升级**只下 31.2 MB**（实测），不重下系统。

三层产物的实测体积（`dist/SHA256SUMS.layers.txt` 含全部哈希）：

| 层 | 裸 erofs | 分发 `.zst` | 分发 `.gz` |
|---|---|---|---|
| `base-24.04.3-l1` | 95.3 MB | **18.6 MB** | 26.5 MB |
| `runtime-1.0.0` | 229.7 MB | **46.5 MB** | 73.5 MB |
| `dsh-0.1.5-rc.2` | 201.5 MB | **31.2 MB** | 47.8 MB |

> ⚠️ **一个会让人返工的坑**：本项目原定用 squashfs 做只读下层，实测发现本机内核
> **`# CONFIG_SQUASHFS is not set`**（完全没有 squashfs，也没有模块），
> 最终改用 Android 原生的 **EROFS**。详见 [`docs/findings.md`](docs/findings.md) §2.1。

## 文档

| 文档 | 内容 |
|---|---|
| [`docs/STATUS.md`](docs/STATUS.md) | **项目状态总账**：已交付什么、验证到什么程度、**做不到什么**、下一步。先看这个 |
| [`docs/findings.md`](docs/findings.md) | 设备/内核/DSHA 的**实测事实**、内核能力、EROFS 约束、体积实测。**排错先看这个** |
| [`docs/architecture.md`](docs/architecture.md) | 架构与**接口契约**（`linuxctl` 命令、status JSON、频道清单、挂载树） |
| [`docs/dsh-profile.md`](docs/dsh-profile.md) | DSH profile/插件机制、许可边界、**已验证的 profile 配方** |
| [`docs/install.md`](docs/install.md) | 部署步骤（含**必须由你在 root 终端执行**的部分） |
| [`docs/ksu-webui-api.md`](docs/ksu-webui-api.md) | KernelSU 模块 WebUI（`webroot/`）与 `ksu` JS API 的**实测签名** |
| [`docs/smoke-test.md`](docs/smoke-test.md) | **真机冒烟测试**：照着跑、把输出发回来的清单（含已知阻塞项） |
| [`docs/updates.md`](docs/updates.md) | 频道/更新/签名，面向用户与第三方发布者 |
| [`docs/repo-strategy.md`](docs/repo-strategy.md) | **仓库策略**：为什么是单仓、什么时候才该拆、怎么拆才不痛 |

## 许可边界（分发前必读）

DSH 的移动端体验靠 `~/.dsh/profiles/` 里的插件提供。我们实测了原方案的插件来源：

| 插件 | 许可 | 可否复用 |
|---|---|---|
| `dsh-web-mobile`（作者 mexiaosh，npm/GitHub 公开） | MIT | ✅ 直接用 |
| `dsh-task-notifier`（npm 公开） | MIT | ✅ 直接用 |
| `dsh-device-shell-guide`、`dsh-status-overlay`（原方案内置，非公开） | MIT 声明 | ⚠️ 可选复用，须附许可与来源 |
| `dsh-app-integration`（原方案内置） | **无 license 字段** | ❌ **默认保留所有权利，不得复制进本项目** |

**v1 的 profile 只用公开 npm 包**，零私有代码依赖，可自由分发。
`rootfs/layer-spec.sh` 里有 `layer_spec_assert_no_forbidden` 断言，构建时会自动拦截违规路径。

## 重要前提与环境约束

- **root 模式**需要真 root（本项目在 **KernelSU** 上开发验证）。**同时提供非 root 的 proot 降级模式**。
- 目标设备内核（Android GKI 常态）**没有** `CONFIG_PID_NS` / `CONFIG_USER_NS` / `CONFIG_SYSVIPC`：
  - → 做的是「真 chroot + mount/UTS 命名空间的监狱」，**不是**完整 PID 命名空间容器；
  - → **systemd 不可用**，用轻量 supervisor。
- 详见 [`docs/findings.md`](docs/findings.md) §2。

## 仓库结构

```
dshroid/
  docs/          # findings / architecture / dsh-profile / install / updates / smoke-test
  rootfs/        # layer-spec.sh（唯一事实源）+ 分层构建脚本 + 包列表 + profile 模板
  runtime/       # root 与 proot 两套运行时（linuxctl 等）
  module/        # KernelSU 模块（开机自启，让环境不依赖 App）
  app/           # Android 启动器（Compose）
  tools/         # 频道签名/清单工具、离线种子、proot bundle、contract-check.mjs
  dist/          # 构建产物
```

## 快速开始

产物都在 `dist/`；**先看 `dist/MANIFEST.txt`** —— 它是总清单（每个产物的用途 + 字节数 + sha256），
并自带一条可用的校验命令：

```bash
cd dist && sha256sum -c <(awk 'NF>=3 && $2 ~ /^[0-9]+$/ {print $3"  "$1}' MANIFEST.txt)
```

| 产物 | 说明 |
|---|---|
| `dshroid-launcher-debug.apk` | Android 启动器（装它） |
| `dshroid-module-0.1.0.zip` | KernelSU 模块（**开机自启、让环境不依赖 App**；内含运行时脚本 + 挂载探测 + 模块 WebUI） |
| `base-*.erofs.{zst,gz}` / `runtime-*.erofs.{zst,gz}` / `dsh-*.erofs.{zst,gz}` | 三层 rootfs（可从频道分发；设备侧也能自己构建） |
| `dshroid-seed-*.tar.zst` | 离线种子（ubuntu-base + Node 官方包） |
| `proot-bundle-arm64.tar.gz` | 非 root 模式用的 proot 二进制（GPLv2 合规） |
| `channel/` | **一份完整可发布的频道**：`channel.json` + `.sig` + 三层两式产物（已跑通 `gen-manifest → sign → verify --deep → publish-check`） |

步骤：

1. 装 APK：`adb install -r dist/dshroid-launcher-debug.apk`
2. 打包并刷模块：`bash module/mkmodule.sh --version 0.1.0` → 用 KernelSU 管理器安装生成的 zip
3. 按 [`docs/smoke-test.md`](docs/smoke-test.md) 在 root 终端跑一次冒烟测试
   （**挂载与安装步骤必须由你执行**，原因见该文档开头）

> 开发/排错时可以直接跑回归测试：
> `bash runtime/root/selftest.sh`（19 项断言；装到设备后在 `$LINUX_HOME/bin/` 下也能跑）

## 许可与上游

本项目采用 **MIT**，见 [`LICENSE`](LICENSE)。

- **上游同步**：同生态里先于我们的两个项目 ——
  [DSH-APP/DSHA](https://github.com/DSH-APP/DSHA) 与
  [xliaoy/DeepSeekHarness](https://github.com/xliaoy/DeepSeekHarness) —— 都是 **MIT**
  （Copyright (c) 2026 qiannianhuanxiang）。
  **MIT 允许我们同步其代码与设计**，保留版权与许可声明即可；因此本项目的定位是**可与其同步的独立实现**
  （架构不同：上游用 proot 用户态模拟，我们用真 chroot + EROFS 分层）。
  **当前未复制其代码**，只参考过 `DeepSeekHarness` README 描述的界面设计语言。
- **第三方组件与义务**（含 proot 的 GPLv2 合规、DSH 插件许可、明确不复用的组件）见
  [`THIRD_PARTY_NOTICES.md`](THIRD_PARTY_NOTICES.md)。
- **提交前注意**：[`.gitignore`](.gitignore) 已排除构建产物、下载缓存与**所有私钥**
  （`channel.key` 一旦进仓库，等于把频道签名体系交出去）。
