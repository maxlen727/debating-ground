import { useEffect, useRef } from 'react';
import { MODES } from '../../shared/modes.ts';
import type { ChatMessage, Entry, Reflection } from '../../shared/types.ts';
import { QUICK, addReflection, currentTurn, dropReflection, finish, generateSeat, hint, nextSpeaker, quick, send, setReflectionDraft, skipSeat, submitReflection } from '../actions.ts';
import { Empty, ErrorBanner, Icon, Sheet } from '../components/ui.tsx';
import { cancelAI } from '../lib/ai.ts';
import { seatName } from '../lib/debate.ts';
import { exportSession } from '../lib/exporter.ts';
import { go, update, useApp, type Session as S } from '../store.ts';

export function Session() {
  const { session: s, ui } = useApp();
  const count = s ? s.entries.length : 0;
  useEffect(() => { window.scrollTo({ top: document.body.scrollHeight, behavior: 'smooth' }); }, [count, ui.busy, ui.hint]);

  if (!s) return (
    <div className="screen"><header className="topbar"><span className="topbar-pad" /><div className="topbar-title"><h1>讨论</h1></div><span className="topbar-right" /></header>
      <Empty title="还没有进行中的讨论" text="写下一个概念，选一种讨论方式，就可以开始。" action={<button className="primary" onClick={() => go('learn')}>开始学习</button>} /></div>
  );

  const debate = s.mode === 'debate';
  const turn = currentTurn(s);
  const sheetMsg = s.entries.find((m): m is ChatMessage => m.id === ui.sheet && m.kind === 'msg');
  const lastAi = [...s.entries].reverse().find(m => m.kind === 'msg' && !m.human)?.id;

  return (
    <div className="screen session">
      <div className="session-top">
        <header className="topbar">
          <button className="icon-btn" aria-label="返回学习" onClick={() => go('learn')}><Icon name="back" /></button>
          <div className="topbar-title"><h1>{s.concept}</h1><p>{MODES[s.mode].name} · {s.topic}</p></div>
          <div className="topbar-right">
            {s.finished ? <button className="secondary small" onClick={() => go('review')}>看复盘</button>
              : <button className="secondary small" disabled={!!ui.busy} onClick={finish}>结束复盘</button>}
          </div>
        </header>
        <div className="progress" aria-label={`要点进度 ${s.covered.length}/${s.keyPoints.length}`}>
          <span className="progress-count">{s.covered.length}<small>/{s.keyPoints.length}</small></span>
          <div className="scroll-x progress-chips" role="list">
            {s.keyPoints.map((k, i) => <span role="listitem" key={i} className={'pt' + (s.covered.includes(i) ? ' lit' : '')}>{k}</span>)}
          </div>
        </div>
        {debate && (
          <div className="stage-strip scroll-x" aria-label="比赛环节">
            {s.stages.map((st, i) => <span key={i} className={'stage-pill' + (turn?.stage === i ? ' on' : (turn && i < turn.stage) || s.finished ? ' done' : '')}>{st.name}</span>)}
          </div>
        )}
      </div>

      <main className="thread pad">
        {s.entries.map(m => <EntryView key={m.id} m={m} s={s} showTip={m.id === lastAi && s.learnerTurns === 0 && !s.finished} />)}
        {ui.busy && <div className="msg"><span className="avatar tone-ghost" aria-hidden="true">…</span><div className="msg-body"><div className="bubble typing"><span className="dots" aria-hidden="true"><i /><i /><i /></span>{ui.busy}<button className="link" onClick={cancelAI}>停止</button></div></div></div>}
      </main>

      {ui.explain && (
        <Sheet title={`${ui.explain.label} · 讲解助手`} onClose={() => update(st => { st.ui.explain = null; })}>
          <blockquote className="quote">{ui.explain.quote}</blockquote>
          <p className="sheet-text">{ui.explain.text || '正在看这句话…'}</p>
          <p className="fineprint">讲解不进入比赛记录，也不占用发言轮次。</p>
        </Sheet>
      )}
      {sheetMsg && (
        <Sheet title="追问这句" onClose={() => update(st => { st.ui.sheet = null; })}>
          <blockquote className="quote">{sheetMsg.text.length > 80 ? sheetMsg.text.slice(0, 80) + '…' : sheetMsg.text}</blockquote>
          <div className="action-list">
            {QUICK.map(q => <button key={q.kind} className="action" onClick={() => quick(q.kind, sheetMsg.id)}><span>{q.label}</span><Icon name="chevron" size={18} /></button>)}
          </div>
          {debate && <p className="fineprint">辩论中，解释由讲解助手单独给出，不打断赛程。</p>}
        </Sheet>
      )}

      <footer className="composer">
        <ErrorBanner />
        {s.finished ? (
          <div className="row between composer-done"><span className="muted">这次讨论已结束。</span><div className="row"><button className="ghost small" onClick={() => exportSession(s)}><Icon name="download" size={16} />导出</button><button className="primary small" onClick={() => go('review')}>看复盘</button></div></div>
        ) : <Composer s={s} />}
      </footer>
    </div>
  );
}

function Composer({ s }: { s: S }) {
  const { ui } = useApp();
  const busy = !!ui.busy;
  const turn = currentTurn(s);
  const debate = s.mode === 'debate';
  const humanTurn = !debate || turn?.role.type === 'human';
  const ref = useRef<HTMLTextAreaElement>(null);
  useEffect(() => { const t = ref.current; if (t) { t.style.height = 'auto'; t.style.height = Math.min(t.scrollHeight, 160) + 'px'; } }, [s.draft]);
  const submit = () => send(s.draft);

  return (
    <>
      {debate && turn && (
        <div className="turn-banner"><span className={'dot team-' + turn.role.team} aria-hidden="true" />轮到 <b>{seatName(turn.role)}</b>{turn.role.type === 'human' ? '（你）' : ''} · {turn.action}{turn.target ? ` → ${turn.target}` : ''}<span className="muted grow-right">{s.cursor + 1}/{s.turns.length}</span></div>
      )}
      {ui.hint && <div className="hint-card"><Icon name="bulb" size={18} /><p>{ui.hint}</p><button className="icon-btn small" aria-label="关闭提示" onClick={() => update(st => { st.ui.hint = null; })}><Icon name="close" size={16} /></button></div>}
      <div className="tools scroll-x">
        {humanTurn && <button className="tool" disabled={busy} onClick={hint}><Icon name="bulb" size={16} />给点提示</button>}
        <button className="tool" disabled={busy} onClick={() => addReflection()}><Icon name="note" size={16} />写个小结</button>
        {!debate && s.personas.length > 1 && <button className="tool" disabled={busy} onClick={nextSpeaker}><Icon name="people" size={16} />请下一位</button>}
        {debate && <button className="tool" disabled={busy} onClick={skipSeat}>跳过本席</button>}
      </div>
      {humanTurn ? (
        <div className="input-row">
          <textarea id="draft" ref={ref} rows={1} value={s.draft} maxLength={4000} aria-label="你的发言"
            placeholder={debate ? `${turn?.action ?? '发言'}：我方认为……因为……` : s.mode === 'teach' ? '开始讲解……' : '说说你的想法……'}
            onChange={e => update(st => { st.session!.draft = e.target.value; })}
            onKeyDown={e => { if (e.key === 'Enter' && !e.shiftKey && !e.nativeEvent.isComposing && window.matchMedia('(pointer: fine)').matches) { e.preventDefault(); submit(); } }} />
          <button className="send" aria-label="发送" disabled={busy || !s.draft.trim()} onClick={submit}><Icon name="send" /></button>
        </div>
      ) : (
        <button className="primary block" disabled={busy} onClick={generateSeat}>让 {turn && seatName(turn.role)} 发言</button>
      )}
    </>
  );
}

function EntryView({ m, s, showTip }: { m: Entry; s: S; showTip: boolean }) {
  if (m.kind === 'sys') return <div className="sysline">{m.text}</div>;
  if (m.kind === 'reflect') return <ReflectCard m={m} concept={s.concept} />;
  const me = !!m.human;
  const persona = s.personas.find(r => r.id === m.who);
  const tone = me ? 'me' : s.mode === 'debate' ? (m.team === 'A' ? 'a' : 'b') : persona?.tone ?? 'a';
  const label = me ? (s.mode === 'debate' ? m.name.replace('（学习者）', '') + ' · 你' : '你')
    : s.mode === 'debate' ? m.name : `${m.name}${persona?.title ? ' · ' + persona.title : ''}`;
  const meta = [s.mode === 'debate' ? m.action : '', m.source === 'demo' && !me ? '演示' : m.source === 'api' && !me ? m.model : ''].filter(Boolean).join(' · ');
  const avatarText = me ? '我' : s.mode === 'debate' ? m.who : m.name.slice(0, 1);
  return (
    <div className={'msg' + (me ? ' me' : '')}>
      <span className={'avatar tone-' + tone} aria-hidden="true">{avatarText}</span>
      <div className="msg-body">
        <div className="msg-meta"><b>{label}</b>{meta && <span>{meta}</span>}{m.skipped && <span>已跳过</span>}</div>
        {me || m.skipped || s.finished
          ? <div className={'bubble' + (m.skipped ? ' skipped' : '')}>{m.text}</div>
          : <button className="bubble tappable" onClick={() => update(st => { st.ui.sheet = m.id; })} aria-haspopup="dialog">{m.text}</button>}
        {showTip && <small className="tip">轻点发言可以追问：没听懂、举例、反驳、分辨事实。</small>}
      </div>
    </div>
  );
}

function ReflectCard({ m, concept }: { m: Reflection; concept: string }) {
  const { ui } = useApp();
  return (
    <section className="reflect" aria-label="小结卡">
      <h3>停一下，用自己的话说说</h3>
      {m.text ? (
        <>
          <p className="mine">{m.text}</p>
          <p className="feedback">{m.feedback}</p>
        </>
      ) : (
        <>
          <p className="muted">你现在怎么理解「{concept}」？一两句话就好。</p>
          <textarea value={m.draft} rows={2} maxLength={1000} aria-label="我的小结" onChange={e => setReflectionDraft(m.id, e.target.value)} />
          <div className="row end">
            <button className="ghost small" onClick={() => dropReflection(m.id)}>跳过</button>
            <button className="primary small" disabled={!!ui.busy || !m.draft.trim()} onClick={() => submitReflection(m.id)}>提交小结</button>
          </div>
        </>
      )}
    </section>
  );
}
