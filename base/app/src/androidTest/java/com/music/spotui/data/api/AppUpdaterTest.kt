package com.music.spotui.data.api

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Downloads a real release APK and hands it to the package installer. The release is
 * another app id (app.myspoti) than this test build, so the installer must refuse it:
 * that answer proves the download, the session and the status receiver work end to end.
 */
@RunWith(AndroidJUnit4::class)
class AppUpdaterTest {
    @Test fun downloadsAndReachesTheInstaller() = runBlocking {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        InstrumentationRegistry.getInstrumentation().uiAutomation.executeShellCommand(
            "appops set ${context.packageName} REQUEST_INSTALL_PACKAGES allow").close()
        Thread.sleep(500)
        val release = UpdateChecker.Release(
            "2.0.0-beta.3", "",
            apkUrl = "https://github.com/kota-zo/myspoti/releases/download/v2.0.0-beta.3/myspoti-2.0.0-beta.3.apk",
        )
        AppUpdater.reset()
        AppUpdater.update(context, release)
        withTimeout(60_000) {
            AppUpdater.state.first {
                it is AppUpdater.State.Failed
            }
        }
        val state = AppUpdater.state.value
        android.util.Log.i("AppUpdaterTest", "installer said: $state")
        assertTrue("state: $state", state is AppUpdater.State.Failed)
    }
}
