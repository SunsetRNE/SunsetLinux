# 宿主通道（host channel）：把"已经存在的访问"变成有名字、有提示、有审计的一条路

> 状态：**已实现，默认关**。环境内侧 `runtime/root/host-channel.sh`；开关在宿主侧
> `linuxctl host-channel on|off`（写 `$LINUX_HOME/etc/host-channel.json`）。
> 相关：`docs/viewpoints.md`（视角地图）、`docs/STATUS.md` §3.10.58。

## 1. 先说清楚：这不是"提权"

用户的原话是「给虚拟环境提权，默认 Root」。实测下来，**权限早就有了**：

| 事实 | 证据 |
|---|---|
| 环境进程是宿主 **root** | `uid=0`；`/proc/<pid>/attr/current` = **`u:r:ksu:s0`**（KernelSU 的 root 域） |
| chroot **不是**安全边界 | 环境里 `/proc` 是宿主 PID 视图（内核无 `CONFIG_PID_NS`），`/proc/1/root` 可达；外面读 `/proc/1/root/data/sunsetlinux` 实测可读 |
| 所以"宿主访问"是既成事实 | 环境里的 root 进程随时可以 `chroot /proc/1/root` 走出去 |

⇒ 真正缺的不是权限，而是三件事：**可见性**（谁知道这条路存在）、**约定**（怎么走才算"正规"）、
**审计**（走了之后留下什么）。本通道就是把这三件事补上。

⚠️ 顺带一个必须写下来的边界：**本项目自己的 AI 会话（DSHA 容器）不会使用这条通道去执行
设备 shell 守卫拒绝过的命令** —— 那属于"换入口绕过设备策略"。通道是给**用户自己的环境**
用的产品能力。

## 2. 设计

```
环境内部                                宿主侧
─────────────────────────────────────  ──────────────────────────────────────
/opt/sunsetlinux/host-channel.sh       $LINUX_HOME/etc/host-channel.json   ← 开关（环境里看不到）
  ├─ status                             {"enabled": false,
  └─ run <cmd...>                          "allow": ["/data/sunsetlinux/share",
        │                                                    "/storage/emulated/0"]}
        │                                $LINUX_HOME/run/host-channel.log    ← 审计（环境里可读）
        ├─ ① 读开关（宿主侧文件）—— 关 ⇒ 退 3，**不执行**，仍写审计
        ├─ ② 白名单：命令里的绝对路径必须落在 allow 前缀下 ⇒ 否则退 4
        └─ ③ 执行：chroot /proc/1/root /system/bin/sh -c "<cmd>"，记审计
```

为什么开关放**宿主侧**：环境里的 agent 不该能自己把这条路打开。chroot 里 `/etc` 是环境自己的，
所以 `$LINUX_HOME/etc/host-channel.json` 在环境内**既看不到也改不到**；只有 App（su）或
用户在 MT 里能改。

## 3. 它挡得住什么、挡不住什么

| | 说明 |
|---|---|
| ✅ 挡得住 | "不知道自己有这条路"（status 会明说开关在宿主侧、怎么开）、"手滑打错路径"（白名单当场拒）、"偷偷跑"（每次调用一行审计，含被拒的） |
| ❌ 挡不住 | 决心绕过的人 —— 命令里用变量、相对路径、`$IFS` 都能绕过白名单；真正想做的人直接 `chroot /proc/1/root` 就行（**今天就能**，且不留痕） |
| ⇒ 因此定级 | **"防手滑 + 留痕"，不是"安全沙箱"**。别把它当边界用 |

## 4. 用法

宿主侧（MT / root shell / App）：

```sh
linuxctl host-channel status     # 看开关 + 最近 15 条审计
linuxctl host-channel on         # 打开（默认 allow 只有交换目录与共享存储）
linuxctl host-channel off        # 关掉
```

环境内部（agent / 终端）：

```sh
/opt/sunsetlinux/host-channel.sh status
/opt/sunsetlinux/host-channel.sh run 'cp /data/sunsetlinux/share/a.apk /data/local/tmp/'
```

审计行形状（`$LINUX_HOME/run/host-channel.log`，环境里也读得到 `/run/host-channel.log`）：

```
2026-09-19 02:41:07	run	cp /data/sunsetlinux/share/a.apk /data/local/tmp/	start
2026-09-19 02:41:07	run-done	cp …	rc=0
```

## 5. 为什么不用"宿主侧常驻 worker"（更干净的方案，但更重）

更"正统"的做法是：环境把请求写进 `$LINUX_HOME/run/host-queue/`，宿主侧的常驻进程
（`sunsetd` 内核，模块 1.0.36+ 已有）取走执行、回写结果。好处是不依赖 chroot 逃逸，
天然可审计、可限流、可以按请求类型做结构化白名单（"装 APK"、"读某路径"…）。

代价：要动 Kotlin 内核 + 协议 + 模块，属于一整轮的量。本轮先上"薄包装"版本把
**可见性/约定/审计**补齐；要不要升级成 worker 版，等真机用下来的感受再定
（这条写在待办里）。

## 6. 怎么验

```sh
# 默认关：run 必须退 3 且不执行
/opt/sunsetlinux/host-channel.sh run 'echo hi'; echo $?    # → 3

# 白名单：路径越界必须退 4
linuxctl host-channel on
/opt/sunsetlinux/host-channel.sh run 'cat /data/secret'; echo $?   # → 4

# 审计：上面两次都要留痕
tail -n 5 /data/sunsetlinux/run/host-channel.log
```

回归：`runtime/root/selftest.sh` 里有一节「宿主通道（默认关 + 白名单 + 审计）」，
钉住默认关、拒绝时留审计、白名单外退 4、白名单内不误拒、未知子命令报错、
以及"环境里真的能调到"（`start.sh` 会把脚本同步进 `/opt/sunsetlinux/`）。
