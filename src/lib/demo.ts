// 演示模式：没有连接 API 时，用本地模板走完整个流程
import type { ModeId, PayloadMap, PrepareResult, Purpose, Review, Turn } from '../../shared/types.ts';
import { getState, update } from '../store.ts';
import type { Session } from '../store.ts';

const ROLES: Record<ModeId, PrepareResult['roles']> = {
  socratic: [{ name: '苏珩', title: '追问者', persona: '温和，但不放过任何含糊的说法' }],
  roundtable: [{ name: '周衡', title: '经济学家', persona: '从稀缺和取舍看问题' }, { name: '沈知', title: '心理学家', persona: '关注人为什么常常忽略代价' }, { name: '顾远', title: '企业管理者', persona: '从真实的经营决策出发' }],
  teach: [{ name: '小满', title: '学生', persona: '高二学生，爱较真' }],
  debate: []
};
const GENERIC_ROLES: Record<ModeId, PrepareResult['roles']> = {
  ...ROLES,
  roundtable: [{ name: '周衡', title: '理论研究者', persona: '关心定义是否严谨' }, { name: '沈知', title: '心理学家', persona: '关心人们为什么会误解它' }, { name: '顾远', title: '实践者', persona: '关心它在真实场景里怎么用' }]
};

function costPrep(mode: ModeId): PrepareResult {
  return {
    concept: '机会成本',
    topic: { socratic: '周末去打工赚 300 元，真正的代价是什么？', roundtable: '为什么人们总是低估自己选择的代价？', teach: '向一个高中生讲清楚什么是机会成本', debate: '上大学的最大成本是学费' }[mode],
    keyPoints: ['放弃的最佳选择', '只算次优那一项', '包含隐性代价', '区别于沉没成本', '用于比较决策'],
    roles: ROLES[mode],
    opening: { socratic: '我们从一个具体场景开始吧。你周六去打工赚了 300 元，你觉得这个选择花掉了你什么？', roundtable: '我先抛砖引玉：经济学里没有免费的午餐，任何选择都有代价。主持人想先听哪个角度？', teach: '老师好！我听说过“机会成本”这个词，但它和普通的“成本”有啥区别啊？花钱不就是成本吗？', debate: '' }[mode]
  };
}
function genericPrep(c: string, mode: ModeId): PrepareResult {
  return {
    concept: c,
    topic: { socratic: `「${c}」到底是什么？它和相近的概念有什么不同？`, roundtable: `「${c}」在不同领域里意味着同一件事吗？`, teach: `向一个高中生讲清楚「${c}」`, debate: `理解「${c}」的关键在于它的定义，而不在于它的应用` }[mode],
    keyPoints: [`${c}的定义`, '它要解决什么问题', '一个典型例子', '常见的误解', '和相近概念的区别'],
    roles: GENERIC_ROLES[mode],
    opening: { socratic: `先说说你的直觉：你觉得「${c}」大概在讲什么？`, roundtable: `我们今天聊「${c}」。主持人能先说说，你是在什么场合第一次遇到它的吗？`, teach: `老师好！「${c}」这个词我常听到，但一直不懂。它到底是什么意思呀？`, debate: '' }[mode]
  };
}

const COST_REPLIES: [string, number[], string[]?][] = [
  ['你说到了“少玩了一天”，这很重要：时间也有稀缺性，代价不只是钱。那如果那天你本来可以去做家教赚 500 元，这时打工的代价又是什么？', [0, 2], ['稀缺性']],
  ['对，关键是你放弃的选项里最好的那一个。睡懒觉、打游戏、做家教都被放弃了，为什么只算做家教？', [1]],
  ['很好。再想一个问题：上个月你已经交了 200 元的健身卡，这笔钱该不该算进周六的选择里？', [3]],
  ['没错，已经花掉、拿不回来的钱不影响现在的选择。那你会怎样用机会成本做一次成本收益分析，决定周六干什么？', [4], ['成本收益分析']]
];

// 演示模式里能查到的术语
const DEMO_POINTS = ['机会成本不等于花掉的钱', '时间也有机会成本', '只比较最好的那个放弃项'];
const DEMO_TERMS: Record<string, { definition: string; relation: string }> = {
  稀缺性: { definition: '想要的东西总比能拿到的多：时间、钱、精力都有限，用在一处就不能用在别处。', relation: '正因为资源稀缺，选择才有代价，机会成本才存在。' },
  成本收益分析: { definition: '把一个选择的所有代价和好处列出来比较，好处大于代价才值得做。', relation: '机会成本是“代价”这一栏里最容易漏掉的一项。' },
  必要条件: { definition: '一个结论要成立就必须满足的条件；不满足它，结论一定不成立。', relation: '辩论中用来检验对方主张的适用范围。' },
  举证责任: { definition: '谁提出主张，谁就有责任拿出证据来支持它。', relation: '决定辩论中哪一方需要先证明自己的说法。' },
  适用边界: { definition: '一个说法在什么条件下成立、超出什么范围就不再成立。', relation: '说清边界，才能避免把概念用错地方。' },
  代表性: { definition: '一个例子能不能代表整体的情况；不具代表性的例子推不出普遍结论。', relation: '辩论中常用来质疑以偏概全的论证。' }
};
const termsIn = (text: string) => Object.keys(DEMO_TERMS).filter(t => text.includes(t)).slice(0, 3);

export const COST_REVIEW: Review = {
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
};

function debateText(s: Session, t: Turn): string {
  const positive = t.role.team === 'A', stance = positive ? '支持' : '反对';
  const opponent = [...s.entries].reverse().find(m => m.kind === 'msg' && m.team && m.team !== t.role.team && !m.skipped);
  let text: string;
  if (t.action === '立论') text = `谢谢主席、各位评委、对方辩友。我方对“${s.topic}”持${stance}立场。判断标准应当是：先把「${s.concept}」的含义说清楚，再比较收益、代价与可替代方案。` + (positive ? '我方认为，应当区分可以约束的实施风险与主张本身的价值，不能仅以存在风险推出否定结论。' : '我方认为，不能从个别场景中的可行性直接推出普遍适用，支持方必须说明其必要条件和适用边界。');
  else if (t.action === '质询') text = '请问对方辩友：贵方结论成立的必要条件是什么？当该条件不满足时，贵方是否仍坚持同样的主张？';
  else if (t.action === '答辩') text = '回应对方质询：我方主张以明确的适用范围为前提，不主张无条件适用。边界之外的情形需要另行判断，不能直接否定边界以内的结论。';
  else if (t.action === '总结陈词') text = `谢谢主席。我方对本辩题的${stance}立场不变。核心分歧在于「${s.concept}」的适用条件、比较标准和举证责任。结论必须由相应论据支持，不能把可能性直接等同于必然性。谢谢。`;
  else text = '对方辩友，从一个案例推导普遍结论，需要说明案例具有何种代表性。我方质疑的正是这一推导，而非案例是否可能发生。请对方回应其适用范围。';
  if (opponent && opponent.kind === 'msg' && t.action !== '立论' && t.action !== '总结陈词') text = `对方辩友刚才表示：“${opponent.text.slice(0, 60)}${opponent.text.length > 60 ? '…' : ''}”\n\n` + text;
  return text;
}

function nextStep(): number {
  const step = getState().session?.demoStep ?? 0;
  update(s => { if (s.session) s.session.demoStep++; });
  return step;
}

const wait = (ms: number) => new Promise(r => setTimeout(r, ms));

export async function demoRun<P extends Purpose>(purpose: P, payload: PayloadMap[P], delay = 450): Promise<unknown> {
  await wait(delay);
  const s = getState().session;
  if (purpose === 'prepare') {
    const p = payload as PayloadMap['prepare'];
    return { data: p.concept === '机会成本' ? costPrep(p.mode) : genericPrep(p.concept, p.mode) };
  }
  if (!s) return null;
  const cost = s.concept === '机会成本';
  const n = s.keyPoints.length;
  switch (purpose) {
    case 'discuss': {
      const p = payload as PayloadMap['discuss'];
      const role = s.personas.find(r => r.id === p.roleId) ?? s.personas[0];
      const step = nextStep();
      if (cost && s.mode === 'socratic') { const [text, covered, terms] = COST_REPLIES[step % COST_REPLIES.length]; return { text, covered, terms }; }
      const kp = s.keyPoints[step % n];
      const text = s.mode === 'socratic' ? `你说的有一部分是对的。那我们看看「${kp}」：你能用自己的话说说，并举一个例子吗？`
        : s.mode === 'teach' ? `老师，我还是没太懂「${kp}」。能举个生活里的例子吗？`
          : `从${role.title}的角度看，讨论「${s.concept}」时最容易被忽略的是「${kp}」。主持人怎么看？`;
      return { text, covered: [step % n] };
    }
    case 'debate': { const step = nextStep(); const text = debateText(s, s.turns[s.cursor]); return { text, covered: [step % n], terms: termsIn(text) }; }
    case 'point': {
      // 从备选里挑一条还没列出的；“机会成本”给真实的例子，其他概念给通用说明
      const pool = cost ? DEMO_POINTS : [`「${s.concept}」最容易被误解的地方`, `「${s.concept}」在生活里的一个例子`, `「${s.concept}」不适用的情况`];
      const point = pool.find(p => !s.keyPoints.includes(p));
      if (!point) throw new Error('演示模式里的备选要点都已经列出了。');
      return { data: { point, why: '演示模式：连接 API 后，AI 会根据目前的讨论提议一条还没讲到的要点。' } };
    }
    case 'term': {
      const term = (payload as PayloadMap['term']).term;
      return { data: { term, ...(DEMO_TERMS[term] ?? { definition: `演示模式：连接 API 后，这里会用简单的话解释「${term}」。`, relation: '' }) } };
    }
    case 'hint': {
      const left = s.keyPoints.filter((_, i) => !s.covered.includes(i));
      return { text: left.length ? `还没讲到「${left[0]}」。试着先举一个你亲身经历过的例子，再说说它和这个要点有什么关系。` : '要点都讲到了。试着用一句话把它们串起来，看看能不能说给别人听懂。' };
    }
    case 'explain': {
      const kind = (payload as PayloadMap['explain']).kind;
      return { text: { unclear: '演示模式：连接 API 后，这里会用更简单的话重新解释这句话。', example: '演示模式：连接 API 后，这里会给出一个能说明这句话的具体例子。', fact: '演示模式：连接 API 后，这里会分辨这句话里哪些是事实、哪些是观点。' }[kind] };
    }
    case 'reflect':
      return cost ? { text: '说得不错：你抓住了“放弃的东西”这个核心。还缺一点：机会成本只算放弃的选项里最好的那一个，不是所有放弃的加起来。', covered: [0] }
        : { text: '演示模式：连接 API 后，这里会对照要点清单评价你的小结，指出说对的部分和最关键的遗漏。', covered: [] };
    case 'review': {
      if (cost && s.mode !== 'debate') return { data: COST_REVIEW };
      const lit = s.covered.length;
      const data: Review = {
        oneLine: `规则式复盘：本次讨论点亮了 ${lit}/${n} 个要点。连接 API 后可获得针对你发言的具体评价和练习题。`,
        points: s.keyPoints.map((k, i) => ({ label: k, status: s.covered.includes(i) ? 'mastered' : 'missing', note: s.covered.includes(i) ? '讨论中点亮了这一点。' : '讨论中还没有讲到。' })),
        misconceptions: [], quiz: []
      };
      return { data };
    }
  }
  return null;
}
