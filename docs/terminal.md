# 终端：原生 PTY（App「终端」面板的底座）

> 代码：`app/app/src/main/cpp/pty.c`（原生）、`core/PtyNative.kt`（JNI 桥）、
> `core/PtySession.kt`（字节流会话）、`core/TerminalEmulator.kt`（屏幕渲染）、
> `ui/TerminalPane.kt`（界面）。编译：`tools/ndk-build-pty.sh`。

## 1. 为什么必须有 PTY

老实现走 `ProcessBuilder` 的**普通管道**：`linuxctl attach` 把 shell exec 进环境，
App 只能"写一行、读一批行"。管道没有 termios，于是：

| 能力 | 无 PTY（老实现） | 有 PTY（现在） |
|---|---|---|
| Ctrl-C / Ctrl-D / Ctrl-Z | **无效**（`0x03` 只是个普通字节） | 有效（行规程 → SIGINT/SIGEOF/SIGTSTP） |
| `vim` / `htop` / `top` | 不能跑（无 tty、拿不到尺寸） | 能跑 |
| 窗口大小 | 不存在 | `TIOCSWINSZ`，跟着控件尺寸走 |
| Tab 补全 / 方向键 | 字节被当正文 | `\t` / `ESC[A` 被正确解释 |
| 进度条 / `>>> ` 提示 | 花屏（`\r`、`ESC[K` 当正文印） | 最小 VT 模拟器正确渲染 |

顺带纠错：老代码注释说"Android 上没有随系统可用的伪终端分配接口"——**是错的**。
设备上 `/dev/ptmx` 存在且 `crw-rw-rw-`，bionic 提供 `grantpt/unlockpt/ptsname_r`，
Termux 用的就是这条路（见 [termux.c](https://github.com/termux/termux-app/blob/master/terminal-emulator/src/main/jni/termux.c)）。
真正的原因是"当时没写原生库"。

## 2. 分配与启动（`pty.c` 的顺序，与 Termux 一致）

```
open("/dev/ptmx", O_RDWR|O_CLOEXEC)
  → grantpt / unlockpt / ptsname_r            // 拿到从设备名
  → tcgetattr + tcsetattr                     // IUTF8；关 IXON/IOFF（否则 Ctrl-S 锁屏）
  → ioctl(TIOCSWINSZ, rows, cols)             // 初始尺寸
  → fork()
      子：setsid() → open(从设备) → dup2 0/1/2 → chdir → execve(target)
      父：master fd → Java（ParcelFileDescriptor.adoptFd）→ 字节流读写
```

**刻意保留 `ISIG` 与 `ICANON`**（Termux 也没关）：Ctrl-C/Ctrl-D、行编辑、回显全靠它们。
另外注意 `fork` 之后子进程里只调 async-signal-safe 的函数（`_exit`），异常都在父进程抛。

## 3. 启动什么（`LinuxCtl.ptySpec()`）

两种模式统一用 `/system/bin/sh -c "exec …"`：

| 模式 | 实际命令 |
|---|---|
| root | `/system/bin/sh -c "exec su -c '<PATH=…; linuxctl attach>'"` |
| proot | `/system/bin/sh -c "exec <linuxctl> attach"`（丢了执行位则 `exec /system/bin/sh <linuxctl> attach`） |

为什么绕一层 sh：`execve` **不查 PATH**，而 `su` 的绝对路径各 ROM 不同
（`/system/bin/su` / `/system/xbin/su` / `/data/adb/ksu/bin/su`…）；同时这样 PTY 在整个
链路最外层，`su` 之后的 chroot/proot → bash 都继承同一个控制终端，Ctrl-C 才能落到
真正的前台进程组。

环境变量（`execve` 是替换式的，所以是**全量**）：`PATH/HOME/LINUX_HOME/SUNSETLINUX_APP_FILES/
SUNSETLINUX_NATIVE_LIB_DIR/SUNSETLINUX_PROROOT_VERSION/TERM=xterm-256color/LANG=C.UTF-8/COLUMNS/LINES`。

## 4. 尺寸与渲染

- 尺寸：界面用 `BoxWithConstraints` 按控件真实尺寸与等宽字号算行列 → `PtySession.resize`
  → `TIOCSWINSZ`。旋转、分屏、键盘弹出都会重算。
- 渲染：`TerminalEmulator`（纯 Kotlin，零 Android 依赖，**12 条单测**）支持
  `\r`（覆盖前擦到行尾，**CRLF 例外**，否则 `a\r\nb` 会丢掉 `a`）、`\b`、`\t`、`ESC[K/J/H/A-D/G`、
  OSC 标题、SGR 剥离、滚屏、增量 UTF-8（一个汉字被切成两次 read 也正确）。
  刻意不做：滚动区、备用屏幕、鼠标上报、彩色（SGR 目前剥离）。
- 回显：**PTY 模式下 App 不再本地回显** —— 行规程会回显，本地再补一次就重复了。
  降级模式（见下）仍然需要本地回显。

## 5. 构建（为什么不用 AGP 的 externalNativeBuild）

官方 NDK **只发布 x86_64 宿主的工具链**（`toolchains/llvm/prebuilt/linux-x86_64/`）。
本项目有 aarch64 构建环境，AGP 会去调那个 x86_64 的 clang → `error=2`。
产物本身与宿主无关（bionic 的头/库都在 `sysroot/`，是纯数据），所以
`tools/ndk-build-pty.sh` 自己驱动 clang：

- 宿主 x86_64（CI）→ 用 NDK 自带 clang；
- 宿主 aarch64（开发机）→ 系统 clang + `--sysroot=<NDK sysroot>`
  + `-resource-dir=<NDK clang 资源目录>` + `-rtlib=compiler-rt`
  （少任何一个都会去找系统资源目录里的 `libclang_rt.builtins-aarch64-android.a` 而失败）。

产物落 `app/app/src/main/jniLibs/arm64-v8a/libsunsetlinux_pty.so`（约 11.6 KB），
由 Gradle 任务 `buildPtySo` 挂在 `preBuild` 上；**没有 NDK 时构建直接失败**并给出安装命令
（不静默产出一个"终端退回行缓冲"的 APK）。

```bash
tools/ndk-build-pty.sh            # 编译
tools/ndk-build-pty.sh --check    # 只校验（ELF=aarch64 + JNI 符号齐全）
```

## 6. 降级（重要）

原生库加载失败时（自编译 APK 漏了 `.so`、ABI 不匹配）：

- `PtyNative.available == false`、`PtyNative.loadError` 给出人话原因；
- `TerminalPane` 退回老的 `TerminalSession`（行缓冲），并在界面顶部**明说**：
  "原生 PTY 不可用（原因）—— 当前是行缓冲降级：全屏程序与 Ctrl-C 不可用"；
- 快捷键条在降级模式下显示为禁用。

契约写在 `PtySessionTest`：JVM 里永远加载不到 .so，所以单测跑到的就是这条降级分支本身。

## 7. 真机验证步骤

```bash
# 0) 确认 .so 真的随 APK 落了盘（nativeLibraryDir）
adb shell run-as io.github.sunsetrne.sunsetlinux ls lib   # 或看 /data/app/*/lib/arm64/
#    期望：libsunsetlinux_pty.so（11.6 KB，与 libproroot*.so 同目录）

# 1) App「终端」→ 连接；顶部应显示「已连入环境（…，原生 PTY）」
# 2) 逐项验：
#    · Ctrl-C：跑 `sleep 300` 后点 Ctrl-C → 立即回到提示符
#    · Tab 补全：输入 `ls /us` + Tab → 补成 /usr
#    · 方向键：↑ 调出上一条命令
#    · 全屏程序：`apt-get install htop && htop` 或直接 `vim /tmp/x` → 界面正常、可退出
#    · 尺寸：旋转屏幕后再开 htop/`stty size` → 行列跟着变
#    · 中文：`echo 中文测试` 正常
# 3) 环境内确认终端类型：`echo $TERM` → xterm-256color；`stty size` → 与屏幕一致
```

失败的常见原因：`.so` 不在 APK 里（自编译）→ 界面会直接显示降级原因；
`su` 路径特殊（非 KernelSU/Magisk 的 root 方案）→ 看 `notify` 里 linuxctl 的报错。
