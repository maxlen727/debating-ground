// 朗读播放：同一时间只播一条发言，再点同一条就停。
// 音频按段到达（房间里由后端一段段推过来，单人时由这里一段段请求），收到第一段就开始播，按顺序接着播。
// 复用同一个 <audio>：手机上第一次播放要在点击里开始，之后换 src 接着播才不会被拦。
import { useSyncExternalStore } from 'react';
import { splitForSpeech } from '../../shared/speech.ts';
import { apiRequest, type Target } from './api.ts';
import { toast } from '../store.ts';

let playing: string | null = null;
let buffer = new Map<number, string>();
let next = 0, lastSeq = -1, waitTimer: ReturnType<typeof setTimeout> | undefined;
let player: HTMLAudioElement | null = null;
const listeners = new Set<() => void>();
const emit = () => listeners.forEach(l => l());

export const usePlaying = () => useSyncExternalStore(l => { listeners.add(l); return () => listeners.delete(l); }, () => playing, () => null);
export const playingId = () => playing;

export function stopVoice() {
  clearTimeout(waitTimer);
  player?.pause();
  playing = null; buffer = new Map(); next = 0; lastSeq = -1;
  emit();
}

// 在点击里调用：先停掉正在播的，再开始等这一条的音频
export function startVoice(id: string) {
  stopVoice();
  player ??= typeof Audio === 'function' ? new Audio() : null;
  playing = id;
  waitTimer = setTimeout(() => { if (playing === id && !buffer.size && next === 0) { toast('朗读没有响应，请稍后再试。'); stopVoice(); } }, 30_000);
  emit();
}

// 收到一段音频；不是正在等的那一条就丢掉（用户已经停了或换了一条）
export function pushAudio(id: string, seq: number, last: boolean, audio: string, mime = 'audio/mpeg') {
  if (id !== playing || seq < next) return;
  clearTimeout(waitTimer);
  buffer.set(seq, `data:${mime};base64,${audio}`);
  if (last) lastSeq = seq;
  pump();
}

// 请求失败：还没开始播就整条放弃；播到一半就播完已经收到的部分为止
export function voiceFailed() {
  if (!playing) return;
  const started = !!player && !player.paused;
  if (!started && !buffer.size) return stopVoice();
  lastSeq = Math.max(next - (started ? 0 : 1), ...buffer.keys());
  pump();
}

function pump() {
  if (!player || !playing || !player.paused && !player.ended && player.src) return;
  const src = buffer.get(next);
  if (!src) { if (lastSeq >= 0 && next > lastSeq) stopVoice(); return; }
  buffer.delete(next);
  const id = playing;
  player.src = src;
  player.onended = () => { if (playing === id) { next++; pump(); } };
  player.onerror = () => { if (playing === id) { toast('这段音频播放失败，请再点一次朗读。'); stopVoice(); } };
  player.play().catch(() => { if (playing === id) { toast('浏览器拦住了播放，请再点一次朗读。'); stopVoice(); } });
}

// 单人讨论：分段请求后端合成；播当前段时就去取下一段
export async function speakText(id: string, text: string, voice: string, target: Target) {
  const parts = splitForSpeech(text);
  if (!parts.length) return;
  startVoice(id);
  try {
    for (const [seq, part] of parts.entries()) {
      if (playing !== id) return;
      const r = await apiRequest<{ audio: string; mime: string }>('/api/tts', { method: 'POST', body: JSON.stringify({ text: part, voice }) }, target);
      pushAudio(id, seq, seq === parts.length - 1, r.audio, r.mime);
    }
  } catch (e) {
    if (playing !== id) return;
    toast((e as Error).message || '朗读失败，请稍后再试。');
    voiceFailed();
  }
}
