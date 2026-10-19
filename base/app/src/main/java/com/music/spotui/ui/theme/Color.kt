package com.music.spotui.ui.theme

import androidx.compose.ui.graphics.Color

val AppBackground: Color get() = if (app.myspoti.power.EnergySettings.oledBlack) Color.Black else Color(0xFF121212)
val GridBackground = Color(0xFF2A2A2A)
/** The accent (Spotify green, or the colour a supporter chose). */
val AppPalette: Color get() = com.music.spotui.data.api.SupportReminder.accentColor
val AppAccent: Color get() = com.music.spotui.data.api.SupportReminder.accentColor