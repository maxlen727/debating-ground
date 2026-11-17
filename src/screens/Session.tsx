import type { CSSProperties } from 'react';
import { Fragment, useEffect, useRef, useState } from 'react';
import { flash } from '../lib/light.ts';
import { MODES } from '../../shared/modes.ts';
import type { ChatMessage } from '../../shared/types.ts';
import { QUICK, addReflection, currentTurn, decidePoint, finish, generateSeat, hint, nextSpeaker, openBranch, openTerm, toggleTeaching, proposePoint, quick, skipSeat, suggestPoint, voiceAvailable } from '../actions.ts';
import { Empty, ErrorBanner, Icon, Sheet } from '../components/ui.tsx';
import { Reveal } from '../components/Reveal.tsx';
import { BackSheet, BranchChips, CanvasPath, ForkForm, ForkHeader, GraphSheet } from '../components/Canvas.tsx';
import { levelEntries } from '../lib/canvas.ts';
import { DebateBoard, NextLectern, RosterSheet, StageDivider, seatWho } from '../components/DebateArena.tsx';
import { useLive } from '../lib/stream.ts';
import { cancelAI } from '../lib/ai.ts';
import { seatName } from '../lib/debate.ts';
import { exportSession } from '../lib/exporter.ts';
import { go, update, useApp, type Session as S } from '../store.ts';
import { RoomBar, InviteSheet, SeatSheet, CallOnSheet, RoomComposer } from '../components/RoomPanel.tsx';
import { HintCard, DraftInput } from '../components/DraftInput.tsx';
import { EntryView } from '../components/Entries.tsx';

// 光：要点刚被点亮时从那个小标签发光，新发言落下时从它身上发一点光，照亮周围的按钮和气泡
function useLightCues(s: S | null) {
  const seen = useRef<{ id: string; covered: number[]; last?: string; at?: string } | null>(null);
  useEffect(() => {
    if (!s) return;
    const prev = seen.current;
    const last = s.entries.at(-1)?.id;
    seen.current = { id: s.id, covered: [...s.covered], last, at: s.at };
    if (!prev || prev.id !== s.id || prev.at !== s.at) return;   // 刚打开、换了分支：不闪
    const lit = s.covered.find(i => !prev.covered.includes(i));
    if (lit !== undefined) {
      const chip = document.querySelectorAll('.progress .pt')[lit];
      chip?.scrollIntoView({ behavior: 'smooth', block: 'nearest', inline: 'center' });
      const t = setTimeout(() => flash(chip, { radius: 230 }), 260);
      return () => clearTimeout(t);
    }
    if (last && last !== prev.last) {
      // 等列表滚到底再发光，光才落在它现在的位置
      const t = setTimeout(() => flash(document.querySelector(`[data-entry="${last}"] .bubble`) ?? document.querySelector(`[data-entry="${last}"]`), { radius: 170, strength: 0.55, duration: 1300 }), 380);
      return () => clearTimeout(t);
    }
    // 只在点亮要点、落下新发言、换分支时发光：s 每次状态更新都是新对象，放进依赖会让它每打一个字就重算一次
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [s?.id, s?.covered.length, s?.entries.length, s?.at]);
}

export function Session() {
  const { session: s, ui } = useApp();
  const live = useLive();
  useLightCues(s);
  const count = s ? s.entries.length : 0;
  const aiBusy = s?.room?.aiBusy;
  useEffect(() => { window.scrollTo({ top: document.body.scrollHeight, behavior: 'smooth' }); }, [count, ui.busy, ui.hint, aiBusy, live?.id, s?.at]);
  // 生成中跟着文字往下滚；用户自己往上翻时不打扰
  const growing = live?.kind === 'msg' ? live.text.length : 0;
  useEffect(() => {
    if (!growing) return;
    const nearBottom = window.innerHeight + window.scrollY > document.body.scrollHeight - 160;
    if (nearBottom) window.scrollTo({ top: document.body.scrollHeight });
  }, [growing]);

  if (!s) return (
    <div className="screen"><header className="topbar"><span className="topbar-pad" /><div className="topbar-title"><h1>讨论</h1></div><span className="topbar-right" /></header>
      <Empty title="还没有进行中的讨论" text="写下一个概念，选一种讨论方式，就可以开始。" action={<button className="primary" onClick={() => go('learn')}>开始学习</button>} /></div>
  );

  const debate = s.mode === 'debate';
  const canvas = s.mode === 'canvas';
  // 知识画布只显示正在看的这一层（主干或某个分支）
  const shown = canvas ? levelEntries(s, s.at) : s.entries;
  const room = s.room;
  const sheetMsg = s.entries.find((m): m is ChatMessage => m.id === ui.sheet && m.kind === 'msg');
  const lastAi = [...s.entries].reverse().find(m => m.kind === 'msg' && !m.human && !m.memberId)?.id;
  const canFinish = !room || room.host;
  const voice = voiceAvailable();
  const pending = s.proposedPoints?.length ?? 0;
  // 辩论赛程没走完就结束，会跳过剩下的环节（包括总结陈词），先确认一下
  const onFinish = () => debate && s.cursor < s.turns.length ? update(st => { st.ui.sheet = 'finish'; }) : finish();

  return (
    <div className="screen session">
      <div className="session-top">
        <header className="topbar">
          <button className="icon-btn" aria-label="返回学习" onClick={() => go('learn')}><Icon name="back" /></button>
          <div className="topbar-title"><h1>{s.concept}</h1><p>{MODES[s.mode].name} · {s.topic}</p></div>
          <div className="topbar-right">
            {s.finished ? <button className="secondary small" onClick={() => go('review')}>看复盘</button>
              : !s.awaitingReview && canFinish && room?.status !== 'lobby' && <button className="secondary small" disabled={!!ui.busy || !!aiBusy} onClick={onFinish}>{room ? '结束讨论' : '结束复盘'}</button>}
          </div>
        </header>
        <div className="progress" aria-label={`要点进度 ${s.covered.length}/${s.keyPoints.length}`}>
          <span className="progress-count">{s.covered.length}<small>/{s.keyPoints.length}</small></span>
          <div className="scroll-x progress-chips" role="list">
            {s.keyPoints.map((k, i) => <span role="listitem" key={i} className={'pt' + (s.covered.includes(i) ? ' lit' : '')} style={{ '--vtn': 'pt-' + i } as CSSProperties}>{k}</span>)}
          </div>
          {!s.finished && (
            <button className="icon-btn points-btn" aria-label={pending ? `补充要点，${pending} 条待确认` : '补充要点'} onClick={() => update(st => { st.ui.sheet = 'points'; })}>
              <Icon name="plus" size={18} />{pending > 0 && <i className="badge-dot">{pending}</i>}
            </button>
          )}
        </div>
        {room && <RoomBar s={s} />}
        {debate && <DebateBoard s={s} />}
        {canvas && <CanvasPath s={s} />}
      </div>

      <main className="thread pad">
        {canvas && <ForkHeader s={s} />}
        {shown.map((m, i) => (
          <Fragment key={m.id}>
            {/* 辩论：环节变了就画一条分隔线 */}
            {debate && m.kind === 'msg' && m.stage !== undefined && m.stage !== prevStage(s, i) && <StageDivider name={s.stages[m.stage]?.name ?? ''} />}
            <EntryView m={m} s={s} showTip={m.id === lastAi && s.learnerTurns === 0 && !s.finished} voice={voice} />
            {canvas && m.kind === 'msg' && <BranchChips s={s} entryId={m.id} />}
          </Fragment>
        ))}
        {live?.kind === 'msg' && !s.entries.some(e => e.id === live.id) ? <LiveMessage s={s} onStop={room ? undefined : cancelAI} />
          : aiBusy || ui.busy ? <>{aiBusy && <Typing label={aiBusy} />}{ui.busy && <Typing label={ui.busy} onStop={cancelAI} />}</>
            : debate && <NextLectern s={s} waiting={nextWaiting(s)} />}
      </main>

      {ui.explain && (
        <Sheet title={`${ui.explain.label} · 讲解助手`} onClose={() => update(st => { st.ui.explain = null; })}>
          <blockquote className="quote">{ui.explain.quote}</blockquote>
          <p className="sheet-text">{live?.kind === 'explain' && live.text ? <Reveal id="explain" text={live.text} live />
            : ui.explain.text ? <Reveal id="explain" text={ui.explain.text} live={false} /> : '正在看这句话…'}</p>
          <p className="fineprint">{room ? '讲解只有你能看到，不占用发言轮次。' : '讲解不进入比赛记录，也不占用发言轮次。'}</p>
        </Sheet>
      )}
      {sheetMsg && (
        <Sheet title={canvas && !s.finished ? "追问或展开" : "追问这句"} onClose={() => update(st => { st.ui.sheet = null; })}>
          <blockquote className="quote">{sheetMsg.text.length > 80 ? sheetMsg.text.slice(0, 80) + '…' : sheetMsg.text}</blockquote>
          <div className="action-list">
            {QUICK.map(q => <button key={q.kind} className="action" onClick={() => quick(q.kind, sheetMsg.id)}><span>{q.label}</span><Icon name="chevron" size={18} /></button>)}
          </div>
          {!!sheetMsg.terms?.length && (
            <div className="stack-sm">
              <h3 className="section-label">这句里的新词</h3>
              <div className="row wrap">{sheetMsg.terms.map(t => <button key={t} className="chip term-chip" onClick={() => openTerm(t, sheetMsg.id)}>{t}</button>)}</div>
            </div>
          )}
          {canvas && !s.finished && <ForkForm m={sheetMsg} />}
          {(debate || room) && <p className="fineprint">{room ? '解释由讲解助手单独给你，不打断别人的发言。' : '辩论中，解释由讲解助手单独给出，不打断赛程。'}</p>}
        </Sheet>
      )}
      {ui.term && (
        <Sheet title={ui.term.term} className="term-sheet" onClose={() => update(st => { st.ui.term = null; })}>
          {ui.term.definition ? (
            <>
              <p className="sheet-text">{ui.term.definition}</p>
              {ui.term.relation && <p className="term-relation"><b>和「{s.concept}」的关系</b>{ui.term.relation}</p>}
              <p className="fineprint">{s.review ? '已存进这次的知识卡片。' : '已记下，复盘后会和这次的知识卡片存在一起。'}查词不占用发言轮次。</p>
              {canvas && !s.finished && <button className="secondary block" disabled={!!ui.busy} onClick={() => openBranch(ui.term!.messageId, ui.term!.term)}><Icon name="branch" size={16} />展开一个分支，聊聊「{ui.term.term}」</button>}
            </>
          ) : <p className="sheet-text muted">正在查这个词……</p>}
        </Sheet>
      )}
      {room && ui.sheet === 'callon' && <CallOnSheet s={s} />}
      {room && ui.sheet === 'seats' && <SeatSheet s={s} />}
      {room?.host && ui.invite && <InviteSheet s={s} />}
      {ui.sheet === 'points' && <PointsSheet s={s} />}
      {debate && ui.sheet === 'roster' && <RosterSheet s={s} />}
      {canvas && ui.sheet === 'tree' && <GraphSheet s={s} />}
      {canvas && ui.sheet === 'back' && <BackSheet s={s} />}
      {ui.sheet === 'finish' && (
        <Sheet title="现在结束比赛？" onClose={() => update(st => { st.ui.sheet = null; })}>
          <p className="sheet-text">还有 {s.turns.length - s.cursor} 次发言没有进行，包括后面的总结陈词。结束后{room ? '每个人' : '你'}只能按已有的发言复盘。</p>
          <div className="row">
            <button className="ghost grow" onClick={() => update(st => { st.ui.sheet = null; })}>继续比赛</button>
            <button className="primary grow" onClick={() => { update(st => { st.ui.sheet = null; }); void finish(); }}>结束并复盘</button>
          </div>
        </Sheet>
      )}
      <footer className="composer">
        <ErrorBanner />
        {s.awaitingReview ? (
          <div className="row between composer-done"><span className="muted">赛程结束了，读完总结陈词再复盘。</span><button className="primary small" disabled={!!ui.busy} onClick={finish}>进入复盘</button></div>
        ) : s.finished ? (
          <div className="row between composer-done"><span className="muted">这次讨论已结束。</span><div className="row"><button className="ghost small" onClick={() => exportSession(s)}><Icon name="download" size={16} />导出</button><button className="primary small" onClick={() => go('review')}>看复盘</button></div></div>
        ) : room ? <RoomComposer s={s} /> : <Composer s={s} />}
      </footer>
    </div>
  );
}

// 生成中的 AI 发言：和正式发言长得一样，文字逐字出现
function LiveMessage({ s, onStop }: { s: S; onStop?: () => void }) {
  const live = useLive()!;
  const debate = s.mode === 'debate';
  const persona = s.personas.find(r => r.id === live.who);
  const seat = debate ? s.seats.find(x => x.id === live.who) : undefined;
  const tone = debate ? (seat?.team === 'A' ? 'a' : 'b') : persona?.tone ?? 'a';
  if (!live.text) return <Typing label={live.label} onStop={onStop} />;
  return (
    <div className={'msg live' + (debate ? ' side-' + (seat?.team ?? 'A') : '')} aria-live="polite" aria-busy="true">
      <span className={'avatar tone-' + tone} aria-hidden="true">{debate ? (live.who ?? '').slice(1) : persona?.name.slice(0, 1) ?? '…'}</span>
      <div className="msg-body">
        <div className="msg-meta"><b>{debate ? (seat ? seatName(seat) : live.label) : `${persona?.name ?? ''}${persona?.title ? ' · ' + persona.title : ''}`}</b><span>正在说</span>{onStop && <button className="link" onClick={onStop}>停止</button>}</div>
        <div className="bubble"><Reveal id={live.id} text={live.text} live /></div>
      </div>
    </div>
  );
}

function Typing({ label, onStop }: { label: string; onStop?: () => void }) {
  return <div className="msg"><span className="avatar tone-ghost" aria-hidden="true">…</span><div className="msg-body"><div className="bubble typing"><span className="dots" aria-hidden="true"><i /><i /><i /></span>{label}{onStop && <button className="link" onClick={onStop}>停止</button>}</div></div></div>;
}

/* ---------- 单人讨论 ---------- */
function Composer({ s }: { s: S }) {
  const { ui } = useApp();
  const busy = !!ui.busy;
  const turn = currentTurn(s);
  const debate = s.mode === 'debate';
  const humanTurn = !debate || turn?.role.type === 'human';

  return (
    <>
      {debate && turn && (
        <div className="turn-banner"><span className={'dot team-' + turn.role.team} aria-hidden="true" />轮到 <b>{seatName(turn.role)}</b>{turn.role.type === 'human' ? '（你）' : ''} · {turn.action}{turn.target ? ` → ${targetName(s, turn.target)}` : ''}<span className="muted grow-right">{s.cursor + 1}/{s.turns.length}</span></div>
      )}
      {s.teaching && (
        <div className="turn-banner teaching-banner"><span className="dot" aria-hidden="true" />你在讲，<b>{s.personas[0]?.name}</b>当学生</div>
      )}
      <HintCard />
      <div className="tools scroll-x">
        {humanTurn && <button className="tool" disabled={busy} onClick={hint}><Icon name="bulb" size={16} />给点提示</button>}
        <button className="tool" disabled={busy} onClick={() => addReflection()}><Icon name="note" size={16} />写个小结</button>
        {s.mode === 'socratic' && <button className={'tool' + (s.teaching ? ' on' : '')} aria-pressed={!!s.teaching} disabled={busy} onClick={toggleTeaching}><Icon name="swap" size={16} />{s.teaching ? '换回来' : '换我来讲'}</button>}
        {!debate && s.personas.length > 1 && <button className="tool" disabled={busy} onClick={nextSpeaker}><Icon name="people" size={16} />请下一位</button>}
        {debate && <button className="tool" disabled={busy} onClick={skipSeat}>跳过本席</button>}
        {s.mode === 'canvas' && s.at && <button className="tool" disabled={busy} onClick={() => update(st => { st.ui.sheet = 'back'; })}><Icon name="turn-back" size={16} />带回{s.branches?.find(b => b.id === s.at)?.parent ? '上一层' : '主干'}</button>}
      </div>
      {humanTurn
        ? <DraftInput s={s} placeholder={debate ? `${turn?.action ?? '发言'}：我方认为……因为……` : s.mode === 'teach' || s.teaching ? `讲给${s.personas[0]?.name ?? '他'}听……` : '说说你的想法……'} />
        : <button className="primary block" disabled={busy} onClick={generateSeat}>让 {turn && seatName(turn.role)} 发言</button>}
    </>
  );
}


// 补充讨论要点：新要点先待确认，采纳后才点亮追踪、进入复盘；多人房间由房主决定
function PointsSheet({ s }: { s: S }) {
  const { ui } = useApp();
  const [text, setText] = useState('');
  const room = s.room;
  const canDecide = !room || room.host;
  const full = s.keyPoints.length >= 6;
  const debate = s.mode === 'debate';
  const add = () => { if (proposePoint(text)) setText(''); };
  return (
    <Sheet title={debate ? '核心争点' : '要弄懂的要点'} onClose={() => update(st => { st.ui.sheet = null; })}>
      <ol className="points-now">{s.keyPoints.map((k, i) => <li key={i} className={s.covered.includes(i) ? 'lit' : ''}>{k}</li>)}</ol>
      {!!s.proposedPoints?.length && (
        <div className="stack-sm">
          <h3 className="section-label">待确认</h3>
          {s.proposedPoints.map(p => (
            <div className="proposal" key={p.id}>
              <div className="grow"><p className="proposal-text">{p.text}</p><small className="muted">{p.source === 'ai' ? 'AI 提议' : `${p.proposer}提议`}</small></div>
              {canDecide && <div className="row"><button className="ghost small" onClick={() => decidePoint(p.id, false)}>忽略</button><button className="secondary small" disabled={full} onClick={() => decidePoint(p.id, true)}>采纳</button></div>}
            </div>
          ))}
        </div>
      )}
      {full ? <p className="fineprint">要点已经有 6 个了，先讲透这些。</p> : (
        <>
          <form className="input-row" onSubmit={e => { e.preventDefault(); add(); }}>
            <input className="grow" value={text} maxLength={40} placeholder={debate ? '写一个值得交锋的争点' : '写一个还想弄懂的要点'} aria-label="新的要点" onChange={e => setText(e.target.value)} />
            <button className="secondary" disabled={!text.trim()}>{canDecide ? '加入' : '提议'}</button>
          </form>
          <button className="ghost block" disabled={!!ui.busy} onClick={suggestPoint}><Icon name="bulb" size={16} />让 AI 提议一条</button>
        </>
      )}
      <p className="fineprint">{room && !room.host ? '你的提议会给房主确认，采纳后大家的要点都会更新。' : debate ? '争点是交锋的方向，不改变赛程；采纳后会点亮追踪，并进入复盘。' : '采纳后会点亮追踪，并进入复盘。'}</p>
    </Sheet>
  );
}

// 辩论：上一条发言属于哪个环节（第一条之前当作“没有”）
function prevStage(s: S, i: number): number | undefined {
  for (let k = i - 1; k >= 0; k--) { const e = s.entries[k]; if (e.kind === 'msg' && e.stage !== undefined) return e.stage; }
  return undefined;
}
// 下一位的空讲台上写什么：等谁
function nextWaiting(s: S): string {
  const t = s.turns[s.cursor];
  const seat = t && s.seats.find(x => x.id === t.role.id);
  if (!seat) return '';
  const who = seatWho(s, seat);
  if (who.short === '我') return '轮到你';
  if (s.room) return who.short === 'AI' ? 'AI 准备中' : who.short === '空' ? '等同学入座' : `等${who.long}发言`;
  return 'AI 待发言';
}
const targetName = (s: S, id: string) => { const t = s.seats.find(x => x.id === id); return t ? seatName(t) : id; };
