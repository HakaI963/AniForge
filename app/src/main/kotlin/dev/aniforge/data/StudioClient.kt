package dev.aniforge.data

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonObject
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import java.util.concurrent.TimeUnit

/**
 * Thin Studio API client.
 * Same JSON contract as the server (`server/aniforge_server/engine.py` → AsProject).
 */
object StudioClient {

    var baseUrl: String = "http://10.0.2.2:8787"

    private val http = OkHttpClient.Builder()
        .connectTimeout(6, TimeUnit.SECONDS)
        .readTimeout(20, TimeUnit.SECONDS)
        .build()

    private val json = Json { ignoreUnknownKeys = true; encodeDefaults = true }

    /** Returns parsed response JSON object. */
    suspend fun call(method: String, path: String, body: JsonObject? = null): JsonObject =
        withContext(Dispatchers.IO) {
            val b = Request.Builder().url(baseUrl.trimEnd('/') + path)
            if (body != null) {
                val media = "application/json; charset=utf-8".toMediaType()
                b.post(body.toString().toRequestBody(media))
            }
            http.newCall(b.build()).execute().use { resp ->
                val text = resp.body?.string().orEmpty()
                if (!resp.isSuccessful) throw RuntimeException("HTTP ${resp.code}: ${text.take(220)}")
                json.parseToJsonElement(text).jsonObject
            }
        }

    suspend fun health(): JsonObject = call("GET", "/health")
    suspend fun schema(): JsonObject = call("GET", "/schema")

    /** Apply a set of ops to a project provided "as JSON text". */
    suspend fun apply(rawProject: JsonObject, ops: List<JsonObject>, message: String): JsonObject {
        val payload = buildJsonObject {
            put("project", rawProject)
            put("ops", buildJsonArray { ops.forEach { add(it) } })
            put("message", JsonPrimitive(message))
        }
        return call("POST", "/edit/apply", payload)
    }

    suspend fun applyFromJson(projectJson: String, opsJson: String, message: String): JsonObject {
        if (projectJson.isBlank()) throw RuntimeException("No project loaded — load demo or pick material")
        val project = json.parseToJsonElement(projectJson).jsonObject
        val opsRaw = json.parseToJsonElement(opsJson.ifBlank { "[]" })
        val ops = when (opsRaw) {
            is kotlinx.serialization.json.JsonArray -> opsRaw.mapNotNull { it.jsonObject }
            else -> emptyList()
        }
        return apply(project, ops, message)
    }

    suspend fun validateFromJson(projectJson: String): JsonObject {
        if (projectJson.isBlank()) throw RuntimeException("No project loaded")
        val project = json.parseToJsonElement(projectJson).jsonObject
        val payload = buildJsonObject { put("project", project) }
        return call("POST", "/project/validate", payload)
    }

    suspend fun render(rawProject: JsonObject, preset: String?): JsonObject {
        val payload = buildJsonObject {
            put("project", rawProject)
            if (!preset.isNullOrBlank()) put("preset", JsonPrimitive(preset))
        }
        return call("POST", "/project/render", payload)
    }

    suspend fun renderFromJson(projectJson: String, preset: String?): JsonObject {
        if (projectJson.isBlank()) throw RuntimeException("No project loaded")
        val project = json.parseToJsonElement(projectJson).jsonObject
        return render(project, preset)
    }

    /** Project wrapper: raw JsonObject as it came from the wire. */
    class AsLike(val raw: JsonObject)
}
