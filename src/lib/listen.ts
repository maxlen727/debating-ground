// 语音输入：录音 → 16k PCM 帧 → 后端（单人走 /api/stt，房间里走房间连接）→ 讯飞 → 边说边把文字填进输入框。
// 不会自动发送：说完由用户自己看一眼、改一改再发。录音只在内存里转发，不保存。
import { useSyncExternalStore } from 'react';
import { LISTEN, type ListenEvent, type ListenRequest } from '../../shared/speech.ts';
import { getState } from '../store.ts';

export type ListenRoute = 'local' | 'remote' | 'room';
export type Phase = 'starting' | 'listening' | 'finishing';
interface Transport { send(m: ListenRequest): void; close(): void }

let phase: Phase | null = null;
let startedAt = 0;
let transport: Transport | null = null;
let stopMic: (() => void) | null = null;
let queue: string[] = [];
let onText: ((text: string) => void) | null = null;
let onEnd: ((error?: string) => void) | null = null;
let timers: ReturnType<typeof setTimeout>[] = [];
const listeners = new Set<() => void>();
const emit = () => listeners.forEach(l => l());

export const useListening = () => useSyncExternalStore(l => { listeners.add(l); return () => listeners.delete(l); }, () => phase, () => null);
export const listeningPhase = () => phase;
export const listeningSince = () => startedAt;

/* ---------- 麦克风 → 16k 单声道 16 位 PCM ---------- */
// AudioWorklet 拿到原始采样（通常 48k 浮点），这里降采样成 16k 并按 1280 字节一帧切好
const TAP = `class Tap extends AudioWorkletProcessor { process(inputs) { const c = inputs[0] && inputs[0][0]; if (c) this.port.postMessage(c.slice(0)); return true; } } registerProcessor('sibian-tap', Tap);`;

export function downsampler(inputRate: number, onFrame: (frame: Uint8Array) => void) {
  const ratio = inputRate / LISTEN.rate, perFrame = LISTEN.frameBytes / 2;
  let pos = 0, acc = 0, n = 0, out = new Int16Array(perFrame), filled = 0;
  return (samples: Float32Array) => {
    for (const x of samples) {
      // 取平均做简单的低通，再按比例抽取
      acc += x; n++; pos += 1;
      if (pos >= ratio) {
        pos -= ratio;
        const v = Math.max(-1, Math.min(1, acc / n));
        out[filled++] = v < 0 ? v * 0x8000 : v * 0x7fff;
        acc = 0; n = 0;
        if (filled === perFrame) { onFrame(new Uint8Array(out.buffer)); out = new Int16Array(perFrame); filled = 0; }
      }
    }
  };
}
const b64 = (bytes: Uint8Array) => { let s = ''; for (let i = 0; i < bytes.length; i++) s += String.fromCharCode(bytes[i]); return btoa(s); };

async function openMic(onFrame: (b64frame: string) => void): Promise<() => void> {
  if (!navigator.mediaDevices?.getUserMedia) throw new Error('这里不能录音：浏览器只允许在 HTTPS 或本机地址上使用麦克风。');
  let stream: MediaStream;
  try { stream = await navigator.mediaDevices.getUserMedia({ audio: { channelCount: 1, echoCancellation: true, noiseSuppression: true, autoGainControl: true } }); }
  catch (e) {
    const name = (e as Error).name;
    throw new Error(name === 'NotAllowedError' || name === 'SecurityError' ? '没有麦克风权限。请在浏览器或系统设置里允许思辨场使用麦克风。' : name === 'NotFoundError' ? '没有找到麦克风。' : '打不开麦克风。');
  }
  const ctx = new AudioContext();
  const feed = downsampler(ctx.sampleRate, f => onFrame(b64(f)));
  const source = ctx.createMediaStreamSource(stream);
  const mute = ctx.createGain(); mute.gain.value = 0; mute.connect(ctx.destination);   // 接到输出上才会持续处理，但不出声
  let node: AudioNode;
  if (ctx.audioWorklet) {
    const url = URL.createObjectURL(new Blob([TAP], { type: 'application/javascript' }));
    await ctx.audioWorklet.addModule(url); URL.revokeObjectURL(url);
    const w = new AudioWorkletNode(ctx, 'sibian-tap');
    w.port.onmessage = e => feed(e.data as Float32Array);
    node = w;
  } else {
    // 旧浏览器没有 AudioWorklet
    const p = ctx.createScriptProcessor(4096, 1, 1);
    p.onaudioprocess = e => feed(e.inputBuffer.getChannelData(0));
    node = p;
  }
  source.connect(node); node.connect(mute);
  if (ctx.state === 'suspended') await ctx.resume();
  return () => { try { source.disconnect(); node.disconnect(); } catch { /* 已断开 */ } stream.getTracks().forEach(t => t.stop()); void ctx.close(); };
}

/* ---------- 连到后端 ---------- */
function openSocket(route: 'local' | 'remote', handle: (e: ListenEvent) => void): Transport {
  const { url, password } = getState().config.remote;
  const base = route === 'remote' ? url.replace(/\/+$/, '') + '/api/stt' : new URL('/api/stt', location.href).href;
  const ws = new WebSocket(base.replace(/^http/, 'ws'));
  const pending: string[] = [];
  ws.onopen = () => { for (const m of pending.splice(0)) ws.send(m); };
  ws.onmessage = e => { try { handle(JSON.parse(String(e.data))); } catch { /* 忽略 */ } };
  ws.onerror = () => handle({ type: 'error', message: route === 'remote' ? '连不上你的后端的语音输入。请确认后端已更新到最新版本。' : '连不上本机服务的语音输入。' });
  ws.onclose = () => { if (phase) handle({ type: 'error', message: '语音输入的连接断开了。' }); };
  return {
    // 口令只在单人连自己部署的后端时放进第一条消息
    send: m => { const raw = JSON.stringify(m.type === 'start' && route === 'remote' ? { ...m, password } : m); if (ws.readyState === WebSocket.OPEN) ws.send(raw); else pending.push(raw); },
    close: () => { ws.onclose = null; ws.onerror = null; try { ws.close(); } catch { /* 已关闭 */ } }
  };
}

// 房间：录音帧经房间连接转发，结果以 { type: 'heard' } 回来，由 lib/room.ts 交给 heard()
let roomSend: ((m: ListenRequest) => boolean) | null = null;
export const setRoomListenSender = (fn: (m: ListenRequest) => boolean) => { roomSend = fn; };

/* ---------- 开始、结束 ---------- */
export async function startListening(route: ListenRoute, handlers: { text: (t: string) => void; end: (error?: string) => void }) {
  if (phase) return;
  phase = 'starting'; startedAt = Date.now(); queue = []; onText = handlers.text; onEnd = handlers.end; emit();
  try {
    transport = route === 'room'
      ? { send: m => { roomSend?.({ ...m }); }, close: () => {} }
      : openSocket(route, heard);
    transport.send({ type: 'start' });
    stopMic = await openMic(frame => {
      if (phase === 'listening') transport?.send({ type: 'audio', audio: frame });
      else if (phase === 'starting') queue.push(frame);   // 后端还没准备好：先攒着
    });
    if (!phase) { stopMic(); stopMic = null; return; }   // 打开麦克风期间已经出错或取消
    // 讯飞单次最长 60 秒，留一点余量自动结束
    timers.push(setTimeout(() => stopListening(), LISTEN.maxMs - 2000));
  } catch (e) {
    finish((e as Error).message || '无法开始语音输入。');
  }
}

// 说完了：先关麦克风，再等最后的结果
export function stopListening() {
  if (!phase || phase === 'finishing') return;
  stopMic?.(); stopMic = null;
  if (phase === 'starting') return cancelListening();
  phase = 'finishing'; emit();
  transport?.send({ type: 'end' });
  timers.push(setTimeout(() => finish('识别结果没有回来，请再说一次。'), 12_000));
}

export function cancelListening() {
  if (!phase) return;
  transport?.send({ type: 'cancel' });
  finish();
}

// 后端发来的事件（单人的 WebSocket 或房间的 heard 消息）
export function heard(e: ListenEvent) {
  if (!phase) return;
  if (e.type === 'ready') {
    if (phase === 'starting') { phase = 'listening'; emit(); for (const f of queue.splice(0)) transport?.send({ type: 'audio', audio: f }); }
    return;
  }
  if (e.type === 'error') return finish(e.message);
  onText?.(e.text);
  if (e.final) finish();
}

function finish(error?: string) {
  timers.forEach(clearTimeout); timers = [];
  stopMic?.(); stopMic = null;
  transport?.close(); transport = null;
  const end = onEnd;
  phase = null; queue = []; onText = null; onEnd = null;
  emit();
  end?.(error);
}
