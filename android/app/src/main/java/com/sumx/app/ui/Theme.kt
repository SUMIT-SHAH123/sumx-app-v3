package com.sumx.app.ui

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.*
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color

object SumxColors {
    val Bg = Color(0xFF05070D); val Surface = Color(0xFF080D18); val Card = Color(0xFF0C1424)
    val Blue = Color(0xFF006BFF); val Cyan = Color(0xFF00D9FF)
    val Text = Color.White; val Muted = Color(0xFFA7B4CC); val Border = Color(0xFF1B2A44)
}

@Composable
fun SumxTheme(content: @Composable () -> Unit) {
    MaterialTheme(
        colorScheme = darkColorScheme(
            primary = SumxColors.Blue, secondary = SumxColors.Cyan, background = SumxColors.Bg,
            surface = SumxColors.Surface, onPrimary = Color.White, onBackground = SumxColors.Text,
            onSurface = SumxColors.Text, error = Color(0xFFFF5C7A)
        )
    ) { Surface(Modifier.fillMaxSize(), color = SumxColors.Bg, contentColor = SumxColors.Text, content = content) }
}
