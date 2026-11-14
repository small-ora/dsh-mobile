/**
 * plugin/admin.js — 主机面板逻辑（无外部依赖）。
 *
 * 读取 /mobile/api/status 与 /mobile/api/devices；操作为刷新上游 Cookie / 重启网关 / 撤销设备。
 * 面板接口由 DSH 同源会话鉴权（插件 index.mjs 里 requestRejection），本文件不接触任何凭据。
 */
;(function () {
  'use strict'

  var els = {
    banner: document.getElementById('banner'),
    gwRunning: document.getElementById('gw-running'),
    gwListen: document.getElementById('gw-listen'),
    gwUpstream: document.getElementById('gw-upstream'),
    gwStreamHub: document.getElementById('gw-streamhub'),
    gwVersion: document.getElementById('gw-version'),
    gwRestarts: document.getElementById('gw-restarts'),
    lanRows: document.getElementById('lan-rows'),
    initialPassword: document.getElementById('initial-password'),
    copyPassword: document.getElementById('copy-password'),
    passwordNote: document.getElementById('password-note'),
    devicesRows: document.getElementById('devices-rows'),
    devicesSummary: document.getElementById('devices-summary'),
    logs: document.getElementById('logs'),
    toast: document.getElementById('toast'),
    btnRefreshCookie: document.getElementById('btn-refresh-cookie'),
    btnRestart: document.getElementById('btn-restart'),
    btnRefresh: document.getElementById('btn-refresh'),
  }

  var state = {
    data: null,
    devices: null,
    timer: null,
    lastPassword: null,
  }
  var toastTimer = null

  function esc(value) {
    return String(value == null ? '' : value).replace(/[&<>"']/g, function (ch) {
      return { '&': '&amp;', '<': '&lt;', '>': '&gt;', '"': '&quot;', "'": '&#39;' }[ch]
    })
  }

  function showToast(text) {
    els.toast.textContent = text
    els.toast.classList.add('show')
    if (toastTimer) clearTimeout(toastTimer)
    toastTimer = setTimeout(function () {
      els.toast.classList.remove('show')
    }, 1800)
  }

  function showBanner(text) {
    if (!text) {
      els.banner.classList.remove('show')
      els.banner.textContent = ''
      return
    }
    els.banner.textContent = text
    els.banner.classList.add('show')
  }

  function platformLabel(platform) {
    var p = String(platform || '').toLowerCase()
    if (p === 'android') return 'Android'
    if (p === 'ios') return 'iOS'
    if (p === 'web') return 'Web'
    return platform ? String(platform) : '未知平台'
  }

  function formatLastSeen(ms) {
    var recent = Number(ms) || 0
    if (!recent) return '从未活跃'
    var diff = Date.now() - recent
    if (diff < 60 * 1000) return '刚刚'
    if (diff < 60 * 60 * 1000) return Math.max(1, Math.floor(diff / 60000)) + ' 分钟前'
    if (diff < 24 * 60 * 60 * 1000) return Math.floor(diff / 3600000) + ' 小时前'
    return new Date(recent).toLocaleDateString()
  }

  function renderDevices(payload) {
    var items = payload && Array.isArray(payload.items) ? payload.items : null
    state.devices = items
    if (!items) {
      els.devicesSummary.textContent = ''
      els.devicesRows.innerHTML = '<div class="row"><span class="k">设备</span><span class="v muted">无法读取设备列表（网关未就绪或鉴权失败）</span></div>'
      return
    }
    var online = items.filter(function (device) {
      return device.online
    }).length
    els.devicesSummary.textContent = items.length ? items.length + ' 台设备 · ' + online + ' 台在线' : ''
    if (!items.length) {
      els.devicesRows.innerHTML = '<div class="row"><span class="k">设备</span><span class="v muted">暂无设备</span></div>'
      return
    }
    els.devicesRows.innerHTML = items
      .map(function (device) {
        var name = esc(device.name || '未命名设备')
        var id = esc(device.id)
        return (
          '<div class="row">' +
          '<span class="k"><span class="dot ' + (device.online ? 'ok' : '') + '"></span>' + (device.online ? '在线' : '离线') + '</span>' +
          '<span class="v"><div class="dev-name">' + name + '</div>' +
          '<div class="dev-meta">' + esc(platformLabel(device.platform)) + ' · 最近活跃：' + esc(formatLastSeen(device.lastSeenAt)) + '</div></span>' +
          '<button class="revoke" type="button" data-revoke-id="' + id + '" data-revoke-name="' + name + '">撤销</button>' +
          '</div>'
        )
      })
      .join('')
  }

  async function copyText(text, label) {
    if (!text) {
      showToast('没有可复制的内容')
      return
    }
    try {
      if (navigator.clipboard && navigator.clipboard.writeText) {
        await navigator.clipboard.writeText(text)
      } else {
        var input = document.createElement('textarea')
        input.value = text
        input.style.position = 'fixed'
        input.style.opacity = '0'
        document.body.appendChild(input)
        input.select()
        document.execCommand('copy')
        document.body.removeChild(input)
      }
      showToast(label || '已复制')
    } catch (err) {
      showToast('复制失败，请手动选择')
    }
  }

  function render(data) {
    state.data = data
    showBanner('')
    var gw = data.gateway || {}
    var health = gw.health || null
    var upstreamOk = Boolean(health && health.upstream && health.upstream.ok)
    var running = Boolean(gw.running)
    els.gwRunning.innerHTML = '<span class="dot ' + (running ? 'ok' : 'bad') + '"></span>' + (running ? '运行中' : '未运行')
    els.gwListen.textContent = (gw.host || '0.0.0.0') + ':' + (gw.port || 8790)
    els.gwUpstream.innerHTML =
      '<span class="dot ' + (upstreamOk ? 'ok' : '') + '"></span>' + (upstreamOk ? '已连接' : '未知/不可达')
    els.gwVersion.textContent = 'gateway ' + ((data.plugin && data.plugin.version) || '—') + ' · plugin ' + ((data.plugin && data.plugin.version) || '—')
    els.gwRestarts.textContent = String(gw.restarts != null ? gw.restarts : 0) + (gw.lastError ? '（最近：' + esc(gw.lastError) + '）' : '')
    var hub = data.streamHub || {}
    var hubConnected = Boolean(hub.connected)
    els.gwStreamHub.innerHTML =
      '<span class="dot ' + (hubConnected ? 'ok' : 'bad') + '"></span>' + (hubConnected ? '已连接（长轮询主通道）' : '未连接')

    var lan = Array.isArray(data.lan) ? data.lan : []
    if (!lan.length) {
      els.lanRows.innerHTML = '<div class="row"><span class="k">地址</span><span class="v muted">暂无可用地址</span></div>'
    } else {
      els.lanRows.innerHTML = lan
        .map(function (item, index) {
          return (
            '<div class="row"><span class="k">' +
            (index === 0 ? '局域网' : item.iface || '地址') +
            '</span><span class="v mono">' +
            esc(item.url) +
            '</span><button class="copy" type="button" data-copy="' +
            esc(item.url) +
            '">复制</button></div>'
          )
        })
        .join('')
    }

    var password = data.initialPassword || null
    state.lastPassword = password
    if (password) {
      els.initialPassword.textContent = password
      els.copyPassword.disabled = false
      els.passwordNote.textContent = '首次登录后请尽快修改口令；修改后本处不再显示。'
    } else {
      els.initialPassword.textContent = '已修改（不再显示）'
      els.copyPassword.disabled = true
      els.passwordNote.textContent = data.config && data.config.mustChangePassword === false ? '管理口令已修改。' : '未找到 initial-password.txt（可能已修改或已删除）。'
    }

    var logs = Array.isArray(data.logs) ? data.logs : []
    els.logs.textContent = logs.length ? logs.join('\n') : '暂无日志'
    els.logs.scrollTop = els.logs.scrollHeight

    if (data.upstream && data.upstream.cookiePresent === false) {
      showBanner('尚未拿到上游 DSH Cookie：请确认 DSH 正在运行，并点击「刷新上游 Cookie」。')
    } else if (gw.lastError) {
      showBanner('网关最近一次异常：' + gw.lastError)
    }
  }

  async function load() {
    var statusResponse
    try {
      statusResponse = await fetch('/mobile/api/status', { credentials: 'same-origin', cache: 'no-store' })
      if (!statusResponse.ok) {
        if (statusResponse.status === 401 || statusResponse.status === 403) {
          showBanner('面板会话未通过 DSH 鉴权，请从 DSH 页面内打开本面板。')
          return
        }
        throw new Error('HTTP ' + statusResponse.status)
      }
      render(await statusResponse.json())
    } catch (err) {
      showBanner('无法读取网关状态：' + (err && err.message ? err.message : '网络错误'))
      return
    }
    try {
      var devicesResponse = await fetch('/mobile/api/devices', { credentials: 'same-origin', cache: 'no-store' })
      if (!devicesResponse.ok) {
        renderDevices(null)
        return
      }
      renderDevices(await devicesResponse.json())
    } catch (err) {
      renderDevices(null)
    }
  }

  async function revokeDevice(id, name) {
    try {
      var response = await fetch('/mobile/api/devices/revoke', {
        method: 'POST',
        credentials: 'same-origin',
        headers: { 'content-type': 'application/json' },
        body: JSON.stringify({ id: id }),
      })
      var payload = await response.json().catch(function () {
        return null
      })
      if (!response.ok || !payload || payload.ok !== true) {
        showToast('撤销「' + (name || '设备') + '」失败')
      } else {
        showToast('已撤销「' + (name || '设备') + '」')
      }
    } catch (err) {
      showToast('撤销失败')
    }
    load()
  }

  async function post(path, label) {
    try {
      var response = await fetch(path, {
        method: 'POST',
        credentials: 'same-origin',
        headers: { 'content-type': 'application/json' },
        body: '{}',
      })
      var payload = await response.json().catch(function () {
        return null
      })
      if (!response.ok || (payload && payload.ok === false)) {
        showToast(label + '失败')
      } else {
        showToast(label + '成功')
      }
    } catch (err) {
      showToast(label + '失败')
    }
    load()
  }

  els.lanRows.addEventListener('click', function (event) {
    var target = event.target
    if (!target || target.tagName !== 'BUTTON') return
    var text = target.getAttribute('data-copy')
    if (text) copyText(text, '地址已复制')
  })

  els.devicesRows.addEventListener('click', function (event) {
    var target = event.target
    if (!target || target.tagName !== 'BUTTON') return
    var id = target.getAttribute('data-revoke-id')
    if (!id) return
    var name = target.getAttribute('data-revoke-name') || '该设备'
    if (window.confirm('撤销设备「' + name + '」？撤销后该设备需重新登录。')) revokeDevice(id, name)
  })

  els.copyPassword.addEventListener('click', function () {
    copyText(state.lastPassword, '口令已复制')
  })

  els.btnRefreshCookie.addEventListener('click', function () {
    post('/mobile/api/refresh-cookie', '刷新 Cookie')
  })

  els.btnRestart.addEventListener('click', function () {
    if (window.confirm('重启网关会短暂断开已连接的 App，继续？')) post('/mobile/api/restart-gateway', '重启网关')
  })

  els.btnRefresh.addEventListener('click', function () {
    load().then(function () {
      showToast('已刷新')
    })
  })

  load()
  state.timer = setInterval(function () {
    if (document.visibilityState === 'visible') load()
  }, 10000)
})()
