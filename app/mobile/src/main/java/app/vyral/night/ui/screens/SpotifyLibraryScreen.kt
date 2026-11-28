package app.vyral.night.ui.screens

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.AlertDialog
import org.jetbrains.compose.resources.painterResource
import app.vyral.night.sharedui.resources.spotify_logo
import app.vyral.night.sharedui.resources.Res
import androidx.compose.foundation.layout.width
import app.vyral.night.ui.icons.NyvoIcon as Icon
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import coil3.compose.AsyncImage
import app.vyral.night.R
import app.vyral.night.data.settings.AppSettings
import app.vyral.night.data.spotify.SpotifyLibrary
import app.vyral.night.data.spotify.SpotifyPlaylist
import app.vyral.night.data.spotify.SpotifyTrack
import app.vyral.night.data.spotify.SpotifyExportCoordinator
import app.vyral.night.data.spotify.SpotifyExportPreview
import kotlinx.coroutines.launch

/**
 * The signed-in Spotify account's playlists. A tap opens one as an ordinary
 * playlist page ([onOpenPlaylist]) — this screen is only the way in.
 */
@Composable
fun SpotifyLibraryScreen(
    onOpenPlaylist: (SpotifyPlaylist) -> Unit,
    onOpenLogin: () -> Unit,
    onOpenTokenSetup: () -> Unit,
    contentPadding: PaddingValues,
    modifier: Modifier = Modifier,
) {
    val cookie by AppSettings.spotifySpdcToken.collectAsStateWithLifecycle()
    var playlists by remember { mutableStateOf<List<SpotifyPlaylist>>(emptyList()) }
    var loading by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }
    var exportPreview by remember { mutableStateOf<SpotifyExportPreview?>(null) }
    var exportBusy by remember { mutableStateOf(false) }
    var exportMessage by remember { mutableStateOf<String?>(null) }
    val scope = rememberCoroutineScope()

    LaunchedEffect(cookie) {
        if (cookie.isBlank()) {
            playlists = emptyList()
            error = null
            return@LaunchedEffect
        }
        loading = true
        error = null
        runCatching { SpotifyLibrary.playlists() }
            .onSuccess { playlists = it }
            .onFailure { error = it.message }
        loading = false
    }

    LazyColumn(
        modifier = modifier.fillMaxSize(),
        contentPadding = contentPadding,
    ) {
        item {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier.padding(start = 20.dp, end = 20.dp, top = 8.dp, bottom = 14.dp),
            ) {
                Icon(
                    painter = painterResource(Res.drawable.spotify_logo),
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.onBackground,
                    modifier = Modifier.size(38.dp),
                )
                Spacer(Modifier.width(12.dp))
                Text(
                    text = stringResource(R.string.spotify),
                    style = MaterialTheme.typography.displayLarge,
                    color = MaterialTheme.colorScheme.onBackground,
                )
            }
        }
        if (cookie.isBlank()) {
            item {
                Column(Modifier.padding(horizontal = 20.dp)) {
                    Text(
                        text = stringResource(R.string.spotify_connect_subtitle),
                        style = MaterialTheme.typography.bodyLarge,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    Spacer(Modifier.height(16.dp))
                    Button(onClick = onOpenLogin) {
                        Text(stringResource(R.string.spotify_sign_in))
                    }
                    Spacer(Modifier.height(8.dp))
                    OutlinedButton(onClick = onOpenTokenSetup) {
                        Text(stringResource(R.string.spotify_use_spdc_token))
                    }
                }
            }
        } else {
            item {
                Column(Modifier.fillMaxWidth().padding(horizontal = 20.dp, vertical = 8.dp)) {
                    OutlinedButton(
                        enabled = !exportBusy,
                        onClick = {
                            exportBusy = true
                            exportMessage = "Preparing a VYRAL export preview…"
                            scope.launch {
                                runCatching { SpotifyExportCoordinator.prepare() }
                                    .onSuccess {
                                        exportPreview = it
                                        exportMessage = null
                                    }
                                    .onFailure { exportMessage = it.message ?: "Could not prepare the Spotify export." }
                                exportBusy = false
                            }
                        },
                        modifier = Modifier.fillMaxWidth(),
                    ) {
                        Text(if (exportBusy) "Preparing export…" else "Export VYRAL library to Spotify")
                    }
                    Text(
                        "Creates private playlists for your VYRAL playlists and monthly Replay, and saves VYRAL liked tracks to Spotify Liked Songs.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(top = 6.dp),
                    )
                    exportMessage?.let { message ->
                        Text(message, color = MaterialTheme.colorScheme.error, modifier = Modifier.padding(top = 6.dp))
                    }
                }
            }
            if (loading) {
                item {
                    Box(Modifier.fillMaxWidth().padding(32.dp), contentAlignment = Alignment.Center) {
                        CircularProgressIndicator()
                    }
                }
            }
            error?.let { message ->
                item {
                    Text(
                        text = message,
                        color = MaterialTheme.colorScheme.error,
                        modifier = Modifier.padding(horizontal = 20.dp, vertical = 8.dp),
                    )
                }
            }
            items(playlists, key = { it.id }) { item ->
                LibraryRow(
                    title = item.name,
                    subtitle = item.owner.orEmpty(),
                    imageUrl = item.imageUrl,
                    onClick = { onOpenPlaylist(item) },
                )
            }
        }
    }

    exportPreview?.let { preview ->
        AlertDialog(
            onDismissRequest = { if (!exportBusy) exportPreview = null },
            title = { Text(if (exportBusy) "Exporting to Spotify" else "Review Spotify export") },
            text = {
                Column {
                    Text("${preview.matchedTracks} matched tracks across ${preview.writes} Spotify changes.")
                    if (preview.unmatchedTracks > 0) {
                        Text("${preview.unmatchedTracks} tracks could not be confidently matched and will be skipped.")
                    }
                    Spacer(Modifier.height(10.dp))
                    Text("VYRAL playlists: ${preview.playlists.count { it.tracks.isNotEmpty() }}")
                    Text("Monthly Replay playlists: ${preview.monthlyReplay.count { it.tracks.isNotEmpty() }}")
                    Text("Tracks to save to Liked Songs: ${preview.likedTracks.size}")
                    preview.playlists.filter { it.tracks.isNotEmpty() }.take(5).forEach {
                        Text("• ${it.name} (${it.tracks.size})", style = MaterialTheme.typography.bodySmall)
                    }
                    if (preview.warnings.isNotEmpty()) {
                        Spacer(Modifier.height(8.dp))
                        preview.warnings.take(3).forEach { warning ->
                            Text(warning, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall)
                        }
                    }
                    if (exportBusy) {
                        Spacer(Modifier.height(12.dp))
                        CircularProgressIndicator()
                    }
                    exportMessage?.let { message ->
                        Spacer(Modifier.height(8.dp))
                        Text(message, color = MaterialTheme.colorScheme.error)
                    }
                }
            },
            confirmButton = {
                TextButton(
                    enabled = !exportBusy && preview.matchedTracks > 0,
                    onClick = {
                        exportBusy = true
                        exportMessage = null
                        scope.launch {
                            runCatching { SpotifyExportCoordinator.export(preview) }
                                .onSuccess { result ->
                                    exportMessage = "Created ${result.playlistsCreated} playlists and saved ${result.likedTracksSaved} tracks."
                                    exportBusy = false
                                    exportPreview = null
                                }
                                .onFailure {
                                    exportMessage = "Export stopped after a Spotify error: ${it.message ?: "unknown failure"}. Earlier changes may already have succeeded; review your Spotify library before retrying."
                                    exportBusy = false
                                    exportPreview = null
                                }
                        }
                    },
                ) { Text(if (exportBusy) "Working…" else "Export") }
            },
            dismissButton = {
                TextButton(enabled = !exportBusy, onClick = { exportPreview = null; exportMessage = null }) {
                    Text("Cancel")
                }
            },
        )
    }
}

@Composable
private fun LibraryRow(
    title: String,
    subtitle: String,
    imageUrl: String?,
    onClick: () -> Unit,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .padding(horizontal = 20.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(14.dp),
    ) {
        AsyncImage(
            model = imageUrl,
            contentDescription = null,
            contentScale = ContentScale.Crop,
            modifier = Modifier.size(52.dp).clip(RoundedCornerShape(8.dp)),
        )
        Column(Modifier.weight(1f)) {
            Text(
                text = title,
                style = MaterialTheme.typography.bodyLarge,
                color = MaterialTheme.colorScheme.onBackground,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            if (subtitle.isNotBlank()) {
                Text(
                    text = subtitle,
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }
    }
}
