const { test } = require('node:test'), assert = require('node:assert/strict'), vm = require('node:vm'), fs = require('node:fs'), path = require('node:path');

// 在 vm 里加载前端脚本；只为 #app 和 #toast 提供假节点
function setup(fetch) {
  const nodes = new Map(), store = {};
  const node = () => ({ innerHTML: '', textContent: '', classList: { add() { }, remove() { } } });
  const ctx = {
    fetch, AbortController, AbortSignal, setTimeout, clearTimeout, console, URL, Blob,
    document: { querySelector(s) { if (s !== '#app' && s !== '#toast') return null; if (!nodes.has(s)) nodes.set(s, node()); return nodes.get(s); }, querySelectorAll() { return []; } },
    localStorage: { getItem: k => store[k], setItem: (k, v) => store[k] = v }
  };
  vm.createContext(ctx);
  for (const f of ['connection.js', 'app.js']) vm.runInContext(fs.readFileSync(path.join(__dirname, '../dist', f), 'utf8'), ctx);
  return { run: code => vm.runInContext(code, ctx), store, html: () => nodes.get('#app').innerHTML };
}
const json = data => new Response(JSON.stringify(data));
const apiOn = "config.aiMode='api';aiState={available:true,configured:true,model:'chosen'};";
const learningPlan = "plan={mode:'socratic',concept:'机会成本',topic:'代价是什么？',keyPoints:['放弃的最佳选择','区别于沉没成本'],opening:'你觉得呢？',roles:[{id:'r0',tone:'c',name:'苏珩',title:'追问者',persona:''}],source:'api',model:'chosen'};startSession();";

test('API failure keeps the learner message and adds no AI reply', async () => {
  const { run, store } = setup(async () => new Response(JSON.stringify({ error: 'API 返回 401：密钥无效或已过期。' }), { status: 502 }));
  run(apiOn + learningPlan);
  await run("sendLearner('少玩了一天。')");
  assert.equal(run("session.messages.filter(m=>m.kind==='msg').length"), 2);
  assert.equal(run("session.messages.at(-1).who"), 'learner');
  assert.match(run('aiError'), /401/);
  assert.ok(!JSON.stringify(store).includes('apiKey'));
});

test('a reply lights up the key points the model reports', async () => {
  let sent;
  const { run } = setup(async (_u, o) => { sent = JSON.parse(o.body); return json({ text: '那做家教的 500 元呢？', covered: [0], model: 'chosen' }); });
  run(apiOn + learningPlan);
  await run("sendLearner('少玩了一天。')");
  assert.equal(sent.purpose, 'discuss'); assert.equal(sent.payload.roleId, 'r0');
  assert.equal(sent.payload.session.messages.at(-1).name, '学习者');
  assert.equal(run("session.messages.at(-1).source"), 'api');
  assert.deepEqual(JSON.parse(run('JSON.stringify(session.covered)')), [0]);
});

test('debate reply advances exactly one seat and records model provenance', async () => {
  const { run } = setup(async () => json({ text: '我方反对。', covered: [], model: 'chosen' }));
  run(apiOn + "config.roles.A[0].type='ai';config.roles.A[1].type='human';plan={mode:'debate',concept:'机会成本',topic:'学费是最大成本',keyPoints:['a','b'],roles:[],opening:'',source:'api'};startSession();");
  await run('generateSeat()');
  assert.equal(run('session.cursor'), 1);
  assert.equal(run("session.messages.at(-1).source"), 'api'); assert.equal(run("session.messages.at(-1).model"), 'chosen');
});

test('cancel preserves the current seat and ignores the late response', async () => {
  const { run } = setup((_url, { signal }) => new Promise((_, reject) => signal.addEventListener('abort', () => reject(new Error('aborted')))));
  run(apiOn + "config.roles.A[0].type='ai';config.roles.A[1].type='human';plan={mode:'debate',concept:'x',topic:'t',keyPoints:['a','b'],roles:[],opening:'',source:'api'};startSession();");
  const pending = run('generateSeat()'); run('aiBusy.controller.abort()'); await pending;
  assert.equal(run('session.cursor'), 0); assert.match(run('aiError'), /取消/);
});

test('demo mode runs the whole loop: prepare, discuss, reflect, review, knowledge card', async () => {
  const { run } = setup(async () => { throw new Error('demo must not call the network'); });
  await run("config.concept='机会成本';config.mode='socratic';prepare()");
  assert.equal(run('page'), 'prepare'); assert.equal(run('plan.keyPoints.length'), 5);
  run('startSession()');
  for (const t of ['少玩了一天', '只算家教', '健身卡不算']) await run(`sendLearner('${t}')`);
  assert.ok(run('session.covered.length') >= 3);
  assert.equal(run("session.messages.filter(m=>m.kind==='reflect').length"), 1, 'a reflection card appears after the third turn');
  run("session.messages.find(m=>m.kind==='reflect').draft='机会成本是放弃的最好选择'");
  await run("submitReflect(session.messages.find(m=>m.kind==='reflect').id)");
  assert.ok(run("session.messages.find(m=>m.kind==='reflect').feedback").length > 0);
  await run('finishSession()');
  assert.equal(run('page'), 'review'); assert.equal(run('library.length'), 1);
  assert.ok(run('library[0].due') > Date.now() + 23 * 3600000);
  run('openCard=library[0].id;rateCard(openCard,true)');
  assert.equal(run('library[0].level'), 1);
  assert.ok(run('library[0].due') > Date.now() + 2.9 * 86400000);
});
