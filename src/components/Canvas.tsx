// 知识画布：路径条、分叉处的分支入口、分支开头、带回来的小结、整棵树的导航，以及展开和带回的面板。
// 只负责展示，操作都在 actions 里。
import { useEffect, useRef, useState, type CSSProperties } from 'react';
import type { ChatMessage, SystemLine } from '../../shared/types.ts';
import { bringBack, goToBranch, openBranch } from '../actions.ts';
import { branchById, branchChain, branchesFrom, graphOf, pathEntries, talkCount, type GraphRow } from '../lib/canvas.ts';
import { flash } from '../lib/light.ts';
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
      <button className="tree-btn" aria-label={`分支图，共 ${total} 个分支`} onClick={() => openSheet('tree')}><Icon name="branch" size={18} />{total > 0 && <small>{total}</small>}</button>
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

// 分支图：像 GitLens 的提交图。每条发言一行，主干和每个分支各占一条彩色轨道，
// 分叉和带回用曲线连起来；点一行就跳到那个分支的那条发言。
const ROW = 56, GAP = 20, PAD = 16;
const lx = (lane: number) => PAD + lane * GAP;
const ry = (row: number) => ROW / 2 + row * ROW;
// 像 git 的图：分叉时一出节点就拐进自己的轨道再直着往下走；带回时直着走到那一行前面才拐回去。
// 隔了很多行的分叉（从很早的一句话展开）也不会斜穿过别的轨道
const forkPath = (x1: number, y1: number, x2: number, y2: number) => `M${x1} ${y1} C${x1} ${y1 + ROW * 0.42} ${x2} ${y1 + ROW * 0.18} ${x2} ${y1 + ROW * 0.6} L${x2} ${y2}`;
const mergePath = (x1: number, y1: number, x2: number, y2: number) => `M${x1} ${y1} L${x1} ${y2 - ROW * 0.6} C${x1} ${y2 - ROW * 0.18} ${x2} ${y2 - ROW * 0.42} ${x2} ${y2}`;

export function GraphSheet({ s }: { s: Session }) {
  const { ui } = useApp();
  const g = graphOf(s);
  const busy = !!ui.busy;
  const onPath = new Set(pathEntries(s).map(e => e.id));
  const here = s.at;
  const firstOf = new Map<string, string>();      // 每个分支的第一行：在那里挂分支名
  for (const r of g.rows) { const k = r.branch ?? ''; if (!firstOf.has(k)) firstOf.set(k, r.id); }
  const topic = (id?: string) => id ? branchById(s, id)?.topic ?? '' : '主干';
  const lastHere = [...g.rows].reverse().find(r => r.branch === here);
  const listRef = useRef<HTMLOListElement>(null);
  // 打开时把你现在所在的地方滚到中间
  // （只滚面板自己，不用 scrollIntoView：它会连后面的页面一起滚）
  useEffect(() => {
    const row = listRef.current?.querySelector<HTMLElement>('.g-row.here-last');
    const sheet = listRef.current?.closest<HTMLElement>('.sheet');
    if (row && sheet) sheet.scrollTop = row.offsetTop + (listRef.current!.offsetTop) - sheet.clientHeight / 2;
  }, []);
  const jump = (row: GraphRow) => {
    goToBranch(row.branch);
    // 等页面切过去、列表滚完，再把那条发言滚到中间，让它亮一下
    setTimeout(() => {
      const el = document.querySelector(`[data-entry="${row.id}"]`);
      el?.scrollIntoView({ block: 'center', behavior: 'smooth' });
      setTimeout(() => flash(el?.querySelector('.bubble') ?? el, { radius: 190, strength: 0.8 }), 380);
    }, 620);
  };
  const width = lx(g.width - 1) + PAD;
  return (
    <Sheet title="分支图" className="graph-sheet" onClose={closeSheet}>
      {!g.rows.length ? <p className="sheet-text muted">还没有发言。</p> : (
        <div className="graph" style={{ '--gw': width + 'px' } as CSSProperties}>
          <svg className="graph-lines" width={width} height={g.rows.length * ROW} aria-hidden="true">
            {g.lanes.map(l => <line key={'l' + (l.branch ?? 't')} className={'lane-' + (l.lane % LANES)} x1={lx(l.lane)} x2={lx(l.lane)} y1={ry(l.fromRow)} y2={ry(l.toRow)} />)}
            {g.edges.map((e, i) => <path key={i} className={'edge lane-' + ((e.kind === 'fork' ? e.toLane : e.fromLane) % LANES) + (e.kind === 'merge' ? ' merge' : '')} d={(e.kind === 'fork' ? forkPath : mergePath)(lx(e.fromLane), ry(e.fromRow), lx(e.toLane), ry(e.toRow))} />)}
            {g.rows.map(r => {
              const x = lx(r.lane), y = ry(r.index), c = 'node lane-' + (r.lane % LANES) + ' ' + r.kind + (onPath.has(r.id) ? '' : ' off');
              return r.kind === 'back' ? <rect key={r.id} className={c} x={x - 6} y={y - 6} width={12} height={12} rx={2} transform={`rotate(45 ${x} ${y})`} />
                : <circle key={r.id} className={c} cx={x} cy={y} r={r.kind === 'me' ? 6.5 : 5.5} />;
            })}
          </svg>
          <ol className="graph-rows" ref={listRef}>
            {g.rows.map(r => (
              <li key={r.id} className={'g-row' + (r.branch === here ? ' here' : '') + (onPath.has(r.id) ? '' : ' off') + (r.id === lastHere?.id ? ' here-last' : '')}>
                <button disabled={busy} onClick={() => jump(r)} aria-label={`${r.who}：${clip(r.text, 40)}，在${topic(r.branch)}`}>
                  <span className="g-head">
                    {firstOf.get(r.branch ?? '') === r.id && <i className={'g-tag lane-' + (r.lane % LANES)}>{topic(r.branch)}</i>}
                    <b>{r.who}</b>
                  </span>
                  <span className="g-text">{r.text}</span>
                </button>
              </li>
            ))}
          </ol>
        </div>
      )}
      <p className="fineprint">每个分支里，AI 只看得到主干到分叉点为止的内容和这个分支本身。点一行就跳过去。</p>
    </Sheet>
  );
}
const LANES = 6;

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
