package dev.aniforge.ui

import android.content.Context
import android.content.Intent
import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.media3.common.MediaItem
import androidx.media3.common.PlaybackParameters
import androidx.media3.exoplayer.ExoPlayer
import dev.aniforge.domain.*
import dev.aniforge.engine.*
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File

/** Drives the playhead + the two preview players (video + music). */
class PlaybackDriver(private val context: Context, private val store: ProjectStore) {
    val videoPlayer: ExoPlayer = ExoPlayer.Builder(context).build()
    val musicPlayer: ExoPlayer = ExoPlayer.Builder(context).build()
    private var vUri: String? = null
    private var mUri: String? = null
    private var lastVidClip: String? = null
    private var lastMusClip: String? = null

    suspend fun tick() {
        if (!store.playing.value) { videoPlayer.pause(); musicPlayer.pause(); return }
        val p = store.project.value
        val next = store.playheadMs.value + 33
        if (next >= p.durationMs()) {
            store.playing.value = false
            store.playheadMs.value = p.durationMs()
            PreviewState.playheadMs = p.durationMs()
            videoPlayer.pause(); musicPlayer.pause()
            return
        }
        store.playheadMs.value = next
        PreviewState.playheadMs = next

        // video
        val vc = p.clips.firstOrNull { it.kind == "video" && it.contains(next) }
        if (vc == null) videoPlayer.pause()
        else {
            if (vUri != vc.sourceUri) {
                videoPlayer.setMediaItem(MediaItem.fromUri(vc.sourceUri)); videoPlayer.prepare()
                vUri = vc.sourceUri; lastVidClip = null
            }
            val src = vc.sourceTimeMs(next).coerceIn(vc.inMs, vc.outMs - 1)
            if (vc.id != lastVidClip) { videoPlayer.seekTo(src); lastVidClip = vc.id }
            else if (kotlin.math.abs(videoPlayer.currentPosition - src) > 300) videoPlayer.seekTo(src)
            videoPlayer.playbackParameters = PlaybackParameters(vc.speed)
            videoPlayer.volume = if (vc.muted) 0f else vc.volume.coerceIn(0f, 1f)
            videoPlayer.play()
        }

        // music
        val mc = p.clips.firstOrNull { it.track == 2 && it.contains(next) }
        if (mc == null) musicPlayer.pause()
        else {
            if (mUri != mc.sourceUri) {
                musicPlayer.setMediaItem(MediaItem.fromUri(mc.sourceUri)); musicPlayer.prepare()
                mUri = mc.sourceUri; lastMusClip = null
            }
            val src = mc.sourceTimeMs(next).coerceIn(mc.inMs, mc.outMs - 1)
            if (mc.id != lastMusClip) { musicPlayer.seekTo(src); lastMusClip = mc.id }
            else if (kotlin.math.abs(musicPlayer.currentPosition - src) > 300) musicPlayer.seekTo(src)
            musicPlayer.playbackParameters = PlaybackParameters(mc.speed)
            musicPlayer.volume = mc.volume.coerceIn(0f, 1f)
            musicPlayer.play()
        }
    }

    fun scrubTo(t: Long) {
        val p = store.project.value
        val clamped = t.coerceIn(0, p.durationMs())
        store.playheadMs.value = clamped
        PreviewState.playheadMs = clamped
        val vc = p.clips.firstOrNull { it.kind == "video" && it.contains(clamped) }
        if (vc != null) {
            if (vUri != vc.sourceUri) {
                videoPlayer.setMediaItem(MediaItem.fromUri(vc.sourceUri)); videoPlayer.prepare(); vUri = vc.sourceUri
            }
            videoPlayer.seekTo(vc.sourceTimeMs(clamped).coerceIn(vc.inMs, vc.outMs - 1))
            videoPlayer.pause()
        }
        val mc = p.clips.firstOrNull { it.track == 2 && it.contains(clamped) }
        if (mc != null) {
            if (mUri != mc.sourceUri) {
                musicPlayer.setMediaItem(MediaItem.fromUri(mc.sourceUri)); musicPlayer.prepare(); mUri = mc.sourceUri
            }
            musicPlayer.seekTo(mc.sourceTimeMs(clamped).coerceIn(mc.inMs, mc.outMs - 1))
            musicPlayer.pause()
        }
    }

    fun release() {
        runCatching { videoPlayer.release() }
        runCatching { musicPlayer.release() }
    }
}

/** Shared import helper (video → track 0, audio → track 2). */
object Importer {
    suspend fun import(context: Context, store: ProjectStore, uri: Uri): String {
        runCatching {
            context.contentResolver.takePersistableUriPermission(
                uri, android.content.Intent.FLAG_GRANT_READ_URI_PERMISSION
            )
        }
        val uriStr = uri.toString()
        val info = MediaProbe.probe(context, uriStr) ?: return "unreadable file"
        val isAudioOnly = !info.hasVideo
        val id = ClipIdGen.next("c")
        val p = store.project.value
        val track = if (isAudioOnly) 2 else 0
        val end = p.clips.filter { it.track == track }.maxOfOrNull { it.outMsEnd() } ?: 0L
        val clip = Clip(
            id = id,
            track = track,
            kind = if (isAudioOnly) "audio" else "video",
            sourceUri = uriStr,
            srcDurationMs = info.durationMs,
            srcW = info.width,
            srcH = info.height,
            positionMs = end,
            inMs = 0L,
            outMs = info.durationMs.coerceAtLeast(200L),
        )
        store.addClip(clip)
        return "added ${if (isAudioOnly) "music" else "video"} (${info.durationMs / 1000}s)"
    }
}

/** Full-screen landscape editor. */
@Composable
fun EditorScreen(store: ProjectStore, onExit: () -> Unit) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val driver = remember { PlaybackDriver(context, store) }
    val exportService = remember { ExportService(context) }
    val scroll = rememberScrollState()
    val zoom = remember { mutableStateOf(1f) }
    var showAuto by remember { mutableStateOf(false) }
    var showExport by remember { mutableStateOf(false) }
    var showHelp by remember { mutableStateOf(false) }
    var tickUi by remember { mutableIntStateOf(0) }

    // playhead ticker
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

    // keep the GL-side snapshot fresh on any edit / scrub
    LaunchedEffect(store.project.value, store.playheadMs.value) {
        PreviewState.project = store.project.value
        PreviewState.playheadMs = store.playheadMs.value
    }

    // preview GL surface
    val preview = remember {
        PreviewView(context).apply {
            callbacks = object : PreviewView.Callbacks {
                override fun onSurfaceTextureAvailable(
                    st: android.graphics.SurfaceTexture,
                    surface: android.view.Surface,
                ) {
                    driver.videoPlayer.setVideoSurface(surface)
                }
            }
        }
    }

    DisposableEffect(Unit) {
        onDispose { driver.release() }
    }

    val videoPicker = rememberLauncherForActivityResult(ActivityResultContracts.OpenMultipleDocuments()) { uris ->
        scope.launch(Dispatchers.IO) {
            for (u in uris) {
                val msg = Importer.import(context, store, u)
                withContext(Dispatchers.Main) { store.say(msg); tickUi++ }
            }
        }
    }
    val audioPicker = rememberLauncherForActivityResult(ActivityResultContracts.OpenMultipleDocuments()) { uris ->
        scope.launch(Dispatchers.IO) {
            for (u in uris) {
                val msg = Importer.import(context, store, u)
                withContext(Dispatchers.Main) { store.say(msg); tickUi++ }
            }
        }
    }
    val refPicker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) scope.launch {
            val msg = AutoEdit.matchReference(store, context, uri.toString())
            store.say(msg); tickUi++
        }
    }

    Column(Modifier.fillMaxSize().background(Color(0xFF0B0E14))) {
        // ── top bar ──────────────────────────────────────────────────────
        Row(
            Modifier.fillMaxWidth().height(42.dp).background(Color(0xFF12151C)),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Spacer(Modifier.width(8.dp))
            IconBtn("‹") { onExit() }
            Spacer(Modifier.width(6.dp))
            Column(Modifier.weight(1f)) {
                Text(store.project.value.title, fontSize = 14.sp, color = Color.White, maxLines = 1)
                Text(
                    "${store.project.value.clips.size} clips · ${timecode(store.project.value.durationMs())}",
                    fontSize = 9.sp, color = Color(0xFF6B7E95)
                )
            }
            IconBtn("undo") { store.undo(); tickUi++ }
            IconBtn("redo") { store.redo(); tickUi++ }
            IconBtn("auto", showAuto) { showAuto = !showAuto }
            IconBtn("save") { store.save(context); store.say("project saved"); tickUi++ }
            IconBtn("⬇ export", showExport) { showExport = !showExport }
            IconBtn("?", showHelp) { showHelp = !showHelp }
            Spacer(Modifier.width(8.dp))
        }

        Row(Modifier.weight(1f)) {
            // ── left: preview + transport + timeline ────────────────────
            Column(Modifier.weight(0.62f).fillMaxHeight()) {
                Box(
                    Modifier
                        .fillMaxWidth()
                        .weight(1f)
                        .background(Color(0xFF050608))
                ) {
                    AndroidView(factory = { preview }, modifier = Modifier.fillMaxSize())
                    if (store.project.value.allVideoClips().isEmpty()) {
                        Column(
                            Modifier.align(Alignment.Center).clickable { videoPicker.launch(arrayOf("video/*")) },
                            horizontalAlignment = Alignment.CenterHorizontally
                        ) {
                            Text("+ import video", fontSize = 16.sp, color = Color(0xFF7EE3C5))
                            Text("tap to pick from your gallery", fontSize = 10.sp, color = Color(0xFF6B7E95))
                        }
                    }
                }
                // transport
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
                        timecode(store.playheadMs.value) + " / " + timecode(store.project.value.durationMs()),
                        fontSize = 10.sp, color = Color(0xFF9FB2C8), modifier = Modifier.padding(horizontal = 6.dp)
                    )
                    IconBtn("✂") {
                        val id = store.selection.value
                        if (id != null) { store.splitClip(id, store.playheadMs.value); tickUi++ }
                    }
                    IconBtn("🗑") {
                        val id = store.selection.value
                        if (id != null) { store.removeClip(id); tickUi++ }
                    }
                    IconBtn("+video") { videoPicker.launch(arrayOf("video/*")) }
                    IconBtn("+music") { audioPicker.launch(arrayOf("audio/*")) }
                    Spacer(Modifier.weight(1f))
                    Text("zoom", fontSize = 9.sp, color = Color(0xFF6B7E95))
                    androidx.compose.material3.Slider(
                        value = zoom.value,
                        onValueChange = { zoom.value = it },
                        valueRange = 0.4f..6f,
                        modifier = Modifier.width(110.dp).height(24.dp)
                    )
                    Spacer(Modifier.width(8.dp))
                }
                // timeline
                TimelineCanvas(store, zoom.value, scroll) { driver.scrubTo(it) }
                Spacer(Modifier.height(4.dp))
            }

            // ── right: inspector ─────────────────────────────────────────
            Column(
                Modifier
                    .weight(0.38f)
                    .fillMaxHeight()
                    .background(Color(0xFF0E1219))
            ) {
                Inspector(store, store.playheadMs.value) { tickUi++ }
                if (showAuto) AutoPanel(store, scope, context, refPicker)
                if (showHelp) HelpPanel()
            }
        }
    }

    if (showExport) {
        ExportDialog(store, exportService, onDismiss = { showExport = false })
    }
}

@Composable
private fun AutoPanel(
    store: ProjectStore,
    scope: kotlinx.coroutines.CoroutineScope,
    context: Context,
    refPicker: androidx.activity.compose.ManagedActivityResultLauncher<Array<String>, List<Uri>>,
) {
    Column(Modifier.fillMaxWidth().background(Color(0xFF141A24))) {
        SectionLabel("auto edit")
        Row(
            Modifier.fillMaxWidth().padding(horizontal = 8.dp),
            horizontalArrangement = Arrangement.spacedBy(5.dp)
        ) {
            IconBtn("⧉ match edit") { refPicker.launch(arrayOf("video/*")) }
            IconBtn("⚡ beat cut") {
                val id = store.selection.value ?: store.project.value.videoTrackClips().firstOrNull()?.id
                if (id != null) scope.launch {
                    val msg = Editor.beatCut(store, id, context); store.say(msg)
                }
            }
            IconBtn("flash") { Editor.flashBeat(store) }
        }
        Row(
            Modifier.fillMaxWidth().padding(horizontal = 8.dp, vertical = 2.dp),
            horizontalArrangement = Arrangement.spacedBy(5.dp)
        ) {
            IconBtn("pastel soft") { AutoEdit.softEdit(store) }
            IconBtn("4:5 sns") { AutoEdit.snsTikTok(store) }
            IconBtn("vivid") { Editor.applyGrade(store, ColorGrade.VIVID) }
        }
        // log
        Column(Modifier.padding(horizontal = 10.dp, vertical = 4.dp)) {
            store.log.take(3).forEach {
                Text("· $it", fontSize = 9.sp, color = Color(0xFF6B7E95), maxLines = 1)
            }
        }
    }
}

@Composable
private fun HelpPanel() {
    Column(Modifier.fillMaxWidth().background(Color(0xFF101722)).padding(8.dp)) {
        Text("quick help", fontSize = 11.sp, color = Color(0xFF7EE3C5))
        Text(
            "import → drag clips on V1/V2, music on M · " +
                    "select clip → cut/motion/look/text/audio · " +
                    "motion tab = keyframes + motion graph · " +
                    "auto = one-tap edits · export = real MP4.",
            fontSize = 9.sp, color = Color(0xFF8FA3BC)
        )
    }
}

@Composable
private fun ExportDialog(store: ProjectStore, service: ExportService, onDismiss: () -> Unit) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var preset by remember { mutableStateOf(Exporter.P720) }
    var progress by remember { mutableStateOf(-1f) }
    var result by remember { mutableStateOf<File?>(null) }
    var error by remember { mutableStateOf<String?>(null) }

    AlertDialog(
        onDismissRequest = { if (progress < 0f) onDismiss() },
        title = { Text("Export MP4", fontSize = 16.sp, color = Color.White) },
        text = {
            Column {
                Row(horizontalArrangement = Arrangement.spacedBy(5.dp)) {
                    SmallChip("1080p", preset == Exporter.P1080) { preset = Exporter.P1080 }
                    SmallChip("720p", preset == Exporter.P720) { preset = Exporter.P720 }
                    SmallChip("480p", preset == Exporter.P480) { preset = Exporter.P480 }
                }
                Spacer(Modifier.height(10.dp))
                when {
                    progress >= 0f && result == null && error == null -> {
                        LinearProgressIndicator(progress = { progress }, modifier = Modifier.fillMaxWidth())
                        Text("rendering… ${(progress * 100).toInt()}%", fontSize = 11.sp, color = Color.Gray)
                        TextButton(onClick = { service.cancel() }) { Text("cancel", color = Color(0xFFFF5252)) }
                    }
                    result != null -> {
                        Text("done → ${result!!.name}", fontSize = 11.sp, color = Color(0xFF7EE3C5))
                        TextButton(onClick = {
                            val f = result!!
                            val share = Intent(Intent.ACTION_SEND).apply {
                                type = "video/mp4"
                                val u = androidx.core.content.FileProvider.getUriForFile(
                                    context, context.packageName + ".files", f)
                                putExtra(Intent.EXTRA_STREAM, u)
                                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                            }
                            context.startActivity(Intent.createChooser(share, "Share video"))
                        }) { Text("share", color = Color(0xFF7EE3C5)) }
                    }
                    error != null -> {
                        Text("failed: $error", fontSize = 11.sp, color = Color(0xFFFF5252))
                    }
                    else -> {
                        Text(
                            "Renders your timeline to a real MP4 (cut · crop · keyframes · grades · music mix).",
                            fontSize = 11.sp, color = Color.Gray
                        )
                        TextButton(onClick = {
                            progress = 0f
                            scope.launch {
                                service.export(store, preset, { p -> progress = p }, { file, err ->
                                    result = file; error = err
                                    if (file != null) store.say("exported ${file.name}")
                                })
                            }
                        }) { Text("start render", color = Color(0xFF7EE3C5)) }
                    }
                }
            }
        },
        confirmButton = {},
        dismissButton = {
            TextButton(onClick = { if (progress < 0f || result != null || error != null) onDismiss() }) {
                Text("close", color = Color.Gray)
            }
        }
    )
}
