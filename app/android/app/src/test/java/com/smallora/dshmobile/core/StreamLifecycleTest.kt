package com.smallora.dshmobile.core

import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.atomic.AtomicInteger

/**
 * 会话事件流生命周期单测（独立审查 P1：follow 流泄漏）。
 *
 * 用假 ApiClient 记录 `openStream/cancelStream` 次数，验证：
 * - 同一 session 重复 open 只开一条流（幂等复用）；
 * - release 后活跃流归零，再次 open 会新开一条；
 * - 连续 open 5 个会话再 close，活跃 follow 流 = 0（不会顶到网关 20 条上限）。
 */
class StreamLifecycleTest {

    private class FakeStreamApi : ApiClient() {
        val opened = CopyOnWriteArrayList<String>()
        val cancelled = CopyOnWriteArrayList<String>()
        private val ids = AtomicInteger()

        override suspend fun openStream(endpoint: String, args: JSONObject): String {
            opened.add(endpoint)
            return "stream-${ids.incrementAndGet()}"
        }

        override suspend fun pollStream(streamId: String, since: Long, waitMs: Long): StreamPoll {
            delay(20) // 模拟长轮询，避免空转
            return StreamPoll(emptyList(), since, closed = false, reset = false, error = null)
        }

        override suspend fun cancelStream(streamId: String) {
            cancelled.add(streamId)
        }

        fun opens(endpoint: String): Int = opened.count { it == endpoint }

        fun activeStreams(): Int = opened.size - cancelled.size
    }

    private fun await(timeoutMs: Long = 5_000, condition: () -> Boolean) {
        val deadline = System.currentTimeMillis() + timeoutMs
        while (System.currentTimeMillis() < deadline) {
            if (condition()) return
            Thread.sleep(10)
        }
        assertTrue("等待条件超时", condition())
    }

    @Test
    fun openIsIdempotentAndReleaseAllowsReopen() = runBlocking {
        val api = FakeStreamApi()
        val repo = SessionRepository(api, null)
        try {
            repo.openSession("s1")
            await { repo.activeFollowCount() == 1 && api.opens("session/follow") == 1 && api.opens("\$events") == 1 }
            assertEquals("\$events 只应开一条", 1, api.opens("\$events"))
            assertTrue(repo.eventsStreamOpen())

            // 重复 open（DisposableEffect 重进/LaunchedEffect 重跑）不应叠加订阅
            repo.openSession("s1")
            repo.openSession("s1")
            Thread.sleep(120)
            assertEquals("重复 open 不得重复开流", 1, api.opens("session/follow"))
            assertEquals(1, repo.activeFollowCount())

            repo.releaseSession("s1")
            await { repo.activeFollowCount() == 0 }
            assertEquals("release 后不应残留 follow 流", 0, repo.activeFollowCount())

            // 再次进入聊天页：应新开一条（旧流已取消）
            repo.openSession("s1")
            await { api.opens("session/follow") == 2 && repo.activeFollowCount() == 1 }
        } finally {
            repo.stop()
        }
        await { api.cancelled.size == api.opened.size }
        assertEquals("stop 后所有流都应取消", 0, api.activeStreams())
    }

    @Test
    fun fiveSessionsClosedLeavesNoActiveStream() = runBlocking {
        val api = FakeStreamApi()
        val repo = SessionRepository(api, null)
        try {
            repeat(5) { index -> repo.openSession("s$index") }
            await { repo.activeFollowCount() == 5 && api.opens("session/follow") == 5 }
            assertEquals("\$events 仍只一条", 1, api.opens("\$events"))

            repeat(5) { index -> repo.releaseSession("s$index") }
            await { repo.activeFollowCount() == 0 }
            assertEquals("5 个会话关闭后活跃 follow 流必须为 0", 0, repo.activeFollowCount())
            // 会话流关闭不影响全局 $events
            assertTrue(repo.eventsStreamOpen())
        } finally {
            repo.stop()
        }
        await { api.cancelled.size == api.opened.size }
        assertEquals(0, api.activeStreams())
    }

    @Test
    fun duplicateReleaseIsSafe() = runBlocking {
        val api = FakeStreamApi()
        val repo = SessionRepository(api, null)
        try {
            repo.openSession("s1")
            await { repo.activeFollowCount() == 1 }
            repo.releaseSession("s1")
            repo.releaseSession("s1") // 幂等
            await { repo.activeFollowCount() == 0 }
            repo.closeSession("not-opened") // 未打开过的会话也安全
        } finally {
            repo.stop()
        }
        await { api.cancelled.size == api.opened.size }
    }
}
