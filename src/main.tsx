import { StrictMode } from 'react';
import { createRoot } from 'react-dom/client';
import { App } from './App.tsx';
import { trackOrigins } from './lib/motion.ts';
import { trackLights } from './lib/light.ts';
import './styles.css';

trackOrigins();
trackLights();
createRoot(document.getElementById('root')!).render(<StrictMode><App /></StrictMode>);
