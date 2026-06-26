package com.nax.atsupager.ui.components

import androidx.compose.animation.animateColorAsState
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.Send
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Groups
import androidx.compose.material.icons.filled.Search
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.nax.atsupager.R
import com.nax.atsupager.data.model.User
import com.nax.atsupager.security.KeyboardSecurity

data class PickerTarget(
    val id: String,
    val name: String,
    val isGroup: Boolean
)

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ContactPickerSheet(
    title: String? = null,
    contacts: List<User> = emptyList(),
    targets: List<PickerTarget>? = null,
    onContactsSelected: (List<String>) -> Unit,
    onDismiss: () -> Unit
) {
    var searchQuery by remember { mutableStateOf("") }
    var selectedIds by remember { mutableStateOf(setOf<String>()) }
    
    val allTargets = remember(contacts, targets) {
        targets ?: contacts.map { PickerTarget(it.id, it.username, false) }
    }
    
    val filteredTargets = remember(searchQuery, allTargets) {
        if (searchQuery.isBlank()) allTargets
        else allTargets.filter { 
            it.name.contains(searchQuery, ignoreCase = true) || 
            it.id.contains(searchQuery, ignoreCase = true) 
        }
    }

    val sortedTargets = remember(filteredTargets, selectedIds) {
        filteredTargets.sortedByDescending { it.id in selectedIds }
    }

    ModalBottomSheet(
        onDismissRequest = onDismiss,
        dragHandle = { BottomSheetDefaults.DragHandle() },
        containerColor = MaterialTheme.colorScheme.surface,
        shape = RoundedCornerShape(topStart = 28.dp, topEnd = 28.dp)
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .fillMaxHeight(0.85f)
                .padding(horizontal = 16.dp)
        ) {
            Row(
                modifier = Modifier.fillMaxWidth().padding(bottom = 16.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        text = title ?: stringResource(R.string.forward_to),
                        style = MaterialTheme.typography.titleLarge,
                        fontWeight = FontWeight.Bold
                    )
                    
                    if (selectedIds.isNotEmpty()) {
                        Spacer(Modifier.width(8.dp))
                        Surface(
                            color = MaterialTheme.colorScheme.primary,
                            shape = CircleShape,
                            modifier = Modifier.size(24.dp)
                        ) {
                            Box(contentAlignment = Alignment.Center) {
                                Text(
                                    text = selectedIds.size.toString(),
                                    color = Color.White,
                                    style = MaterialTheme.typography.labelSmall,
                                    fontWeight = FontWeight.Bold
                                )
                            }
                        }
                    }
                }

                if (selectedIds.isNotEmpty()) {
                    IconButton(
                        onClick = { onContactsSelected(selectedIds.toList()) },
                        colors = IconButtonDefaults.iconButtonColors(
                            containerColor = MaterialTheme.colorScheme.primaryContainer,
                            contentColor = MaterialTheme.colorScheme.onPrimaryContainer
                        )
                    ) {
                        Icon(Icons.AutoMirrored.Filled.Send, contentDescription = "Confirm")
                    }
                }
            }

            StyledTextField(
                value = searchQuery,
                onValueChange = { searchQuery = it },
                placeholderText = stringResource(R.string.search_contacts),
                modifier = Modifier.fillMaxWidth().padding(bottom = 16.dp),
                leadingIcon = { Icon(Icons.Default.Search, null) },
                keyboardOptions = KeyboardSecurity.secureChatOptions
            )

            LazyColumn(
                modifier = Modifier.fillMaxWidth(),
                verticalArrangement = Arrangement.spacedBy(4.dp),
                contentPadding = PaddingValues(bottom = 24.dp)
            ) {
                items(sortedTargets, key = { it.id }) { target ->
                    val isSelected = selectedIds.contains(target.id)
                    val backgroundColor by animateColorAsState(
                        targetValue = if (isSelected) MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.5f) 
                                      else Color.Transparent,
                        label = "background"
                    )

                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clip(RoundedCornerShape(12.dp))
                            .background(backgroundColor)
                            .clickable {
                                selectedIds = if (isSelected) selectedIds - target.id 
                                             else selectedIds + target.id
                            }
                            .padding(vertical = 12.dp, horizontal = 12.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Box(contentAlignment = Alignment.BottomEnd) {
                            if (target.isGroup) {
                                Surface(
                                    modifier = Modifier.size(44.dp),
                                    shape = CircleShape,
                                    color = MaterialTheme.colorScheme.primaryContainer
                                ) {
                                    Box(contentAlignment = Alignment.Center) {
                                        Icon(Icons.Default.Groups, null, tint = MaterialTheme.colorScheme.primary)
                                    }
                                }
                            } else {
                                ContactAvatar(username = target.name, modifier = Modifier.size(44.dp))
                            }
                            
                            if (isSelected) {
                                Surface(
                                    color = MaterialTheme.colorScheme.primary,
                                    shape = CircleShape,
                                    modifier = Modifier.size(16.dp).offset(x = 2.dp, y = 2.dp),
                                    border = BorderStroke(1.5.dp, MaterialTheme.colorScheme.surface)
                                ) {
                                    Icon(Icons.Default.Check, null, tint = Color.White, modifier = Modifier.padding(2.dp))
                                }
                            }
                        }
                        Spacer(modifier = Modifier.width(16.dp))
                        Column {
                            Text(
                                text = target.name,
                                style = MaterialTheme.typography.bodyLarge,
                                fontWeight = if (isSelected) FontWeight.Bold else FontWeight.Medium
                            )
                            Text(
                                text = if (target.isGroup) stringResource(R.string.group) else target.id.take(12) + "...",
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                    }
                }
            }
        }
    }
}
