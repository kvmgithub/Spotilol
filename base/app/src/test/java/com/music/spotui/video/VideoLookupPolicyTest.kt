package com.music.spotui.video

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class VideoLookupPolicyTest {
    @Test fun normalResumeMayWarmTheCurrentTrack() {
        assertTrue(videoLookupOnResumeAllowed(allowAutomatic = true, wantVideo = false, videoOn = false, playerVisible = false))
    }

    @Test fun saverSkipsAnAutomaticLookupOnAppResume() {
        assertFalse(videoLookupOnResumeAllowed(allowAutomatic = false, wantVideo = false, videoOn = false, playerVisible = false))
    }

    @Test fun requestedVideoCanResumeAndFollowTracksInSaver() {
        assertTrue(videoLookupOnResumeAllowed(allowAutomatic = false, wantVideo = true, videoOn = false, playerVisible = false))
    }

    @Test fun alreadyPlayingVideoCanResumeInSaver() {
        assertTrue(videoLookupOnResumeAllowed(allowAutomatic = false, wantVideo = false, videoOn = true, playerVisible = false))
        assertTrue(videoLookupOnResumeAllowed(allowAutomatic = false, wantVideo = true, videoOn = true, playerVisible = false))
    }

    @Test fun visiblePlayerCanResumeItsLookupInSaverWithoutVideoMode() {
        assertTrue(videoLookupOnResumeAllowed(allowAutomatic = false, wantVideo = false, videoOn = false, playerVisible = true))
    }
}
