package com.openminis.app.data.db

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import kotlinx.coroutines.flow.Flow

/**
 * [T-persona-phase2] Membership + speaking order for group sessions.
 *
 * Everything here is scoped by session id. An empty result means "not a group
 * chat" — callers must fall back to `sessions.persona_id` (single chat) rather
 * than inventing a default participant list, otherwise a plain session would
 * silently start answering as two characters.
 */
@Dao
interface SessionPersonaDao {
    @Query("SELECT * FROM session_personas WHERE session_id = :sessionId ORDER BY sort_order ASC")
    suspend fun getForSession(sessionId: String): List<SessionPersonaEntity>

    /** One-shot read for callers that only need "is this a group, and who". */
    @Query("SELECT * FROM session_personas WHERE session_id = :sessionId ORDER BY sort_order ASC")
    fun observeForSession(sessionId: String): Flow<List<SessionPersonaEntity>>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertAll(rows: List<SessionPersonaEntity>)

    @Query("DELETE FROM session_personas WHERE session_id = :sessionId")
    suspend fun deleteForSession(sessionId: String)

    @Query("SELECT COUNT(*) FROM session_personas WHERE session_id = :sessionId")
    suspend fun countForSession(sessionId: String): Int
}
