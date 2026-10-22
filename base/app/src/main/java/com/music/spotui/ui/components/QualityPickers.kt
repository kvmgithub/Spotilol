package com.music.spotui.ui.components

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.material3.RadioButton
import androidx.compose.material3.RadioButtonDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import app.myspoti.engine.R
import com.music.spotui.data.preferences.StreamingQuality
import com.music.spotui.data.preferences.getStreamingQuality
import com.music.spotui.data.preferences.setStreamingQuality

/** The streaming levels of the Web Player, one choice; saved and applied at once. */
@Composable
fun StreamingQualityPicker(modifier: Modifier = Modifier, cellular: Boolean = false, title: Int = R.string.quality_streaming_title) {
    val ctx = LocalContext.current
    var current by remember(cellular) { mutableStateOf(getStreamingQuality(ctx, cellular)) }
    QualityGroup(
        title = stringResource(title),
        detail = stringResource(R.string.quality_streaming_detail),
        options = listOf(
            Option(StreamingQuality.AUTO, R.string.quality_streaming_auto, R.string.quality_streaming_auto_detail),
            Option(StreamingQuality.LOW, R.string.quality_streaming_low, R.string.quality_streaming_low_detail),
            Option(StreamingQuality.NORMAL, R.string.quality_streaming_normal, R.string.quality_streaming_normal_detail),
            Option(StreamingQuality.HIGH, R.string.quality_streaming_high, R.string.quality_streaming_high_detail),
        ),
        selected = current,
        onSelect = { current = it; setStreamingQuality(ctx, it, cellular) },
        modifier = modifier,
    )
}

private class Option<T>(val value: T, val label: Int, val detail: Int)

@Composable
private fun <T> QualityGroup(
    title: String, detail: String, options: List<Option<T>>, selected: T,
    onSelect: (T) -> Unit, modifier: Modifier = Modifier,
) {
    Column(modifier.fillMaxWidth().selectableGroup()) {
        Text(title, color = Color.White, fontSize = 17.sp, fontWeight = FontWeight.Bold,
            modifier = Modifier.padding(horizontal = 20.dp, vertical = 4.dp))
        Text(detail, color = Color(0xFFB3B3B3), fontSize = 13.sp,
            modifier = Modifier.padding(start = 20.dp, end = 20.dp, bottom = 4.dp))
        for (o in options) {
            val on = o.value == selected
            Row(
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier.fillMaxWidth().heightIn(min = 56.dp)
                    .selectable(selected = on, role = Role.RadioButton, onClick = { onSelect(o.value) })
                    .padding(horizontal = 8.dp, vertical = 4.dp),
            ) {
                RadioButton(selected = on, onClick = null, modifier = Modifier.padding(horizontal = 8.dp),
                    colors = RadioButtonDefaults.colors(selectedColor = Color(0xFF1DB954), unselectedColor = Color(0xFFB3B3B3)))
                Column(Modifier.weight(1f).padding(end = 12.dp)) {
                    Text(stringResource(o.label), color = if (on) Color(0xFF1DB954) else Color.White, fontSize = 16.sp)
                    Text(stringResource(o.detail), color = Color(0xFFB3B3B3), fontSize = 12.sp)
                }
            }
        }
    }
}
