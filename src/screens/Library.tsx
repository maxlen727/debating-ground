import { useState } from 'react';
import { MODES } from '../../shared/modes.ts';
import { discussAgain, openCard, rateCard } from '../actions.ts';
import { BusyStrip, Empty, ErrorBanner, Icon, Quiz, TopBar } from '../components/ui.tsx';
import { exportLibrary } from '../lib/exporter.ts';
import { dueLabel } from '../lib/srs.ts';
import { go, useApp, type Card } from '../store.ts';
import { ReviewBody } from './Review.tsx';

function Meter({ c }: { c: Card }) {
  return <span className="meter" aria-label={`已掌握 ${c.points.filter(p => p.status === 'mastered').length}/${c.points.length}`}>{c.points.map((p, i) => <i key={i} className={p.status} />)}</span>;
}

export function Library() {
  const { library } = useApp();
  const now = Date.now();
  const due = library.filter(c => c.due <= now);
  const [tab, setTab] = useState<'due' | 'all'>(due.length ? 'due' : 'all');
  const list = tab === 'due' ? due : library;
  return (
    <div className="screen library">
      <TopBar title="知识库" sub={`${library.length} 张卡片 · 只保存在这台设备`} right={library.length > 0 && <button className="icon-btn" aria-label="导出知识库" onClick={() => exportLibrary(library)}><Icon name="download" /></button>} />
      <div className="stack pad">
        {library.length === 0 ? (
          <Empty title="知识库还是空的" text="完成一次讨论并生成复盘后，它会变成一张知识卡片，并按 1、3、7、14 天的间隔提醒你回顾。" action={<button className="primary" onClick={() => go('learn')}>开始学习</button>} />
        ) : (
          <>
            <div className="segmented" role="tablist">
              <button role="tab" aria-selected={tab === 'due'} className={tab === 'due' ? 'on' : ''} onClick={() => setTab('due')}>待复习 {due.length}</button>
              <button role="tab" aria-selected={tab === 'all'} className={tab === 'all' ? 'on' : ''} onClick={() => setTab('all')}>全部 {library.length}</button>
            </div>
            {list.length === 0 ? <p className="muted center">今天没有要复习的卡片。</p> : (
              <ul className="card-list">
                {list.map(c => (
                  <li key={c.id}>
                    <button className={'kcard' + (c.due <= now ? ' due' : '')} onClick={() => openCard(c.id)}>
                      <span className="row between"><b>{c.concept}</b><small className={c.due <= now ? 'due-tag' : 'muted'}>{c.due <= now ? '待复习' : '下次 ' + dueLabel(c.due, now)}</small></span>
                      <span className="kline">{c.oneLine}</span>
                      <Meter c={c} />
                      <small className="muted">{MODES[c.mode].name} · {new Date(c.created).toLocaleDateString('zh-CN')} · 复习 {c.reviews.length} 次</small>
                    </button>
                  </li>
                ))}
              </ul>
            )}
          </>
        )}
      </div>
    </div>
  );
}

export function CardView() {
  const { library, ui } = useApp();
  const c = library.find(x => x.id === ui.openCard);
  const [showReview, setShowReview] = useState(false);
  if (!c) return <div className="screen"><TopBar title="知识卡片" back="library" /><Empty title="找不到这张卡片" text="它可能已经被清除。" /></div>;
  const answered = Object.keys(ui.quiz['c:' + c.id] ?? {}).length;
  const open = showReview || !c.quiz.length || answered >= c.quiz.length;
  return (
    <div className="screen card-view">
      <TopBar title={c.concept} sub={`${MODES[c.mode].name} · 已复习 ${c.reviews.length} 次`} back="library" />
      <div className="stack pad">
        <p className="lede topic">{c.topic}</p>
        {c.quiz.length > 0 && <section className="panel"><h3 className="section-label">先凭记忆作答</h3><Quiz id={'c:' + c.id} items={c.quiz} /></section>}
        {open ? (
          <>
            <section className="review-hero slim"><p className="display-sm">{c.oneLine}</p></section>
            <ReviewBody r={c} terms={c.terms} />
          </>
        ) : <button className="ghost block" onClick={() => setShowReview(true)}>先看上次的复盘</button>}
        <button className="ghost block" disabled={!!ui.busy} onClick={() => discussAgain(c)}>从薄弱点再讨论一次</button>
      </div>
      <div className="dock">
        <ErrorBanner /><BusyStrip />
        <p className="dock-q">这次回顾得怎么样？</p>
        <div className="row">
          <button className="secondary grow" onClick={() => rateCard(c.id, false)}>还有点模糊</button>
          <button className="primary grow" onClick={() => rateCard(c.id, true)}>记得很清楚</button>
        </div>
      </div>
    </div>
  );
}
