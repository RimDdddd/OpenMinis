package com.openminis.app.data.db

import androidx.room.ColumnInfo
import androidx.room.Entity

/**
 * [T-persona-phase2] Who takes part in a GROUP session, in speaking order.
 *
 * A session is a group chat exactly when it has at least one row here; singles
 * keep using `sessions.persona_id` and never touch this table, so phase-one
 * behaviour is untouched for every existing conversation.
 *
 * `persona_id` uses the same sentinel the rest of the persona code uses
 * ([com.openminis.app.agent.SoulStore.DEFAULT_PERSONA_DIR_NAME], "__default__")
 * for the built-in character, because it has no row in `personas` at all. That
 * sentinel in a NOT NULL column keeps ordering and membership uniform — no
 * nullable column, nothing to special-case when sorting.
 *
 * `sort_order` is the fixed speaking order of one turn: ascending, and the code
 * that creates a group always writes the default character at 0 so she opens.
 *
 * Column types/nullability must mirror MIGRATION_15_16 exactly — Room validates
 * the live schema against this entity on open.
 */
@Entity(
    tableName = "session_personas",
    primaryKeys = ["session_id", "persona_id"],
)
data class SessionPersonaEntity(
    @ColumnInfo(name = "session_id") val sessionId: String,
    @ColumnInfo(name = "persona_id") val personaId: String,
    @ColumnInfo(name = "sort_order") val sortOrder: Int = 0,
)
