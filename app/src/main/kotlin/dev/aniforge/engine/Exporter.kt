package dev.aniforge.engine

import android.content.Context
import android.media.MediaCodec
import android.media.MediaCodecInfo
import android.media.MediaExtractor
import android.media.MediaFormat
import android.media.MediaMuxer
import android.opengl.EGL14
import android.opengl.EGLConfig
import android.opengl.EGLContext
import android.opengl.EGLDisplay
import android.opengl.EGLSurface
import android.graphics.SurfaceTexture
import android.opengl.GLES20
import android.view.Surface
import android.opengl.Matrix
import dev.aniforge.domain.Clip
import dev.aniforge.domain.ColorGrade
import dev.aniforge.domain.Project
import dev.aniforge.domain.Prop
import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.ArrayList
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.math.ceil
import kotlin.math.max
import kotlin.math.min

/**
 * Real MP4 exporter.
 *  - video:  MediaCodec decode → GL transform (crop/motion/grade/title) → MediaCodec encode → MediaMuxer
 *  - audio:  MediaExtractor→MediaCodec PCM decode → speed/volume/mix → AAC encode → same Muxer
 */
class Exporter(private val context: Context) {

    data class Spec(val width: Int, val height: Int, val fps: Int, val videoBitrate: Int) {
        val audioBitrate get() = 128_000
        val sampleRate get() = 44_100
        val channels get() = 2
    }

    companion object {
        val P1080 = Spec(1920, 1080, 30, 12_000_000)
        val P720 = Spec(1280, 720, 30, 8_000_000)
        val P480 = Spec(854, 480, 30, 4_000_000)
    }

    private val canceled = AtomicBoolean(false)
    fun cancel() { canceled.set(true) }

    fun runSync(project: Project, out: File, spec: Spec, onProgress: (Float) -> Unit) {
        out.parentFile?.mkdirs()
        val mux = MediaMuxer(out.absolutePath, MediaMuxer.OutputFormat.MUXER_OUTPUT_MPEG_4)
        val writer = MuxerWriter(mux)
        val err = java.util.concurrent.atomic.AtomicReference<Throwable?>(null)

        val v = Thread { try { videoPass(project, spec, writer, onProgress, err) } catch (t: Throwable) { err.compareAndSet(null, t) } }
        val a = Thread { try { audioPass(project, spec, writer) } catch (t: Throwable) { err.compareAndSet(null, t) } }
        v.start(); a.start()
        v.join(); a.join()
        runCatching { writer.finish() }
        runCatching { mux.release() }
        err.get()?.let { throw it }
    }

    // ------------------------------------------------------------------ muxer
    private class MuxerWriter(private val mux: MediaMuxer) {
        private val lock = Object()
        private var videoTrack = -1
        private var audioTrack = -1
        private var started = false
        private val backlog = ArrayList<IntArray>()  // {trackIdx, size, offset, ptsUs(lo), ptsUs(hi), flags, bytesIdx}
        private val backlogBytes = ArrayList<ByteArray>()

        fun addVideoTrack(format: MediaFormat): Int = synchronized(lock) {
            if (videoTrack < 0) videoTrack = mux.addTrack(format)
            maybeStart(); videoTrack
        }

        fun addAudioTrack(format: MediaFormat): Int = synchronized(lock) {
            if (audioTrack < 0) audioTrack = mux.addTrack(format)
            maybeStart(); audioTrack
        }

        var videoIdx: Int = -1
            set(value) {
                synchronized(lock) { field = value }
            }

        private fun maybeStart() {
            if (!started && videoTrack >= 0 && audioTrack >= 0) { mux.start(); started = true }
        }

        fun hasStarted(): Boolean = synchronized(lock) { started }

        fun safeWrite(track: Int, bb: ByteBuffer, bi: MediaCodec.BufferInfo) {
            synchronized(lock) {
                if (!started) {
                    val bytes = ByteArray(bi.size)
                    bb.position(bi.offset); bb.limit(bi.offset + bi.size)
                    bb.get(bytes)
                    backlog.add(intArrayOf(track, bytes.size, 0, (bi.presentationTimeUs and 0xffffffffL).toInt(), (bi.presentationTimeUs shr 32).toInt(), bi.flags, bytes.size))
                    backlogBytes.add(bytes)
                    return@synchronized
                }
                if ((track == videoTrack || track == audioTrack) && bi.size > 0) {
                    runCatching { mux.writeSampleData(track, bb, bi) }
                }
            }
        }

        fun finish() {
            synchronized(lock) {
                if (started) {
                    for (i in backlog.indices) {
                        val ints = backlog[i]
                        val bytes = backlogBytes[i]
                        val bi = MediaCodec.BufferInfo().apply {
                            set(0, ints[1], (ints[3].toLong() and 0xffffffffL) or (ints[4].toLong() shl 32), ints[5])
                        }
                        val bb = ByteBuffer.wrap(bytes)
                        runCatching { mux.writeSampleData(ints[0], bb, bi) }
                    }
                }
                backlog.clear(); backlogBytes.clear()
                runCatching { mux.stop() }
            }
        }
    }

    // ------------------------------------------------------------------ helpers
    private fun framesFor(clip: Clip, spec: Spec): Int =
        ceil((clip.outMs - clip.inMs).toDouble() * clip.speed / 1000.0 * spec.fps).toInt().coerceAtLeast(1)

    private fun transform(clip: Clip, p: Prop, localMs: Long, def: Float): Float {
        val tr = clip.transforms[p.key] ?: return def
        return tr.sample(localMs / 1000f)
    }

    private class Grade(val bright: Float, val contrast: Float, val sat: Float, val tint: FloatArray)

    private fun gradeOf(clip: Clip): Grade {
        var b = clip.brightness; var c = clip.contrast; var s = clip.saturation
        var tint = floatArrayOf(1f, 1f, 1f)
        when (clip.color) {
            ColorGrade.WARM -> tint = floatArrayOf(1.10f, 1.00f, 0.86f)
            ColorGrade.COOL -> tint = floatArrayOf(0.86f, 1.00f, 1.10f)
            ColorGrade.NOIR -> s = 0f
            ColorGrade.PASTEL -> { b += 0.03f; c *= 0.96f; tint = floatArrayOf(1.05f, 0.99f, 1.05f) }
            ColorGrade.VIVID -> { c *= 1.10f; s = min(2f, s * 1.25f); tint = floatArrayOf(1.06f, 1.02f, 1.02f) }
            ColorGrade.NONE -> {}
        }
        return Grade(b, c, s, tint)
    }

    // ------------------------------------------------------------------ video
    private fun videoPass(project: Project, spec: Spec, writer: MuxerWriter, onProgress: (Float) -> Unit, err: java.util.concurrent.atomic.AtomicReference<Throwable?>) {
        val segments = project.allVideoClips().sortedBy { it.positionMs }
        val totalFrames = max(1L, segments.sumOf { framesFor(it, spec).toLong() })
        val framesDone = java.util.concurrent.atomic.AtomicLong(0)

        val vf = MediaFormat.createVideoFormat(MediaFormat.MIMETYPE_VIDEO_AVC, spec.width, spec.height).apply {
            setInteger(MediaFormat.KEY_COLOR_FORMAT, MediaCodecInfo.CodecCapabilities.COLOR_FormatSurface)
            setInteger(MediaFormat.KEY_BIT_RATE, spec.videoBitrate)
            setInteger(MediaFormat.KEY_FRAME_RATE, spec.fps)
            setInteger(MediaFormat.KEY_I_FRAME_INTERVAL, 1)
        }
        val encoder = MediaCodec.createEncoderByType(MediaFormat.MIMETYPE_VIDEO_AVC)
        encoder.configure(vf, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE)
        val encSurface: Surface = encoder.createInputSurface()
        encoder.start()

        val egl = EglWindow(encSurface)
        egl.makeCurrent()
        val drawer = GLDrawer()
        val videoTex = genTexture()
        val stMatrix = FloatArray(16)
        var trackReported = false

        val info = MediaCodec.BufferInfo()
        var eosQueued = false
        var gotEos = false

        try {
            outer@ for (clip in segments) {
                if (canceled.get()) break@outer
                if (!trackReported) trackReported = tryReportVideoTrack(encoder, info, writer) || trackReported
                val n = framesFor(clip, spec)
                var stream: VideoWindow? = null
                try {
                    stream = VideoWindow(context, clip, videoTex)
                    for (i in 0 until n) {
                        if (canceled.get()) break@outer
                        val outMs = clip.positionMs + (i.toLong() * 1000L) / spec.fps
                        val srcMs = clip.sourceTimeMs(outMs).coerceIn(clip.inMs, clip.outMs)
                        stream.pull(srcMs, stMatrix)
                        val srcAspect = if (clip.srcH > 0 && clip.srcW > 0) clip.srcW.toFloat() / clip.srcH.toFloat() else 16f / 9f
                        val local = max(0L, outMs - clip.positionMs)
                        val scale = transform(clip, Prop.SCALE, local, 1f)
                        val rot = transform(clip, Prop.ROT, local, 0f)
                        val px = transform(clip, Prop.POSX, local, 0f)
                        val py = transform(clip, Prop.POSY, local, 0f)
                        val alpha = transform(clip, Prop.OPACITY, local, 1f).coerceIn(0f, 1f)
                        val extraBright = transform(clip, Prop.BRIGHT, local, 0f)
                        val g = gradeOf(clip)

                        val mvp: FloatArray
                        val win: FloatArray
                        if (clip.crop.aspect != null) {
                            mvp = RenderMat.ortho()
                            win = Crop.window(clip.crop.aspect, srcAspect, px, py, scale)
                        } else {
                            mvp = RenderMat.videoMvp(spec.width, spec.height, srcAspect, scale, rot, px, py)
                            win = floatArrayOf(0f, 0f, 1f, 1f)
                        }

                        GLES20.glClearColor(0f, 0f, 0f, 1f)
                        GLES20.glClear(GLES20.GL_COLOR_BUFFER_BIT)
                        drawer.drawVideo(videoTex, stMatrix, mvp, g.bright + extraBright, g.contrast, g.sat, g.tint, alpha, win)
                        if (clip.title.isNotEmpty()) {
                            TitlePainter.bitmap(clip, spec.width, spec.height)?.let { bmp ->
                                val tex = drawer.overlayTexture("title" + clip.id, bmp)
                                drawer.drawOverlay(tex, spec.width, spec.height, bmp.width, bmp.height, 0f, -0.55f, 1f)
                            }
                        }
                        if (!EGL14.eglSwapBuffers(egl.display, egl.surface)) throw RuntimeException("swap failed")

                        val d = framesDone.incrementAndGet()
                        if (d % 9L == 0L) onProgress((d.toFloat() / totalFrames))
                        if (!trackReported) trackReported = tryReportVideoTrack(encoder, info, writer) || trackReported
                        drainVideo(encoder, info, writer, trackReported)  // write outputs as they come
                    }
                } catch (inner: Throwable) {
                    if (err.get() == null) err.compareAndSet(null, inner)
                } finally {
                    runCatching { stream?.close() }
                    drawer.deleteUnused(emptySet())
                }
            }

            if (canceled.get()) throw RuntimeException("Export canceled")
            // drain + finish
            encoder.signalEndOfInputStream()
            drainVideo(encoder, info, writer, trackReported)
            var waited = 0
            while (!gotEos && waited < 20_000 && !canceled.get()) {
                if (drainVideo(encoder, info, writer, trackReported)) gotEos = true
                Thread.sleep(5)
                waited += 5
            }
        } finally {
            runCatching { encoder.stop() }
            runCatching { encoder.release() }
            egl.release()
        }
    }

    private fun tryReportVideoTrack(encoder: MediaCodec, info: MediaCodec.BufferInfo, writer: MuxerWriter): Boolean {
        var i = 0
        while (true) {
            val idx = encoder.dequeueOutputBuffer(info, 0L)
            when {
                idx == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED -> {
                    writer.videoIdx = writer.addVideoTrack(encoder.outputFormat)
                    return true
                }
                idx >= 0 -> { encoder.releaseOutputBuffer(idx, false) }
                else -> return false
            }
            if (i++ > 4) return false
        }
    }

    /** Drains the encoder; returns true when EOS was consumed. */
    private fun drainVideo(
        encoder: MediaCodec,
        info: MediaCodec.BufferInfo,
        writer: MuxerWriter,
        @Suppress("UNUSED_PARAMETER") trackReady: Boolean,
    ): Boolean {
        var eos = false
        while (true) {
            val idx = encoder.dequeueOutputBuffer(info, 0L)
            when {
                idx == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED -> {
                    writer.videoIdx = writer.addVideoTrack(encoder.outputFormat)
                }
                idx >= 0 -> {
                    if (info.size > 0) {
                        val bb = encoder.getOutputBuffer(idx)
                        if (bb != null) {
                            bb.position(info.offset); bb.limit(info.offset + info.size)
                            writer.safeWrite(writer.videoIdx, bb, info)
                        }
                    }
                    encoder.releaseOutputBuffer(idx, false)
                    if (info.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM != 0) { eos = true; break }
                }
                else -> break // TRY_AGAIN
            }
        }
        return eos
    }

    private fun genTexture(): Int {
        val a = intArrayOf(0)
        GLES20.glGenTextures(1, a, 0)
        return a[0]
    }

    private inner class EglWindow(target: Any) {
        val display: EGLDisplay = EGL14.eglGetDisplay(EGL14.EGL_DEFAULT_DISPLAY)
        private val attrs = intArrayOf(
            EGL14.EGL_SURFACE_TYPE, EGL14.EGL_WINDOW_BIT, EGL14.EGL_RENDERABLE_TYPE, EGL14.EGL_OPENGL_ES2_BIT,
            EGL14.EGL_RED_SIZE, 8, EGL14.EGL_GREEN_SIZE, 8, EGL14.EGL_BLUE_SIZE, 8, EGL14.EGL_NONE, 0)
        private val cfgs = arrayOfNulls<EGLConfig>(1)
        private val counts = intArrayOf(0)
        val config: EGLConfig
        val ctx: EGLContext
        val surface: EGLSurface

        init {
            if (!EGL14.eglInitialize(display, intArrayOf(0), 0, intArrayOf(0), 0)) throw RuntimeException("EGL init")
            EGL14.eglChooseConfig(display, attrs, 0, cfgs, 0, 1, counts, 0)
            config = cfgs[0] ?: throw RuntimeException("EGL config none")
            ctx = EGL14.eglCreateContext(display, config, EGL14.EGL_NO_CONTEXT,
                intArrayOf(EGL14.EGL_CONTEXT_CLIENT_VERSION, 2, EGL14.EGL_NONE, 0), 0)
            surface = EGL14.eglCreateWindowSurface(display, config, target, intArrayOf(EGL14.EGL_NONE, 0), 0)
        }

        fun makeCurrent() { EGL14.eglMakeCurrent(display, surface, surface, ctx) }

        fun destroy() {
            runCatching { EGL14.eglMakeCurrent(display, EGL14.EGL_NO_SURFACE, EGL14.EGL_NO_SURFACE, EGL14.EGL_NO_CONTEXT) }
            runCatching { EGL14.eglDestroySurface(display, surface) }
            runCatching { EGL14.eglDestroyContext(display, ctx) }
        }

        fun release() { destroy() }
    }

    /** Decoder for a single clip, surfacing into GL — GL context must be current. */
    private class VideoWindow(
        context: Context,
        private val clip: Clip,
        videoTex: Int,
    ): AutoCloseable {
        val textureMatrixHolder = FloatArray(16)
        private val st: SurfaceTexture
        private val decoder: MediaCodec
        private val extractor: MediaExtractor
        private val info = MediaCodec.BufferInfo()
        private var eos = false
        private var lastSampleMs = Long.MIN_VALUE

        init {
            st = SurfaceTexture(videoTex)
            extractor = MediaExtractor()
            extractor.setDataSource(context, android.net.Uri.parse(clip.sourceUri), null)
            var ti = -1
            var fmt: MediaFormat? = null
            for (i in 0 until extractor.trackCount) {
                val f = extractor.getTrackFormat(i)
                if (f.getString(MediaFormat.KEY_MIME)?.startsWith("video/") == true) { ti = i; fmt = f; break }
            }
            require(ti >= 0) { "no video track: ${clip.sourceUri}" }
            val f = fmt!!
            runCatching {
                st.setDefaultBufferSize(f.getInteger(MediaFormat.KEY_WIDTH), f.getInteger(MediaFormat.KEY_HEIGHT))
            }
            val surface = Surface(st)
            extractor.selectTrack(ti)
            extractor.seekTo(clip.inMs * 1000, MediaExtractor.SEEK_TO_PREVIOUS_SYNC)
            decoder = MediaCodec.createDecoderByType(f.getString(MediaFormat.KEY_MIME)!!)
            decoder.configure(f, surface, null, 0)
            decoder.start()
        }

        /** Decodes forward until a frame with pts >= targetMs is on screen; updates matrix. */
        fun pull(targetMs: Long, stMatrix: FloatArray) {
            if (targetMs <= lastSampleMs) {
                st.getTransformMatrix(stMatrix); return
            }
            while (!eos) {
                val qi = decoder.dequeueInputBuffer(0)
                if (qi >= 0) {
                    val ib = decoder.getInputBuffer(qi)!!
                    val n = extractor.readSampleData(ib, 0)
                    if (n < 0) { decoder.queueInputBuffer(qi, 0, 0, 0, MediaCodec.BUFFER_FLAG_END_OF_STREAM); eos = true }
                    else decoder.queueInputBuffer(qi, 0, n, extractor.sampleTime, 0)
                }
                val oi = decoder.dequeueOutputBuffer(info, 5_000L)
                if (oi >= 0) {
                    val ptsMs = info.presentationTimeUs / 1000
                    if (info.size > 0 && ptsMs >= targetMs) {
                        decoder.releaseOutputBuffer(oi, true)
                        lastSampleMs = ptsMs
                        break
                    } else {
                        decoder.releaseOutputBuffer(oi, false)
                    }
                    if (info.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM != 0) { eos = true; break }
                } else if (oi == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED) {
                    // fine
                } // else TRY_AGAIN — loop continues
            }
            st.getTransformMatrix(stMatrix)
        }

        override fun close() {
            runCatching { decoder.stop() }
            runCatching { decoder.release() }
            runCatching { extractor.release() }
            runCatching { st.release() }
        }
    }

    // ------------------------------------------------------------------ audio
    private fun audioPass(project: Project, spec: Spec, writer: MuxerWriter) {
        val mixer = AudioMixer(project, spec, context)
        try {
            val af = MediaFormat.createAudioFormat(MediaFormat.MIMETYPE_AUDIO_AAC, spec.sampleRate, spec.channels).apply {
                setInteger(MediaFormat.KEY_AAC_PROFILE, MediaCodecInfo.CodecProfileLevel.AACObjectLC)
                setInteger(MediaFormat.KEY_BIT_RATE, spec.audioBitrate)
                setInteger(MediaFormat.KEY_MAX_INPUT_SIZE, 16384)
            }
            val enc = MediaCodec.createEncoderByType(MediaFormat.MIMETYPE_AUDIO_AAC)
            enc.configure(af, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE)
            enc.start()

            val info = MediaCodec.BufferInfo()
            var audioIdx = -1
            var gotFormat = false
            val chans = spec.channels
            val totalFrames = (project.durationMs() * spec.sampleRate / 1000L).coerceAtLeast(1L)
            val blockFrames = spec.sampleRate / 32
            val pcm = ShortArray(blockFrames * chans)
            var framePos = 0L
            var eos = false
            var capBytes = -1

            fun drain() {
                var guard = 0
                while (guard++ < 64) {
                    val oi = enc.dequeueOutputBuffer(info, 0L)
                    when {
                        oi == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED -> {
                            audioIdx = writer.addAudioTrack(enc.outputFormat); gotFormat = true
                        }
                        oi >= 0 -> {
                            if (info.size > 0 && gotFormat) {
                                val bb = enc.getOutputBuffer(oi)!!
                                bb.position(info.offset); bb.limit(info.offset + info.size)
                                writer.safeWrite(audioIdx, bb, info)
                            }
                            enc.releaseOutputBuffer(oi, false)
                            if (info.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM != 0) eos = true
                        }
                        else -> return
                    }
                    if (eos) return
                }
            }

            while (framePos < totalFrames && !canceled.get() && !eos) {
                val want = min(blockFrames.toLong(), totalFrames - framePos).toInt()
                val frames = mixer.readBlock(pcm, framePos, want, spec, canceled)
                if (frames <= 0) break
                val slots = frames * chans
                var cursor = 0
                while (cursor < slots && !eos) {
                    val ib = enc.dequeueInputBuffer(10_000L)
                    if (ib < 0) { drain(); continue }
                    val bb = enc.getInputBuffer(ib)!!
                    if (capBytes < 0) capBytes = bb.capacity()
                    val capSlots = capBytes / 2
                    val feed = min(capSlots, slots - cursor)
                    bb.clear()
                    val sb = bb.asShortBuffer()
                    for (i in 0 until feed) sb.put(pcm[cursor + i])
                    val frameIdx = framePos + cursor / chans
                    val ptsUs = frameIdx * 1_000_000 / spec.sampleRate
                    enc.queueInputBuffer(ib, 0, feed * 2, ptsUs, 0)
                    cursor += feed
                }
                framePos += frames
                drain()
            }
            if (!eos) {
                var guard = 0
                var posted = false
                while (guard++ < 40 && !posted) {
                    val ib = enc.dequeueInputBuffer(10_000L)
                    if (ib < 0) drain()
                    else { enc.queueInputBuffer(ib, 0, 0, 0, MediaCodec.BUFFER_FLAG_END_OF_STREAM); posted = true }
                }
                var g2 = 0
                while (!eos && g2++ < 100) {
                    val oi = enc.dequeueOutputBuffer(info, 5_000L)
                    if (oi >= 0) {
                        if (info.size > 0 && gotFormat) {
                            val bb = enc.getOutputBuffer(oi)!!
                            bb.position(info.offset); bb.limit(info.offset + info.size)
                            writer.safeWrite(audioIdx, bb, info)
                        }
                        enc.releaseOutputBuffer(oi, false)
                        if (info.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM != 0) eos = true
                    }
                }
            }
            runCatching { enc.stop() }
            runCatching { enc.release() }
        } finally {
            mixer.close()
        }
    }

    // ------------------------------------------------------------------ audio mixer
    private class AudioMixer(val project: Project, val spec: Spec, val context: Context) {
        private val streams = java.util.concurrent.ConcurrentHashMap<String, AudioPull>()

        /**
         * Reads [nFrames] output frames starting at frame [outFrameStart] into dst
         * (interleaved). Returns the number of frames written.
         */
        fun readBlock(dst: ShortArray, outFrameStart: Long, nFrames: Int, spec: Spec, cancel: AtomicBoolean): Int {
            val chans = spec.channels
            if (nFrames <= 0) return 0
            val rate = spec.sampleRate
            val accL = FloatArray(nFrames)
            val accR = FloatArray(nFrames)
            val tStartMs = outFrameStart * 1000L / rate
            val msPerFrame = 1000L
            for (clip in project.clips) {
                if (clip.muted || cancel.get()) continue
                val segStart = clip.positionMs
                val segEnd = clip.outMsEnd()
                if (segEnd <= tStartMs) continue
                val v = clip.volume.coerceIn(0f, 4f)
                val stream = streams.getOrPut(clip.id) { AudioPull(context, clip, spec) }
                for (i in 0 until nFrames) {
                    val outMs = tStartMs + i * msPerFrame / rate
                    if (outMs < segStart || outMs >= segEnd) continue
                    val srcMs = clip.sourceTimeMs(outMs)
                    val pr = stream.read(srcMs)
                    accL[i] += pr.first * v
                    accR[i] += pr.second * v
                }
            }
            for (i in 0 until nFrames) {
                dst[i * chans] = (accL[i].coerceIn(-1f, 1f) * 32767f).toInt().toShort()
                dst[i * chans + 1] = (accR[i].coerceIn(-1f, 1f) * 32767f).toInt().toShort()
            }
            return nFrames
        }

        fun close() { streams.values.forEach { runCatching { it.close() } }; streams.clear() }
    }

    /** Streaming PCM provider for one clip (audio track of any media type), forward-only. */
    private class AudioPull(context: Context, val clip: Clip, val spec: Spec) {
        private var ex: MediaExtractor = MediaExtractor()
        private var dec: MediaCodec? = null
        private var rate = 44_100
        private var samplesAvailable = 0L      // absolute sample index of pending[pendingPos]
        private var pending = FloatArray(32768)
        private var pendingLen = 0
        private var pendingPos = 0
        private var eos = false
        private val info = MediaCodec.BufferInfo()

        init {
            runCatching { ex.setDataSource(context, android.net.Uri.parse(clip.sourceUri), null) }
            var ti = -1
            var fmt: MediaFormat? = null
            for (i in 0 until ex.trackCount) {
                val f = ex.getTrackFormat(i)
                if (f.getString(MediaFormat.KEY_MIME)?.startsWith("audio/") == true) { ti = i; fmt = f; break }
            }
            if (ti < 0) {
                runCatching { ex.release() }
                eos = true
            } else {
            val f = fmt!!
            rate = f.getInteger(MediaFormat.KEY_SAMPLE_RATE)
            ex.selectTrack(ti)
            ex.seekTo(clip.inMs * 1000, MediaExtractor.SEEK_TO_PREVIOUS_SYNC)
            val d = MediaCodec.createDecoderByType(f.getString(MediaFormat.KEY_MIME)!!)
            d.configure(f, null, null, 0)
            d.start()
            dec = d
            }
        }

        /** (left, right) at source time ms. Monotonic; returns (0,0) at/after EOS. */
        fun read(srcMs: Long): Pair<Float, Float> {
            if (eos) return 0f to 0f
            val want = srcMs * rate / 1000L
            var guard = 0
            while (samplesAvailable + (pendingLen - pendingPos) <= want && !eos && guard < 96) {
                service(); guard++
            }
            val rel = (want - samplesAvailable).toInt()
            if (rel < 0 || rel + 1 >= (pendingLen - pendingPos)) return 0f to 0f
            val a0 = pending[pendingPos + rel]
            val a1 = pending[pendingPos + rel + 1]
            // consume samples up to rel (we'll read the next one later)
            for (k in 0 until rel) { /* drop */ }
            pendingPos += rel
            samplesAvailable += rel
            // compact
            if (pendingPos > 0 && pendingPos > pendingLen / 2) {
                System.arraycopy(pending, pendingPos, pending, 0, pendingLen - pendingPos)
                pendingLen -= pendingPos; pendingPos = 0
            }
            return a0 to a1
        }

        private fun service() {
            if (eos) return
            val d = dec ?: return
            val qi = d.dequeueInputBuffer(0)
            if (qi >= 0) {
                val ib = d.getInputBuffer(qi)!!
                val n = ex.readSampleData(ib, 0)
                if (n < 0) { d.queueInputBuffer(qi, 0, 0, 0, MediaCodec.BUFFER_FLAG_END_OF_STREAM); eos = true }
                else d.queueInputBuffer(qi, 0, n, ex.sampleTime, 0)
            }
            val oi = d.dequeueOutputBuffer(info, 4_000L)
            if (oi >= 0) {
                if (info.size > 0) {
                    val sb = d.getOutputBuffer(oi)!!.asShortBuffer()
                    val samples = sb.remaining()
                    if (pendingPos > 0) {
                        System.arraycopy(pending, pendingPos, pending, 0, pendingLen - pendingPos)
                        pendingLen -= pendingPos; pendingPos = 0
                    }
                    while (pendingLen + samples > pending.size) pending = FloatArray(pending.size * 2)
                    for (i in 0 until samples) pending[pendingLen + i] = sb.get() / 32768f
                    pendingLen += samples
                    samplesAvailable = (info.presentationTimeUs / 1000L) * rate / 1000L
                }
                d.releaseOutputBuffer(oi, false)
            } else if (oi == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED) {
                // format updated
            }
            if (info.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM != 0) eos = true
        }

        fun close() {
            runCatching { dec?.stop() }
            runCatching { dec?.release() }
            runCatching { ex.release() }
        }
    }

}
