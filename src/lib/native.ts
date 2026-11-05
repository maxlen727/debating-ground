// 安卓 App 专用：返回键。只在 App 里按需加载（main.tsx），浏览器和测试不会引入 Capacitor 插件。
import { App } from '@capacitor/app';
import { goBack } from '../actions.ts';

export function initNative() {
  // 返回键：先关面板，再回上一层；已经在最外层才退出 App
  void App.addListener('backButton', () => { if (!goBack()) void App.exitApp(); });
}
