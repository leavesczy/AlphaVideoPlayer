package github.leavesczy.alphavideoplayer

import android.content.Context
import github.leavesczy.alphavideoplayer.internal.player.SystemMediaPlayer

/** [AlphaVideoPlayer] using [android.media.MediaPlayer]. Files are filesystem paths; assets use `openFd`. */
class AlphaVideoSystemPlayer(context: Context) : AlphaVideoPlayer(
    context = context,
    mediaPlayer = SystemMediaPlayer(context = context)
)
