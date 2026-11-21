// 读材料（#144、#166）：贴一篇论文或文章（或者链接、PDF），选追问或圆桌，直接围绕它讨论。
// 只负责展示，操作都在 actions 里。完整的材料只存在这台设备上。
import { useEffect, useRef, useState } from 'react';
import type { ModeId } from '../../shared/types.ts';
import {
  addMaterial,
  discussMaterial,
  fetchLink,
  importPdf,
  loadMaterials,
  openMaterial,
  openMaterials,
  removeMaterial,
} from '../actions.ts';
import { CaretField } from '../components/SmoothCaret.tsx';
import { BusyStrip, ErrorBanner, Icon, TopBar } from '../components/ui.tsx';
import { SAMPLE_MATERIAL } from '../lib/demo.ts';
import type { Material } from '../lib/materials.ts';
import { useApp } from '../store.ts';

const date = (t: number) => new Date(t).toLocaleDateString('zh-CN');

// 围绕材料怎么讨论：追问（一位 AI 一路追问）或圆桌（几位 AI 各有角度）
function ModePick({
  mode,
  onChange,
  busy,
}: {
  mode: ModeId;
  onChange: (m: ModeId) => void;
  busy: boolean;
}) {
  return (
    <div className="segmented" role="radiogroup" aria-label="怎么讨论">
      {(['socratic', 'roundtable'] as const).map(id => (
        <button
          key={id}
          role="radio"
          aria-checked={mode === id}
          className={mode === id ? 'on' : ''}
          disabled={busy}
          onClick={() => onChange(id)}
        >
          {id === 'socratic' ? '追问' : '圆桌'}
        </button>
      ))}
    </div>
  );
}

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
  const [mode, setMode] = useState<ModeId>('socratic');
  // 材料从哪来：读到的链接，或者 PDF 的文件名（存进材料，以后知道它出自哪里）
  const [source, setSource] = useState('');
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
    setSource(file.name);
    setNote({
      text: `从 ${r.pages} 页 PDF 里读到了 ${r.text.replace(/\s+/g, '').length} 字。${r.note}核对一下，没问题就开始讨论。`,
      warn: false,
    });
  };
  const read = async () => {
    const r = await fetchLink(link);
    if (!r) return;
    setTitle(r.title.slice(0, 80));
    setText(r.text);
    setSource(r.url);
    const chars = r.text.replace(/\s+/g, '').length;
    setNote({
      text: `读到了 ${chars} 字。${r.note || '核对一下，没问题就开始讨论。'}`,
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
              placeholder="把论文或文章的正文贴在这里。带着小节标题更好，讨论时 AI 会按节找到相关的原文。"
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
          <p className="fineprint">材料和 PDF 只留在这台设备上，讨论时只把相关的几节发给 AI。</p>
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
                      {` · ${m.sections} 节`}
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
        <ModePick mode={mode} onChange={setMode} busy={busy} />
        <button
          className="primary block"
          disabled={busy || !text.trim()}
          onClick={() => void addMaterial(title, text, mode, source || undefined)}
        >
          围绕这篇材料开始讨论
        </button>
      </div>
    </div>
  );
}

// 读过的一份材料：从哪来、原文，继续围绕它讨论
function Reading({ m }: { m: Material }) {
  const { ui } = useApp();
  const busy = !!ui.busy;
  const [mode, setMode] = useState<ModeId>('socratic');
  return (
    <div className="screen material">
      <TopBar
        title={m.title}
        sub={`${m.sections.length} 节 · ${date(m.created)}`}
        back={openMaterials}
      />
      <div className="stack pad">
        {m.source && <p className="fineprint material-source">出自：{m.source}</p>}
        <p className="fineprint">讨论时 AI 会翻到相关的段落，复盘记进概念和材料的笔记。</p>
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
      <div className="dock">
        <ErrorBanner />
        <BusyStrip />
        <ModePick mode={mode} onChange={setMode} busy={busy} />
        <button
          className="primary block"
          disabled={busy}
          onClick={() => void discussMaterial(mode)}
        >
          围绕这篇材料讨论
        </button>
      </div>
    </div>
  );
}
