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

test('demo debate: a rebuttal quotes only what the opponent said, not the quote inside it', async () => {
  update(s => {
    s.config.mode = 'debate';
    s.plan = { mode: 'debate', concept: '机会成本', topic: '上大学的最大成本是学费', keyPoints: ['放弃的最佳选择', '区别于沉没成本'], opening: '', personas: [], source: 'demo', previous: null };
  });
  actions.startSession();
  await actions.send('我方立论……');
  for (let i = 0; i < 8 && !getState().session!.finished; i++) {
    const t = actions.currentTurn(getState().session);
    if (!t) break;
    if (t.role.type === 'human') await actions.send('我方回应……'); else await actions.generateSeat();
  }
  for (const m of getState().session!.entries) {
    if (m.kind === 'msg') assert.ok((m.text.match(/对方辩友刚才表示/g) ?? []).length <= 1, m.text);
  }
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

test('single-player debate: after the last closing statement the learner reads it before the review starts', async () => {
  update(s => {
    s.config.mode = 'debate';
    s.plan = { mode: 'debate', concept: '机会成本', topic: '上大学的最大成本是学费', keyPoints: ['放弃的最佳选择', '区别于沉没成本'], opening: '', personas: [], source: 'demo', previous: null };
  });
  actions.startSession();
  for (let i = 0; i < 40 && !getState().session!.awaitingReview; i++) {
    const t = actions.currentTurn(getState().session)!;
    if (t.role.type === 'human') await actions.send('我方认为学费不是最大成本，因为放弃的收入更高。'); else await actions.generateSeat();
  }
  const s = getState().session!;
  assert.equal(s.awaitingReview, true);
  assert.equal(s.finished, false, 'not finished yet: the last speech stays on screen');
  assert.equal(s.cursor, s.turns.length);
  assert.equal(getState().ui.screen, 'session');
  await actions.finish();
  assert.equal(getState().session!.finished, true);
  assert.equal(getState().session!.awaitingReview, false);
  assert.ok(getState().session!.review, 'review generated after the learner moves on');
});

test('adding key points: my own go straight in, AI suggestions wait for me to accept', async () => {
  update(s => { s.plan = { mode: 'socratic', concept: '机会成本', topic: '代价是什么？', keyPoints: ['放弃的最佳选择', '区别于沉没成本'], opening: '你觉得呢？', personas: [{ id: 'r0', tone: 'c', name: '苏珩', title: '追问者', persona: '' }], source: 'demo', previous: null }; });
  actions.startSession();
  assert.equal(actions.proposePoint('  隐性成本  '), true);
  assert.deepEqual(getState().session!.keyPoints, ['放弃的最佳选择', '区别于沉没成本', '隐性成本']);
  assert.equal(actions.proposePoint('隐性成本'), false, 'no duplicates');

  await actions.suggestPoint();
  const pending = getState().session!.proposedPoints!;
  assert.equal(pending.length, 1);
  assert.equal(pending[0].source, 'ai');
  assert.ok(!getState().session!.keyPoints.includes(pending[0].text), 'not tracked until accepted');
  actions.decidePoint(pending[0].id, true);
  assert.equal(getState().session!.keyPoints.at(-1), pending[0].text);
  assert.equal(getState().session!.proposedPoints!.length, 0);

  await actions.suggestPoint();
  actions.decidePoint(getState().session!.proposedPoints![0].id, false);
  assert.equal(getState().session!.keyPoints.length, 4, 'ignored suggestions are dropped');
  actions.proposePoint('五'); actions.proposePoint('六');
  assert.equal(actions.proposePoint('七'), false, 'at most six key points');
  assert.equal(getState().session!.keyPoints.length, 6);
});

test('read aloud only shows when the backend has iFlytek configured', () => {
  update(s => { s.config.aiMode = 'demo'; });
  assert.equal(actions.voiceAvailable(), false);
  update(s => { s.config.aiMode = 'api'; s.ai.ttsConfigured = true; });
  assert.equal(actions.voiceAvailable(), true);
  update(s => { s.config.aiMode = 'remote'; s.ai.remoteTtsConfigured = false; });
  assert.equal(actions.voiceAvailable(), false);
});

test('a specific event: the AI asks for background first, then everything uses it', async () => {
  const seen: Record<string, unknown>[] = [];
  mockFetch(({ purpose, payload }) => {
    seen.push({ purpose, ...payload });
    if (purpose === 'prepare' && !payload.context) return new Response(JSON.stringify({ data: { concept: '', topic: '', keyPoints: [], roles: [], opening: '', needsContext: '亚托莉是谁？为什么会有退群之争？' }, model: 'm' }));
    if (purpose === 'prepare') return new Response(JSON.stringify({ data: { concept: '亚托莉退群之争', topic: '一个 bot 该不该算群成员？', keyPoints: ['成员资格从哪来', '拟人化'], roles: [{ name: '陈默', title: '社会学', persona: '' }], opening: '我先说个看法。' }, model: 'm' }));
    return new Response(JSON.stringify({ text: '我同意一半。', covered: [], model: 'm' }));
  });
  apiOn();
  update(s => { s.config.concept = '亚托莉退群之争'; s.config.mode = 'socratic'; });
  await actions.prepare();
  assert.equal(getState().ui.screen, 'learn', 'stays on the first page');
  assert.equal(getState().ui.context!.ask, '亚托莉是谁？为什么会有退群之争？');
  await actions.prepare();
  assert.match(getState().ui.toast, /经过/, 'an empty answer is not sent');
  actions.setContextDraft('群里有个接了大模型的 bot 叫亚托莉，有人提议把它踢出去。');
  await actions.prepare();
  assert.equal(getState().ui.screen, 'prepare');
  assert.equal(getState().ui.context, null);
  assert.equal(getState().plan!.context, '群里有个接了大模型的 bot 叫亚托莉，有人提议把它踢出去。');
  actions.startSession();
  await actions.send('我觉得不该踢。');
  const turn = seen.at(-1) as { purpose: string; session: { context: string } };
  assert.equal(turn.purpose, 'discuss');
  assert.match(turn.session.context, /亚托莉/);
});

test('debater backgrounds follow the topic: each preparation replaces them, edits do not leak into the next debate', async () => {
  update(s => { s.config.concept = '机会成本'; s.config.mode = 'debate'; s.config.seats.A[1] = { type: 'ai', bg: '我上一场手动改的背景' }; });
  await actions.prepare();
  const seats = getState().config.seats;
  assert.equal(seats.A[0].bg, '在县城读完高中、家里供两个孩子上学的大一新生');
  assert.notEqual(seats.A[1].bg, '我上一场手动改的背景');
  assert.equal(seats.A[0].type, 'human', 'who sits in a seat is kept');
  update(s => { s.config.seats.B[0].bg = '这一场我改的'; });
  update(s => { s.config.concept = '边际效用'; });
  await actions.prepare();
  assert.match(getState().config.seats.B[0].bg, /边际效用/);
});

test('editing the topic offers to update the roles for it; topic and key points stay', async () => {
  const calls: Record<string, unknown>[] = [];
  mockFetch(({ purpose, payload }) => {
    calls.push(payload);
    return new Response(JSON.stringify({ data: { concept: '机会成本', topic: payload.topic ?? '原来的题目', keyPoints: ['生成的要点一', '生成的要点二'], roles: [{ name: payload.topic ? '新嘉宾' : '旧嘉宾', title: '经济学', persona: '按题目设定' }], opening: payload.topic ? '新的开场白' : '旧的开场白' }, model: 'm' }));
    void purpose;
  });
  apiOn();
  update(s => { s.config.concept = '机会成本'; s.config.mode = 'socratic'; });
  await actions.prepare();
  assert.equal(getState().plan!.rolesFor, '原来的题目');
  update(s => { s.plan!.topic = '我自己改的题目'; s.plan!.keyPoints = ['我的要点', '另一个']; });
  await actions.refreshRoles();
  const p = getState().plan!;
  assert.equal(calls.at(-1)!.topic, '我自己改的题目');
  assert.equal(p.topic, '我自己改的题目');
  assert.deepEqual(p.keyPoints, ['我的要点', '另一个']);
  assert.equal(p.personas[0].name, '新嘉宾');
  assert.equal(p.opening, '新的开场白');
  assert.equal(p.rolesFor, '我自己改的题目');
});

test('knowledge canvas (demo): branch from a word, talk there, bring one line back, review covers both', async () => {
  update(s => { s.config.concept = '机会成本'; s.config.mode = 'canvas'; });
  await actions.prepare();
  actions.startSession();
  await actions.send('少玩了一天');
  let s = getState().session!;
  const said = s.entries.findLast(e => e.kind === 'msg' && !e.human)!;
  assert.ok(said.kind === 'msg' && said.terms?.includes('稀缺性'));
  await actions.openBranch(said.id, '稀缺性');
  s = getState().session!;
  const b = s.branches![0];
  assert.equal(s.at, b.id);
  assert.equal(b.from, said.id);
  const opener = s.entries.at(-1)!;
  assert.ok(opener.kind === 'msg' && opener.branch === b.id && /稀缺性/.test(opener.text), 'the partner opens the branch on its topic');
  await actions.send('比如周末的时间');
  s = getState().session!;
  assert.ok(s.entries.slice(-2).every(e => e.branch === b.id), 'talk inside the branch stays in the branch');
  // 分支里，AI 看到的是主干到分叉点 + 这个分支
  const snap = actions.snapshot(s);
  assert.equal(snap.branch?.topic, '稀缺性');
  assert.ok(snap.messages.some(m => m.text === '少玩了一天'));
  assert.ok(snap.messages.some(m => m.text === '比如周末的时间'));

  actions.bringBack('');
  assert.equal(getState().session!.at, b.id, 'an empty line is not brought back');
  actions.bringBack('时间和钱一样有限，用在一处就不能用在别处');
  s = getState().session!;
  assert.equal(s.at, undefined);
  assert.equal(s.branches![0].gist, '时间和钱一样有限，用在一处就不能用在别处');
  const trunk = actions.snapshot(s);
  assert.equal(trunk.branch, undefined);
  assert.ok(!trunk.messages.some(m => m.text === '比如周末的时间'), 'the trunk does not see the branch talk');
  assert.ok(trunk.messages.some(m => m.kind === 'reflect' && m.text.includes('从分支「稀缺性」带回')), 'but it sees the line brought back');
  // 复盘看整场：分支里的发言带着分支标记
  const whole = actions.snapshot(s, true);
  assert.ok(whole.messages.some(m => m.text === '比如周末的时间' && m.action === '分支：稀缺性'));

  await actions.send('那做家教也是代价');
  assert.equal(getState().session!.entries.at(-1)!.branch, undefined, 'back on the trunk');
  await actions.finish();
  assert.ok(getState().session!.review, 'review generated');
  assert.equal(getState().library.length, 1);
});

test('knowledge canvas (API): a branch request carries only the path and the branch topic', async () => {
  apiOn();
  update(s => {
    s.plan = { mode: 'canvas', concept: '机会成本', topic: '代价是什么？', keyPoints: ['放弃的最佳选择', '区别于沉没成本'], opening: '你觉得呢？', personas: [{ id: 'r0', tone: 'c', name: '林枝', title: '伙伴', persona: '' }], source: 'api', model: 'chosen', previous: null };
  });
  actions.startSession();
  const sent: Record<string, unknown>[] = [];
  let n = 0;
  mockFetch(body => { sent.push(body.payload); n++; return Response.json({ text: `回复${n}`, covered: [], terms: n === 1 ? [] : [], model: 'chosen' }); });
  await actions.send('少玩了一天');            // 回复1 在主干
  await actions.send('那健身卡呢');            // 回复2 在主干
  const s = getState().session!;
  const first = s.entries.find(e => e.kind === 'msg' && e.text === '回复1')!;
  await actions.openBranch(first.id, '稀缺性');  // 回复3：分支开场
  const req = sent.at(-1)!.session as import('../shared/types.ts').SessionSnapshot;
  assert.deepEqual(req.branch, { topic: '稀缺性', from: '回复1', path: [] });
  const texts = req.messages.map(m => m.text);
  assert.ok(texts.includes('回复1') && !texts.includes('那健身卡呢') && !texts.includes('回复2'), 'nothing said after the fork point');
  assert.match(String(sent.at(-1)!.task), /稀缺性/);
  assert.equal(getState().session!.entries.at(-1)!.branch, getState().session!.at);
  // 太长的话题、非画布模式都不开分支
  await actions.openBranch(first.id, '很'.repeat(41));
  assert.equal(getState().session!.branches!.length, 1);
});

test('换我来讲 (demo): swap in, learner teaches, swap back with feedback; review sees the teaching turns', async () => {
  update(s => { s.config.concept = '机会成本'; s.config.mode = 'socratic'; });
  await actions.prepare();
  actions.startSession();
  await actions.toggleTeaching();
  let s = getState().session!;
  assert.equal(s.teaching, true);
  assert.ok(s.entries.some(e => e.kind === 'sys' && e.text.startsWith('换你来讲')));
  assert.match((s.entries.at(-1) as { text: string }).text, /当学生/);
  assert.equal(actions.snapshot(s).teaching, true);
  await actions.send('机会成本就是你为了一件事放弃的最好的另一件事');
  s = getState().session!;
  const mine = s.entries.findLast(e => e.kind === 'msg' && e.human)!;
  assert.ok(mine.kind === 'msg' && mine.action === actions.TEACHING);
  await actions.toggleTeaching();
  s = getState().session!;
  assert.equal(s.teaching, false);
  assert.match((s.entries.at(-1) as { text: string }).text, /讲得|例子/);
  assert.equal(actions.snapshot(s).teaching, undefined);
  assert.ok(actions.snapshot(s, true).messages.some(m => m.action === actions.TEACHING));
});

test('反向讲解 is folded into socratic: a saved teach choice opens as socratic', async () => {
  for (const k of Object.keys(store)) delete store[k];
  store['sibian-v3'] = JSON.stringify({ config: { ...defaultConfig(), mode: 'teach' }, plan: { mode: 'teach', concept: 'x' }, library: [] });
  resetForTests();
  assert.equal(getState().config.mode, 'socratic');
  assert.equal(getState().plan, null);
  const { MODE_ORDER, MODES } = await import('../shared/modes.ts');
  assert.ok(!MODE_ORDER.includes('teach'));
  assert.equal(MODES.teach.name, '反向讲解', 'old cards still show their mode name');
});
