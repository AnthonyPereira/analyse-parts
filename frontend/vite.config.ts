import react from '@vitejs/plugin-react'
import { defineConfig } from 'vite'

// https://vite.dev/config/
export default defineConfig({
  plugins: [react()],
  server: {
    // En développement, les appels /api/... sont relayés vers Spring Boot :
    // le navigateur ne voit qu'une seule origine (localhost:5173), donc pas de CORS.
    proxy: {
      '/api': 'http://localhost:8080',
    },
  },
})
