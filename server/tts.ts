import { ApiError } from './ai.ts';

const HOST = 'tts-api.xfyun.cn';
const ENDPOINT = `wss://${HOST}/v2/tts`;

function base64(bytes: Uint8Array): string {
  let s = '';
  for (let i = 0; i < bytes.length; i += 0x8000) s += String.fromCharCode(...bytes.subarray(i, i + 0x8000));
  return btoa(s);
}

function decodeBase64(text: string): Uint8Array {
  const binary = atob(text);
  const bytes = new Uint8Array(binary.length);
  for (let i = 0; i < binary.length; i++) bytes[i] = binary.charCodeAt(i);
  return bytes;
}

async function signature(apiKey: string, apiSecret: string, date: string): Promise<string> {
  const source = `host: ${HOST}\ndate: ${date}\nGET /v2/tts HTTP/1.1`;
  const key = await crypto.subtle.importKey('raw', new TextEncoder().encode(apiSecret), { name: 'HMAC', hash: 'SHA-256' }, false, ['sign']);
  const mac = await crypto.subtle.sign('HMAC', key, new TextEncoder().encode(source));
  const sig = base64(new Uint8Array(mac));
  return base64(new TextEncoder().encode(`api_key="${apiKey}", algorithm="hmac-sha256", headers="host date request-line", signature="${sig}"`));
}

export async function synthesizeXfyun(text: string, voice: string, env: { XFYUN_APP_ID?: string; XFYUN_API_KEY?: string; XFYUN_API_SECRET?: string }, timeoutMs = 20000): Promise<{ audio: string; mime: 'audio/mpeg' }> {
  const { XFYUN_APP_ID: appId, XFYUN_API_KEY: apiKey, XFYUN_API_SECRET: apiSecret } = env;
  if (!appId || !apiKey || !apiSecret) throw new ApiError('讯飞语音还没有配置。', 503);
  const clean = text.trim();
  if (!clean || new TextEncoder().encode(clean).length > 7900) throw new ApiError('朗读文本为空或超过讯飞单次长度限制。', 400);
  const safeVoice = /^[A-Za-z0-9_-]{1,64}$/.test(voice) ? voice : 'x4_xiaoyan';
  const date = new Date().toUTCString();
  const auth = await signature(apiKey, apiSecret, date);
  const url = new URL(ENDPOINT);
  url.searchParams.set('authorization', auth);
  url.searchParams.set('date', date);
  url.searchParams.set('host', HOST);
  return new Promise((resolve, reject) => {
    let ws: WebSocket;
    let settled = false;
    // 每个分片的 base64 是独立编码的（可能带补位符），必须先解码再拼接，否则拼出的不是合法 MP3
    const chunks: Uint8Array[] = [];
    let total = 0;
    const timer = setTimeout(() => finish(new Error('讯飞语音请求超时。')), timeoutMs);
    const finish = (error?: Error) => {
      if (settled) return;
      settled = true; clearTimeout(timer);
      try { ws?.close(1000, 'done'); } catch { /* 已关闭 */ }
      if (error) reject(error);
      else if (!total) reject(new Error('讯飞没有返回音频。'));
      else {
        const merged = new Uint8Array(total);
        let offset = 0;
        for (const chunk of chunks) { merged.set(chunk, offset); offset += chunk.length; }
        resolve({ audio: base64(merged), mime: 'audio/mpeg' });
      }
    };
    try { ws = new WebSocket(url.toString()); }
    catch { finish(new Error('无法连接讯飞语音服务。')); return; }
    ws.onopen = () => {
      const request = {
        common: { app_id: appId },
        business: { aue: 'lame', sfl: 1, auf: 'audio/L16;rate=16000', vcn: safeVoice, speed: 50, volume: 50, pitch: 50, tte: 'UTF8' },
        data: { status: 2, text: base64(new TextEncoder().encode(clean)) }
      };
      ws.send(JSON.stringify(request));
    };
    ws.onmessage = event => {
      try {
        const message = JSON.parse(String(event.data)) as { code?: number; message?: string; data?: { audio?: string; status?: number } };
        if (message.code !== 0) return finish(new Error(`讯飞语音错误 ${message.code ?? 'unknown'}：${message.message ?? '请求失败'}`));
        if (message.data?.audio) { const chunk = decodeBase64(message.data.audio); if (chunk.length) { chunks.push(chunk); total += chunk.length; } }
        if (message.data?.status === 2) finish();
      } catch { finish(new Error('讯飞返回了无法识别的数据。')); }
    };
    ws.onerror = () => finish(new Error('连接讯飞语音服务失败。'));
    ws.onclose = event => { if (!settled && event.code !== 1000) finish(new Error('讯飞语音连接中断。')); };
  });
}
