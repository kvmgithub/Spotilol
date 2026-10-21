package com.music.spotui.di

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.util.Log
import kotlinx.coroutines.launch
import kotlinx.serialization.json.jsonObject

/**
 * Debug builds only: the playback test (tools/playback_test.py) sends the app's own
 * commands, as a tap would, with
 * `adb shell am broadcast -a app.myspoti.TEST -p app.myspoti.dev --es cmd <cmd> ...`.
 * cmd: play (--es track <uri> --es context <uri>), resume, pause, next, previous, seek (--el ms <n>).
 * cmd: screenon (--ez on false to release) holds the screen on for a long run: One UI does not
 * honour the huge `screen_off_timeout` set over adb, the window flag does.
 * cmd: uitrace (--ei secs <n>, default 30) logs, 5 times a second under the tag MspTrace, what the
 * app shows (position, duration, playing, title, cover) next to what the page plays, so a run
 * can tell a jumping bar or a stale title/cover from the engine's own state (tools/qa.py --ui).
 * cmd: crossfade (--ei secs <0..12>) sets the crossfade between songs, as the Settings slider does.
 * cmd: gql (--es op <operation> --es vars <json>) runs a persisted operation through the app's own
 * Gql layer and logs the raw response: learning a server shape from the phone's real account beats
 * guessing one from a bundle.
 */
class TestControlReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val cmd = intent.getStringExtra("cmd").orEmpty()
        Log.i("MspPlayer", "test command $cmd")
        when (cmd) {
            "gql" -> {
                val op = intent.getStringExtra("op").orEmpty()
                val variables = intent.getStringExtra("vars").orEmpty().ifBlank { "{}" }
                app.myspoti.host.android.MyspotiRuntime.scope.launch {
                    runCatching {
                        app.myspoti.host.android.MyspotiRuntime.spotify.raw.gql(
                            op,
                            app.myspoti.spotify.bridge.Bridge.json.parseToJsonElement(variables).jsonObject,
                        )
                    }.onSuccess { Log.i("MspPlayer", "gql $op vars=$variables -> ${it.toString().take(4000)}") }
                        .onFailure { Log.i("MspPlayer", "gql $op vars=$variables failed: $it") }
                }
            }
            "play" -> SongPlayer.playSong(intent.getStringExtra("track").orEmpty(), contextUri = intent.getStringExtra("context"))
            "resume" -> SongPlayer.play()
            "pause" -> SongPlayer.pause()
            "next" -> SongPlayer.next()
            // The player's own "next" button and the mini player's swipe.
            "swipenext" -> SongPlayer.swipeNext()
            "previous" -> SongPlayer.previous()
            "crossfade" -> com.music.spotui.data.preferences.CrossfadePref.set(context, intent.getIntExtra("secs", 0))
            "uitrace" -> {
                val secs = intent.getIntExtra("secs", 30).coerceIn(1, 600)
                app.myspoti.host.android.MyspotiRuntime.scope.launch(kotlinx.coroutines.Dispatchers.Main) {
                    val song = Graph.currentSongState
                    val end = android.os.SystemClock.elapsedRealtime() + secs * 1000L
                    while (android.os.SystemClock.elapsedRealtime() < end) {
                        val now = System.currentTimeMillis()
                        val e = app.myspoti.host.android.MyspotiRuntime.spotify.player.state.value
                        Log.i("MspTrace", "t=${android.os.SystemClock.elapsedRealtime()} ui_pos=${SongPlayer.getCurrentPosition()} " +
                            "ui_dur=${SongPlayer.getDuration()} ui_play=${SongPlayer.isPlaying()} ui_uri=${SongPlayer.nowPlaying.value.uri} " +
                            "ui_cover=${song.coverUri.value.substringAfterLast('/')} eng_uri=${e?.item?.uri} " +
                            "eng_pos=${e?.positionAt(now)} eng_paused=${e?.isPaused} eng_buf=${e?.isBuffering} " +
                            "ui_title=${song.title.value} | eng_title=${e?.item?.name}")
                        kotlinx.coroutines.delay(200)
                    }
                    Log.i("MspTrace", "end")
                }
            }
            "seek" -> SongPlayer.seekTo(intent.getLongExtra("ms", 0L))
            "screenon" -> context.getSharedPreferences("myspoti_prefs", Context.MODE_PRIVATE)
                .edit().putBoolean("keep_screen_on", intent.getBooleanExtra("on", true)).apply()
            // Debug builds: the same report the Settings row writes, so an unattended run can check
            // that the export really produces a readable file (and where it lands).
            "diagnose" -> runCatching {
                val file = app.myspoti.host.android.Logbook.export(context)
                Log.i("MspPlayer", "diagnostic report: ${file.absolutePath} (${file.length()} bytes)")
            }.onFailure { Log.i("MspPlayer", "diagnostic report failed: $it") }
            // Debug: how many local audio files the app would list (Local Files check without the UI).
            "localfiles" -> Thread {
                val n = runCatching { kotlinx.coroutines.runBlocking { com.music.spotui.data.local.LocalMusic.tracks(context, refresh = true).size } }
                Log.i("MspPlayer", "local files: ${n.getOrElse { "failed: $it" }} track(s)")
            }.start()
        }
    }
}
