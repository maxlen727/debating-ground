// 通用界面组件
import { useEffect, useRef, type ReactNode } from 'react';
import { cancelAI } from '../lib/ai.ts';
import { go, update, useApp, type Screen } from '../store.ts';
import type { PointStatus, QuizItem } from '../../shared/types.ts';
import { STATUS_LABEL } from '../../shared/modes.ts';
import { pickAnswer } from '../actions.ts';

const PATHS: Record<string, string> = {
  back: 'M15 18l-6-6 6-6',
  send: 'M5 12h13M12 5l7 7-7 7',
  plus: 'M12 5v14M5 12h14',
  close: 'M6 6l12 12M18 6L6 18',
  minus: 'M5 12h14',
  up: 'M12 19V5M5 12l7-7 7 7',
  chevron: 'M9 6l6 6-6 6',
  download: 'M12 4v11M7 10l5 5 5-5M5 20h14',
  learn: 'M4 19V6a2 2 0 0 1 2-2h12v15H6a2 2 0 0 0-2 2zM8 8h6',
  chat: 'M5 5h14v10H9l-4 4z',
  library: 'M5 4h4v16H5zM10 4h4v16h-4zM15.5 5l3.5 1-3 14-3.5-1z',
  settings: 'M12 15a3 3 0 1 0 0-6 3 3 0 0 0 0 6zM19 12a7 7 0 0 0-.1-1.2l2-1.5-2-3.4-2.3.9a7 7 0 0 0-2.1-1.2L14 3h-4l-.5 2.6a7 7 0 0 0-2.1 1.2l-2.3-.9-2 3.4 2 1.5A7 7 0 0 0 5 12c0 .4 0 .8.1 1.2l-2 1.5 2 3.4 2.3-.9a7 7 0 0 0 2.1 1.2L10 21h4l.5-2.6a7 7 0 0 0 2.1-1.2l2.3.9 2-3.4-2-1.5c.1-.4.1-.8.1-1.2z',
  bulb: 'M9 18h6M10 21h4M12 3a6 6 0 0 0-3.5 10.9c.6.5 1 1.2 1 2V16h5v-.1c0-.8.4-1.5 1-2A6 6 0 0 0 12 3z',
  note: 'M5 4h10l4 4v12H5zM14 4v5h5M8 13h8M8 17h5',
  people: 'M9 11a3 3 0 1 0 0-6 3 3 0 0 0 0 6zM3 20a6 6 0 0 1 12 0M16 4a3 3 0 0 1 0 6M18 14a5 5 0 0 1 3 6',
  volume: 'M4 9v6h4l5 4V5L8 9zM16.5 8.5a5 5 0 0 1 0 7M19 6a8.5 8.5 0 0 1 0 12',
  stop: 'M7 7h10v10H7z',
  mic: 'M12 3a3 3 0 0 0-3 3v6a3 3 0 0 0 6 0V6a3 3 0 0 0-3-3zM5.5 11a6.5 6.5 0 0 0 13 0M12 17.5V21M8.5 21h7'
};
export function Icon({ name, size = 20 }: { name: keyof typeof PATHS | string; size?: number }) {
  return <svg className="icon" width={size} height={size} viewBox="0 0 24 24" fill="none" stroke="currentColor" strokeWidth="1.8" strokeLinecap="round" strokeLinejoin="round" aria-hidden="true"><path d={PATHS[name]} /></svg>;
}

export function TopBar({ title, sub, back, right }: { title: ReactNode; sub?: ReactNode; back?: Screen | (() => void); right?: ReactNode }) {
  return (
    <header className="topbar">
      {back ? <button className="icon-btn" aria-label="返回" onClick={() => typeof back === 'function' ? back() : go(back)}><Icon name="back" /></button> : <span className="topbar-pad" />}
      <div className="topbar-title"><h1>{title}</h1>{sub && <p>{sub}</p>}</div>
      <div className="topbar-right">{right}</div>
    </header>
  );
}

export function BusyStrip() {
  const { ui } = useApp();
  if (!ui.busy) return null;
  return <div className="busy-strip" role="status"><span className="spinner" aria-hidden="true" /><span>{ui.busy}</span><button className="link" onClick={cancelAI}>取消</button></div>;
}
export function ErrorBanner() {
  const { ui } = useApp();
  if (!ui.error) return null;
  return <div className="alert" role="alert"><span>{ui.error}</span><button className="icon-btn small" aria-label="关闭" onClick={() => update(s => { s.ui.error = ''; })}><Icon name="close" size={16} /></button></div>;
}

export function TabBar() {
  const { ui, library, session } = useApp();
  const due = library.filter(c => c.due <= Date.now()).length;
  const tabs: [Screen, string, string][] = [['learn', 'learn', '学习'], ['session', 'chat', '讨论'], ['library', 'library', '知识库'], ['settings', 'settings', '设置']];
  const active = (s: Screen) => ui.screen === s || (s === 'library' && ui.screen === 'card') || (s === 'session' && ui.screen === 'review');
  return (
    <nav className="tabbar" aria-label="主导航">
      {tabs.map(([s, icon, label]) => (
        <button key={s} className={active(s) ? 'active' : ''} aria-current={active(s) ? 'page' : undefined} onClick={() => go(s === 'session' && session?.finished && session.review ? 'review' : s)}>
          <span className="tab-icon"><Icon name={icon} size={22} />{s === 'library' && due > 0 && <em className="badge-dot">{due}</em>}</span>
          <span>{label}</span>
        </button>
      ))}
    </nav>
  );
}

export function Sheet({ title, onClose, children, className = '' }: { title: string; onClose: () => void; children: ReactNode; className?: string }) {
  const ref = useRef<HTMLDivElement>(null);
  useEffect(() => {
    const k = (e: KeyboardEvent) => { if (e.key === 'Escape') onClose(); };
    window.addEventListener('keydown', k); ref.current?.focus();
    return () => window.removeEventListener('keydown', k);
  }, [onClose]);
  return (
    <div className="sheet-backdrop" onClick={onClose}>
      <div className={'sheet ' + className} role="dialog" aria-modal="true" aria-label={title} tabIndex={-1} ref={ref} onClick={e => e.stopPropagation()}>
        <div className="sheet-grip" aria-hidden="true" />
        <div className="sheet-head"><h2>{title}</h2><button className="icon-btn" aria-label="关闭" onClick={onClose}><Icon name="close" /></button></div>
        {children}
      </div>
    </div>
  );
}

export function Toast() {
  const { ui } = useApp();
  return <div className={'toast' + (ui.toast ? ' show' : '')} role="status" aria-live="polite">{ui.toast}</div>;
}

export function Pill({ status }: { status: PointStatus }) {
  return <span className={'pill ' + status}>{STATUS_LABEL[status]}</span>;
}

export function Quiz({ id, items }: { id: string; items: QuizItem[] }) {
  const { ui } = useApp();
  const picks = ui.quiz[id] ?? {};
  return (
    <ol className="quiz">
      {items.map((q, qi) => {
        const picked = picks[qi];
        return (
          <li key={qi}>
            <p className="q">{q.q}</p>
            <div className="opts">
              {q.options.map((o, oi) => {
                const cls = picked == null ? '' : oi === q.answer ? 'right' : oi === picked ? 'wrong' : 'dim';
                return <button key={oi} className={'opt ' + cls} disabled={picked != null} onClick={() => pickAnswer(id, qi, oi)}><b>{'ABCD'[oi]}</b>{o}</button>;
              })}
            </div>
            {picked != null && <p className={'why ' + (picked === q.answer ? 'ok' : 'no')}>{picked === q.answer ? '答对了。' : `正确答案是 ${'ABCD'[q.answer]}。`}{q.why}</p>}
          </li>
        );
      })}
    </ol>
  );
}

export function Empty({ title, text, action }: { title: string; text: string; action?: ReactNode }) {
  return <div className="empty"><div className="empty-mark" aria-hidden="true">“ ”</div><h2>{title}</h2><p>{text}</p>{action}</div>;
}

export function Stepper({ value, min, max, onChange, label }: { value: number; min: number; max: number; onChange: (v: number) => void; label: string }) {
  return (
    <div className="stepper" role="group" aria-label={label}>
      <button className="icon-btn small" aria-label={'减少' + label} disabled={value <= min} onClick={() => onChange(value - 1)}><Icon name="minus" size={16} /></button>
      <span>{value}</span>
      <button className="icon-btn small" aria-label={'增加' + label} disabled={value >= max} onClick={() => onChange(value + 1)}><Icon name="plus" size={16} /></button>
    </div>
  );
}
