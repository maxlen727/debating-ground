import { test } from 'node:test';
import assert from 'node:assert/strict';
import {
  READER_LIMITS,
  contextQuery,
  materialContext,
  outlineText,
  readSections,
  splitMaterial,
  startContext,
} from '../src/lib/reader.ts';

const para = (n: number, word = '内容') => Array.from({ length: n }, () => word).join('');

test('splitMaterial (#144): headings become sections — markdown, numbered and named ones', () => {
  const s = splitMaterial(
    [
      '前面的一段话。',
      '',
      '# 摘要',
      '这是摘要。',
      '## 1 引言',
      '引言的正文。',
      '2.1 方法细节',
      '方法的正文。',
      'Conclusion',
      '结论的正文。',
      // 句号结尾的短行不是标题
      '3 这一行以句号结尾。',
      '仍然属于结论。',
    ].join('\n'),
  );
  assert.deepEqual(
    s.map(x => x.heading),
    ['开头', '摘要', '1 引言', '2.1 方法细节', 'Conclusion'],
  );
  assert.deepEqual(
    s.map(x => x.n),
    [1, 2, 3, 4, 5],
  );
  assert.match(s[4].text, /3 这一行以句号结尾。\n仍然属于结论/);
});

test('splitMaterial: no structure → chunks by length; a long section splits at paragraphs', () => {
  const plain = splitMaterial(Array.from({ length: 6 }, () => para(800)).join('\n\n'));
  assert.ok(plain.length >= 2);
  assert.ok(plain.every(s => s.text.length <= READER_LIMITS.section));
  assert.equal(plain[0].heading, '第 1 部分');
  const long = splitMaterial(`# 方法\n${Array.from({ length: 5 }, () => para(1000)).join('\n\n')}`);
  assert.deepEqual(long.map(s => s.heading).slice(0, 2), ['方法（1）', '方法（2）']);
  // 节太多时整篇按更大的块重新切，最多 READER_LIMITS.sections 节
  const many = splitMaterial(
    Array.from({ length: 120 }, (_, i) => `# 第${i}节\n${para(20)}`).join('\n'),
  );
  assert.ok(many.length <= READER_LIMITS.sections);
});

test('readSections is capped and marks what was cut', () => {
  const s = splitMaterial(`# 一\n${para(2800, '甲')}\n# 二\n乙丙丁。\n# 三\n没有。`);
  const r = readSections(s, [1, 2], 1000);
  assert.ok(r.length <= 1000, String(r.length));
  assert.match(r, /…（这一节后面省略）\n\n§2 二/);
});

test('materialContext (#166): a short material goes whole; a long one is the outline plus the most relevant sections', () => {
  const short = splitMaterial('# 一\n甲乙。\n# 二\n注意力在这里。');
  assert.equal(materialContext(short, '随便'), '§1 一\n甲乙。\n\n§2 二\n注意力在这里。');
  // 长材料：十节，每节两千多字，只有第 7 节讲注意力
  const long = splitMaterial(
    Array.from(
      { length: 10 },
      (_, i) =>
        `## ${i + 1} 第${i + 1}部分\n${(i === 6 ? '注意力机制让模型关注重要的词。' : '这一节讲别的内容。').repeat(150)}`,
    ).join('\n\n'),
  );
  assert.equal(long.length, 10);
  const ctx = materialContext(long, '讨论题：注意力机制为什么有用？\n我觉得注意力就是加权平均');
  assert.ok(ctx.length <= READER_LIMITS.excerpt, String(ctx.length));
  assert.match(ctx, /^目录：\n§1 1 第1部分\n/);
  assert.match(
    ctx,
    /§10 10 第10部分\n\n§7 7 第7部分\n注意力机制/,
    'the relevant section comes right after the outline',
  );
  // 一节也不相关：给开头几节
  assert.match(startContext(long), /目录：[\s\S]*\n\n§1 1 第1部分\n/);
  assert.equal(startContext(long), materialContext(long, ''));
  // 英文按整个词比较
  const en = splitMaterial(
    Array.from(
      { length: 8 },
      (_, i) =>
        `## ${i + 1} Part\n${(i === 4 ? 'Transformers use attention. ' : 'Other things here. ').repeat(90)}`,
    ).join('\n\n'),
  );
  assert.match(materialContext(en, 'why does attention work'), /\n\n§5 5 Part\nTransformers/);
});

test('contextQuery: the topic, the branch and the last two messages', () => {
  const q = contextQuery({
    topic: '题目',
    branch: { topic: '分支' },
    messages: [{ text: '很早的话' }, { text: '倒数第二' }, { text: '最后一句' }],
  });
  assert.equal(q, '题目\n分支\n倒数第二\n最后一句');
});

test('outlineText stops at its budget', () => {
  const many = Array.from({ length: 80 }, (_, i) => ({
    n: i + 1,
    heading: '很长的标题'.repeat(8),
    text: 'x',
  }));
  const o = outlineText(many);
  assert.ok(o.length <= READER_LIMITS.outline + 3);
  assert.match(o, /……$/);
});
