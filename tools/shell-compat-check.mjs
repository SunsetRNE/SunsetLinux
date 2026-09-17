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
 * ## 第二层：`mksh -n` **看不见**的陷阱（真机上一条条踩出来的）
 *   `mksh -n` 只证明"能解析"。下面这些是"解析得过、真机照死"的，所以单独做成**模式闸门**
 *   （每条都对应一次真机失败，注释里写了为什么）：
 *
 *   | 模式 | 真机表现 | 为什么本地测不出来 |
 *   |---|---|---|
 *   | `printf %q` | `printf: bad %q@20`，整个部署终止 | 容器/CI 的 mksh **有** %q，Android 的**没有** |
 *   | `local x=(…)` | `syntax error: unexpected '('` | mksh 全系都不支持，但 host 侧 `bash -n` 会过 |
 *   | `[[ x =~ re ]]` | `syntax error: unexpected operator/operand '=~'` | 同上 |
 *   | `${BASH_SOURCE[0]}` | 被 busybox ash 解析时 `syntax error: bad substitution`（脚本一行都不跑） | 只有"用裸 `sh` 发起"时才会撞到，本地用 bash 跑一直是对的 |
 *   | `bash start.sh` | 设备上没有 bash → 挂载这一步永远失败 | 本地/CI 的 bash 让它看起来完全正常 |
 *   | 裸 `sh <脚本>` | 模块自启的 PATH 里 `sh` 是 busybox ash，解析不了设备侧脚本 | 同上 |
 *   | nsenter `--wd=` | `nsenter: Unknown option 'wd=…'` → attach/exec **整条不执行** | 宿主/CI 的 nsenter 是 util-linux（有 `-w`），设备的 `/system/bin/nsenter` 是 toybox（**没有**）；本地永远撞不到 |
 *
 *   还有一类**语法闸门永远管不了**的：`x=()` 在 mksh 里**不是空数组**，
 *   `set -u` 下展开 `"${x[@]}"` 会 `parameter not set`（真机第二次失败的原因）。
 *   这种只能靠**行为级单测**兜（见 tools/provision-selftest.mjs 的 make_erofs 一节）。
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

/**
 * 允许出现 `BASH_SOURCE[...]` 的文件 → 原因。
 * 判据很窄：**只有 bash 上下文才会求值**（前面有 `${BASH_SOURCE+set}` 守卫），
 * mksh/ash 下 BASH_SOURCE 未定义 → 短路，下标表达式根本不会执行。
 * 这与"设备侧脚本依赖 bash"是两回事，所以单独列，不放进 TRAPS 白名单式豁免。
 */
const BASH_SOURCE_OK = {
  'runtime/common/status_json.sh': '尾部"是否被 source"的自查，被 `${BASH_SOURCE+set}` 守卫（mksh/ash 下短路）',
  'runtime/common/http_health.sh': '同上',
};

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

/**
 * `mksh -n` 管不住的陷阱：逐行扫（**跳过注释行** —— 项目里到处在注释里解释这些坑）。
 * 返回 [{ line, text, why }]。
 */
const TRAPS = [
  {
    re: /printf[^\n]*%q|%q/,
    why: 'Android 的 mksh **没有** `printf %q`（真机 `printf: bad %q@20`）。' +
         '要转义请用单引号 + sed（见 device-provision.sh 的 squote），或干脆别拼字符串。',
  },
  {
    re: /^\s*local\s+[A-Za-z_][A-Za-z0-9_]*=\(/,
    why: '`local x=(…)` 在 mksh 里是**语法错误**：数组要先 `local x` 再 `x=(…)`。',
  },
  {
    re: /\[\[.*=~/,
    why: '`[[ x =~ re ]]` 在 mksh 里是**语法错误**：用 `case` 的字符类等价的写法。',
  },
  {
    re: /\bx=\(\)/,
    why: '',
  },
  {
    re: /BASH_SOURCE\[/,
    why: '`${BASH_SOURCE[0]}` 只在 bash 里有意义。设备侧脚本可能被 **busybox ash** ' +
         '解析（模块自启时 PATH 前面是 KernelSU 的 busybox，`sh` 就是它），' +
         'ash 对数组下标直接判 `syntax error: bad substitution` —— 2026-09-16 真机事故：' +
         '三个层文件都在、`state.json` 也齐，App 却永远显示「未挂载」，' +
         'service.log 里只有这一行。要用 `$0`；确实需要判断"是否被 source"时，' +
         '让 source 方显式设 `SUNSETLINUX_SOURCED=1`（见 status_json.sh 尾部）。',
  },
  {
    re: /(^|[^\w./-])bash\s+["'$]/,
    quotedSafe: true,
    unless: /(--arg(json)?|--slurpfile|command\s+-v|\bwhich)\s*$/,   // jq 参数名 / 查解释器路径，不是"发起脚本"
    why: '设备侧**没有 bash**（`/system/bin/bash` 不存在）：用 `bash start.sh` 去挂载' +
         '在真机上必然失败（2026-09-16 事故的另一半）。请用 linuxctl.sh 里的 ' +
         '`$SH_BIN`（宿主/CI 是 bash，Android 是 /system/bin/sh）。',
  },
  {
    re: /(^|[^\w./-])sh\s+["'$]/,
    quotedSafe: true,
    unless: /(--arg(json)?|--slurpfile|command\s+-v|\bwhich)\s*$/,
    why: '不要用裸 `sh` 发起设备侧脚本：模块自启的 PATH 里 `sh` 可能是 **busybox ash**，' +
         '解析不了这些脚本，而且"住哪台机器能跑"就交给了 PATH 运气。' +
         '要显式写 `/system/bin/sh`（mksh），能直接执行就优先直接执行（走 shebang）。',
  },
];

function trapScan(rel) {
  const hits = [];
  const lines = readFileSync(join(REPO, rel), 'utf8').split('\n');
  lines.forEach((raw, i) => {
    if (/^\s*#/.test(raw)) return;                    // 注释行：项目里常在这里解释这些坑
    for (const t of TRAPS) {
      if (!t.why) continue;
      const m = t.re.exec(raw);
      if (!m) continue;
      // quotedSafe：命中点落在**双引号字符串里**就不算（那多半是给用户看的提示文案，
      // 例如 log "  sh $BIN_DIR/linuxctl status"）。判据：命中点之前的双引号个数是奇数。
      const at = raw.indexOf(m[0]) + (m[1] ? m[1].length : 0);
      if (t.unless && t.unless.test(raw.slice(0, at))) continue;
      if (t.quotedSafe) {
        const quotes = (raw.slice(0, at).match(/"/g) || []).length;
        if (quotes % 2 === 1) continue;
      }
      hits.push({ line: i + 1, text: raw.trim(), why: t.why });
    }
  });
  return hits;
}

function shellKind(rel) {
  const first = readFileSync(join(REPO, rel), 'utf8').split('\n', 1)[0].trim();
  return first;
}

// ---- nsenter 的**选项面** ---------------------------------------------------
// 设备上的 `/system/bin/nsenter` 是 **toybox**（实测 `--help` 为 Toybox 0.8.12-android），
// 选项表比宿主/CI 的 util-linux **窄**，而且**错一个选项不是降级、是整条命令不执行**：
//
//   | 选项 | 设备实际结果（实测） |
//   |---|---|
//   | `--wd=/path` / `--wd /path` / `-w /path` | `Unknown option 'wd=…'` ← 2026-09-17 真机：attach/exec 必然失败 |
//   | `--mount=/path`（等号） | ✅ 认（`-m=/path` 也认） |
//   | `--mount /path`（空格） | `need -t or =filename` |
//   | `-m -u`（裸 ns 选项） | 只在**同时给了 `-t PID`** 时可用（从该 pid 取 ns） |
//
// cwd 不需要 `--wd`：`chroot NEWROOT` 自己会 chdir 进新根（实测：从 /tmp 起
// `chroot <rootfs> /usr/bin/env -i /bin/pwd` 打印 `/`），而那就是唯一要求。
const NSENTER_LONG = new Set([
  '--all', '--no-fork', '--target', '--cgroup', '--ipc', '--mount',
  '--net', '--pid', '--uts', '--user', '--help',
]);
const NSENTER_SHORT = new Set(['-a', '-F', '-t', '-C', '-i', '-m', '-n', '-p', '-u', '-U', '-h']);
const NSENTER_PATH_OPTS = new Set([
  '--cgroup', '--ipc', '--mount', '--net', '--pid', '--uts', '--user',
  '-C', '-i', '-m', '-n', '-p', '-u', '-U',
]);
const NSENTER_CWD_OPTS = new Set(['-w', '--wd', '--workdir', '--chdir']);

/** 把 `\` 续行拼成"逻辑行"（选项常被拆到下一物理行，逐行扫会漏），返回 {line, text}。 */
function logicalLines(text) {
  const out = [];
  let buf = '';
  let start = 0;
  text.split('\n').forEach((raw, i) => {
    if (buf === '') start = i + 1;
    const cont = /\\\s*$/.test(raw);
    buf += cont ? `${raw.replace(/\\\s*$/, ' ')}` : raw;
    if (!cont) { out.push({ line: start, text: buf }); buf = ''; }
  });
  if (buf) out.push({ line: start, text: buf });
  return out;
}

function nsenterOptionsInText(text) {
  const hits = [];
  for (const { line, text: whole } of logicalLines(text)) {
    if (/^\s*#/.test(whole)) continue;                    // 整行注释
    const re = /\$\{?NSENTER\}?|\bnsenter\b/g;
    let m;
    while ((m = re.exec(whole))) {
      let rest = whole.slice(m.index + m[0].length);
      // `"$NSENTER" …`：变量自己的**收尾引号**要先吃掉，否则下面的 token 匹配一个都进不去
      // （这个洞真出现过：真实代码里全部写成 `"$NSENTER"`，闸门于是空转、变异测试变绿）。
      rest = rest.replace(/^["'](?=\s)/, '');
      let hasTarget = false;
      let sawDashDash = false;   // 是否已经遇到 `--`（toybox 认它：之后才是命令）
      for (let guard = 0; guard < 32; guard++) {
        // 一个 token 可以是"好几段拼起来的"（`--mount="/proc/…/ns/mnt"`）—— 引号内必须继续吃，
        // 否则 token 在 `--mount=` 处被引号截断，同一行后面的 `--wd=` 永远扫不到
        // （第一版就是这么空转的：变异测试全绿，闸门自检才把它揪出来）。
        const tm = /^\s+((?:"[^"]*"|'[^']*'|[^\s"'])+)/.exec(rest);
        if (!tm) break;
        rest = rest.slice(tm[0].length);
        const bare = tm[1].replace(/^["']|["']$/g, '');
        if (bare === '--') { sawDashDash = true; continue; }   // `--` 是终止符：之后交给命令
        if (!bare.startsWith('-')) {
          // 命令开始了。**toybox 的 nsenter 会把命令之后带 `-` 的字面量参数也当成自己的选项**
          // （同二进制实测：`… /bin/echo -l` → `Unknown option 'l'`、
          //  `… /bin/echo --port 3080` → `Unknown option 'port'`）⇒ 整条命令不执行。
          // 2026-09-18 真机因此连坏三处：`dsh start`（尾部 `--port`）、终端 attach/exec、
          // stop 里的 `umount -l/-f`（卸载根本没执行 → "残留挂载"反复出现）。
          // 判据只认**字面量**的 `-x`/`--xx`：`"$@"` 这种不透明参数判不了，也不该猜。
          const looksLikeCommand = /^["']?[A-Za-z0-9_./$@{}-]+["']?$/.test(bare);
          if (!sawDashDash && looksLikeCommand && /(^|\s)["']?--?[A-Za-z]/.test(rest)) {
            hits.push({
              line,
              text: whole.trim().slice(0, 160),
              why: 'nsenter 的命令前必须写 `--`：设备上 nsenter 是 toybox（0.8.12-android 实测），' +
                   '它会把命令之后的字面量选项也当成自己的（`… /bin/echo -l` → `Unknown option \'l\'`）' +
                   '⇒ **整条命令不执行**。正确写法：`nsenter --mount=… --uts=… -- chroot …`；' +
                   'stop 里的 `umount -l/-f` 同理。',
            });
          }
          break;                                          // nsenter 参数到此为止
        }
        const eq = bare.indexOf('=');
        const name = eq === -1 ? bare : bare.slice(0, eq);
        const hasValue = eq !== -1;
        if (NSENTER_CWD_OPTS.has(name)) {
          hits.push({
            line,
            text: whole.trim().slice(0, 160),
            why: '设备的 nsenter 是 **toybox**（0.8.12-android 实测），**没有 cwd 选项**：' +
                 '`--wd=X` / `--wd X` / `-w X` 一律 `Unknown option`（真机原话 ' +
                 '`nsenter: Unknown option \'wd=/data/sunsetlinux/rootfs\'`）⇒ attach/exec **整条不执行**。' +
                 'cwd 不用传：`chroot NEWROOT` 自己会 chdir 进新根（实测 chroot 后 `pwd` = `/`）。',
          });
          break;
        }
        if (!NSENTER_LONG.has(name) && !NSENTER_SHORT.has(name)) {
          hits.push({
            line,
            text: whole.trim().slice(0, 160),
            why: `设备 nsenter（toybox 0.8.12-android，实测 --help）不认选项 ${name}。` +
                 '可用：-a -F -t PID -C -i -m -n -p -u -U（ns 文件写成 =path）。' +
                 '错一个选项 = 整条命令不执行，不是降级。',
          });
          break;
        }
        if (name === '-t' || name === '--target') {
          hasTarget = true;
          if (!hasValue) {
            // `-t 1234`：空格形式的取值也要吃掉，否则 PID 会被当成"命令"，
            // 后面 legit 的 `-m -u` 就会被误报成"命令后的选项"（闸门自检抓到过）。
            const vm = /^\s+((?:"[^"]*"|'[^']*'|[^\s"'])+)/.exec(rest);
            if (vm) rest = rest.slice(vm[0].length);
          }
          continue;
        }
        if (NSENTER_PATH_OPTS.has(name) && !hasValue && !hasTarget) {
          hits.push({
            line,
            text: whole.trim().slice(0, 160),
            why: `ns 参数 ${name} 必须写成**等号**形式（toybox：\`need -t or =filename\`），` +
                 `或先给 \`-t PID\` 再写裸选项：请写成 ${name}=/proc/<pid>/ns/…`,
          });
          break;
        }
      }
    }
  }
  return hits;
}

function nsenterOptionScan(rel) {
  return nsenterOptionsInText(readFileSync(join(REPO, rel), 'utf8'));
}

/**
 * **守护进程不许被前台 spawn**（`spawn_detached` 必须后台化）。
 *
 * 真机事故（2026-09-18，用户截图「点了启动环境，然后就没了，点不了启动 DSH」）：
 * `start.sh` 里原来是 `if spawn_detached …; then uc_ok=1; fi` —— **前台**执行，
 * 父 shell 必须等它结束；而守护链最后的 `entry.sh` **按设计永不退出**
 * （`--no-dsh` 是 `while :; do sleep 1; done`，full 模式 `exec supervise.sh`）。
 * 于是整条链一起挂住：start.sh 不退 → `linuxctl start` 不退 → App 的 su 命令不退 →
 * App 的 `busy` 永久为真 ⇒ 启动区五张卡片全灰、「启动 DSH」的点击被静默丢弃。
 * 设备实证：那条链 20 分钟后仍全部停在 `rt_sigsuspend`，而环境本身 4 秒就 ready 了。
 *
 * 判据：调 `spawn_detached` 的那条**逻辑行**（已合并 `\` 续行）必须以 `&` 或 `& )`
 * 收尾，也就是"父不等它"。宽严取舍的样本见 FOREGROUND_SPAWN_FIXTURES。
 */
function foregroundSpawnInText(text) {
  const hits = [];
  for (const { line, text: whole } of logicalLines(text)) {
    if (/^\s*#/.test(whole)) continue;                        // 整行注释
    const code = whole.replace(/(^|\s)#.*$/, '');              // 行尾注释（本仓注释里到处提 spawn_detached）
    // 只判"看起来像**调用**的"：名字后面跟空白 + 一个不是 `(` 的东西（参数）。
    // 这样 `spawn_detached() {`（定义）、`spawn_""detached`（拼接探针）、
    // `spawn_daemon/spawn_detached（函数改名了？）`（诊断消息里的提及）都不会误报 ——
    // 这三种在真实代码里都出现过（前两个是本轮踩的）。
    if (!/\bspawn_detached\s+[^\s(]/.test(code)) continue;
    if (/spawn_detached\s*\(\s*\)/.test(code)) continue;       // 函数定义
    if (/&\s*\)?\s*$/.test(code)) continue;                    // 已后台：( … & ) 或 … &
    hits.push({
      line,
      text: whole.trim().slice(0, 160),
      why: '守护进程被前台 spawn：父 shell 会一直等它，而 entry.sh 按设计永不退出 ⇒ ' +
           '整条链（start.sh → linuxctl → App 的 su）永不返回，App 的 busy 永久为真、启动区全灰。' +
           '改成 `( spawn_detached … >>"$DAEMON_LOG" 2>&1 </dev/null & )`（见 start.sh 的 spawn_daemon）。',
    });
  }
  return hits;
}

function foregroundSpawnScan(rel) {
  return foregroundSpawnInText(readFileSync(join(REPO, rel), 'utf8'));
}

/** 同上的闸门自检：不许假绿（本项目的老规矩：扫描器自己也得证明会红）。 */
const FOREGROUND_SPAWN_FIXTURES = [
  { bad: true, why: '修复前的原样（前台 spawn 放进 if 条件）',
    text: 'if spawn_detached "$use_setsid" "$UNSHARE" -m -u "$SELF_DIR/start.sh" --inner --make-private >>"$DAEMON_LOG" 2>&1; then\n    uc_ok=1\nfi' },
  { bad: true, why: '续行拆开的前台 spawn（逐行扫会漏）',
    text: 'if spawn_detached "$use_setsid" "$UNSHARE" -m -u --propagation private \\\n        "$SELF_DIR/start.sh" --inner >>"$DAEMON_LOG" 2>&1; then' },
  { bad: true, why: '裸前台调用（不与 if 搭配也要抓）',
    text: 'spawn_detached 1 "$UNSHARE" -m -u "$SELF_DIR/start.sh" --inner' },
  { bad: false, why: '修好后的形态：后台子 shell + 重定向 + & 收尾',
    text: 'spawn_daemon() {\n    ( spawn_detached "$@" >>"$DAEMON_LOG" 2>&1 </dev/null & )\n}' },
  { bad: false, why: '函数定义本身不是调用',
    text: 'spawn_detached() {  # spawn_detached <use_setsid> <cmd> [args...]' },
  { bad: false, why: '注释里提到 spawn_detached',
    text: '# 以前这里是 `if spawn_detached …; then uc_ok=1; fi` —— 前台执行，父 shell 必须等' },
  { bad: false, why: '直接用 & 后台（父也不等）',
    text: 'spawn_detached 1 "$UNSHARE" -m -u "$SELF_DIR/start.sh" --inner >>"$DAEMON_LOG" 2>&1 &' },
  { bad: false, why: '诊断消息里提到函数名（本轮真实误报）',
    text: 'bad "没能从 start.sh 抽出 spawn_daemon/spawn_detached（函数改名了？闸门要跟着改）"' },
  { bad: false, why: '函数名走拼接的探针（本轮真实误报）',
    text: '_fg_probe="spawn_""detached"\n"$_fg_probe" 0 /bin/sh -c \'sleep 3\'' },
];

function foregroundSpawnSelfCheck() {
  const failed = [];
  for (const f of FOREGROUND_SPAWN_FIXTURES) {
    const got = foregroundSpawnInText(f.text).length > 0;
    if (got !== f.bad) {
      failed.push(`${f.bad ? '漏报' : '误报'}：${f.why} → ${JSON.stringify(f.text.slice(0, 80))}`);
    }
  }
  return failed;
}

/**
 * **闸门自检**：闸门自己也得证明"它会红"。
 * 起因：这个 nsenter 扫描器第一版在真实代码上**一直是空转的**（`"$NSENTER"` 的收尾引号
 * 把 token 解析卡死），当轮变异测试全绿 —— 与 §六「能解析 ≠ 函数真的存在」同一类事故。
 * 所以固定几组"必须被抓到 / 必须不被误报"的样本，跑不过就直接判闸门失效（退出 1）。
 */
const NSENTER_FIXTURES = [
  { bad: true, why: '真机原样（带引号的 $NSENTER + --wd=）',
    text: '"$NSENTER" --mount="/proc/$nspid/ns/mnt" --wd="$ROOTFS_DIR" chroot "$ROOTFS_DIR" /bin/true' },
  { bad: true, why: '--wd 空格形式', text: '$NSENTER --wd /data/x /bin/true' },
  { bad: true, why: '-w 短选项', text: 'nsenter -w /data/x /bin/true' },
  { bad: true, why: '未知长选项', text: 'nsenter --fake=1 /bin/true' },
  { bad: true, why: 'ns 参数用空格分隔（toybox: need -t or =filename）',
    text: '"$NSENTER" --mount "/proc/1/ns/mnt" /bin/true' },
  { bad: true, why: '续行拆开的 --wd（逐行扫会漏）',
    text: '"$NSENTER" --mount="/proc/1/ns/mnt" \\\n    --wd=/data/x chroot /data/x /bin/true' },
  { bad: true, why: '命令前没有 `--`（旧写法：设备上必然 `Unknown option`）',
    text: '"$NSENTER" --mount="/proc/$nspid/ns/mnt" --uts="/proc/$nspid/ns/uts" \\\n     chroot "$ROOTFS_DIR" /usr/bin/env -i HOME=/root "$@"' },
  { bad: true, why: 'stop 的 umount -l 少了 `--`（旧写法：卸载根本没执行）',
    text: '"$NSENTER" --mount="/proc/$NSPID/ns/mnt" "$UMOUNT" -l "$target" 2>/dev/null && return 0' },
  { bad: false, why: '修好后的 attach/exec（命令前有 `--`）',
    text: '"$NSENTER" --mount="/proc/$nspid/ns/mnt" --uts="/proc/$nspid/ns/uts" \\\n     -- chroot "$ROOTFS_DIR" /usr/bin/env -i HOME=/root "$@"' },
  { bad: false, why: '修好后的 umount -l（命令前有 `--`）',
    text: '"$NSENTER" --mount="/proc/$NSPID/ns/mnt" -- "$UMOUNT" -l "$target" 2>/dev/null && return 0' },
  { bad: false, why: '-t PID + 裸 ns 选项（toybox 认）', text: 'nsenter -t 1234 -m -u -i -p -n /bin/true' },
  { bad: false, why: '注释里提到 --wd', text: '# 修法：把 --wd="$ROOTFS_DIR" 删掉（toybox 没有 cwd 选项）' },
];

function nsenterSelfCheck() {
  const failed = [];
  for (const f of NSENTER_FIXTURES) {
    const got = nsenterOptionsInText(f.text).length > 0;
    if (got !== f.bad) {
      failed.push(`${f.bad ? '漏报' : '误报'}：${f.why} → ${JSON.stringify(f.text.slice(0, 80))}`);
    }
  }
  return failed;
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
    } else {
      // 环境内脚本（chroot/proot 后的 Ubuntu，bash 一定存在）不参与这层扫描：
      // 它们本来就允许 bash 特性，用设备侧的规则去卡只会逼出假豁免。
      if (Object.prototype.hasOwnProperty.call(ENV_SIDE, rel)) continue;
      for (const h of trapScan(rel)) {
        if (BASH_SOURCE_OK[rel] && /BASH_SOURCE\[/.test(h.text)) continue;
        problems.push(
          `${rel}:${h.line} 命中"mksh 解析得过、真机照死"的陷阱：${h.text}\n       ${h.why}`,
        );
      }
      // nsenter 的选项面（设备是 toybox，宿主/CI 是 util-linux）—— 与上面同源：
      // 宿主上有、设备上没有的选项，本地怎么跑都是绿的。
      for (const h of nsenterOptionScan(rel)) {
        problems.push(
          `${rel}:${h.line} nsenter 选项不被设备（toybox）接受：${h.text}\n       ${h.why}`,
        );
      }
      // 守护进程必须后台化（前台 spawn ⇒ 调用链永不返回 ⇒ App 启动区全灰）
      for (const h of foregroundSpawnScan(rel)) {
        problems.push(`${rel}:${h.line} ${h.why}\n       ${h.text}`);
      }
      if (VERBOSE) notations.push(`${rel} ✅ ${shellKind(rel)}`);
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

// ---- 0) 闸门自检：它会红吗？ -------------------------------------------------
// （先跑这个：如果扫描器本身失效，"全部通过"是假绿 —— 2026-09-17 真发生过一次。）
for (const f of nsenterSelfCheck()) {
  problems.push(`闸门自检失败（nsenter 选项面扫描器失效）：${f}`);
}
for (const f of foregroundSpawnSelfCheck()) {
  problems.push(`闸门自检失败（前台 spawn 扫描器失效）：${f}`);
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
