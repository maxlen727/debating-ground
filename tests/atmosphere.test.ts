// 背景氛围的图片（scripts/atmosphere.py 生成）是不是按当前的参数和颜色令牌生成的：
// 改了 atmosphere.json 或 styles.css 里用到的颜色，忘了重新生成，这里会失败。
import { test } from 'node:test';
import assert from 'node:assert/strict';
import { createHash } from 'node:crypto';
import { readFileSync, existsSync } from 'node:fs';

const dir = new URL('../src/assets/atmosphere/', import.meta.url);
const css = readFileSync(new URL('../src/styles.css', import.meta.url), 'utf8');

function tokens(block: string) {
  return Object.fromEntries(
    [...block.matchAll(/--([a-z0-9-]+):\s*(#[0-9a-fA-F]{6})\s*;/g)].map(m => [m[1], m[2]]),
  );
}

test('背景氛围的图片和当前的参数、颜色令牌一致', () => {
  const raw = readFileSync(new URL('atmosphere.json', dir));
  const params = JSON.parse(raw.toString()) as { glows: { token: string }[] };
  const light = tokens(/:root\s*\{(.*?)\n\}/s.exec(css)![1]);
  const dark = tokens(
    /@media \(prefers-color-scheme: dark\)\s*\{\s*:root\s*\{(.*?)\n {2}\}/s.exec(css)![1],
  );
  const need = ['surface', ...params.glows.map(g => g.token)];
  const list = (t: Record<string, string>) =>
    need
      .map(n => {
        assert.ok(t[n], `styles.css 里没有颜色令牌 --${n}`);
        return t[n].toLowerCase();
      })
      .join(',');
  const text = `${raw.toString()}\nlight:${list(light)}\ndark:${list(dark)}`;
  const expected = createHash('sha256').update(text).digest('hex');
  assert.equal(
    readFileSync(new URL('stamp', dir), 'utf8').trim(),
    expected,
    '参数或颜色变了：跑一次 python3 scripts/atmosphere.py',
  );
  for (const theme of ['light', 'dark'])
    for (const shape of ['portrait', 'landscape'])
      assert.ok(existsSync(new URL(`${theme}-${shape}.webp`, dir)), `${theme}-${shape}.webp`);
});
