import { defineConfig } from 'vitest/config';

export default defineConfig({
  base: '/',
  build: {
    outDir: '../src/main/resources/web',
    emptyOutDir: true,
    assetsDir: 'assets',
    sourcemap: false,
  },
  server: {
    proxy: {
      '/api': 'http://localhost:7080',
      '/a': 'http://localhost:7080',
      '/t': 'http://localhost:7080',
      '/ws': { target: 'ws://localhost:7080', ws: true },
    },
  },
  test: {
    environment: 'node',
    include: ['src/**/*.test.ts'],
  },
});
