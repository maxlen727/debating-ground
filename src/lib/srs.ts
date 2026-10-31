// 间隔复习：记得清楚就拉长间隔，模糊就明天再来
export const DAY = 86_400_000;
export const INTERVALS = [1, 3, 7, 14, 30, 60]; // 天

export function schedule(level: number, remembered: boolean, now = Date.now()) {
  const next = remembered ? Math.min(level + 1, INTERVALS.length - 1) : 0;
  return { level: next, due: now + INTERVALS[next] * DAY, days: INTERVALS[next] };
}

export function dueLabel(due: number, now = Date.now()) {
  const d = Math.ceil((due - now) / DAY);
  return d <= 0 ? '今天' : d === 1 ? '明天' : `${d} 天后`;
}
