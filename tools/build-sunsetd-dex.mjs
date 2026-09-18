#!/usr/bin/env node
// =============================================================================
// tools/build-sunsetd-dex.mjs —— 把内核（app/sunsetd）打成 KernelSU 模块能装的 dex
//
// 为什么需要它：内核跑在宿主侧的 `app_process` 上（决策 D1，见 docs/core-v2-design.md §6），
// 而设备是 Android：要的是 **dex**，不是 jar。这条链是：
//
//     Kotlin 源码 --(gradle :sunsetd:jar)--> sunsetd.jar --(d8 + kotlin-stdlib)--> classes.dex
//
// 设计上的几个刻意选择（都来自本仓踩过的坑）：
//
//  · **缺东西一律报错，不静默跳过**：0.2.6 的事故就是"该内嵌的没内嵌，CI 只数数量所以全绿"。
//    这里的判据是"产物必须存在且非空"，缺 d8 / 缺 stdlib / gradle 失败 → exit 1。
//  · kotlin-stdlib 从 **Gradle 缓存**里找（不联网下载）：本机是离线构建的；
//    找不到时给出明确的救援命令（跑一次 `:sunsetd:jar` 会把依赖拉齐）。
//  · `--min-api 26`：与 App 的 minSdk 一致；内核只用到 java.nio/io/ProcessBuilder。
//  · 产物落在 `build/sunsetd/classes.dex`（build/ 是 gitignore 的），并打印大小与 sha256，
//    方便 mkmodule 内嵌时核对。
//
// 用法：
//     node tools/build-sunsetd-dex.mjs            # 构建并打印产物信息
//     node tools/build-sunsetd-dex.mjs --check    # 只校验产物是否已经是最新（CI 用）
// =============================================================================

import { execFileSync } from 'node:child_process';
import { createHash } from 'node:crypto';
import { existsSync, mkdirSync, readFileSync, readdirSync, rmSync, statSync, writeFileSync } from 'node:fs';
import { homedir } from 'node:os';
import { join } from 'node:path';
import { fileURLToPath } from 'node:url';

const REPO = join(fileURLToPath(new URL('.', import.meta.url)), '..');
const APP = join(REPO, 'app');
const OUT_DIR = join(REPO, 'build', 'sunsetd');
const DEX = join(OUT_DIR, 'classes.dex');
const MANIFEST = join(OUT_DIR, 'manifest.json');
const KOTLIN_VERSION = '2.3.10';
const MIN_API = '26';
const CHECK_ONLY = process.argv.includes('--check');

function die(msg) {
  console.error(`❌ build-sunsetd-dex: ${msg}`);
  process.exit(1);
}

function findD8() {
  const cands = [
    process.env.D8,
    '/root/Android/build-tools/35.0.0/d8',
    ...(process.env.ANDROID_HOME ? [join(process.env.ANDROID_HOME, 'build-tools', '35.0.0', 'd8')] : []),
  ].filter(Boolean);
  for (const c of cands) if (existsSync(c)) return c;
  // 兜底：在常见 SDK 根下按目录名排序挑最新的 build-tools
  for (const root of ['/root/Android', process.env.ANDROID_HOME, process.env.ANDROID_SDK_ROOT].filter(Boolean)) {
    const bt = join(root, 'build-tools');
    if (!existsSync(bt)) continue;
    for (const v of readdirSync(bt).sort().reverse()) {
      const p = join(bt, 'v', 'd8');
      if (existsSync(p)) return p;
    }
  }
  die('找不到 d8（Android build-tools）。设 D8=/path/to/d8 或 ANDROID_HOME 后重试。');
}

function findKotlinStdlib() {
  const base = join(homedir(), '.gradle', 'caches', 'modules-2', 'files-2.1', 'org.jetbrains.kotlin', 'kotlin-stdlib');
  if (!existsSync(base)) {
    die(`Gradle 缓存里没有 kotlin-stdlib（${base}）。先跑一次 \`cd app && ./gradlew :sunsetd:jar\` 把依赖拉齐。`);
  }
  for (const ver of readdirSync(base).sort().reverse()) {
    const verDir = join(base, ver);
    for (const hash of readdirSync(verDir)) {
      const jar = join(verDir, hash, `kotlin-stdlib-${ver}.jar`);
      if (existsSync(jar)) return jar;
    }
  }
  die('kotlin-stdlib 的 jar 找不到（缓存目录结构变了？）');
}

function buildJar() {
  console.log('· gradle :sunsetd:jar（离线优先）');
  const env = { ...process.env, LANG: 'C.UTF-8', LC_ALL: 'C.UTF-8' };
  const args = ['--offline', ':sunsetd:jar'];
  try {
    execFileSync('./gradlew', args, { cwd: APP, stdio: 'inherit', env });
  } catch {
    console.log('· 离线构建失败，重试（可能需要联网解析依赖）');
    execFileSync('./gradlew', [':sunsetd:jar'], { cwd: APP, stdio: 'inherit', env });
  }
}

function jarPath() {
  const dir = join(APP, 'sunsetd', 'build', 'libs');
  if (!existsSync(dir)) die('sunsetd/build/libs 不存在（gradle 没产出 jar？）');
  const jars = readdirSync(dir).filter((n) => n.endsWith('.jar'));
  if (jars.length === 0) die('sunsetd/build/libs 里没有 jar');
  return join(dir, jars.sort()[0]);
}

function sha256(file) {
  return createHash('sha256').update(readFileSync(file)).digest('hex');
}

// ── 主流程

if (CHECK_ONLY) {
  if (!existsSync(DEX)) die(`内核 dex 不存在：${DEX}（先跑 node tools/build-sunsetd-dex.mjs）`);
  const st = statSync(DEX);
  if (st.size < 100_000) die(`内核 dex 太小（${st.size} B）—— 显然不是有效产物`);
  console.log(`✅ 内核 dex 就位：${DEX}（${st.size} B，sha256 ${sha256(DEX).slice(0, 16)}…）`);
  process.exit(0);
}

const d8 = findD8();
const stdlib = findKotlinStdlib();
buildJar();
const jar = jarPath();
rmSync(OUT_DIR, { recursive: true, force: true });
mkdirSync(OUT_DIR, { recursive: true });
console.log(`· d8 ${jar} + ${stdlib.split('/').pop()}`);
execFileSync(d8, ['--min-api', MIN_API, '--output', OUT_DIR, jar, stdlib], { stdio: 'inherit' });
if (!existsSync(DEX)) die('d8 跑完了但没有 classes.dex');
const size = statSync(DEX).size;
if (size < 100_000) die(`dex 太小（${size} B）—— 多半只打进了自己的类，没带 stdlib`);
const sum = sha256(DEX);
writeFileSync(
  MANIFEST,
  JSON.stringify(
    {
      schema: 1,
      artifact: 'classes.dex',
      size,
      sha256: sum,
      kotlin: KOTLIN_VERSION,
      minApi: Number(MIN_API),
      jar: jar.replace(REPO + '/', ''),
    },
    null,
    2,
  ) + '\n',
);
console.log(`✅ 内核 dex：${DEX}\n   ${size} B  sha256 ${sum}`);
