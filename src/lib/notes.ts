// 概念笔记（#140）：一个概念一篇，每次讨论完把复盘合并进来，越来越准——像 skill 的沉淀。
// 合并是确定的规则、不靠 AI：可检查，演示模式也能用。原生版 data/Notes.kt 是同一套规则，
// 用这里生成的标准答案逐条比对（notes-cases.json）。
import { MODES } from '../../shared/modes.ts';
import type {
  ModeId,
  PointStatus,
  Review,
  ReviewPoint,
  SelfCheck,
  TermCard,
} from '../../shared/types.ts';
import type { Source } from '../../shared/types.ts';
import { normalizeForSearch } from './search.ts';

export interface NotePoint extends ReviewPoint {
  checked?: number; // 最后一次自检的时间
}
// 笔记是从哪几次讨论长出来的（像版本记录）
export interface NoteSource {
  sessionId: string;
  at: number;
  mode: ModeId;
  topic: string;
  oneLine: string; // 那次讨论的一句话评价
  source: Source;
  model?: string;
}
export interface Note {
  id: string;
  concept: string;
  summary: string;
  useWhen: string;
  points: NotePoint[];
  examples: string[];
  misconceptions: { said: string; fix: string }[];
  boundaries: string[];
  related: string[];
  stances: { said: string; note: string }[];
  checks: SelfCheck[];
  terms: TermCard[];
  sources: NoteSource[];
  context?: string; // 讨论时交代的背景，再讨论一次时沿用
  created: number;
  updated: number;
}

// 各部分最多留多少条：多了就留最新的
export const NOTE_LIMITS = {
  points: 10,
  examples: 6,
  misconceptions: 6,
  boundaries: 6,
  related: 8,
  stances: 4,
  checks: 6,
} as const;

/** 两个概念名算不算同一个（不区分大小写、全角半角和空格）。 */
export const sameConcept = (a: string, b: string) =>
  normalizeForSearch(a).replace(/ /g, '') === normalizeForSearch(b).replace(/ /g, '');
const same = (a: string, b: string) => sameConcept(a, b);

// 追加并去重（按 key 比较，新的覆盖旧的、排到后面），超过上限时去掉最早的
function mergeList<T>(old: T[], add: T[], key: (x: T) => string, max: number): T[] {
  const out = [...old];
  for (const x of add) {
    const k = key(x);
    if (!k.trim()) continue;
    const i = out.findIndex(y => same(key(y), k));
    if (i >= 0) out.splice(i, 1);
    out.push(x);
  }
  return out.slice(-max);
}

export interface MergeFrom {
  sessionId: string;
  at: number;
  mode: ModeId;
  concept: string;
  topic: string;
  source: Source;
  model?: string;
  context?: string;
  terms?: TermCard[];
}

/** 一篇空笔记。 */
export const emptyNote = (id: string, concept: string, at: number): Note => ({
  id,
  concept,
  summary: '',
  useWhen: '',
  points: [],
  examples: [],
  misconceptions: [],
  boundaries: [],
  related: [],
  stances: [],
  checks: [],
  terms: [],
  sources: [],
  created: at,
  updated: at,
});

/**
 * 把一次讨论的复盘合并进笔记：
 * - 要点按名字对上就更新状态、理由和依据（新的没给原话就留着旧的），对不上就新增；这次没提到的要点不动。
 * - 例子、易错、边界、相关、立场、自检去重后追加，超过上限留最新的。
 * - “一句话”和“什么时候想到它”用最新一次的（理解在往前走），这次没给就留着。
 * - 同一次讨论重新复盘：替换它在来源里的那一条，不会重复。
 */
export function mergeReview(note: Note, r: Review, from: MergeFrom): Note {
  const points = [...note.points];
  for (const p of r.points) {
    if (!p.label.trim()) continue;
    const i = points.findIndex(x => same(x.label, p.label));
    const next: NotePoint = {
      label: p.label,
      status: p.status,
      note: p.note,
      evidence: p.evidence?.trim() || (i >= 0 ? points[i].evidence : undefined) || undefined,
    };
    if (i >= 0) points[i] = { ...points[i], ...next };
    else points.push(next);
  }
  const terms = mergeList(note.terms, from.terms ?? [], t => t.term, 50);
  const sources = [
    ...note.sources.filter(s => s.sessionId !== from.sessionId),
    {
      sessionId: from.sessionId,
      at: from.at,
      mode: from.mode,
      topic: from.topic,
      oneLine: r.oneLine,
      source: from.source,
      ...(from.model ? { model: from.model } : {}),
    },
  ].sort((a, b) => a.at - b.at);
  return {
    ...note,
    summary: r.summary?.trim() || note.summary,
    useWhen: r.useWhen?.trim() || note.useWhen,
    points: points.slice(-NOTE_LIMITS.points),
    examples: mergeList(note.examples, r.examples ?? [], x => x, NOTE_LIMITS.examples),
    misconceptions: mergeList(
      note.misconceptions,
      r.misconceptions,
      m => m.said,
      NOTE_LIMITS.misconceptions,
    ),
    boundaries: mergeList(note.boundaries, r.boundaries ?? [], x => x, NOTE_LIMITS.boundaries),
    related: mergeList(
      note.related,
      (r.related ?? []).filter(x => !same(x, note.concept)),
      x => x,
      NOTE_LIMITS.related,
    ),
    stances: mergeList(note.stances, r.stances ?? [], m => m.said, NOTE_LIMITS.stances),
    checks: mergeList(note.checks, r.checks ?? [], c => c.q, NOTE_LIMITS.checks),
    terms,
    sources,
    context: from.context?.trim() || note.context,
    updated: Math.max(note.updated, from.at),
  };
}

/** 自检：答出来了，它检验的要点算掌握；没答出，算模糊。记下时间。 */
export function checkPoint(note: Note, check: SelfCheck, ok: boolean, at: number): Note {
  const status: PointStatus = ok ? 'mastered' : 'partial';
  const i = check.point ? note.points.findIndex(p => same(p.label, check.point!)) : -1;
  if (i < 0) return { ...note, updated: at };
  const points = note.points.map((p, j) => (j === i ? { ...p, status, checked: at } : p));
  return { ...note, points, updated: at };
}

/** 还没弄清的地方：模糊和没讲到的要点、说错过的地方（从薄弱点再讨论时用）。 */
export const weakOf = (n: Note) => [
  ...n.points.filter(p => p.status !== 'mastered').map(p => p.label),
  ...n.misconceptions.map(m => m.said),
];

/** 几个要点掌握、模糊、没讲到。 */
export function tally(n: Note) {
  const c = { mastered: 0, partial: 0, missing: 0 };
  for (const p of n.points) c[p.status]++;
  return c;
}

/* ---------- 从旧的知识卡片（sibian-v3）迁移 ---------- */

// 旧卡片的结构：一次讨论一张，带四选一的检验题和复习时间
export interface OldCard {
  id: string;
  sessionId: string;
  concept: string;
  topic: string;
  mode: ModeId;
  created: number;
  source: Source;
  model?: string;
  oneLine: string;
  points: ReviewPoint[];
  misconceptions: { said: string; fix: string }[];
  stances?: { said: string; note: string }[];
  quiz?: { q: string; options: string[]; answer: number; why: string }[];
  terms?: TermCard[];
  context?: string;
}

/** 旧卡片按概念合并成笔记（按时间从早到晚合并）。四选一的题变成自检：参考答案是正确选项加解析。 */
export function notesFromCards(cards: OldCard[]): Note[] {
  const notes: Note[] = [];
  for (const c of [...cards].sort((a, b) => a.created - b.created)) {
    let note = notes.find(n => same(n.concept, c.concept));
    if (!note) {
      note = emptyNote(c.id, c.concept, c.created);
      notes.push(note);
    }
    const review: Review = {
      oneLine: c.oneLine,
      points: c.points,
      misconceptions: c.misconceptions,
      stances: c.stances,
      checks: (c.quiz ?? []).map(q => ({
        q: q.q,
        answer: [q.options[q.answer], q.why].filter(Boolean).join('。'),
      })),
    };
    const merged = mergeReview(note, review, {
      sessionId: c.sessionId,
      at: c.created,
      mode: c.mode,
      concept: c.concept,
      topic: c.topic,
      source: c.source,
      model: c.model,
      context: c.context,
      terms: c.terms,
    });
    notes[notes.indexOf(note)] = merged;
  }
  // 最近更新的排前面
  return notes.sort((a, b) => b.updated - a.updated);
}

/* ---------- 导出：一篇笔记一个 Markdown 文件，开头是 name / description ---------- */

const STATUS: Record<PointStatus, string> = {
  mastered: '掌握',
  partial: '模糊',
  missing: '没讲到',
};
const date = (t: number) => new Date(t).toLocaleDateString('zh-CN');
const yaml = (s: string) => JSON.stringify(s.replace(/\s+/g, ' ').trim());

export function noteMarkdown(n: Note, frontmatter = true): string {
  const out: string[] = [];
  if (frontmatter) {
    out.push('---', `name: ${yaml(n.concept)}`);
    if (n.useWhen) out.push(`description: ${yaml(n.useWhen)}`);
    out.push('---', '');
  }
  out.push(`# ${n.concept}`, '');
  if (n.summary) out.push(n.summary, '');
  if (n.useWhen) out.push(`**什么时候想到它**：${n.useWhen}`, '');
  const section = (title: string, lines: string[]) => {
    if (lines.length) out.push(`## ${title}`, '', ...lines, '');
  };
  section(
    '要点',
    n.points.map(
      p =>
        `- [${STATUS[p.status]}] ${p.label}` +
        (p.note ? `：${p.note}` : '') +
        (p.evidence ? `（我说过：“${p.evidence}”）` : ''),
    ),
  );
  section(
    '例子',
    n.examples.map(e => `- ${e}`),
  );
  section(
    '易错',
    n.misconceptions.map(m => `- ~~${m.said}~~ → ${m.fix}`),
  );
  section(
    '边界',
    n.boundaries.map(b => `- ${b}`),
  );
  section(
    '我的立场',
    n.stances.map(st => `- ${st.said}${st.note ? `（${st.note}）` : ''}`),
  );
  section('相关概念', n.related.length ? [n.related.join('、')] : []);
  section(
    '术语',
    n.terms.map(t => `- **${t.term}**：${t.definition}${t.relation ? `（${t.relation}）` : ''}`),
  );
  section(
    '自检',
    n.checks.flatMap((c, i) => [`${i + 1}. ${c.q}`, `   - 参考：${c.answer}`]),
  );
  section(
    '来源',
    n.sources.map(
      s =>
        `- ${date(s.at)} ${MODES[s.mode]?.name ?? s.mode}：${s.topic}${s.oneLine ? ` —— ${s.oneLine}` : ''}`,
    ),
  );
  return out.join('\n').trimEnd() + '\n';
}
