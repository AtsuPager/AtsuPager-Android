/*
 * Copyright (c) 2026 AtsuPager Author. All rights reserved.
 * Published for security audit and educational purposes only.
 */

package com.nax.atsupager.data.network

import com.google.gson.Gson
import com.nax.atsupager.data.db.GroupDao
import com.nax.atsupager.data.db.GroupEntity
import com.nax.atsupager.data.db.GroupMemberEntity
import com.nax.atsupager.data.db.MessageDao
import com.nax.atsupager.data.db.ChatMessage
import com.nax.atsupager.data.db.MessageType
import kotlinx.coroutines.flow.Flow
import java.util.UUID
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class GroupRepository @Inject constructor(
    private val groupDao: GroupDao,
    private val messageDao: MessageDao,
    private val userRepository: UserRepository,
    private val signalRepository: SignalRepository
) {
    private val gson = Gson()

    fun getAllGroupsFlow(): Flow<List<GroupEntity>> = groupDao.getAllGroupsFlow()

    suspend fun getGroupById(groupId: String): GroupEntity? = groupDao.getGroupById(groupId)

    suspend fun createGroup(name: String, memberIds: List<String>): String {
        val groupId = UUID.randomUUID().toString()
        val currentUserId = userRepository.getCurrentUserId() ?: return ""
        
        val group = GroupEntity(
            groupId = groupId,
            name = name,
            ownerId = currentUserId,
            createdAt = System.currentTimeMillis()
        )
        
        val members = (memberIds + currentUserId).distinct().map { userId ->
            GroupMemberEntity(groupId = groupId, userId = userId, role = if (userId == currentUserId) "ADMIN" else "MEMBER")
        }
        
        groupDao.insertGroup(group)
        groupDao.insertMembers(members)

        val memberPackets = members.map { GroupMemberPacket(it.userId, it.role) }
        signalRepository.sendGroupInvite(groupId, name, memberPackets, currentUserId)

        messageDao.insertMessage(ChatMessage(
            fromUserId = currentUserId,
            toUserId = "",
            groupId = groupId,
            text = "SYSTEM_JOINED_GROUP",
            timestamp = System.currentTimeMillis(),
            type = MessageType.SYSTEM
        ))
        
        return groupId
    }

    suspend fun addMembersToGroup(groupId: String, memberIds: List<String>) {
        val group = groupDao.getGroupById(groupId) ?: return
        val currentUserId = userRepository.getCurrentUserId() ?: return
        val currentMembers = groupDao.getGroupMemberIds(groupId)
        
        val newMemberIds = memberIds.filter { !currentMembers.contains(it) }
        if (newMemberIds.isEmpty()) return

        newMemberIds.forEach { groupDao.insertMembers(listOf(GroupMemberEntity(groupId, it))) }
        
        val allMembers = groupDao.getGroupMembersSync(groupId)
        val memberPackets = allMembers.map { GroupMemberPacket(it.userId, it.role) }

        signalRepository.sendGroupInvite(groupId, group.name, memberPackets, currentUserId)

        val updateSignal = SignalData(
            callId = "group_ctrl",
            type = SignalType.GROUP_UPDATE,
            payload = gson.toJson(GroupUpdatePacket(groupId, memberPackets, group.ownerId))
        )
        currentMembers.forEach { if (it != currentUserId) signalRepository.sendSignal(it, updateSignal) }

        newMemberIds.forEach { memberId ->
            messageDao.insertMessage(ChatMessage(
                fromUserId = currentUserId,
                toUserId = "",
                groupId = groupId,
                text = "SYSTEM_MEMBER_JOINED:$memberId",
                timestamp = System.currentTimeMillis(),
                type = MessageType.SYSTEM
            ))
        }
    }

    suspend fun kickMember(groupId: String, userId: String) {
        val currentUserId = userRepository.getCurrentUserId() ?: return
        val members = groupDao.getGroupMemberIds(groupId)
        
        val kickSignal = SignalData(
            callId = "group_ctrl",
            type = SignalType.GROUP_KICK,
            payload = gson.toJson(GroupControlPacket(groupId, userId))
        )
        members.forEach { if (it != currentUserId) signalRepository.sendSignal(it, kickSignal) }

        groupDao.deleteMember(groupId, userId)
        
        messageDao.insertMessage(ChatMessage(
            fromUserId = currentUserId,
            toUserId = "",
            groupId = groupId,
            text = "SYSTEM_MEMBER_KICKED:$userId",
            timestamp = System.currentTimeMillis(),
            type = MessageType.SYSTEM
        ))
    }

    suspend fun leaveGroup(groupId: String, deleteFiles: Boolean = false, deleteForEveryone: Boolean = false) {
        val group = groupDao.getGroupById(groupId) ?: return
        val currentUserId = userRepository.getCurrentUserId() ?: return
        val members = groupDao.getGroupMemberIds(groupId)

        // Если нужно удалить сообщения у всех перед выходом
        if (deleteForEveryone) {
            signalRepository.clearChat(groupId, true, deleteFiles, true)
        }

        if (group.ownerId == currentUserId) {
            val others = members.filter { it != currentUserId }
            if (others.isNotEmpty()) {
                val newOwner = others.first()
                val allMembers = groupDao.getGroupMembersSync(groupId).filter { it.userId != currentUserId }
                val memberPackets = allMembers.map { GroupMemberPacket(it.userId, it.role) }
                val updateSignal = SignalData(
                    callId = "group_ctrl",
                    type = SignalType.GROUP_UPDATE,
                    payload = gson.toJson(GroupUpdatePacket(groupId, memberPackets, newOwner))
                )
                others.forEach { signalRepository.sendSignal(it, updateSignal) }
            }
        } else {
            val leaveSignal = SignalData(
                callId = "group_ctrl",
                type = SignalType.GROUP_LEAVE,
                payload = gson.toJson(GroupControlPacket(groupId, currentUserId))
            )
            members.forEach { if (it != currentUserId) signalRepository.sendSignal(it, leaveSignal) }
        }

        groupDao.deleteGroup(groupId)
        messageDao.deleteAllMessagesForGroup(groupId)
    }

    suspend fun deleteGroup(groupId: String) {
        val currentUserId = userRepository.getCurrentUserId() ?: return
        val members = groupDao.getGroupMemberIds(groupId)
        
        val deleteSignal = SignalData(
            callId = "group_ctrl",
            type = SignalType.GROUP_DELETE,
            payload = gson.toJson(GroupControlPacket(groupId, currentUserId))
        )
        
        members.forEach { memberId ->
            if (memberId != currentUserId) {
                signalRepository.sendSignal(memberId, deleteSignal)
            }
        }

        groupDao.deleteGroup(groupId)
        messageDao.deleteAllMessagesForGroup(groupId)
    }

    suspend fun getGroupMemberIds(groupId: String): List<String> = groupDao.getGroupMemberIds(groupId)
}
