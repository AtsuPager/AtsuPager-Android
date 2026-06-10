package com.nax.atsupager.data.db

import androidx.room.*
import kotlinx.coroutines.flow.Flow

@Dao
interface ChessDao {
    @Query("SELECT * FROM chess_games WHERE contactId = :contactId")
    fun getGame(contactId: String): Flow<ChessGameEntity?>

    @Query("SELECT * FROM chess_games WHERE contactId = :contactId")
    suspend fun getGameSync(contactId: String): ChessGameEntity?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun saveGame(game: ChessGameEntity)

    @Query("DELETE FROM chess_games WHERE contactId = :contactId")
    suspend fun deleteGame(contactId: String)
}
