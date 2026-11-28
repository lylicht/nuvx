package app.vyral.night.data

import app.vyral.night.data.model.LikeStatus
import app.vyral.night.data.model.Song

/** Routes favorites to the store that owns a song's identity. */
object LikeRouting {
    fun status(song: Song, locallyLiked: Set<String>, accountStatus: LikeStatus): LikeStatus {
        val localUri = song.localUri ?: return accountStatus
        return if (localUri in locallyLiked) LikeStatus.LIKE else LikeStatus.INDIFFERENT
    }

    fun toggle(
        song: Song,
        onDeviceToggle: (Song) -> Boolean,
        onAccountToggle: (String) -> Unit,
    ) {
        if (song.localUri != null) onDeviceToggle(song) else onAccountToggle(song.videoId)
    }
}
