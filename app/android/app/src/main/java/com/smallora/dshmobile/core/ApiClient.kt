package com.smallora.dshmobile.core

import android.content.Context
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.MediaType.Companion.toMediaTypeOrNull
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.Response
import org.json.JSONArray
import org.json.JSONObject
import java.io.IOException
import java.util.concurrent.TimeUnit

/** 网关错误（含 HTTP 状态与错误码，文案由 UI 决定）。 */
class ApiException(
    val code: String,
    message: String,
    val httpStatus: Int = 0,
    val retryAfterMs: Long? = null,
    val until: Long? = null,
) : IOException(message)

/** 统一 401：token 失效 / 未登录。 */
class UnauthorizedException(message: String = "登录已过期，请重新登录") : IOException(message)

/**
 * 归一化服务器地址（SPEC §5.2 信任边界：公网必须走 TLS）。
 *
 * - 显式 `http://` / `https://` 原样保留（仅去尾部 "/"）；
 * - 无 scheme 时按主机判断：回环 / 私有网段 / CGNAT（局域网、Tailscale）补 `http://`，
 *   其余公网域名或 IP 一律补 `https://`，避免把口令与设备 token 明文发到公网；
 * - 支持 `[::1]:8790` 形式的 IPv6；非法输入返回空串，由调用方报「服务器地址无效」。
 */
fun normalizeServerBase(raw: String): String {
    val trimmed = raw.trim()
    if (trimmed.isEmpty()) return ""
    val lower = trimmed.lowercase()
    val hasHttp = lower.startsWith("http://")
    val hasHttps = lower.startsWith("https://")
    // 只支持 http/https；其它 scheme（ftp://、ws:// 等）视为非法
    if (lower.contains("://") && !(hasHttp || hasHttps)) return ""
    if (hasHttp || hasHttps) {
        val authority = trimmed.substringAfter("://").substringBefore('/').substringBefore('?').substringBefore('#')
        if (!isValidHost(extractHost(authority))) return ""
        return trimmed.trimEnd('/')
    }
    val host = extractHost(trimmed)
    if (!isValidHost(host)) return ""
    val scheme = if (isPrivateHost(host)) "http" else "https"
    return "$scheme://${trimmed.trimEnd('/')}"
}

/** 从 host[:port]、[v6]:port、user@host 中取出纯主机名（小写，不含端口）。 */
internal fun extractHost(value: String): String {
    var authority = value.trim()
    val at = authority.lastIndexOf('@')
    if (at >= 0) authority = authority.substring(at + 1)
    authority = authority.substringBefore('/').substringBefore('?').substringBefore('#')
    if (authority.startsWith("[")) {
        val end = authority.indexOf(']')
        return if (end > 0) authority.substring(1, end).lowercase() else ""
    }
    // 未加括号的 IPv6：冒号 >= 2 视为纯 IPv6 主机（不带端口）
    if (authority.count { it == ':' } >= 2) return authority.lowercase()
    val colon = authority.indexOf(':')
    if (colon >= 0) authority = authority.substring(0, colon)
    return authority.lowercase()
}

/**
 * 是否局域网/本机/私有地址（可用明文 http）：
 * localhost、127.0.0.0/8、::1、fe80::/10、fc00::/7、10/8、172.16/12、192.168/16、
 * 169.254/16（链路本地）、100.64/10（CGNAT / Tailscale）。
 */
internal fun isPrivateHost(host: String): Boolean {
    if (host.isEmpty()) return false
    if (host == "localhost" || host.endsWith(".localhost")) return true
    if (host == "::1") return true
    if (host.startsWith("fe80:") || host.startsWith("fc") || host.startsWith("fd")) return true
    val parts = host.split('.')
    if (parts.size != 4) return false
    val nums = parts.map { it.toIntOrNull() ?: return false }
    if (nums.any { it !in 0..255 }) return false
    val a = nums[0]
    val b = nums[1]
    return a == 127 ||
        a == 10 ||
        (a == 172 && b in 16..31) ||
        (a == 192 && b == 168) ||
        (a == 169 && b == 254) ||
        (a == 100 && b in 64..127)
}

/** 校验并归一化服务器地址；非法输入在发出任何网络请求前抛 `bad-server`。 */
internal fun validatedServerBase(raw: String): String {
    val normalized = normalizeServerBase(raw)
    if (normalized.isEmpty()) throw ApiException("bad-server", "服务器地址无效")
    return normalized
}

private fun isValidHost(host: String): Boolean {
    if (host.isEmpty() || host.length > 253) return false
    if (host.contains("..")) return false
    return host.all { it.isLetterOrDigit() || it == '.' || it == '-' || it == '_' || it == ':' }
}

/**
 * 网关 HTTP 客户端（OkHttp）：鉴权、RPC、事件流长轮询、文件、审批回答。
 * 事件通道为 HTTP 长轮询（网关内部维护上游 WS），不依赖 WebSocket（SPEC v3）。
 */
open class ApiClient(
    // 保留契约构造签名；当前实现不依赖 Context（JVM 单测可用无参构造）
    @Suppress("UNUSED_PARAMETER") context: Context? = null,
) {

    private val jsonMedia = "application/json; charset=utf-8".toMediaType()
    private val rawMedia = "application/octet-stream".toMediaType()

    private val client: OkHttpClient = OkHttpClient.Builder()
        .connectTimeout(15, TimeUnit.SECONDS)
        .readTimeout(45, TimeUnit.SECONDS)
        .writeTimeout(30, TimeUnit.SECONDS)
        .retryOnConnectionFailure(true)
        .build()

    /** 下载/长轮询共用：读超时放宽（下载不设上限）。 */
    private val streamClient: OkHttpClient = client.newBuilder()
        .readTimeout(0, TimeUnit.SECONDS)
        .build()

    @Volatile
    var currentServer: String? = null
        private set

    @Volatile
    private var token: String? = null

    fun setSession(server: String, token: String?) {
        currentServer = normalizeServerBase(server).ifEmpty { null }
        this.token = token?.ifBlank { null }
    }

    fun clearSession() {
        token = null
    }

    fun hasToken(): Boolean = token != null

    fun authToken(): String? = token

    // ---- 认证 ----

    suspend fun login(base: String, password: String, deviceName: String): LoginResult {
        val normalizedBase = validatedServerBase(base)
        val body = JSONObject()
            .put("password", password)
            .put("deviceName", deviceName)
        val resp = call("POST", "/api/login", body, baseOverride = normalizedBase, auth = false)
        val device = resp.objOrNull("device")
        val deviceId = resp.strOrNull("deviceId") ?: device?.strOrNull("id") ?: ""
        val deviceLabel = resp.strOrNull("deviceName") ?: device?.strOrNull("name") ?: deviceName
        val host = resp.objOrNull("host")
        return LoginResult(
            token = resp.strOrNull("token") ?: throw ApiException("login-failed", "登录响应缺少 token"),
            device = DeviceInfo(id = deviceId, name = deviceLabel),
            mustChangePassword = resp.boolOr("mustChangePassword", false),
            host = parseHost(host),
        )
    }

    suspend fun me(): HostInfo {
        val resp = call("GET", "/api/me")
        val host = resp.objOrNull("host") ?: resp
        return parseHost(host)
    }

    /** 当前设备信息（/api/me 的 device 部分）；撤销当前设备后需要回登录页。 */
    suspend fun meDevice(): DeviceInfo? {
        val resp = call("GET", "/api/me")
        val device = resp.objOrNull("device") ?: return null
        val id = device.strOrNull("id") ?: return null
        return DeviceInfo(id = id, name = device.strOrNull("name") ?: "本机")
    }

    suspend fun logout() {
        try {
            call("POST", "/api/logout", JSONObject())
        } catch (_: Exception) {
            // 退出登录尽力而为：网络失败不阻塞本地清理
        } finally {
            clearSession()
        }
    }

    // ---- RPC ----

    /**
     * 调用网关 RPC 代理，返回上游 result.value。
     * 参数名按真实 DSH 协议由调用方提供（session/list 用 _request，其余多为 request）。
     */
    open suspend fun rpc(method: String, args: JSONObject): JSONObject {
        return (rpcRaw(method, args) as? JSONObject) ?: JSONObject()
    }

    /** RPC 返回数组的接口（如 commands/list）；非数组时返回空数组。 */
    open suspend fun rpcArray(method: String, args: JSONObject): JSONArray {
        return (rpcRaw(method, args) as? JSONArray) ?: JSONArray()
    }

    private suspend fun rpcRaw(method: String, args: JSONObject): Any? {
        val body = JSONObject().put("method", method).put("args", args)
        val resp = call("POST", "/api/rpc", body)
        if (!resp.boolOr("ok", true)) {
            val error = resp.objOrNull("error")
            throw ApiException(
                code = error?.strOrNull("code") ?: "rpc-failed",
                message = error?.strOrNull("message") ?: "请求失败",
            )
        }
        return resp.opt("value")
    }

    // ---- 事件流长轮询 ----

    open suspend fun openStream(endpoint: String, args: JSONObject): String {
        val body = JSONObject().put("endpoint", endpoint).put("args", args)
        val resp = call("POST", "/api/stream/open", body)
        return resp.strOrNull("streamId")
            ?: throw ApiException("stream-open-failed", "打开事件流失败")
    }

    open suspend fun pollStream(streamId: String, since: Long, waitMs: Long = 25000L): StreamPoll {
        val resp = call(
            "GET", "/api/stream/poll", null,
            query = mapOf(
                "streamId" to streamId,
                "since" to since.toString(),
                "wait" to waitMs.toString(),
            ),
        )
        val items = ArrayList<Pair<Long, JSONObject>>()
        val arr = resp.arrOrNull("items")
        if (arr != null) {
            for (i in 0 until arr.length()) {
                val item = arr.optJSONObject(i) ?: continue
                val seq = item.longOr("seq", -1L)
                val value = item.objOrNull("value") ?: continue
                if (seq >= 0) items.add(seq to value)
            }
        }
        return StreamPoll(
            items = items,
            next = resp.longOr("next", since),
            closed = resp.boolOr("closed", false),
            reset = resp.boolOr("reset", false),
            error = resp.strOrNull("error") ?: resp.objOrNull("error")?.strOrNull("message"),
        )
    }

    open suspend fun cancelStream(streamId: String) {
        try {
            call("POST", "/api/stream/cancel", JSONObject().put("streamId", streamId))
        } catch (_: Exception) {
            // 流已关闭/网络失败均可忽略
        }
    }

    // ---- 文件 ----

    suspend fun download(path: String): Response {
        val url = directUrl("/api/files/download", mapOf("path" to path))
        val builder = Request.Builder().url(url).get()
        token?.let { builder.header("Authorization", "Bearer $it") }
        return withContext(Dispatchers.IO) {
            streamClient.newCall(builder.build()).execute()
        }
    }

    suspend fun upload(dir: String, name: String, bytes: ByteArray): JSONObject {
        val url = directUrl("/api/files/upload", mapOf("path" to dir, "name" to name))
        val builder = Request.Builder().url(url)
            .post(bytes.toRequestBody(rawMedia))
            .header("x-content-sha256", sha256Hex(bytes))
        token?.let { builder.header("Authorization", "Bearer $it") }
        try {
            return withContext(Dispatchers.IO) {
                streamClient.newCall(builder.build()).execute().use { it ->
                    if (!it.isSuccessful) throw ApiException("upload-failed", "上传失败: HTTP ${it.code}", it.code)
                    Json.parseObject(it.body?.string()) ?: JSONObject()
                }
            }
        } catch (e: IOException) {
            throw ApiException("network-error", e.message ?: "网络错误")
        }
    }

    /**
     * 流式上传（独立审查 P1：大文件不得 readBytes 全量入内存）：
     * 由调用方提供 InputStream 工厂，RequestBody 内按 64KB 分块写入。
     * `contentLength` 传 -1 时使用 chunked 编码。
     */
    suspend fun uploadStream(
        dir: String,
        name: String,
        contentLength: Long,
        contentType: String?,
        openStream: () -> java.io.InputStream?,
    ): JSONObject {
        val url = directUrl("/api/files/upload", mapOf("path" to dir, "name" to name))
        val body = StreamingRequestBody(
            contentTypeValue = (contentType ?: "application/octet-stream").toMediaTypeOrNull(),
            length = contentLength,
            openStream = openStream,
        )
        val builder = Request.Builder().url(url).post(body)
        token?.let { builder.header("Authorization", "Bearer $it") }
        try {
            return withContext(Dispatchers.IO) {
                streamClient.newCall(builder.build()).execute().use { it ->
                    val text = it.body?.string().orEmpty()
                    val json = Json.parseObject(text) ?: JSONObject()
                    if (it.code == 401) {
                        throw UnauthorizedException(json.strOrNull("error") ?: "登录已过期，请重新登录")
                    }
                    if (!it.isSuccessful) throw httpError(it.code, json)
                    json
                }
            }
        } catch (e: IOException) {
            throw ApiException("network-error", e.message ?: "网络错误")
        }
    }

    // ---- 待处理 / 审批回答 ----

    suspend fun pending(): List<PendingRequest> {
        val resp = call("GET", "/api/pending")
        return Json.parsePendingItems(resp.arrOrNull("items"))
    }

    /**
     * 回答审批/提问。payload 为 {"kind":…,"value":…}（interfaces §5.3）：
     * 取 value 作为 /api/respond 的 outcome 原样发送（approval 为字符串，question 为对象）。
     */
    suspend fun respond(eventId: String, payload: JSONObject): Boolean {
        val outcome: Any? = if (payload.has("value")) {
            val value = payload.opt("value")
            if (value == JSONObject.NULL) JSONObject() else value
        } else {
            payload
        }
        val body = JSONObject().put("eventId", eventId).put("outcome", outcome)
        val resp = call("POST", "/api/respond", body)
        return resp.boolOr("accepted", resp.boolOr("ok", true))
    }

    // ---- REST（管理接口） ----

    suspend fun getJson(path: String): JSONObject = call("GET", path)

    suspend fun postJson(path: String, body: JSONObject = JSONObject()): JSONObject =
        call("POST", path, body)

    suspend fun deleteJson(path: String): JSONObject = call("DELETE", path)

    // ---- 内部实现 ----

    private fun parseHost(host: JSONObject?): HostInfo {
        return HostInfo(
            version = host?.strOrNull("version"),
            gatewayVersion = host?.strOrNull("gatewayVersion"),
        )
    }

    private fun directUrl(path: String, query: Map<String, String> = emptyMap()): HttpUrl {
        val base = currentServer ?: throw ApiException("no-server", "尚未配置服务器")
        val builder = (base + path).toHttpUrlOrNull()?.newBuilder()
            ?: throw ApiException("bad-server", "服务器地址无效")
        for ((key, value) in query) builder.addQueryParameter(key, value)
        return builder.build()
    }

    private suspend fun call(
        method: String,
        path: String,
        body: JSONObject? = null,
        query: Map<String, String> = emptyMap(),
        baseOverride: String? = null,
        auth: Boolean = true,
    ): JSONObject {
        val base = baseOverride ?: currentServer ?: throw ApiException("no-server", "尚未配置服务器")
        val url = (base + path).toHttpUrlOrNull()?.newBuilder()
            ?: throw ApiException("bad-server", "服务器地址无效")
        for ((key, value) in query) url.addQueryParameter(key, value)
        val builder = Request.Builder().url(url.build())
        when (method) {
            "GET" -> builder.get()
            "DELETE" -> builder.delete()
            else -> builder.post((body ?: JSONObject()).toString().toRequestBody(jsonMedia))
        }
        if (auth) {
            val current = token ?: throw UnauthorizedException()
            builder.header("Authorization", "Bearer $current")
        }
        val request = builder.build()
        // 关键：响应体读取也是网络 I/O，必须在 IO 线程完成；
        // 否则大响应（如 session/list）会触发 NetworkOnMainThreadException。
        try {
            return withContext(Dispatchers.IO) {
                client.newCall(request).execute().use { resp ->
                    val text = resp.body?.string().orEmpty()
                    val json = Json.parseObject(text) ?: JSONObject()
                    if (resp.code == 401) {
                        throw UnauthorizedException(json.strOrNull("error") ?: "登录已过期，请重新登录")
                    }
                    if (!resp.isSuccessful) {
                        throw httpError(resp.code, json)
                    }
                    json
                }
            }
        } catch (e: IOException) {
            throw ApiException("network-error", e.message ?: "网络错误")
        }
    }

    private fun httpError(status: Int, json: JSONObject): ApiException {
        val code = json.strOrNull("error")
            ?: json.objOrNull("error")?.strOrNull("code")
            ?: "http-$status"
        val message = json.strOrNull("message")
            ?: json.objOrNull("error")?.strOrNull("message")
            ?: when (status) {
                423 -> "尝试次数过多，已临时锁定"
                429 -> "请求过于频繁，请稍后再试"
                502 -> "上游服务不可用"
                403 -> "没有权限"
                404 -> "接口不存在"
                else -> "请求失败（HTTP $status）"
            }
        return ApiException(
            code = code,
            message = message,
            httpStatus = status,
            retryAfterMs = json.optLong("retryAfterMs", 0L).takeIf { it > 0 },
            until = json.optLong("until", 0L).takeIf { it > 0 },
        )
    }
}

private fun sha256Hex(bytes: ByteArray): String {
    val digest = java.security.MessageDigest.getInstance("SHA-256").digest(bytes)
    val out = StringBuilder(digest.size * 2)
    for (b in digest) {
        val v = b.toInt() and 0xff
        if (v < 16) out.append('0')
        out.append(Integer.toHexString(v))
    }
    return out.toString()
}

/** 供测试/调试使用：把任意 JSON 数组文本解析为对象列表。 */
internal fun parseObjectArray(text: String?): List<JSONObject> =
    Json.parseArray(text)?.toObjectList() ?: emptyList()

internal fun JSONObjectArrayOf(vararg items: JSONObject): JSONArray =
    JSONArray().also { arr -> items.forEach { arr.put(it) } }
