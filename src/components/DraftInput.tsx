// 发言输入框，和它上面的提示卡片、旁边的麦克风按钮；单人和多人讨论共用
import { useEffect, useRef, useState } from 'react';
import { noteTyping, send, stopTyping, toggleListen, listenAvailable } from '../actions.ts';
import { Icon } from './ui.tsx';
import { CaretField } from './SmoothCaret.tsx';
import { Reveal } from './Reveal.tsx';
import { useLive } from '../lib/stream.ts';
import { listeningSince, useListening } from '../lib/listen.ts';
import { typing, update, useApp, type Session as S } from '../store.ts';

export function HintCard() {
  const { ui } = useApp();
  const live = useLive();
  const streaming = live?.kind === 'hint' && !!live.text;
  if (!ui.hint && !streaming) return null;
  return (
    <div className="hint-card">
      <Icon name="bulb" size={18} />
      <p>
        {streaming ? (
          <Reveal id="hint" text={live.text} live />
        ) : (
          <Reveal id="hint" text={ui.hint!} live={false} />
        )}
      </p>
      <button
        className="icon-btn small"
        aria-label="关闭提示"
        onClick={() =>
          update(st => {
            st.ui.hint = null;
          })
        }
      >
        <Icon name="close" size={16} />
      </button>
    </div>
  );
}
export function DraftInput({ s, placeholder }: { s: S; placeholder: string }) {
  const { ui } = useApp();
  const ref = useRef<HTMLTextAreaElement>(null);
  useEffect(() => {
    const t = ref.current;
    if (t) {
      t.style.height = 'auto';
      t.style.height = Math.min(t.scrollHeight, 160) + 'px';
    }
  }, [s.draft]);
  const listening = useListening();
  const mic = listenAvailable();
  useEffect(() => stopTyping, []);
  const submit = () => {
    if (listening) return;
    stopTyping();
    void send(s.draft);
  };
  return (
    <div className="input-row">
      <CaretField fieldRef={ref} className="grow">
        {/* 听写时文字由识别结果填写，先不让手动改，免得被下一次结果覆盖 */}
        <textarea
          id="draft"
          ref={ref}
          rows={1}
          value={s.draft}
          maxLength={s.room ? 2000 : 4000}
          aria-label="你的发言"
          placeholder={listening ? '正在听，说完点一下麦克风' : placeholder}
          readOnly={!!listening}
          onChange={e => {
            typing(st => {
              st.session!.draft = e.target.value;
            });
            noteTyping();
          }}
          onKeyDown={e => {
            if (
              e.key === 'Enter' &&
              !e.shiftKey &&
              !e.nativeEvent.isComposing &&
              window.matchMedia('(pointer: fine)').matches
            ) {
              e.preventDefault();
              submit();
            }
          }}
        />
      </CaretField>
      {mic && <MicButton phase={listening} />}
      <button
        className="send"
        aria-label="发送"
        disabled={!!ui.busy || !!listening || !s.draft.trim()}
        onClick={submit}
      >
        <Icon name="send" />
      </button>
    </div>
  );
}

// 语音输入按钮：点一下开始，再点一下结束；识别出的文字填进输入框，不会自动发送
export function MicButton({ phase }: { phase: ReturnType<typeof useListening> }) {
  const [, tick] = useState(0);
  useEffect(() => {
    if (phase !== 'listening') return;
    const id = setInterval(() => tick(n => n + 1), 500);
    return () => clearInterval(id);
  }, [phase]);
  const secs = phase === 'listening' ? Math.floor((Date.now() - listeningSince()) / 1000) : 0;
  const label =
    phase === 'listening'
      ? `说完了，结束语音输入（已录 ${secs} 秒）`
      : phase === 'finishing'
        ? '正在识别最后一句'
        : phase === 'starting'
          ? '正在打开麦克风'
          : '语音输入';
  return (
    <button
      className={'mic' + (phase ? ' on ' + phase : '')}
      aria-label={label}
      aria-pressed={!!phase}
      disabled={phase === 'finishing'}
      onClick={toggleListen}
    >
      {phase === 'listening' ? <span className="mic-time">{secs}″</span> : <Icon name="mic" />}
    </button>
  );
}
