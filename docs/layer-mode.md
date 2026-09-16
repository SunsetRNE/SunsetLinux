# 层模式：`loop`（默认）与 `dir`（不碰 loop 的兼容开关）

> 动机与"为什么不是交给 KernelSU 挂载"见 [`docs/mount-conflict.md`](mount-conflict.md)；
> 实现在 `runtime/root/start.sh` 的 `resolve_layer_mode()` / `materialize_dirs()` 与
> `build_mount_tree()` 的分支。

## 1. 两种模式各自做什么

| | `loop`（默认） | `dir` |
|---|---|---|
| 只读层 | `losetup` + `mount -t erofs` ×3 | `fsck.erofs --extract=DIR`（Android 自带）解到 `$LINUX_HOME/dirs/{base,runtime,dsh}` |
| 可写层 | `upper.img`（ext4 + loop） | `$LINUX_HOME/dirs-upper`（真目录） |
| 合并 | overlayfs（lowerdir=镜像挂出来的目录） | overlayfs（lowerdir=解开的目录） |
| 占用内核资源 | 4 loop + 3 erofs + 1 ext4 挂载 | **0 loop、0 镜像挂载**（只有 1 个 overlay + binds） |
| 磁盘 | ~550 MB（层镜像） | ~550 MB + **~1.6 GB**（解开的目录） |
| 首次启动耗时 | 秒级 | 解包 550 MB（设备上几分钟），有戳文件，之后幂等跳过 |

两者**共用**其余全部逻辑：目录骨架、DNS/时区抓取、entry.sh 同步、`/proc` `/sys` `/dev` `/sdcard` bind、
`unshare -m` 私有命名空间、chroot、回滚与 `run/mounts` 记录。所以切换模式不影响环境内的任何东西。

## 2. 怎么切（三处，优先级从高到低）

```bash
# ① 命令行（排障时最方便）
/data/sunsetlinux/bin/linuxctl start --layer-mode dir   # 需要 1.0.15+ 的 linuxctl 透传
#   或直接：LINUX_HOME=/data/sunsetlinux /system/bin/sh $LH/bin/start.sh --layer-mode dir

# ② 环境变量（App「设置 → 层模式」就是透传它）
SUNSETLINUX_LAYER_MODE=dir

# ③ 环境配置（持久化，手改即可）
$LINUX_HOME/etc/config.json  →  {"layer_mode": "dir"}
```

未设置时默认 `loop`（省磁盘）。**非法值立即报错**（`start.sh` 在参数解析后立刻校验），不会拖到挂载阶段。

## 3. 怎么验

```bash
# 本次 start 实际用的是哪个（status/doctor 是另一个进程，读 run/layer-mode）
cat /data/sunsetlinux/run/layer-mode
/data/sunsetlinux/bin/linuxctl status | grep -o '"layer_mode":"[a-z]*"'
/data/sunsetlinux/bin/linuxctl doctor        # §1e 层模式：解包器、三层戳是否与当前层文件一致、占用
```

dir 模式下应该看到：
- `run/layer-mode` = `dir`；
- **`losetup -a` 里没有我们（`$LINUX_HOME`）的条目** —— 这就是"不碰 loop"的直接证据；
- `$LINUX_HOME/dirs/{base,runtime,dsh}` 各有内容，`$LINUX_HOME/dirs/.<层>.stamp` 与当前层文件一致。

## 4. 什么时候用 dir

- `linuxctl doctor` §1b 显示 mount 选项探测异常、或 §2/§3 里 loop/erofs 相关项失败；
- `run/linux.log` 里卡在 `losetup` / `mount -t erofs` / `upper.img` 这几步；
- 设备上 loop 数量被别的模块占满、或 `/dev/loop*` 权限异常；
- 想彻底避开 loop 与镜像挂载带来的不确定性。

## 5. 回退与清理

- 切回 `loop`：把 `layer_mode` 改回 `loop`（或不设）即可，`dirs*/` 可以整个删掉：
  `rm -rf $LINUX_HOME/dirs $LINUX_HOME/dirs-upper $LINUX_HOME/dirs-work`。
- dir 模式下**不会**再有 `upper.img` 的写入；可写层就是 `dirs-upper/`（备份/迁移注意这一点）。
- 两层模式的 `state.json`/`layers/` 完全一样，**切换不会动层文件**，也不会丢环境数据（可写层位置不同，
  切换后"新写入"落在另一处；要保留写内容就切回原模式）。
