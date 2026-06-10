package com.nax.atsupager.ui.theme

import android.os.Build
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.dynamicDarkColorScheme
import androidx.compose.material3.dynamicLightColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext

private val DarkColorScheme = darkColorScheme(
    primary = DeepBluePrimaryDark,
    onPrimary = DeepBlueOnPrimaryDark,
    primaryContainer = DeepBluePrimaryContainerDark,
    onPrimaryContainer = DeepBlueOnPrimaryContainerDark,
    secondary = DeepBlueSecondaryDark,
    onSecondary = DeepBlueOnSecondaryDark,
    secondaryContainer = DeepBlueSecondaryContainerDark,
    onSecondaryContainer = DeepBlueOnSecondaryContainerDark,
    tertiary = DeepBlueTertiaryDark,
    onTertiary = DeepBlueOnTertiaryDark,
    tertiaryContainer = DeepBlueTertiaryContainerDark,
    onTertiaryContainer = DeepBlueOnTertiaryContainerDark,
    
    background = Color.Black,
    surface = Color(0xFF1C1C1E),
    onBackground = Color.White,
    onSurface = Color.White,
    surfaceVariant = Color(0xFF2C2C2E),
    onSurfaceVariant = Color.LightGray
)

private val LightColorScheme = lightColorScheme(
    primary = DeepBluePrimaryLight,
    onPrimary = DeepBlueOnPrimaryLight,
    primaryContainer = DeepBluePrimaryContainerLight,
    onPrimaryContainer = DeepBlueOnPrimaryContainerLight,
    secondary = DeepBlueSecondaryLight,
    onSecondary = DeepBlueOnSecondaryLight,
    secondaryContainer = DeepBlueSecondaryContainerLight,
    onSecondaryContainer = DeepBlueOnSecondaryContainerLight,
    tertiary = DeepBlueTertiaryLight,
    onTertiary = DeepBlueOnTertiaryLight,
    tertiaryContainer = DeepBlueTertiaryContainerLight,
    onTertiaryContainer = DeepBlueOnTertiaryContainerLight,

    background = Color.White,
    surface = Color.White,
    onBackground = Color.Black,
    onSurface = Color.Black,
    surfaceVariant = Color(0xFFF2F2F7),
    onSurfaceVariant = Color.Gray
)

@Composable
fun AtsuPagerTheme(
    darkTheme: Boolean = isSystemInDarkTheme(),
    dynamicColor: Boolean = false,
    appFont: AppFont = AppFont.SYSTEM,
    content: @Composable () -> Unit
) {
    val colorScheme = when {
        dynamicColor && Build.VERSION.SDK_INT >= Build.VERSION_CODES.S -> {
            val context = LocalContext.current
            if (darkTheme) dynamicDarkColorScheme(context) else dynamicLightColorScheme(context)
        }

        darkTheme -> DarkColorScheme
        else -> LightColorScheme
    }

    MaterialTheme(
        colorScheme = colorScheme,
        typography = getTypography(appFont),
        content = content
    )
}
