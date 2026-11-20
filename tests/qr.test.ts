// 邀请二维码（#151）。qr-reference.json 是另一个独立实现（Python 的 qrcode 库，固定掩码）生成的，
// 用来确认编码、纠错、交错、版本信息和格式信息都和标准一致；解码也用 OpenCV 手动试过（见 PR）。
import { test } from 'node:test';
import assert from 'node:assert/strict';
import { readFileSync } from 'node:fs';
import { encodeQr } from '../src/lib/qr.ts';

const rows = (m: boolean[][]) => m.map(r => r.map(c => (c ? '1' : '0')).join(''));
const reference = JSON.parse(
  readFileSync(new URL('./qr-reference.json', import.meta.url), 'utf8'),
) as { text: string; mask: number; version: number; rows: string[] }[];

test('qr codes match an independent implementation cell for cell', () => {
  for (const r of reference) {
    const q = encodeQr(r.text, r.mask);
    assert.ok(q);
    assert.equal(q.version, r.version, r.text);
    assert.equal(q.size, r.version * 4 + 17);
    assert.deepEqual(rows(q.modules), r.rows, `${r.text.slice(0, 20)}（掩码 ${r.mask}）`);
  }
});

test('qr picks the smallest version and a mask, and refuses what does not fit', () => {
  const short = encodeQr('hello');
  assert.equal(short?.version, 1);
  assert.ok(short && short.mask >= 0 && short.mask < 8);
  // 字节模式、纠错 M：版本 1 最多 14 个字节，第 15 个就要版本 2
  assert.equal(encodeQr('x'.repeat(14))?.version, 1);
  assert.equal(encodeQr('x'.repeat(15))?.version, 2);
  // 中文按 UTF-8 算字节
  assert.equal(encodeQr('一二三四五')?.version, 2);
  // 版本 40 的上限是 2331 个字节
  assert.equal(encodeQr('x'.repeat(2331))?.version, 40);
  assert.equal(encodeQr('x'.repeat(2332)), null);
});
