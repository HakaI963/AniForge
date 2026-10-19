package dev.aniforge.domain

import android.content.Context
import android.content.SharedPreferences
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import java.net.HttpURLConnection
import java.net.URL

/**
 * Optional LLM brain (OpenAI-compatible /chat/completions).
 * Configure endpoint + key + model once (stored locally); the agent then uses it to
 * interpret the prompt and shape the edit. No key → the offline Director is used.
 */
object Llm {
    private val json = Json { ignoreUnknownKeys = true }

    private fun prefs(context: Context): SharedPreferences =
        context.getSharedPreferences("aniforge_agent", Context.MODE_PRIVATE)

    data class Config(val endpoint: String, val apiKey: String, val model: String) {
        val isValid get() = endpoint.startsWith("http") && model.isNotBlank()
    }

    fun load(context: Context): Config? {
        val p = prefs(context)
        val ep = p.getString("endpoint", "") ?: ""
        val key = p.getString("key", "") ?: ""
        val model = p.getString("model", "") ?: ""
        val c = Config(ep, key, model)
        return if (c.isValid) c else null
    }

    fun save(context: Context, endpoint: String, key: String, model: String) {
        prefs(context).edit()
            .putString("endpoint", endpoint.trim())
            .putString("key", key.trim())
            .putString("model", model.trim())
            .apply()
    }

    fun clear(context: Context) = prefs(context).edit().clear().apply()

    private fun completionUrl(endpoint: String): String =
        if (endpoint.endsWith("/chat/completions")) endpoint
        else endpoint.trimEnd('/') + "/chat/completions"

    private fun httpJson(url: String, apiKey: String?, body: String): String {
        val conn = URL(url).openConnection() as HttpURLConnection
        conn.connectTimeout = 12_000
        conn.readTimeout = 60_000
        conn.requestMethod = "POST"
        conn.doOutput = true
        conn.setRequestProperty("Content-Type", "application/json")
        conn.setRequestProperty("User-Agent", "AniForge-Studio/0.3")
        apiKey?.takeIf { it.isNotBlank() }?.let { conn.setRequestProperty("Authorization", "Bearer $it") }
        conn.outputStream.use { it.write(body.toByteArray(Charsets.UTF_8)) }
        if (conn.responseCode !in 200..299) throw RuntimeException("LLM HTTP ${conn.responseCode}")
        return conn.inputStream.use { it.readBytes().toString(Charsets.UTF_8) }
    }

    /**
     * Asks the LLM to shape the edit. Returns [LlmParams] or null on any failure
     * (the offline Director kicks in as fallback).
     */
    suspend fun plan(
        context: Context,
        cfg: Config,
        prompt: String,
        analyses: List<ClipAnalysis>,
    ): LlmParams? = withContext(Dispatchers.IO) {
        runCatching {
            val brief = analyses.joinToString("\n") { a ->
                "- clip ${a.clip.srcDurationMs / 1000}s, ${a.cuts.size + 1} scenes, energy ${"%.2f".format(a.motion)}"
            }
            val system = """
                You plan video edits. Reply ONLY with compact JSON, no prose:
                {"style":"fast|chill|trailer|sns","target_ms":<ms int 8000..60000>,
                 "cut_every_ms":<ms int 400..3000>,"grade":"none|warm|cool|noir|pastel|vivid",
                 "crop":"free|1:1|4:5|9:16|16:9","intro_title":"<=18 chars" or null,
                 "outro_title":"<=18 chars" or null,"speed_wave":[floats like 1.0,1.2],
                 "music_query":"search words for a music track"}
            """.trimIndent()
            val user = "User prompt: $prompt\nFootage analysis:\n$brief"
            val body = json.encodeToString(
                kotlinx.serialization.json.JsonObject.serializer(),
                kotlinx.serialization.json.buildJsonObject {
                    put("model", kotlinx.serialization.json.JsonPrimitive(cfg.model))
                    put(
                        "messages", kotlinx.serialization.json.buildJsonArray {
                            add(kotlinx.serialization.json.buildJsonObject {
                                put("role", kotlinx.serialization.json.JsonPrimitive("system"))
                                put("content", kotlinx.serialization.json.JsonPrimitive(system))
                            })
                            add(kotlinx.serialization.json.buildJsonObject {
                                put("role", kotlinx.serialization.json.JsonPrimitive("user"))
                                put("content", kotlinx.serialization.json.JsonPrimitive(user))
                            })
                        }
                    )
                    put("temperature", kotlinx.serialization.json.JsonPrimitive(0.3))
                }
            )
            val resp = httpJson(completionUrl(cfg.endpoint), cfg.apiKey, body)
            val content = json.parseToJsonElement(resp).jsonObject["choices"]!!
                .jsonArray[0].jsonObject["message"]!!.jsonObject["content"]!!.jsonPrimitive.content
            val start = content.indexOf('{')
            val end = content.lastIndexOf('}')
            if (start < 0 || end <= start) return@runCatching null
            val plan = json.parseToJsonElement(content.substring(start, end + 1)).jsonObject

            fun s(k: String): String? = plan[k]?.jsonPrimitive?.content
            fun n(k: String): Long? = plan[k]?.jsonPrimitive?.content?.toDoubleOrNull()?.toLong()

            LlmParams(
                style = EditStyle.entries.firstOrNull { it.id == s("style") }
                    ?: PlanParser.parse(prompt, 0).style,
                targetMs = (n("target_ms") ?: 30_000L).coerceIn(8_000L, 90_000L),
                cutEveryMs = (n("cut_every_ms") ?: 900L).coerceIn(350L, 4_000L),
                grade = ColorGrade.entries.firstOrNull { it.name.lowercase() == s("grade") }
                    ?: ColorGrade.NONE,
                crop = when (s("crop")) {
                    "1:1" -> CropMode.R1_1; "4:5" -> CropMode.R4_5
                    "9:16" -> CropMode.R9_16; "16:9" -> CropMode.R16_9
                    else -> CropMode.FREE
                },
                introTitle = s("intro_title")?.take(18),
                outroTitle = s("outro_title")?.take(18),
                speedWave = plan["speed_wave"]?.jsonArray
                    ?.mapNotNull { it.jsonPrimitive.content.toFloatOrNull() }
                    ?.filter { it in 0.25f..4f }
                    ?.ifEmpty { listOf(1f) } ?: listOf(1f),
                musicQuery = s("music_query")?.take(60),
            )
        }.getOrNull()
    }
}
