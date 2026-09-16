#!/usr/bin/env bash
# ============================================================================
# 编译终端用的原生 PTY 库（`app/app/src/main/jniLibs/arm64-v8a/libsunsetlinux_pty.so`）
#
# ## 为什么不用 AGP 的 externalNativeBuild + CMake
#
# 官方 NDK **只发布 x86_64 宿主的工具链**（`toolchains/llvm/prebuilt/linux-x86_64/`）。
# 我们这个环境是 aarch64，AGP 会去调那个 x86_64 的 clang → `error=2` 直接失败。
# 而**编译产物本身与宿主无关**：bionic 的头/库都在 `sysroot/` 里（纯数据），
# 所以这里自己驱动 clang：
#   · 宿主是 x86_64（CI）→ 直接用 NDK 自带的 clang；
#   · 宿主是 aarch64（本机）→ 用系统 clang + `--sysroot=<NDK sysroot>`
#     + `-resource-dir=<NDK clang 资源目录>` + `-rtlib=compiler-rt`
#     （不这么写会去系统资源目录找 `libclang_rt.builtins-aarch64-android.a` 而失败）。
# 两条路用的是**同一个 pty.c、同一组编译选项**，见下面的 CFLAGS。
#
# ## 用法
#   tools/ndk-build-pty.sh                 # 编到 src/main/jniLibs/arm64-v8a/
#   tools/ndk-build-pty.sh --check         # 只校验已有产物（CI 用）
#   NDK=/path/to/ndk tools/ndk-build-pty.sh
# ============================================================================
set -euo pipefail

HERE="$(cd -- "$(dirname -- "$0")" && pwd -P)"
REPO="$(cd -- "$HERE/.." && pwd -P)"
SRC="$REPO/app/app/src/main/cpp/pty.c"
OUT_DIR="$REPO/app/app/src/main/jniLibs/arm64-v8a"
OUT="$OUT_DIR/libsunsetlinux_pty.so"

# 目标：Android 8.0（API 26，与 App 的 minSdk 一致）
API="${API:-26}"
ABI="aarch64-linux-android$API"

NDK_VERSION="${NDK_VERSION:-26.1.10909125}"

find_ndk() {
    for c in "${NDK:-}" "${ANDROID_NDK_HOME:-}" "${ANDROID_NDK_ROOT:-}" \
             "${ANDROID_HOME:-}/ndk/$NDK_VERSION" "${ANDROID_SDK_ROOT:-}/ndk/$NDK_VERSION" \
             "$HOME/Android/ndk/$NDK_VERSION" "/root/Android/ndk/$NDK_VERSION" \
             "/opt/android-ndk-$NDK_VERSION" "/usr/lib/android-ndk"; do
        [ -n "$c" ] && [ -d "$c/toolchains/llvm" ] && { printf '%s' "$c"; return 0; }
    done
    # 最后再扫一遍常见根目录
    for root in "$HOME/Android/ndk" /root/Android/ndk /opt /usr/local; do
        [ -d "$root" ] || continue
        d="$(ls -1d "$root"/*/ 2>/dev/null | head -1 || true)"
        [ -n "$d" ] && [ -d "${d}toolchains/llvm" ] && { printf '%s' "${d%/}"; return 0; }
    done
    return 1
}

verify_so() { # verify_so <path>
    local so="$1"
    [ -f "$so" ] || { echo "✗ 产物不存在：$so" >&2; return 1; }
    python3 - "$so" <<'PY'
import struct, sys
p = sys.argv[1]
b = open(p, 'rb').read()
assert b[:4] == b'\x7fELF', '不是 ELF'
machine = struct.unpack_from('<H', b, 18)[0]
assert machine == 183, f'机器码不是 aarch64（读到 {machine}）'
need = [b'PtyNative_open', b'PtyNative_resize', b'PtyNative_waitFor', b'PtyNative_closeFd', b'PtyNative_signal']
missing = [s.decode() for s in need if s not in b]
assert not missing, f'缺少 JNI 符号：{missing}'
print(f'  aarch64 ELF，{len(b)} 字节，JNI 符号齐全')
PY
}

if [ "${1:-}" = "--check" ]; then
    echo "== 校验 PTY 产物 =="
    verify_so "$OUT"
    echo "✅ $OUT 可用"
    exit 0
fi

[ -f "$SRC" ] || { echo "✗ 找不到源码：$SRC" >&2; exit 1; }
NDK_DIR="$(find_ndk)" || {
    echo "✗ 找不到 Android NDK（版本 $NDK_VERSION）。" >&2
    echo "  装法：sdkmanager --install \"ndk;$NDK_VERSION\"，或设 NDK=/path/to/ndk" >&2
    echo "  终端需要它来编 PTY 分配器；没有它终端会退回行缓冲模式（能力受限）。" >&2
    exit 1
}
SYSROOT="$NDK_DIR/toolchains/llvm/prebuilt/linux-x86_64/sysroot"
[ -d "$SYSROOT" ] || { echo "✗ NDK 里没有 sysroot：$SYSROOT" >&2; exit 1; }
RES_DIR="$(ls -1d "$NDK_DIR"/toolchains/llvm/prebuilt/linux-x86_64/lib/clang/*/ 2>/dev/null | head -1 || true)"

HOST_CLANG="$NDK_DIR/toolchains/llvm/prebuilt/linux-x86_64/bin/clang"
CFLAGS_COMMON=(-O2 -Wall -Wextra -Wno-unused-parameter -fPIC -fvisibility=hidden)

mkdir -p "$OUT_DIR"

if [ -x "$HOST_CLANG" ] && "$HOST_CLANG" --version >/dev/null 2>&1; then
    echo "== 用 NDK 自带 clang（宿主 x86_64）=="
    "$HOST_CLANG" --target="$ABI" --sysroot="$SYSROOT" \
        "${CFLAGS_COMMON[@]}" -shared -o "$OUT" "$SRC"
else
    # aarch64 宿主：NDK 的 clang 跑不了 → 系统 clang + NDK 的 sysroot/资源目录
    CLANG="$(command -v clang || command -v clang-18 || command -v clang-17 || true)"
    [ -n "$CLANG" ] || { echo "✗ 既没有可用的 NDK clang，也没有系统 clang" >&2; exit 1; }
    [ -n "$RES_DIR" ] || { echo "✗ 找不到 NDK 的 clang 资源目录（$NDK_DIR/.../lib/clang/*/）" >&2; exit 1; }
    echo "== 用系统 clang（$(basename "$CLANG")）+ NDK sysroot（宿主非 x86_64）=="
    "$CLANG" --target="$ABI" --sysroot="$SYSROOT" \
        -resource-dir "${RES_DIR%/}" -rtlib=compiler-rt \
        "${CFLAGS_COMMON[@]}" -shared -o "$OUT" "$SRC"
fi

echo "== 校验 =="
verify_so "$OUT"
echo "✅ $OUT"
echo "   目标 $ABI（minSdk 26），源码 $SRC，NDK $(basename "$NDK_DIR")"
