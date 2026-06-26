package com.nax.atsupager.data.db

import androidx.room.Database
import androidx.room.RoomDatabase
import androidx.room.TypeConverters
import com.nax.atsupager.data.model.User

@Database(
    entities = [
        ChatMessage::class, User::class, Contact::class, 
        ChessGameEntity::class, BackgammonGameEntity::class, CheckersGameEntity::class,
        GroupEntity::class, GroupMemberEntity::class
    ], 
    version = 13, // Incrementing version for Mute feature
    exportSchema = false
)
@TypeConverters(Converters::class)
abstract class AppDatabase : RoomDatabase() {
    abstract fun messageDao(): MessageDao
    abstract fun userDao(): UserDao
    abstract fun contactDao(): ContactDao
    abstract fun chessDao(): ChessDao
    abstract fun backgammonDao(): BackgammonDao
    abstract fun checkersDao(): CheckersDao
    abstract fun groupDao(): GroupDao
}
