package app.vyral.night

import android.content.Intent
import android.os.Bundle
import android.view.WindowManager
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.viewModels
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.core.view.WindowCompat
import app.vyral.night.data.settings.AppSettings
import app.vyral.night.data.listentogether.JamInviteLink
import app.vyral.night.playback.MusicLink
import app.vyral.night.playback.PlayerDeepLink
import app.vyral.night.playback.rememberMediaController
import app.vyral.night.playback.rememberPlayerState
import app.vyral.night.ui.MainViewModel
import app.vyral.night.ui.theme.VyralTheme
import app.vyral.night.ui.icons.LocalVyralReduceMotion
import app.vyral.night.ui.tv.TvApp
import app.vyral.night.ui.tv.display.TvRefreshRateController
import app.vyral.night.ui.tv.theme.VyralTvTheme

/**
 * Dedicated TV Activity for Android TV, Google TV, and Fire TV devices.
 * Launched via `android.intent.category.LEANBACK_LAUNCHER`.
 */
class TvActivity : ComponentActivity() {

    private val viewModel: MainViewModel by viewModels()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        // Attach TV Refresh Rate and Display Controller
        TvRefreshRateController.attach(this)

        // Make window full edge-to-edge for 1080p/4K television screens
        WindowCompat.setDecorFitsSystemWindows(window, false)
        window.addFlags(WindowManager.LayoutParams.FLAG_DRAWS_SYSTEM_BAR_BACKGROUNDS)

        setContent {
            val reduceMotion by AppSettings.reduceAnimation.collectAsStateWithLifecycle()
            VyralTvTheme {
                // The phone's Material theme too, so the components shared with
                // it (lyrics, mood sleeves) are set in SF Pro on its type scale.
                VyralTheme(darkTheme = true) {
                    CompositionLocalProvider(LocalVyralReduceMotion provides reduceMotion) {
                        val mediaController = rememberMediaController()
                        val playerState = rememberPlayerState(mediaController)

                        TvApp(
                            viewModel = viewModel,
                            mediaController = mediaController,
                            playerState = playerState,
                        )
                    }
                }
            }
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        consumeLinks(intent)
    }

    private fun consumeLinks(intent: Intent) {
        PlayerDeepLink.consume(intent)
        JamInviteLink.consume(intent)
        MusicLink.consume(intent)
    }

    override fun onDestroy() {
        // Closing the app (back out of Home, or the task being swiped away)
        // pauses the music rather than leaving it playing behind the launcher.
        if (isFinishing) {
            runCatching {
                val future = androidx.media3.session.MediaController.Builder(
                    applicationContext,
                    androidx.media3.session.SessionToken(
                        applicationContext,
                        android.content.ComponentName(
                            applicationContext,
                            app.vyral.night.playback.PlaybackService::class.java,
                        ),
                    ),
                ).buildAsync()
                future.addListener({
                    runCatching { future.get().run { pause(); release() } }
                }, androidx.core.content.ContextCompat.getMainExecutor(applicationContext))
            }
        }
        super.onDestroy()
        TvRefreshRateController.detach()
    }
}
