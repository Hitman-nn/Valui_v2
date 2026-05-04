import { defineConfig } from 'orval';

export default defineConfig({
  valui: {
    input: {
      target: process.env.OPENAPI_URL || 'http://localhost:8080/v3/api-docs',
    },
    output: {
      mode: 'tags-split',
      target: './src/api/generated',
      schemas: './src/api/model',
      client: 'react-query',
      override: {
        mutator: { path: './src/api/client.ts', name: 'apiClient' },
      },
    },
  },
});
