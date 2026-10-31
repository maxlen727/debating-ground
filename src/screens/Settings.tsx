import { useState } from 'react';
import { connect, disconnect, testConnection } from '../lib/api.ts';
import { task } from '../lib/ai.ts';
import { BusyStrip, ErrorBanner, TopBar } from '../components/ui.tsx';
import { toast, update, useApp } from '../store.ts';

export function Settings() {
  const { ai, config, ui } = useApp();
  const [url, setUrl] = useState(ai.baseURL);
  const [model, setModel] = useState(ai.model);
  const [key, setKey] = useState('');
  const busy = !!ui.busy;
  const status = !ai.available ? '本机服务未运行' : ai.configured ? (ai.verified ? '已连接，测试通过' : '已配置，未验证') : '尚未连接';

  const onConnect = async () => {
    if (!url.trim() || !model.trim() || !key.trim()) return toast('请填写接口地址、模型名称和 API Key。');
    const input = { baseURL: url.trim(), model: model.trim(), apiKey: key.trim() };
    setKey('');
    const ok = await task('正在连接并测试', async signal => { await connect(input, signal); return true; });
    if (ok) { update(s => { s.ai.verified = true; }); toast('连接成功，可以开始学习。'); }
  };

  return (
    <div className="screen settings">
      <TopBar title="设置" sub="AI 来源与连接" />
      <div className="stack pad">
        <section className="panel">
          <h3 className="section-label">AI 来源</h3>
          <div className="segmented" role="radiogroup" aria-label="AI 来源">
            <button role="radio" aria-checked={config.aiMode === 'demo'} className={config.aiMode === 'demo' ? 'on' : ''} disabled={busy} onClick={() => update(s => { s.config.aiMode = 'demo'; })}>演示模式</button>
            <button role="radio" aria-checked={config.aiMode === 'api'} className={config.aiMode === 'api' ? 'on' : ''} disabled={busy} onClick={() => update(s => { s.config.aiMode = 'api'; })}>自带 API</button>
          </div>
          <p className="fineprint">{config.aiMode === 'demo' ? '演示模式用本地模板走完整个流程，不需要网络和密钥。' : 'API 模式下，失败的请求不会改变讨论进度，也不会自动换成模板。'}</p>
        </section>

        <section className="panel">
          <div className="row between"><h3 className="section-label">连接你的 API</h3><span className={'status ' + (ai.verified ? 'ok' : ai.configured ? 'warn' : '')}>{status}</span></div>
          {!ai.available && <p className="note">真实 AI 需要本机服务：在项目目录运行 <code>npm start</code>（Windows 可双击 start.cmd），再打开 http://127.0.0.1:4173。</p>}
          <label className="field"><span>接口地址</span><input type="url" autoComplete="off" placeholder="https://你的服务商地址/v1" value={url} onChange={e => setUrl(e.target.value)} /><small>Base URL 或完整的 Chat Completions 地址，会自动补上 /chat/completions。</small></label>
          <label className="field"><span>模型名称</span><input autoComplete="off" placeholder="服务商支持的准确模型 ID" value={model} onChange={e => setModel(e.target.value)} /></label>
          <label className="field"><span>API Key</span><input type="password" autoComplete="off" spellCheck={false} placeholder="每次更换配置都要重新填写" value={key} onChange={e => setKey(e.target.value)} /></label>
          <p className="note">“连接并测试”会把密钥发送到你填写的接口，发起一次少量计费的测试。密钥只存在本机服务的内存里，不写入讨论记录、知识库或浏览器存储；停止服务或断开连接就会清除。</p>
          <button className="primary block" disabled={busy || !ai.available} onClick={onConnect}>连接并测试</button>
          <div className="row">
            <button className="secondary grow" disabled={busy || !ai.configured} onClick={async () => { update(s => { s.ai.verified = false; }); if (await task('正在测试连接', testConnection)) { update(s => { s.ai.verified = true; }); toast('API 测试通过。'); } }}>重新测试</button>
            <button className="ghost grow" disabled={busy || !ai.available} onClick={async () => { if (await task('正在断开连接', async sg => { await disconnect(sg); return true; })) toast('密钥已从本机服务清除。'); }}>断开并清除</button>
          </div>
        </section>

        <section className="panel">
          <h3 className="section-label">关于格式</h3>
          <p className="note">讨论准备和理解复盘要求模型返回 JSON。较小的模型偶尔不按格式回答；遇到“没有按要求返回结构化结果”时，重试一次或换一个更强的模型。目前支持 Bearer 认证的文本 Chat Completions 接口。</p>
        </section>
      </div>
      <div className="dock slim"><ErrorBanner /><BusyStrip /></div>
    </div>
  );
}
