/**
 * gateway/auth.mjs — 口令（scrypt）与设备 token（仅存 SHA-256）鉴权。
 *
 * 凭据模型见 SPEC v2 §5.1 与 docs/interfaces.md §2.1/§7：
 *  - 管理口令：scrypt(N=16384, r=8, p=1)，salt 随机 16B；
 *  - 设备 token：32B 随机 → base64url（43 字符），服务端只存 SHA-256；
 *  - token 30 天滑动过期；可撤销、可命名；登录成功时更新 lastSeenAt；
 *  - 登录限速：同 IP 每分钟 5 次；连续 10 次失败锁 15 分钟；
 *  - WS ticket：HMAC-SHA256，30 秒，不落盘。
 *
 * 本模块不读写文件：状态挂在传入的 config 对象上，持久化由调用方的 persist() 负责。
 */
import crypto from 'node:crypto'
import { promisify } from 'node:util'

export const SCRYPT_PARAMS = Object.freeze({ N: 16384, r: 8, p: 1, keylen: 64 })
export const DEFAULT_DEVICE_TTL_MS = 30 * 24 * 60 * 60 * 1000
export const DEFAULT_TICKET_TTL_MS = 30_000
export const MIN_PASSWORD_LENGTH = 8
export const MAX_PASSWORD_LENGTH = 256

function scrypt(password, salt, params = SCRYPT_PARAMS) {
  return crypto.scryptSync(String(password), salt, params.keylen, {
    N: params.N,
    r: params.r,
    p: params.p,
    maxmem: 128 * params.N * params.r * 2,
  })
}

const scryptCallback = promisify(crypto.scrypt)

/** 异步 scrypt（libuv 线程池，不阻塞事件循环；登录/改密等运行期路径使用）。 */
function scryptAsync(password, salt, params = SCRYPT_PARAMS) {
  return scryptCallback(String(password), salt, params.keylen, {
    N: params.N,
    r: params.r,
    p: params.p,
    maxmem: 128 * params.N * params.r * 2,
  })
}

/** 生成 scrypt 口令记录（salt/hash 为 base64）。 */
export function hashPassword(password, { salt, params = SCRYPT_PARAMS } = {}) {
  if (typeof password !== 'string') throw new TypeError('password must be a string')
  const saltBuf = salt || crypto.randomBytes(16)
  const hashBuf = scrypt(password, saltBuf, params)
  return {
    algo: 'scrypt',
    N: params.N,
    r: params.r,
    p: params.p,
    salt: saltBuf.toString('base64'),
    hash: hashBuf.toString('base64'),
  }
}

/** 常量时间校验口令；记录损坏/算法未知一律返回 false。 */
export function verifyPassword(password, record) {
  if (typeof password !== 'string' || !record || record.algo !== 'scrypt' || !record.salt || !record.hash) return false
  try {
    const expected = Buffer.from(record.hash, 'base64')
    const params = {
      keylen: expected.length || SCRYPT_PARAMS.keylen,
      N: Number(record.N) || SCRYPT_PARAMS.N,
      r: Number(record.r) || SCRYPT_PARAMS.r,
      p: Number(record.p) || SCRYPT_PARAMS.p,
    }
    const actual = scrypt(password, Buffer.from(record.salt, 'base64'), params)
    return actual.length === expected.length && crypto.timingSafeEqual(actual, expected)
  } catch {
    return false
  }
}

/** 异步版本：生成 scrypt 口令记录（运行期改密用，不阻塞事件循环）。 */
export async function hashPasswordAsync(password, { salt, params = SCRYPT_PARAMS } = {}) {
  if (typeof password !== 'string') throw new TypeError('password must be a string')
  const saltBuf = salt || crypto.randomBytes(16)
  const hashBuf = await scryptAsync(password, saltBuf, params)
  return {
    algo: 'scrypt',
    N: params.N,
    r: params.r,
    p: params.p,
    salt: saltBuf.toString('base64'),
    hash: hashBuf.toString('base64'),
  }
}

/** 异步版本：常量时间校验口令（登录/改密用）。 */
export async function verifyPasswordAsync(password, record) {
  if (typeof password !== 'string' || !record || record.algo !== 'scrypt' || !record.salt || !record.hash) return false
  try {
    const expected = Buffer.from(record.hash, 'base64')
    const params = {
      keylen: expected.length || SCRYPT_PARAMS.keylen,
      N: Number(record.N) || SCRYPT_PARAMS.N,
      r: Number(record.r) || SCRYPT_PARAMS.r,
      p: Number(record.p) || SCRYPT_PARAMS.p,
    }
    const actual = await scryptAsync(password, Buffer.from(record.salt, 'base64'), params)
    return actual.length === expected.length && crypto.timingSafeEqual(actual, expected)
  } catch {
    return false
  }
}

/** 常量时间字符串比较（长度不同直接 false）。 */
export function timingSafeEqualString(a, b) {
  if (typeof a !== 'string' || typeof b !== 'string') return false
  const bufA = Buffer.from(a, 'utf8')
  const bufB = Buffer.from(b, 'utf8')
  if (bufA.length !== bufB.length) return false
  return crypto.timingSafeEqual(bufA, bufB)
}

export function hashToken(token) {
  return crypto.createHash('sha256').update(String(token || ''), 'utf8').digest('hex')
}

/** 新设备 token：32B 随机 → base64url（43 字符）；config 只存 hash。 */
export function newDeviceToken() {
  const token = crypto.randomBytes(32).toString('base64url')
  return { token, tokenHash: hashToken(token) }
}

export function validatePasswordStrength(password) {
  if (typeof password !== 'string') return { ok: false, error: 'password-invalid' }
  if (password.length < MIN_PASSWORD_LENGTH) return { ok: false, error: 'password-too-short' }
  if (password.length > MAX_PASSWORD_LENGTH) return { ok: false, error: 'password-too-long' }
  return { ok: true }
}

/**
 * 鉴权服务。config 为 ~/.dsh-mobile/config.json 的内存对象（持久化由 persist 回调负责）。
 * now/secret 可注入，便于测试。
 */
export class AuthService {
  constructor({
    config,
    persist = () => {},
    now = Date.now,
    secret = crypto.randomBytes(32),
    limits = {},
  }) {
    if (!config || typeof config !== 'object') throw new TypeError('config is required')
    this.config = config
    this.persist = persist
    this.now = typeof now === 'function' ? now : () => now
    this.secret = secret
    this.windowMs = limits.windowMs ?? 60_000
    this.maxPerWindow = limits.maxPerWindow ?? 5
    this.maxFailures = limits.maxFailures ?? 10
    this.lockMs = limits.lockMs ?? 15 * 60 * 1000
    this.ticketTtlMs = limits.ticketTtlMs ?? DEFAULT_TICKET_TTL_MS
    this.deviceTtlMs = limits.deviceTtlMs ?? DEFAULT_DEVICE_TTL_MS
    this.maxDevices = limits.maxDevices ?? 50
    this.attempts = new Map() // ip -> {windowStart, count}
    this.locks = new Map() // ip -> {count, until}
    this._persistAt = new Map() // deviceId -> last persisted ms
    this._passwordInFlight = new Set() // deviceId -> 改密进行中（重入保护）
  }

  get devices() {
    return Array.isArray(this.config.devices) ? this.config.devices : (this.config.devices = [])
  }

  get mustChangePassword() {
    return this.config.mustChangePassword === true
  }

  /**
   * 限速/锁定预检查（登录与改密共用同一套 attempts/locks，按客户端 IP 分桶）。
   * 只统计“凭据校验失败”次数：成功登录不消耗配额，失败第 6 次/分钟 → 429。
   */
  _precheck(ip) {
    const now = this.now()
    const lock = this.locks.get(ip)
    if (lock?.until > now) return { ok: false, status: 423, error: 'locked', until: lock.until }
    // 只清理“已过期的锁定”；失败计数记录（until=0）必须保留，否则连续失败永远累计不到 10。
    if (lock && lock.until > 0 && lock.until <= now) this.locks.delete(ip)
    const win = this.attempts.get(ip)
    if (win && now - win.windowStart < this.windowMs && win.count >= this.maxPerWindow) {
      return { ok: false, status: 429, error: 'rate-limited', retryAfterMs: Math.max(0, win.windowStart + this.windowMs - now) }
    }
    return null
  }

  /** 记录一次凭据校验失败（登录/改密共用）；达到 10 次连续失败则锁定 15 分钟。 */
  _recordAuthFailure(ip) {
    const now = this.now()
    this._countAttempt(ip, now)
    const state = this.locks.get(ip) || { count: 0, until: 0 }
    state.count += 1
    if (state.count >= this.maxFailures) {
      state.until = now + this.lockMs
      state.count = 0
      this.locks.set(ip, state)
      return { ok: false, status: 423, error: 'locked', until: state.until }
    }
    this.locks.set(ip, state)
    return { ok: false, status: 401, error: 'invalid-password' }
  }

  /** 登录：成功返回 {ok:true, token, device}；失败返回 {ok:false, status, error, ...}。 */
  async login({ password, deviceName, platform = 'android', ip = 'unknown' } = {}) {
    const guard = this._precheck(ip)
    if (guard) return guard

    const passwordOk = await verifyPasswordAsync(password, this.config.password)
    if (!passwordOk) return this._recordAuthFailure(ip)

    this.locks.delete(ip)
    const now = this.now()
    const { token, tokenHash } = newDeviceToken()
    const device = {
      id: crypto.randomUUID(),
      name: sanitizeDeviceName(deviceName),
      platform: String(platform || 'android').slice(0, 32),
      tokenHash,
      createdAt: now,
      lastSeenAt: now,
    }
    this.devices.push(device)
    // 上限保护：超限时淘汰最久未使用的设备（仍属于用户可见的设备，下次列表可发现被移除）。
    while (this.devices.length > this.maxDevices) {
      let oldest = 0
      for (let i = 1; i < this.devices.length; i++) {
        if ((this.devices[i].lastSeenAt || 0) < (this.devices[oldest].lastSeenAt || 0)) oldest = i
      }
      this.devices.splice(oldest, 1)
    }
    this.persist()
    return { ok: true, token, device: publicDevice(device) }
  }

  _countAttempt(ip, now) {
    let win = this.attempts.get(ip)
    if (!win || now - win.windowStart >= this.windowMs) {
      win = { windowStart: now, count: 0 }
      this.attempts.set(ip, win)
    }
    win.count += 1
    if (win.count > this.maxPerWindow) {
      return { limited: true, retryAfterMs: Math.max(0, win.windowStart + this.windowMs - now) }
    }
    return { limited: false }
  }

  /** 用设备 token 换设备记录；滑动更新 lastSeenAt（节流持久化）。 */
  authenticate(token) {
    if (typeof token !== 'string' || token.length < 16 || token.length > 512) return null
    const digest = hashToken(token)
    const now = this.now()
    const devices = this.devices
    for (let i = 0; i < devices.length; i++) {
      const device = devices[i]
      if (typeof device.tokenHash !== 'string' || device.tokenHash.length !== digest.length) continue
      if (!crypto.timingSafeEqual(Buffer.from(device.tokenHash, 'utf8'), Buffer.from(digest, 'utf8'))) continue
      if (now - Number(device.lastSeenAt || 0) > this.deviceTtlMs) {
        devices.splice(i, 1)
        this.persist()
        return null
      }
      device.lastSeenAt = now
      const last = this._persistAt.get(device.id) || 0
      if (now - last > 60_000) {
        this._persistAt.set(device.id, now)
        this.persist()
      }
      return publicDevice(device)
    }
    return null
  }

  /** 撤销当前 token（logout）。 */
  logout(token) {
    const digest = hashToken(token)
    const devices = this.devices
    for (let i = 0; i < devices.length; i++) {
      if (timingSafeEqualString(devices[i].tokenHash, digest)) {
        devices.splice(i, 1)
        this.persist()
        return true
      }
    }
    return false
  }

  revokeDevice(id) {
    const devices = this.devices
    for (let i = 0; i < devices.length; i++) {
      if (devices[i].id === id) {
        devices.splice(i, 1)
        this.persist()
        return true
      }
    }
    return false
  }

  listDevices() {
    return this.devices.map(publicDevice)
  }

  findDevice(id) {
    return this.devices.find((device) => device.id === id) || null
  }

  /** 设备是否仍有效（存在且未超过 30 天滑动过期）。WS open 时复查，防撤销后继续开流。 */
  isDeviceActive(id) {
    const device = this.findDevice(id)
    if (!device) return false
    return this.now() - Number(device.lastSeenAt || 0) <= this.deviceTtlMs
  }

  /**
   * 修改口令（异步 scrypt，不阻塞长轮询/WS）。
   * - 失败（current 错误）复用登录的 attempts/locks：5 次/分钟 → 429，连续 10 次失败锁 15 分钟 → 423；
   * - 同一设备并发改密做重入保护（第二个请求 409 conflict）；
   * - 成功后清除该 IP 的失败计数；既有设备 token 语义不变（不吊销）。
   */
  async changePassword(current, next, { ip = 'unknown', deviceId = null } = {}) {
    if (deviceId) {
      if (this._passwordInFlight.has(deviceId)) {
        return { ok: false, status: 409, error: 'conflict' }
      }
      this._passwordInFlight.add(deviceId)
    }
    try {
      const guard = this._precheck(ip)
      if (guard) return guard
      const currentOk = await verifyPasswordAsync(current, this.config.password)
      if (!currentOk) return this._recordAuthFailure(ip)
      const strength = validatePasswordStrength(next)
      if (!strength.ok) return { ok: false, status: 400, error: strength.error }
      if (timingSafeEqualString(current, next)) {
        return { ok: false, status: 400, error: 'password-unchanged' }
      }
      this.config.password = await hashPasswordAsync(next)
      this.config.mustChangePassword = false
      this.config.passwordChangedAt = this.now()
      this.locks.delete(ip)
      this.persist()
      return { ok: true }
    } finally {
      if (deviceId) this._passwordInFlight.delete(deviceId)
    }
  }

  issueTicket(deviceId) {
    const expiresAt = this.now() + this.ticketTtlMs
    const payload = Buffer.from(JSON.stringify({ d: deviceId, e: expiresAt }), 'utf8').toString('base64url')
    const sig = crypto.createHmac('sha256', this.secret).update(payload).digest('base64url')
    return { ticket: `${payload}.${sig}`, expiresAt }
  }

  /** 校验 WS ticket，返回 deviceId；失败返回 null。 */
  verifyTicket(ticket) {
    if (typeof ticket !== 'string' || ticket.length > 1024) return null
    const dot = ticket.indexOf('.')
    if (dot <= 0 || dot === ticket.length - 1) return null
    const payload = ticket.slice(0, dot)
    const sig = ticket.slice(dot + 1)
    const expected = crypto.createHmac('sha256', this.secret).update(payload).digest('base64url')
    if (!timingSafeEqualString(sig, expected)) return null
    let decoded
    try {
      decoded = JSON.parse(Buffer.from(payload, 'base64url').toString('utf8'))
    } catch {
      return null
    }
    if (!decoded || typeof decoded.d !== 'string' || typeof decoded.e !== 'number') return null
    if (decoded.e < this.now()) return null
    const device = this.findDevice(decoded.d)
    if (!device) return null
    if (this.now() - Number(device.lastSeenAt || 0) > this.deviceTtlMs) return null
    return device.id
  }
}

function publicDevice(device) {
  return {
    id: device.id,
    name: device.name,
    platform: device.platform,
    createdAt: device.createdAt,
    lastSeenAt: device.lastSeenAt,
  }
}

export function sanitizeDeviceName(name) {
  const text = String(name ?? '').replace(/[\u0000-\u001f\u007f]/g, '').trim().slice(0, 64)
  return text || 'device'
}
