package dev.aniforge.data

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONArray
import org.json.JSONObject

/** AniList GraphQL — anime catalog used as anime "material" (titles to reference in edits). */
object AniListApi {

    private val http = OkHttpClient()

    private val QUERY = """
      |query { Page(perPage: 12) {
      |  media(sort: POPULARITY_DESC, type: ANIME) {
      |    id title { romaji english } coverImage { large } genres
      |  }
      |} }
    """.trimMargin()

    suspend fun trending(): List<CatalogEntry> = withContext(Dispatchers.IO) {
        val mediaType = "application/json; charset=utf-8".toMediaType()
        val body = JSONObject().put("query", QUERY).toString().toRequestBody(mediaType)
        val req = Request.Builder()
            .url("https://graphql.anilist.co")
            .post(body)
            .build()
        http.newCall(req).execute().use { resp ->
            val text = resp.body?.string().orEmpty()
            if (!resp.isSuccessful) return@withContext emptyList()
            val obj = JSONObject(text)
            val media = obj.getJSONObject("data").getJSONObject("Page").getJSONArray("media")
            val out = mutableListOf<CatalogEntry>()
            for (i in 0 until media.length()) {
                val m = media.getJSONObject(i)
                val title = m.getJSONObject("title")
                val genres = m.optJSONArray("genres") ?: JSONArray()
                val genreList = (0 until genres.length()).map { genres.getString(it) }
                out += CatalogEntry(
                    id = m.optInt("id"),
                    title = title.optString("english").ifBlank { title.optString("romaji", "—") },
                    cover = m.getJSONObject("coverImage").optString("large"),
                    genres = genreList.joinToString(" / ")
                )
            }
            out
        }
    }
}

data class CatalogEntry(
    val id: Int,
    val title: String,
    val cover: String,
    val genres: String,
)
