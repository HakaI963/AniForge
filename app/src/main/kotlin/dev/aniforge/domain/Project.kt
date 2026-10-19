package dev.aniforge.domain

import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToLong

/** Model classes for the on-device editor (OpenShot-like timeline). */

enum class CropMode(val label: String, val aspect: Float?) {
    FREE("free", null), R1_1("1:1", 1f), R4_5("4:5", 0.8f),
    R9_16("9:16", 9f / 16f), R16_9("16:9", 16f / 9f)
}

enum class ColorGrade(val label: String) {
    NONE("none"), WARM("warm"), COOL("cool"), NOIR("noir"),
    PASTEL("pastel"), VIVID("vivid")
}

enum class Easing(val label: String) {
    LINEAR("linear"), SMOOTH("smooth"), EASE_IN("ease-in"), EASE_OUT("ease-out")
}

enum class Prop(val key: String, val label: String, val def: Float, val lo: Float, val hi: Float) {
    SCALE("scale", "scale", 1.0f, 0.05f, 4.0f),
    ROT("rot", "rotate", 0.0f, -180f, 180f),
    POSX("posX", "pos X", 0.0f, -1f, 1f),
    POSY("posY", "pos Y", 0.0f, -1f, 1f),
    OPACITY("opacity", "opacity", 1.0f, 0f, 1f),
    VOLUME("volume", "volume", 1.0f, 0f, 1f),
    BRIGHT("bright", "brightness", 0.0f, -0.6f, 0.6f)
}

@Serializable
data class KeyPoint(val t: Float, val v: Float, val ease: String = "smooth")

@Serializable
data class MotionTrack(val points: List<KeyPoint> = emptyList()) {
    fun sample(t: Float): Float {
        if (points.isEmpty()) return 0f
        val sorted = points.sortedBy { it.t }
        if (t <= sorted.first().t) return sorted.first().v
        if (t >= sorted.last().t) return sorted.last().v
        for (i in 0 until sorted.size - 1) {
            val a = sorted[i]; val b = sorted[i + 1]
            if (t in a.t..b.t) {
                val x = if (b.t - a.t <= 1e-5f) 0f else (t - a.t) / (b.t - a.t)
                val y = when (a.ease) {
                    "linear" -> x
                    "easein" -> x * x
                    "easeout" -> 1f - (1f - x) * (1f - x)
                    else -> x * x * (3f - 2f * x) // smooth
                }
                return a.v + (b.v - a.v) * y
            }
        }
        return sorted.last().v
    }

    fun upsert(t: Float, v: Float, ease: String = "smooth"): MotionTrack {
        val pts = points.filter { kotlin.math.abs(it.t - t) > 1e-4f }.toMutableList()
        pts.add(KeyPoint(t, v, ease)); pts.sortBy { it.t }
        return copy(points = pts)
    }

    fun remove(t: Float): MotionTrack =
        copy(points = points.filter { kotlin.math.abs(it.t - t) > 1e-4f })

    fun shifted(dl: Float): MotionTrack = copy(points = points.map { it.copy(t = it.t + dl) })
}

fun MotionTrack?.orDef(def: Float) = this ?: MotionTrack(listOf(KeyPoint(0f, def)))

@Serializable
data class Clip(
    val id: String,
    val track: Int = 0,          // 0,1 video / 2 music
    val kind: String = "video",  // video | audio
    val sourceUri: String = "",
    val srcDurationMs: Long = 0L,
    val srcW: Int = 0,
    val srcH: Int = 0,
    val positionMs: Long = 0L,
    val inMs: Long = 0L,
    val outMs: Long = 0L,
    val speed: Float = 1f,
    val volume: Float = 1f,
    val muted: Boolean = false,
    val crop: CropMode = CropMode.FREE,
    val color: ColorGrade = ColorGrade.NONE,
    val brightness: Float = 0f,
    val contrast: Float = 1f,
    val saturation: Float = 1f,
    val title: String = "",
    val titleSize: Float = 0.10f,
    val titleColor: String = "#FFFFFFFF",
    val transforms: Map<String, MotionTrack> = emptyMap(),
) {
    fun durationMs(): Long = max(1L, ((outMs - inMs) / speed).roundToLong())
    fun outMsEnd(): Long = positionMs + durationMs()
    fun sourceTimeMs(atOutMs: Long): Long =
        inMs + ((atOutMs - positionMs) * speed).roundToLong()

    fun contains(atOutMs: Long): Boolean = atOutMs >= positionMs && atOutMs < outMsEnd()
    fun prop(p: Prop, atLocalOutMs: Float): Float {
        val tr = transforms[p.key] ?: return p.def
        return tr.sample(atLocalOutMs)
    }

    fun withProp(p: Prop, tr: MotionTrack): Clip =
        copy(transforms = transforms + (p.key to tr))
}

@Serializable
data class Project(
    val id: String,
    val title: String,
    val fps: Int = 30,
    val width: Int = 1920,
    val height: Int = 1080,
    val clips: List<Clip> = emptyList(),
) {
    fun durationMs(): Long = clips.maxOfOrNull { it.outMsEnd() } ?: 0L
    fun videoTrackClips(track: Int = 0): List<Clip> =
        clips.filter { it.track != 2 && it.kind == "video" }.sortedBy { it.positionMs }
    fun allVideoClips(): List<Clip> = clips.filter { it.kind == "video" }
    fun visualClips(): List<Clip> = clips.filter { it.kind != "audio" }.sortedBy { it.positionMs }
    fun musicClips(): List<Clip> = clips.filter { it.track == 2 && it.kind == "audio" }
    fun find(id: String): Clip? = clips.firstOrNull { it.id == id }
    fun activeAt(t: Long): List<Clip> = clips.filter { it.contains(t) }
    fun randClipId(): String = "c" + (System.nanoTime() % 1_000_000L)
}

/** Helpers used by preview + export to resolve the timeline at time t. */
object Timeline {
    fun clipsBetween(p: Project, t0: Long, t1: Long): List<Clip> =
        p.clips.filter { c -> c.outMsEnd() > t0 && c.positionMs < t1 }.sortedBy { it.positionMs }

    /** index of frame at output time t (ms) inside clip's own out-frame stream. */
    fun localOutMs(c: Clip, t: Long): Long = t - c.positionMs
}

val projectJson = Json { ignoreUnknownKeys = true; encodeDefaults = true; prettyPrint = true }
fun Project.encode(): String = projectJson.encodeToString(this)
fun decodeProject(s: String): Project? = runCatching { projectJson.decodeFromString<Project>(s) }.getOrNull()

fun deepCopy(p: Project): Project = decodeProject(p.encode()) ?: p

fun newProject(title: String): Project =
    Project(id = "p" + (System.nanoTime() % 1_000_000L), title = title)
