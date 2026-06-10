package com.nax.atsupager.data.db

import androidx.room.*
import kotlinx.coroutines.flow.Flow

@Dao
interface CheckersDao {
    @Query("SELECT * FROM checkers_games WHERE contactId = :contactId")
    fun getGame(contactId: String): Flow<CheckersGameEntity?>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun saveGame(game: CheckersGameEntity)

    @Query("DELETE FROM checkers_games WHERE contactId = :contactId")
    suspend fun deleteGame(contactId: String)
}
