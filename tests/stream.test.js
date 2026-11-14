/**
 * tests/stream.test.js — 流中心 + HTTP 长轮询（v3 主通道）。
 *
 * 覆盖 SPEC v3 AC6 / docs/interfaces.md §2.3：
 *  - open $events → poll 收到 ready；open session/follow → poll 收到 snapshot；
 *  - 无数据时挂起、有数据立即返回（长轮询唤醒）；
 *  - 每流缓冲截断/上游断线 → reset；重连自动重开已登记流；
 *  - 任意 Host + X-Forwarded-* 反代模拟下登录/RPC/长轮询均正常；
 *  - 非法 endpoint、无效 streamId、未鉴权。
 */
import test from 'node:test'
import assert from 'node:assert/strict'
import { connectWebSocket } from '../gateway/ws.mjs'
import { api, login, loginReady, readJson, startFakeUpstream, startTestGateway, waitFor } from './helpers.mjs'

async function poll(gateway, token, streamId, { since = 0, wait = 1000 } = {}) {
  const started = Date.now()
  const response = await readJson(
    await api(gateway, token, `/api/stream/poll?streamId=${encodeURIComponent(streamId)}&since=${since}&wait=${wait}`),
  )
  return { ...response, elapsed: Date.now() - started }
}

async function openStream(gateway, token, endpoint, args = {}) {
  const response = await readJson(
    await api(gateway, token, '/api/stream/open', { method: 'POST', body: { endpoint, args } }),
  )
  assert.equal(response.status, 200, `/api/stream/open ${endpoint} 应 200`)
  assert.equal(typeof response.json.streamId, 'string')
  return response.json.streamId
}

test('$events 长轮询：open → poll 收到 ready', async (t) => {
  const upstream = await startFakeUpstream({ eventsAfterReady: [{ type: 'emit', event: 'api-session/added', args: ['s1'] }] })
  const { gateway } = await startTestGateway(t, { upstream, gatewayOptions: { hubOptions: { retryDelays: [20] } } })
  const session = await loginReady(gateway)
  assert.equal(session.status, 200)

  const streamId = await openStream(gateway, session.json.token, '$events', {})
  const received = []
  let next = 0
  const ready = await waitFor(async () => {
    const response = await poll(gateway, session.json.token, streamId, { since: next, wait: 1500 })
    assert.equal(response.status, 200)
    assert.ok(Array.isArray(response.json.items))
    received.push(...response.json.items)
    next = response.json.next
    return received.find((item) => item.value.type === 'ready') || null
  }, { timeout: 3000, message: 'ready item' })
  assert.equal(ready.seq, 1, 'ready 应是该流的第一个 value')
  assert.equal(typeof ready.value.clientId, 'string')
  assert.equal(next >= 2, true)
  const firstResponse = await poll(gateway, session.json.token, streamId, { since: next, wait: 0 })
  assert.equal(firstResponse.json.closed, false)

  // 后续 emit 帧按序到达（可能和 ready 同一次返回，也可能下一次）
  const emit = await waitFor(async () => {
    const found = received.find((item) => item.value.type === 'emit')
    if (found) return found
    const response = await poll(gateway, session.json.token, streamId, { since: next, wait: 1500 })
    received.push(...response.json.items)
    next = response.json.next
    return received.find((item) => item.value.type === 'emit') || null
  }, { timeout: 3000, message: 'emit item' })
  assert.equal(emit.seq, 2)
  assert.equal(emit.value.event, 'api-session/added')

  // 转运给上游的 open 帧形状：endpoint + payload.args
  const opened = upstream.opened.filter((entry) => entry.endpoint === '$events')
  assert.ok(opened.length >= 1, '内部 pending 订阅 + App 流都应各自 open $events')
  assert.deepEqual(opened[opened.length - 1].args, {})
})

test('session/follow 长轮询：open → poll 收到 snapshot，args 原样透传', async (t) => {
  const upstream = await startFakeUpstream()
  const { gateway } = await startTestGateway(t, { upstream, gatewayOptions: { hubOptions: { retryDelays: [20] } } })
  const session = await loginReady(gateway)
  const args = { request: { address: { kind: 'session', sessionId: 's1' }, assistantStream: true } }
  const streamId = await openStream(gateway, session.json.token, 'session/follow', args)
  const first = await poll(gateway, session.json.token, streamId, { since: 0, wait: 2000 })
  assert.equal(first.json.items.length, 1)
  const value = first.json.items[0].value
  assert.equal(value.type, 'snapshot')
  assert.equal(value.cursor, 1)
  assert.ok(Array.isArray(value.records))
  assert.deepEqual(value.header.address, { kind: 'session', sessionId: 's1' })

  const followOpen = upstream.opened.filter((entry) => entry.endpoint === 'session/follow').at(-1)
  assert.deepEqual(followOpen.args, args, '网关只透传 args，不改写参数名')
})

test('长轮询：无数据挂起等待，数据到达立即返回', async (t) => {
  const upstream = await startFakeUpstream({ snapshotDelayMs: 200 })
  const { gateway } = await startTestGateway(t, { upstream, gatewayOptions: { hubOptions: { retryDelays: [20] } } })
  const session = await loginReady(gateway)
  const streamId = await openStream(gateway, session.json.token, 'session/follow', { request: { address: { kind: 'session', sessionId: 's1' } } })

  const started = Date.now()
  const result = await poll(gateway, session.json.token, streamId, { since: 0, wait: 5000 })
  const elapsed = Date.now() - started
  assert.equal(result.json.items.length, 1)
  assert.equal(result.json.items[0].value.type, 'snapshot')
  assert.ok(elapsed >= 120, `应挂起等待数据（实际 ${elapsed}ms）`)
  assert.ok(elapsed < 3000, `数据到达应立即返回（实际 ${elapsed}ms）`)

  // 有数据时 next 已经推进，再 poll 无数据应挂满 wait 后才返回空
  const idle = await poll(gateway, session.json.token, streamId, { since: result.json.next, wait: 150 })
  assert.equal(idle.json.items.length, 0)
  assert.equal(idle.json.closed, false)
  assert.ok(idle.elapsed >= 100, `无数据应等到 wait 超时（实际 ${idle.elapsed}ms）`)
})

test('上游断线：reset 标记 + 自动重连并重开已登记流', async (t) => {
  const upstream = await startFakeUpstream()
  const { gateway } = await startTestGateway(t, { upstream, gatewayOptions: { hubOptions: { retryDelays: [20] } } })
  const session = await loginReady(gateway)
  const streamId = await openStream(gateway, session.json.token, '$events', {})
  const first = await poll(gateway, session.json.token, streamId, { since: 0, wait: 2000 })
  assert.equal(first.json.items[0].value.type, 'ready')
  const nextBefore = first.json.next

  upstream.closeAllConnections()
  const resetResult = await waitFor(async () => {
    const response = await poll(gateway, session.json.token, streamId, { since: nextBefore, wait: 500 })
    return response.json.reset === true ? response : null
  }, { timeout: 3000, message: 'reset after upstream disconnect' })
  assert.equal(resetResult.json.reset, true)
  assert.equal(resetResult.json.closed, false)
  assert.ok(Number.isFinite(resetResult.json.next))

  // 重连后上游重新 open $events，App 再 poll 应收到新的 ready
  const readyAgain = await waitFor(async () => {
    const response = await poll(gateway, session.json.token, streamId, { since: resetResult.json.next, wait: 600 })
    const ready = response.json.items.find((item) => item.value.type === 'ready')
    return ready ? response : null
  }, { timeout: 4000, message: 'ready after reconnect' })
  assert.ok(readyAgain.json.items.some((item) => item.value.type === 'ready'))
  assert.ok(upstream.opened.filter((entry) => entry.endpoint === '$events').length >= 2, '重连后应自动重开流')

  // /api/streams 调试状态
  const streams = await readJson(await api(gateway, session.json.token, '/api/streams'))
  assert.equal(streams.status, 200)
  assert.ok(streams.json.items.some((entry) => entry.streamId === streamId))
  assert.equal(streams.json.connected, true)
})

test('cancel 关闭流并释放上游；非法 endpoint 403；无效 streamId 返回 stream-not-found', async (t) => {
  const upstream = await startFakeUpstream()
  const { gateway } = await startTestGateway(t, { upstream, gatewayOptions: { hubOptions: { retryDelays: [20] } } })
  const session = await loginReady(gateway)

  const badEndpoint = await readJson(await api(gateway, session.json.token, '/api/stream/open', { method: 'POST', body: { endpoint: 'session/other', args: {} } }))
  assert.equal(badEndpoint.status, 403)
  assert.equal(badEndpoint.json.error, 'method-not-allowed')

  const streamId = await openStream(gateway, session.json.token, 'session/follow', { request: { address: { kind: 'session', sessionId: 's1' } } })
  await poll(gateway, session.json.token, streamId, { since: 0, wait: 500 })
  const cancelled = await readJson(await api(gateway, session.json.token, '/api/stream/cancel', { method: 'POST', body: { streamId } }))
  assert.equal(cancelled.status, 200)
  assert.equal(cancelled.json.ok, true)
  await waitFor(() => upstream.state.cancelled.includes(streamId), { timeout: 2000, message: 'upstream cancel frame' })

  const afterCancel = await poll(gateway, session.json.token, streamId, { since: 0, wait: 0 })
  assert.equal(afterCancel.status, 404)
  assert.equal(afterCancel.json.error, 'stream-not-found')

  const unknown = await poll(gateway, session.json.token, 'no-such-stream', { since: 0, wait: 0 })
  assert.equal(unknown.status, 404)
  assert.equal(unknown.json.error, 'stream-not-found')

  // 内部 $events pending 流不可被客户端轮询/取消（统一 404，不泄露存在性）
  const internalPoll = await poll(gateway, session.json.token, '$events', { since: 0, wait: 0 })
  assert.equal(internalPoll.status, 404)
  const internalCancel = await readJson(await api(gateway, session.json.token, '/api/stream/cancel', { method: 'POST', body: { streamId: '$events' } }))
  assert.equal(internalCancel.status, 404)

  const cancelUnknown = await readJson(await api(gateway, session.json.token, '/api/stream/cancel', { method: 'POST', body: { streamId: 'no-such-stream' } }))
  assert.equal(cancelUnknown.status, 404)

  // 未鉴权访问流接口
  const unauth = await readJson(await api(gateway, null, '/api/stream/open', { method: 'POST', body: { endpoint: '$events', args: {} } }))
  assert.equal(unauth.status, 401)
  const unauthPoll = await readJson(await api(gateway, null, `/api/stream/poll?streamId=x&since=0&wait=0`))
  assert.equal(unauthPoll.status, 401)
})

test('反代模拟：任意 Host + X-Forwarded-Proto/For 下登录、RPC、长轮询都正常', async (t) => {
  const upstream = await startFakeUpstream()
  const { gateway } = await startTestGateway(t, { upstream, gatewayOptions: { hubOptions: { retryDelays: [20] } } })
  const proxyHeaders = {
    host: 'dsh.example.com',
    'x-forwarded-proto': 'https',
    'x-forwarded-for': '203.0.113.9',
    'cf-connecting-ip': '203.0.113.9',
  }
  const session = await loginReady(gateway, { headers: proxyHeaders })
  assert.equal(session.status, 200, '反代头下登录应成功')

  const rpc = await readJson(
    await api(gateway, session.json.token, '/api/rpc', {
      method: 'POST',
      headers: proxyHeaders,
      body: { method: 'session/list', args: { _request: {} } },
    }),
  )
  assert.equal(rpc.status, 200)
  assert.equal(rpc.json.ok, true)

  const streamId = await openStream(gateway, session.json.token, '$events', {})
  const polled = await poll(gateway, session.json.token, streamId, { since: 0, wait: 2000 })
  assert.equal(polled.status, 200)
  assert.equal(polled.json.items[0].value.type, 'ready')
  assert.equal(polled.json.items[0].value.clientId.length > 0, true)

  // X-Forwarded-For 参与登录限速：另一来源 IP 的第 6 次失败不会与 203.0.113.9 共用计数。
  for (let i = 0; i < 5; i++) {
    const bad = await loginReady(gateway, { password: 'bad-password', headers: { 'x-forwarded-for': '203.0.113.10' } })
    assert.equal(bad.status, 401)
  }
  const limited = await loginReady(gateway, { password: 'bad-password', headers: { 'x-forwarded-for': '203.0.113.10' } })
  assert.equal(limited.status, 429)
  const otherProxyIp = await loginReady(gateway, { password: 'bad-password', headers: { 'x-forwarded-for': '203.0.113.11' } })
  assert.equal(otherProxyIp.status, 401, '不同 X-Forwarded-For 来源应独立计数')
})


test('流归属：跨设备 poll/cancel 404、/api/streams 只列自己的流', async (t) => {
  const upstream = await startFakeUpstream()
  const { gateway } = await startTestGateway(t, { upstream, gatewayOptions: { hubOptions: { retryDelays: [20] } } })
  const a = await loginReady(gateway, { deviceName: 'Device-A' })
  const b = await loginReady(gateway, { deviceName: 'Device-B' })
  assert.equal(a.status, 200)
  assert.equal(b.status, 200)
  assert.notEqual(a.json.deviceId, b.json.deviceId)

  const streamA = await openStream(gateway, a.json.token, '$events', {})
  const aPoll = await poll(gateway, a.json.token, streamA, { since: 0, wait: 2000 })
  assert.equal(aPoll.status, 200)
  assert.equal(aPoll.json.items[0].value.type, 'ready')

  // 跨设备 poll / cancel：404 stream-not-found（不泄露存在性）
  const crossPoll = await poll(gateway, b.json.token, streamA, { since: 0, wait: 0 })
  assert.equal(crossPoll.status, 404)
  assert.equal(crossPoll.json.error, 'stream-not-found')
  const crossCancel = await readJson(await api(gateway, b.json.token, '/api/stream/cancel', { method: 'POST', body: { streamId: streamA } }))
  assert.equal(crossCancel.status, 404)

  // 内部 pending 流不可被任何设备访问
  const internalPoll = await poll(gateway, a.json.token, '$events', { since: 0, wait: 0 })
  assert.equal(internalPoll.status, 404)

  // /api/streams 只列该设备自己的流
  const aStreams = await readJson(await api(gateway, a.json.token, '/api/streams'))
  const bStreams = await readJson(await api(gateway, b.json.token, '/api/streams'))
  assert.equal(aStreams.status, 200)
  assert.ok(aStreams.json.items.some((entry) => entry.streamId === streamA))
  assert.equal(bStreams.json.items.some((entry) => entry.streamId === streamA), false)
  assert.ok(bStreams.json.items.every((entry) => entry.ownerDeviceId === b.json.deviceId))

  // owner 自己的流仍正常
  const stillOk = await poll(gateway, a.json.token, streamA, { since: 0, wait: 0 })
  assert.equal(stillOk.status, 200)
})

test('每设备流上限（注入 3）：超限 429，取消后可再开', async (t) => {
  const upstream = await startFakeUpstream()
  const { gateway } = await startTestGateway(t, {
    upstream,
    gatewayOptions: { hubOptions: { retryDelays: [20], maxStreamsPerDevice: 3 } },
  })
  const session = await loginReady(gateway)
  const streamIds = []
  for (let i = 0; i < 3; i++) streamIds.push(await openStream(gateway, session.json.token, '$events', {}))
  const overLimit = await readJson(await api(gateway, session.json.token, '/api/stream/open', { method: 'POST', body: { endpoint: '$events', args: {} } }))
  assert.equal(overLimit.status, 429)
  assert.equal(overLimit.json.error, 'too-many-streams')
  // 取消一个后可再开
  const cancelled = await readJson(await api(gateway, session.json.token, '/api/stream/cancel', { method: 'POST', body: { streamId: streamIds[0] } }))
  assert.equal(cancelled.status, 200)
  const reopened = await readJson(await api(gateway, session.json.token, '/api/stream/open', { method: 'POST', body: { endpoint: '$events', args: {} } }))
  assert.equal(reopened.status, 200)
})

test('撤销设备 / 登出关闭该设备名下所有流', async (t) => {
  const upstream = await startFakeUpstream()
  const { gateway } = await startTestGateway(t, { upstream, gatewayOptions: { hubOptions: { retryDelays: [20] } } })
  const a = await loginReady(gateway, { deviceName: 'Revoked-A' })
  const b = await loginReady(gateway, { deviceName: 'Revoker-B' })
  const streamA = await openStream(gateway, a.json.token, '$events', {})
  await poll(gateway, a.json.token, streamA, { since: 0, wait: 1000 })
  assert.equal(gateway.streams.list({ ownerDeviceId: a.json.deviceId }).length, 1)

  const revoked = await readJson(await api(gateway, b.json.token, `/api/devices/${a.json.deviceId}`, { method: 'DELETE' }))
  assert.equal(revoked.status, 200)
  assert.equal(gateway.streams.list({ ownerDeviceId: a.json.deviceId }).length, 0, '撤销设备应关闭其名下流')
  assert.equal((await readJson(await api(gateway, a.json.token, '/api/me'))).status, 401, '撤销后 token 失效')

  // 登出同样关闭本设备流
  const c = await loginReady(gateway, { deviceName: 'Logout-C' })
  const streamC = await openStream(gateway, c.json.token, '$events', {})
  await poll(gateway, c.json.token, streamC, { since: 0, wait: 1000 })
  assert.equal(gateway.streams.list({ ownerDeviceId: c.json.deviceId }).length, 1)
  const loggedOut = await readJson(await api(gateway, c.json.token, '/api/logout', { method: 'POST' }))
  assert.equal(loggedOut.status, 200)
  assert.equal(gateway.streams.list({ ownerDeviceId: c.json.deviceId }).length, 0, '登出应关闭本设备流')
  assert.equal((await readJson(await api(gateway, c.json.token, '/api/me'))).status, 401)
})


async function wsConnect(gateway, token) {
  const ticketResponse = await readJson(await api(gateway, token, '/api/ws-ticket'))
  assert.equal(ticketResponse.status, 200)
  const ticket = ticketResponse.json.ticket
  const ws = await connectWebSocket(`ws://127.0.0.1:${gateway.port}/api/ws?ticket=${encodeURIComponent(ticket)}`, {
    timeoutMs: 3000,
  })
  return { ws, ticket }
}

function waitWsClose(ws, timeout = 3000) {
  return new Promise((resolve, reject) => {
    const timer = setTimeout(() => {
      try {
        ws.terminate()
      } catch {
        /* ignore */
      }
      reject(new Error('timeout waiting ws close'))
    }, timeout)
    ws.once('close', (info) => {
      clearTimeout(timer)
      resolve(info)
    })
  })
}

function collectWsMessages(ws) {
  const messages = []
  ws.on('text', (text) => {
    try {
      messages.push(JSON.parse(text))
    } catch {
      /* ignore */
    }
  })
  return messages
}

test('撤销设备：关闭已建立 /api/ws（4401），旧 ticket 不能再握手', async (t) => {
  const upstream = await startFakeUpstream()
  const { gateway } = await startTestGateway(t, { upstream, gatewayOptions: { hubOptions: { retryDelays: [20] } } })
  const a = await loginReady(gateway, { deviceName: 'WS-A' })
  const b = await loginReady(gateway, { deviceName: 'WS-B' })
  const { ws, ticket } = await wsConnect(gateway, a.json.token)
  t.after(() => ws.terminate())
  const messages = collectWsMessages(ws)
  ws.sendText(JSON.stringify({ type: 'open', streamId: 's1', endpoint: '$events', payload: { args: {} } }))
  await waitFor(() => messages.find((message) => message.type === 'item' && message.value.type === 'ready'), {
    timeout: 3000,
    message: 'ws ready',
  })

  const closedPromise = waitWsClose(ws)
  const revoked = await readJson(await api(gateway, b.json.token, `/api/devices/${a.json.deviceId}`, { method: 'DELETE' }))
  assert.equal(revoked.status, 200)
  const info = await closedPromise
  assert.equal(info.code, 4401, '撤销设备后已建立的 WS 应被 4401 关闭')

  // 旧 ticket 再握手：设备已不存在 → 401
  await assert.rejects(
    connectWebSocket(`ws://127.0.0.1:${gateway.port}/api/ws?ticket=${encodeURIComponent(ticket)}`, { timeoutMs: 2000 }),
    /ws-upgrade-rejected: 401/,
  )
})

test('登出：关闭该设备已建立 /api/ws（4401）', async (t) => {
  const upstream = await startFakeUpstream()
  const { gateway } = await startTestGateway(t, { upstream, gatewayOptions: { hubOptions: { retryDelays: [20] } } })
  const a = await loginReady(gateway, { deviceName: 'WS-Logout' })
  const { ws } = await wsConnect(gateway, a.json.token)
  t.after(() => ws.terminate())
  const messages = collectWsMessages(ws)
  ws.sendText(JSON.stringify({ type: 'open', streamId: 's1', endpoint: '$events', payload: { args: {} } }))
  await waitFor(() => messages.find((message) => message.type === 'item' && message.value.type === 'ready'), {
    timeout: 3000,
    message: 'ws ready',
  })

  const closedPromise = waitWsClose(ws)
  const loggedOut = await readJson(await api(gateway, a.json.token, '/api/logout', { method: 'POST' }))
  assert.equal(loggedOut.status, 200)
  const info = await closedPromise
  assert.equal(info.code, 4401)
})

test('WS open 时复查设备：凭据已撤销则拒绝 open 并关闭（4401）', async (t) => {
  const upstream = await startFakeUpstream()
  const { gateway } = await startTestGateway(t, { upstream, gatewayOptions: { hubOptions: { retryDelays: [20] } } })
  const a = await loginReady(gateway, { deviceName: 'WS-Race' })
  const { ws } = await wsConnect(gateway, a.json.token)
  t.after(() => ws.terminate())
  const messages = collectWsMessages(ws)

  // 直接撤销（绕过 HTTP 路由，模拟 ticket 签发后、open 前被撤销的竞态）
  assert.equal(gateway.auth.revokeDevice(a.json.deviceId), true)
  const closedPromise = waitWsClose(ws)
  ws.sendText(JSON.stringify({ type: 'open', streamId: 'late', endpoint: '$events', payload: { args: {} } }))
  const error = await waitFor(
    () => messages.find((message) => message.type === 'error' && message.streamId === 'late'),
    { timeout: 3000, message: 'unauthorized error frame' },
  )
  assert.equal(error.error.code, 'unauthorized')
  const info = await closedPromise
  assert.equal(info.code, 4401)
})
