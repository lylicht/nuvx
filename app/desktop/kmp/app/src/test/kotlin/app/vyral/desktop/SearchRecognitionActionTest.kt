package app.vyral.desktop

import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material3.MaterialTheme
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.runComposeUiTest
import androidx.compose.ui.unit.dp
import app.vyral.desktop.ui.screens.SearchScreen
import app.vyral.night.data.model.SearchFilter
import app.vyral.night.data.model.UiState
import kotlin.test.Test

@OptIn(ExperimentalTestApi::class)
class SearchRecognitionActionTest {
    @Test
    fun `empty search landing leaves recognition in the global toolbar`() = runComposeUiTest {
        setContent {
            MaterialTheme {
                SearchScreen(
                    query = "",
                    onQueryChange = {},
                    filter = SearchFilter.ALL,
                    currentSong = null,
                    isPlaying = false,
                    onFilterChange = {},
                    results = null,
                    loadingMore = false,
                    onLoadMore = {},
                    listState = rememberLazyListState(),
                    scrollResetTrigger = 0,
                    focusRequested = false,
                    onFocusHandled = {},
                    onSongClick = { _, _ -> },
                    onSongLongPress = {},
                    onSongSwipe = {},
                    onTopResultPlay = {},
                    onTopResultPlaylist = {},
                    onBrowseClick = {},
                    history = emptyList(),
                    suggestions = emptyList(),
                    typeaheadResults = emptyList(),
                    onSubmit = {},
                    onSuggestionClick = {},
                    onHistoryClick = {},
                    onHistoryRemove = {},
                    onHistoryClear = {},
                    contentPadding = PaddingValues(),
                    topPadding = 0.dp,
                )
            }
        }

        onNodeWithText("Identify a song").assertDoesNotExist()
    }

    @Test
    fun `identify music action is hidden while a query has active results`() = runComposeUiTest {
        setContent {
            MaterialTheme {
                SearchScreen(
                    query = "jazz",
                    onQueryChange = {},
                    filter = SearchFilter.ALL,
                    currentSong = null,
                    isPlaying = false,
                    onFilterChange = {},
                    results = UiState.Success(emptyList()),
                    loadingMore = false,
                    onLoadMore = {},
                    listState = rememberLazyListState(),
                    scrollResetTrigger = 0,
                    focusRequested = false,
                    onFocusHandled = {},
                    onSongClick = { _, _ -> },
                    onSongLongPress = {},
                    onSongSwipe = {},
                    onTopResultPlay = {},
                    onTopResultPlaylist = {},
                    onBrowseClick = {},
                    history = emptyList(),
                    suggestions = emptyList(),
                    typeaheadResults = emptyList(),
                    onSubmit = {},
                    onSuggestionClick = {},
                    onHistoryClick = {},
                    onHistoryRemove = {},
                    onHistoryClear = {},
                    contentPadding = PaddingValues(),
                    topPadding = 0.dp,
                )
            }
        }

        onNodeWithText("Identify a song").assertDoesNotExist()
    }
}
