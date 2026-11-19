// 知识库搜索：按概念名、一句话、什么时候用、要点、例子、术语等找概念笔记。
// 不区分大小写、全角半角（NFKC），空格分开的几个词都要命中（同一张卡片里，可以在不同地方）。
// 排序看命中在哪儿：概念名最重要，其次是一句话、什么时候用和术语，其余最轻；分数一样时保持知识库原来的顺序。
// 原生版 data/Search.kt 是同一套规则，用这里生成的标准答案逐条比对（search-cases.json）。
import type { Note } from './notes.ts';

export const SEARCH_MIN_CARDS = 4; // 笔记少于这么多时不显示搜索框

export const normalizeForSearch = (s: string) =>
  s.normalize('NFKC').toLowerCase().replace(/\s+/g, ' ').trim();

// 一张卡片里可以被搜到的地方，按权重分组
function fields(c: Note): [number, string][] {
  return [
    [3, c.concept],
    [2, c.summary],
    [2, c.useWhen],
    [2, c.terms.map(t => t.term).join(' ')],
    [1, c.points.map(p => p.label).join(' ')],
    [1, c.examples.join(' ')],
    [1, c.misconceptions.map(m => m.said + ' ' + m.fix).join(' ')],
    [1, c.boundaries.join(' ')],
    [1, c.related.join(' ')],
    [1, c.sources.map(x => x.topic).join(' ')],
    [1, c.terms.map(t => t.definition).join(' ')],
  ];
}

/** 返回命中的笔记（按相关度排好）。查询为空时原样返回。 */
export function searchCards(cards: Note[], query: string): Note[] {
  const words = normalizeForSearch(query).split(' ').filter(Boolean);
  if (!words.length) return cards;
  const scored: { c: Note; score: number; i: number }[] = [];
  cards.forEach((c, i) => {
    const fs = fields(c).map(([w, text]) => [w, normalizeForSearch(text)] as const);
    let score = 0;
    for (const word of words) {
      const best = Math.max(0, ...fs.filter(([, text]) => text.includes(word)).map(([w]) => w));
      if (!best) return; // 有一个词哪儿都没有，这张卡片不算
      score += best;
    }
    scored.push({ c, score, i });
  });
  return scored.sort((a, b) => b.score - a.score || a.i - b.i).map(x => x.c);
}
