// 读材料（#144）：贴一篇论文或文章，研读助手读完整理出技术点地图，挑一个点开始讨论。
// 只负责展示，操作都在 actions 里。完整的材料只存在这台设备上。
import { useEffect, useRef, useState } from 'react';
import type { MapPoint, ModeId } from '../../shared/types.ts';
import {
  addMaterial,
  fetchLink,
  importPdf,
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
  const [link, setLink] = useState('');
  // 读链接的结果说明：拿到了什么、是不是全文
  const [note, setNote] = useState<{ text: string; warn: boolean } | null>(null);
  const ref = useRef<HTMLTextAreaElement>(null);
  // 选了 PDF 文件（#162）：只在这台设备上解析
  const pickPdf = async (file: File | undefined) => {
    if (!file) return;
    const r = await importPdf(file);
    if (!r) return;
    setTitle(r.title);
    setText(r.text);
    setNote({
      text: `从 ${r.pages} 页 PDF 里读到了 ${r.text.replace(/\s+/g, '').length} 字。${r.note}核对一下，没问题就开始研读。`,
      warn: false,
    });
  };
  const read = async () => {
    const r = await fetchLink(link);
    if (!r) return;
    setTitle(r.title.slice(0, 80));
    setText(r.text);
    const chars = r.text.replace(/\s+/g, '').length;
    setNote({
      text: `读到了 ${chars} 字。${r.note || '核对一下，没问题就开始研读。'}`,
      warn: !r.complete,
    });
  };
  const busy = !!ui.busy;
  return (
    <div className="screen material">
      <TopBar title="读材料" sub="贴一篇论文或文章，一个点一个点弄懂" back="learn" />
      <div className="stack pad">
        <section className="stack-sm">
          {/* 读链接（#157）：取到的正文填进下面的框，核对后再开始研读 */}
          <div className="row link-row">
            <input
              type="url"
              inputMode="url"
              className="grow"
              value={link}
              placeholder="贴一个链接：arXiv 论文、网页文章"
              aria-label="材料链接"
              disabled={busy}
              enterKeyHint="go"
              onChange={e => setLink(e.target.value)}
              onKeyDown={e => {
                if (e.key === 'Enter' && !e.nativeEvent.isComposing) void read();
              }}
            />
            <button
              className="secondary"
              disabled={busy || !link.trim()}
              onClick={() => void read()}
            >
              读取
            </button>
          </div>
          <label className={'ghost file-pick' + (busy ? ' disabled' : '')}>
            <Icon name="note" size={16} />
            选一个 PDF 文件
            <input
              type="file"
              accept="application/pdf,.pdf"
              disabled={busy}
              onChange={e => {
                void pickPdf(e.target.files?.[0]);
                e.target.value = '';
              }}
            />
          </label>
          {note && (
            <p className={'link-note' + (note.warn ? ' warn' : '')} role="status">
              {note.text}
            </p>
          )}
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
            AI；讨论时，只带这个点的原文节选。读链接时，由本机服务或你的后端去取这个网页，只读不改。PDF
            只在这台设备上解析，不上传。
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
