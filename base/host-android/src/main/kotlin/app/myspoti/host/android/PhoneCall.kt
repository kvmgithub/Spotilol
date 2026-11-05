package app.myspoti.host.android

import android.content.Context

/**
 * The phone ringing or in a call, from the audio mode (Android 12+), told on a thread of its own
 * (Myspoti 2.4.0). The Web Player's audio holds no audio focus, so a call only muted the song,
 * which went on unheard: the app pauses it itself (SongPlayer.phoneCall).
 */
object PhoneCall {
    @Volatile var listener: ((busy: Boolean) -> Unit)? = null
    @Volatile private var installed = false

    fun install(context: Context) {
        if (installed || android.os.Build.VERSION.SDK_INT < 31) return
        installed = true
        runCatching {
            val audio = context.getSystemService(android.media.AudioManager::class.java)
            audio.addOnModeChangedListener(java.util.concurrent.Executors.newSingleThreadExecutor()) { mode ->
                val name = when (mode) {
                    android.media.AudioManager.MODE_NORMAL -> "normal"
                    android.media.AudioManager.MODE_RINGTONE -> "ringing"
                    android.media.AudioManager.MODE_IN_CALL -> "in a call"
                    android.media.AudioManager.MODE_IN_COMMUNICATION -> "in a voice/video call (app)"
                    android.media.AudioManager.MODE_CALL_SCREENING -> "call screening"
                    else -> "mode $mode"
                }
                Logbook.log("audio mode: $name")
                listener?.invoke(mode != android.media.AudioManager.MODE_NORMAL)
            }
        }.onFailure { Logbook.log("audio mode not watched: ${it.javaClass.simpleName}") }
    }
}
