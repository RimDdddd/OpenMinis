package com.openminis.app.sandbox

import com.openminis.app.agent.SoulStore
import java.io.File
import java.util.concurrent.ConcurrentHashMap

/**
 * [T-persona-isolation] Which character a sandbox session belongs to.
 *
 * The sandbox mounts `/var/minis/memory` per session, and — before this — it
 * always mounted the DEFAULT character's folder. A second character's shell
 * therefore opened 米米's SOUL.md and daily logs and read them as her own, which
 * is exactly the "she answers to any name" behaviour that kept coming back.
 *
 * The mount table is built from components that only know a session id
 * (`ExecutionCoordinator`, `TerminalSession`), so the binding is published here
 * when the chat screen learns it and read back when a shell is built.
 *
 * Absent means the built-in default character — the same rule the database
 * uses (`sessions.persona_id IS NULL`), so a session that predates personas
 * behaves identically.
 */
object SessionPersonaRegistry {

    private val bySession = ConcurrentHashMap<String, String>()

    fun set(sessionId: String?, personaId: String?) {
        val sid = sessionId?.takeIf { it.isNotBlank() } ?: return
        if (personaId.isNullOrBlank()) {
            bySession.remove(sid)
        } else {
            bySession[sid] = personaId
        }
    }

    /** Persona for [sessionId], or null = the built-in default character. */
    fun get(sessionId: String?): String? = sessionId?.let { bySession[it] }

    /** The character's own directory (SOUL.md / LONGTERM.md / memory/). */
    fun personaRoot(filesDir: File, personaId: String?): File =
        File(
            File(filesDir, "minis-global/personas"),
            personaId?.takeIf { it.isNotBlank() } ?: SoulStore.DEFAULT_PERSONA_DIR_NAME,
        )
}
