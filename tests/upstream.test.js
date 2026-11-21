/**
 * tests/upstream.test.js — RPC 白名单、现代协议转发（args 只透传不改写）、上游不可达 502。
 * 对应 docs/interfaces.md §2.2/§4 与 SPEC v3 AC3。
 */
import test from 'node:test'
import assert from 'node:assert/strict'
import http from 'node:http'
import net from 'node:net'
import { loginReady, readJson, startFakeUpstream, startTestGateway, api } from './helpers.mjs'
import { PendingStore } from '../gateway/upstream.mjs'

test('RPC 转发：现代协议信封、args 原样透传、Cookie 注入、result 原样返回', async (t) => {
  const upstream = await startFakeUpstream({
    rpcHandlers: {
      'session/list': () => ({ ok: true, value: { items: [{ id: 's1', title: '真实会话' }] } }),
      'session/page': ({ body }) => ({ ok: true, value: { echo: body.payload.args } }),
      'session/projections': ({ body }) => ({ ok: true, value: { projections: body.payload.args.request } }),
      'goals/get': ({ body }) => ({ ok: true, value: { goal: body.payload.args.agentId } }),
      'workspace/create': ({ body }) => ({ ok: true, value: { workspace: body.payload.args.request.path } }),
      'session/fork': () => ({ ok: false, error: { code: 'session/not-found', message: '不存在', details: {} } }),
    },
  })
  const { gateway } = await startTestGateway(t, { upstream, gatewayOptions: { disableStreams: true } })
  const session = await loginReady(gateway)
  assert.equal(session.status, 200)

  const list = await readJson(await api(gateway, session.json.token, '/api/rpc', { method: 'POST', body: { method: 'session/list', args: { _request: {} } } }))
  assert.equal(list.status, 200)
  assert.deepEqual(list.json, { ok: true, value: { items: [{ id: 's1', title: '真实会话' }] } })
  const call = upstream.lastRpc('session/list')
  assert.equal(call.body.type, 'client-request')
  assert.equal(call.body.method, 'session/list')
  assert.equal(typeof call.body.rpcId, 'string')
  assert.ok(call.body.rpcId.length > 0)
  assert.deepEqual(call.body.payload, { args: { _request: {} } })
  assert.equal(call.cookie, upstream.cookie)
  assert.equal(list.text.includes(call.body.rpcId), false, 'rpcId 不应暴露给客户端')

  // 网关只透传 args：v3 补充的方法形状原样到上游
  const page = await readJson(
    await api(gateway, session.json.token, '/api/rpc', {
      method: 'POST',
      body: { method: 'session/page', args: { request: { address: { kind: 'session', sessionId: 's1' }, throughSeq: 10 } } },
    }),
  )
  assert.equal(page.status, 200)
  assert.deepEqual(upstream.lastRpc('session/page').body.payload.args, {
    request: { address: { kind: 'session', sessionId: 's1' }, throughSeq: 10 },
  })
  assert.deepEqual(page.json.value.echo, { request: { address: { kind: 'session', sessionId: 's1' }, throughSeq: 10 } })

  const projects = await readJson(
    await api(gateway, session.json.token, '/api/rpc', {
      method: 'POST',
      body: { method: 'session/projections', args: { request: { address: { kind: 'session', sessionId: 's1' } } } },
    }),
  )
  assert.equal(projects.status, 200)
  assert.deepEqual(upstream.lastRpc('session/projections').body.payload.args.request, { address: { kind: 'session', sessionId: 's1' } })

  const goal = await readJson(await api(gateway, session.json.token, '/api/rpc', { method: 'POST', body: { method: 'goals/get', args: { agentId: 's1' } } }))
  assert.equal(goal.status, 200)
  assert.deepEqual(upstream.lastRpc('goals/get').body.payload.args, { agentId: 's1' })

  const workspace = await readJson(
    await api(gateway, session.json.token, '/api/rpc', { method: 'POST', body: { method: 'workspace/create', args: { request: { path: '/tmp/ws' } } } }),
  )
  assert.equal(workspace.status, 200)
  assert.deepEqual(upstream.lastRpc('workspace/create').body.payload.args, { request: { path: '/tmp/ws' } })

  // 上游业务错误原样透传（HTTP 200 + result.ok=false）
  const fork = await readJson(await api(gateway, session.json.token, '/api/rpc', { method: 'POST', body: { method: 'session/fork', args: { request: { sessionId: 's1' } } } }))
  assert.equal(fork.status, 200)
  assert.equal(fork.json.ok, false)
  assert.equal(fork.json.error.code, 'session/not-found')
})

test('RPC 白名单：非白名单方法 403 且不打上游；session/follow 只走流中心', async (t) => {
  const upstream = await startFakeUpstream()
  const { gateway } = await startTestGateway(t, { upstream, gatewayOptions: { disableStreams: true } })
  const session = await loginReady(gateway)
  const before = upstream.rpcCalls.length

  for (const method of ['session/archive', 'host/describe', 'session/follow', 'session/delete', '__proto__']) {
    const response = await readJson(await api(gateway, session.json.token, '/api/rpc', { method: 'POST', body: { method, args: {} } }))
    assert.equal(response.status, 403, `${method} 应 403`)
    assert.equal(response.json.error, 'method-not-allowed')
  }
  assert.equal(upstream.rpcCalls.length, before, '被拒方法不应打到上游')

  // 方法类型错误
  const bad = await readJson(await api(gateway, session.json.token, '/api/rpc', { method: 'POST', body: { args: {} } }))
  assert.equal(bad.status, 400)
})

test('上游不可达 / Cookie 缺失 → 502 upstream-unavailable', async (t) => {
  // 端口 1 必然连接失败（不可达上游）
  const { gateway } = await startTestGateway(t, {
    env: { DSH_MOBILE_UPSTREAM: 'http://127.0.0.1:1' },
    gatewayOptions: { disableStreams: true },
  })
  const home = gateway.home
  const fs = await import('node:fs')
  const path = await import('node:path')
  // 有 Cookie 但上游不可达
  fs.writeFileSync(path.join(home, 'upstream.cookie'), 'dsh_session=abc\n', { mode: 0o600 })
  const session = await loginReady(gateway)
  const unreachable = await readJson(await api(gateway, session.json.token, '/api/rpc', { method: 'POST', body: { method: 'session/list', args: { _request: {} } } }))
  assert.equal(unreachable.status, 502)
  assert.equal(unreachable.json.error, 'upstream-unavailable')

  // 无 Cookie：直接 502
  fs.unlinkSync(path.join(home, 'upstream.cookie'))
  const noCookie = await readJson(await api(gateway, session.json.token, '/api/rpc', { method: 'POST', body: { method: 'session/modelCatalog', args: {} } }))
  assert.equal(noCookie.status, 502)
  assert.equal(noCookie.json.error, 'upstream-unavailable')
})


test('PendingStore：断线清空 pending 缓存，重连后由上游重放重建；outcome 任意 JSON', async () => {
  const calls = []
  const stubUpstream = {
    async rpc(method, args) {
      calls.push({ method, args })
      return { ok: true, value: { accepted: true } }
    },
  }
  const store = new PendingStore({ upstream: stubUpstream })
  store.handleValue({ type: 'ready', clientId: 'client-1' })
  store.handleValue({
    type: 'waterfall',
    event: 'approval/request',
    eventId: 'evt-1',
    agentId: 's1',
    request: { toolName: 'shell', reason: '测试' },
  })
  assert.equal(store.pending().length, 1)
  assert.equal(store.pending()[0].kind, 'approval')

  // 断线：ready/clientId 与 pending 缓存清空（避免僵尸审批卡片）
  store.markDisconnected()
  assert.equal(store.ready, false)
  assert.equal(store.clientId, null)
  assert.equal(store.pending().length, 0)
  // 断线期间回答 → events-not-ready
  assert.deepEqual(await store.respond('evt-1', 'allowed-once'), { accepted: false, error: 'events-not-ready' })

  // 重连后上游重放 ready + waterfall，缓存重建
  store.handleValue({ type: 'ready', clientId: 'client-2' })
  store.handleValue({
    type: 'waterfall',
    event: 'question/request',
    eventId: 'evt-2',
    agentId: 's1',
    request: { questions: [{ id: 'q1', question: '继续？' }] },
  })
  assert.equal(store.pending().length, 1)
  assert.equal(store.pending()[0].kind, 'question')

  // 对象形态 outcome 原样包成 {kind:'result',value}
  const answers = { answers: [{ id: 'q1', selected: ['是'] }] }
  const accepted = await store.respond('evt-2', answers)
  assert.deepEqual(accepted, { accepted: true })
  assert.equal(calls.length, 1)
  assert.equal(calls[0].method, '$events/result')
  assert.equal(calls[0].args.clientId, 'client-2')
  assert.equal(calls[0].args.eventId, 'evt-2')
  assert.deepEqual(calls[0].args.outcome, { kind: 'result', value: answers })
  assert.equal(store.pending().length, 0)

  // upstream cancel 帧移除 pending
  store.handleValue({ type: 'waterfall', event: 'approval/request', eventId: 'evt-3', agentId: 's1', request: {} })
  assert.equal(store.pending().length, 1)
  store.handleValue({ type: 'cancel', eventId: 'evt-3' })
  assert.equal(store.pending().length, 0)
})


async function rawHttpRequest(port, requestText) {
  return new Promise((resolve, reject) => {
    const socket = net.connect(port, '127.0.0.1')
    let buffer = ''
    const timer = setTimeout(() => {
      socket.destroy()
      resolve(buffer)
    }, 3000)
    timer.unref?.()
    socket.on('connect', () => socket.write(requestText))
    socket.on('data', (chunk) => {
      buffer += chunk.toString('latin1')
    })
    socket.on('end', () => {
      clearTimeout(timer)
      socket.destroy()
      resolve(buffer)
    })
    socket.on('error', (err) => {
      if (buffer) resolve(buffer)
      else reject(err)
    })
  })
}

function chunkedOversize(port, token, chunkCount = 9, chunkSize = 4 * 1024 * 1024) {
  return new Promise((resolve, reject) => {
    let settled = false
    const finish = (code) => {
      if (settled) return
      settled = true
      resolve(code)
    }
    const req = http.request(
      {
        host: '127.0.0.1',
        port,
        path: '/api/rpc',
        method: 'POST',
        headers: { authorization: `Bearer ${token}`, 'content-type': 'application/json' },
      },
      (res) => {
        res.resume()
        finish(res.statusCode)
        // 服务端已响应（413）后停止继续上传，避免测试结束后仍有 async socket 活动。
        try {
          req.destroy()
        } catch {
          /* ignore */
        }
      },
    )
    req.on('error', (err) => {
      if (['EPIPE', 'ECONNRESET', 'ERR_STREAM_DESTROYED'].includes(err.code)) finish(-1)
      else reject(err)
    })
    req.on('socket', (socket) => socket.on('error', () => finish(-1)))
    try {
      req.write('{"method":"session/list","args":{"x":"')
      const chunk = 'A'.repeat(chunkSize)
      for (let i = 0; i < chunkCount && !settled; i++) req.write(chunk)
      if (!settled) req.end('"}}')
    } catch (err) {
      finish(-1)
    }
    setTimeout(() => {
      if (!settled) {
        settled = true
        req.destroy()
        resolve(-1)
      }
    }, 15_000).unref?.()
  })
}

test('/api/rpc 图片内联：2MB / 10MB 可通过，>32MiB 413（其他端点小上限不变）', async (t) => {
  const upstream = await startFakeUpstream({
    rpcHandlers: {
      'session/prompt': ({ body }) => ({ ok: true, value: { images: body.payload.args.request.content.length } }),
    },
  })
  const { gateway } = await startTestGateway(t, { upstream, gatewayOptions: { disableStreams: true } })
  const session = await loginReady(gateway)
  const token = session.json.token

  const sendPrompt = async (imageBytes) => {
    const image = { type: 'image', mediaType: 'image/jpeg', data: 'A'.repeat(imageBytes) }
    return readJson(
      await api(gateway, token, '/api/rpc', {
        method: 'POST',
        body: {
          method: 'session/prompt',
          args: { request: { sessionId: 's1', mode: 'queue', content: [{ type: 'text', text: '看图' }, image] } },
        },
      }),
    )
  }

  const twoMb = await sendPrompt(2 * 1024 * 1024)
  assert.equal(twoMb.status, 200)
  assert.equal(twoMb.json.ok, true)
  assert.equal(upstream.lastRpc('session/prompt').body.payload.args.request.content[1].data.length, 2 * 1024 * 1024)

  const tenMb = await sendPrompt(10 * 1024 * 1024)
  assert.equal(tenMb.status, 200)
  assert.equal(upstream.lastRpc('session/prompt').body.payload.args.request.content[1].data.length, 10 * 1024 * 1024)
  assert.equal(upstream.rpcCount('session/prompt'), 2)

  // >32MiB → 413 body-too-large，不再转发上游
  const tooBig = await sendPrompt(33 * 1024 * 1024)
  assert.equal(tooBig.status, 413)
  assert.equal(tooBig.json.error, 'body-too-large')
  assert.equal(upstream.rpcCount('session/prompt'), 2, '超限 body 不应打到上游')

  // 其他端点仍保持小上限（/api/login 16KiB → 413）
  const bigLogin = await readJson(
    await fetch(`${gateway.url}/api/login`, {
      method: 'POST',
      headers: { 'content-type': 'application/json' },
      body: JSON.stringify({ password: 'x'.repeat(32 * 1024), deviceName: 'big' }),
    }),
  )
  assert.equal(bigLogin.status, 413)
})

test('/api/rpc 拒绝非法 Content-Length 与超限分块 body', async (t) => {
  const upstream = await startFakeUpstream()
  const { gateway } = await startTestGateway(t, { upstream, gatewayOptions: { disableStreams: true } })
  const session = await loginReady(gateway)
  const token = session.json.token

  // 非法 Content-Length：Node HTTP parser 或网关 readBody 均返回 400
  const raw = await rawHttpRequest(
    gateway.port,
    `POST /api/rpc HTTP/1.1\r\nHost: 127.0.0.1:${gateway.port}\r\nAuthorization: Bearer ${token}\r\n` +
      'Content-Type: application/json\r\nContent-Length: abc\r\nConnection: close\r\n\r\n{}',
  )
  assert.match(raw.split('\r\n')[0], /400/)

  // 分块传输超 32MiB → 413（流式统计，不依赖 Content-Length）
  const status = await chunkedOversize(gateway.port, token)
  assert.equal(status, 413)
  assert.equal(upstream.rpcCalls.length, 0)
})


test('RPC 白名单 v0.1.5 扩展：commands/list、commands/execute、permissionPresets/catalog 原样透传', async (t) => {
  const upstream = await startFakeUpstream({
    rpcHandlers: {
      'commands/list': ({ body }) => ({ ok: true, value: { echo: body.payload.args } }),
      'commands/execute': ({ body }) => ({ ok: true, value: { echoed: body.payload.args } }),
      'permissionPresets/catalog': ({ body }) => ({ ok: true, value: { catalog: body.payload.args } }),
    },
  })
  const { gateway } = await startTestGateway(t, { upstream, gatewayOptions: { disableStreams: true } })
  const session = await loginReady(gateway)
  assert.equal(session.status, 200)

  const cases = [
    ['commands/list', { sessionId: 's1' }],
    ['commands/execute', { sessionId: 's1', line: '/export markdown', submittedAttachments: [] }],
    ['permissionPresets/catalog', { sessionId: 's1' }],
  ]
  for (const [method, args] of cases) {
    const response = await readJson(await api(gateway, session.json.token, '/api/rpc', { method: 'POST', body: { method, args } }))
    assert.equal(response.status, 200, `${method} 应 200`)
    assert.equal(response.json.ok, true)
    const call = upstream.lastRpc(method)
    assert.ok(call, `${method} 应转发到上游`)
    assert.equal(call.body.type, 'client-request')
    assert.equal(call.body.method, method)
    assert.deepEqual(call.body.payload.args, args, `${method} args 应原样透传`)
  }

  // 白名单外仍 403（含相近前缀方法，不能被通配放行）
  for (const method of ['commands/delete', 'commands', 'permissionPresets/apply', 'permissionPresets/catalog/extra']) {
    const response = await readJson(await api(gateway, session.json.token, '/api/rpc', { method: 'POST', body: { method, args: {} } }))
    assert.equal(response.status, 403, `${method} 应 403`)
    assert.equal(response.json.error, 'method-not-allowed')
  }
  assert.equal(upstream.rpcCount('commands/delete'), 0)
})


test('事件流白名单：workspace/follow 可用于工作区/归档集合，未知端点拒绝', async () => {
  const { validateClientFrame } = await import('../gateway/upstream.mjs')
  const open = (endpoint) => JSON.stringify({ type: 'open', streamId: 's1', endpoint, args: {} })
  assert.equal(validateClientFrame(open('workspace/follow')).ok, true)
  assert.equal(validateClientFrame(open('session/follow')).ok, true)
  assert.equal(validateClientFrame(open('$events')).ok, true)
  assert.equal(validateClientFrame(open('evil/follow')).ok, false)
})
