package dev.aniforge.engine

import android.content.Context
import android.media.MediaCodec
import android.media.MediaMuxer
import android.view.Surface
import dev.aniforge.domain.Project
import dev.aniforge.domain.ProjectStore
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File

/** Thin wrapper around Exporter; runs on Dispatchers.IO and streams progress. */
class ExportService(private val context: Context) {

    var running = false
        private set
    private var exporter: Exporter? = null

    fun cancel() { exporter?.cancel() }

    suspend fun export(
        store: ProjectStore,
        spec: Exporter.Spec,
        onProgress: (Float) -> Unit,
        onDone: (File?, String?) -> Unit,
    ) {
        if (running) { onDone(null, "already exporting"); return }
        running = true
        val dir = File(context.getExternalFilesDir(null) ?: context.filesDir, "Exports").apply { mkdirs() }
        val out = File(dir, "aniforge-${System.currentTimeMillis()}.mp4")
        try {
            val prj = store.project.value
            withContext(Dispatchers.IO) {
                val ex = Exporter(context)
                exporter = ex
                ex.runSync(prj.copy(clips = prj.clips), out, spec) { p -> onProgress(p.coerceIn(0f, 1f)) }
            }
            onDone(out, null)
        } catch (t: Throwable) {
            onDone(null, t.message ?: "export failed")
        } finally {
            running = false
            exporter = null
        }
    }
}
