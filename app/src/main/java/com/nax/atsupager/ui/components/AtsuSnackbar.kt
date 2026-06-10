package com.nax.atsupager.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import coil.compose.AsyncImage
import com.nax.atsupager.R

@Composable
fun AtsuSnackbarHost(hostState: SnackbarHostState, modifier: Modifier = Modifier) {
    SnackbarHost(
        hostState = hostState,
        modifier = modifier,
        snackbar = { snackbarData ->
            AtsuSnackbar(snackbarData)
        }
    )
}

@Composable
fun AtsuSnackbar(snackbarData: SnackbarData) {
    // Проверяем яркость фона текущей темы приложения.
    val isAppDark = MaterialTheme.colorScheme.surface.luminance() < 0.5f
    
    // Настраиваем цвета в соответствии с выбранной темой приложения (не инвертировано):
    // - В темной теме приложения: темный фон (surfaceVariant) + яркий голубой акцент (0xFF00E5FF)
    // - В светлой теме приложения: светлый фон (surfaceVariant) + глубокий синий акцент (0xFF0091EA)
    val brandColor = if (isAppDark) Color(0xFF00E5FF) else Color(0xFF0091EA)
    
    val containerColor = MaterialTheme.colorScheme.surfaceVariant
    val contentColor = MaterialTheme.colorScheme.onSurfaceVariant

    // Центрируем снекбар по горизонтали и делаем его компактным
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .padding(bottom = 16.dp), // Отступ от низа
        contentAlignment = Alignment.Center
    ) {
        Surface(
            shape = RoundedCornerShape(24.dp),
            color = containerColor,
            contentColor = contentColor,
            tonalElevation = 4.dp,
            shadowElevation = 6.dp, // Увеличим тень для лучшей видимости на не-инвертированном фоне
            modifier = Modifier.padding(horizontal = 16.dp)
        ) {
            Row(
                modifier = Modifier
                    .padding(horizontal = 12.dp, vertical = 8.dp)
                    .widthIn(max = 300.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.Center
            ) {
                // Иконка приложения
                Box(
                    modifier = Modifier
                        .size(20.dp)
                        .clip(CircleShape)
                        .background(brandColor.copy(alpha = 0.15f))
                        .border(1.dp, brandColor.copy(alpha = 0.5f), CircleShape),
                    contentAlignment = Alignment.Center
                ) {
                    AsyncImage(
                        model = R.mipmap.ic_launcher_round,
                        contentDescription = null,
                        modifier = Modifier.fillMaxSize(),
                        contentScale = ContentScale.Fit
                    )
                }

                Spacer(modifier = Modifier.width(10.dp))

                Text(
                    text = snackbarData.visuals.message,
                    style = MaterialTheme.typography.bodyMedium,
                    fontSize = 13.sp,
                    lineHeight = 16.sp
                )

                if (!snackbarData.visuals.actionLabel.isNullOrEmpty()) {
                    Spacer(modifier = Modifier.width(8.dp))
                    TextButton(
                        onClick = { snackbarData.performAction() },
                        contentPadding = PaddingValues(horizontal = 8.dp, vertical = 0.dp),
                        modifier = Modifier.height(24.dp)
                    ) {
                        Text(
                            text = snackbarData.visuals.actionLabel!!,
                            color = brandColor,
                            style = MaterialTheme.typography.labelMedium,
                            fontWeight = FontWeight.Bold
                        )
                    }
                }
            }
        }
    }
}
