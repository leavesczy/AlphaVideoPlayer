package github.leavesczy.alphavideoplayer.internal.render

import android.graphics.SurfaceTexture
import android.opengl.GLES20
import android.opengl.Matrix
import android.view.Surface
import github.leavesczy.alphavideoplayer.AlphaVideo
import github.leavesczy.alphavideoplayer.AlphaVideoLog
import github.leavesczy.alphavideoplayer.internal.widget.IAlphaVideoRenderView
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.FloatBuffer
import java.util.Locale
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import javax.microedition.khronos.egl.EGLConfig
import javax.microedition.khronos.opengles.GL10
import kotlin.math.max
import kotlin.math.min

/**
 * Samples a half-frame RGB + alpha OES [SurfaceTexture].
 * The fragment shader writes source RGB and the alpha half's green channel.
 * Blend is `GL_ONE` / `GL_ONE_MINUS_SRC_ALPHA`, so the RGB half must be black-matted.
 */
internal class AlphaVideoRenderer(
    private val renderView: IAlphaVideoRenderView
) : IAlphaVideoRender {

    companion object {

        private const val COMPONENT = "AlphaVideoRenderer"

        private const val FLOAT_SIZE_BYTES = 4

        private const val TRIANGLE_VERTICES_DATA_STRIDE_BYTES = 7 * FLOAT_SIZE_BYTES

        private const val TRIANGLE_VERTICES_DATA_POS_OFFSET = 0

        private const val TRIANGLE_VERTICES_DATA_UV_OFFSET = 3

        private const val TRIANGLE_VERTICES_DATA_ALPHA_UV_OFFSET = 5

        private const val GL_TEXTURE_EXTERNAL_OES = 0x8D65

    }

    private var surfaceListener: IAlphaVideoRender.SurfaceListener? = null

    private var scaleType = AlphaVideo.ScaleType.FitCenter

    private var layout = AlphaVideo.Layout.AlphaLeft

    private var lastViewWidth = 0f

    private var lastViewHeight = 0f

    private var lastVideoWidth = 0f

    private var lastVideoHeight = 0f

    private var lastScaleType: AlphaVideo.ScaleType? = null

    private var lastLayout: AlphaVideo.Layout? = null

    private var verticesData = floatArrayOf(
        -1.0f, -1.0f, 0f, 0.5f, 0f, 0f, 0f,
        1.0f, -1.0f, 0f, 1f, 0f, 0.5f, 0f,
        -1.0f, 1.0f, 0f, 0.5f, 1f, 0f, 1f,
        1.0f, 1.0f, 0f, 1f, 1f, 0.5f, 1f
    )

    private var triangleVertices: FloatBuffer = createFloatBuffer(data = verticesData)

    private val mvpMatrix = FloatArray(size = 16)

    private val stMatrix = FloatArray(size = 16)

    private var programId = 0

    private var textureId = 0

    private var aPositionHandle = 0

    private var aTextureHandle = 0

    private var aAlphaTextureHandle = 0

    private var uMvpMatrixHandle = 0

    private var uStMatrixHandle = 0

    private val canDraw = AtomicBoolean(false)

    private val updateSurface = AtomicBoolean(false)

    /** An OES frame was consumed. Drawing waits on this. Cleared when sampling stops or GL is released. */
    private val frameLatched = AtomicBoolean(false)

    /** Skips later `updateTexImage` calls. Cleared when the session ends or a new OES texture is created. */
    private val texImageFailureReported = AtomicBoolean(false)

    private var surfaceTexture: SurfaceTexture? = null

    private var playerSurface: Surface? = null

    private val bufferLock = Any()

    private val maxTextureSize = AtomicInteger(0)

    private var packedWidth = 0

    private var packedHeight = 0

    private var appliedBufferWidth = 0

    private var appliedBufferHeight = 0

    private var samplingWidth = 0

    private var samplingHeight = 0

    init {
        Matrix.setIdentityM(stMatrix, 0)
    }

    override fun setSurfaceListener(surfaceListener: IAlphaVideoRender.SurfaceListener) {
        this.surfaceListener = surfaceListener
    }

    override fun setScaleType(scaleType: AlphaVideo.ScaleType) {
        this.scaleType = scaleType
        invalidateGeometry()
    }

    override fun setLayout(layout: AlphaVideo.Layout) {
        this.layout = layout
        invalidateGeometry()
    }

    override fun measureInternal(
        viewWidth: Float,
        viewHeight: Float,
        videoWidth: Float,
        videoHeight: Float
    ) {
        if (viewWidth <= 0 || viewHeight <= 0 || videoWidth <= 0 || videoHeight <= 0) {
            return
        }
        if (viewWidth == lastViewWidth && viewHeight == lastViewHeight
            && videoWidth == lastVideoWidth && videoHeight == lastVideoHeight
            && scaleType == lastScaleType
            && layout == lastLayout
        ) {
            return
        }
        lastViewWidth = viewWidth
        lastViewHeight = viewHeight
        lastVideoWidth = videoWidth
        lastVideoHeight = videoHeight
        lastScaleType = scaleType
        lastLayout = layout
        logScaleDiagnostics(
            viewWidth = viewWidth,
            viewHeight = viewHeight,
            videoWidth = videoWidth,
            videoHeight = videoHeight,
            scaleType = scaleType,
            layout = layout
        )
        val (textureWidth, textureHeight) = samplingTextureSize(
            videoWidth = videoWidth,
            videoHeight = videoHeight
        )
        verticesData = AlphaVideoTextureCropUtil.calculateVerticesData(
            layout = layout,
            scaleType = scaleType,
            viewWidth = viewWidth,
            viewHeight = viewHeight,
            videoWidth = videoWidth,
            videoHeight = videoHeight,
            textureWidth = textureWidth,
            textureHeight = textureHeight
        )
        updateFloatBuffer(data = verticesData)
        if (canDraw.get()) {
            renderView.requestRender()
        }
    }

    override fun onFirstFrame() {
        canDraw.set(true)
        renderView.requestRender()
    }

    override fun clearCanDraw() {
        canDraw.set(false)
        frameLatched.set(false)
    }

    private fun isGeometryReady(): Boolean {
        return lastViewWidth > 0f &&
                lastViewHeight > 0f &&
                lastVideoWidth > 0f &&
                lastVideoHeight > 0f &&
                lastScaleType == scaleType &&
                lastLayout == layout
    }

    private fun invalidateGeometry() {
        lastViewWidth = 0f
        lastViewHeight = 0f
        lastVideoWidth = 0f
        lastVideoHeight = 0f
        lastScaleType = null
        lastLayout = null
    }

    override fun onCompletion() {
        canDraw.set(false)
        frameLatched.set(false)
        texImageFailureReported.set(false)
        invalidateGeometry()
        val texture = surfaceTexture
        if (texture != null && updateSurface.compareAndSet(true, false)) {
            try {
                // Release the buffer. Dropping the signal here stalls every later video.
                texture.updateTexImage()
            } catch (e: Exception) {
                AlphaVideoLog.w(
                    component = COMPONENT,
                    message = "updateTexImage during completion failed",
                    throwable = e
                )
            }
        }
        renderView.requestRender()
    }

    override fun onEglSetupFailed(message: String) {
        surfaceListener?.onRenderSetupFailed(
            errorCode = AlphaVideo.ErrorCode.Egl,
            message = message
        )
    }

    override fun onSurfaceCreated(gl: GL10, config: EGLConfig) {
        val maxSize = IntArray(size = 1)
        GLES20.glGetIntegerv(GLES20.GL_MAX_TEXTURE_SIZE, maxSize, 0)
        if (maxSize[0] > 0) {
            maxTextureSize.set(maxSize[0])
        }
        if (surfaceTexture != null) {
            surfaceListener?.onSurfaceDestroyed()
        }
        releaseGlResources()
        try {
            programId = createProgram()
            if (programId == 0) {
                surfaceListener?.onRenderSetupFailed(
                    errorCode = AlphaVideo.ErrorCode.Render,
                    message = "Failed to create GL program"
                )
                return
            }
            aPositionHandle = GLES20.glGetAttribLocation(programId, "aPosition")
            aTextureHandle = GLES20.glGetAttribLocation(programId, "aTextureCoord")
            aAlphaTextureHandle = GLES20.glGetAttribLocation(programId, "aAlphaTextureCoord")
            uMvpMatrixHandle = GLES20.glGetUniformLocation(programId, "uMVPMatrix")
            uStMatrixHandle = GLES20.glGetUniformLocation(programId, "uSTMatrix")
            if (aPositionHandle == -1 || aTextureHandle == -1 || aAlphaTextureHandle == -1
                || uMvpMatrixHandle == -1 || uStMatrixHandle == -1
            ) {
                releaseGlResources()
                surfaceListener?.onRenderSetupFailed(
                    errorCode = AlphaVideo.ErrorCode.Render,
                    message = "Failed to resolve GL shader locations"
                )
                return
            }
            prepareSurface()
        } catch (e: Exception) {
            AlphaVideoLog.e(
                component = COMPONENT,
                message = "onSurfaceCreated failed",
                throwable = e
            )
            releaseGlResources()
            surfaceListener?.onRenderSetupFailed(
                errorCode = AlphaVideo.ErrorCode.Render,
                message = "onSurfaceCreated failure: ${e.message ?: e}"
            )
        }
    }

    override fun onSurfaceChanged(gl: GL10, width: Int, height: Int) {
        GLES20.glViewport(0, 0, width, height)
    }

    override fun onDrawFrame(gl: GL10) {
        if (updateSurface.compareAndSet(true, false)) {
            val texture = surfaceTexture
            if (texture != null && !texImageFailureReported.get()) {
                try {
                    texture.updateTexImage()
                    texture.getTransformMatrix(stMatrix)
                    frameLatched.set(true)
                } catch (e: Exception) {
                    AlphaVideoLog.e(
                        component = COMPONENT,
                        message = "updateTexImage failed",
                        throwable = e
                    )
                    canDraw.set(false)
                    frameLatched.set(false)
                    if (texImageFailureReported.compareAndSet(false, true)) {
                        surfaceListener?.onRenderSetupFailed(
                            errorCode = AlphaVideo.ErrorCode.Render,
                            message = "updateTexImage failure: ${e.message ?: e}"
                        )
                    }
                }
            }
        }
        GLES20.glClearColor(0.0f, 0.0f, 0.0f, 0.0f)
        GLES20.glClear(GLES20.GL_COLOR_BUFFER_BIT)
        if (!canDraw.get() || !frameLatched.get() || !isGeometryReady() || programId == 0) {
            return
        }
        GLES20.glEnable(GLES20.GL_BLEND)
        GLES20.glBlendFunc(GLES20.GL_ONE, GLES20.GL_ONE_MINUS_SRC_ALPHA)
        GLES20.glUseProgram(programId)
        checkGlError(op = "glUseProgram")
        GLES20.glActiveTexture(GLES20.GL_TEXTURE0)
        GLES20.glBindTexture(GL_TEXTURE_EXTERNAL_OES, textureId)
        triangleVertices.position(TRIANGLE_VERTICES_DATA_POS_OFFSET)
        GLES20.glVertexAttribPointer(
            aPositionHandle, 3, GLES20.GL_FLOAT, false,
            TRIANGLE_VERTICES_DATA_STRIDE_BYTES, triangleVertices
        )
        checkGlError(op = "glVertexAttribPointer maPosition")
        GLES20.glEnableVertexAttribArray(aPositionHandle)
        triangleVertices.position(TRIANGLE_VERTICES_DATA_UV_OFFSET)
        GLES20.glVertexAttribPointer(
            aTextureHandle, 2, GLES20.GL_FLOAT, false,
            TRIANGLE_VERTICES_DATA_STRIDE_BYTES, triangleVertices
        )
        checkGlError(op = "glVertexAttribPointer aTextureHandle")
        GLES20.glEnableVertexAttribArray(aTextureHandle)
        triangleVertices.position(TRIANGLE_VERTICES_DATA_ALPHA_UV_OFFSET)
        GLES20.glVertexAttribPointer(
            aAlphaTextureHandle, 2, GLES20.GL_FLOAT, false,
            TRIANGLE_VERTICES_DATA_STRIDE_BYTES, triangleVertices
        )
        checkGlError(op = "glVertexAttribPointer aAlphaTextureHandle")
        GLES20.glEnableVertexAttribArray(aAlphaTextureHandle)
        Matrix.setIdentityM(mvpMatrix, 0)
        GLES20.glUniformMatrix4fv(uMvpMatrixHandle, 1, false, mvpMatrix, 0)
        GLES20.glUniformMatrix4fv(uStMatrixHandle, 1, false, stMatrix, 0)
        GLES20.glDrawArrays(GLES20.GL_TRIANGLE_STRIP, 0, 4)
        checkGlError(op = "glDrawArrays")
        GLES20.glDisableVertexAttribArray(aPositionHandle)
        GLES20.glDisableVertexAttribArray(aTextureHandle)
        GLES20.glDisableVertexAttribArray(aAlphaTextureHandle)
        GLES20.glBindTexture(GL_TEXTURE_EXTERNAL_OES, 0)
        GLES20.glDisable(GLES20.GL_BLEND)
    }

    override fun onSurfaceDestroyed(gl: GL10?) {
        surfaceListener?.onSurfaceDestroyed()
        releaseGlResources()
    }

    override fun onFrameAvailable(surface: SurfaceTexture) {
        updateSurface.set(true)
        renderView.requestRender()
    }

    private fun createProgram(): Int {
        val vertexShader = loadShader(
            shaderType = GLES20.GL_VERTEX_SHADER,
            source = AlphaVideoShaderSources.VERTEX
        )
        if (vertexShader == 0) {
            return 0
        }
        val pixelShader = loadShader(
            shaderType = GLES20.GL_FRAGMENT_SHADER,
            source = AlphaVideoShaderSources.FRAGMENT
        )
        if (pixelShader == 0) {
            GLES20.glDeleteShader(vertexShader)
            return 0
        }
        var program = GLES20.glCreateProgram()
        if (program != 0) {
            GLES20.glAttachShader(program, vertexShader)
            checkGlError(op = "glAttachShader")
            GLES20.glAttachShader(program, pixelShader)
            checkGlError(op = "glAttachShader")
            GLES20.glLinkProgram(program)
            val linkStatus = IntArray(size = 1)
            GLES20.glGetProgramiv(program, GLES20.GL_LINK_STATUS, linkStatus, 0)
            if (linkStatus[0] != GLES20.GL_TRUE) {
                AlphaVideoLog.e(
                    component = COMPONENT,
                    message = "Could not link program: ${GLES20.glGetProgramInfoLog(program)}"
                )
                GLES20.glDeleteProgram(program)
                program = 0
            }
        }
        GLES20.glDeleteShader(vertexShader)
        GLES20.glDeleteShader(pixelShader)
        return program
    }

    private fun loadShader(shaderType: Int, source: String): Int {
        var shader = GLES20.glCreateShader(shaderType)
        if (shader != 0) {
            GLES20.glShaderSource(shader, source)
            GLES20.glCompileShader(shader)
            val compiled = IntArray(size = 1)
            GLES20.glGetShaderiv(shader, GLES20.GL_COMPILE_STATUS, compiled, 0)
            if (compiled[0] == 0) {
                AlphaVideoLog.e(
                    component = COMPONENT,
                    message = "Could not compile shader $shaderType: ${
                        GLES20.glGetShaderInfoLog(
                            shader
                        )
                    }"
                )
                GLES20.glDeleteShader(shader)
                shader = 0
            }
        }
        return shader
    }

    private fun prepareSurface() {
        val textures = IntArray(size = 1)
        GLES20.glGenTextures(1, textures, 0)
        textureId = textures[0]
        GLES20.glBindTexture(GL_TEXTURE_EXTERNAL_OES, textureId)
        checkGlError(op = "glBindTexture textureId")
        GLES20.glTexParameteri(
            GL_TEXTURE_EXTERNAL_OES,
            GLES20.GL_TEXTURE_MIN_FILTER,
            GLES20.GL_LINEAR
        )
        GLES20.glTexParameteri(
            GL_TEXTURE_EXTERNAL_OES,
            GLES20.GL_TEXTURE_MAG_FILTER,
            GLES20.GL_LINEAR
        )
        GLES20.glTexParameteri(
            GL_TEXTURE_EXTERNAL_OES,
            GLES20.GL_TEXTURE_WRAP_S,
            GLES20.GL_CLAMP_TO_EDGE
        )
        GLES20.glTexParameteri(
            GL_TEXTURE_EXTERNAL_OES,
            GLES20.GL_TEXTURE_WRAP_T,
            GLES20.GL_CLAMP_TO_EDGE
        )
        val texture = SurfaceTexture(textureId)
        texture.setOnFrameAvailableListener(this)
        synchronized(bufferLock) {
            surfaceTexture = texture
            applyDecoderBufferSizeLocked()
        }
        val surface = Surface(texture)
        playerSurface = surface
        texImageFailureReported.set(false)
        surfaceListener?.onSurfacePrepared(surface = surface)
        updateSurface.set(false)
    }

    override fun fitDecoderBuffer(sourceWidth: Int, sourceHeight: Int): Boolean {
        if (sourceWidth <= 0 || sourceHeight <= 0) {
            return false
        }
        synchronized(bufferLock) {
            packedWidth = sourceWidth
            packedHeight = sourceHeight
            return applyDecoderBufferSizeLocked()
        }
    }

    private fun samplingTextureSize(videoWidth: Float, videoHeight: Float): Pair<Float, Float> {
        synchronized(bufferLock) {
            if (samplingWidth > 0 && samplingHeight > 0) {
                return samplingWidth.toFloat() to samplingHeight.toFloat()
            }
        }
        return when (layout) {
            AlphaVideo.Layout.AlphaLeft, AlphaVideo.Layout.AlphaRight -> {
                videoWidth * 2f to videoHeight
            }

            AlphaVideo.Layout.AlphaTop, AlphaVideo.Layout.AlphaBottom -> {
                videoWidth to videoHeight * 2f
            }
        }
    }

    private fun applyDecoderBufferSizeLocked(): Boolean {
        val sourceWidth = packedWidth
        val sourceHeight = packedHeight
        if (sourceWidth <= 0 || sourceHeight <= 0) {
            return false
        }
        val maxSize = maxTextureSize.get()
        // GL_MAX_TEXTURE_SIZE is known only after the context exists.
        val (width, height) = if (maxSize > 0) {
            fittedBufferSize(
                sourceWidth = sourceWidth,
                sourceHeight = sourceHeight,
                maxSize = maxSize
            )
        } else {
            sourceWidth to sourceHeight
        }
        samplingWidth = width
        samplingHeight = height
        val texture = surfaceTexture ?: return false
        val changed = width != appliedBufferWidth || height != appliedBufferHeight
        val downscaled = width != sourceWidth || height != sourceHeight
        if (changed && (downscaled || appliedBufferWidth != 0)) {
            texture.setDefaultBufferSize(width, height)
            appliedBufferWidth = width
            appliedBufferHeight = height
            AlphaVideoLog.i(
                component = COMPONENT,
                message = "decoder buffer ${sourceWidth}x${sourceHeight} -> ${width}x${height} " +
                        "maxTexture=$maxSize"
            )
            return true
        }
        if (appliedBufferWidth == 0) {
            appliedBufferWidth = width
            appliedBufferHeight = height
        }
        return false
    }

    private fun fittedBufferSize(
        sourceWidth: Int,
        sourceHeight: Int,
        maxSize: Int
    ): Pair<Int, Int> {
        if (sourceWidth <= maxSize && sourceHeight <= maxSize) {
            return sourceWidth to sourceHeight
        }
        val scale = min(
            a = maxSize.toFloat() / sourceWidth.toFloat(),
            b = maxSize.toFloat() / sourceHeight.toFloat()
        )
        var width = (sourceWidth * scale).toInt().coerceIn(minimumValue = 2, maximumValue = maxSize)
        var height =
            (sourceHeight * scale).toInt().coerceIn(minimumValue = 2, maximumValue = maxSize)
        // Decoder buffers need even dimensions.
        if (width % 2 != 0) {
            width -= 1
        }
        if (height % 2 != 0) {
            height -= 1
        }
        return width.coerceAtLeast(minimumValue = 2) to height.coerceAtLeast(minimumValue = 2)
    }

    private fun releaseGlResources() {
        canDraw.set(false)
        updateSurface.set(false)
        frameLatched.set(false)
        playerSurface?.release()
        playerSurface = null
        synchronized(bufferLock) {
            surfaceTexture?.setOnFrameAvailableListener(null)
            surfaceTexture?.release()
            surfaceTexture = null
            appliedBufferWidth = 0
            appliedBufferHeight = 0
            samplingWidth = 0
            samplingHeight = 0
        }
        if (textureId != 0) {
            GLES20.glDeleteTextures(1, intArrayOf(textureId), 0)
            textureId = 0
        }
        if (programId != 0) {
            GLES20.glDeleteProgram(programId)
            programId = 0
        }
    }

    private fun createFloatBuffer(data: FloatArray): FloatBuffer {
        return ByteBuffer.allocateDirect(data.size * FLOAT_SIZE_BYTES)
            .order(ByteOrder.nativeOrder())
            .asFloatBuffer()
            .put(data)
            .also { buffer -> buffer.position(0) }
    }

    private fun updateFloatBuffer(data: FloatArray) {
        if (triangleVertices.capacity() < data.size) {
            triangleVertices = createFloatBuffer(data = data)
            return
        }
        triangleVertices.clear()
        triangleVertices.put(data)
        triangleVertices.position(0)
    }

    private fun checkGlError(op: String) {
        val error = GLES20.glGetError()
        if (error != GLES20.GL_NO_ERROR) {
            AlphaVideoLog.e(
                component = COMPONENT,
                message = "$op: glError $error"
            )
        }
    }

    private fun logScaleDiagnostics(
        viewWidth: Float,
        viewHeight: Float,
        videoWidth: Float,
        videoHeight: Float,
        scaleType: AlphaVideo.ScaleType,
        layout: AlphaVideo.Layout
    ) {
        val fitScale = min(a = viewWidth / videoWidth, b = viewHeight / videoHeight)
        val cropScale = max(a = viewWidth / videoWidth, b = viewHeight / videoHeight)
        val (displayWidth, displayHeight, upscale) = when (scaleType) {
            AlphaVideo.ScaleType.FitCenter,
            AlphaVideo.ScaleType.FitTop,
            AlphaVideo.ScaleType.FitBottom -> {
                Triple(
                    first = videoWidth * fitScale,
                    second = videoHeight * fitScale,
                    third = fitScale
                )
            }

            AlphaVideo.ScaleType.FitWidth -> {
                val widthScale = viewWidth / videoWidth
                val fittedHeight = videoHeight * widthScale
                Triple(
                    first = viewWidth,
                    second = min(a = fittedHeight, b = viewHeight),
                    third = widthScale
                )
            }

            AlphaVideo.ScaleType.CenterCrop -> {
                Triple(
                    first = viewWidth,
                    second = viewHeight,
                    third = cropScale
                )
            }
        }
        AlphaVideoLog.i(
            component = COMPONENT,
            message = "scaleDiagnostics rgb=${videoWidth.toInt()}x${videoHeight.toInt()} " +
                    "view=${viewWidth.toInt()}x${viewHeight.toInt()} " +
                    "display=${displayWidth.toInt()}x${displayHeight.toInt()} " +
                    "upscale=${formatScale(value = upscale)}x " +
                    "fitScale=${formatScale(value = fitScale)}x " +
                    "cropScale=${formatScale(value = cropScale)}x " +
                    "scaleType=$scaleType layout=$layout"
        )
    }

    private fun formatScale(value: Float): String {
        return String.format(Locale.US, "%.2f", value)
    }

}
