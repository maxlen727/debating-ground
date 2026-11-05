// 导出讨论记录与知识库（Markdown）
import { MODES, STATUS_LABEL } from '../../shared/modes.ts';
import type { Entry, Review, TermCard } from '../../shared/types.ts';
import type { Card, Session } from '../store.ts';
import { childrenOf, levelEntries } from './canvas.ts';
import { isNativeApp } from './platform.ts';
import { toast } from '../store.ts';

function download(name: string, text: string) {
  // App 的 WebView 不支持下载文件：复制到剪贴板，粘贴到笔记里
  if (isNativeApp()) {
    navigator.clipboard.writeText(text).then(() => toast('已复制到剪贴板，可以粘贴到笔记或聊天里。'), () => toast('没能复制，请稍后再试。'));
    return;
  }
  const a = document.createElement('a');
  const url = URL.createObjectURL(new Blob([text], { type: 'text/markdown;charset=utf-8' }));
  a.href = url; a.download = name; a.click();
  setTimeout(() => URL.revokeObjectURL(url), 1000);
}

export function reviewMarkdown(r: Review & { concept: string; topic: string; mode: Card['mode']; created: number; terms?: TermCard[] }) {
  return `## ${r.concept}\n\n讨论题：${r.topic}\n讨论方式：${MODES[r.mode].name} · ${new Date(r.created).toLocaleDateString('zh-CN')}\n\n${r.oneLine}\n\n`
    + r.points.map(p => `- [${STATUS_LABEL[p.status]}] ${p.label}：${p.note}`).join('\n')
    + (r.misconceptions.length ? '\n\n需要纠正的理解：\n' + r.misconceptions.map(m => `- ${m.said} → ${m.fix}`).join('\n') : '')
    + (r.stances?.length ? '\n\n你的立场：\n' + r.stances.map(m => `- ${m.said}${m.note ? `（${m.note}）` : ''}`).join('\n') : '')
    + (r.quiz.length ? '\n\n练习题：\n' + r.quiz.map((q, i) => `${i + 1}. ${q.q}\n` + q.options.map((o, j) => `   ${'ABCD'[j]}. ${o}`).join('\n') + `\n   答案：${'ABCD'[q.answer]}。${q.why}`).join('\n') : '')
    + (r.terms?.length ? '\n\n这次遇到的新词：\n' + r.terms.map(t => `- ${t.term}：${t.definition}${t.relation ? `（${t.relation}）` : ''}`).join('\n') : '') + '\n';
}

const entriesMarkdown = (list: Entry[]) => list.map(m => m.kind === 'sys' ? (m.back ? `> 带回：${m.text}\n` : '') : m.kind === 'reflect'
  ? (m.text ? `### 小结\n\n${m.text}\n\n> ${m.feedback}\n` : '')
  : `### ${m.name}${m.action ? ' · ' + m.action : ''} · ${m.human || m.memberId ? '真人' : m.source === 'api' ? 'AI · ' + m.model : 'AI · 演示'}\n\n${m.text}\n`).filter(Boolean).join('\n');

// 知识画布：先写主干，再按树的顺序写每个分支，标出它从哪句话展开
function canvasMarkdown(s: Session): string {
  const walk = (parent: string | undefined, depth: number): string => childrenOf(s, parent).map(b => {
    const from = s.entries.find(e => e.id === b.from);
    const quote = from?.kind === 'msg' ? from.text.slice(0, 40) + (from.text.length > 40 ? '…' : '') : '';
    return `\n${'#'.repeat(Math.min(depth + 2, 6))} 分支：${b.topic}\n\n${quote ? `> 从这句展开：${quote}\n\n` : ''}` + entriesMarkdown(levelEntries(s, b.id)) + walk(b.id, depth + 1);
  }).join('');
  return entriesMarkdown(levelEntries(s, undefined)) + walk(undefined, 0);
}

export function sessionMarkdown(s: Session) {
  return `# 思辨场讨论记录\n\n概念：${s.concept}\n讨论题：${s.topic}\n讨论方式：${MODES[s.mode].name}\n要点：${s.keyPoints.map((k, i) => (s.covered.includes(i) ? '●' : '○') + k).join(' ')}\n\n`
    + (s.mode === 'canvas' ? canvasMarkdown(s) : entriesMarkdown(s.entries))
    + (s.review ? '\n# 理解复盘\n\n' + reviewMarkdown({ ...s.review, concept: s.concept, topic: s.topic, mode: s.mode, created: s.created, terms: s.glossary }) : '');
}

export const exportSession = (s: Session) => download(`思辨场-${s.concept}-讨论记录.md`, sessionMarkdown(s));
export const exportLibrary = (cards: Card[]) => download('思辨场-知识库.md', '# 思辨场知识库\n\n' + cards.map(reviewMarkdown).join('\n---\n\n'));
