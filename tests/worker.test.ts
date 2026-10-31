// Worker 只用 Web 标准 API（Request、Response、crypto.subtle），可以直接在 Node 里测试
import { test } from 'node:test';
import assert from 'node:assert/strict';
import { createHandler, type Env } from '../worker/src/index.ts';

const env: Env = { API_KEY: 'sk-worker-secret', API_BASE_URL: 'https://example.com/v1', MODEL: 'team-model', ACCESS_PASSWORD: 'open-sesame', ALLOWED_ORIGINS: 'https://maxlen727.github.io, http://localhost:5173' };
const PAGES = 'https://maxlen727.github.io';

function setup(reply = '连接成功。') {
  const calls: { url: string; init: RequestInit }[] = [];
  const handler = createHandler((async (url: string, init: RequestInit) => {
    calls.push({ url, init });
    return new Response(JSON.stringify({ choices: [{ message: { content: reply }, finish_reason: 'stop' }] }));
  }) as typeof fetch, 1000);
  const call = (path: string, { method = 'GET', password = 'open-sesame', origin = PAGES as string | null, body, e = env }: { method?: string; password?: string | null; origin?: string | null; body?: unknown; e?: Env } = {}) => {
    const headers: Record<string, string> = { 'Content-Type': 'application/json' };
    if (password) headers.Authorization = 'Bearer ' + password;
    if (origin) headers.Origin = origin;
    return handler.fetch(new Request('https://api.example.workers.dev' + path, { method, headers, body: body ? JSON.stringify(body) : undefined }), e);
  };
  return { calls, call };
}

test('the access password is required and compared exactly', async () => {
  const { call, calls } = setup();
  assert.equal((await call('/api/config', { password: null })).status, 401);
  assert.equal((await call('/api/config', { password: 'open-sesam' })).status, 401);
  assert.equal((await call('/api/test', { method: 'POST', password: 'wrong' })).status, 401);
  assert.equal(calls.length, 0, 'no model call without the password');
  const ok = await call('/api/config');
  assert.equal(ok.status, 200);
  assert.deepEqual(await ok.json(), { configured: true, verified: false, baseURL: '', model: 'team-model', rooms: false });
});

test('fails closed when the deployer forgot the password or the AI settings', async () => {
  const { call } = setup();
  const noPw = await call('/api/config', { e: { ...env, ACCESS_PASSWORD: '' } });
  assert.equal(noPw.status, 503); assert.match((await noPw.json()).error, /ACCESS_PASSWORD/);
  const noKey = await call('/api/config', { e: { ...env, API_KEY: undefined } });
  assert.equal(noKey.status, 503); assert.match((await noKey.json()).error, /AI 接口/);
});

test('only allowed websites get CORS access', async () => {
  const { call } = setup();
  const pre = await call('/api/generate', { method: 'OPTIONS', password: null });
  assert.equal(pre.status, 204);
  assert.equal(pre.headers.get('Access-Control-Allow-Origin'), PAGES);
  assert.match(pre.headers.get('Access-Control-Allow-Headers')!, /Authorization/);
  const foreign = await call('/api/config', { origin: 'https://evil.example' });
  assert.equal(foreign.status, 403);
  assert.equal(foreign.headers.get('Access-Control-Allow-Origin'), null);
  assert.equal((await call('/api/config', { origin: null })).status, 200, 'non-browser clients still need the password');
});

test('generate reuses the shared prompts, never follows redirects, and never echoes the key', async () => {
  const { call, calls } = setup('说得对。\n[[要点:1]]');
  const session = { mode: 'socratic', concept: '机会成本', topic: '代价是什么？', keyPoints: ['放弃的最佳选择', '区别于沉没成本'], roles: [{ id: 'r0', name: '苏珩', title: '追问者', persona: '' }], messages: [{ kind: 'msg', name: '学习者', text: '少玩了一天' }] };
  const res = await call('/api/generate', { method: 'POST', body: { purpose: 'discuss', payload: { session, roleId: 'r0' } } });
  assert.equal(res.status, 200);
  const data = await res.json();
  assert.deepEqual(data, { text: '说得对。', covered: [0], model: 'team-model' });
  assert.equal(calls[0].url, 'https://example.com/v1/chat/completions');
  assert.equal(calls[0].init.redirect, 'manual', 'Workers do not support redirect: error');
  assert.equal((calls[0].init.headers as Record<string, string>).Authorization, 'Bearer sk-worker-secret');
  assert.ok(!JSON.stringify(data).includes('sk-worker-secret'));
  assert.equal((await call('/api/generate', { method: 'POST', body: { purpose: 'nope', payload: {} } })).status, 400);
});

test('a provider redirect is refused instead of followed', async () => {
  const handler = createHandler((async () => new Response(null, { status: 307, headers: { Location: 'https://elsewhere.example' } })) as typeof fetch, 1000);
  const res = await handler.fetch(new Request('https://api.example.workers.dev/api/test', { method: 'POST', headers: { Authorization: 'Bearer open-sesame', Origin: PAGES } }), env);
  assert.equal(res.status, 502);
  assert.match((await res.json()).error, /重定向/);
});

test('rooms: creating needs the access password, joining goes straight to the room with no password', async () => {
  const seen: { url: string; method: string; body: string; upgrade: string | null }[] = [];
  const ROOMS = {
    idFromName: (name: string) => name,
    get: (id: unknown) => ({ fetch: async (req: Request) => { seen.push({ url: req.url, method: req.method, body: req.method === 'POST' ? await req.text() : '', upgrade: req.headers.get('Upgrade') }); return Response.json({ roomId: id, invite: 'i', hostToken: 't', expiresAt: 1 }); } })
  };
  const { call } = setup();
  const withRooms = { ...env, ROOMS };
  assert.equal((await call('/api/rooms', { method: 'POST', body: {}, e: env })).status, 503, 'older deployments say they need updating');
  assert.equal((await call('/api/rooms', { method: 'POST', password: null, body: {}, e: withRooms })).status, 401);
  const created = await call('/api/rooms', { method: 'POST', body: { setup: { mode: 'roundtable' }, hostName: '束束', roomId: 'chosen-by-client' }, e: withRooms });
  assert.equal(created.status, 200);
  const sent = JSON.parse(seen[0].body);
  assert.equal(seen[0].url, 'https://room/init');
  assert.match(sent.roomId, /^[A-Za-z0-9_-]{22}$/);
  assert.notEqual(sent.roomId, 'chosen-by-client', 'the backend picks the room id');
  assert.equal((await call('/api/config', { e: withRooms }).then(r => r.json())).rooms, true);

  const handler = createHandler();
  const ws = (path: string, headers: Record<string, string>) => handler.fetch(new Request('https://api.example.workers.dev' + path, { headers }), withRooms);
  const id = 'a'.repeat(22);
  assert.equal((await ws(`/api/rooms/${id}/ws`, { Origin: PAGES })).status, 426);
  assert.equal((await ws(`/api/rooms/${id}/ws`, { Origin: 'https://evil.example', Upgrade: 'websocket' })).status, 403, 'other websites cannot open a room socket');
  assert.equal((await ws('/api/rooms/short/ws', { Origin: PAGES, Upgrade: 'websocket' })).status, 404);
  const ok = await ws(`/api/rooms/${id}/ws`, { Origin: PAGES, Upgrade: 'websocket' });
  assert.equal(ok.status, 200);
  assert.deepEqual(seen.at(-1), { url: 'https://room/ws', method: 'GET', body: '', upgrade: 'websocket' });
});

test('worker/ stays self-contained so the Deploy to Cloudflare button can build it alone', async () => {
  const { readFileSync, readdirSync, statSync } = await import('node:fs');
  const { join, resolve, dirname } = await import('node:path');
  const { vendorFiles, expected } = await import('../scripts/sync-worker.ts');
  for (const [source, copy] of vendorFiles()) {
    assert.equal(readFileSync(copy, 'utf8'), expected(source), `${copy} 已过期，请运行 npm run sync:worker`);
  }
  // 子目录外的文件在按钮部署时不存在
  const workerDir = resolve('worker');
  const walk = (dir: string): string[] => readdirSync(dir).flatMap(f => statSync(join(dir, f)).isDirectory() ? walk(join(dir, f)) : [join(dir, f)]);
  for (const file of walk(join(workerDir, 'src'))) {
    for (const [, spec] of readFileSync(file, 'utf8').matchAll(/from '(\.[^']+)'/g)) {
      assert.ok(resolve(dirname(file), spec).startsWith(workerDir + '/'), `${file} 引用了 worker/ 之外的 ${spec}`);
    }
  }
  // 部署页按 .dev.vars.example 提示填写加密变量，这里只能出现真正的机密
  const keys = readFileSync(join(workerDir, '.dev.vars.example'), 'utf8').split('\n').filter(Boolean).map(l => l.split('=')[0]);
  assert.deepEqual(keys, ['API_KEY', 'API_BASE_URL', 'MODEL', 'ACCESS_PASSWORD']);
});
