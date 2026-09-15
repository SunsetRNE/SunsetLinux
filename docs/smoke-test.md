# 真机冒烟测试（照着跑，把输出发回来）

> **为什么需要你来跑**：开发用的设备 shell 通道有策略限制——**禁止挂载**、**禁止安装应用**
> （`pm install` 被明确拦截：「pm 只允许查询；安装、卸载、清数据和授权不开放给设备命令」），
> 也不允许用其它通道绕过。
>
> 所以下面这些步骤只能由你在 root 终端执行。**我不会绕过这条边界。**
> 你跑完把输出贴回来，我据此定位问题并继续修。

---

## 0. 准备

```bash
# 装启动器（我做不了这一步）
adb install -r dist/sunsetlinux-launcher-debug.apk
# 或把 APK 传到手机点击安装
```

**首次启动会让你选运行模式**（这一步是设计好的，不是出错）：

| 选择 | 后续 |
|---|---|
| **Root 模式**（能力完整） | 需要先装 **KernelSU 模块**（`dist/sunsetlinux-module-*.zip`）。模块负责**开机自启 + 让环境不依赖 App**。刷完重启，再回来部署层 |
| **非 root 模式**（兼容） | 走 proot 虚拟环境，免 root。无真 capabilities、绕不开 FUSE、环境随 App 进程 |

> 两条路径最终都用同一套 `linuxctl`，所以后面 §1–§7 的命令**完全一样**（`status.mode` 会告诉我是哪种）。

### 0.1 关于"要不要 metamodule"

KernelSU 已把**模块文件挂载**交给第三方 metamodule（本机装的是 `magic_mount_rs` v4.0.8-900）。
**我们的模块是纯脚本模块，不挂载任何系统路径，所以不需要 metamodule**——刷写时会打印这句结论。
`doctor` 里也会有一节专门报告探测结果。

装好后**先别急着点「开始部署」**，按下面顺序在 root 终端跑，逐段确认。

---

## 1. 自检（最先跑这个，它一次性告诉我们内核能力）

```bash
su -c '/data/sunsetlinux/bin/linuxctl doctor'
```

> ⚠️ **必须在 Android 侧的 root 终端跑**（也就是上面这条 `su -c` 的形式）。
> 别进环境里跑（`linuxctl exec -- doctor`）——环境内**看不到 `/data/adb`**
> （实测：proot 内只有 1 项，原生 root 有 19 项），那一节会显示"[不适用]"，不是环境坏了。

> 如果 `/data/sunsetlinux/bin/linuxctl` 还不存在（模块没刷或没部署过），先在 App 里完成一次 provision，
> 或者先跑 `device-provision.sh`（见 §2）。

**请把完整输出发我。** 我要看五件事：

- **§1 内核能力**：`CONFIG_EROFS_FS` 是否可用、是否确认没有 squashfs；
- **§1b 命令实现表**（★ 最关键）：`mount` / `unshare` / `chroot` / `losetup` 是不是 toybox 软链，
  以及各项选项支持度——**这决定了挂载树能不能跑起来**，是本机无法验证的头号风险；
- **§1c 挂载实现**：root 实现（Magisk / KernelSU+哪个 metamodule）、以及"本模块是否需要挂载"的结论；
- 有没有 `avc denied`；
- 结尾那一行 JSON。

---

## 2. 首次部署

```bash
# 有离线种子时（更快，且不依赖网络）
su -c '/data/sunsetlinux/bin/linuxctl provision --seed /data/sunsetlinux/seeds'
```

没有种子、让设备自己联网构建也可以（设备上**有** `/system/bin/mkfs.erofs`，能原生建层）。

**要确认的**：
- 三层是否都产出：`ls -la /data/sunsetlinux/layers/` → 期望看到
  `base-<ver>.erofs`、`runtime-<ver>.erofs`、`dsh-<ver>.erofs`
- `upper.img` 是否创建：`ls -la /data/sunsetlinux/upper.img`
- 若失败：把 `linuxctl logs` 和 `cache/.erofs-args`（mkfs.erofs 实际用的参数）发我。

---

## 3. 启动并取状态

```bash
su -c '/data/sunsetlinux/bin/linuxctl start'
su -c '/data/sunsetlinux/bin/linuxctl status'
```

**期望**：`state` 为 `running`、`dsh.healthy` 为 `true`、**`dsh.url` 里含 `?token=`**。

> ⚠️ `dsh.url` 必须带令牌。如果它是 `null` 或不含 `token`，**App 的 WebView 一定会 401**。
> 这时把 `cat /data/sunsetlinux/run/linux.log | tail -40` 发我 —— 里面应该有
> `dsh web: http://127.0.0.1:<port>/?token=...` 那一行。

---

## 4. 四项"证明这套方案有价值"的关键检查

这是**现有 proot 方案（DSHA）做不到的**，也是本项目的核心主张：

```bash
# ① 真 root（不是伪造的）
su -c '/data/sunsetlinux/bin/linuxctl exec -- id'
#    期望：uid=0(root)，而不是普通应用 uid

# ② 真 capabilities（DSHA 做不到）
su -c '/data/sunsetlinux/bin/linuxctl exec -- grep CapEff /proc/self/status'
#    期望：CapEff 非 0。DSHA 的 proot 是伪造 root，这里会是 0 或权限不足

# ③ 能真的挂载（DSHA 做不到）
su -c "/data/sunsetlinux/bin/linuxctl exec -- sh -c 'mkdir -p /mnt/t && mount -t tmpfs tmpfs /mnt/t && echo MOUNT_OK && umount /mnt/t'"
#    期望：打印 MOUNT_OK

# ④ sdcard 绕开 FUSE（DSHA 走 FUSE，慢）
su -c '/data/sunsetlinux/bin/linuxctl exec -- findmnt /mnt/sdcard'
#    期望：文件系统类型是 f2fs，**不是** fuse
```

---

## 5. 生命周期解耦（针对"环境随 App 一起死"）

```bash
# 先手动结束 App 进程
su -c 'am force-stop io.github.sunsetrne.sunsetlinux'
sleep 3
# 环境应该还在跑
su -c '/data/sunsetlinux/bin/linuxctl status'
#    期望：仍然是 running
```

---

## 6. 增量升级与恢复出厂

```bash
# 只换 dsh 层（应只下约 30 MB，不重下系统）
su -c '/data/sunsetlinux/bin/linuxctl update dsh /data/sunsetlinux/cache/dsh-<ver>.erofs'
su -c '/data/sunsetlinux/bin/linuxctl status'

# 恢复出厂（清空可写层）
su -c '/data/sunsetlinux/bin/linuxctl snapshot before-reset'
su -c '/data/sunsetlinux/bin/linuxctl reset'
su -c '/data/sunsetlinux/bin/linuxctl start'
```

---

## 7. 打开界面

点 App 的「DSH」tab（底栏）。**期望**：直接看到 DSH Web GUI（不是 401 纯文本）。

若显示 401：说明 App 加载的不是带令牌地址。把首页状态卡的原始 status JSON 发我。

### 7.1 冷启动与系统栏（用户实测过的问题，改完请重点确认）

| 看什么 | 期望 | 若不对，说明 |
|---|---|---|
| **首次安装后第一次打开** | 立刻出现「SunsetLinux / 首次启动：先选运行方式…」这一屏，**不再是一大块纯黑等半天** | 占位屏没生效（`ui/BootPlaceholder.kt`）或引导没被拉起 |
| 引导过程中按返回键退出，再打开 App | 又回到引导（或占位屏的「打开引导」按钮可点） | `Prefs.onboarded` 没被写 |
| **键盘**：在「插件」tab 输入包名 / 在设置里输入频道 URL | 输入框**在键盘上方可见**，不被盖住 | `imePadding` / `adjustResize` 没生效（告诉我机型与导航方式） |
| DSH Web 里的聊天输入框 | 同样不被键盘盖住 | 同上 |
| **返回手势**（从屏幕左/右边缘滑）：在「更新/插件」tab 上慢慢拖 | 内容是**跟着手指走**的（右移），松手才切回「启动」；中途松手取消会弹回 | `PredictiveBackHandler` 没生效，或又退回成抬手才响应的 `BackHandler` |
| **返回手势**：在 DSH Web 里（有网页历史时）拖动 | 网页跟着手指右移，松手后回到上一页 | 同上 |
| **返回键**：切到「更新」或「插件」tab 后按返回 | 回到「启动」tab，**不是直接退出 App** | 返回回调没生效 |
| 侧边栏打开时按返回 | 关掉侧边栏（而不是切 tab、也不是退出） | 抽屉的返回回调被抢 |
| 底部胶囊底栏 | 完整可见、不被系统导航栏压住；三键导航下也不重叠 | 导航栏 inset 没消费 —— **请把这张情况单独告诉我** |

---

## 8. 模块 WebUI（不用打开 App 也能管理）

在 **KernelSU 管理器 → 模块 → SunsetLinux** 里应该有 WebUI 入口（本机 KernelSU 支持 `webroot/`）。
Magisk 没有原生 WebUI —— 需要 MMRL / KsuWebUI 之类的宿主；**没有也不影响**，App 能做同样的事。

**请确认这几点**（我没法在真机渲染，只能靠你）：

- 页面能正常打开，**不是白屏**（若在普通浏览器里打开会显示"预览模式"横幅，这是设计好的）；
- 状态卡能显示 `state` / `mode` / 三层版本；带令牌的地址**默认打码**，点"复制"能复制完整地址；
- **启动 / 停止 / 重启**能真正生效（回到 App 看状态是否同步变化）；
- **开机自启开关**能写进 `etc/config.json`，并且 **App 设置页里看到的是同一个值**（两边共用一份状态）；
- **一键诊断**能跑出 `doctor` 输出；
- **检查更新 / 应用更新**：注意 WebUI 路径走 **`.gz` 产物**（设备上没有 zstd），
  界面里应注明"App 走 .zst 小 35%"；更新后回 App 看层版本是否变化。
- 日志页能显示 `run/linux.log` 尾部并能暂停。

> 若白屏：把管理器里的 WebView 调试打开（或换 MMRL 试），并告诉我 `ksu` 对象是否存在。

---

## 回传清单（按优先级）

| 优先级 | 内容 | 为什么 |
|---|---|---|
| ★★★ | §1 `doctor` 完整输出（含 §1b / §1c） | 决定挂载树能否工作的头号未知项 + 挂载实现探测 |
| ★★★ | §3 的 status JSON + `run/linux.log` 尾部 | 令牌链路是否真的通 |
| ★★☆ | §2 的 `ls -la /data/sunsetlinux/layers/` | 分层构建是否成功 |
| ★★☆ | §4 的 ①②③④ 输出 | 方案核心价值的证据 |
| ★☆☆ | §5、§6 结果 | 生命周期与更新语义 |

---

## 已知的、本机无法验证的阻塞项（先告诉你，免得意外）

1. **`mount`/`unshare` 是 toybox 版**：`/system/bin/mount` 等都是 toybox 软链，而挂载树用了
   util-linux 风格的长选项（`--rbind` / `--make-rslave`）。运行时已实现**能力探测 + 三级降级**，
   但真实支持面未知 → `doctor` 的 §1b 表会给答案。
   探测结果缓存在 `/data/sunsetlinux/run/cmdprobe`，可 `cat` 出来发我。
2. **Android 版 `mkfs.erofs` 的参数兼容性**：已做 5 级降级，实际用了哪一级见 `cache/.erofs-args`。
3. **erofs 作为 overlay lowerdir**：本机确认了内核有现成实例（`/product/overlay`），
   但没实际挂过我们的层。
4. **App 从未在真机运行过**：UI 观感、libsu 授权、WebView 令牌登录都只有构建级验证。
