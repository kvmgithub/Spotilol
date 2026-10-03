package com.project.lol.ui.components

import android.content.ClipData
import android.content.Intent
import android.net.Uri
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
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
import androidx.compose.ui.platform.LocalClipboard
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.toClipEntry
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.project.lol.R
import com.project.lol.util.GitHubApi
import com.project.lol.util.GitHubRelease
import com.project.lol.util.MarkdownText
import compose.icons.TablerIcons
import compose.icons.tablericons.Copy
import compose.icons.tablericons.InfoCircle
import compose.icons.tablericons.Link
import kotlinx.coroutines.launch
import java.text.SimpleDateFormat
import java.util.Locale

private const val REPO_OWNER = "kvmgithub"
private const val REPO_NAME = "Spotilol"
private const val RELEASES_URL = "https://github.com/kvmgithub/Spotilol/releases"

@Composable
fun ChangelogDialog(onDismiss: () -> Unit) {
    val context = LocalContext.current
    val configuration = LocalConfiguration.current
    val clipboard = LocalClipboard.current
    val scope = rememberCoroutineScope()
    var release by remember { mutableStateOf<GitHubRelease?>(null) }
    var loading by remember { mutableStateOf(true) }
    var failed by remember { mutableStateOf(false) }

    fun fetch() {
        loading = true
        failed = false
        GitHubApi.fetchLatestRelease(REPO_OWNER, REPO_NAME) { r ->
            loading = false
            if (r == null || r.body.isBlank()) {
                failed = true
            } else {
                release = r
            }
        }
    }

    LaunchedEffect(Unit) { fetch() }

    val publishedLabel = release?.publishedAt?.let { iso ->
        runCatching {
            val parsed = SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss'Z'", Locale.US).apply { timeZone = java.util.TimeZone.getTimeZone("UTC") }.parse(iso) ?: return@runCatching null
            SimpleDateFormat("MMM d, yyyy", configuration.locales[0]).format(parsed)
        }.getOrNull()
    }

    AlertDialog(
        onDismissRequest = onDismiss,
        shape = RoundedCornerShape(28.dp),
        title = {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(
                    imageVector = TablerIcons.InfoCircle,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.primary
                )
                Spacer(Modifier.width(10.dp))
                Column {
                    Text(
                        text = stringResource(R.string.settings_changelog_title),
                        style = MaterialTheme.typography.titleLarge,
                        fontWeight = FontWeight.Bold
                    )
                    if (release != null) {
                        Text(
                            text = listOfNotNull(
                                stringResource(R.string.settings_changelog_version, release!!.tagName.removePrefix("v")),
                                publishedLabel
                            ).joinToString(" · "),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }
            }
        },
        text = {
            when {
                loading -> {
                    Box(
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(160.dp),
                        contentAlignment = Alignment.Center
                    ) {
                        CircularProgressIndicator()
                    }
                }
                failed -> {
                    Column(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalAlignment = Alignment.CenterHorizontally
                    ) {
                        Text(
                            text = stringResource(R.string.settings_changelog_error),
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                        Spacer(Modifier.height(8.dp))
                        TextButton(onClick = { fetch() }) {
                            Text(stringResource(R.string.settings_retry), fontWeight = FontWeight.Bold)
                        }
                    }
                }
                release != null -> {
                    val r = release!!
                    MarkdownText(
                        markdown = r.body,
                        modifier = Modifier
                            .fillMaxWidth()
                            .heightIn(max = (configuration.screenHeightDp * 0.65f).dp)
                            .verticalScroll(rememberScrollState()),
                        onLinkClick = { url ->
                            runCatching {
                                context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(url)))
                            }
                        }
                    )
                }
            }
        },
        confirmButton = {
            Row(
                horizontalArrangement = Arrangement.spacedBy(4.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                TextButton(
                    onClick = {
                        runCatching {
                            context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(RELEASES_URL)))
                        }
                    },
                    contentPadding = PaddingValues(horizontal = 10.dp, vertical = 6.dp)
                ) {
                    Icon(
                        imageVector = TablerIcons.Link,
                        contentDescription = null,
                        modifier = Modifier.size(14.dp)
                    )
                    Spacer(Modifier.width(6.dp))
                    Text(stringResource(R.string.settings_changelog_open), fontWeight = FontWeight.Bold)
                }
                TextButton(
                    onClick = {
                        val body = release?.body
                        if (!body.isNullOrBlank()) {
                            scope.launch {
                                clipboard.setClipEntry(
                                    ClipData.newPlainText("spotilol_changelog", body).toClipEntry()
                                )
                            }
                        }
                    },
                    enabled = release != null,
                    contentPadding = PaddingValues(horizontal = 10.dp, vertical = 6.dp)
                ) {
                    Icon(
                        imageVector = TablerIcons.Copy,
                        contentDescription = null,
                        modifier = Modifier.size(14.dp)
                    )
                    Spacer(Modifier.width(6.dp))
                    Text(stringResource(R.string.settings_changelog_copy), fontWeight = FontWeight.Bold)
                }
                TextButton(
                    onClick = onDismiss,
                    contentPadding = PaddingValues(horizontal = 10.dp, vertical = 6.dp)
                ) {
                    Text(stringResource(R.string.settings_close), fontWeight = FontWeight.Bold)
                }
            }
        }
    )
}
