#!/usr/bin/env node
/**
 * 内置（离线）包工具链的**行为级回归**
 *
 * 为什么要有它：`mk-bundle.mjs` 干的事是"把几十 MB 的层原样搬进一个容器里，并记账"。
 * 这种"只是搬运"的代码最容易出**静默错误** —— 偏移算错一位、长度抄错、头与载荷不同步，
 * 都不会报错，只会在用户装机时表现为"解出来的镜像坏了"。所以这里一律**打包 → 解包 → 逐字节比对**，
 * 外加三种必须失败的负例。
 *
 * 覆盖：
 *   · variants.json 的矩阵合法（部件的名字必须在 parts_legend 里 —— 名字写错就是"这个包少一块"）
 *   · 合成小部件：打包 → 校验 → 解包 → 与源文件逐字节一致；头里的版本/raw 校验值来自文件名与清单
 *   · 负例：缺部件要明确失败、魔数不对要失败、载荷被改一个字节要失败
 *   · 真产物（dist/ 里有层时才跑）：四个变体全打一遍并逐个校验
 *
 * 用法：node tools/offline-bundle/selftest.mjs
 */
import { execFileSync } from 'node:child_process';
import { createHash } from 'node:crypto';
import { mkdtempSync, mkdirSync, readFileSync, rmSync, writeFileSync } from 'node:fs';
import { tmpdir } from 'node:os';
import { join } from 'node:path';
import { fileURLToPath } from 'node:url';

const REPO = join(fileURLToPath(new URL('.', import.meta.url)), '..', '..');
const TOOL = join(REPO, 'tools/offline-bundle/mk-bundle.mjs');
const VARIANTS = join(REPO, 'tools/offline-bundle/variants.json');

let pass = 0;
let fail = 0;
const ok = (c, msg) => { if (c) { pass++; console.log('  \x1b[32mok\x1b[0m   ' + msg); } else { fail++; console.log('  \x1b[31mFAIL\x1b[0m ' + msg); } };

function run(args) {
  try {
    const out = execFileSync(process.execPath, [TOOL, ...args], { encoding: 'utf8', stdio: ['ignore', 'pipe', 'pipe'] });
    return { code: 0, out };
  } catch (e) {
    return { code: e.status ?? -1, out: `${e.stdout ?? ''}${e.stderr ?? ''}` };
  }
}

const sha = (p) => createHash('sha256').update(readFileSync(p)).digest('hex');

// ─────────────────────────────────────────── 1) 变体矩阵
console.log('\n== 变体矩阵（variants.json）==');
{
  const spec = JSON.parse(readFileSync(VARIANTS, 'utf8'));
  const legend = Object.keys(spec.parts_legend ?? {});
  const variants = spec.variants ?? {};
  ok(legend.length >= 4, `parts_legend 覆盖四种部件（${legend.join(', ')}）`);
  ok(Object.keys(variants).length >= 4, `至少四个变体（${Object.keys(variants).join(', ')}）`);
  let bad = [];
  for (const [id, v] of Object.entries(variants)) {
    if (!Array.isArray(v.embed)) bad.push(`${id}: 缺 embed`);
    if (!v.label) bad.push(`${id}: 缺 label`);
    for (const p of v.embed ?? []) if (!legend.includes(p)) bad.push(`${id}: 部件 ${p} 不在 legend`);
  }
  ok(bad.length === 0, bad.length ? `矩阵有问题：${bad.join('; ')}` : '每个变体的 embed 都合法');
  // 关键组合必须在（用户点名的口径）
  ok(!!variants.minimal && variants.minimal.embed.length <= 1, '有「最小包」（只内嵌必要组件，不含 Ubuntu 层）');
  ok(!!variants.ubuntu && variants.ubuntu.embed.includes('base') && !variants.ubuntu.embed.includes('dsh'), '有「Ubuntu 不含 dsh」');
  ok(!!variants['ubuntu-proot'] && variants['ubuntu-proot'].embed.includes('proot'), '有「Ubuntu + proot（不含 dsh）」');
  ok(!!variants['ubuntu-proot-dsh'] && variants['ubuntu-proot-dsh'].embed.includes('dsh'), '有「Ubuntu + proot + dsh」（完全离线）');
}

// ─────────────────────────────────────────── 2) 合成部件的全链路
console.log('\n== 合成部件：打包 → 校验 → 解包 → 逐字节比对 ==');
const tmp = mkdtempSync(join(tmpdir(), 'sunsetlinux-bundle-'));
try {
  const dist = join(tmp, 'dist');
  const out = join(tmp, 'bundles');
  mkdirSync(dist, { recursive: true });
  const parts = {
    'base-24.04.3-l1.erofs.zst': Buffer.from('base-payload-'.repeat(300)),
    'runtime-1.0.0.erofs.zst': Buffer.from('runtime-payload-'.repeat(500)),
    'dsh-0.1.5-rc.1.erofs.zst': Buffer.from('dsh-payload-'.repeat(200)),
    'proot-bundle-arm64.tar.gz': Buffer.from('proot-payload-'.repeat(50)),
  };
  for (const [n, b] of Object.entries(parts)) writeFileSync(join(dist, n), b);
  // 裸镜像校验值（raw）—— App 解压后要拿它核对
  writeFileSync(join(dist, 'SHA256SUMS.layers.txt'), [
    `base-24.04.3-l1.erofs                          99893248  ${'f'.repeat(64)}`,
    `runtime-1.0.0.erofs                           240828416  ${'e'.repeat(64)}`,
    `dsh-0.1.5-rc.1.erofs                          211259392  ${'d'.repeat(64)}`,
    '',
  ].join('\n'));

  const built = run(['--variant', 'ubuntu-proot-dsh', '--dir', dist, '--out', out]);
  ok(built.code === 0, `打包成功（退出码 ${built.code}）${built.code === 0 ? '' : '：' + built.out.split('\n').slice(0, 3).join(' / ')}`);
  const bin = join(out, 'ubuntu-proot-dsh.bin');
  ok(built.out.includes('校验通过'), '打包后自动校验通过');

  const header = JSON.parse(readFileSync(join(out, 'ubuntu-proot-dsh.json'), 'utf8'));
  ok(header.variant === 'ubuntu-proot-dsh', '头里的 variant 正确');
  ok(header.base_version === '24.04.3-l1' && header.runtime_version === '1.0.0' && header.dsh_version === '0.1.5-rc.1',
    `版本号从文件名解析正确（base=${header.base_version} runtime=${header.runtime_version} dsh=${header.dsh_version}）`);
  ok(header.parts.every((p) => p.sha256 && p.len > 0 && Number.isInteger(p.off)), '每个部件都有 off/len/sha256');
  const basePart = header.parts.find((p) => p.id === 'base');
  ok(basePart.sha256_raw === 'f'.repeat(64) && basePart.size_raw === 99893248,
    '层部件带上了裸镜像的 sha256_raw/size_raw（来自 SHA256SUMS.layers.txt）');
  const prootPart = header.parts.find((p) => p.kind === 'proot');
  ok(!!prootPart && prootPart.sha256_raw === undefined, 'proot 部件不冒充"层"（没有 raw 字段）');

  const ex = join(tmp, 'extracted');
  const extracted = run(['--extract', bin, '--out', ex]);
  ok(extracted.code === 0, `解包成功（退出码 ${extracted.code}）`);
  let same = 0;
  for (const n of Object.keys(parts)) {
    if (existsSyncSafe(join(ex, n)) && sha(join(ex, n)) === sha(join(dist, n))) same++;
  }
  ok(same === Object.keys(parts).length, `解出来的 ${same}/${Object.keys(parts).length} 个部件与原文件逐字节一致`);

  console.log('\n== 负例（必须明确失败，不许静默）==');
  // ① 缺部件
  const distMinus = join(tmp, 'dist-minus');
  mkdirSync(distMinus, { recursive: true });
  for (const [n, b] of Object.entries(parts)) if (!n.startsWith('dsh-')) writeFileSync(join(distMinus, n), b);
  const miss = run(['--variant', 'ubuntu-proot-dsh', '--dir', distMinus, '--out', join(tmp, 'b2')]);
  ok(miss.code !== 0 && /找不到/.test(miss.out) && /dsh/.test(miss.out),
    '缺 dsh 部件时明确失败并点名缺什么');

  // ② 魔数不对
  const badMagic = join(tmp, 'bad-magic.bin');
  const raw = readFileSync(bin);
  writeFileSync(badMagic, Buffer.concat([Buffer.from('XXXX', 'ascii'), raw.subarray(4)]));
  const v1 = run(['--verify', badMagic]);
  ok(v1.code !== 0 && /魔数/.test(v1.out), '魔数不对 → 校验失败');

  // ③ 载荷被改一个字节
  const corrupt = join(tmp, 'corrupt.bin');
  const copy = Buffer.from(raw);
  copy[copy.length - 1] ^= 0xff;
  writeFileSync(corrupt, copy);
  const v2 = run(['--verify', corrupt]);
  ok(v2.code !== 0 && /sha256 不符/.test(v2.out), '载荷被改一个字节 → 校验失败（sha256 不符）');

  // ④ 未知变体
  const v3 = run(['--variant', 'does-not-exist', '--dir', dist, '--out', out]);
  ok(v3.code !== 0 && /未知变体/.test(v3.out), '未知变体 → 明确失败');
} finally {
  rmSync(tmp, { recursive: true, force: true });
}

// ─────────────────────────────────────────── 3) 真产物（有层才跑）
console.log('\n== 真产物（dist/ 里有层时才跑）==');
{
  const dist = join(REPO, 'dist');
  const real = ['base-24.04.3-l1.erofs.zst', 'runtime-1.0.0.erofs.zst', 'dsh-0.1.5-rc.2.erofs.zst'];
  if (!real.every((n) => existsSyncSafe(join(dist, n)))) {
    console.log('  （dist/ 里没有完整三层，跳过 —— CI 上没有 dist/ 属正常）');
  } else {
    const out = mkdtempSync(join(tmpdir(), 'sunsetlinux-bundle-real-'));
    try {
      const all = run(['--all', '--dir', dist, '--out', out]);
      ok(all.code === 0, `四个变体全部打包成功（退出码 ${all.code}）`);
      const lines = all.out.split('\n').filter((l) => l.includes('校验通过'));
      ok(lines.length === 4, `四个变体逐个校验通过（实际 ${lines.length} 个）`);
      // 逐字节回验两个月牙层
      const ex = join(out, 'x');
      const r = run(['--extract', join(out, 'ubuntu.bin'), '--out', ex]);
      ok(r.code === 0, 'ubuntu 变体解包成功');
      for (const n of ['base-24.04.3-l1.erofs.zst', 'runtime-1.0.0.erofs.zst']) {
        ok(existsSyncSafe(join(ex, n)) && sha(join(ex, n)) === sha(join(dist, n)), `真产物 ${n} 解包后逐字节一致`);
      }
      const hdr = JSON.parse(readFileSync(join(out, 'ubuntu-proot-dsh.json'), 'utf8'));
      ok(hdr.parts.some((p) => p.kind === 'proot') && hdr.parts.some((p) => p.id === 'dsh'),
        '完全离线变体里同时含 proot 与 dsh');
    } finally {
      rmSync(out, { recursive: true, force: true });
    }
  }
}

console.log(`\n== 结果：${pass} 通过 / ${fail} 失败 ==`);
process.exit(fail === 0 ? 0 : 1);

function existsSyncSafe(p) {
  try { return readFileSync(p).length >= 0; } catch { return false; }
}
