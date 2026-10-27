package com.music.spotui.ui.theme

import androidx.compose.ui.graphics.Color

val AppBackground: Color get() = if (app.myspoti.power.EnergySettings.oledBlack) Color.Black else Color(0xFF121212)
val GridBackground = Color(0xFF2A2A2A)
/** The accent (Spotify green, or the colour a supporter chose). */
val AppPalette: Color get() = com.music.spotui.data.api.SupportReminder.accentColor
val AppAccent: Color get() = com.music.spotui.data.api.SupportReminder.accentColor
/** Primary text and icons (Myspoti 2.1.0's tokens, so its screens port unchanged). */
val AppText: Color = Color.White
/** Secondary text and icons. */
val AppMuted: Color = Color(0xFFB3B3B3)
/** Destructive actions (delete, leave, block) and failures shown as text. */
val AppDestructive = Color(0xFFF15E6C)
/** What a tint is mixed with to make a header. */
val AppShade: Color = Color.Black

/** One of the palette's raised greys (#181818 ... #3E3E3E): "a bit raised from the page". */
fun tone(argb: Long): Color = Color(argb)

/** The cover's colour as a page header: darkened toward black, so the title stays readable. */
fun headerTint(tint: Color, amount: Float): Color = androidx.compose.ui.graphics.lerp(tint, Color.Black, amount)
