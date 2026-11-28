package app.vyral.desktop

import androidx.compose.animation.ContentTransform
import androidx.compose.animation.EnterTransition
import androidx.compose.animation.ExitTransition
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.animation.togetherWith
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.tween

internal const val DESKTOP_COLLECTION_TRANSITION_MILLIS = 280

/** The browse id, not loading/content state, keeps the route identity stable as data arrives. */
internal fun desktopCollectionTransitionKey(collection: DesktopCollection?): String =
    collection?.browseId?.takeIf(String::isNotBlank)?.let { "collection:$it" } ?: "page"

internal fun desktopCollectionTransitionDuration(reduceMotion: Boolean): Int =
    if (reduceMotion) 0 else DESKTOP_COLLECTION_TRANSITION_MILLIS

internal fun desktopCollectionTransition(
    initialBrowseId: String?,
    targetBrowseId: String?,
    reduceMotion: Boolean,
): ContentTransform {
    val duration = desktopCollectionTransitionDuration(reduceMotion)
    if (duration == 0) return EnterTransition.None togetherWith ExitTransition.None

    val easing = FastOutSlowInEasing
    return when {
        targetBrowseId != null ->
            (slideInHorizontally(tween(duration, easing = easing)) { (it * 0.055f).toInt() } +
                fadeIn(tween(duration, easing = easing))) togetherWith
                fadeOut(tween(duration, easing = easing), targetAlpha = 0.78f)

        initialBrowseId != null ->
            (slideInHorizontally(tween(duration, easing = easing)) { -(it * 0.035f).toInt() } +
                fadeIn(tween(duration, easing = easing))) togetherWith
                (slideOutHorizontally(tween(duration, easing = easing)) { (it * 0.025f).toInt() } +
                    fadeOut(tween(duration, easing = easing), targetAlpha = 0.78f))

        else -> fadeIn(tween(duration, easing = easing)) togetherWith fadeOut(tween(duration, easing = easing))
    }
}
