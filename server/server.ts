// 思辨场 · 本机服务：托管界面（dist/），并代理用户自带的 AI 接口。密钥只保存在内存里。
import http from 'node:http';
import fs from 'node:fs';
import path from 'node:path';
import crypto from 'node:crypto';
import { fileURLToPath } from 'node:url';
import { ApiError, normalizeConfig, buildRequest, finalize, complete, type ApiConfig } from './ai.ts';
import type { Purpose } from '../shared/types.ts';

interface SessionState { config: ApiConfig | null; verified: boolean; active: AbortController | null; touched: number }

const here = path.dirname(fileURLToPath(import.meta.url));
const TYPES: Record<string, string> = { '.html': 'text/html; charset=utf-8', '.css': 'text/css; charset=utf-8', '.js': 'text/javascript; charset=utf-8', '.json': 'application/json', '.svg': 'image/svg+xml', '.png': 'image/png', '.ico': 'image/x-icon', '.woff2': 'font/woff2', '.webmanifest': 'application/manifest+json' };
const PURPOSES: Purpose[] = ['prepare', 'discuss', 'debate', 'hint', 'explain', 'reflect', 'review'];

export function createServer({ fetchImpl = fetch, timeoutMs = 90000, root = path.join(here, '..', 'dist') }: { fetchImpl?: typeof fetch; timeoutMs?: number; root?: string } = {}) {
  const sessions = new Map<string, SessionState>();
  const json = (res: http.ServerResponse, status: number, data: unknown) => { res.writeHead(status, { 'Content-Type': 'application/json; charset=utf-8', 'Cache-Control': 'no-store' }); res.end(JSON.stringify(data)); };
  async function body(req: http.IncomingMessage): Promise<Record<string, unknown>> {
    const chunks: Buffer[] = []; let size = 0;
    for await (const chunk of req) { size += chunk.length; if (size > 1_000_000) throw new ApiError('请求内容过大。', 413); chunks.push(chunk); }
    try { return JSON.parse(Buffer.concat(chunks).toString('utf8') || '{}'); } catch { throw new ApiError('请求格式错误。'); }
  }

  return http.createServer(async (req, res) => {
    res.setHeader('X-Content-Type-Options', 'nosniff'); res.setHeader('Referrer-Policy', 'no-referrer');
    let url: URL;
    try { url = new URL(req.url ?? '/', 'http://' + req.headers.host); } catch { return json(res, 400, { error: '无效地址' }); }
    if (!['localhost', '127.0.0.1'].includes(url.hostname)) return json(res, 403, { error: '仅支持本机访问。' });

    if (url.pathname.startsWith('/api/')) {
      if (req.headers['x-sibian-client'] !== 'web' || (req.headers.origin && req.headers.origin !== url.origin)) return json(res, 403, { error: '请从本机思辨场页面访问。' });
      let id = req.headers.cookie?.match(/(?:^|;\s*)sibian_session=([a-f0-9]{48})(?:;|$)/)?.[1];
      let state = id ? sessions.get(id) : undefined;
      if (!state) {
        id = crypto.randomBytes(24).toString('hex');
        state = { config: null, verified: false, active: null, touched: Date.now() };
        sessions.set(id, state);
        res.setHeader('Set-Cookie', `sibian_session=${id}; HttpOnly; SameSite=Strict; Path=/`);
      }
      state.touched = Date.now();
      for (const [k, v] of sessions) if (!v.active && Date.now() - v.touched > 8 * 3600000) sessions.delete(k);
      const s = state;
      const publicState = () => ({ configured: !!s.config, verified: s.verified, baseURL: s.config?.endpoint ?? '', model: s.config?.model ?? '' });
      try {
        if (req.method === 'GET' && url.pathname === '/api/config') return json(res, 200, publicState());
        if (req.method === 'POST' && url.pathname === '/api/config') {
          if (s.active) throw new ApiError('请先取消当前请求。', 409);
          s.config = normalizeConfig(await body(req)); s.verified = false;
          return json(res, 200, publicState());
        }
        if (req.method === 'DELETE' && url.pathname === '/api/config') { s.active?.abort(); s.config = null; s.verified = false; return json(res, 200, publicState()); }
        if (req.method === 'POST' && (url.pathname === '/api/test' || url.pathname === '/api/generate')) {
          if (!s.config) throw new ApiError('请先填写并连接 API。', 409);
          if (s.active) throw new ApiError('已有请求正在进行，请等待或取消。', 409);
          const data = await body(req);
          const purpose = url.pathname === '/api/test' ? 'test' : data.purpose as Purpose;
          if (purpose !== 'test' && !PURPOSES.includes(purpose)) throw new ApiError('不支持的 AI 操作。');
          const request = buildRequest(purpose, data.payload);
          if (s.active) throw new ApiError('已有请求正在进行。', 409);
          const config = s.config; if (!config) throw new ApiError('API 已断开连接。', 409);
          s.verified = false;
          const controller = new AbortController(); s.active = controller;
          res.on('close', () => { if (!res.writableEnded) controller.abort(); });
          try {
            const result = await complete(config, request.messages, { fetchImpl, timeoutMs, signal: controller.signal });
            s.verified = true;
            const output = finalize(request, result);
            if (!res.destroyed) json(res, 200, output);
          } finally { if (s.active === controller) s.active = null; }
          return;
        }
        return json(res, 404, { error: '接口不存在。' });
      } catch (e) {
        if (!res.destroyed) json(res, e instanceof ApiError ? e.status : 500, { error: e instanceof ApiError ? e.message : '本机服务未能完成请求。' });
        return;
      }
    }

    if (req.method !== 'GET' && req.method !== 'HEAD') return json(res, 405, { error: '方法不支持' });
    let name: string;
    try { name = decodeURIComponent(url.pathname); } catch { return json(res, 400, { error: '地址无效' }); }
    const file = path.resolve(root, '.' + (name === '/' ? '/index.html' : name));
    if (!file.startsWith(root + path.sep)) return json(res, 403, { error: '禁止访问' });
    fs.readFile(file, (err, data) => {
      if (err) {
        if (name === '/') return json(res, 503, { error: '还没有构建界面。请先运行 npm run build。' });
        return json(res, 404, { error: '文件不存在' });
      }
      res.setHeader('Cache-Control', name.startsWith('/assets/') ? 'public, max-age=31536000, immutable' : 'no-cache');
      res.setHeader('Content-Type', TYPES[path.extname(file)] ?? 'application/octet-stream');
      res.end(req.method === 'HEAD' ? undefined : data);
    });
  });
}

if (process.argv[1] && path.resolve(process.argv[1]) === fileURLToPath(import.meta.url)) {
  const server = createServer();
  server.on('error', (e: NodeJS.ErrnoException) => { console.error(e.code === 'EADDRINUSE' ? '端口 4173 已被占用。请关闭旧版本服务后重新启动。' : '本机服务启动失败。'); process.exitCode = 1; });
  server.listen(4173, '127.0.0.1', () => {
    if (!fs.existsSync(path.join(here, '..', 'dist', 'index.html'))) console.log('提示：还没有构建界面，请先运行 npm run build。');
    console.log('思辨场已启动：http://127.0.0.1:4173\n请在界面的“设置”里填写 API。密钥仅保存在本次服务内存中。按 Ctrl+C 停止服务。');
  });
}
