package dev.aniforge.engine

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.SurfaceTexture
import android.opengl.GLES11Ext
import android.opengl.GLES20
import android.opengl.GLSurfaceView
import android.os.Handler
import android.os.Looper
import android.view.Surface
import dev.aniforge.domain.Clip
import dev.aniforge.domain.Project
import dev.aniforge.domain.Prop
import javax.microedition.khronos.egl.EGLConfig
import javax.microedition.khronos.opengles.GL10
import kotlin.math.max
import kotlin.math.min

/** Snapshot bridge between the Compose/UI thread and the render thread. */
object PreviewState {
    @Volatile var project: Project? = null
    @Volatile var playheadMs: Long = 0
}

/** Text overlay rendering cache (used by both preview and export). */
object TitlePainter {
    private val cache = HashMap<String, Bitmap>()

    @Synchronized
    fun bitmap(clip: Clip, w: Int, h: Int): Bitmap? {
        val key = "${clip.id}|${clip.title}|${clip.titleColor}|h$h"
        cache[key]?.let { return it }
        if (clip.title.isEmpty()) return null
        val bmpH = max(48, (h * min(0.32f, clip.titleSize + 0.08f)).toInt())
        val bmp = Bitmap.createBitmap(w, bmpH, Bitmap.Config.ARGB_8888)
        val c = Canvas(bmp)
        val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            isDither = true
            color = runCatching { Color.parseColor(clip.titleColor) }.getOrDefault(Color.WHITE)
            textSize = max(28f, bmpH * 0.55f)
            textAlign = Paint.Align.CENTER
            setShadowLayer(textSize / 12f, 0f, textSize / 24f, Color.argb(190, 0, 0, 0))
        }
        c.drawText(clip.title, w / 2f, bmpH * 0.66f, paint)
        if (cache.size > 24) cache.clear()
        cache[key] = bmp
        return bmp
    }
}

/** Preview GL surface: renders the compositing frame with the same math as export. */
class PreviewView(context: Context) : GLSurfaceView(context) {

    interface Callbacks {
        fun onSurfaceTextureAvailable(surfaceTexture: SurfaceTexture, surface: Surface)
    }

    private val main = Handler(Looper.getMainLooper())
    val renderer = Renderer()
    var callbacks: Callbacks? = null

    private var videoTextureId = -1
    private var st: SurfaceTexture? = null
    private var frameDirty = false
    private var drawer: GLDrawer? = null
    private val stMatrix = FloatArray(16)

    init {
        setEGLContextClientVersion(2)
        preserveEGLContextOnPause = true
        setEGLConfigChooser(8, 8, 8, 8, 16, 8)
        setRenderer(renderer)
        renderMode = RENDERMODE_CONTINUOUSLY
    }

    /** Call from a GL thread via queueEvent when a new encoded frame lands. */
    fun hasNewFrame() {
        main.post {
            frameDirty = true
            requestRender()
        }
    }

    fun currentSurfaceTexture(): SurfaceTexture? = st

    inner class Renderer : GLSurfaceView.Renderer {
        override fun onSurfaceCreated(gl: GL10?, config: EGLConfig?) {
            drawer = GLDrawer()
            val ids = intArrayOf(0)
            GLES20.glGenTextures(1, ids, 0)
            videoTextureId = ids[0]
            val t = SurfaceTexture(videoTextureId)
            t.setOnFrameAvailableListener({
                frameDirty = true
                main.post { requestRender() }
            }, main)
            st = t
            main.post {
                callbacks?.onSurfaceTextureAvailable(t, Surface(t))
            }
        }

        override fun onSurfaceChanged(gl: GL10?, width: Int, height: Int) {
            GLES20.glViewport(0, 0, width, height)
        }

        override fun onDrawFrame(gl: GL10?) {
            val d = drawer ?: return
            GLES20.glClearColor(0.043f, 0.043f, 0.055f, 1f)
            GLES20.glClear(GLES20.GL_COLOR_BUFFER_BIT or GLES20.GL_DEPTH_BUFFER_BIT)
            val t = st ?: return
            if (frameDirty) { frameDirty = false; runCatching { t.updateTexImage() }.onFailure { return } }
            t.getTransformMatrix(stMatrix)
            try {
                drawTimeline(d)
            } catch (ex: Throwable) {
                // keep rendering, don't crash the UI thread
            }
        }

        private fun drawTimeline(d: GLDrawer) {
            val p = PreviewState.project ?: return
            val t = PreviewState.playheadMs
            val clip = p.clips.firstOrNull { it.kind != "audio" && it.contains(t) } ?: return
            val isImage = clip.kind == "image"
            val srcAspect = if (clip.srcH > 0 && clip.srcW > 0) clip.srcW.toFloat() / clip.srcH.toFloat() else 16f / 9f
            val localMs = max(0L, t - clip.positionMs)
            val local = localMs / 1000f
            val scale = clip.prop(Prop.SCALE, local)
            val rot = clip.prop(Prop.ROT, local)
            val px = clip.prop(Prop.POSX, local)
            val py = clip.prop(Prop.POSY, local)
            val alpha = clip.prop(Prop.OPACITY, local).coerceIn(0f, 1f)
            val bright = clip.brightness + clip.prop(Prop.BRIGHT, local)
            val tint = when (clip.color) {
                dev.aniforge.domain.ColorGrade.WARM -> floatArrayOf(1.10f, 1.00f, 0.86f)
                dev.aniforge.domain.ColorGrade.COOL -> floatArrayOf(0.86f, 1.00f, 1.10f)
                dev.aniforge.domain.ColorGrade.NOIR -> floatArrayOf(1f, 1f, 1f)
                dev.aniforge.domain.ColorGrade.PASTEL -> floatArrayOf(1.05f, 0.99f, 1.05f)
                dev.aniforge.domain.ColorGrade.VIVID -> floatArrayOf(1.06f, 1.02f, 1.02f)
                else -> floatArrayOf(1f, 1f, 1f)
            }
            val mvp: FloatArray
            val win: FloatArray
            if (clip.crop.aspect != null) {
                mvp = RenderMat.ortho()
                win = Crop.window(clip.crop.aspect, srcAspect, px, py, scale)
            } else {
                mvp = RenderMat.videoMvp(width, height, srcAspect, scale, rot, px, py)
                win = floatArrayOf(0f, 0f, 1f, 1f)
            }
            if (isImage) {
                // title card / fetched material: 2D bitmap texture
                val path = clip.sourceUri.removePrefix("file://")
                ImageRenders.begin(path)
                val bmp = ImageRenders.peek(path) ?: return
                val texId = d.overlayTexture("card" + clip.id, bmp)
                val m2 = if (clip.crop.aspect != null) RenderMat.ortho()
                    else RenderMat.videoMvp(width, height, srcAspect, scale, rot, px, py)
                val w2 = if (clip.crop.aspect != null) Crop.window(clip.crop.aspect, srcAspect, px, py, scale)
                    else floatArrayOf(0f, 0f, 1f, 1f)
                d.drawBitmapTexture(texId, m2, bright, clip.contrast, clip.saturation, tint, alpha, w2)
            } else if (clip.color == dev.aniforge.domain.ColorGrade.NOIR) {
                d.drawVideo(videoTextureId, stMatrix, mvp, bright, clip.contrast, 0f, tint, alpha, win)
            } else {
                d.drawVideo(videoTextureId, stMatrix, mvp, bright, clip.contrast, clip.saturation, tint, alpha, win)
            }
            if (clip.title.isNotEmpty()) {
                TitlePainter.bitmap(clip, width, height)?.let { bmp ->
                    val id = d.overlayTexture("title" + clip.id, bmp)
                    d.drawOverlay(id, width, height, bmp.width, bmp.height, 0f, -0.55f, alpha)
                }
            }
        }
    }
}
