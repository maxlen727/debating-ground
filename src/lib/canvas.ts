// 知识画布：讨论是一棵树。所有发言仍按时间存在一个列表里，每条带着自己所在的分支；
// 这里按分支把它们取出来。
import type { Entry } from '../../shared/types.ts';
import type { Branch, Session } from '../store.ts';

type S = Pick<Session, 'entries' | 'branches' | 'at'>;

export const branchById = (s: S, id?: string) => id ? s.branches?.find(b => b.id === id) : undefined;

// 从最外层到 at 这一层的分支链（不含主干）
export function branchChain(s: S, at = s.at): Branch[] {
  const out: Branch[] = [];
  const seen = new Set<string>();
  for (let b = branchById(s, at); b && !seen.has(b.id); b = branchById(s, b.parent)) { seen.add(b.id); out.unshift(b); }
  return out;
}

// 只属于某一层的发言（主干传 undefined）
export const levelEntries = (s: S, at?: string): Entry[] => s.entries.filter(e => e.branch === at);

// AI 在某个分支里能看到的内容：主干到分叉点为止，每一层分支到下一个分叉点为止，再加上这个分支自己。
// 别的分支不混进来。
export function pathEntries(s: S, at = s.at): Entry[] {
  const chain = branchChain(s, at);
  const levels: (string | undefined)[] = [undefined, ...chain.map(b => b.id)];
  return levels.flatMap((level, i) => {
    const own = levelEntries(s, level);
    const fork = chain[i]?.from;           // 下一层是从这一层的哪条发言展开的
    if (!fork) return own;
    const k = own.findIndex(e => e.id === fork);
    return k >= 0 ? own.slice(0, k + 1) : own;
  });
}

export const branchesFrom = (s: S, entryId: string) => (s.branches ?? []).filter(b => b.from === entryId);
export const childrenOf = (s: S, parent?: string) => (s.branches ?? []).filter(b => b.parent === parent);
// 一个分支里有几条发言（只算 AI 和学习者说的话）
export const talkCount = (s: S, at?: string) => levelEntries(s, at).filter(e => e.kind === 'msg').length;

/* ---------- 分支图（像 GitLens 的提交图） ---------- */
// 每条发言、每句带回来的话是一行，按时间排；主干在第 0 条轨道，每个分支占一条轨道。
// 分支的轨道在它“活着”的那段行里被占用（从分叉那一行到它最后一行、或带回的那一行），用完可以给后面的分支复用。
export type NodeKind = 'ai' | 'me' | 'back' | 'reflect';
export interface GraphRow { id: string; index: number; lane: number; branch?: string; kind: NodeKind; who: string; text: string }
export interface GraphEdge { kind: 'fork' | 'merge'; branch: string; fromRow: number; fromLane: number; toRow: number; toLane: number }
export interface GraphLane { branch?: string; lane: number; fromRow: number; toRow: number }
export interface Graph { rows: GraphRow[]; lanes: GraphLane[]; edges: GraphEdge[]; width: number; laneOf: Record<string, number> }

export function graphOf(s: S): Graph {
  const rows: GraphRow[] = [];
  for (const e of s.entries) {
    const kind: NodeKind | null = e.kind === 'msg' ? (e.human ? 'me' : 'ai') : e.kind === 'sys' && e.back ? 'back' : e.kind === 'reflect' && e.text ? 'reflect' : null;
    if (!kind) continue;
    const text = e.kind === 'msg' || e.kind === 'sys' || e.kind === 'reflect' ? e.text : '';
    const who = e.kind === 'msg' ? (e.human ? '你' : e.name) : kind === 'back' ? '带回' : '小结';
    rows.push({ id: e.id, index: rows.length, lane: 0, branch: e.branch, kind, who, text });
  }
  const rowOf = new Map(rows.map(r => [r.id, r.index]));
  const branches = s.branches ?? [];
  // 每个分支占用的行：从分叉的那一行，到它自己最后一行或带回的那一行
  const span = (b: Branch) => {
    const own = rows.filter(r => r.branch === b.id);
    const backs = rows.filter(r => r.kind === 'back' && s.entries.find(e => e.id === r.id && e.kind === 'sys' && e.back === b.id));
    const start = rowOf.get(b.from) ?? own[0]?.index ?? 0;
    const end = Math.max(start, own.at(-1)?.index ?? start, backs.at(-1)?.index ?? start);
    return { start, end, own, backs };
  };
  const laneOf: Record<string, number> = {};
  const taken: { lane: number; start: number; end: number }[] = [];
  const lanes: GraphLane[] = [];
  const edges: GraphEdge[] = [];
  const trunk = rows.filter(r => r.branch === undefined);
  if (trunk.length) lanes.push({ lane: 0, fromRow: trunk[0].index, toRow: trunk.at(-1)!.index });
  // 父分支先分配（按创建顺序，父分支一定比子分支早）
  for (const b of [...branches].sort((x, y) => x.created - y.created)) {
    const { start, end, own, backs } = span(b);
    let lane = 1;
    while (taken.some(t => t.lane === lane && t.start <= end && start <= t.end)) lane++;
    taken.push({ lane, start, end });
    laneOf[b.id] = lane;
    for (const r of own) r.lane = lane;
    const parentLane = b.parent ? laneOf[b.parent] ?? 0 : 0;
    if (own.length) {
      lanes.push({ branch: b.id, lane, fromRow: own[0].index, toRow: own.at(-1)!.index });
      edges.push({ kind: 'fork', branch: b.id, fromRow: start, fromLane: parentLane, toRow: own[0].index, toLane: lane });
    }
    // 带回：从分支在那之前的最后一行，连回上一层带回的那一行
    for (const back of backs) {
      const before = [...own].reverse().find(r => r.index < back.index);
      if (before) edges.push({ kind: 'merge', branch: b.id, fromRow: before.index, fromLane: lane, toRow: back.index, toLane: parentLane });
    }
  }
  const width = Math.max(0, ...rows.map(r => r.lane), ...lanes.map(l => l.lane)) + 1;
  return { rows, lanes, edges, width, laneOf };
}
