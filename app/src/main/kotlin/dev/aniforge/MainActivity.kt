package dev.aniforge

import android.os.Bundle
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import dev.aniforge.data.AniListApi
import dev.aniforge.data.CatalogEntry
import dev.aniforge.data.IntentParser
import dev.aniforge.data.StudioClient
import dev.aniforge.ui.theme.AniForgeTheme
import kotlinx.coroutines.launch

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent { AniForgeTheme { ForgeRoot() } }
    }
}

/** Shared UI state. The Studio API owns timeline truth; the app orchestrates it. */
class ForgeState {
    val baseUrl = mutableStateOf("http://10.0.2.2:8787")
    val project = mutableStateOf("")           // project.osp JSON in editor
    val events = androidx.compose.runtime.mutableStateListOf<String>()
    var page: String by mutableStateOf("catalog")
}

@Composable
private fun ForgeRoot() {
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()
    val state = remember { ForgeState() }

    fun toast(msg: String) = Toast.makeText(ctx, msg, Toast.LENGTH_LONG).show()
    fun onEvent(ev: String) {
        val list = state.events
        list.add(0, ev.take(240))
        if (list.size > 120) list.removeAt(list.lastIndex)
    }

    Column(Modifier.fillMaxSize().background(MaterialTheme.colorScheme.background)) {
        Row(
            Modifier.fillMaxWidth().padding(12.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text("AniForge", fontSize = 22.sp, color = MaterialTheme.colorScheme.primary)
            Spacer(Modifier.weight(1f))
            TabButton("Catalog", state.page == "catalog") { state.page = "catalog" }
            TabButton("Studio", state.page == "studio") { state.page = "studio" }
            TabButton("Agent", state.page == "agent") { state.page = "agent" }
        }

        when (state.page) {
            "catalog" -> CatalogScreen(
                onUse = { link, title ->
                    state.project.value = miniProject(link)
                    onEvent("material → $title")
                    state.page = "studio"
                }
            )
            "agent" -> AgentScreen(state, scope, ::toast, ::onEvent)
            else -> StudioScreen(state, scope, ::toast, ::onEvent)
        }
    }
}

@Composable
private fun TabButton(label: String, active: Boolean, onClick: () -> Unit) {
    if (active) Button(onClick = onClick) { Text(label) }
    else OutlinedButton(onClick = onClick) { Text(label) }
}

/** Demo project the sample API expects (clip id c1) — client only orchestrates, server owns ops. */
private fun miniProject(link: String): String = """
{"version":1,"fps":{"num":24,"den":1},"width":1920,"height":1080,"profile":"shonen",
 "layers":[{"id":0,"name":"V1"}],
 "clips":[{"id":"c1","layer":0,"reader":{"path":"$link","has_video":true,"has_audio":true},
           "position":0.0,"start":0.0,"end":60.0,"speed":1.0,
           "effects":[{"type":"Fade","params":{"in":"0.4","out":"0.4"}}]}]}
""".trimIndent()

// ================================================================ catalog
@Composable
private fun CatalogScreen(onUse: (String, String) -> Unit) {
    val scope = rememberCoroutineScope()
    var entries by remember { mutableStateOf(listOf<CatalogEntry>()) }

    Column(Modifier.fillMaxSize().padding(12.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
        Text("Anime material (AniList trending)", fontSize = 15.sp)
        Button(onClick = { scope.launch { entries = AniListApi.trending() } }) {
            Text("Refresh trending")
        }
        LazyColumn(verticalArrangement = Arrangement.spacedBy(8.dp)) {
            items(entries) { e ->
                Card(
                    Modifier
                        .fillMaxWidth()
                        .clip(RoundedCornerShape(10.dp))
                ) {
                    Column(Modifier.padding(10.dp)) {
                        Text(e.title, fontSize = 15.sp, maxLines = 1)
                        Text(
                            e.genres.ifBlank { "—" },
                            fontSize = 11.sp,
                            color = Color(0xFF9AB3C6)
                        )
                        Button(onClick = { onUse("https://media.aniforge.local/${e.id}.mp4", e.title) }) {
                            Text("Use as material → Studio")
                        }
                    }
                }
            }
        }
    }
}

// ================================================================ studio
@Composable
private fun StudioScreen(
    state: ForgeState,
    scope: kotlinx.coroutines.CoroutineScope,
    toast: (String) -> Unit,
    onEvent: (String) -> Unit
) {
    var opsJson by remember { mutableStateOf(
        "[{\"op\":\"speed\",\"clip_id\":\"c1\",\"ramp\":[[0.0,0.6],[0.5,2.2],[1.2,1.0]]}]"
    ) }

    Column(Modifier.fillMaxSize().padding(12.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
        Text("Studio", fontSize = 16.sp)
        OutlinedTextField(
            value = state.baseUrl.value,
            onValueChange = { state.baseUrl.value = it },
            label = { Text("Studio URL") },
            modifier = Modifier.fillMaxWidth()
        )
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Button(onClick = {
                scope.launch {
                    try {
                        StudioClient.baseUrl = state.baseUrl.value
                        val r = StudioClient.health()
                        onEvent("health → ${r.toString().take(140)}")
                        toast("Studio OK")
                    } catch (e: Exception) { toast("Studio down: ${e.message}") }
                }
            }) { Text("Ping") }
            OutlinedButton(onClick = {
                state.project.value = miniProject("https://media.aniforge.local/demo-op.mp4")
                onEvent("loaded demo project.osp")
            }) { Text("Load demo") }
        }

        OutlinedTextField(
            value = opsJson,
            onValueChange = { opsJson = it },
            label = { Text("ops JSON (see afctl examples)") },
            modifier = Modifier.fillMaxWidth().height(110.dp)
        )
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Button(onClick = {
                scope.launch {
                    try {
                        StudioClient.baseUrl = state.baseUrl.value
                        val resp = StudioClient.applyFromJson(state.project.value, opsJson, "Android app")
                        val updated = resp["project"]?.toString()
                        if (!updated.isNullOrBlank()) state.project.value = updated
                        onEvent("apply → ${(resp["events"]?.toString() ?: "").take(180)}")
                        toast("Applied")
                    } catch (e: Exception) { toast("apply: ${e.message}") }
                }
            }) { Text("Apply ops") }
            OutlinedButton(onClick = {
                scope.launch {
                    try {
                        StudioClient.baseUrl = state.baseUrl.value
                        val resp = StudioClient.renderFromJson(state.project.value, null)
                        onEvent("render → ${resp["job_id"]}")
                    } catch (e: Exception) { toast("render: ${e.message}") }
                }
            }) { Text("Render") }
            TextButton(onClick = {
                scope.launch {
                    try {
                        StudioClient.baseUrl = state.baseUrl.value
                        val resp = StudioClient.validateFromJson(state.project.value)
                        onEvent("validate → ${resp["ok"]} issues=${resp["issues"]}")
                    } catch (e: Exception) { toast("validate: ${e.message}") }
                }
            }) { Text("Validate") }
        }

        OutlinedTextField(
            value = state.project.value,
            onValueChange = { state.project.value = it },
            label = { Text("project.osp (AsProject JSON)") },
            modifier = Modifier.fillMaxWidth().height(220.dp)
        )

        Text("Live events", fontSize = 14.sp, color = MaterialTheme.colorScheme.secondary)
        LazyColumn(Modifier.height(160.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            items(state.events.toList()) { ev -> Text("• $ev", fontSize = 11.sp) }
        }
    }
}

// ================================================================ agent
@Composable
private fun AgentScreen(
    state: ForgeState,
    scope: kotlinx.coroutines.CoroutineScope,
    toast: (String) -> Unit,
    onEvent: (String) -> Unit
) {
    val ctx = LocalContext.current
    var prompt by remember { mutableStateOf("speed 2x on c1") }

    Column(Modifier.fillMaxSize().padding(12.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
        Text("Agent — intent console", fontSize = 16.sp)
        Text("(full agent: OpenCode + afctl; quick intents run from here)",
             fontSize = 11.sp, color = MaterialTheme.colorScheme.onSurface)
        OutlinedTextField(
            value = prompt,
            onValueChange = { prompt = it },
            label = { Text("cut at 4.5 · speed 2x on c1 · chroma c1 · text 第3話 · render shonen-flash") },
            modifier = Modifier.fillMaxWidth()
        )
        Button(onClick = {
            scope.launch {
                try {
                    StudioClient.baseUrl = state.baseUrl.value
                    if (state.project.value.isBlank()) throw RuntimeException("Load a project first (Catalog or Demo)")
                    val raw = kotlinx.serialization.json.Json.parseToJsonElement(state.project.value)
                        as kotlinx.serialization.json.JsonObject
                    val intent = IntentParser.parse(prompt)
                    if (intent.renderPreset != null) {
                        val res = StudioClient.render(raw, intent.renderPreset)
                        onEvent("render → ${res["job_id"]}")
                    } else if (intent.ops.isNotEmpty()) {
                        val res = StudioClient.apply(raw, intent.ops, "Android intent: $prompt")
                        val updated = res["project"] as? kotlinx.serialization.json.JsonObject
                        if (updated != null) state.project.value = updated.toString()
                        onEvent("agent → ${intent.ops.size} op(s)")
                    } else {
                        toast("No ops recognized")
                    }
                } catch (e: Exception) { toast("agent: ${e.message}") }
            }
        }) { Text("Run intent") }
        TextButton(onClick = {
            Toast.makeText(ctx, "cut at 4.5 · speed 2x · chroma c1 · text 第3話 · crop 4:5 · render shonen-flash", Toast.LENGTH_LONG).show()
        }) { Text("Examples") }

        Text("All heavy agent editing lives where OpenCode runs: `afctl` on your studio box, "
             + "ImGui Forge Console, `skills/aniforge-edit` — see README", fontSize = 11.sp,
             color = MaterialTheme.colorScheme.onSurface)
    }
}

private fun kotlinx.serialization.json.JsonElement.javaJson(): kotlinx.serialization.json.JsonObject =
    this as kotlinx.serialization.json.JsonObject
