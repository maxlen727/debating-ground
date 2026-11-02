// 连贯动效：导航方向判断；Node 里没有 View Transitions 时直接切换
import { test } from 'node:test';
import assert from 'node:assert/strict';
import { navKind, transition } from '../src/lib/motion.ts';

const v = (screen: Parameters<typeof navKind>[0]['screen'], overlay: string | null = null) => ({ screen, overlay });

test('going deeper is forward, coming back is back', () => {
  assert.equal(navKind(v('learn'), v('prepare')), 'forward');
  assert.equal(navKind(v('prepare'), v('session')), 'forward');
  assert.equal(navKind(v('session'), v('review')), 'forward');
  assert.equal(navKind(v('review'), v('session')), 'back');
  assert.equal(navKind(v('prepare'), v('learn')), 'back');
});

test('bottom tabs slide sideways in tab order; a library card expands and collapses', () => {
  assert.equal(navKind(v('learn'), v('library')), 'left');
  assert.equal(navKind(v('settings'), v('learn')), 'right');
  assert.equal(navKind(v('learn'), v('session')), 'left', 'the 讨论 tab is a sibling of 学习');
  assert.equal(navKind(v('library'), v('card')), 'expand');
  assert.equal(navKind(v('card'), v('library')), 'collapse');
});

test('sheets open, close and swap on the same screen', () => {
  assert.equal(navKind(v('session'), v('session', 'tree')), 'open');
  assert.equal(navKind(v('session', 'tree'), v('session')), 'close');
  assert.equal(navKind(v('session', 'a'), v('session', 'b')), 'swap');
});

test('without View Transitions the change is applied at once', () => {
  let done = 0;
  transition(v('learn'), v('prepare'), () => { done++; });
  assert.equal(done, 1);
});
