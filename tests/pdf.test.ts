// 读材料：从 PDF 文件里取出文字（#162）。拼行、分段、节标题、去掉页眉页脚，以及用 pdf.js 真的解析一个 PDF。
import { test } from 'node:test';
import assert from 'node:assert/strict';
import { dropRunningLines, extractPdf, pageText, type PdfItem } from '../src/lib/pdf.ts';
import { splitMaterial } from '../src/lib/reader.ts';

// 一行一个文字片段：y 从上往下递减，行距 14
const lines = (rows: (string | null)[], start = 700): PdfItem[] => {
  const out: PdfItem[] = [];
  let y = start;
  for (const r of rows) {
    if (r === null) {
      y -= 14; // 空一行：段落之间的空隙
      continue;
    }
    out.push({ str: r, y, h: 10, eol: true });
    y -= 14;
  }
  return out;
};

test('lines are joined into paragraphs, with hyphenation removed and headings on their own', () => {
  const text = pageText(
    lines([
      'Attention Is All You Need',
      'Abstract',
      'The dominant sequence trans-',
      'duction models are based on',
      'recurrent networks.',
      null,
      'We propose a new simple network.',
      '1 Introduction',
      '循环神经网络曾经是',
      '序列建模的主流方法。',
    ]),
  );
  assert.equal(
    text,
    [
      'Attention Is All You Need',
      'Abstract',
      'The dominant sequence transduction models are based on recurrent networks.',
      'We propose a new simple network.',
      '1 Introduction',
      '循环神经网络曾经是序列建模的主流方法。',
    ].join('\n\n'),
  );
  // 研读时按这些标题切节
  assert.deepEqual(
    splitMaterial(text + '\n\n' + '展开的内容。'.repeat(10)).map(s => s.heading),
    ['开头', 'Abstract', '1 Introduction'],
  );
});

test('pieces on one line without end-of-line marks are kept together', () => {
  const items: PdfItem[] = [
    { str: '机会', y: 500, h: 10, eol: false },
    { str: '成本', y: 500.4, h: 10, eol: false },
    { str: '是放弃的', y: 486, h: 10, eol: false },
    { str: '最好选择。', y: 486, h: 10, eol: true },
  ];
  assert.equal(pageText(items), '机会成本是放弃的最好选择。');
});

test('running headers, footers and bare page numbers are dropped', () => {
  const topics = ['稀缺', '取舍', '边际', '沉没成本', '比较优势'];
  const pages = [1, 2, 3, 4, 5].map(n =>
    [`Journal of Examples, Vol. 3`, `这一页讲${topics[n - 1]}。`, String(n)].join('\n\n'),
  );
  assert.deepEqual(
    dropRunningLines(pages),
    topics.map(t => `这一页讲${t}。`),
  );
  // 页数太少不去：分不清是不是页眉
  assert.deepEqual(dropRunningLines(pages.slice(0, 2)), pages.slice(0, 2));
});

// 拼一个最小的 PDF：每页几行文字（Helvetica，只用 ASCII），交叉引用表的偏移量按实际位置算
function makePdf(pages: string[][], title = 'Test Paper'): Uint8Array {
  const objects: string[] = [];
  const add = (body: string) => objects.push(body) - 1 + 1; // 对象号从 1 开始
  const catalog = add('');
  const pagesObj = add('');
  const font = add('<< /Type /Font /Subtype /Type1 /BaseFont /Helvetica >>');
  const info = add(`<< /Title (${title}) >>`);
  const kids: number[] = [];
  for (const rows of pages) {
    const ops = rows
      .map(
        (r, i) =>
          `BT /F1 11 Tf 72 ${720 - i * 16} Td (${r.replace(/[()\\]/g, m => '\\' + m)}) Tj ET`,
      )
      .join('\n');
    const content = add(`<< /Length ${ops.length} >>\nstream\n${ops}\nendstream`);
    kids.push(
      add(
        `<< /Type /Page /Parent ${pagesObj} 0 R /MediaBox [0 0 612 792] /Resources << /Font << /F1 ${font} 0 R >> >> /Contents ${content} 0 R >>`,
      ),
    );
  }
  objects[catalog - 1] = `<< /Type /Catalog /Pages ${pagesObj} 0 R >>`;
  objects[pagesObj - 1] =
    `<< /Type /Pages /Kids [${kids.map(k => `${k} 0 R`).join(' ')}] /Count ${kids.length} >>`;
  let out = '%PDF-1.4\n';
  const offsets: number[] = [];
  objects.forEach((body, i) => {
    offsets.push(out.length);
    out += `${i + 1} 0 obj\n${body}\nendobj\n`;
  });
  const xref = out.length;
  out += `xref\n0 ${objects.length + 1}\n0000000000 65535 f \n`;
  out += offsets.map(o => `${String(o).padStart(10, '0')} 00000 n \n`).join('');
  out += `trailer\n<< /Size ${objects.length + 1} /Root ${catalog} 0 R /Info ${info} 0 R >>\nstartxref\n${xref}\n%%EOF\n`;
  return new TextEncoder().encode(out);
}

async function nodePdfjs() {
  const pdfjs = await import('pdfjs-dist/legacy/build/pdf.mjs');
  return pdfjs;
}

test('a real PDF is parsed with pdf.js: title, sections and every page', async () => {
  const body = 'Choosing one thing means giving up the best alternative.';
  const pdf = makePdf([
    ['Abstract', body, body, '1 Introduction', body, body, body],
    ['2 Method', body, body, body, '3 Results', body, body],
  ]);
  const r = await extractPdf(pdf, 'paper.pdf', await nodePdfjs());
  assert.ok(r.ok, r.ok ? '' : r.reason);
  assert.equal(r.title, 'Test Paper');
  assert.equal(r.pages, 2);
  assert.deepEqual(
    splitMaterial(r.text).map(s => s.heading),
    ['Abstract', '1 Introduction', '2 Method', '3 Results'],
  );
});

test('PDFs without text or that are not PDFs are reported, not passed off as material', async () => {
  const pdfjs = await nodePdfjs();
  const empty = await extractPdf(makePdf([['x']]), 'scan.pdf', pdfjs);
  assert.ok(!empty.ok && /扫描的图片/.test(empty.reason));
  const junk = await extractPdf(new TextEncoder().encode('not a pdf at all'), 'a.pdf', pdfjs);
  assert.ok(!junk.ok && /打不开/.test(junk.reason));
});
