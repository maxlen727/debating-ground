// 多人房间：在 Node 里驱动 RoomCore，模拟房主和同学的连接
import { test } from 'node:test';
import assert from 'node:assert/strict';
import { RoomCore, snapshotFor, validateSetup, type Generate, type RoomDeps, type SocketLike, type StoredRoom } from '../worker/src/room.ts';
import type { RoomSetup, RoomView, ServerMessage } from '../shared/room.ts';
import { ROOM_LIMITS } from '../shared/room.ts';
import type { Purpose, SessionSnapshot } from '../shared/types.ts';
import { debateTurns, slots } from '../src/lib/debate.ts';
import { defaultConfig } from '../src/store.ts';
import { deadlineOf } from '../src/lib/room.ts';

const ROUNDTABLE = {
  mode: 'roundtable', concept: '机会成本', topic: '选择的代价是什么？', keyPoints: ['放弃的最高价值', '沉没成本无关', '隐性成本'],
  personas: [{ name: '林教授', title: '经济学', persona: '' }, { name: '周老师', title: '心理学', persona: '' }], opening: '大家先说说，什么叫机会成本？', model: 'm'
};
function debateSetup() {
  const c = defaultConfig();
  c.seats.B[0] = { type: 'human', bg: '' };   // 两个真人席位：A1、B1
  return { mode: 'debate', concept: '机会成本', topic: '机会成本应计入决策', keyPoints: ['放弃的最高价值', '沉没成本无关'], seats: slots(c).map(s => ({ ...s, name: '' })), stages: c.stages, turns: debateTurns(c) };
}

interface Client extends SocketLike { inbox: ServerMessage[]; member: string | null; open: boolean; last(): RoomView }
type Synthesize = NonNullable<RoomDeps['synthesize']>;
function setup(generate?: Generate, synthesize?: Synthesize, listen?: RoomDeps['listen']) {
  let stored: StoredRoom | undefined;
  let saves = 0;
  let now = 1_000_000;
  const sockets: Client[] = [];
  type Payload = { session: SessionSnapshot };
  const calls: { purpose: Purpose; payload: Payload }[] = [];
  let alarm = 0;
  const gen: Generate = generate ?? (async (purpose, payload) => {
    calls.push({ purpose, payload: payload as Payload });
    if (purpose === 'review') return { data: { oneLine: '复盘', points: [], misconceptions: [], quiz: [] }, model: 'm' };
    return { text: `AI 回复 ${calls.length}`, covered: [0], model: 'm' };
  });
  const core = new RoomCore({
    load: async () => stored && structuredClone(stored), save: async r => { saves++; stored = structuredClone(r); }, synthesize, listen, destroy: async () => { stored = undefined; },
    sockets: () => sockets.filter(s => s.open), setAlarm: async at => { alarm = at; }, generate: async (p, x, onText) => { if (!generate) return gen(p, x); calls.push({ purpose: p, payload: x as Payload }); return generate(p, x, onText); }, now: () => now
  });
  const connect = (): Client => {
    const c: Client = {
      inbox: [], member: null, open: true,
      send(d) { this.inbox.push(JSON.parse(d)); }, close() { this.open = false; }, memberId() { return this.member; }, setMember(id) { this.member = id; },
      last() { const m = [...this.inbox].reverse().find(x => x.type === 'state' || x.type === 'welcome') as { room: RoomView }; return m.room; }
    };
    sockets.push(c); return c;
  };
  const send = (c: Client, m: object) => core.onMessage(c, JSON.stringify(m));
  return { core, connect, send, calls, get stored() { return stored; }, get alarm() { return alarm; }, get saves() { return saves; }, tick(ms: number) { now += ms; } };
}

async function room(kind: 'roundtable' | 'debate' = 'roundtable', generate?: Generate, synthesize?: Synthesize, listen?: RoomDeps['listen']) {
  const t = setup(generate, synthesize, listen);
  const created = await t.core.init({ roomId: 'r'.repeat(22), setup: kind === 'debate' ? debateSetup() : ROUNDTABLE, hostName: '束束' });
  const host = t.connect();
  await t.send(host, { type: 'resume', token: created.hostToken });
  // 不用展开：stored、alarm 是 getter，要读到最新值
  return Object.assign(t, { created, host });
}
const errors = (c: { inbox: ServerMessage[] }) => c.inbox.filter((m): m is Extract<ServerMessage, { type: 'error' }> => m.type === 'error').map(m => m.message);

test('joining needs the room invite, not the backend password, and the invite is stored only as a hash', async () => {
  const t = await room();
  assert.equal(t.host.inbox[0].type, 'welcome');
  const stranger = t.connect();
  await t.send(stranger, { type: 'hello', invite: 'wrong', name: '路人' });
  assert.equal(stranger.inbox[0].type, 'closed');
  assert.equal(stranger.open, false);
  const guest = t.connect();
  await t.send(guest, { type: 'hello', invite: t.created.invite, name: '小明' });
  const welcome = guest.inbox[0] as Extract<ServerMessage, { type: 'welcome' }>;
  assert.equal(welcome.type, 'welcome');
  assert.deepEqual(welcome.room.members.map(m => [m.name, m.host, m.online]), [['束束', true, true], ['小明', false, true]]);
  const saved = JSON.stringify(t.stored);
  assert.ok(!saved.includes(t.created.invite) && !saved.includes(t.created.hostToken) && !saved.includes(welcome.token), 'secrets are not stored in plain text');
  assert.ok(!JSON.stringify(welcome.room).includes('Hash'), 'hashes never leave the room');
  // 断线后凭成员凭据回来，还是同一个人
  guest.open = false;
  const again = t.connect();
  await t.send(again, { type: 'resume', token: welcome.token });
  assert.equal((again.inbox[0] as { memberId: string }).memberId, welcome.memberId);
  assert.equal(again.last().members.length, 2);
});

test('roundtable: people speak in turn and an AI guest answers after each person', async () => {
  const t = await room();
  const guest = t.connect();
  await t.send(guest, { type: 'hello', invite: t.created.invite, name: '小明' });
  await t.send(guest, { type: 'say', text: '我先说' });
  assert.deepEqual(errors(guest), ['讨论还没有开始。']);
  await t.send(guest, { type: 'start' });
  assert.deepEqual(errors(guest).at(-1), '只有房主可以这样做。');
  await t.send(t.host, { type: 'start' });
  const hostId = t.host.member!, guestId = guest.member!;
  assert.deepEqual(t.host.last().speaker, { kind: 'member', id: hostId });
  await t.send(guest, { type: 'say', text: '抢话' });
  assert.equal(errors(guest).at(-1), '还没轮到你发言。');

  await t.send(t.host, { type: 'say', text: '机会成本是放弃的最好选择。' });
  let v = guest.last();
  assert.deepEqual(v.entries.filter(e => e.kind === 'msg').map(e => e.kind === 'msg' && e.name), ['林教授', '束束', '周老师']);
  assert.deepEqual(v.speaker, { kind: 'member', id: guestId }, 'next person after the AI guest');
  assert.deepEqual(v.covered, [0]);
  // AI 看到的所有真人都是学习者
  assert.match(JSON.stringify(t.calls[0].payload.session.messages), /束束（学习者）/);

  await t.send(guest, { type: 'say', text: '沉没成本不该算进去。' });
  v = t.host.last();
  assert.equal(v.entries.filter(e => e.kind === 'msg').at(-1)!.kind === 'msg' && (v.entries.at(-1) as { name: string }).name, '林教授');
  assert.deepEqual(v.speaker, { kind: 'member', id: hostId }, 'wraps around to the host');

  // 房主可以点名，也可以跳过
  await t.send(t.host, { type: 'callOn', target: { kind: 'member', id: guestId } });
  assert.deepEqual(t.host.last().speaker, { kind: 'member', id: guestId });
  await t.send(t.host, { type: 'pass' });
  assert.deepEqual(t.host.last().speaker, { kind: 'member', id: hostId });
  const before = t.calls.length;
  await t.send(t.host, { type: 'callOn', target: { kind: 'ai', id: 'r1' } });
  assert.equal(t.calls.length, before + 1);
});

test('roundtable skips people who are offline', async () => {
  const t = await room();
  const guest = t.connect();
  await t.send(guest, { type: 'hello', invite: t.created.invite, name: '小明' });
  await t.send(t.host, { type: 'start' });
  guest.open = false;
  await t.core.onClose();
  await t.send(t.host, { type: 'say', text: '我先说' });
  assert.deepEqual(t.host.last().speaker, { kind: 'member', id: t.host.member });
});

test('AI failure stops the room with a visible error and keeps progress; the host retries', async () => {
  let fail = true;
  const t = await room('roundtable', async () => { if (fail) throw new Error('boom'); return { text: '好的', covered: [] }; });
  await t.send(t.host, { type: 'start' });
  await t.send(t.host, { type: 'say', text: '我先说' });
  let v = t.host.last();
  assert.equal(v.error, 'AI 发言失败。');
  assert.equal(v.aiBusy, null);
  assert.equal(v.speaker?.kind, 'ai', 'still the AI guest’s turn');
  assert.equal(v.entries.filter(e => e.kind === 'msg').length, 2, 'nothing was made up');
  fail = false;
  await t.send(t.host, { type: 'retry' });
  v = t.host.last();
  assert.equal(v.error, '');
  assert.equal((v.entries.at(-1) as { text: string }).text, '好的');
});

test('debate: everyone picks a side and seat, empty seats are played by AI, the host can skip or hand a seat to AI', async () => {
  const t = await room('debate');
  const guest = t.connect();
  await t.send(guest, { type: 'hello', invite: t.created.invite, name: '小明' });
  const third = t.connect();
  await t.send(third, { type: 'hello', invite: t.created.invite, name: '小红' });
  let v = t.host.last();
  assert.deepEqual(v.members.map(m => m.seat), ['A1', undefined, undefined], 'only the host has a default seat');
  // 同学自己选阵营和席位，包括原来由 AI 担任的席位
  await t.send(guest, { type: 'seat', seat: 'B1' });
  await t.send(third, { type: 'seat', seat: 'B1' });
  assert.equal(errors(third).at(-1), '小明 已经坐在这个席位了。');
  await t.send(third, { type: 'seat', seat: 'A2' });
  await t.send(third, { type: 'seat', seat: 'Z9' });
  assert.equal(errors(third).at(-1), '找不到这个席位。');
  v = t.host.last();
  assert.deepEqual(v.members.map(m => m.seat), ['A1', 'B1', 'A2']);
  assert.ok(v.entries.some(e => e.kind === 'sys' && e.text === '小红 担任正方 2 辩。'));
  // 换席位、回到旁听
  await t.send(third, { type: 'seat', seat: null });
  await t.send(third, { type: 'seat', seat: 'A2' });

  await t.send(t.host, { type: 'start' });
  await t.send(guest, { type: 'say', text: '我抢先' });
  assert.equal(errors(guest).at(-1), '还没轮到你的席位发言。');
  await t.send(t.host, { type: 'say', text: '我方立论……' });
  await t.send(guest, { type: 'say', text: '反方立论……' });
  v = t.host.last();
  const turns = v.setup.turns;
  // 交叉质询：A2 由小红担任，所以停下来等她；没人坐的 B2 由 AI 自动答辩（每方两席，三辩取末席）
  assert.equal(turns[v.cursor].role.id, 'A2');
  assert.equal(v.cursor, 2);
  await t.send(third, { type: 'say', text: '请问对方三辩……' });
  v = t.host.last();
  const msgs = v.entries.filter(e => e.kind === 'msg');
  assert.deepEqual(msgs.slice(0, 3).map(e => e.kind === 'msg' && e.name), ['正方 1 辩（束束）', '反方 1 辩（小明）', '正方 2 辩（小红）']);
  assert.ok(msgs.some(e => e.kind === 'msg' && e.who === 'B2' && !e.memberId), 'the empty seat was played by AI');
  // AI 看到的赛程里，有人坐的席位是真人
  const debatePrompt = t.calls.find(c => c.purpose === 'debate')!.payload.session;
  assert.deepEqual(debatePrompt.turns!.filter(x => x.role.type === 'human').map(x => x.role.id).filter((x, i, a) => a.indexOf(x) === i).sort(), ['A1', 'A2', 'B1']);

  if (v.status === 'live') {
    const seat = turns[v.cursor].role.id;
    const owner = v.members.find(m => m.seat === seat);
    if (owner && owner.id !== t.host.member) {
      const cursor = v.cursor;
      const other = owner.id === guest.member ? third : guest;
      await t.send(other, { type: 'pass' });
      assert.equal(errors(other).at(-1), '只有当前席位的同学或房主可以跳过。');
      await t.send(t.host, { type: 'aiTakeover' });
      v = t.host.last();
      assert.ok(v.cursor > cursor, 'AI spoke for the absent classmate');
    }
  }
  // 离开席位后，轮到这个席位时由 AI 担任
  await t.send(third, { type: 'seat', seat: null });
  assert.equal(t.host.last().members.find(m => m.name === '小红')!.seat, undefined);
});

test('reviews are personal: only my own words count as the learner’s', async () => {
  const t = await room();
  const guest = t.connect();
  await t.send(guest, { type: 'hello', invite: t.created.invite, name: '小明' });
  await t.send(t.host, { type: 'start' });
  await t.send(t.host, { type: 'say', text: '房主的理解' });
  await t.send(guest, { type: 'say', text: '小明的理解' });
  await t.send(guest, { type: 'ask', reqId: 'x', purpose: 'review', reflections: [] });
  assert.match(JSON.stringify(guest.inbox.at(-1)), /讨论结束后才能复盘/);
  await t.send(guest, { type: 'finish' });
  assert.equal(errors(guest).at(-1), '只有房主可以这样做。');
  await t.send(t.host, { type: 'finish' });
  assert.equal(t.host.last().status, 'finished');
  assert.ok(t.alarm <= 1_000_000 + ROOM_LIMITS.afterFinishMs, 'room is deleted an hour after finishing');

  await t.send(guest, { type: 'ask', reqId: 'r1', purpose: 'review', reflections: ['我的小结'] });
  const answer = guest.inbox.at(-1) as Extract<ServerMessage, { type: 'answer' }>;
  assert.equal(answer.type, 'answer'); assert.equal(answer.ok, true); assert.equal(answer.reqId, 'r1');
  const msgs = t.calls.at(-1)!.payload.session.messages;
  assert.deepEqual(msgs.filter(m => m.name.includes('学习者')).map(m => m.text), ['小明的理解', '我的小结']);
  assert.ok(msgs.some(m => m.name === '束束（同学）' && m.text === '房主的理解'));
  // 只发给提问的人
  assert.ok(!t.host.inbox.some(m => m.type === 'answer'));
  // 旁听者没有发言，不能复盘
  const late = t.connect();
  await t.send(late, { type: 'hello', invite: t.created.invite, name: '迟到' });
  assert.equal(late.inbox[0].type, 'closed', 'finished rooms do not accept new members');
});

test('limits protect the host’s AI quota', async () => {
  const t = await room();
  await t.send(t.host, { type: 'start' });
  for (let i = 0; i < ROOM_LIMITS.personalCalls; i++) await t.send(t.host, { type: 'ask', reqId: String(i), purpose: 'hint' });
  await t.send(t.host, { type: 'ask', reqId: 'over', purpose: 'hint' });
  assert.match(JSON.stringify(t.host.inbox.at(-1)), /次数已经用完/);
  // 人数上限
  const joined = [];
  for (let i = 0; i < ROOM_LIMITS.members + 1; i++) { const c = t.connect(); await t.send(c, { type: 'hello', invite: t.created.invite, name: '同学' + i }); joined.push(c); }
  assert.equal(joined.at(-1)!.inbox[0].type, 'closed');
  assert.equal(t.host.last().members.length, ROOM_LIMITS.members);
  // 过长的发言、伪造的讲解目标
  await t.send(t.host, { type: 'say', text: 'x'.repeat(ROOM_LIMITS.textLength + 1) });
  assert.equal(errors(t.host).at(-1), '发言无效。');
});

test('the room deletes itself when it expires', async () => {
  const t = await room();
  assert.equal(t.alarm, 1_000_000 + ROOM_LIMITS.lifetimeMs);
  await t.core.alarm();
  assert.ok(t.stored, 'not yet');
  t.tick(ROOM_LIMITS.lifetimeMs);
  await t.core.alarm();
  assert.equal(t.stored, undefined);
  assert.equal(t.host.inbox.at(-1)!.type, 'closed');
});

test('setup is validated on the backend', () => {
  assert.throws(() => validateSetup({ ...ROUNDTABLE, mode: 'socratic' }), /只支持圆桌讨论和正反辩论/);
  assert.throws(() => validateSetup({ ...ROUNDTABLE, keyPoints: ['一'] }), /2 到 6/);
  assert.throws(() => validateSetup({ ...ROUNDTABLE, personas: [] }), /至少需要一位/);
  const d = debateSetup();
  assert.throws(() => validateSetup({ ...d, turns: [{ ...d.turns[0], role: { id: 'Z9' } }] }), /比赛流程无效/);
  assert.doesNotThrow(() => validateSetup({ ...d, seats: d.seats.map(s => ({ ...s, type: 'ai' })) }), 'people pick seats in the room');
  const ok = validateSetup(d) as RoomSetup;
  assert.equal(ok.turns.length, d.turns.length);
  assert.equal(ok.seats[0].name, '正方 1 辩');
});

test('snapshot labels only the viewer as the learner', () => {
  const room = {
    setup: validateSetup(ROUNDTABLE), members: [{ id: 'h', name: '束束' }, { id: 'g', name: '小明' }], status: 'live', cursor: 0,
    entries: [{ id: '1', kind: 'msg', who: 'h', memberId: 'h', name: '束束', text: 'a', source: 'human' }, { id: '2', kind: 'msg', who: 'r0', name: '林教授', text: 'b', source: 'api' }]
  } as unknown as StoredRoom;
  assert.deepEqual(snapshotFor(room, null).messages.map(m => m.name), ['束束（学习者）', '林教授']);
  assert.deepEqual(snapshotFor(room, 'g').messages.map(m => m.name), ['束束（同学）', '林教授']);
  assert.deepEqual(snapshotFor(room, 'h').messages.map(m => m.name), ['束束（学习者）', '林教授']);
});

test('room AI turns carry terms, and only marked terms can be looked up', async () => {
  const t = await room('roundtable', async (purpose, payload) => {
    if (purpose === 'term') return { data: { term: (payload as { term: string }).term, definition: '释义', relation: '' } };
    return { text: '时间有稀缺性。', covered: [], terms: ['稀缺性'] };
  });
  await t.send(t.host, { type: 'start' });
  await t.send(t.host, { type: 'say', text: '我先说' });
  const ai = t.host.last().entries.filter(e => e.kind === 'msg').at(-1)!;
  assert.ok(ai.kind === 'msg' && ai.terms?.[0] === '稀缺性');
  await t.send(t.host, { type: 'ask', reqId: 'a', purpose: 'term', entryId: ai.id, term: '稀缺性' });
  assert.deepEqual((t.host.inbox.at(-1) as { result: { data: { definition: string } } }).result.data.definition, '释义');
  await t.send(t.host, { type: 'ask', reqId: 'b', purpose: 'term', entryId: ai.id, term: '随便问个词' });
  assert.match(JSON.stringify(t.host.inbox.at(-1)), /找不到这个词/);
  // 讨论结束后还能查
  await t.send(t.host, { type: 'finish' });
  await t.send(t.host, { type: 'ask', reqId: 'c', purpose: 'term', entryId: ai.id, term: '稀缺性' });
  assert.equal((t.host.inbox.at(-1) as { ok: boolean }).ok, true);
});

test('seats left for classmates wait for someone instead of being played by AI', async () => {
  const t = setup();
  const d = debateSetup();
  d.seats = d.seats.map(x => x.id === 'B1' ? { ...x, type: 'peer' } : x);   // 反方一辩留给同学
  const created = await t.core.init({ roomId: 'p'.repeat(22), setup: d, hostName: '束束' });
  const host = t.connect();
  await t.send(host, { type: 'resume', token: created.hostToken });
  await t.send(host, { type: 'start' });
  await t.send(host, { type: 'say', text: '我方立论……' });
  let v = host.last();
  assert.equal(v.setup.turns[v.cursor].role.id, 'B1', 'waiting for a classmate to take 反方一辩');
  assert.equal(v.aiBusy, null);
  assert.equal(t.calls.length, 0, 'no AI call for an empty classmate seat');
  // 同学中途入座后由他发言
  const guest = t.connect();
  await t.send(guest, { type: 'hello', invite: created.invite, name: '小明' });
  await t.send(guest, { type: 'seat', seat: 'B1' });
  await t.send(guest, { type: 'say', text: '反方立论……' });
  v = host.last();
  assert.ok(v.cursor > 1);
  // 没人来时，房主可以让 AI 代答空着的同学席位
  const t2 = setup();
  const c2 = await t2.core.init({ roomId: 'q'.repeat(22), setup: d, hostName: '束束' });
  const h2 = t2.connect();
  await t2.send(h2, { type: 'resume', token: c2.hostToken });
  await t2.send(h2, { type: 'start' });
  await t2.send(h2, { type: 'say', text: '我方立论……' });
  await t2.send(h2, { type: 'aiTakeover' });
  assert.ok(h2.last().cursor > 1);
});

test('room AI speech is relayed to everyone while it is being written, then saved under the same id', async () => {
  const t = await room('roundtable', async (_p, _x, onText) => {
    for (const part of ['机会', '机会成本', '机会成本就是放弃的最好选择。']) { onText?.(part); await new Promise(r => setTimeout(r, 200)); }
    return { text: '机会成本就是放弃的最好选择。', covered: [] };
  });
  const guest = t.connect();
  await t.send(guest, { type: 'hello', invite: t.created.invite, name: '小明' });
  await t.send(t.host, { type: 'start' });
  await t.send(t.host, { type: 'say', text: '我先说' });
  const streams = guest.inbox.filter((m): m is Extract<ServerMessage, { type: 'stream' }> => m.type === 'stream');
  assert.ok(streams.length >= 2, 'several partial updates');
  assert.equal(new Set(streams.map(m => m.id)).size, 1);
  assert.equal(streams.at(-1)!.text, '机会成本就是放弃的最好选择。');
  const saved = guest.last().entries.at(-1)!;
  assert.equal(saved.id, streams[0].id, 'the final message keeps the stream id');
});

/* ---------- 朗读、正在输入、快捷短语、要点提案、倒计时 ---------- */
const audioOf = (c: { inbox: ServerMessage[] }) => c.inbox.filter((m): m is Extract<ServerMessage, { type: 'audio' }> => m.type === 'audio');
async function guestIn(t: Awaited<ReturnType<typeof room>>, name = '小明') {
  const g = t.connect();
  await t.send(g, { type: 'hello', invite: t.created.invite, name });
  return g;
}

test('reading aloud uses its own quota, never the AI quota, and plays only for the person who asked', async () => {
  const spoken: string[] = [];
  const t = await room('roundtable', undefined, async (text, voice) => { spoken.push(`${voice}:${text}`); return { audio: btoa('mp3-' + spoken.length), mime: 'audio/mpeg' }; });
  const guest = await guestIn(t);
  const opening = t.host.last().entries.find(e => e.kind === 'msg')!;
  assert.equal(t.host.last().voiceAvailable, true);

  await t.send(guest, { type: 'speak', entryId: opening.id, voice: 'x4_yezi' });
  assert.deepEqual(audioOf(guest).map(a => [a.entryId, a.seq, a.last]), [[opening.id, 0, true]]);
  assert.equal(audioOf(t.host).length, 0, 'audio goes only to the one who asked');
  assert.equal(t.stored!.aiCalls, 0, 'reading aloud does not touch the AI quota');
  assert.equal(t.stored!.voiceCalls, 1);
  assert.deepEqual(spoken, ['x4_yezi:大家先说说，什么叫机会成本？']);

  // 同一条、同一个音色再听：用缓存，不再调用讯飞，也不计数
  await t.send(t.host, { type: 'speak', entryId: opening.id, voice: 'x4_yezi' });
  assert.equal(audioOf(t.host).length, 1);
  assert.equal(spoken.length, 1);
  assert.equal(t.stored!.voiceCalls, 1);

  // 换音色、乱写的音色
  await t.send(guest, { type: 'speak', entryId: opening.id, voice: 'bad voice;' });
  assert.equal(spoken.at(-1), 'x4_xiaoyan:大家先说说，什么叫机会成本？', 'an invalid voice falls back to the default');

  // 每人的朗读次数用完后被拒，房间的 AI 照常发言
  // 直接把小明的次数调到上限（RoomCore 内部状态），换个没缓存的音色再点
  (t.core as unknown as { room: StoredRoom }).room.members.find(m => m.name === '小明')!.voiceCalls = ROOM_LIMITS.personalVoice;
  await t.send(guest, { type: 'speak', entryId: opening.id, voice: 'x4_xiaofeng' });
  assert.match(errors(guest).at(-1)!, /朗读次数已经用完/);
  await t.send(t.host, { type: 'start' });
  await t.send(t.host, { type: 'say', text: '是放弃的东西' });
  assert.equal(t.stored!.aiCalls, 1, 'the AI guest still answers');
});

test('long messages are read aloud segment by segment; a backend without iFlytek says so', async () => {
  const parts: string[] = [];
  const t = await room('roundtable', async (_p, _x) => ({ text: '这是一句很长的话。'.repeat(80), covered: [], model: 'm' }), async text => { parts.push(text); return { audio: btoa(String(parts.length)), mime: 'audio/mpeg' }; });
  await t.send(t.host, { type: 'start' });
  await t.send(t.host, { type: 'say', text: '我先说' });
  const long = t.host.last().entries.filter(e => e.kind === 'msg').at(-1)!;
  await t.send(t.host, { type: 'speak', entryId: long.id, voice: '' });
  const got = audioOf(t.host);
  assert.ok(got.length > 1, 'split into several segments');
  assert.deepEqual(got.map(a => a.seq), got.map((_, i) => i));
  assert.equal(got.filter(a => a.last).length, 1);
  assert.equal(got.at(-1)!.last, true);
  assert.ok(parts.every(p => Array.from(p).length <= 300));
  assert.equal(t.stored!.voiceCalls, 1, 'one tap counts once, however many segments');

  const plain = await room();
  assert.equal(plain.host.last().voiceAvailable, false);
  const first = plain.host.last().entries.find(e => e.kind === 'msg')!;
  await plain.send(plain.host, { type: 'speak', entryId: first.id, voice: '' });
  assert.match(errors(plain.host).at(-1)!, /还没有配置讯飞语音/);
});

test('typing status is not written to storage and is only broadcast when it changes', async () => {
  const t = await room();
  const guest = await guestIn(t);
  const saves = t.saves, before = guest.inbox.length;
  for (let i = 0; i < 20; i++) await t.send(t.host, { type: 'typing', active: true });
  assert.equal(t.saves, saves, 'no storage writes');
  const states = guest.inbox.slice(before).filter(m => m.type === 'state');
  assert.equal(states.length, 1, 'one broadcast when typing starts');
  assert.equal(guest.last().members.find(m => m.host)!.typing, true);
  await t.send(t.host, { type: 'typing', active: false });
  assert.equal(guest.last().members.find(m => m.host)!.typing, false);
  // 没续上就自己消失
  await t.send(t.host, { type: 'typing', active: true });
  t.tick(7000);
  assert.equal(t.core.view().members.find(m => m.host)!.typing, false);
});

test('quick messages are a passing nudge: rate limited, never stored, never sent to the AI', async () => {
  const t = await room();
  const guest = await guestIn(t);
  await t.send(guest, { type: 'quickMessage', text: '等一等' });
  assert.match(errors(guest).at(-1)!, /讨论进行中/);
  await t.send(t.host, { type: 'start' });
  const entries = t.stored!.entries.length, saves = t.saves;
  await t.send(guest, { type: 'quickMessage', text: '等一等' });
  assert.deepEqual(t.host.inbox.at(-1), { type: 'quick', memberId: guest.member, name: '小明', text: '等一等' });
  for (let i = 0; i < 320; i++) await t.send(guest, { type: 'quickMessage', text: '请继续' });
  assert.match(errors(guest).at(-1)!, /发得太快/);
  assert.equal(t.host.inbox.filter(m => m.type === 'quick').length, 1);
  t.tick(ROOM_LIMITS.quickGapMs);
  await t.send(guest, { type: 'quickMessage', text: '请继续' });
  assert.equal(t.host.inbox.filter(m => m.type === 'quick').length, 2);
  await t.send(guest, { type: 'quickMessage', text: '随便写点什么' });
  assert.match(errors(guest).at(-1)!, /列表里的快捷短语/);
  assert.equal(t.stored!.entries.length, entries, 'nothing goes into the discussion record');
  assert.equal(t.saves, saves);
  assert.equal(t.calls.length, 0, 'no AI call');
});

test('new key points: classmates propose, the host decides, everyone sees the same list', async () => {
  const t = await room();
  const guest = await guestIn(t);
  await t.send(guest, { type: 'propose', text: '时间也是成本', source: 'human' });
  assert.deepEqual(guest.last().proposals!.map(p => [p.text, p.proposer, p.source]), [['时间也是成本', '小明', 'human']]);
  assert.equal(guest.last().setup.keyPoints.length, 3, 'not a key point until the host accepts');
  const id = guest.last().proposals![0].id;
  await t.send(guest, { type: 'decidePoint', id, accept: true });
  assert.match(errors(guest).at(-1)!, /只有房主/);
  await t.send(t.host, { type: 'decidePoint', id, accept: true });
  assert.deepEqual(guest.last().setup.keyPoints.at(-1), '时间也是成本');
  assert.equal(guest.last().proposals!.length, 0);
  assert.ok(guest.last().entries.some(e => e.kind === 'sys' && e.text === '新增讨论要点：时间也是成本'));
  // 重复的、太多的
  await t.send(guest, { type: 'propose', text: '时间也是成本', source: 'human' });
  assert.match(errors(guest).at(-1)!, /已经有了/);
  await t.send(guest, { type: 'propose', text: '甲', source: 'human' });
  await t.send(guest, { type: 'propose', text: '乙', source: 'ai' });
  await t.send(guest, { type: 'propose', text: '丙', source: 'human' });
  assert.match(errors(guest).at(-1)!, /太多/);
  // 房主自己写的直接加入，忽略的提案就消失
  await t.send(t.host, { type: 'propose', text: '房主加的', source: 'human' });
  assert.equal(t.host.last().setup.keyPoints.at(-1), '房主加的');
  await t.send(t.host, { type: 'decidePoint', id: t.host.last().proposals![0].id, accept: false });
  assert.deepEqual(t.host.last().proposals!.map(p => p.text), ['乙']);
  // AI 提议走个人请求，用的是专门的 point 用途
  await t.send(t.host, { type: 'start' });
  await t.send(guest, { type: 'ask', reqId: 'p', purpose: 'point' });
  assert.equal(t.calls.at(-1)!.purpose, 'point');
});

test('the turn timer is sent with the backend clock so phones with a wrong clock still count down correctly', async () => {
  const t = setup();
  const s = { ...ROUNDTABLE, turnSeconds: 60 };
  const created = await t.core.init({ roomId: 'r'.repeat(22), setup: s, hostName: '束束' });
  const host = t.connect();
  await t.send(host, { type: 'resume', token: created.hostToken });
  await t.send(host, { type: 'start' });
  const v = host.last();
  assert.equal(v.setup.turnSeconds, 60);
  assert.equal(v.turnStartedAt, v.now);
  // 手机时钟快了 5 分钟：截止时间仍是“收到时 + 60 秒”
  const phone = Date.now() + 300_000;
  assert.equal(deadlineOf(v, phone), phone + 60_000);
  assert.equal(deadlineOf({ ...v, turnStartedAt: 0 }, phone), 0, 'no timer while the AI speaks');
  assert.equal(validateSetup({ ...ROUNDTABLE, turnSeconds: 45 }).turnSeconds, 0, 'only the offered limits are accepted');
});

test('voice input in a room: audio goes to iFlytek through the room, text comes back only to the speaker', async () => {
  // 假的听写会话：收到几帧就回几个字，end 时给最终结果
  const sessions: { frames: number; cancelled: boolean }[] = [];
  const listen: RoomDeps['listen'] = emit => {
    const me = { frames: 0, cancelled: false }; sessions.push(me);
    let finished = false;
    return {
      get finished() { return finished; },
      async start() {},
      audio() { me.frames++; emit({ type: 'text', text: '听到'.repeat(me.frames), final: false }); },
      end() { finished = true; emit({ type: 'text', text: '听到了', final: true }); },
      cancel() { me.cancelled = true; finished = true; }
    };
  };
  const t = await room('roundtable', undefined, undefined, listen);
  const guest = await guestIn(t);
  assert.equal(guest.last().listenAvailable, true);
  const heardBy = (c: { inbox: ServerMessage[] }) => c.inbox.filter((m): m is Extract<ServerMessage, { type: 'heard' }> => m.type === 'heard').map(m => m.event);

  await t.send(guest, { type: 'listen', op: 'start' });
  await t.send(guest, { type: 'listen', op: 'start' });
  await t.send(guest, { type: 'listen', op: 'audio', audio: 'AAAA' });
  await t.send(guest, { type: 'listen', op: 'audio', audio: 'AAAA' });
  await t.send(guest, { type: 'listen', op: 'end' });
  const got = heardBy(guest);
  assert.deepEqual(got[0], { type: 'ready' });
  assert.match((got[1] as { message: string }).message, /还没有识别完/, 'one recording at a time');
  assert.deepEqual(got.slice(2), [{ type: 'text', text: '听到', final: false }, { type: 'text', text: '听到听到', final: false }, { type: 'text', text: '听到了', final: true }]);
  assert.equal(heardBy(t.host).length, 0, 'nobody else hears it');
  assert.equal(t.stored!.aiCalls, 0, 'not counted as AI calls');
  assert.equal(t.stored!.listenCalls, 1);
  assert.ok(!JSON.stringify(t.stored).includes('听到'), 'nothing is stored until the person sends it');

  // 断线时取消正在进行的录音
  await t.send(t.host, { type: 'listen', op: 'start' });
  t.host.open = false;
  await t.core.onClose(t.host);
  assert.equal(sessions.at(-1)!.cancelled, true);

  // 次数用完
  (t.core as unknown as { room: StoredRoom }).room.members.find(m => m.name === '小明')!.listenCalls = ROOM_LIMITS.personalListen;
  await t.send(guest, { type: 'listen', op: 'start' });
  assert.match((heardBy(guest).at(-1) as { message: string }).message, /语音输入次数已经用完/);

  // 没配讯飞的房间
  const plain = await room();
  assert.equal(plain.host.last().listenAvailable, false);
  await plain.send(plain.host, { type: 'listen', op: 'start' });
  assert.match(JSON.stringify(plain.host.inbox.at(-1)), /还没有配置讯飞语音/);
});

test('the background the host gave travels with the room to every AI prompt', () => {
  const setup = validateSetup({ ...ROUNDTABLE, context: '  群里有个接了大模型的 bot 叫亚托莉。  ' });
  assert.equal(setup.context, '群里有个接了大模型的 bot 叫亚托莉。');
  assert.equal(validateSetup({ ...ROUNDTABLE, context: 'x'.repeat(900) }).context!.length, 600);
  assert.equal(validateSetup({ ...ROUNDTABLE, context: '   ' }).context, undefined);
  const room = { setup, members: [], entries: [], status: 'live', cursor: 0 } as unknown as StoredRoom;
  assert.equal(snapshotFor(room, null).context, '群里有个接了大模型的 bot 叫亚托莉。');
});
