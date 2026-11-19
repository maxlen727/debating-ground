import type { CSSProperties } from 'react';
import { useState } from 'react';
import { MODES } from '../../shared/modes.ts';
import { discussAgain, openCard, rateCard } from '../actions.ts';
import { BusyStrip, Empty, ErrorBanner, Icon, Quiz, TopBar } from '../components/ui.tsx';
import { exportLibrary } from '../lib/exporter.ts';
import { dueLabel } from '../lib/srs.ts';
import { SEARCH_MIN_CARDS, searchCards } from '../lib/search.ts';
import { go, useApp, type Card } from '../store.ts';
import { ReviewBody } from './Review.tsx';

function Meter({ c }: { c: Card }) {
  return (
    <span
      className="meter"
      aria-label={`已掌握 ${c.points.filter(p => p.status === 'mastered').length}/${c.points.length}`}
    >
      {c.points.map((p, i) => (
        <i key={i} className={p.status} />
      ))}
    </span>
  );
}

export function Library() {
  const { library } = useApp();
  const now = Date.now();
  const due = library.filter(c => c.due <= now);
  const [tab, setTab] = useState<'due' | 'all'>(due.length ? 'due' : 'all');
  // 搜索词只在这一页里，不存档；搜索时不分组，在全部卡片里找
  const [query, setQuery] = useState('');
  const searching = query.trim() !== '';
  const list = searching ? searchCards(library, query) : tab === 'due' ? due : library;
  return (
    <div className="screen library">
      <TopBar
        title="知识库"
        sub={`${library.length} 张卡片 · 只保存在这台设备`}
        right={
          library.length > 0 && (
            <button
              className="icon-btn"
              aria-label="导出知识库"
              onClick={() => exportLibrary(library)}
            >
              <Icon name="download" />
            </button>
          )
        }
      />
      <div className="stack pad">
        {library.length === 0 ? (
          <Empty
            title="知识库还是空的"
            text="完成一次讨论并生成复盘后，它会变成一张知识卡片，并按 1、3、7、14 天的间隔提醒你回顾。"
            action={
              <button className="primary" onClick={() => go('learn')}>
                开始学习
              </button>
            }
          />
        ) : (
          <>
            {library.length >= SEARCH_MIN_CARDS && (
              <label className="search-box">
                <Icon name="search" size={18} />
                <input
                  type="search"
                  value={query}
                  placeholder="搜概念、结论、术语"
                  aria-label="搜索知识卡片"
                  enterKeyHint="search"
                  onChange={e => setQuery(e.target.value)}
                  onKeyDown={e => {
                    if (e.key === 'Escape') setQuery('');
                  }}
                />
                {searching && (
                  <button
                    className="search-clear"
                    aria-label="清除搜索"
                    onClick={() => setQuery('')}
                  >
                    <Icon name="close" size={16} />
                  </button>
                )}
              </label>
            )}
            {searching ? (
              <p className="muted search-count" role="status">
                {list.length ? `找到 ${list.length} 张卡片` : ''}
              </p>
            ) : (
              <div className="segmented" role="tablist">
                <button
                  role="tab"
                  aria-selected={tab === 'due'}
                  className={tab === 'due' ? 'on' : ''}
                  onClick={() => setTab('due')}
                >
                  待复习 {due.length}
                </button>
                <button
                  role="tab"
                  aria-selected={tab === 'all'}
                  className={tab === 'all' ? 'on' : ''}
                  onClick={() => setTab('all')}
                >
                  全部 {library.length}
                </button>
              </div>
            )}
            {list.length === 0 ? (
              searching ? (
                <div className="center stack-sm">
                  <p className="muted">没有找到和“{query.trim()}”有关的卡片。</p>
                  <button className="ghost" onClick={() => setQuery('')}>
                    清除搜索
                  </button>
                </div>
              ) : (
                <p className="muted center">今天没有要复习的卡片。</p>
              )
            ) : (
              <ul className="card-list">
                {list.map(c => (
                  <li key={c.id}>
                    <button
                      className={'kcard' + (c.due <= now ? ' due' : '')}
                      style={{ '--vtn': 'card-' + c.id } as CSSProperties}
                      onClick={() => openCard(c.id)}
                    >
                      <span className="row between">
                        <b>{c.concept}</b>
                        <small className={c.due <= now ? 'due-tag' : 'muted'}>
                          {c.due <= now ? '待复习' : '下次 ' + dueLabel(c.due, now)}
                        </small>
                      </span>
                      <span className="kline">{c.oneLine}</span>
                      <Meter c={c} />
                      <small className="muted">
                        {MODES[c.mode].name} · {new Date(c.created).toLocaleDateString('zh-CN')} ·
                        复习 {c.reviews.length} 次
                      </small>
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
  if (!c)
    return (
      <div className="screen">
        <TopBar title="知识卡片" back="library" />
        <Empty title="找不到这张卡片" text="它可能已经被清除。" />
      </div>
    );
  const answered = Object.keys(ui.quiz['c:' + c.id] ?? {}).length;
  const open = showReview || !c.quiz.length || answered >= c.quiz.length;
  return (
    <div className="screen card-view" style={{ '--vtn': 'card-' + c.id } as CSSProperties}>
      <TopBar
        title={c.concept}
        sub={`${MODES[c.mode].name} · 已复习 ${c.reviews.length} 次`}
        back="library"
      />
      <div className="stack pad">
        <p className="lede topic">{c.topic}</p>
        {c.quiz.length > 0 && (
          <section className="panel">
            <h3 className="section-label">先凭记忆作答</h3>
            <Quiz id={'c:' + c.id} items={c.quiz} />
          </section>
        )}
        {open ? (
          <>
            <section className="review-hero slim">
              <p className="display-sm">{c.oneLine}</p>
            </section>
            <ReviewBody r={c} terms={c.terms} />
          </>
        ) : (
          <button className="ghost block" onClick={() => setShowReview(true)}>
            先看上次的复盘
          </button>
        )}
        <button className="ghost block" disabled={!!ui.busy} onClick={() => discussAgain(c)}>
          从薄弱点再讨论一次
        </button>
      </div>
      <div className="dock">
        <ErrorBanner />
        <BusyStrip />
        <p className="dock-q">这次回顾得怎么样？</p>
        <div className="row">
          <button className="secondary grow" onClick={() => rateCard(c.id, false)}>
            还有点模糊
          </button>
          <button className="primary grow" onClick={() => rateCard(c.id, true)}>
            记得很清楚
          </button>
        </div>
      </div>
    </div>
  );
}
