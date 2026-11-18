// 原生安卓版“直接连接”模式用的 JS 打包文件（native/app/src/main/assets/sibian-ai.js）。
// 它由 server/ai.ts 打包而来：改了提示词却没重新打包，这里会失败，运行 npm run bundle:native。
import { test } from 'node:test';
import assert from 'node:assert/strict';
import { readFileSync } from 'node:fs';
import { runInNewContext } from 'node:vm';
import { BUNDLE, bundle } from '../scripts/bundle-native-ai.ts';
import { buildRequest, finalize } from '../server/ai.ts';
import { readFixture } from '../scripts/native-fixtures.ts';

test('the native AI bundle is up to date with server/ai.ts', async () => {
  assert.equal(
    readFileSync(BUNDLE, 'utf8'),
    await bundle(),
    '打包文件过期了，运行 npm run bundle:native',
  );
});

// 在一个只有语言本身的环境里运行（没有 fetch、URL、定时器），和安卓的 JS 引擎一样
function load() {
  const sandbox: { sibian?: { run: (name: string, args: string) => string } } = {};
  runInNewContext(readFileSync(BUNDLE, 'utf8'), sandbox);
  const run = (name: string, ...args: unknown[]) =>
    JSON.parse(sandbox.sibian!.run(name, JSON.stringify(args))) as { ok?: unknown; error?: string };
  return run;
}

test('the bundle builds the same requests and parses output the same way as the backend', () => {
  const run = load();
  // 用网页版剧本里真实的一步：开始讨论、说了一句之后发给 AI 的快照
  const scripts = JSON.parse(readFixture('flow-scripts.json')) as {
    ops: { op: string }[];
    steps: { snapshot: { roles: { id: string }[] } | null }[];
  }[];
  const first = scripts[0];
  const at = first.ops.findIndex(o => o.op === 'send');
  const session = first.steps[at].snapshot!;
  const payload = { session, roleId: session.roles[0].id };
  const built = buildRequest('discuss', payload);
  assert.deepEqual(run('buildRequest', 'discuss', payload).ok, JSON.parse(JSON.stringify(built)));
  assert.deepEqual(
    run('buildRequest', 'test').ok,
    JSON.parse(JSON.stringify(buildRequest('test'))),
  );
  const result = { text: '时间也有稀缺性。\n[[要点:2]]', model: 'm1' };
  assert.deepEqual(
    run('finalize', built, result).ok,
    JSON.parse(JSON.stringify(finalize(built, result))),
  );
});

test('the bundle reports failures with the backend wording instead of throwing', () => {
  const run = load();
  const bad = run('buildRequest', 'nope', {});
  assert.equal(typeof bad.error, 'string');
  assert.ok(bad.error!.length > 0);
  assert.equal(run('missing').error, '未知的调用。');
  // 没有 URL、fetch 这些接口也能运行
  assert.equal(run('buildRequest', 'test').error, undefined);
});
