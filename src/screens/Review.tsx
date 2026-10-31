import { MODES } from '../../shared/modes.ts';
import type { Review as R, TermCard } from '../../shared/types.ts';
import { discussAgain, generateReview, resume } from '../actions.ts';
import { BusyStrip, Empty, ErrorBanner, Icon, Pill, Quiz, TopBar } from '../components/ui.tsx';
import { exportSession } from '../lib/exporter.ts';
import { go, useApp } from '../store.ts';

export function Ring({ done, total }: { done: number; total: number }) {
  const r = 30, c = 2 * Math.PI * r, frac = total ? done / total : 0;
  return (
    <svg className="ring" viewBox="0 0 76 76" role="img" aria-label={`已掌握 ${done} / ${total}`}>
      <circle cx="38" cy="38" r={r} className="ring-track" />
      <circle cx="38" cy="38" r={r} className="ring-fill" strokeDasharray={`${c * frac} ${c}`} transform="rotate(-90 38 38)" />
      <text x="38" y="36" textAnchor="middle" className="ring-num">{done}</text>
      <text x="38" y="52" textAnchor="middle" className="ring-sub">/ {total}</text>
    </svg>
  );
}

export function ReviewBody({ r, terms }: { r: R; terms?: TermCard[] }) {
  return (
    <>
      <section className="panel">
        <h3 className="section-label">要点掌握情况</h3>
        <ul className="point-list">
          {r.points.map((p, i) => <li key={i}><Pill status={p.status} /><div><b>{p.label}</b><span>{p.note}</span></div></li>)}
        </ul>
      </section>
      {r.misconceptions.length > 0 && (
        <section className="panel">
          <h3 className="section-label">需要纠正的理解</h3>
          {r.misconceptions.map((m, i) => <div key={i} className="fix"><p className="said">{m.said}</p><p className="right"><Icon name="chevron" size={14} />{m.fix}</p></div>)}
        </section>
      )}
      {!!terms?.length && (
        <section className="panel">
          <h3 className="section-label">这次遇到的新词</h3>
          <dl className="glossary">
            {terms.map(t => <div key={t.term}><dt>{t.term}</dt><dd>{t.definition}{t.relation && <small>{t.relation}</small>}</dd></div>)}
          </dl>
        </section>
      )}
    </>
  );
}

export function Review() {
  const { session: s, ui, library } = useApp();
  if (!s) return <div className="screen"><TopBar title="理解复盘" back="learn" /><Empty title="还没有复盘" text="完成一次讨论后，这里会显示你弄懂了多少。" /></div>;
  const r = s.review;
  const hasInput = s.entries.some(m => (m.kind === 'msg' && m.human) || (m.kind === 'reflect' && m.text));
  const card = library.find(c => c.sessionId === s.id);
  const mastered = r ? r.points.filter(p => p.status === 'mastered').length : 0;

  return (
    <div className="screen review">
      <TopBar title="理解复盘" sub={`${s.concept} · ${MODES[s.mode].name}`} back="session" right={<button className="icon-btn" aria-label="导出讨论记录和复盘" onClick={() => exportSession(s)}><Icon name="download" /></button>} />
      <div className="stack pad">
        {!r ? (
          <section className="panel center">
            {!hasInput ? <><p>这次讨论里还没有你的发言，没有可以复盘的内容。</p><button className="primary" onClick={resume}>回到讨论</button></>
              : ui.busy ? <p className="muted"><span className="spinner" aria-hidden="true" /> 正在复盘整场讨论……</p>
                : <><p>复盘还没有生成。</p><button className="primary" onClick={generateReview}>生成理解复盘</button></>}
            <ErrorBanner />
          </section>
        ) : (
          <>
            <section className="review-hero">
              <Ring done={mastered} total={r.points.length} />
              <div>
                <p className="display-sm">{r.oneLine}</p>
                <small className="muted">{r.source === 'api' ? `由 ${r.model} 根据你的发言和小结生成，供参考` : '演示模式：预设示例或规则统计'} · 已存入知识库</small>
              </div>
            </section>
            <ReviewBody r={r} terms={s.glossary} />
            {r.quiz.length > 0 && <section className="panel"><h3 className="section-label">检验一下</h3><Quiz id={'s:' + s.id} items={r.quiz} /></section>}
            <p className="fineprint">第一次复习在明天。到时候知识库会提醒你，先凭记忆做题，再看这份复盘。</p>
          </>
        )}
      </div>
      {r && card && (
        <div className="dock">
          <ErrorBanner /><BusyStrip />
          <div className="row">
            <button className="secondary" onClick={() => go('session')}>回看讨论</button>
            <button className="primary grow" disabled={!!ui.busy} onClick={() => discussAgain(card)}>从薄弱点再讨论</button>
          </div>
        </div>
      )}
    </div>
  );
}
