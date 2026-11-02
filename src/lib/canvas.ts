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
