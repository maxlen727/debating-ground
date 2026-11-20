// AI 调用的统一入口：演示模式走本地模板，API 模式走本机服务。
// 同一时间只允许一个请求；失败时把错误显示出来，返回 null，不改变讨论进度。
import type { PayloadMap, Purpose, ResultMap, Source } from '../../shared/types.ts';
import { STREAMABLE } from '../../shared/purposes.ts';
import { getState, update } from '../store.ts';
import { apiRequest, apiStream } from './api.ts';
import { demoRun } from './demo.ts';
import { clearLive, setLive, updateLive, type StreamKind } from './stream.ts';
import { getMaterial, type Material } from './materials.ts';
import { contextQuery, materialContext } from './reader.ts';

let controller: AbortController | null = null;
export const cancelAI = () => controller?.abort();
export let demoDelay = 450;
export const setDemoDelay = (ms: number) => {
  demoDelay = ms;
};

export async function task<T>(
  label: string,
  op: (signal: AbortSignal) => Promise<T>,
): Promise<T | null> {
  if (controller) return null;
  const c = new AbortController();
  controller = c;
  let timedOut = false;
  const timer = setTimeout(() => {
    timedOut = true;
    c.abort();
  }, 100_000);
  update(s => {
    s.ui.busy = label;
    s.ui.error = '';
  });
  try {
    const result = await op(c.signal);
    if (c.signal.aborted) throw new Error('已取消');
    return result;
  } catch (e) {
    const msg = c.signal.aborted
      ? timedOut
        ? '请求超时，当前进度没有改变。'
        : '已取消，当前进度没有改变。'
      : (e as Error).message || '请求失败，请检查本机服务。';
    update(s => {
      s.ui.error = msg;
    });
    return null;
  } finally {
    clearTimeout(timer);
    controller = null;
    update(s => {
      s.ui.busy = null;
    });
  }
}

// 围绕材料的讨论（#166）：每次请求 AI 前，按讨论题和最近的发言换成材料里最相关的几节。
// 完整的材料在这台设备上（IndexedDB），读一次留在内存里；找不到（被删了）就用讨论开始时存下的那份
let cachedMaterial: Material | null = null;
const hasMaterial = (payload: unknown) =>
  !!(payload as { session?: { material?: unknown } }).session?.material &&
  !!getState().session?.material;
export async function withMaterial<T>(payload: T): Promise<T> {
  const p = payload as { session?: { material?: { title: string; excerpt: string } } };
  const s = p.session;
  const id = getState().session?.material?.id;
  if (!s?.material || !id) return payload;
  const m = cachedMaterial?.id === id ? cachedMaterial : await getMaterial(id);
  if (!m) return payload;
  cachedMaterial = m;
  const excerpt = materialContext(
    m.sections,
    contextQuery(s as Parameters<typeof contextQuery>[0]),
  );
  return { ...p, session: { ...s, material: { title: s.material.title, excerpt } } } as T;
}

// live：传了就边生成边显示（id 会沿用为正式发言的 id）。失败时撤回已显示的文字；成功时由调用方在写入结果后清掉。
export async function runAI<P extends Purpose>(
  purpose: P,
  payload: PayloadMap[P],
  label: string,
  live?: { id: string; kind: StreamKind; who?: string },
): Promise<(ResultMap[P] & { source: Source }) | null> {
  const stream = live && STREAMABLE.includes(purpose) ? live : undefined;
  if (stream) setLive({ ...stream, label, text: '' });
  // 只有围绕材料的讨论才要先读材料；其余请求不多等这一下（取消按钮立刻就能用）
  const ready = hasMaterial(payload) ? await withMaterial(payload) : payload;
  const result = await runAIInner(purpose, ready, label, stream);
  if (!result && stream) clearLive(stream.id);
  return result;
}

async function runAIInner<P extends Purpose>(
  purpose: P,
  payload: PayloadMap[P],
  label: string,
  stream?: { id: string },
): Promise<(ResultMap[P] & { source: Source }) | null> {
  const { config, ai } = getState();
  const onText = (t: string) => {
    if (stream) updateLive(stream.id, t);
  };
  if (config.aiMode === 'api') {
    if (!ai.available || !ai.configured) {
      update(s => {
        s.ui.error = '还没有连接 API。请在“设置”里连接，或切换到演示模式。';
      });
      return null;
    }
    const r = await task(label, signal =>
      stream
        ? apiStream<ResultMap[P]>('/api/generate', { purpose, payload }, onText, signal)
        : apiRequest<ResultMap[P]>('/api/generate', {
            method: 'POST',
            signal,
            body: JSON.stringify({ purpose, payload }),
          }),
    );
    return r && { ...r, source: 'api' as const };
  }
  if (config.aiMode === 'remote') {
    if (!config.remote.url) {
      update(s => {
        s.ui.error = '还没有连接你的后端。请在“设置”里填写后端地址和访问口令，或切换到演示模式。';
      });
      return null;
    }
    const r = await task(label, signal =>
      stream
        ? apiStream<ResultMap[P]>('/api/generate', { purpose, payload }, onText, signal, 'remote')
        : apiRequest<ResultMap[P]>(
            '/api/generate',
            { method: 'POST', signal, body: JSON.stringify({ purpose, payload }) },
            'remote',
          ),
    );
    return r && { ...r, source: 'api' as const };
  }
  const r = await task(label, async signal => {
    const out = (await demoRun(purpose, payload, demoDelay)) as ResultMap[P];
    // 演示模式也模拟逐字生成，测试时（demoDelay 为 0）跳过
    const text = (out as { text?: unknown } | null)?.text;
    if (stream && demoDelay > 0 && typeof text === 'string') {
      for (let i = 0; i < text.length && !signal.aborted;) {
        i = Math.min(text.length, i + 2 + Math.floor(Math.random() * 4));
        onText(text.slice(0, i));
        await new Promise(r => setTimeout(r, 40));
      }
    }
    return out;
  });
  return r && { ...r, source: 'demo' as const };
}
