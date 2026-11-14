package com.smallora.dshmobile.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 服务器地址归一化单测（独立审查 P1 / SPEC §5.2 信任边界）：
 * 无 scheme 时只有本机/私有网段允许明文 http，公网域名与 IP 必须默认 https。
 */
class ServerBaseTest {

    @Test
    fun publicHostWithoutSchemeDefaultsToHttps() {
        assertEquals("https://dsh.example.com", normalizeServerBase("dsh.example.com"))
        assertEquals("https://dsh.example.com:8790", normalizeServerBase("dsh.example.com:8790"))
        assertEquals("https://8.8.8.8", normalizeServerBase("8.8.8.8"))
        assertEquals("https://8.8.8.8:8790", normalizeServerBase("8.8.8.8:8790"))
        assertEquals("https://[2001:db8::1]:8790", normalizeServerBase("[2001:db8::1]:8790"))
    }

    @Test
    fun loopbackAndPrivateNetworksDefaultToHttp() {
        assertEquals("http://localhost", normalizeServerBase("localhost"))
        assertEquals("http://localhost:8790", normalizeServerBase("localhost:8790"))
        assertEquals("http://127.0.0.1:8790", normalizeServerBase("127.0.0.1:8790"))
        assertEquals("http://127.1.2.3", normalizeServerBase("127.1.2.3"))
        assertEquals("http://10.0.0.5:8790", normalizeServerBase("10.0.0.5:8790"))
        assertEquals("http://172.16.0.1", normalizeServerBase("172.16.0.1"))
        assertEquals("http://172.31.255.255", normalizeServerBase("172.31.255.255"))
        assertEquals("http://192.168.31.170:8790", normalizeServerBase("192.168.31.170:8790"))
        assertEquals("http://169.254.10.20", normalizeServerBase("169.254.10.20"))
        // CGNAT / Tailscale
        assertEquals("http://100.64.0.1:8790", normalizeServerBase("100.64.0.1:8790"))
    }

    @Test
    fun ipv6LoopbackAndUniqueLocalDefaultToHttp() {
        assertEquals("http://[::1]:8790", normalizeServerBase("[::1]:8790"))
        assertEquals("http://[fe80::1]:8790", normalizeServerBase("[fe80::1]:8790"))
        assertEquals("http://[fd00::1]:8790", normalizeServerBase("[fd00::1]:8790"))
        assertTrue(isPrivateHost("::1"))
        assertFalse(isPrivateHost("2001:db8::1"))
    }

    @Test
    fun explicitSchemeIsPreservedAndTrailingSlashTrimmed() {
        assertEquals("http://192.168.1.10:8790", normalizeServerBase("http://192.168.1.10:8790/"))
        assertEquals("https://dsh.example.com", normalizeServerBase("https://dsh.example.com/"))
        // 显式 http:// 的公网地址不强制改 https（用户明确选择，风险自负）
        assertEquals("http://dsh.example.com", normalizeServerBase("http://dsh.example.com"))
        assertEquals("https://Dsh.Example.com", normalizeServerBase("https://Dsh.Example.com/"))
    }

    @Test
    fun invalidInputsReturnEmptyAndLoginReportsBadServer() {
        assertEquals("", normalizeServerBase(""))
        assertEquals("", normalizeServerBase("   "))
        assertEquals("", normalizeServerBase("ftp://example.com"))
        assertEquals("", normalizeServerBase("ws://192.168.1.10:8790"))
        assertEquals("", normalizeServerBase("http://"))
        assertEquals("", normalizeServerBase("https:///"))
        assertEquals("", normalizeServerBase("http://bad host"))
        assertEquals("", normalizeServerBase("not a url"))

        // 非法地址在尝试登录前就被拒绝（不会把口令发到任何地址）
        val error = runCatching { validatedServerBase("ftp://example.com") }.exceptionOrNull()
        assertTrue("非法地址应抛 ApiException，实际: $error", error is ApiException)
        assertEquals("bad-server", (error as ApiException).code)
        assertEquals("https://dsh.example.com", validatedServerBase("dsh.example.com"))
    }

    @Test
    fun publicHostsOutsidePrivateRangesAreNotPrivate() {
        assertFalse(isPrivateHost("dsh.example.com"))
        assertFalse(isPrivateHost("8.8.8.8"))
        assertFalse(isPrivateHost("172.32.0.1")) // 172.16/12 之外
        assertFalse(isPrivateHost("192.169.1.1"))
        assertFalse(isPrivateHost("100.128.0.1")) // CGNAT 之外
        assertTrue(isPrivateHost("172.20.0.1"))
    }

    @Test
    fun hostExtractionHandlesPortsBracketsAndUserInfo() {
        assertEquals("192.168.1.10", extractHost("192.168.1.10:8790"))
        assertEquals("dsh.example.com", extractHost("dsh.example.com:443"))
        assertEquals("::1", extractHost("[::1]:8790"))
        assertEquals("example.com", extractHost("user:pass@example.com:443"))
        assertEquals("example.com", extractHost("example.com/path?x=1"))
    }
}
