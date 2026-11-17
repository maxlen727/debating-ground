// 原生安卓版的标准答案（native/app/src/test/resources/fixtures/）是网页版代码生成的。
// 改了存档结构、存档检查或导出，却没重新生成，这里会失败：运行 npm run fixtures:native。
import { test } from 'node:test';
import assert from 'node:assert/strict';
import { readdirSync } from 'node:fs';

test('the native fixtures are up to date with the web app', async () => {
  const { generate, readFixture, FIXTURE_DIR } = await import('../scripts/native-fixtures.ts');
  const fresh = await generate();
  assert.deepEqual(
    readdirSync(FIXTURE_DIR).sort(),
    Object.keys(fresh).sort(),
    '标准答案的文件列表变了，运行 npm run fixtures:native',
  );
  for (const [name, text] of Object.entries(fresh))
    assert.equal(readFixture(name), text, `${name} 过期了，运行 npm run fixtures:native`);
});
