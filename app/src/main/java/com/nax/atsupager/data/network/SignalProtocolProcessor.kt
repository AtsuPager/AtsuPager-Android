/*
 * Copyright (c) 2026 AtsuPager Author. All rights reserved.
 * Published for security audit and educational purposes only.
 */

package com.nax.atsupager.data.network

import android.content.Context
import android.content.SharedPreferences
import com.google.gson.Gson
import com.nax.atsupager.R
import com.nax.atsupager.data.db.*
import com.nax.atsupager.security.EncryptionManager
import com.nax.atsupager.ui.screens.settings.SettingsViewModel
import com.nax.atsupager.webrtc.CallStatusManager
import com.nax.atsupager.webrtc.NotificationHelper
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.flow.first
import java.io.File
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class SignalProtocolProcessor @Inject constructor(
    @ApplicationContext private val context: Context,
    private val messageDao: MessageDao,
    private val groupDao: GroupDao,
    private val userRepository: UserRepository,
    private val notificationHelper: NotificationHelper,
    private val callStatusManager: CallStatusManager,
    private val sharedPreferences: SharedPreferences,
    private val gson: Gson
) {

    suspend fun handleSignal(from: String, rtcData: SignalData, createdAt: Long, currentUserId: String) {
        // Проверка "Только контакты" для сигналов вызова и игр
        val isGameInvite = rtcData.type == SignalType.GAME_INVITE || 
                          rtcData.type == SignalType.CHESS_INVITE || 
                          rtcData.type == SignalType.BACKGAMMON_INVITE || 
                          rtcData.type == SignalType.CHECKERS_INVITE

        if (rtcData.type == SignalType.OFFER || isGameInvite) {
            val onlyContacts = sharedPreferences.getBoolean(SettingsViewModel.PREF_ONLY_CONTACTS, false)
            if (onlyContacts && !userRepository.isContact(from)) return
        }

        when (rtcData.type) {
            SignalType.MSG_DELIVERED -> {
                rtcData.payload?.let { payload ->
                    val packet = gson.fromJson(payload, ReceiptPacket::class.java)
                    messageDao.markAsDelivered(packet.remoteId)
                }
            }
            SignalType.MSG_READ, SignalType.FILE_DOWNLOADED -> {
                rtcData.payload?.let { payload ->
                    val packet = gson.fromJson(payload, ReceiptPacket::class.java)
                    messageDao.markAsRemoteRead(packet.remoteId)
                }
            }
            SignalType.MSG_DELETE -> {
                rtcData.payload?.let { payload ->
                    try {
                        val deletePacket = gson.fromJson(payload, DeleteMessagePacket::class.java)
                        val message = messageDao.getMessageByRemoteId(deletePacket.remoteId)
                        if (message != null) {
                            val isAuthor = message.fromUserId == from
                            val isAdmin = message.groupId?.let { gid -> 
                                val group = groupDao.getGroupById(gid)
                                val role = groupDao.getUserRole(gid, from)
                                from == group?.ownerId || role == "ADMIN"
                            } ?: false
                            if (isAuthor || isAdmin) {
                                message.localFilePath?.let { path -> try { File(path).delete() } catch (e: Exception) { } }
                                messageDao.deleteByRemoteId(deletePacket.remoteId)
                            }
                        }
                    } catch (e: Exception) { }
                }
            }
            SignalType.MSG_MULTI_DELETE -> {
                rtcData.payload?.let { payload ->
                    try {
                        val multiDeletePacket = gson.fromJson(payload, MultiDeletePacket::class.java)
                        val messages = messageDao.getMessagesByRemoteIds(multiDeletePacket.remoteIds)
                        messages.forEach { message ->
                            val isAuthor = message.fromUserId == from
                            val isAdmin = message.groupId?.let { gid -> 
                                val group = groupDao.getGroupById(gid)
                                val role = groupDao.getUserRole(gid, from)
                                from == group?.ownerId || role == "ADMIN"
                            } ?: false
                            if (isAuthor || isAdmin) {
                                message.localFilePath?.let { path -> try { File(path).delete() } catch (e: Exception) { } }
                                messageDao.deleteByRemoteId(message.remoteId)
                            }
                        }
                    } catch (e: Exception) { }
                }
            }
            SignalType.MSG_BULK_DELETE -> {
                rtcData.payload?.let { payload ->
                    try {
                        val packet = gson.fromJson(payload, BulkDeletePacket::class.java)
                        if (from != packet.senderId) return@let
                        val isGroup = packet.chatId.contains("-") || groupDao.getGroupById(packet.chatId) != null
                        if (isGroup) {
                            val existing = groupDao.getGroupById(packet.chatId) ?: return@let
                            val senderRole = groupDao.getUserRole(packet.chatId, from)
                            if (packet.isFullClear) {
                                if (from == existing.ownerId || senderRole == "ADMIN") {
                                    val allMsgs = messageDao.getMessagesForGroupSync(packet.chatId)
                                    allMsgs.forEach { msg -> msg.localFilePath?.let { try { File(it).delete() } catch(_:Exception){} } }
                                    messageDao.forceDeleteAllMessagesForGroup(packet.chatId)
                                }
                            } else {
                                val anchorMsg = packet.lastRemoteId?.let { messageDao.getMessageByRemoteId(it) }
                                val boundaryTime = anchorMsg?.timestamp ?: (packet.beforeTimestamp + 10000L)
                                val authorMsgs = messageDao.getMessagesByAuthorInGroupSync(from, packet.chatId, boundaryTime)
                                authorMsgs.forEach { msg -> msg.localFilePath?.let { try { File(it).delete() } catch(_:Exception){} } }
                                messageDao.deleteMessagesByAuthorInGroup(from, packet.chatId, boundaryTime)
                            }
                        } else {
                            val anchorMsg = packet.lastRemoteId?.let { messageDao.getMessageByRemoteId(it) }
                            val boundaryTime = anchorMsg?.timestamp ?: (packet.beforeTimestamp + 10000L)
                            val messagesWithFiles = messageDao.getMessagesByAuthorInChatSync(packet.senderId, currentUserId, boundaryTime)
                            messagesWithFiles.forEach { msg -> msg.localFilePath?.let { path -> try { File(path).delete() } catch (e: Exception) { } } }
                            messageDao.deleteMessagesByAuthorInChat(packet.senderId, currentUserId, boundaryTime)
                        }
                    } catch (e: Exception) { }
                }
            }
            SignalType.GROUP_INVITE -> {
                rtcData.payload?.let { payload ->
                    val invite = gson.fromJson(payload, GroupInvitePacket::class.java)
                    if (from != invite.ownerId) return@let
                    val group = GroupEntity(invite.groupId, invite.name, invite.ownerId, System.currentTimeMillis())
                    groupDao.insertGroup(group)
                    groupDao.insertMembers(invite.members.map { GroupMemberEntity(invite.groupId, it.userId, it.role) })
                    messageDao.insertMessage(ChatMessage(fromUserId = from, toUserId = currentUserId, groupId = invite.groupId, text = "SYSTEM_JOINED_GROUP", timestamp = createdAt, type = MessageType.SYSTEM))
                    notificationHelper.showUINotification(invite.groupId, context.getString(R.string.notification_new_msg), isGroup = true)
                }
            }
            SignalType.GROUP_LEAVE -> {
                rtcData.payload?.let { payload ->
                    val packet = gson.fromJson(payload, GroupControlPacket::class.java)
                    groupDao.deleteMember(packet.groupId, packet.userId)
                    messageDao.insertMessage(ChatMessage(
                        fromUserId = packet.userId,
                        toUserId = currentUserId,
                        groupId = packet.groupId,
                        text = "SYSTEM_MEMBER_LEFT:${packet.userId}",
                        timestamp = createdAt,
                        type = MessageType.SYSTEM
                    ))
                }
            }
            SignalType.GROUP_KICK -> {
                rtcData.payload?.let { payload ->
                    val packet = gson.fromJson(payload, GroupControlPacket::class.java)
                    val group = groupDao.getGroupById(packet.groupId) ?: return@let
                    val senderRole = groupDao.getUserRole(packet.groupId, from)
                    if (from == group.ownerId || senderRole == "ADMIN") {
                        if (packet.userId == currentUserId) {
                            notificationHelper.showUINotification(packet.groupId, context.getString(R.string.system_you_kicked), isGroup = true)
                            val allMsgs = messageDao.getMessagesForGroupSync(packet.groupId)
                            allMsgs.filter { !it.isSaved }.forEach { msg -> msg.localFilePath?.let { try { File(it).delete() } catch(_:Exception){} } }
                            groupDao.deleteGroup(packet.groupId)
                            messageDao.deleteAllMessagesForGroup(packet.groupId)
                        } else {
                            groupDao.deleteMember(packet.groupId, packet.userId)
                            messageDao.insertMessage(ChatMessage(
                                fromUserId = from,
                                toUserId = currentUserId,
                                groupId = packet.groupId,
                                text = "SYSTEM_MEMBER_KICKED:${packet.userId}",
                                timestamp = createdAt,
                                type = MessageType.SYSTEM
                            ))
                        }
                    }
                }
            }
            SignalType.GROUP_UPDATE -> {
                rtcData.payload?.let { payload ->
                    val packet = gson.fromJson(payload, GroupUpdatePacket::class.java)
                    val group = groupDao.getGroupById(packet.groupId) ?: return@let
                    
                    if (packet.ownerId != null && packet.ownerId != group.ownerId) {
                        groupDao.updateGroupOwner(packet.groupId, packet.ownerId)
                    }
                    
                    val existingMembers = groupDao.getGroupMemberIds(packet.groupId)
                    val newMembers = packet.members.map { it.userId }
                    
                    existingMembers.forEach { if (!newMembers.contains(it)) groupDao.deleteMember(packet.groupId, it) }
                    groupDao.insertMembers(packet.members.map { GroupMemberEntity(packet.groupId, it.userId, it.role) })
                }
            }
            SignalType.GROUP_RENAME -> {
                rtcData.payload?.let { payload ->
                    val packet = gson.fromJson(payload, GroupRenamePacket::class.java)
                    val group = groupDao.getGroupById(packet.groupId) ?: return@let
                    val senderRole = groupDao.getUserRole(packet.groupId, from)
                    if (from == group.ownerId || senderRole == "ADMIN") {
                        groupDao.renameGroup(packet.groupId, packet.name)
                        messageDao.insertMessage(ChatMessage(
                            fromUserId = from,
                            toUserId = currentUserId,
                            groupId = packet.groupId,
                            text = "SYSTEM_GROUP_RENAMED:${packet.name}",
                            timestamp = createdAt,
                            type = MessageType.SYSTEM
                        ))
                    }
                }
            }
            SignalType.GROUP_DELETE -> {
                rtcData.payload?.let { payload ->
                    val packet = gson.fromJson(payload, GroupControlPacket::class.java)
                    val group = groupDao.getGroupById(packet.groupId) ?: return@let
                    if (from == group.ownerId) {
                        notificationHelper.showUINotification(packet.groupId, context.getString(R.string.delete_group), isGroup = true)
                        val allMsgs = messageDao.getMessagesForGroupSync(packet.groupId)
                        allMsgs.forEach { msg -> msg.localFilePath?.let { try { File(it).delete() } catch(_:Exception){} } }
                        groupDao.deleteGroup(packet.groupId)
                        messageDao.forceDeleteAllMessagesForGroup(packet.groupId)
                    }
                }
            }
            SignalType.OFFER -> {
                if (callStatusManager.isRecentlyEnded(rtcData.callId)) return
                val currentCall = callStatusManager.activeCall.value
                if (currentCall != null && currentCall.callId != rtcData.callId) return
                
                messageDao.insertMessage(ChatMessage(
                    fromUserId = from,
                    toUserId = currentUserId,
                    text = "CALL_INCOMING",
                    timestamp = createdAt,
                    type = MessageType.INCOMING_CALL
                ))

                notificationHelper.showCallNotification(from, rtcData.callId ?: "unknown", rtcData.isVideo, rtcData.senderName)
                callStatusManager.startCall(from, false, rtcData.isVideo, rtcData.callId, rtcData.payload, rtcData.senderName)
            }
            SignalType.BYE -> {
                notificationHelper.cancelCallNotification()
                val lastIncoming = messageDao.getLastMessageByType(from, MessageType.INCOMING_CALL)
                if (lastIncoming != null && lastIncoming.text == "CALL_INCOMING") {
                    messageDao.updateMessageTypeAndText(lastIncoming.id, MessageType.MISSED_CALL, "CALL_MISSED")
                    // Показываем уведомление о пропущенном звонке в шторке
                    notificationHelper.showUINotification(from, context.getString(R.string.missed_call), isGroup = false)
                }
            }
            SignalType.GAME_INVITE, SignalType.CHESS_INVITE, SignalType.BACKGAMMON_INVITE, SignalType.CHECKERS_INVITE -> {
                val gameName = when(rtcData.type) {
                    SignalType.CHESS_INVITE -> context.getString(R.string.chess)
                    SignalType.BACKGAMMON_INVITE -> context.getString(R.string.backgammon)
                    SignalType.CHECKERS_INVITE -> context.getString(R.string.checkers)
                    else -> context.getString(R.string.games)
                }
                val senderName = rtcData.senderName ?: userRepository.getUser(from)?.username ?: from.takeLast(6)
                val body = context.getString(R.string.invite_msg, senderName, gameName)
                notificationHelper.showUINotification(from, body, isGroup = false)
            }
            else -> {}
        }
    }

    suspend fun handleChatMessage(from: String, chatMessage: ChatMessage, createdAt: Long, currentUserId: String) {
        val onlyContacts = sharedPreferences.getBoolean(SettingsViewModel.PREF_ONLY_CONTACTS, false)
        if (onlyContacts && chatMessage.groupId == null) {
            if (!userRepository.isContact(from)) return
        }

        val sanitizedType = if (chatMessage.type == MessageType.SYSTEM) MessageType.TEXT else chatMessage.type
        val incomingMsg = chatMessage.copy(
            id = 0,
            fromUserId = from,
            toUserId = currentUserId,
            timestamp = createdAt,
            isRead = false,
            localFilePath = null,
            type = sanitizedType
        )
        messageDao.insertMessage(incomingMsg)

        val isMuted = if (incomingMsg.groupId != null) {
            groupDao.getGroupById(incomingMsg.groupId)?.isMuted ?: false
        } else {
            userRepository.getUser(from)?.isMuted ?: false
        }

        if (!isMuted) {
            notificationHelper.showUINotification(incomingMsg.groupId ?: from, context.getString(R.string.notification_new_msg), isGroup = incomingMsg.groupId != null)
        }
    }

    suspend fun handleTextMessage(from: String, text: String, createdAt: Long, currentUserId: String) {
        val onlyContacts = sharedPreferences.getBoolean(SettingsViewModel.PREF_ONLY_CONTACTS, false)
        if (onlyContacts && !userRepository.isContact(from)) return

        val newMsg = ChatMessage(fromUserId = from, toUserId = currentUserId, text = text, timestamp = createdAt, isRead = false, type = MessageType.TEXT)
        messageDao.insertMessage(newMsg)

        val isMuted = userRepository.getUser(from)?.isMuted ?: false
        if (!isMuted) {
            notificationHelper.showUINotification(from, context.getString(R.string.notification_new_msg))
        }
    }
}
