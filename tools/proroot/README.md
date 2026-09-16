# proroot —— 免 root（非 root）模式的首选运行时

> 本目录只放**账本与许可**：版本号、5 个 `.so` 的 sha256、上游许可原文。
> **二进制不进仓库**，见下面"许可红线"。

## 这是什么

[proroot](https://github.com/coderredlab/proroot) 是 Android 上的无 root Linux 运行时，
proot 的 **drop-in 替代**，走 **LD_PRELOAD** 而不是 ptrace：

| | proot（降级实现） | proroot（首选） |
|---|---|---|
| 原理 | ptrace 拦截每条系统调用 | LD_PRELOAD 改写 libc 调用路径 |
| 开销 | 每次系统调用一次上下文切换（`npm install`/进程创建密集时很明显） | 无 ptrace 往返 |
| 形态 | 自带依赖的二进制 bundle（0.94 MB，解到 `$LINUX_HOME/proot/`） | 5 个 `.so`（共 ~650 KB），随 APK 的 `jniLibs/arm64-v8a/` |
| CLI | `-r/-w/-b/-0/--link2symlink` | **同一套**（drop-in） |
| 许可 | GPLv2（可自由再分发） | **专有**（见下） |

我们四个变体（minimal / ubuntu / ubuntu-proot / ubuntu-proot-dsh）**都带 proroot + proot**，
运行时的选择在 `runtime/proot/start.sh` 的 `resolve_rootless()`：

```
SUNSETLINUX_ROOTLESS=auto|proroot|proot   （App「设置 → 免 root 运行时」透传）
  → etc/config.json 的 rootless_runtime（给 WebUI / 手改留口子）
  → 默认 auto
auto     ：proroot 可用就用；不可用**记下原因**降级 proot
proroot  ：不可用就**明确失败**（用户明确要它，静默换掉等于骗人）
proot    ：跳过 proroot 探测
```

App 必须把 `applicationInfo.nativeLibraryDir` 透传成 `SUNSETLINUX_NATIVE_LIB_DIR`——
proroot 的启动器按 `/proc/self/exe` 的目录找另外 4 个库，脚本自己猜不到这个路径。

实际生效的运行时可以在三处看到：`linuxctl status` 的 `rootless:{kind,version}`、
`linuxctl doctor` 的 `rootless_runtime` 检查项、以及 App「设置」页与「关于」页。

## ★ 许可红线（**别踩**）

上游许可是**专有**的（全文见 [`LICENSE.proroot`](LICENSE.proroot)），要点：

1. 可以在任何应用里使用；
2. **不得再分发修改版**（我们的构建因此禁止 strip：`packaging.jniLibs.keepDebugSymbols`）；
3. 未修改的二进制**只能作为完整应用包（APK/AAB）的一部分**再分发；
4. APK 内必须带许可原文（`assets/licenses/proroot-LICENSE.txt`）；
5. 应用内必须有 attribution（我们放「关于」页）。

所以在本项目里：

- ✅ 允许：构建期从上游 Release 下载 → 打进 `jniLibs` → 以 APK 形式发布；
- ❌ 禁止：把 `.so` 提交进仓库（**公开**仓库里的文件 = 独立分发）；
- ❌ 禁止：把 `.so` 做成 GitHub Release 资产 / CI workflow artifact（同理）；
- ❌ 禁止：修改（strip、patch、重新链接）后分发。

`tools/proroot/compliance-test.mjs` 把上面几条做成闸门（仓库扫描 + 工作流扫描 +
APK 内部 sha256 比对 + 许可与 attribution 检查），CI 每次构建都会跑。

## 用法

```bash
node tools/proroot/fetch.mjs                 # 取到 dist/proroot/（缺什么取什么，幂等）
node tools/proroot/fetch.mjs --check         # 只校验（不联网）
node tools/proroot/fetch.mjs --print-version # 打印版本（脚本记账用）
node tools/proroot/fetch.mjs --update-hashes # 升版本时重新记账（记得复核许可与 obligations）
node tools/proroot/compliance-test.mjs       # 合规闸门
```

Gradle 侧：每个变体都有 `syncProrootLibs<Variant>`（取用 + 校验 + 铺成 jniLibs）与
`syncProrootLicense<Variant>`（许可进 assets）。**构建需要网络**；取不到会直接失败，
不会静默产出一个"免 root 用不了 proroot"的 APK。

## 升版本流程

1. 改 `VENDOR.json` 的 `version` / `released_at` / `release_url`；
2. `node tools/proroot/fetch.mjs --update-hashes`（会打印每个产物的 sha256）；
3. **人工复核**上游许可是否变化：变了就更新 `LICENSE.proroot` 并重算 `license.sha256`；
4. `node tools/proroot/compliance-test.mjs`；
5. 真机验证（见 `docs/proroot.md`）。
