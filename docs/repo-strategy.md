# 仓库策略：为什么是单仓（monorepo）

> 这份文档记录一个**已做的决策**及其依据，避免以后反复推演。
> 结论：**一个仓库**。不要为了"分开管理"而拆成模块仓 / 前端仓 / 合并仓。

---

## 一、决策

```
sunsetlinux/            ← 就这一个仓库
  module/           # KernelSU 模块（含 webroot/ 模块 WebUI、lib/ 挂载探测）
  runtime/          # 运行时：linuxctl / start / stop / doctor / update / entry / supervise
    root/           #   root 模式（真 chroot + overlay）
    proot/          #   非 root 降级模式
    common/         #   status JSON 生成、健康探针（两模式共用）
  rootfs/           # 层构建配方 + layer-spec.sh（★ 唯一事实源）
  app/              # Android 前端（Kotlin + Compose）
  tools/            # 频道签名/清单、契约校验、一致性测试、种子制作
  docs/             # findings / architecture / dsh-profile / STATUS / install / updates / smoke-test …
  dist/             # 构建产物（.gitignore 排除；发布走 Releases + 频道）
```

**模块、前端都不是独立组件**，它们与运行时是同一套契约的两端；产物可以分开发布，源码不分开维护。

---

## 二、依据（实测，不是习惯）

### 2.1 模块包是从运行时**生成**出来的

```
module/mkmodule.sh
  BIN_SRC_DIR="$REPO_DIR/runtime/root"        # 模块的 bin/ = glob 这个目录
  + runtime/common/                           # status_json.sh / http_health.sh
  + rootfs/layer-spec.sh                      # 唯一事实源（linuxctl 会 source 它）
```

模块 zip = `module/` + `runtime/root/*.sh` + `runtime/common/` + `rootfs/layer-spec.sh`。
**若把模块拆出去，它必须 vendor 或 submodule 这套运行时** —— 同一个脚本出现在两处，
就是本项目已经踩过三次的"两份清单必然漂移"。

### 2.2 App 与运行时之间是**冻结契约**级耦合

- `app/.../core/LinuxCtl.kt` 里 **22 处**引用 `linuxctl` / `status`；
- 解析的是 [`architecture.md`](architecture.md) **§3.1 冻结 schema**（键不可省略）；
- 频道清单是 **§5.2 冻结 schema**，由 `tools/channel` 产出、App 消费；
- **三份状态文件由 App 与模块 WebUI 共读共写**：
  `etc/config.json`（开机自启）、`etc/channels.json`、`etc/state.json`。

跨仓之后，每次改动都要维护一张 "App vX ↔ runtime vY ↔ module vZ" 的兼容矩阵。

### 2.3 本项目实测到的 bug，几乎全是契约漂移

| 实测问题 | 本质 |
|---|---|
| `update.sh` 没被打进模块包（WebUI 更新功能会失效） | 打包清单与运行时脱节 |
| `lib/detect-mount.sh` 没进包；`lib/` 没同步到 `$LINUX_HOME/lib` | 同上（同一类连续三次） |
| App 调 `linuxctl update` 不传 `--version` → 版本命名/回滚失效 | App ↔ runtime 契约 |
| proot 版 `status` 缺 `base_url` → 两套实现不一致 | 实现 ↔ 冻结 schema |
| 三处版本比较算法不一致 → 发布清单指向**较旧的层** | 复制实现漂移 |
| App 的失败归因只认英文、运行时文案是中文 → 挂载失败显示成"启动失败" | 文案契约 |

**这些在单仓里是"一次提交 + 一次 CI"就能兜住的；拆成三仓，每一条都会变成跨仓协调。**
而它们的共同特征是**不报错、只静默失效**——正是最难发现、代价最高的一类。

### 2.4 规模上不需要拆

| 目录 | 大小 |
|---|---|
| `module` | 139 K |
| `runtime/root` | 236 K |
| `rootfs` | 179 K |
| `tools` | 266 K |
| `app` | 70 M —— **其中绝大部分是 `app/build/` 构建产物（已 gitignore）** |

App 的真实源码约 **36 个 Kotlin 文件 / 9k 行**。真正大的只有产物，而产物本就该走 Releases。

---

## 三、"三仓 + 合并仓"为什么不划算

拆成「模块仓 / 前端仓 / 合并仓」是**两头都亏**：

- **维护成本 ×3**：三处同步、issue/PR 分散、CI 跑三遍；
- **合并仓必然滞后**：有人在分仓提了修复，合并仓就成了过期假象；
- **收益为零**：你想要的"模块单独可见/可装"用 **tag + Release 资产**就能实现（见 §四）。

---

## 四、不需要第二个仓库就能做到的几件事

| 想要的效果 | 单仓下的做法 |
|---|---|
| 只要模块的人不装 App | `git tag module-v0.1.0` → 把 `sunsetlinux-module-0.1.0.zip` 挂到该 Release；KernelSU 用户只下这个 zip |
| 模块独立发版 | 打 `module-*` tag；模块版本与 App 版本解耦（各自 semver） |
| 只发前端 | 打 `app-*` tag，只挂 APK |
| **用户侧更新** | 走**已建好的签名频道**（HTTP 频道 + npm 频道），比"多仓库"更强：验签失败一律拒绝（已实测篡改/冒签都会被拒） |
| 第三方分发 | 任何人 `keygen → 构建层 → gen-manifest → sign → npm publish`，填「包名 + 公钥 + 指纹」即可订阅 |

**发布路径**：`tag` → CI 构建（APK / 模块 zip / 三层）→ 挂 Release → `gen-manifest` → `sign` → 发布频道。

---

## 五、什么时候**才**该拆（判据）

满足任一条再考虑，且**一次只拆一个**：

- ✅ 模块出现**独立维护者或独立受众**，需要完全脱离 App 发版；
- ✅ 模块的发布节奏与 App **无法协调**（例如模块进 KernelSU 官方仓库）；
- ✅ 许可出现分歧（**当前不存在**：上游 DSHA / DeepSeekHarness 与本项目都是 MIT）；
- ✅ 单仓 clone/CI 慢到影响开发（**当前不存在**）。

### 拆的时候怎么才不痛

**成本已经被压住了** —— 因为我们提前把契约文档化并冻结：

- `architecture.md` **§3.1** status JSON schema（冻结，键不可省略）
- `architecture.md` **§5.2** 频道清单 schema（冻结，三方共用）
- `rootfs/layer-spec.sh` —— 层命名/压缩/版本/禁 path 断言的**唯一事实源**
- `tools/contract-check.mjs` / `tools/cmp-consistency.mjs` —— 契约与一致性的**可执行断言**

所以真要拆，是"移目录 + 改 CI + 把 `layer-spec.sh` 作为独立包发布"，
而**不是"重新对齐接口"**。建议顺序：先把 `rootfs/layer-spec.sh` 抽成可独立引用的包，
再考虑拆模块。

---

## 六、关于"前端"

本项目有**两个前端**，且它们与运行时的耦合同样紧：

| 前端 | 位置 | 与谁共享状态 |
|---|---|---|
| Android App（Compose） | `app/` | `config.json` / `channels.json` / `state.json` |
| 模块 WebUI（`webroot/index.html`） | `module/webroot/` | 同上（**必须与 App 读写同一份，禁止各存一份**） |

两者还**共用同一套配色 token**（单色板），并**同时受传输差异影响**（App 走 `.zst`、WebUI/CLI 走 `.gz`，因为设备侧没有 zstd）。
把"前端"和"它驱动的运行时"分仓，等于把这类**必然同步**改成跨仓协调。**放一起。**

---

## 七、一句话总结

> **产物可以分开发布，源码不要分开放。**
> 什么时候拆，判据在 §五；现在不拆，因为耦合是"冻结契约 + 生成关系"级别的，
> 而这类耦合一旦跨仓，就会以"不报错、只静默失效"的方式持续咬人。
