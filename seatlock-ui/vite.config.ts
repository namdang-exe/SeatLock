import { defineConfig } from 'vite'
import react from '@vitejs/plugin-react'

export default defineConfig({
  plugins: [react()],
  server: {
    port: 5173,
    proxy: {
      '/api/v1/auth':     'http://localhost:8081',
      '/api/v1/venues':   'http://localhost:8082',
      '/api/v1/admin':    'http://localhost:8082',
      '/api/v1/holds':    'http://localhost:8083',
      '/api/v1/bookings': 'http://localhost:8083',
    },
  },
})
