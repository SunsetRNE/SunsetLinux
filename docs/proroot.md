# 免 root 运行时：proroot 首选、proot 降级

> 账本与许可约束在 [`tools/proroot/README.md`](../tools/proroot/README.md)。
> 本文写**运行时怎么选、怎么验、坏了怎么退**。

## 1. 为什么换

免 root（非 root）模式原先只有 proot：它靠 **ptrace** 拦截每条系统调用，
每次调用一次上下文切换。`npm install`、Node 启动、`git` 这类"系统调用密集"的负载
在这上面会明显变慢。proroot 走 **LD_PRELOAD**（改写 libc 调用路径），没有 ptrace 往返，
CLI 与 proot **同一套**（`-r/-w/-b/-0/--link2symlink`），所以是同一份
`start.sh` 参数构造 + 换个启动器。

## 2. 决策顺序（唯一事实源：`runtime/proot/start.sh` 的 `resolve_rootless()`）

```
SUNSETLINUX_ROOTLESS=auto|proroot|proot     ← App「设置 → 免 root 运行时」透传
  → etc/config.json 的 "rootless_runtime"   ← 给 WebUI / 手改留的口子
  → 默认 auto
```

| 取值 | 行为 |
|---|---|
| `auto`（默认） | proroot 可用就用；不可用**记下原因**降级 proot（日志 + doctor 都能看到） |
| `proroot` | 不可用就**明确失败**（带着原因与排查提示），绝不静默降级 |
| `proot` | 跳过 proroot 探测，只用随包 proot |

**proroot 可用的判据**：`SUNSETLINUX_NATIVE_LIB_DIR`（App 传的 `applicationInfo.nativeLibraryDir`）
下 5 个 `.so` 齐全（`libproroot.so` / `-runtime` / `-linker` / `-bridge` / `-stub-loader`）。
启动器按 `/proc/self/exe` 的目录自动发现另外四个，所以必须在同一目录里。
`PROROOT_TMP_DIR` 会被设到 `$LINUX_HOME/cache/proroot`（App 私有目录，可写）。

## 3. 三个地方能看到"实际用了谁"

| 位置 | 看什么 |
|---|---|
| `linuxctl status` | `"rootless":{"kind":"proroot","version":"1.2.8"}`（对外的 `mode` **仍是 `proot`**，§3.1 契约不动；没启动过时是 `null`，不编造） |
| `linuxctl doctor` | 检查项 `rootless_runtime`：proroot 可用性 + 本次实际使用 + 缺哪个文件 |
| App | 「设置 → 免 root 运行时」显示选项，下面一行"本次运行实际使用"；「关于」页也有一行（含 attribution） |

`start.sh` 启动时把结果写进 `$LINUX_HOME/run/rootless`（`<kind> <version>`）——
`status`/`doctor` 是**另一个进程**，只能靠这个文件知道这次用的是谁。

## 4. 真机验证（拿到新 APK 后照这个走）

```bash
# 0) 装机：覆盖安装 SunsetLinux-<版本>-<组合>.apk（同包名同签名，数据不丢）

# 1) 确认 proroot 真的随 APK 落盘了（nativeLibraryDir 里 5 个）
su -c 'ls -l $(pm path io.github.sunsetrne.sunsetlinux | head -1 | sed "s/package://;s#/base.apk##")/lib/arm64/libproroot*.so'
#    看不到就去 App「关于」页看 BuildConfig.PROROOT_VERSION，并确认装的是官方 APK

# 2) 起环境（root 模式 / 免 root 模式都行；proroot 只在免 root 模式生效）
su -c '/system/bin/sh /data/sunsetlinux/bin/linuxctl.sh start'     # root 模式
#   非 root 模式：在 App 里点「启动」，或
#   /system/bin/sh $APP_FILES/sunsetlinux/bin/linuxctl.sh start

# 3) 看实际用了谁
su -c '/system/bin/sh /data/sunsetlinux/bin/linuxctl.sh status' | grep -o '"rootless":{[^}]*}'
su -c '/system/bin/sh /data/sunsetlinux/bin/linuxctl.sh doctor' | grep rootless_runtime
su -c 'cat /data/sunsetlinux/run/rootless'          # 例：proroot 1.2.8

# 4) 环境内自检 + 性能直觉
su -c '/system/bin/sh /data/sunsetlinux/bin/linuxctl.sh exec -- node -v'
su -c '/system/bin/sh /data/sunsetlinux/bin/linuxctl.sh exec -- sh -c "time node -e 1"'
```

判据：

- `run/rootless` 是 `proroot 1.2.8`，`status.rootless.kind == "proroot"`；
- `doctor` 的 `rootless_runtime` 是 proroot 可用（info 级）；
- 环境内 `node -v` / `pnpm -v` 正常，`/root` 可写，`apt-get install` 之类的
  "需要伪造 root"的操作仍然和以前一样（要显式 `--fake-root` / `-0`）。

## 5. 降级与回退

| 现象 | 处理 |
|---|---|
| `run/rootless` 是 `proot`，doctor 说 proroot 不可用 | 多半是装了自己编的 APK（没带 `.so`）或 App 太旧（没透传 `SUNSETLINUX_NATIVE_LIB_DIR`）。装官方 APK / 升级 App 即可；也能显式 `SUNSETLINUX_ROOTLESS=proot` 固定用 proot |
| 显式 `SUNSETLINUX_ROOTLESS=proroot` 时启动失败 | 这是**有意**的：明确要它就不静默降级。错误信息里会写缺哪个文件、以及怎么退回 proot |
| proroot 起不来的新内核/新 ROM | 临时 `SUNSETLINUX_ROOTLESS=proot`（App 设置里也有开关），并把 `run/linux.log` 报给我们；**proot 一定还在**（随包内嵌 + 频道可单独下载） |
| 想彻底不要专有组件 | 用 `SUNSETLINUX_ROOTLESS=proot`，或自建 APK 时跳过 `syncProrootLibs`（会失去"首选运行时"，环境本身照常工作） |

## 6. 许可（一句话）

proroot 是专有许可：**只能随完整 APK 分发**，不得再分发修改版，APK 内必须带许可原文，
应用内必须有 attribution。因此本仓库/Release/CI artifact 里**永远不该**出现裸
`libproroot*.so`（`tools/proroot/compliance-test.mjs` 每次构建都会检查）。
