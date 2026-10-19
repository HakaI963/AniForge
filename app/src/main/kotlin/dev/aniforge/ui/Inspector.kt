package dev.aniforge.ui

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import dev.aniforge.domain.*
import dev.aniforge.engine.AutoEdit
import kotlinx.coroutines.launch

/** Right-hand inspector: Cut · Motion · Look · Text · Audio. */
@Composable
fun Inspector(store: ProjectStore, playheadMs: Long, refresh: () -> Unit) {
    val clip = store.selected
    val tab = remember { mutableStateOf("cut") }
    val scope = rememberCoroutineScope()

    LazyColumn(Modifier.fillMaxSize()) {
        if (clip == null) {
            item {
                SectionLabel("Inspector")
                Text(
                    "tap a clip on the timeline to edit it",
                    fontSize = 11.sp, color = Color.Gray,
                    modifier = Modifier.padding(8.dp)
                )
            }
            return@LazyColumn
        }

        item {
            Row(
                Modifier.fillMaxWidth().padding(horizontal = 8.dp, vertical = 4.dp),
                horizontalArrangement = Arrangement.spacedBy(5.dp)
            ) {
                SmallChip("cut", tab.value == "cut") { tab.value = "cut" }
                SmallChip("motion", tab.value == "motion") { tab.value = "motion" }
                SmallChip("look", tab.value == "look") { tab.value = "look" }
                SmallChip("text", tab.value == "text") { tab.value = "text" }
                SmallChip("audio", tab.value == "audio") { tab.value = "audio" }
            }
        }

        when (tab.value) {
            "cut" -> cutTab(store, clip, playheadMs)
            "motion" -> {
                item {
                    var prop by remember { mutableStateOf(Prop.SCALE) }
                    Row(
                        Modifier.fillMaxWidth().padding(horizontal = 8.dp, vertical = 4.dp),
                        horizontalArrangement = Arrangement.spacedBy(4.dp)
                    ) {
                        SmallChip("scale", prop == Prop.SCALE) { prop = Prop.SCALE }
                        SmallChip("rotate", prop == Prop.ROT) { prop = Prop.ROT }
                        SmallChip("posX", prop == Prop.POSX) { prop = Prop.POSX }
                        SmallChip("posY", prop == Prop.POSY) { prop = Prop.POSY }
                        SmallChip("opacity", prop == Prop.OPACITY) { prop = Prop.OPACITY }
                        SmallChip("vol", prop == Prop.VOLUME) { prop = Prop.VOLUME }
                        SmallChip("bright", prop == Prop.BRIGHT) { prop = Prop.BRIGHT }
                    }
                    MotionGraph(store, prop)
                }
            }
            "look" -> lookTab(store, clip)
            "text" -> textTab(store, clip)
            "audio" -> audioTab(store, clip, scope, refresh)
        }
    }
}

private fun androidx.compose.foundation.lazy.LazyListScope.cutTab(
    store: ProjectStore,
    clip: Clip,
    playheadMs: Long,
) {
    item {
        SectionLabel("cut · ${timecode(clip.inMs)} → ${timecode(clip.outMs)}")
        Row(
            Modifier.fillMaxWidth().padding(horizontal = 8.dp),
            horizontalArrangement = Arrangement.spacedBy(5.dp)
        ) {
            IconBtn("✂ split") { store.splitClip(clip.id, playheadMs); refresh() }
            IconBtn("⧉ dup") { store.duplicate(clip.id); refresh() }
            IconBtn("🗑 del") { store.removeClip(clip.id); refresh() }
        }
        Row(
            Modifier.fillMaxWidth().padding(horizontal = 8.dp, vertical = 4.dp),
            horizontalArrangement = Arrangement.spacedBy(5.dp)
        ) {
            IconBtn("in −0.1s") { nudgeIn(store, clip, -100) }
            IconBtn("in +0.1s") { nudgeIn(store, clip, +100) }
            IconBtn("out −0.1s") { nudgeOut(store, clip, -100) }
            IconBtn("out +0.1s") { nudgeOut(store, clip, +100) }
        }
        SectionLabel("speed")
        var speed by remember(clip.id) { mutableStateOf(clip.speed) }
        SliderRow("speed ×", speed, 0.25f..4f, onChange = { speed = it; AutoEdit.speed(store, it); refresh() }, store = store)
        SectionLabel("position")
        Row(
            Modifier.fillMaxWidth().padding(horizontal = 8.dp),
            horizontalArrangement = Arrangement.spacedBy(5.dp)
        ) {
            IconBtn("⟵ to start") { store.moveClip(clip.id, 0L) }
            IconBtn("⟶ to end") {
                val end = store.project.value.clips.filter { it.track == clip.track && it.id != clip.id }
                    .maxOfOrNull { it.outMsEnd() } ?: 0L
                store.moveClip(clip.id, end)
            }
            IconBtn("↔ snap prev") { AutoEdit.snapAfterPrevious(store) }
        }
    }
}

private fun nudgeIn(store: ProjectStore, clip: Clip, dMs: Long) {
    store.updateClip(clip.id) { c -> c.copy(inMs = (c.inMs + dMs).coerceIn(0L, c.outMs - 100L)) }
}

private fun nudgeOut(store: ProjectStore, clip: Clip, dMs: Long) {
    store.updateClip(clip.id) { c -> c.copy(outMs = (c.outMs + dMs).coerceIn(c.inMs + 100L, c.srcDurationMs)) }
}

private fun androidx.compose.foundation.lazy.LazyListScope.lookTab(store: ProjectStore, clip: Clip) {
    item {
        SectionLabel("crop / ratio")
        Row(
            Modifier.fillMaxWidth().padding(horizontal = 8.dp),
            horizontalArrangement = Arrangement.spacedBy(4.dp)
        ) {
            SmallChip("free", clip.crop == CropMode.FREE) { AutoEdit.crop(store, CropMode.FREE) }
            SmallChip("1:1", clip.crop == CropMode.R1_1) { AutoEdit.crop(store, CropMode.R1_1) }
            SmallChip("4:5", clip.crop == CropMode.R4_5) { AutoEdit.crop(store, CropMode.R4_5) }
            SmallChip("9:16", clip.crop == CropMode.R9_16) { AutoEdit.crop(store, CropMode.R9_16) }
            SmallChip("16:9", clip.crop == CropMode.R16_9) { AutoEdit.crop(store, CropMode.R16_9) }
        }
        SectionLabel("color grade")
        Row(
            Modifier.fillMaxWidth().padding(horizontal = 8.dp),
            horizontalArrangement = Arrangement.spacedBy(4.dp)
        ) {
            SmallChip("none", clip.color == ColorGrade.NONE) { AutoEdit.color(store, ColorGrade.NONE) }
            SmallChip("warm", clip.color == ColorGrade.WARM) { AutoEdit.color(store, ColorGrade.WARM) }
            SmallChip("cool", clip.color == ColorGrade.COOL) { AutoEdit.color(store, ColorGrade.COOL) }
            SmallChip("noir", clip.color == ColorGrade.NOIR) { AutoEdit.color(store, ColorGrade.NOIR) }
            SmallChip("pastel", clip.color == ColorGrade.PASTEL) { AutoEdit.color(store, ColorGrade.PASTEL) }
            SmallChip("vivid", clip.color == ColorGrade.VIVID) { AutoEdit.color(store, ColorGrade.VIVID) }
        }
        var b by remember(clip.id) { mutableStateOf(clip.brightness) }
        var c by remember(clip.id) { mutableStateOf(clip.contrast) }
        var s by remember(clip.id) { mutableStateOf(clip.saturation) }
        SliderRow("brightness", b, -0.6f..0.6f, onChange = { b = it; AutoEdit.bright(store, it) }, store = store)
        SliderRow("contrast", c, 0.5f..1.6f, onChange = { c = it; AutoEdit.contrast(store, it) }, store = store)
        SliderRow("saturation", s, 0f..2f, onChange = { s = it; AutoEdit.saturation(store, it) }, store = store)
    }
}

private fun androidx.compose.foundation.lazy.LazyListScope.textTab(store: ProjectStore, clip: Clip) {
    item {
        SectionLabel("title overlay")
        androidx.compose.material3.OutlinedTextField(
            value = clip.title,
            onValueChange = { txt ->
                store.patch { p ->
                    p.copy(clips = p.clips.map {
                        if (it.id == clip.id) it.copy(title = txt.take(48)) else it
                    })
                }
            },
            placeholder = { Text("title text", fontSize = 12.sp) },
            modifier = Modifier.fillMaxWidth().padding(horizontal = 8.dp),
            singleLine = true,
            textStyle = androidx.compose.ui.text.TextStyle(fontSize = 13.sp, color = Color.White)
        )
        var ts by remember(clip.id) { mutableStateOf(clip.titleSize) }
        SliderRow("title size", ts, 0.04f..0.30f, onChange = { v ->
            ts = v
            store.patch { p ->
                p.copy(clips = p.clips.map { if (it.id == clip.id) it.copy(titleSize = v) else it })
            }
        }, store = store)
        Row(
            Modifier.fillMaxWidth().padding(horizontal = 8.dp),
            horizontalArrangement = Arrangement.spacedBy(4.dp)
        ) {
            listOf("#FFFFFFFF" to "white", "#FF111111" to "black", "#FFFFD54F" to "gold",
                "#FFFF5252" to "red", "#FF7EE3C5" to "mint").forEach { (hex, name) ->
                SmallChip(name, clip.titleColor == hex) {
                    store.updateClip(clip.id) { c -> c.copy(titleColor = hex) }
                }
            }
        }
    }
}

private fun androidx.compose.foundation.lazy.LazyListScope.audioTab(
    store: ProjectStore,
    clip: Clip,
    scope: kotlinx.coroutines.CoroutineScope,
    refresh: () -> Unit,
) {
    item {
        SectionLabel("audio")
        var v by remember(clip.id) { mutableStateOf(clip.volume) }
        SliderRow("volume", v, 0f..2f, onChange = { vol -> v = vol; AutoEdit.volume(store, vol) }, store = store)
        Row(
            Modifier.fillMaxWidth().padding(horizontal = 8.dp),
            horizontalArrangement = Arrangement.spacedBy(5.dp)
        ) {
            SmallChip("mute", clip.muted) { AutoEdit.mute(store, !clip.muted) }
            SmallChip("unmute", !clip.muted) { AutoEdit.mute(store, false) }
            SmallChip("crossfades") { Editor.crossfades(store); refresh() }
        }
        SectionLabel("motion helpers")
        Row(
            Modifier.fillMaxWidth().padding(horizontal = 8.dp),
            horizontalArrangement = Arrangement.spacedBy(5.dp)
        ) {
            SmallChip("fade in/out") { store.autoFade() }
            SmallChip("push-in") { store.autoZoomIn() }
            SmallChip("ken burns ×1.8") { Editor.kenBurns(store, clip.id) }
        }
    }
}
