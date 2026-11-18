// 把 scripts/native-ai-entry.ts（以及它用到的 server/ai.ts、shared/）打包成一个 JS 文件，放进原生安卓版的 assets。
// 原生版的“直接连接”模式用它组装提示词、清洗模型输出，和后端用的是同一份代码。
// 改了 server/ai.ts 或 shared/ 后运行 npm run bundle:native；tests/native-ai.test.ts 会检查打包文件没有过期。
import { build } from 'esbuild';
import { mkdirSync, writeFileSync } from 'node:fs';
import { dirname, join } from 'node:path';
import { fileURLToPath } from 'node:url';

const root = join(dirname(fileURLToPath(import.meta.url)), '..');
export const BUNDLE = join(root, 'native', 'app', 'src', 'main', 'assets', 'sibian-ai.js');
const HEADER =
  '// 自动生成，请勿修改。源文件是 server/ai.ts 和 shared/，修改后运行 npm run bundle:native。\n';

export async function bundle(): Promise<string> {
  const out = await build({
    entryPoints: [join(root, 'scripts', 'native-ai-entry.ts')],
    bundle: true,
    write: false,
    format: 'iife',
    // JS 引擎是 V8，但没有网页和 Node 的接口（fetch、URL、定时器……），只用到语言本身
    platform: 'neutral',
    target: 'es2020',
    charset: 'utf8',
    legalComments: 'none',
  });
  return HEADER + out.outputFiles[0].text;
}

if (process.argv[1] && fileURLToPath(import.meta.url) === process.argv[1]) {
  mkdirSync(dirname(BUNDLE), { recursive: true });
  writeFileSync(BUNDLE, await bundle());
  console.log(`已打包到 ${BUNDLE}`);
}
