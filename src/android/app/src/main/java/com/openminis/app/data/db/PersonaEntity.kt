package com.openminis.app.data.db

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.PrimaryKey

/**
 * [T-persona-phase1] One chat persona (character) the app can speak as.
 *
 * The DEFAULT persona (米米) deliberately has NO row here: `sessions.persona_id
 * = NULL` means "the built-in default", whose SOUL and diary keep living at the
 * historical paths (`minis-global/memory/SOUL.md`, daily logs beside it).
 * This table only ever holds the *additional* personas, so a database that
 * never leaves phase one stays byte-identical to the pre-phase-one one minus an
 * empty table.
 *
 * Column notes, all of which MIGRATION_14_15 must mirror EXACTLY — Room
 * validates the live schema against this entity on every open and aborts with
 * IllegalStateException on any mismatch (type, nullability, or default value):
 *
 * - `icon`: emoji or a data URI. NULL = no custom icon (UI falls back to the
 *   first grapheme of `name`).
 * - `sort_order`: display order in the persona list. NOT NULL, but WITHOUT a
 *   SQL DEFAULT — the column is only ever written by this entity, so a DEFAULT
 *   would just be one more thing that has to match the migration string.
 */
@Entity(tableName = "personas")
data class PersonaEntity(
    @PrimaryKey val id: String,
    val name: String,
    val icon: String? = null,
    @ColumnInfo(name = "created_at") val createdAt: Long, // milliseconds
    @ColumnInfo(name = "sort_order") val sortOrder: Int = 0,
)
