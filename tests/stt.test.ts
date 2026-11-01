// 语音输入：讯飞语音听写的会话、单人连接的处理顺序、前端的降采样
import { test } from 'node:test';
import assert from 'node:assert/strict';
import { Listening, listenRelay } from '../server/stt.ts';
import { downsampler } from '../src/lib/listen.ts';
import { LISTEN, type ListenEvent } from '../shared/speech.ts';

const env = { XFYUN_APP_ID: 'app', XFYUN_API_KEY: 'key-1', XFYUN_API_SECRET: 'secret-1' };
type Sent = { common?: { app_id: string }; business?: Record<string, unknown>; data: { status: number; audio: string; format: string; encoding: string } };

// 假的讯飞听写服务：记下收到的帧；reply 按需推送结果
function fakeIat() {
  const sent: Sent[] = [];
  let socket: { onmessage: ((e: { data: string }) => void) | null; onclose: (() => void) | null } | null = null;
  let url = '';
  class Fake {
    onopen: (() => void) | null = null; onmessage: ((e: { data: string }) => void) | null = null;
    onerror: (() => void) | null = null; onclose: (() => void) | null = null; closed = false;
    constructor(u: string) { url = u; socket = this; setTimeout(() => this.onopen?.(), 0); }
    send(raw: string) { sent.push(JSON.parse(raw)); }
    close() { this.closed = true; }
  }
  const reply = (sn: number, words: string[], extra: Record<string, unknown> = {}, status = 1) =>
    socket!.onmessage?.({ data: JSON.stringify({ code: 0, data: { status, result: { sn, ws: words.map(w => ({ cw: [{ w }] })), ...extra } } }) });
  return { WebSocketImpl: Fake as unknown as typeof WebSocket, sent, reply, raw: (m: unknown) => socket!.onmessage?.({ data: JSON.stringify(m) }), drop: () => socket!.onclose?.(), get url() { return url; } };
}
const tick = () => new Promise(r => setTimeout(r, 5));
const frame = Buffer.alloc(LISTEN.frameBytes, 1).toString('base64');

test('frames follow the iFlytek protocol and partial results are corrected in place', async () => {
  const iat = fakeIat(), events: ListenEvent[] = [];
  const s = new Listening(env, e => events.push(e), { WebSocketImpl: iat.WebSocketImpl });
  await s.start();
  s.audio(frame); s.audio(frame);
  await tick();
  assert.equal(new URL(iat.url).host, 'iat-api.xfyun.cn');
  assert.ok(new URL(iat.url).searchParams.get('authorization'));
  assert.equal(iat.sent[0].data.status, 0);
  assert.equal(iat.sent[0].common!.app_id, 'app');
  assert.deepEqual(iat.sent[0].business, { language: 'zh_cn', domain: 'iat', accent: 'mandarin', dwa: 'wpgs', ptt: 1, eos: 3000 });
  assert.equal(iat.sent[0].data.format, 'audio/L16;rate=16000');
  assert.equal(iat.sent[0].data.encoding, 'raw');
  assert.equal(iat.sent[1].data.status, 1);
  assert.equal(iat.sent[1].common, undefined, 'only the first frame carries the app id');

  // 动态修正：第 2 段替换第 1 段
  iat.reply(1, ['机会', '成功'], { pgs: 'apd' });
  iat.reply(2, ['机会成本'], { pgs: 'rpl', rg: [1, 1] });
  iat.reply(3, ['就是代价', '。'], { pgs: 'apd' });
  assert.deepEqual(events.filter(e => e.type === 'text').map(e => (e as { text: string }).text), ['机会成功', '机会成本', '机会成本就是代价。']);
  s.end();
  assert.equal(iat.sent.at(-1)!.data.status, 2, 'the last frame says we are done');
  iat.reply(4, [], {}, 2);
  assert.deepEqual(events.at(-1), { type: 'text', text: '机会成本就是代价。', final: true });
  assert.equal(s.finished, true);
});

test('errors are reported once and never contain keys; silly input is rejected', async () => {
  const iat = fakeIat(), events: ListenEvent[] = [];
  const s = new Listening(env, e => events.push(e), { WebSocketImpl: iat.WebSocketImpl });
  await s.start(); await tick();
  iat.raw({ code: 11200, message: 'licc limit' });
  iat.drop();
  assert.equal(events.length, 1);
  assert.match((events[0] as { message: string }).message, /11200/);
  assert.ok(!JSON.stringify(events).includes('secret-1') && !JSON.stringify(events).includes('key-1'));

  const bad: ListenEvent[] = [];
  const b = new Listening(env, e => bad.push(e), { WebSocketImpl: fakeIat().WebSocketImpl });
  await b.start();
  b.audio('<script>');
  assert.match((bad[0] as { message: string }).message, /音频格式/);

  // 一句没说就结束：直接给空结果，不再等讯飞
  const empty: ListenEvent[] = [];
  const e = new Listening(env, x => empty.push(x), { WebSocketImpl: fakeIat().WebSocketImpl });
  await e.start(); e.end();
  assert.deepEqual(empty, [{ type: 'text', text: '', final: true }]);

  await assert.rejects(new Listening({}, () => {}).start(), /还没有配置/);
});

test('a session stops taking audio after 60 seconds worth of PCM', async () => {
  const iat = fakeIat();
  const s = new Listening(env, () => {}, { WebSocketImpl: iat.WebSocketImpl });
  await s.start();
  const frames = LISTEN.maxBytes / LISTEN.frameBytes;
  for (let i = 0; i < frames + 20; i++) s.audio(frame);
  await tick();
  assert.equal(iat.sent.filter(f => f.data.status !== 2).length, frames);
  assert.equal(iat.sent.at(-1)!.data.status, 2, 'ended automatically');
});

test('single-player relay: password first, then start, audio, end', async () => {
  const iat = fakeIat();
  const out: ListenEvent[] = []; let closed = 0;
  const relay = listenRelay(env, e => out.push(e), () => closed++, async p => p === 'pw', { WebSocketImpl: iat.WebSocketImpl });
  await relay.handle(JSON.stringify({ type: 'audio', audio: frame }));
  assert.match((out.at(-1) as { message: string }).message, /请先开始/);
  assert.equal(closed, 1, 'a protocol error closes the connection');

  const out2: ListenEvent[] = [];
  const wrong = listenRelay(env, e => out2.push(e), () => {}, async p => p === 'pw', { WebSocketImpl: iat.WebSocketImpl });
  await wrong.handle(JSON.stringify({ type: 'start', password: 'nope' }));
  assert.deepEqual(out2, [{ type: 'error', message: '访问口令不正确。' }]);

  const out3: ListenEvent[] = []; let closed3 = 0;
  const ok = listenRelay(env, e => out3.push(e), () => closed3++, async p => p === 'pw', { WebSocketImpl: iat.WebSocketImpl });
  await ok.handle(JSON.stringify({ type: 'start', password: 'pw' }));
  assert.deepEqual(out3, [{ type: 'ready' }]);
  await ok.handle(JSON.stringify({ type: 'audio', audio: frame }));
  await ok.handle(JSON.stringify({ type: 'end' }));
  await tick();
  iat.reply(1, ['你好'], {}, 2);
  assert.deepEqual(out3.at(-1), { type: 'text', text: '你好', final: true });
  assert.equal(closed3, 1, 'closes after the final result');

  const out4: ListenEvent[] = [];
  const none = listenRelay({}, e => out4.push(e), () => {});
  await none.handle(JSON.stringify({ type: 'start' }));
  assert.match((out4[0] as { message: string }).message, /还没有配置讯飞/);
  [relay, wrong, ok, none].forEach(r => r.close());
});

test('the microphone is turned into 16 kHz 16-bit frames of 40 ms', () => {
  const frames: Uint8Array[] = [];
  const feed = downsampler(48000, f => frames.push(f));
  feed(new Float32Array(1920).fill(0.5));    // 48k 下 40ms
  assert.equal(frames.length, 1);
  assert.equal(frames[0].length, LISTEN.frameBytes);
  const pcm = new Int16Array(frames[0].buffer);
  assert.ok(Math.abs(pcm[100] - 0.5 * 0x7fff) < 2);
  feed(new Float32Array(960).fill(-2));        // 超出范围的采样被截到 -1
  feed(new Float32Array(960).fill(-2));
  assert.equal(new Int16Array(frames[1].buffer)[0], -0x8000);
  // 44.1k 这种不能整除的采样率也能切出完整的帧
  const odd: Uint8Array[] = [];
  const f2 = downsampler(44100, f => odd.push(f));
  f2(new Float32Array(44100));
  assert.ok(odd.length >= 24 && odd.length <= 25, `about one second of frames, got ${odd.length}`);
});
