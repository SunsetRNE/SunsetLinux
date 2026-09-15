# 第三方组件与致谢

本项目采用 **MIT**（见 [`LICENSE`](LICENSE)）。下列第三方组件的许可与义务已一并处理。

## 1. 上游项目（同生态，MIT，可同步）

本项目的"上游"指同一生态里**先于我们**、且我们**会持续同步其可用改进**的项目。
两者的关系是：**架构不同、代码各自独立**（上游用 proot 用户态模拟；我们用真 chroot + EROFS 分层），
但都服务于"在 Android 上跑 DSH"这件事，因此**设计取舍、踩坑经验、插件生态会互相参考**。

| 项目 | 许可 | 版权 |
|---|---|---|
| [DSH-APP/DSHA](https://github.com/DSH-APP/DSHA) | MIT | Copyright (c) 2026 qiannianhuanxiang |
| [xliaoy/DeepSeekHarness](https://github.com/xliaoy/DeepSeekHarness) | MIT | Copyright (c) 2026 qiannianhuanxiang |

**MIT 允许我们同步其代码/设计，但要求保留版权与许可声明。** 因此：

- 若将来**直接引入**其中某个文件或代码片段，必须在该文件头部注明来源与版权，
  并把许可原文一并保留（本项目已随附 MIT 全文，二者许可相同，兼容）。
- 若只是**参考设计**（例如界面交互形态），也在此致谢。
- **当前状态**：本项目**未复制**上述两个项目的代码。
  我们参考过 `DeepSeekHarness` README 中描述的**界面设计语言**（汉堡侧边栏、悬浮胶囊底栏、
  卡片化启动页、常显日志区等），实现是 Kotlin + Compose 重写的，且对其主要痛点（启动失败时
  无从下手）做了**反向设计**（失败带阶段 + 一键诊断 + 对症建议）。

## 2. 运行时内置的第三方组件

| 组件 | 许可 | 说明 |
|---|---|---|
| **PRoot** | **GPLv2** | 非 root（proot）模式的运行时。**已随附许可证与源码获取方式**：见 `dist/proot-bundle-arm64.tar.gz` 内的 `license/GPL-2.0.txt`、`license/proot-copyright.txt` 与 `SOURCE` |
| Node.js（官方 arm64 静态包） | MIT | 解到 runtime 层的 `/opt/node` |
| Ubuntu base 24.04.3 | 各包各自许可（以 GPL/LGPL 为主） | base 层基线；`apt` 包许可随包保留在层内的 `/usr/share/doc/*/copyright` |
| erofs-utils（仅构建期） | GPL-2.0 / LGPL-2.1 | 用于生成 EROFS 层，**不随产物分发** |

## 3. DSH 侧插件（profile 工作区）

默认 profile 只用**公开 npm 包**，零私有代码依赖：

| 插件 | 许可 | 来源 |
|---|---|---|
| [`dsh-web-mobile`](https://github.com/mexiaosqwq/dsh-web-mobile) | MIT（Copyright (c) 2026 mexiaosqwq） | npm 公开 |
| `dsh-task-notifier` | MIT | npm 公开 |
| `@deepseek-ai/dsh`、`@deepseek-ai/dsh-base`、`@deepseek-ai/dsh-web-app` | 见各自包内 LICENSE | npm 公开 |

**明确不复用**的组件（许可原因，已写进构建期断言 `layer_spec_assert_no_forbidden`）：

- `dsh-app-integration`：**没有任何 license 字段 → 默认保留所有权利**，不得复制、不得分发；
- 原方案内置的若干非公开插件：不随本项目分发。

## 4. 构建期工具（不随产物分发）

`mkfs.erofs` / `fsck.erofs`（erofs-utils，GPL-2.0/LGPL-2.1）、`zstd`（BSD/GPL-2.0 双许可）、
Android SDK build-tools、Gradle、Kotlin/Compose（Apache-2.0）——仅开发与打包时使用。

---

> 本文件会随依赖变化更新。**引入任何新第三方组件前，请先确认其许可并补进本文件**；
> 发布工具会在打包时断言禁止路径（见 `rootfs/layer-spec.sh` §7），确保无许可代码不会混进分层产物。
