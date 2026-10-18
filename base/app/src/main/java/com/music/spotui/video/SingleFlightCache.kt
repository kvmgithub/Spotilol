package com.music.spotui.video

import java.util.concurrent.CompletableFuture
import java.util.concurrent.ExecutionException

/** Caller-owned synchronous loads; a cancelled owner never leaves a detached retry. */
internal class SingleFlightCache<K : Any, V : Any>(
    private val capacity: Int,
    private val ttlMs: Long,
    private val nowMs: () -> Long = { System.nanoTime() / 1_000_000 },
) {
    private data class Entry<V>(val value: V, val at: Long)
    private val lock = Any()
    private val values = LinkedHashMap<K, Entry<V>>(16, 0.75f, true)
    private val flights = HashMap<K, CompletableFuture<V>>()

    init { require(capacity > 0 && ttlMs >= 0) }

    internal val cachedSize: Int get() = synchronized(lock) { values.size }
    internal val inFlightCount: Int get() = synchronized(lock) { flights.size }

    fun get(key: K, fresh: Boolean = false, load: () -> V): V {
        checkInterrupted()
        val future: CompletableFuture<V>
        val owner: Boolean
        synchronized(lock) {
            val now = nowMs()
            values.entries.removeAll { (_, entry) -> now - entry.at !in 0 until ttlMs }
            val active = flights[key]
            if (active != null) {
                future = active
                owner = false
            } else {
                if (!fresh) values[key]?.let { return it.value }
                // A forced refresh normally follows a refused URL; do not reuse it after failure.
                if (fresh) values.remove(key)
                future = CompletableFuture()
                flights[key] = future
                owner = true
            }
        }
        if (!owner) {
            try {
                return future.get().also { checkInterrupted() }
            } catch (t: InterruptedException) {
                Thread.currentThread().interrupt()
                throw t
            } catch (t: ExecutionException) {
                throw t.cause ?: t
            }
        }
        try {
            val value = load()
            checkInterrupted()
            synchronized(lock) {
                if (ttlMs > 0) values[key] = Entry(value, nowMs())
                while (values.size > capacity) values.remove(values.keys.first())
                flights.remove(key)
                future.complete(value)
            }
            return value
        } catch (t: Throwable) {
            if (t is InterruptedException) Thread.currentThread().interrupt()
            synchronized(lock) {
                flights.remove(key)
                future.completeExceptionally(t)
            }
            throw t
        }
    }
}

internal fun checkInterrupted() {
    if (Thread.currentThread().isInterrupted) throw InterruptedException("Video metadata request interrupted")
}
