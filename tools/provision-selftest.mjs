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
 *   4. **Android 的 mksh 没有 `printf %q`**（真机 `printf: bad %q@20`）：生成内层引导脚本
 *      那一段当场把部署打死。容器里的 mksh（R59）**有** %q，所以本地怎么测都测不出来 ——
 *      这类"宿主有、设备没有"的差异只能靠静态断言 + 不依赖它的写法一起守。
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
  // Android 的 mksh **没有 printf %q**（真机实测 "printf: bad %q@20"），本地 mksh 有 —— 
  // 所以这条只能静态守：代码里出现 %q 就等于"真机上会死"。
  ok(!/%q/.test(code), '代码里没有 `printf %q`（Android 的 mksh 不支持它）');
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
  // ★ 内层靠"原样重放命令行参数"继承意图（不是导出环境变量 —— 那需要 %q 转义，
  //   而 Android 的 mksh 没有 %q）。所以这里断言的是参数本身出现在引导脚本里。
  ok(inner.includes(customSeeds), '命令行 --seeds 穿过了再执行（原样重放参数）');
  ok(/--force/.test(inner), '命令行 --force 穿过了再执行（原样重放参数）');
  const innerCode = inner.split('\n').filter((l) => !/^\s*#/.test(l)).join('\n');
  ok(!/%q/.test(innerCode), '引导脚本里没有 %q 转义（Android mksh 不支持；注释里提到不算）');
  ok(!/^export /m.test(inner), '引导脚本不再导出一堆变量（改成参数重放）');
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

// ---------------------------------------------------------------------------
// ★ 参数重放必须**原样**穿过：含空格 + 单引号的种子目录是最容易被打碎的那种值。
//   真机踩过的是"转义方式本身在设备上不存在"（%q），所以这里连"转义结果对不对"一起验：
//   跑一遍生成的引导脚本，看它报错时说的路径是不是**一模一样**。
console.log('\n== 参数重放：含空格与单引号的路径（转义必须原样穿过） ==');
{
  const odd = join(tmp, "od'd seeds 目录");
  mkdirSync(odd, { recursive: true });
  const { code } = run('mksh', ['--seeds', odd, '--dump-inner'], { LINUX_HOME: lh });
  ok(code === 0, `--dump-inner 支持怪路径（退出码 ${code}）`);
  const inner = readFileSync(innerPath, 'utf8');
  ok(inner.includes("od'\\''d seeds"), '引导脚本里对单引号做了 POSIX 转义');
  const r = spawnSync('mksh', [innerPath], { encoding: 'utf8', env: { ...process.env, LINUX_HOME: lh } });
  const out = `${r.stdout ?? ''}${r.stderr ?? ''}`.replace(/\u001b\[[0-9;]*m/g, '');
  ok(out.includes(odd), `内层拿到的种子目录与命令行完全一致（没有被转义打碎）`);
  ok(/缺少 Ubuntu base tarball/.test(out), '仍以可读的缺种子错误收场（说明参数真的走到了 extract_base）');
  rmSync(odd, { recursive: true, force: true });
}

rmSync(tmp, { recursive: true, force: true });

console.log(`\n== 结果：${pass} 通过 / ${fail} 失败 ==`);
process.exit(fail === 0 ? 0 : 1);
