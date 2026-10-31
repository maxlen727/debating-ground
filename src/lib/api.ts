// 与本机服务通信
import { update } from '../store.ts';
import type { AiStatus } from '../store.ts';

export async function apiRequest<T = unknown>(path: string, options: RequestInit = {}): Promise<T> {
  const res = await fetch(path, { ...options, credentials: 'same-origin', headers: { 'Content-Type': 'application/json', 'X-Sibian-Client': 'web', ...(options.headers ?? {}) } });
  let data: { error?: string } & Record<string, unknown>;
  try { data = await res.json(); } catch { throw new Error('本机服务没有返回有效响应，请用 npm start 启动完整版本。'); }
  if (!res.ok) throw new Error(data.error || '请求失败。');
  return data as T;
}

type Public = Omit<AiStatus, 'available'>;

export async function refreshStatus() {
  if (typeof location === 'undefined' || !/^https?:$/.test(location.protocol)) return;
  try {
    const data = await apiRequest<Public>('/api/config');
    update(s => { s.ai = { ...s.ai, ...data, available: true }; });
  } catch { update(s => { s.ai.available = false; }); }
}

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
