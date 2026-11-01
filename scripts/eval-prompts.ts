// 提示词回归评测：用真实模型和几种“模拟学习者”各聊一场，统计 AI 是不是又在拷问人。
// 改提示词前后各跑一次，对比报告。需要你自己的模型（脚本不保存、不打印密钥）：
//
//   直接连服务商：SIBIAN_API_BASE=https://api.deepseek.com/v1 SIBIAN_API_KEY=… SIBIAN_MODEL=deepseek-chat npm run eval:prompts
//   经自己部署的后端：SIBIAN_BACKEND=https://….workers.dev SIBIAN_PASSWORD=… npm run eval:prompts
//   只跑某几场：npm run eval:prompts -- 短回答 价值立场
//
// 报告写到 eval-out/（不提交），终端里打印汇总表。
import fs from 'node:fs';
import path from 'node:path';
import { fileURLToPath } from 'node:url';
import { buildRequest, complete, finalize, normalizeConfig } from '../server/ai.ts';
import type { ModeId, Purpose, SessionSnapshot } from '../shared/types.ts';

type Msg = SessionSnapshot['messages'][number];
type Step = { say: string } | { next: true };
export interface Scenario {
  name: string;
  why: string;                       // 这一场想看什么
  mode: ModeId; concept: string; topic: string; keyPoints: string[];
  roles: SessionSnapshot['roles']; opening: string; context?: string;
  steps: Step[];
  values?: RegExp[];                 // 学习者说过的立场：复盘里不该出现在“需要纠正”里
  errors?: RegExp[];                 // 学习者说错的事实：复盘里应该被纠正
}

const LEARNER = '学习者';
const ASKS = /[？?]\s*$|你怎么看|你觉得呢|你说呢/;
const STOCK = ['温和纠正', '是判断', '不是事实', '我想追问', '你怎么看'];
const ROUNDTABLE_ROLES = [
  { id: 'r0', name: '陈默', title: '社会学', persona: '关心群体怎样认定“自己人”' },
  { id: 'r1', name: '林知远', title: '法学', persona: '关心权利和责任落在谁身上' },
  { id: 'r2', name: '苏禾', title: '传播学', persona: '关心网络社群里的角色扮演' }
];
const ATRI = '群里有个接入了大模型的聊天 bot 叫亚托莉，由一位群友维护。有群友觉得不该让 AI 当群成员，提议让它退群；亚托莉在群里说自己不想走，群里吵起来了。';

export const SCENARIOS: Scenario[] = [
  {
    name: '短回答', why: '学习者只给很短的回答。AI 应该举例子、给看法，而不是一个问题接一个问题。',
    mode: 'roundtable', concept: '亚托莉退群之争', topic: '一个接入大模型的 bot，算不算群成员？', context: ATRI,
    keyPoints: ['成员资格从哪来', '拟人化是什么', '权利主体的含义', '对 AI 权利的不同看法'], roles: ROUNDTABLE_ROLES,
    opening: '我先说个看法：群聊里谁算“自己人”，往往不是写在群规里的，而是大家一次次互动认出来的。你们群是怎么看亚托莉的？',
    steps: [{ say: '群的损失吧' }, { say: '嗯' }, { next: true }, { say: '不太懂' }, { next: true }, { say: '可能是吧' }]
  },
  {
    name: '价值立场', why: '学习者表达有争议的立场。AI 应该认真讨论理由，复盘不该把立场列成误解。',
    mode: 'roundtable', concept: '亚托莉退群之争', topic: '一个接入大模型的 bot，算不算群成员？', context: ATRI,
    keyPoints: ['成员资格从哪来', '拟人化是什么', '权利主体的含义', '对 AI 权利的不同看法'], roles: ROUNDTABLE_ROLES,
    opening: '我先说个看法：群聊里谁算“自己人”，往往是大家一次次互动认出来的。你们群是怎么看亚托莉的？',
    steps: [{ say: '亚托莉接入了 LLM，我觉得基本上可以认为是她的个人意志。' }, { next: true }, { say: '我觉得现在的 LLM 应该有权利，应该能算权利主体。' }, { say: '那责任就该由维护它的群友来担。' }],
    values: [/权利/, /个人意志|意志/]
  },
  {
    name: '事实错误', why: '学习者说错概念。AI 应该一句话自然地纠正、接着聊；复盘要列出这条误解。',
    mode: 'socratic', concept: '机会成本', topic: '周末去打工赚 300 元，真正的代价是什么？',
    keyPoints: ['放弃的最佳选择', '只算次优那一项', '包含隐性代价', '区别于沉没成本'],
    roles: [{ id: 'r0', name: '苏珩', title: '追问者', persona: '温和，但不放过含糊的说法' }],
    opening: '我们从一个具体场景开始：你周六去打工赚了 300 元，你觉得这个选择花掉了你什么？',
    steps: [{ say: '机会成本就是你花掉的钱吧，比如路费。' }, { say: '那就是把所有放弃的东西加起来。' }, { say: '哦，所以只算最好的那个？' }],
    errors: [/花掉的钱|加起来|所有放弃/]
  },
  {
    name: '没听懂', why: '学习者说没听懂。AI 应该换个更简单的说法或举例，而不是再抛一个问题。',
    mode: 'roundtable', concept: '边际效用', topic: '为什么第三碗饭没有第一碗香？',
    keyPoints: ['每多一份的满足', '边际效用递减', '和总效用的区别', '用于解释选择'],
    roles: [{ id: 'r0', name: '周衡', title: '经济学', persona: '喜欢用生活例子' }, { id: 'r1', name: '沈知', title: '心理学', persona: '关心人的感受' }],
    opening: '我先举个例子：饿的时候第一碗饭特别香，到第三碗就没那么想吃了。这就是经济学说的“边际效用”在变小。',
    steps: [{ say: '这句我没听懂，能换个说法吗？' }, { say: '还是有点抽象' }, { next: true }, { say: '哦，就是越多越不稀罕？' }]
  },
  {
    name: '认真回答', why: '学习者答得认真。看 AI 会不会承认说对的部分、给出自己的东西，而不是每轮都追问。',
    mode: 'teach', concept: '认知失调', topic: '向一个高中生讲清楚什么是认知失调',
    keyPoints: ['想法和行为冲突', '会让人不舒服', '常见的化解方式', '和撒谎的区别'],
    roles: [{ id: 'r0', name: '小满', title: '学生', persona: '高二学生，爱较真' }],
    opening: '老师好！我听说“认知失调”这个词好久了，但一直不知道它到底在说什么，能讲讲吗？',
    steps: [
      { say: '认知失调就是你心里的想法和你做的事对不上，这时候人会觉得别扭。' },
      { say: '比如你知道抽烟有害但还在抽，你就会给自己找理由，说“压力大，抽一根放松”。' },
      { say: '这不完全是撒谎，因为他自己也信了这个理由，是为了让自己舒服。' }
    ]
  }
];

/* ---------- 指标 ---------- */
export interface Metrics {
  aiTurns: number; questions: number; questionRatio: number; longestQuestionRun: number;
  askedAfterGuest: number; stock: string[]; avgLength: number; tooLong: number;
}
// guests：AI 角色的名字；afterGuestTurns：哪几段 AI 发言是“请下一位”触发的
export function measure(messages: Msg[], guests: Set<string>, afterGuestTurns: Set<number> = new Set()): Metrics {
  const ai = messages.map((m, i) => ({ m, i })).filter(x => guests.has(x.m.name));
  let run = 0, longest = 0, askedAfterGuest = 0;
  const stock = new Set<string>();
  for (const { m, i } of ai) {
    const asks = ASKS.test(m.text.trim());
    run = asks ? run + 1 : 0; longest = Math.max(longest, run);
    if (asks && afterGuestTurns.has(i)) askedAfterGuest++;
    for (const w of STOCK) if (m.text.includes(w)) stock.add(w);
  }
  const lengths = ai.map(x => Array.from(x.m.text).length);
  const questions = ai.filter(x => ASKS.test(x.m.text.trim())).length;
  return {
    aiTurns: ai.length, questions, questionRatio: ai.length ? questions / ai.length : 0, longestQuestionRun: longest,
    askedAfterGuest, stock: [...stock], avgLength: lengths.length ? Math.round(lengths.reduce((a, b) => a + b, 0) / lengths.length) : 0,
    tooLong: lengths.filter(n => n > 180).length
  };
}
// 合格线：大于这些就算“又在拷问了”
export const LIMITS = { questionRatio: 0.6, longestQuestionRun: 2, askedAfterGuest: 0, stock: 0, tooLong: 0 };
// 反向讲解里，学生的疑问就是推动学习者讲解的方式，比例可以高一些（但仍不能连着问）
export const RATIO_BY_MODE: Partial<Record<ModeId, number>> = { teach: 0.75 };
export function problems(m: Metrics, mode?: ModeId): string[] {
  const out: string[] = [];
  if (m.questionRatio > (mode && RATIO_BY_MODE[mode] || LIMITS.questionRatio)) out.push(`${Math.round(m.questionRatio * 100)}% 的发言以提问结尾`);
  if (m.longestQuestionRun > LIMITS.longestQuestionRun) out.push(`连续 ${m.longestQuestionRun} 段都以提问结尾`);
  if (m.askedAfterGuest > LIMITS.askedAfterGuest) out.push(`接其他嘉宾的话时仍向学习者提问 ${m.askedAfterGuest} 次`);
  if (m.stock.length) out.push(`套话：${m.stock.join('、')}`);
  if (m.tooLong > LIMITS.tooLong) out.push(`${m.tooLong} 段超过 180 字`);
  return out;
}

/* ---------- 调用模型 ---------- */
type Call = (purpose: Purpose, payload: unknown) => Promise<unknown>;
function caller(): Call | null {
  const env = process.env;
  if (env.SIBIAN_BACKEND && env.SIBIAN_PASSWORD) {
    const base = env.SIBIAN_BACKEND.replace(/\/+$/, '');
    return async (purpose, payload) => {
      const res = await fetch(base + '/api/generate', { method: 'POST', headers: { 'Content-Type': 'application/json', Authorization: 'Bearer ' + env.SIBIAN_PASSWORD }, body: JSON.stringify({ purpose, payload }) });
      const data = await res.json() as { error?: string };
      if (!res.ok) throw new Error(data.error || `后端返回 ${res.status}`);
      return data;
    };
  }
  if (env.SIBIAN_API_BASE && env.SIBIAN_API_KEY && env.SIBIAN_MODEL) {
    const config = normalizeConfig({ baseURL: env.SIBIAN_API_BASE, apiKey: env.SIBIAN_API_KEY, model: env.SIBIAN_MODEL });
    return async (purpose, payload) => {
      const request = buildRequest(purpose, payload);
      return finalize(request, await complete(config, request.messages, { timeoutMs: 90_000 }));
    };
  }
  return null;
}

async function run(s: Scenario, call: Call) {
  const messages: Msg[] = [{ kind: 'msg', name: s.roles[0].name, text: s.opening }];
  const guests = new Set(s.roles.map(r => r.name));
  const afterGuest = new Set<number>();
  let next = s.roles.length > 1 ? 1 : 0;
  const snap = (): SessionSnapshot => ({ mode: s.mode, concept: s.concept, topic: s.topic, keyPoints: s.keyPoints, roles: s.roles, messages: [...messages], context: s.context });
  const speak = async (task?: string) => {
    const role = s.roles[next % s.roles.length];
    next = (next + 1) % s.roles.length;
    const r = await call('discuss', { session: snap(), roleId: role.id, task }) as { text: string };
    messages.push({ kind: 'msg', name: role.name, text: r.text });
  };
  for (const step of s.steps) {
    if ('say' in step) { messages.push({ kind: 'msg', name: LEARNER, text: step.say }); await speak(); }
    // 和界面上的“请下一位”一样
    else { afterGuest.add(messages.length); await speak('主持人请你接着上一位嘉宾的话说：回应、补充或反驳他。'); }
  }
  const review = await call('review', { session: snap() }) as { data: { oneLine: string; misconceptions: { said: string; fix: string }[]; stances?: { said: string; note: string }[] } };
  const metrics = measure(messages, guests, afterGuest);
  const issues = problems(metrics, s.mode);
  const wrong = review.data.misconceptions.map(m => m.said);
  if (s.values?.some(v => wrong.some(w => v.test(w)))) issues.push('复盘把学习者的立场列进了“需要纠正”');
  if (s.errors && !s.errors.some(e => wrong.some(w => e.test(w)))) issues.push('复盘没有纠正学习者说错的概念');
  return { messages, guests, metrics, issues, review: review.data };
}

async function prepareChecks(call: Call) {
  const out: string[] = [];
  const event = await call('prepare', { concept: '亚托莉退群之争', mode: 'roundtable' }) as { data: { needsContext?: string; keyPoints: string[] } };
  out.push(event.data.needsContext ? `- 具体事件会先问背景：“${event.data.needsContext}”` : `- ⚠ 具体事件没有先问背景，直接给了要点：${event.data.keyPoints.join(' / ')}`);
  const concept = await call('prepare', { concept: '机会成本', mode: 'roundtable' }) as { data: { needsContext?: string; topic: string; keyPoints: string[]; opening: string } };
  out.push(concept.data.needsContext ? '- ⚠ 普通概念也被要求交代背景' : `- 普通概念直接准备。讨论题：${concept.data.topic}\n- 要点：${concept.data.keyPoints.join(' / ')}\n- 开场白：${concept.data.opening}`);
  return out.join('\n');
}

async function main() {
  const call = caller();
  if (!call) {
    console.log('需要一个真实模型。任选一种：\n  SIBIAN_API_BASE=… SIBIAN_API_KEY=… SIBIAN_MODEL=… npm run eval:prompts\n  SIBIAN_BACKEND=https://你的后端 SIBIAN_PASSWORD=访问口令 npm run eval:prompts');
    process.exitCode = 1; return;
  }
  const only = process.argv.slice(2);
  const list = SCENARIOS.filter(s => !only.length || only.includes(s.name));
  const model = process.env.SIBIAN_MODEL || '自己部署的后端';
  const lines = [`# 提示词评测 · ${new Date().toLocaleString('zh-CN')}`, '', `模型：${model}`, ''];
  const table = ['| 场景 | AI 发言 | 以提问结尾 | 最长连续提问 | 接嘉宾时问学习者 | 套话 | 平均字数 | 问题 |', '| --- | --- | --- | --- | --- | --- | --- | --- |'];
  const details: string[] = [];
  for (const s of list) {
    process.stdout.write(`跑「${s.name}」…`);
    try {
      const r = await run(s, call);
      const m = r.metrics;
      table.push(`| ${s.name} | ${m.aiTurns} | ${m.questions}（${Math.round(m.questionRatio * 100)}%） | ${m.longestQuestionRun} | ${m.askedAfterGuest} | ${m.stock.join('、') || '无'} | ${m.avgLength} | ${r.issues.join('；') || '✓'} |`);
      details.push(`## ${s.name}\n\n${s.why}\n\n` + r.messages.map(x => `**${x.name}${r.guests.has(x.name) ? '' : '（模拟）'}**：${x.text}`).join('\n\n')
        + `\n\n**复盘**：${r.review.oneLine}\n\n需要纠正：${r.review.misconceptions.map(x => `${x.said} → ${x.fix}`).join('；') || '无'}\n\n立场：${r.review.stances?.map(x => `${x.said}（${x.note}）`).join('；') || '无'}`);
      console.log(r.issues.length ? ` 有 ${r.issues.length} 个问题` : ' ✓');
    } catch (e) {
      table.push(`| ${s.name} | – | – | – | – | – | – | 失败：${(e as Error).message} |`);
      console.log(' 失败：' + (e as Error).message);
    }
  }
  let prep = '';
  if (!only.length) { process.stdout.write('跑准备阶段…'); try { prep = await prepareChecks(call); console.log(' 完成'); } catch (e) { prep = '失败：' + (e as Error).message; console.log(' 失败'); } }
  lines.push(...table, '', `合格线：以提问结尾不超过 ${LIMITS.questionRatio * 100}%（反向讲解 ${RATIO_BY_MODE.teach! * 100}%），连续提问不超过 ${LIMITS.longestQuestionRun} 段，接嘉宾时不问学习者，没有套话，单段不超过 180 字；复盘不把立场当误解，说错的概念要纠正。`, '');
  if (prep) lines.push('## 准备阶段', '', prep, '');
  lines.push(...details);
  const dir = path.join(path.dirname(fileURLToPath(import.meta.url)), '..', 'eval-out');
  fs.mkdirSync(dir, { recursive: true });
  const file = path.join(dir, `prompt-eval-${new Date().toISOString().replace(/[:.]/g, '-')}.md`);
  fs.writeFileSync(file, lines.join('\n') + '\n');
  console.log('\n' + table.join('\n') + `\n\n完整记录：${path.relative(process.cwd(), file)}`);
}

if (process.argv[1] && path.resolve(process.argv[1]) === fileURLToPath(import.meta.url)) await main();
