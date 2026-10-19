# AlphaVideoPlayer

本项目用于在 Android 系统上播放带透明通道的视频，素材是左右或上下对半拼好的画面：一半是 RGB，一半是灰度 Alpha

## 接入依赖

**基础依赖，通过系统 MediaPlayer 播放视频**

```kotlin
dependencies {
    implementation("io.github.leavesczy:alphavideoplayer:latestVersion")
}
```

**扩展依赖，通过 ExoPlayer 播放视频，因此需一起引入 media3-exoplayer**

```kotlin
dependencies {
    implementation("io.github.leavesczy:alphavideoplayer-exoplayer:latestVersion")
    implementation("androidx.media3:media3-exoplayer:1.11.1")
}
```

## 布局和缩放

`AlphaVideo.Layout` 用于标明视频的格式

- `AlphaLeft`：左 Alpha，右 RGB
- `AlphaRight`：左 RGB，右 Alpha
- `AlphaTop`：上 Alpha，下 RGB
- `AlphaBottom`：上 RGB，下 Alpha

`AlphaVideo.ScaleType` 用于标明视频的播放效果

- `CenterCrop`：铺满裁剪，居中
- `FitWidth`：宽度铺满；高度不够留白，超了裁剪
- `FitCenter`：完整可见，居中留白
- `FitTop` / `FitBottom`：完整可见，贴顶或贴底留白

## 怎么用

- `AlphaVideoSystemPlayer`： MediaPlayer
- `AlphaVideoExoPlayer`：Media3 ExoPlayer

数据源用 `MediaItem.fromFile`（本地文件）或 `fromAsset`（`assets/` 的相对路径，如 `videos/foo.mp4`）

**View：**

```xml
<github.leavesczy.alphavideoplayer.AlphaVideoView
    android:id="@+id/alphaVideoView"
    android:layout_width="match_parent"
    android:layout_height="match_parent" />
```

```kotlin
val videoView: AlphaVideoView = findViewById(R.id.alphaVideoView)
val player: AlphaVideoPlayer = AlphaVideoSystemPlayer(context = this)
player.setListener(listener = object : AlphaVideo.Listener {
    override fun onCompleted() {}
    override fun onError(errorCode: AlphaVideo.ErrorCode, message: String) {}
})
videoView.setPlayer(player = player)

player.setMediaItem(
    mediaItem = AlphaVideo.MediaItem.fromAsset(
        assetPath = "videos/alpha_left_01.mp4",
        scaleType = AlphaVideo.ScaleType.FitCenter,
        layout = AlphaVideo.Layout.AlphaLeft,
        looping = false
    )
)
player.play()

videoView.setPlayer(player = null)
player.release()
```

**Jetpack Compose：**

```kotlin
@Composable
fun AlphaVideoOverlay(modifier: Modifier = Modifier) {
    val context = LocalContext.current
    val player = remember {
        AlphaVideoSystemPlayer(context = context).also {
            it.setListener(listener = object : AlphaVideo.Listener {
                override fun onCompleted() {}
                override fun onError(errorCode: AlphaVideo.ErrorCode, message: String) {}
            })
        }
    }
    AndroidView(
        modifier = modifier
            .fillMaxSize(),
        factory = { viewContext ->
            AlphaVideoView(context = viewContext).also { view ->
                view.setPlayer(player = player)
            }
        },
        onRelease = { view ->
            view.setPlayer(player = null)
            player.release()
        }
    )
}
```

### 主要 API

- `setMediaItem`：切换媒体资源
- `setVolume`：线性音量，范围 `0f..1f`，默认 `0f`
- `prepare` / `play` / `pause` / `stop` / `release`
- `play`：未 prepare 时会先 prepare 再播；暂停中则恢复
- `stop`：若已经播放过则会回调 `onCompleted`
- `onVideoSizeChanged`：RGB 半幅尺寸
- `onStart`：从 prepared 第一次开播时回调

### 调试

`AlphaVideoLog.enabled = true` 后，logcat tag 为 `AlphaVideoPlayer`。默认关闭

![](https://github.com/user-attachments/assets/5dc1d426-f73b-4dad-b919-8b934f721bae)