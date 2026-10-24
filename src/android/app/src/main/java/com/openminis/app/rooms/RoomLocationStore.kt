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
    /** Where someone with no home room of their own ends up. */
    const val DEFAULT_ROOM = "米米书房"

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
     * Give everyone a starting position the first time the map is opened.
     *
     * Without this the file appears only when the user taps a cell, and until
     * then a fresh install shows an empty grid — every cell vacant while the
     * prompt already tells the characters they are in [DEFAULT_ROOM]. Seeding
     * makes the map and the prompt agree from the first launch, and gives the
     * sandbox-side `cat /var/minis/rooms.json` something to show.
     *
     * Writes once and never again: an existing file is returned untouched, so a
     * user who has moved people around does not get their map reset by opening
     * the screen.
     */
    fun seedIfMissing(context: Context, keys: List<String>): Snapshot {
        synchronized(this) {
            if (file(context).exists()) return snapshot(context)
            val positions = keys.map { it.trim() }.filter { it.isNotEmpty() }.distinct()
                .associateWith { homeRoomFor(it) }
            val next = Snapshot(BUILT_IN_ROOMS, positions)
            runCatching { write(context, next) }
            cached = next
            cachedStamp = runCatching { file(context).lastModified() }.getOrDefault(0L)
            return next
        }
    }

    /**
     * [T-persona-phase2] Drop positions whose owner no longer exists.
     *
     * The file is written by several code paths over months of builds: a
     * session id that was once treated as a persona, a character that was
     * renamed or deleted. Those entries keep naming a room in the prompt for a
     * person who is not in the house — and since an unknown id has no SOUL, it
     * resolves to the DEFAULT name and the prompt claims "Minis is in …".
     *
     * Rooms are left alone: a room is a folder, not a row that can go stale.
     */
    fun pruneUnknown(context: Context, validKeys: Set<String>): Snapshot {
        synchronized(this) {
            val current = snapshot(context)
            val kept = current.positions.filterKeys { key ->
                key == USER_KEY || validKeys.contains(key)
            }
            if (kept.size == current.positions.size) return current
            val next = Snapshot(current.rooms, kept)
            runCatching { write(context, next) }
            cached = next
            cachedStamp = runCatching { file(context).lastModified() }.getOrDefault(0L)
            return next
        }
    }

    /**
     * [T-rooms] Merge rooms discovered on disk into the map.
     *
     * A room IS a folder the user created, so the folder list is the source of
     * truth and this file is only its cache: adding or renaming a folder in the
     * file manager must show up on the map without anyone editing json by hand.
     * Existing order and existing positions are preserved — a room that
     * disappears from disk is NOT dropped, because a character may still be
     * standing in it and silently deleting her position is worse than showing a
     * room whose folder was renamed.
     */
    fun syncRooms(context: Context, discovered: List<String>): Snapshot {
        synchronized(this) {
            val current = snapshot(context)
            val merged = (current.rooms + discovered.map { it.trim() }.filter { it.isNotEmpty() })
                .distinct()
            if (merged == current.rooms) return current
            val next = Snapshot(merged, current.positions)
            runCatching { write(context, next) }
            cached = next
            cachedStamp = runCatching { file(context).lastModified() }.getOrDefault(0L)
            return next
        }
    }

    /**
     * Which room is this character's own?
     *
     * Not a table: the rooms ARE folders the user named, and the names already
     * say whose they are ("米米的卧室", "妙妙的房间", "川的卧室"). So the home room
     * is the first room whose name contains the character's name — the same
     * rule a person would use reading the list. No match (a room nobody owns,
     * or a character whose room was renamed) falls back to [DEFAULT_ROOM].
     */
    private fun homeRoomFor(key: String): String {
        val name = if (key == USER_KEY) "川" else SoulStore.cachedMetadataFor(key).value.name
        if (name.isNotBlank()) {
            BUILT_IN_ROOMS.firstOrNull { it.contains(name) }?.let { return it }
        }
        return DEFAULT_ROOM
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
