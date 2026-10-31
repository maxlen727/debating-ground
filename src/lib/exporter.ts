// 导出讨论记录与知识库（Markdown）
import { MODES, STATUS_LABEL } from '../../shared/modes.ts';
import type { Review, TermCard } from '../../shared/types.ts';
import type { Card, Session } from '../store.ts';

function download(name: string, text: string) {
  const a = document.createElement('a');
  const url = URL.createObjectURL(new Blob([text], { type: 'text/markdown;charset=utf-8' }));
  a.href = url; a.download = name; a.click();
  setTimeout(() => URL.revokeObjectURL(url), 1000);
}

export function reviewMarkdown(r: Review & { concept: string; topic: string; mode: Card['mode']; created: number; terms?: TermCard[] }) {
  return `## ${r.concept}\n\n讨论题：${r.topic}\n讨论方式：${MODES[r.mode].name} · ${new Date(r.created).toLocaleDateString('zh-CN')}\n\n${r.oneLine}\n\n`
    + r.points.map(p => `- [${STATUS_LABEL[p.status]}] ${p.label}：${p.note}`).join('\n')
    + (r.misconceptions.length ? '\n\n需要纠正的理解：\n' + r.misconceptions.map(m => `- ${m.said} → ${m.fix}`).join('\n') : '')
    + (r.quiz.length ? '\n\n练习题：\n' + r.quiz.map((q, i) => `${i + 1}. ${q.q}\n` + q.options.map((o, j) => `   ${'ABCD'[j]}. ${o}`).join('\n') + `\n   答案：${'ABCD'[q.answer]}。${q.why}`).join('\n') : '')
    + (r.terms?.length ? '\n\n这次遇到的新词：\n' + r.terms.map(t => `- ${t.term}：${t.definition}${t.relation ? `（${t.relation}）` : ''}`).join('\n') : '') + '\n';
}

export function sessionMarkdown(s: Session) {
  return `# 思辨场讨论记录\n\n概念：${s.concept}\n讨论题：${s.topic}\n讨论方式：${MODES[s.mode].name}\n要点：${s.keyPoints.map((k, i) => (s.covered.includes(i) ? '●' : '○') + k).join(' ')}\n\n`
    + s.entries.map(m => m.kind === 'sys' ? '' : m.kind === 'reflect'
      ? (m.text ? `### 小结\n\n${m.text}\n\n> ${m.feedback}\n` : '')
      : `### ${m.name}${m.action ? ' · ' + m.action : ''} · ${m.human || m.memberId ? '真人' : m.source === 'api' ? 'AI · ' + m.model : 'AI · 演示'}\n\n${m.text}\n`).filter(Boolean).join('\n')
    + (s.review ? '\n# 理解复盘\n\n' + reviewMarkdown({ ...s.review, concept: s.concept, topic: s.topic, mode: s.mode, created: s.created, terms: s.glossary }) : '');
}

export const exportSession = (s: Session) => download(`思辨场-${s.concept}-讨论记录.md`, sessionMarkdown(s));
export const exportLibrary = (cards: Card[]) => download('思辨场-知识库.md', '# 思辨场知识库\n\n' + cards.map(reviewMarkdown).join('\n---\n\n'));
