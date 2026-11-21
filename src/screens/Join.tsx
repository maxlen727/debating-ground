import { useState } from 'react';
import { joinRoom } from '../actions.ts';
import { isLanBackend } from '../lib/room.ts';
import { ErrorBanner, Icon, TopBar } from '../components/ui.tsx';
import { go, update, useApp } from '../store.ts';

// 打开邀请链接后的页面：写个名字就能加入，不需要后端访问口令
export function Join() {
  const { ui, config, session } = useApp();
  const [name, setName] = useState(config.name);
  const join = ui.join;
  const leave = () => {
    update(s => {
      s.ui.join = null;
      s.ui.error = '';
    });
    go('learn');
  };
  if (!join)
    return (
      <div className="screen">
        <TopBar title="加入讨论" back="learn" />
      </div>
    );
  const ongoing = session && !session.finished && session.room?.roomId !== join.roomId;
  const host = (() => {
    try {
      return new URL(join.backend).host;
    } catch {
      return join.backend;
    }
  })();
  return (
    <div className="screen join">
      <TopBar title="加入讨论" back={leave} />
      <div className="stack pad">
        <section className="join-card">
          <span className="join-mark" aria-hidden="true">
            <Icon name="people" size={26} />
          </span>
          <h2>同学邀请你一起讨论</h2>
          <p className="muted">和大家轮流发言，不需要密钥或口令。</p>
        </section>
        <label className="field">
          <span>你的名字</span>
          <input
            value={name}
            maxLength={20}
            autoFocus
            placeholder="大家会看到这个名字"
            onChange={e => setName(e.target.value)}
            onKeyDown={e => {
              if (e.key === 'Enter' && !e.nativeEvent.isComposing) joinRoom(name);
            }}
          />
          <small>只在这场讨论里显示。</small>
        </label>
        <p className="fineprint">
          {ui.join && isLanBackend(ui.join.backend)
            ? `局域网房间开在房主的设备上（${host}），内容只在它的内存里。局域网不加密，别在公共 Wi-Fi 上用。`
            : `内容临时存在房主的后端（${host}），结束 1 小时后删除。`}
          你的小结和复盘只保存在这台设备上。
          {ongoing && '加入后，你正在进行的讨论会结束。'}
        </p>
      </div>
      <div className="dock">
        <ErrorBanner />
        <button className="primary block" disabled={!name.trim()} onClick={() => joinRoom(name)}>
          加入讨论
        </button>
      </div>
    </div>
  );
}
