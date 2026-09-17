#!/usr/bin/env node
/**
 * tools/ci-changeset.mjs —— 流水线的**唯一判定**：这次推送到底要不要编译、要不要发布、发哪些。
 *
 * ## 为什么需要它
 *
 * 以前 `push main` 一律跑完整条流水线（40 分钟，其中 ⑤ 发布就占 35 分钟）。可真正常见的是
 * **只改了文档/CI/测试**或者**只改了模块脚本**——前者产物一个字节都不会变，后者也只是
 * 少部分产物变。于是：
 *
 *   · 该跳的不跳 ⇒ 白烧 35 分钟 runner + 把 1.3 GB 资产原样重传一遍；
 *   · 该发的漏发 ⇒ 更糟（用户等一个永远不会出现的更新）。
 *
 * 所以判定必须**可测**、**可解释**：纯函数 + 自带回归（`tools/ci-changeset-selftest.mjs`），
 * 输出里永远给一句人话理由，并把它写进 job summary。
 *
 * ## 两条口径（都不是拍脑袋）
 *
 * 1. **版本号是"发布意图"的开关**：只有 `app/version.properties` 或 `module/module.prop` 的版本
 *    变了，才认为这次要对外发布。改了代码却没升版本 → **只跑门禁**并给出警告（不静默发布半个东西）。
 * 2. **APK 的内容依赖比看起来多**：APK 里内嵌 `assets/module/sunsetlinux-module.zip`（bare，255 KB）
 *    与 `assets/offline-bundle.bin`，所以 **模块变了 / 离线包变了 ⇒ 6 个 APK 的字节也会变**
 *    （不是"模块变了只重发模块"）。这条直接决定了重传量，必须如实算出来。
 *
 * ## 用法（CI 里）
 *
 *   node tools/ci-changeset.mjs --changed-file /tmp/changed.txt \
 *        --app-version 0.3.11 --module-version 1.0.31 \
 *        --prev-app-version 0.3.10 --prev-module-version 1.0.30 \
 *        --channel auto --event push --github-output
 *
 * 退出码：0 = 判定完成（含"什么都不用做"）；2 = **参数非法**（显式拒绝，不猜）。
 */

import { appendFileSync, readFileSync } from 'node:fs';

/* ------------------------------------------------------------------ 变更集 */

/**
 * 路径 → 影响面。**判据是"这个文件会不会改变某类产物的字节"**，不是"它在哪个目录"。
 * 每条都对应一次真实的坑，注释里写清。
 */
const GROUPS = [
  {
    name: 'apk',
    // App 的代码/资源/版本、Gradle 接线、组合清单（variants.json 决定 flavor 与内嵌什么）
    match: [/^app\//, /^gradle\//, /^tools\/offline-bundle\//, /^\.github\/workflows\/build-apk\.yml$/],
    why: 'App 源码/资源/版本/组合清单 → APK 字节会变',
  },
  {
    name: 'module',
    // 模块脚本、运行时脚本、随模块分发的素材（profiles 与 layer-spec）
    match: [/^module\//, /^runtime\//, /^rootfs\/profiles\//, /^rootfs\/layer-spec\.sh$/],
    why: '模块/运行时脚本 → 模块 zip 会变（且 APK 内嵌了它，见下）',
  },
  {
    name: 'bundles',
    // 离线包（.bin）的输入：组合定义 + web/base/runtime 清单
    match: [/^tools\/offline-bundle\//, /^rootfs\/profiles\//],
    why: '离线包输入 → .bin 会变',
  },
];

/** 明确"改了也不影响任何产物"的路径（写出来是为了让日志能说清"为什么全跳了"）。 */
const IGNORED = [/^docs\//, /\.md$/, /^LICENSE$/, /^testdata\//, /^tools\/ci-/, /^\.github\/workflows\/(ci|pipeline)\.yml$/];

export function classify(files) {
  const touched = { apk: false, module: false, bundles: false };
  const ignored = [];
  const unknown = [];
  for (const f of files) {
    if (!f) continue;
    let hit = false;
    for (const g of GROUPS) {
      if (g.match.some((r) => r.test(f))) {
        touched[g.name] = true;
        hit = true;
      }
    }
    if (hit) continue;
    if (IGNORED.some((r) => r.test(f))) ignored.push(f);
    else unknown.push(f);
  }
  return { touched, ignored, unknown };
}

/* -------------------------------------------------------------- 判定（纯函数） */

const CHANNELS = ['auto', 'stable', 'beta'];

/**
 * @param {object} cfg
 * @param {string[]} cfg.files            变更文件列表（`*` = "拿不到基线，按全都变了算"）
 * @param {string} cfg.appVersion         本次 app/version.properties（如 0.3.11）
 * @param {string} cfg.moduleVersion      本次 module/module.prop（如 1.0.31）
 * @param {string} cfg.prevAppVersion     上次**已发布**的 App 版本（读线上 index.json）
 * @param {string} cfg.prevModuleVersion  上次已发布的模块版本
 * @param {string} cfg.channel            auto | stable | beta
 * @param {string} cfg.event              push | workflow_dispatch
 * @param {boolean} cfg.forceBuild        忽略变更集，强制编译（手动用）
 * @param {boolean} cfg.forcePublish      忽略"版本没变"，强制发布（手动重发用）
 * @returns {{ok:boolean, errors:string[], plan:object}}
 */
export function decide(cfg) {
  const errors = [];
  const files = cfg.files || [];
  const channel = (cfg.channel || 'auto').trim();
  const event = (cfg.event || 'push').trim();

  // ── 参数校验：宁可红在"参数非法"，也不要白跑 35 分钟或发出半套产物 ──
  if (!CHANNELS.includes(channel)) {
    errors.push(`channel 只能是 auto/stable/beta，实际是 "${channel}"`);
  }
  if (!['push', 'workflow_dispatch'].includes(event)) {
    errors.push(`event 只能是 push/workflow_dispatch，实际是 "${event}"`);
  }
  for (const k of ['appVersion', 'moduleVersion']) {
    if (!/^\d+\.\d+/.test(String(cfg[k] ?? ''))) errors.push(`${k} 读不出来（实际 "${cfg[k]}"）——发布中止`);
  }
  if (!Array.isArray(files)) errors.push('files 必须是数组');

  // "*" = 拿不到上次发布基线（比如线上 index.json 读不到）：**保守**当作"全都变了"。
  const all = files.includes('*');
  const { touched, ignored, unknown } = all
    ? { touched: { apk: true, module: true, bundles: true }, ignored: [], unknown: [] }
    : classify(files);

  const appChanged = cfg.appVersion !== cfg.prevAppVersion;
  const moduleChanged = cfg.moduleVersion !== cfg.prevModuleVersion;
  const versionBumped = appChanged || moduleChanged;

  // ★ 关键一条：APK 内嵌了模块 zip 与离线包 ⇒ 它们变了 APK 的字节也会变。
  const apkBytesChange = touched.apk || touched.module || touched.bundles || versionBumped;
  // 离线包（.bin）：输入没变也不够 —— **文件名里带 App 版本**
  // （index.json 的 `bundle` = `SunsetLinux-<appver>-<组合>.bin`），App 版本一变，
  // 新 Release 上的那个名字就是全新的资产，必须重新产出（哪怕字节与上一版相同）。
  // ★ 这条是实跑判定时发现的：一开始只按 touched.bundles 判，会把新版本的 .bin 漏掉。
  const buildBundles = Boolean(cfg.forceBuild || touched.bundles || appChanged);
  const buildApks = Boolean(cfg.forceBuild || apkBytesChange);

  // 发布意图：版本号变了 = 要发；手动 forcePublish = 要发（重发/补发）
  const publish = Boolean(cfg.forcePublish || versionBumped);
  let publishReason = '';
  if (cfg.forcePublish) publishReason = '手动要求发布（force_publish）';
  else if (versionBumped) {
    publishReason = [appChanged && `App ${cfg.prevAppVersion}→${cfg.appVersion}`,
                      moduleChanged && `模块 ${cfg.prevModuleVersion}→${cfg.moduleVersion}`]
      .filter(Boolean).join('；');
  } else publishReason = '版本没变 ⇒ 只跑门禁（要发布请升版本，或手动 force_publish）';

  // 手动要求发布但版本没变：**显式拒绝**（这是"参数校验"最有用的一条：
  // 白跑一轮 35 分钟的发布、还把同样的资产再传一遍，是纯浪费）
  if (cfg.forcePublish && !versionBumped) {
    errors.push(`要求发布，但 App(${cfg.appVersion}) 与模块(${cfg.moduleVersion}) 的版本都没变 —— ` +
      '要么升版本号，要么别勾 force_publish（重发同一个版本请用 cleanup/重跑上次失败的 job）');
  }

  // 改了"发布相关"的代码却没升版本：**警告**（不是错误：日常 push 本来就该只跑门禁）
  const warns = [];
  if (!versionBumped && (touched.apk || touched.module || touched.bundles)) {
    warns.push('这次改了会影响产物的文件，但版本号没变 ⇒ **不会发布**（门禁照跑）。要发布请升版本号。');
  }
  const resolvedChannel = channel === 'auto' ? 'stable' : channel;
  const plan = {
    build_apks: String(buildApks),
    build_bundles: String(buildBundles),
    publish: String(publish && errors.length === 0),
    app_version: cfg.appVersion,
    module_version: cfg.moduleVersion,
    app_version_changed: String(appChanged),
    module_version_changed: String(moduleChanged),
    channel: resolvedChannel,
    tag: `v${cfg.appVersion}`,
    apk_bytes_change: String(apkBytesChange),
    reason: publish && errors.length === 0
      ? `发布（${publishReason}）：${describe(touched, apkBytesChange)}`
      : `不发布：${publishReason}`,
    // 给 summary/排障用
    touched: Object.entries(touched).filter(([, v]) => v).map(([k]) => k).join(',') || '(无)',
    ignored_count: String(ignored.length),
    unknown_paths: unknown.slice(0, 20).join(','),
    warnings: warns,
  };
  return { ok: errors.length === 0, errors, plan };
}

function describe(touched, apkBytesChange) {
  const parts = [];
  if (apkBytesChange) parts.push('重编 6 个 APK');
  if (touched.bundles) parts.push('重打离线包');
  parts.push('模块 zip');
  return parts.join(' + ');
}

/* ------------------------------------------------------------------ CLI */

function parseArgs(argv) {
  const out = { files: [] };
  for (let i = 2; i < argv.length; i++) {
    const a = argv[i];
    const next = () => argv[++i];
    switch (a) {
      case '--changed-file':
        out.files.push(...readFileSync(next(), 'utf8').split('\n').map((s) => s.trim()).filter(Boolean));
        break;
      case '--changed': out.files.push(next()); break;
      case '--app-version': out.appVersion = next(); break;
      case '--module-version': out.moduleVersion = next(); break;
      case '--prev-app-version': out.prevAppVersion = next(); break;
      case '--prev-module-version': out.prevModuleVersion = next(); break;
      case '--channel': out.channel = next(); break;
      case '--event': out.event = next(); break;
      case '--force-build': out.forceBuild = next() === 'true'; break;
      case '--force-publish': out.forcePublish = next() === 'true'; break;
      case '--github-output': out.githubOutput = true; break;
      case '--json': out.json = true; break;
      default:
        console.error(`未知参数：${a}`);
        process.exit(2);
    }
  }
  return out;
}

function main() {
  const argv = process.argv.slice(2);
  if (argv.includes('--help') || argv.length === 0) {
    console.log('用法：node tools/ci-changeset.mjs --changed-file F --app-version X --module-version Y \\\n' +
      '        [--prev-app-version A] [--prev-module-version M] [--channel auto|stable|beta] \\\n' +
      '        [--event push|workflow_dispatch] [--force-build true|false] [--force-publish true|false] [--github-output]');
    process.exit(0);
  }
  const args = parseArgs(process.argv);
  const res = decide(args);

  for (const w of res.plan.warnings) console.log(`::warning::${w}`);
  if (!res.ok) {
    for (const e of res.errors) console.error(`::error::${e}`);
    console.error(`判定失败（参数非法，未做任何事）`);
    process.exit(2);
  }

  if (args.json) console.log(JSON.stringify(res.plan, null, 2));
  if (args.githubOutput) {
    const f = process.env.GITHUB_OUTPUT;
    if (f) {
      const lines = Object.entries(res.plan)
        .filter(([, v]) => typeof v === 'string')
        .map(([k, v]) => `${k}=${v}`)
        .join('\n');
      appendFileSync(f, `${lines}\n`);
    }
  }
  console.log(`判定：${res.plan.reason}`);
  console.log(`  编译 APK=${res.plan.build_apks} 离线包=${res.plan.build_bundles} 发布=${res.plan.publish}` +
    `（通道 ${res.plan.channel}，tag ${res.plan.tag}）`);
  console.log(`  变更面：${res.plan.touched}；忽略 ${res.plan.ignored_count} 个不产物文件` +
    (res.plan.unknown_paths ? `；未归类：${res.plan.unknown_paths}` : ''));
}

if (import.meta.url === `file://${process.argv[1]}`) main();
