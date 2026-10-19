package dev.aniforge.engine

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import java.io.File
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import java.util.concurrent.Future

/** Async decode cache for image-card clips (GL threads may peek without blocking). */
object ImageRenders {
    private val executor: ExecutorService = Executors.newSingleThreadExecutor { r ->
        Thread(r, "af-imgdecode").apply { isDaemon = true }
    }
    private val cache = ConcurrentHashMap<String, Future<Bitmap>>()

    /** Kick off a decode if this image isn't already being decoded. */
    fun begin(path: String, targetW: Int = 1920) {
        if (path.isBlank()) return
        cache.computeIfAbsent(path) {
            executor.submit<Bitmap> {
                val f = File(path)
                if (!f.exists()) return@submit null
                decode(f, targetW)
            }
        }
    }

    /** Non-blocking peek; null while still decoding or if failed. */
    fun peek(path: String): Bitmap? {
        val f = cache[path] ?: return null
        return if (f.isDone) runCatching { f.get() }.getOrNull() else null
    }

    private fun decode(f: File, targetW: Int): Bitmap? {
        val opts = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeFile(f.absolutePath, opts)
        var sample = 1
        while (opts.outWidth / sample > targetW) sample *= 2
        val o = BitmapFactory.Options().apply {
            inSampleSize = sample
            inPreferredConfig = Bitmap.Config.ARGB_8888
        }
        return BitmapFactory.decodeFile(f.absolutePath, o)
    }

    fun clear() {
        cache.values.forEach { runCatching { it.cancel(true) } }
        cache.clear()
    }
}
