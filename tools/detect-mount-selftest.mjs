#!/usr/bin/env node
/**
 * `module/lib/detect-mount.sh` 的**误报**回归。
 *
 * ## 真机事故（2026-09-16）
 *
 * 用户跑 `linuxctl doctor`，报告里写「本模块是否需要挂载: **是**」——而我们的模块
 * 是纯脚本模块（module/ 下没有 system/、vendor/ 之类），安装时同一段逻辑还说"否"。
 *
 * 根因：doctor 用 `sh -c '. "<detect>"; detect_mount_json'` 在子 shell 里跑探测，
 * 于是 `$0` 是 `"sh"`，脚本里"默认取脚本上一级"的 fallback 变成
 * `dirname sh/..` = **当前工作目录**；用户在 `/` 下跑时 `/system` 必然存在 → 误报"需要挂载"。
 *
 * 现在的契约：
 *   · 调用方**必须**显式给 `MODULE_ROOT`（doctor 用已安装模块路径，customize.sh 用 $MODDIR）；
 *   · 没给时只认 `/data/adb/modules[._update]/sunsetlinux`（真有 module.prop 才算），
 *     **绝不用 cwd 猜**；两条都不成立 → `module_needs_mount` 返回 2（未知），
 *     JSON 里是 `null`、报告里写"未知" —— 不硬断言。
 *   · 上下文不可信（看不到 /data/adb）时整项**不适用**，同样不下结论。
 *
 * 用法：node tools/detect-mount-selftest.mjs
 * 退出码：0 通过；1 违例。
 */

import { execFileSync } from 'node:child_process';
import { mkdirSync, mkdtempSync, rmSync, writeFileSync } from 'node:fs';
import { tmpdir } from 'node:os';
import { join } from 'node:path';
import { fileURLToPath } from 'node:url';

const REPO = join(fileURLToPath(new URL('.', import.meta.url)), '..');
const DETECT = join(REPO, 'module/lib/detect-mount.sh');

let pass = 0;
let fail = 0;
const ok = (m) => { pass++; console.log(`  \x1b[32mok\x1b[0m   ${m}`); };
const bad = (m) => { fail++; console.log(`  \x1b[31mFAIL\x1b[0m ${m}`); };

const tmp = mkdtempSync(join(tmpdir(), 'sunsetlinux-detect-'));

/**
 * 假的 Android 侧结构：/data/adb/{ksu,modules}。
 * 脚本的上下文守卫要求 `$ADB_DIR` 与 `$PROBE_MODULES_DIR` 都存在，否则它会（正确地）
 * 判为"不适用"、不再对"是否需要挂载"下结论 —— CI runner 上没有 /data/adb，
 * 不造假结构的话那几条断言就全成了"测不到"（第一次推上去就是这么红的）。
 */
const FAKE_ADB = join(tmp, 'adb');
mkdirSync(join(FAKE_ADB, 'ksu'), { recursive: true });
mkdirSync(join(FAKE_ADB, 'modules'), { recursive: true });

/** 按 doctor 的方式跑探测（子 shell + source），返回解析后的 JSON。 */
function probe({ cwd, moduleRoot, trusted = true }) {
  const env = { ...process.env };
  if (moduleRoot) env.MODULE_ROOT = moduleRoot;
  else delete env.MODULE_ROOT;
  env.ADB_DIR = trusted ? FAKE_ADB : join(tmp, 'no-such-adb');
  env.PROBE_MODULES_DIR = trusted
    ? join(FAKE_ADB, 'modules')
    : join(tmp, 'no-such-adb', 'modules');
  const out = execFileSync('sh', ['-c', '. "$1"; detect_mount_json', '_', DETECT], {
    cwd,
    env,
    encoding: 'utf8',
  });
  return JSON.parse(out.trim().split('\n').pop());
}

try {
  // ① 事故复现：cwd 里有 system/，但没给 MODULE_ROOT → **不得**报 true
  const cwdWithSystem = join(tmp, 'cwd-with-system');
  mkdirSync(join(cwdWithSystem, 'system'), { recursive: true });
  const a = probe({ cwd: cwdWithSystem });
  if (a.module_needs_mount !== true) {
    ok(`cwd 里有 system/ 且未给 MODULE_ROOT → 不误报（得到 ${JSON.stringify(a.module_needs_mount)}）`);
  } else {
    bad('又拿 cwd 当模块根了：cwd 里有 system/ 就报"需要挂载系统路径"（真机误报复现）');
  }

  // ② 真的需要挂载的模块：显式 MODULE_ROOT 指到有 system/ 的目录 → true
  const modWithSystem = join(tmp, 'mod-with-system');
  mkdirSync(join(modWithSystem, 'system'), { recursive: true });
  writeFileSync(join(modWithSystem, 'module.prop'), 'id=demo\n');
  const b = probe({ cwd: tmp, moduleRoot: modWithSystem });
  if (b.module_needs_mount === true) ok('显式 MODULE_ROOT 指向含 system/ 的模块 → 如实报 true');
  else bad(`真实探测失灵：期望 true，得到 ${JSON.stringify(b.module_needs_mount)}`);

  // ③ 我们的模块（纯脚本）：没有 system/ → false
  const ours = join(tmp, 'mod-script-only');
  mkdirSync(join(ours, 'bin'), { recursive: true });
  writeFileSync(join(ours, 'module.prop'), 'id=sunsetlinux\n');
  const c = probe({ cwd: tmp, moduleRoot: ours });
  if (c.module_needs_mount === false) ok('纯脚本模块 → false（"不挂载任何系统路径，无需 metamodule"）');
  else bad(`纯脚本模块判定错：期望 false，得到 ${JSON.stringify(c.module_needs_mount)}`);

  // ④ .replace 目录也算需要挂载（保留既有语义）
  const rep = join(tmp, 'mod-replace');
  mkdirSync(join(rep, '.replace'), { recursive: true });
  writeFileSync(join(rep, 'module.prop'), 'id=demo2\n');
  const d = probe({ cwd: tmp, moduleRoot: rep });
  if (d.module_needs_mount === true) ok('.replace 目录仍判为需要挂载（语义未变）');
  else bad(`.replace 语义丢了：期望 true，得到 ${JSON.stringify(d.module_needs_mount)}`);

  // ⑤ 报告文字：用户 2026-09-19 要求**移除「是否需要挂载」那条提示**，改为陈述实机挂载行为。
  //    所以这里反过来钉住：报告里不许再出现「是否需要挂载」，必须有"本模块的挂载"那一行。
  //    （机器接口不受影响：`detect_mount_json()` 的 module_needs_mount 字段照旧，上面 ①②③④ 测的就是它。）
  const report = execFileSync('sh', ['-c', '. "$1"; detect_mount_report', '_', DETECT], {
    cwd: cwdWithSystem,
    env: {
      ...process.env,
      MODULE_ROOT: '',
      ADB_DIR: FAKE_ADB,
      PROBE_MODULES_DIR: join(FAKE_ADB, 'modules'),
    },
    encoding: 'utf8',
  });
  if (!/是否需要挂载/.test(report)) ok('报告不再输出「是否需要挂载」（按用户要求移除）');
  else bad(`报告里还有「是否需要挂载」：\n${report.split('\n').filter((l) => l.includes('是否需要挂载')).join('\n')}`);
  if (/本模块的挂载\s*:/.test(report) && /不向系统分区放文件/.test(report)) {
    ok('报告改为陈述实机挂载行为（系统分区不动 + 环境自身在私有 ns 里挂）');
  } else {
    bad(`报告没有"实机挂载行为"的陈述：\n${report}`);
  }

  // ⑥ 上下文不可信（看不到 /data/adb）：必须"不适用"，且不对是否需要挂载下结论
  const e = probe({ cwd: tmp, trusted: false });
  if ((e.applicable === false || e.trusted === false) && e.module_needs_mount == null) {
    ok('看不到 /data/adb 时判为不适用，且不硬断言需要挂载（CI runner 上就是这种环境）');
  } else {
    bad(
      `不可信上下文处理不对：applicable=${e.applicable} trusted=${e.trusted} ` +
      `needs=${JSON.stringify(e.module_needs_mount)}`,
    );
  }
} finally {
  rmSync(tmp, { recursive: true, force: true });
}

console.log(`\n== 结果：${pass} 通过 / ${fail} 失败 ==`);
process.exit(fail === 0 ? 0 : 1);
