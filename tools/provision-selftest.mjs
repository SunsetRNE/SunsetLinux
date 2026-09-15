#!/usr/bin/env node
/**
 * `rootfs/device-provision.sh`（设备侧首次部署）的**行为级冒烟**
 *
 * ## 为什么必须有它（不是形式主义，是真踩过两次）
 *   1. **"能解析 ≠ 能跑"**：这个脚本 1300+ 行，`mksh -n` 一直通过，但真机上
 *      `declare -f`（bash 专有）会把内层构建脚本的生成整段打掉 —— 而 unshare 里
 *      起来的正是 mksh。语法闸门看不见这种问题。
 *   2. **`local x` 在 mksh 里是"未设置"，在 bash 里是"空"**：脚本是 `set -euo pipefail`，
 *      于是 `local tb` + `[ -n "$tb" ]` 在 bash 下正常、在 mksh 下直接
 *      `tb: parameter not set` 死在**每个阶段的第一步**。这个 bug 只有真跑一次才会露头。
 *   3. 真机上既没有 Termux 也没有 `/system/bin/bash`：以前那个"没有 BASH_VERSION 就
 *      exec bash，否则 exit 1"的守卫，等于把设备侧首次部署彻底堵死。
 *
 * ## 它断言什么
 *   · 静态：不再有 `declare -f`、不再有 bash 自我再执行守卫、文件头写明 mksh 原生；
 *   · mksh 下：`--help` 能跑（退出 0）、未知参数退出 2、`--dump-inner` 能生成引导脚本；
 *   · 生成的引导脚本：只是"导出环境 → exec 本文件 --inner-run"，不含函数文本；
 *   · **行为**：在 mksh 与 bash 下各跑一次内层（临时 LINUX_HOME、故意不放种子），
 *     必须走到"准备 base 阶段"并以**可读的缺种子错误**收场 ——
 *     不允许出现任何 shell 级错误（not found / parameter not set / syntax error…）；
 *   · 命令行意图必须穿过再执行：`--seeds`/`--force` 要原样出现在引导脚本里。
 *
 * 用法：node tools/provision-selftest.mjs
 * 退出码：0 全过 / 1 有失败。
 */
import { spawnSync } from 'node:child_process';
import { existsSync, mkdtempSync, readFileSync, rmSync, mkdirSync } from 'node:fs';
import { tmpdir } from 'node:os';
import { join } from 'node:path';
import { fileURLToPath } from 'node:url';

const REPO = join(fileURLToPath(new URL('.', import.meta.url)), '..');
const SCRIPT = join(REPO, 'rootfs/device-provision.sh');

let pass = 0;
let fail = 0;
const ok = (c, msg) => {
  if (c) { pass++; console.log('  \x1b[32mok\x1b[0m   ' + msg); }
  else { fail++; console.log('  \x1b[31mFAIL\x1b[0m ' + msg); }
};

/** shell 级错误：这些一旦出现，说明脚本在设备上会以"看不懂的报错"收场 */
const SHELL_ERRORS = [
  /inaccessible or not found/,
  /command not found/,
  /parameter not set/,
  /bad substitution/,
  /syntax error/,
  /not found$/,
  /Illegal option/,
  /no such file or directory: .*\.sh/,
];

/** 统一用 spawnSync：脚本把日志全写 stderr，成功路径也必须一起收（execFileSync 会丢） */
function run(shell, args, env = {}) {
  const r = spawnSync(shell, [SCRIPT, ...args], {
    encoding: 'utf8',
    env: { ...process.env, ...env },
  });
  return { code: r.status ?? -1, out: `${r.stdout ?? ''}${r.stderr ?? ''}` };
}

// ---------------------------------------------------------------------------
console.log('\n== 静态：不再依赖 bash 的写法 ==');
{
  const src = readFileSync(SCRIPT, 'utf8');
  // 只看**代码**：注释里会提到这些旧写法（那是在解释为什么不能再用），不算违例
  const code = src.split('\n').filter((l) => !/^\s*#/.test(l)).join('\n');
  ok(!/declare -f/.test(code), '代码里没有 `declare -f`（bash 专有，mksh 下 not found）');
  ok(!/BASH_VERSION/.test(code), '代码里没有 BASH_VERSION 自我再执行守卫');
  ok(!/exec\s+[^\n]*\/bash\b/.test(code), '没有 exec 到 /system/bin/bash');
  ok(/mksh/.test(src), '文件头写明了 mksh 要求（后来的人不会再"顺手"用回 bash）');
  ok(/--inner-run/.test(src), '有 --inner-run 内部模式（内层靠重新执行本文件，而不是转储函数）');
}

// ---------------------------------------------------------------------------
console.log('\n== 基本命令行（mksh = 设备真 shell） ==');
{
  const { code, out } = run('mksh', ['--help']);
  ok(code === 0, `mksh --help 退出码 0（实际 ${code}）`);
  ok(/用法|sh device-provision/.test(out), 'mksh --help 打印了用法');
}
{
  const { code, out } = run('mksh', ['--nope']);
  ok(code === 2 && /未知参数/.test(out), `未知参数 → 退出码 2（实际 ${code}）`);
}

// ---------------------------------------------------------------------------
console.log('\n== --dump-inner：生成的引导脚本 ==');
const tmp = mkdtempSync(join(tmpdir(), 'sunsetlinux-prov-'));
const customSeeds = join(tmp, 'my-seeds');
const lh = join(tmp, 'linuxhome');
let innerPath = join(lh, 'cache', '.provision-inner.sh');
try {
  const { code, out } = run('mksh', ['--seeds', customSeeds, '--force', '--dump-inner'], { LINUX_HOME: lh });
  ok(code === 0, `--dump-inner 退出码 0（实际 ${code}）`);
  ok(existsSync(innerPath), `生成了引导脚本：${innerPath.replace(tmp, '<tmp>')}`);

  const inner = existsSync(innerPath) ? readFileSync(innerPath, 'utf8') : '';
  ok(/--inner-run/.test(inner), '引导脚本 exec 的是本文件 + --inner-run（函数来自同一份源码）');
  ok(!/declare -f/.test(inner), '引导脚本里没有函数转储（没有 declare -f）');
  ok(inner.split('\n').length < 80, `引导脚本很短（实际 ${inner.split('\n').length} 行；旧写法是 1300 行）`);
  // ★ 再执行会丢掉命令行意图：--seeds / --force 必须原样导出过去
  ok(new RegExp(`SEEDS_DIR=${customSeeds}`).test(inner), '命令行 --seeds 穿过了再执行（SEEDS_DIR 已导出）');
  ok(/export FORCE=1/.test(inner), '命令行 --force 穿过了再执行（FORCE=1 已导出）');
  ok(/语法自检（.*）-n/.test(out.replace(/\u001b\[[0-9;]*m/g, '')) || /-n）/.test(out), '--dump-inner 顺带做了语法自检');
} finally {
  /* 保留到内层测试之后再删 */
}

// ---------------------------------------------------------------------------
console.log('\n== 内层真跑（临时 LINUX_HOME，故意不给种子） ==');
for (const shell of ['mksh', 'bash']) {
  const r = spawnSync(shell, [innerPath], { encoding: 'utf8', env: { ...process.env, LINUX_HOME: lh } });
  const code = r.status ?? -1;
  const plain = `${r.stdout ?? ''}${r.stderr ?? ''}`.replace(/\u001b\[[0-9;]*m/g, '');

  // 必须**走到**第一个阶段 —— 走不到就说明前面有 shell 级问题
  ok(/准备 base 阶段的干净起点/.test(plain), `${shell}：走到了「准备 base 阶段」（而不是在门口炸掉）`);
  ok(plain.includes(`缺少 Ubuntu base tarball`) && plain.includes(customSeeds),
    `${shell}：以可读的"缺种子"错误收场，且用的是 --seeds 指定的目录`);
  ok(code !== 0, `${shell}：缺种子时确实失败（退出码 ${code}，不是"假装成功"）`);
  ok(code !== 126 && code !== 127, `${shell}：不是 shell 找不到命令的退出码（${code}）`);
  ok(!/内层引导脚本已写出/.test(plain), `${shell}：没有递归生成引导脚本`);

  const bad = plain.split('\n').filter((l) => SHELL_ERRORS.some((re) => re.test(l)));
  ok(bad.length === 0, bad.length
    ? `${shell}：出现了 shell 级错误：\n       ${bad.slice(0, 4).join('\n       ')}`
    : `${shell}：没有任何 shell 级错误（not found / parameter not set / syntax error…）`);
}

rmSync(tmp, { recursive: true, force: true });

console.log(`\n== 结果：${pass} 通过 / ${fail} 失败 ==`);
process.exit(fail === 0 ? 0 : 1);
