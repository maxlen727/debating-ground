// 读回 localStorage 里的存档时先检查结构。
// 存档可能来自旧版本（缺字段），也可能写到一半被打断：缺的补上默认值，坏到没法用的那一块才丢掉。
// 原则：知识（概念笔记、旧的知识卡片）能救就救（宁可补空字段，也不整张丢掉）；计划和进行中的讨论坏了就作废，不让界面一打开就崩溃。
import { MODES } from '../../shared/modes.ts';
import type { ModeId, PointStatus, TermCard } from '../../shared/types.ts';
import type { Config, Plan, Session } from '../store.ts';
import type { Note, OldCard } from './notes.ts';

type Loose = Record<string, unknown>;
const isObj = (v: unknown): v is Loose => !!v && typeof v === 'object' && !Array.isArray(v);
const str = (v: unknown, fallback = '') => (typeof v === 'string' ? v : fallback);
const num = (v: unknown, fallback = 0) =>
  typeof v === 'number' && Number.isFinite(v) ? v : fallback;
const list = (v: unknown): unknown[] => (Array.isArray(v) ? v : []);
const strings = (v: unknown) => list(v).filter((x): x is string => typeof x === 'string');
const isMode = (v: unknown): v is ModeId => typeof v === 'string' && v in MODES;

// 旧的知识卡片（sibian-v3 之前）：一次讨论一张。先按旧结构补齐，再按概念合并成笔记
export function restoreCards(v: unknown): OldCard[] {
  return list(v)
    .filter(isObj)
    .filter(c => typeof c.id === 'string' && c.id)
    .map(c => ({
      id: c.id as string,
      sessionId: str(c.sessionId),
      concept: str(c.concept),
      topic: str(c.topic),
      mode: isMode(c.mode) ? c.mode : 'socratic',
      created: num(c.created, Date.now()),
      source: c.source === 'api' ? 'api' : 'demo', // 卡片来自复盘，只有这两种来源
      ...(typeof c.model === 'string' ? { model: c.model } : {}),
      oneLine: str(c.oneLine),
      points: points(c.points),
      misconceptions: pairs(c.misconceptions, 'said', 'fix'),
      stances: pairs(c.stances, 'said', 'note'),
      quiz: list(c.quiz)
        .filter(isObj)
        .filter(q => Array.isArray(q.options))
        .map(q => ({
          q: str(q.q),
          options: strings(q.options),
          answer: num(q.answer),
          why: str(q.why),
        })),
      terms: terms(c.terms),
      ...(typeof c.context === 'string' && c.context ? { context: c.context } : {}),
    }));
}

const STATUSES = ['mastered', 'partial', 'missing'];
function points(v: unknown) {
  return list(v)
    .filter(isObj)
    .filter(p => typeof p.label === 'string' && p.label)
    .map(p => ({
      label: p.label as string,
      status: (STATUSES.includes(p.status as string) ? p.status : 'partial') as PointStatus,
      note: str(p.note),
      ...(typeof p.evidence === 'string' && p.evidence ? { evidence: p.evidence } : {}),
      ...(typeof p.checked === 'number' ? { checked: p.checked } : {}),
    }));
}
function pairs<A extends string, B extends string>(v: unknown, a: A, b: B) {
  return list(v)
    .filter(isObj)
    .filter(x => typeof x[a] === 'string' && x[a])
    .map(x => ({ [a]: x[a] as string, [b]: str(x[b]) }) as Record<A | B, string>);
}
function terms(v: unknown): TermCard[] {
  return list(v)
    .filter(isObj)
    .filter(t => typeof t.term === 'string' && t.term)
    .map(t => ({
      term: t.term as string,
      definition: str(t.definition),
      relation: str(t.relation),
    }));
}

// 概念笔记（sibian-v4）：坏掉的字段补空，笔记本身能救就救
export function restoreNotes(v: unknown): Note[] {
  return list(v)
    .filter(isObj)
    .filter(n => typeof n.id === 'string' && n.id && typeof n.concept === 'string')
    .map(n => {
      const created = num(n.created, Date.now());
      return {
        id: n.id as string,
        concept: n.concept as string,
        summary: str(n.summary),
        useWhen: str(n.useWhen),
        points: points(n.points),
        examples: strings(n.examples),
        misconceptions: pairs(n.misconceptions, 'said', 'fix'),
        boundaries: strings(n.boundaries),
        related: strings(n.related),
        stances: pairs(n.stances, 'said', 'note'),
        checks: list(n.checks)
          .filter(isObj)
          .filter(c => typeof c.q === 'string' && c.q)
          .map(c => ({
            q: c.q as string,
            answer: str(c.answer),
            ...(typeof c.point === 'string' && c.point ? { point: c.point } : {}),
          })),
        terms: terms(n.terms),
        sources: list(n.sources)
          .filter(isObj)
          .filter(x => typeof x.sessionId === 'string')
          .map(x => ({
            sessionId: x.sessionId as string,
            at: num(x.at, created),
            mode: isMode(x.mode) ? x.mode : 'socratic',
            topic: str(x.topic),
            oneLine: str(x.oneLine),
            source: x.source === 'api' ? 'api' : 'demo',
            ...(typeof x.model === 'string' ? { model: x.model } : {}),
            ...(typeof x.material === 'string' && x.material ? { material: x.material } : {}),
          })),
        ...(typeof n.context === 'string' && n.context ? { context: n.context } : {}),
        created,
        updated: num(n.updated, created),
      };
    });
}

// 讨论带着的材料节选（#144）：字段不全就不要了，讨论照常
const materialOk = (m: unknown) =>
  isObj(m) &&
  typeof m.id === 'string' &&
  typeof m.title === 'string' &&
  typeof m.point === 'string' &&
  typeof m.excerpt === 'string';

export function restorePlan(v: unknown): Plan | null {
  if (!isObj(v) || !isMode(v.mode) || typeof v.concept !== 'string' || typeof v.topic !== 'string')
    return null;
  if (!Array.isArray(v.keyPoints) || !Array.isArray(v.personas)) return null;
  const personas = v.personas
    .filter(isObj)
    .filter(p => typeof p.id === 'string' && typeof p.name === 'string');
  // 讨论类模式至少要有一个角色，否则开始讨论时没有人开场
  if (v.mode !== 'debate' && !personas.length) return null;
  const out = {
    ...v,
    keyPoints: strings(v.keyPoints),
    personas,
    opening: str(v.opening),
    source: v.source === 'api' ? 'api' : 'demo',
    previous: isObj(v.previous) ? v.previous : null,
  } as Loose;
  if (!materialOk(out.material)) delete out.material;
  return out as unknown as Plan;
}

export function restoreSession(v: unknown): Session | null {
  if (
    !isObj(v) ||
    typeof v.id !== 'string' ||
    !isMode(v.mode) ||
    typeof v.concept !== 'string' ||
    typeof v.topic !== 'string'
  )
    return null;
  if (!Array.isArray(v.entries) || !Array.isArray(v.keyPoints)) return null;
  const entries = v.entries
    .filter(isObj)
    .filter(
      e =>
        typeof e.id === 'string' &&
        (e.kind === 'msg' || e.kind === 'sys' || e.kind === 'reflect') &&
        typeof e.text === 'string',
    );
  const personas = list(v.personas)
    .filter(isObj)
    .filter(p => typeof p.id === 'string' && typeof p.name === 'string');
  const turns = list(v.turns)
    .filter(isObj)
    .filter(t => isObj(t.role));
  // 单人讨论没有角色、辩论没有赛程，都没法继续（多人房间的状态以后端为准，重连后会补上）
  if (!isObj(v.room) && !v.finished && (v.mode === 'debate' ? !turns.length : !personas.length))
    return null;
  const out = {
    ...v,
    entries,
    personas,
    turns,
    keyPoints: strings(v.keyPoints),
    covered: list(v.covered).filter((n): n is number => typeof n === 'number'),
    seats: list(v.seats).filter(isObj),
    stages: list(v.stages).filter(isObj),
    created: num(v.created, Date.now()),
    learnerTurns: num(v.learnerTurns),
    nextRole: num(v.nextRole),
    demoStep: num(v.demoStep),
    cursor: num(v.cursor),
    finished: v.finished === true,
    draft: str(v.draft),
    previous: isObj(v.previous) ? v.previous : null,
    review: isObj(v.review) && Array.isArray(v.review.points) ? v.review : null,
    ...(Array.isArray(v.branches) ? { branches: v.branches.filter(isObj) } : {}),
  } as Loose;
  if (!isObj(out.room)) delete out.room;
  if (!Array.isArray(out.branches)) delete out.branches;
  // 以前苏格拉底追问里“换我来讲”的开关（#142 去掉了），留着没用
  delete out.teaching;
  if (!materialOk(out.material)) delete out.material;
  return out as unknown as Session;
}

// 设置逐项检查：某一项的形状不对就用默认值，其余照旧
export function restoreConfig(v: unknown, base: Config): Config {
  if (!isObj(v)) return base;
  const seatsOk = (s: unknown) =>
    Array.isArray(s) &&
    s.length > 0 &&
    s.every(x => isObj(x) && typeof x.type === 'string' && typeof x.bg === 'string');
  const seats =
    isObj(v.seats) && seatsOk(v.seats.A) && seatsOk(v.seats.B)
      ? (v.seats as Config['seats'])
      : base.seats;
  const counts =
    isObj(v.counts) &&
    [v.counts.A, v.counts.B].every(n => typeof n === 'number' && n >= 1 && n <= 4)
      ? (v.counts as Config['counts'])
      : base.counts;
  const stages =
    Array.isArray(v.stages) &&
    v.stages.length > 0 &&
    v.stages.every(
      s =>
        isObj(s) &&
        typeof s.name === 'string' &&
        typeof s.kind === 'string' &&
        typeof s.rounds === 'number',
    )
      ? (v.stages as Config['stages'])
      : base.stages;
  const remote =
    isObj(v.remote) && typeof v.remote.url === 'string' && typeof v.remote.password === 'string'
      ? { url: v.remote.url, password: v.remote.password }
      : base.remote;
  return {
    concept: str(v.concept, base.concept),
    mode: isMode(v.mode) ? v.mode : base.mode,
    aiMode: v.aiMode === 'api' || v.aiMode === 'remote' ? v.aiMode : 'demo',
    remote,
    name: str(v.name),
    counts,
    seats,
    stages,
    turnSeconds: ([0, 30, 60, 90, 120] as const).find(n => n === v.turnSeconds) ?? base.turnSeconds,
    voiceName: str(v.voiceName, base.voiceName),
  };
}
