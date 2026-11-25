package com.licht.nuvx.playback

import androidx.media3.common.C
import androidx.media3.common.MediaItem
import androidx.media3.common.Timeline
import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.ExoPlayer
import com.licht.nuvx.playback.smart.TransitionTrackInfo

@UnstableApi
internal class CrossfadeTrackInfoResolver(
    private val active: () -> ExoPlayer,
) {
    fun nextItemDurationMs(nextIndex: Int, item: MediaItem): Long {
        val timeline = active().currentTimeline
        if (!timeline.isEmpty) {
            timeline.getWindow(nextIndex, Timeline.Window()).durationMs
                .takeIf { it != C.TIME_UNSET && it > 0 }
                ?.let { return it }
        }
        return queuedDurationMs(item)
    }

    fun queuedDurationMs(item: MediaItem): Long {
        val uri = item.localConfiguration?.uri ?: return 0L
        val seconds = runCatching { uri.getQueryParameter("d") }.getOrNull()?.toLongOrNull() ?: return 0L
        return if (seconds > 0) seconds * 1000L else 0L
    }
}

internal fun MediaItem.toTransitionInfo(durationMs: Long) = TransitionTrackInfo(
    id = mediaId,
    durationMs = durationMs,
    title = mediaMetadata.title?.toString().orEmpty(),
    artist = mediaMetadata.artist?.toString().orEmpty(),
    album = mediaMetadata.albumTitle?.toString().orEmpty(),
    albumId = mediaMetadata.extras?.getString(EXTRA_ALBUM_ID).orEmpty(),
)

internal fun MediaItem.isSameAlbumAs(other: MediaItem): Boolean {
    val id = mediaMetadata.extras?.getString(EXTRA_ALBUM_ID).orEmpty()
    val otherId = other.mediaMetadata.extras?.getString(EXTRA_ALBUM_ID).orEmpty()
    if (id.isNotBlank() && id == otherId) return true
    val album = mediaMetadata.albumTitle?.toString().orEmpty()
    val otherAlbum = other.mediaMetadata.albumTitle?.toString().orEmpty()
    val artist = mediaMetadata.artist?.toString().orEmpty()
    val otherArtist = other.mediaMetadata.artist?.toString().orEmpty()
    return album.isNotBlank() && album == otherAlbum && artist.isNotBlank() && artist == otherArtist
}
