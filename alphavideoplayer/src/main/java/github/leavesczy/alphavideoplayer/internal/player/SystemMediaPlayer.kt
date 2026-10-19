package github.leavesczy.alphavideoplayer.internal.player

import android.content.Context
import android.content.res.AssetFileDescriptor
import android.media.MediaMetadataRetriever
import android.media.MediaPlayer
import android.view.Surface
import github.leavesczy.alphavideoplayer.AlphaVideo
import github.leavesczy.alphavideoplayer.AlphaVideoLog
import github.leavesczy.alphavideoplayer.AlphaVideoMediaPlayer
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors

internal class SystemMediaPlayer(context: Context) : AlphaVideoMediaPlayer() {

    private val appContext = context.applicationContext

    private var mediaPlayer: MediaPlayer? = null

    private var pendingPrepared = false

    private var volume = 0f

    private var sourcePath: String = ""

    private var assetFd: AssetFileDescriptor? = null

    private var cachedVideoSize: AlphaVideo.VideoSize? = null

    private var metadataExecutor: ExecutorService = newMetadataExecutor()

    override fun init() {
        releasePlayerOnly()
        ensureMetadataExecutor()
        mediaPlayer = MediaPlayer().apply {
            setVolume(volume, volume)
            setOnPreparedListener {
                if (!shouldDeliverCallback()) {
                    return@setOnPreparedListener
                }
                tryNotifyPrepared()
            }
            setOnInfoListener { _, what, _ ->
                if (shouldDeliverCallback() &&
                    what == MediaPlayer.MEDIA_INFO_VIDEO_RENDERING_START
                ) {
                    playerCallback?.onFirstFrame()
                }
                false
            }
            setOnCompletionListener {
                if (shouldDeliverCallback()) {
                    playerCallback?.onCompleted()
                }
            }
            setOnErrorListener { _, what, extra ->
                if (shouldDeliverCallback()) {
                    playerCallback?.onError(
                        errorCode = AlphaVideo.ErrorCode.Decoder,
                        message = "MediaPlayer error what=$what extra=$extra"
                    )
                }
                // true consumes the error, so onCompletion does not also run.
                true
            }
            setOnVideoSizeChangedListener { _, width, height ->
                if (width > 0 && height > 0) {
                    cachedVideoSize = AlphaVideo.VideoSize(
                        width = width,
                        height = height
                    )
                    if (pendingPrepared) {
                        tryNotifyPrepared()
                    }
                }
            }
        }
    }

    override fun setLooping(looping: Boolean) {
        try {
            mediaPlayer?.isLooping = looping
        } catch (_: IllegalStateException) {
        }
    }

    override fun setVolume(volume: Float) {
        this.volume = volume.coerceIn(minimumValue = 0f, maximumValue = 1f)
        try {
            mediaPlayer?.setVolume(this.volume, this.volume)
        } catch (_: IllegalStateException) {
        }
    }

    override fun setSurface(surface: Surface?): Boolean {
        val player = mediaPlayer
        if (player == null) {
            return surface == null
        }
        return try {
            player.setSurface(surface)
            true
        } catch (e: IllegalStateException) {
            AlphaVideoLog.w(
                component = "SystemMediaPlayer",
                message = "setSurface failed, surface=$surface",
                throwable = e
            )
            surface == null
        } catch (e: IllegalArgumentException) {
            AlphaVideoLog.w(
                component = "SystemMediaPlayer",
                message = "setSurface failed, surface=$surface",
                throwable = e
            )
            surface == null
        }
    }

    override fun setSource(path: String) {
        AlphaVideoMediaPlayer.requireLocalOrAssetPath(path = path)
        sourcePath = path
        cachedVideoSize = null
        hasNotifiedPrepared = false
        pendingPrepared = false
        closeAssetFd()
        val player = requirePlayer()
        try {
            if (path.startsWith(prefix = "asset://")) {
                val assetPath = AlphaVideoMediaPlayer.assetRelativePath(path = path)
                val fd = appContext.assets.openFd(assetPath)
                assetFd = fd
                player.setDataSource(fd.fileDescriptor, fd.startOffset, fd.length)
            } else {
                player.setDataSource(path)
            }
        } catch (e: IllegalStateException) {
            closeAssetFd()
            throw IllegalStateException("MediaPlayer.setDataSource failed: $path", e)
        } catch (e: IllegalArgumentException) {
            closeAssetFd()
            throw IllegalArgumentException("MediaPlayer.setDataSource failed: $path", e)
        }
    }

    override fun prepare() {
        acceptDecoderCallbacks()
        pendingPrepared = false
        try {
            requirePlayer().prepareAsync()
        } catch (e: IllegalStateException) {
            playerCallback?.onError(
                errorCode = AlphaVideo.ErrorCode.Prepare,
                message = "MediaPlayer.prepareAsync failed: ${e.message ?: e}"
            )
        }
    }

    override fun getVideoSize(): AlphaVideo.VideoSize {
        cachedVideoSize?.let { size -> return size }
        val player = requirePlayer()
        val width = player.videoWidth
        val height = player.videoHeight
        if (width > 0 && height > 0) {
            return AlphaVideo.VideoSize(
                width = width,
                height = height
            ).also { size -> cachedVideoSize = size }
        }
        throw IllegalStateException("MediaPlayer video size is not available yet")
    }

    override fun getCurrentPosition(): Long {
        return try {
            mediaPlayer?.currentPosition?.toLong() ?: TIME_UNSET
        } catch (_: IllegalStateException) {
            TIME_UNSET
        }
    }

    override fun getDuration(): Long {
        return try {
            val duration = mediaPlayer?.duration ?: return TIME_UNSET
            if (duration < 0) {
                TIME_UNSET
            } else {
                duration.toLong()
            }
        } catch (_: IllegalStateException) {
            TIME_UNSET
        }
    }

    override fun start() {
        try {
            requirePlayer().start()
        } catch (e: IllegalStateException) {
            throw IllegalStateException("MediaPlayer.start failed", e)
        }
    }

    override fun pause() {
        try {
            mediaPlayer?.pause()
        } catch (_: IllegalStateException) {
        }
    }

    override fun stop() {
        invalidateDecoderCallbacks()
        withSuppressedCallbacks {
            try {
                mediaPlayer?.setSurface(null)
            } catch (_: IllegalStateException) {
            }
            try {
                mediaPlayer?.stop()
            } catch (_: IllegalStateException) {
            }
        }
        pendingPrepared = false
    }

    override fun reset() {
        invalidateDecoderCallbacks()
        withSuppressedCallbacks {
            try {
                mediaPlayer?.setSurface(null)
            } catch (_: IllegalStateException) {
            }
            try {
                mediaPlayer?.reset()
            } catch (_: IllegalStateException) {
            }
        }
        closeAssetFd()
        sourcePath = ""
        cachedVideoSize = null
        pendingPrepared = false
        mediaPlayer?.setVolume(volume, volume)
    }

    override fun release() {
        invalidateDecoderCallbacks()
        releasePlayerOnly()
        sourcePath = ""
        cachedVideoSize = null
        pendingPrepared = false
        playerCallback = null
        metadataExecutor.shutdownNow()
    }

    private fun requirePlayer(): MediaPlayer {
        return mediaPlayer ?: error(message = "MediaPlayer is not initialized")
    }

    private fun releasePlayerOnly() {
        closeAssetFd()
        withSuppressedCallbacks {
            mediaPlayer?.release()
        }
        mediaPlayer = null
    }

    /** Emits [Callback.onPrepared] when size is known; else metadata fallback or [Callback.onError]. */
    @Synchronized
    private fun tryNotifyPrepared() {
        if (!shouldDeliverCallback() || hasNotifiedPrepared) {
            return
        }
        val size = resolveVideoSizeOrNull()
        if (size != null) {
            cachedVideoSize = size
            pendingPrepared = false
            emitPreparedOnce()
            return
        }
        if (pendingPrepared) {
            return
        }
        pendingPrepared = true
        val generation = decoderCallbackGeneration()
        val path = sourcePath
        if (path.isBlank()) {
            deliverSizeUnavailable(
                generation = generation,
                message = "video size unavailable: empty source path"
            )
            return
        }
        metadataExecutor.execute {
            val meta = runCatching { readVideoSizeFromMetadata(path = path) }.getOrNull()
            if (meta == null) {
                deliverSizeUnavailable(
                    generation = generation,
                    message = "video size unavailable: metadata read failed"
                )
                return@execute
            }
            deliverPreparedFromMetadata(generation = generation, size = meta)
        }
    }

    @Synchronized
    private fun deliverPreparedFromMetadata(generation: Int, size: AlphaVideo.VideoSize) {
        if (generation != decoderCallbackGeneration() ||
            hasNotifiedPrepared ||
            !pendingPrepared ||
            !shouldDeliverCallback()
        ) {
            return
        }
        cachedVideoSize = size
        pendingPrepared = false
        emitPreparedOnce()
    }

    @Synchronized
    private fun deliverSizeUnavailable(generation: Int, message: String) {
        if (generation != decoderCallbackGeneration() ||
            hasNotifiedPrepared ||
            !shouldDeliverCallback()
        ) {
            return
        }
        pendingPrepared = false
        hasNotifiedPrepared = true
        AlphaVideoLog.e(
            component = "SystemMediaPlayer",
            message = message
        )
        playerCallback?.onError(
            errorCode = AlphaVideo.ErrorCode.VideoSize,
            message = message
        )
    }

    private fun resolveVideoSizeOrNull(): AlphaVideo.VideoSize? {
        cachedVideoSize?.let { size -> return size }
        val width = mediaPlayer?.videoWidth ?: 0
        val height = mediaPlayer?.videoHeight ?: 0
        if (width > 0 && height > 0) {
            return AlphaVideo.VideoSize(
                width = width,
                height = height
            )
        }
        return null
    }

    private fun readVideoSizeFromMetadata(path: String): AlphaVideo.VideoSize {
        val retriever = MediaMetadataRetriever()
        try {
            setRetrieverDataSource(retriever = retriever, path = path)
            val widthStr = retriever.extractMetadata(
                MediaMetadataRetriever.METADATA_KEY_VIDEO_WIDTH
            )
            val heightStr = retriever.extractMetadata(
                MediaMetadataRetriever.METADATA_KEY_VIDEO_HEIGHT
            )
            if (widthStr.isNullOrBlank() || heightStr.isNullOrBlank()) {
                throw IllegalStateException("Failed to read video metadata")
            }
            return AlphaVideo.VideoSize(
                width = widthStr.toInt(),
                height = heightStr.toInt()
            )
        } finally {
            retriever.release()
        }
    }

    private fun closeAssetFd() {
        runCatching { assetFd?.close() }
        assetFd = null
    }

    private fun setRetrieverDataSource(retriever: MediaMetadataRetriever, path: String) {
        if (path.startsWith(prefix = "asset://")) {
            val assetPath = AlphaVideoMediaPlayer.assetRelativePath(path = path)
            appContext.assets.openFd(assetPath).use { fd ->
                retriever.setDataSource(fd.fileDescriptor, fd.startOffset, fd.length)
            }
        } else {
            retriever.setDataSource(path)
        }
    }

    private fun ensureMetadataExecutor() {
        if (metadataExecutor.isShutdown) {
            metadataExecutor = newMetadataExecutor()
        }
    }

    private fun newMetadataExecutor(): ExecutorService {
        return Executors.newSingleThreadExecutor { runnable ->
            Thread(runnable, "AlphaVideo-Meta").also { thread ->
                thread.isDaemon = true
                thread.setUncaughtExceptionHandler { _, throwable ->
                    // A size-read failure uses VideoSize. An exception that escapes this thread uses Internal.
                    AlphaVideoLog.e(
                        component = "SystemMediaPlayer",
                        message = "metadata thread failure",
                        throwable = throwable
                    )
                    runCatching {
                        playerCallback?.onError(
                            errorCode = AlphaVideo.ErrorCode.Internal,
                            message = "metadata failure: ${throwable.message ?: throwable.javaClass.simpleName}"
                        )
                    }
                }
            }
        }
    }

}
