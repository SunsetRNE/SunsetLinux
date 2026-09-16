# App 版本变更说明

> `app/version.properties` 只放 `versionName` / `versionCode` 两个键值；
> **每个版本的来龙去脉写在这里**（新增一版时先加条目，再改 version.properties）。
>
> 版本号规则：Android 只按 `versionCode` 判"是不是新版"，所以**每次对外发布都要 +1**。
> 四个内置组合（minimal / ubuntu / ubuntu-proot / ubuntu-proot-dsh）是同一个 App，
> **共用同一组 versionName/versionCode** —— 换组合就是覆盖安装另一个 APK，数据不丢。

| versionName | versionCode | 内容 |
|---|---|---|
| 0.1.0 | 1 | 首个可用外壳：模式选择、部署向导、状态页 |
| 0.2.0 | 2 | 内置终端、DSH 入口上顶栏、更新进侧边栏、内置官方频道 |
| 0.2.1 | 3 | 部署向导缺层时改调 `device-provision.sh`（`linuxctl provision` 不建层） |
| 0.2.2 | 4 | 模块版本提示更正为 ≥1.0.6（1.0.5 有 `%q` 与 profiles 两个坑） |
| 0.2.3 | 5 | **修"一打开就闪退"**：zstd 能力探测走 aircompressor 的 direct-buffer/Unsafe 快路径，在 Android 上 SIGSEGV；改成流式解码 |
| 0.2.4 | 6 | root 检测升级为可解释状态（无 su / 被拒 / 超时 / 已授权）+ 模块检测（装没装、版本、停用、装了没重启）；「更新」页修好"本地版本读不出来 ⇒ 看不到可更新层" |

## 构建信息（不写进 version.properties）

`app/build.gradle.kts` 构建时算一个**标准版本号**（参照 Branchbase 的做法，见
docs/specs/BUILD-NOTES.md）：

```
标准版本号 = <versionName>-<yyyyMMdd-HHmm>-<7 位 git hash>     例：0.2.4-20260916-0040-8d4920a
```

它注入 `BuildConfig`（`ENGINEERING_VERSION` / `STANDARD_VERSION` / `BUILD_TIME` / `GIT_HASH`
/ `EMBED_VARIANT` / `EMBED_PARTS`），并写进 gh-pages 的 `index.json` 与下载页；
**不进 APK 文件名**（下载页每版只指向最新一份，不存在"堆多代 APK"的问题）。

## 产物命名（定了就不要随手改）

| 产物 | 命名 | 例 |
|---|---|---|
| App（四个组合各一份） | `SunsetLinux-<versionName>-<组合>.apk` | `SunsetLinux-0.2.4-ubuntu-proot-dsh.apk` |
| 离线包（可单独下载/导入） | `SunsetLinux-<versionName>-<组合>.bin` | `SunsetLinux-0.2.4-minimal.bin` |
| KernelSU 模块 | `sunsetlinux-module-<模块版本>.zip` | `sunsetlinux-module-1.0.9.zip` |

组合名（`<组合>`，与 `tools/offline-bundle/variants.json` 里的 id 一一对应）：

| id | 中文名 | 内嵌 | 体积增量 |
|---|---|---|---|
| `minimal` | 最小版 | proot 运行时（非 root 必需件） | +0.9 MB |
| `ubuntu` | Ubuntu 版 | base + runtime | +68 MB |
| `ubuntu-proot` | 免 root 版 | base + runtime + proot | +69 MB |
| `ubuntu-proot-dsh` | 完整离线版 | base + runtime + proot + dsh | +102 MB |
