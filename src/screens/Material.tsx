// 读材料（#144）：贴一篇论文或文章，研读助手读完整理出技术点地图，挑一个点开始讨论。
// 只负责展示，操作都在 actions 里。完整的材料只存在这台设备上。
import { useEffect, useRef, useState } from 'react';
import type { MapPoint, ModeId } from '../../shared/types.ts';
import {
  addMaterial,
  loadMaterials,
  openMaterial,
  openMaterials,
  readMaterial,
  removeMaterial,
  startFromPoint,
} from '../actions.ts';
import { CaretField } from '../components/SmoothCaret.tsx';
import { BusyStrip, ErrorBanner, Icon, TopBar } from '../components/ui.tsx';
import { SAMPLE_MATERIAL } from '../lib/demo.ts';
import type { Material } from '../lib/materials.ts';
import { useApp } from '../store.ts';

const date = (t: number) => new Date(t).toLocaleDateString('zh-CN');

export function MaterialScreen() {
  const { ui } = useApp();
  useEffect(() => {
    void loadMaterials();
  }, []);
  return ui.material ? <Reading m={ui.material} /> : <Paste />;
}

// 贴材料：标题可以不写（用第一行）；下面是最近读过的材料
function Paste() {
  const { ui } = useApp();
  const [title, setTitle] = useState('');
  const [text, setText] = useState('');
  const ref = useRef<HTMLTextAreaElement>(null);
  const busy = !!ui.busy;
  return (
    <div className="screen material">
      <TopBar title="读材料" sub="贴一篇论文或文章，一个点一个点弄懂" back="learn" />
      <div className="stack pad">
        <section className="stack-sm">
          <input
            value={title}
            maxLength={80}
            placeholder="标题（可以不写）"
            aria-label="材料标题"
            disabled={busy}
            onChange={e => setTitle(e.target.value)}
          />
          <CaretField fieldRef={ref}>
            <textarea
              ref={ref}
              className="material-text"
              rows={10}
              value={text}
              aria-label="材料正文"
              placeholder="把论文或文章的正文贴在这里。带着小节标题更好，研读助手会按节来读。"
              disabled={busy}
              onChange={e => setText(e.target.value)}
            />
          </CaretField>
          <div className="row between">
            <small className="muted">{text.trim().length ? `${text.trim().length} 字` : ''}</small>
            <button
              className="ghost"
              disabled={busy}
              onClick={() => {
                setTitle(SAMPLE_MATERIAL.title);
                setText(SAMPLE_MATERIAL.text);
              }}
            >
              用示例文章试试
            </button>
          </div>
          <p className="fineprint">
            材料只保存在这台设备上。研读时，只把研读助手要读的几节发给
            AI；讨论时，只带这个点的原文节选。 链接和 PDF 还在做，先把正文复制过来。
          </p>
        </section>
        {ui.materials.length > 0 && (
          <section>
            <h3 className="section-label">读过的材料</h3>
            <ul className="material-list">
              {ui.materials.map(m => (
                <li key={m.id}>
                  <button className="material-item" onClick={() => void openMaterial(m.id)}>
                    <b>{m.title}</b>
                    <small className="muted">
                      {date(m.created)}
                      {m.points ? ` · ${m.points} 个技术点` : ' · 还没读完'}
                    </small>
                  </button>
                  <button
                    className="icon-btn"
                    aria-label={`删除《${m.title}》`}
                    disabled={busy}
                    onClick={() => void removeMaterial(m.id)}
                  >
                    <Icon name="close" size={18} />
                  </button>
                </li>
              ))}
            </ul>
          </section>
        )}
      </div>
      <div className="dock">
        <ErrorBanner />
        <BusyStrip />
        <button
          className="primary block"
          disabled={busy || !text.trim()}
          onClick={() => void addMaterial(title, text)}
        >
          开始研读
        </button>
      </div>
    </div>
  );
}

// 读一份材料：研读助手每一步在做什么、整理出的技术点地图、原文
function Reading({ m }: { m: Material }) {
  const { ui } = useApp();
  const busy = !!ui.busy;
  const steps = ui.reading ?? m.steps ?? [];
  const map = m.map;
  const [picked, setPicked] = useState<MapPoint | null>(null);
  const [mode, setMode] = useState<ModeId>('socratic');
  const point = picked ?? map?.points[0] ?? null;
  return (
    <div className="screen material">
      <TopBar
        title={map?.title || m.title}
        sub={`${m.sections.length} 节${map ? ` · ${map.points.length} 个技术点` : ''}`}
        back={openMaterials}
      />
      <div className="stack pad">
        {map?.summary && <p className="knowledge material-summary">{map.summary}</p>}
        {steps.length > 0 && (
          <details className="panel trace" open={!map}>
            <summary className="section-label">研读过程 · {steps.length} 步</summary>
            <ol>
              {steps.map((st, i) => (
                <li key={i}>
                  <b>{st.action}</b>
                  {st.thought && <span className="muted">{st.thought}</span>}
                </li>
              ))}
            </ol>
          </details>
        )}
        {!map && !busy && (
          <section className="panel center stack-sm">
            <p>研读助手还没整理出技术点。</p>
            <button className="primary" onClick={() => void readMaterial()}>
              让研读助手读一遍
            </button>
          </section>
        )}
        {map && (
          <section>
            <h3 className="section-label">技术点地图 · 挑一个开始</h3>
            <ul className="map-points">
              {map.points.map(p => (
                <li key={p.name}>
                  <button
                    className={'map-point' + (point?.name === p.name ? ' on' : '')}
                    aria-pressed={point?.name === p.name}
                    disabled={busy}
                    onClick={() => setPicked(p)}
                  >
                    <b className="knowledge">{p.name}</b>
                    {p.why && <span>{p.why}</span>}
                    <small className="muted">
                      {p.sections.length ? p.sections.map(n => `§${n}`).join(' ') : ''}
                      {p.needs.length
                        ? `${p.sections.length ? ' · ' : ''}要先懂：${p.needs.join('、')}`
                        : ''}
                    </small>
                  </button>
                </li>
              ))}
            </ul>
          </section>
        )}
        <details className="panel">
          <summary className="section-label">原文 · {m.sections.length} 节</summary>
          <div className="material-sections">
            {m.sections.map(s => (
              <article key={s.n}>
                <h4>
                  §{s.n} {s.heading}
                </h4>
                <p>{s.text}</p>
              </article>
            ))}
          </div>
        </details>
      </div>
      {map && point && (
        <div className="dock">
          <ErrorBanner />
          <BusyStrip />
          <div className="segmented" role="radiogroup" aria-label="怎么讨论">
            {(['socratic', 'roundtable'] as const).map(id => (
              <button
                key={id}
                role="radio"
                aria-checked={mode === id}
                className={mode === id ? 'on' : ''}
                disabled={busy}
                onClick={() => setMode(id)}
              >
                {id === 'socratic' ? '追问' : '圆桌'}
              </button>
            ))}
          </div>
          <button
            className="primary block"
            disabled={busy}
            onClick={() => void startFromPoint(point, mode)}
          >
            从「{point.name}」开始讨论
          </button>
        </div>
      )}
      {!map && (
        <div className="dock">
          <ErrorBanner />
          <BusyStrip />
        </div>
      )}
    </div>
  );
}
