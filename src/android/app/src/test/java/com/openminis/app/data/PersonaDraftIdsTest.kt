package com.openminis.app.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * [T-persona-phase1] Draft-id contract for "new chat as …".
 *
 * The failure this guards is silent rather than loud: a mis-parse does not
 * crash, it just opens the new chat as the WRONG character — and nobody
 * notices until they read a reply in the wrong voice.
 */
class PersonaDraftIdsTest {

    private val uuid = "3f2b1c4d-0000-4000-8000-abcdefabcdef"

    @Test
    fun encode_addsNothingForDefaultCharacter() {
        val id = "__new__$uuid"
        assertEquals(id, PersonaDraftIds.encode(id, null))
        assertEquals(id, PersonaDraftIds.encode(id, ""))
    }

    @Test
    fun encode_appendsAfterRoutingSegments() {
        val base = "__new__$uuid" + "__grp__group-1"
        assertEquals(
            "$base" + "__psn__persona-9",
            PersonaDraftIds.encode(base, "persona-9"),
        )
    }

    @Test
    fun encodeThenParse_roundTrips() {
        val id = PersonaDraftIds.encode("__new__$uuid", "persona-9")
        assertEquals("persona-9", PersonaDraftIds.fromDraftId(id))
    }

    @Test
    fun parse_plainSessionIdIsDefault() {
        assertNull(PersonaDraftIds.fromDraftId("$uuid"))
        assertNull(PersonaDraftIds.fromDraftId("__new__$uuid"))
    }

    @Test
    fun parse_stopsAtNextSegment() {
        val id = "__new__$uuid" + "__psn__persona-9" + "__fld__folder-3"
        assertEquals("persona-9", PersonaDraftIds.fromDraftId(id))
    }

    @Test
    fun parse_emptySegmentIsDefault() {
        assertNull(PersonaDraftIds.fromDraftId("__new__$uuid" + "__psn__"))
    }
}
