package app.vyral.desktop.ui.screens

import app.vyral.night.ui.icons.NyvoIconButton as IconButton

import app.vyral.night.ui.icons.NyvoIcon as Icon

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.GridItemSpan
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import app.vyral.desktop.ui.icons.VyralSymbols
import app.vyral.desktop.ui.components.VyralLogo
import app.vyral.night.data.YtMusicRepository
import app.vyral.night.data.innertube.Innertube
import app.vyral.night.data.model.HomeFeed
import app.vyral.night.data.model.SearchFilter
import app.vyral.night.data.model.SearchResult
import app.vyral.night.data.model.ShelfLayout
import app.vyral.night.data.model.Song
import coil3.compose.AsyncImage
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

@Composable
fun VyralVideosPage(
    onMusic: () -> Unit,
    onVideo: (Song) -> Unit,
    onSettings: (() -> Unit)? = null,
    modifier: Modifier = Modifier,
) {
    var query by rememberSaveable { mutableStateOf("") }
    var submitted by rememberSaveable { mutableStateOf("") }
    var videos by remember { mutableStateOf<List<Song>>(emptyList()) }
    var continuation by remember { mutableStateOf<String?>(null) }
    var failure by remember { mutableStateOf<String?>(null) }
    var loading by remember { mutableStateOf(true) }
    var suggestions by remember { mutableStateOf<List<String>>(emptyList()) }
    var refresh by remember { mutableIntStateOf(0) }
    val scope = rememberCoroutineScope()

    fun submit(value: String) {
        query = value.trim()
        submitted = query
        suggestions = emptyList()
        refresh++
    }

    LaunchedEffect(submitted, refresh) {
        loading = true
        failure = null
        continuation = null
        val result = if (submitted.isBlank()) {
            YtMusicRepository.home().map(::videoRecommendations)
        } else {
            YtMusicRepository.searchPage(submitted, SearchFilter.VIDEOS).map { page ->
                continuation = page.continuation
                page.rows.filterIsInstance<SearchResult.Track>().map { it.song }.filter(Song::isVideo)
            }
        }
        result.onSuccess { videos = it }.onFailure { failure = it.message ?: "Videos could not be loaded." }
        loading = false
    }

    LaunchedEffect(query, submitted) {
        suggestions = emptyList()
        if (query.isNotBlank() && query != submitted) {
            delay(280)
            suggestions = YtMusicRepository.searchSuggestions(query).getOrDefault(emptyList())
        }
    }

    Surface(modifier = modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background, contentColor = MaterialTheme.colorScheme.onBackground) {
        Column(Modifier.fillMaxSize().windowInsetsPadding(WindowInsets.safeDrawing)) {
            Row(
                Modifier.fillMaxWidth().padding(horizontal = 20.dp, vertical = 10.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                VyralLogo(Modifier.size(44.dp))
                Text("VYRAL", style = MaterialTheme.typography.headlineMedium, modifier = Modifier.weight(1f))
                FilterChip(selected = false, onClick = onMusic, label = { Text("Music") })
                FilterChip(selected = true, onClick = {}, label = { Text("Videos") })
                onSettings?.let { IconButton(onClick = it) { Icon(VyralSymbols.Settings, "Settings") } }
            }
            Row(Modifier.fillMaxWidth().padding(horizontal = 20.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedTextField(
                    value = query,
                    onValueChange = { query = it },
                    modifier = Modifier.weight(1f),
                    singleLine = true,
                    placeholder = { Text("Search music videos") },
                    shape = RoundedCornerShape(24.dp),
                    leadingIcon = { Icon(VyralSymbols.Search, null) },
                    keyboardOptions = androidx.compose.foundation.text.KeyboardOptions(imeAction = androidx.compose.ui.text.input.ImeAction.Search),
                    keyboardActions = androidx.compose.foundation.text.KeyboardActions(onSearch = { submit(query) }),
                )
                Button(onClick = { submit(query) }) { Text("Search") }
            }
            if (suggestions.isNotEmpty()) {
                Column(Modifier.fillMaxWidth().padding(horizontal = 24.dp)) {
                    suggestions.forEach { suggestion ->
                        TextButton(onClick = { submit(suggestion) }, modifier = Modifier.fillMaxWidth()) {
                            Text(suggestion, modifier = Modifier.fillMaxWidth())
                        }
                    }
                }
            }
            Text(
                if (submitted.isBlank()) "Recommended videos" else "Search results",
                style = MaterialTheme.typography.titleMedium,
                modifier = Modifier.padding(horizontal = 24.dp, vertical = 12.dp),
            )
            when {
                loading && videos.isEmpty() -> Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) { CircularProgressIndicator() }
                failure != null -> Column(Modifier.fillMaxSize().padding(32.dp), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.Center) {
                    Text(failure.orEmpty(), style = MaterialTheme.typography.bodyLarge)
                    TextButton(onClick = { refresh++ }) { Text("Retry") }
                }
                videos.isEmpty() -> Column(Modifier.fillMaxSize().padding(32.dp), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.Center) {
                    VyralLogo(Modifier.size(88.dp), contentDescription = null)
                    Text(if (submitted.isBlank()) "No video recommendations are available." else "No music videos found.")
                }
                else -> LazyVerticalGrid(
                    GridCells.Adaptive(250.dp),
                    modifier = Modifier.fillMaxSize(),
                    contentPadding = PaddingValues(20.dp),
                    verticalArrangement = Arrangement.spacedBy(22.dp),
                    horizontalArrangement = Arrangement.spacedBy(16.dp),
                ) {
                    items(videos, key = { it.videoId }) { video ->
                        var focused by remember { mutableStateOf(false) }
                        Column(
                            Modifier.onFocusChanged { focused = it.hasFocus }
                                .clip(RoundedCornerShape(16.dp))
                                .border(if (focused) 3.dp else 0.dp, if (focused) MaterialTheme.colorScheme.primary else Color.Transparent, RoundedCornerShape(16.dp))
                                .clickable { onVideo(video) },
                        ) {
                            Box(Modifier.fillMaxWidth().aspectRatio(16f / 9f).background(MaterialTheme.colorScheme.surfaceVariant)) {
                                video.thumbnailUrl?.let { url -> AsyncImage(url, video.title, Modifier.fillMaxSize(), contentScale = ContentScale.Crop) }
                                video.durationText?.takeIf(String::isNotBlank)?.let { duration ->
                                    Text(duration, color = Color.White, style = MaterialTheme.typography.labelMedium, modifier = Modifier.align(Alignment.BottomEnd).padding(8.dp).clip(RoundedCornerShape(5.dp)).background(Color.Black.copy(alpha = .8f)).padding(horizontal = 5.dp, vertical = 2.dp))
                                }
                            }
                            Text(video.title, style = MaterialTheme.typography.titleMedium, maxLines = 2, overflow = TextOverflow.Ellipsis, modifier = Modifier.padding(horizontal = 8.dp, vertical = 6.dp))
                            Text(video.artist, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 2, modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp))
                        }
                    }
                    continuation?.let { token ->
                        item(span = { GridItemSpan(maxLineSpan) }) {
                            TextButton(enabled = !loading, modifier = Modifier.fillMaxWidth(), onClick = {
                                scope.launch {
                                    loading = true
                                    val result = if (submitted.isBlank()) {
                                        YtMusicRepository.moreHome(token).map(::videoRecommendations)
                                    } else {
                                        YtMusicRepository.searchContinuation(token, SearchFilter.VIDEOS).map { page ->
                                            continuation = page.continuation
                                            page.rows.filterIsInstance<SearchResult.Track>().map { it.song }.filter(Song::isVideo)
                                        }
                                    }
                                    result.onSuccess { videos = (videos + it).distinctBy(Song::videoId) }.onFailure { failure = it.message ?: "More videos could not be loaded." }
                                    loading = false
                                }
                            }) { Text(if (loading) "Loading…" else "More videos") }
                        }
                    }
                }
            }
        }
    }
}

private fun videoRecommendations(feed: HomeFeed): List<Song> = feed.shelves
    .filter { it.layout == ShelfLayout.VIDEO || it.items.any { item -> item.isVideo } }
    .flatMap { it.items }
    .filter { it.isVideo && it.videoId != null }
    .map { item ->
        Song(
            videoId = requireNotNull(item.videoId),
            title = item.title,
            artist = item.subtitle,
            thumbnailUrl = item.thumbnailUrl,
            isVideo = true,
        )
    }
    .distinctBy(Song::videoId)
