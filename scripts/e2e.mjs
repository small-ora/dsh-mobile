#!/usr/bin/env node
/**
 * E2E 验收脚本：对隔离运行的 DSH + dsh-mobile 插件/网关做黑盒验收。
 *
 * 前置（由 Lead/CI 准备，不在本脚本内启动）：
 *   DSH_HOME=/root/.dsh-dev dsh web --profile web --host 127.0.0.1 --port 3091 --no-open
 *   DSH_MOBILE_CONFIG_DIR=/root/.dsh-dev/.dsh-mobile DSH_MOBILE_GATEWAY_HOST=127.0.0.1 \
 *   DSH_MOBILE_GATEWAY_PORT=8790 dsh plugin --profile web add dist/dsh-mobile-plugin-0.1.0.tgz
 *
 * 用法：
 *   E2E_PASSWORD=... node scripts/e2e.mjs
 *   或从 $DSH_MOBILE_CONFIG_DIR/initial-password.txt 读取
 *
 * 验证：AC2/AC3/AC4/AC5/AC6/AC12（见 SPEC.md）。
 */
import { readFileSync, writeFileSync, mkdtempSync, rmSync } from 'node:fs'
import { createHash } from 'node:crypto'
import { tmpdir, homedir } from 'node:os'
import { join } from 'node:path'

const BASE = (process.env.E2E_BASE || 'http://127.0.0.1:8790').replace(/\/+$/, '')
const CONFIG_DIR = process.env.DSH_MOBILE_CONFIG_DIR || join(homedir(), '.dsh-mobile')
const results = []
let token = ''
let password = process.env.E2E_PASSWORD || ''
let clientId = ''

function pass(name, detail = '') { results.push({ name, ok: true, detail }); console.log(`PASS ${name}${detail ? ' — ' + detail : ''}`) }
function fail(name, detail = '') { results.push({ name, ok: false, detail }); console.log(`FAIL ${name}${detail ? ' — ' + detail : ''}`) }
function check(name, cond, detail = '') { cond ? pass(name, detail) : fail(name, detail); return cond }

async function req(path, { method = 'GET', body, headers = {}, timeout = 20000 } = {}) {
  const response = await fetch(BASE + path, {
    method,
    headers: {
      ...(body === undefined ? {} : { 'content-type': 'application/json' }),
      ...(token ? { authorization: 'Bearer ' + token } : {}),
      ...headers,
    },
    ...(body === undefined ? {} : { body: typeof body === 'string' ? body : JSON.stringify(body) }),
    signal: AbortSignal.timeout(timeout),
  })
  const text = await response.text()
  let json = null
  try { json = JSON.parse(text) } catch {}
  return { status: response.status, json, text }
}

function readPassword() {
  if (password) return password
  try {
    password = readFileSync(join(CONFIG_DIR, 'initial-password.txt'), 'utf8').trim()
  } catch {
    password = ''
  }
  return password
}

async function stepHealth() {
  const r = await req('/health', { headers: { host: 'example.com', 'x-forwarded-proto': 'https', 'x-forwarded-for': '203.0.113.9' } })
  check('AC6/健康检查(反代 Host/X-Forwarded)', r.status === 200 && r.json?.ok === true, `status=${r.status} service=${r.json?.service}`)
  const r2 = await req('/api/rpc', { method: 'POST', body: { method: 'session/list', args: { _request: {} } } })
  check('AC2/未登录 RPC → 401', r2.status === 401, `status=${r2.status}`)
}

async function stepLogin() {
  const pwd = readPassword()
  if (!pwd) { fail('登录', '缺少口令（E2E_PASSWORD 或 initial-password.txt）'); return false }
  const bad = await req('/api/login', { method: 'POST', body: { password: 'definitely-wrong', deviceName: 'e2e' } })
  check('AC2/错误口令 → 401', bad.status === 401, `status=${bad.status}`)
  const ok = await req('/api/login', { method: 'POST', body: { password: pwd, deviceName: 'e2e-runner' } })
  if (!check('AC2/正确口令登录', ok.status === 200 && typeof ok.json?.token === 'string', `status=${ok.status}`)) return false
  token = ok.json.token
  const me = await req('/api/me')
  check('AC2/设备 token 可用', me.status === 200 && !!me.json?.device?.id, `device=${me.json?.device?.id}`)

  // mustChangePassword 服务端强制：改密前业务接口 403，改密后恢复
  if (ok.json.mustChangePassword === true) {
    const blocked = await req('/api/rpc', { method: 'POST', body: { method: 'session/list', args: { _request: {} } } })
    check('AC2/首次登录未改密 → 业务接口 403 password-change-required', blocked.status === 403 && blocked.json?.error === 'password-change-required', `status=${blocked.status}`)
    const next = 'e2e-' + Buffer.from(String(Date.now())).toString('base64url').slice(0, 10) + 'Aa1'
    const changed = await req('/api/password', { method: 'POST', body: { current: pwd, next } })
    if (!check('AC2/首次登录强制改密', changed.status === 200 && changed.json?.ok === true, `status=${changed.status}`)) return false
    password = next
    const after = await req('/api/rpc', { method: 'POST', body: { method: 'session/list', args: { _request: {} } } })
    check('AC2/改密后业务接口恢复', after.status === 200 && after.json?.ok === true, `status=${after.status}`)
  }
  return true
}

async function stepRpc() {
  const list = await req('/api/rpc', { method: 'POST', body: { method: 'session/list', args: { _request: {} } } })
  const items = list.json?.value?.items
  check('AC3/session/list 返回真实上游数据', list.status === 200 && list.json?.ok === true && Array.isArray(items), `items=${Array.isArray(items) ? items.length : 'n/a'}`)
  const catalog = await req('/api/rpc', { method: 'POST', body: { method: 'session/modelCatalog', args: {} } })
  check('AC3/session/modelCatalog', catalog.status === 200 && catalog.json?.ok === true, `groups=${catalog.json?.value?.groups?.length ?? 'n/a'}`)
  const denied = await req('/api/rpc', { method: 'POST', body: { method: 'terminal/create', args: {} } })
  check('AC2/白名单外方法 → 403', denied.status === 403, `status=${denied.status}`)
  return items || []
}

async function openStream(endpoint, args) {
  const r = await req('/api/stream/open', { method: 'POST', body: { endpoint, args } })
  if (r.status !== 200 || !r.json?.streamId) throw new Error(`stream open failed: ${r.status} ${r.text.slice(0, 200)}`)
  return r.json.streamId
}

async function poll(streamId, { since = 0, wait = 15000, until, budgetMs = 20000 } = {}) {
  const deadline = Date.now() + budgetMs
  let cursor = since
  const seen = []
  while (Date.now() < deadline) {
    const r = await req(`/api/stream/poll?streamId=${encodeURIComponent(streamId)}&since=${cursor}&wait=${wait}`, { timeout: wait + 10000 })
    if (r.status !== 200) throw new Error(`poll failed: ${r.status}`)
    cursor = r.json.next ?? cursor
    for (const item of r.json.items || []) seen.push(item)
    if (until && until(seen, r.json)) return { seen, next: cursor, last: r.json }
    if (r.json.closed) return { seen, next: cursor, last: r.json }
  }
  return { seen, next: cursor, last: null }
}

async function stepStreams(sessions) {
  const eventsId = await openStream('$events', {})
  const events = await poll(eventsId, { until: (items) => items.some((i) => i.value?.type === 'ready') })
  const ready = events.seen.find((i) => i.value?.type === 'ready')
  check('AC4/$events 流收到 ready', !!ready, ready?.value?.clientId ? `clientId=${String(ready.value.clientId).slice(0, 8)}…` : '')
  clientId = ready?.value?.clientId || ''
  if (!clientId) { await req('/api/stream/cancel', { method: 'POST', body: { streamId: eventsId } }); return }

  // 找一个（或新建）会话，验证 session/follow 快照
  let sessionId = sessions.find((s) => s && !s.blank && !s.parentSessionId)?.sessionId
  if (!sessionId) {
    const created = await req('/api/rpc', { method: 'POST', body: { method: 'session/create', args: { request: {} } } })
    sessionId = created.json?.value?.sessionId
  }
  if (!sessionId) { fail('AC4/session/follow', '无法获得会话'); return }
  const followId = await openStream('session/follow', { request: { address: { kind: 'session', sessionId }, assistantStream: true } })
  const follow = await poll(followId, { until: (items) => items.some((i) => i.value?.type === 'snapshot'), budgetMs: 25000 })
  const snap = follow.seen.find((i) => i.value?.type === 'snapshot')
  check('AC4/session/follow 收到 snapshot', !!snap, snap ? `cursor=${snap.value.cursor} records=${snap.value.records?.length}` : '')
  await req('/api/stream/cancel', { method: 'POST', body: { streamId: followId } }).catch(() => {})
  await req('/api/stream/cancel', { method: 'POST', body: { streamId: eventsId } }).catch(() => {})
}

async function stepPending() {
  const r = await req('/api/pending')
  check('AC/后台待处理接口可用', r.status === 200 && Array.isArray(r.json?.items), `items=${r.json?.items?.length ?? 'n/a'}`)
}

async function stepFiles() {
  const dir = mkdtempSync(join(homedir(), '.dsh-mobile-e2e-'))
  const name = 'e2e-sample.txt'
  const content = 'dsh-mobile e2e ' + Date.now()
  writeFileSync(join(dir, name), content)
  const sha = createHash('sha256').update(content).digest('hex')
  const listing = await req(`/api/files?path=${encodeURIComponent(dir)}`)
  const found = (listing.json?.entries || []).find((e) => e.name === name)
  check('AC5/文件列表', listing.status === 200 && !!found, `entries=${listing.json?.entries?.length ?? 0}`)
  const filePath = join(dir, name)
  const download = await fetch(BASE + '/api/files/download?path=' + encodeURIComponent(filePath), { headers: { authorization: 'Bearer ' + token } })
  const bytes = Buffer.from(await download.arrayBuffer())
  const got = createHash('sha256').update(bytes).digest('hex')
  check('AC5/下载内容 SHA-256 一致', download.status === 200 && got === sha, `sha256=${got.slice(0, 12)}…`)
  const uploadedName = 'e2e-uploaded.txt'
  const upBody = Buffer.from('uploaded ' + Date.now())
  const up = await req(`/api/files/upload?path=${encodeURIComponent(dir)}&name=${uploadedName}`, { method: 'POST', body: upBody, headers: { 'content-type': 'application/octet-stream' } })
  check('AC5/上传', up.status === 201, `status=${up.status}`)
  const traversal = await req('/api/files/download?path=' + encodeURIComponent('/etc/passwd'))
  check('AC5/越权路径 → 403', traversal.status === 403, `status=${traversal.status}`)
  rmSync(dir, { recursive: true, force: true })
}

async function main() {
  console.log(`E2E base=${BASE} config=${CONFIG_DIR}`)
  try {
    await stepHealth()
    if (!(await stepLogin())) throw new Error('login failed')
    const sessions = await stepRpc()
    await stepStreams(sessions)
    await stepPending()
    await stepFiles()
  } catch (error) {
    fail('E2E 异常终止', error?.message || String(error))
  }
  const failed = results.filter((r) => !r.ok)
  console.log(`\n${results.length - failed.length}/${results.length} checks passed`)
  if (failed.length) { console.log('failed:', failed.map((f) => f.name).join('; ')); process.exit(1) }
}

main()
