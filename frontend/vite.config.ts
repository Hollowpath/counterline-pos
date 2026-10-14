/// <reference types="vitest/config" />
import { defineConfig } from 'vite'
import tailwindcss from '@tailwindcss/vite'
import react from '@vitejs/plugin-react'

// https://vite.dev/config/
export default defineConfig({
  plugins: [react(), tailwindcss()],
  build: {
    rollupOptions: {
      // Three apps from one codebase: the POS (index.html), the customer display (display.html)
      // and the back-office dashboard (dashboard.html).
      input: {
        pos: 'index.html',
        display: 'display.html',
        dashboard: 'dashboard.html',
      },
      output: {
        // Libraries in chunks of their own, named for what they are: they change less often than
        // the apps, so browsers keep them cached across releases.
        manualChunks(id) {
          if (!id.includes('node_modules')) return undefined
          if (id.includes('libphonenumber-js') || id.includes('country-flag-icons')) return 'phone'
          return 'vendor'
        },
      },
    },
  },
  server: {
    // Same-origin API calls in development; in production the ALB routes /api to the backend.
    proxy: {
      '/api': 'http://localhost:8080',
    },
  },
  test: {
    environment: 'jsdom',
    setupFiles: ['./src/test/setup.ts'],
    // Workflow tests click through whole sales; with all files in parallel, 5s is too tight.
    testTimeout: 15_000,
  },
})
