import tailwindcss from '@tailwindcss/vite';
import react from '@vitejs/plugin-react';
import { defineConfig } from 'vite';

export default defineConfig({
  plugins: [react(), tailwindcss()],
  server: {
    port: 5174,
    // In development the API is the Spring Cloud Gateway running in Docker Compose.
    proxy: { '/api': 'http://localhost:8091' },
  },
});
