// 与后端通信：本机服务（同源，127.0.0.1:4173）或自己部署的后端（Cloudflare Worker，跨域 + 访问口令）
import { getState, update } from '../store.ts';
import type { AiStatus } from '../store.ts';

export type Target = 'local' | 'remote';

async function readJSON(res: Response, fallback: string) {
  let data: { error?: string } & Record<string, unknown>;
  try { data = await res.json(); } catch { throw new Error(fallback); }
  if (!res.ok) throw new Error(data.error || '请求失败。');
  return data;
}

export async function apiRequest<T = unknown>(path: string, options: RequestInit = {}, target: Target = 'local'): Promise<T> {
  if (target === 'remote') {
    const { url, password } = getState().config.remote;
    if (!url) throw new Error('还没有连接你的后端，请在“设置”里填写后端地址和访问口令。');
    let res: Response;
    try {
      res = await fetch(url.replace(/\/+$/, '') + path, { ...options, credentials: 'omit', headers: { 'Content-Type': 'application/json', Authorization: 'Bearer ' + password, ...(options.headers ?? {}) } });
    } catch (e) {
      if ((e as Error).name === 'AbortError') throw e;
      throw new Error('连不上你的后端。请检查地址是否正确、后端是否已部署，以及 ALLOWED_ORIGINS 是否包含当前网站。');
    }
    return readJSON(res, '后端没有返回有效响应，请确认地址指向思辨场后端。') as Promise<T>;
  }
  const res = await fetch(path, { ...options, credentials: 'same-origin', headers: { 'Content-Type': 'application/json', 'X-Sibian-Client': 'web', ...(options.headers ?? {}) } });
  return readJSON(res, '本机服务没有返回有效响应，请用 npm start 启动完整版本。') as Promise<T>;
}

// 流式请求：后端用 SSE 发来 delta（新增文字）、done（完整结果）、error（失败，已显示的文字要撤回）。
// 旧版后端不认识 stream，会直接返回 JSON，这时按普通请求处理。
export async function apiStream<T>(path: string, body: Record<string, unknown>, onText: (text: string) => void, signal: AbortSignal, target: Target = 'local'): Promise<T> {
  const options: RequestInit = { method: 'POST', signal, body: JSON.stringify({ ...body, stream: true }), headers: { Accept: 'text/event-stream' } };
  let res: Response;
  if (target === 'remote') {
    const { url, password } = getState().config.remote;
    if (!url) throw new Error('还没有连接你的后端，请在“设置”里填写后端地址和访问口令。');
    try {
      res = await fetch(url.replace(/\/+$/, '') + path, { ...options, credentials: 'omit', headers: { 'Content-Type': 'application/json', Authorization: 'Bearer ' + password, Accept: 'text/event-stream' } });
    } catch (e) {
      if ((e as Error).name === 'AbortError') throw e;
      throw new Error('连不上你的后端。请检查地址是否正确、后端是否已部署，以及 ALLOWED_ORIGINS 是否包含当前网站。');
    }
  } else {
    res = await fetch(path, { ...options, credentials: 'same-origin', headers: { 'Content-Type': 'application/json', 'X-Sibian-Client': 'web', Accept: 'text/event-stream' } });
  }
  if (!(res.headers.get('Content-Type') ?? '').includes('text/event-stream') || !res.body)
    return readJSON(res, target === 'remote' ? '后端没有返回有效响应，请确认地址指向思辨场后端。' : '本机服务没有返回有效响应，请用 npm start 启动完整版本。') as Promise<T>;
  const reader = res.body.getReader(), decoder = new TextDecoder();
  let buffer = '', text = '';
  while (true) {
    const { done, value } = await reader.read();
    if (done) break;
    buffer += decoder.decode(value, { stream: true });
    const events = buffer.split('\n\n'); buffer = events.pop() ?? '';
    for (const block of events) {
      const name = block.match(/^event: (.+)$/m)?.[1];
      const raw = block.match(/^data: (.*)$/m)?.[1];
      if (!name || raw === undefined) continue;
      const data = JSON.parse(raw);
      if (name === 'delta') { text += data.t; onText(text); }
      else if (name === 'done') { await reader.cancel().catch(() => {}); return data as T; }
      else if (name === 'error') throw new Error(data.error || '请求失败。');
    }
  }
  throw new Error('连接在生成过程中断开了，本次内容未采用，请重试。');
}

type Public = Pick<AiStatus, 'configured' | 'verified' | 'baseURL' | 'model'> & { ttsConfigured?: boolean };

export async function refreshStatus() {
  if (typeof location === 'undefined' || !/^https?:$/.test(location.protocol)) return;
  try {
    const data = await apiRequest<Public>('/api/config');
    update(s => { s.ai = { ...s.ai, ...data, ttsConfigured: !!data.ttsConfigured, available: true }; });
  } catch { update(s => { s.ai.available = false; }); }
  // 读取后端状态不调用模型，不产生费用
  const { aiMode, remote } = getState().config;
  if (aiMode === 'remote' && remote.url) {
    try { const data = await apiRequest<Public>('/api/config', {}, 'remote'); update(s => { s.ai.remoteModel = data.model; s.ai.remoteTtsConfigured = !!data.ttsConfigured; }); } catch { /* 进入讨论时再提示 */ }
  }
}

/* ---------- 本机服务 ---------- */
export async function connect(input: { baseURL: string; model: string; apiKey: string }, signal: AbortSignal) {
  try {
    const data = await apiRequest<Public>('/api/config', { method: 'POST', signal, body: JSON.stringify(input) });
    update(s => { s.ai = { ...s.ai, ...data }; s.config.aiMode = 'api'; });
    await apiRequest('/api/test', { method: 'POST', signal, body: '{}' });
  } finally {
    input.apiKey = '';
    try { const status = await apiRequest<Public>('/api/config', { signal: AbortSignal.timeout(4000) }); update(s => { s.ai = { ...s.ai, ...status }; }); } catch { /* 忽略 */ }
  }
}
export const testConnection = (signal: AbortSignal) => apiRequest('/api/test', { method: 'POST', signal, body: '{}' });
export async function disconnect(signal: AbortSignal) {
  const data = await apiRequest<Public>('/api/config', { method: 'DELETE', signal });
  update(s => { s.ai = { ...s.ai, ...data }; s.config.aiMode = 'demo'; });
}
export async function saveTtsConfig(input: { appId: string; apiKey: string; apiSecret: string }, signal: AbortSignal) {
  const data = await apiRequest<{ ttsConfigured: boolean }>('/api/tts/config', { method: 'POST', signal, body: JSON.stringify(input) });
  update(s => { s.ai.ttsConfigured = !!data.ttsConfigured; });
}

/* ---------- 自己部署的后端 ---------- */
export function checkRemoteURL(raw: string): string {
  let u: URL;
  try { u = new URL(raw.trim()); } catch { throw new Error('请填写完整的后端地址，例如 https://debating-ground-api.你的子域.workers.dev'); }
  const local = ['localhost', '127.0.0.1', '[::1]'].includes(u.hostname);
  if (u.protocol !== 'https:' && !(u.protocol === 'http:' && local)) throw new Error('后端地址需要使用 HTTPS。');
  if (u.username || u.password || u.search || u.hash) throw new Error('后端地址不能包含账号、口令或查询参数。');
  return u.origin + u.pathname.replace(/\/+$/, '');
}

export async function connectRemote(url: string, password: string, signal: AbortSignal) {
  const clean = checkRemoteURL(url);
  update(s => { s.config.remote = { url: clean, password }; s.ai.remoteVerified = false; });
  const data = await apiRequest<Public>('/api/config', { signal }, 'remote');
  update(s => { s.ai.remoteModel = data.model; s.ai.remoteTtsConfigured = !!data.ttsConfigured; });
  await apiRequest('/api/test', { method: 'POST', signal, body: '{}' }, 'remote');
  update(s => { s.ai.remoteVerified = true; s.config.aiMode = 'remote'; });
}
export function forgetRemote() {
  update(s => { s.config.remote = { url: '', password: '' }; s.ai.remoteVerified = false; s.ai.remoteModel = ''; s.ai.remoteTtsConfigured = false; if (s.config.aiMode === 'remote') s.config.aiMode = 'demo'; });
}
