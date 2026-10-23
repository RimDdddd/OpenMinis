package com.openminis.app.tools

import com.openminis.app.data.repository.MemoryRepository
import org.json.JSONArray
import org.json.JSONObject

/**
 * Tool definitions and execution for memory_write and memory_get.
 * Schema matches iOS AgentToolDefinition exactly.
 */
object MemoryTools {

    // -- Tool Definitions (Anthropic format) --

    fun memoryWriteToolDefinition(): JSONObject {
        val properties = JSONObject().apply {
            put("tool_title", JSONObject().apply {
                put("type", "string")
                put("description", "A concise 5-10 word summary of what this tool call does, shown to the user (e.g. 'Save user preference for Python', 'Note today's project context'). Use the same language as the user.")
            })
            put("scope", JSONObject().apply {
                put("type", "string")
                put("enum", JSONArray().apply { put("daily"); put("longterm"); put("shared"); put("scene") })
                put("description", "Where to write: daily (default), longterm (your own LONGTERM.md), shared (append to GLOBAL.md), scene (append to the house log SCENE.md — events, not rules).")
            })
            put("content", JSONObject().apply {
                put("type", "string")
                put("description", "The memory content to write. Use concise Markdown with a short heading (## Topic) and context about what was done/learned.")
            })
        }

        return JSONObject().apply {
            put("name", "memory_write")
            put("description", "Write a memory entry. scope='daily' (default) appends to today's daily log (YYYY-MM-DD.md); scope='longterm' appends to your OWN LONGTERM.md (durable facts about yourself, the user and what happened between you; no other character reads it); scope='shared' appends to GLOBAL.md, which every character reads — append-only, never rewrite it; scope='scene' appends to the house log SCENE.md (what happened in a room, for everyone who was there or wants to know). Avoid saving passwords, API keys, tokens, or secrets unless the user explicitly confirms after being warned.")
            put("input_schema", JSONObject().apply {
                put("type", "object")
                put("properties", properties)
                put("required", JSONArray().apply {
                    put("tool_title")
                    put("content")
                })
            })
        }
    }

    fun memoryGetToolDefinition(): JSONObject {
        val properties = JSONObject().apply {
            put("tool_title", JSONObject().apply {
                put("type", "string")
                put("description", "A concise 5-10 word summary of what this tool call does, shown to the user (e.g. 'Recall user preferences', 'Search past notes'). Use the same language as the user.")
            })
            put("scope", JSONObject().apply {
                put("type", "string")
                put("description", "Memory scope to search: 'daily' for daily logs only, 'all' for daily logs + GLOBAL.md.")
                put("enum", JSONArray().apply {
                    put("daily")
                    put("all")
                })
            })
            put("keywords", JSONObject().apply {
                put("type", "string")
                put("description", "Space-separated keywords for fuzzy matching (e.g. 'python preference' or 'API key setup'). All keywords must appear in a line or its surrounding context for a match. Leave empty to return full memory files.")
            })
        }

        return JSONObject().apply {
            put("name", "memory_get")
            put("description", "Retrieve memories from persistent storage. Supports keyword-based fuzzy search across memory files. Returns matching lines with surrounding context. Use this to recall previous knowledge, user preferences, or past notes.")
            put("input_schema", JSONObject().apply {
                put("type", "object")
                put("properties", properties)
                put("required", JSONArray().apply {
                    put("tool_title")
                })
            })
        }
    }

    // -- OpenAI Function Calling format --

    fun memoryWriteOpenAIDefinition(): JSONObject {
        return JSONObject().apply {
            put("type", "function")
            put("function", JSONObject().apply {
                put("name", "memory_write")
                put("description", memoryWriteToolDefinition().getString("description"))
                put("parameters", memoryWriteToolDefinition().getJSONObject("input_schema"))
            })
        }
    }

    fun memoryGetOpenAIDefinition(): JSONObject {
        return JSONObject().apply {
            put("type", "function")
            put("function", JSONObject().apply {
                put("name", "memory_get")
                put("description", memoryGetToolDefinition().getString("description"))
                put("parameters", memoryGetToolDefinition().getJSONObject("input_schema"))
            })
        }
    }

    // -- Execution --

    data class ToolResult(
        val output: String,
        val success: Boolean,
        val toolTitle: String = "",
    )

    fun executeMemoryWrite(
        inputJson: String,
        repository: MemoryRepository,
        // [T-private-longterm] Who is writing — used to sign entries appended to
        // the SHARED GLOBAL.md, where two characters write into one file and a
        // bare paragraph would leave "who said this" unanswerable.
        author: String? = null,
    ): ToolResult {
        return try {
            val obj = JSONObject(inputJson)
            val content = obj.optString("content", "")
            val toolTitle = obj.optString("tool_title", "memory_write")
            // [T-private-longterm] Where the entry lands:
            //   daily    (default) today's YYYY-MM-DD.md — the historical behaviour
            //   longterm this character's own LONGTERM.md, nobody else reads it
            //   shared   append to the shared GLOBAL.md every character reads
            val scope = obj.optString("scope", "daily").lowercase()
            val signer = obj.optString("author", "").ifBlank { author }

            if (content.isBlank()) {
                ToolResult("Error: Missing required 'content' parameter", false, toolTitle)
            } else {
                val result = when (scope) {
                    "longterm", "long-term", "private" -> repository.writeLongTerm(content)
                    "shared", "global" -> repository.appendShared(content, signer)
                    // [T-persona-phase2] The house's "what happened" tape, read
                    // by everyone. Rules go to shared; events go here.
                    "scene", "room", "house" -> repository.appendScene(content, signer)
                    else -> repository.writeMemory(content)
                }
                val success = !result.startsWith("Error")
                ToolResult(result, success, toolTitle)
            }
        } catch (e: Exception) {
            ToolResult("Error: ${e.message}", false)
        }
    }

    fun executeMemoryGet(inputJson: String, repository: MemoryRepository): ToolResult {
        return try {
            val obj = JSONObject(inputJson)
            val keywords = obj.optString("keywords", "")
            val scope = obj.optString("scope", "all")
            val toolTitle = obj.optString("tool_title", "memory_get")

            val result = repository.getMemory(keywords, scope)
            ToolResult(result, true, toolTitle)
        } catch (e: Exception) {
            ToolResult("Error: ${e.message}", false)
        }
    }
}
