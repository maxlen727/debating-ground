// 知识画布：路径条、分叉处的分支入口、分支开头、带回来的小结、整棵树的导航，以及展开和带回的面板。
// 只负责展示，操作都在 actions 里。
import { useRef, useState } from 'react';
import type { ChatMessage, SystemLine } from '../../shared/types.ts';
import { bringBack, goToBranch, openBranch } from '../actions.ts';
import { branchById, branchChain, branchesFrom, childrenOf, talkCount } from '../lib/canvas.ts';
import { update, useApp, type Session } from '../store.ts';
import { CaretField } from './SmoothCaret.tsx';
import { Icon, Sheet } from './ui.tsx';

const clip = (t: string, n: number) => t.length > n ? t.slice(0, n) + '…' : t;
const openSheet = (name: string) => update(st => { st.ui.sheet = name; });
const closeSheet = () => update(st => { st.ui.sheet = null; });

// 顶部路径：主干 › 分支 › 子分支；右边打开整棵树
export function CanvasPath({ s }: { s: Session }) {
  const { ui } = useApp();
  const chain = branchChain(s);
  const busy = !!ui.busy;
  const total = s.branches?.length ?? 0;
  return (
    <nav className="canvas-path" aria-label="当前位置">
      <ol className="scroll-x">
        <li><button className={'crumb' + (chain.length ? '' : ' here')} disabled={busy || !chain.length} aria-current={chain.length ? undefined : 'location'} onClick={() => goToBranch(undefined)}>主干</button></li>
        {chain.map((b, i) => (
          <li key={b.id}><span aria-hidden="true">›</span>
            <button className={'crumb' + (i === chain.length - 1 ? ' here' : '')} disabled={busy || i === chain.length - 1} aria-current={i === chain.length - 1 ? 'location' : undefined} onClick={() => goToBranch(b.id)}>{b.topic}</button>
          </li>
        ))}
      </ol>
      <button className="tree-btn" aria-label={`整棵树，共 ${total} 个分支`} onClick={() => openSheet('tree')}><Icon name="branch" size={18} />{total > 0 && <small>{total}</small>}</button>
    </nav>
  );
}

// 分支开头：从哪句话展开、这一枝要弄懂什么
export function ForkHeader({ s }: { s: Session }) {
  const here = branchById(s, s.at);
  if (!here) return null;
  const from = s.entries.find((e): e is ChatMessage => e.id === here.from && e.kind === 'msg');
  return (
    <header className="fork-head">
      {from && <blockquote className="fork-from"><small>从{from.human ? '你' : from.name}的这句话展开</small>{clip(from.text, 90)}</blockquote>}
      <h2>{here.topic}</h2>
    </header>
  );
}

// 发言下面：从这句话展开过的分支
export function BranchChips({ s, entryId }: { s: Session; entryId: string }) {
  const { ui } = useApp();
  const list = branchesFrom(s, entryId);
  if (!list.length) return null;
  return (
    <div className="branch-chips">
      {list.map(b => (
        <button key={b.id} className={'branch-chip' + (b.gist ? ' back' : '')} disabled={!!ui.busy} onClick={() => goToBranch(b.id)}>
          <Icon name="branch" size={15} /><b>{b.topic}</b><small>{talkCount(s, b.id)} 条{b.gist ? ' · 已带回' : ''}</small>
        </button>
      ))}
    </div>
  );
}

// 从分支带回来的那句话（学习者自己写的，属于知识本身）
export function BackLine({ m, s }: { m: SystemLine; s: Session }) {
  const { ui } = useApp();
  const b = branchById(s, m.back);
  return (
    <button className="back-line" data-entry={m.id} disabled={!!ui.busy || !b} onClick={() => b && goToBranch(b.id)}>
      <small><Icon name="turn-back" size={14} />从「{b?.topic ?? '分支'}」带回</small>
      <p>{m.text}</p>
    </button>
  );
}

// 整棵树：主干和所有分支，点哪里去哪里
export function TreeSheet({ s }: { s: Session }) {
  const { ui } = useApp();
  const busy = !!ui.busy;
  const node = (at: string | undefined, label: string, depth: number) => (
    <li key={at ?? 'trunk'}>
      <button className={'tree-node' + (s.at === at ? ' here' : '')} style={{ paddingLeft: 12 + depth * 18 }} disabled={busy} aria-current={s.at === at ? 'location' : undefined} onClick={() => goToBranch(at)}>
        {depth > 0 && <Icon name="branch" size={15} />}<b>{label}</b><small>{talkCount(s, at)} 条{branchById(s, at)?.gist ? ' · 已带回' : ''}</small>
      </button>
      {childrenOf(s, at).length > 0 && <ul>{childrenOf(s, at).map(b => node(b.id, b.topic, depth + 1))}</ul>}
    </li>
  );
  return (
    <Sheet title="整棵树" onClose={closeSheet}>
      <ul className="tree">{node(undefined, '主干', 0)}</ul>
      <p className="fineprint">{s.branches?.length ? '每个分支里，AI 只看得到主干到分叉点为止的内容和这个分支本身。' : '还没有分支。轻点一句发言，就能从那里展开一个分支单独聊。'}</p>
    </Sheet>
  );
}

// 展开分支：AI 标出的新词一点就开；也可以自己写想弄懂什么
export function ForkForm({ m }: { m: ChatMessage }) {
  const { ui } = useApp();
  const [topic, setTopic] = useState('');
  const ref = useRef<HTMLInputElement>(null);
  const busy = !!ui.busy;
  return (
    <div className="fork-form stack-sm">
      <h3 className="section-label">从这句展开一个分支</h3>
      {!!m.terms?.length && <div className="row wrap">{m.terms.map(t => <button key={t} className="chip" disabled={busy} onClick={() => openBranch(m.id, t)}><Icon name="branch" size={14} />{t}</button>)}</div>}
      <div className="row">
        <CaretField fieldRef={ref} className="grow">
          <input ref={ref} value={topic} maxLength={40} placeholder="想单独弄懂什么？比如一个词" aria-label="分支话题"
            onChange={e => setTopic(e.target.value)} onKeyDown={e => { if (e.key === 'Enter' && !e.nativeEvent.isComposing) void openBranch(m.id, topic); }} />
        </CaretField>
        <button className="primary" disabled={busy || !topic.trim()} onClick={() => openBranch(m.id, topic)}>展开</button>
      </div>
    </div>
  );
}

// 带回上一层：学习者自己写一句这个分支弄懂了什么
export function BackSheet({ s }: { s: Session }) {
  const here = branchById(s, s.at);
  const [gist, setGist] = useState(here?.gist ?? '');
  const ref = useRef<HTMLTextAreaElement>(null);
  if (!here) return null;
  const up = branchById(s, here.parent)?.topic ?? '主干';
  return (
    <Sheet title={`带回${up === '主干' ? '主干' : '「' + up + '」'}`} onClose={closeSheet}>
      <p className="sheet-text">用一句话说说，在「{here.topic}」这里你弄懂了什么。这句话会挂回{up === '主干' ? '主干' : '上一层'}，之后的讨论会接着它聊，复盘也会算上它。</p>
      <CaretField fieldRef={ref}><textarea ref={ref} className="serif-input" rows={3} maxLength={300} value={gist} placeholder="我弄懂了……" aria-label="带回的一句话" onChange={e => setGist(e.target.value)} /></CaretField>
      <div className="row">
        <button className="ghost grow" onClick={() => goToBranch(here.parent)}>先不写，直接回去</button>
        <button className="primary grow" disabled={!gist.trim()} onClick={() => bringBack(gist)}>带回去</button>
      </div>
    </Sheet>
  );
}
