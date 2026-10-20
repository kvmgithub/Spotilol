package com.music.spotui.ui.components

import android.content.Context
import android.content.Intent
import android.widget.Toast
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.outlined.Add
import androidx.compose.material.icons.outlined.Done
import androidx.compose.material.icons.outlined.Info
import androidx.compose.material.icons.outlined.PlaylistAdd
import androidx.compose.material.icons.outlined.Share
import androidx.compose.material.icons.outlined.Undo
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import app.myspoti.engine.R
import com.bumptech.glide.integration.compose.ExperimentalGlideComposeApi
import com.bumptech.glide.integration.compose.CrossFade
import com.bumptech.glide.integration.compose.GlideImage
import com.music.spotui.data.entity.SongsModel
import com.music.spotui.di.SongPlayer

private val Accent: Color get() = com.music.spotui.ui.theme.AppAccent
private val Sheet: Color get() = Color(0xFF1F1F1F)

/** Spotify's episode uri, as the page's own menu hands it around. */
val SongsModel.spotifyUri: String get() = "spotify:$url"

/** The episode's own "…" menu, as the official app has it (no like, no lyrics, played marks). */
@OptIn(ExperimentalMaterial3Api::class, ExperimentalGlideComposeApi::class)
@Composable
fun EpisodeOptionsSheet(
    song: SongsModel,
    finished: Boolean,
    onPlay: () -> Unit,
    onMarkFinished: (Boolean) -> Unit,
    /** The episode page: shown only where there is a page to open. */
    onOpen: (() -> Unit)? = null,
    onDismiss: () -> Unit,
) {
    val context = LocalContext.current
    // The + / ✓ follows Spotify's library: ask once for this episode's saved state.
    androidx.compose.runtime.LaunchedEffect(song.url) { SongPlayer.requestSaved(song.spotifyUri) }
    val saved = SongPlayer.library[song.spotifyUri] == true
    ModalBottomSheet(onDismissRequest = onDismiss, containerColor = Sheet) {
        Column(Modifier.navigationBarsPadding().padding(bottom = 12.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth().padding(20.dp, 4.dp, 20.dp, 16.dp)) {
                GlideImage(
                    transition = CrossFade,
                    model = song.coverUri,
                    contentDescription = "",
                    contentScale = ContentScale.Crop,
                    modifier = Modifier.size(52.dp).clip(RoundedCornerShape(4.dp)),
                )
                Column(Modifier.weight(1f).padding(start = 12.dp)) {
                    Text(song.title, color = Color.White, fontSize = 15.sp, fontWeight = FontWeight.Bold, maxLines = 2, overflow = TextOverflow.Ellipsis)
                    Text(song.singer, color = Color.White.copy(alpha = 0.65f), fontSize = 12.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
                }
            }
            SheetAction(Icons.Outlined.Share, stringResource(R.string.a11y_share)) {
                onDismiss()
                shareEpisode(context, song)
            }
            SheetAction(
                if (saved) Icons.Default.Check else Icons.Outlined.Add,
                stringResource(if (saved) R.string.episode_unsave else R.string.episode_save),
            ) {
                SongPlayer.setSaved(song.spotifyUri, !saved)
                onDismiss()
            }
            // Episode downloads are removed in MySpotilol.
            // The mark the official episode menu carries and the music menu does not.
            if (finished) {
                SheetAction(Icons.Outlined.Undo, stringResource(R.string.episode_mark_unplayed)) {
                    onMarkFinished(false)
                    Toast.makeText(context, context.getString(R.string.episode_marked_unplayed), Toast.LENGTH_SHORT).show()
                    onDismiss()
                }
            } else {
                SheetAction(Icons.Outlined.Done, stringResource(R.string.episode_mark_played)) {
                    onMarkFinished(true)
                    Toast.makeText(context, context.getString(R.string.episode_marked_played), Toast.LENGTH_SHORT).show()
                    onDismiss()
                }
            }
            SheetAction(Icons.Outlined.PlaylistAdd, stringResource(R.string.episode_add_queue)) {
                SongPlayer.addEpisodeToQueue(song.url)
                onDismiss()
            }
            SheetAction(Icons.Default.PlayArrow, stringResource(R.string.episode_play)) {
                onDismiss()
                onPlay()
            }
            if (onOpen != null) SheetAction(Icons.Outlined.Info, stringResource(R.string.episode_open)) {
                onDismiss()
                onOpen()
            }
        }
    }
}

@Composable
private fun SheetAction(icon: ImageVector, label: String, onClick: () -> Unit) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier.fillMaxWidth().clickable(onClick = onClick).padding(horizontal = 20.dp, vertical = 14.dp),
    ) {
        Icon(icon, contentDescription = null, tint = Color.White.copy(alpha = 0.9f), modifier = Modifier.size(22.dp))
        Spacer(Modifier.width(16.dp))
        Text(label, color = Color.White, fontSize = 15.sp)
    }
}

/** Shares the episode's own link (the official app opens the same page). */
fun shareEpisode(context: Context, song: SongsModel) {
    val id = song.url.removePrefix("episode:")
    val send = Intent(Intent.ACTION_SEND).apply {
        type = "text/plain"
        putExtra(Intent.EXTRA_TEXT, "https://open.spotify.com/episode/$id")
    }
    runCatching { context.startActivity(Intent.createChooser(send, context.getString(R.string.a11y_share))) }
}

/** "24 juil." from Spotify's ISO date; "" when the date is missing. */
fun episodeDateLabel(iso: String): String {
    if (iso.isBlank()) return ""
    return runCatching {
        val date = java.time.LocalDate.parse(iso.take(10))
        date.format(java.time.format.DateTimeFormatter.ofPattern("d MMM", java.util.Locale.getDefault()))
    }.getOrDefault("")
}

/** "12 min" for a row's meta line. */
@Composable
fun episodeMinutes(durationMs: Int): String =
    stringResource(R.string.episode_duration_min, ((durationMs + 30_000) / 60_000).coerceAtLeast(1))

/** "m:ss" for the player's clocks. */
fun episodeClock(ms: Long): String {
    val total = (ms / 1000).coerceAtLeast(0)
    val h = total / 3600
    val m = (total % 3600) / 60
    val s = total % 60
    return if (h > 0) String.format(java.util.Locale.US, "%d:%02d:%02d", h, m, s)
    else String.format(java.util.Locale.US, "%d:%02d", m, s)
}

/** "1", "1,2", "1,5"… as the speed sheet shows them, in the phone's language. */
fun speedLabel(speed: Double): String =
    if (speed == Math.floor(speed)) speed.toInt().toString()
    else String.format(java.util.Locale.getDefault(), "%.2f", speed).trimEnd('0').trimEnd('.')

/** The stock app's speed sheet: the current value, its scale, then the presets. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun PodcastSpeedSheet(onDismiss: () -> Unit) {
    val current = SongPlayer.speed.value
    ModalBottomSheet(onDismissRequest = onDismiss, containerColor = Sheet) {
        Column(Modifier.fillMaxWidth().navigationBarsPadding().padding(bottom = 20.dp)) {
            Text(
                stringResource(R.string.playback_speed),
                color = Color.White, fontSize = 20.sp, fontWeight = FontWeight.Bold,
                modifier = Modifier.padding(horizontal = 20.dp, vertical = 8.dp),
            )
            Text(
                text = stringResource(R.string.player_speed_chip, speedLabel(current)),
                color = Accent, fontSize = 40.sp, fontWeight = FontWeight.Bold,
                modifier = Modifier.fillMaxWidth().padding(vertical = 12.dp),
                textAlign = androidx.compose.ui.text.style.TextAlign.Center,
            )
            Row(
                horizontalArrangement = Arrangement.spacedBy(12.dp),
                modifier = Modifier.fillMaxWidth().padding(horizontal = 20.dp, vertical = 8.dp),
            ) {
                // The presets of the official sheet: 3× exists for podcasts.
                listOf(1.0, 1.2, 1.5, 2.0, 3.0).forEach { speed ->
                    val selected = kotlin.math.abs(current - speed) < 0.01
                    Box(
                        contentAlignment = Alignment.Center,
                        modifier = Modifier
                            .weight(1f)
                            .height(44.dp)
                            .clip(CircleShape)
                            .background(if (selected) Color.White else Color.White.copy(alpha = 0.12f))
                            .clickable {
                                SongPlayer.setSpeed(speed)
                                onDismiss()
                            },
                    ) {
                        Text(
                            text = speedLabel(speed),
                            color = if (selected) Color.Black else Color.White,
                            fontSize = 15.sp,
                            fontWeight = FontWeight.Bold,
                        )
                    }
                }
            }
        }
    }
}
