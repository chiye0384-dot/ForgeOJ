// Copyright 2026 池也
// SPDX-License-Identifier: Apache-2.0
// Original frontend/config, changing only the proxy target for the isolated Docker network.
import { createServer, loadConfigFromFile } from 'vite'
for (const [port, offlineSocket] of [[5173, false], [5174, true]]) {
  const loaded = await loadConfigFromFile({ command: 'serve', mode: 'development' }, 'vite.config.ts')
  const proxy = {
    ...(offlineSocket ? { '^/api/v1/submissions/[0-9a-f-]+/events$': {
      target: 'http://127.0.0.1:1', ws: true, changeOrigin: false,
      configure(proxy) {
        proxy.on('proxyReqWs', (_outbound, request) => {
          const match = request.url.match(/^\/api\/v1\/submissions\/([0-9a-f-]{36})\/events$/)
          if (match) console.log(JSON.stringify({ event: 'validation.ws_blocked', port, submissionId: match[1] }))
        })
      },
    }} : {}),
    '/api': { ...loaded.config.server.proxy['/api'], target: 'http://api:8080', configure(proxy) {
      proxy.on('proxyReq', (_outbound, request) => {
        const match = request.url.match(/^\/api\/v1\/submissions\/([0-9a-f-]{36})$/)
        if (match) console.log(JSON.stringify({ event: 'validation.result_get', port, submissionId: match[1] }))
      })
      proxy.on('proxyReqWs', (_outbound, request) => {
        const match = request.url.match(/^\/api\/v1\/submissions\/([0-9a-f-]{36})\/events$/)
        if (match) console.log(JSON.stringify({ event: 'validation.ws_forwarded', port, submissionId: match[1] }))
      })
    }},
  }
  const server = await createServer({ ...loaded.config, configFile: false,
    server: { ...loaded.config.server, host: '0.0.0.0', port, strictPort: true, proxy } })
  await server.listen()
  console.log(JSON.stringify({ event: 'validation.frontend_ready', port, offlineSocket }))
}
