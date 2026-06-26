package com.nax.atsupager.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.PersonRemove
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.Star
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

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun GroupMembersSheet(
    members: List<User>,
    ownerId: String,
    currentUserId: String,
    onKickMember: (String) -> Unit,
    onDismiss: () -> Unit
) {
    var searchQuery by remember { mutableStateOf("") }
    var memberToKick by remember { mutableStateOf<User?>(null) }

    val filteredMembers = remember(searchQuery, members) {
        if (searchQuery.isBlank()) members
        else members.filter { 
            it.username.contains(searchQuery, ignoreCase = true) || 
            it.id.contains(searchQuery, ignoreCase = true) 
        }
    }

    val isOwner = currentUserId == ownerId

    if (memberToKick != null) {
        AlertDialog(
            onDismissRequest = { memberToKick = null },
            title = { 
                Text(
                    text = stringResource(R.string.delete),
                    style = MaterialTheme.typography.headlineSmall
                ) 
            },
            text = { 
                Text(
                    text = stringResource(R.string.kick_member_confirm, memberToKick?.username ?: ""),
                    style = MaterialTheme.typography.bodyMedium
                ) 
            },
            confirmButton = {
                Button(
                    onClick = {
                        memberToKick?.let { onKickMember(it.id) }
                        memberToKick = null
                    },
                    colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.error),
                    shape = RoundedCornerShape(12.dp)
                ) {
                    Text(stringResource(R.string.delete))
                }
            },
            dismissButton = {
                TextButton(onClick = { memberToKick = null }) {
                    Text(stringResource(R.string.cancel))
                }
            }
        )
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
            Text(
                text = stringResource(R.string.group_members).ifEmpty { "Group Members" },
                style = MaterialTheme.typography.titleLarge,
                fontWeight = FontWeight.Bold,
                modifier = Modifier.padding(bottom = 16.dp)
            )

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
                items(filteredMembers, key = { it.id }) { member ->
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clip(RoundedCornerShape(12.dp))
                            .padding(vertical = 8.dp, horizontal = 8.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Box(contentAlignment = Alignment.BottomEnd) {
                            ContactAvatar(username = member.username, size = 44.dp)
                            if (member.id == ownerId) {
                                Surface(
                                    color = Color(0xFFFBC02D), // Gold/Star color
                                    shape = androidx.compose.foundation.shape.CircleShape,
                                    modifier = Modifier.size(16.dp).offset(x = 2.dp, y = 2.dp),
                                    border = androidx.compose.foundation.BorderStroke(1.5.dp, MaterialTheme.colorScheme.surface)
                                ) {
                                    Icon(Icons.Default.Star, null, tint = Color.White, modifier = Modifier.padding(2.dp))
                                }
                            }
                        }
                        
                        Spacer(modifier = Modifier.width(16.dp))
                        
                        Column(modifier = Modifier.weight(1f)) {
                            Text(
                                text = if (member.id == currentUserId) "${member.username} (You)" else member.username,
                                style = MaterialTheme.typography.bodyLarge,
                                fontWeight = FontWeight.SemiBold
                            )
                            if (member.id == ownerId) {
                                Text(
                                    text = stringResource(R.string.owner_label),
                                    style = MaterialTheme.typography.labelSmall,
                                    color = Color(0xFFFBC02D)
                                )
                            } else {
                                Text(
                                    text = member.id.take(12) + "...",
                                    style = MaterialTheme.typography.labelSmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                            }
                        }

                        if (isOwner && member.id != currentUserId) {
                            IconButton(
                                onClick = { memberToKick = member },
                                colors = IconButtonDefaults.iconButtonColors(
                                    contentColor = MaterialTheme.colorScheme.error
                                )
                            ) {
                                Icon(Icons.Default.PersonRemove, contentDescription = "Kick")
                            }
                        }
                    }
                }
            }
        }
    }
}
