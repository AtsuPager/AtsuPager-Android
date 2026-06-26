/*
 * Copyright (c) 2026 AtsuPager Author. All rights reserved.
 * Published for security audit and educational purposes only.
 */

package com.nax.atsupager.webrtc

import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import androidx.core.app.NotificationCompat
import com.nax.atsupager.MainActivity
import com.nax.atsupager.R
import com.nax.atsupager.data.db.GroupDao
import com.nax.atsupager.data.network.UserRepository
import com.nax.atsupager.ui.screens.contacts.ContactsRepository
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class NotificationHelper @Inject constructor(
    @ApplicationContext private val context: Context,
    private val userRepository: UserRepository,
    private val contactsRepository: ContactsRepository,
    private val groupDao: GroupDao
) {
    private val helperScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val manager = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager

    fun showCallNotification(fromUserId: String, callId: String, isVideo: Boolean, networkName: String? = null) {
        helperScope.launch {
            val userInContacts = contactsRepository.getContact(fromUserId)
            val name = userInContacts?.username 
                ?: networkName 
                ?: userRepository.getUser(fromUserId)?.username 
                ?: "User_${fromUserId.takeLast(4)}"

            val intent = Intent(context, MainActivity::class.java).apply {
                flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP
                putExtra("incoming_call_from", fromUserId)
                putExtra("call_id", callId)
                putExtra("is_video", isVideo)
                putExtra("caller_network_name", networkName)
            }
            val pendingIntent = PendingIntent.getActivity(
                context, callId.hashCode(), intent,
                PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
            )
            val builder = NotificationCompat.Builder(context, "AtsuCallChannel_v1")
                .setSmallIcon(R.drawable.ic_launcher_foreground)
                .setContentTitle(context.getString(R.string.incoming_call))
                .setContentText(name)
                .setPriority(NotificationCompat.PRIORITY_MAX)
                .setCategory(NotificationCompat.CATEGORY_CALL)
                .setAutoCancel(true)
                .setOngoing(true)
                .setFullScreenIntent(pendingIntent, true)
            
            manager.notify(NtfyService.CALL_NOTIFICATION_ID, builder.build())
        }
    }

    fun showUINotification(id: String, body: String, isGroup: Boolean = false) {
        helperScope.launch {
            val name: String
            if (isGroup) {
                val group = groupDao.getGroupById(id)
                name = group?.name ?: "${context.getString(R.string.group)} ${id.takeLast(4)}"
            } else {
                val contact = contactsRepository.getContact(id)
                val user = userRepository.getUser(id)
                if (contact == null && user == null && id.contains("-")) {
                    // Possible group leftovers
                    name = "${context.getString(R.string.group)} ${id.takeLast(4)}"
                } else {
                    name = contact?.username ?: user?.username ?: id
                }
            }
            
            val intent = Intent(context, MainActivity::class.java).apply {
                flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP
                if (isGroup) putExtra("chat_with_group_id", id) 
                else putExtra("chat_with_user_id", id)
            }
            val pendingIntent = PendingIntent.getActivity(
                context, id.hashCode(), intent,
                PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
            )
            val builder = NotificationCompat.Builder(context, "AtsuMessageChannel_v3")
                .setSmallIcon(R.drawable.ic_launcher_foreground)
                .setContentTitle(name)
                .setContentText(body)
                .setAutoCancel(true)
                .setContentIntent(pendingIntent)
                .setPriority(NotificationCompat.PRIORITY_HIGH)
            
            manager.notify(id.hashCode(), builder.build())
        }
    }

    fun cancelNotification(notificationId: Int) {
        manager.cancel(notificationId)
    }
    
    fun cancelCallNotification() {
        manager.cancel(NtfyService.CALL_NOTIFICATION_ID)
    }
}
