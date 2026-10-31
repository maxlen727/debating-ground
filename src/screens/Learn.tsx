import { MODES, MODE_ORDER } from '../../shared/modes.ts';
import type { ModeId } from '../../shared/types.ts';
import { prepare, openCard } from '../actions.ts';
import { BusyStrip, ErrorBanner, Icon } from '../components/ui.tsx';
import { go, update, useApp } from '../store.ts';

const EXAMPLES = ['机会成本', '贝叶斯定理', '边际效用', '认知失调', '光速不变原理', '梯度下降'];
const GLYPH: Record<ModeId, string> = { socratic: '问', roundtable: '圆', teach: '讲', debate: '辩' };

export function Learn() {
  const { config, library, session, ui, ai } = useApp();
  const due = library.filter(c => c.due <= Date.now());
  const unfinished = session && !session.finished;
  const busy = !!ui.busy;
  return (
    <div className="screen learn">
      <header className="learn-head">
        <div className="brand"><span className="brand-mark" aria-hidden="true">〞</span>思辨场</div>
        <button className="chip-btn" onClick={() => go('settings')}>{config.aiMode === 'api' ? (ai.configured ? '自带 API' : 'API 未连接') : '演示模式'}</button>
      </header>

      <div className="stack pad">
        {unfinished && (
          <button className="banner" onClick={() => go('session')}>
            <span><b>继续讨论</b>{session.concept} · 已点亮 {session.covered.length}/{session.keyPoints.length}</span><Icon name="chevron" />
          </button>
        )}
        {due.length > 0 && (
          <button className="banner warm" onClick={() => due.length === 1 ? openCard(due[0].id) : go('library')}>
            <span><b>今天有 {due.length} 张卡片要复习</b>{due.slice(0, 3).map(c => c.concept).join('、')}</span><Icon name="chevron" />
          </button>
        )}

        <section className="hero">
          <h2 className="display">今天想弄懂<br />什么？</h2>
          <label className="concept-field">
            <span className="sr-only">想弄懂的概念</span>
            <input id="concept" value={config.concept} maxLength={80} placeholder="写下一个概念，比如：机会成本" disabled={busy}
              onChange={e => update(s => { s.config.concept = e.target.value; })}
              onKeyDown={e => { if (e.key === 'Enter' && !e.nativeEvent.isComposing) prepare(); }} />
          </label>
          <div className="chips scroll-x" role="list">
            {EXAMPLES.map(c => <button role="listitem" key={c} className={'chip' + (config.concept === c ? ' on' : '')} disabled={busy} onClick={() => update(s => { s.config.concept = c; })}>{c}</button>)}
          </div>
        </section>

        <section>
          <h3 className="section-label">怎么讨论</h3>
          <div className="modes">
            {MODE_ORDER.map(m => (
              <button key={m} className={'mode' + (config.mode === m ? ' on' : '')} aria-pressed={config.mode === m} disabled={busy} onClick={() => update(s => { s.config.mode = m; })}>
                <span className="mode-glyph" aria-hidden="true">{GLYPH[m]}</span>
                <b>{MODES[m].name}</b>
                <span>{MODES[m].desc}</span>
                <small>适合：{MODES[m].when}</small>
              </button>
            ))}
          </div>
        </section>

        {library.length === 0 && <p className="fineprint">讨论结束后会生成理解复盘，存成知识卡片，按 1、3、7、14 天的间隔提醒你回顾。</p>}
        {config.aiMode === 'demo' && <p className="fineprint">当前是演示模式：“机会成本”有完整示例，其他概念用通用模板。要让 AI 真正参与讨论，需要在电脑上运行思辨场，并在设置里连接自己的 API。</p>}
      </div>

      <div className="dock">
        <ErrorBanner />
        <BusyStrip />
        <button className="primary block" disabled={busy || !config.concept.trim()} onClick={() => prepare()}>准备讨论</button>
      </div>
    </div>
  );
}
