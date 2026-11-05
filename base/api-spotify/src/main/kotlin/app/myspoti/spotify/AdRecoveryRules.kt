package app.myspoti.spotify

import app.myspoti.api.Uri

/**
 * What the player plays after it got past an ad, without the page (Myspoti 2.4.0). The fork has
 * no Connect mirror, so only the rules that hold without it are kept here.
 */
object AdRecoveryRules {
    /**
     * The songs of the user's queue [queued] (before our reload) to add again to the new page's:
     * all in their order, but for the one now [playing] (played on from the queue) and those the
     * page lists already ([has]). Reported: the queue was gone after an ad got past by a reload (#144).
     */
    fun requeued(queued: List<Uri>, playing: Uri?, has: Collection<Uri>): List<Uri> {
        // Played on from the queue: that song and the ones before it in the queue are done.
        val from = queued.indexOf(playing).let { if (it >= 0) it + 1 else 0 }
        // One restored occurrence accounts for one queued occurrence, not every copy of its URI.
        val counts = has.groupingBy { it }.eachCount().toMutableMap()
        return queued.drop(from).filter { uri ->
            val count = counts[uri] ?: 0
            if (count == 0) true else { counts[uri] = count - 1; false }
        }
    }
}
