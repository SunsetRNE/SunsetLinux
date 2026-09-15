#!/usr/bin/env node
/**
 * 版本比较的**三方一致性**测试。
 *
 * 为什么需要它：项目里有三处独立的版本比较实现，语义必须完全一致，否则会出现
 * 「WebUI 说可更新、linuxctl 说不用」这种自相矛盾；更糟的是发布工具会据此**选错层**。
 *
 *   1) module/webroot/index.html  的 PURE.cmpVer        （模块 WebUI，浏览器侧）
 *   2) runtime/root/update.sh     的 cmp               （设备侧更新路径，Node）
 *   3) tools/channel/common.mjs   的 compareVersions   （发布工具：gen-manifest 用它挑"最新版"）
 *
 * 语义要求（三层叠在一起，必须分开处理，否则一定踩坑）：
 *   - base 层的 `<ubuntu 版本>-l<n>`：l<n> 按**整数**比 —— 字典序会得出 "l10" < "l9" 的错误结论；
 *   - 其余层按 semver：**有预发布后缀的更小**（0.1.5-rc.2 < 0.1.5）；
 *   - 主干按数字段比（04 == 4，10 > 9）。
 *
 * 用法：  node tools/cmp-consistency.mjs
 * 退出码：0 三方一致；1 有不一致（会打印对照表）
 *
 * 实现方式：前两处用**大括号配平从真实文件里抽出函数体**再执行（与
 * `runtime/root/selftest.sh`、`module/webroot/selftest.mjs` 的抽取式做法一致）——
 * 这样测的是**发布的代码**，而不是复制一份实现；改了实现立刻反映。
 */

import { readFileSync } from 'node:fs';
import { fileURLToPath } from 'node:url';
import path from 'node:path';

const ROOT = path.resolve(path.dirname(fileURLToPath(import.meta.url)), '..');
const { compareVersions } = await import(path.join(ROOT, 'tools/channel/common.mjs'));

/** 从文本里按大括号配平抽出一个函数（字符串里的括号也是配平的，安全） */
function extractFn(text, header) {
  const i = text.indexOf(header);
  if (i < 0) throw new Error(`找不到实现：${header}`);
  const j = text.indexOf('{', i);
  let depth = 0;
  for (let k = j; k < text.length; k++) {
    if (text[k] === '{') depth++;
    else if (text[k] === '}') { depth--; if (depth === 0) return text.slice(i, k + 1); }
  }
  throw new Error(`大括号不配平：${header}`);
}

/**
 * 抽出 WebUI 里**全部** `PURE.<name> = function …{…}` 定义。
 *
 * 为什么不是只抽 `cmpVer`：`cmpVer` 依赖 `PURE.normVer`（版本规范化），
 * 只抽一个会得到 `TypeError: PURE.normVer is not a function` ——
 * 那是**测试自身**的缺陷，不是被测代码的问题（我第一次就是这么误报的）。
 * 全量抽取后，以后 cmpVer 再增依赖也不用改这里。
 */
function extractAllPure(text) {
  const re = /PURE\.[A-Za-z_$][\w$]*\s*=\s*function/g;
  const parts = [];
  let m;
  while ((m = re.exec(text)) !== null) {
    const i = m.index;
    let j = text.indexOf('{', i);
    let depth = 0;
    let end = -1;
    for (let k = j; k < text.length; k++) {
      if (text[k] === '{') depth++;
      else if (text[k] === '}') { depth--; if (depth === 0) { end = k; break; } }
    }
    if (end > 0) parts.push(text.slice(i, end + 1));
  }
  if (parts.length === 0) throw new Error('在 index.html 里找不到任何 PURE.* 定义');
  return parts.join('\n');
}

let webCmp, updCmp;
try {
  const webSrc = extractAllPure(
    readFileSync(path.join(ROOT, 'module/webroot/index.html'), 'utf8'));
  const updSrc = extractFn(
    readFileSync(path.join(ROOT, 'runtime/root/update.sh'), 'utf8'), 'function cmp(a, b)');
  // WebUI 的实现挂在 PURE 命名空间上，先声明出来
  webCmp = new Function(`var PURE = {};\n${webSrc}\nreturn PURE.cmpVer;`)();
  updCmp = new Function(`${updSrc}\nreturn cmp;`)();
  if (typeof webCmp !== 'function' || typeof updCmp !== 'function') {
    throw new Error('抽取到了定义，但没有取到可调用的函数');
  }
} catch (error) {
  console.error(`❌ 抽取实现失败：${error.message}`);
  console.error('   提示：函数被改名/改结构时本测试会失败 —— 请同步更新本文件里的抽取方式，而不是绕过。');
  process.exit(2);
}

/** 覆盖三类语义 + 镜像用例 */
const PAIRS = [
  // semver 预发布
  ['0.1.5', '0.1.5-rc.2'], ['0.1.5-rc.2', '0.1.5'],
  ['0.1.5-rc.1', '0.1.5-rc.2'], ['0.1.5-rc.2', '0.1.5-rc.10'],
  ['1.0.0-rc.1', '1.0.0'], ['1.0.0', '1.0.0-rc.1'],
  ['0.1.5', '0.1.6-alpha.1'],
  // base 层的 -l<n>（整数比较）
  ['24.04.3-l1', '24.04.3-l2'], ['24.04.3-l9', '24.04.3-l10'],
  ['24.04.3-l10', '24.04.3-l9'], ['24.04.3-l10', '24.04.3-l10'],
  // 主干数字段
  ['1.0.0', '1.0.1'], ['1.0.1', '1.0.0'], ['2.0.0', '1.9.9'], ['1.0.0', '1.0.0'],
  ['24.04.3-l1', '24.10.1-l1'],
];

const sgn = (v) => (v === 0 ? '=' : v > 0 ? '>' : '<');
let bad = 0;
console.log('  A                  B                   webui  update.sh  channel  一致?');
for (const [a, b] of PAIRS) {
  const w = sgn(webCmp(a, b));
  const u = sgn(updCmp(a, b));
  const c = sgn(compareVersions(a, b));
  const ok = w === u && u === c;
  if (!ok) bad++;
  console.log(`  ${a.padEnd(18)} ${b.padEnd(19)} ${w.padEnd(6)} ${u.padEnd(10)} ${c.padEnd(9)} ${ok ? '✅' : '❌ 不一致'}`);
}

if (bad === 0) {
  console.log(`\n✅ 三方一致（${PAIRS.length} 组用例）`);
  process.exit(0);
}
console.log(`\n❌ 有 ${bad}/${PAIRS.length} 组三方不一致 —— 三处实现必须统一语义。`);
console.log('   注意 gen-manifest 会用 tools/channel 的实现给候选层排序并取 cands[0] 当"最新版"，');
console.log('   所以不一致的后果是：**发布清单可能指向较旧的层**（例如把 l9 当成比 l10 新）。');
process.exit(1);
