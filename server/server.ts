// 思辨场 · 本机服务：托管界面（dist/），并代理用户自带的 AI 接口。密钥只保存在内存里。
import http from 'node:http';
import fs from 'node:fs';
import path from 'node:path';
import crypto from 'node:crypto';
import { fileURLToPath } from 'node:url';
import { synthesizeXfyun } from './tts.ts';
import { listenRelay } from './stt.ts';
import { acceptWebSocket, rejectUpgrade } from './wsserver.ts';
import { LAN_PORT, LanRooms, lanAddress } from './lan.ts';
import { SPEECH_SEGMENT } from '../shared/speech.ts';
import { importLink, type Get } from '../shared/pages.ts';
import { nodeGet } from './fetchpage.ts';
import {
  ApiError,
  normalizeConfig,
  buildRequest,
  finalize,
  complete,
  streamGenerate,
  STREAMABLE,
  type ApiConfig,
} from './ai.ts';
import type { Purpose } from '../shared/types.ts';
import { PURPOSES } from '../shared/purposes.ts';

interface TtsConfig {
  appId: string;
  apiKey: string;
  apiSecret: string;
}
interface SessionState {
  config: ApiConfig | null;
  verified: boolean;
  active: AbortController | null;
  touched: number;
  tts: TtsConfig | null;
  fetching?: boolean; // 正在替这个会话取链接（同一时间只取一个）
}

const here = path.dirname(fileURLToPath(import.meta.url));
const TYPES: Record<string, string> = {
  '.html': 'text/html; charset=utf-8',
  '.css': 'text/css; charset=utf-8',
  '.js': 'text/javascript; charset=utf-8',
  // pdf.js 的后台脚本（#162）；不写类型的话浏览器（nosniff）拒绝执行
  '.mjs': 'text/javascript; charset=utf-8',
  '.json': 'application/json',
  '.svg': 'image/svg+xml',
  '.png': 'image/png',
  '.webp': 'image/webp',
  '.ico': 'image/x-icon',
  '.woff2': 'font/woff2',
  '.webmanifest': 'application/manifest+json',
};

// 托管界面（dist/）里的静态文件：本机服务和局域网房间的入口共用
function serveStatic(req: http.IncomingMessage, res: http.ServerResponse, url: URL, root: string) {
  const fail = (status: number, error: string) => {
    res.writeHead(status, { 'Content-Type': 'application/json; charset=utf-8' });
    res.end(JSON.stringify({ error }));
  };
  if (req.method !== 'GET' && req.method !== 'HEAD') return fail(405, '方法不支持');
  let name: string;
  try {
    name = decodeURIComponent(url.pathname);
  } catch {
    return fail(400, '地址无效');
  }
  const file = path.resolve(root, '.' + (name === '/' ? '/index.html' : name));
  if (!file.startsWith(root + path.sep)) return fail(403, '禁止访问');
  fs.readFile(file, (err, data) => {
    if (err) {
      if (name === '/') return fail(503, '还没有构建界面。请先运行 npm run build。');
      return fail(404, '文件不存在');
    }
    res.setHeader(
      'Cache-Control',
      name.startsWith('/assets/') ? 'public, max-age=31536000, immutable' : 'no-cache',
    );
    res.setHeader('Content-Type', TYPES[path.extname(file)] ?? 'application/octet-stream');
    res.end(req.method === 'HEAD' ? undefined : data);
  });
}

export function createServer({
  fetchImpl = fetch,
  timeoutMs = 90000,
  root = path.join(here, '..', 'dist'),
  lan = null,
  getPage = nodeGet(),
}: {
  fetchImpl?: typeof fetch;
  timeoutMs?: number;
  root?: string;
  // 局域网房间（#148）：打开时房主可以在本机页面上开房间，同学从 origin 加入
  lan?: { rooms: LanRooms; origin: string } | null;
  // 读材料的链接（#157）：测试里换成假的
  getPage?: Get;
} = {}) {
  const sessions = new Map<string, SessionState>();
  const json = (res: http.ServerResponse, status: number, data: unknown) => {
    res.writeHead(status, {
      'Content-Type': 'application/json; charset=utf-8',
      'Cache-Control': 'no-store',
    });
    res.end(JSON.stringify(data));
  };
  async function body(req: http.IncomingMessage): Promise<Record<string, unknown>> {
    const chunks: Buffer[] = [];
    let size = 0;
    for await (const chunk of req as AsyncIterable<Buffer>) {
      size += chunk.length;
      if (size > 1_000_000) throw new ApiError('请求内容过大。', 413);
      chunks.push(chunk);
    }
    let data: unknown;
    try {
      data = JSON.parse(Buffer.concat(chunks).toString('utf8') || '{}');
    } catch {
      throw new ApiError('请求格式错误。');
    }
    if (!data || typeof data !== 'object' || Array.isArray(data))
      throw new ApiError('请求格式错误。');
    return data as Record<string, unknown>;
  }

  const envTts = (): TtsConfig | null =>
    process.env.XFYUN_APP_ID && process.env.XFYUN_API_KEY && process.env.XFYUN_API_SECRET
      ? {
          appId: process.env.XFYUN_APP_ID,
          apiKey: process.env.XFYUN_API_KEY,
          apiSecret: process.env.XFYUN_API_SECRET,
        }
      : null;
  const ttsOf = (s: SessionState) => s.tts ?? envTts();
  const xfyunOf = (s: SessionState) => {
    const t = ttsOf(s);
    return t
      ? { XFYUN_APP_ID: t.appId, XFYUN_API_KEY: t.apiKey, XFYUN_API_SECRET: t.apiSecret }
      : {};
  };
  const sessionId = (req: http.IncomingMessage) =>
    req.headers.cookie?.match(/(?:^|;\s*)sibian_session=([a-f0-9]{48})(?:;|$)/)?.[1];
  const publicState = (s: SessionState) => ({
    configured: !!s.config,
    verified: s.verified,
    baseURL: s.config?.endpoint ?? '',
    model: s.config?.model ?? '',
    ttsConfigured: !!ttsOf(s),
    sttConfigured: !!ttsOf(s),
    lan: lan?.origin ?? '',
  });

  // 认出这个浏览器的会话；没有就新建一个，用 HttpOnly Cookie 记住。顺手清掉 8 小时没用过的会话
  function sessionFor(req: http.IncomingMessage, res: http.ServerResponse): SessionState {
    let id = sessionId(req);
    let state = id ? sessions.get(id) : undefined;
    if (!state) {
      id = crypto.randomBytes(24).toString('hex');
      state = { config: null, verified: false, active: null, touched: Date.now(), tts: null };
      sessions.set(id, state);
      res.setHeader('Set-Cookie', `sibian_session=${id}; HttpOnly; SameSite=Strict; Path=/`);
    }
    state.touched = Date.now();
    for (const [k, v] of sessions)
      if (!v.active && Date.now() - v.touched > 8 * 3600000) sessions.delete(k);
    return state;
  }

  // 一次 AI 调用：同一会话同一时间只有一个，浏览器断开就取消
  async function generate(
    s: SessionState,
    req: http.IncomingMessage,
    res: http.ServerResponse,
    test: boolean,
  ) {
    if (!s.config) throw new ApiError('请先填写并连接 API。', 409);
    if (s.active) throw new ApiError('已有请求正在进行，请等待或取消。', 409);
    const data = await body(req);
    const purpose = test ? 'test' : (data.purpose as Purpose);
    if (purpose !== 'test' && !PURPOSES.includes(purpose)) throw new ApiError('不支持的 AI 操作。');
    const request = buildRequest(purpose, data.payload);
    // 上面读请求体时让出过执行权，另一个请求可能已经先开始了：再查一次
    if (s.active) throw new ApiError('已有请求正在进行。', 409);
    const config = s.config;
    if (!config) throw new ApiError('API 已断开连接。', 409);
    s.verified = false;
    const controller = new AbortController();
    s.active = controller;
    res.on('close', () => {
      if (!res.writableEnded) controller.abort();
    });
    try {
      // 浏览器要求流式、而且这种用途输出的是文字：用 SSE 边生成边发
      if (data.stream === true && purpose !== 'test' && STREAMABLE.includes(purpose)) {
        res.writeHead(200, {
          'Content-Type': 'text/event-stream; charset=utf-8',
          'Cache-Control': 'no-store',
          'X-Accel-Buffering': 'no',
        });
        const ok = await streamGenerate(
          config,
          request,
          { fetchImpl, timeoutMs, signal: controller.signal },
          chunk => {
            if (!res.destroyed) res.write(chunk);
          },
        );
        if (ok) s.verified = true;
        if (!res.destroyed) res.end();
        return;
      }
      const result = await complete(config, request.messages, {
        fetchImpl,
        timeoutMs,
        signal: controller.signal,
      });
      s.verified = true;
      const output = finalize(request, result);
      if (!res.destroyed) json(res, 200, output);
    } finally {
      if (s.active === controller) s.active = null;
    }
  }

  type Route = (
    s: SessionState,
    req: http.IncomingMessage,
    res: http.ServerResponse,
  ) => Promise<void> | void;
  const routes: Record<string, Route> = {
    'GET /api/config': (s, _req, res) => json(res, 200, publicState(s)),
    'POST /api/config': async (s, req, res) => {
      if (s.active) throw new ApiError('请先取消当前请求。', 409);
      s.config = normalizeConfig(await body(req));
      s.verified = false;
      json(res, 200, publicState(s));
    },
    'DELETE /api/config': (s, _req, res) => {
      s.active?.abort();
      s.config = null;
      s.verified = false;
      json(res, 200, publicState(s));
    },
    'POST /api/tts': async (s, req, res) => {
      const data = await body(req);
      if (typeof data.text !== 'string' || Array.from(data.text).length > SPEECH_SEGMENT)
        throw new ApiError('朗读文本无效或太长。');
      if (!ttsOf(s)) throw new ApiError('讯飞语音还没有配置。', 503);
      json(res, 200, await synthesizeXfyun(data.text, data.voice, xfyunOf(s)));
    },
    'POST /api/tts/config': async (s, req, res) => {
      const data = await body(req);
      const read = (key: string) => (typeof data[key] === 'string' ? data[key].trim() : '');
      const appId = read('appId'),
        apiKey = read('apiKey'),
        apiSecret = read('apiSecret');
      // 三项都清空：回到环境变量里的配置（如果有）
      if (!appId && !apiKey && !apiSecret) {
        s.tts = null;
        return json(res, 200, { ttsConfigured: !!envTts(), sttConfigured: !!envTts() });
      }
      if (!appId || !apiKey || !apiSecret)
        throw new ApiError('讯飞语音需要同时填写 App ID、API Key 和 API Secret。');
      s.tts = { appId, apiKey, apiSecret };
      json(res, 200, { ttsConfigured: true, sttConfigured: true });
    },
    // 局域网房间：只有本机页面能开；房间里的 AI 用这个会话连好的 API，Key 不会发给同学
    'POST /api/rooms': async (s, req, res) => {
      if (!lan) throw new ApiError('本机服务没有打开局域网房间。用 npm run server:lan 启动。', 404);
      if (!s.config) throw new ApiError('请先在“设置”里连接 API，房间里的 AI 要用它。', 409);
      const data = await body(req);
      const created = await lan.rooms.create(
        { setup: data.setup, hostName: data.hostName },
        async (purpose, payload, onText) => {
          const config = s.config;
          if (!config) throw new ApiError('房主的 API 已经断开，请房主在“设置”里重新连接。', 409);
          const request = buildRequest(purpose, payload);
          const result = await complete(config, request.messages, { fetchImpl, timeoutMs, onText });
          return finalize(request, result);
        },
      );
      json(res, 200, { ...created, backend: lan.origin });
    },
    // 读材料：取一个链接的正文（#157）。不需要 API；只读、地址经过检查，见 server/fetchpage.ts
    'POST /api/fetch': async (s, req, res) => {
      const data = await body(req);
      if (typeof data.url !== 'string' || !data.url.trim() || data.url.length > 2000)
        throw new ApiError('请填写要读取的链接。');
      if (s.fetching) throw new ApiError('正在读取另一个链接，请稍等。', 409);
      s.fetching = true;
      try {
        json(res, 200, await importLink(data.url, getPage));
      } finally {
        s.fetching = false;
      }
    },
    'POST /api/test': (s, req, res) => generate(s, req, res, true),
    'POST /api/generate': (s, req, res) => generate(s, req, res, false),
  };

  async function handle(req: http.IncomingMessage, res: http.ServerResponse) {
    res.setHeader('X-Content-Type-Options', 'nosniff');
    res.setHeader('Referrer-Policy', 'no-referrer');
    let url: URL;
    try {
      url = new URL(req.url ?? '/', 'http://' + req.headers.host);
    } catch {
      return json(res, 400, { error: '无效地址' });
    }
    if (!['localhost', '127.0.0.1'].includes(url.hostname))
      return json(res, 403, { error: '仅支持本机访问。' });

    if (url.pathname.startsWith('/api/')) {
      if (
        req.headers['x-sibian-client'] !== 'web' ||
        (req.headers.origin && req.headers.origin !== url.origin)
      )
        return json(res, 403, { error: '请从本机思辨场页面访问。' });
      const s = sessionFor(req, res);
      const route = routes[`${req.method} ${url.pathname}`];
      try {
        if (!route) return json(res, 404, { error: '接口不存在。' });
        await route(s, req, res);
      } catch (e) {
        if (!res.destroyed)
          json(res, e instanceof ApiError ? e.status : 500, {
            error: e instanceof ApiError ? e.message : '本机服务未能完成请求。',
          });
      }
      return;
    }

    serveStatic(req, res, url, root);
  }
  // 请求处理是异步的：兜底接住没预料到的异常，回一个 500，而不是留下没人处理的 Promise
  const server = http.createServer((req, res) => {
    handle(req, res).catch(() => {
      if (!res.headersSent) json(res, 500, { error: '本机服务未能完成请求。' });
      else res.destroy();
    });
  });

  // 语音输入：浏览器开 WebSocket 时会带上 Cookie 和 Origin，凭这两样认人（和普通请求一样只认本机页面）
  server.on('upgrade', (req: http.IncomingMessage, socket: import('node:stream').Duplex) => {
    let url: URL;
    try {
      url = new URL(req.url ?? '/', 'http://' + req.headers.host);
    } catch {
      return rejectUpgrade(socket, 400, 'Bad Request');
    }
    if (url.pathname !== '/api/stt' || !['localhost', '127.0.0.1'].includes(url.hostname))
      return rejectUpgrade(socket, 404, 'Not Found');
    if (req.headers.origin !== url.origin) return rejectUpgrade(socket, 403, 'Forbidden');
    const id = sessionId(req);
    const state = id ? sessions.get(id) : undefined;
    if (!state) return rejectUpgrade(socket, 401, 'Unauthorized');
    const ws = acceptWebSocket(req, socket);
    if (!ws) return;
    state.touched = Date.now();
    const relay = listenRelay(
      xfyunOf(state),
      e => ws.send(JSON.stringify(e)),
      () => ws.close(),
    );
    ws.onMessage(text => {
      void relay.handle(text);
    });
    ws.onClose(() => relay.close());
  });
  return server;
}

/**
 * 局域网房间的入口（#148）：开在 0.0.0.0 上，同一个 Wi-Fi 里的同学用浏览器打开它加入房间。
 * 它只给界面文件和房间的 WebSocket，不提供 /api/generate、设置这些接口：API Key 和本机的会话都碰不到。
 */
export function createLanServer({
  rooms,
  root = path.join(here, '..', 'dist'),
}: {
  rooms: LanRooms;
  root?: string;
}) {
  const server = http.createServer((req, res) => {
    res.setHeader('X-Content-Type-Options', 'nosniff');
    res.setHeader('Referrer-Policy', 'no-referrer');
    const url = new URL(req.url ?? '/', 'http://lan');
    if (url.pathname.startsWith('/api/')) {
      res.writeHead(404, { 'Content-Type': 'application/json; charset=utf-8' });
      return res.end(JSON.stringify({ error: '局域网入口只用来加入房间。' }));
    }
    serveStatic(req, res, url, root);
  });
  server.on('upgrade', (req: http.IncomingMessage, socket: import('node:stream').Duplex) => {
    const m = (req.url ?? '').match(/^\/api\/rooms\/([^/?]+)\/ws$/);
    if (!m) return rejectUpgrade(socket, 404, 'Not Found');
    rooms.upgrade(req, socket, m[1]);
  });
  return server;
}

if (process.argv[1] && path.resolve(process.argv[1]) === fileURLToPath(import.meta.url)) {
  // npm run server:lan：同时打开局域网房间的入口（#148）
  const lanIp = process.argv.includes('--lan') ? lanAddress() : null;
  if (process.argv.includes('--lan') && !lanIp)
    console.log('没找到这台电脑的局域网地址（连上 Wi-Fi 或有线网了吗？），局域网房间没有打开。');
  const rooms = lanIp ? new LanRooms() : null;
  const lan = rooms && lanIp ? { rooms, origin: `http://${lanIp}:${LAN_PORT}` } : null;
  const server = createServer({ lan });
  if (lan) {
    const entry = createLanServer({ rooms: lan.rooms });
    entry.on('error', () => console.error(`端口 ${LAN_PORT} 已被占用，局域网房间没有打开。`));
    entry.listen(LAN_PORT, '0.0.0.0', () =>
      console.log(
        `局域网房间已打开：同一个 Wi-Fi 里的同学打开你分享的邀请链接（${lan.origin}）就能加入。\n` +
          '局域网里走的是不加密的 HTTP：只在信得过的网络里用（家里、宿舍、教室），别在公共 Wi-Fi 上开。',
      ),
    );
  }
  server.on('error', (e: NodeJS.ErrnoException) => {
    console.error(
      e.code === 'EADDRINUSE'
        ? '端口 4173 已被占用。请关闭旧版本服务后重新启动。'
        : '本机服务启动失败。',
    );
    process.exitCode = 1;
  });
  server.listen(4173, '127.0.0.1', () => {
    if (!fs.existsSync(path.join(here, '..', 'dist', 'index.html')))
      console.log('提示：还没有构建界面，请先运行 npm run build。');
    console.log(
      '思辨场已启动：http://127.0.0.1:4173\n请在界面的“设置”里填写 API。密钥仅保存在本次服务内存中。按 Ctrl+C 停止服务。',
    );
  });
}
