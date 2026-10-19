package dev.aniforge.domain.agent

import android.content.Context
import dev.aniforge.domain.AutoEdit
import dev.aniforge.domain.Clip
import dev.aniforge.domain.ClipIdGen
import dev.aniforge.domain.CropMode
import dev.aniforge.domain.ColorGrade
import dev.aniforge.domain.Editor
import dev.aniforge.domain.ForgeAgent
import dev.aniforge.domain.KeyPoint
import dev.aniforge.domain.Materials
import dev.aniforge.domain.MediaProbe
import dev.aniforge.domain.MotionTrack
import dev.aniforge.domain.Prop
import dev.aniforge.domain.Project
import dev.aniforge.domain.ProjectStore
import dev.aniforge.domain.newProject
import dev.aniforge.engine.Exporter
import dev.aniforge.engine.ExportService
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import java.io.File
import java.net.URLEncoder
import kotlin.math.max
import kotlin.math.min

/**
 * AniForge ChatCut — the agent's tool surface over ONE real editable timeline.
 *
 * Mirrors the OpenChatCut agent architecture: the in-app chat agent drives the
 * same named tools to read/edit the project (tracks, clips, captions, markers,
 * effects, versions, export). Tool names follow OpenChatCut's catalog where the
 * same capability exists on-device; `_af` marks AniForge extensions.
 */
class ToolKit(
    private val context: Context,
    private val store: ProjectStore,
    val exportService: ExportService? = null,
) {
    private val json = Json { ignoreUnknownKeys = true }
    private val pool = LinkedHashMap<String, String>()          // name → uri
    private val versions = ArrayList<Pair<String, Project>>()   // name → snapshot
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    @Volatile private var exportStatus: String? = null

    data class Tool(
        val name: String,
        val desc: String,
        val params: String, // JSON schema for the `parameters` field
        val run: suspend (args: JsonObject) -> String,
        val read: Boolean = false,
    )

    /** Registers a media URI in the pool (the UI importer uses this for named refs like clip1). */
    fun registerInPool(uri: String, name: String? = null): String = regPool(uri, name)

    val poolNames: Set<String> get() = pool.keys

    // ------------------------------------------------------------ helpers
    private fun sopt(o: JsonObject, k: String): String? =
        (o[k] as? kotlinx.serialization.json.JsonPrimitive)?.contentOrNull
    private fun nopt(o: JsonObject, k: String): Long? =
        sopt(o, k)?.toDoubleOrNull()?.toLong()
    private fun fopt(o: JsonObject, k: String): Float? =
        sopt(o, k)?.toFloatOrNull()
    private fun bopt(o: JsonObject, k: String): Boolean? =
        sopt(o, k)?.trim()?.lowercase()?.let { it == "true" || it == "1" || it == "yes" }

    private fun fmt(ms: Long): String {
        val s = ms / 1000f
        return if (s >= 60) "%d:%05.2f".format(s / 60L, s % 60) else "%.2fs".format(s)
    }

    /** Look up a media reference: pool name (case-insensitive / prefix) or a raw path/URL. */
    private fun resolveRef(refRaw: String?): String? {
        val ref = refRaw?.trim().orEmpty()
        if (ref.isEmpty()) return null
        if (ref.startsWith("/") || ref.contains("://")) return ref.removePrefix("file://")
        return pool[ref]
            ?: pool.entries.firstOrNull { it.key.equals(ref, true) || it.key.startsWith(ref, true) }?.value
            ?: pool.entries.firstOrNull { it.value.substringAfterLast('/').contains(ref, true) }?.value
    }

    private fun regPool(uri: String, wantName: String?): String {
        var base = (wantName?.take(24)?.ifBlank { null }
            ?: uri.substringAfterLast('/').substringBefore('?')).ifBlank { "media" }
        var n = base
        var i = 2
        while (pool.containsKey(n)) { n = "$base($i)"; i++ }
        pool[n] = uri
        return n
    }

    private fun target(o: JsonObject): Clip? {
        val id = sopt(o, "itemId") ?: sopt(o, "ref")
        if (!id.isNullOrBlank()) {
            val byId = store.project.value.find(id.trim())
            if (byId != null) return byId
            val byUri = resolveRef(id)?.let { r ->
                store.project.value.clips.firstOrNull { it.sourceUri.contains(r.takeLast(24)) }
            }
            if (byUri != null) return byUri
        }
        return store.selected
            ?: store.project.value.clips.lastOrNull { it.kind == "video" }
            ?: store.project.value.visualClips().lastOrNull()
    }

    private fun gradeOf(s: String?): ColorGrade? {
        val k = s?.trim()?.lowercase() ?: return null
        return ColorGrade.entries.firstOrNull { it.name.lowercase() == k }
            ?: when (k) {
                "b/w", "blackwhite", "mono", "grayscale" -> ColorGrade.NOIR
                "coffee", "amber" -> ColorGrade.WARM
                "cyber" -> ColorGrade.COOL
                else -> null
            }
    }

    private fun cropOf(s: String?): CropMode? {
        val k = s?.trim()?.lowercase() ?: return null
        return when (k) {
            "free", "original" -> CropMode.FREE
            "1:1", "square" -> CropMode.R1_1
            "4:5", "portrait" -> CropMode.R4_5
            "9:16", "vertical", "tiktok" -> CropMode.R9_16
            "16:9", "wide", "landscape" -> CropMode.R16_9
            else -> null
        }
    }

    private fun autoCheckpoint(tag: String) {
        versions.add("$tag ${store.project.value.title.take(12)}" to store.snap())
        if (versions.size > 10) versions.removeAt(0)
    }

    private fun isImageRef(uri: String): Boolean {
        val tail = uri.substringBefore('?').substringAfterLast('/').lowercase()
        return setOf("jpg", "jpeg", "png", "webp", "bmp", "gif").any { tail.endsWith(it) }
    }

    private fun specFor(presetRaw: String?, p: Project): Exporter.Spec {
        val preset = presetRaw?.trim()?.lowercase().orEmpty()
        val vertical = p.height > p.width
        return when {
            preset.startsWith("1080") ->
                if (vertical) Exporter.Spec(1080, 1920, 30, 16_000_000) else Exporter.P1080
            preset.startsWith("480") ->
                if (vertical) Exporter.Spec(480, 854, 30, 3_000_000) else Exporter.P480
            preset == "9:16" || preset == "vertical" -> Exporter.Spec(720, 1280, 30, 6_000_000)
            preset == "1:1" || preset == "4:5" || preset == "square" -> Exporter.Spec(900, 900, 30, 5_000_000)
            preset == "16:9" -> Exporter.P720
            else -> if (vertical) Exporter.Spec(720, 1280, 30, 6_000_000) else Exporter.P720
        }
    }

    // ------------------------------------------------------------ tools
    val tools: List<Tool> = listOf(

        // ── read / ask ───────────────────────────────────────────────────
        Tool(
            "read_timeline",
            "Read the timeline: canvas, duration, every clip (id, track, type, start/in/out, speed, grade, crop, title, fx) and music/markers.",
            """{"type":"object","properties":{},"required":[]}""",
            read = true,
        ) {
            val p = store.project.value
            if (p.clips.isEmpty()) return@Tool "timeline is empty"
            val sb = StringBuilder()
            sb.append("canvas=${p.width}x${p.height} duration=${fmt(p.durationMs())} clips=${p.clips.size}")
            for (c in p.visualClips().take(80)) {
                val trk = if (c.kind == "image") "G" else "V${c.track + 1}"
                sb.append("\n#${c.id} $trk ${c.kind} start=${fmt(c.positionMs)} in=${fmt(c.inMs)} out=${fmt(c.outMs)}")
                sb.append(" speed=${c.speed} vol=${c.volume} grade=${c.color.name.lowercase()} crop=${c.crop.label}")
                if (c.muted) sb.append(" muted")
                if (c.title.isNotBlank()) sb.append(" title=\"${c.title.take(32)}\"")
                if (c.transforms.isNotEmpty()) sb.append(" fx=${c.transforms.keys}")
            }
            for (m in p.musicClips().take(8)) {
                sb.append("\n#${m.id} M audio start=${fmt(m.positionMs)} in=${fmt(m.inMs)} out=${fmt(m.outMs)} vol=${m.volume}")
            }
            for (mk in p.sortedMarkers().take(14)) {
                sb.append("\nmarker ${fmt(mk.tMs)} \"${mk.note.take(28)}\"")
            }
            sb.toString()
        },

        Tool(
            "read_project",
            "Read project-level state: title, media pool contents, saved version checkpoints.",
            """{"type":"object","properties":{},"required":[]}""",
            read = true,
        ) {
            val p = store.project.value
            "project=\"${p.title}\" media pool=[${pool.keys.joinToString(", ")}] versions=[${versions.withIndex().joinToString(", ") { (i, v) -> "${i}:${v.first}" }}]"
        },

        Tool(
            "probe_media",
            "Probe a media file (duration, size, streams). ref = pool name, file path, or URL.",
            """{"type":"object","properties":{"ref":{"type":"string"}},"required":["ref"]}""",
        ) {
            val uri = resolveRef(sopt(it, "ref"))
                ?: return@Tool "unknown media '${sopt(it, "ref")}' — add it first (download_media / import)"
            val info = MediaProbe.probe(context, uri) ?: return@Tool "probe failed (unreadable)"
            "{\"ref\":\"${sopt(it, "ref")}\",\"durMs\":${info.durationMs},\"w\":${info.width},\"h\":${info.height},\"video\":${info.hasVideo},\"audio\":${info.hasAudio}}"
        },

        Tool(
            "detect_scenes",
            "Detect visual scene cuts of a source video (on-device histogram analysis).",
            """{"type":"object","properties":{"ref":{"type":"string"}},"required":["ref"]}""",
        ) {
            val uri = resolveRef(sopt(it, "ref")) ?: return@Tool "unknown media"
            val cuts = runCatching { MediaProbe.detectCuts(context, uri) }.getOrDefault(emptyList())
            val body = cuts.take(60).joinToString(",") { "${it / 1000.0}" }
            "{\"count\":${cuts.size},\"cutsMs\":[$body]}"
        },

        Tool(
            "detect_beats",
            "Detect music beats + bpm from a media's audio (on-device loudness analysis).",
            """{"type":"object","properties":{"ref":{"type":"string"}},"required":["ref"]}""",
        ) {
            val uri = resolveRef(sopt(it, "ref")) ?: return@Tool "unknown media"
            val a = runCatching { MediaProbe.analyzeAudio(context, uri) }.getOrNull()
            val beats = a?.beatTimesMs ?: emptyList()
            if (beats.isEmpty()) return@Tool "{\"bpm\":0,\"beats\":[]}"
            val gaps = beats.zipWithNext().map { (x, y) -> y - x }.sorted()
            val median = gaps.getOrNull(gaps.size / 2) ?: 500L
            val bpm = if (median > 0) (60000.0 / median).toInt() else 0
            "{\"bpm\":$bpm,\"beatCount\":${beats.size},\"beats\":[${beats.take(60).joinToString(",")}...]}"
        },

        Tool(
            "read_transcript",
            "Read a spoken transcript. NOTE: speech recognition is not available on-device in this build — returns a hint instead.",
            """{"type":"object","properties":{},"required":[]}""",
            read = true,
        ) {
            "{\"note\":\"no ASR on device. Use detect_scenes + manage_captions for timed text instead.\"}"
        },

        // ── media pool ───────────────────────────────────────────────────
        Tool(
            "search_stock_media",
            "Search open-license stock media (Openverse, keyless). kind=audio|image.",
            """{"type":"object","properties":{"kind":{"type":"string","enum":["audio","image"]},"query":{"type":"string"}},"required":["query"]}""",
        ) {
            val q = sopt(it, "query").orEmpty()
            val kind = sopt(it, "kind") ?: "audio"
            if (q.isBlank()) return@Tool "query required"
            if (kind == "image") {
                val hits = runCatching { Materials.searchImages(q) }.getOrDefault(emptyList())
                if (hits.isEmpty()) return@Tool "(no results)"
                hits.take(8).joinToString("\n") { h ->
                    "\"${h.title.take(40)}\" ${h.license} ${h.width}x${h.height} download via download_media url=${h.url.take(110)}"
                }
            } else {
                val hits = runCatching { Materials.searchMusic(q) }.getOrDefault(emptyList())
                if (hits.isEmpty()) return@Tool "(no results)"
                hits.take(8).joinToString("\n") { h ->
                    "\"${h.title.take(40)}\" ${h.license} ${h.durationMs / 1000}s download via download_media url=${h.url.take(110)}"
                }
            }
        },

        Tool(
            "download_media",
            "Download a public http(s) media file into the media pool (becomes an addable ref).",
            """{"type":"object","properties":{"url":{"type":"string"},"name":{"type":"string"}},"required":["url"]}""",
        ) {
            val url = sopt(it, "url").orEmpty()
            if (!url.startsWith("http")) return@Tool "url must be http(s)"
            val f = Materials.download(context, url, "asset-${System.currentTimeMillis()}." +
                url.substringBefore('?').substringAfterLast('.').take(4).ifBlank { "bin" })
            val name = regPool(f.absolutePath, sopt(it, "name"))
            "{\"added\":\"$name\",\"bytes\":${f.length()}}"
        },

        Tool(
            "manage_media_pool",
            "List/remove/clear the media pool. Imported project videos appear here under names like clip1.",
            """{"type":"object","properties":{"action":{"type":"string","enum":["list","remove","clear"]},"ref":{"type":"string"}},"required":["action"]}""",
        ) {
            when (sopt(it, "action")) {
                "clear" -> { pool.clear(); "pool cleared" }
                "remove" -> {
                    val r = sopt(it, "ref").orEmpty()
                    pool.entries.removeAll { it.key.equals(r, true) || it.key.startsWith(r, true) }
                    "removed (if present)"
                }
                else -> pool.entries.joinToString("\n") { (k, v) -> "$k → $v" }.ifEmpty { "(pool empty)" }
            }
        },

        // ── building the timeline ───────────────────────────────────────
        Tool(
            "add_clip",
            "Add a video or image to the timeline. ref = pool name/URL/path. track 1|2, startSec optional (default: end of that track).",
            """{"type":"object","properties":{"ref":{"type":"string"},"track":{"type":"integer"},"startSec":{"type":"number"}},"required":["ref"]}""",
        ) {
            val ref = sopt(it, "ref").orEmpty()
            val uri = resolveRef(ref) ?: return@Tool "unknown media '$ref' (add via download_media / generate_image first)"
            val trackIdx = ((nopt(it, "track") ?: 1L).toInt().coerceIn(1, 2)) - 1
            val existing = store.project.value.clips.filter { it.track == trackIdx && it.kind != "audio" }
            val pos = nopt(it, "startSec")?.times(1000)?.coerceAtLeast(0)
                ?: (existing.maxOfOrNull { it.outMsEnd() } ?: 0L)

            val info = MediaProbe.probe(context, uri)
            val clip = when {
                isImageRef(uri) || (info == null) || (!info.hasVideo && info.durationMs <= 0L) -> {
                    Clip(id = ClipIdGen.next("img"), track = 0, kind = "image", sourceUri = uri,
                        srcDurationMs = 2500, srcW = info?.width?.takeIf { v -> v > 0 } ?: 1280,
                        srcH = info?.height?.takeIf { v -> v > 0 } ?: 720,
                        positionMs = pos, inMs = 0, outMs = 2500)
                }
                else -> Clip(id = ClipIdGen.next("c"), track = trackIdx, kind = "video", sourceUri = uri,
                    srcDurationMs = info.durationMs, srcW = info.width, srcH = info.height,
                    positionMs = pos, inMs = 0, outMs = info.durationMs.coerceAtLeast(200))
            }
            store.addClip(clip)
            autoCheckpoint("add")
            "added clip ${clip.id} (${clip.kind}, ${fmt(clip.durationMs())}) at ${fmt(clip.positionMs)}"
        },

        Tool(
            "add_audio",
            "Add music/SFX to track M. ref = pool name, file path, URL — or a search query (auto-fetches an open-license track).",
            """{"type":"object","properties":{"ref":{"type":"string"},"startSec":{"type":"number"}},"required":["ref"]}""",
        ) {
            val ref = sopt(it, "ref").orEmpty()
            var uri = resolveRef(ref)
            if (uri == null) {
                val got = runCatching { Materials.fetchMusic(context, ref) }.getOrNull()
                    ?: return@Tool "no audio resolved for \"$ref\" (online search failed — try exact URLs or the import button)"
                uri = got.path
                regPool(uri, got.title.take(24))
            }
            val info = MediaProbe.probe(context, uri) ?: return@Tool "unreadable audio"
            val pos = nopt(it, "startSec")?.times(1000)?.coerceAtLeast(0)
                ?: (store.project.value.clips.filter { it.track == 2 }.maxOfOrNull { it.outMsEnd() } ?: 0L)
            val dur = info.durationMs.coerceAtLeast(1000)
            store.addClip(Clip(id = ClipIdGen.next("mus"), track = 2, kind = "audio", sourceUri = uri,
                srcDurationMs = dur, positionMs = pos, inMs = 0, outMs = dur))
            autoCheckpoint("add_audio")
            "audio added (${fmt(dur)} at ${fmt(pos)})"
        },

        Tool(
            "generate_image_af",
            "Generate an image from text (keyless AI). Prompt ~60 chars. Optionally places it as a title card at startSec.",
            """{"type":"object","properties":{"prompt":{"type":"string"},"startSec":{"type":"number"}},"required":["prompt"]}""",
        ) {
            val q = sopt(it, "prompt").orEmpty()
            if (q.isBlank()) return@Tool "prompt required"
            val url = "https://image.pollinations.ai/prompt/${URLEncoder.encode(q.take(100), "UTF-8")}" +
                "?width=1280&height=720&nologo=true&seed=${(0..999).random()}"
            val f = Materials.download(context, url, "aiimg-${System.currentTimeMillis()}.jpg")
            val name = regPool(f.absolutePath, q.take(14))
            val start = nopt(it, "startSec")
            if (start != null) {
                val pos = (start * 1000).coerceAtLeast(0)
                store.addClip(Clip(id = ClipIdGen.next("card"), track = 0, kind = "image", sourceUri = f.absolutePath,
                    srcDurationMs = 2500, srcW = 1280, srcH = 720, positionMs = pos, inMs = 0, outMs = 2500))
                autoCheckpoint("image")
                "generated \"$name\" and placed a card at ${fmt(pos)} (use edit_item to put a title on it)"
            } else "generated \"$name\" — in the pool, add via add_clip ref=$name"
        },

        // ── clips editing ───────────────────────────────────────────────
        Tool(
            "edit_item",
            "Edit one item: props.speed (0.25-4), props.volume, props.grade (none/warm/cool/noir/pastel/vivid), props.crop (free/1:1/4:5/9:16/16:9), props.title, props.titleColor (#RRGGBB), props.brightness, props.contrast, props.saturation, props.muted. itemId default = last visual clip.",
            """{"type":"object","properties":{"itemId":{"type":"string"},"props":{"type":"object"}},"required":["props"]}""",
        ) {
            val c = target(it) ?: return@Tool "no item on the timeline yet"
            // props may be nested {props:{...}} or flat on the call
            val props = (it["props"] as? JsonObject) ?: it
            store.updateClip(c.id) { k ->
                val crop = cropOf(sopt(props, "crop")) ?: k.crop
                val grade = gradeOf(sopt(props, "grade")) ?: k.color
                k.copy(
                    speed = fopt(props, "speed")?.coerceIn(0.25f, 4f) ?: k.speed,
                    volume = fopt(props, "volume")?.coerceIn(0f, 2f) ?: k.volume,
                    muted = bopt(props, "muted") ?: k.muted,
                    crop = crop, color = grade,
                    title = sopt(props, "title")?.take(48) ?: k.title,
                    titleColor = sopt(props, "titleColor")?.take(9)?.let {
                        if (Regex("^#[0-9a-fA-F]{6}$").matches(it)) it else k.titleColor
                    } ?: k.titleColor,
                    titleSize = fopt(props, "titleSize")?.coerceIn(0.03f, 0.30f) ?: k.titleSize,
                    brightness = fopt(props, "brightness")?.coerceIn(-0.6f, 0.6f) ?: k.brightness,
                    contrast = fopt(props, "contrast")?.coerceIn(0.5f, 1.8f) ?: k.contrast,
                    saturation = fopt(props, "saturation")?.coerceIn(0f, 2.2f) ?: k.saturation,
                )
            }
            autoCheckpoint("edit")
            "edited ${c.id}"
        },

        Tool(
            "set_item_timing",
            "Retime one item: inSec/outSec (source range), startSec (timeline), fadeInMs/fadeOutMs (opacity ramp, cap 1500ms).",
            """{"type":"object","properties":{"itemId":{"type":"string"},"inSec":{"type":"number"},"outSec":{"type":"number"},"startSec":{"type":"number"},"fadeInMs":{"type":"integer"},"fadeOutMs":{"type":"integer"}}}""",
        ) {
            val c = target(it) ?: return@Tool "no item"
            val srcDur = c.srcDurationMs
            val newIn = (fopt(it, "inSec")?.times(1000)?.toLong() ?: c.inMs).coerceIn(0, max(0, srcDur - 100))
            val newOutRaw = fopt(it, "outSec")?.times(1000)?.toLong() ?: c.outMs
            val newOut = newOutRaw.coerceIn(newIn + 100, max(newIn + 100, srcDur))
            val newPos = fopt(it, "startSec")?.times(1000)?.toLong()?.coerceAtLeast(0) ?: c.positionMs

            val fadeIn = nopt(it, "fadeInMs")?.coerceIn(0, 1500)
            val fadeOut = nopt(it, "fadeOutMs")?.coerceIn(0, 1500)
            val outSecLocal = max(0.2f, ((newOut - newIn) / c.speed) / 1000f)
            var tracks = c.transforms
            if (fadeIn != null) {
                val fi = (fadeIn / 1000f).coerceAtMost(outSecLocal * 0.9f).coerceAtLeast(0.05f)
                tracks = (tracks[Prop.OPACITY.key] ?: MotionTrack())
                    .upsert(0f, 0f).upsert(fi, 1f)
                    .let { merged -> tracks + (Prop.OPACITY.key to merged) }
            }
            if (fadeOut != null) {
                val fo = (fadeOut / 1000f).coerceAtMost(outSecLocal * 0.9f).coerceAtLeast(0.05f)
                tracks = (tracks[Prop.OPACITY.key] ?: MotionTrack())
                    .upsert(outSecLocal - fo, 1f).upsert(outSecLocal, 0f)
                    .let { merged -> tracks + (Prop.OPACITY.key to merged) }
            }
            store.updateClip(c.id) { k -> k.copy(inMs = newIn, outMs = newOut, positionMs = newPos, transforms = tracks) }
            autoCheckpoint("timing")
            "retimed ${c.id}: in=${fmt(newIn)} out=${fmt(newOut)} at=${fmt(newPos)}"
        },

        Tool(
            "split_item",
            "Split a clip into two at a timeline position. atSec is absolute on the timeline (or 'playhead' for the current playhead).",
            """{"type":"object","properties":{"itemId":{"type":"string"},"atSec":{"type":"number"}},"required":[]}""",
        ) {
            val c = target(it) ?: return@Tool "no item"
            val at = sopt(it, "atSec")?.lowercase()?.let { s ->
                if (s.contains("playhead") || s.contains("current")) store.playheadMs.value
                else s.toDoubleOrNull()?.times(1000)?.toLong()
            } ?: store.playheadMs.value
            val ok = store.splitClip(c.id, at)
            if (ok) { autoCheckpoint("split"); "split ${c.id} at ${fmt(at)}" }
            else "split point too close to an edge (needs >100ms on both sides)"
        },

        Tool(
            "move_item",
            "Move a clip: startSec (timeline position) and/or track (1|2 video, 3 = music).",
            """{"type":"object","properties":{"itemId":{"type":"string"},"startSec":{"type":"number"},"track":{"type":"integer"}}}""",
        ) {
            val c = target(it) ?: return@Tool "no item"
            val pos = fopt(it, "startSec")?.times(1000)?.toLong()?.coerceAtLeast(0) ?: c.positionMs
            val wantTrack = nopt(it, "track")
            val newTrack: Int = if (wantTrack == null) c.track
            else if (c.kind == "audio") 2
            else (wantTrack.toInt() - 1).coerceIn(0, 1)
            store.moveClip(c.id, pos, newTrack)
            autoCheckpoint("move")
            "moved ${c.id} → start=${fmt(pos)} on ${if (c.kind == "audio") "M" else "V${newTrack + 1}"}"
        },

        Tool(
            "remove_item",
            "Delete a clip. ripple=true also closes the gap left behind on that track.",
            """{"type":"object","properties":{"itemId":{"type":"string"},"ripple":{"type":"boolean"}}}""",
        ) {
            val c = target(it) ?: return@Tool "no item"
            val dur = c.durationMs()
            val end = c.outMsEnd()
            val track = c.track
            if (bopt(it, "ripple") == true) {
                store.commit { p ->
                    p.copy(clips = p.clips
                        .map {
                            if (it.track == track && it.positionMs >= end)
                                it.copy(positionMs = (it.positionMs - dur).coerceAtLeast(0)) else it
                        }
                        .filterNot { it.id == c.id })
                }
            } else {
                store.removeClip(c.id)
            }
            autoCheckpoint("remove")
            "removed ${c.id}${if (bopt(it, "ripple") == true) " (gap closed)" else ""}"
        },

        Tool(
            "duplicate_item",
            "Duplicate a clip (copy lands right after the original).",
            """{"type":"object","properties":{"itemId":{"type":"string"}}}""",
        ) {
            val c = target(it) ?: return@Tool "no item"
            store.duplicate(c.id)
            autoCheckpoint("dup")
            "duplicated ${c.id}"
        },

        Tool(
            "clear_timeline",
            "Remove ALL clips and start over (undoable).",
            """{"type":"object","properties":{},"required":[]}""",
        ) {
            autoCheckpoint("clear")
            store.commit { p -> p.copy(clips = emptyList()) }
            "timeline cleared (${versions.size} checkpoints kept — undo_last_change restores)"
        },

        Tool(
            "apply_effect",
            "Named effect on one item (itemId) or all visual clips (itemId=\"all\"): pushin, zoomout, kenburns, flash, crossfade, clearfx.",
            """{"type":"object","properties":{"effect":{"type":"string"},"itemId":{"type":"string"}},"required":["effect"]}""",
        ) {
            val eff = sopt(it, "effect").orEmpty().lowercase().trim()
            val ids = if (sopt(it, "itemId").equals("all", true) || eff == "crossfade")
                store.project.value.visualClips().map { v -> v.id }
            else listOf(target(it)?.id ?: return@Tool "no item")
            if (ids.isEmpty()) return@Tool "no visual clips"
            for (id in ids) {
                val c = store.project.value.find(id) ?: continue
                val dLocal = max(0.2f, c.durationMs() / 1000f)
                store.updateClip(id) { k ->
                    when (eff) {
                        "pushin" -> k.withProp(Prop.SCALE, MotionTrack(listOf(KeyPoint(0f, 1f), KeyPoint(dLocal, 1.30f))))
                        "zoomout" -> k.withProp(Prop.SCALE, MotionTrack(listOf(KeyPoint(0f, 1.35f), KeyPoint(dLocal.coerceAtLeast(0.5f), 1f))))
                        "kenburns" -> k.withProp(Prop.SCALE, MotionTrack(listOf(KeyPoint(0f, 1.05f), KeyPoint(dLocal, 1.7f))))
                        "flash" -> {
                            val at = (dLocal - 0.07f).coerceAtLeast(0.02f)
                            k.withProp(Prop.BRIGHT, MotionTrack(listOf(
                                KeyPoint(0f, 0f), KeyPoint((at - 0.02f).coerceAtLeast(0f), 0f),
                                KeyPoint(at, 0.35f), KeyPoint(max(0.05f, dLocal), 0f))))
                        }
                        "crossfade" -> k.withProp(Prop.OPACITY, MotionTrack(listOf(
                            KeyPoint(0f, 0f), KeyPoint(0.25f, 1f),
                            KeyPoint(max(0.3f, dLocal - 0.25f), 1f), KeyPoint(dLocal, 0f))))
                        "clearfx", "reset" -> k.copy(transforms = emptyMap())
                        else -> k
                    }
                }
            }
            autoCheckpoint("fx")
            if (eff !in setOf("pushin", "zoomout", "kenburns", "flash", "crossfade", "clearfx", "reset"))
                "unknown effect '$eff' (try pushin/zoomout/kenburns/flash/crossfade/clearfx)"
            else "effect $eff on ${ids.size} item(s)"
        },

        Tool(
            "set_aspect_ratio",
            "Set the canvas: ratio = 16:9 | 9:16 | 4:5 | 1:1. All visual clips are re-cropped to fill.",
            """{"type":"object","properties":{"ratio":{"type":"string","enum":["16:9","9:16","4:5","1:1"]}},"required":["ratio"]}""",
        ) {
            val (w, h, cm) = when (sopt(it, "ratio")) {
                "9:16" -> Triple(1080, 1920, CropMode.R9_16)
                "4:5" -> Triple(1080, 1350, CropMode.R4_5)
                "1:1" -> Triple(1080, 1080, CropMode.R1_1)
                else -> Triple(1920, 1080, CropMode.R16_9)
            }
            autoCheckpoint("aspect")
            store.commit { p ->
                p.copy(width = w, height = h, clips = p.clips.map {
                    if (it.kind == "audio") it else it.copy(crop = cm)
                })
            }
            "canvas ${w}x${h} (${"%g".format(w.toFloat() / h)}), all clips crop=${cm.label}"
        },

        // ── captions / markers ──────────────────────────────────────────
        Tool(
            "manage_captions",
            "Write caption text onto the visual clip active at each time. lines=[{t:\"12s\"|startSec:12, text}]. action=clear strips all.",
            """{"type":"object","properties":{"action":{"type":"string","enum":["set","clear"]},"color":{"type":"string"},"size":{"type":"number"},"lines":{"type":"array","items":{"type":"object","properties":{"t":{"type":"string"},"startSec":{"type":"number"},"text":{"type":"string"}}}}},"required":["action"]}""",
        ) {
            val action = sopt(it, "action") ?: "set"
            if (action == "clear") {
                autoCheckpoint("captions")
                store.commit { p -> p.copy(clips = p.clips.map { it.copy(title = "") }) }
                return@Tool "captions cleared"
            }
            val lines = it["lines"]?.jsonArray ?: return@Tool "lines required"
            val color = sopt(it, "color")?.takeIf { c -> Regex("^#[0-9a-fA-F]{6}$").matches(c) }
            val size = fopt(it, "size")?.coerceIn(0.03f, 0.30f)
            val spec = lines.mapNotNull { el ->
                val o = el.jsonObject
                val t = (o["t"] as? kotlinx.serialization.json.JsonPrimitive)?.contentOrNull
                    ?.toDoubleOrNull()?.times(1000)?.toLong()
                    ?: (o["startSec"] as? kotlinx.serialization.json.JsonPrimitive)?.contentOrNull
                        ?.toDoubleOrNull()?.times(1000)?.toLong()
                val text = (o["text"] as? kotlinx.serialization.json.JsonPrimitive)?.contentOrNull?.take(40)
                if (t != null && !text.isNullOrBlank()) t to text else null
            }
            if (spec.isEmpty()) return@Tool "no valid lines (each needs t or startSec + text)"
            var n = 0
            for ((t, text) in spec) {
                val targetClip = store.project.value.visualClips().firstOrNull { it.contains(t) }
                if (targetClip == null) continue
                store.updateClip(targetClip.id) { k ->
                    k.copy(title = text, titleColor = color ?: k.titleColor, titleSize = size ?: k.titleSize.coerceIn(0.045f, 0.075f))
                }
                n++
            }
            autoCheckpoint("captions")
            "$n of ${spec.size} caption(s) written (one per clip active at its time)"
        },

        Tool(
            "manage_markers",
            "Timeline markers: action=list|add|clear; add needs tSec + note. Markers show on the timeline.",
            """{"type":"object","properties":{"action":{"type":"string","enum":["list","add","clear"]},"tSec":{"type":"number"},"note":{"type":"string"}},"required":["action"]}""",
        ) {
            when (sopt(it, "action")) {
                "add" -> {
                    val t = fopt(it, "tSec")?.times(1000)?.toLong() ?: store.playheadMs.value
                    val note = sopt(it, "note")?.take(32) ?: "flag"
                    store.commit { p -> p.copy(markers = p.markers + dev.aniforge.domain.Marker(t, note)) }
                    "marker at ${fmt(t)} \"$note\""
                }
                "clear" -> { store.commit { p -> p.copy(markers = emptyList()) }; "markers cleared" }
                else -> store.project.value.sortedMarkers()
                    .joinToString("\n") { "${fmt(it.tMs)} \"${it.note}\"" }
                    .ifEmpty { "(no markers)" }
            }
        },

        // ── smart ops ───────────────────────────────────────────────────
        Tool(
            "auto_grade",
            "Technical color correction preset applied to every visual clip: none|warm|cool|noir|pastel|vivid.",
            """{"type":"object","properties":{"grade":{"type":"string","enum":["none","warm","cool","noir","pastel","vivid"]}},"required":["grade"]}""",
        ) {
            val g = gradeOf(sopt(it, "grade")) ?: return@Tool "unknown grade"
            autoCheckpoint("grade")
            store.commit { p -> p.copy(clips = p.clips.map { if (it.kind == "audio") it else it.copy(color = g) }) }
            "grade ${g.name.lowercase()} on all visual clips"
        },

        Tool(
            "sync_cuts_af",
            "Chop the selected clip into beat-aligned pieces (rhythm editing) using detected beats of its own audio or a given music ref.",
            """{"type":"object","properties":{"itemId":{"type":"string"},"ref":{"type":"string"}}}""",
        ) {
            val c = target(it) ?: return@Tool "no clip"
            val ref = sopt(it, "ref")
            val srcUri = if (ref != null) resolveRef(ref) ?: c.sourceUri else c.sourceUri
            val a = runCatching { MediaProbe.analyzeAudio(context, srcUri) }.getOrNull()
            // beats come in source time of the audio file; when the audio IS the clip this maps 1:1
            val beats = a?.beatTimesMs?.filter { it >= c.inMs + 200 && it <= c.outMs - 200 } ?: emptyList()
            if (beats.size < 2) return@Tool "not enough beats found in this clip's range"
            val bounds = listOf(c.inMs) + beats + listOf(c.outMs)
            val pieces = mutableListOf<Clip>()
            var i = 0
            while (i < bounds.size - 1) {
                val s = bounds[i]; val e = bounds[i + 1]
                if (e - s >= 150) {
                    pieces.add(c.copy(id = ClipIdGen.next(c.id), inMs = s, outMs = e,
                        positionMs = c.positionMs + (s - c.inMs)))
                }
                i++
            }
            if (pieces.size < 2) return@Tool "not enough usable beats in range"
            autoCheckpoint("beats")
            store.commit { p -> p.copy(clips = p.clips.filterNot { it.id == c.id } + pieces) }
            "cut into ${pieces.size} beat-aligned pieces"
        },

        Tool(
            "remove_silence_af",
            "Remove quiet stretches (> minGapSec between detected beats) from one clip's range and close the gaps.",
            """{"type":"object","properties":{"itemId":{"type":"string"},"minGapSec":{"type":"number"}}}""",
        ) {
            val c = target(it) ?: return@Tool "no clip"
            val gapMs = ((fopt(it, "minGapSec") ?: 1.2f) * 1000).toLong().coerceIn(300, 10_000)
            val a = runCatching { MediaProbe.analyzeAudio(context, c.sourceUri) }.getOrNull()
            val beats = a?.beatTimesMs ?: emptyList()
            if (beats.size < 2) return@Tool "audio analysis found no beats — can't locate quiet spans"
            // quiet gaps between beats inside the clip's source range
            val inRange = beats.filter { it > c.inMs && it < c.outMs }
            if (inRange.size < 2) return@Tool "clip range has <2 beats"
            var start = c.inMs
            val loud = mutableListOf<Pair<Long, Long>>() // (srcIn, srcOut)
            var idxIn = 0
            while (idxIn < inRange.size) {
                val b = inRange[idxIn]
                val nxt = inRange.getOrNull(idxIn + 1)
                if (nxt == null || nxt - b >= gapMs) {
                    val segOut = nxt?.minus(gapMs / 2)?.coerceAtLeast(b + 100) ?: c.outMs
                    loud.add(start to min(segOut, c.out))
                    if (nxt == null) break
                    start = nxt - gapMs / 4
                }
                idxIn++
            }
            if (start < c.outMs) loud.add(start to c.outMs)
            val merged = loud.filter { (s, e) -> e - s >= 200 }.distinct()
            if (merged.size <= 1) return@Tool "no removable quiet span ≥ ${gapMs}ms"
            autoCheckpoint("desilence")
            var pos = c.positionMs
            val pieces = merged.filter { (s, e) -> e > s + 200 && s < c.outMs }.map { (s, e) ->
                val piece = c.copy(id = ClipIdGen.next(c.id), inMs = s.coerceIn(0, c.outMs),
                    outMs = min(e, c.outMs), positionMs = pos)
                pos += piece.durationMs()
                piece
            }
            if (pieces.size < 2) return@Tool "nothing to remove"
            store.commit { p -> p.copy(clips = p.clips.filterNot { it.id == c.id } + pieces) }
            "removed quiet spans → ${pieces.size} tight pieces (saved ${(c.durationMs() - pieces.sumOf { it.durationMs() }) / 1000}s)"
        },

        Tool(
            "find_highlights_af",
            "Build a highlights cut: top windows of the source by beat density become tight clips on the timeline.",
            """{"type":"object","properties":{"itemId":{"type":"string"},"clipSec":{"type":"number"},"count":{"type":"integer"}}}""",
        ) {
            val c = target(it) ?: return@Tool "no clip"
            val winMs = ((fopt(it, "clipSec") ?: 2.5f) * 1000).toLong().coerceIn(800, 8000)
            val count = (nopt(it, "count") ?: 6L).toInt().coerceIn(2, 12)
            val a = runCatching { MediaProbe.analyzeAudio(context, c.sourceUri) }.getOrNull()
            val beats = (a?.beatTimesMs ?: emptyList()).filter { it >= c.inMs && it < c.outMs }
            if (beats.size < count) return@Tool "not enough beats for $count highlights"
            val scored = beats.map { b -> b to beats.count { it in b..(b + winMs) } }
                .sortedByDescending { it.second }
            val chosen = mutableListOf<Long>()
            for ((b, _) in scored) {
                if (chosen.size >= count) break
                if (chosen.none { kotlin.math.abs(it - b) < winMs }) chosen.add(b)
            }
            if (chosen.size < 2) return@Tool "couldn't pick distinct highlights"
            autoCheckpoint("highlights")
            var pos = c.positionMs
            val pieces = chosen.sorted().map { b ->
                val s = b.coerceIn(0, max(0L, c.outMs - 600))
                val e = (s + min(winMs, c.outMs - s)).coerceIn(s + 200, c.outMs)
                val piece = c.copy(id = ClipIdGen.next("hl"), inMs = s, outMs = e, positionMs = pos)
                pos += piece.durationMs()
                piece
            }
            store.commit { p -> p.copy(clips = p.clips.filterNot { it.id == c.id } + pieces) }
            "${pieces.size}-shot highlight cut built"
        },

        Tool(
            "match_edit_af",
            "Copy the timing/cut rhythm of a reference video onto it as your own edit (same cut feel, your footage). ref = pool name of the reference video.",
            """{"type":"object","properties":{"ref":{"type":"string"}},"required":["ref"]}""",
        ) {
            val uri = resolveRef(sopt(it, "ref")) ?: return@Tool "unknown reference (add the reference video to the pool first)"
            val msg = AutoEdit.matchReference(store, context, uri)
            autoCheckpoint("match")
            msg
        },

        Tool(
            "auto_edit_af",
            "One-shot full auto edit from a prompt: rebuilds the whole timeline (intro card, cuts, speed waves, grades, outro, music). Destructive but undoable.",
            """{"type":"object","properties":{"prompt":{"type":"string"},"style":{"type":"string","enum":["fast","chill","trailer","sns"]}},"required":["prompt"]}""",
        ) {
            val prompt = sopt(it, "prompt").orEmpty()
            if (prompt.isBlank()) return@Tool "prompt required"
            autoCheckpoint("auto_edit")
            val style = sopt(it, "style")?.let { sid -> dev.aniforge.domain.EditStyle.entries.firstOrNull { e -> e.id == sid } }
            val agent = ForgeAgent(context, store)
            val log = StringBuilder()
            val r = agent.run(prompt, style, null, useMusic = true, useMaterials = true) { step -> log.append(step).append(" | ") }
            if (r == "done") "auto edit done: $log"
            else "auto edit failed: $r"
        },

        // ── history / versions ──────────────────────────────────────────
        Tool(
            "undo_last_change",
            "Revert the last applied change (full undo stack available).",
            """{"type":"object","properties":{},"required":[]}""",
        ) {
            store.undo(); "undone (timeline back to ${fmt(store.project.value.durationMs())})"
        },

        Tool(
            "redo_last_change",
            "Re-apply the change that was undone.",
            """{"type":"object","properties":{},"required":[]}""",
        ) {
            store.redo(); "redone"
        },

        Tool(
            "manage_versions",
            "Checkpoint snapshots: action=list|save|restore; restore needs index (latest = -1 or its index).",
            """{"type":"object","properties":{"action":{"type":"string","enum":["list","save","restore"]},"name":{"type":"string"},"index":{"type":"integer"}},"required":["action"]}""",
        ) {
            when (sopt(it, "action")) {
                "save" -> {
                    val name = (sopt(it, "name") ?: "manual").take(18)
                    versions.add(name to store.snap())
                    if (versions.size > 10) versions.removeAt(0)
                    "checkpoint \"$name\" saved (#${versions.lastIndex})"
                }
                "restore" -> {
                    val idx = (nopt(it, "index") ?: versions.lastIndex.toLong()).toInt()
                    if (idx == -1 && versions.isNotEmpty()) {
                        val v = versions.removeAt(versions.lastIndex)
                        store.commit { _ -> v.second }
                        "restored \"${v.first}\""
                    } else {
                        val v = versions.getOrNull(idx) ?: return@Tool "no checkpoint #$idx"
                        store.commit { _ -> v.second }
                        "restored \"${v.first}\""
                    }
                }
                else -> versions.withIndex().joinToString("\n") { (i, v) ->
                    "#$i \"${v.first}\" ${v.second.clips.size} clips"
                }.ifEmpty { "(no checkpoints)" }
            }
        },

        // ── projects ────────────────────────────────────────────────────
        Tool(
            "manage_projects",
            "Saved projects: action=list|new|save|load. load/new use `name`.",
            """{"type":"object","properties":{"action":{"type":"string","enum":["list","new","save","load"]},"name":{"type":"string"}},"required":["action"]}""",
        ) {
            when (sopt(it, "action")) {
                "list" -> store.listSaved(context)
                    .joinToString("\n") { p -> "\"${p.title}\" ${fmt(p.durationMs())} ${p.clips.size} clips" }
                    .ifEmpty { "(none saved)" }
                "new" -> {
                    val name = (sopt(it, "name") ?: "New edit").take(30)
                    store.commit { _ -> newProject(name) }
                    "fresh project \"$name\" created (undo restores the old one)"
                }
                "save" -> { store.save(context); "saved to device" }
                "load" -> {
                    val n = sopt(it, "name").orEmpty()
                    val hit = store.listSaved(context).firstOrNull { it.title.contains(n, true) }
                        ?: return@Tool "no saved project matching \"$n\""
                    if (store.load(context, hit.id)) "loaded \"${hit.title}\"" else "load failed"
                }
                else -> "unsupported action"
            }
        },

        Tool(
            "read_script",
            "Materialize the timeline as rows (HEAD #id TRACK START in->out speed grade crop title). The agent can edit rows between the call to read_script and the call to apply_script.",
            """{"type":"object","properties":{},"required":[]}""",
            read = true,
        ) {
            val p = store.project.value
            if (p.clips.isEmpty()) return@Tool "(timeline empty)"
            buildString {
                append("# script: TRACK|ID|startSec|inSec|outSec|speed|grade|crop|title")
                for (c in p.visualClips()) {
                    append("\nV${c.track + 1}|${c.id}|${"%.2f".format(c.positionMs / 1000f)}|" +
                        "${"%.2f".format(c.inMs / 1000f)}|${"%.2f".format(c.outMs / 1000f)}|" +
                        "${c.speed}|${c.color.name.lowercase()}|${c.crop.label}|${c.title.replace('|', ' ')}")
                }
                for (m in p.musicClips()) {
                    append("\nM|${m.id}|${"%.2f".format(m.positionMs / 1000f)}|" +
                        "${"%.2f".format(m.inMs / 1000f)}|${"%.2f".format(m.outMs / 1000f)}|${m.speed}|vol=${m.volume}||")
                }
            }
        },

        Tool(
            "apply_script",
            "rows = the edited read_script rows. Each row re-times/re-grades/renames that item id (unknown ids are skipped).",
            """{"type":"object","properties":{"rows":{"type":"array","items":{"type":"string"}}},"required":["rows"]}""",
        ) {
            val rows = it["rows"]?.jsonArray ?: return@Tool "rows required"
            var n = 0
            for (el in rows) {
                val line = (el as? kotlinx.serialization.json.JsonPrimitive)?.contentOrNull ?: continue
                val cols = line.split('|').map { it.trim() }
                if (cols.size < 9) continue
                val c = store.project.value.find(cols[1]) ?: continue
                val isAudio = c.kind == "audio"
                val pos = cols.getOrNull(2)?.toDoubleOrNull()?.times(1000)?.toLong()
                val inMs = cols.getOrNull(3)?.toDoubleOrNull()?.times(1000)?.toLong()
                val outMs = cols.getOrNull(4)?.toDoubleOrNull()?.times(1000)?.toLong()
                store.updateClip(c.id) { k ->
                    val crop = if (isAudio) k.crop else cropOf(cols.getOrNull(7)) ?: k.crop
                    val grade = if (isAudio) k.color else gradeOf(cols.getOrNull(6)) ?: k.color
                    k.copy(
                        positionMs = if (c.kind == "audio") (pos ?: k.positionMs) else (pos?.coerceAtLeast(0) ?: k.positionMs),
                        inMs = (inMs ?: k.inMs).coerceIn(0L, c.srcDurationMs),
                        outMs = (outMs ?: k.outMs).coerceIn(k.inMs + 100, c.srcDurationMs),
                        speed = cols.getOrNull(5)?.takeIf { it.isNotEmpty() }?.toFloatOrNull()?.coerceIn(0.25f, 4f) ?: k.speed,
                        color = grade, crop = crop,
                        title = cols.getOrNull(8)?.takeIf { it.isNotEmpty() }?.take(48) ?: k.title,
                    )
                }
                n++
            }
            autoCheckpoint("script")
            "$n row(s) applied"
        },

        // ── export ──────────────────────────────────────────────────────
        Tool(
            "submit_export",
            "Render the timeline to a real MP4 on-device. preset = auto|720p|1080p|480p|9:16|1:1. Track with check_export.",
            """{"type":"object","properties":{"preset":{"type":"string"}}}""",
        ) {
            val svc = exportService ?: return@Tool "export not available in this context"
            if (svc.running) return@Tool "already rendering (call check_export)"
            val spec = specFor(sopt(it, "preset") ?: "auto", store.project.value)
            exportStatus = "rendering 0%"
            scope.launch {
                svc.export(store, spec, { prog -> exportStatus = "rendering ${(prog * 100).toInt()}%" }) { f, e ->
                    exportStatus = if (f != null) "done → ${f.absolutePath}" else "failed: $e"
                }
            }
            "render started (${spec.width}x${spec.height} @${spec.fps}fps)"
        },

        Tool(
            "check_export",
            "Status of the running/finished MP4 render.",
            """{"type":"object","properties":{"wait":{"type":"boolean"}}}""",
            read = true,
        ) {
            exportStatus ?: "idle (no export started)"
        },
    )
}
