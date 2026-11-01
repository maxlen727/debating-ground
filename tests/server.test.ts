import { test } from 'node:test';
import assert from 'node:assert/strict';
import type { AddressInfo } from 'node:net';
import { createServer } from '../server/server.ts';

test('user API stays isolated in memory; generation uses chosen provider and rejects foreign origins', async t => {
  let upstream: { url: unknown; options: RequestInit } | undefined;
  const server = createServer({ fetchImpl: (async (url: unknown, options: RequestInit) => { upstream = { url, options }; return new Response(JSON.stringify({ choices: [{ message: { content: '连接成功。' }, finish_reason: 'stop' }] })); }) as typeof fetch });
  await new Promise<void>(resolve => server.listen(0, '127.0.0.1', resolve));
  t.after(() => new Promise<void>(resolve => { server.close(() => resolve()); server.closeAllConnections(); }));
  const origin = `http://127.0.0.1:${(server.address() as AddressInfo).port}`;
  let cookie = '';
  async function call(url: string, method = 'GET', body?: unknown, headers: Record<string, string> = {}) {
    const r = await fetch(origin + url, { method, headers: { 'Content-Type': 'application/json', 'X-Sibian-Client': 'web', Origin: origin, Cookie: cookie, ...headers }, body: body ? JSON.stringify(body) : undefined });
    const set = r.headers.get('set-cookie'); if (set) cookie = set.split(';')[0];
    return { status: r.status, body: await r.json() };
  }
  assert.equal((await call('/api/test', 'POST')).status, 409);
  const configured = await call('/api/config', 'POST', { baseURL: 'https://example.com/v1', apiKey: 'test-secret-only', model: 'my-model' });
  assert.equal(configured.status, 200); assert.equal(configured.body.configured, true); assert.ok(!JSON.stringify(configured).includes('test-secret-only'));
  assert.equal((await call('/api/test', 'POST', {})).body.text, '连接成功。');
  assert.equal(upstream!.url, 'https://example.com/v1/chat/completions');
  assert.equal((upstream!.options.headers as Record<string, string>).Authorization, 'Bearer test-secret-only');
  assert.equal((await call('/api/generate', 'POST', { purpose: 'nope', payload: {} })).status, 400);
  assert.equal((await call('/api/config', 'GET', null, { Origin: 'https://foreign.example' })).status, 403);
  const isolated = await fetch(origin + '/api/config', { headers: { 'X-Sibian-Client': 'web' } });
  assert.equal((await isolated.json()).configured, false);
  assert.equal((await call('/api/config', 'DELETE')).body.configured, false);
  assert.equal((await call('/api/test', 'POST')).status, 409);
});

test('static files cannot escape the build folder', async t => {
  const server = createServer({ root: '/nonexistent-dist' });
  await new Promise<void>(resolve => server.listen(0, '127.0.0.1', resolve));
  t.after(() => new Promise<void>(resolve => { server.close(() => resolve()); server.closeAllConnections(); }));
  const origin = `http://127.0.0.1:${(server.address() as AddressInfo).port}`;
  assert.notEqual((await fetch(origin + '/%2e%2e/package.json')).status, 200);
  assert.equal((await fetch(origin + '/')).status, 503);
});

test('generate can stream as server-sent events when the page asks for it', async t => {
  const server = createServer({ fetchImpl: (async () => new Response(
    `data: ${JSON.stringify({ choices: [{ delta: { content: '先想想' } }] })}\n\ndata: ${JSON.stringify({ choices: [{ delta: { content: '代价。\n[[要点:1]]' }, finish_reason: 'stop' }] })}\n\ndata: [DONE]\n\n`,
    { headers: { 'Content-Type': 'text/event-stream' } })) as typeof fetch });
  await new Promise<void>(resolve => server.listen(0, '127.0.0.1', resolve));
  t.after(() => new Promise<void>(resolve => { server.close(() => resolve()); server.closeAllConnections(); }));
  const origin = `http://127.0.0.1:${(server.address() as AddressInfo).port}`;
  const h = { 'Content-Type': 'application/json', 'X-Sibian-Client': 'web', Origin: origin };
  const conf = await fetch(origin + '/api/config', { method: 'POST', headers: h, body: JSON.stringify({ baseURL: 'https://example.com/v1', apiKey: 'k-secret', model: 'm' }) });
  const cookie = conf.headers.get('set-cookie')!.split(';')[0];
  const session = { mode: 'socratic', concept: '机会成本', topic: '代价？', keyPoints: ['放弃的最佳选择', '沉没成本'], roles: [{ id: 'r0', name: '苏珩', title: '', persona: '' }], messages: [] };
  const res = await fetch(origin + '/api/generate', { method: 'POST', headers: { ...h, Cookie: cookie, Accept: 'text/event-stream' }, body: JSON.stringify({ purpose: 'discuss', payload: { session, roleId: 'r0' }, stream: true }) });
  assert.match(res.headers.get('content-type')!, /text\/event-stream/);
  const text = await res.text();
  assert.match(text, /event: delta\ndata: \{"t":"先想想"\}/);
  assert.ok(!text.includes('[[要点'), 'markers never reach the page');
  assert.ok(!text.includes('k-secret'));
  const done = JSON.parse(text.split('event: done\ndata: ')[1]);
  assert.deepEqual([done.text, done.covered], ['先想想代价。', [0]]);
  // 复盘这类 JSON 用途不流式
  const review = await fetch(origin + '/api/generate', { method: 'POST', headers: { ...h, Cookie: cookie }, body: JSON.stringify({ purpose: 'prepare', payload: { concept: 'x', mode: 'socratic' }, stream: true }) });
  assert.match(review.headers.get('content-type')!, /application\/json/);
});

test('iFlytek keys typed in settings stay in server memory and are never echoed back', async t => {
  const server = createServer({});
  await new Promise<void>(resolve => server.listen(0, '127.0.0.1', resolve));
  t.after(() => new Promise<void>(resolve => { server.close(() => resolve()); server.closeAllConnections(); }));
  const origin = `http://127.0.0.1:${(server.address() as AddressInfo).port}`;
  let cookie = '';
  async function call(url: string, method = 'GET', body?: unknown) {
    const r = await fetch(origin + url, { method, headers: { 'Content-Type': 'application/json', 'X-Sibian-Client': 'web', Origin: origin, Cookie: cookie }, body: body ? JSON.stringify(body) : undefined });
    const set = r.headers.get('set-cookie'); if (set) cookie = set.split(';')[0];
    return { status: r.status, text: await r.text() };
  }
  assert.match((await call('/api/config')).text, /"ttsConfigured":false/);
  assert.equal((await call('/api/tts', 'POST', { text: '你好' })).status, 503);
  assert.equal((await call('/api/tts/config', 'POST', { appId: 'a', apiKey: 'only-key' })).status, 400, 'all three are needed');
  const saved = await call('/api/tts/config', 'POST', { appId: 'a', apiKey: 'xf-key-1', apiSecret: 'xf-secret-1' });
  assert.equal(saved.status, 200);
  const state = await call('/api/config');
  assert.match(state.text, /"ttsConfigured":true/);
  assert.ok(![saved.text, state.text].some(t => t.includes('xf-key-1') || t.includes('xf-secret-1')));
  assert.equal((await call('/api/tts', 'POST', { text: '长'.repeat(301) })).status, 400, 'one segment at a time');
  assert.match((await call('/api/tts/config', 'POST', {})).text, /"ttsConfigured":false/);
});
