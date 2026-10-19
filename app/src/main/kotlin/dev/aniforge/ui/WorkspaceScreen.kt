package dev.aniforge.ui

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
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
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import dev.aniforge.domain.ProjectStore
import dev.aniforge.domain.Llm
import dev.aniforge.domain.agent.AgentEvent
import dev.aniforge.domain.agent.AgentLoop
import dev.aniforge.domain.agent.ToolKit
import dev.aniforge.engine.ExportService
import dev.aniforge.engine.PreviewState
import dev.aniforge.engine.PreviewView
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** Chat entries (agent conversation + tool-call trail). */
sealed interface ChatEntry {
    data class Msg(val who: String, val text: String) : ChatEntry
    data class Tool(val name: String, val args: String, val result: String, val ok: Boolean) : ChatEntry
}

/**
 * AniForge ChatCut — the OpenChatCut workspace, ported 1:1 for Android:
 *   LEFT  agent chat (every tool call is visible) · 🧠 brain config · ⚙ pro editor
 *   RIGHT GL preview + transport + multitrack timeline.
 * The agent edits the REAL timeline through ToolKit's tools — nothing scripted.
 */
@Composable
fun WorkspaceScreen(store: ProjectStore, onOpenPro: () -> Unit) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val exportService = remember { ExportService(context) }
    val kit = remember { ToolKit(context, store, exportService) }
    val driver = remember { PlaybackDriver(context, store) }

    val entries = remember { mutableStateListOf<ChatEntry>() }
    val busy = remember { mutableStateOf(false) }
    var tickUi by remember { mutableIntStateOf(0) }
    var inputText by remember { mutableStateOf("") }
    var showExport by remember { mutableStateOf(false) }
    var showBrain by remember { mutableStateOf(false) }
    var zoom by remember { mutableStateOf(1f) }
    val scroll = rememberScrollState()
    val listState = rememberLazyListState()
    val brainReady = remember(showBrain) { Llm.load(context) != null }

    fun push(ev: AgentEvent) {
        when (ev) {
            is AgentEvent.Chat -> entries.add(ChatEntry.Msg(ev.who, ev.text))
            is AgentEvent.ToolUse -> entries.add(ChatEntry.Tool(ev.name, ev.args, ev.result, ev.ok))
            is AgentEvent.Busy -> busy.value = ev.busy
        }
    }

    val loop = remember { AgentLoop(context, kit, onEvent = { push(it) }) }

    fun send(toSend: String? = null) {
        val t = (toSend ?: inputText).trim()
        if (t.isEmpty() || busy.value) return
        inputText = ""
        entries.add(ChatEntry.Msg("user", t))
        busy.value = true
        scope.launch { runCatching { loop.send(t) }.onFailure { busy.value = false } }
    }

    // importers ------------------------------------------------------------
    val videoPicker = rememberLauncherForActivityResult(ActivityResultContracts.OpenMultipleDocuments()) { uris ->
        scope.launch(Dispatchers.IO) {
            for (u in uris) {
                val msg = Importer.import(context, store, u)
                withContext(Dispatchers.Main) {
                    if (!msg.startsWith("unreadable")) {
                        kit.registerInPool(u.toString(), "clip${store.project.value.videoTrackClips().size}")
                        entries.add(ChatEntry.Msg("sys", "＋ $msg"))
                    } else entries.add(ChatEntry.Msg("sys", "import failed: $msg"))
                    tickUi++
                }
            }
        }
    }
    val audioPicker = rememberLauncherForActivityResult(ActivityResultContracts.OpenMultipleDocuments()) { uris ->
        scope.launch(Dispatchers.IO) {
            for (u in uris) {
                val msg = Importer.import(context, store, u)
                withContext(Dispatchers.Main) {
                    if (!msg.startsWith("unreadable")) {
                        kit.registerInPool(u.toString(), "clip${store.project.value.musicClips().size}")
                        entries.add(ChatEntry.Msg("sys", "♪ $msg"))
                    } else entries.add(ChatEntry.Msg("sys", "import failed: $msg"))
                    tickUi++
                }
            }
        }
    }

    // playback plumbing (same as EditorScreen) -------------------------------
    LaunchedEffect(store.playing.value) {
        while (store.playing.value) {
            driver.tick()
            PreviewState.project = store.project.value
            PreviewState.playheadMs = store.playheadMs.value
            tickUi++
            delay(33)
        }
        PreviewState.project = store.project.value
        PreviewState.playheadMs = store.playheadMs.value
    }
    LaunchedEffect(store.project.value, store.playheadMs.value) {
        PreviewState.project = store.project.value
        PreviewState.playheadMs = store.playheadMs.value
    }
    val preview = remember {
        PreviewView(context).apply {
            callbacks = object : PreviewView.Callbacks {
                override fun onSurfaceTextureAvailable(
                    st: android.graphics.SurfaceTexture,
                    surface: android.view.Surface,
                ) { driver.videoPlayer.setVideoSurface(surface) }
            }
        }
    }
    DisposableEffect(Unit) { onDispose { driver.release() } }

    LaunchedEffect(entries.size) {
        if (entries.isNotEmpty()) runCatching { listState.animateScrollToItem(entries.size - 1) }
    }

    // ── layout ──────────────────────────────────────────────────────────
    Row(Modifier.fillMaxSize().background(Color(0xFF0B0E14))) {

        // LEFT: chat column ------------------------------------------------
        Column(Modifier.weight(0.36f).fillMaxHeight().background(Color(0xFF10151E))) {
            Row(
                Modifier.fillMaxWidth().padding(horizontal = 8.dp, vertical = 6.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Column {
                    Text("AniForge", fontSize = 17.sp, color = Color(0xFFFF4D6D), lineHeight = 18.sp)
                    Text("ChatCut", fontSize = 9.sp, color = Color(0xFF6B7E95))
                }
                Spacer(Modifier.weight(1f))
                Text(
                    if (brainReady) "🧠 online" else "🧠 offline",
                    fontSize = 10.sp,
                    color = if (brainReady) Color(0xFF7EE3C5) else Color(0xFF6B7E95),
                    modifier = Modifier.clickable { showBrain = !showBrain }
                )
                Spacer(Modifier.width(6.dp))
                IconBtn("⚙", onClick = onOpenPro)
                Spacer(Modifier.width(4.dp))
                IconBtn("⬇", active = showExport) { showExport = !showExport }
            }

            if (showBrain) {
                Column(Modifier.fillMaxWidth().background(Color(0xFF0D121A))
                    .verticalScroll(rememberScrollState()).heightIn(max = 230.dp)) {
                    BrainConfig()
                    Text(
                        "offline = built-in skills (fixed intents) · online = full agent w/ tools",
                        fontSize = 9.sp, color = Color(0xFF556277),
                        modifier = Modifier.padding(horizontal = 10.dp, vertical = 4.dp)
                    )
                }
            }

            LazyColumn(Modifier.weight(1f).padding(horizontal = 8.dp), state = listState) {
                items(entries) { e -> ChatEntryView(e) }
            }

            if (busy.value) {
                LinearProgressIndicator(
                    modifier = Modifier.fillMaxWidth().height(2.dp).padding(horizontal = 8.dp),
                    color = Color(0xFFFF4D6D)
                )
            }

            // quick actions
            Row(
                Modifier.fillMaxWidth().padding(horizontal = 6.dp, vertical = 3.dp),
                horizontalArrangement = Arrangement.spacedBy(4.dp)
            ) {
                SmallChip("⚡ one-tap cut") { send("one-click edit: make me a hype amv edit with music") }
                SmallChip("🎵 music") { send("add music lofi chill instrumental") }
                SmallChip("9:16") { send("vertical timeline please") }
                SmallChip("export") { send("export the video") }
            }

            Row(
                Modifier.fillMaxWidth().padding(6.dp),
                verticalAlignment = Alignment.Bottom
            ) {
                OutlinedTextField(
                    value = inputText,
                    onValueChange = { inputText = it },
                    placeholder = { Text("tell the agent what to do…", fontSize = 11.sp, color = Color(0xFF556277)) },
                    textStyle = androidx.compose.ui.text.TextStyle(fontSize = 12.sp, color = Color.White),
                    modifier = Modifier.weight(1f).heightIn(min = 20.dp, max = 110.dp)
                )
                Spacer(Modifier.width(5.dp))
                Box(
                    Modifier
                        .clip(RoundedCornerShape(10.dp))
                        .background(if (busy.value) Color(0xFF2A3448) else Color(0xFFFF4D6D))
                        .clickable(enabled = !busy.value) { send() }
                        .padding(horizontal = 13.dp, vertical = 10.dp)
                ) { Text("➤", fontSize = 15.sp, color = Color.White) }
            }
        }

        // RIGHT: preview + timeline ----------------------------------------
        Column(Modifier.weight(0.64f).fillMaxHeight()) {
            Box(Modifier.fillMaxWidth().weight(1f).background(Color(0xFF050608))) {
                AndroidView(factory = { preview }, modifier = Modifier.fillMaxSize())
                if (store.project.value.allVideoClips().isEmpty()) {
                    Column(
                        Modifier.align(Alignment.Center).clickable {
                            videoPicker.launch(arrayOf("video/*"))
                        },
                        horizontalAlignment = Alignment.CenterHorizontally
                    ) {
                        Text("＋ import video", fontSize = 16.sp, color = Color(0xFF7EE3C5))
                        Text("then say what you want done to it", fontSize = 10.sp, color = Color(0xFF6B7E95))
                    }
                }
            }
            Row(
                Modifier.fillMaxWidth().height(34.dp).background(Color(0xFF12151C)),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Spacer(Modifier.width(8.dp))
                IconBtn(if (store.playing.value) "❚❚" else "▶") {
                    if (store.playheadMs.value >= store.project.value.durationMs()) driver.scrubTo(0)
                    store.playing.value = !store.playing.value
                }
                Text(
                    timecode(store.playheadMs.value) + "/" + timecode(store.project.value.durationMs()),
                    fontSize = 10.sp, color = Color(0xFF9FB2C8), modifier = Modifier.padding(horizontal = 5.dp)
                )
                IconBtn("✂") {
                    val id = store.selection.value
                    if (id != null) store.splitClip(id, store.playheadMs.value).also { tickUi++ }
                }
                IconBtn("🗑") {
                    val id = store.selection.value
                    if (id != null) { store.removeClip(id); tickUi++ }
                }
                IconBtn("+vid") { videoPicker.launch(arrayOf("video/*")) }
                IconBtn("+mus") { audioPicker.launch(arrayOf("audio/*")) }
                IconBtn("↩") { store.undo(); tickUi++ }
                IconBtn("↪") { store.redo(); tickUi++ }
                Spacer(Modifier.weight(1f))
                androidx.compose.material3.Slider(
                    value = zoom.value, onValueChange = { zoom.value = it },
                    valueRange = 0.4f..6f,
                    modifier = Modifier.width(90.dp).height(22.dp)
                )
                Spacer(Modifier.width(6.dp))
            }
            TimelineCanvas(store, zoom.value, scroll) { driver.scrubTo(it) }
            Spacer(Modifier.height(3.dp))
        }
    }

    if (showExport) ExportDialog(store, exportService) { showExport = false }
}

@Composable
private fun ChatEntryView(e: ChatEntry) {
    when (e) {
        is ChatEntry.Msg -> when (e.who) {
            "user" -> Box(
                Modifier.fillMaxWidth().padding(vertical = 3.dp),
                contentAlignment = Alignment.CenterEnd
            ) {
                Box(
                    Modifier.clip(RoundedCornerShape(10.dp)).background(Color(0xFFFF4D6D))
                        .padding(horizontal = 10.dp, vertical = 6.dp).widthIn(max = 230.dp)
                ) { Text(e.text, fontSize = 12.sp, color = Color.White) }
            }
            "sys" -> Text(
                e.text,
                fontSize = 10.sp, color = Color(0xFF5FBFA0),
                modifier = Modifier.padding(vertical = 2.dp), maxLines = 2
            )
            else -> Box(Modifier.fillMaxWidth().padding(vertical = 3.dp), contentAlignment = Alignment.CenterStart) {
                Box(
                    Modifier.clip(RoundedCornerShape(10.dp)).background(Color(0xFF232D3F))
                        .padding(horizontal = 10.dp, vertical = 6.dp).widthIn(max = 235.dp)
                ) { Text(e.text, fontSize = 12.sp, color = Color(0xFFE7EEF7)) }
            }
        }
        is ChatEntry.Tool -> Row(Modifier.padding(vertical = 2.dp), verticalAlignment = Alignment.Top) {
            Text(if (e.ok) "✓" else "✗", fontSize = 10.sp, color = if (e.ok) Color(0xFF7EE3C5) else Color(0xFFFF5252))
            Spacer(Modifier.width(4.dp))
            Column(Modifier.clickable { }) {
                Text(
                    e.name + " · " + e.args.replace("\n", " ").take(70),
                    fontSize = 10.sp, color = Color(0xFF8FA3BC), maxLines = 1
                )
                Text(
                    e.result.replace("\n", " ").take(150),
                    fontSize = 9.sp, color = Color(0xFF60708A), maxLines = 2
                )
            }
        }
    }
}
