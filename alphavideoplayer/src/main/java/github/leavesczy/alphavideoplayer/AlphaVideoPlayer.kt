package github.leavesczy.alphavideoplayer

import android.content.Context
import android.media.MediaMetadataRetriever
import android.os.Handler
import android.os.HandlerThread
import android.os.Looper
import android.os.Process
import android.view.Surface
import android.view.View
import android.view.ViewGroup
import github.leavesczy.alphavideoplayer.internal.render.AlphaVideoRenderer
import github.leavesczy.alphavideoplayer.internal.widget.AlphaVideoGLTextureView
import github.leavesczy.alphavideoplayer.internal.widget.IAlphaVideoRenderView
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Orchestrates half-frame alpha playback on a dedicated player thread via [AlphaVideoMediaPlayer]
 * and OpenGL. Attach with [AlphaVideoView.setPlayer].
 * Backends: [AlphaVideoSystemPlayer],
 * [github.leavesczy.alphavideoplayer.exoplayer.AlphaVideoExoPlayer].
 *
 * Public methods may be called from any thread; [AlphaVideo.Listener] runs on the main thread.
 * No playlist. After [release], further commands are ignored.
 * [AlphaVideoView.setPlayer]`(null)` only detaches rendering — call [release] to free resources.
 * Exceptions on the player, GL, and metadata threads are reported with [AlphaVideo.Listener.onError].
 */
abstract class AlphaVideoPlayer protected constructor(
    context: Context,
    private val mediaPlayer: AlphaVideoMediaPlayer
) {

    companion object {

        private const val COMPONENT = "AlphaVideoPlayer"

        private const val NEAR_EOS_SLACK_MS = 50L
    }

    private val appContext = context.applicationContext

    private var mediaItem: AlphaVideo.MediaItem? = null

    /** Item deferred until the decoder OES surface exists. */
    private var pendingMediaItem: AlphaVideo.MediaItem? = null

    private var playerState = PlayerState.NOT_PREPARED

    private var playWhenReady = false

    private val released = AtomicBoolean(false)

    /** Decoder surface must be cleared and set again so a new buffer size takes effect. */
    private var rebindDecoderSurface = false

    /**
     * Session id for orchestrator commands. Stale Prepared / FirstFrame / Completed / Error are
     * ignored. Decoder listeners are gated separately in [AlphaVideoMediaPlayer].
     */
    @Volatile
    private var playbackGeneration = 0

    private var awaitingPrepared = false

    private var surfaceHold: SurfaceHold = SurfaceHold.None

    @Volatile
    private var listener: AlphaVideo.Listener? = null

    private lateinit var renderView: IAlphaVideoRenderView

    private var currentSurface: Surface? = null

    private val playThread = HandlerThread(
        "AlphaVideoPlayer",
        Process.THREAD_PRIORITY_DISPLAY
    ).also { thread ->
        thread.setUncaughtExceptionHandler { _, throwable ->
            reportUncaughtPlayerFailure(throwable = throwable)
        }
        thread.start()
    }

    private val playHandler = Handler(playThread.looper)

    private val mainHandler = Handler(Looper.getMainLooper())

    private val mediaPlayerCallback = object : AlphaVideoMediaPlayer.Callback {

        override fun onPrepared() {
            offer(command = PlayCommand.Prepared(generation = playbackGeneration))
        }

        override fun onFirstFrame() {
            if (released.get()) {
                return
            }
            offer(command = PlayCommand.FirstFrame(generation = playbackGeneration))
        }

        override fun onCompleted() {
            offer(command = PlayCommand.Completed(generation = playbackGeneration))
        }

        override fun onError(errorCode: AlphaVideo.ErrorCode, message: String) {
            offer(
                command = PlayCommand.Error(
                    errorCode = errorCode,
                    message = message,
                    generation = playbackGeneration
                )
            )
        }
    }

    init {
        initRenderView()
        offer(command = PlayCommand.Init)
    }

    fun setListener(listener: AlphaVideo.Listener?) {
        this.listener = listener
    }

    /** Linear gain in `0f..1f`; default `0f`. */
    fun setVolume(volume: Float) {
        offer(
            command = PlayCommand.SetVolume(
                volume = volume.coerceIn(
                    minimumValue = 0f,
                    maximumValue = 1f
                )
            )
        )
    }

    /** Replaces the item and resets the session; does not prepare or emit [AlphaVideo.Listener.onCompleted]. */
    fun setMediaItem(mediaItem: AlphaVideo.MediaItem) {
        AlphaVideoLog.i(
            component = COMPONENT,
            message = "api setMediaItem source=${mediaItem.source} " +
                    "scaleType=${mediaItem.scaleType} layout=${mediaItem.layout} " +
                    "looping=${mediaItem.looping}"
        )
        offer(command = PlayCommand.SetMediaItem(mediaItem = mediaItem))
    }

    /** Clears GL sampling/geometry, then prepares (or defers until the decoder surface is ready). */
    fun prepare() {
        AlphaVideoLog.i(component = COMPONENT, message = "api prepare")
        offer(command = PlayCommand.Prepare)
    }

    /**
     * Shows the render view, then starts, resumes, or prepare-then-starts.
     * Without a decoder surface, keeps play intent until the surface is ready.
     */
    fun play() {
        AlphaVideoLog.i(component = COMPONENT, message = "api play")
        postToMain {
            showRenderView()
        }
        offer(command = PlayCommand.Play)
    }

    /** Clears play intent; does not emit [AlphaVideo.Listener.onCompleted]. */
    fun pause() {
        AlphaVideoLog.i(component = COMPONENT, message = "api pause")
        offer(command = PlayCommand.Pause)
    }

    /** [AlphaVideo.Listener.onCompleted] only if playback had started (playing or paused). */
    fun stop() {
        AlphaVideoLog.i(component = COMPONENT, message = "api stop")
        offer(command = PlayCommand.Stop)
    }

    /**
     * Frees decoder and GL resources. Idempotent.
     * When the player thread has already exited, cleanup runs on the caller thread.
     */
    fun release() {
        if (!released.compareAndSet(false, true)) {
            AlphaVideoLog.d(
                component = COMPONENT,
                message = "api release ignored (already released)"
            )
            return
        }
        AlphaVideoLog.i(component = COMPONENT, message = "api release")
        if (!playThread.isAlive) {
            runCatching { mediaPlayer.release() }
            runCatching { renderView.release() }
            listener = null
            return
        }
        offer(command = PlayCommand.Destroy)
    }

    internal fun attachRenderView(parentView: ViewGroup) {
        renderView.addParentView(parentView = parentView)
    }

    internal fun detachRenderView(parentView: ViewGroup) {
        renderView.removeParentView(parentView = parentView)
    }

    internal fun surfacePrepared(surface: Surface) {
        offer(command = PlayCommand.SurfacePrepared(surface = surface))
    }

    internal fun surfaceDestroyed() {
        offer(command = PlayCommand.SurfaceDestroyed)
    }

    internal fun renderSetupFailed(errorCode: AlphaVideo.ErrorCode, message: String) {
        offer(
            command = PlayCommand.Error(
                errorCode = errorCode,
                message = message,
                generation = playbackGeneration
            )
        )
    }

    private fun initRenderView() {
        renderView = AlphaVideoGLTextureView(
            context = appContext,
            attrs = null
        )
        renderView.setLayoutParams(
            params = ViewGroup.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.MATCH_PARENT
            )
        )
        renderView.setPlayer(player = this)
        renderView.setVideoRenderer(renderer = AlphaVideoRenderer(renderView = renderView))
    }

    private fun initMediaPlayer() {
        mediaPlayer.setCallback(callback = mediaPlayerCallback)
        mediaPlayer.init()
        mediaPlayer.setLooping(looping = false)
    }

    private fun showRenderView() {
        renderView.setVisibility(visibility = View.VISIBLE)
        renderView.bringToFront()
    }

    private fun offer(command: PlayCommand) {
        if (released.get() && command !is PlayCommand.Destroy) {
            return
        }
        playHandler.post {
            try {
                handleCommand(command = command)
            } catch (e: Exception) {
                logError(throwable = e, message = "player command failure")
                // Release is already tearing the session down; another callback would race it.
                if (released.get() || command is PlayCommand.Destroy) {
                    return@post
                }
                val message = "internal failure: ${e.message ?: e}"
                try {
                    failPlayback(errorCode = AlphaVideo.ErrorCode.Internal, message = message)
                } catch (ignored: Exception) {
                    // failPlayback itself failed; still deliver the original error.
                    emitError(errorCode = AlphaVideo.ErrorCode.Internal, message = message)
                }
            }
        }
    }

    private fun reportUncaughtPlayerFailure(throwable: Throwable) {
        logError(throwable = throwable, message = "player thread crashed")
        // The player looper is dying, so decoder and GL stay up until release().
        if (released.get()) {
            return
        }
        val message = "internal failure: ${throwable.message ?: throwable.javaClass.simpleName}"
        mainHandler.post {
            if (released.get()) {
                return@post
            }
            listener?.onError(errorCode = AlphaVideo.ErrorCode.Internal, message = message)
        }
    }

    private fun handleCommand(command: PlayCommand) {
        when (command) {
            PlayCommand.Init -> {
                try {
                    initMediaPlayer()
                } catch (e: Exception) {
                    logError(throwable = e, message = "init media player failure")
                    failPlayback(
                        errorCode = AlphaVideo.ErrorCode.Init,
                        message = "init media player failure: ${e.message ?: e}"
                    )
                }
            }

            is PlayCommand.SetVolume -> {
                mediaPlayer.setVolume(volume = command.volume)
            }

            is PlayCommand.SetMediaItem -> {
                mediaItem = command.mediaItem
                playWhenReady = false
                if (playerState != PlayerState.RELEASE) {
                    resetPlaybackSession(
                        nextState = PlayerState.NOT_PREPARED,
                        decoderAction = DecoderAction.StopThenReset,
                        clearPlayWhenReady = false
                    )
                } else {
                    pendingMediaItem = null
                    clearSurfaceHold()
                }
            }

            PlayCommand.Prepare -> {
                val item = mediaItem ?: return
                runSetSource {
                    applyMediaItem(mediaItem = item, startWhenReady = false)
                }
            }

            PlayCommand.Play -> {
                playWhenReady = true
                when (playerState) {
                    PlayerState.PAUSED,
                    PlayerState.PREPARED -> {
                        if (currentSurface != null) {
                            startPlay()
                        }
                    }

                    PlayerState.STARTED -> Unit
                    else -> {
                        val item = mediaItem ?: return
                        runSetSource {
                            applyMediaItem(mediaItem = item, startWhenReady = true)
                        }
                    }
                }
            }

            is PlayCommand.SurfacePrepared -> onDecoderSurfacePrepared(surface = command.surface)

            PlayCommand.SurfaceDestroyed -> onDecoderSurfaceDestroyed()

            is PlayCommand.Prepared -> {
                if (command.generation != playbackGeneration ||
                    playerState != PlayerState.NOT_PREPARED ||
                    !awaitingPrepared
                ) {
                    AlphaVideoLog.d(
                        component = COMPONENT,
                        message = "Prepared dropped gen=${command.generation}/$playbackGeneration " +
                                "state=$playerState awaiting=$awaitingPrepared"
                    )
                    return
                }
                awaitingPrepared = false
                try {
                    parseVideoSize()
                    playerState = PlayerState.PREPARED
                    AlphaVideoLog.i(
                        component = COMPONENT,
                        message = "state PREPARED gen=$playbackGeneration playWhenReady=$playWhenReady"
                    )
                    if (playWhenReady) {
                        startPlay()
                    }
                } catch (e: Exception) {
                    logError(throwable = e, message = "video size failure")
                    failPlayback(
                        errorCode = AlphaVideo.ErrorCode.VideoSize,
                        message = e.message ?: "invalid video size"
                    )
                }
            }

            is PlayCommand.FirstFrame -> {
                if (command.generation != playbackGeneration) {
                    AlphaVideoLog.d(
                        component = COMPONENT,
                        message = "FirstFrame dropped gen=${command.generation}/$playbackGeneration"
                    )
                    return
                }
                when (playerState) {
                    PlayerState.STARTED,
                    PlayerState.PAUSED -> {
                        AlphaVideoLog.d(
                            component = COMPONENT,
                            message = "firstFrame accepted state=$playerState gen=$playbackGeneration"
                        )
                        renderView.onFirstFrame()
                    }

                    else -> {
                        AlphaVideoLog.d(
                            component = COMPONENT,
                            message = "FirstFrame ignored state=$playerState gen=$playbackGeneration"
                        )
                    }
                }
            }

            PlayCommand.Pause -> {
                playWhenReady = false
                dropSurfaceResumeIntent()
                if (playerState == PlayerState.STARTED) {
                    runCatching { mediaPlayer.pause() }
                    playerState = PlayerState.PAUSED
                    AlphaVideoLog.i(component = COMPONENT, message = "state PAUSED")
                }
            }

            PlayCommand.Stop -> {
                playWhenReady = false
                stopInternal(emitCompleted = true)
            }

            is PlayCommand.Completed -> {
                if (command.generation != playbackGeneration) {
                    AlphaVideoLog.d(
                        component = COMPONENT,
                        message = "Completed dropped gen=${command.generation}/$playbackGeneration"
                    )
                    return
                }
                if (playerState != PlayerState.STARTED &&
                    playerState != PlayerState.PAUSED
                ) {
                    AlphaVideoLog.d(
                        component = COMPONENT,
                        message = "Completed ignored state=$playerState gen=$playbackGeneration"
                    )
                    return
                }
                resetPlaybackSession(
                    nextState = PlayerState.ENDED,
                    decoderAction = DecoderAction.Reset,
                    clearPlayWhenReady = true
                )
                emitCompleted()
            }

            is PlayCommand.Error -> {
                if (command.generation != playbackGeneration) {
                    AlphaVideoLog.d(
                        component = COMPONENT,
                        message = "Error dropped gen=${command.generation}/$playbackGeneration " +
                                "msg=${command.message}"
                    )
                    return
                }
                failPlayback(
                    errorCode = command.errorCode,
                    message = command.message
                )
            }

            PlayCommand.Destroy -> destroyInternal()
        }
    }

    private fun applyMediaItem(mediaItem: AlphaVideo.MediaItem, startWhenReady: Boolean) {
        if (startWhenReady) {
            playWhenReady = true
        }
        invalidatePlayback()
        pendingMediaItem = null
        clearSurfaceHold()
        runCatching { mediaPlayer.reset() }
        renderView.onCompletion()
        playerState = PlayerState.NOT_PREPARED
        bindMediaItemOutput(mediaItem = mediaItem, requireSurface = true)
    }

    private fun handlePendingMediaItem(): Boolean {
        val item = pendingMediaItem ?: return false
        pendingMediaItem = null
        AlphaVideoLog.i(
            component = COMPONENT,
            message = "pendingMediaItem applied source=${item.source}"
        )
        runSetSource {
            bindMediaItemOutput(mediaItem = item, requireSurface = false)
        }
        return true
    }

    private fun bindMediaItemOutput(mediaItem: AlphaVideo.MediaItem, requireSurface: Boolean) {
        val sourcePath = resolveSourcePath(source = mediaItem.source) ?: return
        renderView.setScaleType(scaleType = mediaItem.scaleType)
        renderView.setLayout(layout = mediaItem.layout)
        mediaPlayer.setLooping(looping = mediaItem.looping)
        if (!applyTrackLimits(path = sourcePath)) {
            return
        }
        if (requireSurface && !renderView.isSurfaceCreated()) {
            pendingMediaItem = mediaItem
            AlphaVideoLog.i(
                component = COMPONENT,
                message = "pendingMediaItem deferred (no decoder surface) source=${mediaItem.source}"
            )
            return
        }
        if (rebindDecoderSurface) {
            mediaPlayer.setSurface(surface = null)
            rebindDecoderSurface = false
        }
        if (!bindDecoderSurface(surface = currentSurface)) {
            return
        }
        mediaPlayer.setSource(path = sourcePath)
        prepareMediaPlayer()
    }

    /**
     * Rejects 90°/270° rotation and fits the decoder buffer to the GPU texture limit
     * before the producer connects. A failed metadata read does not fail playback.
     * Returns false when [failPlayback] has already run.
     */
    private fun applyTrackLimits(path: String): Boolean {
        rebindDecoderSurface = false
        val track = readVideoTrack(path = path) ?: return true
        val rotation = normalizedRotation(degrees = track.rotationDegrees)
        if (rotation == 90 || rotation == 270) {
            failPlayback(
                errorCode = AlphaVideo.ErrorCode.UnsupportedRotation,
                message = "video rotation is $rotation degrees; export with rotation baked into the frames"
            )
            return false
        }
        if (track.width > 0 && track.height > 0) {
            rebindDecoderSurface = renderView.fitDecoderBuffer(
                sourceWidth = track.width,
                sourceHeight = track.height
            )
        }
        return true
    }

    private fun readVideoTrack(path: String): VideoTrack? {
        val retriever = MediaMetadataRetriever()
        try {
            if (path.startsWith(prefix = "asset://")) {
                val assetPath = AlphaVideoMediaPlayer.assetRelativePath(path = path)
                appContext.assets.openFd(assetPath).use { fd ->
                    retriever.setDataSource(fd.fileDescriptor, fd.startOffset, fd.length)
                }
            } else {
                retriever.setDataSource(path)
            }
            return VideoTrack(
                width = metadataInt(
                    retriever = retriever,
                    key = MediaMetadataRetriever.METADATA_KEY_VIDEO_WIDTH
                ),
                height = metadataInt(
                    retriever = retriever,
                    key = MediaMetadataRetriever.METADATA_KEY_VIDEO_HEIGHT
                ),
                rotationDegrees = metadataInt(
                    retriever = retriever,
                    key = MediaMetadataRetriever.METADATA_KEY_VIDEO_ROTATION
                )
            )
        } catch (e: Exception) {
            AlphaVideoLog.w(
                component = COMPONENT,
                message = "video track probe failed: $path",
                throwable = e
            )
            return null
        } finally {
            runCatching { retriever.release() }
        }
    }

    private fun metadataInt(retriever: MediaMetadataRetriever, key: Int): Int {
        val raw = retriever.extractMetadata(key) ?: return 0
        return raw.toIntOrNull() ?: raw.toFloatOrNull()?.toInt() ?: 0
    }

    private fun normalizedRotation(degrees: Int): Int {
        val wrapped = degrees % 360
        return if (wrapped < 0) wrapped + 360 else wrapped
    }

    /** Absolute file path or `asset:///<path>`; `null` if [failPlayback] was already invoked. */
    private fun resolveSourcePath(source: AlphaVideo.Source): String? {
        return when (source) {
            is AlphaVideo.Source.File -> {
                val videoFile = source.file
                if (!videoFile.exists()) {
                    failPlayback(
                        errorCode = AlphaVideo.ErrorCode.FileNotFound,
                        message = "file does not exist: ${videoFile.absolutePath}"
                    )
                    return null
                }
                videoFile.absolutePath
            }

            is AlphaVideo.Source.Asset -> {
                val assetPath = source.path
                if (assetPath.isBlank()) {
                    failPlayback(
                        errorCode = AlphaVideo.ErrorCode.AssetNotFound,
                        message = "asset path is empty"
                    )
                    return null
                }
                try {
                    appContext.assets.open(assetPath).close()
                } catch (e: Exception) {
                    failPlayback(
                        errorCode = AlphaVideo.ErrorCode.AssetNotFound,
                        message = "asset does not exist: $assetPath (${e.message ?: e})"
                    )
                    return null
                }
                "asset:///$assetPath"
            }
        }
    }

    private fun prepareMediaPlayer() {
        if (playerState != PlayerState.NOT_PREPARED) {
            return
        }
        awaitingPrepared = true
        mediaPlayer.prepare()
    }

    /** False when a non-null surface could not be attached (after [failPlayback]). */
    private fun bindDecoderSurface(surface: Surface?): Boolean {
        val ok = mediaPlayer.setSurface(surface = surface)
        if (!ok && surface != null) {
            failPlayback(
                errorCode = AlphaVideo.ErrorCode.Surface,
                message = "setSurface failure"
            )
            return false
        }
        return true
    }

    private fun startPlay() {
        if (currentSurface == null) {
            return
        }
        when (playerState) {
            PlayerState.PREPARED -> {
                try {
                    mediaPlayer.start()
                    playerState = PlayerState.STARTED
                    AlphaVideoLog.i(
                        component = COMPONENT,
                        message = "state STARTED gen=$playbackGeneration"
                    )
                    rearmFirstFrameIfHeld()
                    val callback = listener
                    postToMain {
                        AlphaVideoLog.i(component = COMPONENT, message = "listener onStart")
                        callback?.onStart()
                    }
                } catch (e: Exception) {
                    logError(throwable = e, message = "start failure")
                    failPlayback(
                        errorCode = AlphaVideo.ErrorCode.Start,
                        message = "start failure: ${e.message ?: e}"
                    )
                }
            }

            PlayerState.PAUSED -> {
                try {
                    mediaPlayer.start()
                    playerState = PlayerState.STARTED
                    AlphaVideoLog.i(
                        component = COMPONENT,
                        message = "state STARTED (resume) gen=$playbackGeneration"
                    )
                    rearmFirstFrameIfHeld()
                } catch (e: Exception) {
                    logError(throwable = e, message = "resume failure")
                    failPlayback(
                        errorCode = AlphaVideo.ErrorCode.Resume,
                        message = "resume failure: ${e.message ?: e}"
                    )
                }
            }

            else -> Unit
        }
    }

    private fun rearmFirstFrameIfHeld() {
        if (surfaceHold !is SurfaceHold.Lost) {
            return
        }
        clearSurfaceHold()
        renderView.onFirstFrame()
    }

    private fun stopInternal(emitCompleted: Boolean) {
        when (playerState) {
            PlayerState.STARTED,
            PlayerState.PAUSED -> {
                resetPlaybackSession(
                    nextState = PlayerState.STOPPED,
                    decoderAction = DecoderAction.Stop,
                    clearPlayWhenReady = false
                )
                if (emitCompleted) {
                    emitCompleted()
                }
            }

            PlayerState.NOT_PREPARED,
            PlayerState.PREPARED -> {
                resetPlaybackSession(
                    nextState = PlayerState.STOPPED,
                    decoderAction = DecoderAction.Reset,
                    clearPlayWhenReady = false
                )
            }

            PlayerState.ENDED -> {
                resetPlaybackSession(
                    nextState = PlayerState.STOPPED,
                    decoderAction = DecoderAction.Reset,
                    clearPlayWhenReady = true
                )
            }

            else -> Unit
        }
    }

    private fun failPlayback(errorCode: AlphaVideo.ErrorCode, message: String) {
        if (playerState == PlayerState.RELEASE) {
            return
        }
        resetPlaybackSession(
            nextState = PlayerState.STOPPED,
            decoderAction = DecoderAction.Reset,
            clearPlayWhenReady = true
        )
        emitError(errorCode = errorCode, message = message)
    }

    private fun resetPlaybackSession(
        nextState: PlayerState,
        decoderAction: DecoderAction,
        clearPlayWhenReady: Boolean
    ) {
        val fromState = playerState
        invalidatePlayback()
        pendingMediaItem = null
        clearSurfaceHold()
        if (clearPlayWhenReady) {
            playWhenReady = false
        }
        when (decoderAction) {
            DecoderAction.Stop -> runCatching { mediaPlayer.stop() }
            DecoderAction.Reset -> runCatching { mediaPlayer.reset() }
            DecoderAction.StopThenReset -> {
                when (playerState) {
                    PlayerState.STARTED,
                    PlayerState.PAUSED -> runCatching { mediaPlayer.stop() }

                    else -> Unit
                }
                runCatching { mediaPlayer.reset() }
            }
        }
        renderView.onCompletion()
        playerState = nextState
        AlphaVideoLog.i(
            component = COMPONENT,
            message = "session reset $fromState->$nextState action=$decoderAction " +
                    "gen=$playbackGeneration playWhenReady=$playWhenReady"
        )
    }

    private fun invalidatePlayback() {
        playbackGeneration++
        awaitingPrepared = false
    }

    private fun onDecoderSurfacePrepared(surface: Surface) {
        AlphaVideoLog.i(
            component = COMPONENT,
            message = "surface prepared state=$playerState hold=$surfaceHold " +
                    "playWhenReady=$playWhenReady pending=${pendingMediaItem != null}"
        )
        currentSurface = surface
        when (val hold = surfaceHold) {
            is SurfaceHold.Lost -> {
                if (hold.pausedDecoder &&
                    playerState == PlayerState.PAUSED &&
                    shouldCompleteAfterSurfaceLoss(remainingAtLossMs = hold.remainingAtLossMs)
                ) {
                    clearSurfaceHold()
                    playWhenReady = false
                    if (!bindDecoderSurface(surface = surface)) {
                        return
                    }
                    stopInternal(emitCompleted = true)
                    return
                }
                if (hold.pausedDecoder) {
                    surfaceHold = SurfaceHold.Lost(
                        pausedDecoder = false,
                        remainingAtLossMs = AlphaVideoMediaPlayer.TIME_UNSET
                    )
                }
            }

            SurfaceHold.None -> Unit
        }
        if (pendingMediaItem != null) {
            handlePendingMediaItem()
            return
        }
        if (!bindDecoderSurface(surface = surface)) {
            return
        }
        if (playWhenReady &&
            (playerState == PlayerState.PREPARED || playerState == PlayerState.PAUSED)
        ) {
            startPlay()
        }
    }

    private fun onDecoderSurfaceDestroyed() {
        AlphaVideoLog.i(
            component = COMPONENT,
            message = "surface destroyed state=$playerState playWhenReady=$playWhenReady"
        )
        currentSurface = null
        renderView.onSurfaceLost()
        bindDecoderSurface(surface = null)
        if (playerState == PlayerState.STARTED) {
            val remaining = remainingDurationMs()
            runCatching { mediaPlayer.pause() }
            playerState = PlayerState.PAUSED
            surfaceHold = SurfaceHold.Lost(
                pausedDecoder = true,
                remainingAtLossMs = remaining
            )
        } else {
            surfaceHold = SurfaceHold.Lost(
                pausedDecoder = false,
                remainingAtLossMs = AlphaVideoMediaPlayer.TIME_UNSET
            )
        }
    }

    private fun remainingDurationMs(): Long {
        if (mediaItem?.looping == true) {
            return AlphaVideoMediaPlayer.TIME_UNSET
        }
        val duration = mediaPlayer.getDuration()
        val position = mediaPlayer.getCurrentPosition()
        if (duration == AlphaVideoMediaPlayer.TIME_UNSET ||
            position == AlphaVideoMediaPlayer.TIME_UNSET ||
            duration < 0 ||
            position < 0
        ) {
            return AlphaVideoMediaPlayer.TIME_UNSET
        }
        return (duration - position).coerceAtLeast(minimumValue = 0L)
    }

    private fun shouldCompleteAfterSurfaceLoss(remainingAtLossMs: Long): Boolean {
        if (mediaItem?.looping == true) {
            return false
        }
        return remainingAtLossMs != AlphaVideoMediaPlayer.TIME_UNSET &&
                remainingAtLossMs <= NEAR_EOS_SLACK_MS
    }

    private fun clearSurfaceHold() {
        surfaceHold = SurfaceHold.None
    }

    /** Drops auto-resume / near-EOS intent; may leave [SurfaceHold.Lost] for GL sampling rearm. */
    private fun dropSurfaceResumeIntent() {
        val hold = surfaceHold
        if (hold is SurfaceHold.Lost && hold.pausedDecoder) {
            surfaceHold = SurfaceHold.Lost(
                pausedDecoder = false,
                remainingAtLossMs = AlphaVideoMediaPlayer.TIME_UNSET
            )
        }
    }

    private fun parseVideoSize() {
        val videoSize = mediaPlayer.getVideoSize()
        if (videoSize.width <= 0 || videoSize.height <= 0) {
            throw IllegalStateException(
                "invalid video size: ${videoSize.width}x${videoSize.height}"
            )
        }
        val layout = renderView.getLayout()
        val (width, height) = rgbHalfSize(
            sourceWidth = videoSize.width,
            sourceHeight = videoSize.height,
            layout = layout
        )
        val scaleType = renderView.getScaleType()
        AlphaVideoLog.i(
            component = COMPONENT,
            message = "videoSize source=${videoSize.width}x${videoSize.height} " +
                    "rgbHalf=${width}x${height} " +
                    "view=${renderView.getMeasuredWidth()}x${renderView.getMeasuredHeight()} " +
                    "scaleType=$scaleType layout=$layout"
        )
        renderView.measureInternal(
            videoWidth = width.toFloat(),
            videoHeight = height.toFloat()
        )
        val callback = listener
        postToMain {
            AlphaVideoLog.i(
                component = COMPONENT,
                message = "listener onVideoSizeChanged ${width}x${height}"
            )
            callback?.onVideoSizeChanged(
                width = width,
                height = height
            )
        }
    }

    private fun rgbHalfSize(
        sourceWidth: Int,
        sourceHeight: Int,
        layout: AlphaVideo.Layout
    ): Pair<Int, Int> {
        return when (layout) {
            AlphaVideo.Layout.AlphaLeft, AlphaVideo.Layout.AlphaRight -> {
                sourceWidth / 2 to sourceHeight
            }

            AlphaVideo.Layout.AlphaTop, AlphaVideo.Layout.AlphaBottom -> {
                sourceWidth to sourceHeight / 2
            }
        }
    }

    private fun destroyInternal() {
        invalidatePlayback()
        playHandler.removeCallbacksAndMessages(null)
        pendingMediaItem = null
        clearSurfaceHold()
        playWhenReady = false
        when (playerState) {
            PlayerState.STARTED -> {
                runCatching { mediaPlayer.pause() }
                playerState = PlayerState.PAUSED
            }

            else -> Unit
        }
        when (playerState) {
            PlayerState.PAUSED,
            PlayerState.PREPARED,
            PlayerState.ENDED -> {
                runCatching { mediaPlayer.stop() }
                playerState = PlayerState.STOPPED
            }

            else -> Unit
        }
        mediaPlayer.release()
        currentSurface = null
        renderView.release()
        renderView.onPause()
        playerState = PlayerState.RELEASE
        clearCallbacks()
        // release() may queue more work on this looper; let it run before the thread exits.
        playHandler.post {
            playThread.quitSafely()
        }
    }

    private fun clearCallbacks() {
        listener = null
        mainHandler.removeCallbacksAndMessages(null)
    }

    private fun runSetSource(block: () -> Unit) {
        try {
            block()
        } catch (e: Exception) {
            logError(throwable = e, message = "set source failure")
            failPlayback(
                errorCode = AlphaVideo.ErrorCode.Source,
                message = "set source failure: ${e.message ?: e}"
            )
        }
    }

    private fun emitCompleted() {
        val callback = listener
        postToMain {
            AlphaVideoLog.i(component = COMPONENT, message = "listener onCompleted")
            callback?.onCompleted()
        }
    }

    private fun emitError(errorCode: AlphaVideo.ErrorCode, message: String) {
        val callback = listener
        postToMain {
            AlphaVideoLog.e(
                component = COMPONENT,
                message = "listener onError code=${errorCode.code} name=$errorCode msg=$message"
            )
            callback?.onError(errorCode = errorCode, message = message)
        }
    }

    private fun logError(throwable: Throwable, message: String) {
        AlphaVideoLog.e(
            component = COMPONENT,
            message = message,
            throwable = throwable
        )
    }

    private fun postToMain(action: () -> Unit) {
        if (Looper.myLooper() == Looper.getMainLooper()) {
            action()
        } else {
            mainHandler.post(action)
        }
    }

    /**
     * Surface-loss bookkeeping until the next successful [startPlay] (or session reset).
     * [Lost.pausedDecoder]: decoder was paused while playing (auto-resume / near-EOS).
     * [Lost.remainingAtLossMs]: remaining duration snapshot at loss for near-EOS complete.
     * Any [Lost]: rearm GL sampling on the next [startPlay].
     */
    private sealed class SurfaceHold {
        data object None : SurfaceHold()

        data class Lost(
            val pausedDecoder: Boolean,
            val remainingAtLossMs: Long
        ) : SurfaceHold()
    }

    private enum class DecoderAction {
        Stop,
        Reset,
        StopThenReset
    }

    private enum class PlayerState {
        NOT_PREPARED,
        PREPARED,
        STARTED,
        PAUSED,
        STOPPED,
        ENDED,
        RELEASE
    }

    private sealed interface PlayCommand {

        data object Init : PlayCommand

        data class SetMediaItem(val mediaItem: AlphaVideo.MediaItem) : PlayCommand

        data object Prepare : PlayCommand

        data object Play : PlayCommand

        data object Pause : PlayCommand

        data object Stop : PlayCommand

        data class SetVolume(val volume: Float) : PlayCommand

        data class Prepared(val generation: Int) : PlayCommand

        data class FirstFrame(val generation: Int) : PlayCommand

        data class Completed(val generation: Int) : PlayCommand

        data class Error(
            val errorCode: AlphaVideo.ErrorCode,
            val message: String,
            val generation: Int
        ) : PlayCommand

        data class SurfacePrepared(val surface: Surface) : PlayCommand

        data object SurfaceDestroyed : PlayCommand

        data object Destroy : PlayCommand
    }

    private data class VideoTrack(
        val width: Int,
        val height: Int,
        val rotationDegrees: Int
    )

}
