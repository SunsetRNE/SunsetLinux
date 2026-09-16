# 命名体系（**已定名：方案 B SunsetLinux**）

> 用途：记录命名决策与执行结果。
> 核心结论：**只有 `applicationId` 与「环境根路径」改起来最贵，必须在发布前定；其余都能后改。**
>
> ## ✅ 最终定名（2026-09-15 采纳方案 B）
>
> | 项 | 最终值 |
> |---|---|
> | 产品 / App 显示名 | **SunsetLinux** |
> | 仓库 | **`SunsetLinux`** — <https://github.com/SunsetRNE/SunsetLinux>（本地目录 `/root/Q/SunsetLinux`） |
> | Android applicationId | **拆版后有两个**（0.3.0 起，两个 App 可共存）：`io.github.sunsetrne.sunsetlinux.root`（Root 版）/ `…​.proot`（免 root 版）。`io.github.sunsetrne.sunsetlinux` 是 0.2.x 的旧包名，**只作为历史**保留 —— 新包名刻意带后缀，老用户不会"装一个覆盖另一个" |
> | Android namespace / 源码包路径 | **`io.github.sunsetrne.sunsetlinux`**（namespace 与源码路径仍然只有这一个；包名差异只体现在 `applicationId`） |
> | 源码包路径 | `io/github/sunsetrne/sunsetlinux` |
> | KernelSU 模块 id / name | **`sunsetlinux`** / **SunsetLinux** |
> | 环境变量前缀 | **`SUNSETLINUX_`**（原 `DSHROID_`） |
> | 环境根（root 模式） | **`/data/sunsetlinux`**（原 `/data/linux`） |
> | 环境根（非 root / proot） | **`$APP_FILES/sunsetlinux`**（与 root 模式同名，见下方第 7 条映射） |
> | 环境内入口脚本目录 | **`/opt/sunsetlinux`** |
> | Release 资产命名 | 模块：`sunsetlinux-module-<版本>.zip`；App（0.3.0 起）：`SunsetLinux-<版本>-<组合>-debug.apk`，组合 = `<edition>-<tier>`（如 `root-full`、`proot-base`，清单见 `tools/offline-bundle/variants.json`）；离线包：`SunsetLinux-<版本>-<组合>.bin` |
>
> ⚠️ **与 §4 的偏差说明**：§4 原写"环境根刻意不含产品名"，但用户最终拍板用 `/data/sunsetlinux`。
> 因此**环境根现在带产品名**：若将来再改名，就**必须写迁移脚本**把用户数据从
> `/data/sunsetlinux` 搬到新路径（这是当初选择"不含产品名"想避免的成本，现已接受）。
>
> ⚠️ **边界（改名时严格保留，属于 DSH 负载的接口）**：
> `@deepseek-ai/dsh*`、`dsh web`、`dsh plugin`、`dsh_dist_tag`、`DSH_HOME`、以及
> **`docs/architecture.md` §3.1 冻结 schema 里的 `dsh.pid` / `dsh.port` / `dsh.url` /
> `dsh.base_url` / `dsh.version` / `dsh.healthy`** —— 一个字都不能动。
> 改名后已按行数逐项核对：这些名字的出现次数与改名**完全一致**（77/82/6/103/34）。

---

## 一、先看约束：哪些改名代价高

| 项 | 含义 | 改名代价 | 现在必须先定？ |
|---|---|---|---|
| **Android `applicationId`** | 应用的系统身份 | ★★★ **改了就是另一个 App**：用户要卸载重装、root 授权/电池白名单/冻结豁免全部重来、旧数据不可达 | ✅ **是** |
| **环境根路径**（`/data/linux`） | 全部用户数据（会话、配置、可写层）所在 | ★★★ 要写迁移脚本，弄错会丢数据 | ✅ **是** |
| KernelSU 模块 id | `/data/adb/modules/<id>` | ★★ 旧模块残留需手动清；用户要重刷 | ✅ 是（便宜于上两项，但一起定最好） |
| 仓库名 | GitHub 仓库 | ★★ clone URL 变、旧链接靠跳转 | 建议一起定 |
| App 显示名 | 桌面上的名字 | ★ 改字符串 | 可后改 |
| npm 频道包名 | 第三方订阅用 | ★ 新增即可，旧的可继续用 | 可后改 |
| 环境变量前缀（`DSHROID_*`） | 脚本内部 | ★ 全局替换 | 可后改 |

**关键**：`applicationId` 一旦发布就无法回头。所以**先定它**，其余围绕它统一。

---

## 二、`applicationId` 的规范做法

反向 DNS 要求你"拥有"对应的域名。**没有自己的域名时，业界惯例是用 GitHub 用户名**：

```
io.github.<你的GitHub用户名>.<项目名>
```

这比凭空写 `io.dshroid`（隐含 `dshroid.io` 这个不存在的域名）规范，也比 `com.xxx` 更站得住脚。
（社区里 `io.xxx` / `com.xxx` 随手写的很多，但它们经不起推敲 —— 一旦要上应用商店或做商标，就是问题。）

---

## 三、三套候选（各自是一整套，不要混搭）

### 方案 A：沿用「DSHroid」

| 项 | 值 |
|---|---|
| 仓库 | `dshroid` |
| App 显示名 | DSHroid |
| **applicationId** | **`io.github.sunsetrne.dshroid`** |
| 模块 id | `dshroid` |
| 环境根 | `/data/linux` |
| npm 频道 | `@sunsetrne/dshroid-channel-*` |

- ✅ 改动最小（现有一切几乎不动）；名字直白，一看就知道是"DSH 的安卓宿主"
- ⚠️ 名字里含**他人产品名**（DSH）；定位被绑死在"为 DSH 而生"上

### 方案 B：身份化「SunsetLinux」（本项目架构其实更贴这个）

| 项 | 值 |
|---|---|
| 仓库 | `SunsetLinux` |
| App 显示名 | SunsetLinux（中文可叫「日落 Linux」） |
| **applicationId** | **`io.github.sunsetrne.sunsetlinux`** |
| 模块 id | `sunsetlinux` |
| 环境根 | `/data/sunsetlinux` |
| npm 频道 | `@sunsetrne/sunsetlinux-channel-*` |

- ✅ **完全属于你**，可长期持有、不与他人产品名绑定
- ✅ 与**实际架构一致**：我们的运行时是一个**通用的 Android Linux 宿主**（真 chroot + EROFS 分层 + 签名频道），
  **DSH 只是跑在里面的负载**。叫 `SunsetLinux` 比 `DSHroid` 更准确地描述了"这是什么"
- ✅ 以后要跑别的东西（其他 CLI/服务/Agent）不需要再改名
- ⚠️ 需要改一批名字 —— 但**现在改成本最低**（还没发布）

### 方案 C：中性通用「Hostdroid」

| 项 | 值 |
|---|---|
| 仓库 | `hostdroid` |
| App 显示名 | Hostdroid |
| **applicationId** | **`io.github.sunsetrne.hostdroid`** |
| 模块 id | `hostdroid` |
| 环境根 | `/data/linux` |
| npm 频道 | `@sunsetrne/hostdroid-channel-*` |

- ✅ 中性、可演进、不与任何产品绑定
- ⚠️ 辨识度一般，听起来像通用工具而非"你的作品"

---

## 四、命名体系（无论选哪套，规则固定）

| 域 | 规则 | 例（以方案 B 为准） |
|---|---|---|
| Android applicationId | `io.github.sunsetrne.<product>`，全小写、无下划线 | `io.github.sunsetrne.sunsetlinux` |
| Android namespace / 源码包 | 与 applicationId 一致（不必带 `github`，可 `io.sunsetrne.<product>`） | `io.sunsetrne.sunsetlinux` |
| KernelSU 模块 id | 小写字母/数字/`-`/`_`，≤ 32 字符，与产品名一致 | `sunsetlinux` |
| 环境变量前缀 | 大写产品名 + `_` | `SUNSETLINUX_LINUX_HOME` |
| npm 频道包 | `<scope>/<product>-channel-<作者id>` | `@sunsetrne/sunsetlinux-channel-dev` |
| 频道 id | 小写、可含 `-` | `official` / `some-dev` |
| Release 资产 | `<product>-<组件>-<版本>.<扩展名>` | `sunsetlinux-module-0.1.0.zip` |
| 分支 | `main`（正式）· `beta`（预发布）· `feat/*` | — |
| 版本 | 语义化；层版本另有约定（见 `layer-spec.sh` §5） | `0.1.0` / `24.04.3-l1` |

**路径（最终形态；注意"产品名解耦"的初衷已被用户决定覆盖）**：
```
/data/sunsetlinux/                # 环境根（root 模式）—— 含产品名，见上方偏差说明
/data/adb/modules/sunsetlinux/    # 模块（由 KernelSU 决定）
$APP_FILES/sunsetlinux/           # 非 root（proot）模式的环境根，与 root 模式同名
/opt/sunsetlinux/                 # 环境内的入口脚本目录（entry.sh / supervise.sh）
```

> ★ 原设计让环境根**不含产品名**，为的是"改名不必迁移用户数据"。
> 用户的最终决定是 `/data/sunsetlinux`，所以这条成本被接受了：
> **将来若再改环境根，必须写迁移脚本（用户数据在 `/data/sunsetlinux`），不能只改常量。**

---

## 五、改名清单（**执行前**写的清单；下表路径/包名均指旧名）

| # | 位置 | 说明 |
|---|---|---|
| 1 | `app/app/build.gradle.kts` | `namespace` / `applicationId` |
| 2 | `app/app/src/main/java/io/dshroid/**` | 源码包路径与 `package` 声明（连同 import） |
| 3 | `app/app/src/main/AndroidManifest.xml` | `android:name` 全限定名、FileProvider authority |
| 4 | `app/app/src/main/res/values/strings.xml` | `app_name` |
| 5 | `app/**` 其余 | `io.dshroid` → 新包名（全局替换）；`${packageName}.files` 会自动跟随 |
| 6 | `module/module.prop` | `id` / `name` |
| 7 | `module/*.sh`、`runtime/**` | 模块 id、环境变量前缀、默认路径 |
| 8 | `rootfs/layer-spec.sh` | 若含产品名常量 |
| 9 | `tools/**`、`docs/**` | 文案与示例 |
| 10 | `docs/*.md`、`README.md` | 项目名与简介 |
| 11 | 测试 | `TestPaths` 不用改（它按标志文件定位）；`PaletteContrastTest` 里的**源码扫描守卫**要同步（它扫的是 `app/app/src/main/java/io/dshroid` 路径） |

### 执行结果（2026-09-15）

已按上面的清单一次做完，顺序为
`io.dshroid` → `io/dshroid` → `DSHROID_` → `DSHroid` → `Dshroid` → `dshroid` → `/data/linux`
（外加 `APP_FILES/linux`、`APP_FILES}/linux` 两条，覆盖 `${APP_FILES}/linux` 与
`$SUNSETLINUX_APP_FILES/linux` 两种写法）。

- 内容替换：**121 个文件**，规则命中见 PR/提交说明；
- 路径搬迁：两个 Kotlin 包目录 → `io/github/sunsetrne/sunsetlinux`，
  `tools/channel/sunsetlinux-channel`，`dist/*` 产物改名；
- 旧名残留：**0**（本文件里作为"被否决的方案 A"保留的历史记录除外）；
- DSH 负载名：逐项与改名前行数一致，**未误改**。

**改完必须验证**：
```bash
./gradlew :app:assembleDebug :app:testDebugUnitTest     # 两条一起跑
bash runtime/root/selftest.sh && bash runtime/proot/selftest.sh
node module/webroot/selftest.mjs && node tools/cmp-consistency.mjs
# 再全仓 grep 旧名，确认 0 残留
```

---

## 六、我的建议

**推荐方案 B（`SunsetLinux` / `io.github.sunsetrne.sunsetlinux`）** —— **已被用户采纳**，理由：

1. **准确**：我们的运行时**不是**"DSH 专用"，而是一个通用的 Android Linux 宿主。
   模块不挂载系统路径、不依赖 metamodule；更新系统是通用的签名频道；层与负载无关。**DSH 只是跑在里面。**
2. **可持有**：不与他人产品名绑定，你长期维护不用顾虑。
3. **现在改最便宜**：还没发布、`applicationId` 还没被任何设备记录过。

若你更看重**辨识度**（一眼知道是干什么的），选 **A** 也完全可行 —— 只是要接受名字依赖 DSH 这个前提。
**C** 适合"以后要做成通用工具"的路线。
