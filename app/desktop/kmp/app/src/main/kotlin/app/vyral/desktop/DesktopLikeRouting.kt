package app.vyral.desktop

import app.vyral.night.data.model.LikeStatus
import app.vyral.night.data.model.Song

/** The local view of ratings after an action. */
internal data class DesktopRatingState(
    val likedIds: Set<String>,
    val dislikedIds: Set<String>,
)

/**
 * Saves a rating on this device first. Only catalogue video IDs can be sent to the signed-in
 * YouTube account; local file identities such as `local:C:\\Music\\track.mp3` are app-only.
 */
internal fun applyDesktopRating(
    song: Song,
    status: LikeStatus,
    likedIds: Set<String>,
    dislikedIds: Set<String>,
    persistence: DesktopPersistence,
    sendAccountRating: (String, LikeStatus) -> Unit,
): DesktopRatingState {
    val updatedLikes = if (status == LikeStatus.LIKE) likedIds + song.videoId else likedIds - song.videoId
    val updatedDislikes = if (status == LikeStatus.DISLIKE) {
        dislikedIds + song.videoId
    } else {
        dislikedIds - song.videoId
    }

    persistence.saveLikedIds(updatedLikes)
    persistence.saveDislikedIds(updatedDislikes)

    if (DesktopSearchClient.isVideoId(song.videoId)) {
        sendAccountRating(song.videoId, status)
    }

    return DesktopRatingState(updatedLikes, updatedDislikes)
}
