/**
 * tests/integration.test.js — 端到端：登录 → RPC → 长轮询流 → 审批/提问 pending/respond（+ 可选 WS）。
 * 对应 SPEC v3 AC1（健康）/AC3（RPC）/AC4（流）/AC6（通道兼容）在假上游上的可重复证据。
 */
import test from 'node:test'
import assert from 'node:assert/strict'
import { connectWebSocket } from '../gateway/ws.mjs'
import { api, loginReady, readJson, sleep, startFakeUpstream, startTestGateway, waitFor } from './helpers.mjs'

const WATERFALL_APPROVAL = {
  type: 'waterfall',
  event: 'approval/request',
  eventId: 'evt-approval-1',
  agentId: 's1',
  request: { toolName: 'shell', reason: '执行 npm test' },
}

async function pollStream(gateway, token, streamId, { since = 0, wait = 2000 } = {}) {
  return readJson(await api(gateway, token, `/api/stream/poll?streamId=${encodeURIComponent(streamId)}&since=${since}&wait=${wait}`))
}

test('E2E：login → rpc(session/list) → 长轮询 $events(ready) / session/follow(snapshot) → pending/respond', async (t) => {
  const upstream = await startFakeUpstream({
    eventsAfterReady: [WATERFALL_APPROVAL],
    rpcHandlers: {
      'session/list': () => ({
        ok: true,
        value: { items: [{ id: 's1', title: 'E2E 会话', running: false }] },
      }),
      'session/modelCatalog': () => ({ ok: true, value: { providers: [{ id: 'deepseek', models: [] }] } }),
      '$events/result': () => ({ ok: true, value: { accepted: true } }),
    },
  })
  const { gateway } = await startTestGateway(t, {
    upstream,
    gatewayOptions: { hubOptions: { retryDelays: [20] } },
  })

  // 1) 健康检查（免鉴权，无隧道字段）
  const health = await readJson(await fetch(`${gateway.url}/health`))
  assert.equal(health.status, 200)
  assert.equal(health.json.ok, true)
  assert.equal(health.json.service, 'dsh-mobile')

  // 2) 登录
  const session = await loginReady(gateway, { deviceName: 'E2E Device' })
  assert.equal(session.status, 200)
  const token = session.json.token
  assert.match(token, /^[A-Za-z0-9_-]{43}$/)

  // 3) RPC：session/list / session/modelCatalog 拿到假上游真实数据
  const list = await readJson(await api(gateway, token, '/api/rpc', { method: 'POST', body: { method: 'session/list', args: { _request: {} } } }))
  assert.equal(list.status, 200)
  assert.equal(list.json.ok, true)
  assert.equal(list.json.value.items[0].title, 'E2E 会话')

  const catalog = await readJson(await api(gateway, token, '/api/rpc', { method: 'POST', body: { method: 'session/modelCatalog', args: {} } }))
  assert.equal(catalog.status, 200)
  assert.equal(catalog.json.value.providers[0].id, 'deepseek')

  // 上游健康状态在 /health 中体现
  const healthy = await waitFor(async () => {
    const response = await readJson(await fetch(`${gateway.url}/health`))
    return response.json.upstream.ok ? response.json : null
  }, { timeout: 3000, message: 'upstream healthy' })
  assert.equal(healthy.upstream.ok, true)

  // 4) 长轮询：open $events → ready
  const eventsOpen = await readJson(await api(gateway, token, '/api/stream/open', { method: 'POST', body: { endpoint: '$events', args: {} } }))
  assert.equal(eventsOpen.status, 200)
  const eventsStreamId = eventsOpen.json.streamId
  const ready = await pollStream(gateway, token, eventsStreamId, { since: 0, wait: 3000 })
  assert.equal(ready.status, 200)
  const readyItems = ready.json.items.filter((item) => item.value.type === 'ready')
  assert.equal(readyItems.length, 1)
  assert.equal(typeof readyItems[0].value.clientId, 'string')

  // 5) 长轮询：open session/follow → snapshot
  const followArgs = { request: { address: { kind: 'session', sessionId: 's1' }, assistantStream: true } }
  const followOpen = await readJson(
    await api(gateway, token, '/api/stream/open', { method: 'POST', body: { endpoint: 'session/follow', args: followArgs } }),
  )
  assert.equal(followOpen.status, 200)
  const snapshotPoll = await pollStream(gateway, token, followOpen.json.streamId, { since: 0, wait: 3000 })
  assert.equal(snapshotPoll.status, 200)
  const snapshot = snapshotPoll.json.items.find((item) => item.value.type === 'snapshot')
  assert.ok(snapshot, 'session/follow 首个 value 应为 snapshot')
  assert.equal(snapshot.value.hasMore, false)
  assert.ok(Array.isArray(snapshot.value.records))

  // 6) pending：内部 $events 订阅收到 waterfall → /api/pending 可见
  const pending = await waitFor(async () => {
    const response = await readJson(await api(gateway, token, '/api/pending'))
    const item = response.json.items.find((entry) => entry.eventId === WATERFALL_APPROVAL.eventId)
    return item ? { response, item } : null
  }, { timeout: 4000, message: 'pending approval waterfall' })
  assert.equal(pending.item.kind, 'approval')
  assert.equal(pending.item.sessionId, 's1')
  assert.equal(pending.item.toolName, 'shell')
  assert.equal(pending.item.reason, '执行 npm test')

  // 7) respond：outcome 为字符串（审批），网关包成 $events/result
  const responded = await readJson(
    await api(gateway, token, '/api/respond', { method: 'POST', body: { eventId: WATERFALL_APPROVAL.eventId, outcome: 'allowed-once' } }),
  )
  assert.equal(responded.status, 200)
  assert.equal(responded.json.accepted, true)
  const resultCall = upstream.lastRpc('$events/result')
  assert.ok(resultCall, '网关应调用上游 $events/result')
  assert.equal(resultCall.body.method, '$events/result')
  const args = resultCall.body.payload.args
  assert.match(args.clientId, /^fake-client-\d+$/)
  assert.equal(args.eventId, 'evt-approval-1')
  assert.deepEqual(args.outcome, { kind: 'result', value: 'allowed-once' })
  // 已应答的 pending 被移除
  const afterRespond = await readJson(await api(gateway, token, '/api/pending'))
  assert.equal(afterRespond.json.items.some((entry) => entry.eventId === 'evt-approval-1'), false)

  // 8) 提问：outcome 为对象 {answers:[...]}，同样透传
  upstream.pushAll({
    type: 'waterfall',
    event: 'question/request',
    eventId: 'evt-question-1',
    agentId: 's1',
    request: { questions: [{ id: 'q1', question: '继续？', options: [{ label: '是' }], multiSelect: false }] },
  })
  const questionPending = await waitFor(async () => {
    const response = await readJson(await api(gateway, token, '/api/pending'))
    return response.json.items.find((entry) => entry.eventId === 'evt-question-1') || null
  }, { timeout: 3000, message: 'pending question waterfall' })
  assert.equal(questionPending.kind, 'question')
  assert.ok(Array.isArray(questionPending.questions))
  const answers = { answers: [{ id: 'q1', selected: ['是'] }] }
  const answered = await readJson(await api(gateway, token, '/api/respond', { method: 'POST', body: { eventId: 'evt-question-1', outcome: answers } }))
  assert.equal(answered.json.accepted, true)
  assert.deepEqual(upstream.lastRpc('$events/result').body.payload.args.outcome, { kind: 'result', value: answers })

  // 9) 可选 WS 实时通道：ticket → 101 → open $events → ready
  const ticketResponse = await readJson(await api(gateway, token, '/api/ws-ticket'))
  assert.equal(ticketResponse.status, 200)
  assert.equal(typeof ticketResponse.json.ticket, 'string')
  const wsUrl = `ws://127.0.0.1:${gateway.port}/api/ws?ticket=${encodeURIComponent(ticketResponse.json.ticket)}`
  const ws = await connectWebSocket(wsUrl, { timeoutMs: 3000 })
  t.after(() => ws.terminate())
  const wsMessages = []
  ws.on('text', (text) => wsMessages.push(JSON.parse(text)))
  ws.sendText(JSON.stringify({ type: 'open', streamId: 'app-events', endpoint: '$events', payload: { args: {} } }))
  const wsReady = await waitFor(() => wsMessages.find((message) => message.type === 'item' && message.streamId === 'app-events' && message.value.type === 'ready'), {
    timeout: 3000,
    message: 'ws ready item',
  })
  assert.equal(wsReady.value.type, 'ready')
  // 非法 endpoint 会拿到 error 帧
  ws.sendText(JSON.stringify({ type: 'open', streamId: 'bad', endpoint: 'session/other' }))
  const wsError = await waitFor(() => wsMessages.find((message) => message.type === 'error' && message.streamId === 'bad'), {
    timeout: 2000,
    message: 'ws error frame',
  })
  assert.equal(wsError.error.code, 'method-not-allowed')
  ws.sendText(JSON.stringify({ type: 'cancel', streamId: 'app-events' }))
  await sleep(30)
  assert.ok(wsMessages.length > 0)
})
