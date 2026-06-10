package com.nax.atsupager.ui.components

import androidx.compose.animation.*
import androidx.compose.foundation.layout.*
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.RectangleShape
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp

@Composable
fun SelectionActionsBar(
    visible: Boolean,
    selectedCount: Int,
    onClear: () -> Unit,
    onDelete: () -> Unit,
    modifier: Modifier = Modifier,
    onSelectAll: (() -> Unit)? = null,
    onForward: () -> Unit = {},
    canCopy: Boolean = false,
    onCopy: () -> Unit = {},
    canSelectText: Boolean = false,
    onSelectText: () -> Unit = {},
    canExport: Boolean = false,
    onExport: () -> Unit = {}
) {
    AnimatedVisibility(
        visible = visible,
        enter = slideInVertically(initialOffsetY = { -it }) + fadeIn(),
        exit = slideOutVertically(targetOffsetY = { -it }) + fadeOut(),
        modifier = modifier
    ) {
        Surface(
            modifier = Modifier.fillMaxSize(),
            shape = RectangleShape,
            color = MaterialTheme.colorScheme.background,
            tonalElevation = 0.dp,
            shadowElevation = 2.dp
        ) {
            // Контент прижат к низу, так как верхняя часть тулбара обычно пустая в этом режиме
            Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.BottomCenter) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(56.dp)
                        .padding(horizontal = 12.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    IconButton(
                        onClick = onClear,
                        modifier = Modifier.size(42.dp)
                    ) {
                        Icon(
                            imageVector = Icons.Default.Close, 
                            contentDescription = "Cancel", 
                            tint = MaterialTheme.colorScheme.onBackground
                        )
                    }

                    if (onSelectAll != null) {
                        IconButton(
                            onClick = onSelectAll,
                            modifier = Modifier.size(42.dp)
                        ) {
                            Icon(
                                imageVector = Icons.Default.DoneAll, 
                                contentDescription = "Select All", 
                                tint = MaterialTheme.colorScheme.onBackground
                            )
                        }
                    }
                    
                    Text(
                        text = selectedCount.toString(),
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.Bold,
                        color = MaterialTheme.colorScheme.onBackground,
                        modifier = Modifier.padding(start = 12.dp)
                    )
                    
                    Spacer(modifier = Modifier.weight(1f))

                    // Кнопка пересылки доступна всегда, когда выбрано хотя бы одно сообщение
                    IconButton(onClick = onForward, modifier = Modifier.size(42.dp)) {
                        Icon(
                            imageVector = Icons.Default.Forward,
                            contentDescription = "Forward",
                            tint = MaterialTheme.colorScheme.primary
                        )
                    }

                    if (selectedCount == 1) {
                        if (canCopy) {
                            IconButton(onClick = onCopy, modifier = Modifier.size(42.dp)) {
                                Icon(
                                    imageVector = Icons.Default.ContentCopy,
                                    contentDescription = "Copy text",
                                    tint = MaterialTheme.colorScheme.onBackground
                                )
                            }
                        }
                        if (canSelectText) {
                            IconButton(onClick = onSelectText, modifier = Modifier.size(42.dp)) {
                                Icon(
                                    imageVector = Icons.Default.TextFields,
                                    contentDescription = "Select part",
                                    tint = MaterialTheme.colorScheme.onBackground
                                )
                            }
                        }
                        if (canExport) {
                            IconButton(onClick = onExport, modifier = Modifier.size(42.dp)) {
                                Icon(
                                    imageVector = Icons.Default.LockOpen,
                                    contentDescription = "Export/Decrypt",
                                    tint = Color.Red
                                )
                            }
                        }
                    }
                    
                    IconButton(
                        onClick = onDelete,
                        modifier = Modifier.size(42.dp)
                    ) {
                        Icon(
                            imageVector = Icons.Default.Delete,
                            contentDescription = "Delete selected",
                            tint = MaterialTheme.colorScheme.error,
                            modifier = Modifier.size(24.dp)
                        )
                    }
                }
            }
        }
    }
}
