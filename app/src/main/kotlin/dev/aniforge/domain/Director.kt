package dev.aniforge.domain

import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToLong

/** Analysis of one source clip (what the agents "see" in the footage). */
data class ClipAnalysis(
    val clip: Clip,
    val cuts: List<Long>,          // scene-change times (source ms)
    val motion: Float,             // 0..1 rough motion/energy estimate
)

/** Agent #1 — turns prompt params + footage analysis into a complete timeline. */
object Director {

    fun defaultParams(parsed: PlanParser.Parsed): LlmParams = LlmParams(
        style = parsed.style,
        targetMs = parsed.targetMs,
        cutEveryMs = when (parsed.style) {
            EditStyle.FAST -> 750L
            EditStyle.CHILL -> 2200L
            EditStyle.TRAILER -> 1400L
            EditStyle.SOCIAL -> 900L
        },
        grade = when (parsed.style) {
            EditStyle.FAST -> ColorGrade.VIVID
            EditStyle.CHILL -> ColorGrade.PASTEL
            EditStyle.TRAILER -> ColorGrade.COOL
            EditStyle.SOCIAL -> ColorGrade.WARM
        },
        crop = parsed.crop,
        introTitle = if (parsed.wantsText) parsed.headline.take(24) else null,
        outroTitle = null,
        speedWave = when (parsed.style) {
            EditStyle.FAST -> listOf(1.0f, 1.0f, 1.15f, 1.0f, 0.9f, 1.2f)
            EditStyle.CHILL -> listOf(1.0f, 0.92f, 1.0f)
            EditStyle.TRAILER -> listOf(0.75f, 1.0f, 1.0f, 1.15f, 0.6f)
            EditStyle.SOCIAL -> listOf(1.0f, 1.1f, 1.0f, 1.2f)
        },
        musicQuery = parsed.musicQuery,
    )

    private fun grades(style: EditStyle): List<ColorGrade> = when (style) {
        EditStyle.FAST -> listOf(ColorGrade.VIVID, ColorGrade.WARM, ColorGrade.COOL, ColorGrade.NONE)
        EditStyle.CHILL -> listOf(ColorGrade.PASTEL, ColorGrade.NONE)
        EditStyle.TRAILER -> listOf(ColorGrade.COOL, ColorGrade.NOIR, ColorGrade.COOL)
        EditStyle.SOCIAL -> listOf(ColorGrade.WARM, ColorGrade.VIVID, ColorGrade.NONE)
    }

    data class Segment(val clip: Clip, val inMs: Long, val outMs: Long, val outLenMs: Long, val speed: Float, val idx: Int)

    /** Walks the source material scene-by-scene until [targetMs] is consumed. */
    fun pickSegments(analyses: List<ClipAnalysis>, targetMs: Long, speedWave: List<Float>, cutEveryMs: Long): List<Segment> {
        val usable = analyses.filter { it.clip.srcDurationMs > 500L }
        if (usable.isEmpty()) return emptyList()

        // available source windows (per scene)
        val windows = ArrayDeque<Segment>()
        for (ca in usable) {
            val bounds = (listOf(0L) + ca.cuts.filter { it in 300..(ca.clip.srcDurationMs - 300) } + listOf(ca.clip.srcDurationMs))
            for (i in 0 until bounds.size - 1) {
                val s = bounds[i]; val e = bounds[i + 1]
                if (e - s < 350L) continue
                windows.add(Segment(ca.clip, s, e, e - s, 1f, windows.size))
            }
        }
        if (windows.isEmpty()) return emptyList()

        var t = 0L
        var i = 0
        val out = mutableListOf<Segment>()
        while (t < targetMs && windows.isNotEmpty()) {
            val src = windows.removeFirst()
            // output length: cut cadence with slight jitter, but bounded by source window
            var outLen = (cutEveryMs * (if (i % 3 == 2) 1.35f else 1.0f)).roundToLong()
                .coerceIn(280L, max(280L, src.outMs - src.inMs))
            val speed = speedWave[i % speedWave.size].coerceIn(0.25f, 4f)
            // source consumed = outLen * speed
            val srcTake = min((outLen * speed).roundToLong(), src.outMs - src.inMs)
            if (srcTake < 120L) { i++; continue }
            outLen = (srcTake / speed).roundToLong().coerceAtLeast(240L)
            out.add(
                Segment(
                    src.clip, src.inMs, src.inMs + srcTake, outLen,
                    speed, i
                )
            )
            t += outLen
            i++
        }
        return out
    }

    /** Builds the full clip list (intro card + segments + music) for the plan. */
    fun build(
        analyses: List<ClipAnalysis>,
        params: LlmParams,
        musicFile: MaterialFile?,
        cardImage: MaterialFile?,
        withTitleCard: Boolean,
    ): List<Clip> {
        val clips = mutableListOf<Clip>()
        var pos = 0L
        val speedWave = params.speedWave.ifEmpty { listOf(1f) }

        // intro card
        if (withTitleCard && params.introTitle != null && cardImage != null) {
            val card = Clip(
                id = ClipIdGen.next("card"),
                kind = "image",
                sourceUri = "file://" + cardImage.path,
                srcDurationMs = 2_400L,
                srcW = cardImage.width.coerceAtLeast(1280),
                srcH = cardImage.height.coerceAtLeast(720),
                positionMs = pos,
                inMs = 0L,
                outMs = 2_400L,
                crop = CropMode.R16_9,
                title = params.introTitle.take(24),
                titleSize = 0.085f,
                titleColor = "#FFFFD54F",
                transforms = mapOf(
                    Prop.SCALE.key to MotionTrack(listOf(KeyPoint(0f, 1.06f), KeyPoint(2.4f, 1.0f))),
                    Prop.OPACITY.key to MotionTrack(listOf(
                        KeyPoint(0f, 0f), KeyPoint(0.3f, 1f), KeyPoint(2.1f, 1f), KeyPoint(2.4f, 0f))),
                ),
            )
            clips.add(card)
            pos += card.durationMs()
        }

        val segs = pickSegments(analyses, params.targetMs, speedWave, params.cutEveryMs)
        val gradeSet = grades(params.style)
        for (seg in segs) {
            val localEnd = seg.outLenMs / 1000f
            val fade = min(0.14f, localEnd / 4f)
            val pushIn = seg.idx % 2 == 0
            val scaleTrack = when (params.style) {
                EditStyle.CHILL -> MotionTrack(listOf(KeyPoint(0f, 1f), KeyPoint(max(0.3f, localEnd), 1.07f)))
                else -> if (pushIn)
                    MotionTrack(listOf(KeyPoint(0f, 1f), KeyPoint(max(0.3f, localEnd), 1.22f)))
                else
                    MotionTrack(listOf(KeyPoint(0f, 1.22f), KeyPoint(max(0.3f, localEnd), 1f)))
            }
            val rotTrack = if (params.style == EditStyle.SOCIAL && seg.idx % 3 == 1)
                MotionTrack(listOf(KeyPoint(0f, -1.2f), KeyPoint(max(0.3f, localEnd), 1.2f)))
            else null

            val tr = mutableMapOf(
                Prop.SCALE.key to scaleTrack,
                Prop.OPACITY.key to MotionTrack(listOf(
                    KeyPoint(0f, 0f), KeyPoint(fade, 1f),
                    KeyPoint(max(fade, localEnd - fade), 1f), KeyPoint(max(0.2f, localEnd), 0f))),
            )
            rotTrack?.let { tr[Prop.ROT.key] = it }

            clips.add(
                seg.clip.copy(
                    id = ClipIdGen.next("seg"),
                    positionMs = pos,
                    inMs = seg.inMs,
                    outMs = seg.outMs,
                    speed = seg.speed,
                    crop = params.crop,
                    color = gradeSet[seg.idx % gradeSet.size],
                    transforms = tr,
                    title = "",
                )
            )
            pos += seg.outLenMs
        }

        // outro card
        if (withTitleCard && params.outroTitle != null && cardImage != null) {
            val card = Clip(
                id = ClipIdGen.next("card"),
                kind = "image",
                sourceUri = "file://" + cardImage.path,
                srcDurationMs = 1_800L,
                srcW = cardImage.width.coerceAtLeast(1280),
                srcH = cardImage.height.coerceAtLeast(720),
                positionMs = pos,
                inMs = 0L,
                outMs = 1_800L,
                crop = CropMode.R16_9,
                title = params.outroTitle.take(24),
                titleSize = 0.08f,
                titleColor = "#FFFFFFFF",
                transforms = mapOf(
                    Prop.OPACITY.key to MotionTrack(listOf(
                        KeyPoint(0f, 0f), KeyPoint(0.25f, 1f), KeyPoint(1.55f, 1f), KeyPoint(1.8f, 0f))),
                ),
            )
            clips.add(card)
            pos += card.durationMs()
        }

        // music bed
        if (musicFile != null && pos > 0) {
            val vol = if (analyses.any { !it.clip.muted && it.clip.kind == "video" }) 0.55f else 0.9f
            clips.add(
                Clip(
                    id = ClipIdGen.next("mus"),
                    track = 2,
                    kind = "audio",
                    sourceUri = "file://" + musicFile.path,
                    srcDurationMs = musicFile.durationMs,
                    positionMs = 0L,
                    inMs = 0L,
                    outMs = min(musicFile.durationMs, pos),
                    volume = vol,
                    transforms = mapOf(
                        Prop.VOLUME.key to MotionTrack(listOf(
                            KeyPoint(0f, 0f), KeyPoint(0.8f, 1f),
                            KeyPoint(max(1f, pos / 1000f - 1.2f), 1f), KeyPoint(max(1.2f, pos / 1000f), 0f)))
                    ),
                )
            )
        }
        return clips
    }
}
