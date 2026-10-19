package dev.aniforge.ui

import android.content.Context
import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import dev.aniforge.domain.Project
import dev.aniforge.domain.ProjectStore
import dev.aniforge.domain.newProject
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** Project lobby: create, import media, load saved projects. */
@Composable
fun HomeScreen(store: ProjectStore, onOpenEditor: () -> Unit) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var title by remember { mutableStateOf(store.project.value.title) }
    var saved by remember { mutableStateOf(store.listSaved(context)) }
    var status by remember { mutableStateOf("") }

    val videoPicker = rememberLauncherForActivityResult(ActivityResultContracts.OpenMultipleDocuments()) { uris ->
        scope.launch(Dispatchers.IO) {
            val msgs = uris.mapNotNull { u -> Importer.import(context, store, u) }
            withContext(Dispatchers.Main) {
                status = msgs.joinToString(" · ")
                store.say(status)
                onOpenEditor()
            }
        }
    }
    val audioPicker = rememberLauncherForActivityResult(ActivityResultContracts.OpenMultipleDocuments()) { uris ->
        scope.launch(Dispatchers.IO) {
            val msgs = uris.mapNotNull { u -> Importer.import(context, store, u) }
            withContext(Dispatchers.Main) { status = msgs.joinToString(" · "); onOpenEditor() }
        }
    }

    Column(
        Modifier
            .fillMaxSize()
            .background(Color(0xFF0B0E14))
            .verticalScroll(rememberScrollState())
            .padding(28.dp)
    ) {
        Text(
            "AniForge Studio",
            fontSize = 34.sp,
            color = MaterialTheme.colorScheme.primary,
        )
        Text(
            "full video editor · cut · crop · keyframes · motion graph · auto-edit · MP4 export",
            fontSize = 12.sp, color = Color(0xFF8FA3BC)
        )
        Spacer(Modifier.height(24.dp))

        Row(verticalAlignment = Alignment.CenterVertically) {
            OutlinedTextField(
                value = title,
                onValueChange = {
                    title = it
                    store.commit { p -> p.copy(title = it.take(48)) }
                },
                label = { Text("project name") },
                textStyle = TextStyle(fontSize = 14.sp, color = Color.White),
                modifier = Modifier.weight(1f)
            )
            Spacer(Modifier.width(12.dp))
            Box(
                Modifier
                    .clip(RoundedCornerShape(10.dp))
                    .background(MaterialTheme.colorScheme.primary)
                    .clickable {
                        if (store.project.value.clips.isEmpty()) {
                            status = "import at least one video first"
                        } else onOpenEditor()
                    }
                    .padding(horizontal = 18.dp, vertical = 12.dp)
            ) { Text("OPEN EDITOR ▸", color = Color.White, fontSize = 13.sp) }
        }

        Spacer(Modifier.height(16.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            Box(
                Modifier.clip(RoundedCornerShape(10.dp)).background(Color(0xFF1B2434))
                    .clickable { videoPicker.launch(arrayOf("video/*")) }
                    .padding(horizontal = 14.dp, vertical = 10.dp)
            ) { Text("＋ import videos", color = Color(0xFF7EE3C5), fontSize = 13.sp) }
            Box(
                Modifier.clip(RoundedCornerShape(10.dp)).background(Color(0xFF1B2434))
                    .clickable { audioPicker.launch(arrayOf("audio/*")) }
                    .padding(horizontal = 14.dp, vertical = 10.dp)
            ) { Text("＋ import music", color = Color(0xFF7EE3C5), fontSize = 13.sp) }
            Box(
                Modifier.clip(RoundedCornerShape(10.dp)).background(Color(0xFF1B2434))
                    .clickable {
                        store.commit { p -> p.copy(clips = emptyList()) }
                        status = "cleared timeline"
                    }
                    .padding(horizontal = 14.dp, vertical = 10.dp)
            ) { Text("new / clear", color = Color(0xFFC9D6E5), fontSize = 13.sp) }
        }
        if (status.isNotEmpty()) {
            Spacer(Modifier.height(10.dp))
            Text(status, fontSize = 11.sp, color = Color(0xFF6B7E95))
        }

        Spacer(Modifier.height(28.dp))
        Text("saved projects", fontSize = 13.sp, color = Color(0xFF9FB2C8))
        Spacer(Modifier.height(8.dp))
        if (saved.isEmpty()) {
            Text("none yet — they appear here after you press save in the editor", fontSize = 11.sp, color = Color(0xFF556277))
        }
        for (prj in saved.sortedByDescending { it.id }) {
            Row(
                Modifier.fillMaxWidth().padding(vertical = 4.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Column(Modifier.weight(1f).clickable {
                    if (store.load(context, prj.id)) { title = prj.title; status = "loaded ${prj.title}"; onOpenEditor() }
                }) {
                    Text(prj.title, fontSize = 13.sp, color = Color.White)
                    Text(
                        "${prj.clips.size} clips · ${timecode(prj.durationMs())}",
                        fontSize = 10.sp, color = Color(0xFF6B7E95)
                    )
                }
                TextButton(onClick = {
                    scope.launch(Dispatchers.IO) {
                        // refresh list from disk
                    }
                    saved = store.listSaved(context)
                }) { Text("refresh", fontSize = 10.sp, color = Color(0xFF6B7E95)) }
            }
        }
        Spacer(Modifier.height(20.dp))
        Text(
            "landscape editor · everything renders on-device · no account, no upload",
            fontSize = 10.sp, color = Color(0xFF556277)
        )
    }
}
