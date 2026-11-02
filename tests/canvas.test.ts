// 知识画布：分支的路径和 AI 能看到的内容
import { test } from 'node:test';
import assert from 'node:assert/strict';
import { branchChain, pathEntries, levelEntries, branchesFrom, childrenOf, talkCount } from '../src/lib/canvas.ts';
import type { Entry } from '../shared/types.ts';
import type { Branch } from '../src/store.ts';

const msg = (id: string, branch?: string): Entry => ({ id, kind: 'msg', who: 'r0', name: 'AI', text: id, source: 'demo', branch });
// 主干 t1 t2 t3；从 t2 展开 A（a1 a2），从 a1 展开 A1（x1）；从 t3 展开 B（b1）
const entries: Entry[] = [msg('t1'), msg('t2'), msg('a1', 'A'), msg('t3'), msg('a2', 'A'), msg('x1', 'A1'), msg('b1', 'B'), msg('t4')];
const branches: Branch[] = [
  { id: 'A', from: 't2', topic: '稀缺性', created: 1 },
  { id: 'A1', parent: 'A', from: 'a1', topic: '边际', created: 2 },
  { id: 'B', from: 't3', topic: '沉没成本', created: 3 }
];
const s = (at?: string) => ({ entries, branches, at });
const ids = (list: Entry[]) => list.map(e => e.id);

test('the trunk sees only trunk entries', () => {
  assert.deepEqual(ids(pathEntries(s())), ['t1', 't2', 't3', 't4']);
});

test('a branch sees the trunk up to its fork point plus itself, never other branches', () => {
  assert.deepEqual(ids(pathEntries(s('A'))), ['t1', 't2', 'a1', 'a2']);
  assert.deepEqual(ids(pathEntries(s('B'))), ['t1', 't2', 't3', 'b1']);
  // 子分支：主干到 t2，A 到 a1（a2 是在分叉之后说的，不算），再加上自己
  assert.deepEqual(ids(pathEntries(s('A1'))), ['t1', 't2', 'a1', 'x1']);
});

test('chain, levels and children', () => {
  assert.deepEqual(branchChain(s('A1')).map(b => b.id), ['A', 'A1']);
  assert.deepEqual(branchChain(s()), []);
  assert.deepEqual(ids(levelEntries(s(), 'A')), ['a1', 'a2']);
  assert.deepEqual(branchesFrom(s(), 't2').map(b => b.id), ['A']);
  assert.deepEqual(childrenOf(s(), undefined).map(b => b.id), ['A', 'B']);
  assert.equal(talkCount(s(), undefined), 4);
});

test('a broken parent link never loops forever', () => {
  const loop: Branch[] = [{ id: 'X', parent: 'Y', from: 't1', topic: 'x', created: 1 }, { id: 'Y', parent: 'X', from: 't1', topic: 'y', created: 2 }];
  assert.equal(branchChain({ entries, branches: loop, at: 'X' }).length, 2);
});
