package dev.aniforge.domain

import java.util.Locale
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToLong

/**
 * Agent-facing edit plan.
 * The agent (offline director or an LLM) produces one of these from the user's
 * prompt + footage analysis; [ForgeAgent] then executes it onto the timeline.
 */
enum class EditStyle(val id: String, val label: String) {
    FAST("fast", "AMV / hype"),
    CHILL("chill", "chill / aesthetic"),
    TRAILER("trailer", "cinematic trailer"),
    SOCIAL("sns", "reel / short"),
}

data class LlmParams(
    val style: EditStyle,
    val targetMs: Long,
    val cutEveryMs: Long,          // base cut length; recordings may vary ±
    val grade: ColorGrade,
    val crop: CropMode,
    val introTitle: String?,
    val outroTitle: String?,
    val speedWave: List<Float>,
    val musicQuery: String?,
)

data class MaterialFile(
    val path: String,
    val durationMs: Long,
    val title: String,
    val license: String,
    val width: Int = 0,
    val height: Int = 0,
)

/** Prompt → [EditPlan] (keyword-based offline parser used by the Agent). */
object PlanParser {

    data class Parsed(
        val style: EditStyle,
        val targetMs: Long,
        val crop: CropMode,
        val wantsMusic: Boolean,
        val wantsText: Boolean,
        val headline: String,
        val musicQuery: String,
    )

    fun parse(promptRaw: String, footageMs: Long): Parsed {
        val p = promptRaw.lowercase(Locale.US)

        val style = when {
            listOf("shonen", "amv", "hype", "fast", "epic", "battle", "insane", "combo", "action").any { it in p } -> EditStyle.FAST
            listOf("chill", "lofi", "aesthetic", "calm", "soft", "sad", "slow", "lo-fi", "ambient").any { it in p } -> EditStyle.CHILL
            listOf("trailer", "cinematic", "movie", "teaser", "promo").any { it in p } -> EditStyle.TRAILER
            listOf("tiktok", "reel", "shorts", "short", "sns", "instagram", "vertical").any { it in p } -> EditStyle.SOCIAL
            else -> EditStyle.FAST
        }

        // length: explicit request wins, else cap footage
        val num = Regex("(\\d{1,3})\\s*(s\\b|sec|second|seconds?)").find(p)?.groupValues?.get(1)
        val mins = Regex("(\\d{1,2})\\s*(min|minute|minutes?)").find(p)?.groupValues?.get(1)
        val targetMs = when {
            mins != null -> mins.toLong() * 60_000L
            num != null -> num.toLong() * 1000L
            else -> min(max(12_000L, footageMs), 60_000L)
        }

        val crop = when {
            "9:16" in p || "vertical" in p || "tiktok" in p || "reel" in p || "shorts" in p -> CropMode.R9_16
            "4:5" in p -> CropMode.R4_5
            "1:1" in p || "square" in p -> CropMode.R1_1
            "16:9" in p || "landscape" in p || "youtube" in p -> CropMode.R16_9
            else -> if (style == EditStyle.SOCIAL) CropMode.R9_16 else CropMode.R16_9
        }

        val noMusic = listOf("no music", "silent", "mute", "without music", "original audio").any { it in p }
        val wantsMusic = !noMusic

        val wantsText = !listOf("no text", "no title", "no card", "clean").any { it in p }

        // headline: significant words from the prompt (for intro card)
        val stop = setOf(
            "edit", "make", "video", "anime", "with", "music", "style", "please", "want", "need",
            "into", "a", "the", "of", "for", "and", "like", "ambient", "genre"
        )
        val words = Regex("[a-zA-Z0-9\\-']+").findAll(promptRaw)
            .map { it.value }
            .filter { it.lowercase(Locale.US) !in stop && it.length > 2 }
            .take(5)
            .toList()
        val headline = words.joinToString(" ").uppercase(Locale.US).ifEmpty { "ANIME EDIT" }

        val musicWords = words.take(3).joinToString(" ")
        val musicQuery = when (style) {
            EditStyle.FAST -> "epic battle instrumental $musicWords"
            EditStyle.CHILL -> "lofi chill instrumental $musicWords"
            EditStyle.TRAILER -> "cinematic trailer score instrumental"
            EditStyle.SOCIAL -> "upbeat short track instrumental"
        }

        return Parsed(style, targetMs, crop, wantsMusic, wantsText, headline, musicQuery)
    }
}
