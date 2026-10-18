package com.music.spotui.video

import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

class RefinementFilesTest {
    @get:Rule val temp = TemporaryFolder()

    @Test fun retainedPairKeepsContentsUntilOwnerCloses() {
        val ref = temp.newFile("reference").apply { writeText("reference PCM container") }
        val cand = temp.newFile("candidate").apply { writeText("candidate PCM container") }
        val owned = RefinementFiles.retain(ref, cand, temp.root)!!
        assertFalse(ref.exists())
        assertFalse(cand.exists())
        assertEquals("reference PCM container", owned.reference.readText())
        assertEquals("candidate PCM container", owned.candidate.readText())
        owned.close()
        owned.close()
        assertFalse(owned.reference.exists())
        assertFalse(owned.candidate.exists())
    }

    @Test fun secondRenameFailureRemovesAlreadyMovedReference() {
        val ref = temp.newFile("reference").apply { writeText("reference") }
        val absent = File(temp.root, "missing-candidate")
        assertNull(RefinementFiles.retain(ref, absent, temp.root))
        assertTrue("Failed ownership transfer must leave no mv_full file", temp.root.listFiles()!!.none { it.name.startsWith("mv_full_") })
    }

    @Test fun twoRunsOfSameTrackDoNotSharePathsOrCleanup() {
        val first = RefinementFiles.empty(temp.root)
        val second = RefinementFiles.empty(temp.root)
        assertNotEquals(first.reference, second.reference)
        assertNotEquals(first.candidate, second.candidate)
        first.reference.writeText("first")
        first.candidate.writeText("first")
        second.reference.writeText("second")
        second.candidate.writeText("second")
        first.close()
        assertEquals("second", second.reference.readText())
        assertEquals("second", second.candidate.readText())
        second.close()
    }
}
