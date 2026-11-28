package app.vyral.desktop

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.awt.SwingPanel
import androidx.compose.ui.unit.dp
import app.vyral.night.data.innertube.StreamResolver
import app.vyral.night.data.model.Song
import javafx.application.Platform
import javafx.embed.swing.JFXPanel
import javafx.scene.Scene
import javafx.scene.layout.StackPane
import javafx.scene.media.Media
import javafx.scene.media.MediaPlayer
import javafx.scene.media.MediaView

@Composable
internal fun VyralDesktopVideoPlayer(video: Song, onClose: () -> Unit) {
    var streamUrl by remember(video.videoId) { mutableStateOf<String?>(null) }
    var error by remember(video.videoId) { mutableStateOf<String?>(null) }
    var retry by remember { mutableIntStateOf(0) }
    var preparing by remember(video.videoId) { mutableStateOf(true) }
    var position by remember { mutableFloatStateOf(0f) }
    var duration by remember { mutableFloatStateOf(1f) }
    val holder = remember { arrayOfNulls<MediaPlayer>(1) }
    LaunchedEffect(video.videoId, retry) {
        error = null; streamUrl = null; preparing = true
        runCatching { StreamResolver.resolve(video.videoId) }
            .onSuccess { streamUrl = it }
            .onFailure { error = it.message ?: "This video could not be loaded." }
    }
    DisposableEffect(Unit) { onDispose { Platform.runLater { holder[0]?.dispose(); holder[0] = null } } }
    Column(Modifier.fillMaxSize().background(Color.Black).padding(16.dp)) {
        Row(Modifier.fillMaxWidth()) {
            TextButton(onClick = onClose) { Text("Back to videos") }
            Text(video.title, color = Color.White, style = MaterialTheme.typography.titleMedium, modifier = Modifier.weight(1f).padding(12.dp))
        }
        val ready = streamUrl
        if (preparing && error == null) LinearProgressIndicator(Modifier.fillMaxWidth())
        if (ready != null) key(video.videoId, retry) {
            val proxy = remember(ready) { VyralVideoProxy(ready) }
            DisposableEffect(proxy) { onDispose { proxy.close() } }
            SwingPanel(background = Color.Black, modifier = Modifier.fillMaxWidth().weight(1f), factory = {
                JFXPanel().apply {
                    Platform.setImplicitExit(false)
                    Platform.runLater {
                        try {
                            val media = Media(proxy.url)
                            val player = MediaPlayer(media)
                            holder[0] = player
                            player.setOnError { preparing = false; error = "This video could not be played. Retry or choose another video." }
                            media.setOnError { preparing = false; error = "This video format is not supported. Choose another video." }
                            player.setOnReady { preparing = false; duration = player.totalDuration.toSeconds().toFloat().coerceAtLeast(1f); player.play() }
                            player.currentTimeProperty().addListener { _, _, time -> position = time.toSeconds().toFloat() }
                            val view = MediaView(player).apply { isPreserveRatio = true }
                            val pane = StackPane(view).apply { style = "-fx-background-color: black;" }
                            view.fitWidthProperty().bind(pane.widthProperty()); view.fitHeightProperty().bind(pane.heightProperty())
                            scene = Scene(pane)
                        } catch (e: Exception) { preparing = false; error = "Video playback is unavailable. Please retry." }
                    }
                }
            })
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                Button(onClick = { Platform.runLater { holder[0]?.let { if (it.status == MediaPlayer.Status.PLAYING) it.pause() else it.play() } } }) { Text("Play / pause") }
                Slider(value = position.coerceIn(0f, duration), valueRange = 0f..duration, onValueChange = { value -> position = value; Platform.runLater { holder[0]?.seek(javafx.util.Duration.seconds(value.toDouble())) } }, modifier = Modifier.weight(1f))
            }
        } else if (error == null) { CircularProgressIndicator(Modifier.padding(40.dp)); Spacer(Modifier.weight(1f)) }
        error?.let { message -> Text(message, color = Color.White, modifier = Modifier.padding(20.dp)); Button(onClick = { Platform.runLater { holder[0]?.dispose() }; retry++ }) { Text("Retry") } }
    }
}
