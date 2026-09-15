# 环境事实与约束（实测记录）

本文件只记录**实测得到**的事实，作为设计与排错依据。每条都标注了验证方式。

## 1. 设备与权限

| 项目 | 实测值 | 验证方式 |
|---|---|---|
| 机型 | OnePlus PJD110 | `/app/device` |
| Android | 16 (SDK 36) | `/app/device` |
| 内核 | `6.1.141-android14-11-o-g659d588fa0da` aarch64 | `uname -a` |
| 内存 / 存储 | 22.62 GB / 932 GB（可用 625 GB） | `/app/device` |
| Root | **KernelSU 真 root**，`context=u:r:ksu:s0`，可 `su` | `adb-shell id` |

## 2. 内核能力（决定容器形态）

来自 `zcat /proc/config.gz`：

启用：
- `CONFIG_NAMESPACES=y`、`CONFIG_UTS_NS=y`、`CONFIG_NET_NS=y`、`CONFIG_TIME_NS=y`
- `CONFIG_OVERLAY_FS=y`、`CONFIG_EXT4_FS=y`、`CONFIG_F2FS_FS=y`、`CONFIG_TMPFS=y`、`CONFIG_FUSE_FS=y`、`CONFIG_DEVTMPFS`（`/proc/filesystems` 含 devpts、virtiofs）
- `CONFIG_CGROUPS=y`、`CONFIG_MEMCG=y`、`CONFIG_CGROUP_FREEZER=y`、`CONFIG_CGROUP_SCHED=y`
- `CONFIG_SECCOMP=y`、`CONFIG_SECCOMP_FILTER=y`

**未启用（关键限制）：**
- ❌ `CONFIG_PID_NS is not set` → **无 PID 命名空间**
- ❌ `CONFIG_USER_NS is not set` → 无用户命名空间
- ❌ `CONFIG_SYSVIPC is not set`、`CONFIG_POSIX_MQUEUE is not set`

推论：
1. 可以做「**真 chroot + mount/UTS/NET 命名空间的监狱**」，但做不了完整 PID 命名空间容器。
2. **systemd 不可用**（依赖 PID ns 语义与统一 cgroup 委派），必须自备轻量 supervisor。
3. `CONFIG_SYSVIPC` 缺失导致 **`fakeroot` 无法运行**（faked 守护进程靠 SysV IPC 通信）。
4. 非 root 路线（proot）与真 root 路线必须分开设计，不能共用一套挂载逻辑。

### 2.1 只读文件系统：**只能用 EROFS，不能用 squashfs**（实测，曾导致方案返工）

这是本项目最容易踩的坑，务必先看：

```
# CONFIG_SQUASHFS is not set        ← 完全没有 squashfs 支持
```

- `zcat /proc/config.gz | grep -i SQUASHFS` → 只有上面这一行 `is not set`。
- `/proc/filesystems` **不含** squashfs。
- `/system/lib/modules/<kver>/` 下**没有** squashfs.ko → 也不是"模块没加载"。
- ⇒ **squashfs 层在这台机器上根本挂不起来**，原定的"squashfs 只读下层"方案直接失效。

**替代：EROFS**（Android 原生只读压缩文件系统）：

```
CONFIG_EROFS_FS=y
CONFIG_EROFS_FS_XATTR=y
CONFIG_EROFS_FS_POSIX_ACL=y
CONFIG_EROFS_FS_SECURITY=y
CONFIG_EROFS_FS_ZIP=y
# CONFIG_EROFS_FS_ZIP_LZMA is not set     ← 无 LZMA
（也没有 ZIP_DEFLATE / ZIP_ZSTD）
```

- `/proc/filesystems` 有 `erofs`；设备上实测有 **1742 个 erofs 挂载**（`/`、`/system_ext`、`/product` 都是）。
- **`overlayfs + erofs lowerdir` 在本机有现成实例**（`/product/overlay`、`/product/app` 等），
  说明"多层 erofs 作 lowerdir + 可写上层"这条路**内核已证明可行**。
- ⚠️ 本内核 EROFS **只支持 LZ4**（无 LZMA/deflate/zstd）。

**‼️ EROFS 的超级块在偏移 1024，不在偏移 0（实测踩过的 ship-blocker）**

```
真实 erofs 镜像：偏移 0    = 00 00 00 00   （← 空）
                 偏移 1024 = e2 e1 f5 e0   （= 0xE0F5E1E2，EROFS 魔数）
对照 squashfs  ：偏移 0    = 68 73 71 73   （= 'hsqs'，确实在开头）
```

- 所以**只读偏移 0 的格式探测会把每一个合法 erofs 层判成 unknown**，
  后果是 `linuxctl update` 拒收合法层、`start` 拒绝挂载 ——
  症状极具误导性：**"层明明是好的，却报格式不可识别"**。
- 这个 bug 只有用**真实产物**才会暴露：手工造的测试文件如果随便放了 magic，
  反而会"测通过"。这也是"必须真的产出一个层"的价值所在。
- 已修（三处都改为分别探测偏移 0 与偏移 1024）：`runtime/root/start.sh`、
  `runtime/root/doctor.sh`、`runtime/root/linuxctl.sh`。
- 已加**回归测试**：`bash runtime/root/selftest.sh`（11 项断言，含夹具偏移校验、
  三个函数的单元测试、`update` 端到端接受/拒绝），夹具在 `build/fixtures/*.bin`（各 2 KB）。
- 另一条**必须保持**的行为：压缩产物（`.erofs.zst`/`.gz`）应被 `linuxctl update` **拒绝**——
  解压是客户端（App）的职责，`linuxctl` 只接受可直接挂载的裸镜像。别把这条"修"掉。

**补充实测：`overlayfs` 的 `lowerdir` 必须是目录，不能直接把镜像文件当 lowerdir。**
所以每层要先用 `mount -t erofs -o loop,ro` 挂到 `layers-mnt/<id>/`，再用这些目录组 lowerdir。

**压缩策略（实测数据，据此定的默认值）** —— 同一份 node 二进制：

| 方式 | 体积 |
|---|---|
| `mkfs.erofs -z lz4` | 69.0 MB |
| `mkfs.erofs` 不压缩 | 116.4 MB |
| 不压缩的镜像再 `zstd -19` | **31.1 MB** |
| 不压缩的镜像再 `gzip -9` | 41.8 MB |
| （参照）`mksquashfs -comp zstd` | 38.2 MB（内核不支持，仅参考） |

⇒ **默认：镜像不压缩（`mkfs.erofs` 不传 `-z`），分发时整体 zstd 压缩（`.erofs.zst`）。**
分发体积才是用户痛点（"更新又拖又慢"）；flash 有 625 GB 不是瓶颈；不压缩的镜像读取还更快。
注意 `mkfs.erofs` 的 **`-z none` 不是合法取值**，不传 `-z` 才是不压缩。

**压缩后的层体积量级（squashfs-zstd 参照，仅供估算）**：

| 内容 | 源大小 | squashfs-zstd | 说明 |
|---|---|---|---|
| ubuntu-base 24.04.3 解包 | 109 MB | 25.4 MB（xz 22.1 / gzip 27.6 / lz4 38.5） | base 层量级 |
| node 二进制 | 116.4 MB | 38.2 MB | runtime 层大头 |
| dsh 包（含 191 依赖） | 270 MB | 37.3 MB | dsh 层主体 |

## 3. DSHA 现状（被替代对象）

进程树实测（`ps -eo pid,ppid,user,args`）：

```
libproot.so -L --kill-on-exit -0 \
  --rootfs=/data/user/0/com.dsh.client/files/linux/ubuntu --cwd=/root \
  -b /dev -b /dev/urandom:/dev/random -b /proc -b /sys -b /system -b /apex \
  -b /proc/self/fd:/dev/fd \
  -b /storage/emulated/0:/sdcard -b /storage/emulated/0:/storage/emulated/0 \
  /bin/bash -c ... exec dsh web --no-open --host 127.0.0.1 --port 3080
```

| 事实 | 实测值 | 验证方式 |
|---|---|---|
| 应用包名 | `com.dsh.client`，uid **10497**（普通应用） | `ps` USER 列 |
| 技术底座 | **PRoot**（ptrace 系统调用翻译），随包携带 `libproot.so` | 进程命令行 |
| "root" | `id` 显示 `uid=0` 是 proot `-0` **伪造**；真实 uid = 10497 | `ps` 交叉验证 |
| rootfs | `/data/user/0/com.dsh.client/files/linux/ubuntu`，**16 GB，App 私有目录** | 原生 `du -sh` |
| 生命周期 | `--kill-on-exit`：Linux 环境随 App 进程一起死 | 进程参数 |
| 退出行为 | 依赖 `NODE_OPTIONS --import=.../startup-observer.cjs` 注入 + `dsha-startup-checkpoints/{healthy-1,healthy-2,healthy-3,candidate}.json` + `repair-builtin.log`（每次启动执行"内置插件注册修复"）+ `startup-recovery.py` | 文件系统实测 |
| 密钥卫生 | API key 出现在**进程命令行**中（`ps` 可见） | 进程参数 |

### 3.1 关于"proot 很慢"的澄清（实测，避免误判）

热缓存下遍历同一棵 `/system`（3643 文件）：

| 路径 | 实测 |
|---|---|
| proot 内 `find /system -type f` | 0.13 s |
| 原生 root（KernelSU）同一命令 | 0.70 s，其中 `adb-shell 'id'` 基线 0.54 s → **净 ≈ 0.17 s** |

**结论：本机 proot 的元数据遍历开销并不夸张**（现代 proot 有 seccomp 加速）。因此"卡顿"的主因不在 proot 的 syscall 翻译，而更可能是：进程创建、小文件写、App 生命周期（被系统限制后台 / 被冻结模块冻结）、以及补丁层自身的开销。设计时不应把"打 proot"当作性能方案。

### 3.2 冻结风险（需要规避）

设备上装有若干**墓碑调度 / 冻结类**的 KernelSU 模块（自述为 per-app 策略分级 + cgroup freezer 冻结 + 豁免判定）。
**这类模块可能把 DSH 运行环境一并冻结**，新方案必须能加入豁免名单，且环境本体不依附 App 进程。
（此处刻意不点名具体模块与作者：一是与本项目无关，二是不宜对第三方实现作评价。）

### 3.3 proot 文件系统视图不可信（实测）

- proot 内创建 `/tmp/probe_from_proot`，原生 root shell **看不到**。
- proot 内 `du -sh <rootfs>` 报 **7.0 K**，原生同一路径报 **16 G**。

**推论：在 proot 环境内做环境诊断时，路径/大小/挂载信息不能直接采信**，必须用原生 shell 交叉验证。这也是 DSHA "在里面调东西处处是坑" 的根源之一。

**实测补充（`/data/adb` 的可见性差异）**：

```
proot 内   ls /data/adb → 1 项（只有一个自带目录）
原生 root  ls /data/adb → 19 项（ksu, ksud, metamodule, magic_mount, modules, post-fs-data.d, service.d, …）
```

→ 因此**任何"探测 Android 侧状态"的逻辑都必须先判断上下文**：
`/data/adb` 不存在、或存在但没有 `/data/adb/modules`，都应判定为**探测不适用**，
而不是断言"没有 metamodule / root 实现未知"。
（我们的 chroot 挂载树只 bind 了 `/dev`、`/proc`、`/sys`、sdcard，**没有 bind `/data/adb`**，
所以在环境内跑 `doctor` 时这一项必然不可信。）

### 3.4 KernelSU 已把「模块挂载」交给第三方 metamodule（实测）

用户指出的这一条已核实，而且**直接决定模块设计**：

```
/data/adb/metamodule/            ← 存在：metamount.sh / metainstall.sh / config.toml / meta-mm / metauninstall.sh
/data/adb/magic_mount/           ← 存在
/data/adb/modules/magic_mount_rs/  ← id=magic_mount_rs  version=v4.0.8-900  metamodule=1
   自述："An implementation of a metamodule using Magic Mount, Based on MKSU, Template is from meta-overlay"
```

- **KernelSU 不再自己实现模块文件挂载**，改由 metamodule（`magic_mount_rs` / `meta-overlayfs` / `mountify` / `hybrid-mount` …）负责；
  因此**需要 overlay 系统路径的模块，必须在刷写流程里做挂载实现探测**，否则在不同环境里行为不一致。
- **Magisk 仍是原生挂载**，没有这个问题。

**对本项目的结论（关键）：**

1. **我们的模块不需要挂载。** `module/` 下没有 `system/`、`system_ext/`、`vendor/`、`product/`、`odm/`
   任何会被 overlay 的目录——它是**纯脚本模块**（`post-fs-data.sh` + `service.sh`）。
   → **完全不受 KernelSU 删除挂载实现的影响**，也不需要依赖任何 metamodule。
2. 仍然要做**探测 + 报告**（`customize.sh` 刷写时、`doctor` 运行时），因为：
   - 装了 metamodule 的环境里模块作者的假设常不成立，早报告能省掉大量排查；
   - 我们要能明确告诉用户"本模块不挂载任何系统路径，与 metamodule 变动无关"。
3. **我们的 overlay 挂载与 metamodule 不冲突**：本项目所有挂载都在
   `unshare -m` 出来的**私有 mount 命名空间**里（见 architecture §4），
   而 metamodule 的 overlay 在全局命名空间。两者互不可见、互不干扰。
   这正是"自己做独立命名空间"带来的额外好处。
4. 探测实现必须是**通配/存在性判断**，不要写死 `magic_mount_rs`——换台设备就错。

> 顺带记录：设备上还有 `post-mount.d/`、`modules_update/`，说明挂载流程被拆分出多个 hook 点。

## 4. 本机构建链（用于产出 artifact）

| 能力 | 状态 |
|---|---|
| Android SDK | `/root/Android`：build-tools 35.0.0 / 36.0.0、platforms/android-35、NDK、cmdline-tools、platform-tools |
| JDK | `/usr/bin/java`、`javac` 可用；**gradle 未安装**（需自备 wrapper） |
| Node | v24.19.0 |
| DSH | `@deepseek-ai/dsh`，本地 v0.1.5-rc.2；**已公开发布于 npm** |
| DSH npm 通道 | `latest=0.1.5-rc.1`、`next=0.1.5-rc.2`、`alpha=0.1.6-alpha.1` |
| DSH 安装体积 | 270 MB（72 个依赖，`bin: lib/bin.js`，`type: module`） |
| 打包工具 | `mkfs.erofs`/`fsck.erofs`/`dump.erofs`(erofs-utils 1.7.1)、`mkfs.ext4`、`tar`、`zstd`、`proot`(5.1.0)、`fakechroot`、`fakeroot`(不可用)、`mksquashfs`(**内核不支持，勿用**) |
| 网络 | 可访问 `cdimage.ubuntu.com`、`ports.ubuntu.com`、npm registry |

### 4.1 rootfs 构建方式：两条路，各有硬约束

**路线 A（宿主/CI 构建，推荐用于发布）**
在普通 Linux 主机上用 `mmdebstrap`/`debootstrap` + 真 chroot 构建，天然正确。

**路线 B（本机/设备侧构建）**
本机处于 proot 内，**没有 CAP_SYS_CHROOT**，无法真 chroot，因此：

- ❌ **嵌套 proot 不可用**：外层 proot 会二次改写路径，报
  `proot warning: can't chdir("/root/Q/./.") in the guest rootfs`。
- ❌ **fakechroot + apt 不可信**：`fakechroot chroot` 对 `ls/grep/cat` 隔离正确
  （目标 rootfs 实测 91 包、无 `git`），但 **`env` 的 PATH 查找会逃出 chroot**：
  以 `env PATH=... apt-get install git` 执行时，apt 实际跑在**宿主的** apt 状态上并回复
  "git is already the newest version"，而目标 rootfs 里根本没有 `git`。
  若用这种方式构建，结果不可信，且有**误伤宿主**的风险。
- ✅ **可行**：所有进入目标 rootfs 的命令一律使用**绝对路径**；
  且最终验收必须以「目标 rootfs 内实际文件」为准，不以命令回显为准。

**因此确定：设备侧 provisioning 由真 root 运行时原生完成**（见 `architecture.md` 的 L1），
宿主侧仅负责离线种子与可选发布构建。

## 5. 我这条设备 shell 通道的边界（必须告知用户）

- 设备命令保护始终开启：读取任意目录可以；根目录、系统目录、DCIM、Pictures、
  `Android/data`、`Android/obb` 及子目录**只读**；普通 Download 允许写。
- **禁止**：块设备/分区操作、SELinux 修改、`settings put`/`setprop`、**挂载**、刷机、
  卸载/清除应用数据；未知命令直接拦截。
- 禁止用其他通道绕过上述策略。

**影响**：我可以产出并验证 artifact（构建、打包、静态检查、只读探查设备状态），
但**首次部署中涉及挂载的步骤，必须由用户在自己的 root 终端执行，或由装好后的
KernelSU 模块在开机时自行完成**。我不会绕过这条策略。

## 6. DSH 在裁剪 rootfs 里的真实依赖面（实测）

对本地 `@deepseek-ai/dsh@0.1.5-rc.2`（270 MB，191 个顶层依赖）实测得出，
**这是 rootfs 层包列表的硬依据**，不要凭猜测加包。

### 6.1 原生模块（全部预编译，**不需要编译器**）

`find -name '*.node'` 共 **7 个**：

| 模块 | 用途 | 备注 |
|---|---|---|
| `@deepseek-ai/node-addon-system-linux-arm64/bin/glibc/system.node` | 系统信息 | 另有 musl 变体，**我们用 glibc 那套** |
| `@img/sharp-linux-arm64/.../sharp-linux-arm64-0.35.4.node` | 图像处理 | 见 §6.3 |
| `@koromix/koffi-linux-arm64/linux_arm64/koffi.node` | FFI | |
| `node-addon-require-builtin-linux-arm64-gnu/...napi-v9.node` | 内建模块加载 | |
| `node-pty/prebuilds/linux-arm64/pty.node` | 伪终端 | 需要可用的 `/bin/sh` 或 `bash` |

### 6.2 系统库需求（`ldd` 实测，去重后）

| 库 | 归属包 |
|---|---|
| `libc.so.6`（含 libdl/libm/libpthread/libutil/libresolv） | `libc6` |
| `libstdc++.so.6` | `libstdc++6` |
| `libgcc_s.so.1` | `libgcc-s1` |
| `ld-linux-aarch64.so.1` | `libc6` |

Node 自身（`/usr/local/bin/node`，122 MB 官方构建，glibc 2.39）链接的库**完全相同集合**。

**结论：运行 DSH 的必需系统库只有 `libc6` + `libstdc++6` + `libgcc-s1`。**
`git`、`python3`、`ripgrep` 等属于"给 agent 用的工具"，放 runtime 层按需增删，
**不是 DSH 运行的硬依赖**。

### 6.3 sharp 自带 libvips（**不需要系统装 libvips**）

`sharp-linux-arm64-0.35.4.node` 的 `ldd` 显示依赖 `libvips-cpp.so.8.18.6`，但该库**随包提供**于
`@img/sharp-libvips-linux-arm64/lib/libvips-cpp.so.8.18.6`，通过 RPATH `$ORIGIN/...` 解析：

```
RPATH: $ORIGIN/../../sharp-libvips-linux-arm64/lib : ... : $ORIGIN/.../node_modules/@img/sharp-libvips-linux-arm64/lib
```

→ **不要在层里装 `libvips42`**，那是多余体积。注意打包时必须保持 `@img/*` 的相对目录结构不被展平，
否则 RPATH 失效。

### 6.4 体积预算（**已全部实测**，三层产物都已真实产出）

| 层 | 内容 | 源体积 | 裸 erofs | 分发 `.zst` | 分发 `.gz` |
|---|---|---|---|---|---|
| `base-24.04.3-l1` | ubuntu-base + CA 证书 + 时区，已裁剪 | 109 MB | **95.3 MB** | **18.6 MB** | 26.5 MB |
| `runtime-1.0.0` | Node v24.21.0 + pnpm 12.4.2 + `/opt/dshroid` 入口脚本 | 246 MB | **229.7 MB** | **46.5 MB** | 73.5 MB |
| `dsh-0.1.5-rc.2` | `@deepseek-ai/dsh` + 191 依赖 + profile 工作区 | 270 MB | **201.5 MB** | **31.2 MB** | 47.8 MB |

- **全量下载（zstd）= 18.6 + 46.5 + 31.2 = 96.3 MB**；gzip 路径 = 147.8 MB。
- **DSH 自身升级只下 31.2 MB**（gzip 47.8 MB），不重下系统 —— 这是"更新又拖又慢"的正解。
- 对比 DSHA：**16 GB 且在 App 私有目录、卸载即毁**。
- 体积关系不是线性的：erofs 用 4096 字节块，比原目录更省（base 109→95.3 MB）；
  而 zstd 对文本类内容（node_modules）压缩率极高（dsh 201.5→31.2 MB）。
- 产物与哈希：`dist/*.erofs.{zst,gz}`、`dist/SHA256SUMS.layers.txt`（含**压缩产物**与**解压后裸镜像**两组哈希）。
- 已知的实测数据（用于交叉验证）：
  - `mkfs.erofs -z lz4` 对 node 二进制 = 69.0 MB；**不压缩** = 116.4 MB；再 `zstd -19` = 31.1 MB
    → 所以默认"镜像不压缩 + 分发压缩"。
  - `mksquashfs -comp zstd` 对 ubuntu-base 解包 = 25.4 MB（对比 erofs+`zstd -19` = 18.6 MB，**erofs 路线更小**）。

> 历史教训：早期这里估过"合计约 300 MB"，那是**按未压缩**算的错值；
> 还因为 `cp -a` 静默失败只拷了 87 MB，得出过 dsh 层"7.9 MB"的错误结论。
> **体积结论一律以实测为准，且测量前必须核对 `du` 与文件数。**

### 6.5 dsh 层必须包含的路径

```
/usr/local/lib/node_modules/@deepseek-ai/dsh/**    # 含 node_modules，注意 @img 目录结构
/usr/local/bin/dsh -> ../lib/node_modules/@deepseek-ai/dsh/lib/bin.js
/root/.dsh/profiles/**                             # ★ profile 工作区与插件，见 docs/dsh-profile.md
```

- 入口是 `lib/bin.js`（ESM，`#!/usr/bin/env node`），`package.json` 里 `bin: {"dsh": "lib/bin.js"}`。
- `dsh web` 是 `--profile web` 的硬编码别名。
- 包内 `node_modules` 有 **191 个顶层依赖**，**必须整体打包**（离线环境无法回源）。
- ⚠️ **只装全局 npm 包是不够的**：`dsh web` 还需要 `$DSH_HOME/profiles/web/` 里的
  bundle 列表与插件（`dsh-web-mobile` 等）。缺了它界面是桌面版、无移动端适配。
  完整分析见 [`dsh-profile.md`](dsh-profile.md)。

## 7. 内核能力速查（给运行时的硬性输入）

| 需要的功能 | 本机可用性 | 运行时应对 |
|---|---|---|
| mount 命名空间隔离 | ✅ `CONFIG_NAMESPACES` | `unshare -m` |
| overlayfs 分层 rootfs | ✅ `CONFIG_OVERLAY_FS` | **EROFS** lower + ext4 upper（squashfs 内核不支持，见 §2.1） |
| 真 chroot | ✅ 真 root 有 CAP_SYS_CHROOT | `chroot` |
| 独立主机名 | ✅ `CONFIG_UTS_NS` | `unshare -u` |
| 独立网络栈 | ✅ `CONFIG_NET_NS`（默认**不用**，否则断网） | 保留宿主 netns |
| PID 隔离 | ❌ 无 `CONFIG_PID_NS` | 不依赖 PID 1 语义；用 supervisor |
| systemd | ❌ 不可用 | 自备轻量 supervisor |
| 非 root 降级 | ⚠️ 仅 proot | 单独一套 proot 运行时 |
