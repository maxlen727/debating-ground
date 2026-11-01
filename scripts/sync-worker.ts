// 把后端共用的源码原样复制进 worker/src/vendor/。
// 为什么要复制：Deploy to Cloudflare 按钮只会复制 worker/ 这个子目录，子目录外的文件在部署时不存在。
// 只改 server/ 和 shared/ 里的源码，然后运行 npm run sync:worker；tests/worker.test.ts 会检查副本是否最新。
import { readFileSync, writeFileSync, mkdirSync, readdirSync, rmSync } from 'node:fs';
import { dirname, join } from 'node:path';
import { fileURLToPath } from 'node:url';

const root = join(dirname(fileURLToPath(import.meta.url)), '..');
export const VENDOR_DIR = join(root, 'worker', 'src', 'vendor');
export const HEADER = '// 自动生成，请勿修改。源文件在仓库根目录，修改后运行 npm run sync:worker。\n';

// 返回 [源文件, 副本] 对。保持 server/ 与 shared/ 的相对位置，这样 import 路径不用改。
export function vendorFiles(): [string, string][] {
  const shared = readdirSync(join(root, 'shared')).filter(f => f.endsWith('.ts')).map(f => join('shared', f));
  return ['server/ai.ts', 'server/tts.ts', 'server/stt.ts', ...shared].map(rel => [join(root, rel), join(VENDOR_DIR, rel)]);
}

export function expected(source: string): string {
  return HEADER + readFileSync(source, 'utf8');
}

if (import.meta.url === `file://${process.argv[1]}`) {
  rmSync(VENDOR_DIR, { recursive: true, force: true });
  for (const [source, copy] of vendorFiles()) {
    mkdirSync(dirname(copy), { recursive: true });
    writeFileSync(copy, expected(source));
  }
  console.log('已同步到 worker/src/vendor/');
}
