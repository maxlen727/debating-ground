import { test } from 'node:test';
import assert from 'node:assert/strict';
import { normalizeConfig, buildRequest, finalize, splitCover, splitTerms, extractJSON, complete } from '../server/ai.ts';
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
  assert.match(r.messages[0].content, /JSON/); assert.match(r.messages[0].content, /3 个/); assert.match(r.messages[0].content, /薄弱点/);
  assert.deepEqual(user(r).previous.weakPoints, ['区别于沉没成本']);
  assert.throws(() => buildRequest('prepare', { concept: '', mode: 'socratic' }));
  assert.throws(() => buildRequest('prepare', { concept: 'x', mode: 'nope' }));
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
