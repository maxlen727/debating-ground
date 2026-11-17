// 原生安卓版的“标准答案”：用网页版的真实代码跑出几份存档，以及网页版读回它们之后的结果、导出的 Markdown。
// 原生版（native/）的 FixturesTest 读同样的存档，结果必须逐字一致。这样两边的数据逻辑由测试锁在一起。
// 改了存档结构、存档检查（src/lib/restore.ts）或导出（src/lib/exporter.ts）后运行 npm run fixtures:native；
// tests/native-fixtures.test.ts 会检查这些文件没有过期。
import { mkdirSync, readFileSync, writeFileSync } from 'node:fs';
import { dirname, join } from 'node:path';
import { fileURLToPath } from 'node:url';

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
    const saved = store['sibian-v3'];
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
  await situation('机会成本', 'socratic', () => {
    S.update(s => {
      s.session!.teaching = true;
    });
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

  // 原生测试读回存档时用同一个“现在”和时区
  out['meta.json'] = JSON.stringify({ now: NOW, timeZone: TIME_ZONE }, null, 2) + '\n';
  return out;
}

if (process.argv[1] && fileURLToPath(import.meta.url) === process.argv[1]) {
  const files = await generate();
  mkdirSync(FIXTURE_DIR, { recursive: true });
  for (const [name, text] of Object.entries(files)) writeFileSync(join(FIXTURE_DIR, name), text);
  console.log(`已生成 ${Object.keys(files).length} 个文件到 ${FIXTURE_DIR}`);
}

export const readFixture = (name: string) => readFileSync(join(FIXTURE_DIR, name), 'utf8');
