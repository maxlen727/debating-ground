// 讯飞朗读：签名、分片拼接、错误处理，以及前后端共用的分段规则
import { test } from 'node:test';
import assert from 'node:assert/strict';
import { createHmac } from 'node:crypto';
import { synthesizeXfyun } from '../server/tts.ts';
import { cleanVoice, splitForSpeech, SPEECH_MAX } from '../shared/speech.ts';

const env = { XFYUN_APP_ID: 'app', XFYUN_API_KEY: 'key-123', XFYUN_API_SECRET: 'secret-456' };
type Frame = { code: number; message?: string; data?: { audio?: string; status?: number } };

// 假的讯飞服务：记下连接地址和请求，按脚本回复
function fakeSocket(script: (req: Record<string, unknown>) => (Frame | 'close')[]) {
  const seen: { url: string; request?: Record<string, unknown> } = { url: '' };
  class Fake {
    onopen: (() => void) | null = null; onmessage: ((e: { data: string }) => void) | null = null;
    onerror: (() => void) | null = null; onclose: ((e: { code: number }) => void) | null = null;
    closed = false;
    constructor(url: string) { seen.url = url; setTimeout(() => this.onopen?.(), 0); }
    send(raw: string) {
      seen.request = JSON.parse(raw);
      for (const f of script(seen.request!)) setTimeout(() => f === 'close' ? this.onclose?.({ code: 1006 }) : this.onmessage?.({ data: JSON.stringify(f) }), 0);
    }
    close() { this.closed = true; }
  }
  return { WebSocketImpl: Fake as unknown as typeof WebSocket, seen };
}
const b64 = (bytes: number[]) => Buffer.from(bytes).toString('base64');

test('signs the request like iFlytek expects and decodes each chunk before joining', async () => {
  // 两个分片各自带补位符：直接拼 base64 会得到坏的 MP3
  const { WebSocketImpl, seen } = fakeSocket(() => [
    { code: 0, data: { audio: b64([1, 2, 3, 4]), status: 1 } },
    { code: 0, data: { audio: b64([5]), status: 2 } }
  ]);
  const date = new Date('2026-11-01T08:00:00Z');
  const r = await synthesizeXfyun('你好。', 'x4_yezi', env, { WebSocketImpl, now: () => date });
  assert.deepEqual([...Buffer.from(r.audio, 'base64')], [1, 2, 3, 4, 5]);
  assert.equal(r.mime, 'audio/mpeg');

  const url = new URL(seen.url);
  assert.equal(url.host, 'tts-api.xfyun.cn');
  assert.equal(url.searchParams.get('date'), date.toUTCString());
  const auth = Buffer.from(url.searchParams.get('authorization')!, 'base64').toString();
  const sig = createHmac('sha256', env.XFYUN_API_SECRET).update(`host: tts-api.xfyun.cn\ndate: ${date.toUTCString()}\nGET /v2/tts HTTP/1.1`).digest('base64');
  assert.equal(auth, `api_key="key-123", algorithm="hmac-sha256", headers="host date request-line", signature="${sig}"`);
  assert.ok(!seen.url.includes(env.XFYUN_API_SECRET), 'the secret itself never leaves the backend');

  const req = seen.request as { common: { app_id: string }; business: { vcn: string; aue: string }; data: { text: string; status: number } };
  assert.equal(req.common.app_id, 'app');
  assert.equal(req.business.vcn, 'x4_yezi');
  assert.equal(Buffer.from(req.data.text, 'base64').toString(), '你好。');
});

test('errors are explained without leaking keys', async () => {
  await assert.rejects(synthesizeXfyun('你好', '', {}), /还没有配置/);
  await assert.rejects(synthesizeXfyun('   ', '', env), /没有可以朗读/);
  const bad = fakeSocket(() => [{ code: 11200, message: 'licc limit' }]);
  await assert.rejects(synthesizeXfyun('你好', '', env, { WebSocketImpl: bad.WebSocketImpl }), (e: Error) => /11200/.test(e.message) && !e.message.includes('secret-456') && !e.message.includes('key-123'));
  const cut = fakeSocket(() => [{ code: 0, data: { audio: b64([1]), status: 1 } }, 'close']);
  await assert.rejects(synthesizeXfyun('你好', '', env, { WebSocketImpl: cut.WebSocketImpl }), /连接中断/);
  const silent = fakeSocket(() => []);
  await assert.rejects(synthesizeXfyun('你好', '', env, { WebSocketImpl: silent.WebSocketImpl, timeoutMs: 20 }), /超时/);
  // 不合法的音色换成默认的
  const v = fakeSocket(() => [{ code: 0, data: { audio: b64([1]), status: 2 } }]);
  await synthesizeXfyun('你好', 'x"; drop', env, { WebSocketImpl: v.WebSocketImpl });
  assert.equal((v.seen.request as { business: { vcn: string } }).business.vcn, 'x4_xiaoyan');
});

test('speech is split at sentence ends into short segments', () => {
  const text = '机会成本是放弃的选项里价值最高的那个。'.repeat(40);
  const parts = splitForSpeech(text);
  assert.ok(parts.length > 1);
  assert.ok(parts.every(p => Array.from(p).length <= 300));
  assert.ok(parts.slice(0, -1).every(p => p.endsWith('。')), 'breaks at sentence ends');
  assert.equal(parts.join(''), text);
  assert.deepEqual(splitForSpeech('  短句  '), ['短句']);
  assert.deepEqual(splitForSpeech(''), []);
  assert.ok(splitForSpeech('啊'.repeat(9000)).join('').length <= SPEECH_MAX);
  assert.equal(cleanVoice('x4_yezi'), 'x4_yezi');
  assert.equal(cleanVoice(42), 'x4_xiaoyan');
});
