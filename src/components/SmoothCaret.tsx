// 平滑光标：输入框里的光标移动时滑过去，闪烁时淡入淡出（类似 VS Code 的 smooth caret）。
// 浏览器的原生光标没法加动画，所以把它设成透明，再在同样的位置画一个。
// 位置用“镜像元素”测量：复制输入框的排版样式，把光标前的文字放进去，量出光标所在的坐标。
// 有选区、失去焦点时交还原生光标；尊重“减少动态效果”（直接跳到位置、不闪烁）。
import { useCallback, useEffect, useRef, useState, type ReactNode, type RefObject } from 'react';

type Field = HTMLTextAreaElement | HTMLInputElement;
const COPY = ['boxSizing', 'width', 'borderTopWidth', 'borderRightWidth', 'borderBottomWidth', 'borderLeftWidth', 'borderStyle', 'paddingTop', 'paddingRight', 'paddingBottom', 'paddingLeft',
  'fontStyle', 'fontVariant', 'fontWeight', 'fontStretch', 'fontSize', 'lineHeight', 'fontFamily', 'textAlign', 'textTransform', 'textIndent', 'letterSpacing', 'wordSpacing', 'tabSize', 'wordBreak'] as const;

function measure(el: Field, pos: number) {
  const cs = getComputedStyle(el);
  const mirror = document.createElement('div');
  const single = el.nodeName === 'INPUT';
  for (const p of COPY) mirror.style[p] = cs[p];
  Object.assign(mirror.style, { position: 'absolute', visibility: 'hidden', top: '0', left: '-9999px', height: 'auto', overflow: 'hidden', whiteSpace: single ? 'pre' : 'pre-wrap', overflowWrap: single ? 'normal' : 'break-word' });
  mirror.textContent = el.value.slice(0, pos);
  const mark = document.createElement('span');
  mark.textContent = el.value.slice(pos, pos + 1) || '​';
  mirror.appendChild(mark);
  document.body.appendChild(mirror);
  const size = parseFloat(cs.fontSize);
  const height = Math.max(size * 1.15, Math.min(mark.offsetHeight, size * 1.5));
  const top = mark.offsetTop + (mark.offsetHeight - height) / 2;
  const box = { x: mark.offsetLeft - el.scrollLeft, y: top - el.scrollTop, h: height };
  mirror.remove();
  return box;
}

export function useSmoothCaret(ref: RefObject<Field | null>) {
  const [box, setBox] = useState<{ x: number; y: number; h: number; on: boolean; moving: boolean }>({ x: 0, y: 0, h: 0, on: false, moving: false });
  const frame = useRef(0), rest = useRef<ReturnType<typeof setTimeout> | undefined>(undefined);
  const sync = useCallback(() => {
    cancelAnimationFrame(frame.current);
    frame.current = requestAnimationFrame(() => {
      const el = ref.current;
      if (!el) return;
      const focused = document.activeElement === el;
      const collapsed = el.selectionStart !== null && el.selectionStart === el.selectionEnd;
      el.classList.toggle('caret-hidden', focused && collapsed);
      if (!focused || !collapsed) { setBox(b => ({ ...b, on: false })); return; }
      const m = measure(el, el.selectionStart ?? 0);
      // 超出可见区域时不画（例如滚动到别处）
      const visible = m.y + m.h > 0 && m.y < el.clientHeight + 2 && m.x >= -1 && m.x <= el.clientWidth + 2;
      setBox({ ...m, on: visible, moving: true });
      clearTimeout(rest.current);
      rest.current = setTimeout(() => setBox(b => ({ ...b, moving: false })), 450);
    });
  }, [ref]);

  useEffect(() => {
    const el = ref.current;
    if (!el) return;
    const events = ['input', 'keydown', 'keyup', 'pointerup', 'focus', 'blur', 'scroll', 'select', 'compositionupdate', 'compositionend'];
    events.forEach(e => el.addEventListener(e, sync));
    document.addEventListener('selectionchange', sync);
    window.addEventListener('resize', sync);
    const ro = typeof ResizeObserver === 'function' ? new ResizeObserver(sync) : null;
    ro?.observe(el);
    return () => {
      events.forEach(e => el.removeEventListener(e, sync));
      document.removeEventListener('selectionchange', sync);
      window.removeEventListener('resize', sync);
      ro?.disconnect();
      cancelAnimationFrame(frame.current); clearTimeout(rest.current);
      el.classList.remove('caret-hidden');
    };
  }, [ref, sync]);

  return box;
}

// 包住一个输入框：<CaretField fieldRef={ref}><textarea ref={ref} … /></CaretField>
export function CaretField({ fieldRef, className = '', children }: { fieldRef: RefObject<Field | null>; className?: string; children: ReactNode }) {
  const box = useSmoothCaret(fieldRef);
  return (
    <span className={'caret-host ' + className}>
      {children}
      <i aria-hidden="true" className={'smooth-caret' + (box.on ? ' on' : '') + (box.moving ? ' moving' : '')}
        style={{ transform: `translate3d(${box.x}px, ${box.y}px, 0)`, height: box.h }} />
    </span>
  );
}
