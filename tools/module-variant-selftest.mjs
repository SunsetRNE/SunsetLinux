#!/usr/bin/env node
/**
 * 模块两变体（`full` / `bare`）的**行为级**回归 —— `docs/module-variants.md` 的可执行部分。
 *
 * ## 为什么需要它
 *
 * 决策 2 把模块拆成两个版本，它们的区别**不在文档里，而在 zip 里**：
 *   · `full`（默认名 `sunsetlinux-module-<ver>.zip`）= 自带 DSH 层镜像（`dsh/`）
 *   · `bare`（`…-bare.zip`）= 不带 DSH，靠 `linuxctl dsh install` 一条指令从频道装
 *
 * 这里最危险的失败模式是**静默冒充**：打了不带 DSH 的包、却顶着默认名发出去，
 * 用户装完发现环境起不来（`supervise.sh exit 78` 那个症状），而且没有任何一处报错。
 * 所以本回归同时钉住三件事：
 *   ① 打包规则（full 必须有载荷；bare 必须没有；默认名只给 full）
 *   ② 落地链路（装完真的把 `dsh-<版本>.erofs` 放到 layers/ + 写 etc/dsh-builtin.json）
 *   ③ 一条指令（`linuxctl dsh install` 走**验签过的**频道 → 下载 → 校验 → 安装 → 可回滚）
 *
 * ## 怎么做到"行为级"而不是"看文本"
 *
 * 全程用一个**真的频道**（临时 Ed25519 密钥 + 真的 channel.json.sig + `file://` URL）
 * 在临时 LINUX_HOME 里跑真实脚本。验签走的是线上同一份实现（update.sh 生成的
 * verifier），所以"签名不对就装不上"也是真的在验。
 *
 * ## 用法
 *   node tools/module-variant-selftest.mjs
 * 退出码：0 通过；1 有失败。
 */

import { execFileSync, spawnSync } from 'node:child_process';
import {
  cpSync, existsSync, mkdirSync, mkdtempSync, readFileSync, rmSync, writeFileSync,
} from 'node:fs';
import { tmpdir } from 'node:os';
import { join } from 'node:path';
import { fileURLToPath } from 'node:url';

const REPO = join(fileURLToPath(new URL('.', import.meta.url)), '..');
const FIXTURE = join(REPO, 'testdata/fixtures/erofs-head.bin');
const LAYER_V1 = '9.9.9';    // 模块自带的（内置）版本
const LAYER_V2 = '9.9.10';   // 频道里的更新版本
const MODULE_VER = '9.9.9';  // 打包用的模块版本（与层版本无关，取个好认的值）

let pass = 0;
let fail = 0;
const ok = (m) => { pass++; console.log(`  \x1b[32mok\x1b[0m   ${m}`); };
const bad = (m) => { fail++; console.log(`  \x1b[31mFAIL\x1b[0m ${m}`); };

const sh = (args, opts = {}) => {
  const { env, ...rest } = opts;
  return spawnSync('bash', args, {
    encoding: 'utf8', cwd: REPO, ...rest, env: { ...process.env, ...(env || {}) },
  });
};

/** 生成一个真的 dsh 层压缩产物（内容 = 仓库里的 erofs 头夹具），返回 {gz, shaGz, shaRaw} */
function makeLayer(dir, version) {
  const gz = join(dir, `dsh-${version}.erofs.gz`);
  execFileSync('bash', ['-c', `gzip -c ${JSON.stringify(FIXTURE)} > ${JSON.stringify(gz)}`]);
  const shaGz = execFileSync('bash', ['-c', `sha256sum ${JSON.stringify(gz)} | awk '{print $1}'`], { encoding: 'utf8' }).trim();
  const shaRaw = execFileSync('bash', ['-c', `sha256sum ${JSON.stringify(FIXTURE)} | awk '{print $1}'`], { encoding: 'utf8' }).trim();
  return { gz, shaGz, shaRaw, sizeRaw: 2048 };
}

const zipList = (zip) => {
  const r = spawnSync('unzip', ['-l', zip], { encoding: 'utf8' });
  return r.status === 0 ? r.stdout : '';
};
const zipGet = (zip, member) => {
  const r = spawnSync('unzip', ['-p', zip, member], { encoding: 'utf8' });
  return r.status === 0 ? r.stdout : null;
};

console.log('\n=== 模块两变体（full / bare）行为级回归 ===\n');

const root = mkdtempSync(join(tmpdir(), 'sunsetlinux-modvar-'));
const layers = join(root, 'layers-src');
mkdirSync(layers, { recursive: true });
const l1 = makeLayer(layers, LAYER_V1);
const l2 = makeLayer(layers, LAYER_V2);
// 频道侧的 SHA256SUMS（mkmodule 会从这里取 sha256_raw / size_raw）
writeFileSync(join(layers, 'SHA256SUMS.layers.txt'), `${l1.shaRaw}  dsh-${LAYER_V1}.erofs  2048\n`);

// ── ① 打包规则 ──────────────────────────────────────────────────────────────
console.log('① 打包规则');

const bareZip = join(root, `sunsetlinux-module-${MODULE_VER}-bare.zip`);
let r = sh(['module/mkmodule.sh', '--variant', 'bare', '--version', MODULE_VER, '--out', bareZip]);
if (r.status === 0 && existsSync(bareZip)) ok('bare 变体打包成功');
else bad(`bare 变体打包失败：${r.stderr || r.stdout}`);

const bareList = zipList(bareZip);
if (!/\bdsh\//.test(bareList)) ok('bare 包里没有 dsh/（不夹带 DSH）');
else bad('bare 包里出现了 dsh/ —— "标 bare 却夹带"必须被拦住');

const bareProp = zipGet(bareZip, 'module.prop') || '';
if (/^variant=bare$/m.test(bareProp)) ok('bare 包的 module.prop 写了 variant=bare');
else bad('bare 包的 module.prop 没写 variant=bare（用户看不出来装的是哪一种）');

const fullZip = join(root, `sunsetlinux-module-${MODULE_VER}.zip`);
r = sh(['module/mkmodule.sh', '--variant', 'full', '--version', MODULE_VER, '--dsh-layer', l1.gz,
        '--dsh-sums', join(layers, 'SHA256SUMS.layers.txt'), '--out', fullZip]);
if (r.status === 0 && existsSync(fullZip)) ok('full 变体打包成功');
else bad(`full 变体打包失败：${r.stderr || r.stdout}`);

const fullList = zipList(fullZip);
if (fullList.includes(`dsh/dsh-${LAYER_V1}.erofs.gz`) && fullList.includes('dsh/manifest.json')) {
  ok('full 包里有 dsh 载荷 + manifest.json');
} else {
  bad('full 包里缺 dsh 载荷或 manifest.json');
}

// 默认命名规则（docs/module-variants.md §2.6）：**默认名只给 full**；
// bare 必须带 -bare 后缀。用 SUNSETLINUX_DIST_DIR 把默认输出引到临时目录来验证这一点
// （绝不能在仓库 dist/ 里留一个假版本的模块包 —— Gradle 的内嵌任务会挑到它）。
const namedDir = join(root, 'named');
mkdirSync(namedDir, { recursive: true });
r = sh(['module/mkmodule.sh', '--variant', 'full', '--version', MODULE_VER, '--dsh-layer', l1.gz,
        '--dsh-sums', join(layers, 'SHA256SUMS.layers.txt')], { env: { SUNSETLINUX_DIST_DIR: namedDir } });
const namedFull = join(namedDir, `sunsetlinux-module-${MODULE_VER}.zip`);
if (r.status === 0 && existsSync(namedFull)) ok(`默认命名：full → ${`sunsetlinux-module-${MODULE_VER}.zip`}`);
else bad(`full 的默认产物名不对：${r.stderr || r.stdout}`);

r = sh(['module/mkmodule.sh', '--variant', 'bare', '--version', MODULE_VER], { env: { SUNSETLINUX_DIST_DIR: namedDir } });
const namedBare = join(namedDir, `sunsetlinux-module-${MODULE_VER}-bare.zip`);
if (r.status === 0 && existsSync(namedBare)) ok(`默认命名：bare → sunsetlinux-module-${MODULE_VER}-bare.zip`);
else bad(`bare 的默认产物名不对：${r.stderr || r.stdout}`);
if (!existsSync(join(namedDir, `sunsetlinux-module-${MODULE_VER}.zip`)) || true) {
  // 上面 full 已经产出过默认名，这里只确认 bare **没有**覆盖它（同名即冒充）
  const list = execFileSync('bash', ['-c', `ls -1 ${JSON.stringify(namedDir)}`], { encoding: 'utf8' });
  if (list.includes(`-${MODULE_VER}-bare.zip`)) ok('bare 不会占用默认名（否则就是"冒充默认版本"）');
  else bad('bare 的产物没有带 -bare 后缀');
}

const man = JSON.parse(zipGet(fullZip, 'dsh/manifest.json') || '{}');
if (man.version === LAYER_V1 && man.file === `dsh-${LAYER_V1}.erofs.gz` && man.sha256_gz === l1.shaGz) {
  ok('manifest.json 的版本 / 文件名 / sha256_gz 与载荷一致');
} else {
  bad(`manifest.json 与载荷不一致：${JSON.stringify(man)}`);
}
if (man.sha256_raw === l1.shaRaw && man.size_raw === 2048) ok('manifest.json 带上了 raw 校验值（来自频道 SHA256SUMS）');
else bad(`manifest.json 缺 raw 校验值：sha256_raw=${man.sha256_raw} size_raw=${man.size_raw}`);

// 负例：full 不给层、bare 给层 —— 都必须被拒绝（不许"静默冒充默认版本"）
r = sh(['module/mkmodule.sh', '--variant', 'full', '--version', MODULE_VER, '--out', join(root, 'x1.zip')]);
if (r.status !== 0 && /--dsh-layer/.test(r.stderr || '')) ok('负例：full 不给 --dsh-layer → 拒绝打包');
else bad('full 不给 --dsh-layer 竟然打出来了（会冒充默认版本）');

r = sh(['module/mkmodule.sh', '--variant', 'bare', '--version', MODULE_VER, '--dsh-layer', l1.gz,
        '--out', join(root, 'x2.zip')]);
if (r.status !== 0) ok('负例：bare 给 --dsh-layer → 拒绝打包');
else bad('bare 带载荷竟然打出来了');

// ── ①b 打包/CI 的 zip 清单校验**不许走管道**（pipefail 假红）────────────────────
// 【真事故，0.3.6 首跑】`unzip -l "$OUT" | grep -q module.prop` 在 `set -o pipefail` 下
// 是**偶发假红**：`grep -q` 一命中就退出 → unzip 可能吃 SIGPIPE(141) → 整条管道非 0 →
// 报"zip 里没有 module.prop"，而包里明明有它。同一段代码前一次运行还是绿的。
// 本项目在 linuxctl 里早踩过同款（那里已改成 case 匹配）；这次轮到打包脚本与 CI 断言。
{
  // 只看**非注释行**：注释里正好写着"不要这么写"（那是给后来人看的），不能算违规
  const code = (t) => t.split('\n').filter((l) => !/^\s*#/.test(l)).join('\n');
  const mk = code(readFileSync(join(REPO, 'module/mkmodule.sh'), 'utf8'));
  if (!/unzip -l[^\n|]*\|\s*grep/.test(mk)) {
    ok('mkmodule.sh 的 zip 清单校验没有用管道（读进变量 + case）');
  } else {
    bad('mkmodule.sh 又出现 `unzip -l … | grep`：pipefail 下会偶发假红（见 0.3.6 首跑）');
  }
  for (const f of ['.github/workflows/build-apk.yml', '.github/workflows/ci.yml']) {
    const t = code(readFileSync(join(REPO, f), 'utf8'));
    if (/unzip -l[^\n]*\|\s*grep/.test(t) || /echo "\$list"\s*\|\s*grep/.test(t)) {
      bad(`${f} 里还有 \`… | grep -q\` 形式的 APK 资源断言（pipefail 假红风险）`);
    } else {
      ok(`${f} 的 APK 资源断言用 list_has（不走管道）`);
    }
  }
}

// ── ② 落地链路：dsh builtin ─────────────────────────────────────────────────
console.log('\n② 落地链路（linuxctl dsh builtin）');

const lh = join(root, 'linux-home');
mkdirSync(join(lh, 'layers'), { recursive: true });
const modDir = join(root, 'moddir');
mkdirSync(modDir, { recursive: true });
execFileSync('unzip', ['-q', '-o', fullZip, '-d', modDir]);
// 真机上模块根就有 module.prop；夹具里补一个（dsh info 要读 variant）
writeFileSync(join(modDir, 'module.prop'), 'id=sunsetlinux\nversion=v9.9.9\nvariant=full\n');

const ctl = join(REPO, 'runtime/root/linuxctl.sh');
const env = { LINUX_HOME: lh, LINUXCTL_KERNEL_FS_OVERRIDE: 'erofs' };

r = sh([ctl, 'dsh', 'builtin', '--module-dir', modDir], { env });
let j = {};
try { j = JSON.parse(r.stdout.trim().split('\n').pop()); } catch { /* 下面统一判 */ }
if (j.ok === true && j.action === 'materialized' && j.version === LAYER_V1) {
  ok(`内置 DSH 落地成功（${LAYER_V1}）`);
} else {
  bad(`dsh builtin 失败：rc=${r.status} stdout=${r.stdout} stderr=${r.stderr}`);
}
if (existsSync(join(lh, `layers/dsh-${LAYER_V1}.erofs`))) ok('layers/ 里出现了 dsh-<版本>.erofs');
else bad('layers/ 里没有落地层文件');

const builtinJson = join(lh, 'etc/dsh-builtin.json');
if (existsSync(builtinJson) && /"version":\s*"9\.9\.9"/.test(readFileSync(builtinJson, 'utf8'))) {
  ok('etc/dsh-builtin.json 记下了内置版本（回滚目标的事实源）');
} else {
  bad('etc/dsh-builtin.json 没写或版本不对');
}
const st = existsSync(join(lh, 'etc/state.json')) ? readFileSync(join(lh, 'etc/state.json'), 'utf8') : '';
if (/"dsh":\s*\{[^}]*"version":\s*"9\.9\.9"/.test(st)) ok('state.json 指向内置版本（全新环境开箱可启动）');
else bad('state.json 没有 dsh 记录 —— 全新环境启动时会找不到层');

r = sh([ctl, 'dsh', 'builtin', '--module-dir', modDir], { env });
try { j = JSON.parse(r.stdout.trim().split('\n').pop()); } catch { j = {}; }
if (j.action === 'already') ok('第二次执行是幂等的（不会重复解 200 MB）');
else bad(`dsh builtin 不幂等：${r.stdout}`);

r = sh([ctl, 'dsh', 'info'], { env });
try { j = JSON.parse(r.stdout.trim().split('\n').pop()); } catch { j = {}; }
if (j.builtin?.present === true && j.builtin?.version === LAYER_V1) ok('dsh info 报出内置版本');
else bad(`dsh info 没报内置版本：${r.stdout}`);

// 设备侧构建必须**认**这个事实源并跳过 dsh 阶段：否则"模块自带 DSH"只有安装脚本知道，
// device-provision 会把 dsh 层再建一遍（白等半小时），而设备侧建出来的那份正是 exit 78 的成因。
{
  const dp = join(REPO, 'rootfs/device-provision.sh');
  const dpRaw = readFileSync(dp, 'utf8');
  if (/builtin_dsh_layer[\s\S]{0,120}SUNSETLINUX_FORCE_DSH_BUILD/.test(dpRaw)) {
    ok('device-provision 在模块自带 DSH 时跳过设备侧构建（留了强制重建开关）');
  } else {
    bad('device-provision 没有跳过"模块自带 DSH"的分支 —— 会白建一遍并覆盖内置层');
  }
  const helperFile = join(root, 'dp-helpers.sh');
  writeFileSync(helperFile, execFileSync('bash', ['-c',
    `sed -n '/^builtin_dsh_version()/,/^}/p' ${JSON.stringify(dp)}; sed -n '/^builtin_dsh_layer()/,/^}/p' ${JSON.stringify(dp)}`,
  ], { encoding: 'utf8' }));
  const probe = spawnSync('bash', ['-c',
    `. ${JSON.stringify(join(REPO, 'rootfs/layer-spec.sh'))}; . ${JSON.stringify(helperFile)}; ` +
    `LINUX_HOME=${JSON.stringify(lh)}; printf 'v=%s\\nl=%s\\n' "$(builtin_dsh_version)" "$(builtin_dsh_layer)"`,
  ], { cwd: REPO, encoding: 'utf8' });
  if (/v=9\.9\.9/.test(probe.stdout) && /dsh-9\.9\.9\.erofs/.test(probe.stdout)) {
    ok('device-provision 的两个内置 DSH 读函数能定位到层与版本');
  } else {
    bad(`device-provision 的内置 DSH 读函数结果不对：${probe.stdout} ${probe.stderr}`);
  }
}

// 负例：载荷被改坏（sha256_raw 不符）→ 必须拒绝
const brokenHome = join(root, 'lh-broken');
mkdirSync(join(brokenHome, 'layers'), { recursive: true });
const brokenMod = join(root, 'moddir-broken');
cpSync(modDir, brokenMod, { recursive: true });
const payloadPath = join(brokenMod, `dsh/dsh-${LAYER_V1}.erofs.gz`);
execFileSync('bash', ['-c', `gzip -c ${JSON.stringify(join(REPO, 'testdata/fixtures/squashfs-head.bin'))} > ${JSON.stringify(payloadPath)}`]);
r = sh([ctl, 'dsh', 'builtin', '--module-dir', brokenMod], {
  env: { LINUX_HOME: brokenHome, LINUXCTL_KERNEL_FS_OVERRIDE: 'erofs' },
});
if (r.status !== 0 && /sha256/.test(r.stdout)) ok('负例：载荷被改坏 → sha256 不符，拒绝落地');
else bad(`负例没拦住：rc=${r.status} stdout=${r.stdout}`);

// ── ②a 空间检查不许把 KB 乘成字节（mksh 的算术是 **32 位**）────────────────────
// 【真机事故，2026-09-17】装 full 模块时 customize.sh 报：
//     {"ok":false,"error":"空间不足，无法展开内置 DSH 层",
//      "available_kb":632669468,"needed_kb":634880}
// 明明有 603 GB 空闲，needed 只有 620 MB。根因：mksh（设备侧 /system/bin/sh 就是它）
// 的 `$(( ))` 是 **32 位有符号**整数，`avail_kb * 1024` 直接溢出成负数：
//     632669468 * 1024 = 647853535232 → 32 位环绕 → **-686526464** → 小于 need_b → 报"空间不足"。
// 同款写法在 `update.sh cmd_apply` 里也有一份（`dsh install` 的必经之路），一起修。
// 这里用桩 `df` 复现"空闲巨大"的正常机器：脚本必须**装得下去**（而不是算成负数）。
{
  const r = makeLayer(root, LAYER_V1);
  const hugeHome = join(root, 'lh-huge');
  mkdirSync(join(hugeHome, 'layers'), { recursive: true });
  const stub = join(root, 'fakebin-df');
  mkdirSync(stub, { recursive: true });
  // 桩 df：Available 列给一个"正常大机器"的值（603 GiB 空闲）
  //   `df -k <dir>` 的输出格式：第 2 行第 4 列是 Available(KB)
  writeFileSync(join(stub, 'df'), '#!/bin/sh\nprintf "%s\\n" "Filesystem 1K-blocks Used Available Use% Mounted on" "/dev/block/dm-1 900000000 100000 632669468 12% /data"\n');
  execFileSync('bash', ['-c', `chmod +x ${JSON.stringify(join(stub, 'df'))}`]);

  // 模块目录（带 dsh 载荷）
  const hugeMod = join(root, 'moddir-huge');
  mkdirSync(join(hugeMod, 'dsh'), { recursive: true });
  cpSync(join(modDir, 'dsh'), join(hugeMod, 'dsh'), { recursive: true });
  cpSync(join(modDir, 'module.prop'), join(hugeMod, 'module.prop'));

  // ★ 用 **mksh** 跑（设备侧就是它）：bash 是 64 位，这个 bug 在 bash 下根本复现不了
  const mksh = spawnSync('mksh', ['-c', 'echo ok'], { encoding: 'utf8' });
  const shell = mksh.status === 0 ? 'mksh' : 'bash';
  for (const [avail, expectOk, tag] of [['huge', true, '空闲 603 GB'], ['tiny', false, '空闲 1 MB']]) {
    const st2 = join(root, `fakebin-${avail}`);
    mkdirSync(st2, { recursive: true });
    const availKb = avail === 'huge' ? '632669468' : '1024';
    writeFileSync(join(st2, 'df'), `#!/bin/sh\nprintf "%s\\n" "Filesystem 1K-blocks Used Available Use% Mounted on" "/dev/block/dm-1 900000000 100000 ${availKb} 12% /data"\n`);
    execFileSync('bash', ['-c', `chmod +x ${JSON.stringify(join(st2, 'df'))}`]);
    const home = join(root, `lh-${avail}`);
    mkdirSync(join(home, 'layers'), { recursive: true });
    const out = spawnSync(shell, [ctl, 'dsh', 'builtin', '--module-dir', hugeMod], {
      encoding: 'utf8', cwd: REPO,
      env: { ...process.env, PATH: `${st2}:${process.env.PATH}`, LINUX_HOME: home, LINUXCTL_KERNEL_FS_OVERRIDE: 'erofs' },
    });
    const line = (out.stdout || '').trim().split('\n').pop() || '';
    const json = (() => { try { return JSON.parse(line); } catch { return {}; } })();
    if (expectOk && json.ok === true) ok(`空间检查（${tag}）：装得下去（${shell}）`);
    else if (!expectOk && json.ok !== true && /空间不足/.test(line)) ok(`空间检查（${tag}）：如实拒绝（${shell}）`);
    else bad(`空间检查（${tag}）判断错误（shell=${shell}）：${line || out.stderr}`);
  }
  if (r) { /* 上面 makeLayer 只是复用夹具，不再打包 */ }
}

// ── ②a2 「生效层坏了」时内置那份必须被启用（真机 2026-09-17 的 rc=78 就是这么来的）──
// 真机现场：装 full 模块后，内置的 dsh-0.1.5-rc.2 已经落到 layers/ 了，但 state.json 还指着
// **设备侧自建**的 dsh-0.1.5-rc.1（那份没有 /root/.dsh/profiles/**）→ 每次 start 都用坏层 →
// supervise.sh 永远退 78。而 doctor 里同时出现"内置 DSH 0.1.5-rc.2 已就位"与
// "dsh 层内没有 profile"，用户根本无从下手。
// 规则：**没有记录** 或 **生效层确定缺 profile** → 切到内置；生效层是好的 → 绝不动。
{
  const mkSandbox = (tag, activeHasProfile) => {
    const lh = join(root, `lh-active-${tag}`);
    mkdirSync(join(lh, 'layers'), { recursive: true });
    mkdirSync(join(lh, 'etc'), { recursive: true });
    // 生效层：9.9.8（假的 erofs 夹具，magic 在偏移 1024）
    cpSync(join(REPO, 'testdata/fixtures/erofs-head.bin'), join(lh, 'layers/dsh-9.9.8.erofs'));
    writeFileSync(join(lh, 'etc/state.json'),
      '{"schema":1,"layers":{"dsh":{"version":"9.9.8","file":"dsh-9.9.8.erofs"}}}\n');
    // 桩 dump.erofs：按"哪个层文件"回答有没有 profile
    const fb = join(root, `fakebin-dump-${tag}`);
    mkdirSync(fb, { recursive: true });
    const verdict = activeHasProfile
      ? 'echo "Path : /root/.dsh/profiles/web/package.json"; echo "Size: 1  regular file"; exit 0'
      : 'echo "<E> erofs: read inode failed @ /root/.dsh/profiles/web/package.json"; exit 1';
    writeFileSync(join(fb, 'dump.erofs'),
      `#!/bin/sh\nf=""; for a in "$@"; do case "$a" in *.erofs) f="$a" ;; esac; done\n` +
      `case "$f" in *9.9.8*) ${verdict} ;; *) echo "Path : /root/.dsh/profiles/web/package.json"; exit 0 ;; esac\n`);
    execFileSync('bash', ['-c', `chmod +x ${JSON.stringify(join(fb, 'dump.erofs'))}`]);
    return { lh, fb };
  };

  // ① 生效层缺 profile → 必须切到内置（9.9.9）
  {
    const { lh, fb } = mkSandbox('broken', false);
    const out = spawnSync('mksh', [ctl, 'dsh', 'builtin', '--module-dir', modDir], {
      encoding: 'utf8', cwd: REPO,
      env: { ...process.env, PATH: `${fb}:${process.env.PATH}`, LINUX_HOME: lh, LINUXCTL_KERNEL_FS_OVERRIDE: 'erofs' },
    });
    const json = (() => { try { return JSON.parse((out.stdout || '').trim().split('\n').pop()); } catch { return {}; } })();
    const st = readFileSync(join(lh, 'etc/state.json'), 'utf8');
    if (json.state_updated === true && /"dsh":\s*\{[^}]*"version":\s*"9\.9\.9"/.test(st)) {
      ok('生效层缺 profile 时，内置那份被启用（state.json 切到 9.9.9）');
    } else {
      bad(`生效层坏了却没切到内置：state_updated=${json.state_updated} state=${st.slice(0, 160)}`);
    }
  }
  // ①b 载荷与记录都已在，但 state.json 还指着坏层 → **already 快路径也必须纠正**
  //     （真机现场就是这样：record 有、rc.2 文件也在，只是从没被启用；
  //      如果快路径直接 return，`dsh builtin` 会永远答"already"，环境永远起不来）
  {
    const { lh, fb } = mkSandbox('already', false);
    // 先把内置那份灌好（会产生 dsh-builtin.json + layers/dsh-9.9.9.erofs）
    spawnSync('mksh', [ctl, 'dsh', 'builtin', '--module-dir', modDir], {
      encoding: 'utf8', cwd: REPO,
      env: { ...process.env, PATH: `${fb}:${process.env.PATH}`, LINUX_HOME: lh, LINUXCTL_KERNEL_FS_OVERRIDE: 'erofs' },
    });
    // 再把 state.json 拨回坏的旧层，模拟"装模块前就已经有坏层"
    writeFileSync(join(lh, 'etc/state.json'),
      '{"schema":1,"layers":{"dsh":{"version":"9.9.8","file":"dsh-9.9.8.erofs"}}}\n');
    const out = spawnSync('mksh', [ctl, 'dsh', 'builtin', '--module-dir', modDir], {
      encoding: 'utf8', cwd: REPO,
      env: { ...process.env, PATH: `${fb}:${process.env.PATH}`, LINUX_HOME: lh, LINUXCTL_KERNEL_FS_OVERRIDE: 'erofs' },
    });
    const json = (() => { try { return JSON.parse((out.stdout || '').trim().split('\n').pop()); } catch { return {}; } })();
    const st = readFileSync(join(lh, 'etc/state.json'), 'utf8');
    if (json.action === 'already' && json.state_updated === true && /"dsh":\s*\{[^}]*"version":\s*"9\.9\.9"/.test(st)) {
      ok('载荷已就位（action=already）时仍会纠正生效层（真机现场的入口）');
    } else {
      bad(`already 快路径跳过了启用判断：action=${json.action} state_updated=${json.state_updated}`);
    }
  }
  // ② 生效层是好的 → 绝不动它（用户从频道更新的层不能被模块升级悄悄退回）
  {
    const { lh, fb } = mkSandbox('good', true);
    const out = spawnSync('mksh', [ctl, 'dsh', 'builtin', '--module-dir', modDir], {
      encoding: 'utf8', cwd: REPO,
      env: { ...process.env, PATH: `${fb}:${process.env.PATH}`, LINUX_HOME: lh, LINUXCTL_KERNEL_FS_OVERRIDE: 'erofs' },
    });
    const json = (() => { try { return JSON.parse((out.stdout || '').trim().split('\n').pop()); } catch { return {}; } })();
    const st = readFileSync(join(lh, 'etc/state.json'), 'utf8');
    if (json.state_updated === false && /"dsh":\s*\{[^}]*"version":\s*"9\.9\.8"/.test(st)) {
      ok('生效层是好的时不抢它（用户的频道更新不会被模块退回）');
    } else {
      bad(`好层被无故切走了：state_updated=${json.state_updated} state=${st.slice(0, 160)}`);
    }
  }
}

// ── ②b CI 侧那道「APK 内嵌的必须是 bare」闸门：必须先去掉空白再匹配 ──────────
// 【真事故，0.3.6 首跑】三个 root 变体全红，报"APK 内嵌的模块不是 bare 变体"，
// 而 module.json 明明是 `"variant": "bare"`。原因：Gradle 写出的 JSON 冒号后**有空格**，
// 而闸门按 `"variant":"bare"` 逐字匹配 —— 把**正确**的包判成了错。
// 这类假红最贵：它红在"最像真问题"的那条断言上，会让人去改对的东西。
{
  const problems = [];
  for (const f of ['.github/workflows/build-apk.yml', '.github/workflows/ci.yml']) {
    const t = readFileSync(join(REPO, f), 'utf8');
    // 必须同时具备：去掉空白（tr -d ' \n\t'）+ 去掉空白后的匹配串
    const stripsWs = /tr -d ' \\n\\t'/.test(t);
    const matches = /'"variant":"bare"'/.test(t);
    if (!(stripsWs && matches)) problems.push(`${f}（去空白=${stripsWs} 匹配串=${matches}）`);
  }
  if (problems.length === 0) ok('CI 的 bare 变体闸门会先去空白再匹配（Gradle 写的是 "variant": "bare"）');
  else for (const p of problems) bad(`bare 变体闸门没有去空白：${p} —— 会把正确的包判成假红`);
}

// ── ③ 一条指令：从（验签过的）频道装 DSH ────────────────────────────────────
console.log('\n③ 一条指令（linuxctl dsh install / update.sh install dsh）');
// 造一个**真的**频道：临时密钥对 + 真签名 + file:// URL
const chanDir = join(root, 'channel');
mkdirSync(chanDir, { recursive: true });
execFileSync('node', [join(REPO, 'tools/channel/keygen.mjs'), '--out-dir', chanDir], { stdio: 'pipe' });
cpSync(l2.gz, join(chanDir, `dsh-${LAYER_V2}.erofs.gz`));
const manifest = {
  schema: 1,
  generated_at: '2026-09-17T00:00:00Z',
  layers: [{
    id: 'dsh',
    version: LAYER_V2,
    url: `file://${join(chanDir, `dsh-${LAYER_V2}.erofs.gz`)}`,
    url_gz: `file://${join(chanDir, `dsh-${LAYER_V2}.erofs.gz`)}`,
    sha256: l2.shaGz,
    sha256_gz: l2.shaGz,
    sha256_raw: l2.shaRaw,
    size_raw: 2048,
  }],
};
writeFileSync(join(chanDir, 'channel.json'), JSON.stringify(manifest, null, 2));
execFileSync('node', [join(REPO, 'tools/channel/sign.mjs'), '--key', join(chanDir, 'channel.key'),
  '--in', join(chanDir, 'channel.json')], { stdio: 'pipe' });

const pub = readFileSync(join(chanDir, 'channel.pub'), 'utf8').trim();
mkdirSync(join(lh, 'etc'), { recursive: true });
writeFileSync(join(lh, 'etc/channels.json'), JSON.stringify({
  schema: 1,
  channels: [{ id: 'test', name: '本地测试频道', url: `file://${join(chanDir, 'channel.json')}`, pubkey: pub, enabled: true, priority: 100 }],
}, null, 2));

// ★ 把内置官方频道指到一个**不存在的本地路径**：本回归必须完全离线、确定性。
//   （不这么做的话，真在有网的环境里官方频道会以更高优先级胜出，测的就不是本地频道了。）
const offlineEnv = { ...env, UPDATE_TRANSPORT: 'gzip', SUNSETLINUX_OFFICIAL_CHANNEL_URL: 'file:///nonexistent/official-channel.json' };

r = sh([ctl, 'dsh', 'install'], { env: offlineEnv });
let installOut = r.stdout.trim().split('\n').pop();
try { j = JSON.parse(installOut); } catch { j = {}; }
if (j.ok === true && j.version === LAYER_V2) ok(`一条指令装上了频道里的 ${LAYER_V2}`);
else bad(`dsh install 失败：rc=${r.status}\n     stdout=${r.stdout}\n     stderr=${r.stderr}`);
if (existsSync(join(lh, `layers/dsh-${LAYER_V2}.erofs`))) ok('频道层已落到 layers/');
else bad('频道层没有落盘');
const st2 = readFileSync(join(lh, 'etc/state.json'), 'utf8');
if (/"dsh":\s*\{[^}]*"version":\s*"9\.9\.10"/.test(st2)) ok('state.json 指向新版本');
else bad('装完之后 state.json 没指向新版本');

// 回滚（不带 --to）：必须优先回到**内置版本**
r = sh([ctl, 'rollback', 'dsh'], { env });
try { j = JSON.parse(r.stdout.trim().split('\n').pop()); } catch { j = {}; }
if (j.ok === true && j.to === LAYER_V1) ok('rollback dsh 默认回到内置版本（模块自带的那份）');
else bad(`rollback 没回到内置版本：${r.stdout} ${r.stderr}`);

// 负例：签名对不上 → 一条指令必须失败（验签是硬门，不许静默降级）
const badChan = join(root, 'channel-bad');
cpSync(chanDir, badChan, { recursive: true });
const badManifest = JSON.parse(readFileSync(join(badChan, 'channel.json'), 'utf8'));
badManifest.layers[0].version = `${LAYER_V2}-tampered`;
writeFileSync(join(badChan, 'channel.json'), JSON.stringify(badManifest, null, 2));
// 注意：**不改签名**（模拟"清单被替换"）
const lh3 = join(root, 'lh-sign');
mkdirSync(join(lh3, 'layers'), { recursive: true });
mkdirSync(join(lh3, 'etc'), { recursive: true });
writeFileSync(join(lh3, 'etc/channels.json'), JSON.stringify({
  schema: 1,
  channels: [{ id: 'bad', name: '被改过的频道', url: `file://${join(badChan, 'channel.json')}`, pubkey: pub, enabled: true, priority: 100 }],
}, null, 2));
r = sh([ctl, 'dsh', 'install'], {
  env: { LINUX_HOME: lh3, UPDATE_TRANSPORT: 'gzip', LINUXCTL_KERNEL_FS_OVERRIDE: 'erofs',
         SUNSETLINUX_OFFICIAL_CHANNEL_URL: 'file:///nonexistent/official-channel.json' },
});
if (r.status !== 0 && /没通过校验/.test(r.stdout)) ok('负例：清单被改过（签名不符）→ 一条指令拒绝安装，并说明是验签没过');
else bad(`负例没拦住：rc=${r.status} stdout=${r.stdout}`);

// 内置官方频道：CLI-only 环境（没有 channels.json）也必须能"一条指令"。
// ⚠️ 这里**故意只等到 channels.json 被写出来**（它在任何网络 I/O 之前完成），
//    然后就把进程掐掉 —— 否则会去请求真的官方站点，在没网的环境里挂很久。
const lh4 = join(root, 'lh-official');
mkdirSync(join(lh4, 'layers'), { recursive: true });
sh(['-c', `LINUX_HOME=${JSON.stringify(lh4)} bash ${JSON.stringify(join(REPO, 'runtime/root/update.sh'))} install base`],
  { timeout: 15000 });
const official = join(lh4, 'etc/channels.json');
if (existsSync(official) && /"id":\s*"official"/.test(readFileSync(official, 'utf8'))
    && /sunsetrne\.github\.io\/SunsetLinux\/channel\/channel\.json/.test(readFileSync(official, 'utf8'))) {
  ok('CLI-only 环境会自动补上内置官方频道（一条指令的前提）');
} else {
  bad(`没有补上内置官方频道：${existsSync(official) ? readFileSync(official, 'utf8') : '(文件不存在)'}`);
}

rmSync(root, { recursive: true, force: true });

console.log('\n=========================================');
console.log(`  通过 ${pass}，失败 ${fail}`);
console.log('=========================================');
process.exit(fail === 0 ? 0 : 1);
