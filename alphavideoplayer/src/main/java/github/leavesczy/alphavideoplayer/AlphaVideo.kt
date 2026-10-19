package github.leavesczy.alphavideoplayer

import java.io.File

object AlphaVideo {

    /** How the RGB half maps into [AlphaVideoView]. */
    enum class ScaleType {
        CenterCrop,
        FitWidth,
        FitCenter,
        FitTop,
        FitBottom;
    }

    /** Half-frame pack layout after [android.graphics.SurfaceTexture] transform. */
    enum class Layout {
        AlphaLeft,
        AlphaRight,
        AlphaTop,
        AlphaBottom
    }

    /** Packed decoder frame size; [Listener.onVideoSizeChanged] reports the RGB half. */
    data class VideoSize(
        val width: Int,
        val height: Int
    )

    /**
     * Codes for [Listener.onError].
     * [AssetNotFound] includes a blank asset path. [UnsupportedRotation] is 90° or 270°.
     * [Decoder] leaves the backend status in the message.
     * [Internal] is an unexpected failure inside the player; the message contains the exception.
     */
    enum class ErrorCode(val code: Int) {
        FileNotFound(1001),
        AssetNotFound(1002),
        UnsupportedRotation(1003),
        Init(2001),
        Source(2002),
        Prepare(2003),
        VideoSize(2004),
        Start(2005),
        Resume(2006),
        Surface(2007),
        Decoder(2008),
        Render(3001),
        Egl(3002),
        Internal(9000)
    }

    /** Main-thread playback callbacks. */
    interface Listener {

        fun onVideoSizeChanged(width: Int, height: Int) {
        }

        /** Fired only on prepared → started (not when resuming after [AlphaVideoPlayer.pause]). */
        fun onStart() {
        }

        /**
         * Natural end (non-looping), [AlphaVideoPlayer.stop] after playback had started
         * (playing or paused), or surface restore when remaining duration at loss was already
         * near zero. Not after prepare-only stop, [onError], [AlphaVideoPlayer.release], or
         * [AlphaVideoPlayer.setMediaItem].
         */
        fun onCompleted() {
        }

        /** Ends the current item without [onCompleted]. */
        fun onError(errorCode: ErrorCode, message: String) {
        }
    }

    sealed interface Source {

        data class File(val file: java.io.File) : Source

        /** Relative path under `assets/`, e.g. `videos/foo.mp4`. */
        data class Asset(val path: String) : Source

    }

    @ConsistentCopyVisibility
    data class MediaItem private constructor(
        val source: Source,
        val scaleType: ScaleType,
        val layout: Layout,
        val looping: Boolean
    ) {

        companion object {

            fun fromFile(
                file: File,
                scaleType: ScaleType,
                layout: Layout,
                looping: Boolean
            ): MediaItem {
                return MediaItem(
                    source = Source.File(file = file),
                    scaleType = scaleType,
                    layout = layout,
                    looping = looping
                )
            }

            fun fromAsset(
                assetPath: String,
                scaleType: ScaleType,
                layout: Layout,
                looping: Boolean
            ): MediaItem {
                val path = assetPath.trimStart(chars = charArrayOf('/'))
                require(value = path.isNotBlank()) {
                    "asset path must not be blank"
                }
                return MediaItem(
                    source = Source.Asset(path = path),
                    scaleType = scaleType,
                    layout = layout,
                    looping = looping
                )
            }
        }
    }

}
