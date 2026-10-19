package dev.aniforge.domain.agent

import android.content.Context
import dev.aniforge.domain.ProjectStore
import dev.aniforge.domain.Llm
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.JsonObjectSerializer
import kotlinx.serialization.json.contentOrNull
import java.net.HttpURLConnection
import java.net.URL
import kotlin.math.abs

sealed class AgentEvent {
    /** who: user | agent | sys */
    data class Chat(val who: String, val text: String) : AgentEvent()
    data class ToolUse(val name: String, val args: String, val result: String, val ok: Boolean) : AgentEvent()
    data class Busy(val busy: Boolean) : AgentEvent()
}

/**
 * The chat agent loop:
 *   user text → [LLM brain w/ tool-calling] → execute tools on the real timeline →
 *   feed results back → repeat until the agent answers in prose.
 * No LLM configured (or unreachable) → [OfflineBrain] keyword intents drive the
 * same ToolKit so the app stays fully functional on-device.
 */
open class AgentLoop(
    private val context: Context,
    private val kit: ToolKit,
    private val onEvent: (AgentEvent) -> Unit,
) {
    private val json = Json { ignoreUnknownKeys = true }
    private val history = ArrayList<JsonObject>()

    fun reset() = history.clear()

    suspend fun send(userText: String) {
        if (userText.isBlank()) return
        onEvent(AgentEvent.Busy(true))
        try {
            val cfg = Llm.load(context)
            if (cfg != null) {
                runCatching { llmLoop(cfg, userText) }.onFailure { e ->
                    onEvent(AgentEvent.Chat("sys", "💭 brain unreachable (${(e.message ?: "error").take(70)}) — switching to built-in skills"))
                    offline(userText)
                }
            } else {
                offline(userText)
            }
        } finally {
            onEvent(AgentEvent.Busy(false))
        }
    }

    // ------------------------------------------------------------ offline
    private suspend fun offline(text: String) {
        val plan = OfflineBrain.plan(text, kit.store)
        for ((name, args) in plan.calls.take(8)) {
            runTool(name, args)
        }
        onEvent(AgentEvent.Chat("agent", plan.reply))
    }

    // ------------------------------------------------------------ LLM loop
    private suspend fun llmLoop(cfg: Llm.Config, userText: String) = withContext(Dispatchers.IO) {
        history.add(msg("user", userText))
        trimHistory()
        var round = 0
        while (round++ < 9) {
            val resp = runCatching { httpJson(cfg, buildRequestBody(cfg)) }.getOrElse { throw it }
            val root = runCatching { json.parseToJsonElement(resp).jsonObject }
                .getOrElse { throw RuntimeException("malformed LLM response") }
            val message = root["choices"]?.jsonArray?.firstOrNull()?.jsonObject?.get("message")?.jsonObject
                ?: throw RuntimeException("no message in LLM response")
            val content = (message["content"] as? JsonPrimitive)?.contentOrNull
            val toolCalls = message["tool_calls"]?.jsonArray

            if (toolCalls.isNullOrEmpty()) {
                val txt = content?.take(700)?.ifBlank { "(done)" } ?: "(done)"
                history.add(msg("assistant", txt))
                onEvent(AgentEvent.Chat("agent", txt))
                return@withContext
            }

            history.add(cleanAssistant(message))
            for (tc in toolCalls) {
                val fn = tc.jsonObject["function"]?.jsonObject ?: continue
                val name = fn["name"]?.jsonPrimitive?.contentOrNull ?: continue
                val argsRaw = fn["arguments"]?.jsonPrimitive?.contentOrNull ?: "{}"
                val args = runCatching { json.parseToJsonElement(argsRaw).jsonObject }
                    .getOrElse { buildJsonObject { } }
                val result = runTool(name, args, argsRaw)
                val id = (tc.jsonObject["id"] as? JsonPrimitive)?.contentOrNull ?: "call_$round"
                history.add(buildJsonObject {
                    put("role", "tool")
                    put("tool_call_id", id)
                    put("content", result.take(700))
                })
            }
            trimHistory()
        }
        onEvent(AgentEvent.Chat("agent", "that pass is in ✓ (step cap reached — ask me to continue)"))
    }

    /** Executes a tool + emits the chat event. Returns the raw result string. */
    private suspend fun runTool(name: String, args: JsonObject, argsRaw: String? = null): String {
        val pretty = argsRaw?.takeIf { it.length <= 90 } ?: args.toString().take(90)
        val tool = kit.tools.firstOrNull { it.name == name }
        val result = if (tool == null) "error: unknown tool '$name'"
        else runCatching { tool.run(args) }
            .getOrElse { "error: ${it.message ?: it.javaClass.simpleName}" }
        onEvent(AgentEvent.ToolUse(name, pretty, result.take(260), !result.startsWith("error:")))
        return result
    }

    private fun trimHistory() {
        if (history.size > 24) history.subList(0, 4).clear()
    }

    private fun msg(role: String, content: String): JsonObject = buildJsonObject {
        put("role", role); put("content", content)
    }

    private fun cleanAssistant(m: JsonObject): JsonObject = buildJsonObject {
        put("role", "assistant")
        put("content", (m["content"] as? JsonPrimitive)?.contentOrNull ?: "")
        (m["tool_calls"] ?: return@buildJsonObject).let { put("tool_calls", it.jsonArray) }
    }

    private fun buildRequestBody(cfg: Llm.Config): String {
        val toolsArr = buildJsonArray {
            for (t in kit.tools) {
                add(buildJsonObject {
                    put("type", "function")
                    put("function", buildJsonObject {
                        put("name", t.name)
                        put("description", t.desc)
                        put("parameters", runCatching { json.parseToJsonElement(t.params) }
                            .getOrElse { buildJsonObject { } })
                    })
                })
            }
        }
        val messages = buildJsonArray {
            add(msg("system", SYSTEM))
            history.forEach { add(it) }
        }
        val body = buildJsonObject {
            put("model", cfg.model)
            put("messages", messages)
            put("tools", toolsArr)
            put("temperature", 0.4)
        }
        return json.encodeToString(JsonObjectSerializer, body)
    }

    private fun httpJson(cfg: Llm.Config, body: String): String {
        val url = if (cfg.endpoint.endsWith("/chat/completions")) cfg.endpoint
        else cfg.endpoint.trimEnd('/') + "/chat/completions"
        val conn = URL(url).openConnection() as HttpURLConnection
        conn.connectTimeout = 12_000
        conn.readTimeout = 90_000
        conn.requestMethod = "POST"
        conn.doOutput = true
        conn.setRequestProperty("Content-Type", "application/json")
        conn.setRequestProperty("User-Agent", "AniForge-ChatCut/0.4")
        cfg.apiKey.takeIf { it.isNotBlank() }?.let { conn.setRequestProperty("Authorization", "Bearer $it") }
        conn.outputStream.use { it.write(body.toByteArray(Charsets.UTF_8)) }
        if (conn.responseCode !in 200..299) throw RuntimeException("LLM HTTP ${conn.responseCode}")
        return conn.inputStream.use { it.readBytes().toString(Charsets.UTF_8) }
    }

    private companion object {
        val SYSTEM = """
            You are the ChatCut agent - you edit a REAL video timeline through tool calls on an Android device.

            Ground rules:
            1. Your media comes from the media pool: refs returned by download_media/generate_image_af, names like clip1/clip2 for the user's imported files, or explicit file paths/URLs. NEVER invent refs.
            2. When unsure what the timeline holds, call read_timeline once. Avoid spamming read-backs; the user sees every result.
            3. Times in tool args are SECONDS (floats ok). Captions/titles <= 40 chars.
            4. General one-shot 'make me an edit of this' requests → auto_edit_af with the user's words. Surgical requests (split, grade, speed, captions...) → the specific tools.
            5. Money-grade default: after big structural changes, suggest checking the result; offer follow-ups.
            6. Answer in short plain sentences (the user's language). No tool dumps in prose.
        """.trimIndent()
    }
}

/** Keyword intent parser used when no LLM brain is configured (or it's offline). */
object OfflineBrain {

    data class Plan(val reply: String, val calls: List<Pair<String, JsonObject>>)

    fun plan(raw: String, store: ProjectStore): Plan {
        val text = raw.lowercase()
        val calls = ArrayList<Pair<String, JsonObject>>()
        val notes = ArrayList<String>()

        api(raw, text, calls, notes, store)
        if (calls.isEmpty() && notes.isEmpty()) {
            notes.add("I'm the offline brain (no 🧠 configured). Quick things I do right now:\n" +
                "• \"add music lofi\" · \"cut to beats\" · \"warm grade\" · \"vertical\"\n" +
                "• \"one-click edit: 30s epic amv\" · \"export\"\n" +
                "• undo / status / clear")
        }
        val reply = if (calls.isEmpty() && notes.isNotEmpty()) notes.joinToString("\n")
        else (notes.joinToString("\n").take(180)).ifEmpty {
            "executed: " + calls.joinToString(", ") { it.first.substringBefore("_af") }
        }
        return Plan(reply, calls)
    }

    private fun api(raw: String, t: String, out: ArrayList<Pair<String, JsonObject>>, notes: ArrayList<String>, store: ProjectStore) {
        fun call(name: String, args: JsonObject = buildJsonObject { }) {
            out.add(name to args)
        }

        // aspect
        if (listOf("9:16", "vertical", "tiktok", "reel", "shorts", "竖屏").any { t.contains(it) })
            call("set_aspect_ratio", buildJsonObject { put("ratio", "9:16") })
        else if ("1:1" in t || "square" in t) call("set_aspect_ratio", buildJsonObject { put("ratio", "1:1") })
        else if ("4:5" in t) call("set_aspect_ratio", buildJsonObject { put("ratio", "4:5") })
        else if ("16:9" in t || "landscape" in t) call("set_aspect_ratio", buildJsonObject { put("ratio", "16:9") })

        // music (before beat sync so the bed exists)
        val musicHit = Regex("(?:music|音乐)\\s+(?:with\\s+|for\\s+)?([a-z0-9'\\- ]{3,40})").find(t)
        if (musicHit != null) {
            val words = musicHit.groupValues.getOrNull(1)?.trim().orEmpty()
            val style = when {
                listOf("lofi", "lo fi", "chill").any { t.contains(it) } -> "lofi chill instrumental"
                listOf("epic", "battle", "hype", "amv", "燃").any { t.contains(it) } -> "epic battle instrumental"
                listOf("trailer", "cinematic").any { t.contains(it) } -> "cinematic trailer score instrumental"
                listOf("sad", "soft", "emotional").any { t.contains(it) } -> "sad emotional piano instrumental"
                else -> "upbeat instrumental"
            }
            val query = if (words.length >= 3) words else style
            call("add_audio", buildJsonObject { put("ref", query.take(50)) })
        }

        // grades
        val grade = when {
            listOf("warm", "暖", "复古").any { t.contains(it) } -> "warm"
            listOf("noir", "black and white", "黑白", "mono").any { t.contains(it) } -> "noir"
            listOf("pastel", "奶油", "soft look").any { t.contains(it) } -> "pastel"
            listOf("vivid", "鲜艳", "saturated").any { t.contains(it) } -> "vivid"
            listOf("cool", "冷色", "cyber").any { t.contains(it) } -> "cool"
            else -> null
        }
        if (grade != null) call("auto_grade", buildJsonObject { put("grade", grade) })

        // smart cuts
        if (listOf("beat cut", "cut to beats", "card to the beat", "卡点", "节奏", "rhythm cut",
            "cut on beat", "beats").any { t.contains(it) })
            call("sync_cuts_af")
        if (listOf("remove silence", "silence", "去静音", "tighten ", "trim dead").any { t.contains(it) })
            call("remove_silence_af")
        if (listOf("highlight", "best moment", "高光", "montage").any { t.contains(it) })
            call("find_highlights_af")

        // speed xN on the most recent visual clip(s)
        Regex("(\\d(?:\\.\\d+)?)\\s*x\\b|\\bx\\s*(\\d(?:\\.\\d+)?)|speed\\s*(\\d(?:\\.\\d+)?)").find(t)?.let { m ->
            val v = m.groupValues[1].ifEmpty { m.groupValues[2].ifEmpty { m.groupValues[3] } }
            val speed = v.toFloatOrNull()
            if (speed != null && speed in 0.25f..3f) {
                val clips = store.project.value.visualClips().takeLast(6).map { it.id }
                if (clips.isEmpty()) notes.add("no clips on the timeline to speed up")
                else clips.forEach { id ->
                    call("edit_item", buildJsonObject {
                        put("itemId", id)
                        put("props", buildJsonObject { put("speed", speed) })
                    })
                }
            }
        }

        // image
        Regex("(?:image|picture|生成|画)[: ]*(?:of )?(?:an? )?([a-z0-9 ,\\-']{4,80})").find(t)?.let { m ->
            if ("image" in t || "picture" in t || "生成" in t || "画" in t)
                call("generate_image_af", buildJsonObject { put("prompt", m.groupValues[1].take(60)) })
        }

        // housekeeping
        if ("undo" in t || "撤销" in t) call("undo_last_change")
        if ("redo" in t || "恢复" in t || "回退" in t) { out.removeIf { it.first == "undo_last_change" }; call("redo_last_change") }
        if (listOf("clear", "start over", "重置", "清空").any { t.contains(it) }) call("clear_timeline")
        if (listOf("export", "导出", "mp4", "render").any { t.contains(it) })
            call("submit_export", buildJsonObject { put("preset", "auto") })
        if (listOf("status", "timeline", "what do we have", "check the timeline").any { t.contains(it) })
            call("read_timeline")

        // last resort: one-shot auto edit
        val wantsAuto = listOf("auto", "one click", "one-click", "一键", "make me a", "edit it", "edit this",
            "do an edit", "hit edit", "剪一个", "make the edit", "full edit", "amv", "edamv").any { t.contains(it) } ||
            (raw.length > 14 && ("edit" in t || "剪" in t) && out.isEmpty())
        if (wantsAuto && out.none { it.first == "auto_edit_af" })
            call("auto_edit_af", buildJsonObject { put("prompt", raw.take(200)) })
        else if (wantsAuto && out.isNotEmpty())
            notes.add("ran the quick ops — say \"one-click edit\" for a full rebuild")
    }
}
