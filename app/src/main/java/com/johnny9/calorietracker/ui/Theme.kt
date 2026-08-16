package com.johnny9.calorietracker.ui

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color

private val LightColors = lightColorScheme(
    primary = Color(0xFF8B3D23),
    onPrimary = Color.White,
    primaryContainer = Color(0xFFFFDBCF),
    onPrimaryContainer = Color(0xFF351000),
    secondary = Color(0xFF52643F),
    secondaryContainer = Color(0xFFD5E9BC),
    tertiary = Color(0xFF3E6374),
    background = Color(0xFFFFF8F5),
    surface = Color(0xFFFFF8F5),
    surfaceVariant = Color(0xFFF4DED6),
)

private val DarkColors = darkColorScheme(
    primary = Color(0xFFFFB59C),
    onPrimary = Color(0xFF541F0B),
    primaryContainer = Color(0xFF71351F),
    secondary = Color(0xFFB9CDA2),
    tertiary = Color(0xFFA6CDDF),
    background = Color(0xFF17120F),
    surface = Color(0xFF17120F),
    surfaceVariant = Color(0xFF584139),
)

@Composable
fun CalorieTrackerTheme(content: @Composable () -> Unit) {
    MaterialTheme(
        colorScheme = if (isSystemInDarkTheme()) DarkColors else LightColors,
        content = content,
    )
}
