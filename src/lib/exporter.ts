// 导出讨论记录与知识库（Markdown）
import { MODES, STATUS_LABEL } from '../../shared/modes.ts';
import type { Entry, ModeId, Review, TermCard } from '../../shared/types.ts';
import type { Session } from '../store.ts';
import { noteMarkdown, type Note } from './notes.ts';
import { childrenOf, levelEntries } from './canvas.ts';

function download(name: string, text: string) {
  const a = document.createElement('a');
  const url = URL.createObjectURL(new Blob([text], { type: 'text/markdown;charset=utf-8' }));
  a.href = url;
  a.download = name;
  a.click();
  setTimeout(() => URL.revokeObjectURL(url), 1000);
}

export function reviewMarkdown(
  r: Review & {
    concept: string;
    topic: string;
    mode: ModeId;
    created: number;
    terms?: TermCard[];
  },
) {
  const list = (title: string, lines: string[]) =>
    lines.length ? `\n\n${title}：\n` + lines.join('\n') : '';
  return (
    `## ${r.concept}\n\n讨论题：${r.topic}\n讨论方式：${MODES[r.mode].name} · ${new Date(r.created).toLocaleDateString('zh-CN')}\n\n${r.oneLine}` +
    (r.summary ? `\n\n用你的话说：${r.summary}` : '') +
    (r.useWhen ? `\n\n什么时候想到它：${r.useWhen}` : '') +
    '\n\n' +
    r.points
      .map(
        p =>
          `- [${STATUS_LABEL[p.status]}] ${p.label}：${p.note}${p.evidence ? `（你说过：“${p.evidence}”）` : ''}`,
      )
      .join('\n') +
    list(
      '你举过的例子',
      (r.examples ?? []).map(e => `- ${e}`),
    ) +
    list(
      '需要纠正的理解',
      r.misconceptions.map(m => `- ${m.said} → ${m.fix}`),
    ) +
    list(
      '边界',
      (r.boundaries ?? []).map(b => `- ${b}`),
    ) +
    list(
      '你的立场',
      (r.stances ?? []).map(m => `- ${m.said}${m.note ? `（${m.note}）` : ''}`),
    ) +
    list('相关概念', (r.related ?? []).length ? [(r.related ?? []).join('、')] : []) +
    list(
      '自检',
      (r.checks ?? []).map((c, i) => `${i + 1}. ${c.q}\n   参考：${c.answer}`),
    ) +
    list(
      '这次遇到的新词',
      (r.terms ?? []).map(
        t => `- ${t.term}：${t.definition}${t.relation ? `（${t.relation}）` : ''}`,
      ),
    ) +
    '\n'
  );
}

const entriesMarkdown = (list: Entry[]) =>
  list
    .map(m =>
      m.kind === 'sys'
        ? m.back
          ? `> 带回：${m.text}\n`
          : ''
        : m.kind === 'reflect'
          ? m.text
            ? `### 小结\n\n${m.text}\n\n> ${m.feedback}\n`
            : ''
          : `### ${m.name}${m.action ? ' · ' + m.action : ''} · ${m.human || m.memberId ? '真人' : m.source === 'api' ? 'AI · ' + m.model : 'AI · 演示'}\n\n${m.text}\n`,
    )
    .filter(Boolean)
    .join('\n');

// 知识画布：先写主干，再按树的顺序写每个分支，标出它从哪句话展开
function canvasMarkdown(s: Session): string {
  const walk = (parent: string | undefined, depth: number): string =>
    childrenOf(s, parent)
      .map(b => {
        const from = s.entries.find(e => e.id === b.from);
        const quote =
          from?.kind === 'msg' ? from.text.slice(0, 40) + (from.text.length > 40 ? '…' : '') : '';
        return (
          `\n${'#'.repeat(Math.min(depth + 2, 6))} 分支：${b.topic}\n\n${quote ? `> 从这句展开：${quote}\n\n` : ''}` +
          entriesMarkdown(levelEntries(s, b.id)) +
          walk(b.id, depth + 1)
        );
      })
      .join('');
  return entriesMarkdown(levelEntries(s, undefined)) + walk(undefined, 0);
}

export function sessionMarkdown(s: Session) {
  return (
    `# 思辨场讨论记录\n\n概念：${s.concept}\n讨论题：${s.topic}\n讨论方式：${MODES[s.mode].name}\n要点：${s.keyPoints.map((k, i) => (s.covered.includes(i) ? '●' : '○') + k).join(' ')}\n\n` +
    (s.mode === 'canvas' ? canvasMarkdown(s) : entriesMarkdown(s.entries)) +
    (s.review
      ? '\n# 理解复盘\n\n' +
        reviewMarkdown({
          ...s.review,
          concept: s.concept,
          topic: s.topic,
          mode: s.mode,
          created: s.created,
          terms: s.glossary,
        })
      : '')
  );
}

export const exportSession = (s: Session) =>
  download(`思辨场-${s.concept}-讨论记录.md`, sessionMarkdown(s));
// 知识库：一篇篇概念笔记连在一起；单独导出一篇时带上 name / description 开头，可以直接当一个可复用的文件
export const libraryMarkdown = (notes: Note[]) =>
  '# 思辨场知识库\n\n' + notes.map(n => noteMarkdown(n, false)).join('\n---\n\n');
export const exportLibrary = (notes: Note[]) =>
  download('思辨场-知识库.md', libraryMarkdown(notes));
export const exportNote = (note: Note) => download(`${note.concept}.md`, noteMarkdown(note));
