import { useEffect } from 'react';
import { TabBar, Toast } from './components/ui.tsx';
import { refreshStatus } from './lib/api.ts';
import { useApp } from './store.ts';
import { Learn } from './screens/Learn.tsx';
import { Prepare } from './screens/Prepare.tsx';
import { Session } from './screens/Session.tsx';
import { Review } from './screens/Review.tsx';
import { CardView, Library } from './screens/Library.tsx';
import { Settings } from './screens/Settings.tsx';

const SCREENS = { learn: Learn, prepare: Prepare, session: Session, review: Review, library: Library, card: CardView, settings: Settings };

export function App() {
  const { ui } = useApp();
  useEffect(() => { refreshStatus(); }, []);
  const Screen = SCREENS[ui.screen] ?? Learn;
  // 讨论页和准备页占满整屏，不显示底部导航
  const immersive = ui.screen === 'session' || ui.screen === 'prepare' || ui.screen === 'card';
  return (
    <div className={'app' + (immersive ? ' immersive' : '')}>
      <Screen />
      {!immersive && <TabBar />}
      <Toast />
    </div>
  );
}
