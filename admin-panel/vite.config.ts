import { defineConfig } from 'vitest/config'
import react from '@vitejs/plugin-react'

// The SPA talks to `core` over HTTP only (no shared build). In dev, `/api` is
// proxied to a locally-running `core` so the fetch wrapper can use same-origin
// relative URLs in every environment. Both the dev-server port and the backend
// proxy target are env-overridable (VITE_PORT / VITE_API_TARGET) so the dev
// ports can avoid whatever's reserved on the host.
const devPort = Number(process.env.VITE_PORT) || 5173
const apiTarget = process.env.VITE_API_TARGET || 'http://localhost:8090'

export default defineConfig({
  plugins: [react()],
  server: {
    port: devPort,
    proxy: {
      '/api': { target: apiTarget, changeOrigin: true },
    },
  },
  test: {
    globals: true,
    environment: 'jsdom',
    setupFiles: './src/test/setup.ts',
    css: false,
  },
})
