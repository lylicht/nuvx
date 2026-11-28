package app.vyral.night.ui

import android.Manifest
import android.os.Build
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalContext
import app.vyral.night.data.LocalMediaRepository
import app.vyral.night.ui.rememberIsForeground

internal data class LocalMusicAccess(
    val granted: Boolean,
    val request: () -> Unit,
    val refreshPermission: () -> Unit,
)

/** Shared permission, foreground refresh and MediaStore observation for phone and TV. */
@Composable
internal fun rememberLocalMusicAccess(viewModel: MainViewModel): LocalMusicAccess {
    val context = LocalContext.current
    var granted by remember(context) {
        mutableStateOf(LocalMediaRepository.hasStoragePermission(context))
    }
    val foreground = rememberIsForeground()
    val refreshPermission = {
        val current = LocalMediaRepository.hasStoragePermission(context)
        if (granted != current) {
            granted = current
            if (current) viewModel.loadLibrarySongs()
            viewModel.onLocalMediaChanged()
        }
    }
    val permission = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
        Manifest.permission.READ_MEDIA_AUDIO
    } else {
        Manifest.permission.READ_EXTERNAL_STORAGE
    }
    val launcher = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { allowed ->
        granted = allowed && LocalMediaRepository.hasStoragePermission(context)
        if (granted) viewModel.loadLibrarySongs()
        viewModel.onLocalMediaChanged()
    }

    LaunchedEffect(foreground) {
        if (foreground) {
            refreshPermission()
            viewModel.onLocalMediaChanged()
        }
    }
    DisposableEffect(context, foreground, granted, viewModel) {
        val observer = if (foreground && granted) {
            LocalMediaRepository.observeChanges(context, viewModel::onLocalMediaChanged)
        } else null
        onDispose { observer?.close() }
    }

    return LocalMusicAccess(
        granted = granted,
        request = { launcher.launch(permission) },
        refreshPermission = refreshPermission,
    )
}
