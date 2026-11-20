// 安卓手机当局域网房主时跑的房间规则（native/app/src/main/assets/sibian-room.js，#148）。
// 它由 worker/src/room.ts 打包而来：在一个只有语言本身的环境里（没有 crypto、TextEncoder、btoa、定时器）跑一遍完整的房间。
import { test } from 'node:test';
import assert from 'node:assert/strict';
import { createHash, randomBytes } from 'node:crypto';
import { readFileSync } from 'node:fs';
import { runInNewContext } from 'node:vm';
import { ROOM_BUNDLE, bundleRoom } from '../scripts/bundle-native-ai.ts';

test('the native room bundle is up to date with worker/src/room.ts', async () => {
  assert.equal(
    readFileSync(ROOM_BUNDLE, 'utf8'),
    await bundleRoom(),
    '打包文件过期了，运行 npm run bundle:native',
  );
});

interface Effect {
  kind: 'send' | 'close' | 'alarm' | 'generate' | 'gone';
  sock?: string;
  data?: string;
  room?: string;
  at?: number;
  req?: string;
  purpose?: string;
  payload?: Record<string, unknown>;
}
function load() {
  const box: {
    sibianRoom?: {
      call: (n: string, a: string) => Promise<string>;
      sha256Hex: (s: string) => string;
    };
  } = {};
  runInNewContext(readFileSync(ROOM_BUNDLE, 'utf8'), box);
  const room = box.sibianRoom!;
  let now = 1_700_000_000_000;
  const call = async (name: string, ...args: unknown[]) => {
    now += 10;
    const out = JSON.parse(
      await room.call(name, JSON.stringify([...args, now, [...randomBytes(512)]])),
    ) as { effects: Effect[]; result: Record<string, unknown> | null; error: string };
    return out;
  };
  return { call, room, at: () => now, setNow: (t: number) => (now = t) };
}
const sent = (effects: Effect[], sock: string) =>
  effects
    .filter(e => e.kind === 'send' && e.sock === sock)
    .map(e => JSON.parse(e.data!) as Record<string, unknown>);

const SETUP = {
  mode: 'roundtable',
  concept: '机会成本',
  topic: '选择的代价是什么？',
  keyPoints: ['放弃的最高价值', '沉没成本无关'],
  personas: [{ name: '林教授', title: '经济学', persona: '' }],
  opening: '大家先说说？',
  model: 'm',
};

test('SHA-256 in the bundle matches node:crypto', () => {
  const { room } = load();
  for (const s of ['', 'abc', '邀请码-测试-😀', 'x'.repeat(200)])
    assert.equal(room.sha256Hex(s), createHash('sha256').update(s).digest('hex'), s);
});

test('a whole LAN room inside a bare JS engine: effects out, AI results back in', async () => {
  const { call, setNow, at } = load();
  const roomId = 'r'.repeat(22);
  const created = await call('create', roomId, SETUP, '房主');
  assert.equal(created.error, '');
  const { invite, hostToken } = created.result as { invite: string; hostToken: string };
  assert.match(invite, /^[A-Za-z0-9_-]{22}$/);
  assert.ok(
    created.effects.some(e => e.kind === 'alarm' && e.room === roomId),
    'expiry alarm',
  );

  await call('open', roomId, 'h');
  const hostIn = await call(
    'message',
    roomId,
    'h',
    JSON.stringify({ type: 'resume', token: hostToken }),
  );
  assert.equal(sent(hostIn.effects, 'h')[0].type, 'welcome');
  await call('open', roomId, 'g');
  const guestIn = await call(
    'message',
    roomId,
    'g',
    JSON.stringify({ type: 'hello', invite, name: '同学' }),
  );
  assert.equal(sent(guestIn.effects, 'g')[0].type, 'welcome');
  // 拿错邀请码：关门
  await call('open', roomId, 'x');
  const bad = await call(
    'message',
    roomId,
    'x',
    JSON.stringify({ type: 'hello', invite: 'w'.repeat(22), name: '路人' }),
  );
  assert.equal(sent(bad.effects, 'x')[0].type, 'closed');
  assert.ok(bad.effects.some(e => e.kind === 'close' && e.sock === 'x'));

  await call('message', roomId, 'h', JSON.stringify({ type: 'start' }));
  const said = await call(
    'message',
    roomId,
    'g',
    JSON.stringify({ type: 'say', text: '放弃的最好的那个。' }),
  );
  const due = said.effects.findLast(e => e.kind === 'alarm')!;
  assert.ok(due.at! > at(), 'the AI guest replies after a pause');
  // 闹钟到点：房间要 AI 接话，交给 Kotlin 去调
  setNow(due.at!);
  const rang = await call('alarm', roomId);
  const gen = rang.effects.find(e => e.kind === 'generate')!;
  assert.equal(gen.purpose, 'discuss');
  assert.match(JSON.stringify(gen.payload), /放弃的最好的那个。/);
  // AI 结果回来：大家收到新的发言
  const back = await call('resolve', gen.req, true, {
    text: '嘉宾接话。',
    covered: [0],
    model: 'm',
  });
  const state = sent(back.effects, 'g').findLast(m => m.type === 'state')!;
  const entries = (state.room as { entries: { text: string }[] }).entries;
  assert.equal(entries.at(-1)!.text, '嘉宾接话。');
  // AI 失败：原因显示在房间里，不编造发言
  await call('message', roomId, 'g', JSON.stringify({ type: 'say', text: '再说一句。' }));
  setNow(at() + 20_000);
  const again = (await call('alarm', roomId)).effects.find(e => e.kind === 'generate')!;
  const failed = await call('resolve', again.req, false, 'API 返回 429：额度不足。');
  const view = sent(failed.effects, 'g').findLast(m => m.type === 'state')!.room as {
    error: string;
  };
  assert.equal(view.error, 'API 返回 429：额度不足。');
});
