package com.nax.atsupager.data.db

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.RawQuery
import androidx.room.Transaction
import androidx.sqlite.db.SupportSQLiteQuery
import kotlinx.coroutines.flow.Flow

@Dao
interface MessageDao {

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertMessage(message: ChatMessage): Long

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertMessages(messages: List<ChatMessage>)

    @Query("SELECT * FROM messages WHERE groupId IS NULL AND ((fromUserId = :userId1 AND toUserId = :userId2) OR (fromUserId = :userId2 AND toUserId = :userId1)) ORDER BY timestamp ASC")
    fun getMessagesForChat(userId1: String, userId2: String): Flow<List<ChatMessage>>

    @Query("SELECT * FROM messages WHERE groupId = :groupId ORDER BY timestamp ASC")
    fun getMessagesForGroup(groupId: String): Flow<List<ChatMessage>>

    @Query("SELECT * FROM messages WHERE groupId = :groupId ORDER BY timestamp ASC")
    suspend fun getMessagesForGroupSync(groupId: String): List<ChatMessage>

    @Query("SELECT * FROM messages WHERE groupId IS NULL AND ((fromUserId = :userId1 AND toUserId = :userId2) OR (fromUserId = :userId2 AND toUserId = :userId1)) ORDER BY timestamp ASC")
    suspend fun getMessagesForChatSync(userId1: String, userId2: String): List<ChatMessage>

    @Query("SELECT * FROM messages")
    suspend fun getAllMessagesSync(): List<ChatMessage>

    @Query("SELECT COUNT(id) FROM messages WHERE toUserId = :currentUserId AND isRead = 0 AND groupId IS NULL")
    suspend fun getUnreadMessagesCount(currentUserId: String): Int

    @Query("SELECT COUNT(id) FROM messages WHERE (toUserId = :currentUserId OR (groupId IS NOT NULL AND fromUserId != :currentUserId)) AND isRead = 0 AND (groupId IS NULL OR groupId IN (SELECT groupId FROM groups))")
    fun getTotalUnreadCountFlow(currentUserId: String): Flow<Int>

    @Query("SELECT COUNT(id) FROM messages WHERE toUserId = :currentUserId AND fromUserId = :fromUserId AND isRead = 0 AND groupId IS NULL")
    fun getUnreadCountFromUser(currentUserId: String, fromUserId: String): Flow<Int>
    
    @Query("SELECT COUNT(id) FROM messages WHERE groupId = :groupId AND fromUserId != :currentUserId AND isRead = 0")
    fun getUnreadCountForGroup(groupId: String, currentUserId: String): Flow<Int>

    @Query("SELECT COUNT(id) FROM messages WHERE toUserId = :currentUserId AND fromUserId = :fromUserId AND isRead = 0 AND groupId IS NULL")
    suspend fun getUnreadCountFromUserSync(currentUserId: String, fromUserId: String): Int

    @Query("UPDATE messages SET isRead = 1 WHERE toUserId = :currentUserId AND fromUserId = :contactId AND groupId IS NULL")
    suspend fun markMessagesAsRead(currentUserId: String, contactId: String)

    @Query("UPDATE messages SET isRead = 1 WHERE groupId = :groupId")
    suspend fun markGroupMessagesAsRead(groupId: String)

    @Query("UPDATE messages SET isRead = 1 WHERE id = :messageId")
    suspend fun markMessageAsRead(messageId: Long)

    @Query("UPDATE messages SET localFilePath = :localPath WHERE id = :messageId")
    suspend fun updateLocalFilePath(messageId: Long, localPath: String?)

    @Query("UPDATE messages SET fileUrl = :fileUrl WHERE id = :messageId")
    suspend fun updateMessageUrl(messageId: Long, fileUrl: String)

    @Query("UPDATE messages SET fileEncryptionKey = :key WHERE id = :messageId")
    suspend fun updateFileEncryptionKey(messageId: Long, key: String)

    @Query("DELETE FROM messages WHERE (fromUserId = :userId1 AND toUserId = :userId2) OR (fromUserId = :userId2 AND toUserId = :userId1)")
    suspend fun deleteAllMessagesForChat(userId1: String, userId2: String)

    @Query("DELETE FROM messages WHERE groupId = :groupId")
    suspend fun deleteAllMessagesForGroup(groupId: String)

    @Query("DELETE FROM messages WHERE id = :messageId")
    suspend fun deleteMessageById(messageId: Long)

    @Query("SELECT * FROM messages WHERE remoteId = :remoteId LIMIT 1")
    suspend fun getMessageByRemoteId(remoteId: String): ChatMessage?

    @Query("SELECT * FROM messages WHERE remoteId IN (:remoteIds)")
    suspend fun getMessagesByRemoteIds(remoteIds: List<String>): List<ChatMessage>

    @Query("DELETE FROM messages WHERE remoteId = :remoteId")
    suspend fun deleteByRemoteId(remoteId: String)

    @Query("DELETE FROM messages WHERE remoteId IN (:remoteIds)")
    suspend fun deleteByRemoteIds(remoteIds: List<String>)

    @Query("SELECT * FROM messages WHERE fromUserId = :authorId AND toUserId = :contactId AND groupId IS NULL AND timestamp <= :threshold")
    suspend fun getMessagesByAuthorInChatSync(authorId: String, contactId: String, threshold: Long): List<ChatMessage>

    @Query("SELECT * FROM messages WHERE fromUserId = :authorId AND groupId = :groupId AND timestamp <= :threshold")
    suspend fun getMessagesByAuthorInGroupSync(authorId: String, groupId: String, threshold: Long): List<ChatMessage>

    @Query("DELETE FROM messages WHERE fromUserId = :authorId AND toUserId = :contactId AND groupId IS NULL AND timestamp <= :threshold")
    suspend fun deleteMessagesByAuthorInChat(authorId: String, contactId: String, threshold: Long)

    @Query("DELETE FROM messages WHERE fromUserId = :authorId AND groupId = :groupId AND timestamp <= :threshold")
    suspend fun deleteMessagesByAuthorInGroup(authorId: String, groupId: String, threshold: Long)

    @Query("DELETE FROM messages WHERE groupId = :groupId AND timestamp <= :threshold")
    suspend fun deleteMessagesInGroupBefore(groupId: String, threshold: Long)

    @Query("DELETE FROM messages WHERE ((fromUserId = :userId1 AND toUserId = :userId2) OR (fromUserId = :userId2 AND toUserId = :userId1)) AND timestamp <= :threshold")
    suspend fun deleteMessagesInChatBefore(userId1: String, userId2: String, threshold: Long)

    @Query("UPDATE messages SET isDelivered = 1 WHERE remoteId = :remoteId")
    suspend fun markAsDelivered(remoteId: String)

    @Query("UPDATE messages SET remoteRead = 1 WHERE remoteId = :remoteId")
    suspend fun markAsRemoteRead(remoteId: String)

    @Query("SELECT * FROM messages WHERE fromUserId = :fromUserId AND type = :type ORDER BY timestamp DESC LIMIT 1")
    suspend fun getLastMessageByType(fromUserId: String, type: MessageType): ChatMessage?

    @Query("UPDATE messages SET type = :newType, text = :newText WHERE id = :messageId")
    suspend fun updateMessageTypeAndText(messageId: Long, newType: MessageType, newText: String)

    @Query("" +
            "SELECT * FROM messages " +
            "WHERE id IN (" +
            "  SELECT MAX(id) FROM messages " +
            "  WHERE (groupId IS NULL AND (fromUserId = :currentUserId OR toUserId = :currentUserId)) " +
            "  GROUP BY CASE WHEN fromUserId = :currentUserId THEN toUserId ELSE fromUserId END" +
            "  UNION " +
            "  SELECT MAX(id) FROM messages " +
            "  WHERE groupId IS NOT NULL AND groupId IN (SELECT groupId FROM groups) " +
            "  GROUP BY groupId" +
            ") " +
            "ORDER BY timestamp DESC")
    fun getLastMessagesForAllChats(currentUserId: String): Flow<List<ChatMessage>>

    @Query("SELECT * FROM messages WHERE timestamp < :threshold")
    suspend fun getExpiredMessages(threshold: Long): List<ChatMessage>

    @Query("DELETE FROM messages WHERE timestamp < :threshold")
    suspend fun deleteExpiredMessages(threshold: Long)

    @Query("SELECT COUNT(id) FROM messages WHERE localFilePath = :filePath")
    suspend fun getMessageCountByFilePath(filePath: String): Int

    @Query("DELETE FROM messages")
    suspend fun deleteAllMessages()

    @RawQuery
    suspend fun checkpoint(supportSQLiteQuery: SupportSQLiteQuery): Int
}
