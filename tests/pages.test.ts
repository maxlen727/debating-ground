// 读材料：从链接取正文（#157）。地址检查（防 SSRF）、该取哪几个地址、正文提取、是不是全文，以及三端的请求和接口。
import { test } from 'node:test';
import assert from 'node:assert/strict';
import type { AddressInfo } from 'node:net';
import {
  arxivId,
  candidatesFor,
  checkTarget,
  decodeBody,
  extractHtml,
  importLink,
  readFetched,
  type Fetched,
} from '../shared/pages.ts';
import { splitMaterial } from '../src/lib/reader.ts';
import { guardedLookup } from '../server/fetchpage.ts';
import { workerGet } from '../worker/src/fetchpage.ts';
import { createServer } from '../server/server.ts';
import { createHandler } from '../worker/src/index.ts';

test('links to this machine, the LAN or odd ports are refused before any request', () => {
  for (const bad of [
    'http://localhost/a',
    'http://127.0.0.1/',
    'http://127.1/',
    'http://2130706433/',
    'http://0x7f000001/',
    'http://10.0.0.5/',
    'http://172.16.3.4/',
    'http://192.168.1.1/',
    'http://169.254.169.254/latest/meta-data',
    'http://100.64.0.1/',
    'http://0.0.0.0/',
    'http://[::1]/',
    'http://[fd00::1]/',
    'http://[fe80::1]/',
    'http://[::ffff:127.0.0.1]/',
    'http://[::ffff:7f00:1]/',
    'http://printer.local/',
    'http://router.lan/',
    'http://intranet/',
    'http://example.com:8080/',
    'https://user:pw@example.com/',
    'ftp://example.com/x',
    'file:///etc/passwd',
    'javascript:alert(1)',
    '不是链接',
  ])
    assert.ok(checkTarget(bad), bad);
  for (const ok of [
    'https://example.com/a?b=1',
    'http://8.8.8.8/',
    'https://zh.wikipedia.org/wiki/机会成本',
  ])
    assert.equal(checkTarget(ok), null, ok);
});

test('arXiv links of every shape go to the HTML full text first, then the abstract', () => {
  for (const link of [
    'https://arxiv.org/abs/2401.01234',
    'https://arxiv.org/pdf/2401.01234',
    'https://arxiv.org/pdf/2401.01234.pdf',
    'https://www.arxiv.org/html/2401.01234',
    'arXiv:2401.01234',
    '2401.01234',
  ])
    assert.equal(arxivId(link), '2401.01234', link);
  assert.equal(arxivId('https://arxiv.org/abs/2401.01234v3'), '2401.01234v3');
  assert.equal(arxivId('https://arxiv.org/abs/cs/0112017'), 'cs/0112017');
  assert.equal(arxivId('https://example.com/abs/2401.01234'), null);
  assert.deepEqual(candidatesFor('https://arxiv.org/abs/2401.01234v2'), [
    { url: 'https://arxiv.org/html/2401.01234v2', kind: 'arxiv-html' },
    { url: 'https://export.arxiv.org/api/query?id_list=2401.01234', kind: 'arxiv-abstract' },
  ]);
  // 没写 https:// 也认；本机地址不认
  assert.deepEqual(candidatesFor('example.com/post/1'), [
    { url: 'https://example.com/post/1', kind: 'page' },
  ]);
  assert.equal(typeof candidatesFor('http://192.168.1.1/'), 'string');
});

const ARTICLE = `<!doctype html><html><head>
<title>站点标题</title><meta property="og:title" content="边际效用 &amp; 选择">
<style>.x{}</style><script>var nav = "导航里的字";</script></head>
<body>
<header><a href="/">首页</a> 登录</header>
<nav><ul><li>导航一</li><li>导航二</li></ul></nav>
<div class="share-buttons">分享到微博</div>
<main><article>
<h1>边际效用</h1>
<p>人们做选择时，看的是<b>多一份</b>带来的好处。&ldquo;边际&rdquo;就是这个意思。
<h2>1 递减</h2>
<p>第一块蛋糕最香，第五块就没那么想吃了。
<p>公式：<math alttext="MU = \\Delta U / \\Delta Q"><mi>M</mi></math> 表示多一份带来的效用。
<ul><li>第一点<li>第二点</ul>
<h2>2 应用</h2>
<p>${'定价、排队和学习时间的分配都用得上边际思考。'.repeat(80)}</p>
<aside>相关阅读：另一篇</aside>
</article></main>
<footer>版权所有 ©2026</footer>
</body></html>`;

test('the article body is extracted with headings as sections, without menus, scripts or footers', () => {
  const page = extractHtml(ARTICLE);
  assert.equal(page.title, '边际效用 & 选择');
  for (const noise of ['导航', '首页', '分享到微博', '版权所有', '相关阅读', '.x{}'])
    assert.ok(!page.text.includes(noise), noise);
  assert.match(page.text, /^# 边际效用/);
  assert.match(page.text, /\n## 1 递减\n/);
  assert.ok(page.text.includes('“边际”就是这个意思'));
  assert.ok(page.text.includes('MU = \\Delta U / \\Delta Q'));
  assert.ok(page.text.includes('- 第一点\n- 第二点'));
  // 研读时按这些标题切节
  assert.deepEqual(
    splitMaterial(page.text).map(s => s.heading),
    ['边际效用', '1 递减', '2 应用'],
  );
});

const html = (body: string): Fetched => ({
  status: 200,
  url: 'https://example.com/a',
  contentType: 'text/html; charset=utf-8',
  body,
});
const page = { url: 'https://example.com/a', kind: 'page' as const };

test('blocked, login-only, PDF and very short pages are reported, never passed off as the full text', () => {
  const blocked = readFetched(page, { ...html('<p>Forbidden</p>'), status: 403 });
  assert.equal(blocked?.ok, false);
  assert.match(
    blocked && !blocked.ok ? blocked.reason : '',
    /拦住了自动读取（HTTP 403）.*复制过来贴上/,
  );
  const challenge = readFetched(
    page,
    html(
      '<title>Just a moment...</title><p>Checking your browser. Please complete the captcha.</p>',
    ),
  );
  assert.match(challenge && !challenge.ok ? challenge.reason : '', /人机验证/);
  // 正经文章里提到“验证码”不算被拦
  const essay = readFetched(
    page,
    html(`<article><p>${'验证码是一种区分人和机器的办法。'.repeat(120)}</p></article>`),
  );
  assert.equal(essay?.ok, true);
  const pdf = readFetched(page, { ...html('%PDF-1.7 ...'), contentType: 'application/pdf' });
  assert.match(pdf && !pdf.ok ? pdf.reason : '', /PDF/);
  const empty = readFetched(page, html('<div id="app"></div><script>render()</script>'));
  assert.match(empty && !empty.ok ? empty.reason : '', /几乎没有正文/);
  const paywall = readFetched(
    page,
    html(`<article><p>${'开头的一段内容。'.repeat(200)}</p><p>订阅后可阅读全文</p></article>`),
  );
  assert.ok(paywall?.ok && !paywall.complete && /订阅或登录/.test(paywall.note));
  const short = readFetched(page, html(`<article><p>${'一句话。'.repeat(80)}</p></article>`));
  assert.ok(short?.ok && !short.complete && /很短/.test(short.note));
  const full = readFetched(page, html(ARTICLE));
  assert.ok(full?.ok && full.complete);
  assert.equal(readFetched(page, { ...html(''), status: 404 })?.ok, false);
});

const ATOM = `<?xml version="1.0"?><feed><entry><title>Attention Is
 All You Need</title><summary>  The dominant sequence &amp; transduction models...  </summary>
<author><name>Ashish Vaswani</name></author><author><name>Noam Shazeer</name></author></entry></feed>`;

test('an arXiv paper without an HTML version falls back to its abstract, marked as not the full text', async () => {
  const asked: string[] = [];
  const r = await importLink('https://arxiv.org/pdf/1706.03762', async url => {
    asked.push(url);
    if (url.includes('/html/')) return { ...html('Not found'), status: 404, url };
    return { status: 200, url, contentType: 'application/atom+xml', body: ATOM };
  });
  assert.deepEqual(asked, [
    'https://arxiv.org/html/1706.03762',
    'https://export.arxiv.org/api/query?id_list=1706.03762',
  ]);
  assert.ok(r.ok);
  assert.equal(r.title, 'Attention Is All You Need');
  assert.equal(r.complete, false);
  assert.match(
    r.text,
    /^作者：Ashish Vaswani、Noam Shazeer\n\n## 摘要\n\nThe dominant sequence & transduction models\.\.\.$/,
  );
  // 有 HTML 版就用全文
  const full = await importLink('arXiv:2401.01234', async url => ({ ...html(ARTICLE), url }));
  assert.ok(full.ok && full.complete && full.kind === 'arxiv-html');
  // 请求失败：把原因交给学习者
  const down = await importLink('https://example.com/x', async () => {
    throw new Error('找不到这个网站，请检查链接是不是写对了。');
  });
  assert.deepEqual(down, { ok: false, reason: '找不到这个网站，请检查链接是不是写对了。' });
});

test('pages are decoded with the charset they declare', () => {
  const gbk = new Uint8Array([0xc4, 0xe3, 0xba, 0xc3]); // “你好”
  assert.equal(decodeBody(gbk, 'text/html; charset=gbk'), '你好');
  const meta = new TextEncoder().encode('<meta charset="utf-8"><p>好</p>');
  assert.ok(decodeBody(meta, 'text/html').includes('好'));
});

test('the local server only connects to public addresses, even when a name resolves to the LAN', async () => {
  const err = await new Promise<NodeJS.ErrnoException | null>(resolve =>
    guardedLookup('localhost', {}, e => resolve(e)),
  );
  assert.equal(err?.code, 'EBLOCKED');
});

test('the Worker follows redirects only to allowed addresses and caps the page size', async () => {
  const seen: string[] = [];
  const fake = (routes: Record<string, () => Response>) =>
    (async (url: string, init: RequestInit) => {
      seen.push(url);
      assert.equal(init.redirect, 'manual');
      return routes[url]?.() ?? new Response('nope', { status: 404 });
    }) as unknown as typeof fetch;
  const get = workerGet(
    fake({
      'https://a.example/': () => new Response(null, { status: 301, headers: { Location: '/b' } }),
      'https://a.example/b': () =>
        new Response('<p>ok</p>', { headers: { 'Content-Type': 'text/html' } }),
      'https://a.example/evil': () =>
        new Response(null, { status: 302, headers: { Location: 'http://169.254.169.254/' } }),
      'https://a.example/big': () => new Response('x'.repeat(3_000_001)),
    }),
  );
  const ok = await get('https://a.example/');
  assert.equal(ok.url, 'https://a.example/b');
  assert.equal(ok.body, '<p>ok</p>');
  await assert.rejects(get('https://a.example/evil'), /不能读取本机或局域网/);
  assert.ok(!seen.includes('http://169.254.169.254/'));
  await assert.rejects(get('https://a.example/big'), /3 MB/);
  await assert.rejects(get('http://127.0.0.1/'), /本机或局域网/);
  const loop = workerGet(
    async () =>
      new Response(null, {
        status: 302,
        headers: { Location: '/again' },
      }),
  );
  await assert.rejects(loop('https://a.example/'), /跳转了太多次/);
});

test('POST /api/fetch works on the local server without an API and on the Worker only with the password', async t => {
  const server = createServer({
    getPage: async url => ({ ...html(ARTICLE), url }),
  });
  await new Promise<void>(resolve => server.listen(0, '127.0.0.1', resolve));
  t.after(
    () =>
      new Promise<void>(resolve => {
        server.close(() => resolve());
        server.closeAllConnections();
      }),
  );
  const origin = `http://127.0.0.1:${(server.address() as AddressInfo).port}`;
  const post = (body: unknown) =>
    fetch(origin + '/api/fetch', {
      method: 'POST',
      headers: { 'Content-Type': 'application/json', 'X-Sibian-Client': 'web', Origin: origin },
      body: JSON.stringify(body),
    });
  const r = await post({ url: 'https://example.com/a' });
  assert.equal(r.status, 200);
  const data = (await r.json()) as { ok: boolean; title: string };
  assert.equal(data.ok, true);
  assert.equal(data.title, '边际效用 & 选择');
  assert.equal((await post({})).status, 400);
  const local = (await (await post({ url: 'http://192.168.1.1/' })).json()) as { ok: boolean };
  assert.equal(local.ok, false);

  const handler = createHandler(
    (async (url: string) =>
      new Response(ARTICLE, {
        headers: { 'Content-Type': 'text/html' },
        status: url ? 200 : 500,
      })) as unknown as typeof fetch,
  );
  const env = {
    AI_BASE_URL: 'https://x/v1',
    AI_API_KEY: 'k',
    AI_MODEL: 'm',
    ACCESS_PASSWORD: 'pw',
  };
  const call = (password: string) =>
    handler.fetch(
      new Request('https://worker.example/api/fetch', {
        method: 'POST',
        headers: { 'Content-Type': 'application/json', Authorization: 'Bearer ' + password },
        body: JSON.stringify({ url: 'https://example.com/a' }),
      }),
      env,
    );
  assert.equal((await call('wrong')).status, 401);
  const w = (await (await call('pw')).json()) as { ok: boolean; complete: boolean };
  assert.ok(w.ok && w.complete);
});
