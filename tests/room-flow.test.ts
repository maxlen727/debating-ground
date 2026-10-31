// 多人讨论的集成测试：房主用真实的 actions 操作，经过假 WebSocket 连到真实的 RoomCore；
// 另一位同学直接用房间协议发消息。
import { test } from 'node:test';
import assert from 'node:assert/strict';
import type { ServerMessage } from '../shared/room.ts';

const store: Record<string, string> = {};
(globalThis as Record<string, unknown>).localStorage = { getItem: (k: string) => store[k] ?? null, setItem: (k: string, v: string) => { store[k] = v; } };

const { RoomCore } = await import('../worker/src/room.ts');
type Sock = import('../worker/src/room.ts').SocketLike & { open: boolean; inbox: ServerMessage[] };
const sockets: Sock[] = [];
let saved: unknown;
const prompts: { purpose: string; payload: { session: import('../shared/types.ts').SessionSnapshot } }[] = [];
const core = new RoomCore({
  load: async () => (saved ? structuredClone(saved) : undefined) as never, save: async r => { saved = structuredClone(r); }, destroy: async () => { saved = undefined; },
  sockets: () => sockets.filter(s => s.open), setAlarm: async () => {}, now: () => Date.now(),
  generate: async (purpose, payload) => {
    prompts.push({ purpose, payload: payload as (typeof prompts)[number]['payload'] });
    if (purpose === 'review') return { data: { oneLine: '讲清了定义', points: [{ label: '放弃的最高价值', status: 'mastered', note: '说对了' }], misconceptions: [], quiz: [] }, model: 'room-model' };
    if (purpose === 'reflect') return { text: '说得对，再想想隐性成本。', covered: [0], model: 'room-model' };
    return { text: `嘉宾回应 ${prompts.length}`, covered: [prompts.length % 3], model: 'room-model' };
  }
});
const tick = () => new Promise(r => setTimeout(r, 0));

// 浏览器这一侧的 WebSocket
class FakeWebSocket {
  static OPEN = 1;
  readyState = 0;
  onopen: (() => void) | null = null;
  onmessage: ((e: { data: string }) => void) | null = null;
  onclose: (() => void) | null = null;
  server: Sock;
  url: string;
  constructor(url: string) {
    this.url = url;
    let member: string | null = null;
    const self = this;
    this.server = { open: true, inbox: [], send: d => self.onmessage?.({ data: d }), close() { this.open = false; self.readyState = 3; self.onclose?.(); }, memberId: () => member, setMember: id => { member = id; } };
    sockets.push(this.server);
    setTimeout(() => { this.readyState = 1; this.onopen?.(); });
  }
  send(d: string) { void core.onMessage(this.server, d); }
  close() { this.server.open = false; this.readyState = 3; this.onclose?.(); }
}
(globalThis as Record<string, unknown>).WebSocket = FakeWebSocket;

// 后端：创建房间需要访问口令
globalThis.fetch = (async (url: string, init?: RequestInit) => {
  assert.equal(url, 'https://api.example.workers.dev/api/rooms');
  assert.equal((init!.headers as Record<string, string>).Authorization, 'Bearer team-pass');
  const body = JSON.parse(String(init!.body));
  return Response.json(await core.init({ ...body, roomId: 'q'.repeat(22) }));
}) as typeof fetch;

const { getState, update } = await import('../src/store.ts');
const actions = await import('../src/actions.ts');
const { inviteLink, parseInvite, mySpeakingTurn } = await import('../src/lib/room.ts');

// 一位同学：直接说房间协议
function classmate() {
  let member: string | null = null;
  const s: Sock = { open: true, inbox: [], send(d) { this.inbox.push(JSON.parse(d)); }, close() { this.open = false; }, memberId: () => member, setMember: id => { member = id; } };
  sockets.push(s);
  return { s, say: (m: object) => core.onMessage(s, JSON.stringify(m)) };
}

test('a host invites a classmate, they take turns with an AI guest, and each gets a personal review', async () => {
  update(st => {
    st.config.aiMode = 'remote'; st.config.remote = { url: 'https://api.example.workers.dev', password: 'team-pass' };
    st.plan = { mode: 'roundtable', concept: '机会成本', topic: '选择的代价是什么？', keyPoints: ['放弃的最高价值', '沉没成本无关', '隐性成本'], opening: '先说说什么叫机会成本？',
      personas: [{ id: 'r0', tone: 'c', name: '林教授', title: '经济学', persona: '' }, { id: 'r1', tone: 'a', name: '周老师', title: '心理学', persona: '' }], source: 'api', model: 'room-model', previous: null };
  });
  await actions.inviteFriends('束束');
  await tick(); await tick();
  let s = getState().session!;
  assert.ok(s.room, 'a room session');
  assert.equal(s.room!.host, true);
  assert.equal(s.room!.status, 'lobby');
  assert.equal(getState().ui.invite, true, 'the invite sheet opens');
  assert.ok(!JSON.stringify(s.room).includes('team-pass'), 'the access password is not part of the room');

  // 邀请链接能被解析回来，里面没有访问口令
  const url = inviteLink(s.room!, 'https://maxlen727.github.io/debating-ground/app/');
  assert.ok(!url.includes('team-pass'));
  const parsed = parseInvite(new URL(url).hash)!;
  assert.deepEqual(parsed, { backend: 'https://api.example.workers.dev', roomId: s.room!.roomId, invite: s.room!.invite });
  assert.equal(parseInvite('#join=bad'), null);

  const mate = classmate();
  await mate.say({ type: 'hello', invite: parsed.invite, name: '小明' });
  await tick();
  assert.deepEqual(getState().session!.room!.members.map(m => m.name), ['束束', '小明']);

  actions.startRoom();
  await tick();
  s = getState().session!;
  assert.equal(s.room!.status, 'live');
  assert.equal(mySpeakingTurn(s), true, 'the host speaks first');

  await actions.send('机会成本是放弃的最好选择。');
  await tick();
  s = getState().session!;
  const msgs = s.entries.filter(e => e.kind === 'msg');
  assert.deepEqual(msgs.map(m => m.kind === 'msg' && [m.name, !!m.human]), [['林教授', false], ['束束', true], ['周老师', false]]);
  assert.equal(mySpeakingTurn(s), false, 'now it is the classmate');
  await actions.send('插话');
  assert.equal(getState().ui.toast, '还没轮到你发言。');

  await mate.say({ type: 'say', text: '沉没成本不该算。' });
  await tick();
  s = getState().session!;
  const peer = s.entries.filter(e => e.kind === 'msg').find(e => e.kind === 'msg' && e.name === '小明');
  assert.ok(peer && peer.kind === 'msg' && !peer.human && peer.memberId, 'the classmate’s words are not mine');

  // 个人提示和小结只发给自己
  await actions.hint();
  assert.match(getState().ui.hint!, /嘉宾回应/);
  assert.equal(mate.s.inbox.filter(m => m.type === 'answer').length, 0);
  actions.addReflection();
  const card = getState().session!.entries.find(e => e.kind === 'reflect')!;
  actions.setReflectionDraft(card.id, '放弃的最好选择就是机会成本');
  await actions.submitReflection(card.id);
  s = getState().session!;
  assert.equal(s.room!.reflections[0].feedback, '说得对，再想想隐性成本。');
  assert.equal((s.entries.find(e => e.id === card.id) as { feedback: string }).feedback, '说得对，再想想隐性成本。');
  // 新的房间状态到来时，小结卡留在原处
  await mate.say({ type: 'pass' });
  await tick();
  assert.ok(getState().session!.entries.some(e => e.id === card.id));

  // 房主结束讨论：自动生成自己的复盘，存进知识库
  await actions.finish();
  for (let i = 0; i < 5; i++) await tick();
  s = getState().session!;
  assert.equal(s.finished, true);
  assert.equal(getState().ui.screen, 'review');
  assert.equal(s.review?.oneLine, '讲清了定义');
  assert.equal(getState().library[0].sessionId, s.id);
  assert.equal(getState().library[0].concept, '机会成本');
  // 复盘请求里，只有房主自己是学习者，小结也带上了
  const review = prompts.find(p => p.purpose === 'review')!;
  const learner = review.payload.session.messages.filter(m => m.name.includes('学习者')).map(m => m.text);
  assert.deepEqual(learner, ['机会成本是放弃的最好选择。', '放弃的最好选择就是机会成本']);
  // 房间凭据不进入知识卡片
  assert.ok(!JSON.stringify(getState().library).includes(s.room!.token));
});
