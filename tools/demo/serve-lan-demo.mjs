// Copyright 2026 池也; SPDX-License-Identifier: Apache-2.0
// Temporary LAN demonstration only. Serve an accepted build, never the source tree.
import http from 'node:http'
import { readFile, stat } from 'node:fs/promises'
import { resolve, extname, sep } from 'node:path'

const root = resolve(process.env.FORGEOJ_DEMO_STATIC_ROOT || '/site')
const allowedHost = process.env.FORGEOJ_DEMO_HOST
if (!allowedHost || !/^\d{1,3}(\.\d{1,3}){3}:\d+$/.test(allowedHost)) {
  throw new Error('A specific LAN host and port are required')
}
const types = { '.html': 'text/html; charset=utf-8', '.js': 'text/javascript; charset=utf-8',
  '.css': 'text/css; charset=utf-8', '.svg': 'image/svg+xml', '.png': 'image/png',
  '.jpg': 'image/jpeg', '.ico': 'image/x-icon', '.woff2': 'font/woff2' }

// randomUUID is restricted to secure contexts, while getRandomValues also works
// on LAN HTTP. This shim is served only by the disposable demonstration gateway.
const compatibility = `if(typeof crypto.randomUUID!=='function'){
  Object.defineProperty(crypto,'randomUUID',{value:function(){
    const b=crypto.getRandomValues(new Uint8Array(16));
    b[6]=(b[6]&15)|64;b[8]=(b[8]&63)|128;
    const h=Array.from(b,x=>x.toString(16).padStart(2,'0'));
    return h.slice(0,4).join('')+'-'+h.slice(4,6).join('')+'-'+h.slice(6,8).join('')+'-'+h.slice(8,10).join('')+'-'+h.slice(10).join('');
  }});
}`

function deny(response, status) {
  response.writeHead(status, { 'Cache-Control': 'no-store' }); response.end()
}
function permitted(request) {
  return request.headers.host === allowedHost || /^(localhost|127\.0\.0\.1):\d+$/.test(request.headers.host || '')
}
function headers(request) {
  const result = { ...request.headers }
  // No client-provided forwarding metadata is trusted by this gateway.
  for (const name of Object.keys(result)) {
    if (name === 'forwarded' || name.startsWith('x-forwarded-')) delete result[name]
  }
  return result
}
const server = http.createServer(async (request, response) => {
  if (!permitted(request)) return deny(response, 421)
  let pathname
  try { pathname = decodeURIComponent(new URL(request.url, 'http://demo.invalid').pathname) }
  catch { return deny(response, 400) }
  if (pathname.startsWith('/api/')) {
    const upstream = http.request({ hostname: 'api', port: 8080, path: request.url,
      method: request.method, headers: headers(request) }, incoming => {
      response.writeHead(incoming.statusCode, incoming.headers); incoming.pipe(response)
    })
    upstream.setTimeout(15000, () => upstream.destroy())
    upstream.on('error', () => { if (!response.headersSent) deny(response, 503); else response.destroy() })
    request.on('aborted', () => upstream.destroy())
    request.pipe(upstream)
    return
  }
  // The local verification mailbox and source files are not demo endpoints.
  if (pathname.startsWith('/dev-mail') || pathname.startsWith('/@') || pathname.startsWith('/src/')) return deny(response, 404)
  if (!['GET', 'HEAD'].includes(request.method)) return deny(response, 405)
  if (pathname === '/demo-compat.js') {
    response.writeHead(200, { 'Content-Type': types['.js'], 'Cache-Control': 'no-store' })
    return response.end(request.method === 'HEAD' ? undefined : compatibility)
  }
  try {
    let file = resolve(root, '.' + pathname)
    if (!file.startsWith(root + sep) && file !== root) return deny(response, 404)
    let asset = false
    try { asset = (await stat(file)).isFile() } catch { /* SPA route */ }
    if (!asset) {
      if (pathname.startsWith('/assets/') || extname(pathname)) return deny(response, 404)
      file = resolve(root, 'index.html')
    }
    let body = await readFile(file)
    if (file === resolve(root, 'index.html')) {
      body = Buffer.from(body.toString().replace('</head>', '<meta name="forgeoj-local-demo" content="true"><script src="/demo-compat.js"></script></head>'))
    }
    response.writeHead(200, { 'Content-Type': types[extname(file)] || 'application/octet-stream',
      'Content-Length': body.length, 'Cache-Control': 'no-store', 'X-Content-Type-Options': 'nosniff' })
    response.end(request.method === 'HEAD' ? undefined : body)
  } catch { deny(response, 404) }
})
server.on('upgrade', (request, socket, head) => {
  if (!permitted(request) || !/^\/api\/v1\/submissions\/[0-9a-f-]{36}\/events$/.test(request.url)) {
    socket.end('HTTP/1.1 403 Forbidden\r\nConnection: close\r\n\r\n'); return
  }
  const upstream = http.request({ hostname: 'api', port: 8080, path: request.url,
    method: 'GET', headers: headers(request) })
  upstream.setTimeout(10000, () => upstream.destroy())
  upstream.on('upgrade', (response, remote, upstreamHead) => {
    upstream.setTimeout(0)
    socket.write('HTTP/1.1 101 Switching Protocols\r\n' +
      Object.entries(response.headers).map(([name, value]) => `${name}: ${value}`).join('\r\n') + '\r\n\r\n')
    if (upstreamHead.length) socket.write(upstreamHead)
    if (head.length) remote.write(head)
    socket.pipe(remote); remote.pipe(socket)
    socket.on('error', () => remote.destroy()); remote.on('error', () => socket.destroy())
    socket.on('close', () => remote.destroy()); remote.on('close', () => socket.destroy())
  })
  upstream.on('response', response => { response.resume(); socket.end(`HTTP/1.1 ${response.statusCode} Rejected\r\nConnection: close\r\n\r\n`) })
  upstream.on('error', () => socket.destroy())
  socket.on('error', () => upstream.destroy())
  upstream.end()
})
server.requestTimeout = 20000
server.listen(5173, '0.0.0.0', () => console.log('LAN demo gateway ready'))
