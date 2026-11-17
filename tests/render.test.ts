// 界面冒烟测试：把每个页面在几种典型状态下渲染成 HTML，确认不会崩溃、关键文字在。
// Node 不能直接运行 .tsx，借项目已有的 Vite 加载组件（不新增依赖）。不跑副作用（useEffect），也不检查样式。
import { test, before, after } from 'node:test';
import assert from 'node:assert/strict';
import { createServer, type ViteDevServer } from 'vite';
import { createElement } from 'react';
import { renderToString } from 'react-dom/server';

const store: Record<string, string> = {};
(globalThis as Record<string, unknown>).localStorage = {
  getItem: (k: string) => store[k] ?? null,
  setItem: (k: string, v: string) => {
    store[k] = v;
  },
};

let vite: ViteDevServer;
// 页面、store 和 actions 都要从同一个 Vite 实例加载，才是同一份状态
const load = <T>(path: string) => vite.ssrLoadModule(path) as Promise<T>;
type Store = typeof import('../src/store.ts');
type Actions = typeof import('../src/actions.ts');
let S: Store, A: Actions, render: (screen: string) => Promise<string>;

before(async () => {
  vite = await createServer({
    configFile: false,
    logLevel: 'silent',
    server: { middlewareMode: true, hmr: false },
    appType: 'custom',
    optimizeDeps: { noDiscovery: true, include: [] },
    // React 交给 Node 自己加载：和测试里用的是同一份，组件的 hooks 才能用
    ssr: { external: ['react', 'react-dom', 'react/jsx-runtime', 'react/jsx-dev-runtime'] },
    esbuild: { jsx: 'automatic' },
  });
  S = await load<Store>('/src/store.ts');
  A = await load<Actions>('/src/actions.ts');
  (await load<typeof import('../src/lib/ai.ts')>('/src/lib/ai.ts')).setDemoDelay(0);
  const { App } = await load<{ App: () => null }>('/src/App.tsx');
  render = async screen => {
    S.update(s => {
      s.ui.screen = screen as never;
    });
    return renderToString(createElement(App));
  };
});
after(async () => {
  await vite?.close();
});

const text = (html: string) => html.replace(/<[^>]+>/g, ' ').replace(/&[a-z]+;/g, ' ');

test('every tab page renders on a fresh install', async () => {
  S.resetForTests();
  for (const screen of ['learn', 'library', 'settings']) {
    const html = await render(screen);
    assert.ok(html.length > 200, screen + ' rendered something');
  }
  assert.match(text(await render('learn')), /想弄懂/);
  assert.match(text(await render('library')), /知识/);
});

test('a demo discussion renders through every step: prepare, session, review, card', async () => {
  S.resetForTests();
  S.update(s => {
    s.config.concept = '机会成本';
    s.config.mode = 'socratic';
  });
  await A.prepare();
  assert.match(text(await render('prepare')), /讨论题/);
  A.startSession();
  await A.send('机会成本是为了得到一样东西而放弃的最好选择。');
  const session = text(await render('session'));
  assert.match(session, /机会成本是为了得到一样东西/, 'the learner message is shown');
  assert.match(session, /给点提示/);
  await A.finish();
  assert.equal(S.getState().ui.screen, 'review');
  assert.ok(S.getState().session!.review, 'the review exists');
  assert.ok(text(await render('review')).length > 50);
  // 复盘完成就存成知识卡片
  const card = S.getState().library[0];
  assert.ok(card, 'a knowledge card was saved');
  S.update(s => {
    s.ui.openCard = card.id;
  });
  assert.ok(text(await render('card')).includes('机会成本'));
});

test('the other discussion modes render their session page', async () => {
  for (const mode of ['roundtable', 'debate', 'canvas'] as const) {
    S.resetForTests();
    S.update(s => {
      s.config.concept = '机会成本';
      s.config.mode = mode;
    });
    await A.prepare(null, undefined, 'start');
    assert.equal(S.getState().ui.screen, 'session', mode + ' started');
    assert.ok(text(await render('session')).includes('机会成本'), mode);
  }
});

test('a damaged save still renders the home page', async () => {
  store['sibian-v3'] = JSON.stringify({
    session: { id: 1, entries: 'bad' },
    plan: 5,
    library: 'x',
    screen: 'session',
    config: { mode: 'zzz' },
  });
  S.resetForTests();
  assert.equal(S.getState().ui.screen, 'learn');
  assert.match(text(await render('learn')), /想弄懂/);
  delete store['sibian-v3'];
});
