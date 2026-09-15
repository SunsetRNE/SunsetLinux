# 更新、频道与离线种子

> 面向两类读者：**普通用户**（怎么换频道 / 更新 / 回滚 / 离线部署）与
> **第三方发布者**（怎么自己发一个频道，把自己的 DSH 版本或插件发出去）。
> 契约细节见 [`architecture.md`](architecture.md) §5，命令实现见 `tools/channel/`。

---

## 0. 一句话理解这套东西

```
频道（channel） = 一个 URL + 一个公钥
```

* **URL** 指向一份清单 `channel.json`：里面写了"这一版有哪些层、每层的版本号、大小、sha256、下载地址"。
* **公钥** 用来验签：清单旁边有一个 `channel.json.sig`，是用与公钥配对的私钥签的。

层镜像是 **EROFS**（Android 原生的只读文件系统），分发时再把整个镜像
**zstd** 压缩成 `<层名>-<版本>.erofs.zst`。更新时只下载**变化的那一层**：
DSH 换版本时通常只有 `dsh-<版本>.erofs.zst` 变了，**不需要重下整个系统**。

> **为什么不是 squashfs**：本机内核实测 `CONFIG_SQUASHFS is not set`（也没有模块），
> squashfs 层**根本挂不起来**；而 `CONFIG_EROFS_FS=y`，设备上已有 1742 个 erofs 挂载。
> **为什么不压缩镜像**：本内核 EROFS 只支持 LZ4，压缩率太弱（同一份 node：lz4 69.0 MB
> vs 不压缩 116.4 MB），而"不压缩镜像 + 分发时 zstd-19"只要 31.1 MB，还读得更快。
> 规则写在 `rootfs/layer-spec.sh`（唯一事实源）。

任何人有密钥对 + 一个能放静态文件的地方，就能成为更新源。
**官方只是众多频道中的一个**，没有任何中心审核环节。

---

## 1. 普通用户

### 1.1 层、版本与状态

设备上的层放在 `$LINUX_HOME/layers/`（root 模式是 `/data/sunsetlinux/layers/`），
**运行时用的是解压后的裸镜像**（挂载点直接吃 EROFS）：

| 层 | 设备侧镜像 | 内容 | 变化频率 |
|---|---|---|---|
| L0 | `layers/base.erofs` | Ubuntu 24.04 minimal（OS 与运行库） | 很少 |
| L1 | `layers/runtime.erofs` | Node 24 + pnpm + git/python3/ripgrep + `/opt/sunsetlinux` 入口脚本 | 偶尔 |
| L2 | `layers/dsh.erofs` | DSH 本体 + profile 工作区与移动端插件 | **经常** |

频道里发布的**分发产物**带传输压缩后缀，而且**两种都发布**：

```
base-24.04.3-l1.erofs.zst   ← 主产物（zstd，体积最小）→ App 走这条
base-24.04.3-l1.erofs.gz    ← 回退产物（gzip，零依赖）→ 设备侧纯 CLI 走这条
runtime-1.0.0.erofs.zst / .gz
dsh-0.1.5-rc.2.erofs.zst / .gz
```

* **为什么两种都要发**：设备侧实测**没有 zstd，也没有 xz**，只有 toybox 的 `gzip`
  （`/system/bin/zstd`、`/system/bin/xz` 都不存在）。所以：
  * **App 路径**下载 `.zst`（比 `.gz` 小约 35%），用 App 内的 zstd 解压；
  * **纯 CLI / 无 App 路径**（root 终端手工装层、KernelSU 模块自举）只能用 `.gz` + gzip。
  只发一种就会打断其中一条路径，因此构建时**两种都产出**（额外成本只有一份存储）。
* 镜像**不压缩**（内核只支持 LZ4，压缩率太弱）；`.zst`/`.gz` 是整体压缩后的结果，
  解压出来的裸 `.erofs` 直接挂载。
* 每层的 `sha256`/`size` 对应**它自己那个压缩产物**；裸镜像用 `sha256_raw`/`size_raw`。
  App 解压后要用 `sha256_raw`/`size_raw` 再校验一次。
* 实测参照（真实 dsh 层，`0.1.5-rc.2`）：裸镜像 201.5 MB → `.zst` 31.2 MB（6.5×）
  → `.gz` 47.8 MB（4.2×）。**更新一次只需下载 31.2 MB。**
* ⚠️ **zstd 窗口必须锁在 8 MiB（windowLog ≤ 23）**：App 用的是**纯 Java** zstd 解码器
  （`io.airlift:aircompressor` —— `zstd-jni` 没有 Android ABI），窗口上限就是 8 MiB。
  若发布时用了 `--long=27`、`zstd -22` 之类放大窗口的参数，App 会**自动退回 gzip**
  （不报错，但 dsh 层从 31.2 MB 涨到 47.8 MB，用户白下 16 MB）。
  * 构建侧：`build-layers.sh` 固定用 `zstd -19 --zstd=wlog=23`（Node 回退实现也锁 23）。
  * 工具侧：`gen-manifest`/`verify` 会**直接解析 zstd 帧头**核对窗口（常数级开销），
    超限时警告，`--strict`（含 `publish-check`）直接失败。
* 命名与版本规则的唯一事实源是 **`rootfs/layer-spec.sh`**：`<id>-<version>.<ext>`，
  版本必须是语义版本（`base=24.04.3-l1`、`runtime=1.0.0`、`dsh=<npm 版本>`）。
  宿主/CI 的 `build-layers.sh` 与设备侧的 `device-provision.sh` 都 source 它，
  产出的层**同名同内容**。

当前已装版本记在 `etc/state.json`（记的是语义版本）；上次更新时间也在里面。

### 1.2 看有哪些频道

```bash
# 命令行（等价于 App 设置页里的"频道管理"）
sunsetlinux-channel channels list
```

输出会列出每个频道的：启用状态、优先级、**公钥指纹**、名称、URL。

### 1.3 加一个第三方频道（这是重点）

别人只应该给你两样东西：**一个 channel.json 的 URL** 和 **一段公钥**（base64）。
把公钥通过**带外渠道**核对一次指纹，然后：

```bash
sunsetlinux-channel channels add \
  --id someone-dev \
  --name "某开发者的内测" \
  --url https://example.org/sunsetlinux/channel.json \
  --pub 'QSHdDrvryJ6lY3wEKPP+vVUI+lbPiJxpVMQNsX26Vpw=' \
  --priority 50
```

* `--priority` 越大越优先；官方建议 100，第三方建议 50。你也可以在设置页里手动指定用哪个频道。
* 加完先做一次体检（会真的把清单拉下来验签）：

```bash
sunsetlinux-channel channels check --id someone-dev
```

通过会打印 `✅ 验签通过` 与清单名、各层版本；失败会明确告诉你原因，并且**该频道的内容不会生效**。

> ⚠️ **不要只从同一个 URL 取清单和公钥**。攻击者如果能同时替换两者，就等于没有校验。
> 请让发布者用另一个渠道（聊天、邮件、博客）把公钥或指纹发给你，并核对：
> `ed25519:xx:xx:...`（`list` 与 `add` 都会打印这个指纹）。

### 1.4 更新

```bash
# 拉取所有启用频道的清单 → 验签 → 汇总可用版本（按 priority）
linuxctl update --check          # 只看有什么可更新，不下载

# 真的更新：只下载 sha256 变了的那一层，校验后原子替换，然后重启环境
linuxctl update
```

内部流程（`architecture.md` §5.4）：

1. 拉取每个启用频道的 `channel.json` + `channel.json.sig` → **验签**（失败即丢弃该频道）；
2. 汇总可用版本，按 `priority` 或你的手选决定目标；
3. 逐层比对 `sha256`，**只下载变化层**（App 用 `.erofs.zst`；纯 CLI 用 `.erofs.gz`）→
   按清单里的 `sha256`/`size` 校验下载文件 → 解压得到 `<id>.erofs` →
   再核对裸镜像的 `sha256_raw`/`size_raw` → 写 `layers/<id>.erofs.tmp` → 原子 `rename`；
4. `linuxctl update <layer> <file>` 替换层并重启环境；
5. 失败自动回滚到上一版本（旧的会保留为 `layers/*.prev`）。

下载支持断点续传（`curl -C -`），手机上网络抖一下不用从头再来。
App 首页的"更新角标"就是第 1–2 步的结果。

### 1.5 回滚

```bash
linuxctl update --rollback              # 用 layers/*.prev 回退上一次更新
```

也可以直接指定要回退到哪个版本：

```bash
ls -l $LINUX_HOME/layers/               # 看有哪些 *.prev
linuxctl update dsh $LINUX_HOME/layers/dsh-0.1.5-rc.1.erofs.prev
```

* 层是**只读**的，所以"回滚某一层"就是换回旧文件，不会污染别的东西。
* 你的数据在**可写层**（`upper.img`），它跟层是分开的：换层不会丢数据。
* 想彻底回到出厂状态：`linuxctl reset`（清空可写层，层保持当前版本）。

### 1.6 离线种子（新设备 / 弱网部署）

首次部署要下 60 MB 量级的东西，慢网络下很痛苦。种子包把它们提前打好：

```bash
# 在电脑上先做好（或在有网的设备上做）
tools/seed/mkseed.sh --verify
# → dist/sunsetlinux-seed-<日期>.tar.zst   约 57 MB
#   dist/sunsetlinux-seed-<日期>.tar.zst.sha256

# 传到手机后先校验，再解开
tools/seed/verify-seed.sh sunsetlinux-seed-2026-09-15.tar.zst
mkdir -p /data/sunsetlinux/seeds
tar --zstd -xf sunsetlinux-seed-2026-09-15.tar.zst -C /data/sunsetlinux/seeds --strip-components=1

# 首次部署带上 --seed，本地有就不联网
/data/sunsetlinux/bin/linuxctl provision --seed /data/sunsetlinux/seeds
```

种子包内含：`ubuntu-base-24.04.3-base-arm64.tar.gz`、`node-v24.21.0-linux-arm64.tar.xz`、
可选的 `debs/` 缓存，以及 `MANIFEST`（sha256 清单）与 `SOURCES.txt`（上游 URL 与校验依据）。

* **校验不过就不要用**：rootfs 与 Node 二进制是环境的信任根，被替换 = 整机被接管。
* 设备上没有 `zstd` 命令时，可用仓库自带的 Node 实现解开：
  `node tools/seed/zstd-filter.mjs -d < 包.tar.zst > 包.tar`。

### 1.7 装第三方 DSH 插件（不用等上游发版）

runtime 层里带了 `pnpm`，所以 DSH 自带的插件管理是可用的：

```bash
# 在环境内（linuxctl attach 之后）
dsh plugin --profile web add <npm 包名>       # 也可以给相对路径：. 或 ../my-plugin
dsh plugin --profile web list
```

* 插件会装进 `/root/.dsh/profiles/web/node_modules/`；该目录在**只读层**里，
  overlayfs 会自动 copy-up 到可写上层，所以**装得上**；
  而 `linuxctl reset` 之后会回到出厂的 4 个 bundle —— 语义正好符合"恢复出厂"。
* 想把某个插件**固化进发行版**：改 `rootfs/profiles/web-profile/package.json` 里的
  `dsh.profile.bundles`，重新跑 `rootfs/build-layers.sh` 出新的 dsh 层。
  不要依赖设备上的手工安装。
* 插件作者发的"内测内容"，本质就是一个 npm 包名或一个 git/本地路径 —— 任何人都能发。

---

## 2. 第三方发布者

你不需要任何人批准。完整流程 5 步。

### 2.1 生成密钥（一次就够，然后保管好私钥）

```bash
tools/channel/sunsetlinux-channel keygen --out-dir ~/.sunsetlinux-keys
# 私钥  ~/.sunsetlinux-keys/channel.key   (PKCS#8 PEM, 权限 0600)
# 公钥  ~/.sunsetlinux-keys/channel.pub   (Ed25519 raw 32 字节的 base64)
# 指纹  ed25519:62:1c:8a:49:65:67:8d:72   ← 让用户核对这个
```

* 私钥用 openssl 生成的也行（PKCS#8 Ed25519 PEM 即可，`sign.mjs` 能读）。
* **私钥不要提交进 git、不要贴群里**。备份到离线介质。

#### ★ 两个 key 分别放在哪（最容易混的一件事）

频道体系里有**两个都叫 "key" 的东西**，一个必须保密、一个必须公开：

| | 私钥 | 公钥 |
|---|---|---|
| 文件 | `channel.key`（PKCS#8 PEM，0600） | `channel.pub`（raw 32 字节 base64） |
| 放哪 | **GitHub Secret `CHANNEL_SIGNING_KEY`**（只有 CI 要用到时）＋ 你的**离线备份** | **提交进仓库**：`channel` 分支的 `channel/channel.pub`；并随 URL 一起**发给用户** |
| 能进仓库吗 | ❌ 绝对不行（`.gitignore` 已挡 `*.key` / `channel.key` / `*.pem` / `.sunsetlinux-keys/`） | ✅ 本来就要进（它**不是**秘密；用户拿它当信任根） |
| 用途 | 签 `channel.json` 的原始字节 | 验签；用户核对**指纹**防中间人 |

一句话记法：**Secret 里放私钥，仓库里放公钥，指纹发给人。**

#### 公钥"怎么获取"（私钥进了 Secret / 丢了 channel.pub 时）

公钥**可以从私钥推导出来**，不需要重新生成密钥对 —— 重新生成等于换了身份，
所有用户手里的旧公钥会立刻失效（他们会收到"签名者变了"）。

```bash
# 打印公钥与指纹（stdout 只给公钥一行，方便脚本取用）
tools/channel/sunsetlinux-channel pub-of --key ~/.sunsetlinux-keys/channel.key

# 顺便写回文件（补回丢失的 channel.pub）
tools/channel/sunsetlinux-channel pub-of --key ~/.sunsetlinux-keys/channel.key \
    --write ~/.sunsetlinux-keys/channel.pub

# 机器可读
tools/channel/sunsetlinux-channel pub-of --key channel.key --json
# → {"pubkey":"…","fingerprint":"ed25519:a8:e2:…","key_file":"…"}
```

> ⚠️ 实现细节（踩过）：不能把**私钥对象**直接丢给 `keyObjectToRawPub()` ——
> 它导出的是 SPKI（公钥编码），私钥只能导出 PKCS#8，会报
> `options.type is invalid. Received 'spki'`。必须先 `crypto.createPublicKey(priv)`。

#### 核对"CI 里的私钥"与"仓库里的公钥"是不是同一对

这一步很关键：**两者不匹配的话，CI 签出来的清单用户一个都验不过**，而报错只会显示"验签失败"，
很难联想到是密钥对不上。核对办法就是比指纹：

```bash
# ① 仓库里公钥的指纹（channel 分支上那份）
tools/channel/sunsetlinux-channel pub-of --pub channel/channel.pub

# ② 你手里私钥的指纹（应完全一致）
tools/channel/sunsetlinux-channel pub-of --key ~/.sunsetlinux-keys/channel.key 2>&1 | grep 指纹
```

两条命令打印的 `ed25519:xx:xx:…` 必须**一模一样**；不一致就说明 Secret 里的私钥与仓库里的
公钥不是同一对，先换掉其中一个再发布。

CI 侧（`channel` 分支的工作流）本来就会做这件事：它用 Secret 里的私钥签名，再用仓库里的
`channel/channel.pub` **走另一条代码路径独立验签** —— 对不上就直接失败，不会发出坏频道。

### 2.2 构建要发布的层

* 想发**自己的 DSH 版本**：在 Linux 主机/CI 上跑
  `sudo rootfs/build-layers.sh --dsh-dist-tag <tag>` 或 `--dsh-version <版本号>`，
  产物在 `dist/layers-<日期>/`，每层两个文件：

  ```
  base-24.04.3-l1.erofs        # 镜像（挂载用；构建期也会做 fsck.erofs 校验）
  base-24.04.3-l1.erofs.zst    # 分发产物（频道里发布的是这个）
  runtime-1.0.0.erofs[.zst]
  dsh-0.1.5-rc.2.erofs[.zst]
  ```
  默认 `--erofs-compress none`（镜像不压缩，见 §0 的理由）+
  分发时**同时产出** `.erofs.zst`（主）与 `.erofs.gz`（回退，`--no-gzip-fallback` 可关）。
  `mkfs.erofs` 的参数会先做**能力探测**再使用（宿主 erofs-utils 与设备自带版本参数集不完全一致），
  打包后自动跑 `fsck.erofs` 并核对 profile 软链是否还在。
* 只用官方层、只想发个"频道"（比如只转推某个 DSH 版本）：把官方层文件放到一个目录即可。
* DSH 版本可以来自 npm dist-tag（`latest`/`next`/`alpha`）、确切版本号，
  或你自己打的 tarball（`npm i -g ./my-dsh.tgz` 后照常打层）。

> 只改 DSH 版本时，通常只有 dsh 层会变，base/runtime 层原样复用 ——
> 用户因此只下载 dsh 层的 `.erofs.zst`（几十 MB 量级）。

### 2.3 生成清单

```bash
tools/channel/sunsetlinux-channel gen-manifest \
  --dir dist/layers-20260915 \
  --name "某开发者的内测" \
  --base-url https://example.org/sunsetlinux \
  --dsh-dist-tag next \
  --strict
```

它会：

* 扫描 `base-*` / `runtime-*` / `dsh-*` 的 `.erofs`、`.erofs.zst`、`.erofs.gz`
  （也兼容历史 `.squashfs`，但会**警告**：本机内核挂不起来；`--strict` 下直接失败）；
* 同一层同时有裸镜像和压缩产物时，**优先选分发产物**（zstd > gzip > 裸镜像）；
  多个版本并存时取版本号最高的那个，并把被淘汰的候选打印出来；
* 从文件名取 `version`，并校验它符合 `layer-spec.sh §5` 的语义版本约定
  （`--strict` 下不合规即失败）；
* 流式计算**每个分发产物各自**的 `sha256` 与大小：主产物（`.zst`）+ 回退产物（`.gz`）；
* **真解压**算出裸镜像的 `sha256_raw` 与 `size_raw`：主产物解压一次、回退产物再解压一次，
  两者必须解出**逐字节相同**的镜像，否则**直接失败**（说明产物损坏或不同源）；
  同目录若还有裸镜像，也会一起比对（`--quick-raw` 可跳过解压、直接信任裸镜像，不推荐）；
* 核对 `.zst` **帧头**的窗口与内容大小：窗口 > 8 MiB 会警告（`--strict` 失败），
  帧头声明的解压后大小与实测不符也直接失败；
* 联网核对 `--dsh-dist-tag` 指向的版本与 dsh 层文件名是否一致（不一致会警告，`--strict` 下失败）；
* 写出 `channel.json`。

`channel.json` 里每层的字段：

| 字段 | 含义 |
|---|---|
| `id` / `version` | 层标识与语义版本 |
| `fs` | 镜像格式，现在恒为 `erofs` |
| `transport` | **主产物**的压缩方式：`zstd` / `gzip` / `none` |
| `url` | 主产物下载地址（`.erofs.zst`；相对路径时相对 `channel.json` 所在 URL 解析）**→ App 用这条** |
| `sha256` | `url` 指向的那个**压缩产物**的 sha256 |
| `size` | `url` 指向的那个压缩产物的字节数（用户实际要下的量，可用来算进度） |
| `transport_gz` | 回退产物的压缩方式（通常 `gzip`） |
| `url_gz` | 回退产物地址（`.erofs.gz`）**→ 设备侧纯 CLI 用这条**（没有 zstd 时） |
| `sha256_gz` / `size_gz` | 回退产物自己的 sha256 与字节数 |
| `sha256_raw` / `size_raw` | 解压**之后**的裸 EROFS 镜像的 sha256 与字节数（挂载前最后一道校验） |

> ⚠️ **`sha256_raw` / `size_raw` 只能由工具真解压算出来，禁止手填、禁止用 MB 数换算。**
> 真实教训：文档里曾手算 `size_raw = 211288064`（201.5 × 1048576），真实值是
> **211259392**，差 28672 字节 = 7×4096。App 若拿它预分配输出缓冲，一条元数据笔误就会
> 让解压中途失败。三重独立证据：① `.zst` 与 `.gz` 解压后都是 211259392 且逐字节相同；
> ② EROFS 超级块声明 `blocks=51577, blkszbits=12` → 211259392；
> ③ zstd 帧头的 Frame_Content_Size = 211259392。
> `gen-manifest` 会真解压算，`verify` 会**再独立解压算一遍**并比对，不一致直接拒绝。

> 老清单里可能只有 `size`/`transport_size`（没有 `.gz` 与 `*_raw`）——`verify` 会自动兼容，
> 但**新发布的频道请用新字段**：`.gz` 缺失时设备侧的纯 CLI 路径就断了。

示例（`--base-url https://example.org/sunsetlinux` 时）：

```json
{
  "schema": 1,
  "name": "某开发者的内测",
  "generated_at": "2026-09-15T12:00:00Z",
  "layers": [
    { "id": "dsh", "version": "0.1.5-rc.2", "fs": "erofs",
      "transport": "zstd",
      "url":   "https://example.org/sunsetlinux/dsh-0.1.5-rc.2.erofs.zst",
      "sha256": "…", "size": 92103456,
      "transport_gz": "gzip",
      "url_gz": "https://example.org/sunsetlinux/dsh-0.1.5-rc.2.erofs.gz",
      "sha256_gz": "…", "size_gz": 118336512,
      "sha256_raw": "…", "size_raw": 356515840 },
    { "id": "runtime", "version": "1.0.0", "…": "（同上结构）" },
    { "id": "base", "version": "24.04.3-l1", "…": "（同上结构）" }
  ],
  "dsh_npm": { "dist_tag": "next", "package": "@deepseek-ai/dsh" }
}
```

真实产物示例（本机实测的 dsh 层，`dsh-0.1.5-rc.2`）：

```json
{
  "id": "dsh", "version": "0.1.5-rc.2", "transport": "zstd",
  "url": "dsh-0.1.5-rc.2.erofs.zst",
  "sha256": "04ddce5f5edee710fc7466d46db40c70b06b11cbdc3d4bad0e2b9d8b9e25e820",
  "size": 32753059,
  "sha256_raw": "d9674df77923341e39759750c36f57bb53e481296129dd4e1cb80864faa60c29",
  "size_raw": 211259392,
  "transport_gz": "gzip",
  "url_gz": "dsh-0.1.5-rc.2.erofs.gz",
  "sha256_gz": "30333242466252e5b0099bf1e14a34003b1d58fb93c8541b20fd18e4c247c1d9",
  "size_gz": 50146411
}
```

即：裸镜像 **201.5 MB** → 主产物 `.zst` **31.2 MB**（压缩 6.5×）→ 回退 `.gz` **47.8 MB**（4.2×）。
**用户更新 DSH 只需下载 31.2 MB。** 字段顺序固定为
`id, version, transport, url, sha256, size, sha256_raw, size_raw, transport_gz, url_gz, sha256_gz, size_gz`
（`fs` 字段默认不输出，用 `--include-fs` 才会加，避免严格 JSON 解析器报未知字段）。

> ⚠️ `size_raw` 必须是**解压后实际得到的字节数**，不是构建中间产物的 `ls -l` 大小：
> 两者可能相差若干块（本机实测差 28672 字节 = 7×4096）。App 用
> `size_raw` 校验解压结果，填错会导致"解压成功却被判损坏"。

`--base-url` 决定 `layers[].url` 是绝对地址还是相对文件名（相对时相对 channel.json 所在 URL 解析）。
如果层文件托管在别处（对象存储、GitHub Releases），用 `--base-url` 指过去即可。

### 2.4 签名

```bash
tools/channel/sunsetlinux-channel sign \
  --in  dist/layers-20260915/channel.json \
  --key ~/.sunsetlinux-keys/channel.key
# → dist/layers-20260915/channel.json.sig
```

签名对象是 **channel.json 的原始字节**。签完**不要再格式化/改动**这个文件 ——
改一个空格签名就失效（这是刻意的：验签端拿到的字节必须与签的完全一致）。
`sign.mjs` 默认会用对应公钥自验一次，自验不过不会写出签名。

### 2.5 发布前自检 → 上传 → 把 URL + 公钥发出去

```bash
tools/channel/sunsetlinux-channel publish-check \
  --pub ~/.sunsetlinux-keys/channel.pub --dir dist/layers-20260915
# 以 --strict 语义检查（发布前把关，任何一项不过就退出码 1）：
#   channel.json/.sig 齐备；每层的 .zst 与 .gz 都在本地；验签通过；
#   两份产物的 sha256/size 匹配；真解压复算 sha256_raw/size_raw；
#   .zst 与 .gz 解出同一个镜像；zstd 窗口 ≤ 8 MiB（否则 App 会退回 gzip）
```

把**同一个目录下**的全部文件（`channel.json`、`channel.json.sig`、各层的
`.erofs.zst` **和** `.erofs.gz`；裸 `.erofs` 不必上传）上传到任意静态托管：

* GitHub Releases（把 `channel.json` 固定成一个 release 的 asset URL，或放在 Pages 上）
* 任意 HTTP 静态服务器 / CDN / 对象存储（OSS、S3、R2…）
* 自建的小水管机器也行 —— 用户可以只信任你的公钥，跟你用什么托管无关

然后把这两样东西发给用户（**通过另一个渠道**）：

```
URL    : https://example.org/sunsetlinux/channel.json
公钥   : QSHdDrvryJ6lY3wEKPP+vVUI+lbPiJxpVMQNsX26Vpw=
指纹   : ed25519:62:1c:8a:49:65:67:8d:72
```

用户侧：

```bash
sunsetlinux-channel channels add --id your-id --name "你的频道" \
    --url https://example.org/sunsetlinux/channel.json --pub '<公钥>' --priority 50
```

> 也可以把清单发到任何地方（论坛、群文件），只要 `channel.json` 与 `channel.json.sig` 成对出现。

### 2.6 版本号怎么排序（会决定清单指向哪一层）

`gen-manifest` 会在同一层的多个候选里挑"最新版"。排序语义由**三处必须一致**的实现共同定义
（WebUI、`runtime/root/update.sh`、`tools/channel/common.mjs`），规则是：

1. base 层的 `<ubuntu 版本>-l<n>`：`l<n>` 按**整数**比 —— `24.04.3-l9 < 24.04.3-l10 < 24.04.3-l100`
   （按字符串比会得出"l9 比 l10 新"的错误结论）。
2. 其余层按语义版本：**有预发布后缀的更小** —— `0.1.5-rc.2 < 0.1.5`、`1.0.0-rc.1 < 1.0.0`。
3. 主干按数字段比：`04` 视为 `4`，`1.0.10 > 1.0.9`。

也就是说：**目录里同时留着新旧版本是安全的**（回滚正是这么做的），工具会选对最新那层。
防漂移闸门：`node tools/cmp-consistency.mjs`（16 组用例三方对照，必须全绿；任何一处改了语义
或只改一处，它会立刻变红）。

> 边界语义（三处共享，不是 bug）：`24.04.3` 与 `24.04.3-l100` 相比时，`-l100` 被当作预发布
> 后缀 → `24.04.3` 更大；带 `v` 前缀的版本号（`v1.2.3`）排序位置不在常规位置。
> 本项目的 base 层始终使用 `-l<n>` 形式，不会踩到这两个边界。

### 2.7 发布者必须知道的三条硬规则

1. **不能删层，只能换版本。** 层以只读 lower 叠加，`channel.json` 里声明的每一层
   都会成为合并视图的一部分。要"去掉"某个文件，只能出新版层（文件被新版覆盖），
   不能靠"不提供"来删除。所有裁剪（文档、locale、多余包）应在 base 层一次性完成。
2. **版本号必须变。** 用户是按 `sha256` + `version` 判断要不要下载的；
   内容变了但版本号没变，会造成"有人更新了、有人没有"的分裂。
3. **许可红线（产物要能合法分发）。** 发布前确认层里没有来路不明的代码：
   例如 DSHA 的 `dsh-app-integration` 包 **package.json 里没有任何 license 字段 =
   默认保留所有权利，不得复制、不得打进产物**；`dsh-device-shell-guide`、
   `dsh-status-overlay` 是 MIT 但非 npm 公开，复用**必须**随附 MIT 文本并注明来源与作者。
   默认配方（`dsh-base`、`dsh-web-app`、`dsh-web-mobile`、`dsh-task-notifier`）
   全部是 npm 公开、许可清晰，可以直接分发。
   `rootfs/build-layers.sh` 会在打包前做一次自动检查。

---

## 2.9 官方频道怎么部署（本项目自用，一条命令）

前面 §2 是"任何第三方发布者"的通用流程。如果你要发的是**自己项目的官方频道**，
照下面走即可 —— 工具已经把易错点（挑版本、真解压复算 `sha256_raw`、签原始字节、
发布目录里层文件齐备）收进一条命令：

```bash
# 0) 一次性：生成频道密钥（私钥只留在你自己机器上，绝不提交）
tools/channel/sunsetlinux-channel keygen --out-dir ~/.sunsetlinux-keys
#    私钥 ~/.sunsetlinux-keys/channel.key  (PKCS#8 PEM, 0600)
#    公钥 ~/.sunsetlinux-keys/channel.pub  (raw 32 字节 base64 —— 发给用户)
#    指纹 ed25519:87:1f:86:f3:60:21:bd:1f   ← 让用户核对这个，防中间人

# 1) 构建三层（需要 arm64 + 真 chroot；约 244 MiB 产物）
bash rootfs/build-layers.sh --out-dir dist --runtime-dir runtime/root --dsh-dist-tag next

# 2) 生成 + 签名 + 体检 + 整理发布目录（一条命令）
tools/channel/sunsetlinux-channel publish-channel \
    --base-url https://<托管前缀>/sunsetlinux \
    --name "SunsetLinux 官方" --dsh-dist-tag next

#    它会：把 *.erofs.zst / *.erofs.gz **硬链**进 dist/channel（不额外占空间）
#          → gen-manifest（真解压复算 sha256_raw）
#          → sign（签 channel.json 原始字节）
#          → verify --strict（独立验签 + 逐层 + 解压复算）
#          → 打印「要上传的文件清单 + 总量 + 给用户的 URL/公钥/指纹 + 订阅命令」

# 3) 把 dist/channel 里的**全部文件**上传到 --base-url；channel.json 上传后一个字节都别再改
```

### 托管放哪：先算体积再选

实测一次全量频道 **244 MiB**：

| 层 | `.erofs.zst`（App 走这条） | `.erofs.gz`（设备侧纯 CLI 走这条） |
|---|---|---|
| base | 18.6 MiB | 26.5 MiB |
| runtime | 46.5 MiB | **73.5 MiB** |
| dsh | 31.2 MiB | 47.8 MiB |

| 载体 | 适配度 | 说明 |
|---|---|---|
| **对象存储 / CDN**（R2、S3、OSS…） | ✅ 推荐 | 没有单文件/仓库体积限制，快、便宜；清单里 `url` 是绝对地址，可以只把 `channel.json` 放在别处 |
| npm 包（`npm-pack`） | ⚠️ 仅适合小频道 | 省事、自带版本与完整性，但要注意 npm 对包体积的限制，244 MiB 很容易撞墙 |
| GitHub Pages / gh-pages | ⚠️ 会满 | 单文件上限 **100 MB**、仓库软上限 **1 GB** → 全量目录约 **4 次**就撑满，而 `runtime-*.erofs.gz` 已经 73.5 MB 贴着上限 |
| GitHub Releases | ❌ 与当前发布模型冲突 | 它需要 **tag**，而本项目刻意用**分支式发布**（beta/stable）不用 tag |

> 💡 层的日常更新是**增量**的：只改 DSH 时用户只下 `dsh` 层（31.2 MiB），
> 所以别把"全量 244 MiB"当成每次发布的成本 —— 但**首次部署**与**回归旧版本**是真要这么多。

### 想让 CI 持私钥签名？（可选，`channel` 分支）

私钥不想常年放在日常机器上时，可以走"本机出清单 → CI 签名"这条：
本机只跑 `gen-manifest`（**不需要私钥**），把 `channel.json` + `channel.pub` 推到
`channel` 分支，CI 用 Secret `CHANNEL_SIGNING_KEY` 签名、验签、发到 gh-pages `/channel/`。
完整步骤与两种模型的取舍见 [`docs/release-ci.md`](release-ci.md) §5.2。

### 让用户用上你的频道

有两种做法，**当前代码里的默认是第一种**：

1. **用户手填**（现状）：App「设置 → 频道管理 → 添加」，填 `channel.json` 的 URL + 公钥；
   命令行等价：`sunsetlinux-channel channels add --id official --url … --pub …`。
   好处是零信任内置、完全去中心化；代价是**新用户要自己粘两串东西**。
2. **内置默认频道**（⏳ 未实现，需要你定）：把 URL + 公钥写进 App（`BuildConfig` 或环境播种），
   首启就带一个「官方」频道且不可删除。要做的话给我 `--base-url` 对应的
   `channel.json` URL 与公钥即可 —— 指纹会一起写进 `docs/`，用户可以核对。

---

## 3. 安全模型（请读完再发频道）

### 3.1 信任边界

| 事实 | 推论 |
|---|---|
| 频道 = URL + **公钥**；清单必须带有效签名 | 攻击者改不了清单内容（改了就验签失败） |
| 清单里含每层的 `sha256` | 攻击者替换下载文件也会被校验挡住 |
| `sha256`/`size` 对应各自的**压缩产物**，`sha256_raw`/`size_raw` 对应**裸镜像** | 传输被截断/替换被前一组挡住；解压后被截断/写坏被后一组挡住；主/回退两条路径各验各的 |
| `sha256_raw`/`size_raw` 是**真解压算出来**的，不是估算 | 发布工具不会写错；App 预分配缓冲与"解压后校验"才有可靠依据 |
| zstd 窗口 ≤ 8 MiB | App 的纯 Java 解码器能直接解 `.zst`，不会退回 `.gz`（省 35% 下载量） |
| 私钥只在你手里 | 只有你能发布"你这个频道"的新版本 |
| 公钥由用户手工填入 | 用户核对指纹这一步，是**唯一的**人肉信任锚 |

### 3.2 验签失败会怎样

**拒绝，并明确报错，绝不静默降级。**

```
❌ 校验失败：
  · 签名无效：这份 channel.json 与私钥持有者签的那份不一致。
  ...
该频道必须被拒绝。 不要忽略校验失败继续安装：那等于让任何人（包括中间人）
往你的环境里塞任意文件。请向频道发布者核对 URL 与公钥指纹，或换一个频道。
```

失败原因会区分：签名无效 / 签名文件不是合法 base64（例如托管返回了 HTML 错误页）/
层大小不符 / 层 sha256 不符 / 清单 schema 不对。
`verify.mjs` 退出码为 1，`linuxctl update` 会跳过这个频道并继续用已装的版本。

### 3.3 私钥泄露的后果（以及怎么办）

* 拿到私钥的人可以签出**任意**清单，让所有信任这把公钥的用户装到任何东西（含恶意代码）。
* **补救**：立即 `keygen` 生成新密钥对，用新公钥重签清单，并通过所有渠道通知用户
  「换频道 / 更新 pubkey」。旧公钥对应的频道对已更新的用户即失效。
  （公钥是用户手工填的，所以你必须**主动通知** —— 这也是这套系统没有中心化撤销的原因。）
* 因此：私钥不要进 CI 日志、不要进构建产物、不要 `curl` 到别处。
  `rootfs/build-layers.sh` 会检查层里有没有私钥/凭据文件，命中即失败。

### 3.4 中间人 / 恶意镜像

* 私有镜像（把你那份文件改掉）→ 验签失败，用户拒绝。
* 把公钥和清单一起换掉 → 只要用户核对过指纹就发现不了**唯一**条件是用户没核对。
  → 所以请把指纹和公钥放到与清单**不同的渠道**，并鼓励用户核对
  （`sunsetlinux-channel channels list` 会显示指纹，`add` 时也会打印）。
* HTTP（非 HTTPS）托管本身不是致命的（有签名），但会让攻击者知道你在下载什么，
  也给"顺手改公钥"留下机会 → **能用 HTTPS 就用 HTTPS**。

### 3.5 为什么这套东西解决了"拿不到第三方内测"

旧模型：内容要进中心仓库、要过审核、要跟着官方发版节奏 → 第三方内测拿不到、慢。
新模型：

* 发布者**自己生成密钥、自己写一个 channel.json**，传到任何静态托管；
* 用户**把 URL + 公钥加进 channels.json** 就完事，官方不参与、不能否决；
* 更新只下变化层（DSH 版本更新约 94 MB），不用重下整个系统；
* 插件层面同理：`dsh plugin --profile web add <包名>` 可以直接装**任何**第三方发布的
  DSH 插件（runtime 层自带 `pnpm`），不必等上游把插件收进发行版。

安全与开放是对调过的：**不靠中心审核，靠密码学签名 + 用户自己选择信任谁。**

---

## 4. 排错速查

| 现象 | 原因 / 处理 |
|---|---|
| `签名无效` | 清单被改过/重新格式化；或公钥填错；或有人在中间替换。**先核对指纹** |
| `签名文件不是合法 base64` | 托管把 404 页面当 `.sig` 返回了（URL 写错）；用 `publish-check` 复查 |
| `层文件缺失` | 只是还没下载完（正常）→ 加 `--strict` 才会当失败；下完再验 |
| `层 sha256 不符` | 下载文件损坏或版本不对。重新下载；仍不行就是发布者传错了 |
| `裸镜像字节数/sha256 不符` | 解压出的 `.erofs` 被截断/写坏（磁盘满？） |
| `回退产物 sha256 不符` | `.gz` 传坏了 —— 纯 CLI 路径会装到坏层 |
| 回退产物（.gz）不在本地 | 构建时加了 `--no-gzip-fallback`，或忘了上传；`--strict` 下视为失败 |
| `主产物与回退产物解压结果不一致` | 两个产物不是同一次构建出来的（不同源），或其中一个损坏 → 重新构建 |
| `zstd 窗口超 8 MiB` | 发布时用了 `--long=27` / `-22`；App 会退回 gzip。改用 `zstd -19 --zstd=wlog=23` |
| `帧头声明的解压后大小与清单不一致` | 清单里的 `size_raw` 是手填/估算的 → 用 `gen-manifest` 重新生成 |
| 层挂载报 `unknown filesystem type 'squashfs'` | 拿到了旧格式的 squashfs 层；本机内核不支持，必须用 EROFS 层 |
| 分发产物解不开 | `.zst` 用 zstd，`.gz` 用 gzip —— App 按 URL 后缀分派解压器 |
| `pnpm not found on PATH` | runtime 层没带 pnpm（应随层交付）。回到 `runtime` 层修，不要手工装 |
| `dsh plugin` 装完没生效 | 插件写进了可写上层；`reset` 会回出厂。要固化请改 profile 重出层 |
| 更新后界面是桌面版 | dsh 层缺 `profile`（`/root/.dsh/profiles/web/`）。见 `dsh-profile.md` §5 |
| 下载很慢 / 中断 | 支持断点续传；弱网优先用离线种子（§1.6） |

---

## 5. 命令索引

| 命令 | 作用 |
|---|---|
| `sunsetlinux-channel keygen` | 生成 Ed25519 频道密钥对（`channel.key` 0600 / `channel.pub` base64） |
| `sunsetlinux-channel gen-manifest` | 扫描层文件 → 生成冻结 schema 的 `channel.json`（主/回退产物的 sha256+size、裸镜像 `sha256_raw`/`size_raw`） |
| `sunsetlinux-channel sign` | 对 `channel.json` 原始字节签名 → `channel.json.sig` |
| `sunsetlinux-channel pub-of` | 从**私钥**推导公钥与指纹（`--key`），或只查一个公钥的指纹（`--pub`）；用于"私钥进了 Secret/丢了 channel.pub"以及核对密钥对 |
| `sunsetlinux-channel verify` | 验签 + 主/回退产物 sha256/size + **解压复算** `sha256_raw`/`size_raw` + zstd 窗口合规；退出码 0/1（`--no-raw-recompute` 可跳过复算，会明确提示） |
| `sunsetlinux-channel channels …` | `list/add/remove/set/enable/disable/check/init` 管理 `channels.json` |
| `sunsetlinux-channel publish-check` | 发布前体检（`--strict` 语义）：层两种产物齐备 + 验签 + sha256 + 解压复算 + zstd 窗口 |
| `sunsetlinux-channel publish-channel` | **一条命令发布**：整理发布目录（硬链层文件）→ `gen-manifest` → `sign` → `verify --strict` → 打印上传清单与给用户的信息（`tools/channel/publish-channel.mjs`） |
| `tools/seed/mkseed.sh` | 制作离线种子包 `dist/sunsetlinux-seed-<日期>.tar.zst` |
| `tools/seed/verify-seed.sh` | 校验种子包（整包 sha256 + MANIFEST + 文件魔数） |
| `rootfs/build-layers.sh` | 宿主/CI 上用真 chroot 构建三层 EROFS 镜像 + 生成 `.erofs.zst` 与 `.erofs.gz` 双分发产物 |
| `rootfs/layer-spec.sh` | **层规格唯一事实源**：命名/镜像格式/压缩/版本/禁止路径/断言（被构建脚本 source） |
| `rootfs/profiles/install-web-profile.sh` | 在构建 chroot 内把 web profile（插件工作区）装到最终路径 |

每个命令都支持 `--help`，输出为中文。
