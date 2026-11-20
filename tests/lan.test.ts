// 局域网房间（#148）：本机服务开房间，同学从局域网入口用邀请码加入；房间里的 AI 用房主的 API，Key 不出本机
import { test } from 'node:test';
import assert from 'node:assert/strict';
import type { AddressInfo } from 'node:net';
import type http from 'node:http';
import { createLanServer, createServer } from '../server/server.ts';
import { LanRooms, lanAddress } from '../server/lan.ts';
import { isPrivateIPv4 } from '../shared/room.ts';

const SETUP = {
  mode: 'roundtable',
  concept: '机会成本',
  topic: '选择的代价是什么？',
  keyPoints: ['放弃的最高价值', '沉没成本无关', '隐性成本'],
  personas: [
    { name: '林教授', title: '经济学', persona: '' },
    { name: '周老师', title: '心理学', persona: '' },
  ],
  opening: '大家先说说，什么叫机会成本？',
  model: 'm',
};

test('private addresses: only 10/8, 172.16/12 and 192.168/16 count as the local network', () => {
  for (const ip of ['10.0.0.2', '172.16.5.1', '172.31.255.255', '192.168.1.20'])
    assert.equal(isPrivateIPv4(ip), true, ip);
  for (const ip of [
    '8.8.8.8',
    '172.32.0.1',
    '192.169.0.1',
    '127.0.0.1',
    '1.2.3',
    'a.b.c.d',
    '300.1.1.1',
  ])
    assert.equal(isPrivateIPv4(ip), false, ip);
  assert.equal(
    lanAddress({
      lo: [{ family: 'IPv4', address: '127.0.0.1', internal: true }],
      eth: [{ family: 'IPv4', address: '203.0.113.5', internal: false }],
      wlan: [{ family: 'IPv4', address: '192.168.1.20', internal: false }],
    } as unknown as ReturnType<typeof import('node:os').networkInterfaces>),
    '192.168.1.20',
  );
});

const listen = (s: http.Server) =>
  new Promise<string>(resolve =>
    s.listen(0, '127.0.0.1', () => resolve(`127.0.0.1:${(s.address() as AddressInfo).port}`)),
  );
const stop = (s: http.Server) =>
  new Promise<void>(resolve => {
    s.close(() => resolve());
    s.closeAllConnections();
  });

test('LAN room: the host opens it on the local page, a classmate joins over the LAN entry, AI uses the host API', async t => {
  const prompts: string[] = [];
  const rooms = new LanRooms();
  const fetchImpl = (async (_url: unknown, options: RequestInit) => {
    prompts.push(String(options.body));
    return new Response(
      JSON.stringify({
        choices: [{ message: { content: '嘉宾接话。[[要点:1]]' }, finish_reason: 'stop' }],
      }),
    );
  }) as typeof fetch;
  // 局域网入口的地址在测试里也是 127.0.0.1，只是端口不同
  const entry = createLanServer({ rooms, root: '/nonexistent-dist' });
  const entryHost = await listen(entry);
  const local = createServer({ fetchImpl, lan: { rooms, origin: `http://${entryHost}` } });
  const localHost = await listen(local);
  t.after(async () => {
    rooms.closeAll();
    await stop(entry);
    await stop(local);
  });
  let cookie = '';
  const call = async (url: string, method = 'GET', body?: unknown) => {
    const r = await fetch(`http://${localHost}${url}`, {
      method,
      headers: {
        'Content-Type': 'application/json',
        'X-Sibian-Client': 'web',
        Origin: `http://${localHost}`,
        Cookie: cookie,
      },
      body: body ? JSON.stringify(body) : undefined,
    });
    const set = r.headers.get('set-cookie');
    if (set) cookie = set.split(';')[0];
    return { status: r.status, body: (await r.json()) as Record<string, unknown> };
  };
  assert.equal((await call('/api/config')).body.lan, `http://${entryHost}`);
  // 没连 API 不能开房间：房间里的 AI 要用它
  assert.equal((await call('/api/rooms', 'POST', { setup: SETUP, hostName: '房主' })).status, 409);
  await call('/api/config', 'POST', {
    baseURL: 'https://example.com/v1',
    apiKey: 'host-secret-key',
    model: 'm',
  });
  const created = await call('/api/rooms', 'POST', { setup: SETUP, hostName: '房主' });
  assert.equal(created.status, 200);
  assert.equal(created.body.backend, `http://${entryHost}`);
  assert.equal(rooms.count, 1);

  // 局域网入口只给界面和房间：设置、生成这些接口都没有
  assert.equal((await fetch(`http://${entryHost}/api/config`)).status, 404);
  assert.equal((await fetch(`http://${entryHost}/api/generate`, { method: 'POST' })).status, 404);

  // 同学凭邀请码加入，房主凭房主凭据回来
  const messages: Record<string, unknown>[] = [];
  const open = (msg: unknown) =>
    new Promise<WebSocket>((resolve, reject) => {
      const ws = new WebSocket(`ws://${entryHost}/api/rooms/${String(created.body.roomId)}/ws`);
      ws.onopen = () => {
        ws.send(JSON.stringify(msg));
        resolve(ws);
      };
      ws.onerror = () => reject(new Error('连不上房间'));
      ws.onmessage = e => messages.push(JSON.parse(String(e.data)) as Record<string, unknown>);
    });
  const until = async (pred: () => boolean, ms = 2000) => {
    for (let i = 0; i < ms / 10 && !pred(); i++) await new Promise(r => setTimeout(r, 10));
    assert.ok(pred());
  };
  const host = await open({ type: 'resume', token: created.body.hostToken });
  const guest = await open({ type: 'hello', invite: created.body.invite, name: '同学' });
  t.after(() => {
    host.close();
    guest.close();
  });
  await until(() => messages.filter(m => m.type === 'welcome').length >= 2);
  host.send(JSON.stringify({ type: 'start' }));
  await until(() =>
    messages.some(m => m.type === 'state' && (m.room as { status: string }).status === 'live'),
  );
  guest.send(JSON.stringify({ type: 'say', text: '机会成本就是放弃的最好的那个。' }));
  // 同学发言后（大家停 4 秒），AI 嘉宾用房主的 API 接话
  await until(
    () =>
      messages.some(
        m =>
          m.type === 'state' &&
          (m.room as { entries: { text: string }[] }).entries.some(e => e.text === '嘉宾接话。'),
      ),
    8000,
  );
  assert.ok(prompts.some(p => p.includes('机会成本就是放弃的最好的那个。')));
  // Key 只在房主的本机服务里，房间里发出来的任何消息都不含它
  assert.ok(!JSON.stringify(messages).includes('host-secret-key'));

  // 拿错邀请码进不来
  const bad: Record<string, unknown>[] = [];
  const intruder = new WebSocket(`ws://${entryHost}/api/rooms/${String(created.body.roomId)}/ws`);
  intruder.onopen = () =>
    intruder.send(JSON.stringify({ type: 'hello', invite: 'x'.repeat(22), name: '路人' }));
  intruder.onmessage = e => bad.push(JSON.parse(String(e.data)) as Record<string, unknown>);
  await until(() => bad.some(m => m.type === 'closed'));
  intruder.close();
  // 不存在的房间直接拒绝
  await new Promise<void>(resolve => {
    const ws = new WebSocket(`ws://${entryHost}/api/rooms/${'z'.repeat(22)}/ws`);
    ws.onerror = () => resolve();
  });
});

test('without --lan the local server does not open rooms', async t => {
  const local = createServer({});
  const host = await listen(local);
  t.after(() => stop(local));
  const r = await fetch(`http://${host}/api/rooms`, {
    method: 'POST',
    headers: {
      'X-Sibian-Client': 'web',
      Origin: `http://${host}`,
      'Content-Type': 'application/json',
    },
    body: '{}',
  });
  assert.equal(r.status, 404);
  const conf = (await (
    await fetch(`http://${host}/api/config`, {
      headers: { 'X-Sibian-Client': 'web', Origin: `http://${host}` },
    })
  ).json()) as { lan: string };
  assert.equal(conf.lan, '');
});

test('LAN invite links: they open the page on the host computer and only accept private http addresses', async () => {
  const { inviteLink, parseInvite } = await import('../src/lib/room.ts');
  const link = inviteLink({
    backend: 'http://192.168.1.20:4174',
    roomId: 'r'.repeat(22),
    invite: 'c'.repeat(22),
  });
  assert.match(link, /^http:\/\/192\.168\.1\.20:4174\/#join=/);
  assert.deepEqual(parseInvite(link.slice(link.indexOf('#'))), {
    backend: 'http://192.168.1.20:4174',
    roomId: 'r'.repeat(22),
    invite: 'c'.repeat(22),
  });
  // 公网上的 http 不接受（只有局域网和本机可以不加密）
  const pub = inviteLink(
    { backend: 'http://203.0.113.5:4174', roomId: 'r'.repeat(22), invite: 'c'.repeat(22) },
    'http://x/',
  );
  assert.equal(parseInvite(pub.slice(pub.indexOf('#'))), null);
});
