import react from '@vitejs/plugin-react'
import { defineConfig } from 'vite'

// 前后端分离：开发期所有 /api 请求由 Vite 转发到 admin（Spring Boot）。
// 后端不在 8080 时可用 VITE_API_TARGET 覆盖，例如：
//   VITE_API_TARGET=http://localhost:18081 pnpm dev
const apiTarget = process.env.VITE_API_TARGET || 'http://localhost:8080'

// https://vite.dev/config/
export default defineConfig({
  plugins: [react()],
  server: {
    port: 5173,
    proxy: {
      '/api': {
        target: apiTarget,
        changeOrigin: true,
      },
    },
  },
})
