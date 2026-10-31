// 学习流程的集成测试：在 Node 里直接驱动 actions，不渲染界面
import { test, beforeEach } from 'node:test';
import assert from 'node:assert/strict';

const store: Record<string, string> = {};
(globalThis as Record<string, unknown>).localStorage = { getItem: (k: string) => store[k] ?? null, setItem: (k: string, v: string) => { store[k] = v; } };

const { getState, update, resetForTests, defaultConfig } = await import('../src/store.ts');
const actions = await import('../src/actions.ts');
const { setDemoDelay } = await import('../src/lib/ai.ts');
const { schedule, INTERVALS, DAY } = await import('../src/lib/srs.ts');
const { debateTurns, slots } = await import('../src/lib/debate.ts');
setDemoDelay(0);

const realFetch = globalThis.fetch;
function mockFetch(fn: (body: { purpose: string; payload: Record<string, unknown> }) => Response | Promise<Response>) {
  globalThis.fetch = (async (_url: unknown, init?: RequestInit) => fn(JSON.parse(String(init?.body ?? '{}')))) as typeof fetch;
}
const apiOn = () => update(s => { s.config.aiMode = 'api'; s.ai = { available: true, configured: true, verified: true, baseURL: '', model: 'chosen' }; });
const learningPlan = () => update(s => {
  s.plan = { mode: 'socratic', concept: '机会成本', topic: '代价是什么？', keyPoints: ['放弃的最佳选择', '区别于沉没成本'], opening: '你觉得呢？', personas: [{ id: 'r0', tone: 'c', name: '苏珩', title: '追问者', persona: '' }], source: 'api', model: 'chosen', previous: null };
});

beforeEach(() => { for (const k of Object.keys(store)) delete store[k]; globalThis.fetch = realFetch; resetForTests(); });

test('demo mode runs the whole loop: prepare, discuss, reflect, review, knowledge card, review again', async () => {
  update(s => { s.config.concept = '机会成本'; s.config.mode = 'socratic'; });
  await actions.prepare();
  assert.equal(getState().ui.screen, 'prepare');
  assert.equal(getState().plan!.keyPoints.length, 5);
  actions.startSession();
  for (const t of ['少玩了一天', '只算家教', '健身卡不算']) await actions.send(t);
  const s = getState().session!;
  assert.ok(s.covered.length >= 3, 'key points light up');
  const reflection = s.entries.find(e => e.kind === 'reflect');
  assert.ok(reflection, 'a reflection card appears after the third turn');
  actions.setReflectionDraft(reflection!.id, '机会成本是放弃的最好选择');
  await actions.submitReflection(reflection!.id);
  const done = getState().session!.entries.find(e => e.kind === 'reflect');
  assert.ok(done?.kind === 'reflect' && done.feedback.length > 0);
  await actions.finish();
  assert.equal(getState().ui.screen, 'review');
  assert.equal(getState().library.length, 1);
  const card = getState().library[0];
  assert.ok(card.due > Date.now() + 23 * 3600_000, 'first review is tomorrow');
  actions.rateCard(card.id, true);
  assert.equal(getState().library[0].level, 1);
  assert.ok(getState().library[0].due > Date.now() + 2.9 * DAY);
  assert.ok(store['sibian-v3'].includes('"library"'), 'state is persisted');
});

test('API failure keeps the learner message and adds no AI reply', async () => {
  mockFetch(() => new Response(JSON.stringify({ error: 'API 返回 401：密钥无效或已过期。' }), { status: 502 }));
  apiOn(); learningPlan(); actions.startSession();
  await actions.send('少玩了一天。');
  const msgs = getState().session!.entries.filter(e => e.kind === 'msg');
  assert.equal(msgs.length, 2);
  assert.equal(msgs.at(-1)!.kind === 'msg' && msgs.at(-1)!.who, 'learner');
  assert.match(getState().ui.error, /401/);
  assert.ok(!JSON.stringify(store).includes('apiKey'));
});

test('a reply lights up the key points the model reports', async () => {
  let sent: { purpose: string; payload: Record<string, unknown> } | undefined;
  mockFetch(b => { sent = b; return new Response(JSON.stringify({ text: '那做家教的 500 元呢？', covered: [0], model: 'chosen' })); });
  apiOn(); learningPlan(); actions.startSession();
  await actions.send('少玩了一天。');
  assert.equal(sent!.purpose, 'discuss'); assert.equal(sent!.payload.roleId, 'r0');
  const snap = sent!.payload.session as { messages: { name: string }[] };
  assert.equal(snap.messages.at(-1)!.name, '学习者');
  const last = getState().session!.entries.at(-1)!;
  assert.ok(last.kind === 'msg' && last.source === 'api');
  assert.deepEqual(getState().session!.covered, [0]);
});

test('debate reply advances exactly one seat and records model provenance; cancel keeps the seat', async () => {
  mockFetch(() => new Response(JSON.stringify({ text: '我方反对。', covered: [], model: 'chosen' })));
  apiOn();
  update(s => { s.config.seats.A[0].type = 'ai'; s.config.seats.A[1].type = 'human'; s.plan = { mode: 'debate', concept: '机会成本', topic: '学费是最大成本', keyPoints: ['a', 'b'], opening: '', personas: [], source: 'api', previous: null }; });
  actions.startSession();
  await actions.generateSeat();
  assert.equal(getState().session!.cursor, 1);
  const last = getState().session!.entries.at(-1)!;
  assert.ok(last.kind === 'msg' && last.source === 'api' && last.model === 'chosen');

  const { cancelAI } = await import('../src/lib/ai.ts');
  globalThis.fetch = ((_u: unknown, init?: RequestInit) => new Promise((_, reject) => init!.signal!.addEventListener('abort', () => reject(new Error('aborted'))))) as typeof fetch;
  const pending = actions.generateSeat(); cancelAI(); await pending;
  assert.equal(getState().session!.cursor, 1);
  assert.match(getState().ui.error, /取消/);
});

test('debate needs a seat for the learner', () => {
  update(s => { s.config.seats.A[0].type = 'ai'; s.plan = { mode: 'debate', concept: 'x', topic: 't', keyPoints: ['a', 'b'], opening: '', personas: [], source: 'demo', previous: null }; });
  actions.startSession();
  assert.equal(getState().session, null);
});

test('spaced repetition and debate turn order', () => {
  assert.deepEqual(INTERVALS.slice(0, 4), [1, 3, 7, 14]);
  assert.equal(schedule(0, true, 0).days, 3);
  assert.equal(schedule(5, true, 0).days, 60);
  assert.equal(schedule(4, false, 0).days, 1);
  const c = defaultConfig();
  assert.equal(slots(c).length, 4);
  const turns = debateTurns(c);
  assert.deepEqual(turns.slice(0, 2).map(t => t.role.id), ['A1', 'B1']);
  assert.equal(turns.find(t => t.action === '质询')!.target, 'B2');
  assert.deepEqual(turns.slice(-2).map(t => t.role.id), ['B2', 'A2']);
});
