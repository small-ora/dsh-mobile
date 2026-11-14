/**
 * gateway/proxy-trust.mjs — 反代可信边界 + 客户端 IP 解析（登录限速的 key）。
 *
 * 背景（P1 安全修复）：网关默认监听 0.0.0.0 供用户自建 Cloudflare 通道/反代转发。
 * 若无条件信任 `cf-connecting-ip` / `X-Forwarded-For`，任何能直连 8790 的攻击者都能
 * 每次换一个伪造 IP 重置登录限速窗口，从而无限爆破口令。
 *
 * 策略：
 *  - 只有“直连对端”本身可信时才看转发头；
 *  - 默认可信对端 = 回环地址（cloudflared 与本网关同机时的典型情况）；
 *  - 可用 env `DSH_MOBILE_TRUST_PROXY_CIDRS`（逗号分隔 CIDR）追加可信代理网段，
 *    例如 cloudflared 装在另一台内网机时填那台机器的网段；
 *  - 可信时：`cf-connecting-ip` 优先（Cloudflare 边缘会覆盖该头），
 *    否则解析 `X-Forwarded-For` 并**从右向左跳过可信代理**，取最右侧不可信地址
 *    （标准做法，忽略客户端自造在左端的伪造链）；
 *  - 不可信直连：一律用 socket 对端地址，完全忽略所有转发头。
 *
 * `resolve()` 返回 { ip, source, peer, trusted }，source 仅用于日志/调试。
 */
import net from 'node:net'

/** 归一化 IP：IPv4-mapped IPv6 → IPv4、去 zone、IPv6 小写。 */
export function normalizeIp(input) {
  if (typeof input !== 'string') return null
  let ip = input.trim()
  if (!ip || ip.length > 64) return null
  const zone = ip.indexOf('%')
  if (zone > 0) ip = ip.slice(0, zone)
  if (ip.startsWith('::ffff:') && net.isIP(ip.slice(7)) === 4) return ip.slice(7)
  if (ip.startsWith('::FFFF:') && net.isIP(ip.slice(7)) === 4) return ip.slice(7)
  const version = net.isIP(ip)
  if (version === 4) return ip
  if (version === 6) return ip.toLowerCase()
  return null
}

/** 回环：127.0.0.0/8、::1（IPv4-mapped 已归一化成 IPv4）。 */
export function isLoopbackIp(input) {
  const ip = normalizeIp(input)
  if (!ip) return false
  const version = net.isIP(ip)
  if (version === 4) return ip.split('.')[0] === '127'
  if (version === 6) return ip === '::1' || /^0:0:0:0:0:0:0:1$/.test(ip)
  return false
}

function ipv6ToBytes(text) {
  const doubleColon = text.includes('::')
  const [headRaw, tailRaw = ''] = doubleColon ? text.split('::') : [text, '']
  const expand = (part) => {
    if (!part) return []
    const groups = part.split(':')
    if (groups.length && groups[groups.length - 1].includes('.')) {
      const v4 = groups.pop().split('.').map(Number)
      if (v4.length !== 4 || v4.some((n) => !Number.isInteger(n) || n < 0 || n > 255)) return null
      groups.push(((v4[0] << 8) | v4[1]).toString(16), ((v4[2] << 8) | v4[3]).toString(16))
    }
    return groups
  }
  const head = expand(headRaw)
  const tail = expand(tailRaw)
  if (!head || !tail) return null
  let groups
  if (doubleColon) {
    const fill = 8 - head.length - tail.length
    if (fill < 0) return null
    groups = [...head, ...Array(fill).fill('0'), ...tail]
  } else {
    groups = head
  }
  if (groups.length !== 8) return null
  const bytes = Buffer.alloc(16)
  for (let i = 0; i < 8; i++) {
    if (!/^[0-9a-f]{1,4}$/i.test(groups[i] || '0')) return null
    bytes.writeUInt16BE(parseInt(groups[i] || '0', 16), i * 2)
  }
  return bytes
}

function ipToBytes(input) {
  const ip = normalizeIp(input)
  if (!ip) return null
  const version = net.isIP(ip)
  if (version === 4) return { family: 4, bytes: Buffer.from(ip.split('.').map(Number)) }
  if (version === 6) {
    const bytes = ipv6ToBytes(ip)
    return bytes ? { family: 6, bytes } : null
  }
  return null
}

/** 解析 CIDR（支持 IPv4 / IPv6，含 IPv4-mapped 写法）；非法返回 null。 */
export function parseCidr(text) {
  const raw = String(text ?? '').trim()
  const slash = raw.lastIndexOf('/')
  if (slash <= 0) return null
  const addr = ipToBytes(raw.slice(0, slash))
  const prefix = Number(raw.slice(slash + 1))
  if (!addr) return null
  const max = addr.family === 4 ? 32 : 128
  if (!Number.isInteger(prefix) || prefix < 0 || prefix > max) return null
  return { family: addr.family, bytes: addr.bytes, prefix }
}

/** 解析 env 里的逗号分隔 CIDR 列表；非法项忽略。 */
export function parseCidrList(text) {
  const out = []
  for (const part of String(text ?? '').split(',')) {
    if (!part.trim()) continue
    const cidr = parseCidr(part)
    if (cidr) out.push(cidr)
  }
  return out
}

export function matchCidr(input, cidr) {
  const addr = ipToBytes(input)
  if (!addr || addr.family !== cidr.family) return false
  const fullBytes = cidr.prefix >> 3
  const remainder = cidr.prefix & 7
  for (let i = 0; i < fullBytes; i++) {
    if (addr.bytes[i] !== cidr.bytes[i]) return false
  }
  if (remainder) {
    const mask = (0xff << (8 - remainder)) & 0xff
    if ((addr.bytes[fullBytes] & mask) !== (cidr.bytes[fullBytes] & mask)) return false
  }
  return true
}

/**
 * 创建客户端 IP 解析器。
 * env.DSH_MOBILE_TRUST_PROXY_CIDRS 为空时 = 只信回环直连；否则回环 + 这些 CIDR 都可信。
 */
export function createProxyTrust({ env = process.env, logger } = {}) {
  const cidrs = parseCidrList(env.DSH_MOBILE_TRUST_PROXY_CIDRS)
  const mode = cidrs.length ? 'custom' : 'loopback'

  function isTrustedProxy(input) {
    const ip = normalizeIp(input)
    if (!ip) return false
    if (isLoopbackIp(ip)) return true
    return cidrs.some((cidr) => matchCidr(ip, cidr))
  }

  function resolve(req) {
    const peer = normalizeIp(req?.socket?.remoteAddress) || String(req?.socket?.remoteAddress || 'unknown')
    const base = { peer, trusted: isTrustedProxy(peer) }
    if (!base.trusted) return { ...base, ip: peer, source: 'socket' }

    // 可信直连：cf-connecting-ip（Cloudflare 边缘写入，单值）优先。
    const cfRaw = req?.headers?.['cf-connecting-ip']
    const cf = normalizeIp(String(Array.isArray(cfRaw) ? cfRaw[0] : cfRaw ?? '').split(',')[0])
    if (cf) return { ...base, ip: cf, source: 'cf-connecting-ip' }

    // 否则解析 X-Forwarded-For：从右向左跳过可信代理，取最右侧不可信地址。
    const xffRaw = req?.headers?.['x-forwarded-for']
    const xff = Array.isArray(xffRaw) ? xffRaw.join(',') : xffRaw
    if (typeof xff === 'string' && xff.trim()) {
      const chain = xff
        .split(',')
        .map((part) => normalizeIp(part))
        .filter(Boolean)
      for (let i = chain.length - 1; i >= 0; i--) {
        if (isTrustedProxy(chain[i])) continue
        return { ...base, ip: chain[i], source: 'x-forwarded-for' }
      }
      // 整条链都是可信代理：退化为最左端（唯一语义明确的候选）。
      if (chain.length) return { ...base, ip: chain[0], source: 'x-forwarded-for' }
    }
    return { ...base, ip: peer, source: 'socket' }
  }

  /** 对外只暴露模式与数量，不泄露具体网段。 */
  function describe() {
    return { mode, trustedCidrCount: cidrs.length }
  }

  for (const part of String(env.DSH_MOBILE_TRUST_PROXY_CIDRS ?? '').split(',')) {
    if (part.trim() && !parseCidr(part)) logger?.warn('ignored invalid DSH_MOBILE_TRUST_PROXY_CIDRS entry')
  }

  return { resolve, describe, isTrustedProxy, cidrs }
}
