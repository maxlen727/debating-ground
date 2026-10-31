// 学习流程的所有操作：准备 → 讨论 → 小结 → 复盘 → 知识库
import type { ChatMessage, ExplainKind, Previous, SessionSnapshot } from '../shared/types.ts';
import { getState, go, toast, uid, update } from './store.ts';
import type { Card, Session } from './store.ts';
import { runAI } from './lib/ai.ts';
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

export function startSession() {
  const { plan, config, session: old } = getState();
  if (!plan) return;
  const topic = plan.topic.trim();
  const keyPoints = plan.keyPoints.map(k => k.trim()).filter(Boolean).slice(0, 6);
  if (!topic) return toast('请填写讨论题。');
  if (keyPoints.length < 2) return toast('至少保留两个要点。');
  if (plan.mode === 'debate') {
    if (config.stages.some(st => !st.name.trim())) return toast('请为每个环节填写名称。');
    if (!slots(config).some(r => r.type === 'human')) return toast('至少安排一个“我”的席位，你才能参与交锋。');
  }
  if (old && !old.finished && old.entries.some(m => m.kind === 'msg' && m.human)) toast('上一次讨论没有复盘，已结束。');
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
    ss.entries.push({ id: uid(), kind: 'msg', who: role.id, name: role.name, text: res.text, source: res.source, model: res.model });
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
  const res = await runAI('debate', { session: snapshot(s) }, `${seatName(t.role)}正在组织发言`);
  const now = session();
  if (!res || now?.id !== s.id || now.cursor !== s.cursor || now.finished) return;
  update(st => {
    const ss = st.session!;
    ss.entries.push({ id: uid(), kind: 'msg', who: t.role.id, name: seatName(t.role), team: t.role.team, text: res.text, stage: t.stage, action: t.action, target: t.target, source: res.source, model: res.model });
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
    if (s.mode === 'debate' && currentTurn(s)?.role.type !== 'human') return toast('轮到你的席位时，再在发言里反驳这一点。');
    update(st => { st.session!.draft = `我不同意「${quote}」，因为`; });
    globalThis.requestAnimationFrame?.(() => { const d = document.getElementById('draft') as HTMLTextAreaElement | null; d?.focus(); d?.setSelectionRange(d.value.length, d.value.length); });
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
  update(st => { st.session!.entries.push({ id: uid(), kind: 'reflect', text: '', feedback: '', draft: '' }); });
}
export function setReflectionDraft(id: string, draft: string) {
  update(st => { const m = st.session?.entries.find(x => x.id === id); if (m?.kind === 'reflect') m.draft = draft; });
}
export function dropReflection(id: string) { update(st => { st.session!.entries = st.session!.entries.filter(x => x.id !== id); }); }
export async function submitReflection(id: string) {
  const s = session(); if (!s || getState().ui.busy) return;
  const m = s.entries.find(x => x.id === id); if (m?.kind !== 'reflect') return;
  const answer = m.draft.trim(); if (!answer) return toast('用一两句话写下你的理解。');
  const res = await runAI('reflect', { session: snapshot(s), answer }, '正在看你的小结');
  if (!res) return;
  update(st => {
    const e = st.session!.entries.find(x => x.id === id);
    if (e?.kind === 'reflect') { e.text = answer; e.feedback = res.text; }
    markCovered(st.session!, res.covered);
  });
}

/* ---------- 复盘与知识库 ---------- */
export async function finish() {
  if (getState().ui.busy) return;
  update(st => { const s = st.session!; s.finished = true; s.draft = ''; st.ui.hint = null; st.ui.explain = null; st.ui.screen = 'review'; });
  const s = session()!;
  if (s.entries.some(m => (m.kind === 'msg' && m.human) || (m.kind === 'reflect' && m.text))) await generateReview();
}
export function resume() { update(st => { st.session!.finished = false; st.ui.screen = 'session'; }); }

export async function generateReview() {
  const s = session(); if (!s) return;
  const res = await runAI('review', { session: snapshot(s) }, '正在复盘整场讨论');
  if (!res || session()?.id !== s.id) return;
  update(st => {
    const ss = st.session!;
    ss.review = { ...res.data, source: res.source, model: res.model };
    const existing = st.library.find(c => c.sessionId === ss.id);
    const card: Card = {
      id: existing?.id ?? uid(), sessionId: ss.id, concept: ss.concept, topic: ss.topic, mode: ss.mode, created: existing?.created ?? Date.now(),
      ...res.data, source: res.source, model: res.model,
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
