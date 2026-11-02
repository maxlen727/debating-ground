// 光：高亮不只是自己变亮，光会落到周围的按钮、气泡和面板边缘上。
// 整个页面共用一个光源（--lx/--ly/--lr/--lc，强度 --li），受光的元素用一张固定在视口上的径向渐变
// 画自己的边框，于是离光源近的边亮、远的边暗，不需要逐个元素计算。
// 另有一盏跟着鼠标的小灯（--px/--py，强度 --pi），只在有悬停的设备上。

type Point = { x: number; y: number };
interface FlashOptions { color?: string; radius?: number; strength?: number; duration?: number }

const reduced = () => typeof matchMedia === 'function' && matchMedia('(prefers-reduced-motion: reduce)').matches;
let current: Animation | null = null;

function centerOf(target: Element | Point): Point | null {
  if (!('getBoundingClientRect' in target)) return target;
  const r = target.getBoundingClientRect();
  if (!r.width && !r.height) return null;
  return { x: r.left + r.width / 2, y: r.top + r.height / 2 };
}

// 在某个元素（或某个点）上闪一下光：很快亮起，再慢慢暗下去
export function flash(target: Element | Point | null | undefined, { color = 'var(--light)', radius = 200, strength = 1, duration = 1600 }: FlashOptions = {}) {
  if (!target || typeof document === 'undefined' || reduced()) return;
  const c = centerOf(target);
  if (!c) return;
  const root = document.documentElement;
  root.style.setProperty('--lx', c.x + 'px');
  root.style.setProperty('--ly', c.y + 'px');
  root.style.setProperty('--lr', radius + 'px');
  root.style.setProperty('--lc', color);
  current?.cancel();
  current = root.animate([{ '--li': 0 }, { '--li': strength, offset: 0.1 }, { '--li': 0 }], { duration, easing: 'cubic-bezier(.3, .7, .4, 1)' });
}

// 按下主要按钮时，光从手指下溅开一点（触屏也有）；鼠标另有一盏跟着指针的小灯，指针移出页面就熄灭
export function trackLights() {
  if (typeof document === 'undefined' || reduced()) return;
  document.addEventListener('pointerdown', e => {
    const b = (e.target as Element | null)?.closest?.('.primary, .send, .tool, .chip, .branch-chip');
    if (b) flash({ x: e.clientX, y: e.clientY }, { radius: 140, strength: 0.7, duration: 900 });
  }, { passive: true, capture: true });
  if (!matchMedia('(hover: hover) and (pointer: fine)').matches) return;
  const root = document.documentElement;
  let raf = 0, x = 0, y = 0;
  document.addEventListener('pointermove', e => {
    x = e.clientX; y = e.clientY;
    if (raf) return;
    raf = requestAnimationFrame(() => { raf = 0; root.style.setProperty('--px', x + 'px'); root.style.setProperty('--py', y + 'px'); root.style.setProperty('--pi', '1'); });
  }, { passive: true });
  document.documentElement.addEventListener('pointerleave', () => root.style.setProperty('--pi', '0'));
}
