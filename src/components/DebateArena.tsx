// 辩论场：台面（双方席位 + 赛程条）、环节分隔、下一位的空讲台、双方阵容。
// 正方在左、反方在右，和真实的辩论赛场一样；只负责展示。
import type { Turn } from '../../shared/types.ts';
import { seatName } from '../lib/debate.ts';
import { seatOwner } from '../lib/room.ts';
import { update, type DebateSeat, type Session } from '../store.ts';
import { Sheet } from './ui.tsx';
import { useEffect } from 'react';
import { flash } from '../lib/light.ts';

const SIDE = { A: '正方', B: '反方' } as const;
const STANCE = { A: '支持', B: '反对' } as const;

// 这个席位现在由谁说：我 / 同学名字 / AI / 空着（多人房间里留给同学的席位）
export function seatWho(s: Session, seat: DebateSeat): { short: string; long: string } {
  const room = s.room;
  if (room) {
    const owner = seatOwner(s, seat.id);
    if (owner) return owner.id === room.memberId ? { short: '我', long: '你' } : { short: owner.name.slice(0, 1), long: owner.name };
    return seat.type === 'ai' ? { short: 'AI', long: 'AI' } : { short: '空', long: '等同学入座' };
  }
  return seat.type === 'human' ? { short: '我', long: '你' } : { short: 'AI', long: 'AI' };
}

// 台面：左右是双方席位，中间是当前环节和赛程条（每一格是一次发言，按环节分组）
export function DebateBoard({ s }: { s: Session }) {
  const turn: Turn | undefined = s.turns[s.cursor];
  const over = s.finished || s.awaitingReview || !turn;
  const stage = turn ? s.stages[turn.stage] : undefined;
  // 轮到下一位时，他的席位牌亮一下，光带上阵营色
  useEffect(() => {
    if (over || !turn) return;
    const t = setTimeout(() => flash(document.querySelector('.seat-tok.on'), { color: turn.role.team === 'A' ? 'var(--a-fg)' : 'var(--b-fg)', radius: 150, strength: 0.8 }), 200);
    return () => clearTimeout(t);
  }, [s.cursor, over]);
  const groups = s.stages.map((_, i) => s.turns.map((t, k) => ({ t, k })).filter(x => x.t.stage === i)).filter(g => g.length);
  const side = (team: 'A' | 'B') => (
    <div className={'board-side side-' + team}>
      <span className="board-name">{SIDE[team]}<small>{STANCE[team]}</small></span>
      <div className="seat-toks">
        {s.seats.filter(x => x.team === team).map(seat => {
          const who = seatWho(s, seat);
          const on = !over && turn?.role.id === seat.id;
          return <span key={seat.id} className={'seat-tok' + (on ? ' on' : '') + (who.short === '我' ? ' mine' : '')} title={`${seatName(seat)} · ${who.long}`}>{who.short}<i>{seat.index + 1}</i></span>;
        })}
      </div>
    </div>
  );
  return (
    <button className="board" aria-label={over ? '比赛结束，查看双方阵容' : `${stage?.name ?? ''}，第 ${s.cursor + 1} 次发言，共 ${s.turns.length} 次。查看双方阵容`} onClick={() => update(st => { st.ui.sheet = 'roster'; })}>
      {side('A')}
      <div className="board-mid">
        <b>{over ? '比赛结束' : stage?.name}</b>
        <small>{over ? `共 ${s.turns.length} 次发言` : `${s.cursor + 1} / ${s.turns.length}`}</small>
      </div>
      {side('B')}
      <span className="track" aria-hidden="true">
        {groups.map((g, i) => (
          <span key={i} className="track-stage">
            {g.map(({ t, k }) => <i key={k} className={'side-' + t.role.team + (k < s.cursor || over ? ' done' : '') + (k === s.cursor && !over ? ' now' : '')} />)}
          </span>
        ))}
      </span>
    </button>
  );
}

// 环节之间的分隔线
export const StageDivider = ({ name }: { name: string }) => <div className="stage-div" role="separator"><span>{name}</span></div>;

// 下一位的空讲台：站在哪一侧、谁来说、做什么
export function NextLectern({ s, waiting }: { s: Session; waiting: string }) {
  const t = s.turns[s.cursor];
  const seat = t && s.seats.find(x => x.id === t.role.id);
  if (!t || !seat || s.finished || s.awaitingReview) return null;
  const target = t.target ? s.seats.find(x => x.id === t.target) : undefined;
  return (
    <div className={'lectern-next side-' + seat.team}>
      <span className="lectern-who">{seatName(seat)} · {seatWho(s, seat).long}</span>
      <span className="lectern-act">{t.action}{target ? ` → ${seatName(target)}` : ''} · {waiting}</span>
    </div>
  );
}

// 双方阵容：每个席位由谁担任、认知背景是什么
export function RosterSheet({ s }: { s: Session }) {
  return (
    <Sheet title="双方阵容" onClose={() => update(st => { st.ui.sheet = null; })}>
      <div className="roster">
        {(['A', 'B'] as const).map(team => (
          <section key={team} className={'roster-side side-' + team}>
            <h3>{SIDE[team]} · {STANCE[team]}</h3>
            {s.seats.filter(x => x.team === team).map(seat => (
              <div key={seat.id} className="roster-seat">
                <b>{seatName(seat)}<small> · {seatWho(s, seat).long}</small></b>
                {seat.type !== 'human' && seat.bg && <p>{seat.bg}</p>}
              </div>
            ))}
          </section>
        ))}
      </div>
    </Sheet>
  );
}
