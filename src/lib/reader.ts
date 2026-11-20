// 读材料（#144、#166）：把材料切成节，讨论时按话题挑出最相关的几节交给 AI。
// 学习者贴一篇材料就直接围绕它讨论：短材料整篇给 AI；长材料每轮给目录，加上和眼下话题最相关的几节（按词的重合度挑，
// 固定规则，不额外调用模型），总长有上限。原生版 data/Reader.kt 是同一套规则，用这里生成的标准答案比对。
import type { MaterialSection } from '../../shared/types.ts';
import { normalizeForSearch } from './search.ts';

export const READER_LIMITS = {
  text: 300_000, // 整篇材料最多多少字
  sections: 80, // 最多切成几节
  section: 3000, // 一节太长就按段落再切
  excerpt: 10_000, // 每次带给 AI 的材料（目录加原文）最多多少字
  outline: 2000, // 目录最多多少字
} as const;

/* ---------- 切节 ---------- */

// 像标题的一行：Markdown 标题、带编号的短行（1 引言、2.1 方法）、论文里常见的节名
const MD_HEADING = /^#{1,4}\s+(.+)$/;
const NUMBERED = /^(\d{1,2}(?:\.\d{1,2}){0,2})\.?\s+(\S.{0,58})$/;
const NAMED =
  /^(摘要|引言|前言|背景|相关工作|方法|实验|结果|讨论|结论|总结|参考文献|致谢|附录|abstract|introduction|background|related work|methods?|experiments?|results?|discussion|conclusions?|references|acknowledg(?:e)?ments?|appendix)\s*$/i;
const ENDS_LIKE_SENTENCE = /[。！？；，、.!?;,:：]$/;

function headingOf(line: string): string | null {
  const t = line.trim();
  if (!t || t.length > 60) return null;
  const md = t.match(MD_HEADING);
  if (md) return md[1].trim();
  if (ENDS_LIKE_SENTENCE.test(t)) return null;
  const num = t.match(NUMBERED);
  if (num) return `${num[1]} ${num[2].trim()}`;
  if (NAMED.test(t)) return t;
  return null;
}

// 按段落把一段长文字切成不超过 size 字的几块（段落太长就硬切）
function chunk(text: string, size: number): string[] {
  const paras = text
    .split(/\n\s*\n/)
    .map(p => p.trim())
    .filter(Boolean);
  const out: string[] = [];
  let cur = '';
  for (const p of paras) {
    for (let i = 0; i < p.length; i += size) {
      const piece = p.slice(i, i + size);
      if (cur && cur.length + piece.length + 2 > size) {
        out.push(cur);
        cur = '';
      }
      cur = cur ? cur + '\n\n' + piece : piece;
    }
  }
  if (cur) out.push(cur);
  return out;
}

/** 把材料切成节：认得出标题就按标题切，认不出就按长度切；一节太长再按段落切开。 */
export function splitMaterial(raw: string): MaterialSection[] {
  const text = raw.replace(/\r\n?/g, '\n').trim();
  const parts: { heading: string; lines: string[] }[] = [];
  let cur: { heading: string; lines: string[] } = { heading: '开头', lines: [] };
  for (const line of text.split('\n')) {
    const h = headingOf(line);
    if (h) {
      if (cur.lines.join('').trim() || cur.heading !== '开头') parts.push(cur);
      cur = { heading: h, lines: [] };
    } else cur.lines.push(line);
  }
  parts.push(cur);
  let blocks = parts
    .map(p => ({ heading: p.heading, body: p.lines.join('\n').trim() }))
    .filter(p => p.body);
  // 认不出结构（一个标题都没有）：整篇按长度切
  if (blocks.length <= 1 && (blocks[0]?.heading ?? '开头') === '开头')
    blocks = [{ heading: '', body: text }];
  let out: { heading: string; text: string }[] = blocks.flatMap(b => {
    const pieces = chunk(b.body, READER_LIMITS.section);
    return pieces.map((t, i) => ({
      heading: b.heading
        ? pieces.length > 1
          ? `${b.heading}（${i + 1}）`
          : b.heading
        : `第 ${i + 1} 部分`,
      text: t,
    }));
  });
  // 节太多：整篇按更大的块重新切
  if (out.length > READER_LIMITS.sections) {
    const size = Math.ceil(text.length / READER_LIMITS.sections) + 200;
    out = chunk(text, size).map((t, i) => ({ heading: `第 ${i + 1} 部分`, text: t }));
  }
  return out.slice(0, READER_LIMITS.sections).map((s, i) => ({ n: i + 1, ...s }));
}

/* ---------- 工具（只读） ---------- */

const block = (s: MaterialSection) => `§${s.n} ${s.heading}\n${s.text}`;

/** 读某几节：原文拼在一起，总长有上限。 */
export function readSections(
  sections: MaterialSection[],
  ns: number[],
  max: number = READER_LIMITS.excerpt,
) {
  const picked = ns
    .map(n => sections.find(s => s.n === n))
    .filter((s): s is MaterialSection => !!s);
  const more = '…（这一节后面省略）';
  const share = Math.floor((max - 2 * (picked.length - 1)) / Math.max(1, picked.length));
  return picked
    .map(s => {
      const b = block(s);
      return b.length > share ? b.slice(0, share - more.length) + more : b;
    })
    .join('\n\n');
}

/* ---------- 讨论时带给 AI 的材料 ---------- */

/** 目录：每节的编号和标题。 */
export function outlineText(sections: MaterialSection[]): string {
  let out = '目录：';
  for (const s of sections) {
    const line = `\n§${s.n} ${s.heading}`;
    if (out.length + line.length > READER_LIMITS.outline) {
      out += '\n……';
      break;
    }
    out += line;
  }
  return out;
}

// 拿来比较的词：中文按相邻两个字，英文和数字按整个词（三个字母以上）
function terms(text: string): Set<string> {
  const t = normalizeForSearch(text);
  const out = new Set<string>();
  for (const w of t.match(/[a-z0-9]{3,}/g) ?? []) out.add(w);
  const han = t.replace(/[^\u3400-\u9fff]+/g, ' ');
  for (const run of han.split(' '))
    for (let i = 0; i + 1 < run.length; i++) out.add(run.slice(i, i + 2));
  return out;
}

/** 和 query 的相关程度：query 里的词在这一节里出现了几个（标题里出现算两个）。 */
export function relevance(s: MaterialSection, query: Set<string>): number {
  const body = normalizeForSearch(s.text);
  const head = normalizeForSearch(s.heading);
  let n = 0;
  for (const w of query) {
    if (head.includes(w)) n += 2;
    else if (body.includes(w)) n += 1;
  }
  return n;
}

// 整篇的长度（拼成“§节号 标题 + 正文”以后）
const totalLength = (sections: MaterialSection[]) =>
  sections.reduce((n, s) => n + s.heading.length + s.text.length + 8, 0);

/**
 * 讨论时带给 AI 的材料：放得下就整篇；放不下就是目录，加上和 query（讨论题、刚说的话）最相关的几节，
 * 按原文顺序排。一节也不相关时给开头几节。
 */
export function materialContext(sections: MaterialSection[], query: string): string {
  if (!sections.length) return '';
  const all = sections.map(s => s.n);
  if (totalLength(sections) <= READER_LIMITS.excerpt - 40)
    return readSections(sections, all, READER_LIMITS.excerpt);
  const outline = outlineText(sections);
  const budget = READER_LIMITS.excerpt - outline.length - 2;
  const q = terms(query);
  const ranked = sections
    .map(s => ({ s, score: relevance(s, q) }))
    .filter(x => x.score > 0)
    .sort((a, b) => b.score - a.score || a.s.n - b.s.n);
  const picked: number[] = [];
  let used = 0;
  for (const { s } of ranked.length ? ranked : sections.map(s => ({ s }))) {
    const size = s.heading.length + s.text.length + 8;
    if (picked.length && used + size > budget) continue;
    picked.push(s.n);
    used += size;
    if (used >= budget || picked.length >= 6) break;
  }
  return (
    outline +
    '\n\n' +
    readSections(
      sections,
      picked.sort((a, b) => a - b),
      budget,
    )
  );
}

/** 开始讨论时（准备讨论题、要点）带给 AI 的材料：目录和开头几节。 */
export const startContext = (sections: MaterialSection[]) => materialContext(sections, '');

/** 讨论里该按什么找相关的几节：讨论题、正在聊的分支、最近两条发言。 */
export function contextQuery(s: {
  topic: string;
  messages: { text: string }[];
  branch?: { topic: string };
}): string {
  return [s.topic, s.branch?.topic ?? '', ...s.messages.slice(-2).map(m => m.text)].join('\n');
}
