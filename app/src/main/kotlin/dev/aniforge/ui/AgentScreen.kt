package dev.aniforge.ui

import android.content.Context
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import dev.aniforge.domain.*
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * Agent-first home screen:
 *   import your own clips → type what you want → ⚡ AUTO EDIT.
 *   Agent #1 plans & cuts the whole timeline; Agent #2 fetches music + material.
 */
@Composable
fun AgentScreen(store: ProjectStore, onOpenResult: () -> Unit) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val agent = remember { ForgeAgent(context, store) }

    var prompt by remember { mutableStateOf("") }
    var style by remember { mutableStateOf<EditStyle?>(null) }
    var crop by remember { mutableStateOf<CropMode?>(null) }
    var useMusic by remember { mutableStateOf(true) }
    var useMaterials by remember { mutableStateOf(true) }
    var steps by remember { mutableStateOf(listOf<String>()) }
    var busy by remember { mutableStateOf(false) }
    var done by remember { mutableStateOf(false) }
    var showBrainCfg by remember { mutableStateOf(false) }
    var footageInfo by remember { mutableStateOf("") }
    var tick by remember { mutableIntStateOf(0) }

    val videoPicker = rememberLauncherForActivityResult(ActivityResultContracts.OpenMultipleDocuments()) { uris ->
        scope.launch(Dispatchers.IO) {
            val n = uris.mapNotNull { runCatching { Importer.import(context, store, it) }.getOrNull() }
            withContext(Dispatchers.Main) {
                footageInfo = "${n.size} clip(s) added · ${store.project.value.videoTrackClips().size} on the shelf"
                tick++
            }
        }
    }
    val musicPicker = rememberLauncherForActivityResult(ActivityResultContracts.OpenMultipleDocuments()) { uris ->
        scope.launch(Dispatchers.IO) {
            uris.forEach { Importer.import(context, store, it) }
        }
    }

    fun step(s: String) {
        steps = steps + s
    }

    Column(
        Modifier
            .fillMaxSize()
            .background(Color(0xFF0B0E14))
            .verticalScroll(rememberScrollState())
            .padding(20.dp)
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text("AniForge", fontSize = 30.sp, color = Color(0xFFFF4D6D))
            Spacer(Modifier.width(8.dp))
            Text("auto editor", fontSize = 13.sp, color = Color(0xFF8FA3BC))
            Spacer(Modifier.weight(1f))
            Text(
                "🧠 brain", fontSize = 12.sp,
                color = if (Llm.load(context) != null) Color(0xFF7EE3C5) else Color(0xFF6B7E95),
                modifier = Modifier.clickable { showBrainCfg = !showBrainCfg }
            )
        }

        Spacer(Modifier.height(12.dp))
        Row {
            Text(
                if (store.project.value.videoTrackClips().isEmpty()) "no clips yet — import"
                else "${store.project.value.videoTrackClips().size} clip(s) ready · " +
                        store.project.value.videoTrackClips().sumOf { it.durationMs() } / 1000 + "s",
                fontSize = 12.sp, color = Color(0xFF7EE3C5)
            )
        }

        Spacer(Modifier.height(10.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Box(
                Modifier.clip(RoundedCornerShape(10.dp)).background(Color(0xFF1B2434))
                    .clickable(enabled = !busy) { videoPicker.launch(arrayOf("video/*")) }
                    .padding(horizontal = 14.dp, vertical = 10.dp)
            ) { Text("＋ your videos", color = Color(0xFF7EE3C5), fontSize = 13.sp) }
            Box(
                Modifier.clip(RoundedCornerShape(10.dp)).background(Color(0xFF1B2434))
                    .clickable(enabled = !busy) { musicPicker.launch(arrayOf("audio/*")) }
                    .padding(horizontal = 14.dp, vertical = 10.dp)
            ) { Text("＋ own music", color = Color(0xFF7EE3C5), fontSize = 13.sp) }
        }
        if (footageInfo.isNotEmpty()) {
            Text(footageInfo, fontSize = 10.sp, color = Color(0xFF6B7E95), modifier = Modifier.padding(top = 4.dp))
        }

        Spacer(Modifier.height(16.dp))
        OutlinedTextField(
            value = prompt,
            onValueChange = { prompt = it },
            label = { Text("tell the agent what you want", fontSize = 12.sp) },
            placeholder = {
                Text("e.g. 30s hype amv of my fight clip, vertical, with music",
                    fontSize = 11.sp, color = Color(0xFF556277))
            },
            textStyle = androidx.compose.ui.text.TextStyle(fontSize = 13.sp, color = Color.White),
            modifier = Modifier.fillMaxWidth()
        )

        Spacer(Modifier.height(10.dp))
        Text("style (agent auto-detects if left alone)", fontSize = 10.sp, color = Color(0xFF6B7E95))
        Row(horizontalArrangement = Arrangement.spacedBy(5.dp)) {
            EditStyle.entries.forEach { s ->
                SmallChip(s.label, style == s) { style = if (style == s) null else s }
            }
        }

        Spacer(Modifier.height(8.dp))
        Text("frame", fontSize = 10.sp, color = Color(0xFF6B7E95))
        Row(horizontalArrangement = Arrangement.spacedBy(5.dp)) {
            SmallChip("auto", crop == null) { crop = null }
            SmallChip("16:9", crop == CropMode.R16_9) { crop = CropMode.R16_9 }
            SmallChip("9:16", crop == CropMode.R9_16) { crop = CropMode.R9_16 }
            SmallChip("4:5", crop == CropMode.R4_5) { crop = CropMode.R4_5 }
            SmallChip("1:1", crop == CropMode.R1_1) { crop = CropMode.R1_1 }
        }

        Spacer(Modifier.height(8.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(5.dp), verticalAlignment = Alignment.CenterVertically) {
            SmallChip(if (useMusic) "🎵 auto music: on" else "🎵 auto music: off") { useMusic = !useMusic }
            SmallChip(if (useMaterials) "🖼 auto material: on" else "🖼 auto material: off") { useMaterials = !useMaterials }
        }

        if (showBrainCfg) BrainConfig()

        Spacer(Modifier.height(16.dp))
        Box(
            Modifier
                .clip(RoundedCornerShape(12.dp))
                .background(if (busy) Color(0xFF2A3448) else Color(0xFFFF4D6D))
                .clickable(enabled = !busy && store.project.value.videoTrackClips().isNotEmpty()) {
                    val p = prompt.ifBlank { when (style) {
                        EditStyle.CHILL -> "chill aesthetic edit with music"
                        EditStyle.TRAILER -> "cinematic trailer edit, with music"
                        EditStyle.SOCIAL -> "tiktok vertical short edit, with music"
                        else -> "hype amv edit, fast, with music"
                    } }
                    busy = true; done = false; steps = emptyList()
                    scope.launch {
                        val r = agent.run(p, style, crop, useMusic, useMaterials) { s -> step(s) }
                        busy = false
                        done = r == "done"
                    }
                }
                .fillMaxWidth()
                .padding(vertical = 14.dp),
            contentAlignment = Alignment.Center
        ) {
            Text(
                if (busy) "working…" else "⚡  AUTO EDIT",
                fontSize = 18.sp, color = Color.White
            )
        }

        if (steps.isNotEmpty()) {
            Spacer(Modifier.height(14.dp))
            Text("agent feed", fontSize = 10.sp, color = Color(0xFF6B7E95))
            Column {
                steps.forEach { s ->
                    Text("· $s", fontSize = 11.sp, color = Color(0xFF9FB2C8), modifier = Modifier.padding(vertical = 2.dp))
                }
            }
            if (busy) {
                Spacer(Modifier.height(6.dp))
                LinearProgressIndicator(modifier = Modifier.fillMaxWidth().height(3.dp), color = Color(0xFFFF4D6D))
            }
            if (done) {
                Spacer(Modifier.height(10.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Box(
                        Modifier.clip(RoundedCornerShape(10.dp)).background(Color(0xFF2FBF7F))
                            .clickable { onOpenResult() }
                            .padding(horizontal = 16.dp, vertical = 10.dp)
                    ) { Text("▶ watch result", color = Color.White, fontSize = 13.sp) }
                    Box(
                        Modifier.clip(RoundedCornerShape(10.dp)).background(Color(0xFF1B2434))
                            .clickable { onOpenResult() }
                            .padding(horizontal = 16.dp, vertical = 10.dp)
                    ) { Text("↻ tweak", color = Color(0xFFC9D6E5), fontSize = 13.sp) }
                }
            }
        }

        Spacer(Modifier.height(24.dp))
        Text(
            "agent 1 cuts your footage to the prompt (scenes, speed waves, pushes, grades, cards). " +
                    "agent 2 fetches open-license music & card art (openverse, keyless). " +
                    "export → real MP4 on-device.",
            fontSize = 10.sp, color = Color(0xFF556277)
        )
    }
}

/** Optional LLM configuration (any OpenAI-compatible endpoint). */
@Composable
fun BrainConfig() {
    val context = LocalContext.current
    val cfg = remember { Llm.load(context) }
    var endpoint by remember { mutableStateOf(cfg?.endpoint ?: "") }
    var key by remember { mutableStateOf(cfg?.apiKey ?: "") }
    var model by remember { mutableStateOf(cfg?.model ?: "") }

    Column(Modifier.fillMaxWidth().padding(top = 10.dp)) {
        Text("🧠 LLM brain (optional)", fontSize = 11.sp, color = Color(0xFF7EE3C5))
        Text(
            "point at any OpenAI-compatible endpoint (llmapi/openrouter/self-hosted). " +
                    "Without it the built-in on-device director plans your edit.",
            fontSize = 9.sp, color = Color(0xFF6B7E95)
        )
        OutlinedTextField(
            value = endpoint, onValueChange = { endpoint = it },
            label = { Text("endpoint (…/v1)", fontSize = 10.sp) },
            textStyle = androidx.compose.ui.text.TextStyle(fontSize = 11.sp, color = Color.White),
            modifier = Modifier.fillMaxWidth().padding(top = 4.dp), singleLine = true
        )
        OutlinedTextField(
            value = key, onValueChange = { key = it },
            label = { Text("api key (stays on this device)", fontSize = 10.sp) },
            textStyle = androidx.compose.ui.text.TextStyle(fontSize = 11.sp, color = Color.White),
            modifier = Modifier.fillMaxWidth().padding(top = 4.dp), singleLine = true
        )
        OutlinedTextField(
            value = model, onValueChange = { model = it },
            label = { Text("model", fontSize = 10.sp) },
            textStyle = androidx.compose.ui.text.TextStyle(fontSize = 11.sp, color = Color.White),
            modifier = Modifier.fillMaxWidth().padding(top = 4.dp), singleLine = true
        )
        Row(horizontalArrangement = Arrangement.spacedBy(6.dp), modifier = Modifier.padding(top = 6.dp)) {
            SmallChip("save") { Llm.save(context, endpoint, key, model) }
            SmallChip("clear") { Llm.clear(context) }
        }
    }
}
