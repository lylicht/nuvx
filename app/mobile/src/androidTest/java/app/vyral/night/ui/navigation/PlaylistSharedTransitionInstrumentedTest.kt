package app.vyral.night.ui.navigation

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.EnterTransition
import androidx.compose.animation.ExitTransition
import androidx.compose.animation.ExperimentalSharedTransitionApi
import androidx.compose.animation.SharedTransitionLayout
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material3.Text
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.test.ext.junit.runners.AndroidJUnit4
import app.vyral.night.ui.components.LocalVyralPlaylistTransitionsEnabled
import app.vyral.night.ui.components.LocalVyralPlaylistVisibilityScope
import app.vyral.night.ui.components.LocalVyralSharedTransitionScope
import app.vyral.night.ui.components.vyralPlaylistArtworkTransition
import app.vyral.night.ui.components.vyralPlaylistTitleTransition
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@OptIn(ExperimentalSharedTransitionApi::class)
@RunWith(AndroidJUnit4::class)
class PlaylistSharedTransitionInstrumentedTest {
    @get:Rule
    val compose = createComposeRule()

    @Test
    fun matchingPlaylistArtworkMorphsAndReducedMotionSuppressesIt() {
        compose.mainClock.autoAdvance = false
        var detail by mutableStateOf(false)
        var reduceMotion by mutableStateOf(false)
        var transitionActive by mutableStateOf(false)

        compose.setContent {
            SharedTransitionLayout {
                val sharedScope = this
                LaunchedEffect(sharedScope) {
                    snapshotFlow { sharedScope.isTransitionActive }
                        .collect { transitionActive = it }
                }
                AnimatedContent(
                    targetState = detail,
                    transitionSpec = { EnterTransition.None togetherWith ExitTransition.None },
                    label = "playlist-detail-test",
                ) { showingDetail ->
                    CompositionLocalProvider(
                        LocalVyralSharedTransitionScope provides sharedScope,
                        LocalVyralPlaylistVisibilityScope provides this@AnimatedContent,
                        LocalVyralPlaylistTransitionsEnabled provides !reduceMotion,
                    ) {
                        Box(Modifier.fillMaxSize().background(Color.Black)) {
                            if (showingDetail) {
                                Box(
                                    Modifier
                                        .align(Alignment.Center)
                                        .size(220.dp)
                                        .background(Color.Red)
                                        .vyralPlaylistArtworkTransition("VL-transition-test")
                                        .testTag("playlist-hero"),
                                )
                                Text(
                                    text = "Test playlist",
                                    modifier = Modifier
                                        .align(Alignment.BottomCenter)
                                        .vyralPlaylistTitleTransition("VL-transition-test")
                                        .testTag("playlist-detail-title"),
                                    color = Color.White,
                                )
                            } else {
                                Box(
                                    Modifier
                                        .align(Alignment.TopStart)
                                        .padding(24.dp)
                                        .size(72.dp)
                                        .background(Color.Red)
                                        .vyralPlaylistArtworkTransition("VL-transition-test")
                                        .testTag("playlist-card"),
                                )
                                Text(
                                    text = "Test playlist",
                                    modifier = Modifier
                                        .align(Alignment.TopStart)
                                        .padding(start = 108.dp, top = 32.dp)
                                        .vyralPlaylistTitleTransition("VL-transition-test")
                                        .testTag("playlist-card-title"),
                                    color = Color.White,
                                )
                            }
                        }
                    }
                }
            }
        }

        compose.runOnIdle { detail = true }
        compose.mainClock.advanceTimeBy(16)
        compose.mainClock.advanceTimeBy(100)
        compose.waitForIdle()
        assertTrue("The matching card and detail hero should start a shared transition", transitionActive)

        compose.mainClock.advanceTimeBy(1_000)
        compose.waitForIdle()
        assertFalse("The shared transition should settle", transitionActive)

        compose.runOnIdle {
            detail = false
            reduceMotion = true
        }
        compose.mainClock.advanceTimeBy(1_000)
        compose.waitForIdle()
        compose.runOnIdle { detail = true }
        compose.mainClock.advanceTimeBy(400)
        compose.waitForIdle()
        assertFalse("Reduced motion must keep the route transition still", transitionActive)
    }
}
