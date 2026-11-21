import { MODES, MODE_ORDER } from '../../shared/modes.ts';
import type { ModeId } from '../../shared/types.ts';
import { openMaterials, prepare, setContextDraft } from '../actions.ts';
import { useRef } from 'react';
import { BusyStrip, ErrorBanner, Icon } from '../components/ui.tsx';
import { CaretField } from '../components/SmoothCaret.tsx';
import { go, typing, update, useApp } from '../store.ts';

const EXAMPLES = ['机会成本', '贝叶斯定理', '边际效用', '认知失调', '光速不变原理', '梯度下降'];
const GLYPH: Record<ModeId, string> = {
  socratic: '问',
  roundtable: '圆',
  teach: '讲',
  debate: '辩',
  canvas: '枝',
};

export function Learn() {
  const conceptRef = useRef<HTMLInputElement>(null);
  const contextRef = useRef<HTMLTextAreaElement>(null);
  const { config, session, ui, ai } = useApp();
  const unfinished = session && !session.finished;
  const busy = !!ui.busy;
  const canStart = !busy && !!config.concept.trim() && (!ui.context || !!ui.context.text.trim());
  const startLabel = ui.context ? '按这个经过开始' : '开始讨论';
  return (
    <div className="screen learn">
      <header className="learn-head">
        <div className="brand">
          <span className="brand-mark" aria-hidden="true">
            〞
          </span>
          思辨场
        </div>
        <button className="chip-btn" onClick={() => go('settings')}>
          {config.aiMode === 'remote'
            ? ai.remoteVerified || config.remote.url
              ? '我的后端'
              : '后端未连接'
            : config.aiMode === 'api'
              ? ai.configured
                ? '本机服务'
                : '本机未连接'
              : '演示模式'}
        </button>
      </header>

      <div className="stack pad">
        {unfinished && (
          <button className="banner" onClick={() => go('session')}>
            <span>
              <b>继续讨论</b>
              {session.concept} · 已点亮 {session.covered.length}/{session.keyPoints.length}
            </span>
            <Icon name="chevron" />
          </button>
        )}

        <section className="hero">
          <h2 className="display">
            今天想弄懂
            <br />
            什么？
          </h2>
          <label className="concept-field">
            <span className="sr-only">想弄懂的概念</span>
            <CaretField fieldRef={conceptRef}>
              <input
                id="concept"
                ref={conceptRef}
                value={config.concept}
                maxLength={80}
                placeholder="写下一个概念，比如：机会成本"
                disabled={busy}
                onChange={e =>
                  typing(s => {
                    s.config.concept = e.target.value;
                    s.ui.context = null;
                  })
                }
                onKeyDown={e => {
                  if (e.key === 'Enter' && !e.nativeEvent.isComposing)
                    void prepare(null, undefined, 'start');
                }}
              />
            </CaretField>
          </label>
          <div className="chips scroll-x" role="list">
            {EXAMPLES.map(c => (
              <button
                role="listitem"
                key={c}
                className={'chip' + (config.concept === c ? ' on' : '')}
                disabled={busy}
                onClick={() =>
                  update(s => {
                    s.config.concept = c;
                    s.ui.context = null;
                  })
                }
              >
                {c}
              </button>
            ))}
          </div>
          {/* 写的是一件具体的事：AI 先请你交代经过，再出题 */}
          {ui.context && (
            <div className="context-ask">
              <p className="context-q">{ui.context.ask}</p>
              <CaretField fieldRef={contextRef}>
                <textarea
                  ref={contextRef}
                  rows={3}
                  maxLength={600}
                  value={ui.context.text}
                  aria-label="事情的经过"
                  placeholder="用一两句话说说经过：发生了什么、谁怎么想"
                  disabled={busy}
                  onChange={e => setContextDraft(e.target.value)}
                />
              </CaretField>
              <small className="muted">AI 只按你写的经过来讨论，不会自己编细节。</small>
            </div>
          )}
          {/* 读材料（#144、#166）：从一篇论文或文章开始，直接围绕它讨论 */}
          <button className="material-entry" disabled={busy} onClick={openMaterials}>
            <Icon name="note" size={18} />
            <span className="grow">
              <b>从一篇论文或文章开始</b>
              <small>贴上正文、链接或 PDF，直接围绕它讨论</small>
            </span>
            <Icon name="chevron" size={18} />
          </button>
        </section>

        <section>
          <h3 className="section-label">怎么讨论</h3>
          <div className="modes">
            {MODE_ORDER.map(m => (
              <button
                key={m}
                className={'mode' + (config.mode === m ? ' on' : '')}
                aria-pressed={config.mode === m}
                disabled={busy}
                onClick={() =>
                  update(s => {
                    s.config.mode = m;
                  })
                }
              >
                <span className="mode-glyph" aria-hidden="true">
                  {GLYPH[m]}
                </span>
                <b>{MODES[m].name}</b>
                <span>{MODES[m].desc}</span>
              </button>
            ))}
          </div>
        </section>

        {config.aiMode === 'demo' && (
          <p className="fineprint">
            演示模式只有“机会成本”是完整示例。在设置里连接 AI，就能讨论任何概念。
          </p>
        )}
      </div>

      <div className="dock">
        <ErrorBanner />
        <BusyStrip />
        {/* 写下概念后，开始按钮才从底部升起：没写时不摆一条点不了的灰按钮。
            左边先看看题目、要点和角色（或者邀请同学），右边一句话开始，写明用哪种方式讨论哪个概念 */}
        {config.concept.trim() && (
          <div className="start-bar">
            <button
              className="secondary"
              disabled={!canStart}
              title={
                config.mode === 'roundtable' || config.mode === 'debate'
                  ? '先看看题目和角色，或邀请同学'
                  : '先看看题目、要点和角色'
              }
              onClick={() => prepare()}
            >
              先看题目
            </button>
            <button
              className="primary start"
              disabled={!canStart}
              aria-label={`${startLabel}：${MODES[config.mode].name} · ${config.concept.trim()}`}
              onClick={() => prepare(null, undefined, 'start')}
            >
              <span className="start-text">
                <b>{startLabel}</b>
                <small>
                  {MODES[config.mode].name} · {config.concept.trim()}
                </small>
              </span>
              <span className="start-arrow" aria-hidden="true">
                <Icon name="send" size={20} />
              </span>
            </button>
          </div>
        )}
      </div>
    </div>
  );
}
