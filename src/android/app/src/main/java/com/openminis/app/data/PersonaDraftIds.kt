package com.openminis.app.data

/**
 * [T-persona-phase1] The `__psn__<id>` segment a DRAFT session id can carry.
 *
 * A new chat has no row yet, so "which character speaks here" has to travel in
 * the id the list hands to the chat screen — the same trick `__grp__` (model
 * group) and `__fld__` (folder) already use. Kept in one place so the encoder
 * and the parser cannot drift, and pure so it can be unit-tested on the JVM
 * (this module has no Robolectric).
 */
object PersonaDraftIds {

    const val MARKER = "__psn__"

    /** Appends the segment. Null ids (the default character) add nothing. */
    fun encode(draftId: String, personaId: String?): String =
        if (personaId.isNullOrEmpty()) draftId else draftId + MARKER + personaId

    /**
     * The persona id carried by [sessionId], or null.
     *
     * Segment-bounded, not just prefix-stripped: everything after the marker
     * up to the next `__` is the id, so a future segment appended after this
     * one cannot silently become part of a persona id. A plain (non-draft) id,
     * or a draft without the segment, yields null = the default character.
     */
    fun fromDraftId(sessionId: String): String? {
        val raw = sessionId.substringAfter(MARKER, "")
        if (raw.isEmpty()) return null
        return raw.substringBefore("__").takeIf { it.isNotEmpty() }
    }
}
