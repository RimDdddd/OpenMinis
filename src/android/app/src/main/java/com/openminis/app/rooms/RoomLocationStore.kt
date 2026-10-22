package com.openminis.app.rooms

import android.content.Context
import com.openminis.app.agent.SoulStore
import org.json.JSONArray
import org.json.JSONObject
import java.io.File

/**
 * [T-rooms] Where each character is standing.
 *
 * A room IS a folder under `mounts/房间加加加/`, but the app does not read that
 * mount: the folder list is handed to it here, in
 * `filesDir/minis-global/rooms.json`, together with who is in which room. One
 * small json, written by whoever moves, read by both sides:
 *
 * - the app (map screen, prompt tail)
 * - the sandbox (mounted read-only at `/var/minis/rooms.json`, so a character
 *   can look at the map from a shell)
 *
 * Position is a STATE, not an event: it feeds the same "Runtime context" block
 * the clock does, at the very end of the system prompt, so a move invalidates
 * prompt caching from the tail only — never the conversation behind it.
 *
 * Keying: `__default__` is the built-in character's sentinel (it has no row in
 * `personas`), a persona id is itself, and [USER_KEY] is the human. Absent key =
 * [DEFAULT_ROOM], which is also what a phone that never opened the map gets, so
 * the feature is invisible until someone uses it.
 *
 * IO contract: [snapshot] caches in memory and re-reads only when the file's
 * mtime changes, because the prompt builder calls it on every single turn and
 * must not touch the disk on the main thread each time. A malformed file is
 * treated as "no positions" rather than crashing the chat.
 */
object RoomLocationStore {

    /** Positions key for the human. Characters use `__default__` / persona ids. */
    const val USER_KEY = "user"

    private const val REL_PATH = "minis-global/rooms.json"
    private const val DEFAULT_ROOM = "米米书房"

    /** What a fresh install shows before the sandbox has ever written the file. */
    private val BUILT_IN_ROOMS = listOf("米米书房", "米米的卧室", "妙妙的房间", "川的卧室", "浴室")

    data class Snapshot(
        val rooms: List<String>,
        val positions: Map<String, String>,
    ) {
        fun roomOf(key: String): String = positions[key]?.takeIf { it.isNotBlank() } ?: DEFAULT_ROOM
    }

    @Volatile
    private var cached: Snapshot? = null

    @Volatile
    private var cachedStamp: Long = Long.MIN_VALUE

    fun file(context: Context): File = File(context.filesDir, REL_PATH)

    /** Position key for a persona id (null = the built-in character). */
    fun keyOf(personaId: String?): String =
        personaId?.takeIf { it.isNotBlank() } ?: SoulStore.DEFAULT_PERSONA_DIR_NAME

    fun snapshot(context: Context): Snapshot {
        val f = file(context)
        val stamp = if (f.exists()) f.lastModified() else 0L
        cached?.let { if (stamp == cachedStamp) return it }
        synchronized(this) {
            cached?.let { if (stamp == cachedStamp) return it }
            val loaded = if (stamp == 0L) {
                Snapshot(BUILT_IN_ROOMS, emptyMap())
            } else {
                runCatching { parse(f.readText()) }
                    .getOrElse { Snapshot(BUILT_IN_ROOMS, emptyMap()) }
            }
            cached = loaded
            cachedStamp = stamp
            return loaded
        }
    }

    /**
     * Move one participant. Creates the file from the cached view when missing,
     * preserves every other participant's position, and drops an unknown room
     * name only if it is not in the list — an unknown name is still honoured
     * (the sandbox may have added a folder the app has not seen yet), it just
     * gets appended to `rooms` so the map can show it.
     */
    fun moveTo(context: Context, key: String, room: String): Snapshot {
        val name = room.trim()
        if (name.isEmpty()) return snapshot(context)
        synchronized(this) {
            val current = snapshot(context)
            val rooms = if (current.rooms.contains(name)) current.rooms else current.rooms + name
            val positions = current.positions.toMutableMap().apply { put(key, name) }
            val next = Snapshot(rooms, positions)
            runCatching { write(context, next) }
            cached = next
            cachedStamp = runCatching { file(context).lastModified() }.getOrDefault(0L)
            return next
        }
    }

    private fun write(context: Context, snapshot: Snapshot) {
        val f = file(context)
        f.parentFile?.mkdirs()
        val positions = JSONObject()
        snapshot.positions.forEach { (k, v) -> positions.put(k, v) }
        val json = JSONObject().apply {
            put("rooms", JSONArray(snapshot.rooms))
            put("positions", positions)
        }
        val tmp = File(f.parentFile, "${f.name}.tmp")
        tmp.writeText(json.toString(2))
        if (!tmp.renameTo(f)) {
            f.writeText(json.toString(2))
            tmp.delete()
        }
    }

    private fun parse(text: String): Snapshot {
        val root = JSONObject(text)
        val rooms = mutableListOf<String>()
        root.optJSONArray("rooms")?.let { arr ->
            for (i in 0 until arr.length()) {
                val v = arr.optString(i, "")
                if (v.isNotBlank()) rooms.add(v)
            }
        }
        val positions = mutableMapOf<String, String>()
        root.optJSONObject("positions")?.let { obj ->
            obj.keys().forEach { k ->
                val v = obj.optString(k, "")
                if (v.isNotBlank()) positions[k] = v
            }
        }
        if (rooms.isEmpty()) rooms.addAll(BUILT_IN_ROOMS)
        return Snapshot(rooms, positions)
    }
}
