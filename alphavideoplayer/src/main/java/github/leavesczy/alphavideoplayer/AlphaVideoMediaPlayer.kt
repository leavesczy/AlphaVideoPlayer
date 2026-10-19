package github.leavesczy.alphavideoplayer

import android.view.Surface
import java.io.IOException
import java.util.concurrent.atomic.AtomicInteger

/**
 * Decoder SPI for [AlphaVideoPlayer] (player thread).
 * Source: absolute file path or `asset://` / `asset:///`.
 * [setSurface] never throws; returns `false` only when a non-null surface cannot be attached.
 * [Callback.onPrepared] only after a positive video size is known.
 * Gate listeners with [invalidateDecoderCallbacks] / [acceptDecoderCallbacks] /
 * [shouldDeliverCallback].
 */
abstract class AlphaVideoMediaPlayer {

    companion object {

        const val TIME_UNSET = -1L

        fun assetRelativePath(path: String): String {
            return path
                .removePrefix(prefix = "asset:///")
                .removePrefix(prefix = "asset://")
                .trimStart(chars = charArrayOf('/'))
        }

        fun requireLocalOrAssetPath(path: String) {
            when {
                path.startsWith(prefix = "asset://") -> {
                    if (assetRelativePath(path = path).isBlank()) {
                        error(message = "unsupported source: empty asset path")
                    }
                }

                path.startsWith(prefix = "file://") || !path.contains(other = "://") -> Unit

                else -> error(message = "unsupported source (only local file / asset): $path")
            }
        }

    }

    interface Callback {

        fun onPrepared()

        fun onFirstFrame()

        fun onCompleted()

        fun onError(errorCode: AlphaVideo.ErrorCode, message: String)

    }

    protected var playerCallback: Callback? = null

    private val callbackGeneration = AtomicInteger(0)

    private var acceptedCallbackGeneration = 0

    private var suppressCallbacks = false

    protected var hasNotifiedPrepared = false

    fun setCallback(callback: Callback?) {
        playerCallback = callback
    }

    protected fun invalidateDecoderCallbacks() {
        callbackGeneration.incrementAndGet()
        hasNotifiedPrepared = false
    }

    protected fun acceptDecoderCallbacks() {
        acceptedCallbackGeneration = callbackGeneration.get()
        hasNotifiedPrepared = false
    }

    protected fun decoderCallbackGeneration(): Int = callbackGeneration.get()

    protected fun shouldDeliverCallback(): Boolean {
        return !suppressCallbacks &&
                acceptedCallbackGeneration == callbackGeneration.get()
    }

    protected fun withSuppressedCallbacks(block: () -> Unit) {
        suppressCallbacks = true
        try {
            block()
        } finally {
            suppressCallbacks = false
        }
    }

    protected fun emitPreparedOnce() {
        if (!shouldDeliverCallback() || hasNotifiedPrepared) {
            return
        }
        hasNotifiedPrepared = true
        playerCallback?.onPrepared()
    }

    abstract fun init()

    abstract fun setLooping(looping: Boolean)

    /** `0f..1f`; retained across [reset]. */
    abstract fun setVolume(volume: Float)

    abstract fun setSurface(surface: Surface?): Boolean

    @Throws(IOException::class)
    abstract fun setSource(path: String)

    abstract fun prepare()

    @Throws(Exception::class)
    abstract fun getVideoSize(): AlphaVideo.VideoSize

    abstract fun getCurrentPosition(): Long

    abstract fun getDuration(): Long

    abstract fun start()

    abstract fun pause()

    abstract fun stop()

    abstract fun reset()

    abstract fun release()

}
