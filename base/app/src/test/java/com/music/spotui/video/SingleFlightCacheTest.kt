package com.music.spotui.video

import org.junit.Assert.*
import org.junit.Test
import java.io.IOException
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Semaphore
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicLong
import java.util.concurrent.atomic.AtomicReference

class SingleFlightCacheTest {
    @Test fun parallelReadersShareOneLoadAndWarmHitsHaveNoBackendCalls() {
        val cache = SingleFlightCache<String, String>(2, 1_000)
        val calls = AtomicInteger()
        val workers = List(8) { worker { cache.get("video") { calls.incrementAndGet(); Thread.sleep(40); "metadata" } } }
        workers.forEach { it.start() }; workers.forEach { it.joinChecked() }
        assertEquals(1, calls.get())
        workers.forEach { assertEquals("metadata", it.result.get()) }
        assertEquals("metadata", cache.get("video") { fail("Warm cache must not call the backend"); "wrong" })
        assertEquals(1, cache.cachedSize)
        assertEquals(0, cache.inFlightCount)
    }

    @Test fun fakeMonotonicClockExpiresAtExactTtlAndLruIsBounded() {
        val clock = AtomicLong(0)
        val cache = SingleFlightCache<String, Int>(2, 100, clock::get)
        val calls = AtomicInteger()
        fun get(key: String) = cache.get(key) { calls.incrementAndGet() }
        assertEquals(1, get("a")); assertEquals(2, get("b")); assertEquals(1, get("a"))
        assertEquals(3, get("c")); assertEquals(2, cache.cachedSize)
        assertEquals(4, get("b")) // a was accessed; b was evicted.
        clock.set(99); assertEquals(4, get("b"))
        clock.set(100); assertEquals(5, get("b"))
        assertEquals(1, cache.cachedSize) // Expired entries are removed, not retained forever.
    }

    @Test fun forcedRefreshJoinsAnActiveRefreshAndNormalReadersJoinItToo() {
        val cache = SingleFlightCache<String, String>(2, 10_000)
        assertEquals("old", cache.get("v") { "old" })
        val calls = AtomicInteger()
        val entered = CountDownLatch(1)
        val release = CountDownLatch(1)
        val owner = worker { cache.get("v", fresh = true) { calls.incrementAndGet(); entered.countDown(); release.await(); "new" } }
        owner.start(); assertTrue(entered.await(3, TimeUnit.SECONDS))
        val fresh = worker { cache.get("v", fresh = true) { calls.incrementAndGet(); "wrong" } }
        val normal = worker { cache.get("v") { calls.incrementAndGet(); "wrong" } }
        fresh.start(); normal.start(); awaitBlocked(fresh, normal)
        release.countDown(); owner.joinChecked(); fresh.joinChecked(); normal.joinChecked()
        assertEquals(1, calls.get())
        listOf(owner, fresh, normal).forEach { assertEquals("new", it.result.get()) }
    }

    @Test fun failuresAreNotCachedAndFailedForceRefreshDoesNotRestoreAnOldUrl() {
        val cache = SingleFlightCache<String, String>(2, 10_000)
        cache.get("v") { "expired-url" }
        try { cache.get("v", fresh = true) { throw IOException("403") }; fail("Expected failure") }
        catch (expected: IOException) { assertEquals("403", expected.message) }
        assertEquals(0, cache.cachedSize); assertEquals(0, cache.inFlightCount)
        assertEquals("new-url", cache.get("v") { "new-url" })
    }

    @Test fun interruptedWaiterLeavesOwnerAliveWithoutStartingAnotherBackendCall() {
        val cache = SingleFlightCache<String, String>(2, 10_000)
        val calls = AtomicInteger(); val entered = CountDownLatch(1); val release = CountDownLatch(1)
        val owner = worker { cache.get("v") { calls.incrementAndGet(); entered.countDown(); release.await(); "value" } }
        owner.start(); assertTrue(entered.await(3, TimeUnit.SECONDS))
        val waiter = worker { cache.get("v") { calls.incrementAndGet(); "wrong" } }
        waiter.start(); awaitBlocked(waiter); waiter.interrupt(); waiter.joinChecked(allowFailure = true)
        assertTrue(waiter.error.get() is InterruptedException)
        assertTrue(owner.isAlive); assertEquals(1, calls.get())
        release.countDown(); owner.joinChecked()
        assertEquals("value", owner.result.get()); assertEquals(1, calls.get())
    }

    @Test fun interruptedOwnerFailsSharedFlightWithoutRetryOrCachingAndLaterRequestCanRetry() {
        val cache = SingleFlightCache<String, String>(2, 10_000)
        val calls = AtomicInteger(); val entered = CountDownLatch(1); val release = CountDownLatch(1)
        val owner = worker { cache.get("v") { calls.incrementAndGet(); entered.countDown(); release.await(); "wrong" } }
        owner.start(); assertTrue(entered.await(3, TimeUnit.SECONDS))
        val waiter = worker { cache.get("v") { calls.incrementAndGet(); "wrong" } }
        waiter.start(); awaitBlocked(waiter); owner.interrupt()
        owner.joinChecked(allowFailure = true); waiter.joinChecked(allowFailure = true)
        assertTrue(owner.error.get() is InterruptedException); assertTrue(waiter.error.get() is InterruptedException)
        assertEquals(1, calls.get()); assertEquals(0, cache.cachedSize); assertEquals(0, cache.inFlightCount)
        assertEquals("retry", cache.get("v") { calls.incrementAndGet(); "retry" }); assertEquals(2, calls.get())
    }

    @Test fun preInterruptedCallerDoesNotLoadOrReadEvenAWarmCache() {
        val cache = SingleFlightCache<String, String>(2, 1_000)
        cache.get("v") { "warm" }
        val calls = AtomicInteger()
        val reader = worker { Thread.currentThread().interrupt(); cache.get("v") { calls.incrementAndGet(); "wrong" } }
        reader.start(); reader.joinChecked(allowFailure = true)
        assertTrue(reader.error.get() is InterruptedException); assertEquals(0, calls.get())
    }

    @Test fun benchmarkSharedMetadataOnTwoSlotFakeBackend() {
        fun batch(cached: Boolean): Pair<Long, Int> {
            val slots = Semaphore(2)
            val calls = AtomicInteger()
            val cache = SingleFlightCache<String, String>(2, 10_000)
            val start = CountDownLatch(1)
            fun backend(): String {
                slots.acquire()
                try { calls.incrementAndGet(); Thread.sleep(75); return "metadata" }
                finally { slots.release() }
            }
            val workers = List(8) { worker { start.await(); if (cached) cache.get("v", load = ::backend) else backend() } }
            workers.forEach { it.start() }; val t0 = System.nanoTime(); start.countDown()
            workers.forEach { it.joinChecked() }
            return (System.nanoTime() - t0) / 1_000_000 to calls.get()
        }
        val raw = batch(false); val shared = batch(true)
        assertEquals(8, raw.second); assertEquals(1, shared.second)
        println("FAKE_METADATA_BENCH clients=8 backendSlots=2 backendDelayMs=75 rawMs=${raw.first} rawCalls=${raw.second} sharedMs=${shared.first} sharedCalls=${shared.second}")
    }

    private class Worker(block: () -> String) : Thread() {
        val result = AtomicReference<String?>(); val error = AtomicReference<Throwable?>()
        private val task = block
        init { isDaemon = true }
        override fun run() { try { result.set(task()) } catch (t: Throwable) { error.set(t) } }
        fun joinChecked(allowFailure: Boolean = false) {
            join(4_000); assertFalse("Worker did not finish", isAlive)
            if (!allowFailure) error.get()?.let { throw AssertionError("Worker failed", it) }
        }
    }
    private fun worker(block: () -> String) = Worker(block)
    private fun awaitBlocked(vararg workers: Worker) {
        val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(3)
        while (workers.any { it.state != Thread.State.WAITING } && System.nanoTime() < deadline) Thread.sleep(1)
        assertTrue("Waiters must have joined the active flight", workers.all { it.state == Thread.State.WAITING })
    }
}
