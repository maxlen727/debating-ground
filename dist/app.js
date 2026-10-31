// 思辨场 v0.4 · 用讨论把概念弄懂
// 页面：开始学习 → 讨论准备 → 讨论现场 → 理解复盘 → 知识库（间隔复习）
const $ = s => document.querySelector(s);
const esc = v => String(v ?? '').replace(/[&<>"']/g, c => ({ '&': '&amp;', '<': '&lt;', '>': '&gt;', '"': '&quot;', "'": '&#39;' }[c]));

/* ---------- 常量 ---------- */
const MODES = {
  socratic: { name: '苏格拉底追问', icon: '？', desc: 'AI 一直追问，你来回答。适合刚接触一个概念，检查自己哪里没懂。' },
  roundtable: { name: '圆桌讨论', icon: '◎', desc: '三位不同学科背景的 AI 各说各的视角，你来主持和追问。' },
  teach: { name: '反向讲解', icon: '✎', desc: '你当老师讲解，AI 扮演困惑的学生不断提问。检验你能不能讲清楚。' },
  debate: { name: '正反辩论', icon: '⇄', desc: '按赛制分正反方，自定义席位和流程，在交锋中逼出概念的关键点。' }
};
const CONCEPTS = ['机会成本', '光速不变原理', '贝叶斯定理', '边际效用', '认知失调', '梯度下降'];
const QUICK = [['unclear', '没听懂'], ['example', '举个例子'], ['disagree', '我不同意'], ['fact', '事实还是观点？']];
const INTERVALS = [1, 3, 7, 14, 30, 60]; // 复习间隔（天）
const DAY = 86400000;
const STORE = 'sibian-v2';
const stageKinds = { opening: '立论 · 双方一辩', cross: '质询 · 问答配对', free: '自由辩论 · 双方轮替', closing: '总结 · 双方末辩' };
const STATUS = { mastered: '已掌握', partial: '还模糊', missing: '没讲到' };

const defaultConfig = () => ({
  concept: '机会成本', mode: 'socratic', aiMode: 'demo',
  counts: { A: 2, B: 2 },
  roles: {
    A: [{ type: 'human', bg: '' }, { type: 'ai', bg: '经济学研究者，习惯用数据和模型说话。' }],
    B: [{ type: 'ai', bg: '中学老师，关注概念在日常生活里怎么被误用。' }, { type: 'ai', bg: '创业者，从真实的经营取舍出发。' }]
  },
  stages: [{ name: '开篇立论', kind: 'opening', rounds: 1 }, { name: '交叉质询', kind: 'cross', rounds: 1 }, { name: '自由辩论', kind: 'free', rounds: 2 }, { name: '总结陈词', kind: 'closing', rounds: 1 }]
});

/* ---------- 状态 ---------- */
let config = defaultConfig(), page = 'learn', plan = null, session = null, library = [];
let hint = null, explain = null, openCard = null, quizPick = {}, toastTimer;
try {
  const saved = JSON.parse(localStorage.getItem(STORE) || 'null');
  if (saved) {
    config = { ...defaultConfig(), ...saved.config };
    plan = saved.plan || null; session = saved.session || null;
    library = Array.isArray(saved.library) ? saved.library : [];
  }
} catch { }

function save() { try { localStorage.setItem(STORE, JSON.stringify({ config, plan, session, library })); } catch { toast('浏览器未能保存记录，请导出本次内容。'); } }
function toast(text) { $('#toast').textContent = text; $('#toast').classList.add('show'); clearTimeout(toastTimer); toastTimer = setTimeout(() => $('#toast').classList.remove('show'), 3200); }
function on(s, event, fn) { document.querySelectorAll(s).forEach(el => el.addEventListener(event, e => fn(el, e))); }
const uid = () => Date.now().toString(36) + Math.random().toString(36).slice(2, 6);
const wait = ms => new Promise(r => setTimeout(r, ms));
const dueCards = () => library.filter(c => c.due <= Date.now());
const isDebate = () => session?.mode === 'debate';

/* ---------- 辩论赛制（沿用 v0.3） ---------- */
function slots(c = config) { return Array.from({ length: Math.max(c.counts.A, c.counts.B) }, (_, i) => ['A', 'B'].filter(t => i < c.counts[t]).map(t => ({ id: t + (i + 1), team: t, index: i, ...c.roles[t][i] }))).flat(); }
function debateTurns(c) {
  const all = slots(c), team = t => all.filter(r => r.team === t), at = (t, i) => team(t)[Math.min(i, team(t).length - 1)];
  return c.stages.flatMap((s, stage) => Array.from({ length: s.rounds }, (_, round) => {
    const kind = s.kind || 'free'; let turns;
    if (kind === 'opening') turns = [{ role: at('A', 0), action: '立论' }, { role: at('B', 0), action: '立论' }];
    else if (kind === 'cross') turns = [{ role: at('A', 1), action: '质询', target: at('B', 2).id }, { role: at('B', 2), action: '答辩', target: at('A', 1).id }, { role: at('B', 1), action: '质询', target: at('A', 2).id }, { role: at('A', 2), action: '答辩', target: at('B', 1).id }];
    else if (kind === 'closing') turns = [{ role: team('B').at(-1), action: '总结陈词' }, { role: team('A').at(-1), action: '总结陈词' }];
    else turns = [{ role: team('A')[round % team('A').length], action: '自由辩论' }, { role: team('B')[round % team('B').length], action: '自由辩论' }];
    return turns.map(t => ({ ...t, stage, round }));
  }).flat());
}
function seatName(r) { return `${r.team === 'A' ? '正' : '反'}方 ${r.index + 1} 辩`; }
function currentTurn() { return isDebate() && !session.finished ? session.turns[session.cursor] : null; }

/* ---------- 演示内容（未连接 API 时使用） ---------- */
const DEMO = {
  costPrep(mode) {
    const roles = {
      socratic: [{ name: '苏珩', title: '追问者', persona: '温和，但不放过任何含糊的说法' }],
      roundtable: [{ name: '周衡', title: '经济学家', persona: '从稀缺和取舍看问题' }, { name: '沈知', title: '心理学家', persona: '关注人为什么常常忽略机会成本' }, { name: '顾远', title: '企业管理者', persona: '从真实的经营决策出发' }],
      teach: [{ name: '小满', title: '学生', persona: '高二学生，爱较真' }],
      debate: []
    }[mode];
    const topic = { socratic: '周末去打工赚 300 元，真正的代价是什么？', roundtable: '为什么人们总是低估自己选择的代价？', teach: '向一个高中生讲清楚什么是机会成本', debate: '上大学的最大成本是学费' }[mode];
    const opening = { socratic: '我们从一个具体场景开始吧。你周六去打工赚了 300 元，你觉得这个选择花掉了你什么？', roundtable: '我先抛砖引玉：经济学里没有免费的午餐，任何选择都有代价。主持人想先听哪个角度？', teach: '老师好！我听说过“机会成本”这个词，但它和普通的“成本”有啥区别啊？花钱不就是成本吗？', debate: '' }[mode];
    return { concept: '机会成本', topic, keyPoints: ['放弃的最佳选择', '只算次优那一项', '包含隐性代价', '区别于沉没成本', '用于比较决策'], roles, opening };
  },
  genericPrep(c, mode) {
    const roles = {
      socratic: [{ name: '苏珩', title: '追问者', persona: '温和，但不放过任何含糊的说法' }],
      roundtable: [{ name: '周衡', title: '理论研究者', persona: '关心定义是否严谨' }, { name: '沈知', title: '心理学家', persona: '关心人们为什么会误解它' }, { name: '顾远', title: '实践者', persona: '关心它在真实场景里怎么用' }],
      teach: [{ name: '小满', title: '学生', persona: '高二学生，爱较真' }],
      debate: []
    }[mode];
    const topic = { socratic: `「${c}」到底是什么？它和相近的概念有什么不同？`, roundtable: `「${c}」在不同领域里意味着同一件事吗？`, teach: `向一个高中生讲清楚「${c}」`, debate: `理解「${c}」的关键在于它的定义，而不在于它的应用` }[mode];
    const opening = { socratic: `先说说你的直觉：你觉得「${c}」大概在讲什么？`, roundtable: `我们今天聊「${c}」。主持人能先说说，你是在什么场合第一次遇到它的吗？`, teach: `老师好！「${c}」这个词我常听到，但一直不懂。它到底是什么意思呀？`, debate: '' }[mode];
    return { concept: c, topic, keyPoints: [`${c}的定义`, '它要解决什么问题', '一个典型例子', '常见的误解', '和相近概念的区别'], roles, opening };
  },
  costReplies: [
    ['你说到了“少玩了一天”，这很重要：代价不只是钱。那如果那天你本来可以去做家教赚 500 元，这时打工的代价又是什么？', [0, 2]],
    ['对，关键是你放弃的选项里最好的那一个。睡懒觉、打游戏、做家教都被放弃了，为什么只算做家教？', [1]],
    ['很好。再想一个问题：上个月你已经交了 200 元的健身卡，这笔钱该不该算进周六的选择里？', [3]],
    ['没错，已经花掉、拿不回来的钱不影响现在的选择。那你会怎样用机会成本来决定周六干什么？', [4]]
  ],
  genericReply(s, role, step) {
    const kp = s.keyPoints[step % s.keyPoints.length], c = s.concept;
    if (s.mode === 'socratic') return [`你说的有一部分是对的。那我们看看「${kp}」：你能用自己的话说说，并举一个例子吗？`, [step % s.keyPoints.length]];
    if (s.mode === 'teach') return [`老师，我还是没太懂「${kp}」。能举个生活里的例子吗？`, [step % s.keyPoints.length]];
    return [`从${role.title}的角度看，讨论「${c}」时最容易被忽略的是「${kp}」。主持人怎么看？`, [step % s.keyPoints.length]];
  },
  costReview: {
    oneLine: '你已经理解机会成本是“放弃的最好选择”，它和沉没成本的区别还需要再巩固。',
    points: [
      { label: '放弃的最佳选择', status: 'mastered', note: '能用打工和家教的例子说清楚。' },
      { label: '只算次优那一项', status: 'partial', note: '一开始把所有放弃的选项加在了一起。' },
      { label: '包含隐性代价', status: 'mastered', note: '主动提到了时间和休息。' },
      { label: '区别于沉没成本', status: 'partial', note: '知道健身卡不该算，但理由说得不完整。' },
      { label: '用于比较决策', status: 'missing', note: '还没有用它做过一次完整的选择比较。' }
    ],
    misconceptions: [{ said: '机会成本是所有放弃的选项加起来。', fix: '只算放弃的选项中价值最高的那一个，因为你一次只能选一个。' }],
    quiz: [
      { q: '小王周六可以打工赚 300 元、做家教赚 500 元或在家休息。他选择了打工，他的机会成本是？', options: ['300 元', '500 元', '800 元', '0 元'], answer: 1, why: '放弃的选项里价值最高的是家教的 500 元。' },
      { q: '已经付了 200 元、不能退的演唱会门票，当天你更想去加班。门票钱应该怎么看？', options: ['算作去加班的机会成本', '是沉没成本，不影响决策', '要加到演唱会的收益里', '必须去看才不亏'], answer: 1, why: '钱已经花了、拿不回来，选哪个都一样，所以不该影响现在的选择。' },
      { q: '下面哪一项最不可能是“读研三年”的机会成本？', options: ['三年工作的工资', '三年的工作经验', '本科四年的学费', '提前积累的人脉'], answer: 2, why: '本科学费已经付过，是沉没成本；其余都是读研期间放弃的东西。' }
    ]
  }
};
function demoDebateText(t) {
  const positive = t.role.team === 'A', action = t.action || '自由辩论', topic = session.topic, stance = positive ? '支持' : '反对';
  const opponent = session.messages.slice().reverse().find(m => m.kind === 'msg' && m.team && m.team !== t.role.team && !m.skipped);
  let text;
  if (action === '立论') text = `谢谢主席、各位评委、对方辩友。我方对“${topic}”持${stance}立场。判断标准应当是：先把「${session.concept}」的含义说清楚，再比较收益、代价与可替代方案。` + (positive ? '我方认为，应当区分可以约束的实施风险与主张本身的价值，不能仅以存在风险推出否定结论。' : '我方认为，不能从个别场景中的可行性直接推出普遍适用，支持方必须说明其必要条件和适用边界。');
  else if (action === '质询') text = '请问对方辩友：贵方结论成立的必要条件是什么？当该条件不满足时，贵方是否仍坚持同样的主张？';
  else if (action === '答辩') text = '回应对方质询：我方主张以明确的适用范围为前提，不主张无条件适用。边界之外的情形需要另行判断，不能直接否定边界以内的结论。';
  else if (action === '总结陈词') text = `谢谢主席。我方对本辩题的${stance}立场不变。核心分歧在于「${session.concept}」的适用条件、比较标准和举证责任。结论必须由相应论据支持，不能把可能性直接等同于必然性。谢谢。`;
  else text = '对方辩友，从一个案例推导普遍结论，需要说明案例具有何种代表性。我方质疑的正是这一推导，而非案例是否可能发生。请对方回应其适用范围。';
  if (opponent && action !== '立论' && action !== '总结陈词') text = '对方辩友刚才表示：“' + opponent.text.slice(0, 60) + (opponent.text.length > 60 ? '…' : '') + '”\n\n' + text;
  return text;
}
async function demoRun(purpose, payload) {
  await wait(450);
  const s = payload.session, cost = (payload.concept || s?.concept) === '机会成本';
  if (purpose === 'prepare') return { data: cost ? DEMO.costPrep(payload.mode) : DEMO.genericPrep(payload.concept, payload.mode) };
  if (purpose === 'discuss') {
    const role = session.roles.find(r => r.id === payload.roleId), step = session.demoStep++;
    const [text, covered] = cost && session.mode === 'socratic' ? DEMO.costReplies[step % DEMO.costReplies.length] : DEMO.genericReply(session, role, step);
    return { text, covered };
  }
  if (purpose === 'debate') { const step = session.demoStep++; return { text: demoDebateText(currentTurn()), covered: [step % session.keyPoints.length] }; }
  if (purpose === 'hint') { const left = session.keyPoints.filter((_, i) => !session.covered.includes(i)); return { text: left.length ? `还没讲到「${left[0]}」。试着先举一个你亲身经历过的例子，再说说它和这个要点有什么关系。` : '要点都讲到了。试着用一句话把它们串起来，看看能不能说给别人听懂。' }; }
  if (purpose === 'explain') return { text: { unclear: '演示模式：连接 API 后，这里会用更简单的话重新解释这句话。', example: '演示模式：连接 API 后，这里会给出一个能说明这句话的具体例子。', fact: '演示模式：连接 API 后，这里会分辨这句话里哪些是事实、哪些是观点。' }[payload.kind] };
  if (purpose === 'reflect') return cost ? { text: '说得不错：你抓住了“放弃的东西”这个核心。还缺一点：机会成本只算放弃的选项里最好的那一个，不是所有放弃的加起来。', covered: [0] } : { text: '演示模式：连接 API 后，这里会对照要点清单评价你的小结，指出说对的部分和最关键的遗漏。', covered: [] };
  if (purpose === 'review') {
    if (cost && session.mode !== 'debate') return { data: DEMO.costReview };
    const lit = session.keyPoints.filter((_, i) => session.covered.includes(i)).length;
    return { data: { oneLine: `规则式复盘：本次讨论点亮了 ${lit}/${session.keyPoints.length} 个要点。连接 API 后可获得针对你发言的具体评价和练习题。`, points: session.keyPoints.map((k, i) => ({ label: k, status: session.covered.includes(i) ? 'mastered' : 'missing', note: session.covered.includes(i) ? '讨论中点亮了这一点。' : '讨论中还没有讲到。' })), misconceptions: [], quiz: [] } };
  }
  return null;
}
// 统一入口：演示模式走本地模板，API 模式走本机服务
async function run(purpose, payload, label) {
  if (aiReal()) return callAI(purpose, payload, label);
  let out = null;
  await aiTask(label + '（演示）', () => demoRun(purpose, payload), r => { out = r && { ...r, source: 'demo' }; });
  return out;
}

/* ---------- 发送给模型的会话快照 ---------- */
function snapshot() {
  const s = session;
  return {
    mode: s.mode, concept: s.concept, topic: s.topic, keyPoints: s.keyPoints,
    roles: s.roles.map(r => ({ id: r.id, name: r.name, title: r.title, persona: r.persona })),
    messages: s.messages.filter(m => (m.kind === 'msg' || (m.kind === 'reflect' && m.text)) && !m.skipped).map(m => ({ kind: m.kind, name: m.name, text: m.text, action: m.action })),
    turns: s.turns, cursor: s.cursor, stages: s.stages, finished: s.finished
  };
}
function markCovered(list) { for (const i of list || []) if (!session.covered.includes(i) && i >= 0 && i < session.keyPoints.length) session.covered.push(i); }

/* ---------- 学习流程 ---------- */
async function prepare(previous) {
  const concept = config.concept.trim();
  if (!concept) return toast('先写下你想弄懂的概念。');
  if (concept.length > 80) return toast('概念名称请控制在 80 字以内。');
  const res = await run('prepare', { concept, mode: config.mode, previous: previous || null }, '正在准备讨论');
  if (!res) return;
  const d = res.data;
  plan = { mode: config.mode, concept: d.concept || concept, topic: d.topic, keyPoints: d.keyPoints, opening: d.opening,
    roles: (d.roles || []).map((r, i) => ({ id: 'r' + i, tone: ['c', 'a', 'b'][i % 3], name: r.name, title: r.title, persona: r.persona })),
    source: res.source, model: res.model, previous: previous || null };
  page = 'prepare'; save(); render();
}

function startSession() {
  if (!plan) return;
  readPlanForm();
  if (!plan.topic.trim()) return toast('请填写讨论题。');
  if (plan.keyPoints.length < 2) return toast('至少保留两个要点。');
  if (session && !session.finished && session.messages.some(m => m.kind === 'msg' && m.human)) archiveUnfinished();
  const base = { id: uid(), created: Date.now(), mode: plan.mode, concept: plan.concept, topic: plan.topic.trim(), keyPoints: plan.keyPoints, covered: [], messages: [], learnerTurns: 0, nextRole: 0, demoStep: 0, finished: false, review: null, draft: '', previous: plan.previous };
  hint = null; explain = null;
  if (plan.mode === 'debate') {
    if (config.stages.some(s => !s.name.trim())) return toast('请为每个环节填写名称。');
    if (!slots().some(r => r.type === 'human')) return toast('至少安排一个真人席位，你才能参与交锋。');
    const seats = slots();
    session = { ...base, stages: JSON.parse(JSON.stringify(config.stages)), turns: debateTurns(config), cursor: 0,
      roles: seats.map(r => ({ ...r, name: seatName(r) + (r.type === 'human' ? '（学习者）' : ''), title: r.type === 'human' ? '真人' : 'AI 辩手', persona: r.bg || '', tone: r.team === 'A' ? 'a' : 'b' })) };
    session.messages.push({ id: uid(), kind: 'sys', text: `辩题：${session.topic}` });
  } else {
    session = { ...base, roles: plan.roles };
    session.messages.push({ id: uid(), kind: 'sys', text: `讨论题：${session.topic}` });
    session.messages.push({ id: uid(), kind: 'msg', who: plan.roles[0].id, name: plan.roles[0].name, text: plan.opening || '我们开始吧。你怎么理解这个概念？', source: plan.source, model: plan.model });
    session.nextRole = plan.roles.length > 1 ? 1 : 0;
  }
  page = 'session'; save(); render();
}
function archiveUnfinished() { session.finished = true; }

async function sendLearner(text) {
  text = String(text || '').trim();
  if (!text) return toast('先写下你的想法，再发送。');
  if (aiBusy || session.finished) return;
  session.messages.push({ id: uid(), kind: 'msg', who: 'learner', name: '学习者', human: true, text, source: 'human' });
  session.learnerTurns++; session.draft = ''; hint = null; save();
  const roles = session.roles, role = roles[session.nextRole % roles.length];
  if (roles.length > 1) session.nextRole = (session.nextRole + 1) % roles.length;
  await aiSpeak(role);
  maybeReflect();
}
async function aiSpeak(role, task) {
  const id = session.id;
  const res = await run('discuss', { session: snapshot(), roleId: role.id, task }, `${role.name} 正在思考`);
  if (!res || session?.id !== id) return;
  session.messages.push({ id: uid(), kind: 'msg', who: role.id, name: role.name, text: res.text, source: res.source, model: res.model });
  markCovered(res.covered); save(); render();
}
function nextSpeaker() {
  const roles = session.roles, role = roles[session.nextRole % roles.length];
  session.nextRole = (session.nextRole + 1) % roles.length;
  aiSpeak(role, '主持人请你接着前面的发言说。');
}

// 辩论模式：真人席位提交，AI 席位生成
function submitSeat(text, skipped = false) {
  const t = currentTurn(); if (!t) return;
  text = String(text || '').trim();
  if (!text) return toast('先写下你的观点，再提交发言。');
  const human = t.role.type === 'human';
  session.messages.push({ id: uid(), kind: 'msg', who: t.role.id, name: seatName(t.role) + (human ? '（学习者）' : ''), team: t.role.team, human: human && !skipped, text, stage: t.stage, action: t.action, target: t.target, skipped, source: human ? 'human' : 'demo' });
  session.cursor++; session.draft = ''; hint = null;
  if (human && !skipped) session.learnerTurns++;
  if (session.cursor >= session.turns.length) { save(); return finishSession(); }
  save(); render(); maybeReflect();
}
async function generateSeat() {
  const t = currentTurn(); if (!t || aiBusy) return;
  const id = session.id, cursor = session.cursor;
  const res = await run('debate', { session: snapshot() }, `${seatName(t.role)}正在组织发言`);
  if (!res || session?.id !== id || session.cursor !== cursor || session.finished) return;
  session.messages.push({ id: uid(), kind: 'msg', who: t.role.id, name: seatName(t.role), team: t.role.team, text: res.text, stage: t.stage, action: t.action, target: t.target, source: res.source, model: res.model });
  markCovered(res.covered);
  session.cursor++;
  if (session.cursor >= session.turns.length) { save(); return finishSession(); }
  save(); render();
}

// 快捷追问
async function quickAction(kind, mid) {
  const m = session.messages.find(x => x.id === mid); if (!m || aiBusy) return;
  const quote = m.text.length > 26 ? m.text.slice(0, 26) + '…' : m.text;
  if (kind === 'disagree') {
    if (isDebate() && currentTurn()?.role.type !== 'human') return toast('轮到你的席位时，再在发言里反驳这一点。');
    session.draft = `我不同意「${quote}」，因为`; save(); render();
    const d = $('#draft'); if (d) { d.focus(); d.setSelectionRange(d.value.length, d.value.length); }
    return;
  }
  if (!isDebate()) {
    const text = { unclear: `「${quote}」这句我没听懂，能换个说法吗？`, example: `能针对「${quote}」举一个具体例子吗？`, fact: `「${quote}」这是事实还是观点？依据是什么？` }[kind];
    const role = session.roles.find(r => r.id === m.who) || session.roles[0];
    session.messages.push({ id: uid(), kind: 'msg', who: 'learner', name: '学习者', human: true, text, source: 'human' });
    session.learnerTurns++; save();
    await aiSpeak(role); maybeReflect();
    return;
  }
  // 辩论模式下不打断赛程：解释显示在旁边，不进入比赛记录
  const label = QUICK.find(q => q[0] === kind)[1];
  explain = { kind, label, quote, text: '' };
  const res = await run('explain', { session: snapshot(), quote: m.text.slice(0, 4000), kind }, '正在解释');
  if (res) { explain.text = res.text; } else explain = null;
  render();
}

async function getHint() {
  if (aiBusy) return;
  const t = currentTurn();
  const res = await run('hint', { session: snapshot(), covered: session.covered, task: t ? `${seatName(t.role)}要进行${t.action}` : undefined }, '正在想提示');
  if (res) { hint = res.text; render(); }
}

function maybeReflect() {
  if ([3, 6].includes(session.learnerTurns) && !session.finished) addReflect(true);
}
function addReflect(auto) {
  if (session.messages.some(m => m.kind === 'reflect' && !m.text)) return auto ? null : toast('先完成上面那张小结卡。');
  session.messages.push({ id: uid(), kind: 'reflect', text: '', feedback: '', draft: '' });
  save(); render();
}
async function submitReflect(mid) {
  const m = session.messages.find(x => x.id === mid); if (!m || aiBusy) return;
  const answer = (m.draft || '').trim();
  if (!answer) return toast('用一两句话写下你的理解。');
  const res = await run('reflect', { session: snapshot(), answer }, '正在看你的小结');
  if (!res) return;
  m.text = answer; m.feedback = res.text; markCovered(res.covered); save(); render();
}

/* ---------- 复盘与知识库 ---------- */
async function finishSession() {
  if (aiBusy) return;
  session.finished = true; session.draft = ''; hint = null; explain = null;
  page = 'review'; save(); render();
  if (session.messages.some(m => m.kind === 'msg' && m.human) || session.messages.some(m => m.kind === 'reflect' && m.text)) await generateReview();
}
async function generateReview() {
  const id = session.id;
  const res = await run('review', { session: snapshot() }, '正在复盘整场讨论');
  if (!res || session?.id !== id) return;
  session.review = { ...res.data, source: res.source, model: res.model };
  saveCard(); save(); render();
}
function saveCard() {
  const r = session.review, existing = library.find(c => c.sessionId === session.id);
  const card = { id: existing?.id || uid(), sessionId: session.id, concept: session.concept, topic: session.topic, mode: session.mode, created: existing?.created || Date.now(),
    oneLine: r.oneLine, points: r.points, misconceptions: r.misconceptions, quiz: r.quiz, source: r.source, model: r.model,
    level: existing?.level || 0, due: existing?.due || Date.now() + DAY, reviews: existing?.reviews || [] };
  if (existing) Object.assign(existing, card); else library.unshift(card);
}
function rateCard(id, remembered) {
  const c = library.find(x => x.id === id); if (!c) return;
  c.level = remembered ? Math.min(c.level + 1, INTERVALS.length - 1) : 0;
  c.due = Date.now() + INTERVALS[c.level] * DAY;
  c.reviews.push({ at: Date.now(), remembered });
  quizPick[id] = {};
  save(); toast(`下次复习：${INTERVALS[c.level]} 天后。`);
  openCard = null; page = 'library'; render();
}
function weakOf(card) { return card.points.filter(p => p.status !== 'mastered').map(p => p.label).concat(card.misconceptions.map(m => m.said)); }
function discussAgain(card) {
  config.concept = card.concept; config.mode = card.mode || config.mode; save();
  prepare({ oneLine: card.oneLine, weak: weakOf(card) });
}

/* ---------- 导出 ---------- */
function download(name, text) {
  const a = document.createElement('a'), url = URL.createObjectURL(new Blob([text], { type: 'text/markdown;charset=utf-8' }));
  a.href = url; a.download = name; a.click(); setTimeout(() => URL.revokeObjectURL(url), 1000);
}
function cardMarkdown(c) {
  return `## ${c.concept}\n\n讨论题：${c.topic}\n讨论方式：${MODES[c.mode]?.name || ''} · ${new Date(c.created).toLocaleDateString('zh-CN')}\n\n${c.oneLine}\n\n`
    + c.points.map(p => `- [${STATUS[p.status]}] ${p.label}：${p.note}`).join('\n')
    + (c.misconceptions.length ? '\n\n需要纠正的理解：\n' + c.misconceptions.map(m => `- ${m.said} → ${m.fix}`).join('\n') : '')
    + (c.quiz.length ? '\n\n练习题：\n' + c.quiz.map((q, i) => `${i + 1}. ${q.q}\n` + q.options.map((o, j) => `   ${'ABCD'[j]}. ${o}`).join('\n') + `\n   答案：${'ABCD'[q.answer]}。${q.why}`).join('\n') : '') + '\n';
}
function exportSession() {
  const s = session;
  const text = `# 思辨场讨论记录\n\n概念：${s.concept}\n讨论题：${s.topic}\n讨论方式：${MODES[s.mode].name}\n要点：${s.keyPoints.map((k, i) => (s.covered.includes(i) ? '●' : '○') + k).join(' ')}\n\n`
    + s.messages.filter(m => m.kind !== 'sys').map(m => m.kind === 'reflect'
      ? (m.text ? `### 小结\n\n${m.text}\n\n> ${m.feedback}\n` : '')
      : `### ${m.name}${m.action ? ' · ' + m.action : ''} · ${m.human ? '真人' : m.source === 'api' ? 'AI · ' + m.model : 'AI · 演示'}\n\n${m.text}\n`).join('\n')
    + (s.review ? '\n# 理解复盘\n\n' + cardMarkdown({ ...s.review, concept: s.concept, topic: s.topic, mode: s.mode, created: s.created }) : '');
  download(`思辨场-${s.concept}-讨论记录.md`, text);
}
function exportLibrary() { download('思辨场-知识库.md', '# 思辨场知识库\n\n' + library.map(cardMarkdown).join('\n---\n\n')); }

/* ---------- 页面 ---------- */
function shell(body) {
  const due = dueCards().length;
  const nav = [['learn', '◈', '开始学习'], ['session', '◎', '讨论现场'], ['library', '▤', '知识库'], ['connection', '⚙', 'AI 连接']];
  const crumb = { learn: '开始学习', prepare: '讨论准备', session: '讨论现场', review: '理解复盘', library: '知识库', card: '知识卡片', connection: 'AI 连接' }[page];
  return `<div class="shell"><aside class="sidebar"><div><div class="brand"><span class="logo">〞</span>思辨场</div><div class="brand-sub">THINK IN DIALOGUE</div></div>
  <nav class="nav" aria-label="主导航">${nav.map(([id, icon, title]) => `<button data-nav="${id}" class="${page === id || (id === 'library' && page === 'card') || (id === 'session' && page === 'review') || (id === 'learn' && page === 'prepare') ? 'active' : ''}"><span>${icon}</span><span>${title}</span>${id === 'library' && due ? `<em class="count">${due}</em>` : ''}</button>`).join('')}</nav>
  <div class="sidebar-note"><span class="status-dot"></span> 讨论、落盘、回顾<br><span style="font-size:10px;opacity:.7">自带 API · v0.4</span></div></aside>
  <main class="main"><header class="topbar"><span class="breadcrumb">思辨场 / ${crumb}</span><button class="badge" data-nav="connection">${aiBadge()}</button></header>
  <div class="content">${aiNotice()}${body}<footer class="footer"><span>思辨场 · 把概念聊明白</span><span>DISCUSS. REFLECT. REVIEW.</span></footer></div></main></div>`;
}

function learnPage() {
  const due = dueCards(), cont = session && !session.finished;
  return `<section class="hero"><div><div class="eyebrow">LEARN BY DISCUSSION</div><h1>你想真正<br>弄懂什么？</h1><p>写下一个概念，选一种讨论方式。讨论中讲清楚的要点会被点亮，结束后生成理解复盘，存进你的知识库。</p></div><div class="hero-art" aria-hidden="true"><span class="bubble">“</span><span class="bubble second">”</span></div></section>
  <div class="grid"><div class="stack">
  <section class="card"><div class="card-head"><h2><span class="step">01</span>写下概念</h2></div>
  <label for="concept">想弄懂的概念</label><input id="concept" maxlength="80" placeholder="例如：机会成本" value="${esc(config.concept)}">
  <div class="chips">${CONCEPTS.map(c => `<button class="chip" data-concept="${esc(c)}">${esc(c)}</button>`).join('')}</div>
  <p class="note">概念越具体越好。“贝叶斯定理”比“概率论”更适合一次讨论。</p></section>
  <section class="card"><div class="card-head"><h2><span class="step">02</span>选择讨论方式</h2></div>
  <div class="modes">${Object.entries(MODES).map(([k, m]) => `<button class="mode ${config.mode === k ? 'selected' : ''}" data-mode="${k}" aria-pressed="${config.mode === k}"><span class="mode-icon">${m.icon}</span><strong>${m.name}</strong><span>${m.desc}</span></button>`).join('')}</div></section>
  </div>
  <aside class="stack"><section class="card summary"><div class="eyebrow">NEXT</div><h2>准备这次讨论</h2>
  <div class="summary-line"><span>概念</span><strong>${esc(config.concept || '未填写')}</strong></div>
  <div class="summary-line"><span>讨论方式</span><strong>${MODES[config.mode].name}</strong></div>
  <div class="summary-line"><span>AI 来源</span><strong>${aiReal() ? '自带 API' : '演示模板'}</strong></div>
  <button class="primary" id="prepare">准备讨论 <span style="float:right">↗</span></button>
  ${cont ? `<button style="width:100%;margin-top:8px" data-nav="session">继续未完成的讨论 · ${esc(session.concept)}</button>` : ''}
  <p class="note">AI 会先拆出理解这个概念需要掌握的要点，并改写出一个适合讨论的题目。开始前你都可以修改。${aiReal() ? '' : '<br>当前为演示模式：“机会成本”有完整示例，其他概念使用通用模板。'}</p></section>
  <section class="card"><h3>今日待复习</h3>${due.length ? due.slice(0, 4).map(c => `<button class="due-item" data-card="${c.id}"><strong>${esc(c.concept)}</strong><small>${esc(c.oneLine).slice(0, 40)}</small></button>`).join('') + (due.length > 4 ? `<button class="small ghost" data-nav="library">还有 ${due.length - 4} 张 →</button>` : '') : `<p class="note" style="margin:0">${library.length ? '今天没有到期的卡片。' : '完成第一次讨论后，复盘会存成知识卡片，并按间隔提醒你回顾。'}</p>`}</section></aside></div>`;
}

function teamForm(t) {
  return `<div class="team ${t === 'B' ? 'negative' : ''}"><div class="team-title"><span>${t === 'A' ? '正方 · 支持立场' : '反方 · 反对立场'}</span><select aria-label="${t === 'A' ? '正' : '反'}方人数" data-count="${t}">${[1, 2, 3, 4].map(n => `<option ${config.counts[t] === n ? 'selected' : ''}>${n}</option>`).join('')}</select></div>
  ${config.roles[t].slice(0, config.counts[t]).map((r, i) => `<div class="person"><span class="avatar">${t}${i + 1}</span><select aria-label="${t}${i + 1}参与者" data-type="${t},${i}"><option value="human" ${r.type === 'human' ? 'selected' : ''}>真人（我）</option><option value="ai" ${r.type === 'ai' ? 'selected' : ''}>AI 辩手</option></select></div>${r.type === 'ai' ? `<details><summary>${t}${i + 1} 的认知背景</summary><textarea maxlength="500" class="background" aria-label="${t}${i + 1}认知背景" data-bg="${t},${i}">${esc(r.bg)}</textarea></details>` : ''}`).join('')}</div>`;
}
function stageEditor() {
  return `${config.stages.map((s, i) => `<div class="phase"><span class="phase-number">${String(i + 1).padStart(2, '0')}</span><input aria-label="环节${i + 1}名称" maxlength="20" value="${esc(s.name)}" data-stage="${i}"><select class="stage-kind" aria-label="环节${i + 1}规则" data-kind="${i}">${Object.entries(stageKinds).map(([k, label]) => `<option value="${k}" ${s.kind === k ? 'selected' : ''}>${label}</option>`).join('')}</select><select aria-label="环节${i + 1}轮次" data-round="${i}">${[1, 2, 3].map(n => `<option value="${n}" ${s.rounds === n ? 'selected' : ''}>${n} 轮</option>`).join('')}</select><div class="actions"><button class="small" data-up="${i}" aria-label="上移环节${i + 1}" ${i === 0 ? 'disabled' : ''}>↑</button><button class="small" data-remove="${i}" aria-label="删除环节${i + 1}" ${config.stages.length === 1 ? 'disabled' : ''}>×</button></div></div>`).join('')}
  <p class="note">简化赛制：双方一辩立论；二辩质询对方三辩（不足三席时取末席），问答成对；自由辩论按轮次交替；末辩总结，反方先行。</p>`;
}
function preparePage() {
  if (!plan) return `<div class="empty"><div class="empty-symbol">◈</div><h2>还没有准备好的讨论。</h2><button class="primary" data-nav="learn">去写下一个概念</button></div>`;
  const debate = plan.mode === 'debate';
  return `<div class="eyebrow">${esc(plan.concept)} · ${MODES[plan.mode].name}</div><h1 class="screen-title">开始前，看一眼这次要弄懂什么。</h1>
  <p class="muted">${plan.source === 'api' ? '以下内容由 ' + esc(plan.model) + ' 生成' : '以下内容来自演示模板'}，都可以直接修改。${plan.previous ? '这次会从上次还模糊的地方切入。' : ''}</p>
  <div class="grid"><div class="stack">
  <section class="card"><div class="card-head"><h2><span class="step">01</span>${debate ? '辩题' : '讨论题'}</h2></div>
  <textarea id="plan-topic" maxlength="200" aria-label="讨论题">${esc(plan.topic)}</textarea></section>
  <section class="card"><div class="card-head"><h2><span class="step">02</span>要点清单</h2><span class="muted" style="font-size:11px">每行一个，2 到 6 个</span></div>
  <textarea id="plan-points" aria-label="要点清单" style="min-height:150px">${esc(plan.keyPoints.join('\n'))}</textarea>
  <p class="note">讨论中讲清楚一个要点，它就会在讨论现场的顶部点亮。复盘也按这份清单逐条评估。</p></section>
  <section class="card"><div class="card-head"><h2><span class="step">03</span>${debate ? '编排双方席位' : plan.roles.length > 1 ? '参与讨论的嘉宾' : '你的讨论对象'}</h2></div>
  ${debate ? `<div class="two">${teamForm('A')}${teamForm('B')}</div><p class="note">至少安排一个真人席位。AI 辩手的认知背景会影响它的知识范围和表达方式。</p>`
    : plan.roles.map((r, i) => `<div class="role-edit"><span class="avatar tone-${r.tone}">${esc(r.name.slice(0, 1))}</span><div class="grow"><div class="two-tight"><input aria-label="角色${i + 1}名字" maxlength="20" data-role-name="${i}" value="${esc(r.name)}"><input aria-label="角色${i + 1}身份" maxlength="20" data-role-title="${i}" value="${esc(r.title)}"></div><textarea class="background" maxlength="300" aria-label="角色${i + 1}认知背景" data-role-persona="${i}">${esc(r.persona)}</textarea></div></div>`).join('')}</section>
  ${debate ? `<section class="card"><div class="card-head"><h2><span class="step">04</span>比赛流程</h2><button class="small" id="add-stage" ${config.stages.length >= 6 ? 'disabled' : ''}>＋ 添加环节</button></div>${stageEditor()}</section>` : ''}
  </div>
  <aside class="stack"><section class="card summary"><div class="eyebrow">READY</div><h2>${esc(plan.concept)}</h2>
  <div class="summary-line"><span>讨论方式</span><strong>${MODES[plan.mode].name}</strong></div>
  <div class="summary-line"><span>要点</span><strong>${plan.keyPoints.length} 个</strong></div>
  ${debate ? `<div class="summary-line"><span>发言次数</span><strong>${debateTurns(config).length} 次</strong></div>` : `<div class="summary-line"><span>角色</span><strong>${plan.roles.length} 位 AI</strong></div>`}
  <button class="primary" id="start">开始讨论 <span style="float:right">↗</span></button>
  <button style="width:100%;margin-top:8px" id="reprepare">重新准备</button>
  <button class="ghost small" style="width:100%;margin-top:4px" data-nav="learn">换一个概念</button></section></aside></div>`;
}
function readPlanForm() {
  if (!plan) return;
  const t = $('#plan-topic'); if (t) plan.topic = t.value;
  const p = $('#plan-points'); if (p) plan.keyPoints = p.value.split('\n').map(x => x.trim().slice(0, 40)).filter(Boolean).slice(0, 6);
  plan.roles.forEach((r, i) => {
    const n = $(`[data-role-name="${i}"]`), ti = $(`[data-role-title="${i}"]`), pe = $(`[data-role-persona="${i}"]`);
    if (n && n.value.trim()) r.name = n.value.trim(); if (ti) r.title = ti.value.trim(); if (pe) r.persona = pe.value.trim();
  });
}

function avatarFor(m) {
  if (m.human || m.who === 'learner') return `<span class="avatar tone-me">我</span>`;
  const r = session.roles.find(x => x.id === m.who);
  if (isDebate()) return `<span class="avatar ${m.team === 'B' ? 'neg' : ''}">${esc(m.who)}</span>`;
  return `<span class="avatar tone-${r?.tone || 'a'}">${esc((r?.name || 'AI').slice(0, 1))}</span>`;
}
function messageHTML(m) {
  if (m.kind === 'sys') return `<div class="sysline">${esc(m.text)}</div>`;
  if (m.kind === 'reflect') {
    return `<div class="reflect"><h3>停一下，用自己的话说说</h3>${m.text
      ? `<div class="mine">我的小结：${esc(m.text)}</div><div class="fb">${esc(m.feedback)}</div>`
      : `<p class="note" style="margin:0">你现在怎么理解「${esc(session.concept)}」？一两句话就好。</p><textarea data-reflect="${m.id}" maxlength="1000" aria-label="我的小结">${esc(m.draft)}</textarea><div class="row" style="justify-content:flex-end"><button class="small ghost" data-reflect-skip="${m.id}">跳过</button><button class="small primary" data-reflect-send="${m.id}">提交小结</button></div>`}</div>`;
  }
  const me = m.human, debate = isDebate();
  const meta = debate ? `<span>· ${esc(session.stages[m.stage]?.name || '')} · ${esc(m.action || '发言')}</span>${m.skipped ? '<span>已跳过</span>' : ''}` : (m.who !== 'learner' ? `<span>${esc(session.roles.find(r => r.id === m.who)?.title || '')}</span>` : '');
  const acts = !me && !m.skipped && !session.finished ? `<details class="acts"><summary>追问这句</summary><div class="row">${QUICK.map(([k, l]) => `<button class="small" data-quick="${k}" data-mid="${m.id}">${l}</button>`).join('')}</div></details>` : '';
  return `<article class="message ${me ? 'me' : ''} ${m.team === 'B' ? 'neg' : ''}">${avatarFor(m)}<div class="message-body"><div class="message-meta"><strong>${esc(me ? (debate ? m.name : '我') : m.name)}</strong><span>${messageSource(m)}</span>${meta}</div><div class="message-text">${esc(m.text)}</div>${acts}</div></article>`;
}
function progressBar() {
  return `<div class="points-bar" role="list" aria-label="要点进度"><span class="points-count">${session.covered.length}/${session.keyPoints.length}</span>${session.keyPoints.map((k, i) => `<span role="listitem" class="pt ${session.covered.includes(i) ? 'lit' : ''}">${esc(k)}</span>`).join('')}</div>`;
}
function sessionPage() {
  if (!session) return `<div class="empty"><div class="empty-symbol">◎</div><h2>选一个概念，开始一次讨论。</h2><p>讨论中讲清楚的要点会被点亮，结束后生成理解复盘。</p><button class="primary" data-nav="learn">开始学习</button></div>`;
  const debate = isDebate(), t = currentTurn();
  const roster = debate
    ? ['A', 'B'].map(team => `<div class="card"><h3>${team === 'A' ? '正方阵营' : '反方阵营'}</h3>${session.roles.filter(r => r.team === team).map(r => `<div class="person ${t?.role.id === r.id ? 'current' : ''}" title="${esc(r.persona)}"><span class="avatar ${team === 'B' ? 'neg' : ''}">${r.id}</span><div><div class="person-name">${seatName(r)}</div><div class="person-meta">${r.type === 'human' ? '真人 · 你' : 'AI 辩手'}</div></div></div>`).join('')}</div>`).join('')
    : `<div class="card"><h3>${session.roles.length > 1 ? '圆桌嘉宾' : '讨论对象'}</h3>${session.roles.map(r => `<div class="person" title="${esc(r.persona)}"><span class="avatar tone-${r.tone}">${esc(r.name.slice(0, 1))}</span><div><div class="person-name">${esc(r.name)}</div><div class="person-meta">${esc(r.title)}</div></div></div>`).join('')}</div>`;
  let composer;
  if (session.finished) composer = `<div class="composer row between"><span class="muted">这次讨论已结束。</span><button class="primary" data-nav="review">查看理解复盘 ↗</button></div>`;
  else {
    const humanTurn = !debate || t.role.type === 'human';
    const tools = `<div class="row tools">${humanTurn ? '<button class="small soft" id="hint">卡住了，给点提示</button>' : ''}<button class="small" id="add-reflect">写个小结</button>${!debate && session.roles.length > 1 ? '<button class="small" id="next-speaker">请下一位发言</button>' : ''}</div>`;
    const input = humanTurn
      ? `<textarea id="draft" maxlength="4000" placeholder="${debate ? '我方认为……因为……例如……' : session.mode === 'teach' ? '开始讲解……' : '说说你的想法……'}" aria-label="你的发言">${esc(session.draft || '')}</textarea><div class="row between"><small class="muted">Enter 发送 · Shift+Enter 换行</small><button class="primary" id="send">${debate ? '提交发言' : '发送'} ↑</button></div>`
      : `<p class="note">本席由 AI 辩手发言。${aiReal() ? '会调用你配置的模型。' : '当前使用演示模板。'}</p><button class="primary" id="generate" style="margin-top:12px">辩手发言 ↗</button>`;
    composer = `<div class="composer">${debate ? `<div class="row between"><span class="turn-label">● 轮到 ${seatName(t.role)} · ${esc(t.action)}${t.target ? ' → ' + esc(t.target) : ''}</span><small>进度 ${session.cursor + 1} / ${session.turns.length}</small></div>` : ''}
    ${hint ? `<div class="hintbox"><strong>思路提示</strong><p>${esc(hint)}</p><button class="small ghost" id="close-hint" aria-label="关闭提示">×</button></div>` : ''}
    ${tools}${input}
    <div class="row between" style="margin-top:14px">${debate ? '<button class="small ghost" id="skip">主席：跳过本席</button>' : '<span></span>'}<button class="small ghost" id="finish">结束并复盘 →</button></div></div>`;
  }
  return `<div class="arena-header"><div class="row between"><div class="eyebrow">${MODES[session.mode].name} · THE DIALOGUE ROOM</div><button class="small" id="export">↓ 导出记录</button></div>
  <h1>${esc(session.concept)}</h1><p class="muted topic-line">${esc(session.topic)}</p>${progressBar()}</div>
  <div class="arena-grid"><aside class="roster stack">${roster}<p class="note">点 AI 发言下面的“追问这句”，可以让它换个说法、举例子，或说明哪些是事实。</p></aside>
  <section class="card">${debate && !session.finished ? `<div class="notice">主席：现在进行${esc(session.stages[t.stage].name)}，请${seatName(t.role)}进行${esc(t.action)}${t.target ? '，对方席位为 ' + esc(t.target) : ''}。</div><div class="stagebar">${session.stages.map((s, i) => `<span class="stage-pill ${t.stage === i ? 'active' : ''}">${i + 1} · ${esc(s.name)}</span>`).join('')}</div>` : ''}
  <div class="transcript" id="transcript">${session.messages.map(messageHTML).join('')}</div>
  ${explain ? `<div class="explain"><div class="row between"><strong>${esc(explain.label)} · 讲解助手</strong><button class="small ghost" id="close-explain" aria-label="关闭讲解">×</button></div><blockquote>${esc(explain.quote)}</blockquote><p>${esc(explain.text || '正在解释…')}</p><small class="muted">讲解不进入比赛记录，也不占用发言轮次。</small></div>` : ''}
  ${composer}</section></div>`;
}

function quizHTML(key, quiz) {
  const picks = quizPick[key] || {};
  return `<div class="quiz">${quiz.map((q, qi) => {
    const picked = picks[qi];
    return `<div class="q"><p><strong>${qi + 1}. ${esc(q.q)}</strong></p>${q.options.map((o, oi) => `<button class="opt ${picked != null ? (oi === q.answer ? 'right' : oi === picked ? 'wrong' : '') : ''}" data-quiz="${esc(key)}" data-q="${qi}" data-o="${oi}" ${picked != null ? 'disabled' : ''}>${'ABCD'[oi]}. ${esc(o)}</button>`).join('')}${picked != null ? `<p class="why">${picked === q.answer ? '答对了。' : '正确答案是 ' + 'ABCD'[q.answer] + '。'}${esc(q.why)}</p>` : ''}</div>`;
  }).join('')}</div>`;
}
function reviewBody(r) {
  return `<p class="oneline">${esc(r.oneLine)}</p>
  <section class="card"><h2>要点掌握情况</h2><div class="plist">${r.points.map(p => `<div class="pitem"><span class="pill ${p.status}">${STATUS[p.status]}</span><strong>${esc(p.label)}</strong><span class="pnote">${esc(p.note)}</span></div>`).join('')}</div></section>
  ${r.misconceptions.length ? `<section class="card"><h2>需要纠正的理解</h2>${r.misconceptions.map(m => `<div class="mis"><div>${esc(m.said)}</div><div class="fix">→ ${esc(m.fix)}</div></div>`).join('')}</section>` : ''}`;
}
function reviewPage() {
  if (!session) { page = 'learn'; return learnPage(); }
  const r = session.review, hasInput = session.messages.some(m => (m.kind === 'msg' && m.human) || (m.kind === 'reflect' && m.text));
  const head = `<div class="eyebrow">REFLECT & REVIEW</div><h1 class="screen-title">${esc(session.concept)}：这次弄懂了多少</h1><p class="muted">${esc(session.topic)} · ${MODES[session.mode].name}</p>`;
  if (!r) return head + `<section class="card">${!hasInput ? '<p>这次讨论里还没有你的发言，没有可以复盘的内容。</p><button class="primary" id="resume">回到讨论</button>' : aiBusy ? '<p class="muted">正在复盘整场讨论……</p>' : `<p>复盘还没有生成。</p><button class="primary" id="gen-review">生成理解复盘</button>`}</section>`;
  return head + `<div class="notice">${r.source === 'api' ? '由 ' + esc(r.model) + ' 根据你的发言和小结生成，供参考。' : '演示模式的复盘：来自预设示例或规则统计。'} 这张复盘已存入知识库。</div>
  <div class="grid"><div class="stack">${reviewBody(r)}${r.quiz.length ? `<section class="card"><h2>检验一下</h2>${quizHTML('s:' + session.id, r.quiz)}</section>` : ''}</div>
  <aside class="stack"><section class="card summary"><h2>接下来</h2><div class="summary-line"><span>点亮要点</span><strong>${session.covered.length} / ${session.keyPoints.length}</strong></div><div class="summary-line"><span>第一次复习</span><strong>明天</strong></div>
  <button class="primary" id="again">从薄弱点再讨论一次 ↗</button><button style="width:100%;margin-top:8px" data-nav="library">打开知识库</button><button style="width:100%;margin-top:8px" data-nav="session">回看完整讨论</button>
  <button class="small" style="width:100%;margin-top:8px" id="export">↓ 导出讨论记录和复盘</button><p class="note">再讨论时，AI 会读取这次复盘里还模糊的要点，从那里开始追问。</p></section></aside></div>`;
}

function libraryPage() {
  const due = dueCards();
  const fmt = t => { const d = Math.round((t - Date.now()) / DAY); return d <= 0 ? '今天' : d === 1 ? '明天' : d + ' 天后'; };
  return `<div class="eyebrow">YOUR KNOWLEDGE</div><h1 class="screen-title">讨论过的，都在这里。</h1><p class="muted">${library.length} 张知识卡片 · ${due.length} 张今天待复习 · 只保存在此浏览器</p>
  ${library.length ? `<div class="row" style="margin-bottom:18px"><button id="export-lib">↓ 导出知识库</button></div><div class="cards">${library.map(c => {
    const m = c.points.filter(p => p.status === 'mastered').length;
    return `<button class="kcard ${c.due <= Date.now() ? 'due' : ''}" data-card="${c.id}"><span class="row between"><strong>${esc(c.concept)}</strong><small>${c.due <= Date.now() ? '待复习' : '下次 ' + fmt(c.due)}</small></span><span class="kline">${esc(c.oneLine)}</span><span class="meter" aria-label="已掌握 ${m}/${c.points.length}">${c.points.map(p => `<i class="${p.status}"></i>`).join('')}</span><small>${MODES[c.mode]?.name || ''} · ${new Date(c.created).toLocaleDateString('zh-CN')}</small></button>`;
  }).join('')}</div>` : `<section class="card"><div class="empty"><div class="empty-symbol">▤</div><h3>知识库还是空的。</h3><p>完成一次讨论并生成复盘后，它会变成一张知识卡片，按 1、3、7、14 天的间隔提醒你回顾。</p><button class="primary" data-nav="learn">开始学习</button></div></section>`}`;
}
function cardPage() {
  const c = library.find(x => x.id === openCard);
  if (!c) { page = 'library'; return libraryPage(); }
  const answered = Object.keys(quizPick['c:' + c.id] || {}).length;
  return `<div class="eyebrow">KNOWLEDGE CARD</div><h1 class="screen-title">${esc(c.concept)}</h1><p class="muted">${esc(c.topic)} · ${MODES[c.mode]?.name || ''} · 已复习 ${c.reviews.length} 次</p>
  <div class="grid"><div class="stack">${c.quiz.length ? `<section class="card"><h2>先做题，再看答案</h2><p class="note" style="margin:0 0 14px">不看下面的复盘，凭记忆作答。</p>${quizHTML('c:' + c.id, c.quiz)}</section>` : ''}
  <details class="card fold" ${c.quiz.length && answered < c.quiz.length ? '' : 'open'}><summary><h2 style="display:inline">上次的复盘</h2></summary><div class="stack" style="margin-top:16px">${reviewBody(c)}</div></details></div>
  <aside class="stack"><section class="card summary"><h2>这次回顾得怎么样？</h2><p class="note" style="margin-top:0">记得清楚，间隔会拉长；还模糊，明天再来。</p>
  <button class="primary" data-rate="1">记得很清楚</button><button style="width:100%;margin-top:8px" data-rate="0">还有点模糊</button>
  <button style="width:100%;margin-top:18px" id="card-again">从薄弱点再讨论一次 ↗</button><button class="small ghost" style="width:100%;margin-top:6px" data-nav="library">返回知识库</button></section></aside></div>`;
}

/* ---------- 渲染与事件 ---------- */
function render() {
  const pages = { learn: learnPage, prepare: preparePage, session: sessionPage, review: reviewPage, library: libraryPage, card: cardPage, connection: connectionPage };
  $('#app').innerHTML = shell((pages[page] || learnPage)());
  bind(); bindAI();
  const tr = $('#transcript'); if (tr) tr.scrollTop = tr.scrollHeight;
}

function bind() {
  on('[data-nav]', 'click', el => { if (page === 'prepare') readPlanForm(); page = el.dataset.nav; explain = null; render(); window.scrollTo(0, 0); });
  // 开始学习
  on('#concept', 'input', el => { config.concept = el.value; save(); });
  on('#concept', 'keydown', (el, e) => { if (e.key === 'Enter' && !e.isComposing) prepare(); });
  on('[data-concept]', 'click', el => { config.concept = el.dataset.concept; save(); render(); });
  on('[data-mode]', 'click', el => { config.mode = el.dataset.mode; save(); render(); });
  on('#prepare', 'click', () => prepare());
  // 讨论准备
  on('#start', 'click', startSession);
  on('#reprepare', 'click', () => { config.concept = plan.concept; config.mode = plan.mode; prepare(plan.previous); });
  on('#plan-topic,#plan-points,[data-role-name],[data-role-title],[data-role-persona]', 'change', () => { readPlanForm(); save(); });
  on('[data-count]', 'change', el => { readPlanForm(); const t = el.dataset.count; config.counts[t] = +el.value; while (config.roles[t].length < config.counts[t]) config.roles[t].push({ type: 'ai', bg: '从实际案例出发，关注论证的适用条件。' }); save(); render(); });
  on('[data-type]', 'change', el => { readPlanForm(); const [t, i] = el.dataset.type.split(','); config.roles[t][i].type = el.value; save(); render(); });
  on('[data-bg]', 'input', el => { const [t, i] = el.dataset.bg.split(','); config.roles[t][i].bg = el.value; save(); });
  on('[data-stage]', 'change', el => { config.stages[+el.dataset.stage].name = el.value; save(); });
  on('[data-kind]', 'change', el => { readPlanForm(); config.stages[+el.dataset.kind].kind = el.value; save(); render(); });
  on('[data-round]', 'change', el => { readPlanForm(); config.stages[+el.dataset.round].rounds = +el.value; save(); render(); });
  on('[data-up]', 'click', el => { readPlanForm(); const i = +el.dataset.up; [config.stages[i - 1], config.stages[i]] = [config.stages[i], config.stages[i - 1]]; save(); render(); });
  on('[data-remove]', 'click', el => { readPlanForm(); config.stages.splice(+el.dataset.remove, 1); save(); render(); });
  on('#add-stage', 'click', () => { readPlanForm(); config.stages.push({ name: '自由辩论', kind: 'free', rounds: 1 }); save(); render(); });
  // 讨论现场
  on('#draft', 'input', el => { session.draft = el.value; save(); });
  on('#draft', 'keydown', (el, e) => { if (e.key === 'Enter' && !e.shiftKey && !e.isComposing) { e.preventDefault(); isDebate() ? submitSeat(el.value) : sendLearner(el.value); } });
  on('#send', 'click', () => { const v = $('#draft').value; isDebate() ? submitSeat(v) : sendLearner(v); });
  on('#generate', 'click', generateSeat);
  on('#skip', 'click', () => submitSeat('主席宣布：本席放弃本次发言。', true));
  on('#finish', 'click', finishSession);
  on('#hint', 'click', getHint);
  on('#close-hint', 'click', () => { hint = null; render(); });
  on('#close-explain', 'click', () => { explain = null; render(); });
  on('#add-reflect', 'click', () => addReflect(false));
  on('#next-speaker', 'click', nextSpeaker);
  on('[data-quick]', 'click', el => quickAction(el.dataset.quick, el.dataset.mid));
  on('[data-reflect]', 'input', el => { const m = session.messages.find(x => x.id === el.dataset.reflect); if (m) { m.draft = el.value; save(); } });
  on('[data-reflect-send]', 'click', el => submitReflect(el.dataset.reflectSend));
  on('[data-reflect-skip]', 'click', el => { session.messages = session.messages.filter(x => x.id !== el.dataset.reflectSkip); save(); render(); });
  on('#export', 'click', exportSession);
  // 复盘与知识库
  on('#gen-review', 'click', generateReview);
  on('#resume', 'click', () => { session.finished = false; page = 'session'; save(); render(); });
  on('#again', 'click', () => { const c = library.find(x => x.sessionId === session.id); if (c) discussAgain(c); });
  on('[data-quiz]', 'click', el => { const k = el.dataset.quiz; quizPick[k] = { ...(quizPick[k] || {}), [el.dataset.q]: +el.dataset.o }; render(); });
  on('[data-card]', 'click', el => { openCard = el.dataset.card; page = 'card'; render(); window.scrollTo(0, 0); });
  on('[data-rate]', 'click', el => rateCard(openCard, el.dataset.rate === '1'));
  on('#card-again', 'click', () => { const c = library.find(x => x.id === openCard); if (c) discussAgain(c); });
  on('#export-lib', 'click', exportLibrary);
}

render();
initAI();
