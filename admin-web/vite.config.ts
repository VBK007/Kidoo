import react from '@vitejs/plugin-react'
import { defineConfig } from 'vite'

/**
 * `/api` is proxied to the Spring app rather than called cross-origin.
 *
 * The backend does allow any origin, so a direct call would work — but going
 * through the proxy means the console runs same-origin in development exactly
 * as it does behind a reverse proxy in production, so there is no CORS
 * behaviour that only exists in one of the two.
 *
 * Point it elsewhere with `KIDO_API=http://host:port npm run dev`.
 */
export default defineConfig({
  plugins: [react()],
  server: {
    port: 5173,
    proxy: {
      '/api': {
        target: process.env.KIDO_API ?? 'http://localhost:8080',
        changeOrigin: true,
      },
    },
  },
})
