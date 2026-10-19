package dev.aniforge.domain

import android.content.Context
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * The in-app AI director (Agent #1 + Agent #2):
 *
 *   1. parses the prompt (offline + optional LLM brain)
 *   2. analyzes every clip the user imported (scene cuts, cadence, energy)
 *   3. Agent #2 fetches music + card material from the open web (keyless, license-aware)
 *   4. Director builds the whole timeline: intro card → cut to rhythm w/ push-ins,
 *      speed wave, grade rotation, crop plan, outro card, beat-mixed music bed
 *   5. the result lands on the timeline, ready to preview & export
 */
class ForgeAgent(
    private val context: Context,
    private val store: ProjectStore,
) {
    private fun log(line: String) = store.say(line)

    private fun motionOf(cuts: List<Long>, durationMs: Long, ctxLuma: Float = 0f): Float {
        // proxy: more cuts + more length variety on a clip = higher energy
        val cutRate = if (durationMs > 0) cuts.size / (durationMs / 10_000f) else 0f
        return (0.15f + cutRate * 0.6f).coerceIn(0.05f, 1f) + ctxLuma * 0.2f
    }

    suspend fun run(
        prompt: String,
        overrideStyle: EditStyle?,
        overrideCrop: CropMode?,
        useMusic: Boolean = true,
        useMaterials: Boolean = true,
        onStep: (String) -> Unit = {},
    ): String {
        val project = store.project.value
        val footage = project.allVideoClips()
        if (footage.isEmpty()) return "import videos first (back → ＋ import videos)"

        onStep("🧠 reading your prompt…")
        log("prompt: \"${prompt.take(64)}\"")
        val parsed = PlanParser.parse(prompt, footage.sumOf { it.durationMs() })
        var params = Director.defaultParams(parsed.copy(style = overrideStyle ?: parsed.style))
        overrideCrop?.let { params = params.copy(crop = it) }

        onStep("📽 analyzing ${footage.size} clip(s): scenes, energy…")
        val analyses = withContext(Dispatchers.IO) {
            footage.take(6).map { clip ->
                val cuts = runCatching { MediaProbe.detectCuts(context, clip.sourceUri) }.getOrDefault(emptyList())
                ClipAnalysis(clip, cuts, motionOf(cuts, clip.srcDurationMs))
            }
        }
        log("footage: ${analyses.sumOf { it.clip.srcDurationMs } / 1000}s · ${analyses.sumOf { it.cuts.size }} scene changes")

        // optional LLM brain
        Llm.load(context)?.let { cfg ->
            onStep("💠 consulting LLM brain (${cfg.model})…")
            val shaped = Llm.plan(context, cfg, prompt, analyses)
            if (shaped != null) {
                params = shaped
                log("LLM plan: ${params.style.id} · cuts every ${params.cutEveryMs}ms · ${params.grade.name.lowercase()}")
            } else {
                log("LLM unreachable — using on-device director")
            }
        } ?: run { log("using on-device director (no LLM configured)") }

        // ---- Agent #2: music ------------------------------------------------
        var music: MaterialFile? = null
        if (useMusic) {
            onStep("🎵 searching music: \"${params.musicQuery ?: parsed.musicQuery}\"…")
            val m = runCatching { Materials.fetchMusic(context, params.musicQuery ?: parsed.musicQuery) }
                .getOrNull()
            if (m != null) {
                music = m
                log("music ✓ \"${m.title}\" (${m.license.uppercase()}, ${m.durationMs / 1000}s)")
                onStep("🎵 music found ✓")
            } else {
                log("no music found — check connection, or import music manually")
                onStep("🎵 no music this run")
            }
        }

        // ---- Agent #2: card material ---------------------------------------
        var card: MaterialFile? = null
        if (useMaterials && params.introTitle != null) {
            val q = when (params.style) {
                EditStyle.FAST -> "${parsed.headline.lowercase()} anime sky city night"
                EditStyle.CHILL -> "pastel sunset sky clouds aesthetic"
                EditStyle.TRAILER -> "cinematic dark city lights"
                EditStyle.SOCIAL -> "portrait studio backdrop neon"
            }
            onStep("🖼 fetching card art: \"$q\"…")
            card = runCatching { Materials.fetchCardImage(context, q) }.getOrNull()
            if (card != null) log("card art ✓ \"${card.title}\" (${card.license.uppercase()})")
            else log("no card art — titles will play over footage")
        }

        // ---- Director executes ---------------------------------------------
        onStep("🎬 building the timeline (intro → cuts → outro → music)…")
        val clips = Director.build(
            analyses = analyses,
            params = params,
            musicFile = music,
            cardImage = card,
            withTitleCard = useMaterials && parsed.wantsText,
        )
        if (clips.none { it.kind == "video" }) return "couldn't build a plan from that footage"

        store.commit { p -> p.copy(clips = clips) }
        store.selection.value = null
        store.playheadMs.value = 0L

        val total = store.project.value.durationMs()
        val cutCount = clips.count { it.kind == "video" }
        onStep("✅ edit ready — $cutCount cuts · ${total / 1000}s · ${if (music != null) "with music" else "no music"}")
        log("final duration ${total / 1000}s, ${clips.size} clips on timeline")
        return "done"
    }
}
