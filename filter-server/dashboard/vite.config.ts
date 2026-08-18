import { defineConfig, loadEnv } from 'vite'
import path from 'path'
import tailwindcss from '@tailwindcss/vite'
import react from '@vitejs/plugin-react'


function figmaAssetResolver() {
  return {
    name: 'figma-asset-resolver',
    resolveId(id) {
      if (id.startsWith('figma:asset/')) {
        const filename = id.replace('figma:asset/', '')
        return path.resolve(__dirname, 'src/assets', filename)
      }
    },
  }
}

export default defineConfig(({ mode }) => {
  // VITE_DEV_TOKEN is lockprofile_service.py's LOCKPROFILE_TOKEN, for local dev only -- in
  // production Caddy injects this same bearer header itself (see Caddyfile's /dashboard-api/*
  // block), so the built app never contains or needs it. Put VITE_DEV_TOKEN in a gitignored
  // .env.local pointed at whatever LOCKPROFILE_TOKEN your local `lockprofile_service.py` is
  // running with (see dashboard/README.md).
  const env = loadEnv(mode, process.cwd(), 'VITE_')

  return {
    // Served by Caddy at /dashboard/* (see filter-server/Caddyfile's handle_path /dashboard/*),
    // not at the site root -- without this, the built index.html's asset URLs would be root-
    // relative (/assets/...) and 404 once deployed.
    base: '/dashboard/',
    plugins: [
      figmaAssetResolver(),
      // The React and Tailwind plugins are both required for Make, even if
      // Tailwind is not being actively used – do not remove them
      react(),
      tailwindcss(),
    ],
    resolve: {
      alias: {
        // Alias @ to the src directory
        '@': path.resolve(__dirname, './src'),
      },
    },

    server: {
      proxy: {
        // No path rewrite: production Caddy uses `handle /dashboard-api/*` (not `handle_path`),
        // which forwards the full path unchanged to lockprofile_service.py -- its routes match on
        // the literal "/dashboard-api/..." path (see DASHBOARD_DEVICE_RE etc). Stripping the
        // prefix here would route dev requests differently than prod.
        '/dashboard-api': {
          target: env.VITE_DEV_API_TARGET || 'http://127.0.0.1:8091',
          changeOrigin: true,
          headers: env.VITE_DEV_TOKEN ? { Authorization: `Bearer ${env.VITE_DEV_TOKEN}` } : {},
        },
      },
    },

    // File types to support raw imports. Never add .css, .tsx, or .ts files to this.
    assetsInclude: ['**/*.svg', '**/*.csv'],
  }
})
