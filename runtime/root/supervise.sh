#!/bin/bash
# 注：同 entry.sh —— 原为 `#!/usr/bin/env bash`，chroot 后 env 找不到 bash（继承的是安卓 PATH）；
# start.sh 已在 chroot 前显式给 PATH，这里改成绝对路径双保险。
# =============================================================================
# sunsetlinux · runtime/root/supervise.sh
#
# 环境内 supervisor（无 systemd，findings §2）。跑：
#   /usr/local/bin/dsh web --no-open --host 127.0.0.1 --port <port>
#
# 为什么**不能** `exec node ...`（这是与 architecture.md §3.2 初版的关键差异，
# 依据 §3.3 的实测鉴权模型）：
#   `dsh web` 的登录令牌 launchToken = base64url(randomBytes(...))，
#   **只随机在进程内，不落任何文件**，唯一出口是它启动时打印到 stdout 的那一行：
#       dsh web: http://127.0.0.1:3080/?token=<launchToken>[ (LAN: ...)]
#   所以必须"后台启动 + 截获 stdout + 解析 URL + 落盘 + 前台等待 + 信号透传"。
#
# 本脚本做的事：
#   1. 前置检查：DSH_HOME/profile 工作区存在、dsh 可执行（缺失就明确报错，
#      绝不静默让 DSH 自己去联网装插件 —— 那会破坏"离线可用"的承诺）
#   2. 后台起 dsh，stdout+stderr 追加到 run/linux.log
#   3. 后台解析器轮询日志，抓 `dsh web: ` 行，写 run/dsh.url（0600）与 run/dsh.port
#   4. 前台 wait，把 SIGTERM/SIGINT/SIGHUP 透传给 dsh（stop 依赖它干净退出）
#   5. 退出时清理 run/dsh.pid 与 run/dsh.url（避免 App 读到过期令牌）
#
# 不做"崩溃自动重启"：DSH 反复崩会变成静默重启循环，反而难排查。
# 崩溃由 linuxctl status 的 state=error + last_error 暴露给用户。
# =============================================================================
set -uo pipefail

LINUX_HOME="${LINUX_HOME:-/data/sunsetlinux}"
RUN_DIR="$LINUX_HOME/run"
PORT=3080

while [ $# -gt 0 ]; do
    case "$1" in
        --port) PORT="${2:-3080}"; shift 2 ;;
        [0-9]*) PORT="$1"; shift ;;
        *) shift ;;
    esac
done
case "${PORT:-}" in ''|*[!0-9]*) PORT=3080 ;; esac
if [ "$PORT" -lt 1 ] || [ "$PORT" -gt 65535 ]; then PORT=3080; fi

LOG="$RUN_DIR/linux.log"
URL_FILE="$RUN_DIR/dsh.url"
PID_FILE="$RUN_DIR/dsh.pid"
PORT_FILE="$RUN_DIR/dsh.port"
ERR_FILE="$RUN_DIR/last-error"

mkdir -p "$RUN_DIR" 2>/dev/null || true
: >> "$LOG" 2>/dev/null || true

log() {
    local line
    line="[$(date '+%Y-%m-%d %H:%M:%S')] [supervise.sh] $*"
    printf '%s\n' "$line" >&2
    printf '%s\n' "$line" >> "$LOG" 2>/dev/null || true
}

# ---------------------------------------------------------------------------
# 0) 清理上一轮的凭证文件 —— 契约要求"停止时清理 dsh.url/dsh.pid"，
#    但进程被 kill -9 时没机会清理，所以启动时也清一次，双保险。
# ---------------------------------------------------------------------------
rm -f "$URL_FILE" "$PID_FILE" "$PORT_FILE" 2>/dev/null || true
rm -f "$ERR_FILE" 2>/dev/null || true

# ---------------------------------------------------------------------------
# 1) 前置检查
# ---------------------------------------------------------------------------
DSH_BIN=""
for cand in /usr/local/bin/dsh /opt/node/bin/dsh; do
    [ -x "$cand" ] && { DSH_BIN="$cand"; break; }
done
if [ -z "$DSH_BIN" ]; then
    msg="/usr/local/bin/dsh 不存在或不可执行；DSH 层（L2）没装上或入口软链丢了。修复：在宿主执行 linuxctl update dsh <dsh.squashfs>，或重新 provision。"
    log "ERROR: $msg"
    printf '%s\n' "$msg" > "$ERR_FILE" 2>/dev/null || true
    exit 78
fi

NODE_BIN=""
for cand in /opt/node/bin/node /usr/local/bin/node /usr/bin/node; do
    [ -x "$cand" ] && { NODE_BIN="$cand"; break; }
done
if [ -z "$NODE_BIN" ]; then
    msg="找不到 node（期望 /opt/node/bin/node，runtime 层 L1）。修复：重装 runtime 层。"
    log "ERROR: $msg"
    printf '%s\n' "$msg" > "$ERR_FILE" 2>/dev/null || true
    exit 78
fi
export PATH="/opt/node/bin:/usr/local/bin:$PATH"

# profile 工作区检查（docs/dsh-profile.md §4.1）
# `dsh web` 不是"跑个 CLI"，而是加载 $DSH_HOME/profiles/web 这个 workspace：
#   package.json 的 dsh.profile.bundles 决定插件树，cordis.yml 是空入口。
# 缺了它 DSH 会自己尝试装插件（联网）→ 界面变桌面版。必须显式拦下。
DSH_HOME="${DSH_HOME:-/root/.dsh}"
export DSH_HOME
PROFILE_DIR="$DSH_HOME/profiles/web"
PROFILE_MANIFEST="$PROFILE_DIR/package.json"
if [ ! -f "$PROFILE_MANIFEST" ]; then
    msg="profile 工作区缺失：$PROFILE_MANIFEST 不存在。dsh web 缺了它界面会是桌面版（无移动端适配）。修复：装包含 /root/.dsh/profiles/** 的 dsh 层（docs/dsh-profile.md §3.1），执行 linuxctl update dsh <file>。"
    log "ERROR: $msg"
    printf '%s\n' "$msg" > "$ERR_FILE" 2>/dev/null || true
    exit 78
fi
# 插件可解析性：只检查 bundle 列出的包能不能在 profile 或 DSH 自身 node_modules 里找到
if ! missing="$("$NODE_BIN" -e '
const { createRequire } = require("node:module");
const path = require("node:path"), fs = require("node:fs");
const dir = process.argv[1];
const req = createRequire(path.join(dir, "package.json"));
let manifest = {};
try { manifest = JSON.parse(fs.readFileSync(path.join(dir, "package.json"), "utf8")); } catch {}
const bundles = manifest?.dsh?.profile?.bundles ?? [];
const out = [];
for (const b of bundles) {
  try { req.resolve(b + "/package.json"); }
  catch { try { req.resolve(b); } catch { out.push(b); } }
}
process.stdout.write(out.join(","));
' "$PROFILE_DIR" 2>/dev/null)"; then
    log "WARN: profile 可解析性探针执行失败（不致命，继续启动）"
elif [ -n "$missing" ]; then
    msg="profile bundle 无法解析：$missing。请重装 dsh 层（缺插件会在启动时联网安装或直接失败）。"
    log "ERROR: $msg"
    printf '%s\n' "$msg" > "$ERR_FILE" 2>/dev/null || true
    exit 78
fi
log "profile 检查通过：$PROFILE_DIR（DSH_HOME=$DSH_HOME）"

# ---------------------------------------------------------------------------
# 2) 后台启动 dsh
#    注意：**不把任何密钥放命令行**。密钥走 $DSH_HOME/env 或 .credentials.yaml
#    （entry.sh 已加载 env，权限 0600）。
# ---------------------------------------------------------------------------
# 先记下当前日志行数，解析器从这一行之后开始跟 —— 否则 dsh 打印得太快，
# 解析器还没起来，那一行就被跳过了（会表现为 run/dsh.url 一直不出现）。
LOG_LINES="$(wc -l < "$LOG" 2>/dev/null | tr -dc '0-9' || echo 0)"
case "${LOG_LINES:-}" in ''|*[!0-9]*) LOG_LINES=0 ;; esac
log "启动：$DSH_BIN web --no-open --host 127.0.0.1 --port $PORT（日志起始行 $LOG_LINES）"
# stdout/stderr 直接追加到日志文件：**不经管道**，避免缓冲导致 URL 行迟迟不落盘；
# 同时保留原始输出便于排查（§3.3 要求日志里能看到那一行）。
"$DSH_BIN" web --no-open --host 127.0.0.1 --port "$PORT" >> "$LOG" 2>&1 &
DSH_PID=$!
printf '%s\n' "$DSH_PID" > "$PID_FILE" 2>/dev/null || true
log "dsh 已后台启动，pid=$DSH_PID"

# ---------------------------------------------------------------------------
# 3) URL 解析器（后台）—— 契约 §3.3 的解析规则：
#    匹配以 `dsh web: ` 开头的行；取 `http://127.0.0.1:` 起、到第一个空白为止的子串；
#    必须含 `?token=` 才落盘。绝不贪婪匹配到行尾（否则会吞掉 " (LAN: ...)"）。
#    「dsh web: opening the default browser...」这类行不含 http 前缀，天然被排除。
# ---------------------------------------------------------------------------
# 用**轮询 + sed** 解析，而不是 `tail -F | while read`：
#   实测 tail 管道的行缓冲会让 URL 行延迟到 20s 才被读到（DSH 启动只要 ~1s），
#   而且尾部管道会遗留孤儿进程。轮询 200ms 一次，秒级内必然抓到，且随时可停。
parse_url_stream() {
    local line url p start count i
    start=$(( ${LOG_LINES:-0} + 1 ))
    while :; do
        # 只看 dsh 启动之后写进日志的那部分
        count="$(wc -l < "$LOG" 2>/dev/null | tr -dc '0-9' || echo 0)"
        case "${count:-}" in ''|*[!0-9]*) count=0 ;; esac
        if [ "$count" -ge "$start" ]; then
            # 契约 §3.3：匹配以 `dsh web: ` 开头的行，取 http://127.0.0.1: 起、
            # 到第一个空白为止的子串；必须含 ?token=。第一个匹配即最新令牌（本次启动）。
            url="$(sed -n "${start},\$p" "$LOG" 2>/dev/null \
                   | sed -n 's/^dsh web: \(http:\/\/127\.0\.0\.1:[^ ]*\).*/\1/p' \
                   | grep -F '?token=' | head -n1)"
            if [ -n "$url" ]; then
                # 先写临时文件再 mv（避免 App 读到半个 URL），权限 0600
                if printf '%s\n' "$url" > "$URL_FILE.tmp" 2>/dev/null; then
                    chmod 0600 "$URL_FILE.tmp" 2>/dev/null || true
                    mv -f "$URL_FILE.tmp" "$URL_FILE" 2>/dev/null || true
                    p="$(printf '%s' "$url" | sed -n 's|^http://[^:/]*:\([0-9][0-9]*\).*|\1|p')"
                    case "$p" in ''|*[!0-9]*) p="$PORT" ;; esac
                    printf '%s\n' "$p" > "$PORT_FILE" 2>/dev/null || true
                    printf '[%s] [supervise.sh] 已捕获带令牌 URL 并写入 %s（port=%s）\n' \
                        "$(date '+%Y-%m-%d %H:%M:%S')" "$URL_FILE" "$p" >> "$LOG" 2>/dev/null || true
                    return 0
                fi
            fi
        fi
        # dsh 已经死了就没必要继续找
        kill -0 "$DSH_PID" 2>/dev/null || return 1
        # sleep 0.2 在 toybox/coreutils 都支持
        sleep 0.2
    done
}

parse_url_stream &
PARSER_PID=$!

# ---------------------------------------------------------------------------
# 4) 信号透传 + 前台等待
# ---------------------------------------------------------------------------
CHILD=0
shutdown() {
    # 防止重入（TERM 之后再收到 INT）
    [ "$CHILD" = "1" ] && return 0
    CHILD=1
    log "收到停止信号，向 dsh(pid=$DSH_PID) 与解析器(pid=$PARSER_PID) 转发"
    kill -TERM "$PARSER_PID" 2>/dev/null || true
    kill -TERM "$DSH_PID" 2>/dev/null || true
    # 最多等 15 秒（DSH 关闭要写会话/存储），超时再 KILL
    local i=0
    while [ "$i" -lt 150 ]; do
        kill -0 "$DSH_PID" 2>/dev/null || break
        sleep 0.1
        i=$(( i + 1 ))
    done
    if kill -0 "$DSH_PID" 2>/dev/null; then
        log "dsh 未在 15s 内退出，发送 KILL"
        kill -KILL "$DSH_PID" 2>/dev/null || true
    fi
    wait "$DSH_PID" 2>/dev/null || true
    rm -f "$URL_FILE" "$PID_FILE" "$PORT_FILE" 2>/dev/null || true
    log "supervisor 退出"
    exit 0
}
trap shutdown TERM INT HUP QUIT

wait "$DSH_PID"
RC=$?

# 正常路径：dsh 自己退出了（崩溃或正常 shutdown）
kill -TERM "$PARSER_PID" 2>/dev/null || true
wait "$PARSER_PID" 2>/dev/null || true
log "dsh 进程退出，rc=$RC"
if [ "$RC" -ne 0 ] && [ ! -s "$ERR_FILE" ]; then
    printf 'dsh 进程异常退出（rc=%s）；详见 %s\n' "$RC" "$LOG" > "$ERR_FILE" 2>/dev/null || true
fi
rm -f "$URL_FILE" "$PID_FILE" "$PORT_FILE" 2>/dev/null || true
exit "$RC"
