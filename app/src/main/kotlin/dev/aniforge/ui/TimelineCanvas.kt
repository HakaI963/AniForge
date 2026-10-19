package dev.aniforge.ui

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.nativeCanvas
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import dev.aniforge.domain.Clip
import dev.aniforge.domain.ProjectStore
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min

private val VideoA = Color(0xFFE2447A)
private val VideoB = Color(0xFF4A8FE2)
private val MusicC = Color(0xFF3FBF7F)
private val PlayheadC = Color(0xFFFF5252)

/** Multi-track timeline. Tap = select · drag = move (+vertical → switch track) ·
 *  edge handles (selected) = trim · ruler drag = scrub. */
@Composable
fun TimelineCanvas(
    store: ProjectStore,
    zoom: Float,
    scrollState: androidx.compose.foundation.ScrollState,
    onSeek: (Long) -> Unit = {},
) {
    val density = LocalDensity.current
    val p = store.project.value
    val ppsDp = 56f * zoom
    val totalMs = p.durationMs()
    val contentW = (totalMs / 1000f * ppsDp).coerceAtLeast(320f) + 24f

    Box(
        Modifier
            .fillMaxWidth()
            .height(132.dp)
            .horizontalScroll(scrollState)
    ) {
        Column(Modifier.width(with(density) { contentW.dp })) {
            // ruler + scrub
            Box(
                Modifier
                    .fillMaxWidth()
                    .height(22.dp)
                    .pointerInput(ppsDp, totalMs) {
                        detectTapGestures { o ->
                            val pps = with(density) { ppsDp.dp.toPx() }
                            onSeek((o.x / pps * 1000).toLong().coerceIn(0, totalMs))
                        }
                    }
                    .pointerInput(ppsDp, totalMs) {
                        detectDragGestures { ch, amt ->
                            ch.consume()
                            val pps = with(density) { ppsDp.dp.toPx() }
                            val t = store.playheadMs.value + (amt.x / pps * 1000).toLong()
                            onSeek(t.coerceIn(0, totalMs))
                        }
                    }
            ) {
                RulerCanvas(totalMs)
                // marker flags (agent-manageable)
                Canvas(Modifier.fillMaxSize()) {
                    val pps = size.width / (totalMs / 1000f).coerceAtLeast(1f)
                    val paint = android.graphics.Paint().apply {
                        color = android.graphics.Color.rgb(0xFF, 0xD5, 0x4F)
                        textSize = 26f
                        isAntiAlias = true
                    }
                    val nc = drawContext.canvas.nativeCanvas
                    for (m in store.project.value.sortedMarkers()) {
                        val x = m.tMs / 1000f * pps
                        nc.drawLine(x, 0f, x, size.height - 2f, paint)
                        if (m.note.isNotBlank()) nc.drawText("⚑", x + 2f, 26f, paint)
                    }
                }
            }

            TrackRow(store, 0, VideoA, ppsDp)
            TrackRow(store, 1, VideoB, ppsDp)
            TrackRow(store, 2, MusicC, ppsDp)
        }
        // playhead spans ruler + tracks
        Box(
            Modifier
                .offset(x = (store.playheadMs.value / 1000f * ppsDp).dp)
                .width(2.dp)
                .height(132.dp)
                .background(PlayheadC)
        )
    }
}

@Composable
private fun TrackRow(store: ProjectStore, track: Int, tint: Color, ppsDp: Float) {
    val density = LocalDensity.current
    var shiftMs by remember { mutableStateOf(0L) }   // visual offset while dragging

    Row(Modifier.height(36.dp), verticalAlignment = Alignment.CenterVertically) {
        var cursor = 0L
        val clips = store.project.value.clips.filter { it.track == track }.sortedBy { it.positionMs }
        for (clip in clips) {
            val gap = (clip.positionMs - cursor).coerceAtLeast(0L)
            if (gap > 0) Spacer(Modifier.width((gap / 1000f * ppsDp).dp))
            val selected = clip.id == store.selection.value
            val visible = clip.copy(positionMs = clip.positionMs + shiftMs)

            Box(
                Modifier
                    .offset(x = (shiftMs / 1000f * ppsDp).dp)
                    .width((visible.durationMs() / 1000f * ppsDp).dp.coerceAtLeast(14.dp))
                    .height(30.dp)
                    .clip(RoundedCornerShape(6.dp))
                    .background(if (selected) tint.copy(alpha = 0.95f) else tint.copy(alpha = 0.60f))
                    .clickable { store.selection.value = clip.id }
                    .pointerInput(clip.id, ppsDp) {
                        var acc = 0f
                        var switched = false
                        detectDragGestures(
                            onDragStart = {
                                acc = 0f; switched = false; shiftMs = 0L
                                store.checkpoint()
                            },
                            onDrag = { ch, amt ->
                                ch.consume()
                                if (!switched && clip.kind == "video" && abs(amt.y) > 55f) {
                                    switched = true
                                    val to = if (amt.y > 0) min(1, clip.track + 1) else max(0, clip.track - 1)
                                    if (to != clip.track) store.moveClip(clip.id, clip.positionMs, to)
                                    shiftMs = 0L
                                } else if (!switched) {
                                    acc += amt.x
                                    val pps = with(density) { ppsDp.dp.toPx() }
                                    shiftMs = (acc / pps * 1000).toLong()
                                }
                            },
                            onDragEnd = {
                                if (!switched && shiftMs != 0L) {
                                    store.patch { pr ->
                                        pr.copy(clips = pr.clips.map {
                                            if (it.id == clip.id) it.copy(positionMs = (clip.positionMs + shiftMs).coerceAtLeast(0L)) else it
                                        })
                                    }
                                }
                                store.closeCheckpoint()
                                shiftMs = 0L
                            },
                            onDragCancel = { store.closeCheckpoint(); shiftMs = 0L }
                        )
                    }
            ) {
                Text(
                    when {
                        clip.kind == "audio" -> "♪"
                        clip.kind == "image" -> "◧ card"
                        else -> clip.title.ifEmpty { "clip" }
                    },
                    fontSize = 9.sp, color = Color.White, maxLines = 1,
                    modifier = Modifier.padding(start = 5.dp, top = 2.dp)
                )
                if (selected) {
                    Box(
                        Modifier.align(Alignment.CenterStart).width(13.dp).height(30.dp)
                            .background(Color(0xFFFFD54F))
                            .pointerInput(clip.id) {
                                detectDragGestures(
                                    onDragStart = { store.checkpoint() },
                                    onDrag = { ch, amt ->
                                        ch.consume()
                                        val pps = with(density) { ppsDp.dp.toPx() }
                                        val dMs = (amt.x / pps * 1000).toLong()
                                        store.patch { pr ->
                                            pr.copy(clips = pr.clips.map {
                                                if (it.id == clip.id)
                                                    it.copy(inMs = (clip.inMs + dMs).coerceIn(0L, clip.outMs - 100L))
                                                else it
                                            })
                                        }
                                    },
                                    onDragEnd = { store.closeCheckpoint() },
                                    onDragCancel = { store.closeCheckpoint() }
                                )
                            }
                    )
                    Box(
                        Modifier.align(Alignment.CenterEnd).width(13.dp).height(30.dp)
                            .background(Color(0xFFEF5350))
                            .pointerInput(clip.id) {
                                detectDragGestures(
                                    onDragStart = { store.checkpoint() },
                                    onDrag = { ch, amt ->
                                        ch.consume()
                                        val pps = with(density) { ppsDp.dp.toPx() }
                                        val dMs = (amt.x / pps * 1000).toLong()
                                        store.patch { pr ->
                                            pr.copy(clips = pr.clips.map {
                                                if (it.id == clip.id)
                                                    it.copy(outMs = (clip.outMs + dMs).coerceIn(clip.inMs + 100L, clip.srcDurationMs))
                                                else it
                                            })
                                        }
                                    },
                                    onDragEnd = { store.closeCheckpoint() },
                                    onDragCancel = { store.closeCheckpoint() }
                                )
                            }
                    )
                }
            }
            cursor = clip.positionMs + clip.durationMs()
        }
    }
}

@Composable
private fun RulerCanvas(widthMs: Long) {
    Canvas(Modifier.fillMaxSize()) {
        val paint = android.graphics.Paint().apply {
            color = android.graphics.Color.GRAY
            textSize = 26f
            isAntiAlias = true
        }
        val nc = drawContext.canvas.nativeCanvas
        val pxPerSec = size.width / (widthMs / 1000f).coerceAtLeast(1f)
        val stepSec = if (widthMs > 120_000) 30 else if (widthMs > 40_000) 10 else 5
        var sec = 0L
        while (sec * 1000 <= widthMs) {
            val x = sec * pxPerSec
            nc.drawLine(x, size.height - 6f, x, size.height, paint)
            if (sec % (stepSec * 2) == 0L) nc.drawText("$sec", x + 3f, 12f, paint)
            sec += stepSec
        }
    }
}
