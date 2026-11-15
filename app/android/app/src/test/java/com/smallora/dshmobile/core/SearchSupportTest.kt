package com.smallora.dshmobile.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** 搜索不可用的识别/提示文案（当前主机未挂 session-query 时不应把英文原文弹给用户）。 */
class SearchSupportTest {

    @Test
    fun disabledMessageFromGatewayIsRecognized() {
        val e = ApiException(
            code = "gateway/internal",
            message = "session search failed: SessionQueryError: session search is disabled: " +
                "this deployment does not mount @deepseek-ai/dsh-session-query",
        )
        assertEquals(SEARCH_UNAVAILABLE_MESSAGE, searchUnavailableReason(e))
    }

    @Test
    fun unavailableWordingsAreRecognized() {
        assertTrue(searchUnavailableReason(ApiException("gateway/internal", "session-query missing")) != null)
        assertTrue(searchUnavailableReason(ApiException("x", "SESSION SEARCH IS UNAVAILABLE")) != null)
    }

    @Test
    fun genuineSearchErrorsAreNotMasked() {
        assertNull(searchUnavailableReason(ApiException("gateway/bad-request", "session search query must not be empty")))
        assertNull(searchUnavailableReason(ApiException("upstream-unavailable", "upstream unreachable")))
        assertNull(searchUnavailableReason(IllegalStateException("boom")))
    }

    @Test
    fun exceptionWithoutMessageIsNotMasked() {
        assertNull(searchUnavailableReason(RuntimeException()))
    }
}
