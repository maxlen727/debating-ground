import { useEffect } from 'react';
import { TabBar, Toast } from './components/ui.tsx';
import { refreshStatus } from './lib/api.ts';
import { connect, readInviteFromLocation } from './lib/room.ts';
import { getState, useApp } from './store.ts';
import { Learn } from './screens/Learn.tsx';
import { Prepare } from './screens/Prepare.tsx';
import { Session } from './screens/Session.tsx';
import { Review } from './screens/Review.tsx';
import { CardView, Library } from './screens/Library.tsx';
import { Settings } from './screens/Settings.tsx';
import { Join } from './screens/Join.tsx';

const SCREENS = { learn: Learn, prepare: Prepare, session: Session, review: Review, library: Library, card: CardView, settings: Settings, join: Join };

export function App() {
  const { ui } = useApp();
  useEffect(() => {
    refreshStatus();
    // 打开了邀请链接，或者上次停在一场还没结束的多人讨论里
    readInviteFromLocation();
    const s = getState().session;
    if (s?.room && !s.room.closed && getState().ui.screen !== 'join') connect();
    const onHash = () => readInviteFromLocation();
    window.addEventListener('hashchange', onHash);
    return () => window.removeEventListener('hashchange', onHash);
  }, []);
  const Screen = SCREENS[ui.screen] ?? Learn;
  // 讨论页和准备页占满整屏，不显示底部导航
  const immersive = ui.screen === 'session' || ui.screen === 'prepare' || ui.screen === 'card' || ui.screen === 'join';
  return (
    <div className={'app' + (immersive ? ' immersive' : '')}>
      <Screen />
      {!immersive && <TabBar />}
      <Toast />
    </div>
  );
}
