// 正在生成的 AI 文字。它变化得很快（每秒几十次），所以不放进全局 store：
// 那里每次修改都会复制整个状态并写入 localStorage。半截文字也不该被保存。
import { useSyncExternalStore } from 'react';

export type StreamKind = 'msg' | 'hint' | 'explain' | 'reflect';
export interface Live { id: string; kind: StreamKind; label: string; text: string; who?: string }

let live: Live | null = null;
const listeners = new Set<() => void>();
const emit = () => listeners.forEach(l => l());

export const getLive = () => live;
// 新的一段生成从头显示（同一个 id 上次没显示完的进度作废）
export function setLive(next: Live | null) { if (next) shown.delete(next.id); live = next; emit(); }
export function updateLive(id: string, text: string) { if (live?.id === id && live.text !== text) { live = { ...live, text }; emit(); } }
export function clearLive(id?: string) { if (live && (!id || live.id === id)) { live = null; emit(); } }
export function useLive(): Live | null {
  return useSyncExternalStore(cb => { listeners.add(cb); return () => listeners.delete(cb); }, () => live);
}

/* ---------- 逐字显示的进度 ---------- */
// 生成中的气泡和完成后的正式发言用同一个 id，共享“已经显示到第几个字”，换成正式发言时动画不会重来
const shown = new Map<string, number>();
export const revealed = (id: string) => shown.get(id);
export const setRevealed = (id: string, n: number) => { shown.set(id, n); };
export const doneRevealing = (id: string) => { shown.delete(id); };
