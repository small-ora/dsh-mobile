/**
 * tests/attachment.test.js — 网关 /api/attachment 代理（图片/文件字节流）。
 *
 * 覆盖：
 *  - Bearer 设备 token 鉴权（未登录 401）；
 *  - 参数校验（非 64hex / 缺参数 → 400，且不打上游）；
 *  - 200 流式透传 content-type/content-length + 字节完整；HEAD 只回头；
 *  - 上游 Cookie 注入、客户端 Authorization/Cookie 不透传给上游；
 *  - 上游 404/503 错误映射。
 */
import test from 'node:test'
import assert from 'node:assert/strict'
import http from 'node:http'
import { api, loginReady, readJson, startTestGateway } from './helpers.mjs'

const IMAGE_ID = 'a'.repeat(64)

function createAttachmentUpstream() {
  const requests = []
  let mode = 'ok'
  const server = http.createServer((req, res) => {
    let url
    try {
      url = new URL(req.url || '/', 'http://localhost')
    } catch {
      res.writeHead(400)
      return res.end()
    }
    requests.push({ path: url.pathname, query: Object.fromEntries(url.searchParams), headers: req.headers })
    if (url.pathname !== '/mobile/api/session-file') {
      res.writeHead(404, { 'content-type': 'application/json' })
      return res.end('{"ok":false,"code":"not-found"}')
    }
    if (mode === '404') {
      res.writeHead(404, { 'content-type': 'application/json' })
      return res.end('{"ok":false,"code":"file-not-in-session"}')
    }
    if (mode === '503') {
      res.writeHead(503, { 'content-type': 'application/json' })
      return res.end('{"ok":false,"code":"files-unsupported"}')
    }
    const body = Buffer.from('IMAGEDATA')
    res.writeHead(200, { 'content-type': 'image/png', 'content-length': body.length })
    if (req.method === 'HEAD') return res.end()
    return res.end(body)
  })
  return {
    url: '',
    cookie: 'dsh_session=fake-cookie',
    requests,
    setMode(next) {
      mode = next
    },
    async listen() {
      await new Promise((resolve, reject) => {
        server.once('error', reject)
        server.listen(0, '127.0.0.1', () => {
          server.off('error', reject)
          resolve()
        })
      })
      this.url = `http://127.0.0.1:${server.address().port}`
      return this
    },
    close() {
      server.closeAllConnections?.()
      return new Promise((resolve) => server.close(() => resolve()))
    },
  }
}

async function fetchAttachment(gateway, token, path, init = {}) {
  const headers = { ...(init.headers || {}) }
  if (token) headers.authorization = `Bearer ${token}`
  return fetch(`${gateway.url}${path}`, { ...init, headers })
}

test('附件代理：200 字节流 / HEAD / 只注入上游 Cookie（不透传客户端凭据）', async (t) => {
  const upstream = await createAttachmentUpstream().listen()
  const { gateway } = await startTestGateway(t, { upstream, gatewayOptions: { disableStreams: true } })
  const session = await loginReady(gateway)
  assert.equal(session.status, 200)
  const token = session.json.token

  const path = `/api/attachment?sessionId=s1&attachmentId=${IMAGE_ID}`
  const response = await fetchAttachment(gateway, token, path, {
    headers: { cookie: 'client-session=should-not-forward', 'x-client': 'app' },
  })
  assert.equal(response.status, 200)
  assert.equal(response.headers.get('content-type'), 'image/png')
  assert.equal(response.headers.get('content-length'), '9')
  assert.equal(response.headers.get('cache-control'), 'private, no-store')
  assert.equal(response.headers.get('x-content-type-options'), 'nosniff')
  assert.equal(await response.text(), 'IMAGEDATA')

  const upstreamRequest = upstream.requests.at(-1)
  assert.equal(upstreamRequest.path, '/mobile/api/session-file')
  assert.deepEqual(upstreamRequest.query, { sessionId: 's1', attachmentId: IMAGE_ID })
  assert.equal(upstreamRequest.headers.cookie, 'dsh_session=fake-cookie', '只注入上游 Cookie')
  assert.equal(upstreamRequest.headers.authorization, undefined, '客户端 Authorization 不得透传上游')
  assert.ok(!String(upstreamRequest.headers.cookie).includes('client-session'), '客户端 Cookie 不得透传上游')

  // HEAD：只回头，无 body
  const head = await fetchAttachment(gateway, token, path, { method: 'HEAD' })
  assert.equal(head.status, 200)
  assert.equal(head.headers.get('content-type'), 'image/png')
  assert.equal(head.headers.get('content-length'), '9')
  assert.equal(await head.text(), '')

  // 未鉴权 → 401，且不再打上游
  const before = upstream.requests.length
  const unauth = await readJson(await fetchAttachment(gateway, null, path))
  assert.equal(unauth.status, 401)
  assert.equal(upstream.requests.length, before)
})

test('附件代理：参数非法 400、上游 404/503 映射', async (t) => {
  const upstream = await createAttachmentUpstream().listen()
  const { gateway } = await startTestGateway(t, { upstream, gatewayOptions: { disableStreams: true } })
  const session = await loginReady(gateway)
  const token = session.json.token

  const before = upstream.requests.length
  for (const badPath of [
    '/api/attachment?sessionId=s1&attachmentId=xyz',
    `/api/attachment?attachmentId=${IMAGE_ID}`,
    '/api/attachment?sessionId=s1',
    `/api/attachment?sessionId=${'x'.repeat(201)}&attachmentId=${IMAGE_ID}`,
  ]) {
    const bad = await readJson(await fetchAttachment(gateway, token, badPath))
    assert.equal(bad.status, 400, badPath)
    assert.equal(bad.json.error, 'invalid-reference')
  }
  assert.equal(upstream.requests.length, before, '参数非法不应打上游')

  upstream.setMode('404')
  const notFound = await readJson(await fetchAttachment(gateway, token, `/api/attachment?sessionId=s1&attachmentId=${IMAGE_ID}`))
  assert.equal(notFound.status, 404)
  assert.equal(notFound.json.error, 'attachment-not-found')

  upstream.setMode('503')
  const unsupported = await readJson(await fetchAttachment(gateway, token, `/api/attachment?sessionId=s1&attachmentId=${IMAGE_ID}`))
  assert.equal(unsupported.status, 503)
  assert.equal(unsupported.json.error, 'attachment-unsupported')

  // 鉴权失败不因为是附件接口而放宽
  const noToken = await readJson(await fetchAttachment(gateway, null, `/api/attachment?sessionId=s1&attachmentId=${IMAGE_ID}`))
  assert.equal(noToken.status, 401)
})
