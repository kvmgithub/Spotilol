package com.music.spotui.ui.components

import android.content.Context
import android.graphics.Bitmap
import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
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
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.outlined.Delete
import androidx.compose.material.icons.outlined.Edit
import androidx.compose.material.icons.outlined.Group
import androidx.compose.material.icons.outlined.Lock
import androidx.compose.material.icons.outlined.Person
import androidx.compose.material.icons.outlined.Public
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import app.myspoti.api.PlaylistAccess
import app.myspoti.engine.R
import app.myspoti.host.android.MyspotiRuntime
import com.bumptech.glide.integration.compose.ExperimentalGlideComposeApi
import com.bumptech.glide.integration.compose.GlideImage
import com.bumptech.glide.integration.compose.placeholder
import com.music.spotui.data.entity.ContextPageModel
import com.music.spotui.ui.theme.AppAccent
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

/**
 * A playlist's ⋯ menu. On your own playlists: edit the name, description and cover, choose
 * who can see it (public, private, collaborative) and delete it. On someone else's: their
 * profile. [onChanged] reloads the page after an edit; [onDeleted] leaves it.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun PlaylistOptionsSheet(
    page: ContextPageModel,
    onDismiss: () -> Unit,
    onChanged: () -> Unit,
    onDeleted: () -> Unit,
    onProfile: (String) -> Unit,
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    LaunchedEffect(Unit) { com.music.spotui.data.api.ProfileCache.ensure(context) }
    val mine = com.music.spotui.data.api.ProfileCache.id?.let { page.ownerUri == "spotify:user:$it" } == true
    var access by remember { mutableStateOf<PlaylistAccess?>(null) }
    var editing by remember { mutableStateOf(false) }
    var deleting by remember { mutableStateOf(false) }
    LaunchedEffect(page.uri, mine) {
        if (mine) access = runCatching { MyspotiRuntime.api.playlists.access(page.uri) }.getOrNull()
    }

    // Taps are sent one at a time, in order; a refused one puts back what Spotify really has.
    val accessWrites = remember { kotlinx.coroutines.sync.Mutex() }
    fun setAccess(target: PlaylistAccess) {
        if (access == target) return
        access = target
        scope.launch {
            accessWrites.withLock {
                runCatching { MyspotiRuntime.api.playlists.setAccess(page.uri, target) }
                    .onSuccess { onChanged() }
                    .onFailure {
                        access = runCatching { MyspotiRuntime.api.playlists.access(page.uri) }.getOrNull()
                        android.widget.Toast.makeText(context, R.string.playlist_change_refused, android.widget.Toast.LENGTH_SHORT).show()
                    }
            }
        }
    }

    fun run(block: suspend () -> Unit, after: () -> Unit = onChanged) = scope.launch {
        runCatching { block() }
            .onSuccess { after() }
            .onFailure { android.widget.Toast.makeText(context, R.string.playlist_change_refused, android.widget.Toast.LENGTH_SHORT).show() }
    }

    ModalBottomSheet(onDismissRequest = onDismiss, containerColor = Color(0xFF282828)) {
        Column(Modifier.navigationBarsPadding().padding(bottom = 12.dp)) {
            Text(page.name, color = Color.White, fontSize = 18.sp, fontWeight = FontWeight.Bold, maxLines = 1,
                overflow = TextOverflow.Ellipsis, modifier = Modifier.padding(20.dp, 0.dp, 20.dp, 12.dp))
            if (mine) {
                OptionRow(Icons.Outlined.Edit, stringResource(R.string.playlist_edit_details)) { editing = true }
                Text(stringResource(R.string.playlist_access_title), color = Color(0xFFB3B3B3), fontSize = 13.sp,
                    modifier = Modifier.padding(20.dp, 12.dp, 20.dp, 4.dp))
                AccessRow(Icons.Outlined.Public, R.string.playlist_access_public, R.string.playlist_access_public_detail, access == PlaylistAccess.PUBLIC) {
                    setAccess(PlaylistAccess.PUBLIC)
                }
                AccessRow(Icons.Outlined.Lock, R.string.playlist_access_private, R.string.playlist_access_private_detail, access == PlaylistAccess.PRIVATE) {
                    setAccess(PlaylistAccess.PRIVATE)
                }
                AccessRow(Icons.Outlined.Group, R.string.playlist_access_collaborative, R.string.playlist_access_collaborative_detail, access == PlaylistAccess.COLLABORATIVE) {
                    setAccess(PlaylistAccess.COLLABORATIVE)
                }
                Spacer(Modifier.height(8.dp))
                OptionRow(Icons.Outlined.Delete, stringResource(R.string.local_delete_playlist)) { deleting = true }
            } else if (page.ownerUri.startsWith("spotify:user:")) {
                OptionRow(Icons.Outlined.Person, stringResource(R.string.go_to_profile)) {
                    onDismiss(); onProfile(page.ownerUri.substringAfterLast(':'))
                }
            }
        }
    }

    if (editing) EditDetailsDialog(page, onDismiss = { editing = false }) { name, description, cover ->
        editing = false
        run({
            if (name != page.name) MyspotiRuntime.api.playlists.rename(page.uri, name)
            if (description != page.description) MyspotiRuntime.api.playlists.setDescription(page.uri, description)
            when (cover) {
                is CoverChange.New -> MyspotiRuntime.api.playlists.setCover(page.uri, cover.jpeg)
                CoverChange.Remove -> MyspotiRuntime.api.playlists.setCover(page.uri, null)
                CoverChange.Keep -> {}
            }
        }, {
            android.widget.Toast.makeText(context, R.string.playlist_saved, android.widget.Toast.LENGTH_SHORT).show()
            onDismiss(); onChanged()
        })
    }

    if (deleting) AlertDialog(
        onDismissRequest = { deleting = false },
        containerColor = Color(0xFF282828),
        text = { Text(stringResource(R.string.playlist_delete_confirm, page.name), color = Color.White) },
        confirmButton = {
            TextButton(onClick = {
                deleting = false
                run({ MyspotiRuntime.api.playlists.delete(page.uri) }, { onDismiss(); onDeleted() })
            }) { Text(stringResource(R.string.actions_picker_delete), color = Color(0xFFF15E6C)) }
        },
        dismissButton = { TextButton(onClick = { deleting = false }) { Text(stringResource(R.string.cancel), color = Color.White) } },
    )
}

private sealed interface CoverChange {
    data object Keep : CoverChange
    data object Remove : CoverChange
    class New(val jpeg: ByteArray, val preview: Bitmap) : CoverChange
}

@OptIn(ExperimentalGlideComposeApi::class)
@Composable
private fun EditDetailsDialog(page: ContextPageModel, onDismiss: () -> Unit, onSave: (String, String, CoverChange) -> Unit) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var name by remember { mutableStateOf(page.name) }
    var description by remember { mutableStateOf(page.description) }
    var cover by remember { mutableStateOf<CoverChange>(CoverChange.Keep) }
    val picker = rememberLauncherForActivityResult(ActivityResultContracts.PickVisualMedia()) { uri ->
        if (uri != null) scope.launch {
            val made = withContext(Dispatchers.Default) { runCatching { coverJpeg(context, uri) }.getOrNull() }
            if (made != null) cover = CoverChange.New(made.first, made.second)
        }
    }
    val fieldColors = OutlinedTextFieldDefaults.colors(
        focusedTextColor = Color.White, unfocusedTextColor = Color.White, cursorColor = AppAccent,
        focusedBorderColor = AppAccent, unfocusedBorderColor = Color(0xFF555555),
        focusedLabelColor = AppAccent, unfocusedLabelColor = Color(0xFFB3B3B3),
    )
    AlertDialog(
        onDismissRequest = onDismiss,
        containerColor = Color(0xFF282828),
        title = { Text(stringResource(R.string.playlist_edit_details), color = Color.White) },
        text = {
            Column {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    val c = cover
                    Box(Modifier.size(96.dp).clip(RoundedCornerShape(4.dp)).background(Color(0xFF3A3A3A))
                        .clickable { picker.launch(PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly)) }) {
                        when (c) {
                            is CoverChange.New -> Image(c.preview.asImageBitmap(), null, contentScale = ContentScale.Crop, modifier = Modifier.size(96.dp))
                            CoverChange.Remove -> {}
                            CoverChange.Keep -> GlideImage(page.coverUri, null, contentScale = ContentScale.Crop,
                                failure = placeholder(R.drawable.placeholder), modifier = Modifier.size(96.dp))
                        }
                    }
                    Spacer(Modifier.width(12.dp))
                    Column {
                        TextButton(onClick = { picker.launch(PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly)) }) {
                            Text(stringResource(R.string.playlist_change_cover), color = Color.White)
                        }
                        TextButton(onClick = { cover = CoverChange.Remove }) {
                            Text(stringResource(R.string.playlist_remove_cover), color = Color(0xFFB3B3B3))
                        }
                    }
                }
                Spacer(Modifier.height(12.dp))
                OutlinedTextField(name, { name = it }, label = { Text(stringResource(R.string.playlist_name_label)) },
                    singleLine = true, colors = fieldColors, modifier = Modifier.fillMaxWidth())
                Spacer(Modifier.height(8.dp))
                OutlinedTextField(description, { description = it }, label = { Text(stringResource(R.string.playlist_description_label)) },
                    minLines = 2, maxLines = 4, colors = fieldColors, modifier = Modifier.fillMaxWidth())
            }
        },
        confirmButton = {
            TextButton(enabled = name.isNotBlank(), onClick = { onSave(name.trim(), description.trim(), cover) }) {
                Text(stringResource(R.string.playlist_save), color = AppAccent, fontWeight = FontWeight.Bold)
            }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text(stringResource(R.string.cancel), color = Color.White) } },
    )
}

/** The picked image as Spotify takes a cover: square, 640 px, JPEG under 256 KB. */
private fun coverJpeg(context: Context, uri: Uri): Pair<ByteArray, Bitmap> {
    val source = context.contentResolver.openInputStream(uri).use { android.graphics.BitmapFactory.decodeStream(it) }
        ?: error("not an image")
    val side = minOf(source.width, source.height)
    val square = Bitmap.createBitmap(source, (source.width - side) / 2, (source.height - side) / 2, side, side)
    val scaled = Bitmap.createScaledBitmap(square, 640, 640, true)
    var quality = 90
    var bytes: ByteArray
    do {
        bytes = java.io.ByteArrayOutputStream().also { scaled.compress(Bitmap.CompressFormat.JPEG, quality, it) }.toByteArray()
        quality -= 10
    } while (bytes.size > 256 * 1024 && quality > 30)
    return bytes to scaled
}

@Composable
private fun OptionRow(icon: ImageVector, label: String, onClick: () -> Unit) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier.fillMaxWidth().clickable(onClick = onClick).padding(horizontal = 20.dp, vertical = 14.dp),
    ) {
        Icon(icon, null, tint = Color.White, modifier = Modifier.size(24.dp))
        Spacer(Modifier.width(16.dp))
        Text(label, color = Color.White, fontSize = 16.sp)
    }
}

@Composable
private fun AccessRow(icon: ImageVector, label: Int, detail: Int, selected: Boolean, onClick: () -> Unit) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier.fillMaxWidth().clickable(onClick = onClick).padding(horizontal = 20.dp, vertical = 10.dp),
    ) {
        Icon(icon, null, tint = if (selected) AppAccent else Color.White, modifier = Modifier.size(24.dp))
        Spacer(Modifier.width(16.dp))
        Column(Modifier.weight(1f)) {
            Text(stringResource(label), color = if (selected) AppAccent else Color.White, fontSize = 16.sp)
            Text(stringResource(detail), color = Color(0xFFB3B3B3), fontSize = 13.sp)
        }
        if (selected) Icon(Icons.Default.Check, null, tint = AppAccent, modifier = Modifier.size(20.dp))
    }
}
