// 逐字显示 AI 正在生成的文字。
// 收到的文字一次可能来一大段，直接整段蹦出来会很突兀：这里按“积压越多越快”的速度追赶，
// 最新的几个字淡入，末尾跟着一个光标。生成中的气泡和完成后的正式发言共用同一个 id 的进度，所以衔接时不会重来。
import { useEffect, useRef, useState, type ReactNode } from 'react';
import { doneRevealing, revealed, setRevealed } from '../lib/stream.ts';

const reducedMotion = () => typeof matchMedia === 'function' && matchMedia('(prefers-reduced-motion: reduce)').matches;
const FADE = 18;  // 最新的这么多个字单独淡入

export function Reveal({ id, text, live, final }: { id: string; text: string; live: boolean; final?: ReactNode }) {
  const [, frame] = useState(0);
  const target = useRef(text); target.current = text;
  const still = useRef(live); still.current = live;
  const skip = reducedMotion() || (!live && revealed(id) === undefined);

  useEffect(() => {
    if (skip) return;
    if (revealed(id) === undefined) setRevealed(id, 0);
    let raf = 0, last = performance.now();
    const tick = (now: number) => {
      const dt = Math.min(0.1, (now - last) / 1000); last = now;
      const n = revealed(id) ?? 0, len = target.current.length, backlog = len - n;
      if (backlog <= 0) {
        if (!still.current) { doneRevealing(id); frame(x => x + 1); return; }
      } else {
        // 积压少时慢慢写，积压多时加速追上，避免最后一口气吐出一大段
        const speed = Math.max(24, backlog * 4.5);
        setRevealed(id, Math.min(len, n + speed * dt));
        frame(x => x + 1);
      }
      raf = requestAnimationFrame(tick);
    };
    raf = requestAnimationFrame(tick);
    return () => cancelAnimationFrame(raf);
  }, [id, skip]);

  const n = Math.floor(revealed(id) ?? (skip ? text.length : 0));
  if (skip || (!live && revealed(id) === undefined)) return <>{final ?? text}</>;
  const cut = Math.max(0, n - FADE);
  return (
    <>
      {text.slice(0, cut)}
      {Array.from(text.slice(cut, n), (ch, i) => <span key={cut + i} className="ink">{ch}</span>)}
      <i className="stream-caret" aria-hidden="true" />
    </>
  );
}
