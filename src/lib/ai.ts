// AI 调用的统一入口：演示模式走本地模板，API 模式走本机服务。
// 同一时间只允许一个请求；失败时把错误显示出来，返回 null，不改变讨论进度。
import type { PayloadMap, Purpose, ResultMap, Source } from '../../shared/types.ts';
import { getState, update } from '../store.ts';
import { apiRequest } from './api.ts';
import { demoRun } from './demo.ts';

let controller: AbortController | null = null;
export const cancelAI = () => controller?.abort();
export let demoDelay = 450;
export const setDemoDelay = (ms: number) => { demoDelay = ms; };

export async function task<T>(label: string, op: (signal: AbortSignal) => Promise<T>): Promise<T | null> {
  if (controller) return null;
  const c = new AbortController(); controller = c;
  let timedOut = false;
  const timer = setTimeout(() => { timedOut = true; c.abort(); }, 100_000);
  update(s => { s.ui.busy = label; s.ui.error = ''; });
  try {
    const result = await op(c.signal);
    if (c.signal.aborted) throw new Error('已取消');
    return result;
  } catch (e) {
    const msg = c.signal.aborted ? (timedOut ? '请求超时，当前进度没有改变。' : '已取消，当前进度没有改变。') : (e as Error).message || '请求失败，请检查本机服务。';
    update(s => { s.ui.error = msg; });
    return null;
  } finally {
    clearTimeout(timer); controller = null;
    update(s => { s.ui.busy = null; });
  }
}

export async function runAI<P extends Purpose>(purpose: P, payload: PayloadMap[P], label: string): Promise<(ResultMap[P] & { source: Source }) | null> {
  const { config, ai } = getState();
  if (config.aiMode === 'api') {
    if (!ai.available || !ai.configured) {
      update(s => { s.ui.error = '还没有连接 API。请在“设置”里连接，或切换到演示模式。'; });
      return null;
    }
    const r = await task(label, signal => apiRequest<ResultMap[P]>('/api/generate', { method: 'POST', signal, body: JSON.stringify({ purpose, payload }) }));
    return r && { ...r, source: 'api' as const };
  }
  if (config.aiMode === 'remote') {
    if (!config.remote.url) {
      update(s => { s.ui.error = '还没有连接你的后端。请在“设置”里填写后端地址和访问口令，或切换到演示模式。'; });
      return null;
    }
    const r = await task(label, signal => apiRequest<ResultMap[P]>('/api/generate', { method: 'POST', signal, body: JSON.stringify({ purpose, payload }) }, 'remote'));
    return r && { ...r, source: 'api' as const };
  }
  const r = await task(label, () => demoRun(purpose, payload, demoDelay) as Promise<ResultMap[P]>);
  return r && { ...r, source: 'demo' as const };
}
