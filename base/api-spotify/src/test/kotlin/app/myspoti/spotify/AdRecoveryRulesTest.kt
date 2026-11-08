package app.myspoti.spotify

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test

class AdRecoveryRulesTest {
    @Test fun theUsersQueueIsAddedAgainAfterOurReload() {
        val q = listOf("t:a", "t:b", "t:c")
        // Played on from the queue's first song: the others come again, in their order.
        assertEquals(listOf("t:b", "t:c"), AdRecoveryRules.requeued(q, playing = "t:a", has = emptySet()))
        // The song before the ad played on (not from the queue): all of it.
        assertEquals(q, AdRecoveryRules.requeued(q, playing = "t:x", has = emptySet()))
        // What the new page lists already is not added twice.
        assertEquals(listOf("t:c"), AdRecoveryRules.requeued(q, playing = "t:a", has = setOf("t:b")))
        assertEquals(emptyList<String>(), AdRecoveryRules.requeued(q, playing = "t:c", has = emptySet()))
    }

    @Test fun aRestoredOccurrenceDoesNotRemoveEveryDuplicateFromTheQueue() {
        val q = listOf("t:a", "t:b", "t:b", "t:c")
        assertEquals(listOf("t:b", "t:c"), AdRecoveryRules.requeued(q, playing = "t:a", has = listOf("t:b")))
        assertEquals(listOf("t:c"), AdRecoveryRules.requeued(q, playing = "t:a", has = listOf("t:b", "t:b")))
        assertEquals(listOf("t:b", "t:b", "t:c"), AdRecoveryRules.requeued(q, playing = "t:a", has = emptyList()))
    }

    @Test fun aResumedSongIsNotTakenAsTheQueuedCopyOfIt() {
        // Album song A resumes after the reload, the queue was [B, A, C]: all of it comes again.
        val q = listOf("t:b", "t:a", "t:c")
        assertEquals(q, AdRecoveryRules.requeued(q, playing = "t:a", has = emptySet(), playedOn = false))
        // Played on from the queue's head: only that one is done.
        assertEquals(listOf("t:a", "t:c"), AdRecoveryRules.requeued(q, playing = "t:b", has = emptySet(), playedOn = true))
    }
}
