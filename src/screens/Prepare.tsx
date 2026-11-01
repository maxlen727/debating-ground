import { MODES, STAGE_KINDS } from '../../shared/modes.ts';
import type { StageKind, Team } from '../../shared/types.ts';
import { useRef, useState } from 'react';
import { CaretField } from '../components/SmoothCaret.tsx';
import { canInvite, inviteFriends, prepare, startSession } from '../actions.ts';
import { BusyStrip, ErrorBanner, Icon, Sheet, Stepper, TopBar } from '../components/ui.tsx';
import { debateTurns, slots } from '../lib/debate.ts';
import { go, update, useApp } from '../store.ts';

export function Prepare() {
  const { plan, config, ui } = useApp();
  const [inviting, setInviting] = useState(false);
  const contextRef = useRef<HTMLTextAreaElement>(null);
  if (!plan) return <div className="screen"><TopBar title="准备讨论" back="learn" /></div>;
  const debate = plan.mode === 'debate';
  const busy = !!ui.busy;
  const setPlan = (fn: (p: NonNullable<typeof plan>) => void) => update(s => { if (s.plan) fn(s.plan); });

  return (
    <div className="screen prepare">
      <TopBar title={plan.concept} sub={MODES[plan.mode].name} back="learn" />
      <div className="stack pad">
        <p className="lede">{plan.source === 'api' ? `由 ${plan.model} 准备` : '演示模板'}，开始前都可以改。{plan.previous && '这次会从上次还模糊的地方切入。'}</p>

        {plan.context !== undefined && (
          <section className="panel">
            <h3 className="section-label">你交代的经过</h3>
            <CaretField fieldRef={contextRef}>
              <textarea ref={contextRef} className="context-input" value={plan.context} maxLength={600} rows={3} aria-label="事情的经过" onChange={e => setPlan(p => { p.context = e.target.value; })} />
            </CaretField>
            <p className="fineprint">讨论时 AI 会以这段经过为准。</p>
          </section>
        )}

        <section className="panel">
          <h3 className="section-label">{debate ? '辩题' : '讨论题'}</h3>
          <textarea className="topic-input" value={plan.topic} maxLength={200} rows={2} aria-label="讨论题" onChange={e => setPlan(p => { p.topic = e.target.value; })} />
        </section>

        <section className="panel">
          <div className="row between"><h3 className="section-label">{debate ? '核心争点' : '要弄懂的要点'}</h3><small className="muted">{plan.keyPoints.length} / 6</small></div>
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
          <p className="fineprint">{debate ? '3 到 4 个核心争点就够了：它们是交锋的方向，不是要逐条完成的清单，也不影响赛程。复盘看的是你对每个争点背后概念的理解，不看输赢。' : '讨论中讲清楚一个要点，它会在顶部点亮；复盘也按这份清单逐条评估。'}</p>
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
        {canInvite(plan.mode) && <button className="ghost block" disabled={busy} onClick={() => setInviting(true)}><Icon name="people" size={18} />邀请同学一起讨论</button>}
      </div>
      {inviting && <InviteSheet onClose={() => setInviting(false)} />}
    </div>
  );
}

function InviteSheet({ onClose }: { onClose: () => void }) {
  const { config, plan, ui } = useApp();
  const [name, setName] = useState(config.name);
  const ready = config.aiMode === 'remote' && !!config.remote.url;
  const debate = plan?.mode === 'debate';
  const peerSeats = slots(config).filter(r => r.type === 'peer').length + Math.max(0, slots(config).filter(r => r.type === 'human').length - 1);
  return (
    <Sheet title="邀请同学一起讨论" onClose={onClose}>
      {ready ? (
        <>
          <p className="sheet-text">创建一个讨论房间，把链接发给同学。大家用自己的手机加入，{debate ? '按赛制轮流上场' : '轮流发言，每个人说完由一位 AI 嘉宾接话'}。</p>
          <label className="field">
            <span>你的名字</span>
            <input value={name} maxLength={20} placeholder="同学会看到这个名字" onChange={e => setName(e.target.value)} />
          </label>
          <label className="field">
            <span>每次发言的时限（到点只提醒，不打断）</span>
            <select value={config.turnSeconds} onChange={e => update(s => { s.config.turnSeconds = Number(e.target.value) as 0 | 30 | 60 | 90 | 120; })}>
              <option value={0}>不计时</option><option value={30}>30 秒</option><option value={60}>1 分钟</option><option value={90}>1 分 30 秒</option><option value={120}>2 分钟</option>
            </select>
          </label>
          {debate && <p className="note">标为“同学”的席位留给加入的同学，轮到时没人坐就等着，你可以跳过或让 AI 代答；标为“AI”的席位由 AI 担任，同学也可以坐进去。你默认坐第一个标为“我”的席位。进房间后每个人都可以自己选席位。</p>}
          {debate && peerSeats === 0 && <p className="alert soft">现在没有给同学留席位：同学进来只能坐进 AI 的席位或旁听。可以先关掉这里，把一些席位设成“同学”。</p>}
          <p className="fineprint">AI 使用你的后端和额度，同学不需要访问口令。每个房间最多 8 人、150 次 AI 调用；如果配了讯飞朗读，朗读另算，最多 120 次。讨论内容临时保存在你的后端，结束 1 小时后自动删除。</p>
          <ErrorBanner />
          <button className="primary block" disabled={!!ui.busy || !name.trim()} onClick={async () => { await inviteFriends(name); }}>{ui.busy ? ui.busy : '创建房间'}</button>
        </>
      ) : (
        <>
          <p className="sheet-text">多人讨论需要一个属于你的后端：房间和 AI 都在那里运行。</p>
          <p className="note">先在“设置”里选择“我的后端”并连接。还没有后端的话，可以一键部署到你自己的 Cloudflare 账号（免费额度就够）。</p>
          <button className="primary block" onClick={() => go('settings')}>去设置</button>
        </>
      )}
    </Sheet>
  );
}

const SEAT_LABEL = { human: '我', peer: '同学', ai: 'AI' } as const;

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
                    {(['human', 'peer', 'ai'] as const).map(v => <button key={v} role="radio" aria-checked={r.type === v} className={r.type === v ? 'on' : ''} onClick={() => update(s => { s.config.seats[t][i].type = v; })}>{SEAT_LABEL[v]}</button>)}
                  </div>
                </div>
                {r.type === 'peer' && <p className="seat-hint">邀请同学时留给同学；单人讨论时由 AI 担任。</p>}
                {r.type !== 'human' && <textarea value={r.bg} rows={2} maxLength={500} aria-label={`${t}${i + 1} 认知背景`} placeholder="认知背景" onChange={e => update(s => { s.config.seats[t][i].bg = e.target.value; })} />}
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
