#!/usr/bin/env node
/**
 * oneshot-setup.sh 的**冒烟回归**（只跑"体检"模式，它不改任何东西，所以本机/CI 都能跑）
 *
 * 为什么要有它（不是形式主义，是真踩过）：
 *   1. 一次"删空行"的编辑把 `bad()  { … }` 这一行**并进了上一行注释**里 ——
 *      `bad` 从此没被定义。`mksh -n` 照样通过（语法没错），真机上只表现成一行
 *      `bad: inaccessible or not found`，**而且阻断计数永远是 0**：明明缺三层镜像，
 *      脚本却报"没有阻断项"，然后一路往下跑 provisioning。用户是唯一的发现者。
 *   2. 部署脚本 `device-provision.sh` 内部要求 **bash**，而 Android 自带只有 mksh ——
 *      同类"能解析 ≠ 能跑"的隐形门槛。现在体检里会明确报出来。
 *
 * 所以这里断言的是**行为**：脚本能被 mksh 与 bash 跑起来、八个体检段落都在、
 * 没有任何 "command not found" 类错误、并且**该报的阻断项真的报了**。
 *
 * 用法：node tools/oneshot-selftest.mjs
 * 退出码：0 全过 / 1 有失败。
 */
import { execFileSync } from 'node:child_process';
import { mkdtempSync, rmSync } from 'node:fs';
import { tmpdir } from 'node:os';
import { join } from 'node:path';
import { fileURLToPath } from 'node:url';

const REPO = join(fileURLToPath(new URL('.', import.meta.url)), '..');
const SCRIPT = join(REPO, 'runtime/root/oneshot-setup.sh');

let pass = 0, fail = 0;
const ok = (c, msg) => { if (c) { pass++; console.log('  \x1b[32mok\x1b[0m   ' + msg); } else { fail++; console.log('  \x1b[31mFAIL\x1b[0m ' + msg); } };

const SECTIONS = ['身份与 shell', '模块', '环境根与层', '工具链', '空间与网络', '种子', '版本', '结论'];

/** 在临时目录里跑（证明"任意目录可执行"），返回 {code, out} */
function run(shell, args = []) {
  const cwd = mkdtempSync(join(tmpdir(), 'sunsetlinux-oneshot-'));
  try {
    const out = execFileSync(shell, [SCRIPT, ...args], {
      cwd, encoding: 'utf8', stdio: ['ignore', 'pipe', 'pipe'],
      env: { ...process.env, SUNSETLINUX_ONESHOT_TEST: '1' },
    });
    return { code: 0, out };
  } catch (e) {
    return { code: e.status ?? -1, out: `${e.stdout ?? ''}${e.stderr ?? ''}` };
  } finally {
    rmSync(cwd, { recursive: true, force: true });
  }
}

for (const shell of ['mksh', 'bash']) {
  console.log(`\n== ${shell}：体检模式（--help 之外不带参数＝只读） ==`);
  const { code, out } = run(shell);

  ok(code === 0, `退出码 0（实际 ${code}）—— 体检模式不该以失败收场`);
  for (const s of SECTIONS) {
    // 段落标题允许带后缀说明（如「== 种子（部署要用的离线包） ==」），所以只匹配前缀
    ok(out.includes(`== ${s}`), `输出里有「${s}」这一段`);
  }

  // ★ 关键断言：任何"命令找不到"都说明有函数/命令没定义（真机上就是这样把 ✗ 变成一行报错的）
  const notFound = out.split('\n').filter((l) =>
    /inaccessible or not found|command not found|: not found$|not found:/.test(l));
  ok(notFound.length === 0, notFound.length
    ? `有"命令找不到"的输出（说明某个函数没定义）：\n       ${notFound.slice(0, 3).join('\n       ')}`
    : '没有 "not found" 类错误（函数都定义了）');

  // ★ 第二个关键断言：本机没有 /data/adb/modules/sunsetlinux、也没有层，
  //   所以**必须**报出阻断项。如果 bad() 又没定义，这里会立刻红。
  ok(out.includes('✗'), '报出了阻断项（✗）—— 缺模块/缺层时必须能被判为阻断');
  ok(/[1-9]\d* 个阻断项/.test(out), '结论里有"N 个阻断项"且 N ≥ 1');

  // 体检里应当提到 bash 门槛（device-provision.sh 要求 bash）
  ok(/bash/.test(out), '体检提到了 bash 门槛（部署脚本要求 bash）');
}

console.log('\n== --help ==');
for (const shell of ['mksh', 'bash']) {
  const { code, out } = run(shell, ['--help']);
  ok(code === 0 && out.includes('用法'), `${shell}：--help 退出 0 且打印用法`);
}

console.log('\n== 未知参数必须被拒绝（而不是默默继续） ==');
{
  const { code, out } = run('mksh', ['--nope']);
  ok(code === 2 && /未知参数/.test(out), `未知参数 → 退出码 2（实际 ${code}）`);
}

console.log(`\n== 结果：${pass} 通过 / ${fail} 失败 ==`);
process.exit(fail === 0 ? 0 : 1);
