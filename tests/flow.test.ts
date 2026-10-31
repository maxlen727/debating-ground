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
const apiOn = () => update(s => { s.config.aiMode = 'api'; s.ai = { available: true, configured: true, verified: true, baseURL: '', model: 'chosen', remoteVerified: false, remoteModel: '' }; });
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

test('remote backend: connect with the access password, then generate through it', async () => {
  const seen: { url: string; auth: string | null; body: string }[] = [];
  globalThis.fetch = (async (url: string, init?: RequestInit) => {
    seen.push({ url: String(url), auth: new Headers(init?.headers).get('Authorization'), body: String(init?.body ?? '') });
    if (String(url).endsWith('/api/config')) return new Response(JSON.stringify({ configured: true, verified: false, baseURL: '', model: 'team-model' }));
    if (String(url).endsWith('/api/test')) return new Response(JSON.stringify({ text: '连接成功。', model: 'team-model' }));
    return new Response(JSON.stringify({ text: '那做家教的 500 元呢？', covered: [1], model: 'team-model' }));
  }) as typeof fetch;
  const { connectRemote, checkRemoteURL } = await import('../src/lib/api.ts');
  assert.throws(() => checkRemoteURL('http://api.example.com'), /HTTPS/);
  await connectRemote('https://api.example.workers.dev/', 'open-sesame', new AbortController().signal);
  assert.equal(getState().config.aiMode, 'remote');
  assert.equal(getState().config.remote.url, 'https://api.example.workers.dev');
  assert.equal(getState().ai.remoteVerified, true);
  learningPlan(); actions.startSession();
  await actions.send('少玩了一天。');
  const gen = seen.at(-1)!;
  assert.equal(gen.url, 'https://api.example.workers.dev/api/generate');
  assert.equal(gen.auth, 'Bearer open-sesame');
  assert.deepEqual(getState().session!.covered, [1]);
  // 口令不进入讨论记录或知识库
  assert.ok(!JSON.stringify(getState().session).includes('open-sesame'));
});

test('demo: unfamiliar terms are marked, explained on tap, and saved with the knowledge card', async () => {
  update(s => { s.config.concept = '机会成本'; s.config.mode = 'socratic'; });
  await actions.prepare();
  actions.startSession();
  await actions.send('少玩了一天');
  const reply = getState().session!.entries.filter(e => e.kind === 'msg').at(-1)!;
  assert.ok(reply.kind === 'msg' && reply.terms?.includes('稀缺性'));
  await actions.openTerm('稀缺性', reply.id);
  assert.equal(getState().ui.term?.term, '稀缺性');
  assert.match(getState().ui.term!.definition, /有限/);
  assert.deepEqual(getState().session!.glossary!.map(g => g.term), ['稀缺性']);
  // 没标出来的词查不了
  update(s => { s.ui.term = null; });
  await actions.openTerm('机会成本', reply.id);
  assert.equal(getState().ui.term, null);
  // 第二次打开直接用记下的解释
  await actions.openTerm('稀缺性', reply.id);
  assert.equal(getState().ui.busy, null);
  await actions.finish();
  assert.deepEqual(getState().library[0].terms!.map(t => t.term), ['稀缺性']);
});

test('single-player debate: a seat left for classmates is played by AI', async () => {
  update(s => {
    s.config.mode = 'debate';
    s.config.seats.B[0] = { type: 'peer', bg: '' };
    s.plan = { mode: 'debate', concept: '机会成本', topic: '上大学的最大成本是学费', keyPoints: ['放弃的最佳选择', '区别于沉没成本'], opening: '', personas: [], source: 'demo', previous: null };
  });
  actions.startSession();
  await actions.send('我方立论……');
  const t = actions.currentTurn(getState().session);
  assert.equal(t?.role.id, 'B1');
  await actions.generateSeat();
  assert.ok(getState().session!.cursor >= 2, 'AI spoke for 反方一辩');
});

test('streaming in API mode: text shows while it is written; a failure removes it and keeps progress', async () => {
  const { getLive } = await import('../src/lib/stream.ts');
  apiOn(); learningPlan(); actions.startSession();
  const enc = new TextEncoder();
  let fail = false;
  const seenLive: string[] = [];
  globalThis.fetch = (async (_url: unknown, init?: RequestInit) => {
    assert.equal(JSON.parse(String(init!.body)).stream, true);
    const body = new ReadableStream({ async start(c) {
      c.enqueue(enc.encode('event: delta\ndata: {"t":"先想想"}\n\n'));
      await new Promise(r => setTimeout(r, 5));
      seenLive.push(getLive()?.text ?? '');
      if (fail) c.enqueue(enc.encode('event: error\ndata: {"error":"模型输出被截断，本次内容未采用。","status":502}\n\n'));
      else c.enqueue(enc.encode('event: delta\ndata: {"t":"代价。"}\n\nevent: done\ndata: {"text":"先想想代价。","covered":[0],"terms":[],"model":"chosen"}\n\n'));
      c.close();
    } });
    return new Response(body, { headers: { 'Content-Type': 'text/event-stream' } });
  }) as typeof fetch;
  await actions.send('少玩了一天');
  assert.deepEqual(seenLive, ['先想想']);
  let s = getState().session!;
  const last = s.entries.at(-1)!;
  assert.ok(last.kind === 'msg' && last.text === '先想想代价。');
  assert.equal(getLive(), null, 'the live bubble is handed over to the saved message');
  const before = s.entries.length;
  fail = true;
  await actions.speak('r0');
  s = getState().session!;
  assert.equal(s.entries.length, before, 'no half answer is kept');
  assert.equal(getLive(), null);
  assert.match(getState().ui.error, /截断/);
});
