import { defineConfig } from 'vite'
import vue from '@vitejs/plugin-vue'

const backendOrigin = process.env.VITE_BACKEND_ORIGIN || process.env.BACKEND_ORIGIN || 'http://127.0.0.1:18083'

export default defineConfig({
  plugins: [vue()],
  server: {
    host: '127.0.0.1',
    port: Number(process.env.FRONTEND_PORT || 5186),
    strictPort: true,
    hmr: false,
    proxy: {
      '/api': backendOrigin,
      '/rules': backendOrigin
    }
  }
})
