// 读材料（#144、#162）：从学习者选的 PDF 文件里取出文字。用 Mozilla 的 pdf.js，只在选了 PDF 时才加载（单独的一块，
// 平时打开页面不下载它）。文件只在这台设备上解析，不上传；之后和贴进来的正文一样，讨论时只把相关的几节发给 AI。
// PDF 里没有“段落”和“标题”，只有一行行字和它们的位置：按行距把行拼回段落，单独成行、像标题的短行留成一行，
// 讨论时 splitMaterial 会按它们切节。

export const PDF_LIMITS = {
  bytes: 30_000_000, // 文件最大 30 MB
  pages: 300,
  text: 300_000, // 和材料的上限一样
} as const;

/** 一页里的一小段文字和它的位置（pdf.js 的 TextItem 里用到的部分）。 */
export interface PdfItem {
  str: string;
  y: number; // 基线的纵坐标（往上是正）
  h: number; // 字高
  eol: boolean; // 这一段之后换行
}

const CJK = /[\u3000-\u303f\u3400-\u9fff\uf900-\ufaff\uff00-\uffef]/;

// 两行之间拼接：英文行末的连字符去掉；中文之间不加空格
function join(a: string, b: string): string {
  if (!a) return b;
  if (/[a-z]-$/i.test(a) && /^[a-z]/.test(b)) return a.slice(0, -1) + b;
  if (CJK.test(a.slice(-1)) && CJK.test(b[0] ?? '')) return a + b;
  return a + ' ' + b;
}

/** 一页的文字：按位置拼成行，行距明显变大处分段；像节标题的短行单独成段。 */
export function pageText(items: PdfItem[]): string {
  // 先拼成行
  const lines: { text: string; y: number; h: number }[] = [];
  let cur: { text: string; y: number; h: number } | null = null;
  for (const it of items) {
    if (!cur) cur = { text: '', y: it.y, h: it.h };
    // 同一行里纵坐标几乎不变；变了很多又没标换行，也当新的一行
    if (cur.text && Math.abs(it.y - cur.y) > Math.max(2, cur.h * 0.6)) {
      lines.push(cur);
      cur = { text: '', y: it.y, h: it.h };
    }
    cur.text += it.str;
    cur.h = Math.max(cur.h, it.h);
    if (it.eol) {
      lines.push(cur);
      cur = null;
    }
  }
  if (cur) lines.push(cur);
  const clean = lines
    .map(l => ({ ...l, text: l.text.replace(/\s+/g, ' ').trim() }))
    .filter(l => l.text);
  // 再拼成段：行距比正常大一截，或者这一行像节标题，就另起一段
  const gaps = clean
    .slice(1)
    .map((l, i) => Math.abs(clean[i].y - l.y))
    .filter(g => g > 0);
  const normal = gaps.length ? gaps.sort((a, b) => a - b)[Math.floor(gaps.length / 2)] : 0;
  const heading = (t: string) =>
    t.length <= 60 &&
    !/[。！？；，.!?;,:：]$/.test(t) &&
    // 编号后面要跟文字（“3.2 Attention”），跟数字的是表格里的一行（“32 16 16 5.01”）
    (/^(\d{1,2}(\.\d{1,2}){0,2}\.?|[IVX]{1,4}\.)\s+[^\d\s.,]/.test(t) ||
      /^(摘要|引言|前言|背景|相关工作|方法|实验|结果|讨论|结论|总结|参考文献|致谢|附录|abstract|introduction|background|related work|methods?|experiments?|results?|discussion|conclusions?|references|acknowledg(e)?ments?|appendix)$/i.test(
        t,
      ));
  // 表格里几乎全是数字的一行：和上一行同样是数字时接在一起，免得一行行被当成节标题
  const numberish = (t: string) => (t.match(/[a-z\u3400-\u9fff]/gi)?.length ?? 0) < 3;
  const paras: string[] = [];
  let para = '';
  clean.forEach((l, i) => {
    const gap = i ? Math.abs(clean[i - 1].y - l.y) : 0;
    const table = i > 0 && numberish(l.text) && numberish(clean[i - 1].text);
    const breakHere =
      !table &&
      (i === 0 ||
        heading(l.text) ||
        heading(clean[i - 1].text) ||
        (normal > 0 && gap > normal * 1.4));
    if (breakHere && para) {
      paras.push(para);
      para = '';
    }
    para = join(para, l.text);
  });
  if (para) paras.push(para);
  return paras.join('\n\n');
}

/** 去掉每页都有的页眉页脚（同样的短行在一半以上的页上出现）和单独的页码。 */
export function dropRunningLines(pages: string[]): string[] {
  if (pages.length < 4) return pages;
  const count = new Map<string, number>();
  for (const p of pages)
    for (const line of new Set(p.split('\n\n').map(s => s.replace(/\d+/g, '#').trim())))
      if (line.length <= 80) count.set(line, (count.get(line) ?? 0) + 1);
  const running = new Set([...count].filter(([, n]) => n >= pages.length / 2).map(([k]) => k));
  return pages.map(p =>
    p
      .split('\n\n')
      .filter(s => !running.has(s.replace(/\d+/g, '#').trim()) && !/^\d{1,4}$/.test(s.trim()))
      .join('\n\n'),
  );
}

/** 一页里字最大的那几段（字高在最大值的九成以上），拼成一行。 */
export function largestText(items: PdfItem[]): string {
  const max = Math.max(0, ...items.filter(it => it.str.trim()).map(it => it.h));
  if (!max) return '';
  return items
    .filter(it => it.str.trim() && it.h >= max * 0.9)
    .map(it => it.str.trim())
    .join(' ')
    .replace(/\s+/g, ' ')
    .slice(0, 120);
}

export type PdfResult =
  | { ok: true; title: string; text: string; pages: number; note: string }
  | { ok: false; reason: string };

type PdfJs = typeof import('pdfjs-dist');

/** 读一个 PDF 文件。只在这台设备上解析。pdf.js 只在这时才加载。 */
export async function readPdf(file: File): Promise<PdfResult> {
  if (file.size > PDF_LIMITS.bytes)
    return { ok: false, reason: '这个 PDF 太大了（超过 30 MB）。可以只把想读的部分复制出来贴上。' };
  const [pdfjs, worker] = await Promise.all([
    import('pdfjs-dist'),
    import('pdfjs-dist/build/pdf.worker.min.mjs?url'),
  ]);
  pdfjs.GlobalWorkerOptions.workerSrc = worker.default;
  return extractPdf(new Uint8Array(await file.arrayBuffer()), file.name, pdfjs);
}

/** 从 PDF 的字节里取出文字（[pdfjs] 由调用方给：网页里是浏览器版，测试里是 Node 版）。 */
export async function extractPdf(data: Uint8Array, name: string, pdfjs: PdfJs): Promise<PdfResult> {
  // 只取文字：不加载字体，不去网上拿任何资源（PDF 里的脚本 pdf.js 本来就不执行）
  const task = pdfjs.getDocument({
    data,
    disableFontFace: true,
    useSystemFonts: false,
    useWorkerFetch: false,
    enableXfa: false,
    stopAtErrors: false,
  });
  let doc: Awaited<typeof task.promise>;
  try {
    doc = await task.promise;
  } catch (e) {
    // 打开失败也要把后台的 worker 关掉
    void task.destroy();
    const name = (e as { name?: string }).name;
    return {
      ok: false,
      reason:
        name === 'PasswordException'
          ? '这个 PDF 有密码保护，打不开。先用阅读器去掉密码，或者把正文复制过来贴上。'
          : '这个文件打不开，可能不是 PDF 或者已经损坏。',
    };
  }
  const numPages = doc.numPages;
  const total = Math.min(numPages, PDF_LIMITS.pages);
  const pages: string[] = [];
  let firstTitle = '';
  let length = 0;
  for (let i = 1; i <= total && length < PDF_LIMITS.text; i++) {
    const page = await doc.getPage(i);
    const content = await page.getTextContent();
    const items: PdfItem[] = [];
    for (const it of content.items)
      // 旋转过的字（arXiv 页边竖着的编号、水印）不是正文
      if ('str' in it && !Number(it.transform[1]) && !Number(it.transform[2]))
        items.push({
          str: it.str,
          y: Number(it.transform[5]),
          h: it.height || Math.abs(Number(it.transform[3])),
          eol: it.hasEOL,
        });
    // 没有标题信息时用第一页上字最大的那几段（论文的题目）
    if (i === 1) firstTitle = largestText(items);
    const t = pageText(items);
    pages.push(t);
    length += t.length;
    page.cleanup();
  }
  const meta = await doc.getMetadata().catch(() => null);
  await task.destroy();
  const text = dropRunningLines(pages).join('\n\n').slice(0, PDF_LIMITS.text).trim();
  const chars = text.replace(/\s+/g, '').length;
  if (chars < 200)
    return {
      ok: false,
      reason:
        '这个 PDF 里几乎没有可以复制的文字，可能是扫描的图片。识别图片里的字还不支持，可以用别的工具转成文字再贴上。',
    };
  const info = (meta?.info ?? {}) as { Title?: unknown };
  const title =
    (typeof info.Title === 'string' && info.Title.trim()) ||
    firstTitle ||
    name.replace(/\.pdf$/i, '');
  const cut = numPages > pages.length || length >= PDF_LIMITS.text;
  return {
    ok: true,
    title: title.slice(0, 80),
    text,
    pages: pages.length,
    note: cut
      ? `PDF 太长，只读了前 ${pages.length} 页（${PDF_LIMITS.text / 10000} 万字以内）。`
      : 'PDF 没有段落和标题的标记，是按排版拼回来的，公式和表格可能会乱一点。',
  };
}
