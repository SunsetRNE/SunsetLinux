#!/usr/bin/env node
/**
 * tools/ci-changeset-selftest.mjs —— `ci-changeset.mjs` 的回归。
 *
 * 判定错一格的后果很大，而且**在 CI 里看不出来**：
 *   · 判成"要发布"→ 白烧 35 分钟 + 把 1.3 GB 资产原样重传；
 *   · 判成"不发布"→ 该出的版本永远出不来（用户等一个不存在的更新）。
 * 所以这里把判定当纯函数穷举，并**显式钉住**几条口径：
 *   ① 只改文档/CI ⇒ 全跳（连编译都不做）；
 *   ② 只改模块脚本 ⇒ 模块重打 + **APK 也要重编**（APK 里内嵌了模块 zip）；
 *   ③ 只改模块脚本 ⇒ **离线包不重打**（.bin 的字节与模块无关）；
 *   ④ 版本没变而要求发布 ⇒ **参数非法、直接拒绝**；
 *   ⑤ channel 写错 ⇒ 参数非法；
 *   ⑥ 拿不到上次发布基线（"*"）⇒ 保守当"全都变了"。
 *
 * 用法：node tools/ci-changeset-selftest.mjs   （退出码 0/1）
 */

import { decide, classify } from './ci-changeset.mjs';

let pass = 0, fail = 0;
const ok = (c, msg) => { if (c) { pass++; console.log('  \x1b[32mok\x1b[0m   ' + msg); } else { fail++; console.log('  \x1b[31mFAIL\x1b[0m ' + msg); } };

const BASE = {
  files: [],
  appVersion: '0.3.11',
  moduleVersion: '1.0.31',
  prevAppVersion: '0.3.10',
  prevModuleVersion: '1.0.30',
  channel: 'auto',
  event: 'push',
  forceBuild: false,
  forcePublish: false,
};

const run = (over = {}) => decide({ ...BASE, ...over });

console.log('== 路径归类 ==');
{
  const c1 = classify(['docs/STATUS.md', 'README.md', 'tools/ci-changeset.mjs']);
  ok(!c1.touched.apk && !c1.touched.module && !c1.touched.bundles, '文档/CI 工具 → 三个面都不动');
  const c2 = classify(['module/webroot/index.html']);
  ok(c2.touched.module && !c2.touched.apk && !c2.touched.bundles, '模块 WebUI → 只动"模块"面');
  const c3 = classify(['app/app/src/main/java/x.kt']);
  ok(c3.touched.apk && !c3.touched.module, 'App 代码 → 只动"APK"面');
  const c4 = classify(['tools/offline-bundle/variants.json']);
  ok(c4.touched.apk && c4.touched.bundles, 'variants.json → APK 与离线包都算（它决定 flavor 与内嵌什么）');
  const c5 = classify(['runtime/root/start.sh']);
  ok(c5.touched.module && !c5.touched.bundles, '运行时脚本 → 模块面（不是离线包面）');
}

console.log('\n== ① 只改文档/CI ⇒ 什么都不做 ==');
{
  const r = run({ files: ['docs/STATUS.md', 'README.md', 'testdata/channel/channel.json'],
                  appVersion: '0.3.10', moduleVersion: '1.0.30' });
  ok(r.ok, '判定完成（不是错误）');
  ok(r.plan.build_apks === 'false', '不编译 APK');
  ok(r.plan.build_bundles === 'false', '不重打离线包');
  ok(r.plan.publish === 'false', '不发布');
  ok(/版本没变/.test(r.plan.reason), `理由说明白（${r.plan.reason}）`);
}

console.log('\n== ② 只改模块脚本（且升了模块版本）⇒ 模块重打 + APK 必须重编 ==');
{
  const r = run({ files: ['module/webroot/index.html', 'module/module.prop'],
                  appVersion: '0.3.10', moduleVersion: '1.0.31' });
  ok(r.ok, '判定完成');
  ok(r.plan.build_apks === 'true', '★ APK 要重编（APK 内嵌模块 zip，字节会变）');
  ok(r.plan.apk_bytes_change === 'true', '如实报告"APK 字节会变"');
  ok(r.plan.build_bundles === 'false', '★ 离线包**不**重打（.bin 的字节与模块无关；App 版本也没变）');
  ok(r.plan.publish === 'true', '要发布（模块版本变了）');
  ok(/模块 1\.0\.30→1\.0\.31/.test(r.plan.reason), `理由带上版本变化（${r.plan.reason}）`);
}

console.log('\n== ③ 只改 App ⇒ 编译 + 发布（离线包因为"文件名带 App 版本"也要重打）==');
{
  const r = run({ files: ['app/app/src/main/java/x.kt', 'app/version.properties'] });
  ok(r.plan.build_apks === 'true' && r.plan.publish === 'true', '编译 + 发布');
  ok(r.plan.build_bundles === 'true', 'App 版本变了 ⇒ 离线包重打（见 ③b 的理由）');
}

console.log('\n== ③b App 版本变了 ⇒ 离线包必须重打（文件名带 App 版本）==');
{
  const r = run({ files: ['app/version.properties'] });          // app 0.3.10→0.3.11
  ok(r.plan.build_bundles === 'true', '★ App 版本变了 → 重打离线包（否则新 Release 上一个 .bin 都没有）');
  const r2 = run({ files: ['module/module.prop'], appVersion: '0.3.10', moduleVersion: '1.0.31' });
  ok(r2.plan.build_bundles === 'false', '只有模块版本变 → 离线包仍不重打');
}

console.log('\n== ④ 改了产物相关文件但没升版本 ⇒ 只跑门禁 + 警告 ==');
{
  const r = run({ files: ['runtime/root/linuxctl.sh'],
                  appVersion: '0.3.10', moduleVersion: '1.0.30' });
  ok(r.ok, '不是错误（日常 push 就该只跑门禁）');
  ok(r.plan.publish === 'false', '不发布');
  ok(r.plan.warnings.length === 1 && /版本号没变/.test(r.plan.warnings[0]),
    `给出一条明确警告（${r.plan.warnings[0] || '无'}）`);
}

console.log('\n== ⑤ 参数非法 ⇒ 显式拒绝（退出码 2 的路径）==');
{
  const bad = run({ channel: 'production' });
  ok(!bad.ok && bad.errors.some((e) => /channel/.test(e)), 'channel 非法要拒绝');
  const forced = run({ forcePublish: true, appVersion: '0.3.10', moduleVersion: '1.0.30' });
  ok(!forced.ok && forced.errors.some((e) => /版本都没变/.test(e)),
    '★ 要求发布但版本没变 → 拒绝（别白跑 35 分钟）');
  const badv = run({ appVersion: '' });
  ok(!badv.ok && badv.errors.some((e) => /读不出来/.test(e)), '版本读不出来要拒绝');
  const badev = run({ event: 'schedule' });
  ok(!badev.ok, 'event 非法要拒绝');
}

console.log('\n== ⑥ 拿不到基线（"*"）⇒ 保守：当成全都变了 ==');
{
  const r = run({ files: ['*'], appVersion: '0.3.10', moduleVersion: '1.0.30' });
  ok(r.plan.build_apks === 'true' && r.plan.build_bundles === 'true', '全都重编（宁可多做，不可漏发）');
}

console.log('\n== ⑦ 手动 force_publish（版本没变但确实要重发）==');
{
  const r = run({ forcePublish: true, files: ['docs/x.md'], appVersion: '0.3.10', moduleVersion: '1.0.30' });
  // 版本没变 ⇒ 上面 ⑤ 已判定为非法；这里验证的是"版本变了 + force_publish"仍照发
  ok(!r.ok, '（版本没变时强制发布 → 非法，符合 ⑤）');
  const r2 = run({ forcePublish: true, files: ['app/x.kt'] });
  ok(r2.ok && r2.plan.publish === 'true', '版本变了 + force_publish → 发布');
}

console.log('\n== ⑧ 输出形态（CI 直接消费）==');
{
  const r = run({ files: ['app/x.kt'] });
  for (const k of ['build_apks', 'build_bundles', 'publish', 'channel', 'tag', 'reason', 'touched']) {
    ok(typeof r.plan[k] === 'string', `输出字段 ${k} 是字符串（GITHUB_OUTPUT 只吃标量）`);
  }
  ok(r.plan.tag === 'v0.3.11', 'tag 由 App 版本推');
  ok(r.plan.channel === 'stable', 'channel=auto 在 main 上解析为 stable');
}

console.log('\n=========================================');
console.log(`  通过 ${pass}，失败 ${fail}`);
console.log('=========================================');
process.exit(fail ? 1 : 0);
