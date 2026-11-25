/**
 * gateway/config.mjs — ~/.dsh-mobile 目录、config.json、upstream.cookie 的读写与权限。
 *
 * 契约（SPEC v2 §5.1/§7，docs/interfaces.md §1/§7）：
 *  - 目录 0700；config.json / upstream.cookie / initial-password.txt 0600；
 *  - 原子写（临时文件 + fsync + rename + chmod）；
 *  - 首次启动随机生成 10 位无歧义口令，只以 scrypt 记录存 config.json，
 *    明文写入 initial-password.txt（插件面板展示，改密后删除）。
 */
import crypto from 'node:crypto'
import fs from 'node:fs'
import os from 'node:os'
import path from 'node:path'
import { hashPassword } from './auth.mjs'

/** 网关/插件共用版本号（与 plugin/package.json 保持一致）。 */
export const VERSION = '0.1.14'
export const DEFAULT_PORT = 8790
export const DEFAULT_UPSTREAM_PORT = 3080

const PASSWORD_CHARSET = 'ABCDEFGHJKLMNPQRSTUVWXYZabcdefghijkmnpqrstuvwxyz23456789'
export const PASSWORD_LENGTH = 10

export function resolveHome(env = process.env) {
  // 测试隔离：DSH_MOBILE_CONFIG_DIR 优先；否则 DSH_MOBILE_HOME；否则 ~/.dsh-mobile。
  if (env.DSH_MOBILE_CONFIG_DIR) return path.resolve(env.DSH_MOBILE_CONFIG_DIR)
  if (env.DSH_MOBILE_HOME) return path.resolve(env.DSH_MOBILE_HOME)
  const home = env.HOME || os.homedir()
  return path.join(home, '.dsh-mobile')
}

/** 网关监听地址：默认 0.0.0.0（供 Cloudflare 通道/局域网访问），可用 env 收紧。 */
export function resolveBindHost(env = process.env, override) {
  const host = override ?? env.DSH_MOBILE_GATEWAY_HOST
  return host && String(host).trim() ? String(host).trim() : '0.0.0.0'
}

export function homePaths(home) {
  return {
    home,
    config: path.join(home, 'config.json'),
    cookie: path.join(home, 'upstream.cookie'),
    upstream: path.join(home, 'upstream.json'),
    initialPassword: path.join(home, 'initial-password.txt'),
    bin: path.join(home, 'bin'),
    logs: path.join(home, 'logs'),
  }
}

export function ensureDir(dir, mode = 0o700) {
  fs.mkdirSync(dir, { recursive: true, mode })
  try {
    fs.chmodSync(dir, mode)
  } catch {
    /* 权限修复失败不致命（如只读挂载），后续文件模式仍会收紧 */
  }
  return dir
}

/** 原子写：临时文件 + fsync + rename；目标文件模式显式 chmod。 */
export function atomicWriteFile(file, data, mode = 0o600) {
  const dir = path.dirname(file)
  ensureDir(dir, 0o700)
  const tmp = path.join(dir, `.${path.basename(file)}.${process.pid}.${crypto.randomBytes(4).toString('hex')}.tmp`)
  const buf = Buffer.isBuffer(data) ? data : Buffer.from(String(data), 'utf8')
  let fd
  try {
    fd = fs.openSync(tmp, 'w', mode)
    fs.writeSync(fd, buf, 0, buf.length, 0)
    fs.fsyncSync(fd)
  } finally {
    if (fd !== undefined) fs.closeSync(fd)
  }
  fs.chmodSync(tmp, mode)
  fs.renameSync(tmp, file)
  fs.chmodSync(file, mode)
  return file
}

export function generatePassword(length = PASSWORD_LENGTH) {
  const len = Math.max(4, Math.min(128, Number(length) || PASSWORD_LENGTH))
  let out = ''
  // 拒绝采样避免取模偏差；字符集无 0/O/1/l/I 等易混字符。
  while (out.length < len) {
    const byte = crypto.randomBytes(1)[0]
    if (byte >= 256 - (256 % PASSWORD_CHARSET.length)) continue
    out += PASSWORD_CHARSET[byte % PASSWORD_CHARSET.length]
  }
  return out
}

export function defaultConfig({ passwordRecord } = {}) {
  return {
    version: 1,
    createdAt: new Date().toISOString(),
    mustChangePassword: true,
    password: passwordRecord,
    devices: [],
    roots: [],
    port: { gateway: DEFAULT_PORT },
    log: { level: 'info' },
  }
}

/** 补齐旧版本/手改配置缺省字段，保证后续代码不会因缺字段崩溃。 */
export function normalizeConfig(config) {
  if (!config || typeof config !== 'object' || Array.isArray(config)) throw new Error('config-invalid')
  if (!config.password || config.password.algo !== 'scrypt') throw new Error('config-invalid-password')
  config.version = Number(config.version) || 1
  if (typeof config.mustChangePassword !== 'boolean') config.mustChangePassword = false
  if (!Array.isArray(config.devices)) config.devices = []
  // v3：不再管理 Cloudflare 隧道，配置中不存任何隧道/凭据字段。
  delete config.tunnel
  if (!Array.isArray(config.roots)) config.roots = []
  if (!config.port || typeof config.port !== 'object') config.port = { gateway: DEFAULT_PORT }
  if (!config.log || typeof config.log !== 'object') config.log = { level: 'info' }
  return config
}

/**
 * 加载或首次生成配置。返回 {config, path, created, initialPassword}。
 * 首次生成的明文口令同时写入 initial-password.txt（0600）。
 */
export function loadConfig(home, { env = process.env } = {}) {
  const paths = homePaths(home)
  ensureDir(paths.home, 0o700)
  ensureDir(paths.bin, 0o700)
  ensureDir(paths.logs, 0o700)

  let created = false
  let initialPassword = null
  let config
  if (!fs.existsSync(paths.config)) {
    initialPassword = generatePassword(PASSWORD_LENGTH)
    config = defaultConfig({ passwordRecord: hashPassword(initialPassword) })
    config.port = { gateway: Number(env.DSH_MOBILE_GATEWAY_PORT) || DEFAULT_PORT }
    atomicWriteFile(paths.initialPassword, `${initialPassword}\n`, 0o600)
    saveConfig(home, config)
    created = true
  } else {
    let text
    try {
      text = fs.readFileSync(paths.config, 'utf8')
    } catch (err) {
      throw new Error(`config-unreadable: ${err.code || err.message}`)
    }
    try {
      config = JSON.parse(text)
    } catch {
      throw new Error('config-corrupt: config.json is not valid JSON')
    }
    normalizeConfig(config)
    // 权限自愈：历史文件可能过宽。
    try {
      fs.chmodSync(paths.config, 0o600)
    } catch {
      /* ignore */
    }
  }
  return { config, path: paths.config, created, initialPassword }
}

export function saveConfig(home, config) {
  normalizeConfig(config)
  const paths = homePaths(home)
  atomicWriteFile(paths.config, `${JSON.stringify(config, null, 2)}\n`, 0o600)
  return config
}

export function readInitialPassword(home) {
  const paths = homePaths(home)
  try {
    const text = fs.readFileSync(paths.initialPassword, 'utf8').trim()
    return text && text.length <= 128 ? text : null
  } catch {
    return null
  }
}

export function removeInitialPassword(home) {
  try {
    fs.unlinkSync(homePaths(home).initialPassword)
    return true
  } catch {
    return false
  }
}

/** 读上游 Cookie（每次请求都读，插件刷新后网关无需重启）。 */
export function readUpstreamCookie(home) {
  const paths = homePaths(home)
  let text
  try {
    text = fs.readFileSync(paths.cookie, 'utf8').trim()
  } catch {
    return null
  }
  if (!text || text.length > 4096) return null
  if (/[\r\n\u0000]/.test(text)) return null
  if (!/^[\x20-\x7e]+$/.test(text) || !text.includes('=')) return null
  return text
}

export function writeUpstreamCookie(home, cookie) {
  const value = String(cookie || '').trim()
  if (!value || value.length > 4096 || !/^[\x20-\x7e]+$/.test(value) || /[\r\n\u0000]/.test(value) || !value.includes('=')) {
    throw new Error('invalid-upstream-cookie')
  }
  return atomicWriteFile(homePaths(home).cookie, `${value}\n`, 0o600)
}

function normalizeBaseUrl(value) {
  let url
  try {
    url = new URL(String(value || ''))
  } catch {
    return null
  }
  if (url.protocol !== 'http:' && url.protocol !== 'https:') return null
  if (url.username || url.password) return null
  return url.origin + (url.pathname === '/' ? '' : url.pathname.replace(/\/$/, ''))
}

/** 上游 DSH 地址：env > upstream.json（插件写入）> 默认本机 3080。 */
export function readUpstreamBase(home, env = process.env) {
  const fromEnv = normalizeBaseUrl(env.DSH_MOBILE_UPSTREAM)
  if (fromEnv) return fromEnv
  try {
    const parsed = JSON.parse(fs.readFileSync(homePaths(home).upstream, 'utf8'))
    const fromFile = normalizeBaseUrl(parsed?.base)
    if (fromFile) return fromFile
  } catch {
    /* fall through */
  }
  const port = Number(env.DSH_MOBILE_UPSTREAM_PORT) || DEFAULT_UPSTREAM_PORT
  return `http://127.0.0.1:${port}`
}

export function writeUpstreamBase(home, base) {
  const normalized = normalizeBaseUrl(base)
  if (!normalized) throw new Error('invalid-upstream-base')
  return atomicWriteFile(homePaths(home).upstream, `${JSON.stringify({ base: normalized })}\n`, 0o600)
}

/** 允许根：~ + config.roots + env.DSH_MOBILE_ROOTS(JSON 数组) + 插件传入的 DSH cwd。 */
export function resolveRoots({ home, config, env = process.env }) {
  const candidates = []
  candidates.push(env.HOME || os.homedir())
  for (const root of config?.roots || []) candidates.push(String(root))
  if (env.DSH_MOBILE_ROOTS) {
    try {
      const parsed = JSON.parse(env.DSH_MOBILE_ROOTS)
      if (Array.isArray(parsed)) candidates.push(...parsed.map(String))
    } catch {
      /* ignore malformed env */
    }
  }
  const out = []
  const seen = new Set()
  for (const raw of candidates) {
    if (!raw || typeof raw !== 'string') continue
    const expanded = raw === '~' ? os.homedir() : raw.startsWith('~/') ? path.join(os.homedir(), raw.slice(2)) : raw
    const resolved = path.resolve(expanded)
    if (seen.has(resolved)) continue
    seen.add(resolved)
    out.push(resolved)
  }
  return out
}

export { normalizeBaseUrl }
