import { defineConfig } from 'vitest/config';

export default defineConfig({
  // Relative base so the build can be hosted from any sub-path (e.g. GitHub Pages).
  base: './',
  worker: { format: 'es' },
  test: { include: ['test/**/*.test.ts'] },
});
