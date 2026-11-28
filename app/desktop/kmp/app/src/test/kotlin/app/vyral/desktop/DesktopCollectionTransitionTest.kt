package app.vyral.desktop

import app.vyral.night.data.model.BrowseType
import kotlin.test.Test
import kotlin.test.assertEquals

class DesktopCollectionTransitionTest {
    @Test
    fun collectionIdentityDoesNotChangeWhenThePageFinishesLoading() {
        val loading = DesktopCollection(
            browseId = "VLPLplaylist",
            title = "Mix",
            subtitle = "",
            thumbnailUrl = null,
            type = BrowseType.PLAYLIST,
            songs = emptyList(),
            loading = true,
        )
        val loaded = loading.copy(songs = emptyList(), loading = false)

        assertEquals(
            desktopCollectionTransitionKey(loading),
            desktopCollectionTransitionKey(loaded),
        )
    }

    @Test
    fun reducedMotionRemovesRouteAnimationDuration() {
        assertEquals(0, desktopCollectionTransitionDuration(reduceMotion = true))
        assertEquals(DESKTOP_COLLECTION_TRANSITION_MILLIS, desktopCollectionTransitionDuration(reduceMotion = false))
    }
}
