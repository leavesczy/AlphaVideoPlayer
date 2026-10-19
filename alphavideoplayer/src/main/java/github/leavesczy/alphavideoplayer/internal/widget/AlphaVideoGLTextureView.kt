package github.leavesczy.alphavideoplayer.internal.widget

import android.content.Context
import android.os.Looper
import android.util.AttributeSet
import android.view.Surface
import android.view.ViewGroup
import github.leavesczy.alphavideoplayer.AlphaVideo
import github.leavesczy.alphavideoplayer.AlphaVideoPlayer
import github.leavesczy.alphavideoplayer.internal.render.IAlphaVideoRender
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

internal class AlphaVideoGLTextureView(
    context: Context,
    attrs: AttributeSet? = null
) : AlphaVideoBaseGLTextureView(context = context, attrs = attrs), IAlphaVideoRenderView {

    companion object {

        private const val GL_CONTEXT_VERSION = 2

    }

    private var playerController: AlphaVideoPlayer? = null

    private var videoRenderer: IAlphaVideoRender? = null

    private val surfaceListener = object : IAlphaVideoRender.SurfaceListener {
        override fun onSurfacePrepared(surface: Surface) {
            surfaceCreated = true
            playerController?.surfacePrepared(surface = surface)
        }

        override fun onSurfaceDestroyed() {
            surfaceCreated = false
            playerController?.surfaceDestroyed()
        }

        override fun onRenderSetupFailed(errorCode: AlphaVideo.ErrorCode, message: String) {
            surfaceCreated = false
            playerController?.renderSetupFailed(errorCode = errorCode, message = message)
        }
    }

    @Volatile
    private var surfaceCreated = false

    private var scaleType = AlphaVideo.ScaleType.FitCenter

    private var layout = AlphaVideo.Layout.AlphaLeft

    private var contentWidth = 0f

    private var contentHeight = 0f

    private var lastMeasuredWidth = 0

    private var lastMeasuredHeight = 0

    init {
        setEGLContextClientVersion(version = GL_CONTEXT_VERSION)
        setEGLConfigChooser(
            red = 8,
            green = 8,
            blue = 8,
            alpha = 8,
            depth = 0,
            stencil = 0
        )
        preserveEGLContextOnPause = true
        isOpaque = false
    }

    override fun addParentView(parentView: ViewGroup) {
        if (parentView.indexOfChild(this) == -1) {
            (parent as? ViewGroup)?.removeView(this)
            parentView.addView(this)
        }
    }

    override fun removeParentView(parentView: ViewGroup) {
        parentView.removeView(this)
    }

    override fun setPlayer(player: AlphaVideoPlayer) {
        playerController = player
    }

    override fun setVideoRenderer(renderer: IAlphaVideoRender) {
        videoRenderer = renderer
        renderer.setSurfaceListener(surfaceListener = surfaceListener)
        setRenderer(renderer = renderer)
        renderMode = RENDER_MODE_WHEN_DIRTY
    }

    override fun setScaleType(scaleType: AlphaVideo.ScaleType) {
        this.scaleType = scaleType
        contentWidth = 0f
        contentHeight = 0f
        lastMeasuredWidth = 0
        lastMeasuredHeight = 0
        val renderer = videoRenderer ?: return
        val apply = Runnable {
            renderer.setScaleType(scaleType = scaleType)
        }
        if (!queueEvent(runnable = apply)) {
            apply.run()
        }
    }

    override fun getScaleType(): AlphaVideo.ScaleType = scaleType

    override fun setLayout(layout: AlphaVideo.Layout) {
        this.layout = layout
        contentWidth = 0f
        contentHeight = 0f
        lastMeasuredWidth = 0
        lastMeasuredHeight = 0
        val renderer = videoRenderer ?: return
        val apply = Runnable {
            renderer.setLayout(layout = layout)
        }
        if (!queueEvent(runnable = apply)) {
            apply.run()
        }
    }

    override fun getLayout(): AlphaVideo.Layout = layout

    override fun isSurfaceCreated(): Boolean = surfaceCreated

    override fun measureInternal(videoWidth: Float, videoHeight: Float) {
        val sizeChanged = videoWidth > 0 && videoHeight > 0 &&
                (videoWidth != contentWidth || videoHeight != contentHeight)
        if (videoWidth > 0 && videoHeight > 0) {
            contentWidth = videoWidth
            contentHeight = videoHeight
        }
        if (Looper.myLooper() == Looper.getMainLooper()) {
            applyMeasureInternal(force = sizeChanged)
        } else {
            post {
                applyMeasureInternal(force = sizeChanged)
            }
        }
    }

    private fun applyMeasureInternal(force: Boolean) {
        val width = measuredWidth
        val height = measuredHeight
        if (width <= 0 || height <= 0 || contentWidth <= 0 || contentHeight <= 0) {
            return
        }
        if (!force && width == lastMeasuredWidth && height == lastMeasuredHeight) {
            return
        }
        lastMeasuredWidth = width
        lastMeasuredHeight = height
        val renderer = videoRenderer ?: return
        val videoWidth = contentWidth
        val videoHeight = contentHeight
        val apply = Runnable {
            renderer.measureInternal(
                viewWidth = width.toFloat(),
                viewHeight = height.toFloat(),
                videoWidth = videoWidth,
                videoHeight = videoHeight
            )
        }
        if (!queueEvent(runnable = apply)) {
            apply.run()
        }
    }

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        super.onMeasure(widthMeasureSpec, heightMeasureSpec)
        measureInternal(videoWidth = contentWidth, videoHeight = contentHeight)
    }

    override fun onFirstFrame() {
        val renderer = videoRenderer ?: return
        val apply = Runnable {
            renderer.onFirstFrame()
        }
        if (!queueEvent(runnable = apply)) {
            apply.run()
        }
    }

    override fun onCompletion() {
        val renderer = videoRenderer ?: return
        renderer.clearCanDraw()
        val latch = CountDownLatch(1)
        val apply = Runnable {
            try {
                renderer.onCompletion()
            } finally {
                latch.countDown()
            }
        }
        if (!queueEvent(runnable = apply)) {
            apply.run()
            return
        }
        // The next decoder must not start while this runnable can still drop its frame signal.
        runCatching { latch.await(1L, TimeUnit.SECONDS) }
    }

    override fun onSurfaceLost() {
        videoRenderer?.clearCanDraw()
    }

    override fun fitDecoderBuffer(sourceWidth: Int, sourceHeight: Int): Boolean {
        return videoRenderer?.fitDecoderBuffer(
            sourceWidth = sourceWidth,
            sourceHeight = sourceHeight
        ) == true
    }

    override fun release() {
        playerController = null
        val latch = CountDownLatch(1)
        val cleanup = Runnable {
            try {
                videoRenderer?.onSurfaceDestroyed(gl = null)
            } finally {
                latch.countDown()
            }
        }
        try {
            if (!queueEvent(runnable = cleanup)) {
                cleanup.run()
                return
            }
            // On timeout the queued teardown stays on the GL thread.
            latch.await(1L, TimeUnit.SECONDS)
        } catch (_: Exception) {
            // The wait was interrupted. Tear down on this thread.
            runCatching { videoRenderer?.onSurfaceDestroyed(gl = null) }
        }
    }

}
