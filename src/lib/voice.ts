import { apiRequest, type Target } from './api.ts';

export async function playAudio(audio: string, mime = 'audio/mpeg') {
  const player = new Audio(`data:${mime};base64,${audio}`);
  await new Promise<void>((resolve, reject) => { player.onended = () => resolve(); player.onerror = () => reject(new Error('audio playback failed')); void player.play().catch(reject); });
}

export async function speakText(text: string, voice: string, target: Target) {
  const chars = Array.from(text.trim());
  for (let i = 0; i < chars.length; i += 1700) {
    const result = await apiRequest<{ audio: string; mime: string }>('/api/tts', { method: 'POST', body: JSON.stringify({ text: chars.slice(i, i + 1700).join(''), voice }) }, target);
    await playAudio(result.audio, result.mime);
  }
}
