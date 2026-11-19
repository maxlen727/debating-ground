import type { CSSProperties } from 'react';
import { useState } from 'react';
import { MODES } from '../../shared/modes.ts';
import { discussAgain, openCard, selfCheck } from '../actions.ts';
import { BusyStrip, Empty, ErrorBanner, Icon, TopBar } from '../components/ui.tsx';
import { exportLibrary, exportNote } from '../lib/exporter.ts';
import { sameConcept, tally, type Note } from '../lib/notes.ts';
import { SEARCH_MIN_CARDS, searchCards } from '../lib/search.ts';
import { go, update, useApp } from '../store.ts';
import { Bullets, PointList } from './Review.tsx';

// 知识库：一个概念一篇笔记，每次讨论完把理解沉淀进去（#140）。只保存在这台设备上。

const date = (t: number) => new Date(t).toLocaleDateString('zh-CN');

// 每个要点一格：掌握是绿的，模糊是黄的，没讲到是灰的
function Meter({ n }: { n: Note }) {
  const c = tally(n);
  return (
    <span
      className="meter"
      aria-label={`掌握 ${c.mastered}，模糊 ${c.partial}，没讲到 ${c.missing}`}
    >
      {n.points.map((p, i) => (
        <i key={i} className={p.status} />
      ))}
    </span>
  );
}

export function Library() {
  const { library } = useApp();
  // 搜索词只在这一页里，不存档
  const [query, setQuery] = useState('');
  const searching = query.trim() !== '';
  const list = searching ? searchCards(library, query) : library;
  return (
    <div className="screen library">
      <TopBar
        title="知识库"
        sub={`${library.length} 个概念 · 只保存在这台设备`}
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
            text="每讨论完一个概念，复盘会整理成一篇它的笔记：用你自己的话说清它是什么、什么时候用、你举过的例子和容易错的地方，还有几道自检题。以后再讨论同一个概念，新的理解会接着沉淀进这篇笔记。"
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
                  placeholder="搜概念、要点、例子、术语"
                  aria-label="搜索概念笔记"
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
            {searching && list.length > 0 && (
              <p className="muted search-count" role="status">
                找到 {list.length} 个概念
              </p>
            )}
            {list.length === 0 ? (
              <div className="center stack-sm">
                <p className="muted">没有找到和“{query.trim()}”有关的笔记。</p>
                <button className="ghost" onClick={() => setQuery('')}>
                  清除搜索
                </button>
              </div>
            ) : (
              <ul className="card-list">
                {list.map(n => {
                  const c = tally(n);
                  const weak = c.partial + c.missing;
                  return (
                    <li key={n.id}>
                      <button
                        className="kcard"
                        style={{ '--vtn': 'card-' + n.id } as CSSProperties}
                        onClick={() => openCard(n.id)}
                      >
                        <span className="row between">
                          <b>{n.concept}</b>
                          {weak > 0 && <small className="weak-tag">{weak} 处还模糊</small>}
                        </span>
                        <span className={'kline' + (n.summary ? '' : ' muted')}>
                          {n.summary || '还没有用自己的话说清它，再讨论一次就能补上。'}
                        </span>
                        <Meter n={n} />
                        <small className="muted">
                          {n.sources.length} 次讨论 · 更新于 {date(n.updated)}
                          {n.checks.length ? ` · ${n.checks.length} 道自检` : ''}
                        </small>
                      </button>
                    </li>
                  );
                })}
              </ul>
            )}
          </>
        )}
      </div>
    </div>
  );
}

// 自检：先自己想（最好说出来或写下来），再看参考要点，标答出来没有。结果会改对应要点的状态
function SelfChecks({ n }: { n: Note }) {
  const [open, setOpen] = useState<number[]>([]);
  if (!n.checks.length) return null;
  return (
    <section className="panel">
      <h3 className="section-label">自检</h3>
      <ol className="checks">
        {n.checks.map((c, i) => {
          const point = c.point ? n.points.find(p => sameConcept(p.label, c.point!)) : undefined;
          return (
            <li key={i}>
              <p className="knowledge">{c.q}</p>
              {open.includes(i) ? (
                <div className="check-answer">
                  <p>
                    <b>参考要点</b>
                    {c.answer}
                  </p>
                  <div className="row">
                    <button className="secondary grow" onClick={() => selfCheck(n.id, i, false)}>
                      没答出
                    </button>
                    <button className="primary grow" onClick={() => selfCheck(n.id, i, true)}>
                      答出来了
                    </button>
                  </div>
                  {point && (
                    <p className="fineprint">
                      检验的是“{point.label}”
                      {point.checked ? `，上次自检在 ${date(point.checked)}` : ''}。
                    </p>
                  )}
                </div>
              ) : (
                <button className="ghost" onClick={() => setOpen([...open, i])}>
                  想好了，看参考要点
                </button>
              )}
            </li>
          );
        })}
      </ol>
      <p className="fineprint">
        先不看答案，用自己的话说一遍，再对照参考要点。只看得懂不算，说得出来才算。
      </p>
    </section>
  );
}

// 一篇概念笔记：从上到下是“它是什么、什么时候用、要点（带依据）、例子、易错、边界、立场、相关、术语、自检、来源”
export function CardView() {
  const { library, ui } = useApp();
  const n = library.find(x => x.id === ui.openCard);
  if (!n)
    return (
      <div className="screen">
        <TopBar title="概念笔记" back="library" />
        <Empty title="找不到这篇笔记" text="它可能已经被清除。" />
      </div>
    );
  const c = tally(n);
  const open = (concept: string) => {
    const other = library.find(x => sameConcept(x.concept, concept));
    if (other) return openCard(other.id);
    // 知识库里还没有：去学习页讨论它
    update(st => {
      st.config.concept = concept;
      st.ui.context = null;
    });
    go('learn');
  };
  return (
    <div className="screen card-view" style={{ '--vtn': 'card-' + n.id } as CSSProperties}>
      <TopBar
        title={n.concept}
        sub={`${n.sources.length} 次讨论 · 掌握 ${c.mastered}/${n.points.length}`}
        back="library"
        right={
          <button className="icon-btn" aria-label="导出这篇笔记" onClick={() => exportNote(n)}>
            <Icon name="download" />
          </button>
        }
      />
      <div className="stack pad">
        <section className="note-gist">
          <p className="display-sm">
            {n.summary || (
              <span className="muted">还没有用自己的话说清它。再讨论一次，复盘会补上。</span>
            )}
          </p>
          {n.useWhen && (
            <p className="use-when">
              <b>什么时候想到它</b>
              {n.useWhen}
            </p>
          )}
        </section>
        {n.points.length > 0 && (
          <section className="panel">
            <h3 className="section-label">要点</h3>
            <PointList points={n.points} />
          </section>
        )}
        <Bullets title="你举过的例子" items={n.examples} />
        {n.misconceptions.length > 0 && (
          <section className="panel">
            <h3 className="section-label">易错</h3>
            {n.misconceptions.map((m, i) => (
              <div key={i} className="fix">
                <p className="said">{m.said}</p>
                <p className="right">
                  <Icon name="chevron" size={14} />
                  {m.fix}
                </p>
              </div>
            ))}
          </section>
        )}
        <Bullets title="边界" items={n.boundaries} note="它不是什么、和哪些相近的概念容易混。" />
        {n.stances.length > 0 && (
          <section className="panel">
            <h3 className="section-label">我的立场</h3>
            {n.stances.map((m, i) => (
              <div key={i} className="stance">
                <p className="said">{m.said}</p>
                {m.note && <p className="note">{m.note}</p>}
              </div>
            ))}
          </section>
        )}
        {n.related.length > 0 && (
          <section className="panel">
            <h3 className="section-label">相关概念</h3>
            <div className="related">
              {n.related.map(r => {
                const has = library.some(x => sameConcept(x.concept, r));
                return (
                  <button key={r} className={'chip' + (has ? ' on' : '')} onClick={() => open(r)}>
                    {r}
                    {!has && <small> · 去讨论</small>}
                  </button>
                );
              })}
            </div>
          </section>
        )}
        {n.terms.length > 0 && (
          <section className="panel">
            <h3 className="section-label">术语</h3>
            <dl className="glossary">
              {n.terms.map(t => (
                <div key={t.term}>
                  <dt>{t.term}</dt>
                  <dd>
                    {t.definition}
                    {t.relation && <small>{t.relation}</small>}
                  </dd>
                </div>
              ))}
            </dl>
          </section>
        )}
        <SelfChecks n={n} />
        <section className="panel">
          <h3 className="section-label">来源</h3>
          <ul className="sources">
            {n.sources
              .slice()
              .reverse()
              .map(x => (
                <li key={x.sessionId}>
                  <small className="muted">
                    {date(x.at)} · {MODES[x.mode]?.name ?? x.mode}
                    {x.source === 'demo' ? ' · 演示' : ''}
                  </small>
                  <span>{x.topic}</span>
                  {x.oneLine && <small>{x.oneLine}</small>}
                </li>
              ))}
          </ul>
        </section>
      </div>
      <div className="dock">
        <ErrorBanner />
        <BusyStrip />
        <button className="primary block" disabled={!!ui.busy} onClick={() => discussAgain(n)}>
          {c.partial + c.missing > 0 ? '从模糊的地方再讨论一次' : '再讨论一次，往深里走'}
        </button>
      </div>
    </div>
  );
}
