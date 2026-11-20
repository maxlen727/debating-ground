// 原生安卓版“直接连接”模式在 JS 引擎（androidx.javascriptengine）里执行的入口。
// 提示词是产品的核心，只在 server/ai.ts 里写一份：原生版通过这里调用 buildRequest 和 finalize，
// 网络请求和 API Key 都留在 Kotlin 那边，密钥不进入 JS。
// 改了 server/ai.ts 或 shared/ 后运行 npm run bundle:native，tests/native-ai.test.ts 会检查打包文件没有过期。
// 先补上沙箱里没有的 URL（读链接要用）
import './native-url.ts';
import { ApiError, buildRequest, finalize, type BuiltRequest } from '../server/ai.ts';
import type { Purpose } from '../shared/types.ts';
import {
  candidatesFor,
  checkTarget,
  readFetched,
  type Candidate,
  type Fetched,
} from '../shared/pages.ts';

const FNS: Record<string, (...args: never[]) => unknown> = {
  buildRequest: (purpose: Purpose | 'test', payload: unknown) => buildRequest(purpose, payload),
  finalize: (request: BuiltRequest, result: { text: string; model: string }) =>
    finalize(request, result),
  // 读材料的链接（#157）：该取哪几个地址、每一跳能不能去、取到的内容能不能用。网络请求在 Kotlin 那边
  linkPlan: (link: string) => candidatesFor(link),
  checkTarget: (url: string) => checkTarget(url),
  readFetched: (c: Candidate, f: Fetched) => readFetched(c, f),
};

// 参数和结果都用 JSON 字符串传：成功是 {"ok": 结果}，失败是 {"error": 原因}（和后端返回的错误文案一样）
function run(name: string, args: string): string {
  try {
    const fn = FNS[name];
    if (!fn) throw new ApiError('未知的调用。', 500);
    return JSON.stringify({ ok: fn(...(JSON.parse(args) as never[])) });
  } catch (e) {
    return JSON.stringify({
      error: e instanceof ApiError ? e.message : '生成请求时出错了，请重试。',
    });
  }
}

(globalThis as { sibian?: unknown }).sibian = { run };
