#!/usr/bin/env node
/**
 * tools/seed/zstd-filter.mjs —— 用 Node 内置 zlib 做 zstd/gzip 过滤器（stdin → stdout）
 *
 * 为什么需要它：
 *   本机（Android 上的 Ubuntu/proot）**没有装 zstd 命令**，而离线种子包要求
 *   `.tar.zst`。Node 24 的 `node:zlib` 已内建 zstd（createZstdCompress /
 *   createZstdDecompress），所以这里用它顶替外部 zstd 二进制，零第三方依赖。
 *   mkseed.sh / verify-seed.sh 会**优先用系统 zstd**，找不到才回退到本脚本。
 *
 * 用法：
 *   node zstd-filter.mjs [-d] [-l 19] < 输入 > 输出
 *     -d, --decompress   解压（默认压缩）
 *     -l, --level <N>    压缩级别 1–22（默认 19；越大越小越慢）
 *
 * 退出码：0 成功；1 参数/IO 错误（错误信息为中文，走 stderr）。
 */

import { createZstdCompress, createZstdDecompress, createGzip, createGunzip } from 'node:zlib';
import { pipeline } from 'node:stream/promises';
import { constants as zlibConstants } from 'node:zlib';

function die(msg) {
  process.stderr.write(`[错误] ${msg}\n`);
  process.exit(1);
}

const argv = process.argv.slice(2);
let decompress = false;
let level = 19;
let levelSet = false;
let algo = 'zstd'; // 'zstd' | 'gzip'
// zstd 窗口上限默认 23（= 8 MiB）。**不要提高**：dshroid 的 App 用纯 Java 解码器
// （io.airlift:aircompressor，因为 zstd-jni 没有 Android ABI），窗口上限就是 8 MiB
// （windowLog ≤ 23）。超过它 App 会退回 gzip，dsh 层下载体积 31.2 MB → 47.8 MB，用户白下。
let windowLog = 23;
let allowLargeWindow = false;

for (let i = 0; i < argv.length; i++) {
  const a = argv[i];
  if (a === '-d' || a === '--decompress' || a === '--uncompress') decompress = true;
  else if (a === '-l' || a === '--level') {
    level = Number(argv[++i]);
    levelSet = true;
  } else if (a.startsWith('--level=')) {
    level = Number(a.slice(8));
    levelSet = true;
  } else if (a === '-w' || a === '--window') {
    windowLog = Number(argv[++i]);
  } else if (a.startsWith('--window=')) {
    windowLog = Number(a.slice(9));
  } else if (a === '--allow-large-window') {
    allowLargeWindow = true;
  } else if (a === '-a' || a === '--algo') {
    algo = String(argv[++i] || '').toLowerCase();
  } else if (a.startsWith('--algo=')) {
    algo = a.slice(7).toLowerCase();
  } else if (a === '-h' || a === '--help') {
    process.stdout.write(
      '用法：node zstd-filter.mjs [-d] [-a zstd|gzip] [-l 1-22] < 输入 > 输出\n' +
        '  -d, --decompress      解压（默认压缩）\n' +
        '  -a, --algo <算法>      zstd（默认）| gzip\n' +
        '  -l, --level <N>        压缩级别：zstd 1–22（默认 19）| gzip 1–9（默认 9）\n' +
        '  -w, --window <N>       zstd 窗口 windowLog（默认 23 = 8 MiB，**不要提高**：\n' +
        '                         App 的纯 Java 解码器上限就是 8 MiB，超了会退回 gzip）\n' +
        '      --allow-large-window  明知故犯地允许 windowLog > 23（会警告）\n' +
        '本脚本用 Node 内置 zlib，用于系统缺少 zstd/gzip 命令的环境。\n' +
        '默认算法保持 zstd，因此老的调用方式不受影响。\n',
    );
    process.exit(0);
  } else die(`未知参数：${a}（用 --help 查看用法）`);
}

if (algo !== 'zstd' && algo !== 'gzip') die(`不支持的算法：${algo}（可用：zstd | gzip）`);
if (!Number.isInteger(windowLog) || windowLog < 10 || windowLog > 31) {
  die(`--window 必须是 10–31 的整数，实际：${windowLog}`);
}
if (algo === 'zstd' && windowLog > 23) {
  if (!allowLargeWindow) {
    die(
      `--window ${windowLog} 超过 8 MiB 上限（windowLog 23）：dshroid App 的纯 Java zstd 解码器\n` +
      '解不了这么大的窗口，会自动退回 gzip 下载（等于让用户白下十几 MB）。\n' +
      '确实需要请显式加 --allow-large-window。',
    );
  }
  process.stderr.write(`[警告] windowLog=${windowLog} > 23：App 会退回 gzip 下载\n`);
}
if (!levelSet) level = algo === 'gzip' ? 9 : 19;
const maxLevel = algo === 'gzip' ? 9 : 22;
if (levelSet && (!Number.isInteger(level) || level < 1 || level > maxLevel)) {
  die(`压缩级别必须是 1–${maxLevel} 的整数（算法：${algo}），实际：${level}`);
}

// 管道两端都接 stdio；错误要明确报出来，不能静默产生半个文件。
async function run() {
  if (decompress) {
    await pipeline(
      process.stdin,
      algo === 'gzip' ? createGunzip() : createZstdDecompress(),
      process.stdout,
    );
  } else {
    await pipeline(
      process.stdin,
      algo === 'gzip'
        ? createGzip({ level })
        : createZstdCompress({
            params: {
              [zlibConstants.ZSTD_c_compressionLevel]: level,
              // 显式锁窗口：zstd 高级别（-20 以上）默认窗口会超过 8 MiB
              [zlibConstants.ZSTD_c_windowLog]: windowLog,
            },
          }),
      process.stdout,
    );
  }
}

run().catch((e) => {
  // EPIPE 常见于 `... | head`，不算错误。
  if (e?.code === 'EPIPE') process.exit(0);
  die(`${algo} ${decompress ? '解压' : '压缩'}失败：${e?.message || e}`);
});
