# SunsetLinux

**在你的 Android 手机上跑一个真正的 Ubuntu 环境，并在里面原生运行 [DSH（DeepSeek Harness）](https://www.npmjs.com/package/@deepseek-ai/dsh)。**

不是模拟器、不是"前缀替换"式的假 Linux：它是**真 root + 真 chroot + 真 `/proc` `/dev` `/tmp`**，
分层镜像（EROFS）只读挂载 + 一个可写层，关机重启后环境与你的数据都还在。
配套的 App 负责一键启动、看状态、开终端、装更新；环境本身由 KernelSU 模块在开机时拉起，
**不依赖 App 活着**。

> 当前版本：**App 0.3.23** · **模块 1.0.47** · 层内 DSH `0.1.6-alpha.2`
> 下载页：<https://sunsetrne.github.io/SunsetLinux/> · 全部产物：<https://github.com/SunsetRNE/SunsetLinux/releases/latest>

---

## 一、装上它能得到什么

| 你会得到 | 说明 |
|---|---|
| 一个真 Ubuntu 24.04 | `apt`、`node`、`python3`、`git` 都能用；`/` 是三层只读镜像 + 可写层的合成视图 |
| 里面的 DSH Web 界面 | 启动后浏览器打开 `http://127.0.0.1:3080`，就是完整的 DSH GUI（App 里也有入口） |
| 一个原生终端 | App 的「终端」页直接进环境（真 PTY：`Ctrl-C`、`vim`、`htop` 都能用） |
| 和手机文件互通 | 环境内 `/mnt/sdcard/Download` **就是**手机的 Download；`/share` 是和宿主对拷的交换目录 |
| 只下变化部分的更新 | base / runtime / dsh 三层分开，升级通常只下载几十 MB |
| 开机自启、与 App 解耦 | App 被系统杀掉或没打开，环境照常在跑（root 版） |

---

## 二、我需要准备什么

| 项目 | 要求 |
|---|---|
| 系统 | Android 8.0+（`minSdk 26`）。本项目在 **Android 16** 上验证 |
| Root | **root 版**需要真 root（**KernelSU** 已验证；Magisk / APatch 理论可行但未验证）。**免 root 版**什么都不需要 |
| 存储 | 建议预留 **1.5 GB**（首次实际下载约 95–100 MB） |
| 网络 | 首次部署需要联网；选「完整离线版」APK 则装完即可用 |
| 省电设置 | 关闭对 SunsetLinux 的电池优化；把 App 加入"冻结 / 墓碑 / 省电"类模块的**豁免名单**（否则 App 界面会刷不动状态） |

---

## 三、选哪个版本（两个 App 不等价，请按实际情况选）

|  | **SunsetLinux Root**（推荐） | **SunsetLinux 免root** |
|---|---|---|
| 桌面名 | SunsetLinux Root | SunsetLinux 免root |
| 需要 root | 是 | 否 |
| 环境 | 真 chroot + overlay 分层，**真 capabilities** | proot 沙箱，**没有真 capabilities** |
| 存活 | 模块开机自启，**与 App 生命周期解耦** | 随 App 进程，App 被清掉环境就停 |
| 能做什么 | 挂载、装系统包、跑任何 Linux 程序 | 日常命令、Node/Python 开发；绕不开 FUSE 限制 |

两个 App **包名不同、可以共存**（数据各放各的，互不影响）。

每个版本还各有 **3 个内置档位**，区别只是"APK 里预装了多少"，装完都能用：

| 档位 | 文件名的中间那段 | 适合 |
|---|---|---|
| 最小版 | `-minimal-` | 有 Wi-Fi，想要最小的 APK（层全部联网从频道装） |
| Ubuntu 版 | `-base-` | 想自己控制 DSH 版本（从频道装 dsh 层） |
| 完整离线版 | `-full-` | 想装完零下载直接用（APK 体积最大） |

---

## 四、安装（3 步）

**第 1 步 · 装 App**

到 [下载页](https://sunsetrne.github.io/SunsetLinux/) 或 [Releases](https://github.com/SunsetRNE/SunsetLinux/releases/latest) 取 APK，传到手机点安装（允许"安装未知来源应用"）。

- 有 root → `SunsetLinux-0.3.23-root-minimal-debug.apk`
- 没有 root → `SunsetLinux-0.3.23-proot-minimal-debug.apk`
- 想装完零下载 → 把上面的 `-minimal-` 换成 `-full-`

**第 2 步 · 刷模块（只有 root 版需要）**

用 KernelSU 管理器刷入 `sunsetlinux-module-1.0.47.zip` → **重启一次**。
（免 root 版跳过这步。）

**第 3 步 · 打开 App，跟着引导走**

root 版：检测 `su` → 刷入模块 → 重启 → 部署层 → 一键启动。
免 root 版：铺运行时 → 铺环境 → 启动。

第一次要下几十到一百多 MB 的层；之后启动是**秒级**的。

---

## 五、用起来

- **启动 / 停止**：App 首页的「一键启动」；停止就是「停止环境」。
  只想用终端不想起 DSH 时，用「仅启动环境」。
- **打开 DSH**：App 里的「打开 DSH」，或自己在浏览器开 `http://127.0.0.1:3080`
  （端口被占用时会自动让路，实际端口显示在 App 上）。
- **配模型与凭据**：在 DSH 自己的界面里配；也可以把 key 写进环境内的 `/root/.dsh/env`
  然后 `chmod 600 /root/.dsh/env`。**不要把 key 写在命令行里**（同机其它进程能看到）。
- **终端**：App 的「终端」页就是环境内的 shell（root 版是 `uid 0`）。
  进去先敲 `linuxctl whereami`，它会告诉你"你现在在哪个视角"，并打出路径地图。
- **和手机换文件**（两个方向，任选）：
  - **共享存储**：环境内 `/mnt/sdcard/Download` = 手机的 Download（`/storage/emulated/0` 是它的软链）
  - **交换目录**：环境内 `/share` ↔ 宿主 `/data/sunsetlinux/share`，两边同一批文件，0777
- **更新**：App 的「更新」页会检查频道（层 + DSH），一键装。层与 dsh 是分开的，只下变化的那一层。
- **出问题先看诊断**：App 的「诊断」页跑一遍 `linuxctl doctor`，把输出（或「导出排障包」）发出来就能定位。

---

## 六、常见问题

**Q：启动后一直"部署中 / 起不来"？**
看「诊断」页的结论。最常见两种：层还没装完（网络问题），或者设备把 App 冻了（去省电设置里豁免）。

**Q：环境里找不到手机上的文件？**
正确的路径是 `/mnt/sdcard/Download`。如果你用的是 **模块 1.0.46 之前**的版本，
`/mnt/sdcard` 挂错了层（挂成了"所有用户的父目录"），那里根本没有 `Download` —— 刷 1.0.47 即可修好。

**Q：DSH 界面提示端口被占用？**
环境会让路换端口（实际端口在 App 上显示）。不用手动改。

**Q：终端连上几秒就断开？**
这是 **0.3.23 之前**的已知缺陷（PTY 句柄被系统回收）。升到 0.3.23 即可。

**Q：环境里执行命令不问我"是否允许"？**
环境内的 DSH 默认是 `danger-full-access`（不询问）。这是刻意的——环境本来就是 uid 0，
而沙箱在这类内核上没有可用后端，问了也没有实际约束力。想改回来：在环境内编辑
`/root/.dsh/env` 加一行 `DSH_PERMISSION_MODE=workspace-write`，重启环境。

**Q：卸载 App 之后磁盘没释放？**
**这是设计如此**：环境与你的数据放在 App 私有目录之外，卸载 App / 换模块都不会删它们。
要真正清理，见 [docs/uninstall.md](docs/uninstall.md)：
先在宿主终端 `linuxctl stop` → 再 `rm -rf`（或 `linuxctl purge`），顺序反了会留下悬挂挂载。

**Q：免 root 版能当 root 用吗？**
不能。免 root 版是 proot 沙箱里**伪造**的 root，没有真 capabilities、不能挂载。
需要真 root 能力请用 Root 版。

---

## 七、安全须知（请读完再用）

- **chroot 不是安全边界**：环境内进程是 `uid 0`、SELinux 域是宿主的 root 域，
  且 `/proc/1/root` 可达。请把它当成"**你手机上的一个 root shell**"来对待，别在里面跑来路不明的脚本。
- **环境内的 DSH 默认全权**（不弹审批），原因与改法见上一个问题。
- **宿主通道默认关闭**：环境内想访问宿主文件需要你在宿主侧显式打开
  （`linuxctl host-channel on`），它带白名单与全量审计，定位是"防手滑 + 留痕"，**不是沙箱**。
- **卸载不自动删数据**：见上一节。里面可能有你的凭据与会话记录。
- 本项目**只用公开 npm 包**，不依赖任何私有代码；构建时会在 `layer-spec.sh` 里拦截违规路径。

---

## 八、卸载

1. 停环境（在宿主终端）：`linuxctl stop`
2. 确认没有残留（挂载 / 进程 / 端口 / 各目录大小）：`linuxctl footprint`
3. 删数据：`rm -rf /data/sunsetlinux`（或 `linuxctl purge`）
4. 卸载模块（KernelSU 管理器）与 App

细节与"为什么不能直接 `rm -rf`"见 [docs/uninstall.md](docs/uninstall.md)。

---

## 九、许可与致谢

- 本项目采用 **MIT**，见 [LICENSE](LICENSE)。
- 第三方组件、上游致谢与分发义务（proot 的 GPLv2 合规、DSH 侧插件许可等）见
  [THIRD_PARTY_NOTICES.md](THIRD_PARTY_NOTICES.md)。
- DSH 本体就是官方的 `@deepseek-ai/dsh`，本项目**不 fork 它**，只负责给它一个真实环境、
  把 profile 工作区按契约铺好，并把"版本与更新"做成可分发的分层产物。

---

## 十、给开发者 / 想深入的人

上面是"怎么用"。**实现细节、实测事实与踩过的坑**都在 `docs/`：

| 文档 | 内容 |
|---|---|
| [docs/STATUS.md](docs/STATUS.md) | 状态总账：交付了什么、验证到什么程度、**做不到什么**、下一步 |
| [docs/architecture.md](docs/architecture.md) | 架构与接口契约（`linuxctl` 命令、status JSON、频道清单、挂载树） |
| [docs/viewpoints.md](docs/viewpoints.md) | 视角地图：三个挂载命名空间、路径映射、权限模型 |
| [docs/findings.md](docs/findings.md) | 设备 / 内核实测事实（内核能力、EROFS 约束、体积实测）—— 排错先看 |
| [docs/dsh-profile.md](docs/dsh-profile.md) | DSH profile / 插件机制与已验证的 profile 配方 |
| [docs/host-channel.md](docs/host-channel.md) | 宿主通道：默认关的宿主访问通道、边界与审计 |
| [docs/updates.md](docs/updates.md) | 频道 / 更新 / 签名，面向用户与第三方发布者 |
| [docs/release-ci.md](docs/release-ci.md) | 分支式发布、回归门禁、层产物为什么不每次重建 |
| [docs/HANDOFF.md](docs/HANDOFF.md) | 交接：仓库 / CI / 密钥落在哪、怎么恢复上下文 |

**仓库结构**

```
sunsetlinux/
  docs/          # STATUS / HANDOFF / viewpoints / architecture / findings …
  rootfs/        # layer-spec.sh（唯一事实源）+ 分层构建 + 包列表 + profile 模板
  runtime/       # root 与 proot 两套运行时（linuxctl / start / supervise / doctor / selftest…）
  module/        # KernelSU 模块（开机自启、含 WebUI 与视角地图）
  app/           # Android 启动器（Compose，两个 edition × 三个档位）
  tools/         # 频道签名 / 清单、离线包、proot bundle、各类 CI 闸门
```

**跑回归**

```bash
bash runtime/root/selftest.sh          # 运行时脚本契约（159 项断言；bash 与 mksh 都要过）
cd app && LC_ALL=C.UTF-8 ./gradlew :app:testRootMinimalDebugUnitTest   # App 单测（304 项）
cd app && LC_ALL=C.UTF-8 ./gradlew :sunsetd:test                        # 内核单测（51 项）
```

> ⚠️ 仓库里测试函数名含中文，`sun.jnu.encoding` 由 locale 决定 —— 本机跑 Gradle 必须带
> `LC_ALL=C.UTF-8`，否则会以 `Internal compiler error` 失败（CI 天然是 UTF-8，无需处理）。
