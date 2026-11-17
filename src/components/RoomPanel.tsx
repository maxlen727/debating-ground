// 多人讨论的界面：房间状态栏、邀请、选席位、点名和发言区
import { useEffect, useState } from 'react';
import { QUICK_MESSAGES } from '../../shared/room.ts';
import {
  addReflection,
  aiTakeover,
  callOn,
  hint,
  passTurn,
  pickSeat,
  quickMessage,
  retryAI,
  startRoom,
  toggleAiPause,
} from '../actions.ts';
import { Icon, Sheet } from './ui.tsx';
import { seatName } from '../lib/debate.ts';
import { inviteLink, mySeat, mySpeakingTurn, seatOwner, speakerLabel } from '../lib/room.ts';
import { toast, update, useApp, type Session as S } from '../store.ts';
import { HintCard, DraftInput } from './DraftInput.tsx';

/* ---------- 多人讨论 ---------- */
export function RoomBar({ s }: { s: S }) {
  const room = s.room!;
  const seatOf = (seat?: string) => s.seats.find(x => x.id === seat);
  return (
    <div className="room-bar">
      <div className="scroll-x members" role="list" aria-label="参与讨论的人">
        {room.members.map(m => {
          const seat = seatOf(m.seat);
          return (
            <span
              role="listitem"
              key={m.id}
              className={
                'member' + (m.online ? '' : ' away') + (m.id === room.memberId ? ' me' : '')
              }
            >
              <i
                className={'presence' + (m.online ? ' on' : '')}
                aria-label={m.online ? '在线' : '离开'}
              />
              {m.id === room.memberId ? '你' : m.name}
              {m.host && <small>房主</small>}
              {s.mode === 'debate' && <small>{seat ? seatName(seat) : '旁听'}</small>}
              {m.typing && m.id !== room.memberId && (
                <small className="typing-label">正在输入…</small>
              )}
            </span>
          );
        })}
      </div>
      {room.host && !s.finished && (
        <button
          className="chip-btn"
          onClick={() =>
            update(st => {
              st.ui.invite = true;
            })
          }
        >
          <Icon name="plus" size={14} />
          邀请
        </button>
      )}
      {(room.closed || !room.connected) && (
        <p className="room-status" role="status">
          {room.closed || '正在连接房间…'}
        </p>
      )}
    </div>
  );
}

export function InviteSheet({ s }: { s: S }) {
  const room = s.room!;
  const url = room.invite ? inviteLink(room) : '';
  const close = () =>
    update(st => {
      st.ui.invite = false;
    });
  const copy = async () => {
    try {
      await navigator.clipboard.writeText(url);
      toast('邀请链接已复制。');
    } catch {
      toast('没能复制，请长按链接手动复制。');
    }
  };
  const share = async () => {
    try {
      await navigator.share({ title: `一起讨论「${s.concept}」`, url });
    } catch {
      /* 用户取消 */
    }
  };
  const left = Math.max(0, Math.round((room.expiresAt - Date.now()) / 60000));
  return (
    <Sheet title="邀请同学" onClose={close}>
      {url ? (
        <>
          <p className="sheet-text">把这个链接发给同学，打开后写个名字就能加入。</p>
          <code className="invite-link">{url}</code>
          <div className="row">
            <button className="primary grow" onClick={copy}>
              复制链接
            </button>
            {typeof navigator.share === 'function' && (
              <button className="secondary" onClick={share}>
                分享
              </button>
            )}
          </div>
          <p className="fineprint">
            拿到链接的人都能加入，只发给一起讨论的同学。房间约{' '}
            {left >= 60 ? Math.round(left / 60) + ' 小时' : left + ' 分钟'}
            后到期，讨论内容随房间一起删除。
          </p>
        </>
      ) : (
        <p className="sheet-text">这台设备上没有保存邀请链接。请在创建房间的设备上分享。</p>
      )}
    </Sheet>
  );
}

export function SeatSheet({ s }: { s: S }) {
  const room = s.room!;
  const mine = mySeat(s);
  return (
    <Sheet
      title="选择席位"
      onClose={() =>
        update(st => {
          st.ui.sheet = null;
        })
      }
    >
      <div className="seat-pick">
        {(['A', 'B'] as const).map(team => (
          <section key={team} className={'team team-' + team}>
            <b>{team === 'A' ? '正方 · 支持' : '反方 · 反对'}</b>
            {s.seats
              .filter(x => x.team === team)
              .map(seat => {
                const owner = seatOwner(s, seat.id);
                const me = owner?.id === room.memberId;
                return (
                  <button
                    key={seat.id}
                    className={'seat-option' + (me ? ' on' : '')}
                    disabled={!!owner && !me}
                    aria-pressed={me}
                    onClick={() => !me && pickSeat(seat.id)}
                  >
                    <span>{seatName(seat)}</span>
                    <small>
                      {me
                        ? '你'
                        : owner
                          ? owner.name
                          : seat.type === 'ai'
                            ? 'AI 担任 · 也可以坐'
                            : '留给同学 · 空着'}
                    </small>
                  </button>
                );
              })}
          </section>
        ))}
      </div>
      {mine && (
        <button className="ghost block" onClick={() => pickSeat(null)}>
          离开席位，改为旁听
        </button>
      )}
      <p className="fineprint">
        没人坐的席位由 AI 担任。比赛开始后也可以上场或换席位，轮到你的席位时会提醒你发言。
      </p>
    </Sheet>
  );
}

export function CallOnSheet({ s }: { s: S }) {
  const room = s.room!;
  return (
    <Sheet
      title="请谁说说"
      onClose={() =>
        update(st => {
          st.ui.sheet = null;
        })
      }
    >
      <div className="action-list">
        {room.members.map(m => (
          <button
            key={m.id}
            className="action"
            onClick={() => callOn({ kind: 'member', id: m.id })}
          >
            <span>
              {m.id === room.memberId ? '我' : m.name}
              {!m.online && <small className="muted"> · 不在线</small>}
            </span>
            <Icon name="chevron" size={18} />
          </button>
        ))}
        {s.personas.map(p => (
          <button key={p.id} className="action" onClick={() => callOn({ kind: 'ai', id: p.id })}>
            <span>
              {p.name}
              <small className="muted"> · AI 嘉宾</small>
            </span>
            <Icon name="chevron" size={18} />
          </button>
        ))}
      </div>
    </Sheet>
  );
}

export function RoomComposer({ s }: { s: S }) {
  const { ui } = useApp();
  const room = s.room!;
  const mine = mySpeakingTurn(s);
  const busy = !!ui.busy;

  if (room.status === 'lobby') {
    const n = room.members.length;
    return room.host ? (
      <>
        <p className="lobby-note">
          {n > 1 ? `已有 ${n} 人在房间里。` : '还没有同学加入。'}
          人到齐后开始讨论，晚来的同学也可以中途加入。
        </p>
        <div className="row">
          <button
            className="secondary"
            onClick={() =>
              update(st => {
                st.ui.invite = true;
              })
            }
          >
            邀请同学
          </button>
          <button className="primary grow" disabled={!room.connected} onClick={startRoom}>
            开始讨论
          </button>
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
  const humanSeat =
    s.mode === 'debate' && !!t && (!!seatOwner(s, t.role.id) || t.role.type !== 'ai');
  return (
    <>
      {room.error ? (
        <div className="alert" role="alert">
          <span>{room.error}</span>
          {room.host && (
            <button className="link" onClick={retryAI}>
              重试
            </button>
          )}
        </div>
      ) : s.mode === 'roundtable' ? (
        // 圆桌是群聊：没有“轮到谁”，只说清楚 AI 嘉宾什么时候接话
        <div className="turn-banner chat-banner">
          {room.aiBusy ? (
            <b>{room.aiBusy}</b>
          ) : room.aiPaused ? (
            <span>AI 嘉宾暂停接话，房主点名时才说</span>
          ) : (
            <span>想说就说，大家停下来几秒后 AI 嘉宾会接话</span>
          )}
        </div>
      ) : (
        <div className="turn-banner">
          {s.mode === 'debate' && t && (
            <span className={'dot team-' + t.role.team} aria-hidden="true" />
          )}
          {mine ? (
            <b>轮到你发言</b>
          ) : room.aiBusy ? (
            <b>{room.aiBusy}</b>
          ) : (
            <>
              轮到 <b>{speakerLabel(s)}</b>
            </>
          )}
          <span className="grow-right row">
            {room.turnDeadline > 0 && !room.aiBusy && <Countdown deadline={room.turnDeadline} />}
            {s.mode === 'debate' && (
              <span className="muted">
                {s.cursor + 1}/{s.turns.length}
              </span>
            )}
          </span>
        </div>
      )}
      <HintCard />
      <div className="tools scroll-x">
        {mine && (
          <button className="tool" disabled={busy} onClick={hint}>
            <Icon name="bulb" size={16} />
            给点提示
          </button>
        )}
        <button className="tool" disabled={busy} onClick={() => addReflection()}>
          <Icon name="note" size={16} />
          写个小结
        </button>
        {room.host && s.mode === 'roundtable' && (
          <button
            className="tool"
            disabled={!!room.aiBusy}
            onClick={() =>
              update(st => {
                st.ui.sheet = 'callon';
              })
            }
          >
            <Icon name="people" size={16} />
            点名
          </button>
        )}
        {room.host && humanSeat && !mine && !room.aiBusy && (
          <button className="tool" onClick={aiTakeover}>
            让 AI 代答
          </button>
        )}
        {room.host && s.mode === 'roundtable' && (
          <button className="tool" aria-pressed={!!room.aiPaused} onClick={toggleAiPause}>
            {room.aiPaused ? '恢复 AI 接话' : '暂停 AI 接话'}
          </button>
        )}
        {s.mode === 'debate' && (mine || room.host) && !room.aiBusy && (
          <button className="tool" onClick={passTurn}>
            {mine ? '这次先跳过' : '跳过当前发言'}
          </button>
        )}
        {s.mode === 'debate' && (
          <button
            className="tool"
            onClick={() =>
              update(st => {
                st.ui.sheet = 'seats';
              })
            }
          >
            <Icon name="people" size={16} />
            {mySeat(s) ? '换席位' : '上场辩论'}
          </button>
        )}
        {/* 快捷短语：只是提醒大家，不进讨论记录 */}
        <span className="tools-sep" aria-hidden="true" />
        {QUICK_MESSAGES.map(text => (
          <button key={text} className="tool quiet" onClick={() => quickMessage(text)}>
            {text}
          </button>
        ))}
      </div>
      {mine ? (
        <DraftInput
          s={s}
          placeholder={
            s.mode === 'debate'
              ? `${t?.action ?? '发言'}：我方认为……因为……`
              : '说点什么，随时可以插话……'
          }
        />
      ) : (
        <p className="lobby-note">
          {room.aiBusy ? '稍等，AI 正在发言。' : '轮到你时，这里会出现输入框。'}
        </p>
      )}
    </>
  );
}

// 辩论开始前：提醒每个人选好自己的席位
export function SeatPrompt({ s, primary = false }: { s: S; primary?: boolean }) {
  const seat = s.seats.find(x => x.id === mySeat(s));
  const open = () =>
    update(st => {
      st.ui.sheet = 'seats';
    });
  return seat ? (
    <button className="ghost block" onClick={open}>
      你是{seatName(seat)} · 换席位
    </button>
  ) : (
    <button className={(primary ? 'primary' : 'secondary') + ' block'} onClick={open}>
      选择阵营和席位
    </button>
  );
}
// 发言限时只是提醒：到点不打断，还可以把话说完
export function Countdown({ deadline }: { deadline: number }) {
  const [, tick] = useState(0);
  useEffect(() => {
    const id = setInterval(() => tick(n => n + 1), 1000);
    return () => clearInterval(id);
  }, []);
  const left = Math.max(0, Math.ceil((deadline - Date.now()) / 1000));
  const text = `${Math.floor(left / 60)}:${String(left % 60).padStart(2, '0')}`;
  return left ? (
    <span
      className={'turn-countdown' + (left <= 10 ? ' soon' : '')}
      aria-label={`这次发言还剩 ${left} 秒`}
    >
      {text}
    </span>
  ) : (
    <span className="turn-countdown expired">时间到</span>
  );
}
