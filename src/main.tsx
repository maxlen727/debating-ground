import { StrictMode } from 'react';
import { createRoot } from 'react-dom/client';
import { App } from './App.tsx';
import { trackOrigins } from './lib/motion.ts';
import { trackLights } from './lib/light.ts';
import { isNativeApp } from './lib/platform.ts';
import './styles.css';

trackOrigins();
trackLights();
// 安卓 App：接管返回键（浏览器里不加载这段）
if (isNativeApp()) void import('./lib/native.ts').then(m => m.initNative());
createRoot(document.getElementById('root')!).render(<StrictMode><App /></StrictMode>);
