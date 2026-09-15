#!/usr/bin/env node
/**
 * 设备侧脚本的 **shell 兼容性闸门**
 *
 * ## 为什么需要它（这是一个真实踩过的坑）
 *   Android 上**没有 bash**：`/system/bin/sh` 是 mksh。而本项目的运行时脚本
 *   最早是按"宿主上有 bash"写的 —— 于是模块在真机上 `linuxctl start` 直接
 *   以 `syntax error: unexpected '('` 收场（`declare -a`、`local -a`、
 *   C 式 `for ((...))` 在 mksh 里都是语法错误）。这类问题**本地 bash 跑得通、
 *   真机一行都跑不了**，靠人眼 review 是查不出来的 —— 所以做成闸门。
 *
 * ## 判定规则
 *   1. **设备侧脚本**（`module/*.sh`、`runtime/root/*.sh`）必须能被 `mksh -n` 解析。
 *      它们由 `/system/bin/sh` 执行（模块自启、App `su -c`、用户手敲），没有 bash 可用。
 *   2. **环境内脚本**（`runtime/root/entry.sh`、`supervise.sh`）跑在 chroot 后的
 *      Ubuntu 里，bash 一定存在；但"能被 mksh 解析"仍是更好的约束，一并检查。
 *   3. `runtime/proot/*.sh` 里**已知**还是 bash 的，必须写进下面的 KNOWN_BASH_ONLY
 *      （带原因），否则算违例；反过来，一旦某个文件已经能过 mksh，就必须把它从
 *      名单里删掉 —— 免得名单腐烂成"反正都在名单里"。
 *
 * ## 用法
 *   node tools/shell-compat-check.mjs [--verbose]
 *   MKSHS="mksh" node tools/shell-compat-check.mjs      # 指定解释器（CI 里可装 mksh）
 *
 * 退出码：0 通过；1 有违例。
 */

import { execFileSync } from 'node:child_process';
import { readdirSync, readFileSync } from 'node:fs';
import { join, relative } from 'node:path';
import { fileURLToPath } from 'node:url';

const REPO = join(fileURLToPath(new URL('.', import.meta.url)), '..');
const VERBOSE = process.argv.includes('--verbose');
const MKSHS = (process.env.MKSHS || 'mksh').split(/\s+/);

/**
 * 设备侧（/system/bin/sh 执行）目录：**必须**过 mksh。
 * 判据是"设备上会不会执行 / source 它"：
 *   - module/*            模块脚本由 KernelSU 调用
 *   - runtime/root/*      App `su -c` 与模块自启调用
 *   - runtime/common/*    **被 runtime/root/linuxctl.sh 整份 source**（一个语法错就整脚本报废）
 *   - rootfs/*.sh         设备侧首次部署（device-provision.sh）+ layer-spec.sh（被 source）
 */
const DEVICE_SIDE = ['module', 'runtime/root', 'runtime/common', 'rootfs'];

/**
 * 虽然在设备侧目录里，但**只在本机/CI 跑**的工具 → 不参与兼容性判定。
 * 判据是"设备上会不会执行它"，不是"它放在哪个目录"。
 */
const HOST_ONLY = {
  'module/mkmodule.sh': '打包工具：在开发机/CI 上把 module/ 压成 zip，设备侧从不执行它',
  'rootfs/build-layers.sh': '发布构建脚本：要真 chroot + mmdebstrap，只在开发机/CI 上跑',
};

/**
 * **环境内**脚本：跑在 chroot / proot 后的 Ubuntu 里，那里必然有 bash，
 * 所以允许（也只有这些允许）用 bash shebang。
 */
const ENV_SIDE = {
  'runtime/root/entry.sh': '环境内入口（chroot 后由 bash 执行）',
  'runtime/root/supervise.sh': '环境内 supervisor（chroot 后由 bash 执行）',
  'runtime/proot/entry.sh': '环境内入口（proot 内由 bash 执行）',
};

/**
 * 已知仍然只能跑 bash 的脚本 → 原因。
 * ⚠️ 这不是"允许清单"，是**欠债清单**：修好一个就删一条，否则脚本会失败。
 *
 * 2026-09-15：**欠债已清零**。`runtime/proot/{linuxctl,start}.sh` 改成 mksh 可解析：
 *   `[[ =~ ]]` → `case` 字符类（整数/名称/IPv4 判定）、C 式 `for (( ))` → `while`、
 *   进程替换（`< <(cmd)` → here-doc；`2> >(tee …)` → 日志增量回放）、
 *   `local x=()` → 先声明再赋值、`${BASH_SOURCE[0]}` → `${BASH_SOURCE[0]:-$0}`、
 *   shebang → `#!/system/bin/sh`。上面几条都是 mksh 的**语法错误**（不是运行时差异），
 *   所以修好后 `mksh -n` 与真机执行都能过。
 *   仍未做的：真机跑一次 proot 模式的 `provision`/`start`（需要设备）。
 */
const KNOWN_BASH_ONLY = {};

const problems = [];
const notations = [];

function shFiles(dir) {
  const abs = join(REPO, dir);
  let names;
  try { names = readdirSync(abs); } catch { return []; }
  return names.filter((n) => n.endsWith('.sh')).map((n) => join(dir, n)).sort();
}

function parseCheck(rel) {
  try {
    execFileSync(MKSHS[0], [...MKSHS.slice(1), '-n', join(REPO, rel)], {
      stdio: ['ignore', 'pipe', 'pipe'],
    });
    return null;
  } catch (error) {
    const msg = (error.stderr?.toString() || error.message || '').trim();
    return msg.split('\n')[0] || '解析失败';
  }
}

function shellKind(rel) {
  const first = readFileSync(join(REPO, rel), 'utf8').split('\n', 1)[0].trim();
  return first;
}

// ---- 1) 设备侧：硬性要求 ---------------------------------------------------
for (const dir of DEVICE_SIDE) {
  for (const rel of shFiles(dir)) {
    if (Object.prototype.hasOwnProperty.call(HOST_ONLY, rel)) continue;   // 宿主机工具，不判
    const err = parseCheck(rel);
    if (err) {
      problems.push(
        `${rel} 不是 mksh 可解析的语法（设备上只有 /system/bin/sh，没有 bash）\n       ${err}\n` +
        `       提示：去掉 bash 专有语法（declare -a / local -a / for (( )) / =\~ 等），` +
        `或用 POSIX 字符串替代数组。`,
      );
    } else if (VERBOSE) {
      notations.push(`${rel} ✅ ${shellKind(rel)}`);
    }
  }
}

// ---- 2) 其余脚本：要么过 mksh，要么在欠债清单里 ----------------------------
const others = [...shFiles('runtime/proot')];
for (const rel of others) {
  const err = parseCheck(rel);
  const known = Object.prototype.hasOwnProperty.call(KNOWN_BASH_ONLY, rel);
  if (err && !known) {
    problems.push(
      `${rel} 不能跑 mksh，且不在 KNOWN_BASH_ONLY 名单里 —— ` +
      `要么修好，要么**显式登记**欠债（含原因）：\n       ${err}`,
    );
  } else if (!err && known) {
    problems.push(
      `${rel} 已经能跑 mksh 了，但仍留在 KNOWN_BASH_ONLY 名单里。` +
      `请删掉这一条，让名单反映真实欠债。`,
    );
  } else if (!err && VERBOSE) {
    notations.push(`${rel} ✅ ${shellKind(rel)}`);
  }
}

// ---- 3) shebang：设备侧必须指向设备上真实存在的解释器 ----------------------
// 这一条同样来自真实事故：脚本改成 POSIX 了，shebang 却还写着 `#!/usr/bin/env bash`，
// 而 Android 上**没有 bash**，于是 App 直接执行 `$LINUX_HOME/bin/linuxctl` 时
// 内核找不到解释器 → 表现为"找不到 linuxctl"。
const allDeviceFiles = DEVICE_SIDE.flatMap((d) => shFiles(d));
for (const rel of allDeviceFiles) {
  if (Object.prototype.hasOwnProperty.call(HOST_ONLY, rel)) continue;    // 宿主机工具
  const shebang = shellKind(rel);
  if (Object.prototype.hasOwnProperty.call(ENV_SIDE, rel)) {
    if (!/bash/.test(shebang)) {
      problems.push(`${rel} 是环境内脚本（bash 可用），但 shebang 是 ${shebang} —— 环境内请显式用 bash`);
    }
    continue;
  }
  if (shebang !== '#!/system/bin/sh') {
    problems.push(
      `${rel} 的 shebang 是 ${shebang}，设备侧应为 #!/system/bin/sh` +
      `（Android 上没有 bash；环境内脚本才允许 bash，且需登记进 ENV_SIDE）`,
    );
  }
}

// ---- 输出 ------------------------------------------------------------------
if (problems.length === 0) {
  const known = Object.keys(KNOWN_BASH_ONLY);
  console.log(`✅ shell 兼容性检查通过`);
  console.log(`   设备侧（必须 mksh 可解析）：${DEVICE_SIDE.map((d) => `${d}/*.sh(${shFiles(d).length})`).join(' ')}`);
  if (known.length) {
    console.log(`   ⚠️ 欠债中（仍只能跑 bash，真机不可用）：`);
    for (const k of known) console.log(`      - ${k}`);
  }
  if (VERBOSE && notations.length) for (const n of notations) console.log(`   ${n}`);
  process.exit(0);
}
console.log(`❌ shell 兼容性检查失败：${problems.length} 处`);
for (const p of problems) console.log(`   - ${p}`);
process.exit(1);
