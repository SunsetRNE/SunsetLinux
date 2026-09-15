# 部署指南

本文件说明如何把 SunsetLinux 装到设备上并跑起来。

> **关于本项目的开发方式，需要如实说明一件事**
>
> 开发过程中使用的设备 shell 通道有策略限制：**禁止执行挂载、块设备/分区操作、SELinux 修改、
> 卸载/清除应用数据等命令**，也不允许用其它通道绕过。
>
> 因此：所有涉及**挂载**的步骤（首次 provisioning 里的 `mount`/`chroot`、`linuxctl start`、
> 模块开机自启）**必须由你在自己的 root 终端执行，或由装好后的 KernelSU 模块在开机时自行完成**。
> 我们能做的是把产物（裁剪好的层、模块、APK、脚本）全部构建好，并验证不涉及挂载的部分。
> 这条边界不会被绕过。

---

## 0. 前置条件

| 项目 | 要求 |
|---|---|
| Android | 8.0+（`minSdk 26`）；本项目在 **Android 16** 上验证 |
| Root | root 模式需真 root（**KernelSU** 已验证；Magisk/APatch 理论可行但未验证） |
| 存储 | 建议预留 **1.5 GB**（三层 erofs 解压后约 500 MB + 可写层默认 8 GiB 稀疏；**实际下载只约 95–100 MB**） |
| 网络 | 首次部署需要；使用离线种子则不需要 |
| 其它 | 关闭对 SunsetLinux 的电池优化；把 SunsetLinux 加入冻结/省电类模块的**豁免名单** |

> **冻结豁免很重要**：设备上那类"墓碑调度 / 冻结"模块（按 App 分级 + cgroup freezer）会按 per-app 策略用
> cgroup freezer 冻进程。root 模式下环境本体虽不依赖 App 进程，但 App 被冻会导致状态卡与
> WebView 无法刷新，所以仍应把 SunsetLinux 加入豁免。

---

## 1. 安装 APK

产物：`dist/sunsetlinux-launcher-debug.apk`

```bash
# 在电脑上（已配置 adb）
adb install -r dist/sunsetlinux-launcher-debug.apk
```

或直接把 APK 传到手机点击安装。

首次启动后 App 会走**门禁引导**，让你显式选择运行模式（这一步是设计好的，不是出错）：

| 选择 | 后续流程 | 能力 |
|---|---|---|
| **Root 模式**（推荐） | 检测 `su` → 引导**安装 KernelSU 模块** → 重启 → 回到 App 部署层 → 启动 | 真 root + 真 chroot + overlay 分层。**真 capabilities、能挂载、环境不被 App 杀死** |
| **非 root 模式** | 解压/下载 proot 虚拟环境 → 启动 | 免 root，但**无真 capabilities、绕不开 FUSE、环境随 App 进程** |

> 引导页会**诚实写出两者的差距**——这两个模式**不等价**，别被"表面上都能跑起来"迷惑。
> 两条路径最终都收敛到同一套 `linuxctl`，之后 App 的行为完全一致（模式只体现在 `status.mode`）。

---

## 2. 首次部署（provisioning）

Provisioning 会：建目录树 → 铺三层 erofs → 创建可写层镜像 → 写入配置。
**这一步涉及挂载与 chroot，必须由 root 执行。**

> ⚠️ **先分清两件事**（真机上踩过）：`linuxctl provision` **只会**建目录树、`upper.img`、
> 写 `config.json`/`state.json` —— 它**不构建层**。层只有两个来源：**频道里已发布的层**（方式 C），
> 或者**设备侧原生构建**（方式 B，`device-provision.sh`）。只有 `linuxctl provision` 是跑不出环境的。

### 方式 A：App 向导

在 `ProvisionActivity` 里：选模式 → 选频道（或选"使用离线种子"）→ 点开始。

向导会分两步走，并在日志里说清每一步：

1. `su -c linuxctl provision` —— 建目录树 / `upper.img` / 配置（**它不构建层**）；
2. 若还缺层，**root 模式**会接着跑 `device-provision.sh`（设备侧原生构建，真 chroot 里装
   apt + npm；十几分钟到半小时，已有层会跳过）；**proot 模式**没有真 chroot，
   只能去「更新」页从频道装层，向导会直接这么提示。

> App **0.2.1 起**才是上面这个行为；0.2.0 及更早只调第 1 步 —— 在没有预置层的机器上会以
> "provision 失败"收场（那不是坏了，是缺"构建"这一步）。

### 方式 B：设备侧原生构建（在 root 终端执行，不需要 bash / Termux）

```bash
# 1) 首次部署入口：建目录树 + 在真 chroot 里装 Ubuntu base → Node+pnpm → DSH，
#    每层打成 erofs 只读镜像，最后建可写层 upper.img 并写 config/state。
sh /data/adb/modules/sunsetlinux/bin/device-provision.sh --seeds /data/sunsetlinux/seeds

# 2) 启动
/data/sunsetlinux/bin/linuxctl start

# 3) 看状态（stdout 是 JSON）
/data/sunsetlinux/bin/linuxctl status
```

- 种子（`ubuntu-base-*.tar.gz` + Node 官方 arm64 包）放 `--seeds` 指的目录；
  没有的话脚本会打印下载 URL，或直接用 `oneshot-setup.sh --run`（它会先下种子）。
- ★ **不需要 bash、不需要 Termux**（模块 1.0.6 起 `device-provision.sh` 是 mksh 原生的，
  设备自带的 `sh` 直接跑）。装的是旧模块会有两种表现：**1.0.5 及更早**报「需要 bash」；
  **1.0.5** 还能一路跑到 `printf: bad %q`（Android 的 mksh 没有 `%q`）——升到 1.0.6 即可。
- 这一步要跑 apt + npm，**十几分钟到半小时**；日志在 `/data/sunsetlinux/cache/provision.log`。

### 方式 C：从已发布的层安装（最快）

如果频道里已有构建好的层，可以直接下载安装，跳过在设备上跑 apt：

```bash
/data/sunsetlinux/bin/linuxctl update base    /data/sunsetlinux/cache/base-<ver>.erofs
/data/sunsetlinux/bin/linuxctl update runtime /data/sunsetlinux/cache/runtime-<ver>.erofs
/data/sunsetlinux/bin/linuxctl update dsh     /data/sunsetlinux/cache/dsh-<ver>.erofs
```

> ⚠️ `update` 只吃**解压后的裸 `.erofs`**（喂 `.zst`/`.gz` 会被明确拒绝，见 `docs/STATUS.md` §3.2）。

### 方式 D：懒人体检 / 一键部署（`oneshot-setup.sh`，**在哪都能跑**）

不想记上面那些步骤时用这个。它随模块一起装到设备上：

```bash
# 只体检（**只读，不改任何东西**，可以随便跑）：
sh /data/adb/modules/sunsetlinux/bin/oneshot-setup.sh

# 体检没问题就真部署（需要 root；会自动下缺的种子 → 真 chroot 建三层 → start → status）：
su -c 'sh /data/adb/modules/sunsetlinux/bin/oneshot-setup.sh --run'
```

它检查：身份与 shell（是否 root / 有没有 `su`）、模块是否装了、`linuxctl` 与
`device-provision.sh` 在不在、环境根与**三层只读镜像**齐不齐（"缺少层文件"就是这里报的）、
工具链（`chroot`/`mount`/`mkfs.erofs`/`mke2fs`…）、`/data` 剩余空间、网络、以及
**种子**（ubuntu-base + Node 官方包；缺了直接告诉你 URL，或在 `--run` 时替你下）。
每一项都给出"怎么修"，最后给一条可以直接复制的下一步命令。

**在 MT 管理器里跑也没问题**（`/storage/emulated/0/Download/` 里就能跑）：

| 事项 | 说明 |
|---|---|
| MT 的「系统」模式 | 用的是 `/system/bin/sh` = **mksh**，本脚本按 mksh 写并进了 `tools/shell-compat-check.mjs` 闸门 |
| MT 的「扩展包」模式 | 给的是 bash/ash，同样能跑（脚本只用 POSIX + `case`，不用数组/`[[ =~ ]]`/进程替换） |
| 为什么不是 `./oneshot-setup.sh` | `/sdcard` 是 FAT/exFAT，**没有可执行位** → 用 `sh 脚本` 跑 |
| 保存脚本时 | 存成 **LF** 换行；用会写 CRLF 的编辑器保存过，mksh 会报 `\r` 相关语法错 |
| 真的写东西时 | 一律落在 `/data`（`/data/sunsetlinux`），不会往 `/sdcard` 写 |

> `--run` 会在真 chroot 里跑 apt + npm（十几分钟到半小时），日志在
> `/data/sunsetlinux/run/linux.log`；中断了可以直接重跑（provisioning 是幂等的）。

---

## 3. 安装 KernelSU 模块（让环境开机自启、且不依赖 App）

这一步是"**环境不被 App 杀死**"的关键：模块在 `late_start` 调用 `linuxctl start`，
环境由 init 派生，App 被杀/被冻都不影响它。

1. 用**打包脚本**生成模块 zip（**不要**自己 `cd module && zip` —— 那样打出来的模块没有
   `bin/`、`lib/`、`webroot/`，装上去是残的：`post-fs-data.sh` 无事可做、
   `/data/sunsetlinux/bin/linuxctl` 永远不会出现、App 也就无法控制环境）：

   ```bash
   bash module/mkmodule.sh --version 0.1.0
   # 产物：dist/sunsetlinux-module-0.1.0.zip（+ .sha256）
   ```

   脚本会铺 `bin/`（运行时脚本 + `layer-spec.sh` + `selftest.sh` + 夹具）、
   `lib/`（`detect-mount.sh`）、`webroot/`（模块 WebUI），并在缺失关键项时**拒绝打包**。

2. 在 KernelSU 管理器里「安装模块」→ 选 `dist/sunsetlinux-module-<ver>.zip` → 重启。

3. 重启后验证：

   ```bash
   su -c '/data/sunsetlinux/bin/linuxctl status'
   ```

> 模块的 `uninstall.sh` **默认保留 `/data/sunsetlinux`**（不删你的数据和会话），只清理模块自身文件。

### 3.1 模块 WebUI（不用打开 App 也能管理）

- 形态：KernelSU 的**模块 WebUI**。在 **KernelSU 管理器 → 模块 → SunsetLinux** 里打开。
- **Magisk 没有原生 WebUI**，需要第三方宿主（**MMRL** / KsuWebUI）才能显示；
  **没有也不影响使用**——App 能做同样的事，两边读写同一份配置。
- 能做的事：看状态 / 启动·停止·重启 / **开机自启开关** / 日志尾部 / **一键诊断** / **检查并应用更新** / 回滚。
- 注意：WebUI 走 **`.gz` 产物**（设备上没有 zstd），App 走 `.zst`（小 35%）。
- 实现细节与 `ksu` JS API 见 [`ksu-webui-api.md`](ksu-webui-api.md)。

---

## 4. 验收清单

> 想要一份**照着跑、把输出发回来**的精简版，见 [`smoke-test.md`](smoke-test.md)（推荐先看这个）。

部署完成后逐条核对（对应 `docs/architecture.md` §8）：

| # | 检查 | 命令 | 期望 |
|---|---|---|---|
| 1 | 环境在跑 | `linuxctl status` | `state == "running"`，`dsh.healthy == true` |
| 2 | **真 root** | `linuxctl exec -- id` | `uid=0`，且 `grep CapEff /proc/self/status` 非 0 |
| 3 | 真 capabilities | `linuxctl exec -- mount -t tmpfs tmpfs /mnt/t && echo ok` | 输出 `ok` |
| 4 | 生命周期解耦 | 杀掉 App 进程后再 `linuxctl status` | 仍是 `running` |
| 5 | sdcard 绕开 FUSE | `linuxctl exec -- findmnt /mnt/sdcard` | 显示 `f2fs`，**不是** `fuse` |
| 6 | 增量升级 | 只替换 `dsh-<ver>.erofs` 重启 | DSH 版本变化，其它层不动（**只下约 28 MB**，实测） |
| 7 | 恢复出厂 | `linuxctl reset && linuxctl start` | 回到干净状态 |
| 8 | 第三方频道 | 添加自签频道 | 能列出并安装其发布的层 |

**第 2、3 条是本方案的核心价值证明**：现有 PRoot 方案这两条都做不到。

---

## 5. 关于 `dsh web` 的登录地址（容易踩的坑）

`dsh web` **对 `GET /` 默认返回 401**，必须用带令牌的地址：

```
http://127.0.0.1:3080/?token=<launchToken>
```

- 该令牌是**进程内随机生成、不落盘、每次重启都变**的，只能从 `dsh web` 的 stdout 抓取。
- `linuxctl` 会自动把它写到 `$LINUX_HOME/run/dsh.url`（**0600**），并放进 `status.dsh.url`。
- **App 每次打开 WebView 都要重新读 `status.dsh.url`**，不要缓存。
- Cookie 绑定 `Host` 授权域：地址必须始终是 `127.0.0.1:<port>`，
  不要换成 `localhost:<port>`，否则 Cookie 不匹配 → 401。

想在电脑浏览器上看 DSH GUI，用端口转发（不要改绑定地址）：

```bash
adb forward tcp:3080 tcp:3080
# 然后手动把 status.dsh.url 里的 127.0.0.1 换成 localhost 打开——
# 注意此时 Cookie 的 authority 变了，需要重新用带 token 的 URL 访问一次
```

---

## 6. 常见问题

**Q: WebView 打开是一片 401 纯文本**
A: 你加载的是裸地址。必须加载 `status.dsh.url`（带 `?token=`）。见 §5。

**Q: `status` 里 `dsh.url` 是 `null`**
A: 环境刚启动、`dsh web` 还没打印 URL。等 2–5 秒重试；仍不行看 `linuxctl logs`
里有没有 `dsh web: http://...` 那一行。

**Q: `linuxctl start` 报挂载失败**
A: 跑 `/data/sunsetlinux/bin/linuxctl doctor`。常见原因：上次异常退出留下半挂载状态
（`stop` 会尽量清理）、`upper.img` 损坏、或内核不支持某个挂载选项。
把 `doctor` 输出和 `linuxctl logs` 一起看。

**Q: 环境起来一会就没了 / 被冻**
A: 先把 SunsetLinux 加入**后台冻结类模块**的豁免名单，并关闭电池优化（见 §0）。
root 模式下环境本体不依附 App 进程，但**系统级的冻结仍可能影响 App 的状态显示与通知**。

**Q: 手机很烫之后，环境/进程直接被系统杀掉了 —— 有办法避免吗？**
A: **没有彻底的办法，这是 Android 系统级行为**（热降频 + 低内存/thermal 守护进程），
应用层设计挡不住。要如实说明：

- root 模式的价值是**降低被误杀的次数**（环境由 KernelSU 模块在开机时拉起、
  **不依附 App 进程**，App 被冻结或被杀不影响它），**但它不能免疫系统级强杀**；
- 被强杀之后：`run/dsh.pid` 会变成陈旧 PID，`linuxctl status` 会据此判定为 `error`
  并给出"环境进程已退出"的原因 —— **可见性是有的，自动恢复没有**。
  要做自动重启就得有守护进程，而守护进程同样会被杀；
- **实用建议**：不要在手机上跑长时间高负载任务（例如大型 `npm install`、编译构建）。
  这类负载正是触发降频→强杀的主因；把重活放到电脑上做。
- 若确实要长时间跑，注意散热（别放被子里/口袋里充电运行），并关闭不必要的前台应用。

**Q: root 模式不可用，只能 proot**
A: 先确认 `su` 可用；KernelSU 下需要在管理器里给 SunsetLinux 授权 root。
proot 模式是降级方案，能力差距见 [`../runtime/proot/README.md`](../runtime/proot/README.md)。

---

## 7. 卸载

| 目标 | 操作 |
|---|---|
| 只卸 App | 直接卸载。**环境与数据保留**（在 `/data/sunsetlinux`） |
| 卸模块、保留数据 | 在 KernelSU 管理器卸载模块。`/data/sunsetlinux` **保留** |
| 完全清除 | 手工 `rm -rf /data/sunsetlinux`（**会删除所有会话与配置，不可恢复**） |

> 对数据安全的承诺：升级只替换**只读层**，可写层（你的会话、配置、密钥）在 `upper.img` 里，
> **不会**因为升级或换频道而丢失。上线前建议 `linuxctl snapshot <name>` 备份可写层。
