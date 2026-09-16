# SunsetLinux — proot 降级运行时（非 root）

本目录是 **非 root（proot）模式** 的全部实现。契约见 `docs/architecture.md` §2.2 / §3 / §3.2；
设备实测事实见 `docs/findings.md`（尤其 §3 DSHA 的做法 与 §3.3「proot 文件系统视图不可信」）。

```
runtime/proot/
  linuxctl.sh    # 全部子命令 + §3.1 状态 JSON（唯一对外接口，App 调用它）
  start.sh       # 用 proot 把 rootfs 装起来（bind / DNS / 密钥环境 / 生命周期）
  entry.sh       # rootfs 内的入口 + supervisor：准备环境 → **后台**起 dsh web →
                 #   截获 stdout/stderr → 解析带令牌 URL 落盘 run/dsh.url → 前台等待 + 信号透传
  selftest.sh    # 带令牌 URL 解析器的回归自测（合成日志直接调 entry.sh 的真实函数）
  README.md      # 本文件
tools/proot-bundle/
  mkproot-bundle.sh   # 产出 dist/proot-bundle-<arch>.tar.gz（自带 proot + 依赖 + GPLv2 许可）
```

环境根默认 `$SUNSETLINUX_APP_FILES/sunsetlinux`，可用 `LINUX_HOME` 覆盖：

```
$APP_FILES/sunsetlinux/
  rootfs/                  # 直接解开的 Ubuntu 目录（proot 模式下它本身就是可写层）
  etc/{config.json,channels.json,state.json,env,binds?}
  run/{dsh.url,dsh.pid,dsh.pid.starttime,dsh.port,proot.pid,state,linux.log,last_error}
  snapshots/<name>.tar.zst|.tar.gz (+ .json 元数据)
  cache/  bin/{linuxctl,start.sh,entry.sh,selftest.sh}  proot/（随包 bundle 解包在这里）
```

---

## 1. 快速上手

```bash
export SUNSETLINUX_APP_FILES=/data/user/0/io.github.sunsetrne.sunsetlinux/files      # App 一般自动注入
BIN=$SUNSETLINUX_APP_FILES/sunsetlinux/bin

# 0) 一次性：解包随包 proot（不依赖设备上已装 proot / Termux）
mkdir -p "$SUNSETLINUX_APP_FILES/sunsetlinux/proot"
tar -xzf dist/proot-bundle-arm64.tar.gz -C "$SUNSETLINUX_APP_FILES/sunsetlinux/proot"

# 1) 部署（rootfs 有两种来路，任选）
#    ① 出厂种子 tarball：
linuxctl provision --seed /sdcard/sunsetlinux-seed
#    ② 已经装好的 base 层（频道 / App 内嵌离线包给的就是它）——**无参**即可，
#       provision 会发现 $LINUX_HOME/layers/base-*.erofs 并直接解成 rootfs：
linuxctl provision

# 2) 启动 / 状态 / 日志
linuxctl start
linuxctl status
linuxctl logs -n 200

# 3) 自检（内核/文件/端口/密钥权限/DNS/proot 二进制 + **部署就绪度**）
linuxctl doctor

# 4) 进环境 / 执行命令
linuxctl attach                 # 交互 shell
linuxctl exec -- id             # 非交互
```

`provision` 的种子默认按 `--seed` → `$SUNSETLINUX_SEED_DIR` → `$LINUX_HOME/seeds` →
`$LINUX_HOME/cache` 的顺序查找 `*ubuntu*.*` / `*base*.*` / 任意 `*.tar.{zst,gz,xz,bz2}`；
**都找不到时**会退到 `$LINUX_HOME/layers/base-*.erofs`（用设备自带的 `fsck.erofs --extract` 解包）——
这条是为了让 App 的「频道 / 离线包」在免 root 模式下也能用同一份层（见下）。

### 1.0 层的格式：erofs 与 tar 都吃（免 root 与 root 用同一份层）

- `erofs`：用 `/system/bin/fsck.erofs --extract=<dir>`（erofs-utils ≥1.6，Android 内置；
  可用 `SUNSETLINUX_EROFS_EXTRACT=<路径>` 覆盖）。**App 的频道与内嵌离线包给的就是这种**。
- `tar.gz` / `tar.zst` / `tar.xz` / `tar.bz2`：直接用 `tar` 解（老格式仍然支持）。
- erofs 会按**超级块 magic**（`0xE0F5E1E2`，在**偏移 1024**，不是偏移 0）校验，损坏的镜像当场报错，
  不会再像以前那样"落到默认分支、什么都不查就放行"。
- 注意代价：解包 base 层（≈240 MB erofs → 约 1 GB 目录）是**分钟级 IO**，与 root 模式的 dir 层模式同一量级。

### 1.1 部署后校验（照这个清单确认，别只看 provision 的退出码）

```bash
H=$SUNSETLINUX_APP_FILES/sunsetlinux

# 1) 契约入口必须在（App 只调这个路径；provision 会幂等补齐）
test -x "$H/bin/linuxctl" && echo "OK bin/linuxctl"

# 2) rootfs 内入口必须在（每次 provision/start 都会刷新成最新版本）
test -f "$H/rootfs/opt/sunsetlinux/entry.sh" && echo "OK entry.sh"

# 3) 状态 JSON 合法（§3.1）
"$H/bin/linuxctl" status | python3 -m json.tool >/dev/null && echo "OK status JSON"

# 4) 自检（端口/文件/密钥权限/DNS/登录 URL/proot 二进制/契约入口）
"$H/bin/linuxctl" doctor

# 5) start 之后：登录 URL 必须出现（§1.2；这是 App 能打开界面的前提）
"$H/bin/linuxctl" start
test -s "$H/run/dsh.url" && grep -q '?token=' "$H/run/dsh.url" && echo "OK 已捕获登录 URL"
"$H/bin/linuxctl" status | grep -o '"url":"[^"]*"'       # 带 ?token=
"$H/bin/linuxctl" status | grep -o '"base_url":"[^"]*"'  # 裸地址，无 token
```

`provision` 的 JSON 会回带 `result.bin_linuxctl` / `result.bin_linuxctl_ok`，
`doctor` 有 `bin_linuxctl` 与 `dsh_url` 两个检查项 —— 用它们做机器可读的校验。

### 1.2 `dsh web` 登录契约（architecture.md §3.3，必须严格遵守）

| 事实 | 本实现对应的行为 |
|---|---|
| 裸 `GET /` 返回 **401** | `status.dsh.base_url` 只是裸地址，**App 不能拿它开 WebView**；`doctor` 与日志都会提示 |
| 登录地址是 `http://127.0.0.1:<port>/?token=<launchToken>`，GET 后 303 到 `/` 并下发签名 Cookie | `entry.sh` 从 `dsh web` 启动那一行截获它，写进 `run/dsh.url`（**0600**）；`status.dsh.url` 就是它 |
| 令牌进程内随机、不落盘、每次重启都变 | 每次 `start` 先删旧 `run/dsh.url` 再重新捕获；`stop`（以及 dsh 自己退出时）也删掉，绝不把过期令牌留给 App |
| 令牌唯一出口是 stdout 那一行 `dsh web: <url>[ (LAN: ...)]` | 解析规则：匹配 `^[[:space:]]*dsh web: `、取 `http://127.0.0.1:` 起**到第一个空白为止**、必须含 `?token=`、去掉行尾 CR；只扫本次启动之后新写入的日志行（避免拿到上一轮旧令牌）。原始行保留在 `run/linux.log` 便于排查；打到 stderr 也能抓到（stdout/stderr 同向追加） |
| Cookie 绑定 `Host` 授权域 | `dsh.url` 与 `base_url` 都用 `127.0.0.1`，App 不要改成 `localhost` |
| URL 取不到时 | `dsh.url = null`（**绝不编造裸地址冒充登录地址**）；`base_url` 仍给出裸地址供探活 |
| 健康判定 | 探针打 **`base_url`**：**收到任何 HTTP 响应即健康**（401 属正常）；只有连不上/超时才不健康 |

解析器回归自测：`runtime/proot/selftest.sh`（18 条断言，覆盖 LAN 尾部、CR、旧令牌、缺 `?token=` 等）。

---

## 2. 能力边界（对照 `architecture.md` §1）

| | root 模式 | **proot 模式（本目录）** | 差距的实际后果 |
|---|---|---|---|
| 底座 | 真 root（KernelSU）+ 真 chroot | PRoot（ptrace 系统调用翻译） | 每个系统调用被拦截改写，进程创建/小文件写明显变慢 |
| 隔离 | `unshare -m` + `unshare -u` | **无** | 环境与宿主共享挂载/网络栈；`mount` 不可用 |
| 分层 | squashfs + ext4 overlayfs | **无**，直接目录 | 没有原子层切换，`update` 是解包覆盖；`reset` 靠出厂种子重建 |
| 环境根 | `/data/sunsetlinux`（卸载 App 不丢） | `$APP_FILES/sunsetlinux`（**App 私有，卸载即毁**） | 这是 proot 模式的固有限制：改路径就得有 root |
| sdcard | `/mnt/pass_through/0/emulated`（直挂，绕 FUSE） | `/storage/emulated/0`（FUSE，**绕不开**） | sdcard 上大量小文件 IO 很慢 |
| 生命周期 | 与 App 解耦（KernelSU 模块开机自启） | 与 App 同 uid，靠 `setsid+nohup` 尽量脱离调用方 | 系统冻结/清理 App 时可能连环境一起冻；root 模式的豁免名单这里用不上 |
| 身份 | 真 uid 0 + 真 capabilities | **保持 App 真实 uid（默认不伪造）** | 不能 `mount/chown/mknod`，不能装包（要装包得显式 `--fake-root`，见 §5） |
| 定位 | 主力 | 兼容兜底 | 能用、可更新、可快照；但别拿它当 root 用 |

**仍然保持一致的部分**（这是设计目标）：

* 同一个 Linux 侧入口 `rootfs/opt/sunsetlinux/entry.sh`；
* `linuxctl` 路径、子命令、退出码语义，以及 **`status` 的 §3.1 JSON 结构完全一致**；
* `mode` 字段固定为 `"proot"`，App 只读这一个字段就能区分模式。

### 2.1 `layers` / `storage` 在 proot 模式下怎么填

proot 没有 squashfs 分层，所以字段是**逻辑映射**（键不可省略，取不到为 `null`）：

| 字段 | proot 模式含义 |
|---|---|
| `layers.base` | `rootfs/` 里 `/usr/local` 以外的部分；`version` 取 `rootfs/etc/sunsetlinux-base-version` 或 `/etc/os-release` 的 `VERSION_ID` |
| `layers.runtime` | `rootfs/usr/local` 里除 DSH 以外的部分；`version` 取 `rootfs/etc/sunsetlinux-runtime-version`（层构建时写入），否则 `null` |
| `layers.dsh` | `rootfs/usr/local/lib/node_modules/@deepseek-ai/dsh`；`version` 读它的 `package.json` |
| `layers.*.mounted` | 「该部分已就位」（不是真的 mount） |
| `storage.upper_used` | `rootfs` 实际占用（缓存值） |
| `storage.upper_total` | **宿主文件系统总容量**，不是配额 |

容量测量只在 `provision / update / restore / reset / snapshot(status --refresh-sizes)` 时做一次，
结果缓存在 `etc/state.json`，`status` 直接读缓存 —— 否则 App 每次轮询状态都要 `du` 一整棵 rootfs。

### 2.2 哪些"能力缺失"其实能补（2026-09-16 审计）

把 §2 的差距按**能不能补**重新分一遍 —— 固有限制别当待办，能补的别当"用户运气不好"：

| 差距 | 性质 | 处置 |
|---|---|---|
| 无 mount / 无命名空间隔离 | **固有**（没有 CAP_SYS_ADMIN） | 用 `-b` bind 覆盖常见需求（`/dev` `/proc` `/sys` `/sdcard` …，见 `start.sh`） |
| 无分层（overlay / squashfs） | **固有** | `update` 走"解包到 staging 再原子换目录" |
| 环境根只能在 App 私有目录 | **固有**（改路径就得有 root） | `snapshot`/`restore` 让数据可搬迁；文档明示卸载即毁 |
| sdcard 走 FUSE 慢 | **固有** | — |
| 生命周期随 App（无开机自启） | **固有** | foreground service + 省电豁免，尽量降低被杀概率 |
| 不能 chown / mknod / 装包 | 大部分固有 | 装包用显式 `--fake-root`（`-0`），并明确告知这是**伪造 root** |
| **随包 proot 太旧（5.1.0 / 2014）** | ✅ **可补，已补** | bundle 升级到 **proot 5.4.0**：新增 `--link2symlink`、`--kill-on-exit`、`--port`、`--netcoop`、`--mixed-mode`，外加十年的 ptrace 翻译修复。（`--sysvipc` 连 5.4.0 都没有，别写进文档） |
| **`--link2symlink` 在老 proot 上直接崩** | ✅ **可补，已补** | 实测 5.1.0 遇到它会 `unknown option '--link2symlink'` + fatal（一开配置就起不来）。`start.sh` 现在先问一次 `--help` 再决定加不加，不支持就跳过 + 提醒 |
| **proot 运行时没有分发路径** | ⚠️ **只补了一半** | `app/app/src/main/assets/` **不存在** —— doctor 里那句"App 应随包携带"一直是空的，所以**非 root 用户开箱即用不了 proot 模式**。现在发布页会带上 `proot-bundle-arm64.tar.gz`（~1 MiB，稳定 URL）；**建议**下一步直接塞进 APK assets，provision 时解到 `$LINUX_HOME/proot/`（1 MiB 成本极低） |
| **proot 模式的 rootfs 来源** | ⚠️ **未定** | `provision` 需要离线种子（~59 MiB）；发布页也会带上它。要"开箱即用"就得二选一：APK 内置种子，或走频道下发层（与 §4 的分发选型是同一件事） |

> **结论**：真正绕不过去的只有"没有 root ⇒ 没有 mount / 命名空间 / 分层"这一条；
> 其余（proot 版本、开关支持、运行时与 rootfs 的分发）都是能补的工程问题 —— 本文已补掉前两项，
> 后两项卡在"分发选型"，见 `docs/STATUS.md` §七。

---

## 3. 相对 DSHA 的改进（逐条对应 findings §3）

| DSHA 的问题 | 本实现 |
|---|---|
| rootfs 在 App 私有目录，卸载即全毁 | proot 模式下无法根治（没有 root 就写不了 `/data`）；但 `snapshot/restore` 让数据可搬迁，且 README/文档明确标注这个风险 |
| `-0` 伪造 root 却让用户以为是真 root | **默认不加 `-0`**，保持真实 uid；`doctor`/日志/`entry.sh` 都会明确说明「伪造 root，能力受限」；见 §5 |
| `--kill-on-exit`：环境随 App 进程死 | **不使用**；`setsid + nohup` 独立会话，PID 落 `run/proot.pid`，停止用进程组信号 |
| `NODE_OPTIONS --import` 注入 + 检查点 + 自修复脚本 | **完全没有**注入层；`start.sh`/`entry.sh` 主动 `unset NODE_OPTIONS` 并把它记进日志；存活判定只看 PID+starttime+HTTP 健康 |
| API key 明文出现在命令行（`ps` 可见） | 密钥只放 `$LINUX_HOME/etc/env`（0600）或环境内 `/root/.dsh/env`，**以环境变量传入**；命令行只有路径与开关 |
| proot 内文件系统视图不可信却被当可信 | 检测 `TracerPid != 0`（被 ptrace 跟踪 = 跑在 proot 里）后**拒绝现场测量**，只用缓存值；`doctor` 会报告当前上下文 |
| 依赖设备上已有 proot / Termux | `tools/proot-bundle` 自带 proot + 全部动态依赖 + glibc 解释器，并对齐 GPLv2 合规 |

---

## 4. 启动时到底做了什么（`start.sh`）

```
1. 解析环境根 / rootfs / run 目录；校验 rootfs 完整性（/bin/sh、/opt/sunsetlinux/entry.sh）
2. 解析 proot：SUNSETLINUX_PROOT_CMD > SUNSETLINUX_PROOT_BIN > $LINUX_HOME/proot/proot-launch.sh
   > $BIN_DIR/proot-launch.sh > $LINUX_HOME/proot/bin/proot > PATH
   （脚本型包装器若 shebang 解释器不存在 —— Android 上没有 /bin/sh —— 自动改用找到的 sh 执行）
3. 组装 proot 参数：-r <rootfs> -w /root
   -b /dev -b /proc -b /sys -b /system -b /apex
   -b /proc/self/fd:/dev/fd -b /storage/emulated/0:/sdcard
   -b <LINUX_HOME>/run:/run/sunsetlinux          # 控制通道：写回 dsh.pid/dsh.port/linux.log
   （不存在的 bind 源会警告并跳过；/dev /proc /sys 缺失则直接失败）
   可选：etc/binds 文件、SUNSETLINUX_EXTRA_BINDS、--link2symlink（npm 硬链接兜底）、-0（仅 --fake-root）
4. DNS：多路回退 getprop(net.dns1/各接口 dns) → /system/etc/resolv.conf → /etc/resolv.conf → ndc
   → 公共 DNS，写进 rootfs/etc/resolv.conf，并把来源与结果写进日志 + status 附加字段
5. 时区：getprop persist.sys.timezone → 导出 TZ + 写 rootfs/etc/sunsetlinux-tz
6. 密钥：读取 etc/env（0600，权限不对会被收紧）→ 以环境变量传给 guest（**不进命令行**）
7. 后台启动：setsid nohup bash -c '写 run/proot.pid; exec proot ... /opt/sunsetlinux/entry.sh'
   （同时关闭继承来的 fd 3..64：否则后台 guest 会一直持有 linuxctl 的 flock 锁）
8. 等待就绪：run/dsh.pid + run/dsh.port + TCP 可连；进程提前退出则把日志尾部的原因写进 last_error
```

`entry.sh`（rootfs 内）随后 —— **注意这里不能用 `exec`**（§3.3 的令牌只能从 stdout 截获）：
```
1. 写 /etc/hosts、设置时区(/etc/localtime)、HOME=/root、DSH_HOME=/root/.dsh
2. DNS 兜底检查（为空则从 /system/etc/resolv.conf 复制或写公共 DNS）
3. 加载 /root/.dsh/env（0600）；unset NODE_OPTIONS
4. 先清掉上一轮的 run/dsh.url / dsh.pid / dsh.port
5. 记下 run/linux.log 当前行号，然后**后台**启动：
     node <dsh bin> web --no-open --host 127.0.0.1 --port <port> >> run/linux.log 2>&1 &
   （stdout 与 stderr 同向追加：`dsh web: ...?token=` 打到哪个流都能抓到，原始行也留在日志里）
6. 写 run/dsh.pid（+ dsh.pid.starttime 防 PID 复用）、run/dsh.port（先写请求端口）、state=running
7. 后台解析器轮询日志（200ms 一次，只扫第 5 步之后的行动）：
     匹配 `^[[:space:]]*dsh web: ` → 取到第一个空白为止 → 必须含 ?token= → 去掉行尾 CR
     命中后**原子**写 run/dsh.url（0600），并用 URL 里的实际端口覆盖 run/dsh.port
8. trap TERM/INT/HUP/QUIT：透传给 dsh 与解析器，最多等 15s 再 KILL；随后删 run/dsh.url/dsh.pid/dsh.port
9. 前台 wait 受监管的 node；它退出后同样清理，并写 state（rc=0 → stopped，rc≠0 → error + last_error）
```

> **关于 `supervise.sh`**：`architecture.md` §3.2 描述的 supervisor 是 `/opt/sunsetlinux/supervise.sh`。
> proot 模式下**刻意不拆这个文件**，而由 `entry.sh` 同时承担入口与 supervisor 职责
> （准备环境 → 后台起 dsh → 截获令牌 → 落盘 → 前台等待 → 信号透传）。
> 行为契约与 root 侧 `runtime/root/supervise.sh` **一致**：
> `run/dsh.url`(0600)、`run/dsh.port`、`run/dsh.pid`、`run/linux.log`，以及「退出即删凭证文件」；
> 差别只是 proot 模式的 run 目录经 `-b $LINUX_HOME/run:/run/sunsetlinux` 这个 bind 暴露给环境内。

`exec` 之后 PID 不变，所以 `run/dsh.pid` 就是最终 node 的 PID；proot 不虚拟 PID，
因此这个 PID 在宿主侧同样有效 —— `linuxctl status` 靠 `PID + /proc/<pid>/stat` 的 starttime
精确判定（防 PID 复用），不依赖 cmdline 特征、也不依赖任何自修复脚本。

---

## 5. 关于「是否使用 `-0`」的最终决定

**默认不用 `-0`。**理由：

1. `-0` 只是伪造 `getuid()` 与部分系统调用的返回值，**不带来任何真实 capabilities**：
   `mount/chown/mknod` 依旧失败。它最大的害处是让用户以为拿到了 root（DSHA 就是这么被诟病的）。
2. proot 模式下需要写 rootfs 的地方，靠**布局**解决即可：`rootfs` 本来就在
   App 私有目录里、属主就是当前 uid，解包/覆盖/删除都不需要 root 身份；
   `/etc/resolv.conf`、`/etc/hosts` 这些由宿主侧写真实文件（不是 proot 合成的视图）。
3. 需要「装包」这类场景（apt/dpkg 会检查 uid）确实要 `-0`，所以做成**显式开关**：
   `linuxctl start --fake-root` 或 `SUNSETLINUX_PROOT_FAKE_ROOT=1`（另有 `--no-fake-root` 关回去）。
   打开时会：
   * 在 stderr + `run/linux.log` 打印「当前为伪造 root（proot -0）…没有真实 capabilities」；
   * 把 `SUNSETLINUX_FAKE_ROOT=1` 传给环境，`entry.sh` 再警告一次；
   * 把选择固化进 `etc/config.json` 的 `fake_root`，`doctor` 读得到。

结论：**伪造 root 是一个需要用户明确点头的降级开关，不是一个默认会被误认成真 root 的状态。**

---

## 6. 已知坑（照实说）

### 6.1 文件系统视图不可信（findings §3.3）

* proot 里 `du/stat/find` 看到的路径、大小、挂载信息都可能被改写
  （实测：proot 内 `du -sh rootfs` 报 7 KB，原生报 16 GB）。
* 因此 `linuxctl` 检测到自己在被 ptrace 跟踪时**不做现场测量**，只用 `etc/state.json`
  的缓存值，并在 `doctor` 里以 `context` 检查项明确报告。
  需要强制测量可以设 `SUNSETLINUX_ALLOW_TRACED_MEASURE=1`（只在你知道后果时用）。
* 推论：**`status`/`doctor` 最好由 App 原生侧调用**。若你的 App 只能用自带 proot 进
  rootfs 调 `linuxctl`，功能可用（PID/信号/HTTP 健康都还准），但容量字段会退化成缓存值。

### 6.2 绕不开 FUSE

* `-b /storage/emulated/0:/sdcard` 这条 bind 的宿主源本身就是 FUSE 挂载，
  proot 无法把它变成直挂。root 模式用 `/mnt/pass_through/0/emulated` 才有意义（那需要 root）。
* `/dev/fuse` **不是** proot 模式的必需项（`doctor` 会明确说这一点）；需要它是 root 模式的
  pass_through 方案。
* sdcard 上读写大量小文件非常慢；建议把工作区放在 `$LINUX_HOME` 里（App 私有、ext4/f2fs 直挂）。

### 6.3 ptrace 带来的不兼容

* 依赖 `ptrace` 自身、反调试、完整性校验的程序（部分运行时、加固过的二进制）会异常或被拒。
* **不能运行 setuid 程序**，不能 `mount`/`chown`/`mknod`/改系统目录。
* 本机内核没有 `CONFIG_SYSVIPC`（findings §2）：需要 SysV IPC 的程序（如 `fakeroot`）跑不起来。
* aarch64 rootfs 只能跑 aarch64 程序，没有 qemu-user 就没有跨架构能力。
* proot 5.1.0（Ubuntu 打包）实测 `seccomp_filter = no`：**每个系统调用都要 ptrace 往返**，
  进程创建/小文件写会比 root 模式明显慢（元数据遍历因为热缓存尚可，见 findings §3.1）。
  如果自行编译 proot，务必确认 `--version` 里 `seccomp_filter = yes`。
* 没有 PID 命名空间（内核不支持），所以 **没有 PID 1 语义、systemd 不可用**；
  本实现直接 `exec node`，不做服务管理 —— 要跑多进程服务请自己在环境内写脚本。

### 6.4 Android 后台限制

* proot 模式下环境进程属于普通 App uid，会被 Doze、cgroup freezer、LMK，
  以及那类按 App 分级的墓碑/冻结模块影响；**proot 模式没法把自己加进内核豁免名单**。
* 因此 App 侧仍应：前台服务（Android 14+ `specialUse`）+ `PARTIAL_WAKE_LOCK` + 通知 action；
  并提示用户把本 App 加入电池优化白名单 / 冻结模块豁免。
* `setsid + nohup` 只能保证「调用方（终端/一次性进程）退出后环境不死」，
  保证不了「系统冻结 App 时环境不死」。这是与 root 模式（KernelSU 模块开机自启）的本质差距。

### 6.5 其它

* 环境根在 App 私有目录 → **卸载 App 会连环境一起删**。重要数据请用
  `linuxctl snapshot` 定期备份到 sdcard（`/sdcard` 在环境内就是共享存储）。
* `snapshot` 默认拒绝在运行中做（会不一致）；`--force` 可强行快照。
* `update runtime` 是**合并覆盖**，不是原子替换（proot 模式没有 overlay）；
  `update dsh` / `update base` 是原子的（先 staging 再 rename），并保留 `.prev`/`rootfs.prev` 供回滚。
* 没有 zstd 时快照/层包自动回退 `tar.gz`（两条路径都实测过：本机起初没有 zstd，走 gzip；
  后来 `zstd` 出现在 `/usr/bin`，103 MB 的 rootfs 打出 25 MB 的 `.tar.zst` 并成功 restore）。
  Android 系统本身没有 zstd，而 root 版产出的种子/层常是 `.tar.zst`，
  所以 `linuxctl` 支持把静态 zstd 放进 `$LINUX_HOME/bin/zstd`（或设 `SUNSETLINUX_ZSTD`）；
  缺 zstd 时会给出明确指引，而不是含糊地失败。
* 端口冲突、`/data` 被挂载为 `noexec`（proot 二进制没法 exec）都会在 `doctor` 里报出来。

---

## 7. stdout 契约与例外

* `status`：stdout 严格是 §3.1 JSON（9 个顶层键，一个不少，取不到为 `null`）。
  `dsh` 子对象的关键语义（§3.3）：
  `url` = **带令牌**的登录地址（唯一来源是 `run/dsh.url`，没有就是 `null`）；
  `base_url` = 不带令牌的裸地址（探活用，**不要**拿它开 WebView）；
  `port` = 实际生效端口（以 `url` 里的端口为准，其次 `run/dsh.port`，再次 `etc/config.json`）；
  `healthy` = 对 `base_url` 的一次 `GET /`（401 也算健康，因为服务在监听）。
* `provision/start/stop/snapshot/restore/reset/update/doctor`：stdout 是
  **完整 §3.1 对象 + `action` / `ok` / `result`**，App 可以一套解析代码通吃。
* 刻意的两处例外：
  * `logs`：默认把日志尾部**原样**输出到 stdout（日志页要的就是文本）；
    需要结构化用 `logs --json`。
  * `attach` / `exec`：stdout/stderr 属于被执行的命令，退出码也是命令的退出码。
* 人类可读信息一律走 stderr；`--help` 也走 stderr。

### 7.1 退出码

| 命令 | 退出码 |
|---|---|
| `provision` | 0 成功 / 2 已部署（stdout 仍是合法 JSON）/ 1 失败 |
| `start` | 0 成功或已在运行；**1 = 启动失败**（严守「JSON 里 state=error 更可靠」的取舍，见下） |
| `stop` / `status` / `logs` / `snapshot` / `restore` / `reset` / `update` | 0 成功 / 1 失败 |
| `attach` / `exec` | 所执行命令的退出码 |
| `doctor` | 0 无 error 级问题 / 1 有 |

> 与 `architecture.md` §3 表格的唯一偏差：`start` 在**真的没启动成功**时返回 1 而不是 0。
> 理由：契约要求「失败时 `state="error"` 且 `last_error` 可读」，而一个失败的 CLI 返回 0
> 会让 shell 层的判断全部失灵。`state`/`last_error` 仍然如实反映，App 以 JSON 为准即可。

---

## 8. 环境变量总表

| 变量 | 作用 |
|---|---|
| `SUNSETLINUX_APP_FILES` | App 私有 files 目录；默认环境根 = `$SUNSETLINUX_APP_FILES/sunsetlinux` |
| `LINUX_HOME` | 直接指定环境根（优先级最高） |
| `SUNSETLINUX_PROOT_CMD` | proot 命令前缀，如 `/system/bin/sh /path/proot-launch.sh`（空格分隔） |
| `SUNSETLINUX_PROOT_BIN` | 单个 proot 可执行文件 |
| `SUNSETLINUX_PROOT_FAKE_ROOT` | `1` 等价 `--fake-root`（默认 0） |
| `SUNSETLINUX_PORT` / `SUNSETLINUX_HOST` | 覆盖 `etc/config.json` 里的端口/监听地址 |
| `SUNSETLINUX_START_TIMEOUT` | `start` 等就绪上限（秒，默认 120） |
| `SUNSETLINUX_START_GRACE` | 超过这么多秒还不健康 → `state=error`（默认 90） |
| `SUNSETLINUX_STOP_TIMEOUT` | `stop` 等进程退出上限（秒，默认 20） |
| `SUNSETLINUX_LOCK_TIMEOUT` | 抢锁等待上限（秒，默认 30） |
| `SUNSETLINUX_EXTRA_BINDS` | 追加 bind，空格分隔（`/a:/b /c`）；也支持 `etc/binds` 文件 |
| `SUNSETLINUX_LINK2SYMLINK` | `1` 加 `--link2symlink`（等价 `config.json:link2symlink`） |
| `SUNSETLINUX_SDCARD` | sdcard 宿主路径（默认 `/storage/emulated/0`） |
| `SUNSETLINUX_HOSTNAME` | guest 主机名（默认 `sunsetlinux`） |
| `SUNSETLINUX_ALLOW_TRACED_MEASURE` | `1` 允许在被 ptrace 跟踪时也做容量测量（默认禁止） |
| `SUNSETLINUX_SEED_DIR` | 出厂种子的额外搜索目录 |

`etc/config.json` 字段：`port` / `host` / `fake_root` / `link2symlink` / `extra_binds` / `linux_home`。

---

## 9. 密钥卫生（相对 DSHA 的关键改进）

* 密钥来源二选一：宿主侧 `$LINUX_HOME/etc/env`（0600，权限不对会被自动收紧并警告），
  或环境内 `/root/.dsh/.credentials.yaml`（DSH 自己的约定）/ `/root/.dsh/env`（0600）。
* 传递方式只有**环境变量**：`start.sh` 里 `set -a; . etc/env; set +a`，
  再 `exec proot`（环境被 guest 继承）。proot 命令行里只有 `-r/-b/-w` 和路径，
  **`ps` 看不到任何密钥**（自测里有专门断言）。
* 日志里只打印**变量名**，不打印值；`doctor` 会检查 `etc/env` 权限。
* `NODE_OPTIONS` 会被主动清除 —— 既拒绝 DSHA 那套注入式补丁，也避免宿主环境
  （比如别处注入的 `--require=...`）意外污染 DSH。
* **`run/dsh.url` 里的登录令牌同样按凭据对待**：文件权限 0600、只在运行期存在、
  `stop`/进程退出即删、`start` 前先删旧的（避免 App 读到过期令牌）。
  注意一个**刻意的取舍**：按 §3.3 的排查要求，`dsh web:` 那一行原始输出会保留在
  `run/linux.log` 里（内含令牌）。该文件也在 App 私有目录、与 rootfs 同信任边界，
  但 App 的「日志页/上传日志」功能应当意识到这一点（要么打码 `?token=...`，要么提示用户）。

---

## 10. 自测与产物

* 全部脚本 `bash -n` 通过；本机没有 `shellcheck`（未安装，避免改动宿主），
  因此以「静态检查 + 假 rootfs/假 proot 的功能自测」代替。
* `runtime/proot/selftest.sh` 是一条命令即可复跑的最小回归（解析规则最容易随上游漂移）。
* 功能自测（可复现，不碰宿主系统、不需要嵌套 proot）：
  1. **假 rootfs + 假 proot**：用一个 tarball 造 rootfs，再用一个只模拟
     proot 参数与 guest 行为的假 proot 驱动 `start/stop/status/exec`，断言：
     bind 列表正确、无 `-0`、无 `--kill-on-exit`、密钥只在环境变量里（不在命令行）、
     setsid 独立会话（ppid=1）、命令幂等、flock 不泄漏、`status` JSON 与 §3.1 键集合一致。
  2. **真 rootfs**：用 `build/ubuntu-base.tar.gz`（真实 Ubuntu 24.04 base，2565 个文件 /
     103 MB）跑 `provision → status → doctor → update dsh → snapshot → restore → reset`，
     全部通过；`snapshot` 在 zstd 可用时产出 `.tar.zst` 并成功 restore。
  3. **令牌链路（§3.3）**：
     a. `runtime/proot/selftest.sh`（18 条断言）：直接加载 `entry.sh` 的真实解析函数
        （`SUNSETLINUX_ENTRY_LIB=1` 钩子，不碰任何系统文件），用合成日志验证
        「标准行 / `(LAN: …)` 尾部不贪婪 / 行首空白与 CR / 只有 401 行 → 空 /
        旧令牌被 start_line 过滤 / 缺 `?token=` 不算 / 落盘 0600 / 端口取自 URL /
        非法输入拒绝 / 清理函数」。
     b. **假 proot + 忠实模拟 dsh web 鉴权的假服务**（裸 `/` → 401，
        `/?token=<T>` → 303 + Set-Cookie，带 Cookie 的 `/` → 200）驱动整条链，
        断言：`start` 后 `run/dsh.url` 存在且含 `?token=`（权限 0600）；
        `status.dsh.url` 与文件一致、`base_url` 是裸地址且不含 token、
        `port` 与实际端口一致、`healthy=true`（401 也算健康）；
        裸 `base_url` 请求确实返回 **401**（即 bug 场景），而带令牌 URL
        `curl` 得到 **303 → 200**（App 的真实用法）；`run/linux.log` 里保留了原始
        `dsh web: ` 行；`stop` 删掉 `dsh.url`/`dsh.pid` 且 `status.url=null`；
        重启后令牌**不同**（轮换）；`doctor` 的 `dsh_url`/`bin_linuxctl` 检查通过，
        并把 `dsh.url` 权限改成 644 时能报出 error。
  这三组测试覆盖了除「真 proot 跑真 rootfs / 真 dsh web」以外的全部逻辑。

## 11. 明确的未验证项

1. **真实 proot 跑真实 rootfs 未验证**：本机处于 proot 内，嵌套 proot 不可用
   （findings §4.1）。`start.sh` 的参数与生命周期逻辑用假 proot 验证过，
   但「proot 真能把 Ubuntu rootfs 跑起来并让 node 监听端口」必须在真机/CI 上验证。
1b. **真 `dsh web` 的那一行输出未在真机解析过**：解析规则严格照 §3.3 实测结论实现
   （含 LAN 尾部、CR、旧令牌过滤），并用合成日志 + 忠实模拟鉴权的假服务验证到
   「401 → 带令牌 303 → 200」全链路；但**真机首次 `start` 必须确认
   `run/dsh.url` 出现且含 `?token=`**（`doctor` 的 `dsh_url` 检查项就是为此准备的）。
   若上游改了那一行的格式，表现为 `status.dsh.url=null`，`run/linux.log` 里能看到原始行。
2. 真实设备上 `getprop/ndc` 的 DNS 结果、`/system/etc/resolv.conf` 内容未验证
   （本机没有这些命令/文件，落到了 `/etc/resolv.conf` 回退路径）。
3. 后台冻结行为（墓碑类模块 / Doze）未验证，只能按架构建议要求 App 做前台服务与豁免。
4. `update base/runtime` 在真实大层（几百 MB ~ 数 GB）上的耗时/空间峰值未实测。
5. bundle 的 proot 二进制在真实 Android 上的可执行性（SELinux/noexec）未验证，
   文档给了 nativeLibraryDir 方案，但需要真机确认。
