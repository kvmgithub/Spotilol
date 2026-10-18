package com.music.spotui.ui.screens

import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.systemBarsPadding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import app.myspoti.engine.R
import com.music.spotui.ui.components.pressable
import com.music.spotui.ui.theme.AppBackground
import com.music.spotui.ui.theme.AppSans

/**
 * First screen of a signed-out app: what Myspoti is, then Spotify's own login page
 * (shown by the engine once [onLogin] is tapped).
 */
@Composable
fun WelcomeScreen(onLogin: () -> Unit) {
    Box(
        Modifier
            .fillMaxSize()
            .background(AppBackground)
            .background(
                Brush.radialGradient(
                    colors = listOf(Color(0x551ED760), Color.Transparent),
                    center = Offset(540f, 380f),
                    radius = 1100f,
                ),
            )
            .systemBarsPadding()
            .padding(horizontal = 28.dp, vertical = 24.dp),
    ) {
        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.Center,
            modifier = Modifier.fillMaxWidth().align(Alignment.Center).padding(bottom = 120.dp),
        ) {
            Image(
                painter = painterResource(R.drawable.ic_launcher_foreground),
                contentDescription = null,
                modifier = Modifier.size(132.dp).clip(RoundedCornerShape(36.dp)).background(Color(0xFF181818)),
            )
            Spacer(Modifier.height(28.dp))
            Text("Myspoti", color = Color.White, fontFamily = AppSans, fontWeight = FontWeight.ExtraBold, fontSize = 40.sp)
            Text(
                stringResource(R.string.welcome_tagline),
                color = Color.White.copy(alpha = 0.7f), fontFamily = AppSans, fontSize = 17.sp, lineHeight = 24.sp,
                textAlign = TextAlign.Center, modifier = Modifier.padding(top = 12.dp),
            )
        }
        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            modifier = Modifier.fillMaxWidth().align(Alignment.BottomCenter),
        ) {
            Box(
                contentAlignment = Alignment.Center,
                modifier = Modifier
                    .fillMaxWidth()
                    .height(54.dp)
                    .pressable(0.96f, onClick = onLogin)
                    .clip(RoundedCornerShape(50))
                    .background(com.music.spotui.ui.theme.AppAccent),
            ) {
                Text(stringResource(R.string.welcome_login), color = Color.Black, fontFamily = AppSans,
                    fontWeight = FontWeight.Bold, fontSize = 17.sp)
            }
            Text(
                stringResource(R.string.welcome_privacy),
                color = Color.White.copy(alpha = 0.5f), fontFamily = AppSans, fontSize = 13.sp, lineHeight = 18.sp,
                textAlign = TextAlign.Center, modifier = Modifier.padding(top = 14.dp),
            )
        }
    }
}
