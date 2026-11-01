import { defineConfig } from 'vite';
import react from '@vitejs/plugin-react';

// 开发时：npm run server 启动本机服务（4173），npm run dev 启动界面（5173），/api 请求转发给本机服务
export default defineConfig({
  plugins: [react()],
  base: './',
  server: {
    host: '127.0.0.1',
    proxy: {
      // 语音输入是 WebSocket，单独转发；要写在 /api 前面
      '/api/stt': {
        target: 'ws://127.0.0.1:4173',
        ws: true,
        changeOrigin: true,
        configure: proxy => proxy.on('proxyReqWs', req => req.setHeader('origin', 'http://127.0.0.1:4173'))
      },
      '/api': {
        target: 'http://127.0.0.1:4173',
        changeOrigin: true,
        configure: proxy => proxy.on('proxyReq', req => req.setHeader('origin', 'http://127.0.0.1:4173'))
      }
    }
  },
  build: { outDir: 'dist', target: 'es2020' }
});
