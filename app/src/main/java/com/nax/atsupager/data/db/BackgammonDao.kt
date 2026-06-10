package com.nax.atsupager.data.db

import androidx.room.*
import kotlinx.coroutines.flow.Flow

@Dao
interface BackgammonDao {
    @Query("SELECT * FROM backgammon_games WHERE contactId = :contactId")
    fun getGame(contactId: String): Flow<BackgammonGameEntity?>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun saveGame(game: BackgammonGameEntity)

    @Query("DELETE FROM backgammon_games WHERE contactId = :contactId")
    suspend fun deleteGame(contactId: String)
}
