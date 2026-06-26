package com.nax.atsupager.data.di

import android.content.Context
import android.content.SharedPreferences
import androidx.room.Room
import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase
import com.google.gson.Gson
import com.nax.atsupager.data.db.AppDatabase
import com.nax.atsupager.data.db.BackgammonDao
import com.nax.atsupager.data.db.CheckersDao
import com.nax.atsupager.data.db.ChessDao
import com.nax.atsupager.data.db.ContactDao
import com.nax.atsupager.data.db.GroupDao
import com.nax.atsupager.data.db.MessageDao
import com.nax.atsupager.data.db.UserDao
import com.nax.atsupager.data.manager.SessionManager
import com.nax.atsupager.security.KeyStorageManager
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.android.qualifiers.ApplicationContext
import dagger.hilt.components.SingletonComponent
import java.security.MessageDigest
import javax.inject.Named
import javax.inject.Singleton

@InstallIn(SingletonComponent::class)
@Module
object DatabaseModule {

    val MIGRATION_2_3 = object : Migration(2, 3) {
        override fun migrate(database: SupportSQLiteDatabase) {
            database.execSQL("CREATE TABLE IF NOT EXISTS `users` (`id` TEXT NOT NULL, `username` TEXT NOT NULL, `publicKey` TEXT, PRIMARY KEY(`id`))")
            database.execSQL("CREATE TABLE IF NOT EXISTS `contacts` (`userId` TEXT NOT NULL, PRIMARY KEY(`userId`))")
        }
    }

    val MIGRATION_3_4 = object : Migration(3, 4) {
        override fun migrate(database: SupportSQLiteDatabase) {
            database.execSQL("CREATE TABLE IF NOT EXISTS `chess_games` (`contactId` TEXT NOT NULL, `fen` TEXT NOT NULL, `myColor` TEXT NOT NULL, `lastUpdated` INTEGER NOT NULL, `isMyTurn` INTEGER NOT NULL, `history` TEXT NOT NULL DEFAULT '', PRIMARY KEY(`contactId`))")
        }
    }

    val MIGRATION_8_9 = object : Migration(8, 9) {
        override fun migrate(database: SupportSQLiteDatabase) {
            database.execSQL("ALTER TABLE messages ADD COLUMN isDelivered INTEGER NOT NULL DEFAULT 0")
            database.execSQL("ALTER TABLE messages ADD COLUMN remoteRead INTEGER NOT NULL DEFAULT 0")
        }
    }

    @Provides
    @Singleton
    @Named("global_prefs")
    fun provideGlobalSharedPreferences(@ApplicationContext context: Context): SharedPreferences {
        return context.getSharedPreferences("AtsuGlobalPrefs", Context.MODE_PRIVATE)
    }

    @Provides
    @Singleton
    fun provideSharedPreferences(
        @ApplicationContext context: Context,
        @Named("global_prefs") globalPrefs: SharedPreferences
    ): SharedPreferences {
        val currentUserId = globalPrefs.getString(SessionManager.KEY_ACTIVE_PROFILE_ID, SessionManager.NO_PROFILE) ?: SessionManager.NO_PROFILE
        val prefsName = if (currentUserId == SessionManager.NO_PROFILE) "AtsuPagerPrefs" else "AtsuProfilePrefs_$currentUserId"
        return context.getSharedPreferences(prefsName, Context.MODE_PRIVATE)
    }

    @Provides
    @Singleton
    fun provideAppDatabase(
        @ApplicationContext appContext: Context,
        keyStorageManager: KeyStorageManager,
        @Named("global_prefs") globalPrefs: SharedPreferences
    ): AppDatabase {
        val currentUserId = globalPrefs.getString(SessionManager.KEY_ACTIVE_PROFILE_ID, SessionManager.NO_PROFILE) ?: SessionManager.NO_PROFILE
        
        val dbName: String
        val passphrase: ByteArray

        if (currentUserId == SessionManager.NO_PROFILE) {
            dbName = "atsu_empty.db"
            passphrase = keyStorageManager.getDevicePersistentPassphrase()
        } else {
            val hash = MessageDigest.getInstance("SHA-256")
                .digest(currentUserId.toByteArray())
                .joinToString("") { "%02x".format(it) }
                .take(16)
            dbName = "atsu_user_$hash.db"
            passphrase = keyStorageManager.getDatabasePassphrase(currentUserId)
        }

        // Используем полное имя класса для принудительного резолва
        val factory = net.sqlcipher.database.SupportFactory(passphrase)

        return Room.databaseBuilder(appContext, AppDatabase::class.java, dbName)
            .openHelperFactory(factory)
            .addMigrations(MIGRATION_2_3, MIGRATION_3_4, MIGRATION_8_9)
            .fallbackToDestructiveMigration()
            .build()
    }

    @Provides
    fun provideMessageDao(appDatabase: AppDatabase): MessageDao = appDatabase.messageDao()
    @Provides
    fun provideUserDao(appDatabase: AppDatabase): UserDao = appDatabase.userDao()
    @Provides
    fun provideContactDao(appDatabase: AppDatabase): ContactDao = appDatabase.contactDao()
    @Provides
    fun provideChessDao(appDatabase: AppDatabase): ChessDao = appDatabase.chessDao()
    @Provides
    fun provideBackgammonDao(appDatabase: AppDatabase): BackgammonDao = appDatabase.backgammonDao()
    @Provides
    fun provideCheckersDao(appDatabase: AppDatabase): CheckersDao = appDatabase.checkersDao()
    @Provides
    fun provideGroupDao(appDatabase: AppDatabase): GroupDao = appDatabase.groupDao()
    @Provides
    @Singleton
    fun provideGson(): Gson = Gson()
}
