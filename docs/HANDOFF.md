# 交接：当前状态与续做清单

> 用途：**对话记录可能被删**，所以把"现在到哪了、还剩什么、怎么接着做"落进仓库。
> 和 `docs/STATUS.md`（总账）配合看：STATUS 讲**项目本身**做到什么程度，本文讲**这次协作**的落点。
> 最后更新：2026-09-15

---

## 一、已经跑通的（有证据，不是"应该能行"）

**仓库**：<https://github.com/SunsetRNE/SunsetLinux>（public；本地用 SSH 推送）

| 分支 | 内容 | 谁维护 |
|---|---|---|
| `main` | 源码主线（= 正式通道） | 人 |
| `beta` | 与 main 同内容（= 预发布通道） | 人（`git push origin main:beta` 快进即可） |
| `channel` | **只有频道数据**：`channel.pub`、`channel-key-backup.enc`、`KEY-BACKUP.md`、签名工作流 | 人 + 该分支的 CI 自动补 `.sig` |
| `gh-pages` | 发布产物：`/stable/`、`/beta/`、`/channel/` | **CI 独占，不要手工改** |

**CI 三条线路**（详见 `docs/release-ci.md` §二·五）：

1. `ci.yml` 回归门禁 —— 三个**并行** job：`shell`（运行时断言 bash+mksh、mksh 闸门、.sh 语法）、
   `node`（WebUI 纯函数、版本一致性、status 契约、.mjs 语法）、`android`（构建+47 单测，上传制品 `apk-debug`）。
2. `release.yml` 发布 —— `beta`/`main` 推送触发；**复用 ① 的 APK 制品**（不再重编），加模块 zip 与
   `index.json`，发到 gh-pages 的 `/beta/` 或 `/stable/`。
3. `layers.yml` 层与频道 —— **手动触发**，跑在自建 arm64 runner 上（244 MiB、要真 chroot），
   支持 `--skip-*` 增量重编；有 `CHANNEL_SIGNING_KEY` 就签名+独立验签。

**已发布的产物**（gh-pages `stable/`）：APK、`sunsetlinux-module-1.0.0.zip`、`index.json`。
门禁那轮实测：脚本断言 20/20（bash 与 mksh 各一遍）、proot 18/18、WebUI 60/60、版本一致性 16/16、
契约通过、Android 构建 `BUILD SUCCESSFUL`、**单测 47/0**。

**频道密钥**：公钥 `YXoAcwa3clZCRd7+DlIHMS3cQ40HlyXVSKblvX5Ye1M=`，
指纹 `ed25519:06:d0:c4:4d:29:1c:ef:66`；`channel` 分支有公钥与**加密**私钥备份
（AES-256-CBC + PBKDF2 600k；口令在密码管理器中，**不在任何文件里**）。

---

## 二、需要你操作的（按优先级）

1. **开 Pages**：Settings → Pages → Build and deployment → Source **Deploy from a branch** →
   Branch **`gh-pages`** / **`(root)`** → Save，勾 `Enforce HTTPS`。
   生效后：`https://sunsetrne.github.io/SunsetLinux/stable/`（`/beta/` 同理；`/channel/` 等清单发布后出现）。
   > ⚠️ 别选 `main`：那是源码分支，Pages 会去构建 Jekyll，`/stable/…` 全是 404。
2. **配 Secret `CHANNEL_SIGNING_KEY`**：值是私钥全文（`channel.key` 的内容，含 BEGIN/END 两行）。
   来源二选一：① 容器内 `~/.sunsetlinux-keys/channel.key`；② 用口令解密 `channel` 分支的
   `channel-key-backup.enc`（命令见 `channel/KEY-BACKUP.md`）。配好后 `channel` 分支一推清单，
   CI 就会签名 + 用仓库里的公钥独立验签 + 发到 gh-pages `/channel/`。
3. **重建层镜像**：现有 `dist/*.erofs` 是**改名完成之前**构建的，里面还留着死文件
   `/opt/dshroid/*.sh`（不影响启动 —— `start.sh` 会把模块里的新版同步到 `/opt/sunsetlinux`，
   但镜像该重打）。两条路：本地/arm64 机器上 `bash rootfs/build-layers.sh` 后
   `sunsetlinux-channel publish-channel --base-url …`；或注册自建 arm64 runner 用 `layers.yml`。
4. **托管选型**：层文件一次全量 **244 MiB**（base 18.6+26.5 / runtime 46.5+73.5 / dsh 31.2+47.8）。
   gh-pages 单文件上限 100 MB、仓库软上限 1 GB → **约 4 次就满**；建议层放对象存储/CDN，
   清单与 APK/模块放 gh-pages（清单里的 `url` 是绝对地址，可跨域名）。

---

## 三、已知欠债（别当成可用）

- **proot 模式的宿主侧脚本仍是 bash**（数组、`=~`、进程替换）→ 真机不可用；
  `tools/shell-compat-check.mjs` 已显式登记为"欠债"，修好一个就要从名单删一个。
- **App「彻底卸载」入口**只有命令行：`linuxctl purge [--yes|--arm|--disarm]`（UI 未做）。
- **真机端到端未验证**：`su -c '/data/sunsetlinux/bin/linuxctl doctor'`。
  我这条设备通道被策略禁止装应用与做挂载（`[POLICY_BLOCKED]`），必须你在 root 终端跑。
- **高温被系统强杀**：应用层解决不了（见 `docs/STATUS.md` §4.1）；root 模式只是把环境
  与 App 进程解耦，降低被回收的频率，不可能阻止系统级 thermal/LMK。

---

## 四、删掉对话后，恢复上下文的最短路径

```bash
# 1) 看这两份就够：项目总账 + 本次协作落点
less docs/STATUS.md docs/HANDOFF.md

# 2) 最近做了什么
git log --oneline -12

# 3) 本地三件套确认状态（都是秒级～分钟级）
node tools/shell-compat-check.mjs --verbose
bash runtime/root/selftest.sh && mksh runtime/root/selftest.sh
cd app && ./gradlew :app:assembleDebug :app:testDebugUnitTest

# 4) 盯 CI：浏览器看 /actions，或（无需 token）
git ls-remote https://github.com/SunsetRNE/SunsetLinux.git | grep gh-pages   # 出现=发布成功
```

---

## 五、这次踩过、别再犯的坑（细节见 `docs/release-ci.md` §六）

- **私钥永不明文进仓库**（`.gitignore` 已挡 `*.key`/`*.pem`/`.sunsetlinux-keys/`）；
  公钥**必须**进仓库（`channel` 分支的 `channel.pub`）—— 两者别搞反。
- GitHub 的**同一并发组只允许一个 pending 运行**：main/beta 别背靠背推，否则后到者顶掉先到者。
- Android 上**只有 mksh**：设备侧脚本必须能被 mksh 解析（CI 有闸门守着）。
- `secrets` 上下文在步骤 `if` 里**不可用**，只能放进 `env` 再由脚本判断。
- 未认证的 GitHub API 限额 **60 次/小时**：让 AI 盯 CI 时要么给 token，要么直接贴日志。
- `android-actions/setup-android` 的默认包列表含**已被移除的 `tools` 包**，必须显式给 `packages:`。
- 单元测试夹具必须在**仓库内可跟踪**的路径（`testdata/fixtures/`），否则 CI 上会"SKIP 却显示通过"。
