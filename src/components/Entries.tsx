// 讨论记录里的一条：发言、系统提示或小结卡片
import { useRef } from 'react';
import type { Entry, Reflection } from '../../shared/types.ts';
import {
  dropReflection,
  openTerm,
  setReflectionDraft,
  submitReflection,
  toggleVoice,
} from '../actions.ts';
import { canBranch } from '../lib/canvas.ts';
import { Icon } from './ui.tsx';
import { CaretField } from './SmoothCaret.tsx';
import { Reveal } from './Reveal.tsx';
import { BackLine } from './Canvas.tsx';
import { useLive } from '../lib/stream.ts';
import { seatName } from '../lib/debate.ts';
import { isPeer } from '../lib/room.ts';
import { usePlaying } from '../lib/voice.ts';
import { update, useApp, type Session as S } from '../store.ts';

// 把发言里 AI 标出的术语加上虚线
// 点到的是哪个术语（发言里的术语带 data-term）
const termAt = (target: EventTarget) =>
  (target as HTMLElement).closest?.<HTMLElement>('[data-term]')?.dataset.term ?? null;
const escapeRe = (s: string) => s.replace(/[.*+?^${}()|[\]\\]/g, '\\$&');

export function WithTerms({ text, terms }: { text: string; terms?: string[] }) {
  if (!terms?.length) return <>{text}</>;
  const list = [...terms].sort((a, b) => b.length - a.length);
  const parts = text.split(new RegExp(`(${list.map(escapeRe).join('|')})`, 'g'));
  return (
    <>
      {parts.map((part, i) =>
        list.includes(part) ? (
          <span key={i} className="term" data-term={part}>
            {part}
          </span>
        ) : (
          part
        ),
      )}
    </>
  );
}

export function EntryView({
  m,
  s,
  showTip,
  voice,
}: {
  m: Entry;
  s: S;
  showTip: boolean;
  voice: boolean;
}) {
  if (m.kind === 'sys') {
    if (m.back) return <BackLine m={m} s={s} />;
    const topic = m.text.match(/^(讨论题|辩题)：([\s\S]+)$/);
    return topic ? (
      <div className="topic-line">
        <small>{topic[1]}</small>
        <p>{topic[2]}</p>
      </div>
    ) : (
      <div className="sysline">{m.text}</div>
    );
  }
  if (m.kind === 'reflect') return <ReflectCard m={m} concept={s.concept} />;
  const me = !!m.human;
  const peer = isPeer(m);
  const persona = s.personas.find(r => r.id === m.who);
  const debate = s.mode === 'debate';
  const tone = debate
    ? m.team === 'A'
      ? 'a'
      : 'b'
    : me
      ? 'me'
      : peer
        ? 'peer'
        : (persona?.tone ?? 'a');
  const seat = debate ? m.name.replace(/（.*）$/, '') : '';
  const target = debate && m.target ? s.seats.find(x => x.id === m.target) : undefined;
  const label = me
    ? debate
      ? seat + ' · 你'
      : '你'
    : debate
      ? m.name
      : peer
        ? m.name
        : `${m.name}${persona?.title ? ' · ' + persona.title : ''}`;
  const meta = [
    debate
      ? (m.action ?? '') + (target ? ` → ${seatName(target)}` : '')
      : // 以前“换我来讲”留下的发言（#142 去掉了这个开关），旧讨论里照样标出来
        m.action === '讲给伙伴听'
        ? '讲解'
        : m.action === '当学生'
          ? '当学生'
          : '',
    m.source === 'demo' && !me ? '演示' : m.source === 'api' && !me ? m.model : '',
    peer && !debate ? '同学' : '',
  ]
    .filter(Boolean)
    .join(' · ');
  const avatarText = debate ? m.who.slice(1) : me ? '我' : m.name.slice(0, 1);
  // 辩论按阵营分左右（正方左、反方右），不按“是不是我”
  const side = debate
    ? ' side-' + (m.team ?? 'A') + (me ? ' mine' : '')
    : (me ? ' me' : '') + (peer ? ' peer' : '');
  return (
    <div className={'msg' + side} data-entry={m.id}>
      <span className={'avatar tone-' + tone} aria-hidden="true">
        {avatarText}
      </span>
      <div className="msg-body">
        <div className="msg-meta">
          <b>{label}</b>
          {meta && <span>{meta}</span>}
          {m.skipped && <span>已跳过</span>}
          {voice && !m.skipped && <VoiceButton id={m.id} label={label} />}
        </div>
        {me || m.skipped || s.finished ? (
          <div
            className={'bubble' + (m.skipped ? ' skipped' : '')}
            onClick={e => termAt(e.target) && openTerm(termAt(e.target)!, m.id)}
          >
            <Reveal
              id={m.id}
              text={m.text}
              live={false}
              final={<WithTerms text={m.text} terms={m.terms} />}
            />
          </div>
        ) : (
          <button
            className="bubble tappable"
            aria-haspopup="dialog"
            onClick={e => {
              // 点在虚线词上就查词，否则打开追问面板
              const term = termAt(e.target);
              if (term) void openTerm(term, m.id);
              else
                update(st => {
                  st.ui.sheet = m.id;
                });
            }}
          >
            <Reveal
              id={m.id}
              text={m.text}
              live={false}
              final={<WithTerms text={m.text} terms={m.terms} />}
            />
          </button>
        )}
        {showTip && (
          <small className="tip">
            {canBranch(s)
              ? '轻点发言可以追问，或从这句展开一个分支单独聊。'
              : '轻点发言可以追问：没听懂、举例、反驳、分辨事实。'}
            {!!m.terms?.length && '带虚线的词，点一下看解释。'}
          </small>
        )}
      </div>
    </div>
  );
}

export function ReflectCard({ m, concept }: { m: Reflection; concept: string }) {
  const { ui, session } = useApp();
  const draftRef = useRef<HTMLTextAreaElement>(null);
  const live = useLive();
  const streaming = live?.kind === 'reflect' && live.id === m.id && !!live.text;
  return (
    <section className="reflect" aria-label="小结卡" data-entry={m.id}>
      <h3>停一下，用自己的话说说</h3>
      {m.text ? (
        <>
          <p className="mine">{m.text}</p>
          <p className="feedback">
            <Reveal id={m.id} text={m.feedback} live={false} />
          </p>
        </>
      ) : (
        <>
          <p className="muted">
            你现在怎么理解「{concept}」？一两句话就好。{session?.room && '小结只有你能看到。'}
          </p>
          <CaretField fieldRef={draftRef}>
            <textarea
              ref={draftRef}
              value={m.draft}
              rows={2}
              maxLength={1000}
              aria-label="我的小结"
              onChange={e => setReflectionDraft(m.id, e.target.value)}
            />
          </CaretField>
          <div className="row end">
            <button className="ghost small" onClick={() => dropReflection(m.id)}>
              跳过
            </button>
            <button
              className="primary small"
              disabled={!!ui.busy || !m.draft.trim()}
              onClick={() => submitReflection(m.id)}
            >
              提交小结
            </button>
          </div>
          {streaming && (
            <p className="feedback">
              <Reveal id={m.id} text={live.text} live />
            </p>
          )}
        </>
      )}
    </section>
  );
}

// 朗读按钮：只有后端配好讯飞时才出现；看起来是小字，可点区域有 44px 高
export function VoiceButton({ id, label }: { id: string; label: string }) {
  const on = usePlaying() === id;
  return (
    <button
      className={'voice-play' + (on ? ' on' : '')}
      aria-pressed={on}
      aria-label={on ? `停止朗读${label}的发言` : `朗读${label}的发言`}
      onClick={() => toggleVoice(id)}
    >
      <Icon name={on ? 'stop' : 'volume'} size={14} />
      {on ? '停止' : '朗读'}
    </button>
  );
}
