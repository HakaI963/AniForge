package dev.aniforge.domain

import android.content.Context
import android.graphics.Bitmap
import android.media.MediaCodec
import android.media.MediaExtractor
import android.media.MediaFormat
import android.media.MediaMetadataRetriever
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.nio.ByteBuffer
import java.nio.ShortBuffer
import kotlin.math.min
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.sqrt

data class MediaInfo(
    val uri: String,
    val durationMs: Long,
    val width: Int,
    val height: Int,
    val rotation: Int,
    val hasVideo: Boolean,
    val hasAudio: Boolean,
) {
    val isVideo get() = hasVideo
}

/** Scene cut boundaries (ms) + beat times derived from audio loudness. */
data class EditAnalysis(
    val cutTimesMs: List<Long>,
    val beatTimesMs: List<Long>,
)

object MediaProbe {

    suspend fun probe(context: Context, uri: String): MediaInfo? = withContext(Dispatchers.IO) {
        runCatching {
            val mmr = MediaMetadataRetriever()
            mmr.setDataSource(context, android.net.Uri.parse(uri))
            fun ms(s: String?): Long = s?.toLongOrNull() ?: 0L
            val info = MediaInfo(
                uri = uri,
                durationMs = ms(mmr.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION)),
                width = mmr.extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_WIDTH)?.toIntOrNull() ?: 0,
                height = mmr.extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_HEIGHT)?.toIntOrNull() ?: 0,
                rotation = mmr.extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_ROTATION)?.toIntOrNull() ?: 0,
                hasVideo = !mmr.extractMetadata(MediaMetadataRetriever.METADATA_KEY_HAS_VIDEO).isNullOrEmpty(),
                hasAudio = !mmr.extractMetadata(MediaMetadataRetriever.METADATA_KEY_HAS_AUDIO).isNullOrEmpty()
            )
            mmr.release()
            info
        }.getOrNull()
    }

    suspend fun thumb(context: Context, uri: String, atMs: Long = 200): Bitmap? = withContext(Dispatchers.IO) {
        runCatching {
            val mmr = MediaMetadataRetriever()
            mmr.setDataSource(context, android.net.Uri.parse(uri))
            val b = mmr.getFrameAtTime(atMs * 1000, MediaMetadataRetriever.OPTION_CLOSEST_SYNC)
            mmr.release()
            b
        }.getOrNull()
    }

    /**
     * Scene detection by tiny-frame histogram delta (≈4 samples/sec).
     * Returns sorted cut positions (ms).
     */
    suspend fun detectCuts(context: Context, uri: String, sampleMs: Int = 250): List<Long> = withContext(Dispatchers.IO) {
        val info = probe(context, uri) ?: return@withContext emptyList()
        val ret = mutableListOf<Long>()
        runCatching {
            val mmr = MediaMetadataRetriever()
            mmr.setDataSource(context, android.net.Uri.parse(uri))
            var prev: IntArray? = null
            var t = 0L
            while (t < info.durationMs && ret.size < 400) {
                val f = mmr.getFrameAtTime(t * 1000, MediaMetadataRetriever.OPTION_CLOSEST_SYNC)
                if (f != null) {
                    val small = Bitmap.createScaledBitmap(f, 96, 54, true)
                    val px = IntArray(96 * 54); small.getPixels(px, 0, 96, 0, 0, 96, 54)
                    val hist = IntArray(16)
                    for (p in px) {
                        val r = (p shr 16) and 0xff; val g = (p shr 8) and 0xff; val b = p and 0xff
                        val l = ((r * 299 + g * 587 + b * 114) / 1000).coerceIn(0, 255)
                        hist[(l shr 4)]++
                    }
                    val score = prev?.let { h ->
                        var d = 0f
                        for (i in hist.indices) d += abs(hist[i] - h[i])
                        d / (96f * 54f) * 16f / 255f
                    } ?: 0f
                    if (prev != null && score > 0.20f && t - (ret.lastOrNull() ?: -10_000L) > 500L) {
                        ret.add(t)
                    }
                    prev = hist
                    small.recycle(); f.recycle()
                }
                t += sampleMs
            }
            mmr.release()
        }
        ret
    }

    /** Loudness envelope (20ms buckets, RMS 0..1) + beat times from loud peaks. */
    suspend fun analyzeAudio(context: Context, uri: String): EditAnalysis = withContext(Dispatchers.IO) {
        val env = mutableListOf<Float>()
        val beats = mutableListOf<Long>()
        runCatching {
            val ex = MediaExtractor()
            try {
                ex.setDataSource(context, android.net.Uri.parse(uri), null)
                var ti = -1
                var fmt: MediaFormat? = null
                for (i in 0 until ex.trackCount) {
                    val f = ex.getTrackFormat(i)
                    if (f.getString(MediaFormat.KEY_MIME)?.startsWith("audio/") == true) { ti = i; fmt = f; break }
                }
                if (ti < 0 || fmt == null) return@runCatching
                val f = fmt!!
                val rate = f.getInteger(MediaFormat.KEY_SAMPLE_RATE)
                val chans = f.getInteger(MediaFormat.KEY_CHANNEL_COUNT)
                val mime = f.getString(MediaFormat.KEY_MIME)!!
                ex.selectTrack(ti)
                val dec = MediaCodec.createDecoderByType(mime)
                dec.configure(f, null, null, 0)
                dec.start()
                val win = max(1, rate / 50) // 20ms
                val sbuf = ShortArray(win * chans * 64)
                var feedEos = false
                var lastT = 0L
                val info = MediaCodec.BufferInfo()
                while (env.size < 6000 && lastT < 240_000L) {
                    // queue input
                    if (!feedEos) {
                        val ib = dec.dequeueInputBuffer(0)
                        if (ib >= 0) {
                            val bb: ByteBuffer = dec.getInputBuffer(ib)!!
                            val n = ex.readSampleData(bb, 0)
                            if (n < 0) {
                                dec.queueInputBuffer(ib, 0, 0, 0, MediaCodec.BUFFER_FLAG_END_OF_STREAM)
                                feedEos = true
                            } else {
                                lastT = ex.sampleTime
                                dec.queueInputBuffer(ib, 0, n, ex.sampleTime, 0)
                            }
                        }
                    }
                    val ob = dec.dequeueOutputBuffer(info, 5_000)
                    if (ob >= 0) {
                        if (info.size > 0) {
                            val sb = dec.getOutputBuffer(ob)?.asShortBuffer() ?: ShortBuffer.allocate(1)
                            var pos = 0
                            while (sb.hasRemaining() && pos < sbuf.size) {
                                sbuf[pos++] = sb.get()
                            }
                            pushEnvelope(sbuf, pos, chans, win, env)
                        }
                        dec.releaseOutputBuffer(ob, false)
                    } else if (ob == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED) {
                        // ignore
                    }
                    if (info.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM != 0) break
                }
                dec.stop(); dec.release()
            } catch (_: Throwable) { }
            finally { runCatching { ex.release() } }
        }
        deriveBeats(env, beats)
        EditAnalysis(cutTimesMs = emptyList(), beatTimesMs = beats)
    }

    private fun pushEnvelope(src: ShortArray, len: Int, chans: Int, win: Int, env: MutableList<Float>) {
        var i = 0
        while (i + win <= len) {
            var sum = 0f; var n = 0
            var j = i
            while (j < i + win) {
                val v = src[j] / 32768f
                sum += v * v; n += 1
                j += max(1, chans)
            }
            env.add(sqrt(sum / max(1, n)))
            i += win
        }
    }

    private fun deriveBeats(env: List<Float>, out: MutableList<Long>) {
        if (env.size < 4) return
        val avg = env.sum() / env.size
        val thr = max(0.05f, avg * 2.2f)
        var lastBeat = -10_000f
        for (i in 1 until env.size - 1) {
            val v = env[i]
            if (v > thr && v >= env[i - 1] && v > env[i + 1]) {
                val t = i * 20f
                if (t - lastBeat >= 240f) { out.add(t.toLong()); lastBeat = t }
            }
        }
        if (out.size > 96) {
            val step = out.size / 96 + 1
            val trimmed = out.filterIndexed { idx, _ -> idx % step == 0 }
            out.clear(); out.addAll(trimmed)
        }
    }
}
