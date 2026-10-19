package dev.aniforge.domain

import android.content.Context
import kotlin.math.max
import kotlin.math.min

/**
 * Deterministic on-device auto edit engine:
 *   • beatCut — chop a clip on the music's RMS peaks
 *   • autoRhythm — recreate an existing video's cut pattern onto your own clip
 *   • flashes / fades / ken burns / sns crops / grades / speed presets
 */
object ClipIdGen {
    private var seed = 4242L
    fun next(base: String): String = "$base-${(++seed % 100_000L)}"
}

object Editor {

    /** Split at N detected beats — a chiptune-cut effect. */
    suspend fun beatCut(store: ProjectStore, clipId: String, context: Context): String {
        val clip = store.project.value.find(clipId) ?: return "clip not found"
        val analysis = MediaProbe.analyzeAudio(context, clip.sourceUri)
        val beats = analysis.beatTimesMs.filter { it >= clip.inMs + 200 && it <= clip.outMs - 200 }
        if (beats.size < 2) return "not enough beats — try a music-heavy part"
        val bounds = listOf(clip.inMs) + beats + listOf(clip.outMs)
        val pieces = mutableListOf<Clip>()
        var idx = 0
        while (idx < bounds.size - 1) {
            val s = bounds[idx]; val e = bounds[idx + 1]
            if (e - s < 120) { idx++; continue }
            pieces.add(
                clip.copy(
                    id = ClipIdGen.next(clip.id),
                    inMs = s,
                    outMs = e,
                    positionMs = clip.positionMs + (s - clip.inMs)
                )
            )
            idx++
        }
        store.commit { p -> p.copy(clips = p.clips.filterNot { it.id == clipId } + pieces) }
        return "cut into ${pieces.size} beat-aligned pieces"
    }

    /**
     * Copy the timing of an existing video (its detected scene cuts) onto the
     * user's own clip: same rhythm, own footage. Alternate treatments for the
     * anime-look (crop variety + grade + gentle push-in + fade-in pushes).
     */
    suspend fun autoRhythm(store: ProjectStore, context: Context, refUri: String): String {
        val info = MediaProbe.probe(context, refUri) ?: return "reference video not readable"
        val cuts = MediaProbe.detectCuts(context, refUri)
        if (cuts.size < 2) return "couldn't detect cuts in the reference — pick another video"
        val scenes = mutableListOf<Pair<Long, Long>>()
        var s = 0L
        for (cut in cuts) { scenes.add(s to cut); s = cut }
        scenes.add(s to info.durationMs)

        val target = store.selected ?: store.project.value.videoTrackClips().firstOrNull()
            ?: return "import one of your videos first"
        val targetSrcDur = target.outMs - target.inMs
        val referenceDur = max(1L, info.durationMs)
        val pace = targetSrcDur.toFloat() / referenceDur.toFloat() // >1 → ref is faster than ours

        val crops = listOf(CropMode.R16_9, CropMode.R4_5, CropMode.R1_1, CropMode.R9_16)
        val grades = listOf(ColorGrade.NONE, ColorGrade.WARM, ColorGrade.COOL, ColorGrade.VIVID)
        var pos = target.positionMs
        var srcIn = target.inMs
        var idx = 0
        val pieces = mutableListOf<Clip>()
        for ((refStart, refEnd) in scenes) {
            if (pos >= target.outMsEnd()) break
            val sceneLenMs = max(160L, refEnd - refStart)
            val outLen = (sceneLenMs * pace).toLong().coerceAtLeast(240L)
            val srcTake = (outLen * target.speed).toLong().coerceAtLeast(40L)
            val endPos = min(pos + outLen, target.outMsEnd())
            val srcOut = min(srcIn + srcTake, target.outMs).coerceAtLeast(srcIn + 40L)
            val localEnd = ((endPos - pos) / 1000f).coerceAtLeast(0.2f)
            val mapped = target.copy(
                id = ClipIdGen.next(target.id),
                positionMs = pos,
                inMs = srcIn,
                outMs = srcOut,
                crop = if (idx % 3 == 2) crops[idx % crops.size] else CropMode.R16_9,
                color = grades[idx % grades.size],
                transforms = when (idx % 2) {
                    0 -> mapOf(Prop.SCALE.key to MotionTrack(listOf(KeyPoint(0f, 1f), KeyPoint(localEnd, 1.35f))))
                    else -> mapOf(
                        Prop.SCALE.key to MotionTrack(listOf(KeyPoint(0f, 1.3f), KeyPoint(localEnd, 1.0f))),
                        Prop.OPACITY.key to MotionTrack(listOf(
                            KeyPoint(0f, 0f), KeyPoint(0.1f, 1f),
                            KeyPoint(max(0.2f, localEnd - 0.1f), 1f), KeyPoint(max(0.25f, localEnd), 0f)))
                    )
                }
            )
            pieces.add(mapped)
            pos = endPos
            srcIn = min(srcOut, target.outMs - 40L)
            if (srcIn + 100 >= target.outMs) break
            idx++
        }
        store.commit { p -> p.copy(clips = p.clips.filterNot { it.id == target.id } + pieces) }
        store.say("Auto-rhythm matched: ${pieces.size} cuts from ${referenceDur / 1000}s reference")
        return "rebuilt into ${pieces.size} cuts matching the reference"
    }

    fun flashBeat(store: ProjectStore) {
        val targets = store.project.value.videoTrackClips()
        if (targets.isEmpty()) { store.say("no video clips"); return }
        store.commit { p ->
            p.copy(clips = p.clips.map { c ->
                if (c.kind != "video") return@map c
                val endLocal = c.durationMs() / 1000f
                val flashAt = (endLocal - 0.06f).coerceAtLeast(0.02f)
                val bright = MotionTrack(listOf(
                    KeyPoint(0f, 0f), KeyPoint(flashAt - 0.02f, 0f),
                    KeyPoint(flashAt, 0.35f), KeyPoint(max(0.04f, endLocal), 0f)))
                c.copy(transforms = c.transforms + (Prop.BRIGHT.key to bright))
            })
        }
    }

    fun applyGrade(store: ProjectStore, g: ColorGrade) {
        store.commit { p -> p.copy(clips = p.clips.map { if (it.kind == "video") it.copy(color = g) else it }) }
    }

    fun applyCrop(store: ProjectStore, mode: CropMode) {
        store.commit { p -> p.copy(clips = p.clips.map { if (it.kind == "video") it.copy(crop = mode) else it }) }
    }

    fun crossfades(store: ProjectStore, fadeMs: Long = 200) {
        val f = fadeMs / 1000f
        store.commit { p ->
            p.copy(clips = p.clips.map { c ->
                if (c.kind != "video" || c.durationMs() < 2 * fadeMs) return@map c
                val loc = c.durationMs() / 1000f
                c.copy(transforms = c.transforms + (Prop.OPACITY.key to MotionTrack(listOf(
                    KeyPoint(0f, 0f), KeyPoint(f, 1f),
                    KeyPoint(max(f, loc - f), 1f), KeyPoint(loc, 0f)))))
            })
        }
    }

    fun kenBurns(store: ProjectStore, clipId: String, to: Float = 1.8f) {
        val c = store.project.value.find(clipId) ?: return
        val loc = c.durationMs() / 1000f
        val tr = MotionTrack(listOf(KeyPoint(0f, 1f), KeyPoint(max(0.6f, loc), to)))
        store.updateClip(clipId) { it.withProp(Prop.SCALE, tr) }
    }
}

/** Small helpers used across the editor. */
object ClipOps {
    fun splitAt(store: ProjectStore, atMs: Long): Boolean = store.splitClip(store.selection.value ?: "", atMs)

    fun deleteSelected(store: ProjectStore) { store.removeClip(store.selection.value ?: "") }

    fun clearProject(store: ProjectStore) {
        store.commit { p -> p.copy(clips = emptyList()) }
    }
}
