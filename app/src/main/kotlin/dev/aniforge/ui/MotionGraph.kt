package dev.aniforge.ui

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.*
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import dev.aniforge.domain.*
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min

/**
 * Motion graph — draggable keyframe curve editor for one transform property
 * (scale / rotate / position / opacity / volume / brightness).
 *  Tap empty = add keyframe · drag point = move · long-press point = delete.
 */
@Composable
fun MotionGraph(store: ProjectStore, prop: Prop, modifier: Modifier = Modifier) {
    val clip = store.selected
    if (clip == null) {
        Text("select a clip first", fontSize = 11.sp, color = Color.Gray, modifier = modifier.padding(8.dp))
        return
    }

    val tMax = max(0.5f, clip.durationMs() / 1000f)
    var working by remember(clip.id, prop) { mutableStateOf<List<KeyPoint>?>(null) }
    var selectedIdx by remember(clip.id, prop) { mutableStateOf<Int?>(null) }

    val basePoints = working ?: (clip.transforms[prop.key]?.points ?: listOf(KeyPoint(0f, prop.def)))

    Column(modifier.fillMaxWidth()) {
        Row(
            Modifier.fillMaxWidth().padding(horizontal = 8.dp),
            horizontalArrangement = Arrangement.SpaceBetween
        ) {
            Text("${prop.label}  ·  ${(tMax).format1()}s", fontSize = 11.sp, color = Color(0xFF9FB2C8))
            Text(
                if (selectedIdx != null) "drag to move · long-press to delete"
                else "tap curve to add a keyframe",
                fontSize = 10.sp, color = Color(0xFF6B7E95)
            )
        }

        Canvas(
            Modifier
                .fillMaxWidth()
                .height(140.dp)
                .padding(horizontal = 8.dp, vertical = 4.dp)
                .pointerInput(clip.id, prop, tMax) {
                    detectTapGestures(
                        onTap = { o ->
                            val (t, v) = mapTo(o, size.width.toFloat(), size.height.toFloat(), tMax, prop)
                            val hit = hitTest(basePoints, o, size.width.toFloat(), size.height.toFloat(), tMax, prop)
                            if (hit != null) {
                                selectedIdx = hit
                            } else {
                                val pts = basePoints + KeyPoint(t, v)
                                commit(store, clip.id, prop, pts)
                                working = null
                                selectedIdx = null
                            }
                        },
                        onLongPress = { o ->
                            val hit = hitTest(basePoints, o, size.width.toFloat(), size.height.toFloat(), tMax, prop)
                            if (hit != null && basePoints.size > 1) {
                                commit(store, clip.id, prop, basePoints.filterIndexed { i, _ -> i != hit })
                                working = null; selectedIdx = null
                            }
                        }
                    )
                }
                .pointerInput(clip.id, prop, tMax) {
                    detectDragGestures(
                        onDragStart = { o ->
                            if (selectedIdx == null) {
                                selectedIdx = hitTest(basePoints, o, size.width.toFloat(), size.height.toFloat(), tMax, prop)
                            }
                            if (selectedIdx != null) store.checkpoint()
                        },
                        onDrag = { ch, amt ->
                            ch.consume()
                            val idx = selectedIdx ?: return@detectDragGestures
                            if (idx >= basePoints.size) return@detectDragGestures
                            val pos = pointOf(basePoints[idx], size.width.toFloat(), size.height.toFloat(), tMax, prop) + amt
                            val (t, v) = mapTo(pos, size.width.toFloat(), size.height.toFloat(), tMax, prop)
                            // keep order stable while dragging; sort on commit
                            working = basePoints.mapIndexed { i, p ->
                                if (i == idx) p.copy(t = t.coerceIn(0f, tMax), v = v) else p
                            }
                        },
                        onDragEnd = {
                            working?.let { commit(store, clip.id, prop, it) }
                            working = null
                            store.closeCheckpoint()
                        },
                        onDragCancel = { working = null; store.closeCheckpoint() }
                    )
                }
        ) {
            drawGraph(this, basePoints, tMax, prop, selectedIdx)
        }

        Row(Modifier.padding(horizontal = 8.dp), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            SmallChip("reset") {
                commit(store, clip.id, prop, listOf(KeyPoint(0f, prop.def), KeyPoint(max(0.2f, tMax), prop.def)))
                selectedIdx = null
            }
            SmallChip("clear") {
                commit(store, clip.id, prop, listOf(KeyPoint(0f, prop.def), KeyPoint(max(0.2f, tMax), prop.def)))
                selectedIdx = null
            }
        }
        Spacer(Modifier.height(2.dp))
    }
}

private fun commit(store: ProjectStore, clipId: String, prop: Prop, pts: List<KeyPoint>) {
    store.updateClip(clipId) { c ->
        c.copy(transforms = c.transforms + (prop.key to MotionTrack(pts.sortedBy { it.t })))
    }
}

private fun padX() = 34f
private fun padY() = 12f

private fun pointOf(p: KeyPoint, w: Float, h: Float, tMax: Float, prop: Prop): Offset {
    val x = padX() + (p.t / tMax) * (w - 2 * padX())
    val fy = ((p.v - prop.lo) / (prop.hi - prop.lo)).coerceIn(0f, 1f)
    val y = padY() + (1f - fy) * (h - 2 * padY())
    return Offset(x, y)
}

private fun mapTo(o: Offset, w: Float, h: Float, tMax: Float, prop: Prop): Pair<Float, Float> {
    val t = (((o.x - padX()) / (w - 2 * padX())) * tMax).coerceIn(0f, tMax)
    val fy = 1f - ((o.y - padY()) / (h - 2 * padY())).coerceIn(0f, 1f)
    val v = prop.lo + fy * (prop.hi - prop.lo)
    return t to v
}

private fun hitTest(points: List<KeyPoint>, o: Offset, w: Float, h: Float, tMax: Float, prop: Prop): Int? {
    var best = -1; var bestD = 40f
    points.forEachIndexed { i, p ->
        val d = (pointOf(p, w, h, tMax, prop) - o).getDistance()
        if (d < bestD) { bestD = d; best = i }
    }
    return best.takeIf { it >= 0 }
}

private fun drawGraph(
    c: androidx.compose.ui.graphics.drawscope.DrawScope,
    points: List<KeyPoint>,
    tMax: Float,
    prop: Prop,
    selected: Int?,
) {
    val w = c.size.width; val h = c.size.height
    // frame + grid
    c.drawLine(Color(0xFF39465A), Offset(padX(), padY()), Offset(padX(), h - padY()), 1.5f)
    c.drawLine(Color(0xFF39465A), Offset(padX(), h - padY()), Offset(w - padX(), h - padY()), 1.5f)
    for (g in 1..3) {
        val y = padY() + (h - 2 * padY()) * g / 4f
        c.drawLine(Color(0x22FFFFFF), Offset(padX(), y), Offset(w - padX(), y), 1f)
    }
    // labels
    val p = android.graphics.Paint().apply {
        color = android.graphics.Color.argb(200, 159, 178, 200); textSize = 22f; isAntiAlias = true
    }
    val nc = c.drawContext.canvas.nativeCanvas
    nc.drawText("${prop.hi.format1()}", 4f, padY() + 16f, p)
    nc.drawText("${prop.lo.format1()}", 4f, h - padY() - 4f, p)

    // curve
    val track = MotionTrack(points)
    if (points.isEmpty()) return
    val steps = 120
    var prev: Offset? = null
    for (i in 0..steps) {
        val t = tMax * i / steps
        val v = track.sample(t)
        val pt = pointOf(KeyPoint(t, v), w, h, tMax, prop)
        prev?.let { c.drawLine(Color(0xFFFF4D6D), it, pt, 2.5f) }
        prev = pt
    }
    // keyframes
    points.forEachIndexed { i, k ->
        val pt = pointOf(k, w, h, tMax, prop)
        c.drawCircle(if (i == selected) Color(0xFFFFD54F) else Color(0xFF7EE3C5), if (i == selected) 8f else 6f, pt)
    }
}

private fun Float.format1(): String = "%.1f".format(this)
