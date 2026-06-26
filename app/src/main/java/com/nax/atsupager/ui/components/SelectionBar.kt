package com.nax.atsupager.ui.components

import androidx.compose.animation.*
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.RectangleShape
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.nax.atsupager.R

@Composable
fun RoundCheckbox(selected: Boolean, modifier: Modifier = Modifier) {
    val backgroundColor = if (selected) MaterialTheme.colorScheme.primary else Color.Transparent
    val borderColor = if (selected) MaterialTheme.colorScheme.primary else Color.Gray
    Box(
        modifier = modifier
            .size(24.dp)
            .clip(CircleShape)
            .background(backgroundColor)
            .border(2.dp, borderColor, CircleShape),
        contentAlignment = Alignment.Center
    ) {
        if (selected) {
            Icon(Icons.Default.Check, null, modifier = Modifier.size(16.dp), tint = Color.White)
        }
    }
}

@Composable
fun SelectionActionsBar(
    visible: Boolean,
    selectedCount: Int,
    totalCount: Int,
    onClear: () -> Unit,
    onDelete: () -> Unit,
    modifier: Modifier = Modifier,
    onSelectAll: (() -> Unit)? = null,
    onReply: () -> Unit = {},
    canReply: Boolean = false,
    onForward: () -> Unit = {},
    canForward: Boolean = false,
    canCopy: Boolean = false,
    onCopy: () -> Unit = {},
    canSelectText: Boolean = false,
    onSelectText: () -> Unit = {},
    canExport: Boolean = false,
    onExport: () -> Unit = {},
    canEdit: Boolean = false,
    onEdit: () -> Unit = {},
    canGroup: Boolean = false,
    onGroup: () -> Unit = {},
    canShare: Boolean = false,
    onShare: () -> Unit = {}
) {
    val isAllSelected = selectedCount == totalCount && totalCount > 0

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
            Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.BottomCenter) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(56.dp)
                        .padding(horizontal = 12.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Row(
                        modifier = Modifier
                            .clip(RoundedCornerShape(12.dp))
                            .clickable { if (isAllSelected) onClear() else onSelectAll?.invoke() }
                            .padding(horizontal = 8.dp, vertical = 8.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        RoundCheckbox(selected = isAllSelected)
                        Spacer(Modifier.width(12.dp))
                        Text(
                            text = stringResource(R.string.done_all),
                            style = MaterialTheme.typography.titleMedium,
                            fontWeight = FontWeight.Bold,
                            color = MaterialTheme.colorScheme.onBackground
                        )
                    }

                    Spacer(Modifier.width(12.dp))
                    Text(
                        text = selectedCount.toString(),
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.Bold,
                        color = MaterialTheme.colorScheme.primary
                    )

                    Spacer(modifier = Modifier.weight(1f))

                    if (selectedCount == 1) {
                        if (canEdit) {
                            IconButton(onClick = onEdit, modifier = Modifier.size(42.dp)) {
                                Icon(Icons.Default.Edit, "Edit", tint = MaterialTheme.colorScheme.primary)
                            }
                        }
                        if (canReply) {
                            IconButton(onClick = onReply, modifier = Modifier.size(42.dp)) {
                                Icon(imageVector = Icons.Default.Reply, contentDescription = "Reply", tint = MaterialTheme.colorScheme.primary)
                            }
                        }
                    }

                    if (canGroup && selectedCount >= 1) {
                        IconButton(onClick = onGroup, modifier = Modifier.size(42.dp)) {
                            Icon(Icons.Default.GroupAdd, "Group", tint = MaterialTheme.colorScheme.primary)
                        }
                    }

                    if (canForward) {
                        IconButton(onClick = onForward, modifier = Modifier.size(42.dp)) {
                            Icon(imageVector = Icons.Default.Forward, contentDescription = "Forward", tint = MaterialTheme.colorScheme.primary)
                        }
                    }

                    if (selectedCount == 1) {
                        if (canShare) {
                            IconButton(onClick = onShare, modifier = Modifier.size(42.dp)) {
                                Icon(Icons.Default.Share, "Share", tint = MaterialTheme.colorScheme.primary)
                            }
                        }
                        if (canCopy) {
                            IconButton(onClick = onCopy, modifier = Modifier.size(42.dp)) {
                                Icon(imageVector = Icons.Default.ContentCopy, contentDescription = "Copy", tint = MaterialTheme.colorScheme.primary)
                            }
                        }
                        if (canSelectText) {
                            IconButton(onClick = onSelectText, modifier = Modifier.size(42.dp)) {
                                Icon(imageVector = Icons.Default.TextFields, contentDescription = "Select part", tint = MaterialTheme.colorScheme.onBackground)
                            }
                        }
                        if (canExport) {
                            IconButton(onClick = onExport, modifier = Modifier.size(42.dp)) {
                                Icon(imageVector = Icons.Default.LockOpen, contentDescription = "Export", tint = Color.Red)
                            }
                        }
                    }

                    IconButton(onClick = onDelete, modifier = Modifier.size(42.dp)) {
                        Icon(imageVector = Icons.Default.Delete, contentDescription = "Delete", tint = MaterialTheme.colorScheme.error, modifier = Modifier.size(24.dp))
                    }
                }
            }
        }
    }
}
