# 视角地图：同一台机器上的三个挂载命名空间（以及由此而来的坑）

> 面向两拨读者：
> - **人和 AI**：`module/share/地图-视角.md` 是随模块发布的**同一份内容的用户版**，
>   开机被投递到 `$LINUX_HOME/share/地图-视角.md`（环境里 = `/share/地图-视角.md`）
>   与 `$LINUX_HOME/README-地图.md`（MT 里一眼能看到）。
> - **改代码的人**：本文多讲"为什么"与"怎么验"。
>
> 动态版（按你此刻所在的位置给结论）：`linuxctl whereami` / `linuxctl whereami --json`。

## 1. 一条实测出来的事实

同一台机器（OnePlus PJD110 / Android 16）上，**同一个路径字符串在不同视角下指向不同目录**：

| 视角 | mount ns（实测） | `/data/sunsetlinux` 是 |
|---|---|---|
| 全局（pid 1） | `mnt:[4026532884]` | 真的那棵（含 `upper.img`、`dirs-upper`、`bin`） |
| 设备 shell（ksu su） | `mnt:[4026536055]` | 同一棵树，但**看不到环境私有挂载**（`upper/` 是空挂载点） |
| SunsetLinux 环境（`unshare -m`） | `mnt:[4026535677]` | **没有这个路径**（chroot 里看不到 `/data`） |
| DSHA 的 proot Ubuntu（AI 会话常在这） | 随容器 | ⚠️ **影子目录**：`/data` 没被 bind ⇒ 落到 rootfs 里的 `data/` |

**硬证据**：真目录 inode `1652135`、影子目录 inode `2909534` —— 同一个 f2fs 分区（device `65103`），
**同名不同物**。只看路径分辨不出来，所以必须有一个"自报家门"的命令（`whereami`）。

影子视图的来源（实锤）：DSHA 壳启动 proot 的命令行里 bind 了
`/dev /proc /sys /system /apex`、`/storage/emulated/0 → /sdcard`，
**没有 `/data`** ⇒ proot 把 `/data/...` 解析到 `--rootfs=…/ubuntu` 里的 `data/`。

## 2. 路径地图（环境内部 ↔ 宿主）

overlay 的真实入参（环境启动日志里那行，可直接 grep）：

```
mount -t overlay overlay -o lowerdir=…/layers-mnt/dsh:…/layers-mnt/runtime:…/layers-mnt/base,
      upperdir=/data/sunsetlinux/upper/upper, workdir=/data/sunsetlinux/upper/work
      /data/sunsetlinux/rootfs
```

| 环境内部 | 宿主 / MT | 说明 |
|---|---|---|
| `/` | `$LINUX_HOME/rootfs` | overlay 合成（三层只读 + 可写层） |
| 写入的任何文件 | `$LINUX_HOME/upper/upper/…` | 可写层（`upper.img` 里的 `upper/`）。双 `upper` = 挂载点 + upperdir 目录名 |
| `/mnt/sdcard` | `/storage/emulated/0` | 共享存储的**用户存储根**（`/storage/emulated/0` 是指向它的软链）。Download = `/mnt/sdcard/Download` |
| `/share` | `$LINUX_HOME/share` | **交换目录**（见 §3） |
| `/run` | `$LINUX_HOME/run` | 状态与日志（同一批 inode） |

## 3. 交换目录（`/share` ↔ `$LINUX_HOME/share`）

- 实现：`start.sh` 的 `mount_share()`，在 sdcard 之后 `rbind`，权限 `0777`，**失败只 warn 不 die**
  （少一个便利目录不该让环境起不来）。
- 为什么需要：可写层在 `upper.img` 里、只有环境自己的 ns 可见；共享存储虽然挂了，
  但和"工作区"是两回事。给一条**两侧都固定、MT 里能直接进**的通道，人和 AI 都不用猜路径。
- 地图就放在这里首次落地（`post-fs-data.sh` 投递），所以"进容器第一眼"就能看到说明。

## 4. 环境内 DSH 的权限模型：默认 `danger-full-access`

- DSH 的沙箱与审批是**一条变量**决定的（`dsh-base` 组合原文）：

  ```yaml
  - id: approval
    name: '@deepseek-ai/dsh-user-approval'
    config:
      policy: !!js "(process.env.DSH_PERMISSION_MODE ?? 'workspace-write') === 'danger-full-access' ? 'never' : 'ask'"
  ```

  不设置 = `workspace-write` + `ask`（**逐条提权**）；设成 `danger-full-access` = 无沙箱 + 不询问。
- 这台机器上 `workspace-write` 的意义是**零**：没有 `bwrap`，Landlock 也未暴露
  （`/sys/kernel/security/lsm` 不存在）⇒ 沙箱只能 fail-closed 把命令拒掉。
- 而环境本身是"真 chroot + 真 root"：进程 uid 0、SELinux 域 `u:r:ksu:s0`。
  **这不是安全边界**（`/proc/1/root` 通着，root 进程可以走出去）—— 所以"提权"从来不是缺的东西，
  缺的是**可见性、约定与审计**。
- ⇒ `supervise.sh` 在加载 `$DSH_HOME/env` **之后**兜底：
  `DSH_PERMISSION_MODE` 未设置时给 `danger-full-access`，并写一行日志说明怎么改回去。
  用户写在 `env` 文件里的值优先（顺序有自测钉住）。
- 壳（DSHA）对容器里的会话设的也是 `danger-full-access`（实测：启动命令行里
  `export DSH_PERMISSION_MODE='danger-full-access'`）——两边行为因此一致。

## 5. 挂载隔离：`unshare -m` 在这台机器上可能没隔住

- `start.sh` 只 `unshare -m -u`，并且**不 unshare net / pid**（进 netns 会断网；内核无 `CONFIG_PID_NS`）。
- toybox 的 `unshare` 不认 `--propagation`（启动日志里那句"未能设为 rslave（toybox 不支持该选项）"），
  于是当 `/` 是 **shared** 时，环境的挂载会**回流到全局命名空间** ——
  这正是"MT 能看见 `upper/upper`、而设备 shell 看不见"的最可能解释。
- 判据（`doctor.sh` §1f，两条都可判定、不靠猜）：
  1. 影子视图：`$LH` 在但没有 `upper.img`/`dirs-upper`/`bin` ⇒ warn；
  2. 回流：环境在跑时 `grep -c 'sunsetlinux/rootfs' /proc/1/mountinfo` > 0 ⇒ warn
     （命中数一并报出来）。
- 影响：两个环境同时启动会互踩挂载。真要做严格隔离，得自己实现 propagation
  （`mount --make-rprivate /` 之类），这是**未做**的待办。

## 6. 怎么验

| 验什么 | 命令 |
|---|---|
| 我在哪个视角 | `linuxctl whereami`（环境内/宿主都可跑；只读，不建任何目录） |
| 视角判定的行为回归 | `bash runtime/root/selftest.sh`（含 shadow/host 两种形态 + 只读性 + 参数校验） |
| 交换目录 | 环境里 `ls /share`；宿主 `ls $LINUX_HOME/share`；`doctor` §1f 会报挂没挂上 |
| 权限默认值 | 环境里 `echo $DSH_PERMISSION_MODE`；`ls /proc/$(pgrep -f 'dsh web')/environ` 里能看到 |
| 回流 | 环境跑着时在 MT 终端 `grep sunsetlinux /proc/mounts`；或 `linuxctl doctor` 看 §1f |
