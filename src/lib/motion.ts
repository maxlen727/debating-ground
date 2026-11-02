// 页面和面板的连贯动效：从哪里来，回哪里去。
// 换页面、开关面板时，状态在 View Transitions 里提交：浏览器拍下前后两帧，
// 同名（view-transition-name）的元素从旧位置变形到新位置，其余部分按前进/后退的方向滑入滑出。
// 面板从点开它的那个按钮长出来，关上时缩回那个按钮。
// 不支持 View Transitions、或者用户要求减少动效时，直接切换。
import { flushSync } from 'react-dom';
import type { Screen } from '../store.ts';

export interface View { screen: Screen; overlay: string | null }

// 页面的层级：越深越“往里走”。同一层的标签页之间按标签顺序左右滑
const DEPTH: Record<Screen, number> = { learn: 0, library: 0, settings: 0, join: 0, prepare: 1, card: 1, session: 2, review: 3 };
const TABS: Screen[] = ['learn', 'session', 'library', 'settings'];

export type Nav = 'forward' | 'back' | 'left' | 'right' | 'expand' | 'collapse' | 'open' | 'close' | 'swap';
export function navKind(from: View, to: View): Nav {
  if (from.screen !== to.screen) {
    // 知识库的卡片点开时长成整页，返回时缩回列表里原来的位置
    if (from.screen === 'library' && to.screen === 'card') return 'expand';
    if (from.screen === 'card' && to.screen === 'library') return 'collapse';
    const a = DEPTH[from.screen], b = DEPTH[to.screen];
    // 标签栏上的“讨论”和“学习”“知识库”是平级的：底部导航来回切按左右滑
    const ta = TABS.indexOf(from.screen), tb = TABS.indexOf(to.screen);
    if (a === b || (ta >= 0 && tb >= 0 && (a === 0 || b === 0) && Math.abs(a - b) === 2)) return tb > ta ? 'left' : 'right';
    return b > a ? 'forward' : 'back';
  }
  if (!from.overlay && to.overlay) return 'open';
  if (from.overlay && !to.overlay) return 'close';
  return 'swap';
}

// 记下最近一次点的是哪个元素：面板从它长出来
let pressed: Element | null = null;
let sheetOrigin: HTMLElement | null = null;
const ORIGIN = 'button, [role="button"], a, label, .kcard';
export function trackOrigins() {
  document.addEventListener('pointerdown', e => { pressed = (e.target as Element | null)?.closest?.(ORIGIN) ?? null; }, true);
  document.addEventListener('keydown', e => { if (e.key === 'Enter' || e.key === ' ') pressed = document.activeElement; }, true);
  if (canAnimate()) document.documentElement.classList.add('vt');
}

const reduced = () => typeof matchMedia === 'function' && matchMedia('(prefers-reduced-motion: reduce)').matches;
const canAnimate = () => typeof document !== 'undefined' && typeof document.startViewTransition === 'function' && !reduced();

const name = (el: HTMLElement | null, value: string) => { if (el?.isConnected) el.style.viewTransitionName = value; };
let running: ViewTransition | null = null;
// 浏览器拍下旧画面之前，界面不能先变：这段时间里别的状态更新先不通知界面，等过渡回调里一起画
let capturing = false;
export const holdingRenders = () => capturing;

// commit 负责把新状态交给界面；screen 变了时顺便回到页面顶部（在新的一帧里做，旧画面不会先跳一下）
export function transition(from: View, to: View, commit: () => void) {
  const toTop = from.screen !== to.screen;
  const apply = () => { commit(); if (toTop) globalThis.scrollTo?.({ top: 0 }); };
  if (!canAnimate()) return apply();
  const kind = navKind(from, to);
  const root = document.documentElement;
  root.dataset.nav = kind;
  // 打开：被点的元素和面板同名，前一帧是按钮，后一帧是面板
  const origin = kind === 'open' && pressed instanceof HTMLElement ? pressed : null;
  if (origin) { sheetOrigin = origin; name(origin, 'sheet'); }
  running?.skipTransition();
  capturing = true;
  const vt = document.startViewTransition(() => {
    capturing = false;
    flushSync(apply);
    if (origin) origin.style.viewTransitionName = '';
    // 关上：面板已经不在了，让原来那个按钮接过这个名字，面板缩回去
    if (kind === 'close') name(sheetOrigin, 'sheet');
  });
  running = vt;
  // 过渡被跳过时回调也会执行；万一没执行，别让界面一直不更新
  vt.updateCallbackDone.catch(() => { if (capturing) { capturing = false; apply(); } });
  vt.finished.finally(() => {
    if (kind === 'close' && sheetOrigin) { sheetOrigin.style.viewTransitionName = ''; sheetOrigin = null; }
    if (running === vt) { running = null; delete root.dataset.nav; }
  });
}
