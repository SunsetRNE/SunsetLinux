# SunsetLinux 架构与接口契约

> 目标：在 Android 上自建一套**可原生运行、可增量更新、可第三方分发**的 DSH 运行环境，替代 DSHA(proot) 的体验。
> 本文件是唯一事实源。所有组件必须严格遵守这里的路径、命令与 JSON schema。

---

## 0. 设计目标（对应用户痛点）

| 痛点 | 设计对策 |
|---|---|
| 真 root 能力缺失 | root 版走**真 chroot + mount 命名空间**，真 uid 0 与真 capabilities |
| 稳定性 / 自修复补丁层 | 环境与 App 生命周期解耦，无注入式补丁，不做"检查点自修复" |
| 性能 | root 版零 ptrace 开销；sdcard 走 `/mnt/pass_through` 绕开 FUSE |
| 界面丑 | 启动器用 Kotlin + **Compose Material 3** 重做 |
| 更新又拖又慢 | **三层 erofs 分层**，只更新变化的那层；支持断点续传与本地种子 |
| 拿不到第三方内测 | **可插拔频道**：任何人有公钥即可建频道；DSH 版本按 npm dist-tag（`latest`/`next`/`alpha`）或自定义 tarball 选择 |
| 数据易失 | 环境根在 `/data/sunsetlinux`（非 App 私有），卸载 App 不丢；可写层可快照 |

---

## 1. 两种运行模式

产品同时交付 **root 版**与**非 root 版**，由同一 App 按 `su` 可用性自动选择，也可手动强制。

| | root 模式 | proot 模式（降级） |
|---|---|---|
| 底座 | 真 root（KernelSU）+ 真 chroot | PRoot（ptrace） |
| 隔离 | `unshare -m` + `unshare -u` | 无 |
| 分层 | overlayfs：erofs(lower) + ext4(upper) | 无 overlay，直接目录 |
| 环境根 | `/data/sunsetlinux` | `$APP_FILES/sunsetlinux` |
| sdcard | `/mnt/pass_through/0/emulated` 直挂（绕 FUSE） | `/storage/emulated/0`（FUSE） |
| 生命周期 | 与 App **解耦**；KernelSU 模块开机自启 | 随 App 进程 |
| 定位 | 主力 | 兼容兜底 |

两种模式**共用同一个 Linux 侧入口**：`/opt/sunsetlinux/entry.sh`（在 rootfs 内），
只有"如何进入 rootfs"的启动器不同。这样行为一致、便于测试。

### 1.1 ★ 宿主侧脚本的 shell 约束（**冻结，踩过坑**）

**Android 上没有 bash。** `/system/bin/bash` 不存在，`/system/bin/sh` 是 **mksh**。
因此：

| 脚本 | 运行位置 | 解释器约束 |
|---|---|---|
| `module/*.sh` | Android（KernelSU 调用） | 必须 `#!/system/bin/sh` 且能被 mksh 解析 |
| `runtime/root/*.sh` | Android（`su -c` / 开机自启 / App 调用） | 同上（`linuxctl` 会被 App **直接执行**，吃 shebang） |
| `runtime/common/*.sh` | Android（被 `linuxctl` **整份 source**） | 同上：一个语法错就让整个 `linuxctl` 报废 |
| `rootfs/{layer-spec,device-provision}.sh` | Android（`layer-spec.sh` 被 source） | 同上 |
| `runtime/root/{entry,supervise}.sh` | **chroot 后**的 Ubuntu 内 | 可用 bash（环境内一定有） |

**禁止**在宿主侧脚本里使用：`declare -a` / `local x=()`、C 式 `for (( … ))`、
`${var:i:1}`、进程替换 `>(…)`、`[[ =~ ]]`。
`[[ ]]`、`${v//pat/rep}`、`+=`（数组追加）在 mksh R59 里**可用**，但仍不推荐。

> 为什么写成冻结约束：这不是理论问题。改名前实测 `mksh -n runtime/root/start.sh`
> 直接报 `syntax error: unexpected '('` —— 也就是说真机上 `linuxctl start` 从来没能
> 跑起来过。现在 `tools/shell-compat-check.mjs` 在 CI 里守着这条线。

---

## 2. 设备侧目录布局

### 2.1 root 模式（`LINUX_HOME=/data/sunsetlinux`）

```
/data/sunsetlinux/
  etc/
    config.json          # 运行配置（端口、模式、开机自启、冻结豁免）
    channels.json        # 频道列表（见 §5）
    state.json           # 当前已装层版本 + 上次更新时间
  layers/
    base-24.04.3-l1.erofs  # L0 只读：Ubuntu 24.04 minimal（很少变）
    runtime-1.0.0.erofs    # L1 只读：Node + pnpm + 工具 + /opt/sunsetlinux 入口脚本
    dsh-<npm版本>.erofs    # L2 只读：@deepseek-ai/dsh + profile 工作区（经常变）
  layers-mnt/              # 每层的 loop 挂载点
    {base,runtime,dsh}/    # ★ overlay 的 lowerdir 必须是**目录**，不能直接给镜像文件
  upper.img              # 可写层：ext4 稀疏镜像（默认 8 GiB，可扩）
  upper/                 # upper.img 的挂载点（overlay upperdir = upper/upper）
  work/                  # overlay workdir
  rootfs/                # overlay 合并后的挂载点（= chroot 目标）
  seeds/                 # 离线种子（ubuntu-base tarball、node tarball、deb 缓存）
  cache/                 # 下载缓存
  snapshots/             # 可写层快照（tar.zst）
  run/
    dsh.pid              # supervisor 的 PID（宿主 PID 空间）
    dsh.port             # 实际监听的端口
    dsh.url              # 带令牌的登录 URL，权限 0600（每次重启都会变，见 §3.3）
    linux.log            # 环境日志（App 日志页读取）
  bin/
    linuxctl             # 见 §3
    entry.sh             # 兼容入口（转发到 linuxctl）
```

### 2.2 proot 模式（`LINUX_HOME=$APP_FILES/sunsetlinux`）

```
$APP_FILES/sunsetlinux/
  rootfs/                # 直接解开的 Ubuntu 目录（非分层）
  etc/{config.json,channels.json,state.json}
  run/{dsh.pid,dsh.port,dsh.url,linux.log}   # dsh.url 为 0600 的带令牌登录地址
  bin/linuxctl
```

**契约：`linuxctl` 在两种模式下路径相同（相对 `LINUX_HOME`），命令与 JSON 输出完全一致。**
App 不需要关心模式差异，只读 `status` 里的 `mode` 字段。

---

## 3. `linuxctl` 命令契约

实现：`runtime/root/linuxctl.sh` 与 `runtime/proot/linuxctl.sh`。所有子命令必须**幂等**。
所有输出：人类可读文本走 stderr，**JSON 走 stdout**（便于 App 解析）。

| 命令 | 作用 | 退出码 |
|---|---|---|
| `provision [--seed <dir>]` | 首次部署：建目录树、铺层、建 upper.img、写入 config | 0 成功 / 2 已部署 |
| `start` | 启动环境（幂等，已运行则直接返回 running） | 0 |
| `stop` | 停止环境（幂等） | 0 |
| `status` | 输出状态 JSON（§3.1） | 0 |
| `attach [-- cmd...]` | 进入环境执行命令；无 `--` 则开交互 shell | 命令退出码 |
| `exec -- cmd...` | 同 attach 但非交互（供 App 调用） | 命令退出码 |
| `logs [-n N]` | 输出日志尾部 | 0 |
| `snapshot <name>` | 把可写层打包到 `snapshots/<name>.tar.zst` | 0 |
| `restore <name>` | 从快照恢复可写层 | 0 |
| `reset` | 清空可写层 → 恢复出厂 | 0 |
| `update <layer> <file>` | 原子替换某层并重启环境 | 0 |
| `doctor` | 自检（内核能力、层完整性、端口占用、SELinux denials） | 0/1 |

### 3.1 `status` JSON schema（**冻结，App 依赖**）

```json
{
  "schema": 1,
  "mode": "root",
  "state": "running",
  "pid": 12345,
  "uptime_sec": 3721,
  "dsh": {
    "url": "http://127.0.0.1:3080/?token=<launchToken>",
    "base_url": "http://127.0.0.1:3080",
    "port": 3080,
    "version": "0.1.5-rc.2",
    "healthy": true
  },
  "layers": {
    "base":    { "version": "24.04.3-l1", "size": 361234567, "mounted": true },
    "runtime": { "version": "1.0.0",     "size": 88123456,  "mounted": true },
    "dsh":     { "version": "0.1.5-rc.2","size": 91234567,  "mounted": true }
  },
  "storage": { "upper_used": 123456789, "upper_total": 8589934592 },
  "last_error": null
}
```

- `state` ∈ `stopped | starting | running | stopping | error`
- `mode` ∈ `root | proot`
- `url` 是**带令牌**的登录 URL（见 §3.3）；取不到时为 `null`，**绝不用 base_url 冒充登录地址**。
- `base_url` 的**规范形式：`http://127.0.0.1:<port>` —— 无尾斜杠、无路径、无 query**。
  两种模式必须产出**逐字符相同**的形式（App 可能做比较与拼接）。
  - **不要**用"从 `url` 去掉 query"的方式推导：`${url%%\?*}` 会得到带尾斜杠的 `http://127.0.0.1:3080/`，
    与规范形式不一致。
  - **应当由生效端口拼出**：端口按 `url 里的端口 → run/dsh.port → config.port` 的优先级取。
  - 只要端口可知就给出 `base_url`（即使 `url` 为 `null`、环境未运行）。
  - 已实测对齐：`runtime/root/linuxctl.sh` 与 `runtime/proot/linuxctl.sh` 现在都符合本规范。
- `healthy`：向 `base_url` 发 `GET /`，**收到任何 HTTP 响应即视为健康**（未带令牌时预期返回 **401**，这说明服务在跑，不算不健康）。
- 任何字段不可得时为 `null`，**不得省略键**。
- 失败时 `state="error"` 且 `last_error` 为可读原因。

### 3.2 环境内约定（rootfs 内部）

- 环境入口：`/opt/sunsetlinux/entry.sh`
  - 职责：准备 `/etc/resolv.conf`（取 Android 当前 DNS）、`/etc/hosts`、时区、
    `HOME=/root`、`DSH_HOME=/root/.dsh`，然后 `exec` supervisor。
  - **不得**使用 `NODE_OPTIONS --import` 之类注入式补丁。
- supervisor：`/opt/sunsetlinux/supervise.sh`
  - 无 systemd；跑 **`dsh web --no-open --host 127.0.0.1 --port <port>`**。
    （**不要**写成 `exec node <dsh bin> web ...`：profile 工作区要靠 `dsh` launcher 解析，
    见 [`dsh-profile.md`](dsh-profile.md)；`dsh` 是 `lib/bin.js`，`web` 是 `--profile web` 的别名。）
  - **必须捕获 stdout**，从中提取带令牌 URL，写入 `$LINUX_HOME/run/dsh.url`
    （**权限 0600**，因为它是登录凭据）。
    → 因此 supervise.sh **不能直接 `exec`**，要"后台启动 + 截获输出 + 解析 + 落盘 + 前台等待 + SIGTERM 透传"。
    同时写 `run/dsh.pid`、`run/dsh.port`；stdout/stderr 追加到 `run/linux.log`。
  - 注意：`run/dsh.port` 要写**实际**生效的端口（端口被占时 dsh 可能自行换端口）。
  - 停止时要清理 `run/dsh.url` 与 `run/dsh.pid`，避免 App 读到过期令牌。
- 密钥：**绝不放进命令行**。放 `/root/.dsh/.credentials.yaml`（0600）或环境变量文件
  `/root/.dsh/env`（0600，由 entry.sh `set -a; . file`）。

### 3.3 `dsh web` 鉴权模型（实测，**必须严格遵守**）

这部分是实测 DSH 0.1.5-rc.2 的 `@deepseek-ai/dsh-client-connection` 实现得出的结论：

1. `dsh web` 只在 `--host`（我们固定 `127.0.0.1`）上监听，**对 `GET /` 默认返回 401**：
   `dsh web authentication required; reopen the URL printed by dsh web.`
2. 鉴权靠 **查询参数 `?token=<launchToken>`**（参数名实测为 `token`）：
   `GET /?token=<正确令牌>` → **303 重定向到干净 `/`**，并下发一个**签名 Cookie**。
3. `launchToken` 是 `base64url(randomBytes(...))`，**进程内随机、不持久化到任何文件**。
   → **唯一获取途径是解析 `dsh web` 启动时打印到 stdout 的 URL。**
   实测 `/root/.dsha-web.identity` 里的数字**不是**令牌（用它请求得到 401）。
4. **令牌每次重启都会变** → App **不得缓存** `dsh.url`，每次 `start` 后必须重新读 `status`。
5. **Cookie 绑定请求的 `Host` 授权域**（如 `127.0.0.1:3080`）。
   → WebView 必须使用与 `dsh.url` **完全一致**的授权域；
     若 `dsh.url` 是 `127.0.0.1:3080` 而页面改用 `localhost:3080`，Cookie 不匹配 → 401。
6. 带令牌 URL 可重复使用（每次都重新签发 Cookie），但令牌随进程失效。

**stdout 的确切格式**（实测 `@deepseek-ai/dsh-web-app/lib/index.js` 的 `announceReady`）：

```
dsh web: http://127.0.0.1:3080/?token=<launchToken>
```

- 固定前缀 `dsh web: `，其后紧跟带令牌 URL。
- 若运行时能取到 LAN 地址，会在同一行尾部追加 ` (LAN: http://<lan-ip>:<port>/?token=<launchToken>)`。
  → **解析时必须只取第一个空白之前的 URL 部分**，不要贪婪匹配到行尾。
- 该行由 `config.printUrl` 控制（默认开启）。我们启动时用 `--no-open` 避免它去拉浏览器。

**recommended 解析**：在 stdout 中匹配以 `dsh web: ` 开头的行，取 `http://127.0.0.1:` 起始、
到第一个空白为止的子串；校验其中含 `?token=` 后再落盘。

**给 App 的要求**：`DshWebActivity` 每次打开都从最新 `status.dsh.url` 取值加载；
WebView 需启用 Cookie。加载后应停在 `/`（已鉴权），而不是停带 `?token=` 的地址。

---

## 4. root 模式挂载树（`runtime/root/start.sh` 的执行顺序）

```
1. unshare -m --propagation private           # 独立 mount 命名空间
2. unshare -u  → hostname sunsetlinux             # 独立 UTS
   （保留宿主 netns：进 netns 会断网）
3. 可写层：mount -t ext4 -o loop,rw,noatime upper.img upper/
4. 只读层逐层 loop 挂载（★ lowerdir 必须是目录，不能直接给镜像文件）：
     mount -t erofs -o loop,ro layers/base-<ver>.erofs    layers-mnt/base
     mount -t erofs -o loop,ro layers/runtime-<ver>.erofs layers-mnt/runtime
     mount -t erofs -o loop,ro layers/dsh-<ver>.erofs     layers-mnt/dsh
   （挂载前用 magic 校验格式：erofs=0xE0F5E1E2；第三方频道发错格式要给可读错误）
5. 合并：
     mount -t overlay overlay \
       -o lowerdir=layers-mnt/dsh:layers-mnt/runtime:layers-mnt/base,\
          upperdir=upper/upper,workdir=work \
       rootfs/
6. mount -t proc     proc     rootfs/proc
7. mount --rbind /sys         rootfs/sys      (后接 mount --make-rslave)
8. mount --rbind /dev         rootfs/dev      (后接 mount --make-rslave)
   mkdir -p rootfs/dev/{pts,shm}; mount -t devpts devpts rootfs/dev/pts
   mount -t tmpfs -o mode=1777 tmpfs rootfs/dev/shm
9. /tmp 用 tmpfs；**/run 必须 rbind 而不是 tmpfs**：
     mount -t tmpfs tmpfs rootfs/tmp
     mount --rbind $LINUX_HOME/run rootfs/run
   ★ 理由：`dsh.pid` / `dsh.port` / `dsh.url` / `linux.log` 由环境内写入、但由
     **chroot 外的 `linuxctl`** 读取。tmpfs 会让宿主完全看不到里面的内容，契约无法实现。
     rbind 后两侧是同一批 inode，语义与 §2.1 一致，且不需要任何同步机制。
10. sdcard（绕 FUSE）：
     mount --rbind /mnt/pass_through/0/emulated rootfs/mnt/sdcard
     ln -sfn /mnt/sdcard rootfs/storage/emulated/0
   失败则回退 mount --rbind /storage/emulated/0
11. chroot rootfs /opt/sunsetlinux/entry.sh
```

**注意**：
- 本机内核无 `CONFIG_PID_NS`，因此**不做** `unshare -p`，也**不假设 PID 1 语义**。
- **层格式必须是 EROFS**：本机 `# CONFIG_SQUASHFS is not set`，squashfs 根本挂不起来（见 `findings.md` §2）。
- `stop` 要**按相反顺序**卸载，并逐个 `umount` 掉 `layers-mnt/*` 的 loop 挂载点，避免 loop 泄漏。

---

## 5. 频道与更新系统

### 5.1 `channels.json`

```json
{
  "schema": 1,
  "channels": [
    { "id": "official", "name": "官方", "url": "https://example.org/sunsetlinux/channel.json",
      "pubkey": "<ed25519 公钥 base64>", "enabled": true, "priority": 100 },
    { "id": "some-dev", "name": "某开发者内测", "url": "https://.../channel.json",
      "pubkey": "...", "enabled": true, "priority": 50 }
  ]
}
```

- 频道就是**一个 URL + 一个公钥**。第三方开发者可自由发布，无需任何中心审核 → 直接解决"拿不到内测"。
- 签名：`channel.json` 旁有 `channel.json.sig`（ed25519 over 原始字节）。
  校验失败 → 拒绝该频道并提示，**不得静默降级**。

### 5.2 `channel.json`（频道清单）

> **本 schema 冻结**（App、CLI、发布工具三方共用）。改动必须同步 `tools/channel/` 与 `app/`。
>
> 下面示例里的**数值取自一次真实构建**（不是示意值）：`sha256`/`size` 是 `.zst` 与 `.gz` 的
> 真实哈希与字节数，`sha256_raw`/`size_raw` 是解压后裸镜像的真实值。
> 真实三层产物的完整字段见 `dist/channel.json.example` 与 `dist/SHA256SUMS.layers.txt`。

```json
{
  "schema": 1,
  "name": "官方频道",
  "generated_at": "2026-09-15T12:00:00Z",
  "layers": [
    {
      "id": "dsh",
      "version": "0.1.5-rc.2",

      "url": "dsh-0.1.5-rc.2.erofs.zst",   "transport": "zstd",
      "sha256": "<.zst 文件本身的 sha256>",  "size": 32715571,

      "url_gz": "dsh-0.1.5-rc.2.erofs.gz",  "transport_gz": "gzip",
      "sha256_gz": "<.gz 文件本身的 sha256>", "size_gz": 50123456,

      "sha256_raw": "<解压后裸 .erofs 的 sha256>", "size_raw": 211259392
    }
  ],
  "dsh_npm": { "dist_tag": "next", "package": "@deepseek-ai/dsh" }
}
```

**字段语义（关键，别搞混）**：

| 字段 | 含义 |
|---|---|
| `url` / `transport` / `sha256` / `size` | **主产物 = 压缩后的 `.erofs.zst`**。`sha256`/`size` 都是**压缩文件本身**的。 |
| `url_gz` / `transport_gz` / `sha256_gz` / `size_gz` | **回退产物 = `.erofs.gz`**。给"没有 zstd 可用的客户端"（设备侧无 zstd/xz，只有 toybox gzip）。 |
| `sha256_raw` / `size_raw` | **解压之后、真正拿去挂载的裸 `.erofs` 镜像**的哈希与大小。 |

> ⚠️ **`size_raw`/`sha256_raw` 必须由发布工具按真实字节算出来，禁止手填或估算。**
> 我们为此踩过一次：文档示例里曾写 `size_raw: 211288064`，而那是
> `201.5 × 1048576` **手算**出来的，真实值是 **211259392**（差 28672 字节）。
> 客户端若拿 `size_raw` 去预分配输出缓冲，一条元数据笔误就会让解压中途失败。
> 正确做法：发布时**实际解压一次**再 `stat` + `sha256sum`（见 `tools/channel/`）。
> App 侧也做了防御：以 zstd 帧头的 content size 为准、`size_raw` 不符只告警不阻断。

**消费方规则（App 与 CLI 都必须遵守）**：

1. 选产物：**支持 zstd 就取 `url`**；否则取 `url_gz`。两者都不可得 → 明确报错，不得静默跳过。
2. 下载后：按**所选产物的 `sha256`** 校验（这是完整性校验，防止传输损坏）。
3. **必须解压**：按后缀分派（`.zst`→zstd，`.gz`→gzip，无后缀→原样）。
   ⚠️ **绝不能把压缩文件直接交给 `linuxctl update`** —— 它会做 magic 检测
   （erofs=`0xE0F5E1E2`），压缩文件必然被判为"既不是 erofs 也不是 squashfs"而失败。
4. 解压后：再按 **`sha256_raw`** 校验，然后才把裸 `.erofs` 交给 `linuxctl update`。
5. `url` 为相对路径时，相对 `channel.json` 所在 URL 解析。
6. App/CLI 比对 `state.json` 与各频道 `layers[].version`，**只下载变化的那一层**。

> **历史缺陷（✅ 已修复）**：早期 `UpdateApplier` 在第 2 步之后**跳过了解压**，直接把 `.erofs.zst`
> 交给 `linuxctl update` → 更新必然失败（实测报 `magic=28b52ffd`，即 zstd 魔数）。
> 现已按上面 6 条实现，并且 `runtime/root/selftest.sh` 里锁定了"压缩产物必须被拒绝"这条断言，
> 防止有人把校验改宽。
>
> **已用真实三层产物跑通完整发布链路**（`dist/channel/` 就是一份可直接发布的产物）：
> `keygen → gen-manifest → sign → verify --deep → publish-check` 全部通过，
> 且 `verify --deep` 会**整体解压复算** `sha256_raw`/`size_raw` 并与清单比对。
>
> ⚠️ **`sha256_raw`/`size_raw` 必须由发布工具实际解压后算出，禁止手填。**
> 我们为此踩过一次：文档示例曾写 `size_raw: 211288064`，那是 `201.5 × 1048576` **手算**的，
> 真实值是 **211259392**（差 28672 字节）。客户端若拿它预分配缓冲，一条元数据笔误就会让解压中途失败。
>
> ⚠️ **zstd 窗口必须锁在默认档（`windowLog=23` = 8 MiB）**：App 侧用的是**纯 Java** 解码器
> （`zstd-jni` 没有 Android ABI，见下），窗口上限 8 MiB。若发布时用 `--long=27`/`-22` 放大窗口，
> App 会**自动退回 gzip**（不失败，但 dsh 层从 31.2 MB 变 47.8 MB，用户白下）。

### 5.3 DSH 层生成规则（关键：让更新变轻）

`dsh.erofs` 包含以下内容（**含 profile 工作区，详见 [`dsh-profile.md`](dsh-profile.md)**）：

```
/usr/local/lib/node_modules/@deepseek-ai/dsh/**    # DSH 主体 + 其 191 个依赖
/usr/local/bin/dsh -> ../lib/node_modules/@deepseek-ai/dsh/lib/bin.js
/root/.dsh/profiles/**                             # profile 工作区与插件（只读）
```

- 用户状态（`sessions/`、`settings.yaml`、`.credentials.yaml`、`storages/`）**不在层里**，
  由运行时写入可写上层；overlayfs 天然完成切分，不需要符号链接。
- 只装 `@deepseek-ai/dsh` 全局包是**不完整**的：`dsh web` 靠
  `$DSH_HOME/profiles/<profile>/` 里的 bundle 列表 + `cordis.patch.yml` 组装插件树，
  缺了它界面是桌面版、也没有移动端适配。详见 `dsh-profile.md`。
- v1 的 bundles 只用**公开 npm**：`@deepseek-ai/dsh-base`、`@deepseek-ai/dsh-web-app`、
  `dsh-web-mobile`、`dsh-task-notifier`。原生集成由我们的 App 负责。

`runtime.erofs` 只包含 Node 与基础工具；`base.erofs` 是 OS。
**层间不做删除**（overlay 只读 lower 无法表达 whiteout）→ 所有裁剪在 base 层一次性完成。

### 5.4 更新流程

1. 拉取所有启用频道的 `channel.json` + `.sig` → 验签。
2. 汇总可用版本，按 `priority` 或用户手选决定目标版本。
3. 逐层比对 `sha256`，仅下载变化层 → 校验 → 写入 `layers/<id>.erofs.tmp` → 原子 rename。
4. `linuxctl update <layer> <file>` → 重启环境。
5. 失败 → 回滚到上一版本（保留 `layers/*.prev`）。

---

## 6. Android App 契约

- 包名：`io.github.sunsetrne.sunsetlinux`（可改）；minSdk 26，targetSdk 36。
- 技术：Kotlin + Jetpack Compose + Material 3（深色优先）。
- 与 Linux 侧唯一接口：调用 `linuxctl`（root 模式经 `su -c`）并解析 §3.1 的 JSON。
- 组件：
  - `LauncherActivity`：Compose 首页 —— 状态卡（模式/状态/URL/运行时长）、一键启动/停止、
    「打开 DSH」按钮（WebView）、更新角标。
  - `DshWebActivity`：WebView 加载 `status.dsh.url`；提供"在浏览器打开"。
  - `LinuxService`：前台服务（Android 14+ 用 `specialUse` 类型）+ `PARTIAL_WAKE_LOCK`；
    通知含 启动/停止/重启/打开 四个 action。
  - `ProvisionActivity`：首次部署向导（选模式、选频道、下载层、进度）。
  - 设置页：端口、频道管理、开机自启、墓碑/冻结类模块的豁免提示、日志查看。
- 启动器**必须能不被 App 存活所约束**：root 模式下 `start` 由 KernelSU 模块在开机时执行，
  App 仅查询状态。App 被杀不影响环境。

### 6.1 KernelSU 模块

```
module/
  module.prop
  customize.sh        # 安装时：写 /data/sunsetlinux、探测挂载实现、打印结论
  post-fs-data.sh     # 早期：准备目录与权限、同步脚本（不启动）
  service.sh          # late_start：调用 /data/sunsetlinux/bin/linuxctl start（尊重 autostart）
  uninstall.sh        # 默认保留 /data/sunsetlinux（不删用户数据），仅清理模块自身
  webroot/index.html  # ★ KernelSU 模块 WebUI（见 §6.2）
  mkmodule.sh         # 打包：把运行时脚本与 webroot/ 打进 zip
```

**★ 本模块是「纯脚本模块」，不挂载任何系统路径。**

- `module/` 下没有 `system/`、`system_ext/`、`vendor/`、`product/`、`odm/` 等会被 overlay 的目录。
- 因此它**不依赖任何 metamodule**，也**不受 KernelSU 删除内置挂载实现的影响**
  （实测该设备用 `magic_mount_rs` v4.0.8-900 作 metamodule，详见 [`findings.md`](findings.md) §3.4）。
- 本项目自己的 overlay 挂载全部发生在 `unshare -m` 的**私有 mount 命名空间**内，
  与 metamodule 在全局命名空间做的事**互不可见、互不干扰**。
- 虽然不需要挂载，`customize.sh` 与 `doctor` 仍会**探测并报告**挂载实现
  （Magisk 原生 / KernelSU + 哪个 metamodule），以便换环境时快速定位。

### 6.2 模块 WebUI（`webroot/`）

- 形态：KernelSU 的**模块 WebUI** —— 模块根放 `webroot/index.html`，
  由 KernelSU 管理器（或 MMRL / KsuWebUI 等第三方宿主）在 WebView 里打开。
- **Magisk 没有原生 WebUI**，需要第三方宿主才能显示；**没有它也不影响使用**，因为"壳"（Android App）提供同样的管理能力。
- 实测本机 KernelSU 暴露的 JS API（取自设备上某模块 `webroot/index.html` 的真实用法）：
  `ksu.exec(cmd)` 执行命令、`ksu.toast(msg)` 提示。
  实现必须**防御式**：`ksu` 不存在时给可读提示而非白屏；`ksu.exec` 的 Promise/回调两种风格都要兼容。
- 功能（与 App **共用同一份 `etc/config.json`，不得各存状态**）：
  状态卡 / 启动·停止·重启 / **开机自启开关** / 日志尾部 / **一键诊断（doctor）** / 未部署时的下一步指引。
- 单文件、零外部依赖（WebUI 环境可能无网络），深色优先，配色与 App 一致。

### 6.3 首启模式选择（App）

首次启动走**门禁流程**，让用户显式选择运行模式，之后两条路径收敛到同一个 `linuxctl` 契约：

| 选择 | 后续流程 |
|---|---|
| **Root 模式**（推荐） | 检测 `su` → 引导**安装 KernelSU 模块**（模块负责开机自启、让环境不依赖 App）→ 重启后回来 → 部署层 → 启动 |
| **非 root 模式** | 解压/下载 proot 运行时与 rootfs（虚拟环境）→ 启动 |

- 引导页必须**诚实写出能力差距**（真 capabilities / 能挂载 / 环境不被杀 vs 无 capabilities / FUSE / 随 App 进程）。
- 已有的"强制 root 但 `su` 不可用时自动降级并说明原因"逻辑保留；**显式选择优先，探测结果用于校验与提示**。
- App 后续逻辑**不得按模式分支**——模式信息只体现在 `status.mode`。

### 6.4 App 界面设计语言（含取舍理由）

参考了社区同类项目（`xliaoy/DeepSeekHarness`，DSHA 的免 root 二开版，MIT）**在其 README 里描述的设计语言**，
但我们**只借鉴交互形态，不复制代码**（它是 Java+XML，我们是 Kotlin+Compose），并针对它最大的痛点做了反向设计。

| 维度 | 做法 |
|---|---|
| **侧边栏** | `ModalNavigationDrawer`：顶栏汉堡 + 边缘右滑；设置类入口（诊断 / 频道 / 日志导出 / 冻结与省电豁免 / 重新部署 / 恢复出厂 / 关于）全部收进侧边栏 |
| 底栏 | **胶囊式导航栏 3 tab**：启动 / 更新 / DSH |
| 启动页 | 卡片化四段：**状态 → 地址 → 日志 → 操作** |
| **日志区** | **固定高度常显**（约 220dp）+ 行号 + 关键行高亮 + 自动跟随。**不要**做成"有日志才撑开"——那会让失败现场消失 |
| 地址 | 本机地址必做；**局域网标为灰态"需开启（后续）"**，不伪造能力 |
| 状态栏 | `enableEdgeToEdge`，顶栏与状态栏同色，图标随明暗 |
| **inset 契约** | 边到边后**每个页面都要自己消费 inset**：普通页面 `safeDrawingPadding()`；带输入框的页面再加 `imePadding()`（否则键盘盖住输入框），并声明 `windowSoftInputMode="adjustResize"`；外壳里由 `AppShell` 统一消费一次。有 `UiInsetsContractTest` 守着 |
| 返回导航 | 一律 `PredictiveBackHandler`（**跟手**：进度映射成水平位移，抬手才提交）；非首页 tab → 回首页，DSH Web → 网页历史；侧边栏打开时由抽屉自己的回调优先 |
| 冷启动首帧 | 未完成引导时只画 `BootPlaceholder`（轻量占位屏），**不组合整套外壳** —— 避免"纯黑屏等待" |
| 崩溃 | 全局 `UncaughtExceptionHandler` 记录**异常消息 + cause 链 + 40 帧**，可从侧边栏导出 |

**★ 反向设计（针对"老是启动失败却无从下手"）**：

1. 失败必须**带阶段**（授权 / 部署 / 解压 / 挂载 / 启动 / 健康检查），并同时展示 `last_error`；
2. **一键诊断**：醒目入口跑 `doctor`，输出流式展示 + 一键复制，并提示"把这段发给开发者"；
3. 失败时给出**下一步动作建议**（层缺失→去部署；权限不足→去授权；端口占用→改端口）；
4. 状态卡显示 `mode` 徽章 + 状态点。**单色下没有语义色**，所以状态靠"点形态 + 明度 + 文案加粗 + 错误态强描边"区分（见上表）。

**★ 配色 token（App 与模块 WebUI 共用一套，改这里就要两边同步）**：

**全部灰阶（R=G=B）**：单一强调色 = 白，靠**明度 + 描边 + 加粗**表达状态 —— 语义色已彻底移除。
真值见 `ui/theme/Theme.kt`，有 `PaletteContrastTest` 守着（谁塞回彩色就测红）。

| 用途 | token | 值 |
|---|---|---|
| 页面底 / 卡片 / 次级面 / 输入底 | `Mono0`…`Mono3` | `#000000` / `#0A0A0A` / `#141414` / `#1F1F1F` |
| 描边 / 强描边 | `Line` / `LineStrong` | `#2A2A2A` / `#3D3D3D` |
| 强调 / 强调上的字 | `Accent` / `OnAccent` | `#FFFFFF` / `#000000` |
| 正文 / 次要 / 弱化 | `TextPrimary` / `TextSecondary` / `TextMuted` | `#F5F5F5` / `#A3A3A3` / `#6E6E6E` |
| 警告 / 危险 | `WarnTone` / `Danger` | `#C4C4C4` / `#FFFFFF`（危险靠**强描边 + 加粗**抢注意力） |
| 状态点：运行 / 过渡 / 停止中 / 停止 / 错误 / 未知 | `StateRunning`…`StateUnknown` | 白 `#FFFFFF` / `#8A8A8A` / `#8A8A8A` / `#5A5A5A` / 白 `#FFFFFF` / `#6E6E6E` |

**★ 登录令牌默认打码（有意偏离社区同类做法）**：地址卡常显 `base_url`；
带令牌的登录链接**默认隐藏**，提供「显示」与「复制」。
理由：令牌是登录凭据，直接印在屏幕上等于把它暴露在截图/投屏里（§3.3 也要求展示用 `base_url`）。
"复制到桌面浏览器"的能力完整保留。

---

## 7. 仓库结构

```
sunsetlinux/
  docs/{findings.md,architecture.md,dsh-profile.md,ksu-webui-api.md,install.md,updates.md,smoke-test.md}
  rootfs/
    layer-spec.sh          # ★ 唯一共同事实源：层格式/命名/压缩/版本/禁 path 断言
    profiles/{base.packages,runtime.packages}
    profiles/web-profile/            # DSH profile 模板（package.json + cordis.yml）
    profiles/install-web-profile.sh  # 把模板装成可用的 profile 工作区（已实测）
    build-layers.sh        # 宿主/CI 发布构建（source layer-spec.sh）
    device-provision.sh    # 设备侧原生 provisioning（source layer-spec.sh）
  runtime/
    root/                  # ★ 该目录下 **全部 *.sh** 都会被打进模块（glob，非手写清单）
      linuxctl.sh          #   主入口；子命令见 §3
      start.sh stop.sh status.sh
      entry.sh supervise.sh
      doctor.sh            #   自检（含 §1b toybox 能力、§1c 挂载实现探测）
      selftest.sh          #   回归测试（真机上也能跑；夹具在 build/fixtures）
      update.sh            #   WebUI 走 gzip 的更新路径（下载→校验→解压→linuxctl update --version）
      layer-spec.sh 的副本由打包脚本从 rootfs/ 取
    proot/{linuxctl.sh,start.sh,entry.sh,selftest.sh}
    common/{status_json.sh,http_health.sh}
  module/                  # KernelSU 模块
    lib/detect-mount.sh    #   root 实现 / metamodule 探测（含"不在 Android 侧"守卫）
    webroot/               #   ★ 模块 WebUI：index.html + selftest.mjs（纯函数单测，41 项）
    mkmodule.sh            #   打包（bin/ 由 glob 派生，并对必需项断言）
  app/                     # Android 工程（Gradle + Kotlin + Compose）
  tools/
    channel/               # 频道清单生成 + 签名 + 校验 CLI（Ed25519）
                           #   publish-channel.mjs = 一条命令发布（含发布目录整理 + 体检）
                           #   shell-compat-check.mjs 在 tools/ 下：设备侧脚本的 mksh 闸门
    seed/                  # 离线种子制作
    proot-bundle/          # proot 二进制自带打包（GPLv2 合规）
    proot-runtime/         # proot 运行时脚本打包（sunsetlinux-proot-runtime/bin/*）
    contract-check.mjs     # status JSON 契约一致性断言（两套运行时都要过）
  build/fixtures/          # 层格式探测的回归测试夹具（3×2KB 的 magic 头）
  dist/                    # 产物
```

> ⚠️ **打包列表由目录派生，不要改回手写清单**：
> `module/mkmodule.sh` 取 `runtime/root/*.sh` 全量 + `module/lib/` + `module/webroot/`；
> `module/post-fs-data.sh` 遍历 `$BIN_SRC/*`。
> 我们在这上面连踩三次（`lib/detect-mount.sh` 没进包、`lib/` 没同步、`update.sh` 没进包），
> 每次都是"不崩但整块功能静默消失"。手写两份列表必然漂移。

**层的唯一事实源约定**：`rootfs/build-layers.sh`（宿主/CI）是发布构建的事实源；
`rootfs/device-provision.sh`（设备侧）必须产出**一致的层**。两者都 `source rootfs/layer-spec.sh`，
不得各自内联定义层内容、命名、压缩参数或版本格式。

---

## 8. 验收标准

> **契约一致性可以先自动化验证**，不必等真机：
> `node tools/contract-check.mjs --cmd 'bash runtime/root/linuxctl.sh status'`
> 会对 §3.1 的全部键、类型、枚举与三条不变量（base_url 规范形式 / url 必带 token / 不运行不得健康）做断言。
> 两套运行时都必须通过；该脚本也能抓住"缺 `base_url`""`url` 无 token""base_url 带尾斜杠"这些**真实踩过的坑**。

1. `linuxctl provision && linuxctl start` 后 `status.state == "running"`，`dsh.healthy == true`。
2. 环境内 `id` 显示 `uid=0` 且为**真实** root（`cat /proc/self/status | grep CapEff` 非零）。
3. 环境内 `mount -t tmpfs tmpfs /mnt/t && echo ok` 成功（真 capabilities 证据）。
4. 杀掉 App 进程后环境仍 `running`（生命周期解耦证据）。
5. `/mnt/sdcard` 为直挂（`findmnt` 显示 f2fs，非 fuse）。
6. 仅替换 `dsh.erofs` 即可升级 DSH 版本，`reset` 后回到出厂。
7. 第三方频道（自签公钥）能被加入并成功安装其发布的层。
