# 内置（离线）包 · 工具与格式

给「**内嵌 Ubuntu / 多组合**」用的打包工具。产物是**离线包**（`.bin`）：一个 APK 或一个下载文件里
自带环境，装完不用联网就能起。

## 为什么不是"把整个 rootfs 拼成一坨"

本项目本来就是**三层 erofs + overlayfs**（base / runtime / dsh，见 `docs/architecture.md`）。
所以"组合"= **内嵌哪几个部件**，而不是重新打一个 rootfs：

| 部件 | 是什么 | 压缩后约 |
|---|---|---|
| `base` | Ubuntu 基础层（完整 rootfs） | 18.6 MB |
| `runtime` | Node + pnpm + 工具 | 46.5 MB |
| `dsh` | DSH 本体 + web profile | 31.2 MB |
| `proot` | 非 root 模式的 proot 运行时（自带依赖） | 0.9 MB |
| `proroot` | 非 root 模式的**首选**运行时（LD_PRELOAD，5 个 .so） | 随 APK 的 jniLibs，**不进 .bin** |

组合（**唯一事实源**是 `variants.json`，改表即可，工具与 CI 都读它）：

> **proroot 不进离线包（.bin）**：它是专有许可的 5 个 `.so`，只能随**完整 APK** 分发
> （见 `tools/proroot/README.md`），所以直接打在 APK 的 `jniLibs/arm64-v8a/` 里；
> 四个组合都带它，proot 仍作为降级实现内嵌在 `.bin` 里。

| 变体 id | 中文名 | 内嵌 | 离线包体积 | 说明 |
|---|---|---|---|---|
| `minimal` | 最小版 | proot | **0.9 MiB** | 只带 proot 运行时；Ubuntu 与 dsh 按需下载/导入 |
| `ubuntu` | Ubuntu 版 | base + runtime | **65.1 MiB** | 内嵌 Ubuntu 基础环境（root 模式可用） |
| `ubuntu-proot` | 免 root 版 | base + runtime + proot | **66.0 MiB** | root / 非 root 都能离线起步（不含 dsh） |
| `ubuntu-proot-dsh` | 完整离线版 | base + runtime + proot + dsh | **97.2 MiB** | 装完开机即用，零下载 |

**产物命名**（`app/VERSION-NOTES.md` 里也记了一份，定了就别随手改）：

| 产物 | 命名 | 例 |
|---|---|---|
| APK（四个组合各一份） | `SunsetLinux-<App 版本>-<变体>.apk` | `SunsetLinux-0.2.4-ubuntu-proot-dsh.apk` |
| 离线包 | `SunsetLinux-<App 版本>-<变体>.bin` | `SunsetLinux-0.2.4-minimal.bin` |
| KernelSU 模块 | `sunsetlinux-module-<模块版本>.zip` | `sunsetlinux-module-1.0.10.zip` |

> APK 是**同一个 App**（同包名、同签名、同 versionCode），换组合 = 覆盖安装，数据不丢。
> 本工具本地产出的中间名是 `<变体>.bin`；CI 发布前会重命名成上表的发布名
> （Gradle 内嵌任务两种名字都认，见 `app/app/build.gradle.kts` 的 `EmbedOfflineBundle`）。

> 体积是"内嵌进 APK 后 APK 的增量"（载荷就是压缩产物，不二次压缩）。
> 上游 DSHA 的思路一致：`offline-rootfs.bin`（base+runtime）与 `dsh-runtime.bin`（dsh）分包，
> 冷装两份都解、局部更新只读 dsh 包 —— 我们这里只是把"两份"推广成"按组合"。

## 容器格式 `sunsetlinux-bundle` v1（layout `layers-split-v1`）

```
偏移 0      4 字节   魔数 "SLB1"
偏移 4      4 字节   头长度 N（小端 uint32）
偏移 8      N 字节   头 JSON（UTF-8）：各部件 off/len/sha256 + 版本 + 变体名
偏移 8+N    载荷      各部件字节按头里顺序**原样**拼接
```

- `off` 相对**载荷起点**（= 8+N），头里因此不需要放"头自己多长"；
- 载荷是**原始分发产物**，一字节不改（打包只是搬运 + 记账）；
- 层的部件还带 `sha256_raw` / `size_raw`（解压后裸镜像的校验值），
  App 解压完能**当场核对**，不必等频道清单；
- 读端不需要新写解包器：校验 sha256 → 层交给既有解压 + `linuxctl update`，
  proot 解到 `$LINUX_HOME/proot`。

## 用法

```bash
# 打包（默认从 dist/ 找部件；.zst 优先、.gz 回退）
node tools/offline-bundle/mk-bundle.mjs --variant ubuntu-proot-dsh
node tools/offline-bundle/mk-bundle.mjs --all            # 四个变体全打（每个打完立刻校验）

# 校验 / 解包（读端的参照实现，App 侧照它写）
node tools/offline-bundle/mk-bundle.mjs --verify  dist/bundles/ubuntu.bin
node tools/offline-bundle/mk-bundle.mjs --extract dist/bundles/ubuntu.bin --out /tmp/x

# 回归（合成部件全链路 + 三类负例 + 真产物逐字节回验）
node tools/offline-bundle/selftest.mjs
```

产物：`dist/bundles/<变体>.bin` 与同名的 `<变体>.json`（给人看/给 CI 读的清单，含 sha256）。

CI：`.github/workflows/offline-bundle.yml` 从**频道清单**拉层（验签 + 逐层 sha256 校验），
打包四个变体并挂到 Release（`v<App 版本>`）—— 所以用户下不到"半成品"，
每一份都能对着清单核对。
