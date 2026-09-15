#!/usr/bin/env node
/**
 * status JSON 契约一致性检查（architecture.md §3.1，**冻结 schema**）
 *
 * 为什么需要它：`linuxctl` 有 root 与 proot **两套独立实现**，Android App 又依赖同一份 schema。
 * 三方任何一处漂移，都会表现为"App 显示异常/打不开界面"这类难查的问题。
 * 这个脚本把 §3.1 变成可执行的断言，而不是靠人眼看文档。
 *
 * 用法：
 *   node tools/contract-check.mjs --cmd 'bash runtime/root/linuxctl.sh status'
 *   node tools/contract-check.mjs --file /tmp/status.json
 *   node tools/contract-check.mjs --file a.json --file b.json      # 多份一起校验
 *
 * 退出码：0 全部通过；1 有违例。
 */

import { execFileSync } from 'node:child_process';
import { readFileSync } from 'node:fs';

// ---- §3.1 冻结 schema 的期望结构 -------------------------------------------
const STATES = ['stopped', 'starting', 'running', 'stopping', 'error'];
const MODES = ['root', 'proot'];

/** 叶子字段的期望类型：'string' | 'number' | 'boolean' | 'null-able' 语义统一见下 */
const SPEC = {
  schema: { type: 'number', const: 1 },
  mode: { type: 'string', enum: MODES },
  state: { type: 'string', enum: STATES },
  pid: { type: 'number-or-null' },
  uptime_sec: { type: 'number-or-null' },
  dsh: {
    type: 'object',
    children: {
      url: { type: 'string-or-null' },
      base_url: { type: 'string-or-null' },
      port: { type: 'number-or-null' },
      version: { type: 'string-or-null' },
      healthy: { type: 'boolean' },
    },
  },
  layers: {
    type: 'object',
    children: {
      base: layerSpec(),
      runtime: layerSpec(),
      dsh: layerSpec(),
    },
  },
  storage: {
    type: 'object',
    children: {
      upper_used: { type: 'number-or-null' },
      upper_total: { type: 'number-or-null' },
    },
  },
  last_error: { type: 'string-or-null' },
};

function layerSpec() {
  return {
    type: 'object',
    children: {
      version: { type: 'string-or-null' },
      size: { type: 'number-or-null' },
      mounted: { type: 'boolean' },
    },
  };
}

// ---- 校验 -------------------------------------------------------------------
const problems = [];
const notes = [];

function typeOk(value, want) {
  switch (want) {
    case 'string-or-null': return value === null || typeof value === 'string';
    case 'number-or-null': return value === null || (typeof value === 'number' && Number.isFinite(value));
    case 'boolean': return typeof value === 'boolean';
    case 'number': return typeof value === 'number' && Number.isFinite(value);
    case 'string': return typeof value === 'string';
    case 'object': return value !== null && typeof value === 'object' && !Array.isArray(value);
    default: return false;
  }
}

function walk(node, spec, path, obj) {
  for (const [key, sub] of Object.entries(spec)) {
    const here = path ? `${path}.${key}` : key;
    if (sub.type === 'object') {
      if (!(key in obj)) { problems.push(`缺少键：${here}`); continue; }
      const v = obj[key];
      if (!typeOk(v, 'object')) { problems.push(`${here} 应为对象，实际 ${JSON.stringify(v)}`); continue; }
      walk(v, sub.children, here, v);
      continue;
    }
    if (!(key in obj)) { problems.push(`缺少键：${here}（契约要求键不可省略，取不到应为 null）`); continue; }
    const v = obj[key];
    if (!typeOk(v, sub.type)) {
      problems.push(`${here} 类型错：期望 ${sub.type}，实际 ${JSON.stringify(v)}（${typeof v}）`);
      continue;
    }
    if (sub.enum && v !== null && !sub.enum.includes(v)) {
      problems.push(`${here} 取值非法："${v}"，允许：${sub.enum.join(' | ')}`);
    }
    if ('const' in sub && v !== sub.const) {
      problems.push(`${here} 应为常量 ${sub.const}，实际 ${JSON.stringify(v)}`);
    }
  }
  // 额外键不算错（允许附加 action/ok/result），但记下来
  for (const key of Object.keys(obj)) {
    if (!(key in spec)) notes.push(`${path ? path + '.' : ''}${key}（附加键，允许）`);
  }
}

function checkObject(obj, label) {
  if (obj === null || typeof obj !== 'object' || Array.isArray(obj)) {
    problems.push(`${label}: 顶层不是 JSON 对象`);
    return;
  }
  walk(obj, SPEC, '', obj);

  // 语义交叉校验（契约里写明的不变量）
  const dsh = obj.dsh ?? {};
  if (typeof dsh.base_url === 'string') {
    const canonical = /^http:\/\/127\.0\.0\.1:\d+$/.test(dsh.base_url);
    if (!canonical) {
      problems.push(`dsh.base_url 不符合规范形式 http://127.0.0.1:<port>（无尾斜杠/无路径/无 query）：${dsh.base_url}`);
    } else if (typeof dsh.port === 'number') {
      const p = Number(dsh.base_url.split(':').pop());
      if (p !== dsh.port) problems.push(`dsh.base_url 的端口(${p}) 与 dsh.port(${dsh.port}) 不一致`);
    }
  }
  if (typeof dsh.url === 'string' && !dsh.url.includes('?token=')) {
    problems.push(`dsh.url 是字符串但不含 ?token=（契约 §3.3：url 必须是带令牌的登录地址，取不到应为 null）：${dsh.url}`);
  }
  if (dsh.url !== null && dsh.url !== undefined && typeof dsh.url === 'string') {
    if (typeof dsh.base_url === 'string' && !dsh.url.startsWith(dsh.base_url)) {
      problems.push(`dsh.url 不是以 dsh.base_url 开头：url=${dsh.url} base_url=${dsh.base_url}`);
    }
  }
  if (obj.state !== 'running' && dsh.healthy === true) {
    problems.push(`state=${obj.state} 但 dsh.healthy=true（不运行不应健康）`);
  }
}

// ---- 入口 -------------------------------------------------------------------
const argv = process.argv.slice(2);
const inputs = [];
for (let i = 0; i < argv.length; i++) {
  if (argv[i] === '--file') inputs.push({ kind: 'file', value: argv[++i] });
  else if (argv[i] === '--cmd') inputs.push({ kind: 'cmd', value: argv[++i] });
  else if (argv[i] === '--help') {
    console.log(readFileSync(new URL(import.meta.url)).toString().split('\n').slice(0, 18).join('\n'));
    process.exit(0);
  }
}
if (inputs.length === 0) {
  console.error('用法：node tools/contract-check.mjs --cmd \'bash runtime/root/linuxctl.sh status\'');
  console.error('      node tools/contract-check.mjs --file a.json [--file b.json ...]');
  process.exit(2);
}

for (const input of inputs) {
  const label = input.kind === 'file' ? input.value : input.value;
  let raw;
  try {
    raw = input.kind === 'file'
      ? readFileSync(input.value, 'utf8')
      : execFileSync('bash', ['-c', input.value], { encoding: 'utf8', stdio: ['ignore', 'pipe', 'pipe'] });
  } catch (error) {
    problems.push(`${label}: 执行/读取失败 — ${error.message.trim().split('\n')[0]}`);
    continue;
  }
  // stdout 可能混有其它内容：从第一个 { 到最后一个 } 取
  const start = raw.indexOf('{');
  const end = raw.lastIndexOf('}');
  if (start === -1 || end === -1) { problems.push(`${label}: 输出里找不到 JSON 对象`); continue; }
  let obj;
  try { obj = JSON.parse(raw.slice(start, end + 1)); }
  catch (error) { problems.push(`${label}: JSON 解析失败 — ${error.message}`); continue; }
  checkObject(obj, label);
}

if (problems.length === 0) {
  console.log(`✅ 契约检查通过（${inputs.length} 份输入，§3.1 全部键与不变量均满足）`);
  if (notes.length) console.log(`   附加键（允许）：${[...new Set(notes)].join(', ')}`);
  process.exit(0);
}
console.log(`❌ 契约检查失败：${problems.length} 处违例`);
for (const p of problems) console.log(`   - ${p}`);
process.exit(1);
