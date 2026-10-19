package dev.aniforge.domain

import android.content.Context
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import java.io.File
import kotlin.math.max

/** Editor state + history (undo/redo) + disk persistence. */
class ProjectStore {

    val project = mutableStateOf(Project(id = "p" + (System.nanoTime() % 1_000_000), title = "Untitled EP"))
    val selection = mutableStateOf<String?>(null)
    val playheadMs = mutableStateOf(0L)
    val playing = mutableStateOf(false)
    val log = mutableStateListOf<String>()
    var lastSaved: Long = 0L
    private val undoStack = mutableListOf<Project>()
    private val redoStack = mutableListOf<Project>()
    private var openCheckpoint = false

    fun snap(): Project = deepCopy(project.value)

    /** Push an undo point once before a continuous gesture (drag). */
    fun checkpoint() {
        if (openCheckpoint) return
        undoStack.add(snap())
        if (undoStack.size > 60) undoStack.removeAt(0)
        redoStack.clear()
        openCheckpoint = true
    }

    fun closeCheckpoint() { openCheckpoint = false }

    /** Apply a change WITHOUT creating an undo entry (used inside drag gestures after checkpoint()). */
    fun patch(mutate: (Project) -> Project) {
        project.value = mutate(project.value)
        lastSaved = 0L
    }

    fun commit(mutate: (Project) -> Project) {
        closeCheckpoint()
        val before = snap()
        undoStack.add(before)
        if (undoStack.size > 60) undoStack.removeAt(0)
        redoStack.clear()
        project.value = mutate(project.value)
        lastSaved = 0L
    }

    fun undo() {
        closeCheckpoint()
        if (undoStack.isEmpty()) return
        redoStack.add(snap())
        project.value = undoStack.removeAt(undoStack.lastIndex)
    }

    fun redo() {
        closeCheckpoint()
        if (redoStack.isEmpty()) return
        undoStack.add(snap())
        project.value = redoStack.removeAt(redoStack.lastIndex)
    }

    fun say(line: String) {
        log.add(0, line)
        if (log.size > 80) log.removeAt(log.lastIndex)
    }

    val selected: Clip? get() = project.value.find(selection.value ?: "")

    // ---- ops (commit) ----
    fun addClip(clip: Clip) = commit { p -> p.copy(clips = p.clips + clip) }

    fun updateClip(id: String, mut: (Clip) -> Clip) = commit { p ->
        p.copy(clips = p.clips.map { if (it.id == id) mut(it) else it })
    }

    fun removeClip(id: String) = commit { p -> p.copy(clips = p.clips.filterNot { it.id == id }) }

    fun splitClip(id: String, atMs: Long): Boolean {
        val c = project.value.find(id) ?: return false
        val local = atMs - c.positionMs
        if (local <= 100 || local >= c.durationMs() - 100) return false
        val splitSrc = c.sourceTimeMs(atMs)
        val right = c.copy(
            id = c.id + "b",
            positionMs = atMs,
            inMs = splitSrc,
            // keyframe times were clip-local — shift them for the right piece
            transforms = c.transforms.mapValues { (_, tr) ->
                tr.shifted(-((atMs - c.positionMs) / 1000f))
            }
        )
        val left = c.copy(outMs = splitSrc)
        commit { p ->
            p.copy(clips = p.clips.map { if (it.id == id) left else it } + right)
        }
        selection.value = right.id
        return true
    }

    fun moveClip(id: String, newPosition: Long, newTrack: Int? = null) = updateClip(id) { c ->
        c.copy(positionMs = max(0L, newPosition), track = newTrack ?: c.track)
    }

    fun duplicate(id: String): Boolean {
        val c = project.value.find(id) ?: return false
        val copy = c.copy(id = c.id + "x" + (System.nanoTime() % 10_000), positionMs = c.outMsEnd())
        addClip(copy)
        selection.value = copy.id
        return true
    }

    fun deleteRange(id: String) = removeClip(id)

    // ---- compose-editors ----
    fun setProp(p: Prop, atMs: Long, v: Float, ease: String = "smooth") {
        val clip = selected ?: return
        val local = (atMs - clip.positionMs) / 1000f
        updateClip(clip.id) { c ->
            val tr = (c.transforms[p.key] ?: MotionTrack()).upsert(local, v, ease)
            c.withProp(p, tr)
        }
    }

    fun autoFade(ms: Long = 300) {
        val clip = selected ?: return
        val endLocal = clip.durationMs() / 1000f
        val tr = MotionTrack(listOf(
            KeyPoint(0f, 0f, "smooth"),
            KeyPoint(ms / 1000f, 1f, "smooth"),
            KeyPoint((endLocal - ms / 1000f).coerceAtLeast(0.05f), 1f, "smooth"),
            KeyPoint(endLocal.coerceAtLeast(0.06f), 0f, "smooth")))
        updateClip(clip.id) { c -> c.withProp(Prop.OPACITY, tr) }
    }

    fun autoZoomIn(ms: Long = 800) {
        val clip = selected ?: return
        val endLocal = clip.durationMs() / 1000f
        val tr = MotionTrack(listOf(
            KeyPoint(0f, 1f, "smooth"),
            KeyPoint(endLocal.coerceAtLeast(ms / 1000f), 1.6f)))
        updateClip(clip.id) { c -> c.withProp(Prop.SCALE, tr) }
    }

    // ---- persistence ----
    val file: File? get() = currentDir?.let { d -> File(d, "project-${project.value.id}.af.json") }
    private var currentDir: File? = null

    fun save(context: Context) {
        currentDir = currentDir ?: File(context.filesDir, "projects").apply { mkdirs() }
        runCatching { file?.writeText(project.value.encode()); lastSaved = System.currentTimeMillis() }
    }

    fun load(context: Context, id: String): Boolean {
        val d = File(context.filesDir, "projects")
        val f = File(d, "project-$id.af.json")
        val prj = f.takeIf { it.exists() }?.readText()?.let { decodeProject(it) } ?: return false
        undoStack.clear(); redoStack.clear()
        project.value = prj
        selection.value = null
        playheadMs.value = 0L
        currentDir = d
        return true
    }

    fun listSaved(context: Context): List<Project> {
        val d = File(context.filesDir, "projects")
        return d.listFiles()?.mapNotNull { runCatching { decodeProject(it.readText()) }.getOrNull() } ?: emptyList()
    }
}
