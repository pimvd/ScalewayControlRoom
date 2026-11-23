package com.pvd.scwcontrolroom.ui

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color

/** The iPhone app's accent: the icon's green, deeper in light mode. Never Scaleway's colours. */
private val light = lightColorScheme(
    primary = Color(0xFF0E8A63),
    onPrimary = Color.White,
    primaryContainer = Color(0xFFCDEFE1),
    onPrimaryContainer = Color(0xFF00281A),
    secondaryContainer = Color(0xFFD6EBE2),
    onSecondaryContainer = Color(0xFF0F2A20),
)

private val dark = darkColorScheme(
    primary = Color(0xFF34C08B),
    onPrimary = Color(0xFF00382A),
    primaryContainer = Color(0xFF00513C),
    onPrimaryContainer = Color(0xFFB4F1D6),
    secondaryContainer = Color(0xFF2B4A3E),
    onSecondaryContainer = Color(0xFFD6EBE2),
)

val Warning = Color(0xFFE08A00)

@Composable
fun ControlRoomTheme(content: @Composable () -> Unit) {
    MaterialTheme(colorScheme = if (isSystemInDarkTheme()) dark else light, content = content)
}
