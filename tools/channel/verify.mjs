#!/usr/bin/env node
/**
 * tools/channel/verify.mjs —— 频道清单验签 + 层文件完整性校验
 *
 * 两步（architecture.md §5.1 / §5.4）：
 *   1) 用公钥对 channel.json 的原始字节验签；失败 → **拒绝该频道并明确报错**。
 *   2) 逐层校验：
 *        · 主分发产物（url / sha256 / size）—— App 走这条
 *        · 回退分发产物（url_gz / sha256_gz / size_gz）—— 设备侧只有 gzip，纯 CLI 走这条
 *        · 解压后的裸镜像（sha256_raw / size_raw）—— **默认真的解压一遍自己复算**，
 *          并与清单、与同目录裸镜像、与 .gz 的解压结果三方比对（--no-raw-recompute 可跳过）
 *        · zstd 帧头窗口必须 ≤ 8 MiB，否则 App 的纯 Java 解码器会退回 gzip（白下 16 MB）
 *      每层的 sha256/size 都是针对它自己那个**压缩产物**的。
 *
 * 退出码：0 = 全部通过；1 = 验签失败 / 层校验失败 / 用法错误。
 * **绝不静默降级**：任何一项不一致都会打印红色中文原因并以 1 退出。
 *
 * 用法：
 *   node verify.mjs --pub channel.pub [--in channel.json] [--dir <层目录>] [--json]
 */

import fs from 'node:fs';
import path from 'node:path';
import crypto from 'node:crypto';
import {
  parseArgs, wantsHelp, printHelp, die, info, ok, warn,
  loadPublicKey, publicKeyFingerprint, sha256File, formatBytes,
  readJson, bold, dim, green, red, yellow,
  imageNameOf, readImageSizeSync, countDecompressedBytes, hashDecompressedBytes,
  readZstdFrameInfo, checkZstdFrameForApp, APP_MAX_ZSTD_WINDOW_LOG,
} from './common.mjs';

const HELP = `用法：node verify.mjs [选项]

用公钥校验 channel.json 的 Ed25519 签名，并校验各层文件的 size/sha256。

选项：
  --pub <文件|base64>  频道公钥：channel.pub 文件路径、裸 base64、或 PEM（三选一）【必需】
  --in <文件>          channel.json 路径（默认：channel.json）
  --sig <文件>         签名文件（默认：<channel.json>.sig）
  --dir <目录>         层文件所在目录（默认：channel.json 所在目录）
  --strict             层文件缺失也视为失败（默认：缺失＝跳过并警告）
  --deep               已等价于默认行为（保留兼容）：整体解压并复算 sha256_raw/size_raw
  --no-raw-recompute   跳过"解压复算 raw"（默认会做；跳过时会明确提示，不静默）
  --no-layers          只验签，不校验层文件（清单刚生成、层还没传完时用）
  --json               结果同时以 JSON 打到 stdout（供 App/CLI 解析）
  --quiet              只输出结论行
  --help               显示本帮助

退出码：
  0  签名有效，且（如校验层）所有层文件 sha256/size 匹配
  1  验签失败、层校验失败、或参数/文件错误

安全语义：
  验签失败意味着「这份清单不是持私钥者发布的」或「字节被改过」。
  此时必须丢弃整个频道，不得"继续用旧清单凑合"或"跳过校验"。`;

const spec = {
  pub: { type: 'string', default: '' },
  in: { type: 'string', default: 'channel.json' },
  sig: { type: 'string', default: '' },
  dir: { type: 'string', default: '' },
  strict: { type: 'boolean', default: false },
  deep: { type: 'boolean', default: false },
  'no-raw-recompute': { type: 'boolean', default: false },
  'no-layers': { type: 'boolean', default: false },
  json: { type: 'boolean', default: false },
  quiet: { type: 'boolean', default: false },
};

const problems = [];

/**
 * 把清单里的层字段归一化成统一的四个量：下载产物的 (sha256,size) 与裸镜像的 (sha256,size)。
 * 兼容三种写法：
 *   A. 新清单：sha256/size（压缩产物）+ sha256_raw/size_raw（裸镜像）
 *   B. 上一版：sha256 + transport_size（压缩产物）+ size（裸镜像）
 *   C. 裸镜像分发：sha256 + size（同一个文件），无 *_raw
 */
function normalizeLayer(layer) {
  const transport = layer.transport ?? (layer.url && /\.gz$/i.test(layer.url) ? 'gzip'
    : layer.url && /\.(zst|zstd)$/i.test(layer.url) ? 'zstd' : 'none');
  const hasRaw = typeof layer.sha256_raw === 'string' && layer.sha256_raw.length > 0;
  let downloadSize = null;
  let rawSize = null;
  if (hasRaw) {
    downloadSize = typeof layer.size === 'number' ? layer.size : null;
    rawSize = typeof layer.size_raw === 'number' ? layer.size_raw : null;
  } else if (typeof layer.transport_size === 'number') {
    downloadSize = layer.transport_size;
    rawSize = typeof layer.size === 'number' ? layer.size : null;
  } else {
    downloadSize = typeof layer.size === 'number' ? layer.size : null;
    rawSize = null; // 无法区分时不做裸镜像校验
  }
  return {
    transport,
    downloadSha: layer.sha256 ?? null,
    downloadSize,
    rawSha: hasRaw ? layer.sha256_raw : null,
    rawSize,
  };
}
const notes = [];
let quietMode = false; // 由 --quiet 置位；say() 据此抑制过程输出

function say(line) {
  if (!quietMode) process.stderr.write(`${line}\n`);
}

async function main() {
  const argv = process.argv.slice(2);
  if (wantsHelp(argv)) return printHelp('sunsetlinux 频道校验（verify）', HELP);
  const { values } = parseArgs(argv, spec);

  if (!values.pub) die('缺少 --pub <公钥>（channel.pub 文件路径、裸 base64 或 PEM）');
  quietMode = values.quiet === true;

  const inFile = path.resolve(values.in);
  const sigFile = values.sig ? path.resolve(values.sig) : `${inFile}.sig`;
  const layerDir = values.dir ? path.resolve(values.dir) : path.dirname(inFile);

  if (!fs.existsSync(inFile)) die(`找不到清单：${inFile}`);
  if (!fs.existsSync(sigFile)) {
    die(`找不到签名文件：${sigFile}\n频道必须同时提供 channel.json 与 channel.json.sig，缺一不可。`);
  }

  const result = {
    schema: 1,
    manifest: inFile,
    signature: sigFile,
    pubkey_fingerprint: null,
    signature_valid: false,
    manifest_name: null,
    layers: [],
    ok: false,
    errors: [],
  };

  // ---- 步骤 1：验签 -------------------------------------------------------
  const pubObj = loadPublicKey(values.pub, '公钥');
  const fp = publicKeyFingerprint(pubObj);
  result.pubkey_fingerprint = fp;
  if (!quietMode) {
    say(bold('【1/2】签名校验'));
    say(`  清单    ${inFile}`);
    say(`  签名    ${sigFile}`);
    say(`  公钥    ${fp}`);
  }

  const raw = fs.readFileSync(inFile);
  const sigText = fs.readFileSync(sigFile, 'utf8').trim();
  let sigBuf = null;
  if (!/^[A-Za-z0-9+/]+={0,2}$/.test(sigText.replace(/\s+/g, ''))) {
    problems.push('签名文件不是合法 base64 —— 文件可能在传输中被破坏（用了 HTML 错误页顶替？）');
    result.errors.push('signature_not_base64');
  } else {
    sigBuf = Buffer.from(sigText.replace(/\s+/g, ''), 'base64');
    if (sigBuf.length !== 64) {
      problems.push(`签名长度应为 64 字节，实际 ${sigBuf.length} 字节 —— 签名文件不完整或格式不对`);
      result.errors.push('signature_bad_length');
      sigBuf = null;
    }
  }

  if (sigBuf) {
    let valid = false;
    try {
      valid = crypto.verify(null, raw, pubObj, sigBuf);
    } catch (e) {
      problems.push(`验签过程抛错：${e.message}`);
      result.errors.push('verify_threw');
    }
    result.signature_valid = valid;
    if (valid) {
      say(`  ${green('✅ 签名有效')} —— 该清单确实由持有对应私钥的人发布，且字节未被改动`);
    } else {
      const why =
        '签名无效：这份 channel.json 与私钥持有者签的那份不一致。\n' +
        '  可能原因：① 清单被改动/重新格式化过（签名只对原始字节有效）；\n' +
        '            ② 公钥填错（不是发布这个清单的那把）；\n' +
        '            ③ 有人伪造/中间人替换了清单。';
      problems.push(why);
      result.errors.push('signature_invalid');
    }
  }

  // ---- 步骤 2：层文件 -----------------------------------------------------
  let manifest = null;
  try {
    manifest = readJson(inFile);
  } catch {
    // readJson 里已 die；保险起见不继续
  }
  result.manifest_name = manifest?.name ?? null;

  if (manifest?.schema !== 1) {
    problems.push(`channel.json 的 schema 应为 1，实际是 ${JSON.stringify(manifest?.schema)}`);
    result.errors.push('bad_schema');
  }
  if (!Array.isArray(manifest?.layers) || manifest.layers.length === 0) {
    problems.push('channel.json 里没有 layers 数组或为空 —— 这份清单没有任何可用层');
    result.errors.push('no_layers');
  }

  if (!values['no-layers'] && Array.isArray(manifest?.layers)) {
    if (!quietMode) {
      say('');
      say(bold('【2/2】层文件校验'));
      say(`  目录    ${layerDir}`);
      say(`  频道    ${manifest.name ?? '(未命名)'}   生成于 ${manifest.generated_at ?? '?'}`);
      if (manifest.dsh_npm) {
        say(`  DSH     ${manifest.dsh_npm.package}@${manifest.dsh_npm.dist_tag ?? '(未指定 tag)'}`);
      }
    }

    for (const layer of manifest.layers) {
      const rec = {
        id: layer.id,
        version: layer.version,
        file: null,
        status: 'unknown',
        expected_sha256: layer.sha256 ?? null,
        actual_sha256: null,
        expected_size: layer.size ?? null,
        actual_size: null,
      };
      if (typeof layer.url !== 'string' || layer.url.length === 0) {
        rec.status = 'error';
        problems.push(`层 ${layer.id} 没有 url 字段`);
        result.layers.push(rec);
        continue;
      }
      // 绝对 URL 与相对路径都要能校验：取 URL 的文件名部分，去 --dir 里找同名文件。
      // （发布时通常写了 --base-url，清单里就是绝对 URL，但层文件还在本地，
      //   此时不做校验等于漏掉了发布前最该做的一次完整性检查。）
      const isRemote = /^[a-z][a-z0-9+.-]*:\/\//i.test(layer.url);
      let localName;
      if (isRemote) {
        try {
          localName = path.basename(decodeURIComponent(new URL(layer.url).pathname));
        } catch {
          localName = null;
        }
      } else {
        localName = decodeURIComponent(layer.url);
      }
      if (localName === null) {
        rec.status = 'error';
        problems.push(`层 ${layer.id} 的 url 无法解析：${layer.url}`);
        result.errors.push(`layer_url:${layer.id}`);
        result.layers.push(rec);
        continue;
      }
      const file = path.join(layerDir, localName);
      rec.file = file;
      if (!fs.existsSync(file)) {
        if (isRemote) {
          // 远程托管、本地没有副本：合理场景（例如用户侧体检别人的频道）。
          rec.status = 'skipped-remote';
          rec.file = layer.url;
          notes.push(`层 ${layer.id} 是远程 URL（${layer.url}），本地无同名文件 —— 跳过本地 sha256 校验`);
          result.layers.push(rec);
          continue;
        }
        rec.status = 'missing';
        const msg =
          `层文件缺失：${file}\n` +
          `  → 若只是"还没下载"，属正常；若是刚下载完，说明下载不完整。` +
          (values.strict ? '' : '（--strict 下这会是失败）');
        if (values.strict) {
          problems.push(msg);
          result.errors.push(`layer_missing:${layer.id}`);
        } else {
          notes.push(msg);
          warn(`层 ${layer.id}：文件不存在，跳过（${path.basename(file)}）`);
        }
        result.layers.push(rec);
        continue;
      }

      const norm = normalizeLayer(layer);
      const st = fs.statSync(file);
      rec.actual_size = st.size;
      rec.transport = norm.transport;
      rec.image_size = norm.rawSize;
      let failed = false;

      // ① 主分发产物：sha256 与 size 都必须匹配（size = 这个压缩文件本身的字节数）
      if (typeof norm.downloadSize === 'number' && st.size !== norm.downloadSize) {
        rec.status = 'transport-size-mismatch';
        problems.push(
          `层 ${layer.id} 主产物大小不符：期望 ${norm.downloadSize} 字节（${formatBytes(norm.downloadSize)}），` +
          `实际 ${st.size} 字节（${formatBytes(st.size)}）—— 下载不完整或文件被换过，已判定失败，跳过 sha256`,
        );
        result.errors.push(`layer_size:${layer.id}`);
        result.layers.push(rec);
        continue;
      }
      const actual = await sha256File(file);
      rec.actual_sha256 = actual;
      if (typeof norm.downloadSha !== 'string' || actual !== norm.downloadSha.toLowerCase()) {
        rec.status = 'sha256-mismatch';
        failed = true;
        problems.push(
          `层 ${layer.id} 主产物 sha256 不符：\n` +
          `    期望  ${norm.downloadSha}\n` +
          `    实际  ${actual}`,
        );
        result.errors.push(`layer_sha256:${layer.id}`);
      }

      // ② 回退产物（.gz）：本地有就一并校验 —— 设备侧只有 gzip，这条路径同样要能信
      let gzStatus = null;
      let gzLocalPath = null;
      if (typeof layer.url_gz === 'string' && layer.url_gz) {
        const gzName = path.basename(decodeURIComponent(
          /^[a-z][a-z0-9+.-]*:\/\//i.test(layer.url_gz) ? new URL(layer.url_gz).pathname : layer.url_gz,
        ));
        const gzFile = path.join(layerDir, gzName);
        gzLocalPath = gzFile;
        if (!fs.existsSync(gzFile)) {
          gzLocalPath = null;
          gzStatus = 'missing';
          const msg =
            `层 ${layer.id} 的回退产物（.gz）不在本地：${gzName}\n` +
            `  → 设备侧没有 zstd/xz，只有 toybox gzip；缺了它，"纯 CLI 手工装层"这条路会断。`;
          if (values.strict) {
            failed = true;
            problems.push(msg);
            result.errors.push(`layer_gz_missing:${layer.id}`);
          } else {
            notes.push(msg);
            warn(`层 ${layer.id}：回退产物 .gz 不在本地（--strict 下会视为失败）`);
          }
        } else {
          const gzSt = fs.statSync(gzFile);
          if (typeof layer.size_gz === 'number' && gzSt.size !== layer.size_gz) {
            gzStatus = 'size-mismatch';
            failed = true;
            problems.push(
              `层 ${layer.id} 回退产物大小不符：期望 ${layer.size_gz}，实际 ${gzSt.size}（${gzName}）`,
            );
            result.errors.push(`layer_gz_size:${layer.id}`);
          } else if (typeof layer.sha256_gz === 'string') {
            const gzSha = await sha256File(gzFile);
            if (gzSha !== layer.sha256_gz.toLowerCase()) {
              gzStatus = 'sha256-mismatch';
              failed = true;
              problems.push(
                `层 ${layer.id} 回退产物 sha256 不符（${gzName}）：\n    期望  ${layer.sha256_gz}\n    实际  ${gzSha}`,
              );
              result.errors.push(`layer_gz_sha256:${layer.id}`);
            } else {
              gzStatus = 'ok';
            }
          }
        }
      }

      // ③ 裸镜像（解压后）：**默认真的解压一遍独立复算**（不信任任何捷径）
      let rawStatus = 'skip';
      const hasRawFields = norm.rawSize !== null || norm.rawSha;
      if (hasRawFields && norm.transport === 'none') {
        rawStatus = 'same-as-download'; // 分发物就是裸镜像，① 已覆盖
      } else if (hasRawFields) {
        if (!values['no-raw-recompute']) {
          // 主产物解压复算
          const got = await hashDecompressedBytes(file, norm.transport);
          let bad = false;
          if (norm.rawSize !== null && got.size !== norm.rawSize) {
            bad = true;
            problems.push(
              `层 ${layer.id} 解压后大小不符：清单 ${norm.rawSize}（${formatBytes(norm.rawSize)}），` +
              `实测 ${got.size}（${formatBytes(got.size)}）`,
            );
            result.errors.push(`layer_raw_size:${layer.id}`);
          }
          if (norm.rawSha && got.sha256 !== norm.rawSha.toLowerCase()) {
            bad = true;
            problems.push(
              `层 ${layer.id} 解压后 sha256 不符：\n    清单  ${norm.rawSha}\n    实测  ${got.sha256}`,
            );
            result.errors.push(`layer_raw_sha256:${layer.id}`);
          }
          // 回退产物也要解压，且必须解出同一个镜像
          if (gzLocalPath) {
            const gzRaw = await hashDecompressedBytes(gzLocalPath, layer.transport_gz || 'gzip');
            if (gzRaw.sha256 !== got.sha256 || gzRaw.size !== got.size) {
              bad = true;
              problems.push(
                `层 ${layer.id} 主产物与回退产物解压结果不一致（产物不同源或损坏）：\n` +
                `    ${path.basename(file)}  → ${got.size} 字节 ${got.sha256}\n` +
                `    ${path.basename(gzLocalPath)} → ${gzRaw.size} 字节 ${gzRaw.sha256}`,
              );
              result.errors.push(`layer_raw_mismatch:${layer.id}`);
            }
          }
          // 同目录裸镜像也要与解压结果一致（App 也可能直接拿到裸镜像）
          const sibling = path.join(layerDir, imageNameOf(localName));
          if (fs.existsSync(sibling)) {
            const sib = fs.statSync(sibling).size;
            const sibSha = await sha256File(sibling);
            if (sib !== got.size || sibSha !== got.sha256) {
              bad = true;
              problems.push(
                `层 ${layer.id} 同目录裸镜像与解压产物不一致：\n` +
                `    ${path.basename(sibling)} → ${sib} 字节 ${sibSha}\n` +
                `    解压产物 → ${got.size} 字节 ${got.sha256}`,
              );
              result.errors.push(`layer_raw_sibling:${layer.id}`);
            }
          }
          rawStatus = bad ? 'mismatch' : 'recomputed';
          failed = failed || bad;
        } else {
          // 显式跳过：只做"本地有裸镜像时"的轻量检查，并且一定打印提示
          const sibling = path.join(layerDir, imageNameOf(localName));
          if (fs.existsSync(sibling)) {
            const onDisk = fs.statSync(sibling).size;
            if (norm.rawSize !== null && onDisk !== norm.rawSize) {
              failed = true;
              rawStatus = 'size-mismatch';
              problems.push(
                `层 ${layer.id} 裸镜像字节数不符：期望 ${norm.rawSize}，实际 ${onDisk}（${path.basename(sibling)}）`,
              );
              result.errors.push(`layer_raw_size:${layer.id}`);
            } else {
              rawStatus = 'sibling-ok';
            }
          } else {
            rawStatus = 'skipped';
          }
          notes.push(
            `层 ${layer.id}：--no-raw-recompute，未解压复算 sha256_raw/size_raw` +
            `（${rawStatus === 'sibling-ok' ? '仅比对了同目录裸镜像的大小' : '本地也没有裸镜像可比对'}）`,
          );
        }
      }

      // ④ zstd 帧头窗口：必须 ≤ 8 MiB，否则 App 会退回 gzip（能装但白下 16 MB）
      if (norm.transport === 'zstd') {
        const frame = readZstdFrameInfo(file);
        const verdict = checkZstdFrameForApp(frame);
        if (!verdict.ok) {
          const msg =
            `层 ${layer.id} 的 zstd 帧超出 App 解码能力：${verdict.reason}\n` +
            `  → 用户能装上，但会退回 gzip 下载（等于白下十几 MB）。\n` +
            `  → 发布方应把窗口锁在默认档：zstd -19 --zstd=wlog=${APP_MAX_ZSTD_WINDOW_LOG}`;
          if (values.strict) {
            failed = true;
            problems.push(msg);
            result.errors.push(`layer_zstd_window:${layer.id}`);
          } else {
            notes.push(msg);
            warn(`层 ${layer.id}：zstd 窗口超 8 MiB（--strict 下视为失败）`);
          }
        }
        if (frame.isZstd && frame.contentSize !== null && norm.rawSize !== null &&
            frame.contentSize !== norm.rawSize) {
          const msg =
            `层 ${layer.id} zstd 帧头声明的解压后大小（${frame.contentSize}）与清单 size_raw` +
            `（${norm.rawSize}）不一致 —— 帧头是 App 预分配的依据，务必核对`;
          if (values.strict) {
            failed = true;
            problems.push(msg);
            result.errors.push(`layer_zstd_fcs:${layer.id}`);
          } else {
            warn(msg);
          }
        }
      }

      if (!failed) {
        rec.status = 'ok';
        if (!quietMode) {
          const ratio = norm.transport === 'none' || !norm.rawSize
            ? ''
            : dim(`  解压后 ${formatBytes(norm.rawSize)}（${(norm.rawSize / st.size).toFixed(1)}×）`);
          say(
            `  ${green('✅')} ${String(layer.id).padEnd(8)} ${String(layer.version ?? '').padEnd(16)} ` +
            `${norm.transport.padEnd(5)}${formatBytes(st.size).padStart(10)}${ratio}` +
            `${gzStatus === 'ok' ? dim('  +gz ✓') : ''}`,
          );
          if (rawStatus === 'skipped' && norm.transport !== 'none') {
            say(`       ${yellow('（--no-raw-recompute：未解压复算 raw 值）')}`);
          } else if (rawStatus === 'recomputed') {
            say(`       ${dim('已解压复算 sha256_raw/size_raw 并与清单、回退产物、同目录裸镜像比对')}`);
          }
        }
      }
      result.layers.push(rec);
    }
  } else if (values['no-layers'] && !quietMode) {
    say('');
    say(bold('【2/2】层文件校验 —— 已由 --no-layers 跳过'));
  }

  // ---- 结论 --------------------------------------------------------------
  result.ok = problems.length === 0;

  if (values.json) {
    process.stdout.write(JSON.stringify(result, null, 2) + '\n');
  }

  process.stderr.write('\n');
  if (result.ok) {
    process.stderr.write(`${green(bold('✅ 校验通过'))}：签名有效，层文件与清单一致。\n`);
    if (notes.length && !quietMode) {
      for (const n of notes) process.stderr.write(`${yellow('  [提示]')} ${n}\n`);
    }
    process.exit(0);
  }

  process.stderr.write(`${red(bold('❌ 校验失败'))}：\n`);
  for (const p of problems) {
    for (const line of String(p).split('\n')) process.stderr.write(`  ${red('·')} ${line}\n`);
  }
  process.stderr.write(
    `\n${red('该频道必须被拒绝。')} 不要忽略校验失败继续安装：那等于让任何人（包括中间人）\n` +
    `往你的环境里塞任意文件。请向频道发布者核对 URL 与公钥指纹，或换一个频道。\n`,
  );
  process.exit(1);
}

main().catch((e) => die(`未预期错误：${e?.stack || e}`));
