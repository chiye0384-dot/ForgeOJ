// Copyright 2026 池也
// SPDX-License-Identifier: Apache-2.0
// Disposable replay only. No Docker control, credentials, persistence or production mail route.
import { createServer, request } from 'node:http'
createServer((incoming, outgoing) => {
  if (incoming.method !== 'GET' || incoming.url !== '/') {
    outgoing.writeHead(404).end()
    return
  }
  const upstream = request('http://127.0.0.1:2525/', { timeout: 3000 }, response => {
    outgoing.writeHead(response.statusCode, response.headers)
    response.pipe(outgoing)
  })
  upstream.on('timeout', () => upstream.destroy())
  upstream.on('error', () => outgoing.writeHead(503).end())
  upstream.end()
}).listen(2526, '0.0.0.0')
