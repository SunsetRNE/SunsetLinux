#!/usr/bin/env node
/**
 * tools/channel/gen-manifest.mjs —— 扫描层文件目录，生成 channel.json
 *
 * 层规格的唯一事实源是 rootfs/layer-spec.sh：
 *   镜像   <id>-<version>.erofs         （EROFS；内核 CONFIG_SQUASHFS is not set，squashfs 挂不起来）
 *   分发   <id>-<version>.erofs.zst     （zstd，推荐）/ .gz（gzip 兼容回退）
 *
 * 产出（architecture.md §5.2 + 传输压缩扩展字段）：
 *   layers[]: { id, version, transport, url, sha256, size,
 *               transport_gz, url_gz, sha256_gz, size_gz,
 *               sha256_raw, size_raw }
 *     url / sha256 / size            主分发产物（默认 .erofs.zst；App 走这条，体积最小）
 *     url_gz / sha256_gz / size_gz   回退分发产物（.erofs.gz；**设备侧只有 gzip**，
 *                                    root 终端手工装层、模块自举走这条）
 *     sha256_raw / size_raw          解压**之后**的裸 EROFS 镜像的哈希与字节数。
 *        ⚠ 这两个值**只能真解压算出来**，禁止手填/MB 换算/从 size 反推：
 *          曾经手算出 211288064，真实值是 211259392（差 28672 字节 = 7×4096），
 *          会让 App 的"解压后校验/缓冲预分配"出问题。
 *        本工具默认对主产物与回退产物**各解压一次**并比对；两者不一致 → 直接失败。
 *     transport                      "zstd" | "gzip" | "none"（主产物的压缩方式）
 *     transport_gz                   回退产物的压缩方式（通常 "gzip"）
 *   每层的 sha256 与 size **都是针对它自己那个压缩产物**的；裸镜像用 sha256_raw/size_raw。
 *   为什么两种都发布：设备实测没有 zstd/xz，只有 toybox gzip —— 只发一种会打断一条路径
 *   （App 走 .zst，纯 CLI 走 .gz），见 rootfs/layer-spec.sh §3。
 *
 * 行为：
 *   - 扫 `base-*` / `runtime-*` / `dsh-*` 的 .erofs(.zst|.gz)（也兼容历史 .squashfs，
 *     但会警告：本机内核不支持 squashfs）。
 *   - 同一层同一版本同时存在多种产物时，取传输压缩优先级最高的（zstd > gzip > 裸镜像）。
 *   - 同一层有多个文件时取版本号最大者，其余打警告（避免把旧层写进清单）。
 *   - `--dsh-dist-tag` 会写进 dsh_npm.dist_tag，并顺带用 npm registry 校验
 *     「tag 当前指向的版本」与「dsh 层文件名里的版本」是否一致（不一致给警告）。
 *     `--offline` 可跳过这次网络查询。
 *
 * 用法：
 *   node gen-manifest.mjs --dir dist/layers-2026-09-15 \
 *        --name "官方" --base-url https://example.org/dshroid --dsh-dist-tag next
 */

import fs from 'node:fs';
import path from 'node:path';
import {
  parseArgs, wantsHelp, printHelp, die, info, ok, warn,
  sha256File, formatBytes, parseLayerFilename, compareVersions, LAYER_ORDER,
  fetchDistTags, writeFileEnsured, bold, dim, green, yellow,
  TRANSPORT_RANK, transportExtOf, imageNameOf, readImageSizeSync,
  readImageSizeFromCompressed, countDecompressedBytes, hashDecompressedBytes,
  versionLooksValid, versionHint,
  readZstdFrameInfo, checkZstdFrameForApp, APP_MAX_ZSTD_WINDOW_LOG,
} from './common.mjs';

const HELP = `用法：node gen-manifest.mjs [选项]

扫描一个目录下的层文件（base-*.erofs[.zst] / runtime-* / dsh-*），
计算 sha256 与 size，生成符合 architecture.md §5.2 的 channel.json。

选项：
  --dir <目录>            层文件所在目录（默认：当前目录）
  --out <文件>            channel.json 输出路径（默认：<dir>/channel.json）
  --name <名字>           频道名（默认："未命名频道"）
  --base-url <前缀>       层文件的下载前缀。给出时 url 写成绝对 URL
                          "<前缀>/<文件名>"；不给出则写相对文件名
                          （按 §5.2，相对路径相对 channel.json 所在 URL 解析）
  --dsh-dist-tag <tag>    DSH 的 npm dist-tag（latest / next / alpha / 自定义），
                          写进 dsh_npm.dist_tag，并联网校验版本是否对得上
  --dsh-package <包名>    DSH 包名（默认：@deepseek-ai/dsh）
  --generated-at <ISO>    固定 generated_at（可复现构建用；默认取当前 UTC 时间）
  --quick-raw             有裸镜像同目录时直接算它的 sha256（快），
                          默认**不信任捷径**：一定真解压一遍产物来算 raw 值
  --offline               不联网查询 npm dist-tags
  --include-fs            额外输出 "fs":"erofs" 字段（默认不输出，严格对齐冻结 schema）
  --strict                以下情况改为失败退出：层缺失、dist-tag 版本不一致、
                          版本号不符合 layer-spec 语义版本约定、只找到 squashfs 层
  --help                  显示本帮助

输出：
  stdout 只打印写入的 channel.json 路径（一行），人类可读摘要走 stderr。

下一步：node sign.mjs --in <channel.json> --key channel.key`;

const spec = {
  dir: { type: 'string', default: '.' },
  out: { type: 'string', default: '' },
  name: { type: 'string', default: '未命名频道' },
  'base-url': { type: 'string', default: '' },
  'dsh-dist-tag': { type: 'string', default: '' },
  'dsh-package': { type: 'string', default: '@deepseek-ai/dsh' },
  'generated-at': { type: 'string', default: '' },
  offline: { type: 'boolean', default: false },
  strict: { type: 'boolean', default: false },
  'include-fs': { type: 'boolean', default: false },
  'quick-raw': { type: 'boolean', default: false },
};

const quietStderr = process.env.DSHROID_QUIET === '1';

function isoUtc(d = new Date()) {
  return `${d.toISOString().slice(0, 19)}Z`;
}

async function main() {
  const argv = process.argv.slice(2);
  if (wantsHelp(argv)) return printHelp('dshroid 频道清单生成（gen-manifest）', HELP);

  const { values } = parseArgs(argv, spec);
  const dir = path.resolve(values.dir);
  if (!fs.existsSync(dir) || !fs.statSync(dir).isDirectory()) {
    die(`目录不存在或不是目录：${dir}`);
  }
  const outFile = values.out ? path.resolve(values.out) : path.join(dir, 'channel.json');

  if (values['generated-at']) {
    const t = Date.parse(values['generated-at']);
    if (Number.isNaN(t)) die(`--generated-at 不是合法时间：${values['generated-at']}`);
  }

  // ---- 1. 扫描层文件（镜像 + 传输产物都算候选）---------------------------
  const entries = fs.readdirSync(dir).filter((f) =>
    /\.(erofs|squashfs)(\.(zst|zstd|gz))?$/i.test(f),
  );
  if (entries.length === 0) {
    die(`目录里没有任何层文件：${dir}\n应形如 <id>-<version>.erofs（或分发的 .erofs.zst），见 rootfs/layer-spec.sh`);
  }

  /** @type {Map<string, {version:string,file:string,fs:string,transport:string}[]>} */
  const byLayer = new Map();
  const unknown = [];
  for (const f of entries.sort()) {
    const parsed = parseLayerFilename(f);
    if (!parsed) {
      unknown.push(f);
      continue;
    }
    if (!byLayer.has(parsed.id)) byLayer.set(parsed.id, []);
    byLayer.get(parsed.id).push(parsed);
  }
  if (unknown.length) {
    warn(`忽略 ${unknown.length} 个命名不符合规范的文件（应为 <id>-<version>.erofs[.zst|.gz]）：`);
    for (const f of unknown) process.stderr.write(`        ${dim(f)}\n`);
  }

  const ids = [...byLayer.keys()].sort((a, b) => LAYER_ORDER[a] - LAYER_ORDER[b]);
  const missing = Object.keys(LAYER_ORDER).filter((id) => !byLayer.has(id));
  for (const id of missing) {
    const msg = `缺少 ${id} 层文件（${id}-<version>.erofs[.zst|.gz]）`;
    if (values.strict) die(msg + '，--strict 下视为失败');
    warn(msg + ' —— channel.json 里不会出现该层');
  }

  // ---- 2. 逐层选产物 + 算 sha256 / size ----------------------------------
  const layers = [];
  for (const id of ids) {
    // 排序：版本优先 → EROFS 优先于 squashfs → 传输压缩优先级（zstd>gzip>none）
    const cands = byLayer.get(id).slice().sort((a, b) => {
      const v = compareVersions(b.version, a.version);
      if (v !== 0) return v;
      if (a.fs !== b.fs) return a.fs === 'erofs' ? -1 : 1;
      return TRANSPORT_RANK[b.transport] - TRANSPORT_RANK[a.transport];
    });
    const pick = cands[0];
    if (cands.length > 1) {
      warn(`层 ${id} 有 ${cands.length} 个候选，按「版本 → EROFS 优先 → 传输压缩优先」选用：`);
      for (const c of cands) {
        process.stderr.write(
          `        ${dim(`${c.file}${c === pick ? '  ← 选用' : ''}`)}\n`,
        );
      }
    }

    if (pick.fs === 'squashfs') {
      const msg =
        `层 ${id} 只有 squashfs 产物（${pick.file}）—— 本机内核 CONFIG_SQUASHFS is not set，` +
        '这种层在设备上挂不起来，必须改出 EROFS（见 rootfs/layer-spec.sh §1）。';
      if (values.strict) die(msg + '（--strict）');
      warn(msg);
    }
    // 附加提示（不改共享正则，避免与 layer-spec.sh §5 漂移）：
    // base 用 `<ubuntu>-l<n>`；若 runtime/dsh 的版本号长得像 base 版本，多半是文件名写错了层。
    if (id !== 'base' && /-l\d+$/.test(String(pick.version))) {
      warn(
        `层 ${id} 的版本号 ${pick.version} 长得像 base 层的 ${'<ubuntu 版本>-l<n>'} —— ` +
        `是文件名写错层了吗？（${id} 应为语义版本）`,
      );
    }
    if (!versionLooksValid(id, pick.version)) {
      const msg =
        `层 ${id} 的版本号不符合候选语义版本约定：${pick.version}（应 ${versionHint(id)}）。` +
        'state.json / channel.json 里记录的必须是语义版本，不得用文件名占位。';
      if (values.strict) die(msg + '（--strict）');
      warn(msg);
    }

    // 主产物（默认 .zst）与回退产物（.gz）都要各自算 sha256 与大小
    const artOf = async (cand) => {
      const full = path.join(dir, cand.file);
      const st = fs.statSync(full);
      process.stderr.write(
        `${dim('  计算 sha256：')}${cand.file} ${dim(`（${cand.transport}，${formatBytes(st.size)}）`)}\n`,
      );
      return {
        ...cand,
        size: st.size,
        sha256: await sha256File(full),
        url: values['base-url']
          ? `${values['base-url'].replace(/\/+$/, '')}/${encodeURIComponent(cand.file)}`
          : cand.file,
      };
    };

    const primary = await artOf(pick);
    // 回退候选：同一版本、同一 fs 的 gzip 产物（若没有 zstd 主产物，主产物本身可能就是 gzip）
    const gzCand = cands.find(
      (c) => c !== pick && c.transport === 'gzip' && c.fs === pick.fs && c.version === pick.version,
    );
    const fallback = gzCand && primary.transport !== 'gzip' ? await artOf(gzCand) : null;

    // ---- 裸镜像（解压后）的大小与 sha256 ----
    // 大小：优先读同目录的裸镜像；否则只解压头部解析超级块（不整文件解压）。
    // sha256_raw：同目录有裸镜像就直接算；否则整体解压算（正确性优先，会慢一点）。
    const siblingPath = path.join(dir, imageNameOf(pick.file));
    const hasSibling = pick.transport !== 'none' && fs.existsSync(siblingPath);
    let imageInfo = null;
    if (pick.transport === 'none') {
      imageInfo = readImageSizeSync(path.join(dir, pick.file));
    } else {
      if (hasSibling) imageInfo = readImageSizeSync(siblingPath);
      if (!imageInfo) imageInfo = await readImageSizeFromCompressed(path.join(dir, pick.file), pick.transport);
      if (!imageInfo) {
        warn(`无法从头部解析 ${pick.file} 的镜像大小 —— 改为整体解压计数（会慢一点）`);
        imageInfo = { fs: pick.fs, size: await countDecompressedBytes(path.join(dir, pick.file), pick.transport) };
      }
    }
    if (!imageInfo) {
      die(`读不出 ${pick.file} 的镜像大小：不是合法的 EROFS/squashfs 镜像？（构建是否失败了）`);
    }
    if (imageInfo.fs !== pick.fs) {
      warn(`文件后缀声明 ${pick.fs}，但镜像魔数显示是 ${imageInfo.fs}（${pick.file}）`);
    }

    // ★ raw 值必须来自**真实解压**（见文件头部说明）：
    //   主产物解压一次；有回退产物再解压一次；两者必须完全一致，否则直接失败。
    let rawInfo;
    if (values['quick-raw'] && hasSibling) {
      process.stderr.write(`${dim(`  --quick-raw：裸镜像 ${imageNameOf(pick.file)} 在同目录，直接算它的 sha256`)}\n`);
      rawInfo = { size: fs.statSync(siblingPath).size, sha256: await sha256File(siblingPath) };
    } else if (pick.transport === 'none') {
      rawInfo = { size: primary.size, sha256: primary.sha256 };
    } else {
      process.stderr.write(`${dim(`  真解压 ${pick.file} 计算 sha256_raw / size_raw…`)}\n`);
      rawInfo = await hashDecompressedBytes(path.join(dir, pick.file), pick.transport);
    }

    if (fallback) {
      process.stderr.write(`${dim(`  真解压 ${fallback.file} 校验它与主产物解压结果一致…`)}\n`);
      const fbRaw = await hashDecompressedBytes(path.join(dir, fallback.file), fallback.transport);
      if (fbRaw.size !== rawInfo.size || fbRaw.sha256 !== rawInfo.sha256) {
        die(
          `产物损坏或不同源：${pick.file} 与 ${fallback.file} 解压结果不一致！\n` +
          `    ${pick.file}      → ${fbRaw.size === rawInfo.size ? '' : `${rawInfo.size} 字节 `}${rawInfo.sha256}\n` +
          `    ${fallback.file}  → ${fbRaw.size === rawInfo.size ? '' : `${fbRaw.size} 字节 `}${fbRaw.sha256}\n` +
          '两者必须解压出逐字节相同的裸 EROFS 镜像，请重新构建层。',
        );
      }
      ok(`主产物与回退产物解压结果一致：${rawInfo.size} 字节 / ${rawInfo.sha256.slice(0, 16)}…`);
    }

    if (hasSibling) {
      const sib = { size: fs.statSync(siblingPath).size, sha256: await sha256File(siblingPath) };
      if (sib.size !== rawInfo.size || sib.sha256 !== rawInfo.sha256) {
        die(
          `同目录的裸镜像 ${imageNameOf(pick.file)} 与解压产物不一致！\n` +
          `    文件      → ${sib.size} 字节 ${sib.sha256}\n` +
          `    解压产物  → ${rawInfo.size} 字节 ${rawInfo.sha256}`,
        );
      }
      process.stderr.write(`${dim(`  同目录裸镜像与解压产物一致（${sib.size} 字节）`)}\n`);
    }

    if (rawInfo.size !== imageInfo.size) {
      die(
        `镜像大小两处不一致：超级块声明 ${imageInfo.size}，实际解压得到 ${rawInfo.size}` +
        `（${pick.file}）—— 产物可能损坏，拒绝生成清单`,
      );
    }

    // zstd 帧头合规：窗口必须 ≤ 8 MiB（App 是纯 Java 解码器）
    if (primary.transport === 'zstd') {
      const frame = readZstdFrameInfo(path.join(dir, pick.file));
      const verdict = checkZstdFrameForApp(frame);
      if (!verdict.ok) {
        const msg =
          `层 ${id} 的 zstd 帧超出 App 解码能力：${verdict.reason}\n` +
          `  → 用户能装上，但会退回 gzip 下载（dsh 层 31.2 MB → 47.8 MB），等于白下。\n` +
          `  → 修法：构建时把窗口锁在默认档（zstd -19 --zstd=wlog=${APP_MAX_ZSTD_WINDOW_LOG}），不要用 --long=27 / -22。`;
        if (values.strict) die(msg);
        warn(msg);
      } else if (!quietStderr) {
        process.stderr.write(`${dim(`  zstd 帧头合规：${verdict.reason}`)}\n`);
      }
      if (frame.isZstd && frame.contentSize !== null && frame.contentSize !== rawInfo.size) {
        die(
          `zstd 帧头声明的解压后大小（${frame.contentSize}）与实际解压结果（${rawInfo.size}）不一致 —— 产物可疑，拒绝生成清单`,
        );
      }
    }

    const layer = {
      id,
      version: pick.version,
      // 注意：architecture.md §5.2 的冻结 schema **没有** fs 字段（格式由 .erofs 后缀可知），
      // 默认不输出，避免严格的 JSON 解析器（ignoreUnknownKeys=false）报错。
      ...(values['include-fs'] ? { fs: pick.fs } : {}),
      transport: primary.transport,
      url: primary.url,
      sha256: primary.sha256,
      size: primary.size,
      sha256_raw: rawInfo.sha256,
      size_raw: rawInfo.size,
    };
    if (fallback) {
      layer.transport_gz = fallback.transport;
      layer.url_gz = fallback.url;
      layer.sha256_gz = fallback.sha256;
      layer.size_gz = fallback.size;
    }
    layers.push(layer);
  }

  // ---- 3. dsh_npm 元信息 + dist-tag 校验 ---------------------------------
  const distTag = values['dsh-dist-tag'] || null;
  const dshNpm = { dist_tag: distTag, package: values['dsh-package'] };
  const dshLayer = layers.find((l) => l.id === 'dsh');

  if (distTag && !values.offline) {
    info(`联网核对 npm dist-tag：${values['dsh-package']}@${distTag} …`);
    const tags = await fetchDistTags(values['dsh-package']);
    if (!tags) {
      warn('查询 npm registry 失败（网络不可用？），跳过 dist-tag 版本核对。');
      warn('提示：加 --offline 可消除本警告；发布前建议手工确认 tag 指向正确版本。');
    } else if (!(distTag in tags)) {
      const msg = `npm 上不存在 dist-tag "${distTag}"，现有：${Object.keys(tags).join(', ')}`;
      if (values.strict) die(msg);
      warn(msg);
    } else {
      const expect = tags[distTag];
      if (!dshLayer) {
        warn(`dist-tag ${distTag} → ${expect}，但清单里没有 dsh 层，无法核对。`);
      } else if (dshLayer.version !== expect) {
        const msg =
          `版本不一致：dist-tag "${distTag}" 在 npm 上指向 ${expect}，` +
          `而 dsh 层文件名是 ${dshLayer.version}。`;
        if (values.strict) die(msg + '（--strict）');
        warn(msg);
        warn('用户按这个 tag 装到的东西会和清单写的版本不同，请确认文件名是否写错。');
      } else {
        ok(`dist-tag 核对通过：${distTag} → ${dshLayer.version}`);
      }
    }
  } else if (distTag && values.offline) {
    info('--offline：跳过 npm dist-tag 核对。');
  }

  // ---- 4. 落盘 -----------------------------------------------------------
  const manifest = {
    schema: 1,
    name: values.name,
    generated_at: values['generated-at'] ? isoUtc(new Date(values['generated-at'])) : isoUtc(),
    layers,
    dsh_npm: dshNpm,
  };
  const body = JSON.stringify(manifest, null, 2) + '\n';
  const bytes = writeFileEnsured(outFile, body, { mode: 0o644 });
  const selfSha = (await sha256File(outFile));

  process.stderr.write('\n');
  process.stderr.write(
    `  ${bold('层'.padEnd(9))}${bold('版本'.padEnd(16))}${bold('下载'.padStart(10))}${bold('回退'.padStart(11))}${bold('裸镜像'.padStart(11))}  ${bold('传输')}\n`,
  );
  for (const l of layers) {
    process.stderr.write(
      `  ${l.id.padEnd(9)}${String(l.version).padEnd(16)}${formatBytes(l.size).padStart(10)}` +
      `${(l.size_gz ? formatBytes(l.size_gz) : '—').padStart(11)}${formatBytes(l.size_raw).padStart(11)}  ` +
      `${l.transport}${l.transport_gz ? '+gzip' : ''}` +
      `${l.transport === 'none' ? yellow('（裸镜像，分发不推荐）') : ''}\n`,
    );
  }
  ok(`已写入 ${outFile}（${bytes} 字节）`);
  process.stderr.write(`  ${dim('channel.json 自身 sha256：')}${green(selfSha)}\n`);
  process.stderr.write(`  ${dim('下一步：')}node sign.mjs --in ${path.relative(process.cwd(), outFile) || outFile} --key channel.key\n`);

  process.stdout.write(`${outFile}\n`);
}

main().catch((e) => die(`未预期错误：${e?.stack || e}`));
