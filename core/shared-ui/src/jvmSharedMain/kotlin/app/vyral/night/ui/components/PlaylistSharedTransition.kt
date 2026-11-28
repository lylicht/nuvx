@file:OptIn(androidx.compose.animation.ExperimentalSharedTransitionApi::class)

package app.vyral.night.ui.components

import androidx.compose.animation.AnimatedVisibilityScope
import androidx.compose.animation.SharedTransitionScope
import androidx.compose.animation.core.spring
import androidx.compose.runtime.Composable
import androidx.compose.runtime.compositionLocalOf
import androidx.compose.ui.Modifier

/** Shared scope supplied by the platform navigation host; null in previews or hosts without transitions. */
val LocalVyralSharedTransitionScope = compositionLocalOf<SharedTransitionScope?> { null }

/** Visibility scope belonging to the current navigation destination. */
val LocalVyralPlaylistVisibilityScope = compositionLocalOf<AnimatedVisibilityScope?> { null }

/** Hosts switch this off for reduced-motion users; calls then remain plain modifiers. */
val LocalVyralPlaylistTransitionsEnabled = compositionLocalOf { true }

/** Stable artwork key shared by a playlist card and its detail hero. */
internal fun playlistArtworkTransitionKey(browseId: String): String = "vyral:playlist-artwork:$browseId"

/** Stable title key shared by a playlist card and its detail heading. */
internal fun playlistTitleTransitionKey(browseId: String): String = "vyral:playlist-title:$browseId"

/** Morphs only when the same playlist is represented in both navigation destinations. */
@Composable
fun Modifier.vyralPlaylistArtworkTransition(browseId: String?): Modifier =
    sharedPlaylistElement(browseId, title = false)

/** Carries a playlist title between its source card and detail heading. */
@Composable
fun Modifier.vyralPlaylistTitleTransition(browseId: String?): Modifier =
    sharedPlaylistElement(browseId, title = true)

@Composable
private fun Modifier.sharedPlaylistElement(browseId: String?, title: Boolean): Modifier {
    if (!LocalVyralPlaylistTransitionsEnabled.current || browseId.isNullOrBlank() || browseId.startsWith("local:")) return this
    val sharedScope = LocalVyralSharedTransitionScope.current ?: return this
    val visibilityScope = LocalVyralPlaylistVisibilityScope.current ?: return this
    val key = if (title) playlistTitleTransitionKey(browseId) else playlistArtworkTransitionKey(browseId)
    return with(sharedScope) {
        if (title) {
            sharedBounds(
                sharedContentState = rememberSharedContentState(key),
                animatedVisibilityScope = visibilityScope,
                boundsTransform = { _, _ ->
                    spring(dampingRatio = 0.86f, stiffness = 360f)
                },
                renderInOverlayDuringTransition = true,
            )
        } else {
            sharedElement(
                sharedContentState = rememberSharedContentState(key),
                animatedVisibilityScope = visibilityScope,
                boundsTransform = { _, _ ->
                    spring(dampingRatio = 0.86f, stiffness = 360f)
                },
                renderInOverlayDuringTransition = true,
                zIndexInOverlay = 1f,
            )
        }
    }
}
