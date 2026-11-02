import { StrictMode } from 'react';
import { createRoot } from 'react-dom/client';
import { App } from './App.tsx';
import { trackOrigins } from './lib/motion.ts';
import './styles.css';

trackOrigins();
createRoot(document.getElementById('root')!).render(<StrictMode><App /></StrictMode>);
