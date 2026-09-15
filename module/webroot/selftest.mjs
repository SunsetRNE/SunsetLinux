#!/usr/bin/env node
/**
 * module/webroot/selftest.mjs —— WebUI 纯函数回归测试（本机可跑，无需真机）
 *
 * 为什么能离线测：`docs/ksu-webui-api.md` §4 —— WebUI 的核心逻辑
 * （status JSON → 视图模型、日志渲染、版本比对、更新摘要、打码）都是**纯函数**，
 * 不依赖 DOM，也不依赖 ksu 宿主。真机上无法验证的只有"渲染与 ksu.exec 的真实返回"。
 *
 * 用法：node module/webroot/selftest.mjs
 * 退出码：0 全过 / 1 有失败
 *
 * 说明：本测试**从 index.html 里抽取真实的 <script>** 来跑，而不是复制一份实现 ——
 * 改了页面逻辑就会立刻反映到这里（与 runtime/root/selftest.sh 的抽取式设计一致）。
 */
import fs from 'node:fs';
import path from 'node:path';
import { fileURLToPath } from 'node:url';

const here = path.dirname(fileURLToPath(import.meta.url));
const htmlPath = path.join(here, 'index.html');
const html = fs.readFileSync(htmlPath, 'utf8');
const m = html.match(/<script>\n([\s\S]*?)\n<\/script>/);
if (!m) { console.error('FAIL 在 index.html 里找不到 <script> 块'); process.exit(1); }

let pass = 0, fail = 0;
const ok = (c, msg) => { if (c) { pass++; console.log('  \x1b[32mok\x1b[0m   ' + msg); } else { fail++; console.log('  \x1b[31mFAIL\x1b[0m ' + msg); } };

// 用一个最小沙箱执行整个 script：document 为 undefined，正好走"预览模式"、
// 也不会注册 DOMContentLoaded 回调（boot 不会跑）。
const sandbox = { window: {}, console, module: { exports: {} } };
const P = new Function('window', 'document', 'console', 'module', 'globalThis',
  m[1] + '\n;return globalThis.SUNSETLINUX_PURE;')(sandbox.window, undefined, console, sandbox.module, sandbox);
if (!P) { console.error('FAIL 未能取到 SUNSETLINUX_PURE（纯函数区被改名了？）'); process.exit(1); }

console.log('== 转义（日志/命令输出必须转义，否则 < 会破坏页面）==');
ok(P.esc('<script>alert(1)</script>') === '&lt;script&gt;alert(1)&lt;/script&gt;', 'esc 转义尖括号');
ok(P.esc('a & b "c" \'d\'') === 'a &amp; b &quot;c&quot; &#39;d&#39;', 'esc 转义 & " \'');
ok(P.esc(null) === '' && P.esc(undefined) === '', 'esc(null/undefined) 返回空串');

console.log('\n== 令牌打码（App 侧约定：带 ?token= 的完整链接默认打码）==');
const U = 'http://127.0.0.1:3080/?token=OZqp9Fe5V7TiCKHe01jOAn6FdEgCXZ_THoU-DyvWAys';
const mu = P.maskUrl(U);
ok(mu.includes('token=OZqp****WAys') && !mu.includes('V7TiCKHe'), 'maskUrl 打码中段、保留首尾各 4 位');
ok(P.maskUrl('http://127.0.0.1:3080/') === 'http://127.0.0.1:3080/', 'maskUrl 无 token 时原样');
ok(P.maskUrl('') === '' && P.maskUrl(null) === '', 'maskUrl 空值不炸');

console.log('\n== 运行时长 ==');
ok(P.fmtUptime(null) === '—' && P.fmtUptime(undefined) === '—', 'fmtUptime(null) 显示"—"（不是 0 秒）');
ok(P.fmtUptime(45) === '45 秒', 'fmtUptime 秒');
ok(P.fmtUptime(3721) === '1 小时 2 分', 'fmtUptime 时+分');
ok(P.fmtUptime(90061) === '1 天 1 小时', 'fmtUptime 天（不再多出尾空格）');

console.log('\n== JSON 解析（linuxctl 输出可能混日志）==');
ok(P.tryJson('log line\n{"a":1}') && P.tryJson('log line\n{"a":1}').a === 1, 'tryJson 跳过非 JSON 行');
ok(P.tryJson('{bad') === null, 'tryJson 坏 JSON 返回 null');

console.log('\n== status → 视图模型 ==');
const st = { mode: 'root', state: 'running', pid: 1, uptime_sec: 3721,
  dsh: { url: U, base_url: 'http://127.0.0.1:3080', port: 3080, version: '0.1.5-rc.2', healthy: true },
  layers: { base: { version: '24.04.3-l1', size: 140000000, mounted: true },
            runtime: { version: '1.0.0', size: 70000000, mounted: true },
            dsh: { version: '0.1.5-rc.2', size: 90000000, mounted: true } },
  storage: { upper_used: 123456789, upper_total: 8589934592 }, last_error: null };
const v = P.statusView(st);
ok(v.state === 'running' && v.mode === 'root', 'state/mode');
ok(v.urlMasked.includes('****') && !v.urlMasked.includes('V7TiCKHe'), 'url 呈现时已打码');
ok(v.layers.length === 3 && v.layers[0].version === '24.04.3-l1', '三层版本');
ok(v.upperUsedMB === 118, '可写层已用 MB');
ok(P.statusView({}).state === null && P.statusView({}).layers.length === 3, '空对象不炸，仍返回三层占位');

console.log('\n== 日志渲染 ==');
const big = Array.from({ length: 500 }, (_, i) => 'line' + i).join('\n');
const lv = P.logView(big, 400);
ok(lv.lines === 400 && lv.text.startsWith('line100') && lv.truncated === true, '截断到最后 400 行');
ok(P.logView('<b>x</b>').text === '<b>x</b>', 'logView 不转义（由 esc 在插入时负责）');

console.log('\n== 版本比较（三套语义：-l<n> / semver 预发布 / 数字段）==');
const cases = [
  ['0.1.5-rc.2', '0.1.5', -1], ['0.1.5', '0.1.5-rc.2', 1],
  ['24.04.3-l10', '24.04.3-l9', 1], ['24.04.3-l9', '24.04.3-l10', -1],
  ['24.04.3-l9', '24.04.3-l1', 1], ['24.04.2-l99', '24.04.3-l1', -1],
  ['2.0.0', '1.0.0', 1], ['1.0.0', '1.0.0', 0],
  ['0.1.6-alpha.1', '0.1.5-rc.2', 1], ['1.0.0', '1.0.0-alpha', 1],
  ['24.04.3-l1', '24.04.3-l1', 0],
];
for (const [a, b, want] of cases) {
  const got = P.cmpVer(a, b);
  ok(got === want, `cmpVer ${a} vs ${b} = ${got}（期望 ${want}）`);
}

console.log('\n== 更新摘要（验签失败必须显式报错，不得静默）==');
const res = { channels: [
    { id: 'a', name: '官方', ok: true, signature_valid: true, pubkey_fingerprint: 'a583b7b4cc5b2f3b' },
    { id: 'b', name: '可疑', ok: false, signature_valid: false, error: '签名无效：清单与公钥不匹配' }],
  updates: [
    { id: 'dsh', version: '2.0.0', installed: '1.0.0', url: 'x.gz', sha256: 's', transport: 'gzip', size: 1, channel: 'a' },
    { id: 'runtime', version: '1.0.0', installed: null, url: 'y.gz', sha256: 't', transport: 'gzip', size: 2, channel: 'a' }],
  errors: [] };
const us = P.updateSummary(res);
ok(us.errors.length === 1 && us.errors[0].includes('验签失败'), '验签失败进 errors');
ok(us.updates.length === 2, '两个可更新项');
ok(us.updates[0].label === '1.0.0 → 2.0.0', '升级 label');
ok(us.updates[1].label === '安装 1.0.0' && us.updates[1].installed === null, '首次安装 label');
ok(P.updateSummary({ channels: [], updates: [] }).warnings.length === 1, '无频道给可读提示');
ok(P.updateSummary({ channels: [{ id: 'a', signature_valid: true, ok: true }], updates: [], errors: [] })
     .warnings.some(w => w.includes('最新')), '已是最新给提示');

console.log('\n== 回滚模型 ==');
const rm = P.rollbackModel({ installed: { base: ['24.04.3-l1'], runtime: ['1.0.0'], dsh: ['0.1.5-rc.1', '0.1.5-rc.2'] },
                             active: { base: '24.04.3-l1', runtime: '1.0.0', dsh: '0.1.5-rc.2' } });
const dsh = rm.find(r => r.id === 'dsh');
ok(rm.length === 3, '三层都列出');
ok(dsh.active === '0.1.5-rc.2' && dsh.rollbackTo === '0.1.5-rc.1', '回滚候选排除当前版本');
ok(rm.find(r => r.id === 'base').rollbackTo === null, '只有一个版本时无回滚候选');

console.log('\n== 配色必须是灰阶（黑白单色板，与 App 一致）==');
{
  const hexes = [...new Set((html.match(/#[0-9A-Fa-f]{6}/g) || []))];
  const bad = hexes.filter((h) => {
    const r = parseInt(h.slice(1, 3), 16), g = parseInt(h.slice(3, 5), 16), b = parseInt(h.slice(5, 7), 16);
    return !(r === g && g === b);
  });
  ok(hexes.length > 0, `页面里找到了 ${hexes.length} 个 hex 颜色`);
  ok(bad.length === 0, bad.length ? `以下颜色不是灰阶：${bad.join(', ')}` : '所有 hex 颜色均为灰阶（R=G=B）');
  // rgba 也只允许白色带透明度（等价于灰阶）
  const rgbas = [...new Set((html.match(/rgba?\([^)]*\)/g) || []))];
  const badRgba = rgbas.filter((c) => {
    const m = c.match(/(\d+)\s*,\s*(\d+)\s*,\s*(\d+)/);
    if (!m) return false;
    return !(m[1] === m[2] && m[2] === m[3]);
  });
  ok(badRgba.length === 0, badRgba.length ? `非灰阶 rgba：${badRgba.join(', ')}` : '所有 rgba 均为灰阶');
  // 旧配色不得残留
  const legacy = ['#34D399', '#FBBF24', '#F87171', '#38BDF8', '#22D3EE', '#0E1626', '#070C16'];
  const left = legacy.filter((c) => html.toUpperCase().includes(c));
  ok(left.length === 0, left.length ? `残留旧配色：${left.join(', ')}` : '无旧彩色 token 残留');
}

console.log('\n== 图标：一律单色内联 SVG，不许出现 emoji ==');
{
  // 为什么立这条：emoji 是**彩色**的（和本页单色板冲突），而且同一串码点在不同 ROM 的
  // emoji 字体下渲染差别很大。图标统一走页首的 sprite（<use href="#i-…">）。
  // 注意：● ○ ✕ ✓ ★ 这些**单色符号**是刻意保留的设计元素（下面「状态不用颜色」那组断言依赖它们），
  // 所以这里只禁真正的 emoji 码点。
  const EMOJI = /[\u{1F000}-\u{1FAFF}\u{2139}\u{2699}\u{26A0}\u{2B06}\u{FE0F}]/gu;
  const found = html.match(EMOJI) || [];
  ok(found.length === 0, found.length ? `index.html 里还有 emoji：${[...new Set(found)].join(' ')}` : '没有 emoji');
  const icons = (html.match(/<use href="#i-[a-z]+"\/>/g) || []).length;
  ok(icons >= 5, `图标走 sprite（当前引用 ${icons} 处）`);
  ok(/<symbol id="i-dash"/.test(html) && /<symbol id="i-upd"/.test(html), 'sprite 里定义了状态/更新等图标');
  ok(!/font-size:17px;line-height:1/.test(html), '旧的 emoji 字号规则已移除（图标改由 .ico 控制尺寸）');
}

console.log('\n== 状态不用颜色：图标 + 文案 ==');
{
  const sv = P.stateView;
  ok(sv('running').cls === 'running' && sv('running').icon === '\u25CF' && sv('running').dot === 'solid', '运行 = 实心点 ●');
  ok(sv('starting').dot === 'hollow' && sv('stopping').dot === 'hollow', '过渡 = 空心点 ○');
  ok(sv('starting').label === '启动中' && sv('stopping').label === '停止中', '过渡文案区分启动/停止');
  ok(sv('stopped').cls === 'stopped' && sv('stopped').dot === 'hollow', '停止 = 暗灰空心');
  ok(sv('error').icon === '\u2715' && sv('error').cls === 'error', '错误 = ✕ 图标 + error 类');
  ok(sv(null).cls === 'stopped' && sv('weird').label === 'weird', '未知状态不炸');
  // 状态语义不得再依赖颜色 token
  ok(!/--ok:|--bad:|--warn:/.test(html), '已移除 --ok/--bad/--warn 彩色 token');
}

console.log('\n== 模块版本与自我更新 ==');
{
  ok(P.normVer('v1.0.0') === '1.0.0' && P.normVer('1.0.0') === '1.0.0', 'normVer 去掉开头的 v');
  const mv = P.moduleView({ version: 'v1.0.0', versionCode: '10000', bin_version: '1.0.0', installed: true });
  ok(mv.versionShort === '1.0.0' && mv.drift === false, 'moduleView：v1.0.0 与 1.0.0 视为一致（不误报漂移）');
  const mv2 = P.moduleView({ version: 'v2.0.0', bin_version: 'v1.0.0' });
  ok(mv2.drift === true, 'moduleView：模块 2.0.0 / 脚本 1.0.0 → 报漂移');
  ok(P.moduleView({}).name === 'SunsetLinux', 'moduleView 空对象给默认名');

  const up = P.moduleUpdateView({ module_installed: 'v1.0.0',
    channels: [{ id: 'a', name: '官方', signature_valid: true }],
    module: { version: '2.0.0', url: 'x.zip', sha256: 's', size: 1, channel_name: '官方' }, errors: [] });
  ok(up.hasUpdate === true && up.update.version === '2.0.0', 'moduleUpdateView：有更新');
  const up2 = P.moduleUpdateView({ module_installed: 'v1.0.0',
    channels: [{ id: 'a', name: '官方', signature_valid: true }], module: null,
    module_latest: { version: '1.0.0' }, errors: [] });
  ok(up2.hasUpdate === false && up2.notes.length === 1, 'moduleUpdateView：已是最新给提示');
  const up3 = P.moduleUpdateView({ channels: [{ id: 'e', name: '可疑', signature_valid: false, error: '签名无效' }], module: null, errors: [] });
  ok(up3.errors.length === 1 && up3.errors[0].includes('验签失败'), 'moduleUpdateView：验签失败必须报错（不静默）');
  const up4 = P.moduleUpdateView({ channels: [{ id: 'a', signature_valid: true }], module: null, errors: ['已启用频道的清单里没有 module 段（发布者尚未提供模块更新）'] });
  ok(up4.errors.length === 1 && !up4.notes.length, 'moduleUpdateView：频道没提供 module 段时给出可读说明');
}

console.log('\n== 结果判定 ==');
ok(P.resultOk({ ok: true }) === true && P.resultOk({ state: 'running' }) === true, '正常');
ok(P.resultOk({ ok: false }) === false && P.resultOk({ state: 'error' }) === false && P.resultOk(null) === false, '失败情形');

console.log('\n=========================================');
console.log(`  通过 ${pass}，失败 ${fail}`);
console.log('=========================================');
process.exit(fail ? 1 : 0);
