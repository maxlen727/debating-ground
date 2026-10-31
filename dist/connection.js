// 思辨场 v0.4 · 与本机服务的连接（用户自带 API）
let aiState = { available: false, configured: false, verified: false, baseURL: '', model: '' }, aiBusy = null, aiError = '';

function aiReal() { return config.aiMode === 'api'; }
function aiBadge() { return aiReal() ? (aiState.configured ? '自带 API · ' + esc(aiState.model) : 'API 模式 · 待连接') : '演示模式 · 本地模板'; }
function aiNotice() {
  return aiBusy
    ? `<div class="notice row between" role="status"><span>${esc(aiBusy.label)} · 请稍候</span><button class="small" id="cancel-ai">取消请求</button></div>`
    : aiError ? `<div class="notice error" role="alert">${esc(aiError)}</div>` : '';
}

function connectionPage() {
  return `<div class="eyebrow">BRING YOUR OWN API</div><h1 class="screen-title">连接你的 AI。</h1><p class="muted">由你选择服务商和模型。讨论角色、思路提示、小结反馈和理解复盘都会使用它。</p>
  <div class="grid"><section class="card"><div class="card-head"><h2>API 连接设置</h2><span class="badge">${aiState.configured ? (aiState.verified ? '测试已通过' : '已配置 · 未验证') : '尚未连接'}</span></div>
  ${!aiState.available ? '<div class="notice">真实 AI 需要本机服务。请解压完整版本，运行 start.cmd（或 npm start），再打开 http://127.0.0.1:4173。单独打开 HTML 只能使用演示模式。</div>' : ''}
  <label for="api-url">接口地址 · Base URL 或完整 Chat Completions 地址</label><input id="api-url" type="url" autocomplete="off" placeholder="https://你的服务商地址/v1" value="${esc(aiState.baseURL)}">
  <p class="note">基础地址会自动补上 /chat/completions；请使用服务商文档提供的地址。</p>
  <label for="api-model" style="margin-top:20px">模型名称</label><input id="api-model" autocomplete="off" placeholder="填写该服务商支持的准确模型 ID" value="${esc(aiState.model)}">
  <label for="api-key" style="margin-top:20px">API Key</label><input id="api-key" type="password" autocomplete="off" spellcheck="false" placeholder="每次更换配置请重新填写密钥">
  <p class="note">密钥只保存在本机服务内存，不写入讨论记录、知识库或浏览器存储。断开连接或停止服务会清除密钥；关闭网页不等于停止服务。</p>
  <div class="notice" style="margin-top:18px">点击“连接并测试”会将密钥发送至你填写的接口，发起一次少量计费的测试。之后的每次讨论会向该服务商发送概念、讨论题、角色背景和讨论记录。</div>
  <div class="row"><button id="connect-api" class="primary" ${!aiState.available ? 'disabled' : ''}>连接并测试 ↗</button><button id="test-api" ${!aiState.configured ? 'disabled' : ''}>重新测试</button><button id="disconnect-api" ${!aiState.available ? 'disabled' : ''}>断开并清除密钥</button></div></section>
  <aside class="stack"><section class="card summary"><h2>AI 来源</h2><label for="ai-mode">本次使用</label><select id="ai-mode"><option value="demo" ${!aiReal() ? 'selected' : ''}>演示模式 · 无需 API</option><option value="api" ${aiReal() ? 'selected' : ''}>真实 AI · 用户自带 API</option></select>
  <p class="note">API 模式请求失败时保留当前进度，不自动切换为模板。已有发言保留原始来源标记。</p><button data-nav="learn" style="width:100%;margin-top:15px">返回开始学习</button></section>
  <section class="card"><h3>结构化输出</h3><p class="note">讨论准备和理解复盘要求模型返回 JSON。较小的模型偶尔不按格式回答，出现“没有按要求返回结构化结果”时重试一次，或换一个更强的模型。</p></section>
  <section class="card"><h3>当前支持</h3><p class="note">支持 Bearer 密钥认证、文本 Chat Completions 协议。模型需支持 system 与 user 消息。原生 Anthropic、Gemini 或仅支持 Responses 的接口尚未适配。</p></section></aside></div>`;
}

async function apiRequest(path, options = {}) {
  const res = await fetch(path, { ...options, credentials: 'same-origin', headers: { 'Content-Type': 'application/json', 'X-Sibian-Client': 'web', ...options.headers } });
  let data; try { data = await res.json(); } catch { throw new Error('本机服务没有返回有效响应，请启动完整版本。'); }
  if (!res.ok) throw new Error(data.error || '请求失败。');
  return data;
}

async function initAI() {
  if (typeof location === 'undefined' || !/^https?:$/.test(location.protocol)) return;
  try { const data = await apiRequest('/api/config'); aiState = { ...aiState, ...data, available: true }; render(); } catch { aiState.available = false; }
}

async function aiTask(label, operation, onSuccess) {
  if (aiBusy) return;
  const controller = new AbortController(); aiBusy = { controller, label }; aiError = ''; render();
  let timedOut = false;
  const timeout = setTimeout(() => { timedOut = true; controller.abort(); }, 100000);
  try {
    const result = await operation(controller.signal);
    if (controller.signal.aborted) throw new Error('已取消');
    aiBusy = null; onSuccess?.(result);
  } catch (e) {
    aiError = controller.signal.aborted ? (timedOut ? '请求超时，当前进度未改变。' : '请求已取消，当前进度未改变。') : e.message || '请求失败，请检查本机服务。';
  } finally { clearTimeout(timeout); aiBusy = null; render(); }
}

function requireAI() {
  if (aiState.configured && aiState.available) return true;
  aiError = '请先连接你自己的 API，或在“AI 连接”里切换到演示模式。'; page = 'connection'; render(); return false;
}

// 调用本机服务的一个 AI 用途；失败时返回 null（错误已显示在页面顶部）
async function callAI(purpose, payload, label) {
  if (!requireAI()) return null;
  let out = null;
  await aiTask(label, signal => apiRequest('/api/generate', { method: 'POST', signal, body: JSON.stringify({ purpose, payload }) }), r => { out = { ...r, source: 'api' }; });
  return out;
}

function bindAI() {
  on('#ai-mode', 'change', el => { config.aiMode = el.value; save(); render(); });
  on('#cancel-ai', 'click', () => aiBusy?.controller.abort());
  on('#connect-api', 'click', () => {
    const input = { baseURL: $('#api-url').value.trim(), model: $('#api-model').value.trim(), apiKey: $('#api-key').value.trim() };
    if (!input.baseURL || !input.model || !input.apiKey) return toast('请填写接口地址、模型名称和 API Key。');
    $('#api-key').value = '';
    aiTask('正在连接并测试', async signal => {
      try {
        const data = await apiRequest('/api/config', { method: 'POST', signal, body: JSON.stringify(input) });
        aiState = { ...aiState, ...data }; config.aiMode = 'api'; save();
        return await apiRequest('/api/test', { method: 'POST', signal, body: '{}' });
      } finally {
        input.apiKey = '';
        try { const status = await apiRequest('/api/config', { signal: AbortSignal.timeout(4000) }); aiState = { ...aiState, ...status }; } catch { }
      }
    }, () => { aiState.verified = true; toast('连接成功，可以开始学习。'); });
  });
  on('#test-api', 'click', () => { aiState.verified = false; return aiTask('正在测试连接', signal => apiRequest('/api/test', { method: 'POST', signal, body: '{}' }), () => { aiState.verified = true; toast('API 测试通过。'); }); });
  on('#disconnect-api', 'click', () => aiTask('正在断开连接', signal => apiRequest('/api/config', { method: 'DELETE', signal }), data => { aiState = { ...aiState, ...data }; config.aiMode = 'demo'; save(); toast('密钥已从本机服务清除。'); }));
  if (aiBusy) document.querySelectorAll('button,input,select,textarea').forEach(el => { if (el.id !== 'cancel-ai') el.disabled = true; });
}

function messageSource(m) {
  if (m.who === 'learner' || m.human) return '真人';
  return m.source === 'api' ? 'AI · ' + esc(m.model) : 'AI · 演示';
}
