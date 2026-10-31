// 正反辩论的赛制：席位与发言顺序（沿用 v0.3 的简化赛制）
import type { Seat, SeatConfig, Stage, Team, Turn } from '../../shared/types.ts';

export interface DebateConfig { counts: Record<Team, number>; seats: Record<Team, SeatConfig[]>; stages: Stage[] }

export function slots(c: DebateConfig): Seat[] {
  const rows = Math.max(c.counts.A, c.counts.B);
  return Array.from({ length: rows }, (_, i) =>
    (['A', 'B'] as const).filter(t => i < c.counts[t]).map(t => ({ id: t + (i + 1), team: t, index: i, ...c.seats[t][i] }))
  ).flat();
}

export function debateTurns(c: DebateConfig): Turn[] {
  const all = slots(c);
  const team = (t: Team) => all.filter(r => r.team === t);
  const at = (t: Team, i: number) => team(t)[Math.min(i, team(t).length - 1)];
  return c.stages.flatMap((s, stage) => Array.from({ length: s.rounds }, (_, round) => {
    let turns: Omit<Turn, 'stage' | 'round'>[];
    if (s.kind === 'opening') turns = [{ role: at('A', 0), action: '立论' }, { role: at('B', 0), action: '立论' }];
    else if (s.kind === 'cross') turns = [
      { role: at('A', 1), action: '质询', target: at('B', 2).id }, { role: at('B', 2), action: '答辩', target: at('A', 1).id },
      { role: at('B', 1), action: '质询', target: at('A', 2).id }, { role: at('A', 2), action: '答辩', target: at('B', 1).id }];
    else if (s.kind === 'closing') turns = [{ role: team('B').at(-1)!, action: '总结陈词' }, { role: team('A').at(-1)!, action: '总结陈词' }];
    else turns = [{ role: team('A')[round % team('A').length], action: '自由辩论' }, { role: team('B')[round % team('B').length], action: '自由辩论' }];
    return turns.map(t => ({ ...t, stage, round }));
  }).flat());
}

export const seatName = (r: Pick<Seat, 'team' | 'index'>) => `${r.team === 'A' ? '正' : '反'}方 ${r.index + 1} 辩`;
