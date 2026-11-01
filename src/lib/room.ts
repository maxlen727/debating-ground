// 多人讨论：连接房间、同步状态、发送操作。
// 房间状态以后端为准：这里只把收到的状态映射成本地的 Session，发言和跳过都交给后端判断。
import type { ClientMessage, CreatedRoom, RoomSetup, RoomView, ServerMessage, Speaker } from '../../shared/room.ts';
import type { ChatMessage, Entry } from '../../shared/types.ts';
import { getState, toast, uid, update } from '../store.ts';
import type { Plan, RoomLink, Session } from '../store.ts';
import type { DebateConfig } from './debate.ts';
import { apiRequest } from './api.ts';
import { task } from './ai.ts';
import { clearLive, getLive, setLive, updateLive } from './stream.ts';
import { debateTurns, seatName, slots } from './debate.ts';
import { pushAudio, voiceFailed } from './voice.ts';
import { heard, setRoomListenSender } from './listen.ts';

/* ---------- 邀请链接 ---------- */
// 邀请码放在 # 后面：浏览器不会把它发给托管界面的网站，打开后立即从地址栏移除
const b64 = (s: string) => btoa(unescape(encodeURIComponent(s))).replace(/\+/g, '-').replace(/\//g, '_').replace(/=+$/, '');
const unb64 = (s: string) => decodeURIComponent(escape(atob(s.replace(/-/g, '+').replace(/_/g, '/'))));

export function inviteLink(link: Pick<RoomLink, 'backend' | 'roomId' | 'invite'>, base = location.origin + location.pathname) {
  return `${base}#join=${b64(JSON.stringify({ b: link.backend, r: link.roomId, c: link.invite }))}`;
}
export function parseInvite(hash: string): { backend: string; roomId: string; invite: string } | null {
  const m = hash.match(/^#join=([A-Za-z0-9_-]+)$/);
  if (!m) return null;
  try {
    const v = JSON.parse(unb64(m[1]));
    const u = new URL(v.b);
    const local = ['localhost', '127.0.0.1', '[::1]'].includes(u.hostname);
    if ((u.protocol !== 'https:' && !(u.protocol === 'http:' && local)) || typeof v.r !== 'string' || typeof v.c !== 'string') return null;
    return { backend: u.origin + u.pathname.replace(/\/+$/, ''), roomId: v.r, invite: v.c };
  } catch { return null; }
}
export function readInviteFromLocation() {
  if (typeof location === 'undefined') return;
  const join = parseInvite(location.hash);
  if (!location.hash.startsWith('#join=')) return;
  history.replaceState(null, '', location.pathname + location.search);
  if (!join) return toast('邀请链接不完整，请让房主重新发一次。');
  const s = getState().session;
  if (s?.room?.roomId === join.roomId && !s.room.closed) { update(st => { st.ui.screen = 'session'; }); return connect(); }
  update(st => { st.ui.join = join; st.ui.screen = 'join'; });
}

/* ---------- 把房间状态映射成本地讨论 ---------- */
export function toSession(view: RoomView, prev: Session | null, link: Omit<RoomLink, 'status' | 'members' | 'speaker' | 'aiBusy' | 'error' | 'expiresAt' | 'turnSeconds' | 'turnDeadline' | 'voiceAvailable' | 'listenAvailable'>): Session {
  const me = link.memberId;
  const same = prev?.room?.roomId === view.id ? prev : null;
  // 只有自己的发言算“我”的；同学的发言保留 memberId，界面上显示为同学
  const entries: Entry[] = view.entries.map(e => e.kind === 'msg' ? { ...e, human: e.memberId === me && !e.skipped } : e);
  for (const r of link.reflections) {
    const at = entries.findIndex(e => e.id === r.after);
    const { after: _after, ...card } = r;
    entries.splice(at < 0 ? entries.length : at + 1, 0, card);
  }
  const { setup } = view;
  return {
    id: 'room-' + view.id, created: same?.created ?? Date.now(), mode: setup.mode, concept: setup.concept, topic: setup.topic, keyPoints: setup.keyPoints,
    covered: view.covered, entries,
    learnerTurns: view.entries.filter(e => e.kind === 'msg' && e.memberId === me && !e.skipped).length,
    nextRole: 0, demoStep: 0, finished: view.status === 'finished', draft: same?.draft ?? '', previous: null,
    personas: setup.personas, seats: setup.seats, stages: setup.stages, turns: setup.turns, cursor: view.cursor,
    review: same?.review ?? null, glossary: same?.glossary, proposedPoints: view.proposals ?? [], context: setup.context,
    room: { ...link, status: view.status, members: view.members, speaker: view.speaker, aiBusy: view.aiBusy, error: view.error, expiresAt: view.expiresAt,
      turnSeconds: setup.turnSeconds ?? 0, turnDeadline: deadlineOf(view), voiceAvailable: !!view.voiceAvailable, listenAvailable: !!view.listenAvailable }
  };
}

// 倒计时的截止时间换算成这台设备的时钟：后端时间和手机时间可能差好几秒甚至几分钟
export function deadlineOf(view: RoomView, localNow = Date.now()): number {
  const seconds = view.setup.turnSeconds ?? 0;
  if (!seconds || !view.turnStartedAt || view.status !== 'live' || view.now === undefined) return 0;
  return localNow + (view.turnStartedAt + seconds * 1000 - view.now);
}

// 现在能不能由我发言
export function mySpeakingTurn(s: Session | null): boolean {
  const room = s?.room;
  if (!s || !room || room.status !== 'live' || room.aiBusy || room.error) return false;
  if (s.mode === 'roundtable') return room.speaker?.kind === 'member' && room.speaker.id === room.memberId;
  const t = s.turns[s.cursor];
  const mine = room.members.find(m => m.id === room.memberId)?.seat;
  return !!t && !!mine && t.role.id === mine;
}
// 辩论席位现在由谁坐：没人坐的席位由 AI 担任
export const seatOwner = (s: Session, seat: string) => s.room?.members.find(m => m.seat === seat);
export const mySeat = (s: Session) => s.room?.members.find(m => m.id === s.room!.memberId)?.seat;
export function speakerLabel(s: Session): string {
  const room = s.room!;
  if (room.aiBusy) return room.aiBusy;
  if (s.mode === 'debate') {
    const t = s.turns[s.cursor];
    if (!t) return '';
    const owner = room.members.find(m => m.seat === t.role.id);
    const who = owner ? (owner.id === room.memberId ? '你' : owner.name) : t.role.type === 'ai' ? 'AI' : '等同学入座';
    return `${seatName(t.role)}（${who}）· ${t.action}`;
  }
  const sp: Speaker = room.speaker;
  if (!sp) return '';
  if (sp.kind === 'ai') return s.personas.find(p => p.id === sp.id)?.name ?? 'AI 嘉宾';
  return sp.id === room.memberId ? '你' : room.members.find(m => m.id === sp.id)?.name ?? '同学';
}

/* ---------- 连接 ---------- */
let socket: WebSocket | null = null;
let retryTimer: ReturnType<typeof setTimeout> | undefined;
let retries = 0;
let joinName = '';
const pending = new Map<string, { resolve: (v: unknown) => void; reject: (e: Error) => void }>();

const link = () => getState().session?.room ?? null;
export const wsURL = (backend: string, roomId: string) => backend.replace(/^http/, 'ws') + `/api/rooms/${roomId}/ws`;

function setLink(fn: (r: RoomLink) => void) { update(st => { if (st.session?.room) fn(st.session.room); }); }

export function connect(target?: { backend: string; roomId: string; invite: string }, name = '') {
  clearTimeout(retryTimer);
  socket?.close();
  const room = target ?? link();
  if (!room) return;
  joinName = name;
  const ws = new WebSocket(wsURL(room.backend, room.roomId));
  socket = ws;
  ws.onopen = () => {
    retries = 0;
    const r = link();
    const hello: ClientMessage = target ? { type: 'hello', invite: target.invite, name: joinName } : { type: 'resume', token: r!.token };
    ws.send(JSON.stringify(hello));
  };
  ws.onmessage = e => { if (socket === ws) handle(JSON.parse(String(e.data)) as ServerMessage, target); };
  ws.onclose = () => {
    if (socket !== ws) return;
    socket = null;
    for (const p of pending.values()) p.reject(new Error('和房间的连接断开了，请稍后重试。'));
    pending.clear();
    const r = link();
    if (target && !r?.connected) {
      // 还没加入成功就断开：多半是地址不对或后端没有更新
      if (getState().ui.screen === 'join') update(st => { st.ui.error = st.ui.error || '连不上这个房间。请确认邀请链接完整，或请房主检查后端是否已更新。'; });
      return;
    }
    if (!r || r.closed) return;
    setLink(x => { x.connected = false; });
    retryTimer = setTimeout(() => connect(), Math.min(10_000, 1000 * 2 ** retries++));
  };
}

function handle(msg: ServerMessage, target?: { backend: string; roomId: string; invite: string }) {
  if (msg.type === 'welcome') {
    const prev = getState().session;
    const base = prev?.room?.roomId === msg.room.id ? prev.room : null;
    const l = {
      backend: target?.backend ?? base!.backend, roomId: msg.room.id, token: msg.token, invite: base?.invite ?? '',
      memberId: msg.memberId, host: msg.room.members.find(m => m.id === msg.memberId)?.host ?? false,
      connected: true, closed: '', reflections: base?.reflections ?? []
    };
    update(st => {
      st.session = toSession(msg.room, st.session, l);
      if (st.ui.screen === 'join') { st.ui.screen = 'session'; st.ui.join = null; st.ui.error = ''; }
    });
    return;
  }
  if (msg.type === 'state') return applyView(msg.room);
  if (msg.type === 'heard') return heard(msg.event);
  if (msg.type === 'audio') return pushAudio(msg.entryId, msg.seq, msg.last, msg.audio, msg.mime);
  if (msg.type === 'quick') {
    const me = getState().session?.room?.memberId;
    return toast(msg.memberId === me ? `已提醒大家：${msg.text}` : `${msg.name}：${msg.text}`);
  }
  if (msg.type === 'stream') {
    const s = getState().session;
    if (!s?.room) return;
    if (getLive()?.id === msg.id) return updateLive(msg.id, msg.text);
    const who = s.mode === 'debate' ? s.turns[s.cursor]?.role.id : s.room.speaker?.id;
    setLive({ id: msg.id, kind: 'msg', label: s.room.aiBusy ?? '', text: msg.text, who });
    return;
  }
  if (msg.type === 'answer') {
    const p = pending.get(msg.reqId);
    pending.delete(msg.reqId);
    if (msg.ok) p?.resolve(msg.result); else p?.reject(new Error(msg.error));
    return;
  }
  if (msg.type === 'error') {
    // 旧版后端不认识新加的操作（例如选席位），只会回“不支持的操作”；告诉用户该怎么办
    voiceFailed();   // 朗读请求被拒（例如次数用完）时，按钮恢复成“朗读”
    if (msg.message === '不支持的操作。') return toast(getState().session?.room?.host ? '你的后端版本较旧，不支持这个操作。请按文档“怎么更新后端”更新后再试。' : '房主的后端版本较旧，不支持这个操作。请房主更新后端。');
    return toast(msg.message);
  }
  if (msg.type === 'closed') {
    clearTimeout(retryTimer);
    if (getState().ui.screen === 'join') { update(st => { st.ui.error = msg.reason; }); return; }
    setLink(r => { r.closed = msg.reason; r.connected = false; });
    toast(msg.reason);
  }
}

function applyView(view: RoomView) {
  const before = getState().session;
  if (!before?.room || before.room.roomId !== view.id) return;
  const wasFinished = before.finished;
  update(st => {
    const s = st.session!;
    const next = toSession(view, s, { ...s.room!, connected: true });
    // 自己发言满 3 次、6 次时，停下来写个小结（和单人模式一样）
    if (next.learnerTurns > s.learnerTurns && [3, 6].includes(next.learnerTurns) && !next.finished && !next.room!.reflections.some(r => !r.text)) {
      const last = view.entries.at(-1)?.id ?? '';
      next.room!.reflections.push({ id: uid(), kind: 'reflect', text: '', feedback: '', draft: '', after: last });
      Object.assign(next, toSession(view, s, { ...next.room! }));
    }
    st.session = next;
    if (next.finished && !wasFinished && st.ui.screen === 'session') st.ui.screen = 'review';
  });
  const now = getState().session!;
  // AI 发言写进记录（或失败）后，生成中的气泡交给正式发言接着显示
  const live = getLive();
  if (live?.kind === 'msg' && !view.aiBusy) clearLive(live.id);
  if (now.finished && !wasFinished) void onFinished?.();
}
// 讨论结束时由 actions 生成个人复盘（避免两个模块互相引用）
let onFinished: (() => Promise<void>) | null = null;
export const setOnFinished = (fn: () => Promise<void>) => { onFinished = fn; };

// 语音输入的录音帧经房间连接转发
setRoomListenSender(m => send({ type: 'listen', op: m.type, audio: m.type === 'audio' ? m.audio : undefined }, m.type === 'audio'));

// quiet：顺带发的状态（正在输入），连接断了就算了，不提示
export function send(msg: ClientMessage, quiet = false) {
  if (!socket || socket.readyState !== WebSocket.OPEN) { if (!quiet) toast('正在重新连接房间，请稍后再试。'); return false; }
  socket.send(JSON.stringify(msg));
  return true;
}

// 个人请求（提示、讲解、小结、复盘）：结果只发给自己
type Ask = Extract<ClientMessage, { type: 'ask' }>;
export type AskBody = Ask extends infer M ? M extends Ask ? Omit<M, 'reqId'> : never : never;
export function ask<T>(msg: AskBody, label: string): Promise<T | null> {
  return task(label, signal => new Promise<T>((resolve, reject) => {
    const reqId = uid();
    pending.set(reqId, { resolve: v => resolve(v as T), reject });
    signal.addEventListener('abort', () => { pending.delete(reqId); reject(new Error('已取消')); }, { once: true });
    if (!send({ ...msg, reqId } as ClientMessage)) { pending.delete(reqId); reject(new Error('和房间的连接断开了，请稍后重试。')); }
  }));
}

export function leave() {
  clearTimeout(retryTimer);
  const ws = socket; socket = null; ws?.close();
  pending.clear();
}

/* ---------- 房主创建房间 ---------- */
export function setupFrom(plan: Plan, config: DebateConfig & { remote: { url: string }; turnSeconds: number }): RoomSetup {
  const keyPoints = plan.keyPoints.map(k => k.trim()).filter(Boolean).slice(0, 6);
  const turnSeconds = [0, 30, 60, 90, 120].includes(config.turnSeconds) ? config.turnSeconds : 0;
  if (plan.mode === 'debate') {
    const seats = slots(config).map(r => ({ ...r, name: seatName(r) }));
    return { mode: 'debate', concept: plan.concept, topic: plan.topic.trim(), keyPoints, personas: [], opening: '', seats, stages: structuredClone(config.stages), turns: debateTurns(config), model: plan.model, turnSeconds, context: plan.context?.trim() || undefined };
  }
  return { mode: 'roundtable', concept: plan.concept, topic: plan.topic.trim(), keyPoints, personas: plan.personas, opening: plan.opening, seats: [], stages: [], turns: [], model: plan.model, turnSeconds, context: plan.context?.trim() || undefined };
}

export async function createRoom(setup: RoomSetup, hostName: string) {
  const backend = getState().config.remote.url;
  const created = await task('正在创建房间', signal => apiRequest<CreatedRoom>('/api/rooms', { method: 'POST', signal, body: JSON.stringify({ setup, hostName }) }, 'remote'));
  if (!created) {
    // v0.8 之前的后端不认识“同学”席位
    if (getState().ui.error.includes('辩论席位无效')) update(st => { st.ui.error = '你的后端版本较旧，不支持“同学”席位。请按文档“怎么更新后端”更新后再试。'; });
    return false;
  }
  leave();
  update(st => {
    // 先放一个占位的讨论，连上房间后用后端的状态替换
    const view: RoomView = { id: created.roomId, status: 'lobby', setup, members: [], entries: [], covered: [], cursor: 0, speaker: null, aiBusy: null, error: '', aiCalls: 0, expiresAt: created.expiresAt };
    st.session = toSession(view, null, { backend, roomId: created.roomId, token: created.hostToken, invite: created.invite, memberId: '', host: true, connected: false, closed: '', reflections: [] });
    st.ui = { ...st.ui, screen: 'session', invite: true, hint: null, explain: null, sheet: null, error: '' };
  });
  connect();
  return true;
}

export function join(name: string) {
  const target = getState().ui.join;
  if (!target) return;
  update(st => { st.ui.error = ''; st.config.name = name; });
  connect(target, name);
}

export const isPeer = (m: ChatMessage) => !!m.memberId && !m.human;
