package app.myspoti.host.android

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.ApplicationInfo
import android.content.pm.ServiceInfo
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.os.Build
import android.os.Bundle
import android.support.v4.media.MediaBrowserCompat
import android.support.v4.media.MediaMetadataCompat
import android.support.v4.media.session.MediaSessionCompat
import android.support.v4.media.session.PlaybackStateCompat
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.app.ServiceCompat
import androidx.media.MediaBrowserServiceCompat
import androidx.media.VolumeProviderCompat
import androidx.media.app.NotificationCompat.MediaStyle
import androidx.media.session.MediaButtonReceiver
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request

/** What the notification, lock screen, Bluetooth and Android Auto show. */
data class NowPlayingInfo(
    val id: String,
    val title: String,
    val artist: String,
    val album: String,
    val coverUrl: String?,
    val durationMs: Long,
    val positionMs: Long,
    val playing: Boolean,
    val buffering: Boolean = false,
    val liked: Boolean = false,
    val shuffle: Boolean = false,
) {
    /** Everything but the position: a moving position alone is not a new state. */
    fun sameAs(other: NowPlayingInfo) = id == other.id && title == other.title && artist == other.artist &&
        album == other.album && coverUrl == other.coverUrl && durationMs == other.durationMs &&
        playing == other.playing && buffering == other.buffering && liked == other.liked && shuffle == other.shuffle
}

/** Commands from the system (notification buttons, headset, car, lock screen). */
interface PlaybackControls {
    fun play()
    fun pause()
    fun next()
    fun previous()
    fun seekTo(ms: Long)
    fun setLiked(liked: Boolean)
    fun playFromMediaId(mediaId: String)
    fun playFromSearch(query: String)
    fun setRemoteVolume(volume: Int)
    fun setShuffle(on: Boolean) {}
}

/** Android Auto / media browser content. */
interface MediaLibrary {
    suspend fun children(context: Context, parentId: String): List<MediaBrowserCompat.MediaItem>
    suspend fun search(query: String): List<MediaBrowserCompat.MediaItem>
}

/**
 * Media session + notification + browser service. The app pushes the state with [update]
 * and receives commands through [controls]; the service never talks to Spotify itself.
 */
class PlaybackService : MediaBrowserServiceCompat() {
    private lateinit var session: MediaSessionCompat
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
    private var foreground = false
    private var lastCoverUrl: String? = null
    private var cover: Bitmap? = null

    override fun onCreate() {
        super.onCreate()
        instance = this
        session = MediaSessionCompat(this, "Myspoti").apply {
            setCallback(callback)
            setSessionActivity(launchIntent())
            isActive = true
        }
        sessionToken = session.sessionToken
        createChannel()
        current?.let { apply(it) }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        // Every start may be a startForegroundService() (playback, a headset button or the
        // notification's dismissal through MediaButtonReceiver, another app): Android kills the
        // app unless startForeground() follows, paused or not. A paused player then leaves the
        // foreground after PAUSE_GRACE_MS, as after any pause.
        val info = current
        startForegroundSafely(build(info), always = true)
        MediaButtonReceiver.handleIntent(session, intent)
        if (info != null) show(info)
        // The paused notification was swiped away: leave the foreground with it.
        if (info?.playing != true && isStop(intent)) {
            handler.removeCallbacks(leaveForeground)
            ServiceCompat.stopForeground(this, ServiceCompat.STOP_FOREGROUND_REMOVE)
            foreground = false
        }
        return START_NOT_STICKY
    }

    private fun isStop(intent: Intent?): Boolean =
        intent?.action == Intent.ACTION_MEDIA_BUTTON &&
            androidx.core.content.IntentCompat.getParcelableExtra(intent, Intent.EXTRA_KEY_EVENT, android.view.KeyEvent::class.java)
                ?.keyCode == android.view.KeyEvent.KEYCODE_MEDIA_STOP

    override fun onDestroy() {
        instance = null
        session.release()
        scope.cancel()
        super.onDestroy()
    }

    override fun onTaskRemoved(rootIntent: Intent?) {
        if (current?.playing != true) stopSelf()
    }

    private val callback = object : MediaSessionCompat.Callback() {
        override fun onPlay() { controls?.play() }
        override fun onPause() { controls?.pause() }
        override fun onSkipToNext() { controls?.next() }
        override fun onSkipToPrevious() { controls?.previous() }
        override fun onSeekTo(pos: Long) { controls?.seekTo(pos) }
        override fun onStop() { controls?.pause() }
        override fun onPlayFromMediaId(mediaId: String, extras: Bundle?) { controls?.playFromMediaId(mediaId) }
        override fun onPlayFromSearch(query: String, extras: Bundle?) { controls?.playFromSearch(query) }
        override fun onCustomAction(action: String, extras: Bundle?) {
            when (action) {
                ACTION_LIKE -> controls?.setLiked(!(current?.liked ?: false))
                ACTION_SHUFFLE -> controls?.setShuffle(!(current?.shuffle ?: false))
            }
        }
    }

    private fun apply(info: NowPlayingInfo) {
        session.setMetadata(MediaMetadataCompat.Builder()
            .putString(MediaMetadataCompat.METADATA_KEY_MEDIA_ID, info.id)
            .putString(MediaMetadataCompat.METADATA_KEY_TITLE, info.title)
            .putString(MediaMetadataCompat.METADATA_KEY_ARTIST, info.artist)
            .putString(MediaMetadataCompat.METADATA_KEY_ALBUM, info.album)
            .putString(MediaMetadataCompat.METADATA_KEY_ALBUM_ART_URI, info.coverUrl)
            .putLong(MediaMetadataCompat.METADATA_KEY_DURATION, info.durationMs)
            .putBitmap(MediaMetadataCompat.METADATA_KEY_ALBUM_ART, cover.takeIf { info.coverUrl == lastCoverUrl })
            .build())
        val state = when {
            info.buffering && info.playing -> PlaybackStateCompat.STATE_BUFFERING
            info.playing -> PlaybackStateCompat.STATE_PLAYING
            else -> PlaybackStateCompat.STATE_PAUSED
        }
        session.setPlaybackState(PlaybackStateCompat.Builder()
            .setActions(
                PlaybackStateCompat.ACTION_PLAY or PlaybackStateCompat.ACTION_PAUSE or PlaybackStateCompat.ACTION_PLAY_PAUSE or
                    PlaybackStateCompat.ACTION_SKIP_TO_NEXT or PlaybackStateCompat.ACTION_SKIP_TO_PREVIOUS or
                    PlaybackStateCompat.ACTION_SEEK_TO or PlaybackStateCompat.ACTION_PLAY_FROM_MEDIA_ID or
                    PlaybackStateCompat.ACTION_PLAY_FROM_SEARCH
            )
            // Like on the left of previous / play / next, shuffle on the right: five buttons, so
            // play / pause sits in the middle (lock screen, Samsung Now Bar, Android 13+ shade).
            .addCustomAction(ACTION_LIKE, if (info.liked) "Unlike" else "Like",
                if (info.liked) R.drawable.msp_ic_liked else R.drawable.msp_ic_like)
            .addCustomAction(ACTION_SHUFFLE, if (info.shuffle) "Shuffle off" else "Shuffle on",
                if (info.shuffle) R.drawable.msp_ic_shuffle_on else R.drawable.msp_ic_shuffle)
            .setState(state, info.positionMs, if (info.playing && !info.buffering) 1f else 0f)
            .build())
        if (info.coverUrl != lastCoverUrl) loadCover(info.coverUrl)
    }

    private fun loadCover(url: String?) {
        lastCoverUrl = url
        cover = null
        if (url.isNullOrBlank()) return
        scope.launch {
            val bitmap = withContext(Dispatchers.IO) {
                runCatching {
                    http.newCall(Request.Builder().url(url).build()).execute().use { BitmapFactory.decodeStream(it.body.byteStream()) }
                }.getOrNull()
            }
            if (url == lastCoverUrl && bitmap != null) {
                cover = bitmap
                current?.let { apply(it); show(it) }
            }
        }
    }

    private val handler = android.os.Handler(android.os.Looper.getMainLooper())
    /** Leaves the foreground only after a real pause, never between two songs. */
    private val leaveForeground = Runnable {
        if (current?.playing != true && foreground) {
            ServiceCompat.stopForeground(this, ServiceCompat.STOP_FOREGROUND_DETACH)
            foreground = false
        }
    }

    private fun show(info: NowPlayingInfo) {
        val notification = build(info)
        if (info.playing || info.buffering) {
            handler.removeCallbacks(leaveForeground)
            startForegroundSafely(notification)
        } else {
            // The player is briefly "paused" while it loads the next song. Leaving the
            // foreground then let Android freeze the app with the screen off, and playback
            // stopped after one song: the service stays a minute in the foreground.
            if (foreground) {
                runCatching { NotificationManagerCompat.from(this).notify(NOTIFICATION_ID, notification) }
                handler.removeCallbacks(leaveForeground)
                handler.postDelayed(leaveForeground, PAUSE_GRACE_MS)
            } else runCatching { NotificationManagerCompat.from(this).notify(NOTIFICATION_ID, notification) }
        }
    }

    /** [always]: call startForeground() even when already in the foreground (a new foreground start). */
    private fun startForegroundSafely(notification: Notification, always: Boolean = false) {
        if (foreground && !always) {
            runCatching { NotificationManagerCompat.from(this).notify(NOTIFICATION_ID, notification) }
            return
        }
        runCatching {
            ServiceCompat.startForeground(this, NOTIFICATION_ID, notification,
                if (Build.VERSION.SDK_INT >= 29) ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PLAYBACK else 0)
            foreground = true
        }
    }

    private fun build(info: NowPlayingInfo?): Notification {
        val playing = info?.playing == true
        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(R.drawable.msp_ic_notification)
            .setContentTitle(info?.title ?: "")
            .setContentText(info?.artist ?: "")
            .setSubText(info?.album)
            .setLargeIcon(cover)
            .setContentIntent(launchIntent())
            .setDeleteIntent(MediaButtonReceiver.buildMediaButtonPendingIntent(this, PlaybackStateCompat.ACTION_STOP))
            .setVisibility(NotificationCompat.VISIBILITY_PUBLIC)
            .setOnlyAlertOnce(true)
            .setOngoing(playing)
            .addAction(R.drawable.msp_ic_previous, "Previous",
                MediaButtonReceiver.buildMediaButtonPendingIntent(this, PlaybackStateCompat.ACTION_SKIP_TO_PREVIOUS))
            .addAction(if (playing) R.drawable.msp_ic_pause else R.drawable.msp_ic_play, if (playing) "Pause" else "Play",
                MediaButtonReceiver.buildMediaButtonPendingIntent(this, PlaybackStateCompat.ACTION_PLAY_PAUSE))
            .addAction(R.drawable.msp_ic_next, "Next",
                MediaButtonReceiver.buildMediaButtonPendingIntent(this, PlaybackStateCompat.ACTION_SKIP_TO_NEXT))
            .setStyle(MediaStyle().setMediaSession(session.sessionToken).setShowActionsInCompactView(0, 1, 2))
            .build()
    }

    private fun launchIntent(): PendingIntent? {
        val intent = packageManager.getLaunchIntentForPackage(packageName) ?: return null
        return PendingIntent.getActivity(this, 0, intent, PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
    }

    private fun createChannel() {
        val manager = getSystemService(NotificationManager::class.java)
        if (manager.getNotificationChannel(CHANNEL_ID) == null) {
            manager.createNotificationChannel(NotificationChannel(CHANNEL_ID, "Playback", NotificationManager.IMPORTANCE_LOW).apply {
                setShowBadge(false)
            })
        }
    }

    /** Playback on a Connect device: the volume keys drive that device instead of the phone. */
    private fun setRemote(remote: Boolean, volume: Int) {
        if (!remote) {
            session.setPlaybackToLocal(android.media.AudioManager.STREAM_MUSIC)
            return
        }
        session.setPlaybackToRemote(object : VolumeProviderCompat(VOLUME_CONTROL_ABSOLUTE, 100, volume) {
            override fun onSetVolumeTo(volume: Int) { currentVolume = volume; controls?.setRemoteVolume(volume) }
            override fun onAdjustVolume(direction: Int) {
                val v = (currentVolume + direction * 5).coerceIn(0, 100)
                currentVolume = v
                controls?.setRemoteVolume(v)
            }
        })
    }

    // Media browser (Android Auto).
    override fun onGetRoot(clientPackageName: String, clientUid: Int, rootHints: Bundle?): BrowserRoot? {
        // The service is exported (Android Auto requires it), but the library —
        // playlists, Liked Songs, search — is only for Auto, Assistant, Bluetooth,
        // Wear, System UI, other system apps and the app itself. Any other app
        // gets an empty root: it can still connect and use the session's
        // play/pause controls, but browsing and search return nothing.
        val packages = runCatching { packageManager.getPackagesForUid(clientUid)?.toList() }.getOrNull().orEmpty()
        if (clientPackageName != packageName && clientPackageName !in packages) return null
        if (!mayBrowse(clientPackageName)) {
            android.util.Log.w("Myspoti", "media browser: empty root for $clientPackageName")
            return BrowserRoot(EMPTY_ROOT_ID, null)
        }
        return BrowserRoot(ROOT_ID, Bundle().apply { putBoolean("android.media.browse.SEARCH_SUPPORTED", true) })
    }

    override fun onLoadChildren(parentId: String, result: Result<MutableList<MediaBrowserCompat.MediaItem>>) {
        // A client may subscribe to any id, not only the root it was given:
        // check the caller here too.
        if (parentId == EMPTY_ROOT_ID || !callerMayBrowse()) return result.sendResult(mutableListOf())
        val lib = library ?: return result.sendResult(mutableListOf())
        result.detach()
        scope.launch { result.sendResult(runCatching { lib.children(this@PlaybackService, parentId).toMutableList() }.getOrElse { mutableListOf() }) }
    }

    override fun onSearch(query: String, extras: Bundle?, result: Result<MutableList<MediaBrowserCompat.MediaItem>>) {
        if (!callerMayBrowse()) return result.sendResult(mutableListOf())
        val lib = library ?: return result.sendResult(mutableListOf())
        result.detach()
        scope.launch { result.sendResult(runCatching { lib.search(query).toMutableList() }.getOrElse { mutableListOf() }) }
    }

    /** The app itself, the listed browsers, and system apps may browse the library. */
    private fun mayBrowse(clientPackageName: String): Boolean {
        if (clientPackageName == packageName || clientPackageName in ALLOWED_BROWSERS) return true
        val flags = runCatching { packageManager.getApplicationInfo(clientPackageName, 0).flags }.getOrDefault(0)
        return (flags and ApplicationInfo.FLAG_SYSTEM) != 0
    }

    /** Whether the client behind the current onLoadChildren/onSearch call may browse. */
    private fun callerMayBrowse(): Boolean {
        val caller = runCatching { currentBrowserInfo }.getOrNull() ?: return false
        val packages = runCatching { packageManager.getPackagesForUid(caller.uid)?.toList() }.getOrNull().orEmpty()
        return caller.packageName in packages && mayBrowse(caller.packageName)
    }

    companion object {
        const val ROOT_ID = "root"
        /** Root for callers that may control playback but not browse (see onGetRoot). */
        private const val EMPTY_ROOT_ID = "empty"
        private const val CHANNEL_ID = "playback"
        private const val NOTIFICATION_ID = 1
        private const val ACTION_LIKE = "like"
        private const val ACTION_SHUFFLE = "shuffle"
        private val http = OkHttpClient()

        @Volatile private var instance: PlaybackService? = null
        @Volatile private var current: NowPlayingInfo? = null

        private const val PAUSE_GRACE_MS = 60_000L
        /** A state that did not change is republished at most that often (see [update]). */
        private const val POSITION_REPUSH_MS = 10_000L

        /** Packages allowed to browse the library (see onGetRoot). */
        private val ALLOWED_BROWSERS = setOf(
            "com.google.android.projection.gearhead", // Android Auto (phone)
            "com.google.android.apps.automotive.templates.host", // Android Automotive OS
            "com.google.android.googlequicksearchbox", // Google Assistant
            "com.google.android.carassistant",
            "com.android.systemui",
            "com.android.bluetooth", // car stereos browsing over Bluetooth AVRCP
            "com.google.android.wearable.app", // Wear OS
            "com.android.car.media", // Android Automotive media center
        )

        /** Set once by the app. */
        @Volatile var controls: PlaybackControls? = null
        @Volatile var library: MediaLibrary? = null

        /** Pushes the now-playing state; starts the service when playback starts. */
        fun update(context: Context, info: NowPlayingInfo) {
            val before = current
            current = info
            val svc = instance
            if (svc == null) {
                if (info.playing) runCatching {
                    androidx.core.content.ContextCompat.startForegroundService(context, Intent(context, PlaybackService::class.java))
                }
                return
            }
            // The player reports several times per second (position, buffering): only a real
            // change reaches the session and the notification, the system extrapolates the
            // position in between (the known state is republished every POSITION_REPUSH_MS).
            if (before != null && before.sameAs(info) &&
                kotlin.math.abs(info.positionMs - before.positionMs) < POSITION_REPUSH_MS
            ) return
            svc.apply(info)
            svc.show(info)
        }

        /** Signed out: the notification and the media session go, nothing of the account stays. */
        fun end() {
            current = null
            instance?.let { svc ->
                svc.handler.removeCallbacksAndMessages(null)
                ServiceCompat.stopForeground(svc, ServiceCompat.STOP_FOREGROUND_REMOVE)
                svc.foreground = false
                svc.session.isActive = false
                svc.stopSelf()
            }
        }

        /** [remote] when a Connect device plays; [volume] 0..100. */
        fun setPlaybackDevice(remote: Boolean, volume: Int) { instance?.setRemote(remote, volume) }
    }
}
