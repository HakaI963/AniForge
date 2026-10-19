package dev.aniforge.engine

import android.content.Context
import dev.aniforge.domain.Clip
import dev.aniforge.domain.ColorGrade
import dev.aniforge.domain.CropMode
import dev.aniforge.domain.EditAnalysis
import dev.aniforge.domain.Editor
import dev.aniforge.domain.KeyPoint
import dev.aniforge.domain.MotionTrack
import dev.aniforge.domain.Project
import dev.aniforge.domain.ProjectStore
import dev.aniforge.domain.Prop
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlin.math.max

/**
 * The app's one-click "auto edit" flows. All deterministic, on-device (no AI/network at this layer).
 */
object AutoEdit {

    /** Rebuild: same cut/durations as a reference video, own footage. */
    suspend fun matchReference(store: ProjectStore, context: Context, refUri: String): String =
        Editor.autoRhythm(store, context, refUri)

    /** Chop at music beats + flash + grade variance. */
    suspend fun shonenBeat(store: ProjectStore, context: Context): String {
        val clips = store.project.value.videoTrackClips()
        if (clips.isEmpty()) return "no video clips"
        var done = 0
        for (c in clips) {
            val r = runCatching { Editor.beatCut(store, c.id, context) }.getOrNull() ?: "?"
            if (r.contains("pieces")) done++
        }
        Editor.flashBeat(store)
        Editor.applyGrade(store, dev.aniforge.domain.ColorGrade.VIVID)
        return "shonen beat edit ready (${done} clip(s))"
    }

    /** Soft, ready-for-social edit: fades, pastel, gentle push-ins. */
    fun softEdit(store: ProjectStore) {
        Editor.crossfades(store)
        Editor.applyGrade(store, dev.aniforge.domain.ColorGrade.PASTEL)
        for (c in store.project.value.videoTrackClips()) Editor.kenBurns(store, c.id, 1.25f)
        store.say("soft edit applied")
    }

    /** 4:5 vertical crop for SNS. */
    fun snsTikTok(store: ProjectStore, mode: CropMode = CropMode.R4_5) {
        Editor.applyCrop(store, mode)
        Editor.crossfades(store)
        store.say("SNS crop + fades")
    }

    suspend fun analyzeReference(context: Context, refUri: String): EditAnalysis? {
        return withContext(Dispatchers.IO) {
            val info = MediaProbe.probe(context, refUri)
            val cuts = MediaProbe.detectCuts(context, refUri)
            val beats = runCatching { MediaProbe.analyzeAudio(context, refUri) }.getOrNull()
            EditAnalysis(cuts, beats?.beatTimesMs ?: emptyList())
        }
    }

    /** Clip-facing helpers used by the inspector (drags call these per tick → patch, no undo spam) */
    private fun patchClip(store: ProjectStore, mut: (Clip) -> Clip) {
        val id = store.selection.value ?: return
        store.patch { p ->
            p.copy(clips = p.clips.map { if (it.id == id) mut(it) else it })
        }
    }

    fun speed(store: ProjectStore, s: Float) = patchClip(store) { it.copy(speed = s.coerceIn(0.25f, 4f)) }

    fun volume(store: ProjectStore, v: Float) = patchClip(store) { it.copy(volume = v.coerceIn(0f, 2f)) }

    fun mute(store: ProjectStore, m: Boolean) {
        val id = store.selection.value ?: return
        store.updateClip(id) { it.copy(muted = m) }
    }

    fun color(store: ProjectStore, g: ColorGrade) {
        val id = store.selection.value ?: return
        store.updateClip(id) { it.copy(color = g) }
    }

    fun crop(store: ProjectStore, c: CropMode) {
        val id = store.selection.value ?: return
        store.updateClip(id) { it.copy(crop = c) }
    }

    fun bright(store: ProjectStore, b: Float) = patchClip(store) { it.copy(brightness = b.coerceIn(-0.6f, 0.6f)) }

    fun contrast(store: ProjectStore, c: Float) = patchClip(store) { it.copy(contrast = c.coerceIn(0.5f, 1.6f)) }

    fun saturation(store: ProjectStore, s: Float) = patchClip(store) { it.copy(saturation = s.coerceIn(0f, 2f)) }

    fun title(store: ProjectStore, text: String) = patchClip(store) { it.copy(title = text.take(48)) }

    /** Position the selected clip exactly after the previous clip on that track. */
    fun snapAfterPrevious(store: ProjectStore) {
        val id = store.selection.value ?: return
        val p = store.project.value
        val sel = p.find(id) ?: return
        val before = p.clips.filter { it.track == sel.track && it.outMsEnd() <= sel.positionMs }
            .maxByOrNull { it.outMsEnd() }
        val t = before?.outMsEnd() ?: 0L
        if (t > 0) store.moveClip(id, t)
    }
}

/** Static keyframing helpers (per clip, used by the motion graph). */
object Keyframing {
    fun tracks(): List<Prop> = listOf(Prop.SCALE, Prop.ROT, Prop.POSX, Prop.POSY,
        Prop.OPACITY, Prop.VOLUME, Prop.BRIGHT)

    fun get(clip: Clip, p: Prop): MotionTrack = clip.transforms[p.key] ?: MotionTrack()

    fun upsert(store: ProjectStore, clipId: String, p: Prop, t: Float, v: Float, ease: String = "smooth") {
        store.updateClip(clipId) { c ->
            val k = (c.transforms[p.key] ?: MotionTrack()).upsert(t, v, ease)
            c.copy(transforms = c.transforms + (p.key to k))
        }
    }

    fun ensureInitial(store: ProjectStore, clipId: String, p: Prop) {
        val c = store.project.value.find(clipId) ?: return
        if (c.transforms[p.key] != null) return
        val dur = c.durationMs() / 1000f
        val k = MotionTrack(listOf(KeyPoint(0f, p.def), KeyPoint(max(0.2f, dur), p.def)))
        store.updateClip(clipId) { cc -> cc.copy(transforms = cc.transforms + (p.key to k)) }
    }
}
