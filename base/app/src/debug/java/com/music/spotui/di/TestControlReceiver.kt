package com.music.spotui.di

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.util.Log

/**
 * Debug builds only: the playback test (tools/playback_test.py) sends the app's own
 * commands, as a tap would, with
 * `adb shell am broadcast -a app.myspoti.TEST -p app.myspoti.dev --es cmd <cmd> ...`.
 * cmd: play (--es track <uri> --es context <uri>), resume, pause, next, previous, seek (--el ms <n>).
 */
class TestControlReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val cmd = intent.getStringExtra("cmd").orEmpty()
        Log.i("MspPlayer", "test command $cmd")
        when (cmd) {
            "play" -> SongPlayer.playSong(intent.getStringExtra("track").orEmpty(), contextUri = intent.getStringExtra("context"))
            "resume" -> SongPlayer.play()
            "pause" -> SongPlayer.pause()
            "next" -> SongPlayer.next()
            "previous" -> SongPlayer.previous()
            "seek" -> SongPlayer.seekTo(intent.getLongExtra("ms", 0L))
        }
    }
}
