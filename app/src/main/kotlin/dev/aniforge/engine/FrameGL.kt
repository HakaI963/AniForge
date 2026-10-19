package dev.aniforge.engine

import android.graphics.Bitmap
import android.opengl.GLES20
import android.opengl.GLUtils
import android.opengl.Matrix
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.FloatBuffer
import kotlin.math.min

/** GLSL sources + GL helpers, shared by preview (GLSurfaceView) and export (encoder EGL). */
object Shaders {
    const val VS = """
        uniform mat4 uMVP;
        uniform mat4 uSTMat;
        attribute vec4 aPos;
        attribute vec2 aUV;
        varying vec2 vTex;
        void main(){
            gl_Position = uMVP * aPos;
            vTex = (uSTMat * vec4(aUV, 0.0, 1.0)).xy;
        }
    """

    const val FS_VIDEO = """
        #extension GL_OES_EGL_image_external : require
        precision mediump float;
        varying vec2 vTex;
        uniform samplerExternalOES uTex;
        uniform float uBright;
        uniform float uContrast;
        uniform float uSat;
        uniform vec3 uTint;
        uniform float uAlpha;
        uniform vec4 uClampUV;
        void main(){
            vec2 uv = clamp(vTex, uClampUV.xy, uClampUV.zw);
            vec3 c = texture2D(uTex, uv).rgb;
            c = (c - 0.5) * uContrast + 0.5 + uBright;
            float l = dot(c, vec3(0.2126, 0.7152, 0.0722));
            c = mix(vec3(l), c, uSat);
            c *= uTint;
            gl_FragColor = vec4(clamp(c, 0.0, 1.0), uAlpha);
        }
    """

    const val FS_OVERLAY = """
        precision mediump float;
        varying vec2 vTex;
        uniform sampler2D uTex;
        uniform float uAlpha;
        void main(){
            vec4 c = texture2D(uTex, vTex);
            gl_FragColor = vec4(c.rgb, c.a * uAlpha);
        }
    """
}

private fun Float.toStringNice(): String = "%.6f".format(this)

class GLDrawer {

    data class Programs(val video: Int, val overlay: Int)
    val programs: Programs

    private val quad: FloatBuffer = ByteBuffer.allocateDirect(4 * 4 * 4).run {
        order(ByteOrder.nativeOrder()); asFloatBuffer()
    }.apply {
        // x, y, u, v — full-frame quad, crop window handled via uClampUV in FS
        put(floatArrayOf(-1f, -1f, 0f, 1f))
        put(floatArrayOf(1f, -1f, 1f, 1f))
        put(floatArrayOf(1f, 1f, 1f, 0f))
        put(floatArrayOf(-1f, 1f, 0f, 0f))
        position(0)
    }

    private val idx: ByteBuffer = ByteBuffer.allocateDirect(6).apply {
        put(0); put(1); put(2); put(0); put(2); put(3); position(0)
    }

    init {
        val vs = compile(GLES20.GL_VERTEX_SHADER, Shaders.VS)
        val fsVideo = compile(GLES20.GL_FRAGMENT_SHADER, Shaders.FS_VIDEO)
        val fsOv = compile(GLES20.GL_FRAGMENT_SHADER, Shaders.FS_OVERLAY)
        val pv = GLES20.glCreateProgram()
        GLES20.glAttachShader(pv, vs); GLES20.glAttachShader(pv, fsVideo); GLES20.glLinkProgram(pv)
        val po = GLES20.glCreateProgram()
        GLES20.glAttachShader(po, vs); GLES20.glAttachShader(po, fsOv); GLES20.glLinkProgram(po)
        programs = Programs(pv, po)
        quad.position(0); idx.position(0)
    }

    private fun compile(type: Int, src: String): Int {
        val s = GLES20.glCreateShader(type)
        GLES20.glShaderSource(s, src); GLES20.glCompileShader(s)
        return s
    }

    fun drawVideo(
        texId: Int,
        stMat: FloatArray,       // SurfaceTexture transform
        mvp: FloatArray,         // clip-space MVP for the quad
        bright: Float, contrast: Float, sat: Float,
        tint: FloatArray,        // r,g,b multipliers
        alpha: Float,
        uvc: FloatArray,         // crop window [l,t,r,b] in texture space (pre-ST)
    ) {
        GLES20.glUseProgram(programs.video)
        val locPos = GLES20.glGetAttribLocation(programs.video, "aPos")
        val locUv = GLES20.glGetAttribLocation(programs.video, "aUV")
        GLES20.glEnableVertexAttribArray(locPos)
        GLES20.glEnableVertexAttribArray(locUv)
        quad.position(0)
        GLES20.glVertexAttribPointer(locPos, 2, GLES20.GL_FLOAT, false, 16, quad)
        quad.position(2)
        GLES20.glVertexAttribPointer(locUv, 2, GLES20.GL_FLOAT, false, 16, quad)

        GLES20.glUniformMatrix4fv(GLES20.glGetUniformLocation(programs.video, "uMVP"), 1, false, mvp, 0)
        GLES20.glUniformMatrix4fv(GLES20.glGetUniformLocation(programs.video, "uSTMat"), 1, false, stMat, 0)
        GLES20.glUniform1f(GLES20.glGetUniformLocation(programs.video, "uBright"), bright)
        GLES20.glUniform1f(GLES20.glGetUniformLocation(programs.video, "uContrast"), contrast)
        // scale slightly above nominal to keep highlights visible
        GLES20.glUniform1f(GLES20.glGetUniformLocation(programs.video, "uSat"), (sat * 1.0f))
        GLES20.glUniform3f(GLES20.glGetUniformLocation(programs.video, "uTint"), tint[0], tint[1], tint[2])
        GLES20.glUniform1f(GLES20.glGetUniformLocation(programs.video, "uAlpha"), alpha)
        GLES20.glUniform4f(GLES20.glGetUniformLocation(programs.video, "uClampUV"), uvc[0], uvc[1], uvc[2], uvc[3])

        GLES20.glActiveTexture(GLES20.GL_TEXTURE0)
        GLES20.glBindTexture(android.opengl.GLES11Ext.GL_TEXTURE_EXTERNAL_OES, texId)
        GLES20.glUniform1i(GLES20.glGetUniformLocation(programs.video, "uTex"), 0)

        GLES20.glDrawElements(GLES20.GL_TRIANGLES, 6, GLES20.GL_UNSIGNED_BYTE, idx)
        GLES20.glDisableVertexAttribArray(locPos)
        GLES20.glDisableVertexAttribArray(locUv)
        GLES20.glBindTexture(android.opengl.GLES11Ext.GL_TEXTURE_EXTERNAL_OES, 0)
    }

    /** android Bitmap as a GL texture ( pulverized — recreated per frame). */
    private val overlayCache = HashMap<String, Int>()
    private val mvpLocal = FloatArray(16)

    fun overlayTexture(key: String, bmp: Bitmap): Int {
        val cached = overlayCache[key]
        if (cached != null) return cached
        val id = newTextureId()
        overlayCache[key] = id
        GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, id)
        GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_MIN_FILTER, GLES20.GL_LINEAR)
        GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_MAG_FILTER, GLES20.GL_LINEAR)
        GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_WRAP_S, GLES20.GL_CLAMP_TO_EDGE)
        GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_WRAP_T, GLES20.GL_CLAMP_TO_EDGE)
        GLUtils.texImage2D(GLES20.GL_TEXTURE_2D, 0, bmp, 0)
        GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, 0)
        return id
    }

    fun deleteUnused(keep: Set<String>) {
        val it = overlayCache.entries.iterator()
        while (it.hasNext()) {
            val e = it.next()
            if (e.key !in keep) { deleteTex(e.value); it.remove() }
        }
    }

    fun deleteTex(id: Int) { GLES20.glDeleteTextures(1, intArrayOf(id), 0) }

    private fun newTextureId(): Int =
        intArrayOf(0).also { GLES20.glGenTextures(1, it, 0) }[0]

    fun drawOverlay(
        texId: Int,
        w: Int, h: Int,
        bmpW: Int, bmpH: Int,
        posX: Float, posY: Float,       // -1..1 fractions of remaining space
        alpha: Float,
    ) {
        GLES20.glEnable(GLES20.GL_BLEND)
        GLES20.glBlendFunc(GLES20.GL_SRC_ALPHA, GLES20.GL_ONE_MINUS_SRC_ALPHA)
        GLES20.glUseProgram(programs.overlay)

        val relW = bmpW.toFloat() / w // fraction of width
        val relH = bmpH.toFloat() / h
        val m = mvpLocal; Matrix.setIdentityM(m, 0)
        Matrix.orthoM(m, 0, -1f, 1f, -1f, 1f, -1f, 1f)
        Matrix.translateM(m, 0, posX * (2f - relW), -posY * (2f - relH), 0f)
        Matrix.scaleM(m, 0, min(relW * 2f, 0.95f), min(relH * 2f, 0.95f), 1f)

        val locPos = GLES20.glGetAttribLocation(programs.overlay, "aPos")
        val locUv = GLES20.glGetAttribLocation(programs.overlay, "aUV")
        GLES20.glEnableVertexAttribArray(locPos); GLES20.glEnableVertexAttribArray(locUv)
        quad.position(0)
        GLES20.glVertexAttribPointer(locPos, 2, GLES20.GL_FLOAT, false, 16, quad)
        quad.position(2)
        GLES20.glVertexAttribPointer(locUv, 2, GLES20.GL_FLOAT, false, 16, quad)
        GLES20.glUniformMatrix4fv(GLES20.glGetUniformLocation(programs.overlay, "uMVP"), 1, false, m, 0)
        val ident = FloatArray(16); Matrix.setIdentityM(ident, 0)
        GLES20.glUniformMatrix4fv(GLES20.glGetUniformLocation(programs.overlay, "uSTMat"), 1, false, ident, 0)
        GLES20.glUniform1f(GLES20.glGetUniformLocation(programs.overlay, "uAlpha"), alpha)
        GLES20.glActiveTexture(GLES20.GL_TEXTURE0)
        GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, texId)
        GLES20.glUniform1i(GLES20.glGetUniformLocation(programs.overlay, "uTex"), 0)
        GLES20.glDrawElements(GLES20.GL_TRIANGLES, 6, GLES20.GL_UNSIGNED_BYTE, idx)
        GLES20.glDisableVertexAttribArray(locPos); GLES20.glDisableVertexAttribArray(locUv)
        GLES20.glDisable(GLES20.GL_BLEND)
        GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, 0)
    }


/** MVP computation for 2D output quads. */
object RenderMat {
    private val tmp = FloatArray(16)
    private val tmp2 = FloatArray(16)
    private val out = FloatArray(16)

    /**
     * Returns an MVP placing a full-frame quad inside an aspect-correct box of the output,
     * then applying clip transform (scale/rot/pan on top of the box).
     * For crop modes that fill the frame, pass boxAspect == targetAspect (i.e. null → we compute).
     */
    fun videoMvp(outW: Int, outH: Int, boxAspect: Float, scale: Float, rotDeg: Float, posX: Float, posY: Float): FloatArray {
        val targetAspect = outW.toFloat() / outH.toFloat()
        var bw = 1f; var bh = 1f
        if (boxAspect > targetAspect) bh = targetAspect / boxAspect else bw = boxAspect / targetAspect
        Matrix.setIdentityM(tmp, 0)
        tmp[0] = bw; tmp[5] = bh
        Matrix.setIdentityM(tmp2, 0)
        Matrix.translateM(tmp2, 0, posX, -posY, 0f)
        Matrix.rotateM(tmp2, 0, rotDeg, 0f, 0f, 1f)
        Matrix.scaleM(tmp2, 0, scale, scale, 1f)
        Matrix.multiplyMM(out, 0, tmp, 0, tmp2, 0)
        return out.clone()
    }

    /** Crop-mode MVP: crop box fills the whole output frame (uv window defines the crop). */
    fun cropFillMvp(scale: Float, rotDeg: Float, posX: Float, posY: Float): FloatArray {
        Matrix.setIdentityM(tmp, 0)
        Matrix.setIdentityM(tmp2, 0)
        Matrix.translateM(tmp2, 0, posX, -posY, 0f)
        Matrix.rotateM(tmp2, 0, rotDeg, 0f, 0f, 1f)
        Matrix.scaleM(tmp2, 0, scale, scale, 1f)
        Matrix.multiplyMM(out, 0, tmp, 0, tmp2, 0)
        return out.clone()
    }

    fun ortho(): FloatArray = FloatArray(16).also { Matrix.orthoM(it, 0, -1f, 1f, -1f, 1f, -1f, 1f) }
}

/** Crop/pan/zoom UV windows in normalized source space. */
object Crop {
    fun window(cropAspect: Float?, srcAspect: Float, posX: Float, posY: Float, scale: Float): FloatArray {
        val zoom = scale.coerceAtLeast(0.05f)
        val windowAspect = cropAspect ?: srcAspect
        var winW = 1f; var winH = 1f
        if (windowAspect <= srcAspect) winW = windowAspect / srcAspect else winH = srcAspect / windowAspect
        winW /= zoom; winH /= zoom
        val w = winW.coerceIn(0.02f, 1f); val h = winH.coerceIn(0.02f, 1f)
        val marginX = (1f - w) / 2f; val marginY = (1f - h) / 2f
        val cx = 0.5f + posX * marginX
        val cy = 0.5f + posY * marginY
        return floatArrayOf(cx - w / 2f, cy - h / 2f, cx + w / 2f, cy + h / 2f)
    }
}
}
