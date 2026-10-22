package com.openminis.app.data

import com.openminis.app.data.db.PersonaDao
import com.openminis.app.data.db.PersonaEntity
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * [T-persona-phase1] A tiny process-wide cache of the `personas` table, for the
 * surfaces that have to render a character NAME next to something else: the
 * session list, the session-row badge, any picker that does not want to become
 * a coroutine owner just to resolve an id.
 *
 * Deliberately not a second source of truth — the table is. It is refreshed:
 *   - once when the session list appears,
 *   - after the Soul screen creates / renames / deletes a character,
 *   - after a backup import restores characters.
 *
 * A stale entry therefore costs a wrong label for at most one screen, and an
 * id with no entry (character deleted, or restored by an older build) simply
 * renders nothing — which is the same fallback the assistant header uses.
 */
object PersonaCache {

    private val _byId = MutableStateFlow<Map<String, PersonaEntity>>(emptyMap())

    /** Read-only map keyed by persona id. Collected by the session list rows. */
    val byId: StateFlow<Map<String, PersonaEntity>> = _byId.asStateFlow()

    suspend fun refresh(dao: PersonaDao) {
        runCatching { set(dao.getAll()) }
    }

    fun set(list: List<PersonaEntity>) {
        _byId.value = list.associateBy { it.id }
    }

    /** Display name for [personaId], or null when there is nothing to show. */
    fun name(personaId: String?): String? =
        personaId?.let { _byId.value[it]?.name }?.takeIf { it.isNotBlank() }
}
