// 原生安卓版的“标准答案”：用网页版的真实代码跑出几份存档，以及网页版读回它们之后的结果、导出的 Markdown。
// 原生版（native/）的 FixturesTest 读同样的存档，结果必须逐字一致。这样两边的数据逻辑由测试锁在一起。
// 改了存档结构、存档检查（src/lib/restore.ts）或导出（src/lib/exporter.ts）后运行 npm run fixtures:native；
// tests/native-fixtures.test.ts 会检查这些文件没有过期。
import { mkdirSync, readFileSync, writeFileSync } from 'node:fs';
import { dirname, join } from 'node:path';
import { fileURLToPath } from 'node:url';
import { graphOf } from '../src/lib/canvas.ts';
import { splitForSpeech } from '../shared/speech.ts';
import { encodeQr, type QrCode } from '../src/lib/qr.ts';

const root = join(dirname(fileURLToPath(import.meta.url)), '..');
export const FIXTURE_DIR = join(root, 'native', 'app', 'src', 'test', 'resources', 'fixtures');
export const NOW = 1763366400000; // 2025-11-17 16:00（上海）：生成过程里的“现在”
export const TIME_ZONE = 'Asia/Shanghai';

// 生成过程必须每次都一样：固定时区、时间和随机数
function freeze() {
  process.env.TZ = TIME_ZONE;
  let t = NOW;
  Date.now = () => (t += 1000);
  let seed = 7;
  Math.random = () => ((seed = (seed * 16807) % 2147483647) - 1) / 2147483646;
  const store: Record<string, string> = {};
  (globalThis as Record<string, unknown>).localStorage = {
    getItem: (k: string) => store[k] ?? null,
    setItem: (k: string, v: string) => {
      store[k] = v;
    },
    removeItem: (k: string) => {
      delete store[k];
    },
  };
  return { store, reset: () => (t = NOW) };
}

// 一份内容很全的知识卡片：误解、立场、练习题、术语都有
const richCard = {
  id: 'card-rich',
  sessionId: 's-old',
  concept: '边际效用',
  topic: '第三块蛋糕为什么没有第一块好吃？',
  mode: 'roundtable',
  created: NOW - 5 * 86_400_000,
  source: 'api',
  model: 'some-model',
  level: 2,
  due: NOW + 86_400_000,
  reviews: [{ at: NOW - 86_400_000, remembered: true }],
  oneLine: '你分清了总效用和边际效用。',
  points: [
    { label: '每多一份带来的满足在变少', status: 'mastered', note: '用蛋糕的例子讲清楚了' },
    { label: '总效用仍可能上升', status: 'partial', note: '提到了但没展开' },
    { label: '和价格的关系', status: 'missing', note: '' },
  ],
  misconceptions: [{ said: '吃得越多越不满足', fix: '是每多一份的满足变少，总满足可能还在增加' }],
  stances: [
    { said: '水比钻石更有用', note: '理由是生存必需，这是价值判断' },
    { said: '没有注释的立场', note: '' },
  ],
  quiz: [
    {
      q: '第三块蛋糕带来的满足比第一块少，说明什么？',
      options: ['总效用下降', '边际效用递减', '价格太高'],
      answer: 1,
      why: '每多一份的满足在变少。',
    },
  ],
  terms: [
    {
      term: '总效用',
      definition: '消费全部数量得到的满足之和',
      relation: '边际效用是它每多一份的变化',
    },
    { term: '无关系', definition: '只是一个词', relation: '' },
  ],
};

export async function generate(): Promise<Record<string, string>> {
  const { store, reset } = freeze();
  const S = await import('../src/store.ts');
  const A = await import('../src/actions.ts');
  const { setDemoDelay } = await import('../src/lib/ai.ts');
  const { sessionMarkdown, libraryMarkdown } = await import('../src/lib/exporter.ts');
  setDemoDelay(0);
  const out: Record<string, string> = {};

  // 一个场景：从干净的状态开始跑 setup，取出存档；再像打开 App 一样读回来，记下结果和导出
  async function scenario(name: string, setup: () => unknown) {
    for (const k of Object.keys(store)) delete store[k];
    reset();
    S.resetForTests();
    await setup();
    // 用旧存档（sibian-v3，知识库是一张张卡片）起步的场景：像打开新版 App 一样读进来，合并成概念笔记，
    // 再存一次 v4。迁移前的旧存档也写进标准答案（.v3.json），原生版读它也要得到同样的结果（#140）
    const legacy = store['sibian-v4'] ? undefined : store['sibian-v3'];
    if (legacy) {
      const ticking = Date.now;
      Date.now = () => NOW;
      S.resetForTests();
      S.update(() => {});
      Date.now = ticking;
      out[`${name}.v3.json`] = legacy;
    }
    const saved = store['sibian-v4'];
    if (!saved) throw new Error(`${name}: 没有生成存档`);
    // 读回存档时把“现在”固定成 NOW：缺了时间的卡片会补上它，原生版测试传同一个 NOW
    const ticking = Date.now;
    Date.now = () => NOW;
    S.resetForTests();
    Date.now = ticking;
    const st = S.getState();
    const restored = {
      config: st.config,
      plan: st.plan,
      session: st.session,
      library: st.library,
      screen: st.ui.screen,
    };
    out[`${name}.saved.json`] = saved;
    out[`${name}.restored.json`] = JSON.stringify(restored, null, 2) + '\n';
    if (st.session) out[`${name}.session.md`] = sessionMarkdown(st.session);
    out[`${name}.library.md`] = libraryMarkdown(st.library);
  }
  const raw = (json: unknown) => () => {
    store['sibian-v3'] = JSON.stringify(json);
  };

  await scenario('socratic-finished', async () => {
    S.update(s => {
      s.config.concept = '机会成本';
      s.config.mode = 'socratic';
    });
    await A.prepare(null, undefined, 'start');
    await A.send('机会成本是为了得到一样东西而放弃的最好的那个选择。');
    A.addReflection();
    const r = S.getState().session!.entries.findLast(e => e.kind === 'reflect')!;
    A.setReflectionDraft(r.id, '代价不只是钱，还有放弃的最好选择。');
    await A.submitReflection(r.id);
    await A.finish();
  });

  await scenario('canvas-branches', async () => {
    S.update(s => {
      s.config.concept = '机会成本';
      s.config.mode = 'canvas';
    });
    await A.prepare(null, undefined, 'start');
    await A.send('少玩了一天');
    const said = S.getState().session!.entries.findLast(e => e.kind === 'msg' && !e.human)!;
    await A.openBranch(said.id, '稀缺性');
    await A.send('比如周末的时间');
    A.bringBack('时间和钱一样有限，用在一处就不能用在别处');
    await A.send('那做家教也是代价');
  });

  await scenario('debate-in-progress', async () => {
    S.update(s => {
      s.config.concept = '机会成本';
      s.config.mode = 'debate';
    });
    await A.prepare(null, undefined, 'start');
    await A.send('我方认为，机会成本让人看清每个选择真正的代价。');
  });

  await scenario(
    'rich-library',
    raw({
      config: { concept: '边际效用', mode: 'roundtable' },
      library: [richCard],
      screen: 'library',
    }),
  );

  await scenario(
    'damaged',
    raw({
      config: {
        concept: '边际效用',
        mode: 'nope',
        seats: { A: 'x' },
        counts: { A: 9, B: 2 },
        stages: [],
        turnSeconds: 45,
        remote: { url: 1 },
      },
      plan: { mode: 'socratic', concept: 'x' },
      session: { id: 's1', mode: 'socratic', concept: 'x', topic: 'y', entries: 'oops' },
      library: [
        null,
        'x',
        {
          id: 'c1',
          concept: '机会成本',
          oneLine: '放弃的最好选择',
          points: [{ label: 'a', status: 'mastered', note: '' }, 3],
          quiz: [{ q: '没有选项' }, { q: '有选项', options: ['甲', '乙'], answer: 0, why: '' }],
        },
        { concept: '没有 id' },
      ],
      screen: 'session',
    }),
  );

  await scenario(
    'old-teach',
    raw({
      config: { concept: '光速不变原理', mode: 'teach', turnSeconds: 60, name: '小明' },
      plan: {
        mode: 'teach',
        concept: '光速不变原理',
        topic: '向高中生讲清楚',
        keyPoints: ['a', 'b'],
        personas: [{ id: 'r0', name: '学生' }],
      },
      library: [],
      screen: 'learn',
    }),
  );
  // 演示模式的标准答案：在几种典型的讨论状态下，每种用途各调用一次网页版的 demoRun，
  // 记下调用前的讨论、payload、结果和调用后的 demoStep。原生版 DemoTest 用同样的输入，四样都要一致。
  // 从材料里的一个技术点开始讨论（#144）：讨论、计划带着原文节选，笔记的来源记下材料标题
  await scenario('from-material', async () => {
    const { SAMPLE_MATERIAL } = await import('../src/lib/demo.ts');
    await A.addMaterial(SAMPLE_MATERIAL.title, SAMPLE_MATERIAL.text);
    const point = S.getState().ui.material!.map!.points[1];
    await A.startFromPoint(point, 'socratic');
    await A.send('已经花掉的钱拿不回来，所以不该影响接下来的决定。');
    await A.finish();
  });

  const { demoRun } = await import('../src/lib/demo.ts');
  const cases: unknown[] = [];
  async function record(purpose: string, payload: Record<string, unknown>) {
    const before = structuredClone(S.getState().session);
    let result: unknown;
    try {
      result = await demoRun(purpose as never, payload as never, 0);
    } catch (e) {
      result = { error: (e as Error).message };
    }
    cases.push({
      purpose,
      payload,
      session: before,
      result: result ?? null,
      demoStep: S.getState().session?.demoStep ?? null,
    });
    // 每次调用都从同一个状态开始，互不影响
    S.update(st => {
      st.session = before;
    });
  }
  const everyPurpose = async () => {
    const s = S.getState().session!;
    // 辩论没有角色、只走 debate 用途；讨论类模式才有 discuss
    if (s.mode === 'debate') await record('debate', {});
    else {
      await record('discuss', { roleId: s.personas[0].id });
      await record('discuss', { roleId: s.personas.at(-1)!.id, task: '换他来讲：学习者要当老师' });
      await record('discuss', { roleId: s.personas[0].id, task: '学习者讲完了，请回到讨论' });
    }
    await record('point', {});
    await record('term', { term: '稀缺性' });
    await record('term', { term: '不在演示里的词' });
    await record('hint', {});
    for (const kind of ['unclear', 'example', 'fact']) await record('explain', { kind });
    await record('reflect', { answer: '我的理解' });
    await record('review', {});
  };
  async function situation(concept: string, mode: string, setup?: () => Promise<void> | void) {
    for (const k of Object.keys(store)) delete store[k];
    reset();
    S.resetForTests();
    S.update(s => {
      s.config.concept = concept;
      s.config.mode = mode as never;
    });
    await A.prepare(null, undefined, 'start');
    await setup?.();
    await everyPurpose();
  }
  await situation('机会成本', 'socratic');
  await situation('机会成本', 'socratic', () => {
    S.update(s => {
      s.session!.demoStep = 5;
      s.session!.covered = [0, 1, 2, 3, 4];
    });
  });
  await situation('机会成本', 'teach');
  // 追问和圆桌里也能展开分支（#142）
  await situation('边际效用', 'roundtable', async () => {
    const said = S.getState().session!.entries.findLast(e => e.kind === 'msg' && !e.human)!;
    await A.openBranch(said.id, '总效用');
  });
  await situation('边际效用', 'roundtable');
  await situation('边际效用', 'socratic');
  await situation('机会成本', 'canvas', async () => {
    await A.send('少玩了一天');
    const said = S.getState().session!.entries.findLast(e => e.kind === 'msg' && !e.human)!;
    await A.openBranch(said.id, '稀缺性');
  });
  await situation('边际效用', 'canvas', async () => {
    const said = S.getState().session!.entries.findLast(e => e.kind === 'msg' && !e.human)!;
    await A.openBranch(said.id, '没听过的词');
  });
  await situation('机会成本', 'debate', async () => {
    await A.send(
      '我方认为，机会成本让人看清每个选择真正的代价，而上大学放弃的四年工资远比学费更大。这一点在县城尤其明显，很多同学都是这样算账的。',
    );
  });
  await situation('边际效用', 'debate');
  // 要点都列满了：演示模式里没有备选要点可提
  await situation('机会成本', 'socratic', () => {
    S.update(s => {
      s.session!.keyPoints.push(
        '机会成本不等于花掉的钱',
        '时间也有机会成本',
        '只比较最好的那个放弃项',
      );
    });
  });
  // 准备：每种模式、“机会成本”和别的概念、改过题目
  for (const mode of ['socratic', 'roundtable', 'teach', 'debate', 'canvas'])
    for (const concept of ['机会成本', '边际效用']) {
      S.update(s => {
        s.session = null;
      });
      await record('prepare', { concept, mode });
    }
  await record('prepare', { concept: '边际效用', mode: 'debate', topic: '学习者自己改过的辩题' });
  out['demo-cases.json'] = JSON.stringify(cases) + '\n';

  // 学习流程的剧本：在网页版用演示模式跑一串操作，每一步之后记下规整后的状态和发给 AI 的快照。
  // 原生版 FlowTest 按同样的剧本操作，每一步都要一致。id 和时间戳比较前换成占位符（canonical）。
  out['flow-scripts.json'] = JSON.stringify(await recordFlows(S, A)) + '\n';

  // 原生测试读回存档时用同一个“现在”和时区
  out['direct-cases.json'] = JSON.stringify(await directCases()) + '\n';
  // 朗读的分段：原生版按同样的规则切，一段一段请求后端合成
  out['speech-cases.json'] = JSON.stringify(speechCases()) + '\n';
  // 多人房间：状态映射、邀请链接、倒计时、轮到谁、建房间时提交的内容
  out['room-cases.json'] = JSON.stringify(await roomCases()) + '\n';
  // 知识库搜索：同样的卡片和查询，原生版的结果和顺序要一样
  out['search-cases.json'] = JSON.stringify(await searchCases()) + '\n';
  // 概念笔记的合并规则（#140）：同样的复盘按同样的顺序合并，原生版每一步的笔记、导出和统计都要一样
  out['notes-cases.json'] = JSON.stringify(await notesCases()) + '\n';
  // 读材料（#144、#154）：切节、工具、节选和演示模式的研读，原生版 ReaderTest 逐字比对
  out['reader-cases.json'] =
    JSON.stringify({
      cases: await readerCases(),
      demo: await demoReadCases(),
      sample: (await import('../src/lib/demo.ts')).SAMPLE_MATERIAL,
    }) + '\n';
  // 邀请二维码（#151）：同样的链接，原生版挑的版本、掩码和每一格都要一样
  out['qr-cases.json'] = JSON.stringify(qrCases()) + '\n';
  out['meta.json'] = JSON.stringify({ now: NOW, timeZone: TIME_ZONE }, null, 2) + '\n';
  return out;
}

async function notesCases() {
  const N = await import('../src/lib/notes.ts');
  const { COST_REVIEW } = await import('../src/lib/demo.ts');
  const from = (sessionId: string, at: number, extra: object = {}) => ({
    sessionId,
    at,
    mode: 'socratic' as const,
    concept: '机会成本',
    topic: '题目 ' + sessionId,
    source: 'api' as const,
    model: 'm',
    ...extra,
  });
  const second = {
    oneLine: '第二次：区别讲清楚了',
    summary: '',
    useWhen: '要做取舍的时候',
    points: [
      { label: '区别于沉没成本', status: 'mastered' as const, note: '能说清了', evidence: '' },
      {
        label: ' 只算次优那一项 ',
        status: 'missing' as const,
        note: '又忘了',
        evidence: '加起来吧',
      },
      { label: '新要点', status: 'partial' as const, note: '刚提到' },
    ],
    examples: ['周末去打工赚 300 元，放弃了在家复习的收获。', '读研三年', '', '买房还是租房'],
    misconceptions: [{ said: '机会成本是所有放弃的选项加起来。', fix: '改过的说法' }],
    boundaries: ['不是会计成本'],
    related: ['机会成本', '沉没 成本', '比较优势'],
    stances: [{ said: '应该多考虑时间', note: '理由清楚' }],
    checks: [
      { q: COST_REVIEW.checks![0].q, answer: '新的参考答案', point: '只算次优那一项' },
      { q: '新的一道题', answer: '答案', point: '不存在的要点' },
    ],
  };
  const many = {
    oneLine: '第三次',
    points: Array.from({ length: 9 }, (_, i) => ({
      label: '要点 ' + i,
      status: 'partial' as const,
      note: '',
    })),
    examples: Array.from({ length: 8 }, (_, i) => '例子 ' + i),
    misconceptions: [],
    related: Array.from({ length: 10 }, (_, i) => '概念 ' + i),
    checks: Array.from({ length: 8 }, (_, i) => ({ q: '题 ' + i, answer: '答 ' + i })),
  };
  const steps: unknown[] = [];
  const record = (label: string, n: import('../src/lib/notes.ts').Note) =>
    steps.push({
      label,
      note: n,
      markdown: N.noteMarkdown(n),
      weak: N.weakOf(n),
      tally: N.tally(n),
    });
  let n = N.emptyNote('n1', '机会成本', NOW);
  n = N.mergeReview(
    n,
    COST_REVIEW,
    from('s1', NOW + 1000, {
      context: '周末打工',
      terms: [{ term: '稀缺性', definition: '有限', relation: '' }],
    }),
  );
  record('第一次讨论', n);
  n = N.mergeReview(n, second, from('s2', NOW + 2000, { mode: 'debate' }));
  record('第二次讨论', n);
  n = N.mergeReview(n, second, from('s2', NOW + 3000, { mode: 'debate' }));
  record('同一次讨论重新复盘', n);
  n = N.checkPoint(n, n.checks[0], false, NOW + 4000);
  record('自检没答出', n);
  n = N.checkPoint(
    n,
    n.checks.find(c => c.point === '区别于沉没成本')!,
    true,
    NOW + 5000,
  );
  record('自检答出来了', n);
  n = N.checkPoint(n, { q: '没挂要点', answer: 'x' }, true, NOW + 6000);
  record('自检题没挂要点', n);
  n = N.mergeReview(n, many, from('s3', NOW + 7000));
  record('超过上限', n);
  return {
    // 原生版 NotesTest 用同样的输入走一遍同样的步骤
    inputs: { now: NOW, cost: COST_REVIEW, second, many },
    steps,
    same: [
      ['机会成本', '机会成本 '],
      ['ＡＢＣ', 'abc'],
      ['沉没 成本', '沉没成本'],
      ['机会成本', '沉没成本'],
    ].map(([a, b]) => ({ a, b, same: N.sameConcept(a, b) })),
  };
}

async function searchCases() {
  const { searchCards, normalizeForSearch } = await import('../src/lib/search.ts');
  const { emptyNote } = await import('../src/lib/notes.ts');
  const note = (id: string, concept: string, extra: object) => ({
    ...emptyNote(id, concept, NOW),
    ...extra,
  });
  const cards = [
    note('a', '机会成本', {
      summary: '放弃的最好选择的价值',
      useWhen: '周末打工的代价',
      points: [{ label: '包含隐性代价', status: 'mastered', note: '' }],
    }),
    note('b', '沉没成本', {
      summary: '已经花掉、收不回的成本',
      examples: ['健身卡'],
      misconceptions: [{ said: '花了钱就要用', fix: '不影响决策' }],
    }),
    note('c', '边际效用', {
      terms: [{ term: '机会成本', definition: '放弃的最好选择', relation: '' }],
      boundaries: ['不是总效用'],
      related: ['满足感'],
    }),
    note('d', 'Bayes 定理', {
      summary: '用证据更新信念',
      sources: [
        {
          sessionId: 's',
          at: NOW,
          mode: 'socratic',
          topic: '体检结果的意义',
          oneLine: '',
          source: 'demo',
        },
      ],
    }),
  ];
  const queries = [
    '',
    '  ',
    '机会成本',
    '成本',
    '成本 决策',
    'ｂａｙｅｓ',
    'BAYES',
    '边际',
    '效用 满足',
    '不存在',
    '最好\u3000选择',
    'Ⅻ',
    '①',
    '健身卡',
    '总效用',
    '体检',
    '隐性',
    '满足感',
  ];
  return {
    cards,
    queries: queries.map(q => ({
      q,
      ids: searchCards(cards as never, q).map(c => c.id),
      norm: normalizeForSearch(q),
    })),
  };
}

async function roomCases() {
  const R = await import('../src/lib/room.ts');
  const { defaultConfig } = await import('../src/store.ts');
  const ticking = Date.now;
  Date.now = () => NOW;
  const persona = { id: 'p1', name: '苏珩', title: '追问者', persona: '…', tone: 'a' as const };
  const roundtable = {
    mode: 'roundtable' as const,
    concept: '机会成本',
    topic: '周末打工的代价是什么？',
    keyPoints: ['放弃的最佳选择', '包含隐性代价'],
    personas: [persona],
    opening: '我们开始吧。',
    seats: [],
    stages: [],
    turns: [],
    model: 'm',
    turnSeconds: 60,
  };
  const config = { ...defaultConfig(), counts: { A: 2, B: 1 }, turnSeconds: 90 };
  const plan = {
    mode: 'debate' as const,
    concept: '边际效用',
    topic: '  辩题  ',
    keyPoints: [' a ', '', 'b', 'c', 'd', 'e', 'f', 'g'],
    opening: '',
    personas: [],
    source: 'api' as const,
    model: 'm',
    previous: null,
    context: '  经过  ',
  };
  const debateSetup = R.setupFrom(plan, { ...config, remote: { url: 'https://x' } });
  const members = [
    { id: 'me', name: '小明', host: true, online: true, seat: 'A1' },
    { id: 'u2', name: '小红', host: false, online: false, seat: 'B1', typing: true },
  ];
  const msg = (id: string, memberId: string | undefined, skipped = false) => ({
    id,
    kind: 'msg' as const,
    who: memberId ? 'learner' : 'p1',
    name: memberId ? '同学' : '苏珩',
    text: '发言 ' + id,
    source: memberId ? ('human' as const) : ('api' as const),
    memberId,
    skipped: skipped || undefined,
  });
  const view = (setup: typeof roundtable | typeof debateSetup, extra: Record<string, unknown>) => ({
    id: 'r'.repeat(22),
    status: 'live' as const,
    setup,
    members,
    entries: [
      { id: 's0', kind: 'sys' as const, text: '讨论题：…' },
      msg('m1', 'me'),
      msg('m2', 'u2'),
      msg('m3', undefined),
      msg('m4', 'me', true),
    ],
    covered: [1],
    cursor: 1,
    speaker: { kind: 'member' as const, id: 'u2' },
    aiBusy: null,
    error: '',
    aiCalls: 3,
    expiresAt: NOW + 3_600_000,
    now: NOW + 5000,
    turnStartedAt: NOW,
    voiceAvailable: true,
    ...extra,
  });
  const link = {
    backend: 'https://api.example.dev',
    roomId: 'r'.repeat(22),
    token: 'tok',
    invite: 'inv',
    memberId: 'me',
    host: true,
    connected: true,
    closed: '',
    reflections: [
      { id: 'rf', kind: 'reflect' as const, text: '小结', feedback: '好', draft: '', after: 'm2' },
    ],
  };
  const views = [
    view(roundtable, {}),
    view(roundtable, {
      status: 'finished',
      speaker: { kind: 'ai', id: 'p1' },
      aiBusy: '苏珩正在发言',
      aiPaused: true,
    }),
    view(debateSetup, {
      status: 'lobby',
      speaker: null,
      proposals: [{ id: 'q', text: '新要点', source: 'ai', proposer: 'AI', by: '' }],
    }),
    view(debateSetup, { cursor: 0, error: 'AI 发言失败', listenAvailable: true }),
    view(debateSetup, { cursor: 1 }),
    view(debateSetup, { cursor: 2, members: [members[0]] }),
  ];
  const prev = { ...R.toSession(views[0], null, link), draft: '草稿', created: NOW - 999 };
  const sessions = views.map((v, i) => {
    const s = R.toSession(v, i === 1 ? prev : null, link);
    return {
      view: v,
      prev: i === 1 ? prev : null,
      session: s,
      mySpeakingTurn: R.mySpeakingTurn(s),
      speakerLabel: R.speakerLabel(s),
      deadline: R.deadlineOf(v, NOW + 100_000),
    };
  });
  const hashes = [
    '#join=' +
      Buffer.from(
        JSON.stringify({ b: 'https://api.example.dev/', r: 'r'.repeat(22), c: 'inv' }),
      ).toString('base64url'),
    '#join=' +
      Buffer.from(JSON.stringify({ b: 'http://localhost:8787', r: 'x', c: 'y' })).toString(
        'base64url',
      ),
    '#join=' +
      Buffer.from(JSON.stringify({ b: 'http://example.com', r: 'x', c: 'y' })).toString(
        'base64url',
      ),
    '#join=' +
      Buffer.from(JSON.stringify({ b: 'https://a.dev', r: 1, c: 'y' })).toString('base64url'),
    '#join=不是',
    '#other',
  ];
  const out = {
    sessions,
    invites: hashes.map(hash => ({ hash, parsed: R.parseInvite(hash) })),
    links: [
      {
        link: { backend: 'https://api.example.dev', roomId: 'r'.repeat(22), invite: 'inv' },
        base: 'https://maxlen727.github.io/debating-ground/app/',
      },
    ].map(c => ({ ...c, url: R.inviteLink(c.link, c.base) })),
    setups: [
      { plan, config, setup: debateSetup },
      {
        plan: { ...plan, mode: 'roundtable', personas: [persona], opening: '开场' },
        config: { ...config, turnSeconds: 45 },
        setup: R.setupFrom(
          { ...plan, mode: 'roundtable', personas: [persona], opening: '开场' } as never,
          { ...config, turnSeconds: 45, remote: { url: '' } },
        ),
      },
    ],
  };
  Date.now = ticking;
  return out;
}

function speechCases() {
  const long = '机会成本是你为了得到某样东西而放弃的最好的其他选择。'.repeat(30);
  const inputs = [
    '',
    '   ',
    '你好。',
    '  第一句。\n\n  第二句！第三句？  ',
    long,
    '没有标点'.repeat(200),
    'Hello world. This is English! Mixed 中文；还有分号;以及换行\n继续',
    '表情😀和生僻字𠀀也要按一个字算。'.repeat(40),
    '很长'.repeat(3000),
    'tab\t和\u3000全角空格\u00a0不换行空格',
  ];
  return inputs.map(text => ({ text, parts: splitForSpeech(text) }));
}

// ---------- 学习流程的剧本 ----------
type Op = Record<string, unknown> & { op: string };

// 剧本里贴的材料：有节名，演示模式的研读会按节整理出技术点
const MATERIAL_TEXT = [
  '摘要',
  '这篇短文讲稀缺性和取舍：资源有限，选了一样就要放下另一样，放下的里面最好的那个就是代价。',
  '1 稀缺性',
  '时间、钱和注意力都是有限的。想要的东西总比能拥有的多，所以每个人都要做选择，每个选择都意味着放弃。',
  '2 取舍',
  '取舍就是在几个选项之间权衡：多上一节课，就少睡一会儿；多买一件衣服，就少存一点钱。',
  '3 沉没成本',
  '已经花掉、拿不回来的钱和时间，不应该影响接下来的决定。只比较从现在开始的得失。',
  '结论',
  '做决定时，看清放弃了什么，比看清得到了什么更难，也更重要。',
].join('\n');
export const FLOW_SCRIPTS: { name: string; ops: Op[] }[] = [
  {
    name: 'socratic-full',
    ops: [
      { op: 'config', concept: '机会成本', mode: 'socratic' },
      { op: 'prepare' },
      { op: 'start' },
      { op: 'send', text: '   ' },
      { op: 'send', text: '机会成本就是为了得到一样东西而放弃的最好的那个选择。' },
      { op: 'quick', kind: 'unclear', target: 'lastAi' },
      { op: 'quick', kind: 'disagree', target: 'lastAi' },
      { op: 'send', text: '健身卡的钱已经花了，不该算。' },
      { op: 'reflectSubmit', draft: '' },
      { op: 'reflectSubmit', draft: '代价不只是钱，还有放弃的最好选择。' },
      { op: 'addReflection' },
      { op: 'addReflection' },
      { op: 'dropReflection' },
      { op: 'hint' },
      { op: 'suggestPoint' },
      { op: 'decidePoint', accept: false },
      { op: 'suggestPoint' },
      { op: 'decidePoint', accept: true },
      { op: 'proposePoint', text: '放弃的最佳选择' },
      { op: 'proposePoint', text: '机会成本和会计成本的区别' },
      { op: 'suggestPoint' },
      { op: 'term', term: '稀缺性' },
      { op: 'term', term: '稀缺性' },
      { op: 'openBranch', topic: '稀缺性' },
      { op: 'send', text: '比如你周六去打工，就不能去做家教。' },
      { op: 'bringBack', gist: '东西有限，所以选了一样就得放弃别的。' },
      { op: 'send', text: '所以机会成本只算放弃的那个最好的。' },
      { op: 'finish' },
      { op: 'check', index: 0, ok: true },
      { op: 'check', index: 1, ok: false },
      { op: 'discussAgain' },
      { op: 'start' },
      // 讲给 AI 听：从笔记开始，讲完的复盘合并回同一篇笔记（#142）
      { op: 'teachBack' },
      { op: 'send', text: '机会成本就是你选了一样东西，放弃掉的选项里最好的那个。' },
      { op: 'send', text: '比如周六打工赚 300，放弃了做家教的 500，机会成本是 500，不是 800。' },
      { op: 'finish' },
    ],
  },
  {
    name: 'roundtable-generic',
    ops: [
      { op: 'config', concept: '边际效用', mode: 'roundtable' },
      { op: 'prepare', then: 'start' },
      { op: 'send', text: '我觉得就是越吃越不想吃。' },
      { op: 'nextSpeaker' },
      // 圆桌里展开分支：由说那句话的嘉宾单独接着聊，不轮流，也不请下一位（#142）
      { op: 'openBranch', topic: '总效用' },
      { op: 'send', text: '总效用就是所有加起来的满足吗？' },
      { op: 'nextSpeaker' },
      { op: 'goToBranch', to: 'trunk' },
      { op: 'send', text: '那第三块蛋糕还值得吃吗？' },
      { op: 'quick', kind: 'example', target: 'lastAi' },
      { op: 'quick', kind: 'fact', target: 'lastAi' },
      { op: 'reflectSubmit', draft: '每多一份带来的满足在变少。' },
      { op: 'suggestPoint' },
      { op: 'suggestPoint' },
      { op: 'finish' },
    ],
  },
  {
    name: 'canvas-tree',
    ops: [
      { op: 'config', concept: '机会成本', mode: 'canvas' },
      { op: 'prepare', then: 'start' },
      { op: 'send', text: '少玩了一天' },
      { op: 'openBranch', target: 'lastAi', topic: '' },
      { op: 'openBranch', target: 'lastAi', topic: '稀缺性' },
      { op: 'send', text: '比如周末的时间' },
      { op: 'openBranch', target: 'lastAi', topic: '时间的价值' },
      { op: 'bringBack', gist: '' },
      { op: 'bringBack', gist: '时间也是一种稀缺的资源' },
      { op: 'addReflection' },
      { op: 'goToBranch', to: 'trunk' },
      { op: 'goToBranch', to: 'first' },
      { op: 'bringBack', gist: '时间和钱一样有限，用在一处就不能用在别处' },
      { op: 'send', text: '那做家教也是代价' },
      { op: 'finish' },
    ],
  },
  {
    name: 'debate-match',
    ops: [
      { op: 'config', concept: '机会成本', mode: 'debate' },
      { op: 'prepare' },
      { op: 'refreshRoles' },
      { op: 'start' },
      { op: 'send', text: '我方认为，上大学最大的成本不是学费，而是放弃的四年工资和工作经验。' },
      { op: 'generateSeat' },
      { op: 'quick', kind: 'unclear', target: 'lastAi' },
      { op: 'quick', kind: 'disagree', target: 'lastAi' },
      { op: 'hint' },
      { op: 'generateSeat' },
      { op: 'skipSeat' },
      { op: 'generateSeat' },
      { op: 'generateSeat' },
      { op: 'send', text: '请问对方：如果学费全免，上大学还有成本吗？' },
      { op: 'suggestPoint' },
      { op: 'untilEnd' },
      { op: 'finish' },
    ],
  },
  {
    name: 'edge-cases',
    ops: [
      { op: 'config', concept: '   ', mode: 'socratic' },
      { op: 'prepare' },
      { op: 'config', concept: '认知失调', mode: 'debate' },
      { op: 'seatsAllAi' },
      { op: 'prepare', then: 'start' },
      { op: 'start' },
      { op: 'config', concept: '认知失调', mode: 'socratic' },
      { op: 'prepare', then: 'start' },
      { op: 'send', text: '就是想法和做法不一致的时候会难受。' },
      { op: 'config', concept: '认知失调', mode: 'socratic' },
      { op: 'prepare', then: 'start' },
      { op: 'finish' },
    ],
  },
  // 读材料（#144、#154）：太短的材料不收；贴一篇演示材料，研读助手整理出地图，从一个技术点开始讨论
  {
    name: 'from-material',
    ops: [
      { op: 'addMaterial', title: '', text: '太短了' },
      { op: 'addMaterial', title: '', text: MATERIAL_TEXT },
      { op: 'startFromPoint', index: 0, mode: 'roundtable' },
      { op: 'send', text: '稀缺就是想要的比能有的多，所以选了一样就得放下另一样。' },
      { op: 'finish' },
    ],
  },
];

const graphShape = (s: Parameters<typeof graphOf>[0]) => {
  const { rows, lanes, edges, width } = graphOf(s);
  return { rows, lanes, edges, width };
};

/* ---------- 直接连接服务商：原生版移植的部分（地址检查、流式显示过滤、调用和错误文案） ---------- */
export const DIRECT_KEY = 'sk-test-0123456789';
const chat = (content: string, finish = 'stop') =>
  JSON.stringify({ choices: [{ message: { content }, finish_reason: finish }] });
const sse = (...events: unknown[]) =>
  events.map(e => 'data: ' + (typeof e === 'string' ? e : JSON.stringify(e)) + '\n\n').join('');
const delta = (content: string, finish: string | null = null) => ({
  choices: [{ delta: { content }, finish_reason: finish }],
});
// 服务商可能返回的几种情况。stream：这次是不是请求了流式输出
export const HTTP_CASES: {
  name: string;
  stream: boolean;
  status: number;
  type: string;
  body: string;
}[] = [
  {
    name: 'json',
    stream: false,
    status: 200,
    type: 'application/json',
    body: chat('  机会成本是放弃的最好选择。  '),
  },
  {
    name: 'json-key',
    stream: false,
    status: 200,
    type: 'application/json',
    body: chat(`密钥是 ${DIRECT_KEY} 哦`),
  },
  {
    name: 'truncated',
    stream: false,
    status: 200,
    type: 'application/json',
    body: chat('半截', 'length'),
  },
  { name: 'empty', stream: false, status: 200, type: 'application/json', body: chat('   ') },
  { name: 'not-json', stream: false, status: 200, type: 'text/html', body: '<html>出错了</html>' },
  {
    name: 'too-long',
    stream: false,
    status: 200,
    type: 'application/json',
    body: chat('长'.repeat(20001)),
  },
  ...[401, 403, 404, 429, 500].map(status => ({
    name: 'status-' + status,
    stream: false,
    status,
    type: 'application/json',
    body: '{"error":{"message":"nope"}}',
  })),
  { name: 'redirect', stream: false, status: 302, type: 'text/plain', body: '' },
  {
    name: 'stream',
    stream: true,
    status: 200,
    type: 'text/event-stream',
    body: sse(
      delta('机会'),
      delta('成本是'),
      delta(`放弃的 ${DIRECT_KEY.slice(0, 5)}`),
      delta(DIRECT_KEY.slice(5) + ' 最好选择。\n['),
      delta('[要点:1]]'),
      { comment: 'ignored' },
      'not json',
      delta('', 'stop'),
      '[DONE]',
    ),
  },
  {
    name: 'stream-error',
    stream: true,
    status: 200,
    type: 'text/event-stream',
    body: sse(delta('说到一半'), { error: { message: 'overloaded' } }),
  },
  {
    name: 'stream-truncated',
    stream: true,
    status: 200,
    type: 'text/event-stream',
    body: sse(delta('说到一半'), delta('', 'length')),
  },
  // 请求了流式，服务商却直接回了 JSON：按普通请求处理
  {
    name: 'stream-as-json',
    stream: true,
    status: 200,
    type: 'application/json',
    body: chat('一次说完。[[术语:稀缺性]]'),
  },
];

async function directCases() {
  const { visibleText, normalizeConfig, complete } = await import('../server/ai.ts');
  const visible = [
    ['你好', ''],
    ['你好[[要点:1]]', ''],
    ['你好\n[', ''],
    ['  前后有空格  ', ''],
    ['说到 [半截', ''],
    [`泄露 ${DIRECT_KEY} 了`, DIRECT_KEY],
    [`末尾是密钥开头 ${DIRECT_KEY.slice(0, 6)}`, DIRECT_KEY],
    [`两次 ${DIRECT_KEY}${DIRECT_KEY}`, DIRECT_KEY],
    ['没有密钥', DIRECT_KEY],
    ['[[术语:稀缺性]] 开头就是标记', DIRECT_KEY],
  ].map(([raw, key]) => ({ raw, key, out: visibleText(raw, key) }));
  const configs = [
    { baseURL: 'https://api.example.com/v1', model: 'm1', apiKey: DIRECT_KEY },
    { baseURL: 'https://api.example.com/v1/', model: ' m1 ', apiKey: ` ${DIRECT_KEY} ` },
    { baseURL: 'https://api.example.com/v1/chat/completions', model: 'm1', apiKey: 'k' },
    { baseURL: 'https://api.example.com', model: 'm1', apiKey: 'k' },
    { baseURL: 'http://localhost:11434/v1', model: 'm1', apiKey: 'k' },
    { baseURL: 'http://127.0.0.1:8080', model: 'm1', apiKey: 'k' },
    { baseURL: 'http://api.example.com/v1', model: 'm1', apiKey: 'k' },
    { baseURL: 'https://user:pw@api.example.com/v1', model: 'm1', apiKey: 'k' },
    { baseURL: 'https://api.example.com/v1?key=1', model: 'm1', apiKey: 'k' },
    { baseURL: 'https://api.example.com/v1#x', model: 'm1', apiKey: 'k' },
    { baseURL: 'api.example.com', model: 'm1', apiKey: 'k' },
    { baseURL: '', model: 'm1', apiKey: 'k' },
    { baseURL: 'https://api.example.com/v1', model: 'm1', apiKey: '' },
    { baseURL: 'https://api.example.com/v1', model: 'm1', apiKey: 'a\nb' },
    { baseURL: 'https://api.example.com/v1', model: '', apiKey: 'k' },
    { baseURL: 'https://api.example.com/v1', model: 'm'.repeat(201), apiKey: 'k' },
    { baseURL: 'https://API.Example.com:443/v1', model: 'm1', apiKey: 'k' },
  ].map(input => {
    try {
      const c = normalizeConfig(input);
      return { input, endpoint: c.endpoint, model: c.model };
    } catch (e) {
      return { input, error: (e as Error).message };
    }
  });
  const http = [];
  for (const c of HTTP_CASES) {
    const shown: string[] = [];
    const fetchImpl = (async () =>
      new Response(c.status === 302 ? null : c.body, {
        status: c.status,
        headers: {
          'Content-Type': c.type,
          ...(c.status === 302 ? { Location: 'https://evil.example' } : {}),
        },
      })) as typeof fetch;
    let outcome: { text?: string; error?: string };
    try {
      const r = await complete(
        {
          endpoint: 'https://api.example.com/v1/chat/completions',
          apiKey: DIRECT_KEY,
          model: 'm1',
        },
        [{ role: 'user', content: '你好' }],
        { fetchImpl, onText: c.stream ? v => shown.push(v) : undefined },
      );
      outcome = { text: r.text };
    } catch (e) {
      outcome = { error: (e as Error).message };
    }
    http.push({ ...c, shown, ...outcome });
  }
  return { key: DIRECT_KEY, visible, configs, http };
}

// 按同一套规则从状态里找操作的目标（原生版 FlowTest 用同样的规则）
type St = ReturnType<typeof import('../src/store.ts').getState>;
const lastAi = (st: St) => st.session!.entries.findLast(e => e.kind === 'msg' && !e.human)!;
const lastReflection = (st: St) => st.session!.entries.findLast(e => e.kind === 'reflect');

const ID_KEYS = new Set([
  'id',
  'sessionId',
  'from',
  'parent',
  'branch',
  'back',
  'messageId',
  'openCard',
  'sheet',
  'at',
]);
const TIME_KEYS = new Set(['created', 'due', 'at', 'updated', 'checked']);
// 规整：id 换成按出现顺序编号的占位符，时间戳换成 <t>。键按字母顺序遍历，两边的顺序一致
export function canonical(v: unknown, ids = new Map<string, string>(), key = ''): unknown {
  if (Array.isArray(v)) return v.map(x => canonical(x, ids, key));
  if (v && typeof v === 'object') {
    const out: Record<string, unknown> = {};
    for (const k of Object.keys(v).sort()) {
      const x = (v as Record<string, unknown>)[k];
      if (x === undefined) continue;
      out[k] = canonical(x, ids, k);
    }
    return out;
  }
  if (typeof v === 'number' && TIME_KEYS.has(key)) return '<t>';
  if (typeof v === 'string' && ID_KEYS.has(key)) {
    if (!ids.has(v)) ids.set(v, `#${ids.size + 1}`);
    return ids.get(v);
  }
  return v;
}

async function recordFlows(
  S: typeof import('../src/store.ts'),
  A: typeof import('../src/actions.ts'),
) {
  const scripts = [];
  for (const script of FLOW_SCRIPTS) {
    S.resetForTests();
    // 每段剧本都从干净的初始状态开始（不带上一段剧本留下的设置、页面和存档）
    S.update(s => {
      s.config = S.defaultConfig();
      s.library = [];
      s.session = null;
      s.plan = null;
      s.ui.screen = 'learn';
    });
    const steps = [];
    for (const o of script.ops) {
      const st = S.getState();
      switch (o.op) {
        case 'config':
          S.update(s => {
            s.config.concept = o.concept as string;
            s.config.mode = o.mode as never;
          });
          break;
        case 'seatsAllAi':
          S.update(s => {
            for (const t of ['A', 'B'] as const) s.config.seats[t].forEach(x => (x.type = 'ai'));
          });
          break;
        case 'prepare':
          await A.prepare(null, undefined, (o.then as 'start' | undefined) ?? 'review');
          break;
        case 'refreshRoles':
          await A.refreshRoles();
          break;
        case 'start':
          A.startSession();
          break;
        case 'send':
          await A.send(o.text as string);
          break;
        case 'quick':
          await A.quick(o.kind as never, lastAi(st).id);
          break;
        case 'addReflection':
          A.addReflection();
          break;
        case 'dropReflection':
          A.dropReflection(lastReflection(st)!.id);
          break;
        case 'reflectSubmit': {
          if (
            !lastReflection(st) ||
            lastReflection(st)!.kind !== 'reflect' ||
            (lastReflection(st) as { text: string }).text
          )
            A.addReflection();
          const r = lastReflection(S.getState())!;
          A.setReflectionDraft(r.id, o.draft as string);
          await A.submitReflection(r.id);
          break;
        }
        case 'hint':
          await A.hint();
          break;
        case 'proposePoint':
          A.proposePoint(o.text as string);
          break;
        case 'suggestPoint':
          await A.suggestPoint();
          break;
        case 'decidePoint':
          A.decidePoint(st.session!.proposedPoints![0].id, o.accept as boolean);
          break;
        case 'term': {
          const m = st.session!.entries.findLast(
            e => e.kind === 'msg' && e.terms?.includes(o.term as string),
          )!;
          await A.openTerm(o.term as string, m.id);
          break;
        }
        case 'teachBack':
          A.teachBack(st.library[0]);
          break;
        case 'nextSpeaker':
          await A.nextSpeaker();
          break;
        case 'openBranch':
          await A.openBranch(lastAi(st).id, o.topic as string);
          break;
        case 'goToBranch':
          A.goToBranch(o.to === 'trunk' ? undefined : st.session!.branches![0].id);
          break;
        case 'bringBack':
          A.bringBack(o.gist as string);
          break;
        case 'generateSeat':
          await A.generateSeat();
          break;
        case 'skipSeat':
          A.skipSeat();
          break;
        case 'untilEnd':
          // 把剩下的赛程走完：轮到“我”就发一句，轮到 AI 就生成
          for (let guard = 0; guard < 40 && A.currentTurn(S.getState().session); guard++) {
            const t = A.currentTurn(S.getState().session)!;
            if (t.role.type === 'human')
              await A.send(`${t.action}：我方坚持机会成本才是最大的代价。`);
            else await A.generateSeat();
          }
          break;
        case 'finish':
          await A.finish();
          break;
        case 'check':
          A.selfCheck(S.getState().library[0].id, o.index as number, o.ok as boolean);
          break;
        case 'addMaterial':
          await A.addMaterial(o.title as string, o.text as string);
          break;
        case 'startFromPoint':
          await A.startFromPoint(
            S.getState().ui.material!.map!.points[o.index as number],
            o.mode as never,
          );
          break;
        case 'discussAgain':
          await A.discussAgain(S.getState().library[0]);
          break;
        default:
          throw new Error(`未知操作 ${o.op}`);
      }
      const now = S.getState();
      const ui = now.ui;
      steps.push({
        state: canonical({
          config: now.config,
          plan: now.plan,
          session: now.session,
          library: now.library,
          ui: {
            screen: ui.screen,
            busy: ui.busy,
            error: ui.error,
            toast: ui.toast,
            hint: ui.hint,
            explain: ui.explain,
            sheet: ui.sheet,
            openCard: ui.openCard,
            term: ui.term,
            context: ui.context,
          },
        }),
        snapshot: now.session ? canonical(A.snapshot(now.session)) : null,
        whole: now.session ? canonical(A.snapshot(now.session, true)) : null,
        // 知识画布的分支图（laneOf 的键是原始 id，不比；同样的信息在 rows 里）
        graph: now.session?.mode === 'canvas' ? canonical(graphShape(now.session)) : null,
      });
      // 提示只算这一步的
      S.update(s => {
        s.ui.toast = '';
      });
    }
    scripts.push({ name: script.name, ops: script.ops, steps });
  }
  return scripts;
}

async function readerCases() {
  const R = await import('../src/lib/reader.ts');
  const { SAMPLE_MATERIAL } = await import('../src/lib/demo.ts');
  const para = (n: number, len: number) =>
    Array.from({ length: n }, (_, i) => `第${i + 1}段：` + '边际成本随产量变化。'.repeat(len)).join(
      '\n\n',
    );
  const texts: Record<string, string> = {
    sample: SAMPLE_MATERIAL.text,
    markdown: `# 注意力机制\n\n导语一句。\n\n## 1 背景\n\n序列模型很难记住远处的词。\n\n## 2 方法\n\nQuery、Key、Value 三个矩阵。\n\n### 2.1 缩放\n\n除以根号 d。\n\n## 参考文献\n\n[1] Vaswani 2017.`,
    // 全角空格、CRLF、带编号的节名，像句子的行不算标题
    numbered: `Abstract\r\nWe study scarcity.\r\n\r\n1\u3000Introduction\r\nPeople choose.\r\n2.1 这一行很像句子，\r\n不是标题。\r\n3. Results\r\nIt works.\r\nCONCLUSION\r\nDone.`,
    unstructured: para(12, 40),
    longSection: '方法\n' + para(3, 160) + '\n\n结论\n很短的结论。',
    many: Array.from(
      { length: 95 },
      (_, i) => `## 小节${i + 1}\n内容${i + 1}：` + '稀缺'.repeat(30),
    ).join('\n\n'),
    onlyIntro: '开头的一段话，没有任何标题。\n\n第二段也没有。',
  };
  return Object.entries(texts).map(([name, text]) => {
    const sections = R.splitMaterial(text);
    const n = sections.length;
    return {
      name,
      text,
      sections,
      outline: R.outlineOf(sections),
      reads: [
        { ns: [1], max: 12000 },
        { ns: [1, 2, 99], max: 12000 },
        { ns: sections.map(x => x.n), max: 600 },
        { ns: [n, 1], max: 80 },
      ].map(r => ({ ...r, out: R.readSections(sections, r.ns, r.max) })),
      searches: ['机会成本', 'ＣＯＮＣＬＵＳＩＯＮ', '稀缺', '不存在的词', '  ', 'd'].map(q => ({
        q,
        out: R.searchMaterial(sections, q),
      })),
      excerpts: [
        { name: '缩放', why: '', sections: [4, 5], needs: [] },
        { name: '机会成本', why: '', sections: [], needs: [] },
        { name: '找不到的点', why: '', sections: [999], needs: [] },
      ].map(point => ({ point, out: R.excerptFor(name, sections, point) })),
      tools: [
        { thought: '先读', action: 'read' as const, sections: [1, 2] },
        { thought: '搜一下', action: 'search' as const, query: '成本' },
      ].map(a => ({ a, out: R.runTool(sections, a) })),
    };
  });
}

async function demoReadCases() {
  const R = await import('../src/lib/reader.ts');
  const { demoRun, SAMPLE_MATERIAL } = await import('../src/lib/demo.ts');
  const out = [];
  for (const [title, text] of [
    [SAMPLE_MATERIAL.title, SAMPLE_MATERIAL.text],
    ['只有开头', '开头的一段话，没有任何标题。\n\n第二段也没有。'],
    [
      '注意力',
      '# 注意力机制\n\n## 1 背景\n\n序列模型。\n\n## 2.1 方法（续）\n\n矩阵。\n\n## Conclusions\n\n完。',
    ],
  ]) {
    const outline = R.outlineOf(R.splitMaterial(text));
    const first = { title, outline, steps: [] };
    const second = {
      title,
      outline,
      steps: [{ thought: 't', action: 'a', observation: 'o' }],
    };
    out.push({ payload: first, out: await demoRun('read', first, 0) });
    out.push({ payload: second, out: await demoRun('read', second, 0) });
  }
  return out;
}

function qrCases() {
  const texts = [
    'A',
    'http://192.168.1.23:4174/#join=eyJiIjoiaHR0cDovLzE5Mi4xNjguMS4yMzo0MTc0IiwiciI6IkFiQ2RFZkdoSWpLbE1uT3BRclN0VXYiLCJjIjoieHl6In0',
    '中文邀请链接 http://10.0.0.2:4174/',
    'https://sibian.example.workers.dev/#join=' + 'abcDEF_-'.repeat(40),
    'x'.repeat(1200),
  ];
  const rows = (q: QrCode) => q.modules.map(r => r.map(c => (c ? '1' : '0')).join(''));
  return texts.map(text => {
    const q = encodeQr(text)!;
    const fixed = encodeQr(text, 6)!;
    return { text, version: q.version, mask: q.mask, rows: rows(q), mask6: rows(fixed) };
  });
}

if (process.argv[1] && fileURLToPath(import.meta.url) === process.argv[1]) {
  const files = await generate();
  mkdirSync(FIXTURE_DIR, { recursive: true });
  for (const [name, text] of Object.entries(files)) writeFileSync(join(FIXTURE_DIR, name), text);
  console.log(`已生成 ${Object.keys(files).length} 个文件到 ${FIXTURE_DIR}`);
}

export const readFixture = (name: string) => readFileSync(join(FIXTURE_DIR, name), 'utf8');
