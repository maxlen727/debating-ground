import { MODES, STAGE_KINDS } from '../../shared/modes.ts';
import type { StageKind, Team } from '../../shared/types.ts';
import { prepare, startSession } from '../actions.ts';
import { BusyStrip, ErrorBanner, Icon, Stepper, TopBar } from '../components/ui.tsx';
import { debateTurns } from '../lib/debate.ts';
import { update, useApp } from '../store.ts';

export function Prepare() {
  const { plan, config, ui } = useApp();
  if (!plan) return <div className="screen"><TopBar title="准备讨论" back="learn" /></div>;
  const debate = plan.mode === 'debate';
  const busy = !!ui.busy;
  const setPlan = (fn: (p: NonNullable<typeof plan>) => void) => update(s => { if (s.plan) fn(s.plan); });

  return (
    <div className="screen prepare">
      <TopBar title={plan.concept} sub={MODES[plan.mode].name} back="learn" />
      <div className="stack pad">
        <p className="lede">{plan.source === 'api' ? `由 ${plan.model} 准备` : '演示模板'}，开始前都可以改。{plan.previous && '这次会从上次还模糊的地方切入。'}</p>

        <section className="panel">
          <h3 className="section-label">{debate ? '辩题' : '讨论题'}</h3>
          <textarea className="topic-input" value={plan.topic} maxLength={200} rows={2} aria-label="讨论题" onChange={e => setPlan(p => { p.topic = e.target.value; })} />
        </section>

        <section className="panel">
          <div className="row between"><h3 className="section-label">要弄懂的要点</h3><small className="muted">{plan.keyPoints.length} / 6</small></div>
          <ol className="point-edit">
            {plan.keyPoints.map((k, i) => (
              <li key={i}>
                <span className="num">{i + 1}</span>
                <input value={k} maxLength={40} aria-label={`要点 ${i + 1}`} onChange={e => setPlan(p => { p.keyPoints[i] = e.target.value; })} />
                <button className="icon-btn small" aria-label={`删除要点 ${i + 1}`} disabled={plan.keyPoints.length <= 2} onClick={() => setPlan(p => { p.keyPoints.splice(i, 1); })}><Icon name="close" size={16} /></button>
              </li>
            ))}
          </ol>
          {plan.keyPoints.length < 6 && <button className="ghost small" onClick={() => setPlan(p => { p.keyPoints.push(''); })}><Icon name="plus" size={16} />添加要点</button>}
          <p className="fineprint">讨论中讲清楚一个要点，它会在顶部点亮；复盘也按这份清单逐条评估。</p>
        </section>

        {!debate && (
          <section className="panel">
            <h3 className="section-label">{plan.personas.length > 1 ? '圆桌嘉宾' : '讨论对象'}</h3>
            <div className="persona-list">
              {plan.personas.map((r, i) => (
                <div key={r.id} className="persona-edit">
                  <span className={'avatar tone-' + r.tone} aria-hidden="true">{r.name.slice(0, 1)}</span>
                  <div className="grow">
                    <div className="two">
                      <input value={r.name} maxLength={20} aria-label={`角色 ${i + 1} 名字`} onChange={e => setPlan(p => { p.personas[i].name = e.target.value; })} />
                      <input value={r.title} maxLength={20} aria-label={`角色 ${i + 1} 身份`} onChange={e => setPlan(p => { p.personas[i].title = e.target.value; })} />
                    </div>
                    <textarea value={r.persona} maxLength={300} rows={2} aria-label={`角色 ${i + 1} 认知背景`} placeholder="认知背景：会影响它的知识范围和表达方式" onChange={e => setPlan(p => { p.personas[i].persona = e.target.value; })} />
                  </div>
                </div>
              ))}
            </div>
          </section>
        )}

        {debate && <Seats />}
        {debate && <Stages />}
        {debate && <p className="fineprint">共 {debateTurns(config).length} 次发言。简化赛制：双方一辩立论；二辩质询对方三辩（不足三席时取末席）；自由辩论轮流；末辩总结，反方先行。</p>}
      </div>

      <div className="dock">
        <ErrorBanner />
        <BusyStrip />
        <div className="row">
          <button className="secondary" disabled={busy} onClick={() => { update(s => { s.config.concept = plan.concept; s.config.mode = plan.mode; }); prepare(plan.previous); }}>重新生成</button>
          <button className="primary grow" disabled={busy} onClick={startSession}>开始讨论</button>
        </div>
      </div>
    </div>
  );
}

function Seats() {
  const { config } = useApp();
  const setCount = (t: Team, n: number) => update(s => {
    s.config.counts[t] = n;
    while (s.config.seats[t].length < n) s.config.seats[t].push({ type: 'ai', bg: '从实际案例出发，关注论证的适用条件。' });
  });
  return (
    <section className="panel">
      <h3 className="section-label">席位</h3>
      <div className="teams">
        {(['A', 'B'] as const).map(t => (
          <div key={t} className={'team team-' + t}>
            <div className="row between"><b>{t === 'A' ? '正方 · 支持' : '反方 · 反对'}</b><Stepper value={config.counts[t]} min={1} max={4} label={t === 'A' ? '正方人数' : '反方人数'} onChange={n => setCount(t, n)} /></div>
            {config.seats[t].slice(0, config.counts[t]).map((r, i) => (
              <div key={i} className="seat">
                <div className="row between">
                  <span className="seat-name">{t === 'A' ? '正' : '反'}方 {i + 1} 辩</span>
                  <div className="segmented small" role="radiogroup" aria-label={`${t}${i + 1} 由谁发言`}>
                    {(['human', 'ai'] as const).map(v => <button key={v} role="radio" aria-checked={r.type === v} className={r.type === v ? 'on' : ''} onClick={() => update(s => { s.config.seats[t][i].type = v; })}>{v === 'human' ? '我' : 'AI'}</button>)}
                  </div>
                </div>
                {r.type === 'ai' && <textarea value={r.bg} rows={2} maxLength={500} aria-label={`${t}${i + 1} 认知背景`} placeholder="认知背景" onChange={e => update(s => { s.config.seats[t][i].bg = e.target.value; })} />}
              </div>
            ))}
          </div>
        ))}
      </div>
    </section>
  );
}

function Stages() {
  const { config } = useApp();
  return (
    <section className="panel">
      <div className="row between"><h3 className="section-label">比赛流程</h3>
        {config.stages.length < 6 && <button className="ghost small" onClick={() => update(s => { s.config.stages.push({ name: '自由辩论', kind: 'free', rounds: 1 }); })}><Icon name="plus" size={16} />添加环节</button>}
      </div>
      <ol className="stage-list">
        {config.stages.map((st, i) => (
          <li key={i} className="stage-row">
            <span className="num">{i + 1}</span>
            <div className="grow stage-fields">
              <input value={st.name} maxLength={20} aria-label={`环节 ${i + 1} 名称`} onChange={e => update(s => { s.config.stages[i].name = e.target.value; })} />
              <div className="row">
                <select value={st.kind} aria-label={`环节 ${i + 1} 规则`} onChange={e => update(s => { s.config.stages[i].kind = e.target.value as StageKind; })}>
                  {Object.entries(STAGE_KINDS).map(([k, l]) => <option key={k} value={k}>{l}</option>)}
                </select>
                <Stepper value={st.rounds} min={1} max={3} label={`环节 ${i + 1} 轮数`} onChange={n => update(s => { s.config.stages[i].rounds = n; })} />
              </div>
            </div>
            <div className="stage-actions">
              <button className="icon-btn small" aria-label={`上移环节 ${i + 1}`} disabled={i === 0} onClick={() => update(s => { const a = s.config.stages; [a[i - 1], a[i]] = [a[i], a[i - 1]]; })}><Icon name="up" size={16} /></button>
              <button className="icon-btn small" aria-label={`删除环节 ${i + 1}`} disabled={config.stages.length === 1} onClick={() => update(s => { s.config.stages.splice(i, 1); })}><Icon name="close" size={16} /></button>
            </div>
          </li>
        ))}
      </ol>
    </section>
  );
}
