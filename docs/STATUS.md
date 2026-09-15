# 项目状态总账（整理用）

> 用途：一眼看清**已经有什么、验证到什么程度、哪些做不到、下一步该干什么**。
> 更新的原则：**只写有证据的**。凡未实测的都标"未验证"，不写成已完成。

最后整理时间：2026-09-15

---

## 一、一句话现状

**所有可构建的产物都已产出并逐项验证**；**唯一没做的是真机端到端**——
而真机验证需要你执行（见 §五：我这条设备通道被策略禁止安装与挂载）。
新一批需求（单色界面 / npm 频道 / 插件 UI / dist-tag 选择 / 模块更新）**代码已落地，产物待重打**。

---

## 二、交付物（`dist/`，哈希见 `dist/MANIFEST.txt`）

| 产物 | 说明 | 状态 |
|---|---|---|
| `dshroid-launcher-debug.apk` | Android 启动器（Compose M3） | ⚠️ 单色化后**待重建**（源码已通过编译） |
| `dshroid-module-0.1.0.zip` | KernelSU 模块（开机自启 + 挂载探测 + 模块 WebUI） | ⚠️ 单色化后**待重打** |
| `base-24.04.3-l1.erofs.{zst,gz}` | base 层 | ✅ 已产出并验证 |
| `runtime-1.0.0.erofs.{zst,gz}` | runtime 层（Node 24.21.0 + pnpm 12.4.2 + 入口脚本） | ✅ |
| `dsh-0.1.5-rc.2.erofs.{zst,gz}` | dsh 层（DSH + 191 依赖 + profile 工作区） | ✅ |
| `channel/` | **已签名、可发布的频道**（三层两式 + 清单 + 签名） | ✅ 全链路跑通 |
| `dshroid-seed-*.tar.zst` | 离线种子（ubuntu-base + Node 官方包） | ✅ |
| `proot-bundle-arm64.tar.gz` | 非 root 模式的 proot（GPLv2 合规，随附许可与 SOURCE） | ✅ |

**分发体积**：全量 **96.3 MB**（zstd）；**DSH 单层升级 31.2 MB**。

---

## 三、已验证的部分（都有实测证据）

### 3.1 自动化测试（全部可复跑）

| 测试 | 断言数 | 结果 |
|---|---|---|
| `runtime/root/selftest.sh` | 19 | ✅ |
| `runtime/proot/selftest.sh` | 18 | ✅ |
| `module/webroot/selftest.mjs`（WebUI 纯函数） | 41 | ✅ |
| `tools/cmp-consistency.mjs`（三方版本比较） | 16 | ✅ |
| `tools/contract-check.mjs`（status JSON 契约，root+proot） | — | ✅ 双通过 |
| App 单测（解压 13 + 归因 8） | 21 | ✅ |

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

---

## 五、需要你做的（唯一的阻塞点）

按 `docs/smoke-test.md` 走，**第一条最关键**：

```bash
su -c '/data/linux/bin/linuxctl doctor'
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

1. **重打两个产物**：单色化后的 APK 与模块 zip（源码已通过编译，只差打包）；
2. **你在真机跑 `doctor`** → 我据此修真机问题（最可能是 toybox mount 的降级分支）；
3. 真机通过后再考虑：局域网访问、脚本自更新、更多频道的实测。
