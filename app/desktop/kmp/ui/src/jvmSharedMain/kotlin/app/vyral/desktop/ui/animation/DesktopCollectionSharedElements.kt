package app.vyral.desktop.ui.animation

import androidx.compose.animation.AnimatedVisibilityScope
import androidx.compose.animation.ExperimentalSharedTransitionApi
import androidx.compose.animation.SharedTransitionScope
import androidx.compose.animation.core.FiniteAnimationSpec
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.snap
import androidx.compose.animation.core.spring
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.compositionLocalOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Rect
import app.vyral.night.ui.icons.LocalVyralReduceMotion

private val LocalDesktopSharedTransitionScope = compositionLocalOf<SharedTransitionScope?> { null }
private val LocalDesktopAnimatedVisibilityScope = compositionLocalOf<AnimatedVisibilityScope?> { null }

@OptIn(ExperimentalSharedTransitionApi::class)
@Composable
fun ProvideDesktopSharedElementScopes(
    sharedTransitionScope: SharedTransitionScope,
    animatedVisibilityScope: AnimatedVisibilityScope,
    content: @Composable () -> Unit,
) {
    CompositionLocalProvider(
        LocalDesktopSharedTransitionScope provides sharedTransitionScope,
        LocalDesktopAnimatedVisibilityScope provides animatedVisibilityScope,
        content = content,
    )
}

@OptIn(ExperimentalSharedTransitionApi::class)
@Composable
fun Modifier.desktopCollectionArtwork(browseId: String?): Modifier =
    desktopCollectionSharedElement(browseId, "artwork")

@OptIn(ExperimentalSharedTransitionApi::class)
@Composable
fun Modifier.desktopCollectionTitle(browseId: String?): Modifier =
    desktopCollectionSharedElement(browseId, "title", bounds = true)

@OptIn(ExperimentalSharedTransitionApi::class)
@Composable
private fun Modifier.desktopCollectionSharedElement(
    browseId: String?,
    element: String,
    bounds: Boolean = false,
): Modifier {
    val sharedScope = LocalDesktopSharedTransitionScope.current ?: return this
    val visibilityScope = LocalDesktopAnimatedVisibilityScope.current ?: return this
    val id = desktopCollectionSharedIdentity(browseId) ?: return this
    val reduceMotion = LocalVyralReduceMotion.current
    return with(sharedScope) {
        val state = rememberSharedContentState("desktop-collection:$element:$id")
        if (bounds) {
            this@desktopCollectionSharedElement.sharedBounds(
                sharedContentState = state,
                animatedVisibilityScope = visibilityScope,
                boundsTransform = { _, _ -> desktopCollectionBounds(reduceMotion) },
                renderInOverlayDuringTransition = true,
                zIndexInOverlay = 2f,
            )
        } else {
            this@desktopCollectionSharedElement.sharedElement(
                sharedContentState = state,
                animatedVisibilityScope = visibilityScope,
                boundsTransform = { _, _ -> desktopCollectionBounds(reduceMotion) },
                renderInOverlayDuringTransition = true,
                zIndexInOverlay = 1f,
            )
        }
    }
}

internal fun desktopCollectionSharedIdentity(browseId: String?): String? =
    browseId
        ?.takeIf { it.isNotBlank() && !it.startsWith("local:") }
        ?.removePrefix("desktop-playlist:")
        ?.takeIf(String::isNotBlank)

private fun desktopCollectionBounds(reduceMotion: Boolean): FiniteAnimationSpec<Rect> =
    if (reduceMotion) snap() else spring(dampingRatio = 0.9f, stiffness = Spring.StiffnessMediumLow)
