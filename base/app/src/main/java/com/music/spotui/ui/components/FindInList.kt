package com.music.spotui.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Search
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import java.text.Normalizer
import java.util.Locale

private val MARKS = Regex("""\p{M}+""")
private val SPACES = Regex("""\s+""")

/**
 * Titles and names folded once (lower case, no accents) and kept: a playlist search folds each
 * row's fields on every key typed. The patterns were compiled again for every field of every row
 * and held the main thread over a second a key on a big playlist (reported: typing in the search
 * froze the player until the app crashed).
 */
private const val FOLDED_KEEP = 20_000
private val folded = object : LinkedHashMap<String, String>(1024, 0.75f, true) {
    override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, String>?) = size > FOLDED_KEEP
}
private fun fold(s: String): String = if (s.isEmpty()) s else synchronized(folded) {
    folded.getOrPut(s) { Normalizer.normalize(s, Normalizer.Form.NFD).replace(MARKS, "").lowercase(Locale.ROOT) }
}

/**
 * "Find in…" match: every word of [query] appears in one of [fields], ignoring case and
 * accents ("beyonce hal" finds "Halo" by Beyoncé). A blank query matches everything.
 */
fun matchesFind(query: String, vararg fields: String): Boolean {
    val words = fold(query).split(SPACES).filter { it.isNotEmpty() }
    if (words.isEmpty()) return true
    return words.all { w -> fields.any { fold(it).contains(w) } }
}

/** The "Find in…" field at the top of a list of songs. */
@Composable
fun FindInListField(query: String, onQuery: (String) -> Unit, hint: String, modifier: Modifier = Modifier) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = modifier.fillMaxWidth().clip(RoundedCornerShape(8.dp)).background(Color(0x33FFFFFF)).padding(horizontal = 12.dp),
    ) {
        Icon(Icons.Default.Search, contentDescription = null, tint = Color.White, modifier = Modifier.size(18.dp))
        BasicTextField(
            value = query,
            onValueChange = onQuery,
            singleLine = true,
            textStyle = TextStyle(color = Color.White, fontSize = 15.sp),
            cursorBrush = SolidColor(Color.White),
            keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
            modifier = Modifier.weight(1f).padding(8.dp, 10.dp),
            decorationBox = { inner ->
                Box {
                    if (query.isEmpty()) Text(hint, color = Color(0xFFB3B3B3), fontSize = 15.sp, maxLines = 1, overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis)
                    inner()
                }
            },
        )
        if (query.isNotEmpty()) Icon(
            Icons.Default.Close, contentDescription = null, tint = Color(0xFFB3B3B3),
            modifier = Modifier.size(18.dp).pressable(0.92f) { onQuery("") },
        )
    }
}

/** "Couldn't find …" under a "Find in…" field with no match. */
@Composable
fun FindInListEmpty(text: String) {
    Text(text, color = Color(0xFFB3B3B3), fontSize = 14.sp, modifier = Modifier.fillMaxWidth().padding(16.dp, 24.dp))
}
