import type { CSSProperties } from 'react';
import { MODES } from '../../shared/modes.ts';
import type { Review as R, TermCard } from '../../shared/types.ts';
import { discussAgain, generateReview, openCard, resume } from '../actions.ts';
import { BusyStrip, Empty, ErrorBanner, Icon, Pill, TopBar } from '../components/ui.tsx';
import { exportSession } from '../lib/exporter.ts';
import { go, useApp } from '../store.ts';
import { useEffect } from 'react';
import { flash } from '../lib/light.ts';

export function Ring({ done, total }: { done: number; total: number }) {
  const r = 30,
    c = 2 * Math.PI * r,
    frac = total ? done / total : 0;
  return (
    <svg className="ring" viewBox="0 0 76 76" role="img" aria-label={`已掌握 ${done} / ${total}`}>
      <circle cx="38" cy="38" r={r} className="ring-track" />
      <circle
        cx="38"
        cy="38"
        r={r}
        className="ring-fill"
        strokeDasharray={`${c * frac} ${c}`}
        transform="rotate(-90 38 38)"
      />
      <text x="38" y="36" textAnchor="middle" className="ring-num">
        {done}
      </text>
      <text x="38" y="52" textAnchor="middle" className="ring-sub">
        / {total}
      </text>
    </svg>
  );
}

// 要点：状态、判断理由，以及学习者自己说过的原话（“掌握”要能追到依据）
export function PointList({ points }: { points: R['points'] }) {
  return (
    <ul className="point-list">
      {points.map((p, i) => (
        <li key={i} style={{ '--vtn': 'pt-' + i } as CSSProperties}>
          <Pill status={p.status} />
          <div>
            <b>{p.label}</b>
            <span>{p.note}</span>
            {p.evidence && <q className="evidence">{p.evidence}</q>}
          </div>
        </li>
      ))}
    </ul>
  );
}

// 一组短条目（例子、边界）
export function Bullets({
  title,
  items,
  note,
}: {
  title: string;
  items?: string[];
  note?: string;
}) {
  if (!items?.length) return null;
  return (
    <section className="panel">
      <h3 className="section-label">{title}</h3>
      <ul className="bullets">
        {items.map((x, i) => (
          <li key={i}>{x}</li>
        ))}
      </ul>
      {note && <p className="fineprint">{note}</p>}
    </section>
  );
}

export function ReviewBody({ r, terms }: { r: R; terms?: TermCard[] }) {
  return (
    <>
      <section className="panel">
        <h3 className="section-label">要点掌握情况</h3>
        <PointList points={r.points} />
      </section>
      <Bullets title="你举过的例子" items={r.examples} />
      {r.misconceptions.length > 0 && (
        <section className="panel">
          <h3 className="section-label">需要纠正的理解</h3>
          {r.misconceptions.map((m, i) => (
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
      <Bullets title="边界" items={r.boundaries} note="它不是什么、和哪些相近的概念容易混。" />
      {/* 立场不是误解：不划掉、不纠正，只点评理由 */}
      {!!r.stances?.length && (
        <section className="panel">
          <h3 className="section-label">你的立场</h3>
          {r.stances.map((m, i) => (
            <div key={i} className="stance">
              <p className="said">{m.said}</p>
              {m.note && <p className="note">{m.note}</p>}
            </div>
          ))}
          <p className="fineprint">立场没有对错，这里只看理由是否说清楚、有没有考虑反方。</p>
        </section>
      )}
      {!!terms?.length && (
        <section className="panel">
          <h3 className="section-label">这次遇到的新词</h3>
          <dl className="glossary">
            {terms.map(t => (
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
    </>
  );
}

export function Review() {
  const { session: s, ui, library } = useApp();
  // 复盘出来时，掌握度圆环亮一下（等页面过渡结束）
  const ready = !!s?.review;
  useEffect(() => {
    if (!ready) return;
    const t = setTimeout(
      () =>
        flash(document.querySelector('.review .ring'), {
          radius: 240,
          strength: 0.9,
          duration: 1800,
        }),
      520,
    );
    return () => clearTimeout(t);
  }, [ready, s?.id]);
  if (!s)
    return (
      <div className="screen">
        <TopBar title="理解复盘" back="learn" />
        <Empty title="还没有复盘" text="完成一次讨论后，这里会显示你弄懂了多少。" />
      </div>
    );
  const r = s.review;
  const hasInput = s.entries.some(
    m => (m.kind === 'msg' && m.human) || (m.kind === 'reflect' && m.text),
  );
  // 这次复盘沉淀进了哪篇概念笔记
  const note = library.find(n => n.sources.some(x => x.sessionId === s.id));
  const mastered = r ? r.points.filter(p => p.status === 'mastered').length : 0;

  return (
    <div className="screen review">
      <TopBar
        title="理解复盘"
        sub={`${s.concept} · ${MODES[s.mode].name}`}
        back="session"
        right={
          <button
            className="icon-btn"
            aria-label="导出讨论记录和复盘"
            onClick={() => exportSession(s)}
          >
            <Icon name="download" />
          </button>
        }
      />
      <div className="stack pad">
        {!r ? (
          <section className="panel center">
            {!hasInput ? (
              <>
                <p>这次讨论里还没有你的发言，没有可以复盘的内容。</p>
                <button className="primary" onClick={resume}>
                  回到讨论
                </button>
              </>
            ) : ui.busy ? (
              <p className="muted">
                <span className="spinner" aria-hidden="true" /> 正在复盘整场讨论……
              </p>
            ) : (
              <>
                <p>复盘还没有生成。</p>
                <button className="primary" onClick={generateReview}>
                  生成理解复盘
                </button>
              </>
            )}
            <ErrorBanner />
          </section>
        ) : (
          <>
            <section className="review-hero">
              <Ring done={mastered} total={r.points.length} />
              <div>
                <p className="display-sm">{r.oneLine}</p>
                <small className="muted">
                  {r.source === 'api'
                    ? `由 ${r.model} 根据你的发言和小结生成，供参考`
                    : '演示模式：预设示例或规则统计'}{' '}
                </small>
              </div>
            </section>
            {(r.summary || r.useWhen) && (
              <section className="panel note-gist">
                {r.summary && (
                  <>
                    <h3 className="section-label">用你的话说</h3>
                    <p className="knowledge">{r.summary}</p>
                  </>
                )}
                {r.useWhen && (
                  <p className="use-when">
                    <b>什么时候想到它</b>
                    {r.useWhen}
                  </p>
                )}
              </section>
            )}
            <ReviewBody r={r} terms={s.glossary} />
            {note && (
              <button className="settled" onClick={() => openCard(note.id)}>
                <span className="settled-mark" aria-hidden="true">
                  <Icon name="library" size={20} />
                </span>
                <span className="grow">
                  <b>已整理进「{note.concept}」的笔记</b>
                  <small>
                    {note.sources.length > 1
                      ? `这篇笔记来自 ${note.sources.length} 次讨论，这次的理解合并进去了`
                      : '新的一篇：以后再讨论这个概念，理解会接着沉淀进来'}
                    {note.checks.length ? `，有 ${note.checks.length} 道自检题` : ''}
                  </small>
                </span>
                <Icon name="chevron" size={18} />
              </button>
            )}
          </>
        )}
      </div>
      {r && note && (
        <div className="dock">
          <ErrorBanner />
          <BusyStrip />
          <div className="row">
            <button className="secondary" onClick={() => go('session')}>
              回看讨论
            </button>
            <button
              className="primary grow"
              disabled={!!ui.busy}
              onClick={() => discussAgain(note)}
            >
              从薄弱点再讨论
            </button>
          </div>
        </div>
      )}
    </div>
  );
}
