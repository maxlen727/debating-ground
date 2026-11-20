// 安卓 App 读 PDF（#164）：App 在看不见的 WebView 里打开 pdf.html，调用 sibianPdf 读它交过来的文件，
// 和网页版用同一份 src/lib/pdf.ts。结果放在 window.sibianPdfResult 里，App 轮询取回（不往页面注入任何接口）。
import { extractPdf } from './lib/pdf.ts';

type Done = { done: true; result: string };

async function read(path: string, name: string) {
  const w = window as unknown as { sibianPdfResult?: Done };
  try {
    const res = await fetch(path);
    if (!res.ok) throw new Error('读不到这个文件。');
    const [pdfjs, worker] = await Promise.all([
      import('pdfjs-dist'),
      import('pdfjs-dist/build/pdf.worker.min.mjs?url'),
    ]);
    pdfjs.GlobalWorkerOptions.workerSrc = worker.default;
    const r = await extractPdf(new Uint8Array(await res.arrayBuffer()), name, pdfjs);
    w.sibianPdfResult = { done: true, result: JSON.stringify(r) };
  } catch {
    w.sibianPdfResult = {
      done: true,
      result: JSON.stringify({ ok: false, reason: '这个文件打不开，可能不是 PDF 或者已经损坏。' }),
    };
  }
}

(window as unknown as { sibianPdf: (path: string, name: string) => void }).sibianPdf = (
  path,
  name,
) => {
  void read(path, name);
};
