# 交接：当前状态与续做清单

> 用途：**对话记录可能被删/会被换掉**，所以把"现在到哪了、还剩什么、怎么接着做"落进仓库。
> 和 `docs/STATUS.md`（项目总账）配合看：STATUS 讲**项目本身**做到什么程度，本文讲**这次协作的落点**。
> 最后更新：**2026-09-16**（本轮 12 个提交，全部已推送、CI 已发布）

---

## 〇、一分钟版

- 分支：`main` = `beta` = **`bfabbc8`**；`channel` = `0d1efff`；`gh-pages` 由 CI 独占。
- 发布：`/stable/` **run 40**、`/beta/` **run 41**，都是 `bfabbc8`，**模块 1.0.4**、**App 0.2.0**。
- **下载页（别点 GitHub Releases，那里永远是空的）**：
  <https://sunsetrne.github.io/SunsetLinux/> → `/stable/`（正式）· `/beta/`（预发布）。
- 你自己那台机器：模块还停在旧包、`/data/sunsetlinux/layers/` 仍然是空的
  → root 模式起不来、内置终端也就没环境可连（见 §二）。

**分支与 CI 拓扑**（细节见 `docs/release-ci.md` §二·五）：

| 分支 | 内容 | 谁维护 |
|---|---|---|
| `main` / `beta` | 源码主线 / 预发布（同内容） | 人（`git push origin main:beta` 快进） |
| `channel` | 只有频道数据（公钥、加密私钥备份、签名工作流） | 人 + 该分支 CI 补 `.sig` |
| `layers` | **层产物传送带**（orphan：`.erofs.zst/.gz` + `layers-release.yml` + 可选 `TAG`） | 人（每次 `--force` 重置） |
| `gh-pages` | 发布产物 `/stable/`、`/beta/`、`/channel/` | **CI 独占** |

CI 四条线路：① `ci.yml` 回归门禁（shell / node / android 三并行）② `release.yml` 发布（复用①的 APK 制品）
③ `layers.yml` 层与频道（手动、arm64 自建 runner）④ `layers-release.yml` 层托管（`layers` 分支 → Release 资产）。

---

## 一、本轮做完的（都有证据，不是"应该能行"）

| # | 提交 | 做了什么 | 证据 |
|---|---|---|---|
| 1 | `2a9512d` | **proot 宿主侧脚本 mksh 化**（`=~`/`for ((`/进程替换/`local x=()`/shebang），并修掉 `mksh -n` 抓不到的运行时缺陷：**bash 的 `/dev/tcp` 在 mksh 里不存在**（端口探测改三层回退）；`runtime/root/start.sh` 的 `port_busy` 一并修 | 欠债名单清零；`selftest-funcs.sh` 65/65（bash+mksh）；`mksh proot/selftest.sh` 18/18；契约 4 组合通过 |
| 2 | `ae8987b` | **层托管管线**（`layers` 分支 → Release 资产）；修 `channel.yml` 预检**缺 `-L`**（Release 地址 302，会把正常清单判成"下载不到"） | 实测同一 URL 无 `-L`=302 / 加 `-L`=206；产物守卫模拟 4 情况；YAML+28 run 块语法通过 |
| 3 | `dcef953` | **App 外壳重排**：DSH 上顶栏、底栏改 启动/插件/**终端**、更新进侧边栏；**新增内置终端**（`linuxctl attach` 常驻会话，明确无 PTY 的限制） | App 单测 62/0；`ShellLayoutContractTest` 7 条 |
| 4 | `ea0a7eb` | **模块 WebUI 去 emoji → 内联 SVG 图标**；**内置官方频道**（URL+公钥写死在代码、读时并入写时剔除、UI 只留启用开关） | WebUI 自测 64/64（+3 条图标守卫）；`OfficialChannelContractTest` 4 条 |
| 5 | `840c75a` | **修 APK 签名随机漂移**（原先无 `signingConfig` → 每次 CI 一把新 debug key）；**App 0.1.0/1 → 0.2.0/2** | 发布包签名 = `5d5fa724…69f7` = 仓库内固定密钥（改前是 `84be9523…`）；`assembleRelease` 也过 |
| 6 | `cd054a1` | **懒人体检/一键部署 `oneshot-setup.sh`**（任意目录、MT 系统/扩展包双模式、默认只读、`--run` 才动手） | 假设备布局 4 场景 + `--run` 桩流程；进 mksh 闸门 |
| 7 | `8a88ddd` | oneshot 补 `truncate`/`sha256sum` 体检 + **模块版本 vs 官方发布**比对 | 真机输出驱动；本地复现"设备 1.0.0 / 官方 1.0.2"提示 |
| 8 | `890d54a` | **下载页**：站点根 + `/stable/` + `/beta/` 由 CI 每次生成 `index.html` | `/stable/` 从 `application/json` 变 `text/html`；站点根 404 → 200；本地跑生成器 + HTML 闭合检查 |
| 9 | `cd537c2` | **修我自己的 bug**：`bad()` 定义被"删空行"编辑并进注释 → `mksh -n` 照过、真机只多一行 `bad: inaccessible…`，**且阻断计数永远 0**；新增 `tools/oneshot-selftest.mjs`（29 条，进 CI） | 冒烟在 mksh+bash 下断言"八段都在、无 not found、缺模块/缺层必须报阻断" |
| 10 | `2113217` | **proot 能力审计 + 随包 proot 5.1.0(2014) → 5.4.0**；修 `--link2symlink` 在老 proot 上直接 fatal；修 bundle 工具自检判据 | bundle 988,717 B / `3d72e0c3…`；两版 proot 实测两条分支；`--verify` 通过 |
| 11 | `bfabbc8` | 把"CI 不产出 proot bundle"**说出来**（不再静默跳过） | job summary 有 ⚠️/✅ 分支 |

**测试总账**：App **62/0** · WebUI **64/64** · proot 纯函数 **65/65**（bash+mksh）· proot entry 解析 **18/18**（mksh）·
root selftest **20/20**（bash+mksh）· 版本一致性 **16/16** · 契约 root/proot × bash/mksh 全通过 · oneshot 冒烟 **29/29**。

---

## 二、需要你操作的（按优先级）

1. **真机把层建出来**（唯一挡着 root 模式 + 终端/DSH 全链路验收的事）：
   - 先装**模块 1.0.4**（KernelSU 管理器）：`…/stable/sunsetlinux-module-1.0.4.zip`，装完**重启一次**；
   - `device-provision.sh` **内部要求 bash**（Android 自带只有 mksh）→ 用 Termux 的 bash：
     ```bash
     su -c '/data/data/com.termux/files/usr/bin/bash /data/adb/modules/sunsetlinux/bin/device-provision.sh --seeds /data/sunsetlinux/seeds'
     ```
     种子**已经下好了**（上一轮 `--run` 成功下载了 ubuntu-base 与 Node 包）；跑完 `linuxctl start`。
   - 失败就把 `--run` 最后 ~40 行、或 `linuxctl doctor` 的输出贴回来。
2. **arm64 上重打层 → 发布**（"官方频道有货"的前置）：
   ```bash
   sudo bash rootfs/build-layers.sh --out-dir dist --runtime-dir runtime/root --dsh-dist-tag next
   ```
   然后按 `docs/release-ci.md` **§5.3**：把 `dist/*.erofs.gz`（建议带 gz，设备侧优先它）推到 `layers` 分支 →
   CI 上传 Release 并打印 base-url → 本机 `publish-channel --base-url …` → 推 `channel/channel.json` → `/channel/` 上线。
3. **决定内置形态**：见 §三·2（建议照 DSHA 的 `split-runtime-v1` 分包）。

---

## 三、下一步技术清单（方向已定，尚未开工）

1. **proot 运行时进 `jniLibs` + App 自动解压**（建议先做）
   - 现状：App 里**没有 `assets/`**，doctor 那句"App 应随包携带 proot"一直是空的 → 非 root 模式开箱即用不了；
   - 做法：CI 从 Debian 的 proot arm64 deb 重建 bundle（命令已验证可复现）→ 放进 `jniLibs/arm64-v8a/`
     （**Android 10+ 不能 execve App 私有目录里的文件，但 `nativeLibraryDir` 里的可以**）→ App 首启解压/校验；
   - 顺带给"设备侧要 bash"开一条路（bash 也能这么做）。
2. **内置裁剪版 Ubuntu（分包形态）**——用户明确要，上游 DSHA 就是这么做的：
   - `offline-rootfs.bin`（Ubuntu = base+runtime）与 `dsh-runtime.bin`（dsh）**分包**；
   - **CI 生成资产、不入库**；**冷装两份都解、局部更新只读 dsh 包**（不必每次重下整套）；
   - 参考上游脚本：`scripts/ci-make-offline-bundle.sh`、`tools/prepare-standard-assets.py`、`scripts/offline-provision.sh`。
3. **PTY 终端**（解掉"vim/htop 不可用"）：自研 `forkpty` JNI（约百行，避开 `termux-jni` 的 GPL 传染风险）。
   上游留下的硬性细节：fork/exec 握手登记出生身份、16 KB 页对齐、关闭标签必须核验会话真的退出。
4. **两处待查/待修**：
   - **App 的 zstd 解压 bug**：设备报过 `Invalid magic prefix: 0: offset=542314549248`（505 GiB 偏移，明显不对），
     它决定内置包用 `.zst` 还是 `.gz`；
   - **`device-provision.sh` 的 bash 依赖**：1315 行里只有 2 处数组 + 2 处 `[[ ]]`、没有 `=~`/进程替换，
     改成 mksh 可跑是根治（模块在 stock Android 上自足，不需要 Termux）。
5. **性能路线（可选）**：自己编译 proot 打开 **seccomp 加速器**（`proot --version` 会打印
   `built-in accelerators: … seccomp_filter = yes/no`；现在的 Debian 5.4.0 是 `no`）。
   **不采用 proroot**（见 §四）。

---

## 四、上游 DSHA 的关键数据（本轮查证，可直接引用）

仓库：<https://github.com/DSH-APP/DSHA>（MIT；**本容器就跑在它的 App 里**，`com.dsh.client`）

| 项 | 数据 |
|---|---|
| APK | 标准版 **176.69 MiB**、兼容版 253.23 MiB（`standard` minSdk 30 / `low` minSdk 23 + Gecko 143）——**两个 flavor 都内置离线 rootfs** |
| 内置形态 | `assets/offline-rootfs.bin`（**不入库，CI 生成**，标记 `offline-rootfs.layout=split-runtime-v1`）+ `dsh-runtime.bin` 分包；冷装两份都解，**局部更新只读 dsh 包**；**不为普通 dsh 更新递增 Ubuntu 基础环境版本** |
| 环境身份 | 版本码 + Ubuntu 基础环境版本 + dsh 版本；基础版本相同则事务替换受管运行时，不同才保护数据重建 |
| proot 打包 | 作为 `jniLibs/arm64-v8a/*.so`（`libproroot*.so`、低版 `libproot_legacy.so`）→ 直接可执行 |
| 终端 | **有 PTY**：`tools/termux-jni/dsha-pty.c`（2.9 KB）+ `PtySession.java` + `PtyTerminalFragment.java` |
| proroot | LD_PRELOAD + 二进制补丁做进程内路径翻译，**零 ptrace 开销**；但**闭源**、单人维护、作者已转向、Termux 拒绝打包；l2s 结构与 proot 不兼容（它 21 处脚本依赖）；**上游自己默认关闭** |
| 踩坑 | `--link2symlink` 下要 `NARB_DISABLE_NATIVE_CACHE=1`（默认 link+unlink 缓存首次悬空）；DNS 默认 `auto`（`EAI_AGAIN`/`EAI_FAIL` 才重试一次 IPv4，**不重放 HTTP**）；随包 CA/npm 入口不依赖 apt |
| 其他 | 383 项单测（382 过/1 跳过）、Lint 无错；发布走 **GitHub Releases** + `.apk.sha256`；发布说明明确"沿用原发布签名" |

---

## 五、删掉对话后，恢复上下文的最短路径

```bash
# 1) 看这两份就够：项目总账 + 本次落点
less docs/STATUS.md docs/HANDOFF.md

# 2) 最近做了什么（本轮 12 个提交）
git log --oneline 35ac91d..HEAD

# 3) 本地全量门禁（秒级～分钟级）
node tools/shell-compat-check.mjs --verbose
node module/webroot/selftest.mjs
node tools/oneshot-selftest.mjs
node tools/cmp-consistency.mjs
bash runtime/proot/selftest-funcs.sh && mksh runtime/proot/selftest-funcs.sh
bash runtime/root/selftest.sh && mksh runtime/root/selftest.sh
cd app && ./gradlew :app:assembleDebug :app:testDebugUnitTest

# 4) 当前发布版本（无需 token）
curl -s https://sunsetrne.github.io/SunsetLinux/stable/index.json
```

---

## 六、本轮踩过、别再犯的坑

- **"能解析"≠"函数真的存在"**：一次删空行的编辑把 `bad() {}` 并进注释，`mksh -n` 全绿，
  真机只多一行 `bad: inaccessible or not found`，**而且阻断计数永远是 0**（该拦的不拦，用户是唯一发现者）。
  → 规矩：**任何体检/闸门脚本都要有"行为级冒烟"**（`tools/oneshot-selftest.mjs` 就是这么来的）。
- **只看 `mksh -n` 不够**：`/dev/tcp`、`device-provision.sh` 的 bash 依赖都属于"语法过、运行时废"。
- **`dist/` 是 gitignore 的** → CI 拿不到本地产物；发布步骤里依赖本地构建物的，必须显式说"本次没有"，
  **不许静默跳过**（`release.yml` 的 job summary 已这么做）。
- **编辑时别在空行边界动手**：删空行会把下一行粘上来（本轮那个 bug 就是这么来的）。
- **GitHub Release 资产地址会 302**：任何"能不能下载"的预检都要 `curl -L`（`channel.yml` 已修）。
- **Releases 页面永远是空的**（刻意不用 tag）；下载页在 gh-pages，见 §〇。
- **签名必须固定**：授权按【包名+签名】记（KernelSU），换 key 会让已授的 root 全失效。
- **`--link2symlink` 只有 proot ≥5.3 有**，喂给老 proot 会 fatal；启用后注意
  `NARB_DISABLE_NATIVE_CACHE=1` 那类缓存悬空问题（上游踩过）。
