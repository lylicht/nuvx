package app.vyral.night.recognition

import androidx.activity.compose.BackHandler
import android.Manifest
import android.app.Activity
import android.content.Context
import android.content.Intent
import android.media.projection.MediaProjectionManager
import android.net.Uri
import android.os.Build
import android.provider.Settings
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.zIndex
import androidx.core.content.ContextCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import app.vyral.night.R
import app.vyral.night.data.settings.AppSettings
import app.vyral.night.ui.haptics.Haptic
import app.vyral.night.ui.haptics.rememberHaptics
import app.vyral.night.ui.icons.SfIcons
import app.vyral.night.ui.icons.NyvoIcon as Icon
import app.vyral.night.ui.components.AlertAction
import app.vyral.night.ui.components.AlertRule
import app.vyral.night.ui.components.AlertScaffold
import coil3.compose.AsyncImage
import dev.chrisbanes.haze.HazeState
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch

private typealias RecognitionCapture = suspend (onProgress: (Long) -> Unit) -> ByteArray

@Composable
internal fun SongRecognitionDialog(
    hazeState: HazeState? = null,
    onDismiss: () -> Unit,
    onFindInSearch: (String) -> Unit,
) {
    val context = LocalContext.current
    val lifecycleOwner = LocalLifecycleOwner.current
    val pipeline = remember(context) { newAndroidRecognitionPipeline(context.applicationContext) }
    val state by pipeline.state.collectAsState()
    val scope = rememberCoroutineScope()
    val haptics = rememberHaptics()
    val reduceMotion by AppSettings.reduceAnimation.collectAsState()
    var consentGiven by rememberSaveable { mutableStateOf(false) }
    var recognitionJob by remember { mutableStateOf<Job?>(null) }
    val captureModes = remember(context) { recognitionCaptureModes(context) }
    var selectedModeName by rememberSaveable {
        mutableStateOf(captureModes.firstOrNull()?.name ?: RecognitionCaptureMode.MICROPHONE.name)
    }
    val selectedMode = captureModes.firstOrNull { it.name == selectedModeName } ?: captureModes.firstOrNull()

    val runRecognition: (RecognitionCapture?) -> Unit = { captureAudio ->
        recognitionJob?.cancel()
        pipeline.reset()
        haptics.play(Haptic.Select)
        recognitionJob = scope.launch {
            if (captureAudio == null) pipeline.recognize() else pipeline.recognize(captureAudio)
        }
    }
    val projectionLauncher = rememberLauncherForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
        val resultData = result.data
        if (result.resultCode == Activity.RESULT_OK && resultData != null && Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            val captureAudio: RecognitionCapture = { progress ->
                captureDeviceAudioPcm(context, result.resultCode, resultData, progress)
            }
            runRecognition(captureAudio)
        } else {
            pipeline.setDeviceCaptureConsentDenied()
        }
    }
    val permissionLauncher = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        if (!granted) pipeline.setPermissionDenied()
        else if (selectedMode == RecognitionCaptureMode.DEVICE_AUDIO && Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            val projectionManager = context.getSystemService(Context.MEDIA_PROJECTION_SERVICE) as? MediaProjectionManager
            projectionManager?.let { projectionLauncher.launch(it.createScreenCaptureIntent()) }
                ?: pipeline.setAudioUnavailable()
        } else {
            runRecognition(null)
        }
    }
    val begin: () -> Unit = {
        if (ContextCompat.checkSelfPermission(context, Manifest.permission.RECORD_AUDIO) == android.content.pm.PackageManager.PERMISSION_GRANTED) {
            if (selectedMode == RecognitionCaptureMode.DEVICE_AUDIO && Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                val projectionManager = context.getSystemService(Context.MEDIA_PROJECTION_SERVICE) as? MediaProjectionManager
                projectionManager?.let { projectionLauncher.launch(it.createScreenCaptureIntent()) }
                    ?: pipeline.setAudioUnavailable()
            } else {
                runRecognition(null)
            }
        } else {
            permissionLauncher.launch(Manifest.permission.RECORD_AUDIO)
        }
    }
    val close: () -> Unit = {
        recognitionJob?.cancel()
        pipeline.reset()
        haptics.play(Haptic.Select)
        onDismiss()
    }

    DisposableEffect(lifecycleOwner, pipeline) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_STOP && recognitionJob?.isActive == true) close()
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose {
            lifecycleOwner.lifecycle.removeObserver(observer)
            recognitionJob?.cancel()
            pipeline.reset()
        }
    }

    if (captureModes.isEmpty()) {
        RecognitionAlert(hazeState, close) {
            RecognitionAlertText(
                title = stringResource(R.string.recognize_audio_unavailable),
                body = stringResource(R.string.recognize_no_capture_mode),
            )
            AlertRule()
            AlertAction(stringResource(R.string.cancel), emphasised = true, onClick = close)
        }
        return
    }

    if (!consentGiven) {
        RecognitionAlert(hazeState, close) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .heightIn(max = 400.dp)
                    .verticalScroll(rememberScrollState())
                    .padding(horizontal = 12.dp, vertical = 16.dp),
                verticalArrangement = Arrangement.spacedBy(4.dp),
            ) {
                Text(
                    stringResource(R.string.identify_song),
                    style = MaterialTheme.typography.bodyLarge,
                    color = MaterialTheme.colorScheme.onSurface,
                    textAlign = TextAlign.Center,
                    modifier = Modifier.fillMaxWidth(),
                )
                captureModes.forEach { mode ->
                    val selected = selectedMode == mode
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .selectable(selected, role = Role.RadioButton) { selectedModeName = mode.name },
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        RadioButton(selected = selected, onClick = { selectedModeName = mode.name })
                        Column {
                            Text(
                                stringResource(if (mode == RecognitionCaptureMode.MICROPHONE) R.string.recognize_nearby_music else R.string.recognize_device_audio),
                                color = MaterialTheme.colorScheme.onSurface,
                            )
                            Text(
                                stringResource(
                                    if (mode == RecognitionCaptureMode.MICROPHONE) R.string.recognize_nearby_music_description
                                    else R.string.recognize_device_audio_description,
                                ),
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                    }
                }
                Text(
                    stringResource(
                        if (selectedMode == RecognitionCaptureMode.DEVICE_AUDIO) R.string.recognize_device_audio_disclosure
                        else R.string.recognize_disclosure_body,
                    ),
                    color = MaterialTheme.colorScheme.onSurface,
                )
            }
            AlertRule()
            AlertAction(
                label = stringResource(R.string.recognize_continue),
                emphasised = true,
                onClick = { consentGiven = true; begin() },
            )
            AlertRule()
            AlertAction(stringResource(R.string.cancel), emphasised = false, onClick = close)
        }
        return
    }

    when (val current = state) {
        is RecognitionState.Found -> RecognitionAlert(hazeState, close) {
            Column(
                modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 18.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
            ) {
                current.song.artworkUrl?.let { artwork ->
                    AsyncImage(
                        model = artwork,
                        contentDescription = stringResource(R.string.recognize_artwork_description),
                        modifier = Modifier.size(120.dp).clip(RoundedCornerShape(14.dp)),
                    )
                }
                Text(
                    current.song.title,
                    modifier = Modifier.padding(top = 12.dp),
                    style = MaterialTheme.typography.bodyLarge,
                    color = MaterialTheme.colorScheme.onSurface,
                    textAlign = TextAlign.Center,
                )
                Text(
                    current.song.artist,
                    modifier = Modifier.padding(top = 3.dp),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    textAlign = TextAlign.Center,
                )
            }
            AlertRule()
            AlertAction(
                label = stringResource(R.string.recognize_find_in_search),
                emphasised = true,
                onClick = {
                    val query = current.song.searchQuery()
                    close()
                    onFindInSearch(query)
                },
            )
            AlertRule()
            AlertAction(stringResource(R.string.try_again), emphasised = false, onClick = begin)
            AlertRule()
            AlertAction(stringResource(R.string.cancel), emphasised = false, onClick = close)
        }

        RecognitionState.NoMatch -> RecognitionMessageDialog(
            hazeState = hazeState,
            title = stringResource(R.string.recognize_no_match),
            body = stringResource(
                if (selectedMode == RecognitionCaptureMode.DEVICE_AUDIO) R.string.recognize_device_audio_no_match_body
                else R.string.recognize_no_match_body,
            ),
            onDismiss = close,
            onRetry = begin,
            onUseMicrophone = if (selectedMode == RecognitionCaptureMode.DEVICE_AUDIO && RecognitionCaptureMode.MICROPHONE in captureModes) {
                { pipeline.reset(); selectedModeName = RecognitionCaptureMode.MICROPHONE.name; consentGiven = false }
            } else null,
        )

        RecognitionState.PermissionDenied -> RecognitionAlert(hazeState, close) {
            RecognitionAlertText(
                title = stringResource(R.string.recognize_permission_title),
                body = stringResource(
                    if (selectedMode == RecognitionCaptureMode.DEVICE_AUDIO) R.string.recognize_device_audio_permission_body
                    else R.string.recognize_permission_body,
                ),
            )
            AlertRule()
            AlertAction(
                label = stringResource(if (selectedMode == RecognitionCaptureMode.DEVICE_AUDIO) R.string.recognize_allow_audio_capture else R.string.recognize_allow_microphone),
                emphasised = true,
                onClick = begin,
            )
            AlertRule()
            AlertAction(
                label = stringResource(R.string.recognize_open_settings),
                emphasised = false,
                onClick = {
                    context.startActivity(
                        Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS).apply {
                            data = Uri.fromParts("package", context.packageName, null)
                            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                        },
                    )
                },
            )
            AlertRule()
            AlertAction(stringResource(R.string.cancel), emphasised = false, onClick = close)
        }

        RecognitionState.DeviceCaptureConsentDenied -> RecognitionMessageDialog(
            hazeState = hazeState,
            title = stringResource(R.string.recognize_device_audio_consent_denied_title),
            body = stringResource(R.string.recognize_device_audio_consent_denied_body),
            onDismiss = close,
            onRetry = begin,
            onUseMicrophone = if (RecognitionCaptureMode.MICROPHONE in captureModes) {
                { pipeline.reset(); selectedModeName = RecognitionCaptureMode.MICROPHONE.name; consentGiven = false }
            } else null,
        )

        RecognitionState.AudioUnavailable -> RecognitionMessageDialog(
            hazeState = hazeState,
            title = stringResource(R.string.recognize_audio_unavailable),
            body = stringResource(
                if (selectedMode == RecognitionCaptureMode.DEVICE_AUDIO) R.string.recognize_device_audio_unavailable_body
                else R.string.recognize_audio_unavailable_body,
            ),
            onDismiss = close,
            onRetry = begin,
            onUseMicrophone = if (selectedMode == RecognitionCaptureMode.DEVICE_AUDIO && RecognitionCaptureMode.MICROPHONE in captureModes) {
                { pipeline.reset(); selectedModeName = RecognitionCaptureMode.MICROPHONE.name; consentGiven = false }
            } else null,
        )

        RecognitionState.NetworkError -> RecognitionMessageDialog(
            hazeState = hazeState,
            title = stringResource(R.string.recognize_lookup_failed),
            body = stringResource(R.string.recognize_lookup_failed_body),
            onDismiss = close,
            onRetry = begin,
        )

        RecognitionState.Idle,
        RecognitionState.Matching,
        is RecognitionState.Listening -> {
            val elapsed = (current as? RecognitionState.Listening)?.elapsedMs ?: 0L
            val matching = current == RecognitionState.Matching
            RecognitionAlert(hazeState, close) {
                Column(
                    modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 18.dp),
                    horizontalAlignment = Alignment.CenterHorizontally,
                ) {
                    if (matching) {
                        CircularProgressIndicator(modifier = Modifier.padding(10.dp).size(48.dp), strokeWidth = 3.dp)
                    } else {
                        ListeningPulse(reduceMotion = reduceMotion)
                    }
                    Text(
                        stringResource(
                            if (matching) R.string.recognize_matching
                            else if (selectedMode == RecognitionCaptureMode.DEVICE_AUDIO) R.string.recognize_device_audio_listening
                            else R.string.recognize_listening,
                        ),
                        modifier = Modifier.padding(top = 10.dp),
                        style = MaterialTheme.typography.bodyLarge,
                        color = MaterialTheme.colorScheme.onSurface,
                        textAlign = TextAlign.Center,
                    )
                    Text(
                        if (matching) stringResource(R.string.recognize_matching_body)
                        else stringResource(
                            if (selectedMode == RecognitionCaptureMode.DEVICE_AUDIO) R.string.recognize_device_audio_progress
                            else R.string.recognize_listening_progress,
                            ((elapsed + 999) / 1_000).toInt().coerceAtMost(10),
                        ),
                        modifier = Modifier.padding(top = 4.dp),
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        textAlign = TextAlign.Center,
                    )
                }
                AlertRule()
                AlertAction(stringResource(R.string.cancel), emphasised = true, onClick = close)
            }
        }
    }
}

@Composable
private fun RecognitionMessageDialog(
    hazeState: HazeState?,
    title: String,
    body: String,
    onDismiss: () -> Unit,
    onRetry: () -> Unit,
    onUseMicrophone: (() -> Unit)? = null,
) {
    RecognitionAlert(hazeState, onDismiss) {
        RecognitionAlertText(title, body)
        AlertRule()
        onUseMicrophone?.let { fallback ->
            AlertAction(
                stringResource(R.string.recognize_use_nearby_microphone),
                emphasised = false,
                onClick = fallback,
            )
            AlertRule()
        }
        AlertAction(stringResource(R.string.try_again), emphasised = true, onClick = onRetry)
        AlertRule()
        AlertAction(stringResource(R.string.cancel), emphasised = false, onClick = onDismiss)
    }
}

@Composable
private fun RecognitionAlertText(title: String, body: String) {
    Column(
        modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 19.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Text(
            title,
            style = MaterialTheme.typography.bodyLarge,
            color = MaterialTheme.colorScheme.onSurface,
            textAlign = TextAlign.Center,
        )
        Text(
            body,
            modifier = Modifier.padding(top = 4.dp),
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurface,
            textAlign = TextAlign.Center,
        )
    }
}

@Composable
private fun RecognitionAlert(
    hazeState: HazeState?,
    onDismiss: () -> Unit,
    content: @Composable androidx.compose.foundation.layout.ColumnScope.() -> Unit,
) {
    BackHandler(onBack = onDismiss)
    AlertScaffold(
        hazeState = remember(hazeState) { hazeState ?: HazeState() },
        onDismiss = onDismiss,
        frosted = hazeState != null,
        modifier = Modifier.zIndex(100f),
        content = content,
    )
}

@Composable
private fun ListeningPulse(reduceMotion: Boolean) {
    val pulse = if (reduceMotion) 1f else rememberInfiniteTransition(label = "songRecognitionPulse")
        .animateFloat(
            initialValue = 1f,
            targetValue = 1.28f,
            animationSpec = infiniteRepeatable(tween(760, easing = LinearEasing), RepeatMode.Reverse),
            label = "songRecognitionPulseScale",
        ).value
    Box(
        modifier = Modifier
            .size(82.dp)
            .graphicsLayer { scaleX = pulse; scaleY = pulse }
            .background(MaterialTheme.colorScheme.primary.copy(alpha = if (reduceMotion) 0.16f else 0.12f), CircleShape),
        contentAlignment = Alignment.Center,
    ) {
        Surface(
            modifier = Modifier.size(62.dp),
            shape = CircleShape,
            color = MaterialTheme.colorScheme.primary,
            contentColor = MaterialTheme.colorScheme.onPrimary,
        ) {
            Box(contentAlignment = Alignment.Center) {
                Icon(
                    imageVector = SfIcons.Recognize,
                    contentDescription = null,
                    modifier = Modifier.size(30.dp),
                )
            }
        }
    }
}
