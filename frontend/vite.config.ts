import { defineConfig, loadEnv } from 'vite';
import react from '@vitejs/plugin-react';

export default defineConfig(({ mode }) => {
  const env = loadEnv(mode, process.cwd(), '');
  const target = env.API_PROXY_TARGET || 'http://127.0.0.1:8082';
  const apiOrigin = env.VITE_API_BASE_URL ? new URL(env.VITE_API_BASE_URL).origin : '';
  const proxy = {
    '^/api/v1/analytics/summary(?:\\?|$)': { target },
    '^/actuator/health/readiness$': { target },
  };
  const headers = {
    'X-Content-Type-Options': 'nosniff',
    'Referrer-Policy': 'no-referrer',
    'Permissions-Policy': 'camera=(), microphone=(), geolocation=()',
    'X-Frame-Options': 'DENY',
  };
  const csp = `default-src 'self'; script-src 'self'; style-src 'self'; connect-src 'self' ${apiOrigin}; object-src 'none'; base-uri 'self'; frame-ancestors 'none'`;
  return {
    plugins: [react()],
    server: { host: '127.0.0.1', port: 5173, strictPort: true, cors: false, proxy, headers },
    preview: {
      host: '127.0.0.1', port: 4173, strictPort: true, cors: false, proxy,
      headers: { ...headers, 'Content-Security-Policy': csp },
    },
  };
});
