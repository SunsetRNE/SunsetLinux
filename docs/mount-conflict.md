# 挂载冲突：我们的 chroot 挂载 vs KernelSU 模块的自动挂载

> 结论先给：**不会冲突**。下面是判定依据、为什么"交给 metamodule 去挂"这条路走不通，
> 以及 `linuxctl doctor` 里 §1d 那五条证据各自在验什么。

## 1. 两套挂载分别是什么

| | KernelSU 的模块挂载 | 我们的环境挂载 |
|---|---|---|
| 谁执行 | **metamodule** 的 `metamount.sh`（启动时，root，优先于常规模块） | 我们模块的 `service.sh`/`linuxctl start`（late_start） |
| 挂载什么 | **常规模块目录里的 `system/`、`vendor/`、`product/`…** → overlay/重定向到 Android 系统路径 | 我们自己的 `layers/*.erofs`（loop+erofs）、`upper.img`（ext4）、再 overlay 到 `$LINUX_HOME/rootfs`，加 `/dev`、`/proc`、`/sys` 等 bind |
| 挂到哪 | `/system`、`/vendor`、`/product`、`/system_ext`、`/odm`…（**Android 系统路径**） | `$LINUX_HOME/layers-mnt/*`、`$LINUX_HOME/rootfs`、以及 rootfs 内部（**全在我们的目录下**） |
| 实现方式 | `meta-overlayfs` = overlayfs；`magic_mount_rs` = **内核级路径重定向**（不产生 mount 条目） | 真 mount：`mount -t erofs/ext4/overlay`、`-o bind` |
| 命名空间 | 全局（内核重定向对所有进程生效） | **我们自己 `unshare -m` 出来的私有 mount namespace** |
| 约定 | 挂载必须把 source 标成 `KSU`，KernelSU 才能识别/管理 | 不标 KSU（KSU 不认、也不管我们的挂载，正是我们要的） |

官方文档：KernelSU 的挂载能力**已经交给 metamodule**（[metamodule.md](https://github.com/tiann/KernelSU/blob/main/website/docs/guide/metamodule.md)），
metamodule 是**单实例**的，`metamount.sh` 的职责就是"把启用的常规模块 systemlessly 挂上系统路径"。

## 2. 为什么不会冲突（三条硬理由）

1. **目标路径不相交**：metamodule 动的是 `/system`、`/vendor`、`/product`…；我们动的是
   `/data/sunsetlinux/**`。两边**没有同一个挂载点**，也就不存在"谁覆盖谁"。
2. **命名空间不同**：我们的挂载在 `unshare -m` 出来的私有 namespace 里（`start.sh` 第 1 步，
   supervisor 与 PID 1 的 `/proc/<pid>/ns/mnt` 不同），**不会出现在全局挂载表里**，
   别人看不到、也卸载不到；反过来 metamodule 的内核级重定向对我们是透明的
   （我们在 chroot 里 bind 宿主 `/system` 时，看到的就是被模块 overlay 后的样子 —— 这是同向的，
   不是冲突）。
3. **我们的模块没有 `system/` 目录**：所以 metamodule 对我们的模块**无事可做**，
   连"要不要挂"这一步都不存在。这也是 doctor §1c 的 `module_needs_mount` 在查的东西
   （真机曾误报成"是"，根因与修复见 `docs/STATUS.md` §3.10.18）。

另外两条工程上的隔离：

- **loop 设备**：我们只通过 `losetup -f` 取**空闲**设备，且在私有 namespace 里使用；
  别人的 loop（例如某个用 ext4 镜像的模块）不会被我们抢。
- **卸载**：我们的回滚只卸载**自己记录过**的路径（`run/mounts` 逐条反向 umount），
  从不 `umount -a`，也不会碰 `/system` 等全局挂载点。

## 3. 为什么不能"把挂载交给 metamodule"

- metamodule 的钩子契约是"**模块目录 → Android 系统路径**"的 overlay/重定向，
  **没有**"把某个 erofs 挂到调用方指定目录"这种通用挂载服务；
- metamodule **单实例**：我们若自带一个 metamodule 去挂，会**顶掉用户已装的
  `magic_mount_rs`**，代价是设备上**所有其它模块**都不再被挂载（切换还要"卸全部模块→卸 metamodule→重启"）；
- 我们的挂载必须发生在**随后 chroot 的那个进程树所在的 namespace** 里；
  metamodule 在启动早期挂到全局，我们的进程树要看到它得靠 `nsenter` 加入别人的 namespace —— 逻辑上更绕、更脆。

一句话：**这不是"抢活干"，而是两件不同的事**。

## 4. doctor §1d：把"没有冲突"变成可验证的证据

`linuxctl doctor` 的 §1d 会逐条给出：

| 检查 | 判据 | 期望 |
|---|---|---|
| ④ 模块自身 | `$MODDIR` 下有没有 `system/`、`vendor/`、`.replace`… | 没有 → "metamodule 无事可做" |
| ① 命名空间隔离 | `readlink /proc/<supervisor>/ns/mnt` ≠ `readlink /proc/1/ns/mnt` | 不同 → 挂载在私有 ns |
| ② 全局泄漏 | `grep " $LINUX_HOME" /proc/1/mountinfo` 的条数 | 0 条 → 目标路径不相交 |
| ③ loop 占用 | `losetup -a` 里属于 `$LINUX_HOME` 的条数 vs 总数 | 只占空闲、不与人争 |
| ⑤ 运行期存续 | 运行中 `$ROOTFS_DIR/etc/os-release` 可读 | 可读 → 没被别的机制清掉 |

环境未运行时，①②⑤ 会打印"环境未运行 → 稍后再看"（不硬断言）。任何一条异常都会在结论里
标成 warn，并给出对应的 `[warn]` 行。

## 5. 如果将来真要"外置挂载"

比起交给 metamodule，更现实的是**给 start.sh 留一个 provider 钩子**：
`SUNSETLINUX_MOUNT_PROVIDER=<脚本>` —— 由它把三层准备好到 `$LINUX_HOME/layers-mnt/{base,runtime,dsh}`
并给出 upper，我们只做 bind + chroot + entry。这样：
- 第三方 chroot 管理器/自研脚本/将来 KernelSU 若提供通用 mount API，都能接进来；
- 默认实现仍是现在这套（`erofs` + overlay），接口固定但实现可替换。

这条**还没做**（需要时才做）；另外还有一条"少碰内核资源"的备选：
层解包成目录 + 只 chroot/bind（**完全不碰 loop 与 overlay**），代价是约 1.6 GB 磁盘、
层更新要重新解包，换来的是挂载面从十几个操作降到 3 个 bind。
