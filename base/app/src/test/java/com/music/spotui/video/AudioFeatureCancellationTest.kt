package com.music.spotui.video

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.concurrent.atomic.AtomicReference

class AudioFeatureCancellationTest {
    @Test fun interruptedWorkerAbortsFeatureExtraction() {
        val failure = AtomicReference<Throwable?>()
        val worker = Thread {
            Thread.currentThread().interrupt()
            try {
                AudioAlign.features(FloatArray(11_025 * 10))
                failure.set(AssertionError("Interrupted feature extraction completed instead of aborting"))
            } catch (t: Throwable) {
                failure.set(t)
            }
        }
        worker.start()
        worker.join(5_000)
        assertFalse("Interrupted worker must terminate", worker.isAlive)
        assertTrue("Expected InterruptedException, got ${failure.get()}", failure.get() is InterruptedException)
    }
}
