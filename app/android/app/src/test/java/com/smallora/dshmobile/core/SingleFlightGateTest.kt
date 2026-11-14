package com.smallora.dshmobile.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.Collections
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger

/**
 * 并发单飞门闩单测（独立审查 P2）：
 * 并发 5 次 ensure → 只创建/启动 1 次 `$events` 泵（即只 open 1 条流）。
 */
class SingleFlightGateTest {

    @Test
    fun concurrentGetCreatesOnlyOnce() {
        val openCount = AtomicInteger(0)
        val gate = SingleFlightGate {
            openCount.incrementAndGet()
            Any()
        }

        val threads = 5
        val ready = CountDownLatch(threads)
        val start = CountDownLatch(1)
        val results = Collections.synchronizedList(ArrayList<Any>())
        val pool = Executors.newFixedThreadPool(threads)
        try {
            repeat(threads) {
                pool.submit {
                    ready.countDown()
                    start.await()
                    results.add(gate.get())
                }
            }
            assertTrue("线程应全部就绪", ready.await(5, TimeUnit.SECONDS))
            start.countDown() // 同时放行，最大化竞态
            pool.shutdown()
            assertTrue("线程应全部结束", pool.awaitTermination(10, TimeUnit.SECONDS))
        } finally {
            pool.shutdownNow()
        }

        assertEquals("并发 ensure 只能 open 一次", 1, openCount.get())
        assertEquals(threads, results.size)
        val instance = results.first()
        results.forEach { assertSame("所有调用者应拿到同一个泵实例", instance, it) }
    }

    @Test
    fun sequentialCallsDoNotRecreate() {
        val openCount = AtomicInteger(0)
        val gate = SingleFlightGate {
            openCount.incrementAndGet()
            Any()
        }
        val first = gate.get()
        val second = gate.get()
        assertSame(first, second)
        assertEquals(1, openCount.get())
    }

    @Test
    fun peekIsNullBeforeCreationAndClearAllowsRecreate() {
        val openCount = AtomicInteger(0)
        val gate = SingleFlightGate {
            openCount.incrementAndGet()
            Any()
        }
        assertNull(gate.peek())
        val first = gate.get()
        assertSame(first, gate.peek())

        gate.clear()
        assertNull(gate.peek())
        val second = gate.get()
        assertEquals("clear 后应允许重新 open（退出登录/切换服务器）", 2, openCount.get())
        assertTrue(first !== second)
    }
}
