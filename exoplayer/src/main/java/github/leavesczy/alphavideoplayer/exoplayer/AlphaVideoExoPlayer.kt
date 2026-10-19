package github.leavesczy.alphavideoplayer.exoplayer

import android.annotation.SuppressLint
import android.content.Context
import android.net.Uri
import android.os.Handler
import android.os.Looper
import android.view.Surface
import androidx.annotation.OptIn
import androidx.media3.common.C
import androidx.media3.common.MediaItem
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.common.VideoSize
import androidx.media3.common.util.UnstableApi
import androidx.media3.datasource.DefaultDataSource
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.source.DefaultMediaSourceFactory
import github.leavesczy.alphavideoplayer.AlphaVideo
import github.leavesczy.alphavideoplayer.AlphaVideoLog
import github.leavesczy.alphavideoplayer.AlphaVideoMediaPlayer
import github.leavesczy.alphavideoplayer.AlphaVideoPlayer
import java.io.File

/**
 * [AlphaVideoPlayer] backed by Media3 [ExoPlayer] on the library player-thread [Looper].
 * Sources: local file / `asset://`. Host must provide `androidx.media3:media3-exoplayer`
 * (`compileOnly` in this module).
 */
class AlphaVideoExoPlayer(context: Context) : AlphaVideoPlayer(
    context = context,
    mediaPlayer = ExoMediaPlayer(context = context)
)

private class ExoMediaPlayer(context: Context) : AlphaVideoMediaPlayer() {

    companion object {

        private const val COMPONENT = "ExoMediaPlayer"

        private const val VIDEO_SIZE_TIMEOUT_MS = 3_000L
    }

    private val appContext = context.applicationContext

    private var exoPlayer: ExoPlayer? = null

    private var playerHandler: Handler? = null

    private var volume = 0f

    private var isPlayerReady = false

    /** [Callback.onFirstFrame] is held until [start] sets this. */
    private var playbackStarted = false

    private var pendingFirstFrame = false

    private var currVideoWidth = 0

    private var currVideoHeight = 0

    private val videoSizeTimeoutRunnable = Runnable {
        if (!shouldDeliverCallback() || hasNotifiedPrepared || !isPlayerReady) {
            return@Runnable
        }
        refreshVideoSizeFromPlayer()
        if (currVideoWidth > 0 && currVideoHeight > 0) {
            maybeNotifyPrepared()
            return@Runnable
        }
        AlphaVideoLog.e(
            component = COMPONENT,
            message = "video size timeout after STATE_READY (${VIDEO_SIZE_TIMEOUT_MS}ms)"
        )
        playerCallback?.onError(
            errorCode = AlphaVideo.ErrorCode.VideoSize,
            message = "ExoPlayer video size unavailable after READY"
        )
    }

    private val exoPlayerListener = object : Player.Listener {
        override fun onPlaybackStateChanged(playbackState: Int) {
            if (!shouldDeliverCallback()) {
                return
            }
            when (playbackState) {
                Player.STATE_READY -> {
                    isPlayerReady = true
                    AlphaVideoLog.i(
                        component = COMPONENT,
                        message = "STATE_READY hasSize=${currVideoWidth > 0 && currVideoHeight > 0} " +
                                "size=${currVideoWidth}x${currVideoHeight}"
                    )
                    maybeNotifyPrepared()
                    scheduleVideoSizeTimeoutIfNeeded()
                }

                Player.STATE_ENDED -> {
                    playerCallback?.onCompleted()
                }

                Player.STATE_IDLE -> {
                    isPlayerReady = false
                    cancelVideoSizeTimeout()
                }

                else -> Unit
            }
        }

        override fun onRenderedFirstFrame() {
            if (!shouldDeliverCallback()) {
                return
            }
            if (!playbackStarted) {
                AlphaVideoLog.d(
                    component = COMPONENT,
                    message = "onRenderedFirstFrame deferred until start"
                )
                pendingFirstFrame = true
                return
            }
            playerCallback?.onFirstFrame()
        }

        override fun onPlayerError(error: PlaybackException) {
            if (!shouldDeliverCallback()) {
                return
            }
            isPlayerReady = false
            playerCallback?.onError(
                errorCode = AlphaVideo.ErrorCode.Decoder,
                message = buildErrorDescription(error = error)
            )
        }

        override fun onVideoSizeChanged(videoSize: VideoSize) {
            if (videoSize.width > 0 && videoSize.height > 0) {
                currVideoWidth = videoSize.width
                currVideoHeight = videoSize.height
                if (shouldDeliverCallback()) {
                    maybeNotifyPrepared()
                }
            }
        }
    }

    @OptIn(UnstableApi::class)
    override fun init() {
        releasePlayerOnly()
        val looper = Looper.myLooper()
            ?: error(message = "ExoMediaPlayer.init must run on a Looper thread")
        playerHandler = Handler(looper)
        val mediaSourceFactory = DefaultMediaSourceFactory(
            DefaultDataSource.Factory(appContext)
        )
        val player = ExoPlayer.Builder(appContext)
            .setLooper(looper)
            .setMediaSourceFactory(mediaSourceFactory)
            .setWakeMode(C.WAKE_MODE_NONE)
            .build()
        player.addListener(exoPlayerListener)
        player.volume = volume
        player.playWhenReady = false
        exoPlayer = player
    }

    override fun setLooping(looping: Boolean) {
        exoPlayer?.repeatMode = if (looping) {
            Player.REPEAT_MODE_ONE
        } else {
            Player.REPEAT_MODE_OFF
        }
    }

    override fun setVolume(volume: Float) {
        this.volume = volume.coerceIn(minimumValue = 0f, maximumValue = 1f)
        exoPlayer?.volume = this.volume
    }

    override fun setSurface(surface: Surface?): Boolean {
        val player = exoPlayer
        if (player == null) {
            return surface == null
        }
        return runCatching {
            if (surface == null) {
                player.clearVideoSurface()
            } else {
                player.setVideoSurface(surface)
            }
        }.isSuccess || surface == null
    }

    override fun setSource(path: String) {
        AlphaVideoMediaPlayer.requireLocalOrAssetPath(path = path)
        cancelVideoSizeTimeout()
        hasNotifiedPrepared = false
        isPlayerReady = false
        playbackStarted = false
        pendingFirstFrame = false
        currVideoWidth = 0
        currVideoHeight = 0
        val player = requirePlayer()
        try {
            player.setMediaItem(
                MediaItem.fromUri(toMediaUri(path = path)),
                /* resetPosition= */ true
            )
        } catch (e: Exception) {
            throw IllegalStateException("ExoPlayer.setMediaItem failed: $path", e)
        }
    }

    override fun prepare() {
        acceptDecoderCallbacks()
        cancelVideoSizeTimeout()
        isPlayerReady = false
        playbackStarted = false
        pendingFirstFrame = false
        try {
            requirePlayer().prepare()
        } catch (e: Exception) {
            playerCallback?.onError(
                errorCode = AlphaVideo.ErrorCode.Prepare,
                message = "ExoPlayer.prepare failed: ${e.message ?: e}"
            )
        }
    }

    override fun getVideoSize(): AlphaVideo.VideoSize {
        refreshVideoSizeFromPlayer()
        if (currVideoWidth <= 0 || currVideoHeight <= 0) {
            throw IllegalStateException("ExoPlayer video size is not available yet")
        }
        return AlphaVideo.VideoSize(
            width = currVideoWidth,
            height = currVideoHeight
        )
    }

    override fun getCurrentPosition(): Long {
        val player = exoPlayer ?: return TIME_UNSET
        val position = player.currentPosition
        return if (position < 0) {
            TIME_UNSET
        } else {
            position
        }
    }

    override fun getDuration(): Long {
        val player = exoPlayer ?: return TIME_UNSET
        val duration = player.duration
        return if (duration == C.TIME_UNSET || duration < 0) {
            TIME_UNSET
        } else {
            duration
        }
    }

    override fun start() {
        val player = requirePlayer()
        playbackStarted = true
        player.play()
        if (pendingFirstFrame) {
            pendingFirstFrame = false
            if (shouldDeliverCallback()) {
                playerCallback?.onFirstFrame()
            }
        }
    }

    override fun pause() {
        try {
            exoPlayer?.pause()
        } catch (_: Exception) {
        }
    }

    override fun stop() {
        invalidateDecoderCallbacks()
        cancelVideoSizeTimeout()
        withSuppressedCallbacks {
            exoPlayer?.clearVideoSurface()
            exoPlayer?.stop()
            exoPlayer?.playWhenReady = false
        }
        isPlayerReady = false
        playbackStarted = false
        pendingFirstFrame = false
    }

    override fun reset() {
        invalidateDecoderCallbacks()
        cancelVideoSizeTimeout()
        isPlayerReady = false
        playbackStarted = false
        pendingFirstFrame = false
        currVideoWidth = 0
        currVideoHeight = 0
        val player = exoPlayer ?: return
        withSuppressedCallbacks {
            // Detach before the codec is released, or its callback thread dies while still bound.
            player.clearVideoSurface()
            player.stop()
            player.clearMediaItems()
            player.playWhenReady = false
        }
    }

    override fun release() {
        invalidateDecoderCallbacks()
        cancelVideoSizeTimeout()
        releasePlayerOnly()
        playerHandler = null
        isPlayerReady = false
        playbackStarted = false
        pendingFirstFrame = false
        currVideoWidth = 0
        currVideoHeight = 0
        playerCallback = null
    }

    private fun requirePlayer(): ExoPlayer {
        return exoPlayer ?: error(message = "ExoPlayer is not initialized")
    }

    private fun releasePlayerOnly() {
        val player = exoPlayer ?: return
        withSuppressedCallbacks {
            player.removeListener(exoPlayerListener)
            player.clearVideoSurface()
            player.release()
        }
        exoPlayer = null
    }

    /** [Callback.onPrepared] when [Player.STATE_READY] and a positive video size are both known. */
    private fun maybeNotifyPrepared() {
        if (!shouldDeliverCallback() || hasNotifiedPrepared || !isPlayerReady) {
            return
        }
        refreshVideoSizeFromPlayer()
        if (currVideoWidth <= 0 || currVideoHeight <= 0) {
            return
        }
        cancelVideoSizeTimeout()
        emitPreparedOnce()
    }

    private fun scheduleVideoSizeTimeoutIfNeeded() {
        if (hasNotifiedPrepared || (currVideoWidth > 0 && currVideoHeight > 0)) {
            return
        }
        val handler = playerHandler ?: return
        handler.removeCallbacks(videoSizeTimeoutRunnable)
        handler.postDelayed(videoSizeTimeoutRunnable, VIDEO_SIZE_TIMEOUT_MS)
    }

    private fun cancelVideoSizeTimeout() {
        playerHandler?.removeCallbacks(videoSizeTimeoutRunnable)
    }

    private fun refreshVideoSizeFromPlayer() {
        if (currVideoWidth > 0 && currVideoHeight > 0) {
            return
        }
        val size = exoPlayer?.videoSize ?: return
        if (size.width > 0 && size.height > 0) {
            currVideoWidth = size.width
            currVideoHeight = size.height
        }
    }

    @SuppressLint("UseKtx")
    private fun toMediaUri(path: String): Uri {
        return when {
            path.startsWith(prefix = "asset://") ||
                    path.startsWith(prefix = "file://") -> {
                Uri.parse(path)
            }

            else -> {
                Uri.parse(File(path).toURI().toString())
            }
        }
    }

    private fun buildErrorDescription(error: PlaybackException): String {
        val message = error.message.orEmpty()
        return if (message.isEmpty()) {
            "ExoPlayer error ${error.errorCodeName} (${error.errorCode})"
        } else {
            "ExoPlayer error ${error.errorCodeName} (${error.errorCode}): $message"
        }
    }

}
