import { useEffect, useRef, useState } from 'react';
import { MODES } from '../../shared/modes.ts';
import { QUICK_MESSAGES } from '../../shared/room.ts';
import type { ChatMessage, Entry, Reflection } from '../../shared/types.ts';
import { QUICK, addReflection, aiTakeover, callOn, currentTurn, decidePoint, dropReflection, finish, generateSeat, hint, nextSpeaker, noteTyping, openTerm, passTurn, pickSeat, proposePoint, quick, quickMessage, retryAI, send, setReflectionDraft, skipSeat, startRoom, stopTyping, submitReflection, suggestPoint, toggleVoice, voiceAvailable } from '../actions.ts';
import { Empty, ErrorBanner, Icon, Sheet } from '../components/ui.tsx';
import { CaretField } from '../components/SmoothCaret.tsx';
import { Reveal } from '../components/Reveal.tsx';
import { useLive } from '../lib/stream.ts';
import { cancelAI } from '../lib/ai.ts';
import { seatName } from '../lib/debate.ts';
import { exportSession } from '../lib/exporter.ts';
import { inviteLink, isPeer, mySeat, mySpeakingTurn, seatOwner, speakerLabel } from '../lib/room.ts';
import { usePlaying } from '../lib/voice.ts';
import { go, toast, update, useApp, type Session as S } from '../store.ts';

export function Session() {
  const { session: s, ui } = useApp();
  const live = useLive();
  const count = s ? s.entries.length : 0;
  const aiBusy = s?.room?.aiBusy;
  useEffect(() => { window.scrollTo({ top: document.body.scrollHeight, behavior: 'smooth' }); }, [count, ui.busy, ui.hint, aiBusy, live?.id]);
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
  const room = s.room;
  const turn = currentTurn(s);
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
            {s.keyPoints.map((k, i) => <span role="listitem" key={i} className={'pt' + (s.covered.includes(i) ? ' lit' : '')}>{k}</span>)}
          </div>
          {!s.finished && (
            <button className="icon-btn points-btn" aria-label={pending ? `补充要点，${pending} 条待确认` : '补充要点'} onClick={() => update(st => { st.ui.sheet = 'points'; })}>
              <Icon name="plus" size={18} />{pending > 0 && <i className="badge-dot">{pending}</i>}
            </button>
          )}
        </div>
        {room && <RoomBar s={s} />}
        {debate && (
          <div className="stage-strip scroll-x" aria-label="比赛环节">
            {s.stages.map((st, i) => <span key={i} className={'stage-pill' + (turn?.stage === i ? ' on' : (turn && i < turn.stage) || s.finished || s.awaitingReview ? ' done' : '')}>{st.name}</span>)}
          </div>
        )}
      </div>

      <main className="thread pad">
        {s.entries.map(m => <EntryView key={m.id} m={m} s={s} showTip={m.id === lastAi && s.learnerTurns === 0 && !s.finished} voice={voice} />)}
        {live?.kind === 'msg' && !s.entries.some(e => e.id === live.id) ? <LiveMessage s={s} onStop={room ? undefined : cancelAI} />
          : <>{aiBusy && <Typing label={aiBusy} />}{ui.busy && <Typing label={ui.busy} onStop={cancelAI} />}</>}
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
        <Sheet title="追问这句" onClose={() => update(st => { st.ui.sheet = null; })}>
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
            </>
          ) : <p className="sheet-text muted">正在查这个词……</p>}
        </Sheet>
      )}
      {room && ui.sheet === 'callon' && <CallOnSheet s={s} />}
      {room && ui.sheet === 'seats' && <SeatSheet s={s} />}
      {room?.host && ui.invite && <InviteSheet s={s} />}
      {ui.sheet === 'points' && <PointsSheet s={s} />}
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
    <div className="msg live" aria-live="polite" aria-busy="true">
      <span className={'avatar tone-' + tone} aria-hidden="true">{debate ? live.who : persona?.name.slice(0, 1) ?? '…'}</span>
      <div className="msg-body">
        <div className="msg-meta"><b>{debate ? (seat ? seatName(seat) : live.label) : `${persona?.name ?? ''}${persona?.title ? ' · ' + persona.title : ''}`}</b><span>正在说</span>{onStop && <button className="link" onClick={onStop}>停止</button>}</div>
        <div className="bubble"><Reveal id={live.id} text={live.text} live /></div>
      </div>
    </div>
  );
}

function HintCard() {
  const { ui } = useApp();
  const live = useLive();
  const streaming = live?.kind === 'hint' && !!live.text;
  if (!ui.hint && !streaming) return null;
  return (
    <div className="hint-card"><Icon name="bulb" size={18} />
      <p>{streaming ? <Reveal id="hint" text={live!.text} live /> : <Reveal id="hint" text={ui.hint!} live={false} />}</p>
      <button className="icon-btn small" aria-label="关闭提示" onClick={() => update(st => { st.ui.hint = null; })}><Icon name="close" size={16} /></button>
    </div>
  );
}

function Typing({ label, onStop }: { label: string; onStop?: () => void }) {
  return <div className="msg"><span className="avatar tone-ghost" aria-hidden="true">…</span><div className="msg-body"><div className="bubble typing"><span className="dots" aria-hidden="true"><i /><i /><i /></span>{label}{onStop && <button className="link" onClick={onStop}>停止</button>}</div></div></div>;
}

/* ---------- 多人讨论 ---------- */
function RoomBar({ s }: { s: S }) {
  const room = s.room!;
  const seatOf = (seat?: string) => s.seats.find(x => x.id === seat);
  return (
    <div className="room-bar">
      <div className="scroll-x members" role="list" aria-label="参与讨论的人">
        {room.members.map(m => {
          const seat = seatOf(m.seat);
          return (
            <span role="listitem" key={m.id} className={'member' + (m.online ? '' : ' away') + (m.id === room.memberId ? ' me' : '')}>
              <i className={'presence' + (m.online ? ' on' : '')} aria-label={m.online ? '在线' : '离开'} />
              {m.id === room.memberId ? '你' : m.name}{m.host && <small>房主</small>}{s.mode === 'debate' && <small>{seat ? seatName(seat) : '旁听'}</small>}{m.typing && m.id !== room.memberId && <small className="typing-label">正在输入…</small>}
            </span>
          );
        })}
      </div>
      {room.host && !s.finished && <button className="chip-btn" onClick={() => update(st => { st.ui.invite = true; })}><Icon name="plus" size={14} />邀请</button>}
      {(room.closed || !room.connected) && <p className="room-status" role="status">{room.closed || '正在连接房间…'}</p>}
    </div>
  );
}

function InviteSheet({ s }: { s: S }) {
  const room = s.room!;
  const url = room.invite ? inviteLink(room) : '';
  const close = () => update(st => { st.ui.invite = false; });
  const copy = async () => {
    try { await navigator.clipboard.writeText(url); toast('邀请链接已复制。'); }
    catch { toast('没能复制，请长按链接手动复制。'); }
  };
  const share = async () => { try { await navigator.share({ title: `一起讨论「${s.concept}」`, url }); } catch { /* 用户取消 */ } };
  const left = Math.max(0, Math.round((room.expiresAt - Date.now()) / 60000));
  return (
    <Sheet title="邀请同学" onClose={close}>
      {url ? (
        <>
          <p className="sheet-text">把这个链接发给同学，打开后写个名字就能加入。</p>
          <code className="invite-link">{url}</code>
          <div className="row">
            <button className="primary grow" onClick={copy}>复制链接</button>
            {typeof navigator.share === 'function' && <button className="secondary" onClick={share}>分享</button>}
          </div>
          <p className="fineprint">拿到链接的人都能加入，只发给一起讨论的同学。房间约 {left >= 60 ? Math.round(left / 60) + ' 小时' : left + ' 分钟'}后到期，讨论内容随房间一起删除。</p>
        </>
      ) : <p className="sheet-text">这台设备上没有保存邀请链接。请在创建房间的设备上分享。</p>}
    </Sheet>
  );
}

function SeatSheet({ s }: { s: S }) {
  const room = s.room!;
  const mine = mySeat(s);
  return (
    <Sheet title="选择席位" onClose={() => update(st => { st.ui.sheet = null; })}>
      <div className="seat-pick">
        {(['A', 'B'] as const).map(team => (
          <section key={team} className={'team team-' + team}>
            <b>{team === 'A' ? '正方 · 支持' : '反方 · 反对'}</b>
            {s.seats.filter(x => x.team === team).map(seat => {
              const owner = seatOwner(s, seat.id);
              const me = owner?.id === room.memberId;
              return (
                <button key={seat.id} className={'seat-option' + (me ? ' on' : '')} disabled={!!owner && !me} aria-pressed={me} onClick={() => !me && pickSeat(seat.id)}>
                  <span>{seatName(seat)}</span>
                  <small>{me ? '你' : owner ? owner.name : seat.type === 'ai' ? 'AI 担任 · 也可以坐' : '留给同学 · 空着'}</small>
                </button>
              );
            })}
          </section>
        ))}
      </div>
      {mine && <button className="ghost block" onClick={() => pickSeat(null)}>离开席位，改为旁听</button>}
      <p className="fineprint">没人坐的席位由 AI 担任。比赛开始后也可以上场或换席位，轮到你的席位时会提醒你发言。</p>
    </Sheet>
  );
}

function CallOnSheet({ s }: { s: S }) {
  const room = s.room!;
  return (
    <Sheet title="请谁发言" onClose={() => update(st => { st.ui.sheet = null; })}>
      <div className="action-list">
        {room.members.map(m => <button key={m.id} className="action" onClick={() => callOn({ kind: 'member', id: m.id })}><span>{m.id === room.memberId ? '我' : m.name}{!m.online && <small className="muted"> · 不在线</small>}</span><Icon name="chevron" size={18} /></button>)}
        {s.personas.map(p => <button key={p.id} className="action" onClick={() => callOn({ kind: 'ai', id: p.id })}><span>{p.name}<small className="muted"> · AI 嘉宾</small></span><Icon name="chevron" size={18} /></button>)}
      </div>
    </Sheet>
  );
}

function RoomComposer({ s }: { s: S }) {
  const { ui } = useApp();
  const room = s.room!;
  const mine = mySpeakingTurn(s);
  const busy = !!ui.busy;

  if (room.status === 'lobby') {
    const n = room.members.length;
    return room.host ? (
      <>
        <p className="lobby-note">{n > 1 ? `已有 ${n} 人在房间里。` : '还没有同学加入。'}人到齐后开始讨论，晚来的同学也可以中途加入。</p>
        <div className="row">
          <button className="secondary" onClick={() => update(st => { st.ui.invite = true; })}>邀请同学</button>
          <button className="primary grow" disabled={!room.connected} onClick={startRoom}>开始讨论</button>
        </div>
        {s.mode === 'debate' && <SeatPrompt s={s} />}
      </>
    ) : (
      <>
        <p className="lobby-note">等房主开始讨论……</p>
        {s.mode === 'debate' && <SeatPrompt s={s} primary />}
      </>
    );
  }

  const t = s.turns[s.cursor];
  // 现在轮到的是“等人”的席位（有同学坐，或留给同学但还空着）：房主可以让 AI 代答
  const humanSeat = s.mode === 'debate' && !!t && (!!seatOwner(s, t.role.id) || t.role.type !== 'ai');
  return (
    <>
      {room.error ? (
        <div className="alert" role="alert"><span>{room.error}</span>{room.host && <button className="link" onClick={retryAI}>重试</button>}</div>
      ) : (
        <div className="turn-banner">
          {s.mode === 'debate' && t && <span className={'dot team-' + t.role.team} aria-hidden="true" />}
          {mine ? <b>轮到你发言</b> : room.aiBusy ? <b>{room.aiBusy}</b> : <>轮到 <b>{speakerLabel(s)}</b></>}
          <span className="grow-right row">
            {room.turnDeadline > 0 && !room.aiBusy && <Countdown deadline={room.turnDeadline} />}
            {s.mode === 'debate' && <span className="muted">{s.cursor + 1}/{s.turns.length}</span>}
          </span>
        </div>
      )}
      <HintCard />
      <div className="tools scroll-x">
        {mine && <button className="tool" disabled={busy} onClick={hint}><Icon name="bulb" size={16} />给点提示</button>}
        <button className="tool" disabled={busy} onClick={() => addReflection()}><Icon name="note" size={16} />写个小结</button>
        {room.host && s.mode === 'roundtable' && <button className="tool" disabled={!!room.aiBusy} onClick={() => update(st => { st.ui.sheet = 'callon'; })}><Icon name="people" size={16} />点名</button>}
        {room.host && humanSeat && !mine && !room.aiBusy && <button className="tool" onClick={aiTakeover}>让 AI 代答</button>}
        {(mine || room.host) && !room.aiBusy && <button className="tool" onClick={passTurn}>{mine ? '这次先跳过' : '跳过当前发言'}</button>}
        {s.mode === 'debate' && <button className="tool" onClick={() => update(st => { st.ui.sheet = 'seats'; })}><Icon name="people" size={16} />{mySeat(s) ? '换席位' : '上场辩论'}</button>}
        {/* 快捷短语：只是提醒大家，不进讨论记录 */}
        <span className="tools-sep" aria-hidden="true" />
        {QUICK_MESSAGES.map(text => <button key={text} className="tool quiet" onClick={() => quickMessage(text)}>{text}</button>)}
      </div>
      {mine ? <DraftInput s={s} placeholder={s.mode === 'debate' ? `${t?.action ?? '发言'}：我方认为……因为……` : '说说你的想法……'} /> : <p className="lobby-note">{room.aiBusy ? '稍等，AI 正在发言。' : '轮到你时，这里会出现输入框。'}</p>}
    </>
  );
}

// 辩论开始前：提醒每个人选好自己的席位
function SeatPrompt({ s, primary = false }: { s: S; primary?: boolean }) {
  const seat = s.seats.find(x => x.id === mySeat(s));
  const open = () => update(st => { st.ui.sheet = 'seats'; });
  return seat
    ? <button className="ghost block" onClick={open}>你是{seatName(seat)} · 换席位</button>
    : <button className={(primary ? 'primary' : 'secondary') + ' block'} onClick={open}>选择阵营和席位</button>;
}
// 发言限时只是提醒：到点不打断，还可以把话说完
function Countdown({ deadline }: { deadline: number }) {
  const [, tick] = useState(0);
  useEffect(() => { const id = setInterval(() => tick(n => n + 1), 1000); return () => clearInterval(id); }, []);
  const left = Math.max(0, Math.ceil((deadline - Date.now()) / 1000));
  const text = `${Math.floor(left / 60)}:${String(left % 60).padStart(2, '0')}`;
  return left
    ? <span className={'turn-countdown' + (left <= 10 ? ' soon' : '')} aria-label={`这次发言还剩 ${left} 秒`}>{text}</span>
    : <span className="turn-countdown expired">时间到</span>;
}

function DraftInput({ s, placeholder }: { s: S; placeholder: string }) {
  const { ui } = useApp();
  const ref = useRef<HTMLTextAreaElement>(null);
  useEffect(() => { const t = ref.current; if (t) { t.style.height = 'auto'; t.style.height = Math.min(t.scrollHeight, 160) + 'px'; } }, [s.draft]);
  useEffect(() => stopTyping, []);
  const submit = () => { stopTyping(); send(s.draft); };
  return (
    <div className="input-row">
      <CaretField fieldRef={ref} className="grow">
        <textarea id="draft" ref={ref} rows={1} value={s.draft} maxLength={s.room ? 2000 : 4000} aria-label="你的发言" placeholder={placeholder}
          onChange={e => { update(st => { st.session!.draft = e.target.value; }); noteTyping(); }}
          onKeyDown={e => { if (e.key === 'Enter' && !e.shiftKey && !e.nativeEvent.isComposing && window.matchMedia('(pointer: fine)').matches) { e.preventDefault(); submit(); } }} />
      </CaretField>
      <button className="send" aria-label="发送" disabled={!!ui.busy || !s.draft.trim()} onClick={submit}><Icon name="send" /></button>
    </div>
  );
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
        <div className="turn-banner"><span className={'dot team-' + turn.role.team} aria-hidden="true" />轮到 <b>{seatName(turn.role)}</b>{turn.role.type === 'human' ? '（你）' : ''} · {turn.action}{turn.target ? ` → ${turn.target}` : ''}<span className="muted grow-right">{s.cursor + 1}/{s.turns.length}</span></div>
      )}
      <HintCard />
      <div className="tools scroll-x">
        {humanTurn && <button className="tool" disabled={busy} onClick={hint}><Icon name="bulb" size={16} />给点提示</button>}
        <button className="tool" disabled={busy} onClick={() => addReflection()}><Icon name="note" size={16} />写个小结</button>
        {!debate && s.personas.length > 1 && <button className="tool" disabled={busy} onClick={nextSpeaker}><Icon name="people" size={16} />请下一位</button>}
        {debate && <button className="tool" disabled={busy} onClick={skipSeat}>跳过本席</button>}
      </div>
      {humanTurn
        ? <DraftInput s={s} placeholder={debate ? `${turn?.action ?? '发言'}：我方认为……因为……` : s.mode === 'teach' ? '开始讲解……' : '说说你的想法……'} />
        : <button className="primary block" disabled={busy} onClick={generateSeat}>让 {turn && seatName(turn.role)} 发言</button>}
    </>
  );
}

const termAt = (target: EventTarget) => (target as HTMLElement).closest?.<HTMLElement>('[data-term]')?.dataset.term ?? null;
const escapeRe = (s: string) => s.replace(/[.*+?^${}()|[\]\\]/g, '\\$&');

// 把发言里 AI 标出的术语加上虚线
function WithTerms({ text, terms }: { text: string; terms?: string[] }) {
  if (!terms?.length) return <>{text}</>;
  const list = [...terms].sort((a, b) => b.length - a.length);
  const parts = text.split(new RegExp(`(${list.map(escapeRe).join('|')})`, 'g'));
  return <>{parts.map((part, i) => list.includes(part) ? <span key={i} className="term" data-term={part}>{part}</span> : part)}</>;
}

function EntryView({ m, s, showTip, voice }: { m: Entry; s: S; showTip: boolean; voice: boolean }) {
  if (m.kind === 'sys') {
    const topic = m.text.match(/^(讨论题|辩题)：([\s\S]+)$/);
    return topic ? <div className="topic-line"><small>{topic[1]}</small><p>{topic[2]}</p></div> : <div className="sysline">{m.text}</div>;
  }
  if (m.kind === 'reflect') return <ReflectCard m={m} concept={s.concept} />;
  const me = !!m.human;
  const peer = isPeer(m);
  const persona = s.personas.find(r => r.id === m.who);
  const debate = s.mode === 'debate';
  const tone = me ? 'me' : debate ? (m.team === 'A' ? 'a' : 'b') : peer ? 'peer' : persona?.tone ?? 'a';
  const seat = debate ? m.name.replace(/（.*）$/, '') : '';
  const label = me ? (debate ? seat + ' · 你' : '你')
    : debate ? m.name : peer ? m.name : `${m.name}${persona?.title ? ' · ' + persona.title : ''}`;
  const meta = [debate ? m.action : '', m.source === 'demo' && !me ? '演示' : m.source === 'api' && !me ? m.model : '', peer && !debate ? '同学' : ''].filter(Boolean).join(' · ');
  const avatarText = me ? '我' : debate ? m.who : m.name.slice(0, 1);
  return (
    <div className={'msg' + (me ? ' me' : '') + (peer ? ' peer' : '')}>
      <span className={'avatar tone-' + tone} aria-hidden="true">{avatarText}</span>
      <div className="msg-body">
        <div className="msg-meta"><b>{label}</b>{meta && <span>{meta}</span>}{m.skipped && <span>已跳过</span>}{voice && !m.skipped && <VoiceButton id={m.id} label={label} />}</div>
        {me || m.skipped || s.finished
          ? <div className={'bubble' + (m.skipped ? ' skipped' : '')} onClick={e => termAt(e.target) && openTerm(termAt(e.target)!, m.id)}><Reveal id={m.id} text={m.text} live={false} final={<WithTerms text={m.text} terms={m.terms} />} /></div>
          : <button className="bubble tappable" aria-haspopup="dialog" onClick={e => {
              // 点在虚线词上就查词，否则打开追问面板
              const term = termAt(e.target);
              if (term) openTerm(term, m.id); else update(st => { st.ui.sheet = m.id; });
            }}><Reveal id={m.id} text={m.text} live={false} final={<WithTerms text={m.text} terms={m.terms} />} /></button>}
        {showTip && <small className="tip">轻点发言可以追问：没听懂、举例、反驳、分辨事实。{!!m.terms?.length && '带虚线的词，点一下看解释。'}</small>}
      </div>
    </div>
  );
}

function ReflectCard({ m, concept }: { m: Reflection; concept: string }) {
  const { ui, session } = useApp();
  const draftRef = useRef<HTMLTextAreaElement>(null);
  const live = useLive();
  const streaming = live?.kind === 'reflect' && live.id === m.id && !!live.text;
  return (
    <section className="reflect" aria-label="小结卡">
      <h3>停一下，用自己的话说说</h3>
      {m.text ? (
        <>
          <p className="mine">{m.text}</p>
          <p className="feedback"><Reveal id={m.id} text={m.feedback} live={false} /></p>
        </>
      ) : (
        <>
          <p className="muted">你现在怎么理解「{concept}」？一两句话就好。{session?.room && '小结只有你能看到。'}</p>
          <CaretField fieldRef={draftRef}><textarea ref={draftRef} value={m.draft} rows={2} maxLength={1000} aria-label="我的小结" onChange={e => setReflectionDraft(m.id, e.target.value)} /></CaretField>
          <div className="row end">
            <button className="ghost small" onClick={() => dropReflection(m.id)}>跳过</button>
            <button className="primary small" disabled={!!ui.busy || !m.draft.trim()} onClick={() => submitReflection(m.id)}>提交小结</button>
          </div>
          {streaming && <p className="feedback"><Reveal id={m.id} text={live!.text} live /></p>}
        </>
      )}
    </section>
  );
}

// 朗读按钮：只有后端配好讯飞时才出现；看起来是小字，可点区域有 44px 高
function VoiceButton({ id, label }: { id: string; label: string }) {
  const on = usePlaying() === id;
  return (
    <button className={'voice-play' + (on ? ' on' : '')} aria-pressed={on} aria-label={on ? `停止朗读${label}的发言` : `朗读${label}的发言`} onClick={() => toggleVoice(id)}>
      <Icon name={on ? 'stop' : 'volume'} size={14} />{on ? '停止' : '朗读'}
    </button>
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
