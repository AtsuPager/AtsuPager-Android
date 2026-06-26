package com.nax.atsupager.data.db

import androidx.room.*
import kotlinx.coroutines.flow.Flow

@Dao
interface GroupDao {
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertGroup(group: GroupEntity)

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertGroups(groups: List<GroupEntity>)

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertMembers(members: List<GroupMemberEntity>)

    @Query("SELECT * FROM groups WHERE groupId = :groupId")
    suspend fun getGroupById(groupId: String): GroupEntity?

    @Query("SELECT * FROM groups")
    fun getAllGroupsFlow(): Flow<List<GroupEntity>>

    @Query("SELECT * FROM groups")
    suspend fun getAllGroupsSync(): List<GroupEntity>

    @Query("SELECT * FROM group_members")
    suspend fun getAllMembersSync(): List<GroupMemberEntity>

    @Query("SELECT * FROM group_members")
    fun getAllMembersFlow(): Flow<List<GroupMemberEntity>>

    @Query("SELECT * FROM group_members WHERE groupId = :groupId")
    suspend fun getGroupMembersSync(groupId: String): List<GroupMemberEntity>

    @Query("SELECT userId FROM group_members WHERE groupId = :groupId")
    suspend fun getGroupMemberIds(groupId: String): List<String>

    @Query("SELECT userId FROM group_members WHERE groupId = :groupId")
    fun getGroupMemberIdsFlow(groupId: String): Flow<List<String>>

    @Query("SELECT role FROM group_members WHERE groupId = :groupId AND userId = :userId")
    suspend fun getUserRole(groupId: String, userId: String): String?

    @Query("DELETE FROM group_members WHERE groupId = :groupId AND userId = :userId")
    suspend fun deleteMember(groupId: String, userId: String)

    @Query("DELETE FROM group_members WHERE groupId = :groupId")
    suspend fun deleteGroupMembers(groupId: String)

    @Transaction
    suspend fun updateGroupMembers(groupId: String, members: List<GroupMemberEntity>) {
        deleteGroupMembers(groupId)
        insertMembers(members)
    }

    @Query("UPDATE groups SET name = :newName WHERE groupId = :groupId")
    suspend fun renameGroup(groupId: String, newName: String)

    @Query("UPDATE groups SET ownerId = :newOwnerId WHERE groupId = :groupId")
    suspend fun updateGroupOwner(groupId: String, newOwnerId: String)

    @Query("UPDATE groups SET isMuted = :muted WHERE groupId = :groupId")
    suspend fun setMuted(groupId: String, muted: Boolean)

    @Query("DELETE FROM groups WHERE groupId = :groupId")
    suspend fun deleteGroup(groupId: String)

    @Query("DELETE FROM groups")
    suspend fun deleteAllGroups()

    @Query("DELETE FROM group_members")
    suspend fun deleteAllMembers()
}
