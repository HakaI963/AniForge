package dev.aniforge.domain

import kotlinx.serialization.Serializable
import kotlinx.serialization.SerialName
import kotlinx.serialization.json.Json

/** AsProject — the AniForge port of the OpenShot .osp timeline (JSON). */
@Serializable
data class Fraction(val num: Int = 24, val den: Int = 1) {
    fun value(): Float = num / den.coerceAtLeast(1).toFloat()
}

@Serializable
data class Reader(
    @SerialName("path") val path: String = "",
    @SerialName("has_video") val hasVideo: Boolean = true,
    @SerialName("has_audio") val hasAudio: Boolean = true,
    @SerialName("duration") val duration: Float = 0f,
    @SerialName("media_type") val mediaType: String = "video"
)

@Serializable
data class Effect(val type: String = "Fade", val params: Map<String, String>? = null)

@Serializable
data class Coordinate(val X: Float = 0f, val Y: Float = 1f)

@Serializable
data class Point(val co: Coordinate = Coordinate(), val interpolation: String = "BEZIER")

@Serializable
data class Keyframe(val points: List<Point>? = null, val default: Float = 1f)

@Serializable
data class TextBlock(val content: String = "", val style: String = "anime", val position: String = "center")

@Serializable
data class Layer(val id: Int = 0, val name: String = "V1")

@Serializable
data class Marker(val position: Float = 0f, val label: String = "")

@Serializable
data class Clip(
    val id: String,
    val layer: Int = 0,
    val reader: Reader = Reader(),
    val position: Float = 0f,
    val start: Float = 0f,
    val end: Float = 0f,
    val speed: Float = 1f,
    val effects: List<Effect>? = null,
    val keyframes: Map<String, Keyframe>? = null,
    val text: TextBlock? = null,
    val crop: Map<String, String>? = null,
) {
    fun duration(): Float = ((end - start).coerceAtLeast(0f)) / speed.coerceAtLeast(0.001f)
    fun outgoing(): Float = position + duration()
}

@Serializable
data class AsProject(
    val version: Int = 1,
    val fps: Fraction = Fraction(),
    val width: Int = 1920,
    val height: Int = 1080,
    val profile: String = "shonen",
    val layers: List<Layer> = listOf(Layer(0, "V1")),
    val clips: List<Clip> = emptyList(),
    val markers: List<Marker>? = null,
) {
    fun duration(): Float = clips.maxOfOrNull { it.outgoing() } ?: 0f
    fun find(id: String): Clip? = clips.firstOrNull { it.id == id }
}

object Demo {
    val json = Json { ignoreUnknownKeys = true; encodeDefaults = true }

    fun sampleProject(): AsProject = AsProject(
        layers = listOf(Layer(0, "V1"), Layer(1, "V2")),
        clips = listOf(
            Clip(
                id = "c1", layer = 0,
                reader = Reader("https://example.com/op.mp4", duration = 90f),
                position = 0f, start = 0.5f, end = 12f,
                effects = listOf(Effect("Fade", mapOf("in" to "0.4", "out" to "0.4")))
            ),
            Clip(
                id = "c2", layer = 1,
                reader = Reader("https://example.com/bgm.mp3", hasVideo = false, mediaType = "audio", duration = 240f),
                position = 0f, start = 0f, end = 240f, speed = 1f
            )
        ),
        markers = listOf(Marker(8.4f, "kiai!"))
    )

    fun toJson(p: AsProject): String = json.encodeToString(AsProject.serializer(), p)
    fun fromJson(s: String): AsProject = json.decodeFromString(AsProject.serializer(), s)
}
