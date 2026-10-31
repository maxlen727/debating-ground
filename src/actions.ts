// 学习流程的所有操作：准备 → 讨论 → 小结 → 复盘 → 知识库
import type { ChatMessage, ExplainKind, Previous, Review, SessionSnapshot, TermCard } from '../shared/types.ts';
import { getState, go, toast, uid, update } from './store.ts';
import type { Card, Session } from './store.ts';
import { runAI } from './lib/ai.ts';
import * as room from './lib/room.ts';
import { debateTurns, seatName, slots } from './lib/debate.ts';
import { DAY, schedule } from './lib/srs.ts';

const TONES = ['c', 'a', 'b'] as const;
const session = () => getState().session;

export function snapshot(s: Session): SessionSnapshot {
  return {
    mode: s.mode, concept: s.concept, topic: s.topic, keyPoints: s.keyPoints,
    roles: s.personas.map(r => ({ id: r.id, name: r.name, title: r.title, persona: r.persona })),
    messages: s.entries.flatMap((m): SessionSnapshot['messages'] => m.kind === 'msg' && !m.skipped ? [{ kind: 'msg', name: m.name, text: m.text, action: m.action }]
      : m.kind === 'reflect' && m.text ? [{ kind: 'reflect', name: '学习者', text: m.text }] : []),
    turns: s.mode === 'debate' ? s.turns : undefined, cursor: s.mode === 'debate' ? s.cursor : undefined,
    stages: s.mode === 'debate' ? s.stages : undefined, finished: s.finished
  };
}
const markCovered = (s: Session, list: number[] = []) => { for (const i of list) if (i >= 0 && i < s.keyPoints.length && !s.covered.includes(i)) s.covered.push(i); };
export const currentTurn = (s: Session | null) => s && s.mode === 'debate' && !s.finished ? s.turns[s.cursor] ?? null : null;

/* ---------- 准备 ---------- */
export async function prepare(previous: Previous | null = null) {
  const { config } = getState();
  const concept = config.concept.trim();
  if (!concept) return toast('先写下你想弄懂的概念。');
  const res = await runAI('prepare', { concept, mode: config.mode, previous }, '正在拆解要点、准备讨论');
  if (!res) return;
  const d = res.data;
  update(s => {
    s.plan = {
      mode: config.mode, concept: d.concept || concept, topic: d.topic, keyPoints: d.keyPoints, opening: d.opening,
      personas: d.roles.map((r, i) => ({ id: 'r' + i, tone: TONES[i % 3], name: r.name, title: r.title, persona: r.persona })),
      source: res.source, model: res.model, previous
    };
    s.ui.screen = 'prepare';
  });
}

function planProblem(): string {
  const { plan, config } = getState();
  if (!plan) return '还没有准备好讨论。';
  if (!plan.topic.trim()) return '请填写讨论题。';
  if (plan.keyPoints.map(k => k.trim()).filter(Boolean).length < 2) return '至少保留两个要点。';
  if (plan.mode === 'debate') {
    if (config.stages.some(st => !st.name.trim())) return '请为每个环节填写名称。';
    if (!slots(config).some(r => r.type === 'human')) return '至少安排一个“我”的席位，你才能参与交锋。';
  }
  return '';
}
function endOld() {
  const old = session();
  if (old?.room) room.leave();
  if (old && !old.finished && old.entries.some(m => m.kind === 'msg' && m.human)) toast('上一次讨论没有复盘，已结束。');
}

export function startSession() {
  const { plan, config } = getState();
  if (!plan) return;
  const problem = planProblem();
  if (problem) return toast(problem);
  const topic = plan.topic.trim();
  const keyPoints = plan.keyPoints.map(k => k.trim()).filter(Boolean).slice(0, 6);
  endOld();
  update(s => {
    const base: Session = {
      id: uid(), created: Date.now(), mode: plan.mode, concept: plan.concept, topic, keyPoints,
      covered: [], entries: [], learnerTurns: 0, nextRole: 0, demoStep: 0, finished: false, draft: '', previous: plan.previous,
      personas: [], seats: [], stages: [], turns: [], cursor: 0, review: null
    };
    if (plan.mode === 'debate') {
      base.seats = slots(config).map(r => ({ ...r, name: seatName(r) }));
      base.stages = structuredClone(config.stages);
      base.turns = debateTurns(config);
      base.entries.push({ id: uid(), kind: 'sys', text: `辩题：${topic}` });
    } else {
      base.personas = plan.personas;
      base.entries.push({ id: uid(), kind: 'sys', text: `讨论题：${topic}` });
      const first = plan.personas[0];
      base.entries.push({ id: uid(), kind: 'msg', who: first.id, name: first.name, text: plan.opening || '我们开始吧。你怎么理解这个概念？', source: plan.source, model: plan.model });
      base.nextRole = plan.personas.length > 1 ? 1 : 0;
    }
    s.session = base;
    s.ui = { ...s.ui, screen: 'session', hint: null, explain: null, sheet: null, error: '' };
  });
}

/* ---------- 多人讨论 ---------- */
export const canInvite = (mode: string) => mode === 'roundtable' || mode === 'debate';
export async function inviteFriends(name: string) {
  const { plan, config, ui } = getState();
  if (!plan || ui.busy) return;
  if (!canInvite(plan.mode)) return toast('多人讨论支持圆桌讨论和正反辩论。');
  if (config.aiMode !== 'remote' || !config.remote.url) return toast('多人讨论需要先在“设置”里连接你的后端。');
  const problem = planProblem();
  if (problem) return toast(problem);
  name = name.trim();
  if (!name) return toast('写下你在讨论里显示的名字。');
  update(st => { st.config.name = name; });
  endOld();
  await room.createRoom(room.setupFrom(plan, config), name);
}
export const joinRoom = (name: string) => name.trim() ? room.join(name.trim()) : toast('写下你在讨论里显示的名字。');
export const startRoom = () => room.send({ type: 'start' });
export const callOn = (target: NonNullable<import('../shared/room.ts').Speaker>) => { update(st => { st.ui.sheet = null; }); room.send({ type: 'callOn', target }); };
export const passTurn = () => room.send({ type: 'pass' });
export const aiTakeover = () => room.send({ type: 'aiTakeover' });
export const retryAI = () => room.send({ type: 'retry' });
export function leaveRoom() {
  room.leave();
  update(st => { st.session = null; st.ui.screen = 'learn'; });
}
room.setOnFinished(async () => {
  const s = session();
  if (s && (s.entries.some(m => m.kind === 'msg' && m.human) || s.room?.reflections.some(r => r.text))) await generateReview();
});

/* ---------- 讨论 ---------- */
function pushLearner(text: string) {
  update(st => {
    const s = st.session!;
    s.entries.push({ id: uid(), kind: 'msg', who: 'learner', name: '学习者', human: true, text, source: 'human' });
    s.learnerTurns++; s.draft = ''; st.ui.hint = null;
  });
}

export async function send(text: string) {
  const s = session(); text = text.trim();
  if (!s || s.finished || getState().ui.busy) return;
  if (!text) return toast('先写下你的想法，再发送。');
  if (s.room) {
    if (!room.mySpeakingTurn(s)) return toast('还没轮到你发言。');
    if (room.send({ type: 'say', text })) update(st => { st.session!.draft = ''; st.ui.hint = null; });
    return;
  }
  if (s.mode === 'debate') return submitSeat(text);
  pushLearner(text);
  const role = s.personas[s.nextRole % s.personas.length];
  if (s.personas.length > 1) update(st => { st.session!.nextRole = (s.nextRole + 1) % s.personas.length; });
  await speak(role.id);
  maybeReflect();
}

export async function speak(roleId: string, task?: string) {
  const s = session(); if (!s) return;
  const role = s.personas.find(r => r.id === roleId)!;
  const res = await runAI('discuss', { session: snapshot(s), roleId, task }, `${role.name} 正在思考`);
  if (!res || session()?.id !== s.id) return;
  update(st => {
    const ss = st.session!;
    ss.entries.push({ id: uid(), kind: 'msg', who: role.id, name: role.name, text: res.text, source: res.source, model: res.model, terms: res.terms?.length ? res.terms : undefined });
    markCovered(ss, res.covered);
  });
}

export function nextSpeaker() {
  const s = session(); if (!s || getState().ui.busy) return;
  const role = s.personas[s.nextRole % s.personas.length];
  update(st => { st.session!.nextRole = (s.nextRole + 1) % s.personas.length; });
  return speak(role.id, '主持人请你接着前面的发言说。');
}

export function submitSeat(text: string, skipped = false) {
  const s = session(); const t = currentTurn(s); if (!s || !t) return;
  if (s.room) { room.send(skipped ? { type: 'pass' } : { type: 'say', text }); return; }
  const human = t.role.type === 'human';
  update(st => {
    const ss = st.session!;
    ss.entries.push({ id: uid(), kind: 'msg', who: t.role.id, name: seatName(t.role) + (human ? '（学习者）' : ''), team: t.role.team, human: human && !skipped, text, stage: t.stage, action: t.action, target: t.target, skipped, source: human ? 'human' : 'demo' });
    ss.cursor++; ss.draft = ''; st.ui.hint = null;
    if (human && !skipped) ss.learnerTurns++;
  });
  if (session()!.cursor >= s.turns.length) return finish();
  maybeReflect();
}
export const skipSeat = () => submitSeat('主席宣布：本席放弃本次发言。', true);

export async function generateSeat() {
  const s = session(); const t = currentTurn(s); if (!s || !t || getState().ui.busy) return;
  if (s.room) { room.send({ type: 'aiTakeover' }); return; }
  const res = await runAI('debate', { session: snapshot(s) }, `${seatName(t.role)}正在组织发言`);
  const now = session();
  if (!res || now?.id !== s.id || now.cursor !== s.cursor || now.finished) return;
  update(st => {
    const ss = st.session!;
    ss.entries.push({ id: uid(), kind: 'msg', who: t.role.id, name: seatName(t.role), team: t.role.team, text: res.text, stage: t.stage, action: t.action, target: t.target, source: res.source, model: res.model, terms: res.terms?.length ? res.terms : undefined });
    markCovered(ss, res.covered);
    ss.cursor++;
  });
  if (session()!.cursor >= s.turns.length) return finish();
}

/* ---------- 快捷追问、提示、小结 ---------- */
export const QUICK: { kind: ExplainKind | 'disagree'; label: string }[] = [
  { kind: 'unclear', label: '没听懂' }, { kind: 'example', label: '举个例子' }, { kind: 'disagree', label: '我不同意' }, { kind: 'fact', label: '这是事实还是观点？' }
];

export async function quick(kind: ExplainKind | 'disagree', messageId: string) {
  const s = session(); if (!s || getState().ui.busy) return;
  const m = s.entries.find((x): x is ChatMessage => x.id === messageId && x.kind === 'msg'); if (!m) return;
  update(st => { st.ui.sheet = null; });
  const quote = m.text.length > 26 ? m.text.slice(0, 26) + '…' : m.text;
  if (kind === 'disagree') {
    if (s.room && !room.mySpeakingTurn(s)) return toast('轮到你发言时，再在发言里回应这一点。');
    if (!s.room && s.mode === 'debate' && currentTurn(s)?.role.type !== 'human') return toast('轮到你的席位时，再在发言里反驳这一点。');
    update(st => { st.session!.draft = `我不同意「${quote}」，因为`; });
    globalThis.requestAnimationFrame?.(() => { const d = document.getElementById('draft') as HTMLTextAreaElement | null; d?.focus(); d?.setSelectionRange(d.value.length, d.value.length); });
    return;
  }
  if (s.room) {
    // 多人讨论不打断别人的轮次：解释只给自己看
    const label = QUICK.find(q => q.kind === kind)!.label;
    update(st => { st.ui.explain = { kind, label, quote, text: '' }; });
    const res = await room.ask<{ text: string }>({ type: 'ask', purpose: 'explain', entryId: m.id, kind }, '讲解助手正在看这句话');
    update(st => { if (res && st.ui.explain) st.ui.explain.text = res.text; else st.ui.explain = null; });
    return;
  }
  if (s.mode !== 'debate') {
    const text = { unclear: `「${quote}」这句我没听懂，能换个说法吗？`, example: `能针对「${quote}」举一个具体例子吗？`, fact: `「${quote}」这是事实还是观点？依据是什么？` }[kind];
    pushLearner(text);
    const role = s.personas.find(r => r.id === m.who) ?? s.personas[0];
    await speak(role.id);
    maybeReflect();
    return;
  }
  // 辩论模式不打断赛程：解释放在一旁，不进入比赛记录
  const label = QUICK.find(q => q.kind === kind)!.label;
  update(st => { st.ui.explain = { kind, label, quote, text: '' }; });
  const res = await runAI('explain', { session: snapshot(s), quote: m.text.slice(0, 4000), kind }, '讲解助手正在看这句话');
  update(st => { if (res && st.ui.explain) st.ui.explain.text = res.text; else st.ui.explain = null; });
}

export async function hint() {
  const s = session(); if (!s || getState().ui.busy) return;
  if (s.room) {
    const res = await room.ask<{ text: string }>({ type: 'ask', purpose: 'hint' }, '正在想一条提示');
    if (res) update(st => { st.ui.hint = res.text; });
    return;
  }
  const t = currentTurn(s);
  const res = await runAI('hint', { session: snapshot(s), covered: s.covered, task: t ? `${seatName(t.role)}要进行${t.action}` : undefined }, '正在想一条提示');
  if (res) update(st => { st.ui.hint = res.text; });
}

function maybeReflect() {
  const s = session();
  if (s && !s.finished && [3, 6].includes(s.learnerTurns)) addReflection(true);
}
export function addReflection(auto = false) {
  const s = session(); if (!s) return;
  if (s.entries.some(m => m.kind === 'reflect' && !m.text)) { if (!auto) toast('先完成上面那张小结卡。'); return; }
  update(st => {
    const card = { id: uid(), kind: 'reflect' as const, text: '', feedback: '', draft: '' };
    st.session!.entries.push(card);
    // 多人讨论的小结只留在自己的设备上，记下它跟在哪条发言后面
    st.session!.room?.reflections.push({ ...card, after: s.entries.at(-1)?.id ?? '' });
  });
}
// 小结卡在 entries 里显示；多人讨论时 room.reflections 里还有一份，两份一起改
function editReflection(st: { session: Session | null }, id: string, fn: (m: { text: string; feedback: string; draft: string }) => void) {
  const m = st.session?.entries.find(x => x.id === id); if (m?.kind === 'reflect') fn(m);
  const r = st.session?.room?.reflections.find(x => x.id === id); if (r) fn(r);
}
export function setReflectionDraft(id: string, draft: string) { update(st => editReflection(st, id, m => { m.draft = draft; })); }
export function dropReflection(id: string) {
  update(st => {
    st.session!.entries = st.session!.entries.filter(x => x.id !== id);
    if (st.session!.room) st.session!.room.reflections = st.session!.room.reflections.filter(x => x.id !== id);
  });
}
export async function submitReflection(id: string) {
  const s = session(); if (!s || getState().ui.busy) return;
  const m = s.entries.find(x => x.id === id); if (m?.kind !== 'reflect') return;
  const answer = m.draft.trim(); if (!answer) return toast('用一两句话写下你的理解。');
  if (s.room) {
    const res = await room.ask<{ text: string }>({ type: 'ask', purpose: 'reflect', answer }, '正在看你的小结');
    if (res) update(st => editReflection(st, id, e => { e.text = answer; e.feedback = res.text; }));
    return;
  }
  const res = await runAI('reflect', { session: snapshot(s), answer }, '正在看你的小结');
  if (!res) return;
  update(st => {
    const e = st.session!.entries.find(x => x.id === id);
    if (e?.kind === 'reflect') { e.text = answer; e.feedback = res.text; }
    markCovered(st.session!, res.covered);
  });
}

/* ---------- 术语小卡片 ---------- */
// 讨论里 AI 标出的陌生词：轻点查看释义。查过的词记在这次讨论里，复盘时存进知识卡片。
export async function openTerm(term: string, messageId: string) {
  const s = session(); if (!s) return;
  update(st => { st.ui.sheet = null; });
  const known = s.glossary?.find(g => g.term === term);
  if (known) return update(st => { st.ui.term = { ...known, messageId }; });
  if (getState().ui.busy) return toast('稍等，上一个请求还没有完成。');
  const m = s.entries.find((x): x is ChatMessage => x.id === messageId && x.kind === 'msg');
  if (!m?.terms?.includes(term)) return;
  update(st => { st.ui.term = { term, definition: '', relation: '', messageId }; });
  const res = s.room
    ? await room.ask<{ data: TermCard }>({ type: 'ask', purpose: 'term', entryId: messageId, term }, `正在查「${term}」`)
    : await runAI('term', { session: snapshot(s), term, quote: m.text.slice(0, 400) }, `正在查「${term}」`);
  update(st => {
    if (!res) { st.ui.term = null; return; }
    const card = { ...res.data, term };
    if (st.ui.term?.term === term) st.ui.term = { ...card, messageId };
    const ss = st.session;
    if (ss?.id !== s.id) return;
    ss.glossary = [...(ss.glossary ?? []).filter(g => g.term !== term), card];
    // 已经复盘过的讨论，直接补进知识卡片
    const saved = st.library.find(c => c.sessionId === ss.id);
    if (saved) saved.terms = ss.glossary;
  });
}

/* ---------- 复盘与知识库 ---------- */
export async function finish() {
  if (getState().ui.busy) return;
  const r = session()?.room;
  if (r) { if (r.host) room.send({ type: 'finish' }); return; }
  update(st => { const s = st.session!; s.finished = true; s.draft = ''; st.ui.hint = null; st.ui.explain = null; st.ui.screen = 'review'; });
  const s = session()!;
  if (s.entries.some(m => (m.kind === 'msg' && m.human) || (m.kind === 'reflect' && m.text))) await generateReview();
}
export function resume() { if (session()?.room) return; update(st => { st.session!.finished = false; st.ui.screen = 'session'; }); }

export async function generateReview() {
  const s = session(); if (!s) return;
  const res = s.room
    ? await room.ask<{ data: Review; model?: string }>({ type: 'ask', purpose: 'review', reflections: s.room.reflections.filter(r => r.text).map(r => r.text) }, '正在复盘你在这场讨论里的理解').then(r => r && { ...r, source: 'api' as const })
    : await runAI('review', { session: snapshot(s) }, '正在复盘整场讨论');
  if (!res || session()?.id !== s.id) return;
  update(st => {
    const ss = st.session!;
    ss.review = { ...res.data, source: res.source, model: res.model };
    const existing = st.library.find(c => c.sessionId === ss.id);
    const card: Card = {
      id: existing?.id ?? uid(), sessionId: ss.id, concept: ss.concept, topic: ss.topic, mode: ss.mode, created: existing?.created ?? Date.now(),
      ...res.data, source: res.source, model: res.model, terms: ss.glossary?.length ? ss.glossary : existing?.terms,
      level: existing?.level ?? 0, due: existing?.due ?? Date.now() + DAY, reviews: existing?.reviews ?? []
    };
    if (existing) Object.assign(existing, card); else st.library.unshift(card);
  });
}

export function rateCard(id: string, remembered: boolean) {
  const c = getState().library.find(x => x.id === id); if (!c) return;
  const next = schedule(c.level, remembered);
  update(st => {
    const card = st.library.find(x => x.id === id)!;
    card.level = next.level; card.due = next.due; card.reviews.push({ at: Date.now(), remembered });
    delete st.ui.quiz['c:' + id];
    st.ui.openCard = null; st.ui.screen = 'library';
  });
  toast(`下次复习：${next.days} 天后`);
}
export const weakOf = (c: Card) => c.points.filter(p => p.status !== 'mastered').map(p => p.label).concat(c.misconceptions.map(m => m.said));
export function discussAgain(card: Card) {
  update(st => { st.config.concept = card.concept; st.config.mode = card.mode; });
  return prepare({ oneLine: card.oneLine, weak: weakOf(card) });
}
export function pickAnswer(key: string, q: number, o: number) { update(st => { st.ui.quiz[key] = { ...(st.ui.quiz[key] ?? {}), [q]: o }; }); }
export function openCard(id: string) { update(st => { st.ui.openCard = id; }); go('card'); }
