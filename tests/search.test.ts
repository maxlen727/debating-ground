import { test } from 'node:test';
import assert from 'node:assert/strict';
import { normalizeForSearch, searchCards } from '../src/lib/search.ts';
import type { Card } from '../src/store.ts';

const card = (id: string, extra: Partial<Card>): Card => ({
  id,
  sessionId: 's-' + id,
  concept: '',
  topic: '',
  mode: 'socratic',
  created: 0,
  source: 'demo',
  level: 0,
  due: 0,
  reviews: [],
  oneLine: '',
  points: [],
  misconceptions: [],
  quiz: [],
  ...extra,
});

const library = [
  card('a', { concept: '机会成本', oneLine: '放弃的最好选择的价值', topic: '周末打工的代价' }),
  card('b', {
    concept: '沉没成本',
    oneLine: '已经花掉、收不回的成本',
    points: [{ label: '不该影响决策', status: 'mastered', note: '' }],
  }),
  card('c', {
    concept: '边际效用',
    oneLine: '多一份带来的满足',
    terms: [{ term: '机会成本', definition: '', relation: '' }],
  }),
  card('d', { concept: 'Bayes 定理', oneLine: '用证据更新信念' }),
];
const ids = (q: string) => searchCards(library, q).map(c => c.id);

test('空查询原样返回', () => {
  assert.deepEqual(ids('  '), ['a', 'b', 'c', 'd']);
});

test('概念名命中排在术语命中前面', () => {
  assert.deepEqual(ids('机会成本'), ['a', 'c']);
});

test('几个词都要命中，可以在不同地方', () => {
  assert.deepEqual(ids('成本 决策'), ['b']);
  assert.deepEqual(ids('成本 不存在的词'), []);
});

test('不区分大小写和全角半角', () => {
  assert.deepEqual(ids('ｂａｙｅｓ'), ['d']);
  assert.deepEqual(ids('BAYES'), ['d']);
  assert.equal(normalizeForSearch('  Ａ　b  '), 'a b');
});

test('分数一样时保持原来的顺序', () => {
  assert.deepEqual(ids('成本'), ['a', 'b', 'c']);
});
