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
            "previous" -> SongPlayer.previous()
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
