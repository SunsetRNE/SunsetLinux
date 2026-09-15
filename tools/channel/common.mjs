/**
 * tools/channel/common.mjs —— sunsetlinux 频道工具链共用小工具
 *
 * 约束（见 docs/architecture.md §5、docs/findings.md §4）：
 *   - 只用 Node 内置模块，零第三方依赖。
 *   - 签名算法固定 Ed25519（Node 24 原生支持）。
 *   - 公钥对外表示：raw 32 字节的 base64（architecture.md §5.1 的 "pubkey" 字段）。
 *     内部 KeyObject 需要 SPKI DER，二者互转在下面两个函数里。
 *   - 私钥对外表示：PKCS#8 PEM（openssl 也能读，便于第三方用别的工具链签名）。
 *   - 所有面向用户的输出用中文。
 */

import crypto from 'node:crypto';
import zlib from 'node:zlib';
import fs from 'node:fs';
import path from 'node:path';

/* ------------------------------------------------------------------ 输出 */

export const C = {
  red: (s) => `\u001b[31m${s}\u001b[0m`,
  green: (s) => `\u001b[32m${s}\u001b[0m`,
  yellow: (s) => `\u001b[33m${s}\u001b[0m`,
  cyan: (s) => `\u001b[36m${s}\u001b[0m`,
  dim: (s) => `\u001b[2m${s}\u001b[0m`,
  bold: (s) => `\u001b[1m${s}\u001b[0m`,
};

// 非 TTY（被 App / CI 捕获）时不输出颜色，避免日志里混入转义序列。
const useColor = process.stderr.isTTY === true;
const paint = (fn) => (s) => (useColor ? fn(String(s)) : String(s));

export const red = paint(C.red);
export const green = paint(C.green);
export const yellow = paint(C.yellow);
export const cyan = paint(C.cyan);
export const dim = paint(C.dim);
export const bold = paint(C.bold);

/** 人类可读进度信息 → stderr（保证 stdout 只有机器可解析的 JSON）。 */
export function info(msg) {
  process.stderr.write(`${cyan('[信息]')} ${msg}\n`);
}
export function ok(msg) {
  process.stderr.write(`${green('[完成]')} ${msg}\n`);
}
export function warn(msg) {
  process.stderr.write(`${yellow('[警告]')} ${msg}\n`);
}
export function fail(msg) {
  process.stderr.write(`${red('[错误]')} ${msg}\n`);
}
/** 明确失败：打印中文原因后以退出码 1 结束。 */
export function die(msg, code = 1) {
  fail(msg);
  process.exit(code);
}

/* ------------------------------------------------------------ 参数解析 */

/**
 * 极简参数解析器。spec 形如：
 *   { dir: { type: 'string', default: '.' }, force: { type: 'boolean' }, ... }
 * 支持 `--key value` / `--key=value` / `--flag` / `-h`。
 * 返回 { values, positionals }；未知参数直接报错（避免拼错参数被静默忽略）。
 */
export function parseArgs(argv, spec = {}) {
  const aliases = { '-h': '--help' };
  const values = {};
  const positionals = [];
  for (const [k, v] of Object.entries(spec)) {
    if (v.default !== undefined) values[k] = v.default;
  }

  const boolOf = (v) => {
    if (v === undefined || v === '' || v === 'true' || v === '1' || v === 'yes') return true;
    if (v === 'false' || v === '0' || v === 'no') return false;
    return Boolean(v);
  };

  for (let i = 0; i < argv.length; i++) {
    let a = argv[i];
    if (aliases[a]) a = aliases[a];
    if (a === '--') {
      positionals.push(...argv.slice(i + 1));
      break;
    }
    if (!a.startsWith('--')) {
      if (a.startsWith('-') && a.length > 1) die(`未知短参数：${a}（用 --help 查看用法）`);
      positionals.push(a);
      continue;
    }
    let key = a.slice(2);
    let inline;
    const eq = key.indexOf('=');
    if (eq >= 0) {
      inline = key.slice(eq + 1);
      key = key.slice(0, eq);
    }
    if (!(key in spec)) die(`未知参数：--${key}（用 --help 查看用法）`);
    const type = spec[key].type || 'string';
    if (type === 'boolean') {
      values[key] = boolOf(inline);
    } else if (inline !== undefined) {
      values[key] = inline;
    } else {
      const next = argv[i + 1];
      if (next === undefined || (next.startsWith('--') && next.length > 2)) {
        die(`参数 --${key} 缺少取值`);
      }
      values[key] = next;
      i++;
    }
  }
  return { values, positionals };
}

/** 统一的 --help 处理：返回 true 表示已打印并应退出。 */
export function wantsHelp(argv) {
  return argv.includes('--help') || argv.includes('-h');
}

/** 打印帮助文本（中文），统一带一行固定尾注。 */
export function printHelp(title, body) {
  process.stdout.write(`${bold(title)}\n\n${body}\n`);
  process.stdout.write(
    `\n${dim('sunsetlinux 频道工具链 · 详见 docs/updates.md 与 docs/architecture.md §5')}\n`,
  );
}

/* -------------------------------------------------------------- 文件工具 */

export function sha256File(file) {
  return new Promise((resolve, reject) => {
    const h = crypto.createHash('sha256');
    const s = fs.createReadStream(file);
    s.on('error', reject);
    s.on('data', (d) => h.update(d));
    s.on('end', () => resolve(h.digest('hex')));
  });
}

export function sha256Bytes(buf) {
  return crypto.createHash('sha256').update(buf).digest('hex');
}

export function formatBytes(n) {
  if (!Number.isFinite(n)) return '未知';
  const u = ['B', 'KiB', 'MiB', 'GiB', 'TiB'];
  let i = 0;
  let v = n;
  while (v >= 1024 && i < u.length - 1) {
    v /= 1024;
    i++;
  }
  return `${v.toFixed(i === 0 ? 0 : 2)} ${u[i]}`;
}

export function readJson(file) {
  let txt;
  try {
    txt = fs.readFileSync(file, 'utf8');
  } catch (e) {
    die(`读不到文件：${file}（${e.code || e.message}）`);
  }
  try {
    return JSON.parse(txt);
  } catch (e) {
    die(`不是合法 JSON：${file} —— ${e.message}`);
  }
}

/** 写文件并（可选）设置权限；父目录自动创建。返回写入字节数。 */
export function writeFileEnsured(file, data, { mode } = {}) {
  fs.mkdirSync(path.dirname(path.resolve(file)), { recursive: true });
  const buf = Buffer.isBuffer(data) ? data : Buffer.from(data);
  const tmp = `${file}.tmp-${process.pid}`;
  fs.writeFileSync(tmp, buf, mode ? { mode } : undefined);
  if (mode) fs.chmodSync(tmp, mode);
  fs.renameSync(tmp, file);
  return buf.length;
}

export function mustExist(file, what = '文件') {
  if (!fs.existsSync(file)) die(`${what}不存在：${file}`);
  return file;
}

/* -------------------------------------------------------------- 密钥处理 */

// Ed25519 SPKI DER 固定前缀（302a300506032b6570032100）+ 32 字节 raw 公钥。
const ED25519_SPKI_PREFIX = Buffer.from('302a300506032b6570032100', 'hex');

export function rawPubToKeyObject(raw32, label = '公钥') {
  if (!Buffer.isBuffer(raw32) || raw32.length !== 32) {
    die(`${label}长度不对：Ed25519 raw 公钥必须是 32 字节，实际 ${raw32?.length}`);
  }
  const der = Buffer.concat([ED25519_SPKI_PREFIX, raw32]);
  return crypto.createPublicKey({ key: der, format: 'der', type: 'spki' });
}

export function keyObjectToRawPub(pubKeyObj) {
  const der = pubKeyObj.export({ format: 'der', type: 'spki' });
  const raw = der.subarray(der.length - 32);
  if (raw.length !== 32) die('内部错误：导出的 Ed25519 公钥不是 32 字节');
  return Buffer.from(raw);
}

/**
 * 从「公钥文件路径 / 裸 base64 / PEM」解析出公钥 KeyObject。
 * 三种写法都接受，是为了让第三方发布者可以用 openssl 生成的公钥，
 * 也可以直接用我们 keygen 产出的 channel.pub。
 *
 * tryLoadPublicKey 不退出进程（供批量体检场景收集错误）；
 * loadPublicKey 是其"失败即 die"的包装，供单值场景用。
 */
export function tryLoadPublicKey(input, label = '公钥') {
  const text = (() => {
    if (typeof input !== 'string' || input.length === 0) return { err: `${label}不能为空` };
    if (input.includes('-----BEGIN')) return { text: input };
    try {
      if (fs.existsSync(input) && fs.statSync(input).isFile()) {
        return { text: fs.readFileSync(input, 'utf8') };
      }
    } catch { /* 路径不可读 → 当字面量处理 */ }
    return { text: input };
  })();
  if (text.err) return { ok: false, error: text.err };

  const body = text.text.trim();
  if (body.includes('-----BEGIN')) {
    try {
      const k = crypto.createPublicKey(body);
      if (k.asymmetricKeyType !== 'ed25519') {
        return { ok: false, error: `${label}类型必须是 ed25519，实际是 ${k.asymmetricKeyType}` };
      }
      return { ok: true, key: k };
    } catch (e) {
      return { ok: false, error: `${label}不是可用的 PEM 公钥：${e.message}` };
    }
  }
  // 允许 base64 里带空格/换行（很多人会从文档里复制粘贴）。
  const b64 = body.replace(/\s+/g, '');
  if (!/^[A-Za-z0-9+/]+={0,2}$/.test(b64)) {
    return { ok: false, error: `${label}不是合法 base64，也不是 PEM` };
  }
  const raw = Buffer.from(b64, 'base64');
  if (raw.length !== 32) {
    return {
      ok: false,
      error: `${label}长度不对：Ed25519 raw 公钥必须是 32 字节，实际 ${raw.length} 字节`,
    };
  }
  return { ok: true, key: rawPubToKeyObject(raw, label) };
}

export function loadPublicKey(input, label = '公钥') {
  const r = tryLoadPublicKey(input, label);
  if (!r.ok) die(r.error);
  return r.key;
}

/** 从私钥文件（PKCS#8 PEM，openssl 亦可生成）或环境变量拿到私钥 KeyObject。 */
export function loadPrivateKey(file) {
  const pem = mustExist(file, '私钥文件');
  let txt = fs.readFileSync(pem, 'utf8');
  if (!txt.includes('-----BEGIN')) {
    // 容忍裸 base64 的 PKCS#8 DER（有些人会把 DER 转 base64 保存）。
    const raw = Buffer.from(txt.replace(/\s+/g, ''), 'base64');
    return crypto.createPrivateKey({ key: raw, format: 'der', type: 'pkcs8' });
  }
  try {
    const k = crypto.createPrivateKey(txt);
    if (k.asymmetricKeyType !== 'ed25519') {
      die(`私钥类型必须是 ed25519，实际是 ${k.asymmetricKeyType}`);
    }
    return k;
  } catch (e) {
    die(`私钥无法解析：${file} —— ${e.message}`);
  }
}

export function publicKeyFingerprint(pubKeyObj) {
  const raw = keyObjectToRawPub(pubKeyObj);
  const h = sha256Bytes(raw).slice(0, 16).match(/.{2}/g).join(':');
  return `ed25519:${h}`;
}

/* ------------------------------------------------------- 层文件名与版本 */

// 层规格的**唯一事实源**是 rootfs/layer-spec.sh；本文件的解析规则必须与它一致。
// 层文件：  <id>-<version>.erofs            镜像（本地挂载用）
// 传输产物：<id>-<version>.erofs.zst        分发用（zstd，推荐）
//           <id>-<version>.erofs.gz         分发用（gzip，兼容回退）
// 历史格式 *.squashfs 仍能识别，但会打警告：本机内核 `CONFIG_SQUASHFS is not set`，
// squashfs 层**挂不起来**（见 rootfs/layer-spec.sh §1）。
export const LAYER_IDS = ['base', 'runtime', 'dsh'];
export const LAYER_ORDER = { base: 0, runtime: 1, dsh: 2 };

// 传输压缩方式的优先级：同一版本同时存在多种产物时，取最高的那个。
// zstd > gzip > 不压缩（zstd 体积最小；gzip 是 App 零依赖回退；裸镜像不推荐分发）。
export const TRANSPORT_RANK = { zstd: 2, gzip: 1, none: 0 };
export const TRANSPORT_ALIASES = { zst: 'zstd', zstd: 'zstd', gz: 'gzip', gzip: 'gzip' };

const LAYER_RE = /^(base|runtime|dsh)-(.+?)\.(erofs|squashfs)(?:\.(zst|zstd|gz))?$/;

/** 'zst'/'gz'/undefined → 'zstd' | 'gzip' | 'none' */
export function transportFromExt(ext) {
  if (!ext) return 'none';
  return TRANSPORT_ALIASES[String(ext).toLowerCase()] || 'none';
}

/** 'zstd' → '.zst'，'gzip' → '.gz'，'none' → ''（与 layer-spec.sh 一致） */
export function transportExtOf(transport) {
  if (transport === 'zstd') return '.zst';
  if (transport === 'gzip') return '.gz';
  return '';
}

/** 去掉传输后缀，得到镜像文件名：a.erofs.zst → a.erofs */
export function imageNameOf(file) {
  return String(file).replace(/\.(zst|zstd|gz)$/i, '');
}

/**
 * 解析层文件名。返回 { id, version, fs, transport, file } 或 null。
 *   base-24.04.3-l1.erofs.zst → { id:'base', version:'24.04.3-l1', fs:'erofs', transport:'zstd' }
 *   dsh-0.1.5-rc.2.erofs      → { id:'dsh',  version:'0.1.5-rc.2',  fs:'erofs', transport:'none' }
 */
export function parseLayerFilename(name) {
  const base = path.basename(name);
  const m = LAYER_RE.exec(base);
  if (!m) return null;
  return {
    id: m[1],
    version: m[2],
    fs: m[3],
    transport: transportFromExt(m[4]),
    file: base,
  };
}

/* ------------------------------------------------- zstd 帧头 / 窗口上限 */

/**
 * App 侧用的是**纯 Java** zstd 解码器（io.airlift:aircompressor —— zstd-jni 没有
 * Android ABI），它的**窗口上限是 8 MiB**（windowLog ≤ 23）。
 * 发布时若用了 `--long=27`、`-22` 之类会放大窗口的参数，App 会**自动退回 gzip**
 * （不报错，但 dsh 层下载体积从 31.2 MB 涨到 47.8 MB —— 用户白下 16 MB）。
 * 因此发布工具必须：① 把 windowLog 锁在默认档 23；② 在生成/校验清单时检查帧头。
 */
export const APP_MAX_ZSTD_WINDOW_LOG = 23; // 8 MiB
export const APP_MAX_ZSTD_WINDOW_BYTES = 8 * 1024 * 1024;

/**
 * 只读前 24 字节解析 zstd 帧头（RFC 8878 §3.1.1）。
 * 返回 { isZstd, fcsFlag, singleSegment, checksum, windowLog, windowSize, contentSize }
 * 或 { isZstd:false, error }。**不做任何解压**，代价是常数级。
 *
 * 注意 "single-segment"：此时窗口大小 = 帧内容大小，所以内容 > 8 MiB 一样会超限，
 * 不能只看 windowLog。
 */
export function readZstdFrameInfo(file) {
  let fd;
  try {
    fd = fs.openSync(file, 'r');
    const b = Buffer.alloc(24);
    const n = fs.readSync(fd, b, 0, 24, 0);
    if (n < 6 || b.readUInt32LE(0) !== 0xfd2fb528) return { isZstd: false, error: '不是 zstd 帧（magic 不匹配）' };
    const fhd = b[4];
    const fcsFlag = (fhd >> 6) & 3;
    const singleSegment = (fhd >> 5) & 1;
    const checksum = (fhd >> 2) & 1;
    const dictFlag = fhd & 3;

    let off = 5;
    let windowLog = null;
    let windowSize = null;
    if (!singleSegment) {
      const wd = b[off++];
      const exp = wd >> 3;
      const mant = wd & 7;
      windowLog = 10 + exp;
      const base = 2 ** windowLog;
      windowSize = base + Math.floor(base / 8) * mant;
    }
    off += [0, 1, 2, 4][dictFlag]; // Dictionary_ID
    const fcsSize = fcsFlag === 0 ? (singleSegment ? 1 : 0) : [0, 2, 4, 8][fcsFlag];
    let contentSize = null;
    if (fcsSize === 1) contentSize = b.readUInt8(off);
    else if (fcsSize === 2) contentSize = b.readUInt16LE(off) + 256; // 2 字节形式带 +256 偏移
    else if (fcsSize === 4) contentSize = b.readUInt32LE(off);
    else if (fcsSize === 8) contentSize = Number(b.readBigUInt64LE(off));

    return { isZstd: true, fcsFlag, singleSegment, checksum, windowLog, windowSize, contentSize };
  } catch (e) {
    return { isZstd: false, error: e.message };
  } finally {
    if (fd !== undefined) { try { fs.closeSync(fd); } catch { /* ignore */ } }
  }
}

/**
 * 判断 zstd 帧是否在 App 的解码能力内。
 * 返回 { ok, reason }：ok=false 时 reason 是人类可读的中文原因。
 */
export function checkZstdFrameForApp(info) {
  if (!info || !info.isZstd) {
    return { ok: false, reason: `无法解析 zstd 帧头：${info?.error || '未知原因'}` };
  }
  if (info.singleSegment) {
    const n = info.contentSize ?? 0;
    if (n > APP_MAX_ZSTD_WINDOW_BYTES) {
      return {
        ok: false,
        reason:
          `帧是 single-segment 且内容 ${n} 字节（${(n / 1048576).toFixed(1)} MiB）> 8 MiB 上限；` +
          'App 的纯 Java 解码器会退回 gzip',
      };
    }
    return { ok: true, reason: `single-segment，内容 ${n} 字节（≤ 8 MiB）` };
  }
  if (info.windowLog > APP_MAX_ZSTD_WINDOW_LOG) {
    return {
      ok: false,
      reason:
        `zstd 窗口 windowLog=${info.windowLog}（${(info.windowSize / 1048576).toFixed(1)} MiB）` +
        `> 上限 ${APP_MAX_ZSTD_WINDOW_LOG}（8 MiB）；App 的纯 Java 解码器会退回 gzip`,
    };
  }
  return { ok: true, reason: `windowLog=${info.windowLog}（${(info.windowSize / 1048576).toFixed(1)} MiB，≤ 8 MiB）` };
}

/**
 * 版本比较 —— **三处实现必须逐字同构**（本项目有 3 份独立实现）：
 *   ① module/webroot/index.html 的 PURE.cmpVer（WebUI，浏览器侧）
 *   ② runtime/root/update.sh   的 cmp        （设备侧更新路径）
 *   ③ 本函数                                 （发布工具：gen-manifest 用它挑"最新版"）
 * 不一致的后果（真实发生过）：WebUI 说可更新、linuxctl 说不用；更糟的是
 * gen-manifest 会给候选层排错序，把**旧层**当成"最新版"写进清单。
 * 防漂移闸门：`node tools/cmp-consistency.mjs`（16 组用例三方对照，必须全绿）。
 *
 * 语义（三层叠在一起，必须分开处理）：
 *   ① base 层 `<ubuntu>-l<n>`：l<n> 按**整数**比 —— 字典序会得出 "l10" < "l9" 的错误结论；
 *   ② semver：**有预发布后缀的更小**（0.1.5-rc.2 < 0.1.5）—— 不能拿 rc 跟 5 做字典序；
 *   ③ 主干按数字段比（04 == 4，10 > 9）。
 *
 * 下面的函数体与 runtime/root/update.sh 的 cmp 逐字同构，改一处必须三处一起改。
 */
export function compareVersions(a, b) {
  const sa = String(a ?? '').trim(), sb = String(b ?? '').trim();
  const ma = sa.match(/^(.*?)-l(\d+)$/), mb = sb.match(/^(.*?)-l(\d+)$/);
  if (ma && mb && ma[1] === mb[1]) {
    const ra = Number(ma[2]), rb = Number(mb[2]);
    return ra === rb ? 0 : (ra < rb ? -1 : 1);
  }
  const split = (v) => { const i = v.indexOf('-'); return i < 0 ? { core: v, pre: null } : { core: v.slice(0, i), pre: v.slice(i + 1) }; };
  function cmpCore(x, y) {
    const px = x.split('.'), py = y.split('.');
    const n = Math.max(px.length, py.length);
    for (let i = 0; i < n; i++) {
      const a1 = px[i] ?? '0', b1 = py[i] ?? '0';
      const na = /^\d+$/.test(a1), nb = /^\d+$/.test(b1);
      if (na && nb) { if (Number(a1) !== Number(b1)) return Number(a1) < Number(b1) ? -1 : 1; }
      else if (na !== nb) return na ? 1 : -1;
      else if (a1 !== b1) return a1 < b1 ? -1 : 1;
    }
    return 0;
  }
  const A = split(sa), B = split(sb);
  const c = cmpCore(A.core, B.core);
  if (c !== 0) return c;
  if (A.pre === null && B.pre === null) return 0;
  if (A.pre === null) return 1;
  if (B.pre === null) return -1;
  const pa = A.pre.split('.'), pb = B.pre.split('.');
  const m = Math.max(pa.length, pb.length);
  for (let j = 0; j < m; j++) {
    const x1 = pa[j], y1 = pb[j];
    if (x1 === undefined) return -1;
    if (y1 === undefined) return 1;
    if (x1 === y1) continue;
    const nx1 = /^\d+$/.test(x1), ny1 = /^\d+$/.test(y1);
    if (nx1 && ny1) return Number(x1) < Number(y1) ? -1 : 1;
    if (nx1 !== ny1) return nx1 ? -1 : 1;
    return x1 < y1 ? -1 : 1;
  }
  return 0;
}

/**
 * 从已解压/已下载的镜像文件里读出**精确的镜像字节数**，不需要解压整个传输产物：
 *   - EROFS：超级块在偏移 1024，magic 0xE0F5E1E2，blkszbits 在 +12，blocks 在 +36，
 *     镜像大小 = blocks << blkszbits（已用 mkfs.erofs 产物与 stat 逐字节比对验证）
 *   - squashfs（历史格式）：magic 'hsqs'，block_size 在 +12，bytes_used 在 +40（u64）
 * 读不出来返回 null（调用方降级处理）。
 */
export function readImageSizeSync(file) {
  let fd;
  try {
    fd = fs.openSync(file, 'r');
    const head = Buffer.alloc(64);
    const n = fs.readSync(fd, head, 0, 64, 0);
    if (n >= 64 && head.readUInt32LE(0) === 0x73717368) {
      // squashfs: bytes_used 是 u64，只有低 32 位在这 64 字节里，高 32 位单独读
      const hi = Buffer.alloc(4);
      fs.readSync(fd, hi, 0, 4, 44);
      const lo = head.readUInt32LE(40);
      const size = lo + hi.readUInt32LE(0) * 4294967296;
      return { fs: 'squashfs', size };
    }
    const sb = Buffer.alloc(48);
    const got = fs.readSync(fd, sb, 0, 48, 1024);
    if (got >= 48 && sb.readUInt32LE(0) === 0xe0f5e1e2) {
      const blkszbits = sb[12];
      const blocks = sb.readUInt32LE(36);
      if (blkszbits >= 9 && blkszbits <= 20 && blocks > 0) {
        return { fs: 'erofs', size: blocks * 2 ** blkszbits };
      }
    }
    return null;
  } catch {
    return null;
  } finally {
    if (fd !== undefined) {
      try { fs.closeSync(fd); } catch { /* ignore */ }
    }
  }
}

/**
 * 版本号规则（与 rootfs/layer-spec.sh §5 保持一致，改一处必须改两处）：
 *   base    : <ubuntu 版本>-l<n>，例如 24.04.3-l1（l<n> 是对基线的第 n 次修订）
 *   runtime : 语义版本，例如 1.0.0
 *   dsh     : 与 npm 上 @deepseek-ai/dsh 完全一致的版本，例如 0.1.5-rc.2
 */
export const VERSION_PATTERNS = {
  base: /^[0-9]+\.[0-9]+\.[0-9]+-l[0-9]+$/,
  runtime: /^[0-9]+\.[0-9]+\.[0-9]+(-[0-9A-Za-z.-]+)?$/,
  dsh: /^[0-9]+\.[0-9]+\.[0-9]+(-[0-9A-Za-z.-]+)?$/,
};

/** 版本是否符合 layer-spec.sh §5 的语义版本约定 */
export function versionLooksValid(id, version) {
  const re = VERSION_PATTERNS[id];
  return re ? re.test(String(version)) : true;
}

/** 语义版本建议（给出符合规范的写法，用于错误提示） */
export function versionHint(id) {
  if (id === 'base') return '形如 24.04.3-l1（Ubuntu 版本 + -l<修订号>）';
  if (id === 'runtime') return '形如 1.0.0（语义版本）';
  if (id === 'dsh') return '与 npm 上 @deepseek-ai/dsh 的版本完全一致，形如 0.1.5-rc.2';
  return '语义版本';
}

function decompressorFor(transport) {
  if (transport === 'zstd') return zlib.createZstdDecompress();
  if (transport === 'gzip') return zlib.createGunzip();
  return null;
}

/**
 * 从**压缩的**传输产物（.erofs.zst / .gz）里读出镜像字节数：
 * 只喂给解压器前面一小段数据，够解析超级块就立刻销毁流 —— 90 MB 的层不用整体解压。
 * 返回 { fs, size } 或 null。
 */
export async function readImageSizeFromCompressed(file, transport) {
  const dec = decompressorFor(transport);
  if (!dec) return null;
  const fd = fs.openSync(file, 'r');
  try {
    const head = Buffer.alloc(64 * 1024);
    const n = fs.readSync(fd, head, 0, head.length, 0);
    const chunks = [];
    let total = 0;
    const done = new Promise((resolve) => {
      dec.on('data', (c) => {
        chunks.push(c);
        total += c.length;
        if (total >= 1200) resolve(true);
      });
      dec.on('end', () => resolve(true));
      dec.on('error', () => resolve(false));
    });
    dec.write(head.subarray(0, n));
    dec.end();
    const timer = new Promise((r) => setTimeout(() => r(false), 5000));
    await Promise.race([done, timer]);
    try { dec.destroy(); } catch { /* ignore */ }
    if (total < 1200) return null;
    const buf = Buffer.concat(chunks);
    // EROFS 超级块在 1024
    if (buf.length >= 1072 && buf.readUInt32LE(1024) === 0xe0f5e1e2) {
      const blkszbits = buf[1024 + 12];
      const blocks = buf.readUInt32LE(1024 + 36);
      if (blkszbits >= 9 && blkszbits <= 20 && blocks > 0) {
        return { fs: 'erofs', size: blocks * 2 ** blkszbits };
      }
    }
    // squashfs（历史）超级块在 0
    if (buf.readUInt32LE(0) === 0x73717368) {
      const hi = buf.length >= 48 ? buf.readUInt32LE(44) : 0;
      return { fs: 'squashfs', size: buf.readUInt32LE(40) + hi * 4294967296 };
    }
    return null;
  } catch {
    return null;
  } finally {
    fs.closeSync(fd);
  }
}

/** 整体解压：同时得到解压后字节数与 sha256（用于清单里的 sha256_raw、以及 verify --deep） */
export async function hashDecompressedBytes(file, transport) {
  const dec = decompressorFor(transport);
  const src = fs.createReadStream(file);
  const h = crypto.createHash('sha256');
  let total = 0;
  if (!dec) {
    // 已经是裸镜像：直接读文件
    await new Promise((resolve, reject) => {
      src.on('data', (c) => { total += c.length; h.update(c); })
        .on('end', resolve)
        .on('error', reject);
    });
    return { sha256: h.digest('hex'), size: total };
  }
  await new Promise((resolve, reject) => {
    src.pipe(dec)
      .on('data', (c) => { total += c.length; h.update(c); })
      .on('end', resolve)
      .on('error', reject);
  });
  return { sha256: h.digest('hex'), size: total };
}

/** 兜底：整体解压并计数（只在超级块解析失败时使用，慢但一定对） */
export async function countDecompressedBytes(file, transport) {
  const dec = decompressorFor(transport);
  if (!dec) {
    return fs.statSync(file).size;
  }
  return await new Promise((resolve, reject) => {
    let total = 0;
    fs.createReadStream(file)
      .pipe(dec)
      .on('data', (c) => { total += c.length; })
      .on('end', () => resolve(total))
      .on('error', reject);
  });
}

/** 拉取 npm dist-tags（网络失败不致命，返回 null 让调用方降级）。 */
export async function fetchDistTags(pkg = '@deepseek-ai/dsh', timeoutMs = 8000) {
  const url = `https://registry.npmjs.org/-/package/${pkg.replace('/', '%2f')}/dist-tags`;
  const ac = new AbortController();
  const t = setTimeout(() => ac.abort(), timeoutMs);
  try {
    const r = await fetch(url, { signal: ac.signal });
    if (!r.ok) return null;
    return await r.json();
  } catch {
    return null;
  } finally {
    clearTimeout(t);
  }
}
