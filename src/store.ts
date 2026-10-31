// 全局状态：一个可订阅的对象，持久化到 localStorage。
// update(fn) 在副本上修改再整体替换，组件用 useApp() 订阅。
import { useSyncExternalStore } from 'react';
import type { Entry, ModeId, Persona, Previous, Review, Seat, SeatConfig, Source, Stage, Team, Turn, ExplainKind } from '../shared/types.ts';

export type Screen = 'learn' | 'prepare' | 'session' | 'review' | 'library' | 'card' | 'settings';

export interface Config {
  concept: string;
  mode: ModeId;
  aiMode: 'demo' | 'api';
  counts: Record<Team, number>;
  seats: Record<Team, SeatConfig[]>;
  stages: Stage[];
}
export interface Plan {
  mode: ModeId; concept: string; topic: string; keyPoints: string[]; opening: string;
  personas: Persona[]; source: Source; model?: string; previous: Previous | null;
}
export interface DebateSeat extends Seat { name: string }
export interface Session {
  id: string; created: number; mode: ModeId; concept: string; topic: string; keyPoints: string[];
  covered: number[]; entries: Entry[]; learnerTurns: number; nextRole: number; demoStep: number;
  finished: boolean; draft: string; previous: Previous | null;
  personas: Persona[];
  seats: DebateSeat[]; stages: Stage[]; turns: Turn[]; cursor: number;
  review: (Review & { source: Source; model?: string }) | null;
}
export interface Card extends Review {
  id: string; sessionId: string; concept: string; topic: string; mode: ModeId; created: number;
  source: Source; model?: string; level: number; due: number; reviews: { at: number; remembered: boolean }[];
}
export interface AiStatus { available: boolean; configured: boolean; verified: boolean; baseURL: string; model: string }
export interface Ui {
  screen: Screen;
  busy: string | null;           // 正在进行的 AI 请求说明
  error: string;
  toast: string;
  hint: string | null;
  explain: { kind: ExplainKind; label: string; quote: string; text: string } | null;
  sheet: string | null;          // 打开操作面板的消息 id
  openCard: string | null;
  quiz: Record<string, Record<number, number>>;
}
export interface AppState { config: Config; plan: Plan | null; session: Session | null; library: Card[]; ai: AiStatus; ui: Ui }

const STORE = 'sibian-v3';

export const defaultConfig = (): Config => ({
  concept: '机会成本', mode: 'socratic', aiMode: 'demo',
  counts: { A: 2, B: 2 },
  seats: {
    A: [{ type: 'human', bg: '' }, { type: 'ai', bg: '经济学研究者，习惯用数据和模型说话。' }],
    B: [{ type: 'ai', bg: '中学老师，关注概念在日常生活里怎么被误用。' }, { type: 'ai', bg: '创业者，从真实的经营取舍出发。' }]
  },
  stages: [
    { name: '开篇立论', kind: 'opening', rounds: 1 }, { name: '交叉质询', kind: 'cross', rounds: 1 },
    { name: '自由辩论', kind: 'free', rounds: 2 }, { name: '总结陈词', kind: 'closing', rounds: 1 }
  ]
});
const defaultUi = (): Ui => ({ screen: 'learn', busy: null, error: '', toast: '', hint: null, explain: null, sheet: null, openCard: null, quiz: {} });

function load(): AppState {
  const base: AppState = { config: defaultConfig(), plan: null, session: null, library: [], ai: { available: false, configured: false, verified: false, baseURL: '', model: '' }, ui: defaultUi() };
  try {
    const saved = JSON.parse(localStorage.getItem(STORE) ?? 'null');
    if (saved) return { ...base, config: { ...base.config, ...saved.config }, plan: saved.plan ?? null, session: saved.session ?? null, library: Array.isArray(saved.library) ? saved.library : [], ui: { ...base.ui, screen: saved.screen === 'session' && saved.session && !saved.session.finished ? 'session' : 'learn' } };
    // v0.4 的知识卡片结构相同，迁移过来
    const v2 = JSON.parse(localStorage.getItem('sibian-v2') ?? 'null');
    if (v2 && Array.isArray(v2.library)) return { ...base, library: v2.library };
  } catch { /* 读取失败就从头开始 */ }
  return base;
}

let state: AppState = load();
const listeners = new Set<() => void>();
let saveFailed = false;

function persist() {
  try {
    localStorage.setItem(STORE, JSON.stringify({ config: state.config, plan: state.plan, session: state.session, library: state.library, screen: state.ui.screen }));
    saveFailed = false;
  } catch {
    if (!saveFailed) { saveFailed = true; queueMicrotask(() => toast('浏览器未能保存记录，请导出本次内容。')); }
  }
}

export const getState = () => state;
export function update(fn: (draft: AppState) => void) {
  const next = structuredClone(state);
  fn(next);
  state = next;
  persist();
  listeners.forEach(l => l());
}
export function useApp(): AppState {
  return useSyncExternalStore(cb => { listeners.add(cb); return () => listeners.delete(cb); }, () => state);
}

let toastTimer: ReturnType<typeof setTimeout> | undefined;
export function toast(text: string) {
  update(s => { s.ui.toast = text; });
  clearTimeout(toastTimer);
  toastTimer = setTimeout(() => update(s => { s.ui.toast = ''; }), 3000);
}
export function go(screen: Screen) {
  update(s => { s.ui.screen = screen; s.ui.sheet = null; s.ui.explain = null; });
  globalThis.scrollTo?.({ top: 0 });
}
export const uid = () => Date.now().toString(36) + Math.random().toString(36).slice(2, 7);

// 测试用：重置为初始状态
export function resetForTests(next?: Partial<AppState>) {
  state = { ...load(), ...next };
  listeners.forEach(l => l());
}
