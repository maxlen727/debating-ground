import { test } from 'node:test';
import assert from 'node:assert/strict';
import { normalizeConfig, buildRequest, finalize, splitCover, splitTerms, extractJSON, complete, visibleText, streamGenerate } from '../server/ai.ts';
import type { SessionSnapshot } from '../shared/types.ts';

const cfg = { baseURL: 'https://example.com/v1/', apiKey: 'test-secret', model: 'chosen-model' };
const learning: SessionSnapshot = {
  mode: 'socratic', concept: '机会成本', topic: '打工赚 300 元的代价是什么？', keyPoints: ['放弃的最佳选择', '区别于沉没成本'],
  roles: [{ id: 'r0', name: '苏珩', title: '追问者', persona: '不放过含糊的说法' }],
  messages: [{ kind: 'msg', name: '苏珩', text: '你觉得代价是什么？' }, { kind: 'msg', name: '学习者', text: '少玩了一天。' }]
};
const debate: SessionSnapshot = {
  ...learning, mode: 'debate', roles: [], stages: [{ name: '交叉质询', kind: 'cross', rounds: 1 }], cursor: 0,
  turns: [{ role: { id: 'B2', team: 'B', index: 1, type: 'ai', bg: '关注教育公平' }, stage: 0, round: 0, action: '质询', target: 'A2' }],
  messages: [{ kind: 'msg', name: '正方 1 辩（学习者）', text: '学费是最大成本。', action: '立论' }]
};
const user = (r: { messages: { content: string }[] }) => JSON.parse(r.messages[1].content);

test('endpoint normalization and credential validation', () => {
  assert.equal(normalizeConfig(cfg).endpoint, 'https://example.com/v1/chat/completions');
  assert.equal(normalizeConfig({ ...cfg, baseURL: 'https://example.com/v1/chat/completions' }).endpoint, 'https://example.com/v1/chat/completions');
  for (const baseURL of ['http://example.com/v1', 'https://user:pass@example.com/v1', 'https://example.com/v1?key=x']) assert.throws(() => normalizeConfig({ ...cfg, baseURL }));
  assert.throws(() => normalizeConfig({ ...cfg, apiKey: '' }));
});

test('prepare asks for structured output and passes weak points from the last review', () => {
  const r = buildRequest('prepare', { concept: '机会成本', mode: 'roundtable', previous: { oneLine: '还差一点', weak: ['区别于沉没成本'] } });
  assert.equal(r.output, 'prepare');
  assert.match(r.messages[0].content, /JSON/);
  // 探索式讨论：4 到 5 个理解要点
  assert.match(r.messages[0].content, /keyPoints 是真正理解这个概念必须掌握的 4 到 5 个要点/);
  assert.match(r.messages[0].content, /薄弱点/);
  assert.deepEqual(user(r).previous.weakPoints, ['区别于沉没成本']);
  assert.throws(() => buildRequest('prepare', { concept: '', mode: 'socratic' }));
  assert.throws(() => buildRequest('prepare', { concept: 'x', mode: 'nope' }));
});

test('prepare differentiates debate core disputes from exploration key points', () => {
  // 辩论模式：3 到 4 个核心争点，指引讨论方向而不是清单
  const r = buildRequest('prepare', { concept: '机会成本', mode: 'debate' });
  assert.match(r.messages[0].content, /keyPoints 是本场辩论最值得交锋的 3 到 4 个核心争点或判断标准/);
  assert.match(r.messages[0].content, /不要把它们写成必须逐项完成的清单/);
  // 样例输出（手写，不是模型的真实返回）：3 个争点也要被 finalize 接受
  const sample = finalize({ messages: [], output: 'prepare', mode: 'debate' }, {
    text: JSON.stringify({
      concept: '机会成本', topic: '打工赚 300 元的代价不等于花的钱',
      keyPoints: ['代价是放弃的最佳选择', '显性成本与隐性成本', '沉没成本不该影响决策'],
      roles: [{ name: '正方', title: '立论者', persona: '稳' }], opening: ''
    }), model: 'm'
  }) as { data: { keyPoints: string[] } };
  assert.equal(sample.data.keyPoints.length, 3, '3 个核心争点也应被接受（数量允许变化）');
});

test('discussion turn carries role, mode rule, numbered key points and transcript', () => {
  const r = buildRequest('discuss', { session: learning, roleId: 'r0' });
  assert.match(r.messages[0].content, /苏珩/); assert.match(r.messages[0].content, /只问一个问题/); assert.match(r.messages[0].content, /\[\[要点/);
  assert.deepEqual(user(r).keyPoints[1], { number: 2, point: '区别于沉没成本' });
  assert.equal(user(r).transcript[1].speaker, '学习者');
  assert.throws(() => buildRequest('discuss', { session: learning, roleId: 'missing' }));
  assert.throws(() => buildRequest('discuss', { session: debate, roleId: 'r0' }));
});

test('debater keeps stance and seat but must use the concept accurately', () => {
  const r = buildRequest('debate', { session: debate });
  assert.match(r.messages[0].content, /不是教练/); assert.match(r.messages[0].content, /不能为了立场歪曲概念/);
  const ctx = user(r);
  assert.equal(ctx.seat.id, 'B2'); assert.equal(ctx.stance, '反对'); assert.equal(ctx.target, 'A2'); assert.equal(ctx.concept, '机会成本');
});

test('hint, explain, reflect and review requests', () => {
  assert.deepEqual(user(buildRequest('hint', { session: learning, covered: [0] })).notYetCovered, ['区别于沉没成本']);
  assert.match(buildRequest('explain', { session: learning, quote: '你觉得代价是什么？', kind: 'fact' }).messages[0].content, /事实/);
  assert.throws(() => buildRequest('explain', { session: learning, quote: 'x', kind: 'other' }));
  assert.equal(buildRequest('reflect', { session: learning, answer: '代价是放弃的最好选择' }).output, 'cover');
  assert.match(buildRequest('review', { session: learning }).messages[0].content, /只依据学习者本人/);
});

test('coverage marker is parsed, bounded and hidden from the text', () => {
  assert.deepEqual(splitCover('说得对。\n[[要点:1, 2, 9]]', 2), { text: '说得对。', covered: [0, 1] });
  assert.deepEqual(splitCover('没有标记', 2), { text: '没有标记', covered: [] });
  assert.deepEqual(splitCover('全角\n[[要点：2]]', 3).covered, [1]);
});

test('structured outputs are extracted tolerantly and validated', () => {
  assert.deepEqual(extractJSON('好的：\n```json\n{"a":1}\n```'), { a: 1 });
  assert.deepEqual(extractJSON('结果 {"a":2} 如上'), { a: 2 });
  assert.throws(() => extractJSON('没有 JSON'), /结构化/);
  const prep = finalize({ messages: [], output: 'prepare', mode: 'socratic' }, { text: JSON.stringify({ concept: '机会成本', topic: '代价是什么？', keyPoints: ['a', 'b', 'c'], roles: [{ name: '苏珩', title: '追问者', persona: 'p' }], opening: '你觉得呢？' }), model: 'm' });
  assert.equal((prep as { data: { roles: { name: string }[] } }).data.roles[0].name, '苏珩');
  assert.throws(() => finalize({ messages: [], output: 'prepare', mode: 'socratic' }, { text: '{"topic":"x","keyPoints":["a","b"],"roles":[]}', model: 'm' }), /不完整/);
  const rev = finalize({ messages: [], output: 'review', n: 2 }, { text: JSON.stringify({ oneLine: 'ok', points: [{ label: 'a', status: 'weird', note: '' }], quiz: [{ q: 'q', options: ['1', '2'], answer: 7, why: '' }] }), model: 'm' }) as { data: { points: { status: string }[]; quiz: { answer: number }[]; misconceptions: unknown[] } };
  assert.equal(rev.data.points[0].status, 'partial'); assert.equal(rev.data.quiz[0].answer, 0); assert.deepEqual(rev.data.misconceptions, []);
});

test('request contract, text extraction, and no silent fallback', async () => {
  let captured: { url: unknown; options: RequestInit } | undefined;
  const fakeFetch = (async (url: unknown, options: RequestInit) => { captured = { url, options }; return new Response(JSON.stringify({ choices: [{ message: { content: '我方反对这一主张。' }, finish_reason: 'stop' }] })); }) as typeof fetch;
  const result = await complete(normalizeConfig(cfg), buildRequest('debate', { session: debate }).messages, { fetchImpl: fakeFetch });
  assert.equal(result.text, '我方反对这一主张。');
  assert.equal((captured!.options.headers as Record<string, string>).Authorization, 'Bearer test-secret');
  assert.equal(captured!.options.redirect, 'error');
  assert.equal(JSON.parse(captured!.options.body as string).model, 'chosen-model');
  await assert.rejects(complete(normalizeConfig(cfg), [], { fetchImpl: (async () => new Response('test-secret', { status: 401 })) as typeof fetch }), (e: Error) => /401/.test(e.message) && !e.message.includes('test-secret'));
  await assert.rejects(complete(normalizeConfig(cfg), [], { fetchImpl: (async () => new Response(JSON.stringify({ choices: [{ message: { content: '' } }] }))) as typeof fetch }), /空|文本/);
  await assert.rejects(complete(normalizeConfig(cfg), [], { fetchImpl: (async () => new Response(JSON.stringify({ choices: [{ message: { content: '未完成' }, finish_reason: 'length' }] }))) as typeof fetch }), /截断/);
});

test('abort and timeout propagate as recoverable errors', async () => {
  const controller = new AbortController(); controller.abort();
  await assert.rejects(complete(normalizeConfig(cfg), [], { signal: controller.signal }), /取消/);
  const waits = ((_url: unknown, { signal }: RequestInit) => new Promise((_, reject) => signal!.addEventListener('abort', () => reject(signal!.reason), { once: true }))) as typeof fetch;
  await assert.rejects(complete(normalizeConfig(cfg), [], { fetchImpl: waits, timeoutMs: 15 }), /超时/);
});

test('discussion and debate turns mark unfamiliar terms, but never the concept or its key points', () => {
  for (const r of [buildRequest('discuss', { session: learning, roleId: 'r0' }), buildRequest('debate', { session: debate })]) {
    assert.match(r.messages[0].content, /\[\[术语:词1,词2\]\]/);
    assert.match(r.messages[0].content, /不要写讨论的概念本身或要点清单/);
    assert.deepEqual(r.exclude, ['机会成本', '放弃的最佳选择', '区别于沉没成本']);
  }
  const r = buildRequest('discuss', { session: learning, roleId: 'r0' });
  const raw = '时间也有稀缺性，就像边际效用递减一样。想想沉没成本呢？\n[[要点:1]]\n[[术语:稀缺性、边际效用,沉没成本,机会成本,不存在的词]]';
  const out = finalize(r, { text: raw, model: 'm' }) as { text: string; covered: number[]; terms: string[] };
  assert.equal(out.text, '时间也有稀缺性，就像边际效用递减一样。想想沉没成本呢？', 'both markers are removed');
  assert.deepEqual(out.covered, [0]);
  assert.deepEqual(out.terms, ['稀缺性', '边际效用'], 'key points, the concept and words not in the text are dropped');
  // 标记写在要点标记前面也能识别；没有术语就是空数组
  assert.deepEqual(splitTerms('甲乙丙\n[[术语:甲乙]]\n[[要点:]]').terms, ['甲乙']);
  assert.deepEqual((finalize(r, { text: '好的。\n[[要点:]]', model: 'm' }) as { terms: string[] }).terms, []);
  // 小结反馈不标术语
  assert.equal('terms' in finalize(buildRequest('reflect', { session: learning, answer: '放弃的' }), { text: '对。\n[[要点:1]]', model: 'm' }), false);
});

test('term cards explain the word without teaching the key points', () => {
  const r = buildRequest('term', { session: learning, term: '稀缺性', quote: '时间也有稀缺性' });
  assert.equal(r.output, 'term');
  assert.match(r.messages[0].content, /definition/); assert.match(r.messages[0].content, /不要讲解资料中 keyPoints/);
  assert.equal(user(r).term, '稀缺性');
  assert.deepEqual(user(r).keyPoints, learning.keyPoints);
  assert.throws(() => buildRequest('term', { session: learning, term: '沉没成本' }), /留给讨论/);
  assert.throws(() => buildRequest('term', { session: learning, term: '' }));
  const card = finalize(r, { text: '```json\n{"definition":"资源有限。","relation":"所以选择有代价。"}\n```', model: 'm' }) as { data: { term: string; definition: string; relation: string } };
  assert.deepEqual(card.data, { term: '稀缺性', definition: '资源有限。', relation: '所以选择有代价。' });
  assert.throws(() => finalize(r, { text: '{"relation":"x"}', model: 'm' }), /没有返回这个词的解释/);
});

// 模拟服务商的流式响应：每个片段一行 data:
const sse = (parts: string[], { finish = 'stop', extra = '' } = {}) => new Response(
  parts.map(p => `data: ${JSON.stringify({ choices: [{ delta: { content: p }, finish_reason: null }] })}\n\n`).join('')
  + `data: ${JSON.stringify({ choices: [{ delta: {}, finish_reason: finish }] })}\n\n` + extra + 'data: [DONE]\n\n',
  { headers: { 'Content-Type': 'text/event-stream' } });

test('visible text hides markers and anything that could be the start of the key', () => {
  assert.equal(visibleText('你好[[要点:1]]'), '你好');
  assert.equal(visibleText('你好\n['), '你好', 'a lone [ might be a marker');
  assert.equal(visibleText('你好 sk-ab', 'sk-abcdef'), '你好', 'a partial key is held back');
  assert.equal(visibleText('密钥 sk-abcdef 结束', 'sk-abcdef'), '密钥 [密钥已隐藏] 结束');
  // 越收越长时，显示的部分总是前一次的延长
  const raw = '机会成本是放弃的最好选择。\n[[要点:1]]\n[[术语:稀缺性]]';
  let last = '';
  for (let i = 1; i <= raw.length; i++) { const v = visibleText(raw.slice(0, i)); assert.ok(v.startsWith(last.trimEnd()), `prefix at ${i}`); last = v; }
  assert.equal(last, '机会成本是放弃的最好选择。');
});

test('streaming: deltas arrive in order, markers never show, the result is validated as before', async () => {
  let body: Record<string, unknown> = {};
  const seen: string[] = [];
  const fetchImpl = (async (_u: unknown, init: RequestInit) => { body = JSON.parse(String(init.body)); return sse(['你说到了“少玩', '一天”。', '\n[[要', '点:1]]\n[[术语:稀缺性]]']); }) as typeof fetch;
  const out = await complete(normalizeConfig(cfg), [], { fetchImpl, onText: t => seen.push(t) });
  assert.equal(body.stream, true);
  assert.deepEqual(seen, ['你说到了“少玩', '你说到了“少玩一天”。']);
  assert.equal(out.text, '你说到了“少玩一天”。\n[[要点:1]]\n[[术语:稀缺性]]');
  // 走 SSE 发给浏览器：delta 只有新增部分，done 里是 finalize 之后的结果
  const r = buildRequest('discuss', { session: learning, roleId: 'r0' });
  const chunks: string[] = [];
  const ok = await streamGenerate(normalizeConfig(cfg), r, { fetchImpl }, c => chunks.push(c));
  assert.equal(ok, true);
  assert.deepEqual(chunks.slice(0, 2), ['event: delta\ndata: {"t":"你说到了“少玩"}\n\n', 'event: delta\ndata: {"t":"一天”。"}\n\n']);
  assert.match(chunks.at(-1)!, /^event: done/);
  assert.deepEqual(JSON.parse(chunks.at(-1)!.split('data: ')[1]).covered, [0]);
});

test('streaming failures discard the partial text instead of keeping half an answer', async () => {
  const run = (res: () => Response, timeoutMs = 1000) => {
    const chunks: string[] = [];
    return streamGenerate(normalizeConfig(cfg), buildRequest('discuss', { session: learning, roleId: 'r0' }), { fetchImpl: (async () => res()) as typeof fetch, timeoutMs }, c => chunks.push(c)).then(ok => ({ ok, chunks }));
  };
  const cut = await run(() => sse(['说到一半'], { finish: 'length' }));
  assert.equal(cut.ok, false);
  assert.match(cut.chunks.at(-1)!, /^event: error[\s\S]*截断/);
  const broken = await run(() => sse(['说到一半'], { extra: `data: ${JSON.stringify({ error: { message: 'overloaded' } })}\n\n` }));
  assert.match(broken.chunks.at(-1)!, /服务商在生成过程中报错/);
  // 服务商不支持流式、直接返回 JSON：照常可用，一次给出全部文字
  const plain = await run(() => new Response(JSON.stringify({ choices: [{ message: { content: '好的。\n[[要点:]]' }, finish_reason: 'stop' }] })));
  assert.equal(plain.ok, true);
  assert.deepEqual(plain.chunks.map(c => c.split('\n')[0]), ['event: delta', 'event: done']);
  // 超时按“多久没有新内容”计算
  const stalled = await run(() => new Response(new ReadableStream({ start(c) { c.enqueue(new TextEncoder().encode(`data: ${JSON.stringify({ choices: [{ delta: { content: '先说' } }] })}\n\n`)); } }), { headers: { 'Content-Type': 'text/event-stream' } }), 50);
  assert.match(stalled.chunks.at(-1)!, /超时/);
});

test('debate reviews judge understanding of each core dispute, not who won', () => {
  const d = buildRequest('review', { session: debate }).messages[0].content;
  assert.match(d, /核心争点逐条/);
  assert.match(d, /不评判辩论输赢/);
  assert.match(d, /只依据学习者本人/);
  const l = buildRequest('review', { session: learning }).messages[0].content;
  assert.match(l, /按要点清单逐条/);
  assert.doesNotMatch(l, /争点/);
});

test('point suggestions: one short new point as JSON, never a repeat of an existing one', () => {
  const r = buildRequest('point', { session: learning });
  assert.equal(r.output, 'point');
  assert.match(r.messages[0].content, /不参与讨论/);
  assert.match(r.messages[0].content, /不要编造来源/);
  assert.match(r.messages[0].content, /"point"/);
  assert.deepEqual(user(r).keyPoints, learning.keyPoints);
  assert.match(buildRequest('point', { session: debate }).messages[0].content, /争点/);
  const ok = finalize(r, { text: '{"point":"时间也有机会成本","why":"学习者只算了钱"}', model: 'm' }) as { data: { point: string; why: string } };
  assert.deepEqual(ok.data, { point: '时间也有机会成本', why: '学习者只算了钱' });
  assert.throws(() => finalize(r, { text: JSON.stringify({ point: learning.keyPoints[0], why: '' }), model: 'm' }), /重复/);
  assert.throws(() => finalize(r, { text: '{"why":"x"}', model: 'm' }), /没有给出要点/);
});
