package com.music.spotui.ui.theme

import androidx.compose.ui.graphics.Color

val Purple80 = Color(0xFFD0BCFF)
val PurpleGrey80 = Color(0xFFCCC2DC)
val Pink80 = Color(0xFFEFB8C8)

val Purple40 = Color(0xFF6650a4)
val PurpleGrey40 = Color(0xFF625b71)
val Pink40 = Color(0xFF7D5260)
val AppBackground: Color get() = if (app.myspoti.power.EnergySettings.oledBlack) Color.Black else Color(0xFF121212)
val GridBackground = Color(0xFF2A2A2A)
/** The accent (Spotify green, or the colour a supporter chose). */
val AppPalette: Color get() = com.music.spotui.data.api.SupportReminder.accentColor
val AppAccent: Color get() = com.music.spotui.data.api.SupportReminder.accentColor