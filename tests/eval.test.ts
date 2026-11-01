// 提示词评测脚本自己的逻辑：指标算得对、合格线判得对（不调用模型）
import { test } from 'node:test';
import assert from 'node:assert/strict';
import { SCENARIOS, measure, problems } from '../scripts/eval-prompts.ts';
import { buildRequest } from '../server/ai.ts';

test('the interrogation pattern from the real transcript fails the eval', () => {
  const guests = new Set(['陈默', '林知远']);
  const msgs = [
    { kind: 'msg' as const, name: '陈默', text: '亚托莉要是真退了，损失的是她，还是群？' },
    { kind: 'msg' as const, name: '学习者', text: '群的损失' },
    { kind: 'msg' as const, name: '林知远', text: '“是群的损失”是判断，不是事实。我想追问：你们事先约定过吗？' },
    { kind: 'msg' as const, name: '学习者', text: '没有' },
    { kind: 'msg' as const, name: '陈默', text: '这里得温和纠正一下。那谁有资格决定？' },
    { kind: 'msg' as const, name: '林知远', text: '我补充一点程序。你们群里撤之前有谁被问过吗？' }
  ];
  const m = measure(msgs, guests, new Set([5]));
  assert.equal(m.aiTurns, 4);
  assert.equal(m.questions, 4);
  assert.equal(m.longestQuestionRun, 4);
  assert.equal(m.askedAfterGuest, 1);
  for (const w of ['温和纠正', '我想追问', '是判断', '不是事实']) assert.ok(m.stock.includes(w), w);
  const p = problems(m);
  assert.ok(p.some(x => /100%/.test(x)));
  assert.ok(p.some(x => /连续 4 段/.test(x)));
  assert.ok(p.some(x => /接其他嘉宾/.test(x)));
  assert.ok(p.some(x => /套话/.test(x)));
});

test('a partner-style conversation passes', () => {
  const guests = new Set(['陈默']);
  const msgs = [
    { kind: 'msg' as const, name: '陈默', text: '我先说个例子：很多群把常驻的 bot 当吉祥物。' },
    { kind: 'msg' as const, name: '学习者', text: '对，我们群也是' },
    { kind: 'msg' as const, name: '陈默', text: '那它被踢走的时候，大家其实在争“它算不算自己人”。你们群里谁最舍不得它？' },
    { kind: 'msg' as const, name: '学习者', text: '维护它的那个人' },
    { kind: 'msg' as const, name: '陈默', text: '这就说得通了：对他来说，踢走亚托莉像是在否定他花的心思。' }
  ];
  assert.deepEqual(problems(measure(msgs, guests)), []);
});

test('every scenario builds valid prompts', () => {
  for (const s of SCENARIOS) {
    const session = { mode: s.mode, concept: s.concept, topic: s.topic, keyPoints: s.keyPoints, roles: s.roles, messages: [{ kind: 'msg' as const, name: s.roles[0].name, text: s.opening }], context: s.context };
    assert.ok(buildRequest('discuss', { session, roleId: s.roles[0].id }).messages.length === 2, s.name);
    assert.ok(buildRequest('review', { session }).messages.length === 2, s.name);
  }
});
