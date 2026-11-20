import { test } from 'node:test';
import assert from 'node:assert/strict';
import type { ReadAction } from '../shared/types.ts';
import {
  READER_LIMITS,
  excerptFor,
  readSections,
  runReader,
  searchMaterial,
  splitMaterial,
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

test('reader tools: read is capped, search shows where, excerpt falls back to a search', () => {
  const s = splitMaterial(
    `# 一\n${para(2800, '甲')}\n# 二\n乙丙丁注意力机制在这里出现。\n# 三\n没有。`,
  );
  const r = readSections(s, [1, 2], 1000);
  assert.ok(r.length <= 1000, String(r.length));
  assert.match(r, /…（这一节后面省略）\n\n§2 二/);
  assert.match(searchMaterial(s, '注意力'), /^§2 二：…/);
  assert.match(searchMaterial(s, '不存在的词'), /没有找到/);
  const ex = excerptFor('论文', s, { name: '注意力机制', why: '', sections: [], needs: [] });
  assert.match(ex.excerpt, /^§2 二/);
  assert.equal(ex.point, '注意力机制');
  assert.ok(
    excerptFor('论文', s, { name: 'x', why: '', sections: [1, 2, 3], needs: [] }).excerpt.length <=
      READER_LIMITS.excerpt + 40,
  );
});

test('runReader: one action per step, observations fed back, last step must finish, null stops', async () => {
  const s = splitMaterial('# 一\n甲乙。\n# 二\n注意力在这里。');
  const seen: unknown[] = [];
  const script: ReadAction[] = [
    { thought: '先读第一节', action: 'read', sections: [1] },
    { thought: '找找注意力', action: 'search', query: '注意力' },
    {
      thought: '够了',
      action: 'finish',
      map: {
        title: '',
        summary: '讲注意力',
        points: [{ name: '注意力', why: 'w', sections: [2], needs: [] }],
      },
    },
  ];
  const res = await runReader('材料', s, async p => {
    seen.push(structuredClone(p));
    return script[p.steps.length];
  });
  assert.equal(res!.map.title, '材料', 'falls back to the material title');
  assert.deepEqual(
    res!.steps.map(st => st.action),
    ['读第 1 节', '搜「注意力」', '整理成地图'],
  );
  const third = seen[2] as { steps: { observation: string }[] };
  assert.match(third.steps[0].observation, /甲乙/);
  assert.match(third.steps[1].observation, /§2 二：…注意力在这里/);
  // 一直不结束：走到上限的那一步带 last，之后放弃
  const lasts: boolean[] = [];
  const never = await runReader('材料', s, async p => {
    lasts.push(!!p.last);
    return { thought: '', action: 'read', sections: [1] };
  });
  assert.equal(never, null);
  assert.equal(lasts.length, READER_LIMITS.steps);
  assert.deepEqual(lasts.slice(-2), [false, true]);
  assert.equal(await runReader('材料', s, async () => null), null);
});
