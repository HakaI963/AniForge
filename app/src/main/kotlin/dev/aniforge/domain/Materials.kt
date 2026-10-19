package dev.aniforge.domain

import android.content.Context
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import java.io.File
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder

/**
 * Agent #2 — auto-finds and downloads free/open-license material:
 *   • music (Openverse audio search, keyless public API)
 *   • images for intro/outro cards
 */
object Materials {

    private val json = Json { ignoreUnknownKeys = true }

    private const val UA = "AniForge-Studio/0.3 (https://github.com/HakaI963/AniForge)"

    private fun http(urlStr: String, apiKey: String? = null): ByteArray {
        val conn = URL(urlStr).openConnection() as HttpURLConnection
        conn.connectTimeout = 12_000
        conn.readTimeout = 30_000
        conn.setRequestProperty("User-Agent", UA)
        conn.setRequestProperty("Accept", "application/json")
        apiKey?.let { conn.setRequestProperty("Authorization", "Bearer $it") }
        if (conn.responseCode !in 200..299) {
            throw RuntimeException("HTTP ${conn.responseCode} for $urlStr")
        }
        return conn.inputStream.use { it.readBytes() }
    }

    data class AudioHit(val url: String, val title: String, val durationMs: Long, val license: String)
    data class ImageHit(val url: String, val title: String, val width: Int, val height: Int, val license: String)

    suspend fun searchMusic(query: String, apiKey: String? = null): List<AudioHit> = withContext(Dispatchers.IO) {
        runCatching {
            val q = URLEncoder.encode(query.take(80), "UTF-8")
            val body = http("https://api.openverse.org/v1/audio/?q=$q&page_size=20", apiKey)
            val root = json.parseToJsonElement(body.toString(Charsets.UTF_8)).jsonObject
            val arr = root["results"]?.jsonArray ?: return@runCatching emptyList()
            arr.mapNotNull { el ->
                val o = el.jsonObject
                val url = o["url"]?.jsonPrimitive?.content ?: return@mapNotNull null
                val durRaw = o["duration"]?.jsonPrimitive?.content?.toLongOrNull() ?: 0L
                // API returns ms; if implausibly small, treat as seconds
                val durMs = if (durRaw in 1..10_000) durRaw * 1000L else durRaw
                AudioHit(
                    url = url,
                    title = o["title"]?.jsonPrimitive?.content ?: "track",
                    durationMs = durMs,
                    license = (o["license"]?.jsonPrimitive?.content ?: "?"),
                )
            }
        }.getOrDefault(emptyList())
    }

    suspend fun searchImages(query: String, apiKey: String? = null): List<ImageHit> = withContext(Dispatchers.IO) {
        runCatching {
            val q = URLEncoder.encode(query.take(80), "UTF-8")
            val body = http("https://api.openverse.org/v1/images/?q=$q&page_size=20", apiKey)
            val root = json.parseToJsonElement(body.toString(Charsets.UTF_8)).jsonObject
            val arr = root["results"]?.jsonArray ?: return@runCatching emptyList()
            arr.mapNotNull { el ->
                val o = el.jsonObject
                val url = o["url"]?.jsonPrimitive?.content ?: return@mapNotNull null
                ImageHit(
                    url = url,
                    title = o["title"]?.jsonPrimitive?.content ?: "image",
                    width = o["width"]?.jsonPrimitive?.content?.toIntOrNull() ?: 0,
                    height = o["height"]?.jsonPrimitive?.content?.toIntOrNull() ?: 0,
                    license = (o["license"]?.jsonPrimitive?.content ?: "?"),
                )
            }
        }.getOrDefault(emptyList())
    }

    private fun materialsDir(context: Context): File =
        File(context.filesDir, "materials").apply { mkdirs() }

    suspend fun download(context: Context, url: String, name: String): File = withContext(Dispatchers.IO) {
        val bytes = http(url)
        val dst = File(materialsDir(context), name)
        dst.writeBytes(bytes)
        dst
    }

    /** Picks the best music hit (prefer CC0 / longer), downloads it. */
    suspend fun fetchMusic(context: Context, query: String, apiKey: String? = null): MaterialFile? {
        val hits = searchMusic(query, apiKey)
            .filter { it.url.isNotBlank() && it.durationMs > 30_000L }
            .sortedWith(
                compareByDescending<AudioHit> { it.license in listOf("cc0", "pdm") }
                    .thenByDescending { it.durationMs }
            )
        if (hits.isEmpty()) return null
        for (hit in hits.take(4)) {
            runCatching {
                val ext = hit.url.substringBefore("?").substringAfterLast('.').take(4).ifBlank { "mp3" }
                val f = download(context, hit.url, "music-${System.currentTimeMillis()}.$ext")
                if (f.length() > 20_000) {
                    return MaterialFile(f.absolutePath, hit.durationMs, hit.title, hit.license)
                }
            }
        }
        return null
    }

    /** Fetches a suitable (wide) image for a title card. */
    suspend fun fetchCardImage(context: Context, query: String, apiKey: String? = null): MaterialFile? {
        val hits = searchImages(query, apiKey)
            .filter { it.width >= 640 && it.width >= it.height }
        for (hit in hits.take(5)) {
            runCatching {
                val ext = hit.url.substringBefore("?").substringAfterLast('.').take(4).ifBlank { "jpg" }
                val f = download(context, hit.url, "card-${System.currentTimeMillis()}.$ext")
                if (f.length() > 8_000) {
                    return MaterialFile(f.absolutePath, 0L, hit.title, hit.license, hit.width, hit.height)
                }
            }
        }
        return null
    }
}
