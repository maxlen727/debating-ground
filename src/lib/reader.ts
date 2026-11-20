// 读材料（#144）：把材料切成节、研读助手的工具（读某几节、搜一个词）和它的循环。
// 循环学 mini-swe-agent：每一步模型只给一个动作，这里执行后把结果接回去，直到它给出“技术点地图”或者到步数上限。
// 循环在前端跑：材料只存在学习者的设备上；后端每一步只做一次模型调用（用途 read），API Key 照旧只在后端。
// 工具都是只读的，原文当资料不当指令（提示词里写明了）。原生版 data/Reader.kt 是同一套规则。
import type {
  MapPoint,
  MaterialExcerpt,
  MaterialMap,
  MaterialSection,
  ReadAction,
  ReadStep,
} from '../../shared/types.ts';
import { normalizeForSearch } from './search.ts';

export const READER_LIMITS = {
  text: 300_000, // 整篇材料最多多少字
  sections: 80, // 最多切成几节
  section: 3000, // 一节太长就按段落再切
  observation: 12_000, // 一步最多读回多少字
  excerpt: 8000, // 讨论时带上的节选
  steps: 8, // 研读助手最多走几步
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

export const outlineOf = (sections: MaterialSection[]) =>
  sections.map(s => ({ n: s.n, heading: s.heading, chars: s.text.length }));

/* ---------- 工具（只读） ---------- */

const block = (s: MaterialSection) => `§${s.n} ${s.heading}\n${s.text}`;

/** 读某几节：原文拼在一起，总长有上限。 */
export function readSections(
  sections: MaterialSection[],
  ns: number[],
  max: number = READER_LIMITS.observation,
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

/** 在原文里搜一个词：最多 5 处，每处前后各带一些文字，标出在第几节。 */
export function searchMaterial(sections: MaterialSection[], query: string) {
  const q = normalizeForSearch(query);
  if (!q) return '没有要搜的词。';
  const hits: string[] = [];
  for (const s of sections) {
    const t = normalizeForSearch(s.text);
    let from = 0;
    while (hits.length < 5) {
      const i = t.indexOf(q, from);
      if (i < 0) break;
      hits.push(`§${s.n} ${s.heading}：…${t.slice(Math.max(0, i - 80), i + q.length + 80)}…`);
      from = i + q.length;
    }
    if (hits.length >= 5) break;
  }
  return hits.length ? hits.join('\n') : `原文里没有找到「${query}」。`;
}

/** 执行一个动作，得到这一步的记录（给人看的动作描述 + 给模型看的原文）。 */
export function runTool(sections: MaterialSection[], a: ReadAction): ReadStep {
  if (a.action === 'read')
    return {
      thought: a.thought,
      action: `读第 ${a.sections.join('、')} 节`,
      observation: readSections(sections, a.sections),
    };
  if (a.action === 'search')
    return {
      thought: a.thought,
      action: `搜「${a.query}」`,
      observation: searchMaterial(sections, a.query),
    };
  return { thought: a.thought, action: '整理成地图', observation: '' };
}

/* ---------- 讨论时带上的节选 ---------- */

/** 一个技术点在材料里的原文节选：它所在的几节；没写在哪几节就搜它的名字。 */
export function excerptFor(
  title: string,
  sections: MaterialSection[],
  point: MapPoint,
): MaterialExcerpt {
  let ns = point.sections.filter(n => sections.some(s => s.n === n));
  if (!ns.length) {
    const q = normalizeForSearch(point.name);
    ns = sections
      .filter(s => normalizeForSearch(s.text).includes(q))
      .slice(0, 2)
      .map(s => s.n);
  }
  if (!ns.length) ns = [sections[0]?.n ?? 1];
  return {
    title,
    point: point.name,
    excerpt: readSections(sections, ns.slice(0, 4), READER_LIMITS.excerpt),
  };
}

/* ---------- 研读助手的循环 ---------- */

export type Step = (payload: {
  title: string;
  outline: ReturnType<typeof outlineOf>;
  steps: ReadStep[];
  last?: boolean;
}) => Promise<ReadAction | null>;

/**
 * 跑一遍研读助手：每一步问模型要一个动作，执行后记下来，直到它给出地图。
 * onStep 每走一步就通知一次（界面上显示它在做什么）。模型出错或被取消时返回 null。
 */
export async function runReader(
  title: string,
  sections: MaterialSection[],
  ask: Step,
  onStep: (steps: ReadStep[]) => void = () => {},
): Promise<{ map: MaterialMap; steps: ReadStep[] } | null> {
  const outline = outlineOf(sections);
  const steps: ReadStep[] = [];
  for (let i = 0; i < READER_LIMITS.steps; i++) {
    const last = i === READER_LIMITS.steps - 1;
    const a = await ask({ title, outline, steps, last: last || undefined });
    if (!a) return null;
    const step = runTool(sections, a);
    steps.push(step);
    onStep([...steps]);
    if (a.action === 'finish') return { map: { ...a.map, title: a.map.title || title }, steps };
  }
  return null;
}
