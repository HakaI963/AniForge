package dev.aniforge.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Slider
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

/** Compact chip button. */
@Composable
fun SmallChip(
    label: String,
    active: Boolean = false,
    modifier: Modifier = Modifier,
    onClick: () -> Unit,
) {
    Box(
        modifier
            .clip(RoundedCornerShape(20.dp))
            .background(if (active) Color(0xFFFF4D6D) else Color(0xFF232D3F))
            .clickable { onClick() }
            .padding(horizontal = 10.dp, vertical = 5.dp)
    ) {
        Text(label, fontSize = 11.sp, color = if (active) Color.White else Color(0xFFC9D6E5), maxLines = 1)
    }
}

/** Label + slider row. When a store is provided, drags become one undo checkpoint. */
@Composable
fun SliderRow(
    label: String,
    value: Float,
    range: ClosedFloatingPointRange<Float>,
    onChange: (Float) -> Unit,
    store: dev.aniforge.domain.ProjectStore? = null,
) {
    Column(Modifier.fillMaxWidth().padding(horizontal = 8.dp, vertical = 2.dp)) {
        Text("$label  ${"%.2f".format(value)}", fontSize = 11.sp, color = Color(0xFF9FB2C8))
        Slider(
            value = value.coerceIn(range.start, range.endInclusive),
            onValueChange = { v ->
                if (store != null) store.checkpoint()
                onChange(v)
            },
            onValueChangeFinished = { store?.closeCheckpoint() },
            valueRange = range,
            modifier = Modifier.height(26.dp)
        )
    }
}

@Composable
fun SectionLabel(text: String) {
    Text(
        text.uppercase(),
        fontSize = 10.sp,
        color = Color(0xFF6B7E95),
        letterSpacing = 1.5.sp,
        modifier = Modifier.padding(start = 8.dp, top = 8.dp, bottom = 2.dp)
    )
}

/** Toolbar icon-ish button. */
@Composable
fun IconBtn(label: String, active: Boolean = false, onClick: () -> Unit) {
    Box(
        Modifier
            .clip(RoundedCornerShape(8.dp))
            .background(if (active) Color(0xFFFF4D6D) else Color(0xFF232D3F))
            .clickable { onClick() }
            .padding(horizontal = 9.dp, vertical = 6.dp)
    ) {
        Text(label, fontSize = 13.sp, color = Color.White, textAlign = TextAlign.Center)
    }
}

/** Simple one-line helper to show timecodes. */
fun timecode(ms: Long): String {
    val s = ms / 1000
    return "%02d:%02d.%02d".format(s / 60, s % 60, (ms % 1000) / 10)
}
