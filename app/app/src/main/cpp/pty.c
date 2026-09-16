/*
 * SunsetLinux —— 原生 PTY（App「终端」的底座）
 *
 * ============================================================================
 * 为什么要有它
 * ============================================================================
 * 以前的终端走 ProcessBuilder 的**普通管道**：App 侧只能"写一行、读一批行"。
 * 管道没有 termios，于是：
 *   · Ctrl-C 无效（0x03 只是普通字节，没有行规程去把它变成 SIGINT）—— 本地实测确认过；
 *   · vim/htop/top 这类全屏程序不能跑（没有 tty、拿不到窗口大小）；
 *   · 没有作业控制（fg/bg/Ctrl-Z）。
 *
 * 有了 PTY，上面三件事都由内核的行规程负责：App 只负责
 *   「把字节写进 master / 从 master 读字节 / 在窗口变化时 TIOCSWINSZ」。
 *
 * ============================================================================
 * 与 Termux 的关系（同一套做法，值得对照阅读）
 * ============================================================================
 * Android 上**没有**现成的 PTY 分配 API，但 `/dev/ptmx` 是有的，bionic 也提供了
 * grantpt/unlockpt/ptsname_r。Termux 的做法（terminal-emulator/src/main/jni/termux.c）
 * 就是标准答案，本文件按同样的顺序实现：
 *
 *   open("/dev/ptmx") → grantpt → unlockpt → ptsname_r
 *     → tcgetattr/tcsetattr（IUTF8；关掉 IXON/IXOFF，否则 Ctrl-S 会把显示锁死）
 *     → ioctl(TIOCSWINSZ) 设初始行列
 *     → fork
 *        子：setsid() → open(从设备) → dup2 到 0/1/2 → execve(目标程序)
 *        父：把 master fd 交给 Java 侧（ParcelFileDescriptor.adoptFd），
 *            Java 直接用 FileInput/OutputStream 读写
 *
 * **刻意保留 ISIG 与 ICANON**（Termux 也没关）：Ctrl-C/Ctrl-D/Ctrl-Z、行编辑、
 * ^S/^Q 之外的所有行规程功能都靠它们。窗口大小变化由 Java 侧调 resize() 发
 * TIOCSWINSZ —— master 在我们手里，所以能精确跟着控件尺寸走（这是比
 * "层内 script 包一层"更强的地方：script 的 stdin 是管道，永远学不到尺寸）。
 *
 * ============================================================================
 * 约定
 * ============================================================================
 *   · 所有返回 -1 的路径都**已经**向 Java 侧抛了 RuntimeException（信息给用户看）；
 *   · 子进程不再返回；父进程里 master fd 是 CLOEXEC 的，避免泄漏给子进程；
 *   · waitFor 只是 waitpid 包装，返回退出码；被信号杀死时返回 128+signo（与 shell 一致）。
 */

#include <jni.h>

#include <errno.h>
#include <fcntl.h>
#include <pty.h>
#include <signal.h>
#include <stdio.h>
#include <stdlib.h>
#include <string.h>
#include <sys/ioctl.h>
#include <sys/types.h>
#include <sys/wait.h>
#include <termios.h>
#include <unistd.h>

#define TAG "sunsetlinux-pty"

/* 把 errno 变成带上下文的异常（不要在子进程里调用：fork 后只能调 async-signal-safe 的东西） */
static void throw_errno(JNIEnv *env, const char *what) {
    char buf[256];
    snprintf(buf, sizeof(buf), "%s 失败：%s (errno=%d)", what, strerror(errno), errno);
    jclass cls = (*env)->FindClass(env, "java/lang/RuntimeException");
    if (cls != NULL) (*env)->ThrowNew(env, cls, buf);
}

static void throw_msg(JNIEnv *env, const char *msg) {
    jclass cls = (*env)->FindClass(env, "java/lang/RuntimeException");
    if (cls != NULL) (*env)->ThrowNew(env, cls, msg);
}

/* Java String[] → char*[]（调用方负责 free_argv） */
static char **to_argv(JNIEnv *env, jobjectArray arr, const char *prog) {
    jsize n = arr == NULL ? 0 : (*env)->GetArrayLength(env, arr);
    char **out = (char **) calloc((size_t) n + 2, sizeof(char *));
    if (out == NULL) return NULL;
    int k = 0;
    if (prog != NULL) out[k++] = strdup(prog);      /* argv[0] = 程序名 */
    for (jsize i = 0; i < n; i++) {
        jstring s = (jstring) (*env)->GetObjectArrayElement(env, arr, i);
        const char *c = s == NULL ? NULL : (*env)->GetStringUTFChars(env, s, NULL);
        out[k++] = strdup(c == NULL ? "" : c);
        if (s != NULL) (*env)->ReleaseStringUTFChars(env, s, c);
    }
    out[k] = NULL;
    return out;
}

static void free_argv(char **argv) {
    if (argv == NULL) return;
    for (char **p = argv; *p != NULL; p++) free(*p);
    free(argv);
}

/* 子进程里：把除 0/1/2 以外的 fd 全关掉（避免把 App 的 socket/文件泄漏进终端） */
static void close_extra_fds(void) {
    for (int fd = 3; fd < 1024; fd++) close(fd);
}

/*
 * 分配 PTY 并 fork+exec。
 *
 * @return master fd（成功）；失败抛异常并返回 -1。
 *         pidOut[0] 被写入子进程 pid。
 */
JNIEXPORT jint JNICALL
Java_io_github_sunsetrne_sunsetlinux_core_PtyNative_open(
        JNIEnv *env, jclass clazz,
        jstring jcmd, jstring jcwd, jobjectArray jargs, jobjectArray jenv,
        jintArray jpidOut, jint rows, jint cols) {

    const char *cmd = (*env)->GetStringUTFChars(env, jcmd, NULL);
    if (cmd == NULL) return -1;
    const char *cwd = jcwd == NULL ? NULL : (*env)->GetStringUTFChars(env, jcwd, NULL);

    /* 程序名（argv[0]）：取路径最后一段，便于 ps 里看清是什么 */
    const char *slash = strrchr(cmd, '/');
    const char *prog = slash == NULL ? cmd : slash + 1;

    char **argv = to_argv(env, jargs, prog);
    char **envp = to_argv(env, jenv, NULL);          /* 环境变量：没有 argv[0] */
    if (argv == NULL || envp == NULL) {
        free_argv(argv);
        free_argv(envp);
        (*env)->ReleaseStringUTFChars(env, jcmd, cmd);
        if (cwd != NULL) (*env)->ReleaseStringUTFChars(env, jcwd, cwd);
        throw_msg(env, "内存不足：构造 argv/envp 失败");
        return -1;
    }

    int ptm = open("/dev/ptmx", O_RDWR | O_CLOEXEC);
    if (ptm < 0) {
        free_argv(argv);
        free_argv(envp);
        (*env)->ReleaseStringUTFChars(env, jcmd, cmd);
        if (cwd != NULL) (*env)->ReleaseStringUTFChars(env, jcwd, cwd);
        throw_errno(env, "打开 /dev/ptmx");
        return -1;
    }

    char devname[128];
    if (grantpt(ptm) != 0 || unlockpt(ptm) != 0 || ptsname_r(ptm, devname, sizeof(devname)) != 0) {
        close(ptm);
        free_argv(argv);
        free_argv(envp);
        (*env)->ReleaseStringUTFChars(env, jcmd, cmd);
        if (cwd != NULL) (*env)->ReleaseStringUTFChars(env, jcwd, cwd);
        throw_errno(env, "grantpt/unlockpt/ptsname_r");
        return -1;
    }

    /* 行规程：UTF-8 输入 + 关掉流控（Ctrl-S 否则会把显示锁死）。
       注意**不要**动 ISIG/ICANON/ECHO —— Ctrl-C/Ctrl-D、行编辑、回显都靠它们。 */
    struct termios tios;
    if (tcgetattr(ptm, &tios) == 0) {
        tios.c_iflag |= IUTF8;
        tios.c_iflag &= ~(IXON | IXOFF);
        tcsetattr(ptm, TCSANOW, &tios);
    }

    struct winsize ws;
    memset(&ws, 0, sizeof(ws));
    ws.ws_row = (unsigned short) (rows > 0 ? rows : 24);
    ws.ws_col = (unsigned short) (cols > 0 ? cols : 80);
    ioctl(ptm, TIOCSWINSZ, &ws);

    pid_t pid = fork();
    if (pid < 0) {
        close(ptm);
        free_argv(argv);
        free_argv(envp);
        (*env)->ReleaseStringUTFChars(env, jcmd, cmd);
        if (cwd != NULL) (*env)->ReleaseStringUTFChars(env, jcwd, cwd);
        throw_errno(env, "fork");
        return -1;
    }

    if (pid == 0) {
        /* ---- 子进程（fork 之后只能调 async-signal-safe 的函数）---- */
        sigset_t all;
        sigfillset(&all);
        sigprocmask(SIG_UNBLOCK, &all, NULL);        /* 解掉 Java 侧可能屏蔽的信号 */
        close(ptm);

        if (setsid() < 0) _exit(126);                /* 成为新会话首进程 */

        int pts = open(devname, O_RDWR);
        if (pts < 0) _exit(126);
        /* 新会话且没有控制终端 → 这次 open 会让该 slave 成为控制终端（不要加 O_NOCTTY） */
        dup2(pts, STDIN_FILENO);
        dup2(pts, STDOUT_FILENO);
        dup2(pts, STDERR_FILENO);
        if (pts > STDERR_FILENO) close(pts);
        close_extra_fds();

        if (cwd != NULL && cwd[0] != '\0') {
            if (chdir(cwd) != 0) _exit(126);
        }
        execve(cmd, argv, envp);
        _exit(127);                                  /* exec 失败：127 = command not found */
    }

    /* ---- 父进程 ---- */
    int pidOut[1];
    pidOut[0] = (int) pid;
    (*env)->SetIntArrayRegion(env, jpidOut, 0, 1, pidOut);

    free_argv(argv);
    free_argv(envp);
    (*env)->ReleaseStringUTFChars(env, jcmd, cmd);
    if (cwd != NULL) (*env)->ReleaseStringUTFChars(env, jcwd, cwd);
    return ptm;
}

/* 窗口大小变化：控件尺寸变了就调它（vim/htop 立刻跟着重排） */
JNIEXPORT void JNICALL
Java_io_github_sunsetrne_sunsetlinux_core_PtyNative_resize(
        JNIEnv *env, jclass clazz, jint fd, jint rows, jint cols) {
    if (fd < 0) return;
    struct winsize ws;
    memset(&ws, 0, sizeof(ws));
    ws.ws_row = (unsigned short) (rows > 0 ? rows : 24);
    ws.ws_col = (unsigned short) (cols > 0 ? cols : 80);
    if (ioctl(fd, TIOCSWINSZ, &ws) != 0) throw_errno(env, "TIOCSWINSZ");
}

/* 等子进程结束，返回退出码（被信号杀死 → 128+signo，与 shell 一致） */
JNIEXPORT jint JNICALL
Java_io_github_sunsetrne_sunsetlinux_core_PtyNative_waitFor(
        JNIEnv *env, jclass clazz, jint pid) {
    int status = 0;
    pid_t r;
    do {
        r = waitpid((pid_t) pid, &status, 0);
    } while (r < 0 && errno == EINTR);

    if (r < 0) return -1;
    if (WIFEXITED(status)) return WEXITSTATUS(status);
    if (WIFSIGNALED(status)) return 128 + WTERMSIG(status);
    return -1;
}

/* 关掉 master fd：关闭会让内核向前台进程组发 SIGHUP，所以它就是"正常结束会话"的方式 */
JNIEXPORT void JNICALL
Java_io_github_sunsetrne_sunsetlinux_core_PtyNative_closeFd(
        JNIEnv *env, jclass clazz, jint fd) {
    if (fd >= 0) close(fd);
}

/* 给子进程发信号（stop() 的兜底：SIGHUP 之后还不退就 SIGKILL） */
JNIEXPORT void JNICALL
Java_io_github_sunsetrne_sunsetlinux_core_PtyNative_signal(
        JNIEnv *env, jclass clazz, jint pid, jint sig) {
    if (pid > 0) kill((pid_t) pid, sig);
}
