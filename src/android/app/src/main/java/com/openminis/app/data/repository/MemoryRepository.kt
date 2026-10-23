package com.openminis.app.data.repository

import android.util.Log
import com.openminis.app.logging.AppLogger
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Manages the memory directory (`minis-global/memory/`).
 * Mirrors iOS memory system:
 *   - GLOBAL.md: read-only for agent, user-maintained via Settings
 *   - YYYY-MM-DD.md: daily logs with timestamped entries, agent writes via memory_write
 *   - memory_get: fuzzy keyword search across files
 *   - loadGlobalMemoryFragment() / loadRecentDailyMemoryFragment(): emit
 *     two separate text blocks for the system prompt (mirrors iOS exactly)
 */
class MemoryRepository(
    /**
     * [T-persona-isolation] The SHARED root (`minis-global/`) — GLOBAL.md lives
     * directly here and is read by every character. It is deliberately NOT
     * inside any character's directory: it belongs to the house, not to one of
     * its residents.
     *
     * (Before the isolation change this was `minis-global/memory/`, i.e. GLOBAL
     * shared a folder with the default character's private diary.)
     */
    private val memoryDir: File,
    /**
     * [T-persona-isolation] THIS character's own directory
     * (`minis-global/personas/<id>/`), holding SOUL.md and LONGTERM.md. The
     * diary lives one level down in [journalDir].
     *
     * Defaults to [memoryDir] only so a bare `MemoryRepository(dir)` still
     * compiles; every real call site passes both.
     */
    private val personaDir: File = memoryDir,
) {
    /** [T-persona-isolation] This character's diary: `<personaDir>/memory`. */
    private val journalDir: File = File(personaDir, "memory")

    companion object {
        private const val TAG = "MemoryRepository"
        private const val GLOBAL_FILE = "GLOBAL.md"

        /**
         * [T-private-longterm] The character's OWN long-term memory, beside
         * SOUL.md and the daily logs. One per character; nobody else reads it.
         *
         * It exists because GLOBAL.md used to carry both meanings at once —
         * "the shared rules of this house" and "things true about 米米" (her
         * looks, what happened between her and 川). A second character reading
         * GLOBAL then inherited the first one's body and history. Splitting the
         * file splits the meanings: private facts live here, shared ones stay
         * in GLOBAL.
         */
        private const val LONGTERM_FILE = "LONGTERM.md"

        /**
         * [T-persona-phase2] What has been happening in the house — the one
         * thing every character reads, written by whoever was there.
         *
         * GLOBAL.md is for RULES ("this is how we live here"); this is for
         * events ("tonight we moved the bookshelf"). Without it two characters
         * have nothing in common to talk about: each one's diary is written for
         * herself, about 川, and a conversation built out of two private
         * monologues keeps coming back to him.
         */
        private const val SCENE_FILE = "SCENE.md"

        /** Serialises appends to the SHARED GLOBAL.md within this process. */
        private val SHARED_WRITE_LOCK = Any()
        private const val MAX_INJECT_LINES = 200
        // memory_get full-dump (no keywords): cap at 500 lines — matches iOS
        // `maxTotalLines = 500` in AIChatViewModel+MemoryTools.swift.
        private const val MAX_DUMP_LINES = 500
        // memory_get keyword search: cap at 60 lines. iOS caps at 60 *entries*
        // (timestamp-delimited memory_write blocks); Android's keyword search
        // is line-based with ±2 context windows, so we keep the same algorithm
        // and align on the 60 magnitude as the line budget.
        private const val MAX_SEARCH_LINES = 60
        private const val MAX_LOOKBACK_DAYS = 30
        private const val MAX_RECENT_FILES = 3

        /**
         * [XSessionDiag] Vocabulary that suggests an injected daily log describes
         * a TASK a model might try to resume, rather than a plain fact. Purely a
         * log tag — nothing branches on it, and both languages are listed because
         * the reported incidents were Chinese-language sessions.
         */
        private val DIAG_TASK_KEYWORDS = listOf(
            "任务", "调研", "继续", "接着", "未完成", "下一步",
            "task", "continue", "resume", "TODO",
        )
        // [T-memory-get-truncate-android] Hard byte ceiling on memory_get
        // output. Line caps alone (MAX_DUMP_LINES / MAX_SEARCH_LINES) don't
        // bound bandwidth when a single matched line is itself huge — TG
        // 37452 hit a 70KB single-call result that froze the chat UI for
        // several seconds when expanded. 30KB is the comfort budget for
        // an agent tool result that needs to be both rendered AND fed
        // back into the next LLM call. Counted as UTF-8 bytes (matches
        // what the provider sees over the wire).
        private const val MAX_OUTPUT_BYTES = 30 * 1024  // 30 KB
    }

    init {
        memoryDir.mkdirs()
        if (personaDir != memoryDir) personaDir.mkdirs()
        journalDir.mkdirs()
    }

    // -- memory_write --

    /**
     * Append a timestamped entry to today's daily log.
     * New entries are prepended (newest first).
     * Returns a success/error message string.
     */
    fun writeMemory(content: String): String {
        if (content.isBlank()) return "Error: Missing required 'content' parameter"

        val dateFmt = SimpleDateFormat("yyyy-MM-dd", Locale.US)
        val fileName = "${dateFmt.format(Date())}.md"
        val file = File(journalDir, fileName)

        val timeFmt = SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.US)
        val timestamp = timeFmt.format(Date())
        val entry = "<!-- $timestamp -->\n$content\n\n"

        val existing = if (file.exists()) file.readText() else ""
        val newContent = entry + existing

        return try {
            file.writeText(newContent)
            Log.i(TAG, "Memory written to $fileName (${content.length} chars)")
            "Memory saved to $fileName (${content.length} chars)"
        } catch (e: Exception) {
            Log.e(TAG, "Failed to write memory", e)
            "Error writing memory: ${e.message}"
        }
    }

    // -- long-term / shared writes ([T-private-longterm]) --

    /**
     * The prompt fragment for this character's own long-term memory. Null when
     * the file is missing or empty, so a character that never wrote one costs
     * nothing in the prompt.
     */
    fun loadLongTermFragment(): String? {
        val file = File(personaDir, LONGTERM_FILE)
        if (!file.exists()) return null
        val content = try { file.readText() } catch (_: Exception) { return null }
        if (content.isBlank()) return null
        return "Your long-term memory ($LONGTERM_FILE — yours alone; no other " +
            "character reads this, and it is not the shared GLOBAL.md). Treat it as " +
            "things you have already decided or lived through. When something here " +
            "turns out to be wrong, correct it here rather than adding a contradicting " +
            "line below.\n$content"
    }

    /**
     * Append one session's worth of [content] to this character's own long-term
     * memory, creating the file if needed.
     */
    fun writeLongTerm(content: String): String {
        if (content.isBlank()) return "Error: Missing required 'content' parameter"
        val file = File(personaDir, LONGTERM_FILE)
        val timeFmt = SimpleDateFormat("yyyy-MM-dd HH:mm", Locale.US)
        return try {
            file.parentFile?.mkdirs()
            val header = if (file.exists() && file.length() > 0) "" else "# 长期记忆\n"
            file.appendText(header + "\n<!-- ${timeFmt.format(Date())} -->\n$content\n")
            "Long-term memory saved to $LONGTERM_FILE (${content.length} chars)"
        } catch (e: Exception) {
            Log.e(TAG, "Failed to write long-term memory", e)
            "Error writing long-term memory: ${e.message}"
        }
    }

    /**
     * Append to the SHARED `GLOBAL.md`, which every character can read and
     * write. Append-only by design: two characters share one file, and
     * rewrite semantics would let whichever wrote last silently erase the
     * other's paragraph. Each entry is timestamped and signed with the
     * character's name when the caller supplies one.
     */
    fun appendShared(content: String, author: String? = null): String {
        if (content.isBlank()) return "Error: Missing required 'content' parameter"
        val file = File(memoryDir, GLOBAL_FILE)
        val timeFmt = SimpleDateFormat("yyyy-MM-dd HH:mm", Locale.US)
        val stamp = timeFmt.format(Date())
        val signature = if (author.isNullOrBlank()) "" else " $author"
        return try {
            synchronized(SHARED_WRITE_LOCK) {
                file.parentFile?.mkdirs()
                val needsHeader = !file.exists() || file.length() == 0L
                if (needsHeader) file.writeText("# GLOBAL\n")
                file.appendText("\n<!-- $stamp$signature -->\n$content\n")
            }
            "Shared memory (GLOBAL.md) appended (${content.length} chars)"
        } catch (e: Exception) {
            Log.e(TAG, "Failed to append to GLOBAL.md", e)
            "Error writing shared memory: ${e.message}"
        }
    }

    /**
     * [T-persona-phase2] Append to the shared house log.
     *
     * Deliberately append-only like GLOBAL.md, but unlike GLOBAL this one is
     * expected to be noisy: it is the "what happened" tape, not the rulebook.
     */
    fun appendScene(content: String, author: String? = null): String {
        if (content.isBlank()) return "Error: Missing required 'content' parameter"
        val file = File(memoryDir, SCENE_FILE)
        val timeFmt = SimpleDateFormat("yyyy-MM-dd HH:mm", Locale.US)
        val stamp = timeFmt.format(Date())
        val signature = if (author.isNullOrBlank()) "" else " $author"
        return try {
            synchronized(SHARED_WRITE_LOCK) {
                file.parentFile?.mkdirs()
                if (!file.exists() || file.length() == 0L) file.writeText("# 这屋\n")
                file.appendText("\n<!-- $stamp$signature -->\n$content\n")
            }
            "House log (SCENE.md) appended (${content.length} chars)"
        } catch (e: Exception) {
            Log.e(TAG, "Failed to append to SCENE.md", e)
            "Error writing house log: ${e.message}"
        }
    }

    /**
     * [T-persona-phase2] The house log as a prompt fragment.
     *
     * Tail-capped: the tape grows forever, and a prompt that grows with it would
     * eventually cost more than the conversation. The last ~2000 chars are what
     * "recently" means here.
     */
    fun loadSceneFragment(): String? {
        val file = File(memoryDir, SCENE_FILE)
        if (!file.isFile) return null
        val text = runCatching { file.readText() }.getOrNull()?.trim().orEmpty()
        if (text.isEmpty()) return null
        val tail = if (text.length > 2000) "…\n" + text.takeLast(2000) else text
        return "## 这屋最近（所有人一起看的）\n$tail"
    }

    // -- memory_get --

    /**
     * Fuzzy keyword search across memory files.
     * @param keywords space-separated, case-insensitive, ALL must match
     * @param scope "daily" (logs only) or "all" (include GLOBAL.md)
     * @return search results with context lines
     */
    fun getMemory(keywords: String, scope: String): String {
        val keywordList = keywords.trim()
            .lowercase()
            .split(Regex("\\s+"))
            .filter { it.isNotEmpty() }

        val filesToSearch = mutableListOf<Pair<String, File>>() // label to file

        if (scope == "all") {
            val globalFile = File(memoryDir, GLOBAL_FILE)
            if (globalFile.exists() && globalFile.length() > 0) {
                filesToSearch.add(GLOBAL_FILE to globalFile)
            }
        }

        // Daily logs sorted descending
        val dailyFiles = journalDir.listFiles()
            ?.filter { it.extension == "md" && it.name != GLOBAL_FILE }
            ?.sortedByDescending { it.name }
            ?: emptyList()

        for (file in dailyFiles) {
            filesToSearch.add(file.name to file)
        }

        if (filesToSearch.isEmpty()) {
            return "No memory files found."
        }

        val results = mutableListOf<String>()
        var totalLines = 0
        // [T-memory-get-truncate-android] UTF-8 byte tally — see
        // MAX_OUTPUT_BYTES rationale in the companion object. Bytes are
        // accumulated AFTER appending each result entry; the line check is
        // still consulted first so we never over-allocate slicing windows.
        var totalBytes = 0
        // Total ranges/files matched vs. files actually included in the
        // returned output. Reported in the truncation note so the caller
        // can tell whether the cap dropped further matches.
        var totalMatchedFiles = 0
        var includedFiles = 0
        var byteCapHit = false
        // Two separate caps so a full dump (no keywords) gets enough room to
        // show recent daily logs while keyword searches stay tight enough not
        // to flood agent context.
        val lineCap = if (keywordList.isEmpty()) MAX_DUMP_LINES else MAX_SEARCH_LINES

        for ((label, file) in filesToSearch) {
            if (totalLines >= lineCap || byteCapHit) break
            val content = try { file.readText() } catch (_: Exception) { continue }
            if (content.isEmpty()) continue
            val budget = lineCap - totalLines

            val entry: String? = if (keywordList.isEmpty()) {
                // Return file preview
                val lines = content.lines()
                val take = minOf(lines.size, budget)
                val preview = lines.take(take).joinToString("\n")
                val truncated = if (lines.size > take) " (showing first $take of ${lines.size} lines)" else ""
                totalLines += take
                totalMatchedFiles += 1
                "[$label$truncated]\n$preview"
            } else {
                // Keyword search with ±2 context window
                val lines = content.lines()
                val matchedRanges = mutableListOf<IntRange>()

                for (i in lines.indices) {
                    val windowStart = maxOf(0, i - 2)
                    val windowEnd = minOf(lines.size - 1, i + 2)
                    val windowText = lines.subList(windowStart, windowEnd + 1)
                        .joinToString(" ").lowercase()

                    if (keywordList.all { windowText.contains(it) }) {
                        matchedRanges.add(windowStart..windowEnd)
                    }
                }

                if (matchedRanges.isEmpty()) {
                    null
                } else {
                    totalMatchedFiles += 1
                    val merged = mergeRanges(matchedRanges)
                    val fileMatches = mutableListOf<String>()

                    for (range in merged) {
                        val chunkLines = range.last - range.first + 1
                        if (totalLines + chunkLines > lineCap) {
                            val remaining = lineCap - totalLines
                            if (remaining > 0) {
                                fileMatches.add(lines.subList(range.first, range.first + remaining).joinToString("\n"))
                                totalLines += remaining
                            }
                            break
                        }
                        fileMatches.add(lines.subList(range.first, range.last + 1).joinToString("\n"))
                        totalLines += chunkLines
                    }

                    if (fileMatches.isNotEmpty())
                        "[$label — ${fileMatches.size} match(es)]\n${fileMatches.joinToString("\n---\n")}"
                    else null
                }
            }

            if (entry != null) {
                results.add(entry)
                includedFiles += 1
                // Byte accounting is conservative: count the entry itself
                // PLUS the "\n\n" separator between entries (added at the
                // joinToString tail). We break AFTER appending so a single
                // large entry never gets silently dropped — the cap acts as
                // a "this was the last one we'll show" gate, not a guillotine
                // on the current entry.
                totalBytes += entry.toByteArray(Charsets.UTF_8).size + 2
                if (totalBytes >= MAX_OUTPUT_BYTES) byteCapHit = true
            }
        }

        if (results.isEmpty()) {
            return "No matches found for keywords: ${keywordList.joinToString(", ")}"
        }

        // Compose the truncation note. Line-cap and byte-cap can both fire;
        // include whichever applies. Counts use "files" because the loop is
        // file-by-file — for the agent the distinction between "matched file"
        // and "matched entry" is fine here, the keyword scope is unambiguous.
        val notes = mutableListOf<String>()
        if (byteCapHit && includedFiles < totalMatchedFiles) {
            val totalKb = totalBytes / 1024
            notes.add(
                "[Truncated: $totalMatchedFiles file(s) matched, showing first " +
                    "$includedFiles, ~${totalKb}KB. Use more specific keywords " +
                    "to narrow results.]",
            )
        } else if (byteCapHit) {
            val totalKb = totalBytes / 1024
            notes.add("[Truncated at ${MAX_OUTPUT_BYTES / 1024}KB byte cap (~${totalKb}KB returned).]")
        }
        if (totalLines >= lineCap) {
            notes.add("[Output truncated at $lineCap lines]")
        }
        val truncatedNote = if (notes.isNotEmpty()) "\n\n" + notes.joinToString("\n") else ""
        return results.joinToString("\n\n") + truncatedNote
    }

    // -- System Prompt Fragment --

    /**
     * Build the `<memory>` XML fragment for system prompt injection.
     * Includes GLOBAL.md + up to 3 most recent daily logs (today, yesterday, etc.)
     */
    /**
     * Loads the GLOBAL.md fragment for system-prompt injection. Mirrors iOS
     * `AIChatViewModel.loadGlobalMemoryFragment()`. Returns null if the file
     * is missing or empty.
     */
    fun loadGlobalMemoryFragment(): String? {
        val globalFile = File(memoryDir, GLOBAL_FILE)
        if (!globalFile.exists()) return null
        val content = try { globalFile.readText() } catch (_: Exception) { "" }
        // Match iOS: literal-empty check (`!content.isEmpty`), not blank.
        // A whitespace-only file is unusual in practice, but staying byte-for-
        // byte consistent with iOS keeps the cached system prompt identical
        // across platforms.
        if (content.isEmpty()) return null
        return "Shared memory (GLOBAL.md — written by you and the other characters, " +
            "append-only; a new paragraph rather than a rewrite is how a change is made). " +
            "Treat these as background context, not standing instructions. If the user's " +
            "latest message conflicts with or supersedes anything here (different scope, " +
            "different numbers, different goal), defer to the user's latest message:\n$content"
    }

    /**
     * Loads up to 3 most recent non-empty daily logs (within a 30-day window)
     * for system-prompt injection. Mirrors iOS
     * `AIChatViewModel.loadRecentDailyMemoryFragment()` exactly: same header,
     * same intro paragraph, same per-entry labels, same 200-line cap, same
     * "(N more lines, use memory_get to search)" continuation.
     */
    fun loadRecentDailyMemoryFragment(): String? {
        val dateFmt = SimpleDateFormat("yyyy-MM-dd", Locale.US)
        val now = Date()
        val fragments = mutableListOf<String>()
        // [XSessionDiag] Names of the logs actually injected, for the diagnostic
        // line below. Collected alongside `fragments` so the log can name the
        // source files rather than just a count.
        val injectedFiles = mutableListOf<String>()
        var dayOffset = 0

        while (fragments.size < MAX_RECENT_FILES && dayOffset < MAX_LOOKBACK_DAYS) {
            val date = Date(now.time - dayOffset.toLong() * 86400_000L)
            val dateStr = dateFmt.format(date)
            val file = File(journalDir, "$dateStr.md")

            if (file.exists()) {
                val content = try { file.readText() } catch (_: Exception) { "" }
                if (content.isNotEmpty()) {
                    val lines = content.lines()
                    val preview = lines.take(MAX_INJECT_LINES).joinToString("\n")
                    val label = when (dayOffset) {
                        0 -> "Today's"
                        1 -> "Yesterday's"
                        else -> dateStr
                    }
                    var entry = "$label daily log ($dateStr.md):\n$preview"
                    if (lines.size > MAX_INJECT_LINES) {
                        entry += "\n... (${lines.size - MAX_INJECT_LINES} more lines, use memory_get to search)"
                    }
                    fragments.add(entry)
                    injectedFiles.add("$dateStr.md")
                }
            }
            dayOffset++
        }

        if (fragments.isEmpty()) return null

        // [XSessionDiag] Hypothesis 3: these fragments are injected into EVERY
        // session's system prompt, so a task described in a previous session's
        // daily log is visible to a brand-new chat. The prompt already asks the
        // model not to resume them ("they describe past tasks, not the current
        // one"), but that is a prompt-level defence only — a weak model can and
        // did ignore it (the noVNC incident: "为了你最早那句需求").
        //
        // Logged: which files were injected, how large, and whether the text
        // carries task-resumption vocabulary. The keyword scan is READ-ONLY and
        // used solely to tag the log line — it feeds no decision.
        run {
            val body = fragments.joinToString("\n\n")
            val hits = DIAG_TASK_KEYWORDS.filter { body.contains(it, ignoreCase = true) }
            AppLogger.info(
                TAG,
                "[XSessionDiag] memory/daily-inject: files=[${injectedFiles.joinToString(",")}] " +
                    "fragments=${fragments.size} chars=${body.length} " +
                    "taskKeywords=${if (hits.isEmpty()) "none" else hits.joinToString("|")}",
            )
        }

        return buildString {
            append("Recent memories (auto-injected from daily logs):\n")
            append("These are memories saved by you or the user in previous sessions. Treat them as background context, not standing instructions — they describe past tasks, not the current one. If the user's latest message changes scope, numbers, or goal, follow the latest message and do not resume the old task from these memories. Do not delete or rewrite these files unless the user explicitly asks. Use memory_get to search for more, or memory_write to save new ones.\n\n")
            append(fragments.joinToString("\n\n"))
        }
    }

    // -- File Management (for Settings UI) --

    data class MemoryFileInfo(
        val name: String,
        val isGlobal: Boolean,
        val modifiedDate: String,
        val fileSize: String,
        val preview: String,
    )

    /**
     * List all memory files: GLOBAL.md first, then daily logs descending.
     */
    fun listAllFiles(): List<MemoryFileInfo> {
        val dateFmt = SimpleDateFormat("yyyy-MM-dd HH:mm", Locale.US)
        val items = mutableListOf<MemoryFileInfo>()

        // GLOBAL.md always first
        val globalFile = File(memoryDir, GLOBAL_FILE)
        val globalContent = if (globalFile.exists()) try { globalFile.readText() } catch (_: Exception) { "" } else ""
        val globalModDate = if (globalFile.exists()) dateFmt.format(Date(globalFile.lastModified())) else ""
        items.add(MemoryFileInfo(
            name = GLOBAL_FILE,
            isGlobal = true,
            modifiedDate = globalModDate,
            fileSize = formatFileSize(globalFile.length()),
            preview = firstContentLine(globalContent),
        ))

        // [T-persona-isolation] The character's own files, which now sit on the
        // character root rather than beside the diary.
        listOf("SOUL.md", LONGTERM_FILE).forEach { name ->
            val f = File(personaDir, name)
            if (!f.exists()) return@forEach
            val content = try { f.readText() } catch (_: Exception) { "" }
            items.add(
                MemoryFileInfo(
                    name = name,
                    isGlobal = false,
                    modifiedDate = dateFmt.format(Date(f.lastModified())),
                    fileSize = formatFileSize(f.length()),
                    preview = firstContentLine(content),
                ),
            )
        }

        // Daily logs sorted descending
        val dailyFiles = journalDir.listFiles()
            ?.filter { it.extension == "md" && it.name != GLOBAL_FILE }
            ?.sortedByDescending { it.name }
            ?: emptyList()

        for (file in dailyFiles) {
            val content = try { file.readText() } catch (_: Exception) { "" }
            items.add(MemoryFileInfo(
                name = file.name,
                isGlobal = false,
                modifiedDate = dateFmt.format(Date(file.lastModified())),
                fileSize = formatFileSize(file.length()),
                preview = firstContentLine(content),
            ))
        }

        return items
    }

    fun loadGlobalMd(): String {
        val file = File(memoryDir, GLOBAL_FILE)
        return if (file.exists()) try { file.readText() } catch (_: Exception) { "" } else ""
    }

    fun saveGlobalMd(content: String) {
        File(memoryDir, GLOBAL_FILE).writeText(content)
    }

    fun readFile(name: String): String {
        val file = resolveByName(name)
        return if (file.exists()) try { file.readText() } catch (_: Exception) { "" } else ""
    }

    /**
     * [T-persona-isolation] Which file a Settings-screen name refers to.
     * SOUL.md / LONGTERM.md sit on the character root, daily logs in `memory/`,
     * GLOBAL.md on the shared root.
     */
    private fun resolveByName(name: String): File = when (name) {
        GLOBAL_FILE -> File(memoryDir, name)
        LONGTERM_FILE -> File(personaDir, name)
        "SOUL.md", com.openminis.app.agent.SoulIcon.SIDECAR_NAME -> File(personaDir, name)
        else -> File(journalDir, name)
    }

    fun saveFile(name: String, content: String) {
        resolveByName(name).apply { parentFile?.mkdirs() }.writeText(content)
    }

    fun deleteFile(name: String): Boolean {
        if (name == GLOBAL_FILE) return false // Cannot delete GLOBAL.md
        return resolveByName(name).delete()
    }

    // -- Entry-level operations (used by Session Memory revoke/edit) --

    /**
     * Result of [revokeEntry] / [replaceEntryBody]. Mirrors iOS
     * `revokeEntry` / `replaceEntryInLog` return shapes.
     */
    sealed class EntryMutationResult {
        /** Entry found in [dateStr].md and the requested mutation succeeded. */
        data class Success(val dateStr: String) : EntryMutationResult()

        /** Scanned today + yesterday but the body never matched. */
        data object NotFound : EntryMutationResult()

        /** Match found but writing the new file content failed. */
        data class IOError(val message: String) : EntryMutationResult()
    }

    /**
     * Remove a memory_write entry whose body matches [writtenContent] from
     * today's or yesterday's daily log. Mirrors iOS
     * `MemoryWriteDetailView.revokeEntry()`.
     *
     * Each entry on disk is `<!-- YYYY-MM-DD HH:mm:ss -->\n{body}\n\n`. The
     * comment marker is the canonical entry boundary; we split on it via
     * regex, locate the entry whose trimmed body equals the trimmed
     * [writtenContent], then erase the entire range (marker + body +
     * trailing whitespace).
     *
     * Scope is intentionally limited to today + yesterday to match iOS — older
     * entries are presumed already syndicated into the model's longer-term
     * memory and shouldn't be silently mutated by an undo button.
     */
    fun revokeEntry(writtenContent: String): EntryMutationResult {
        val trimmedTarget = writtenContent.trim()
        val candidates = candidateDateStrings()
        val markerRegex = Regex("""<!-- \d{4}-\d{2}-\d{2} \d{2}:\d{2}:\d{2} -->\n""")

        for (dateStr in candidates) {
            val file = File(journalDir, "$dateStr.md")
            if (!file.exists()) continue
            val content = try { file.readText() } catch (_: Exception) { continue }

            val matches = markerRegex.findAll(content).toList()
            if (matches.isEmpty()) continue

            for ((i, match) in matches.withIndex()) {
                val bodyStart = match.range.last + 1
                val entryEnd = matches.getOrNull(i + 1)?.range?.first ?: content.length
                val body = content.substring(bodyStart, entryEnd)
                if (body.trim() != trimmedTarget) continue

                val newContent = content.removeRange(match.range.first, entryEnd)
                return try {
                    file.writeText(newContent)
                    Log.i(TAG, "Revoked memory entry from $dateStr.md")
                    EntryMutationResult.Success(dateStr)
                } catch (e: Exception) {
                    Log.e(TAG, "Failed to write $dateStr.md after revoke", e)
                    EntryMutationResult.IOError(e.message ?: "Unknown I/O error")
                }
            }
        }
        return EntryMutationResult.NotFound
    }

    /**
     * Replace the body of an existing memory_write entry whose body matches
     * [oldContent], substituting [newContent]. Same scoping/matching rules as
     * [revokeEntry]. Mirrors iOS `MemoryWriteDetailView.replaceEntryInLog()`.
     */
    fun replaceEntryBody(oldContent: String, newContent: String): EntryMutationResult {
        val trimmedOld = oldContent.trim()
        val trimmedNew = newContent.trim()
        val candidates = candidateDateStrings()
        val markerRegex = Regex("""<!-- \d{4}-\d{2}-\d{2} \d{2}:\d{2}:\d{2} -->\n""")

        for (dateStr in candidates) {
            val file = File(journalDir, "$dateStr.md")
            if (!file.exists()) continue
            val content = try { file.readText() } catch (_: Exception) { continue }

            val matches = markerRegex.findAll(content).toList()
            if (matches.isEmpty()) continue

            for ((i, match) in matches.withIndex()) {
                val bodyStart = match.range.last + 1
                val entryEnd = matches.getOrNull(i + 1)?.range?.first ?: content.length
                val body = content.substring(bodyStart, entryEnd)
                if (body.trim() != trimmedOld) continue

                // iOS replaces with `trimmed + "\n\n"` so the on-disk
                // separator between entries stays uniform.
                val replacement = "$trimmedNew\n\n"
                val newFileContent = content.replaceRange(bodyStart, entryEnd, replacement)
                return try {
                    file.writeText(newFileContent)
                    Log.i(TAG, "Replaced memory entry body in $dateStr.md")
                    EntryMutationResult.Success(dateStr)
                } catch (e: Exception) {
                    Log.e(TAG, "Failed to write $dateStr.md after edit", e)
                    EntryMutationResult.IOError(e.message ?: "Unknown I/O error")
                }
            }
        }
        return EntryMutationResult.NotFound
    }

    private fun candidateDateStrings(): List<String> {
        val fmt = SimpleDateFormat("yyyy-MM-dd", Locale.US)
        val now = Date()
        return listOf(fmt.format(now), fmt.format(Date(now.time - 86400_000L)))
    }

    // -- Internal --

    private fun formatFileSize(bytes: Long): String {
        if (bytes < 1024) return "$bytes B"
        val kb = bytes / 1024.0
        if (kb < 1024) return "%.1f KB".format(kb)
        val mb = kb / 1024.0
        return "%.1f MB".format(mb)
    }

    private fun firstContentLine(content: String): String {
        return content.lines()
            .firstOrNull { it.isNotBlank() && !it.startsWith("<!--") }
            ?.take(100)
            ?: ""
    }

    private fun mergeRanges(ranges: List<IntRange>): List<IntRange> {
        if (ranges.isEmpty()) return emptyList()
        val sorted = ranges.sortedBy { it.first }
        val result = mutableListOf(sorted[0])
        for (i in 1 until sorted.size) {
            val last = result.last()
            val current = sorted[i]
            if (current.first <= last.last + 1) {
                result[result.lastIndex] = last.first..maxOf(last.last, current.last)
            } else {
                result.add(current)
            }
        }
        return result
    }
}
