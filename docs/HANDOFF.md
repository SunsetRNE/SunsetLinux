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
| `layers` | **层产物的传送带**（orphan：只有 `.erofs.zst`/`.gz` + `layers-release.yml` + 可选 `TAG`）→ CI 上传到 Release | 人（每次 `--force` 重置） |
| `gh-pages` | 发布产物：`/stable/`、`/beta/`、`/channel/` | **CI 独占，不要手工改** |

**CI 四条线路**（详见 `docs/release-ci.md` §二·五）：

1. `ci.yml` 回归门禁 —— 三个**并行** job：`shell`（运行时断言 bash+mksh、mksh 闸门、.sh 语法）、
   `node`（WebUI 纯函数、版本一致性、status 契约、.mjs 语法）、`android`（构建+单测，上传制品 `apk-debug`）。
2. `release.yml` 发布 —— `beta`/`main` 推送触发；**复用 ① 的 APK 制品**（不再重编），加模块 zip 与
   `index.json`，发到 gh-pages 的 `/beta/` 或 `/stable/`。
3. `layers.yml` 层与频道 —— **手动触发**，跑在自建 arm64 runner 上（244 MiB、要真 chroot），
   支持 `--skip-*` 增量重编；有 `CHANNEL_SIGNING_KEY` 就签名+独立验签。
4. `layers-release.yml` 层托管 —— `layers` 分支推送触发；**不构建**，只把 staging 分支里的
   分发产物传到 Release 资产（"Release 当纯存储"），并打印 `publish-channel` 用的 base-url。

**已发布的产物**（gh-pages `stable/`）：APK、`sunsetlinux-module-1.0.1.zip`、`index.json`。
门禁那轮实测：脚本断言 20/20（bash 与 mksh 各一遍）、proot 18/18、**proot 纯函数 65/65**、
WebUI 64/64、版本一致性 16/16、契约通过、Android 构建 `BUILD SUCCESSFUL`、**单测 58/0**。

**App 外壳（2026-09-16 真机反馈后调整）**：DSH 从底栏移到**顶栏图标**、底栏变成
**启动 / 插件 / 终端**三格、**更新移到侧边栏**；并新增**内置终端**（走 `linuxctl attach`，
没有 PTY：`vim`/`htop` 不可用。详见 `docs/STATUS.md` §3.6 与 `docs/smoke-test.md` §7.0）。
⚠️ 要用上这些得**装新 APK**（`/stable/` 或 `/beta/` 的 `sunsetlinux-launcher-debug.apk`）。

**频道密钥**：公钥 `YXoAcwa3clZCRd7+DlIHMS3cQ40HlyXVSKblvX5Ye1M=`，
指纹 `ed25519:06:d0:c4:4d:29:1c:ef:66`；`channel` 分支有公钥与**加密**私钥备份
（AES-256-CBC + PBKDF2 600k；口令在密码管理器中，**不在任何文件里**）。

---

## 二、需要你操作的（按优先级）

1. ~~开 Pages~~ ✅ **已完成**（`/stable/`、`/beta/` 都在线；`/channel/` 等清单发布后出现）。
2. ~~配 Secret `CHANNEL_SIGNING_KEY`~~ ✅ **已完成**（你已配好；`channel` 分支一推清单，
   CI 就签名 + 用仓库里的公钥独立验签 + 发到 gh-pages `/channel/`）。
3. **重建层镜像**：现有 `dist/*.erofs` 是**改名完成之前**构建的，里面还留着死文件
   `/opt/dshroid/*.sh`（不影响启动 —— `start.sh` 启动时会把模块里的新版同步到 `/opt/sunsetlinux`，
   但镜像该重打）。必须在**有 arm64 + 真 chroot 的机器**上跑 `rootfs/build-layers.sh`
   （本工作容器实测 `CapEff=0`、`mount` 是假的，跑不了）。
4. ~~托管选型~~ ✅ **已定：Release 当纯存储 + `layers` 分支传送带**。层（244 MiB，只出 zst 时
   101 MiB）放 Release 资产，清单/APK/模块仍放 gh-pages。构建机不需要 token：
   产物推到 `layers` 分支 → `layers-release.yml` 收集上传 → 打印 `publish-channel` 用的
   base-url。完整步骤见 `docs/release-ci.md` **§5.3**（含三条必须知道的事：下载端都跟 302、
   裸 `.erofs` 超 git 单文件 100 MB 硬限不能进分支、staging 分支每次要 `--force` 重置）。

---

## 三、已知欠债（别当成可用）

- **proot 宿主侧脚本已 mksh 化（2026-09-15 修完）**：`runtime/proot/{linuxctl,start}.sh`
  现在能被 mksh 解析，并跑同一套纯函数回归 `runtime/proot/selftest-funcs.sh`
  （bash / mksh 各 65/65 通过）；`tools/shell-compat-check.mjs` 的欠债名单**已清零**。
  顺带修掉一个 `mksh -n` 抓不到的**运行时**缺陷：`/dev/tcp` 是 bash 专有特性，
  mksh 下恒失败（真机表现＝"服务起来了却一直判未就绪"），已换成三层回退的端口探测
  （`/proc/net/tcp` → `nc -z` → `ss -ltn`）；root 侧 `runtime/root/start.sh` 的
  `port_busy` 同一个毛病也一并修了。
  **仍未验证**：真机跑一次 proot 模式的 `provision`/`start`（需要设备）。
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
- **"能不能下载"的预检必须带 `curl -L`**：Release 资产地址会 302 跳到
  `release-assets.githubusercontent.com`（实测：同一 URL 无 `-L` 得 **302**、加 `-L` 得 **206**）。
  `channel.yml` 原来没带，会把完全正常的 Release 清单判成"层下载不到"而拒发；已修。
- **层产物进 git 有两道静默坑**：①仓库根 `.gitignore` 挡了 `*.erofs`/`*.erofs.zst`/`*.erofs.gz`，
  `git add` 会**不报错地漏掉**；②`git checkout --orphan` 之后工作区里仍是整棵源码树，
  `git add -A` 会把源码一起提交。→ 在**干净目录里另起一个仓库**再 `push --force`（`release-ci.md` §5.3）。
- **mksh 的坑不止语法**：`/dev/tcp`、`local x=()` 之类"语法闸门抓不到"的运行时差异，
  只有真跑或做双解释器回归才看得出来（`runtime/proot/selftest-funcs.sh` 就是为此加的）。
