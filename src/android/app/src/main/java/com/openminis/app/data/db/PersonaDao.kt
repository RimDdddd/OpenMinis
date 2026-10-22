package com.openminis.app.data.db

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import kotlinx.coroutines.flow.Flow

/**
 * [T-persona-phase1] Queries for the `personas` table.
 *
 * The built-in default persona has no row here (`sessions.persona_id = NULL`),
 * so every caller has to treat "no row for this id" and "no id at all" the same
 * way: fall back to the default character. See `SoulStore.personaPath`.
 */
@Dao
interface PersonaDao {
    @Query("SELECT * FROM personas ORDER BY sort_order ASC, created_at ASC")
    suspend fun getAll(): List<PersonaEntity>

    /** Live list for the Settings → Soul screen. */
    @Query("SELECT * FROM personas ORDER BY sort_order ASC, created_at ASC")
    fun observeAll(): Flow<List<PersonaEntity>>

    @Query("SELECT * FROM personas WHERE id = :id LIMIT 1")
    suspend fun getById(id: String): PersonaEntity?

    @Query("SELECT COUNT(*) FROM personas")
    suspend fun count(): Int

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsert(entity: PersonaEntity)

    @Query("DELETE FROM personas WHERE id = :id")
    suspend fun deleteById(id: String)
}
