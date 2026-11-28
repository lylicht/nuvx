package app.vyral.night.ui.tv.screens

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.media3.session.MediaController
import app.vyral.night.data.model.BrowseType
import app.vyral.night.data.model.MoodGenre
import app.vyral.night.data.model.MoodGenreSection
import app.vyral.night.data.model.HomeShelf
import app.vyral.night.data.model.ShelfItem
import app.vyral.night.data.model.Song
import app.vyral.night.data.model.UiState
import app.vyral.night.playback.playSongs
import app.vyral.night.ui.MainViewModel
import app.vyral.night.ui.tv.components.TvChromeScrollEffect
import app.vyral.night.ui.tv.components.TvCard
import app.vyral.night.ui.tv.components.TvErrorState
import app.vyral.night.ui.tv.components.TvMoodCard
import app.vyral.night.ui.tv.components.TvShelf
import app.vyral.night.ui.tv.dialogs.TvSongMenu
import app.vyral.night.ui.tv.theme.TvDimensions
import app.vyral.night.ui.tv.theme.TvGlass
import app.vyral.night.ui.tv.theme.TvType
import kotlinx.coroutines.launch

/**
 * Explore, laid out as the phone's: every mood and genre in one grid (the
 * server's sections only decide the order), each a colour-striped duotone
 * sleeve. Choosing one opens its playlists as shelves; Back returns to the grid.
 */
@Composable
fun TvExploreScreen(
    viewModel: MainViewModel,
    mediaController: MediaController?,
    onNavigateToDetail: (browseId: String, title: String, subtitle: String, thumbnailUrl: String?, type: BrowseType) -> Unit,
    onNavigateToNowPlaying: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val selected by viewModel.selectedMoodGenre.collectAsState()
    val scope = rememberCoroutineScope()
    var menuSong by remember { mutableStateOf<Song?>(null) }
    val mood = selected
    if (mood == null) {
        val explore by viewModel.explore.collectAsState()
        val shelves by viewModel.exploreShelves.collectAsState()
        TvMoodGrid(
            state = explore,
            shelves = shelves,
            onCategory = viewModel::openMoodGenre,
            onRetry = { viewModel.loadExplore() },
            onPlaySong = { song ->
                scope.launch { mediaController?.playSongs(listOf(song), 0) }
                onNavigateToNowPlaying()
            },
            onSongMenu = { menuSong = it },
            onNavigateToDetail = onNavigateToDetail,
            modifier = modifier,
        )
    } else {
        BackHandler { viewModel.closeMoodGenre() }
        TvMoodPage(
            mood = mood,
            viewModel = viewModel,
            mediaController = mediaController,
            onNavigateToDetail = onNavigateToDetail,
            onNavigateToNowPlaying = onNavigateToNowPlaying,
            modifier = modifier,
        )
    }
    menuSong?.let { song ->
        TvSongMenu(
            song = song,
            viewModel = viewModel,
            mediaController = mediaController,
            onNavigateToDetail = onNavigateToDetail,
            onDismiss = { menuSong = null },
        )
    }
}

@Composable
private fun TvMoodGrid(
    state: UiState<List<MoodGenreSection>>,
    shelves: List<HomeShelf>,
    onCategory: (MoodGenre) -> Unit,
    onRetry: () -> Unit,
    onPlaySong: (Song) -> Unit,
    onSongMenu: (Song) -> Unit,
    onNavigateToDetail: (browseId: String, title: String, subtitle: String, thumbnailUrl: String?, type: BrowseType) -> Unit,
    modifier: Modifier = Modifier,
) {
    val listState = rememberLazyListState()
    TvChromeScrollEffect(listState)
    when (state) {
        UiState.Loading -> TvShelvesLoading(modifier)
        is UiState.Error -> TvErrorState(message = state.message, onRetry = onRetry, modifier = modifier)
        is UiState.Success -> LazyColumn(
        state = listState,
        modifier = modifier.fillMaxSize(),
        contentPadding = PaddingValues(
            start = TvDimensions.SafeMarginHorizontal,
            end = TvDimensions.SafeMarginHorizontal,
            top = TvDimensions.ContentTop + 3.dp,
            bottom = 38.dp,
        ),
        verticalArrangement = Arrangement.spacedBy(TvDimensions.ShelfSpacing),
    ) {
        items(shelves, key = { "explore-shelf:${it.title}" }) { shelf ->
            TvShelf(
                title = shelf.title,
                subtitle = shelf.subtitle.takeIf(String::isNotBlank),
                items = shelf.items,
                key = { it.videoId ?: it.browseId ?: it.title },
            ) { item ->
                TvCard(
                    title = item.title,
                    subtitle = item.subtitle,
                    artworkUrl = item.thumbnailUrl,
                    cardWidth = 163.dp,
                    onClick = { openShelfItem(item, onPlaySong, onNavigateToDetail) },
                    onLongClick = item.toSong()?.let { song -> { onSongMenu(song) } },
                    playlistBrowseId = item.browseId?.takeIf { browseTypeOf(it) == BrowseType.PLAYLIST },
                )
            }
        }
        items(state.data.filter { it.items.isNotEmpty() }, key = { "explore-mood:${it.title}" }) { section ->
            TvShelf(
                title = section.title,
                items = section.items.distinctBy { it.browseId to it.params },
                key = { "${it.browseId}|${it.params}" },
            ) { item ->
                TvMoodCard(item = item, onClick = { onCategory(item) })
            }
        }
    }
    }
}

@Composable
private fun TvMoodPage(
    mood: MoodGenre,
    viewModel: MainViewModel,
    mediaController: MediaController?,
    onNavigateToDetail: (browseId: String, title: String, subtitle: String, thumbnailUrl: String?, type: BrowseType) -> Unit,
    onNavigateToNowPlaying: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val shelves by viewModel.moodGenreShelves.collectAsState()
    val scope = rememberCoroutineScope()
    var menuSong by remember { mutableStateOf<Song?>(null) }

    when (val state = shelves) {
        UiState.Loading -> TvShelvesLoading(modifier)
        is UiState.Error -> TvErrorState(
            message = state.message,
            onRetry = { viewModel.openMoodGenre(mood) },
            modifier = modifier,
        )
        is UiState.Success -> TvShelfFeed(
            shelves = state.data,
            onPlaySong = { song ->
                scope.launch { mediaController?.playSongs(listOf(song), 0) }
                onNavigateToNowPlaying()
            },
            onSongMenu = { menuSong = it },
            onNavigateToDetail = onNavigateToDetail,
            header = {
                Text(
                    text = mood.title,
                    style = TvType.LargeTitle,
                    color = TvGlass.TextPrimary,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.padding(
                        start = TvDimensions.SafeMarginHorizontal,
                        end = TvDimensions.SafeMarginHorizontal,
                        bottom = 3.dp,
                    ),
                )
            },
            modifier = modifier,
        )
    }

    menuSong?.let { song ->
        TvSongMenu(
            song = song,
            viewModel = viewModel,
            mediaController = mediaController,
            onNavigateToDetail = onNavigateToDetail,
            onDismiss = { menuSong = null },
        )
    }
}

/** The phone's own floor for a mood card's width; the grid fits as many columns as that allows. */
