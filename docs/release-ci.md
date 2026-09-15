# 发布与 CI：分支式、回归门禁、不建 tag

> 本文确定发布模型并说明为什么这样设计。
> 一句话：**推送分支即发布**；`beta` 出预发布、`main` 出正式；每次发布前先跑**全量回归**，绿了才发。

---

## 一、为什么"不用 tag"要换个分发形态

**GitHub Releases 在机制上必须挂在 tag 上** —— 所以"不建 tag + 要给人下载"这两件事，
只能用别的载体。可选的有三种：

| 载体 | 需要 tag？ | 用户下载体验 | 适合 |
|---|---|---|---|
| GitHub Releases | **需要**（哪怕是 `latest` 这种移动 tag） | 最好（有页面、有说明） | 有 tag 的流程 |
| **`gh-pages` 分支（GitHub Pages）** | **不需要** | 好（固定 URL，浏览器直接下） | ★ 本方案 |
| Workflow Artifacts | 不需要 | 差（要登录、90 天过期） | 仅内部调试 |

**采用 `gh-pages` 分支**：CI 把产物推到该分支的固定目录，网页与频道都从同一处取。
这同时解决了"频道 URL 从哪来"的问题 —— **频道就是 Pages 上的一个静态文件**。

```
gh-pages 分支
  /beta/           ← beta 频道
    channel.json
    channel.json.sig
    sunsetlinux-launcher-<版本>.apk
    sunsetlinux-module-<版本>.zip
    base-*.erofs.zst  runtime-*.erofs.zst  dsh-*.erofs.zst
  /stable/         ← 正式频道（结构同上）
  index.html       ← 可选：一句说明 + 两个频道链接
```

频道 URL 形如：
```
https://<用户名>.github.io/<仓库名>/beta/channel.json
https://<用户名>.github.io/<仓库名>/stable/channel.json
```

---

## 二、分支模型

| 分支 | 定位 | 推送后发生什么 |
|---|---|---|
| `main` | 正式版源码 | 跑全量回归 → 通过则发布到 **`/stable/`** 频道 |
| `beta` | 预发布源码 | 跑全量回归 → 通过则发布到 **`/beta/`** 频道 |
| `feat/*`、`fix/*` | 开发 | 只跑**回归门禁**，不发布 |

**合并方向**：`feat/*` → `beta` →（验收后）→ `main`。
这样 `beta` 天然是"下一版候选"，`main` 是"已验收"。

---

## 三、回归门禁（发布前必过）

无论哪个分支，CI 先跑**同一套全量检查**；**任一失败即中止发布**（不发半成品）。

| 检查 | 命令 | 断言数 |
|---|---|---|
| Android 单测（必须两条一起跑） | `./gradlew :app:assembleDebug :app:testDebugUnitTest` | 47 |
| root 运行时回归（bash） | `bash runtime/root/selftest.sh` | 20 |
| root 运行时回归（**mksh**，模拟设备侧） | `mksh runtime/root/selftest.sh` | 20 |
| proot 运行时回归 | `bash runtime/proot/selftest.sh` | 18 |
| 模块 WebUI 纯函数 | `node module/webroot/selftest.mjs` | 60 |
| 三方版本比较一致性 | `node tools/cmp-consistency.mjs` | 16 |
| status JSON 契约 | `node tools/contract-check.mjs` | 冻结 schema |
| **设备侧 shell 兼容性**（mksh 可解析 + shebang 正确） | `node tools/shell-compat-check.mjs` | 19 个脚本 |

> ⚠️ **`assembleDebug` 不编译 test 源集**：只跑它会**静默跳过全部单测**。
> 这在开发中真实发生过一次（单测引用已删除的符号，APK 照样出得来）。所以这两条永远一起跑。
>
> ⚠️ **设备侧脚本必须过 mksh**：Android 上没有 bash，`/system/bin/sh` 是 mksh。
> 这一关失败意味着"本地 bash 全绿、真机一行都跑不了"（本轮就是这么发现 root 模式从来没跑起来过）。
> 详见 `docs/architecture.md` §1.1 与 `docs/STATUS.md` §3.4。


---

## 四、层产物（base/runtime/dsh）：**不要每次推送都重建**

三层两式合计约 150 MB，而且构建需要 **arm64 + 真 chroot + qemu/binfmt**（GitHub 的 runner 是 x86-64）。
每次推送都重建会又慢又浪费。所以：

| 产物 | 触发方式 | 说明 |
|---|---|---|
| APK / 模块 zip | **每次推送**（beta/main） | 小、快（`app/`、`module/`、`runtime/` 任一变化就该重发） |
| 三层镜像 | **手动触发**，或**仅当 `rootfs/` 变化时** | 工作流里用 `docker/setup-qemu-action` 提供 arm64 模拟；也可在有 arm64 机器时本地构建后上传 |

> 层的**日常增量**交给频道本身：用户升级只下 `dsh` 层（约 31 MB），不需要 CI 每次重发三份。

---

## 五、需要的 Secrets（**绝不提交到仓库**）

| Secret | 用途 | 缺失时 |
|---|---|---|
| `CHANNEL_SIGNING_KEY` | Ed25519 私钥，签 `channel.json` | ⚠️ **当前工作流并未使用它**：`release.yml` 只发布 APK + 模块 + `index.json`；**层与频道清单是手工发布的**（见 §六与 `docs/updates.md` §3）。配不配都不影响 CI 通过 —— 这条以前写成"缺失即失败"，是文档与实现的漂移，已更正 |
| `ANDROID_KEYSTORE_BASE64` | 正式签名 keystore（base64） | 未配置时只出 **debug 包**，并在 Release 说明里标注 |
| `ANDROID_KEYSTORE_PASSWORD` / `ANDROID_KEY_ALIAS` / `ANDROID_KEY_PASSWORD` | 签名口令 | 同上 |

### 5.1 层产物与频道清单：**不在 CI 里，走 `publish-channel`**

三层两式合计约 **244 MB**（实测：base 18.6+26.5 / runtime 46.5+73.5 / dsh 31.2+47.8 MiB），
且构建需要 **arm64 + 真 chroot**（GitHub runner 是 x86-64，要 qemu/binfmt）。所以它们**不进 CI**，
而是在有 arm64 的机器上构建后，用一条命令发布：

```bash
# 1) 构建层（arm64 + 真 chroot；本工作容器没有 CAP_SYS_ADMIN，chroot 会报 Function not implemented）
bash rootfs/build-layers.sh --out-dir dist --runtime-dir runtime/root --dsh-dist-tag next

# 2) 生成 + 签名 + 体检 + 打印上传清单（层文件用硬链整理进发布目录，不额外占空间）
tools/channel/sunsetlinux-channel publish-channel \
    --base-url https://<你的托管前缀>/sunsetlinux \
    --key ~/.sunsetlinux-keys/channel.key \
    --pub ~/.sunsetlinux-keys/channel.pub \
    --name "SunsetLinux 官方" --dsh-dist-tag next

# 3) 把打印出来的文件清单整体上传到 <base-url>，然后把 URL + 公钥 + 指纹发给用户
```

> ⚠️ **托管选型有个硬约束**：GitHub Pages 单文件上限 **100 MB**、仓库软上限 **1 GB**。
> 一次全量频道 244 MB → gh-pages 大约 **4 次**就撑满，而且单文件 73.5 MB（`runtime-*.erofs.gz`）
> 已经贴着 100 MB 上限。**建议：层文件放对象存储/CDN（R2、S3、OSS…），
> `channel.json` 与 `.sig` 放哪都行**（清单里的 `url` 是绝对地址，可以指向另一个域名）。
> 用户只信公钥，跟你用什么托管无关。

### 5.2 `CHANNEL_SIGNING_KEY` 到底怎么弄（两种模型，二选一）

它就是一个 **Ed25519 私钥（PKCS#8 PEM，即 `channel.key` 的全部内容）**，存进
仓库 Secret，让 CI 能在没有你本机参与的情况下签 `channel.json`。

**先生成一次密钥对**（在你自己的机器上，且只做一次）：

```bash
tools/channel/sunsetlinux-channel keygen --out-dir ~/.sunsetlinux-keys
#   ~/.sunsetlinux-keys/channel.key  私钥（0600）—— 离线备份好，丢了就签不出"同一个频道"
#   ~/.sunsetlinux-keys/channel.pub  公钥（base64）—— 发给用户
#   指纹 ed25519:xx:xx:…              —— 让用户核对，防中间人
```

**写进 Secret**（二选一）：

| 方式 | 命令/路径 |
|---|---|
| 网页 | 仓库 → Settings → Secrets and variables → **Actions** → New repository secret；名字 `CHANNEL_SIGNING_KEY`，值 = `channel.key` 的**全部内容**（含 `-----BEGIN/END-----` 两行） |
| 命令行 | `gh secret set CHANNEL_SIGNING_KEY < ~/.sunsetlinux-keys/channel.key` |

> ⚠️ Secret 是**只写**的：网页上再也读不出来，只能覆盖。所以私钥的**唯一权威副本是你的离线备份**，
> 不要把它当成"存在 GitHub 上了"。

**两种签名模型**（本仓库两条都实现了，按需选）：

| | A. 本机签名（默认可用） | B. CI 签名（`channel` 分支） |
|---|---|---|
| 命令 | `sunsetlinux-channel publish-channel --key ~/.sunsetlinux-keys/channel.key …` | 本机只跑 `gen-manifest`（**不需要私钥**）→ 把清单推到 `channel` 分支 → CI 用 Secret 签名 |
| 私钥在哪 | 你的机器 | GitHub Secret + 你的离线备份（**日常机器上可以没有**） |
| 需要 Secret | 不需要 | 需要 `CHANNEL_SIGNING_KEY` |
| 适合 | 一个人、一台机器，想最短路径 | 多台机器/多人协作，或不想让私钥常年待在日常机器上 |
| 风险 | 私钥在那台机器的磁盘上 | **任何能改本仓库工作流的人都能签频道** → 请给 `main`/`channel` 开分支保护 |

**为什么"清单必须在有层镜像的机器上生成"**：`sha256_raw`/`size_raw` 要真解压算（曾经手算差 28672 字节），
而层产物 244 MB 且需要 arm64 + 真 chroot。CI 拿不到层文件就生成不了清单 —— 所以 CI 只做
"签名 + 发布"，不假装能生成。B 模型正是照这个边界切的：**本机出清单（无私钥），CI 持私钥签名**。

**CI 签名做了什么**（`.github/workflows/channel.yml`，触发条件：推送到 `channel` 分支且 `channel/channel.json` 变化）：

1. 检查 `channel.json` 与 `channel.pub` 都在、且 `CHANNEL_SIGNING_KEY` 已配置（**缺失就失败，绝不跳过签名**）；
2. **逐层检查 URL 现在就能下载**（Range 请求取 1 字节）——防止"清单先发了、层还没传"；
   确需绕过时可设仓库变量 `CHANNEL_SKIP_URL_CHECK=1`；
3. 把 Secret 写成 0600 的临时文件 → `sign.mjs` 签原始字节 → 用 `verify.mjs` **另一条代码路径**独立验签；
4. 清掉私钥（`if: always()`）→ 提交 `channel.json.sig` 回 `channel` 分支 → 发到 gh-pages `/channel/`；
5. 在运行摘要里输出**清单 URL + 公钥 + 指纹**，直接复制去发给用户。

> ⚠️ **两个工作流共用并发组 `gh-pages`**（`release.yml` 与 `channel.yml`）：它们都会写 gh-pages，
> 各用各的组名就可能并发 force push 互相覆盖（`keep_files: true` 只能保住"运行开始时已存在的文件"）。

**为什么签名密钥必须是 Secret**：频道的安全模型是"npm/HTTP 只是传输，信任根是公钥验签"。
私钥一旦泄漏，任何人都能签出"你的"频道，**整个更新体系失效**（这一点已实测：篡改与冒签都会被拒，
但那是建立在私钥不外泄的前提上）。

> 本地开发用的 `channel.key` **已经**在 `.gitignore` 里；仓库里还曾遗留过一份测试私钥，已清除。
> 生成与保管方式见 `docs/updates.md`。

---

## 六、用户侧怎么用

1. 在 App 里添加频道（URL + 公钥 + 指纹）；
2. 想尝鲜 → 订阅 **`/beta/channel.json`**；想稳 → 订阅 **`/stable/channel.json`**；
3. 更新时只下载**变化的那一层**（`dsh` 层约 31 MB，`runtime` 约 46 MB，`base` 约 19 MB）。

**公钥必须由用户手输或从可信渠道获取，绝不要从频道包里读** —— 那等于让攻击者自带信任根。
`gen-manifest` 只把公钥写进包内 README **供人核对**，不写进信任链。

---

## 七、与"署名/身份"的关系

- git 提交身份：`SunsetRNE <z100o190zgxc@163.com>`（已配置全局与仓库级）；
- 频道签名密钥：**与 git 身份无关**，是独立的 Ed25519 密钥对，私钥只存 Secret 与你的离线备份；
- 公钥指纹（形如 `ed25519:xx:xx:…`）是给用户核对用的，应写进 README 与发布说明。

---

## 八、落地状态

| 项 | 状态 |
|---|---|
| `main` / `beta` 分支 | ✅ 已建立 |
| git 身份配置 | ✅ SunsetRNE |
| 回归门禁可用的命令 | ✅ 全部就绪（见 §三） |
| `.github/workflows/` | ⏳ 待添加（`ci.yml` 门禁 + `release.yml` 分支发布） |
| GitHub Secrets | ⏳ **需要你在仓库设置里配**（我无法代配，也不该把私钥写进代码） |
| 正式签名 keystore | ⏳ 需要你生成并备份（丢了就无法给同一 App 发新版） |
