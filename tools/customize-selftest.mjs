#!/usr/bin/env node
/**
 * 模块安装脚本（`module/customize.sh`）的**行为级**回归。
 *
 * ## 为什么需要它（真机踩过）
 *
 * 用户在 KernelSU 管理器里刷模块时，屏幕上看到的是这段文案被**截断**的：
 *
 *     ★ 更省事：**装完重启就行** —— 模块会在开机时自己建层（零点击，
 *       只在没有任何
 *
 * 原因：`ui_print "   只在"没有任何 base 层"时触发…"` —— 双引号串里又写了 ASCII
 * 双引号，shell 把句子切断，后半句被当成**命令**去执行。而 `mksh -n` / `bash -n`
 * 都认为这是合法语法（确实是两条命令），所以语法闸门抓不到；只有"真的跑一遍并
 * 检查 stderr"才能抓住。这个脚本就是那道闸门。
 *
 * 顺带钉住两条产品决策（都被真机反馈纠正过）：
 *   · **推荐路径是"装完重启"**（1.0.9 起开机自建层），手动 provision 命令只是备选 ——
 *     文案顺序不能反过来，否则用户以为必须手敲那半小时的命令；
 *   · 给用户的命令一律 `/system/bin/sh`，**不能写裸 `sh`**（模块环境里可能是
 *     busybox ash，解析不了设备侧脚本）。
 *
 * ## 用法
 *   node tools/customize-selftest.mjs
 *   MKSHS="busybox ash" node tools/customize-selftest.mjs
 *
 * 退出码：0 通过；1 有失败。
 */

import { execFileSync, spawnSync } from 'node:child_process';
import { cpSync, mkdirSync, mkdtempSync, readFileSync, rmSync, writeFileSync, existsSync } from 'node:fs';
import { tmpdir } from 'node:os';
import { join } from 'node:path';
import { fileURLToPath } from 'node:url';

const REPO = join(fileURLToPath(new URL('.', import.meta.url)), '..');
const MKSHS = (process.env.MKSHS || 'mksh').split(/\s+/);

let pass = 0;
let fail = 0;
const ok = (m) => { pass++; console.log(`  \x1b[32mok\x1b[0m   ${m}`); };
const bad = (m) => { fail++; console.log(`  \x1b[31mFAIL\x1b[0m ${m}`); };

/**
 * 搭一个"像设备"的沙箱：模块目录 + 环境根 + getprop 桩。
 * @param {boolean} withLayers 是否预置一个 dsh 层（决定走哪条提示分支）
 */
function sandbox(withLayers) {
  const root = mkdtempSync(join(tmpdir(), 'sunsetlinux-customize-'));
  const mod = join(root, 'mod');
  const lh = join(root, 'lh');
  const bin = join(root, 'bin');
  mkdirSync(join(mod, 'bin'), { recursive: true });
  mkdirSync(join(mod, 'lib'), { recursive: true });
  mkdirSync(join(mod, 'webroot'), { recursive: true });
  mkdirSync(bin, { recursive: true });
  mkdirSync(join(lh, 'layers'), { recursive: true });

  // bin/ 只要"有内容"即可（脚本会 ls 它），用真的 runtime 脚本更贴近真机
  for (const f of ['linuxctl.sh', 'start.sh', 'stop.sh', 'status.sh', 'update.sh', 'doctor.sh']) {
    cpSync(join(REPO, 'runtime/root', f), join(mod, 'bin', f));
  }
  cpSync(join(REPO, 'runtime/common'), join(mod, 'bin', 'common'), { recursive: true });
  cpSync(join(REPO, 'module/lib/detect-mount.sh'), join(mod, 'lib', 'detect-mount.sh'));
  cpSync(join(REPO, 'module/module.prop'), join(mod, 'module.prop'));
  writeFileSync(join(mod, 'webroot', 'index.html'), '<html></html>\n');
  // KernelSU 的安装器函数在沙箱里不存在：脚本里都是 `|| true`，这里给个空实现更贴近
  writeFileSync(join(bin, 'getprop'), '#!/bin/sh\necho arm64-v8a\n', { mode: 0o755 });
  if (withLayers) writeFileSync(join(lh, 'layers', 'dsh.erofs'), 'x');

  return { root, mod, lh, bin };
}

function run(sh, box) {
  const r = spawnSync(sh[0], [...sh.slice(1), join(REPO, 'module/customize.sh')], {
    cwd: REPO,
    env: {
      ...process.env,
      PATH: `${box.bin}:${process.env.PATH}`,
      MODPATH: box.mod,
      LINUX_HOME: box.lh,
    },
    encoding: 'utf8',
  });
  return { code: r.status, out: r.stdout || '', err: r.stderr || '' };
}

console.log(`\n== 模块安装脚本（${MKSHS.join(' ')}）==`);

// ---- 1) 首次安装（没有层）：走"装完重启"分支 --------------------------------
{
  const box = sandbox(false);
  const { code, out, err } = run(MKSHS, box);
  const tag = '首次安装';

  if (code === 0) ok(`${tag}：退出码 0`); else bad(`${tag}：退出码 ${code}`);
  // ★ 这一条就是本次事故的判据：被 ASCII 双引号截断的句子会留下这些噪声
  if (!/not found|No such file|syntax error|unexpected/.test(err)) {
    ok(`${tag}：stderr 干净（没有"字符串被截断成命令"的痕迹）`);
  } else {
    bad(`${tag}：stderr 有异常 —— 多半又是 ui_print 串里写 ASCII 双引号把句子截断了\n       ${err.trim().split('\n').slice(0, 3).join('\n       ')}`);
  }

  const iReboot = out.indexOf('装完');
  const iManual = out.indexOf('device-provision.sh --seeds');
  if (iReboot > 0 && iManual > iReboot) {
    ok(`${tag}：先推荐"装完重启"，再给手动 provision 命令（顺序不能反）`);
  } else {
    bad(`${tag}：推荐顺序不对（重启提示 @${iReboot}，手动命令 @${iManual}）`);
  }

  if (/本次安装的模块版本：v?\d/.test(out)) ok(`${tag}：打印了模块版本`);
  else bad(`${tag}：没有打印模块版本（用户刷完不知道装的是哪版）`);

  // ★ 关键：ui_print 的句子必须**完整**打出来。被 ASCII 双引号截断时，
  //   后半句会被当成 ui_print 的**额外参数**被吞掉（不报错！），所以只能按内容断言。
  const mustSay = [
    '重启后模块会在开机时自己把环境建出来',
    '两条可选的加速/排查路径',
    '本次安装的模块版本',
  ];
  for (const sentence of mustSay) {
    if (out.includes(sentence)) ok(`${tag}：完整打出「${sentence}…」`);
    else bad(`${tag}：没有完整打出「${sentence}…」（句子被截断/吞掉了）`);
  }

  // 给用户看的命令一律 /system/bin/sh
  const bareSh = out.split('\n').filter((l) => /(^|\s)sh\s+\/data\//.test(l));
  if (bareSh.length === 0) ok(`${tag}：建议命令都用 /system/bin/sh（没有裸 sh）`);
  else bad(`${tag}：有裸 sh 建议命令：${bareSh[0].trim()}`);

  rmSync(box.root, { recursive: true, force: true });
}

// ---- 2) 已有层：不该再念 provision 那一大段 --------------------------------
{
  const box = sandbox(true);
  const { code, out, err } = run(MKSHS, box);
  const tag = '已有层';

  if (code === 0) ok(`${tag}：退出码 0`); else bad(`${tag}：退出码 ${code}`);
  if (err.trim() === '') ok(`${tag}：stderr 为空`); else bad(`${tag}：stderr：${err.trim().split('\n')[0]}`);
  if (/已检测到 DSH 层/.test(out)) ok(`${tag}：提示"开机自动启动"`); else bad(`${tag}：没检测到层`);
  if (!/device-provision\.sh --seeds/.test(out)) ok(`${tag}：不再念首次部署那一大段`);
  else bad(`${tag}：已有层却还在提示 provision`);

  rmSync(box.root, { recursive: true, force: true });
}

// ---- 3) 源码级：设备侧路径必须存在（打包漏文件是真踩过的坑）-----------------
{
  const raw = readFileSync(join(REPO, 'module/customize.sh'), 'utf8');
  const shellName = existsSync(join(REPO, 'module/lib/detect-mount.sh'));
  if (shellName) ok('detect-mount.sh 在仓库里（安装时会装载它做挂载实现探测）');
  else bad('module/lib/detect-mount.sh 不存在，安装时探测会跳过');
  if (/profiles\//.test(readFileSync(join(REPO, 'module/mkmodule.sh'), 'utf8'))) {
    ok('mkmodule.sh 会打包 profiles/（1.0.5 漏过，设备侧构建会退化成空壳）');
  } else {
    bad('mkmodule.sh 没有打包 profiles/');
  }
  if (!/(?<![\w./-])sh\s+"?\$MODDIR/.test(raw)) ok('customize.sh 内部没有用它自己都警告的裸 sh');
  else bad('customize.sh 里出现了裸 sh 调用');

  // ★★ 静态判据（这次事故的根因）：`ui_print "…"…"…"` —— 一个 ui_print 行里出现
  //    超过两个双引号，说明句子中间又开了引号，shell 会把后半句当成额外参数吞掉。
  //    这类错误 `mksh -n`/`bash -n` 都看不见（语法完全合法），只有静态数引号最可靠。
  const badQuotes = raw.split('\n')
    .map((line, i) => ({ line, i: i + 1 }))
    .filter(({ line }) => /^\s*(\[.*\]\s*&&\s*)?ui_print\s/.test(line))
    .filter(({ line }) => {
      // 只看 `ui_print` **之后**的引号：条件里的 "$MODDIR/..." 不算
      const at = line.indexOf('ui_print');
      const q = (line.slice(at).match(/"/g) || []).length;
      return q !== 0 && q !== 2;
    });
  if (badQuotes.length === 0) {
    ok('每条 ui_print 都只有一个双引号串（不会再出现"句子被吞掉"）');
  } else {
    for (const b of badQuotes.slice(0, 3)) {
      bad(`customize.sh:${b.i} 的 ui_print 参数里有 ${(b.line.slice(b.line.indexOf('ui_print')).match(/"/g) || []).length} 个双引号 —— 串中间又开了引号，后半句会被吞掉：\n       ${b.line.trim()}`);
    }
  }
}

console.log(`\n=========================================`);
console.log(`  通过 ${pass}，失败 ${fail}`);
console.log(`=========================================`);
process.exit(fail === 0 ? 0 : 1);
