package github.leavesczy.alphavideoplayer.sample

import android.content.Context
import android.content.Intent
import android.graphics.Color
import android.os.Bundle
import android.util.Log
import androidx.activity.ComponentActivity
import androidx.activity.SystemBarStyle
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material3.Button
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import github.leavesczy.alphavideoplayer.AlphaVideo
import github.leavesczy.alphavideoplayer.AlphaVideoLog
import github.leavesczy.alphavideoplayer.AlphaVideoPlayer
import github.leavesczy.alphavideoplayer.AlphaVideoSystemPlayer
import github.leavesczy.alphavideoplayer.AlphaVideoView
import github.leavesczy.alphavideoplayer.exoplayer.AlphaVideoExoPlayer
import github.leavesczy.alphavideoplayer.sample.VideoAssets.Video

class PlayerActivity : ComponentActivity() {

    companion object {

        private const val TAG = "PlayerActivity"

        private const val EXTRA_PLAYER_TYPE = "extra_player_type"

        internal fun intent(context: Context, playerType: PlayerType): Intent {
            return Intent(context, PlayerActivity::class.java).putExtra(
                EXTRA_PLAYER_TYPE,
                playerType.name
            )
        }
    }

    private val playerType: PlayerType by lazy {
        val name = intent.getStringExtra(EXTRA_PLAYER_TYPE).orEmpty()
        runCatching { PlayerType.valueOf(value = name) }
            .getOrDefault(defaultValue = PlayerType.ExoPlayer)
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        AlphaVideoLog.enabled = true
        enableEdgeToEdge(statusBarStyle = SystemBarStyle.dark(scrim = Color.TRANSPARENT))
        setContent {
            AppTheme {
                PlayerPage(
                    modifier = Modifier
                        .fillMaxSize(),
                    playerType = playerType,
                    createPlayer = ::createPlayer,
                    onBack = ::finish,
                    log = ::log
                )
            }
        }
    }

    private fun createPlayer(): AlphaVideoPlayer {
        return when (playerType) {
            PlayerType.ExoPlayer -> AlphaVideoExoPlayer(context = applicationContext)
            PlayerType.SystemPlayer -> AlphaVideoSystemPlayer(context = applicationContext)
        }
    }

    private fun log(message: () -> Any) {
        Log.d(TAG, "[$playerType] ${message()}")
    }

}

@Composable
private fun PlayerPage(
    modifier: Modifier,
    playerType: PlayerType,
    createPlayer: () -> AlphaVideoPlayer,
    onBack: () -> Unit,
    log: (() -> Any) -> Unit
) {
    val context = LocalContext.current
    val videos = remember(key1 = context) {
        VideoAssets.list(context = context)
    }
    var scaleType by remember {
        mutableStateOf(value = AlphaVideo.ScaleType.FitWidth)
    }
    val listener = remember {
        object : AlphaVideo.Listener {
            override fun onVideoSizeChanged(width: Int, height: Int) {
                log {
                    "onVideoSizeChanged width=$width, height=$height"
                }
            }

            override fun onStart() {
                log {
                    "onStart"
                }
            }

            override fun onCompleted() {
                log {
                    "onCompleted"
                }
            }

            override fun onError(errorCode: AlphaVideo.ErrorCode, message: String) {
                log {
                    "onError code=${errorCode.code} name=$errorCode message=$message"
                }
            }
        }
    }
    val player = remember {
        createPlayer().also { created ->
            created.setListener(listener = listener)
        }
    }
    Scaffold(
        modifier = modifier
            .fillMaxSize(),
        containerColor = MaterialTheme.colorScheme.background,
        contentWindowInsets = WindowInsets()
    ) { innerPadding ->
        Box(
            modifier = Modifier
                .fillMaxSize()
                .padding(paddingValues = innerPadding)
        ) {
            Column(
                modifier = Modifier
                    .navigationBarsPadding()
                    .fillMaxSize()
            ) {
                TopBar(
                    modifier = Modifier,
                    title = when (playerType) {
                        PlayerType.ExoPlayer -> stringResource(id = R.string.demo_title_exo)
                        PlayerType.SystemPlayer -> stringResource(id = R.string.demo_title_system)
                    },
                    onBack = onBack
                )
                ScaleTypeSelector(
                    modifier = Modifier
                        .padding(vertical = 10.dp)
                        .fillMaxWidth(),
                    selected = scaleType,
                    onSelected = { type ->
                        scaleType = type
                    }
                )
                LazyColumn(
                    modifier = Modifier
                        .weight(weight = 1f)
                        .fillMaxWidth(),
                    contentPadding = PaddingValues(horizontal = 24.dp, vertical = 10.dp),
                    verticalArrangement = Arrangement.spacedBy(
                        space = 12.dp,
                        alignment = Alignment.CenterVertically
                    ),
                    horizontalAlignment = Alignment.CenterHorizontally
                ) {
                    items(
                        items = videos,
                        key = { video ->
                            video.path
                        }
                    ) { video ->
                        Button(
                            modifier = Modifier
                                .fillMaxWidth(),
                            onClick = {
                                play(
                                    player = player,
                                    video = video,
                                    scaleType = scaleType
                                )
                            }
                        ) {
                            Text(
                                text = stringResource(
                                    id = R.string.action_play_video,
                                    video.name
                                ),
                                style = MaterialTheme.typography.labelLarge
                            )
                        }
                    }
                }
            }
            AndroidView(
                modifier = Modifier
                    .fillMaxSize(),
                factory = { viewContext ->
                    AlphaVideoView(context = viewContext).also { view ->
                        view.isClickable = false
                        view.isFocusable = false
                        view.setPlayer(player = player)
                    }
                },
                onRelease = { view ->
                    view.setPlayer(player = null)
                    player.release()
                }
            )
        }
    }
}

@Composable
private fun ScaleTypeSelector(
    modifier: Modifier,
    selected: AlphaVideo.ScaleType,
    onSelected: (AlphaVideo.ScaleType) -> Unit
) {
    Column(
        modifier = modifier,
        verticalArrangement = Arrangement.spacedBy(space = 8.dp)
    ) {
        Text(
            modifier = Modifier
                .padding(horizontal = 24.dp),
            text = stringResource(id = R.string.label_scale_type),
            style = MaterialTheme.typography.titleMedium,
            color = MaterialTheme.colorScheme.onBackground
        )
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .horizontalScroll(state = rememberScrollState())
                .padding(horizontal = 24.dp),
            horizontalArrangement = Arrangement.spacedBy(space = 8.dp)
        ) {
            AlphaVideo.ScaleType.entries.forEach { type ->
                FilterChip(
                    selected = type == selected,
                    onClick = { onSelected(type) },
                    label = {
                        Text(text = type.name)
                    }
                )
            }
        }
    }
}

private fun play(
    player: AlphaVideoPlayer,
    video: Video,
    scaleType: AlphaVideo.ScaleType
) {
    player.setMediaItem(
        mediaItem = AlphaVideo.MediaItem.fromAsset(
            assetPath = video.path,
            scaleType = scaleType,
            layout = VideoLayouts.from(name = video.name),
            looping = false
        )
    )
    player.play()
}
