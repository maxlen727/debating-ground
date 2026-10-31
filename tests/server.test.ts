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
