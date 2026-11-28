package app.vyral.desktop

import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalWindowInfo
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import app.vyral.desktop.recognition.RecognitionDialogPhase
import app.vyral.desktop.recognition.DesktopRecognitionInput
import app.vyral.desktop.recognition.RecognizedSong
import app.vyral.desktop.ui.icons.VyralSymbols
import app.vyral.night.ui.icons.LocalVyralReduceMotion
import app.vyral.night.ui.icons.NyvoIcon as Icon
import coil3.compose.AsyncImage

@Composable
internal fun DesktopSongRecognitionDialog(
    phase: RecognitionDialogPhase,
    result: RecognizedSong?,
    error: String?,
    inputs: List<DesktopRecognitionInput>,
    selectedInput: DesktopRecognitionInput,
    onInputSelected: (DesktopRecognitionInput) -> Unit,
    onContinue: () -> Unit,
    onRetry: () -> Unit,
    onCancel: () -> Unit,
    onFindInSearch: (RecognizedSong) -> Unit,
) {
    val windowInfo = LocalWindowInfo.current
    val isCapturingOrMatching = phase == RecognitionDialogPhase.LISTENING ||
        phase == RecognitionDialogPhase.MATCHING
    LaunchedEffect(windowInfo.isWindowFocused, isCapturingOrMatching) {
        if (isCapturingOrMatching && !windowInfo.isWindowFocused) onCancel()
    }

    AlertDialog(
        onDismissRequest = onCancel,
        icon = {
            Box(
                modifier = Modifier
                    .size(68.dp)
                    .clip(CircleShape)
                    .background(DesktopAccent.copy(alpha = 0.14f)),
                contentAlignment = Alignment.Center,
            ) {
                if (phase == RecognitionDialogPhase.LISTENING || phase == RecognitionDialogPhase.MATCHING) {
                    RecognitionWaveform(phase)
                } else {
                    Icon(
                        VyralSymbols.Recognition,
                        DesktopStrings["desktop_song_recognition_title", "Identify a song"],
                        tint = DesktopAccent,
                        modifier = Modifier.size(28.dp),
                    )
                }
            }
        },
        title = {
            Text(
                text = when (phase) {
                    RecognitionDialogPhase.DISCLOSURE -> DesktopStrings["desktop_song_recognition_title", "Identify a song"]
                    RecognitionDialogPhase.LISTENING -> DesktopStrings["desktop_song_recognition_listening", "Listening for music"]
                    RecognitionDialogPhase.MATCHING -> DesktopStrings["desktop_song_recognition_matching", "Matching song"]
                    RecognitionDialogPhase.FOUND -> DesktopStrings["desktop_song_recognition_found", "Song identified"]
                    RecognitionDialogPhase.NO_MATCH -> DesktopStrings["desktop_song_recognition_no_match", "No match found"]
                    RecognitionDialogPhase.ERROR -> DesktopStrings["desktop_song_recognition_error_title", "Could not identify this song"]
                },
                textAlign = TextAlign.Center,
            )
        },
        text = {
            Column(
                modifier = Modifier.fillMaxWidth(),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                when (phase) {
                    RecognitionDialogPhase.DISCLOSURE -> Column(
                        modifier = Modifier.fillMaxWidth(),
                        verticalArrangement = Arrangement.spacedBy(10.dp),
                    ) {
                        Text(
                            DesktopStrings[
                                "desktop_song_recognition_disclosure",
                                "After you continue, VYRAL listens for up to 10 seconds. It sends an audio fingerprint to Shazam to identify the song; the recording itself is not sent. Recognition only starts when you continue, and you can cancel at any time.",
                            ],
                            textAlign = TextAlign.Center,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                        RecognitionInputChoices(inputs, selectedInput, onInputSelected)
                    }
                    RecognitionDialogPhase.LISTENING -> Text(
                        DesktopStrings["desktop_song_recognition_listening_body", "Keep the music audible to this microphone. Listening stops after 10 seconds or when you cancel."],
                        textAlign = TextAlign.Center,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    RecognitionDialogPhase.MATCHING -> Text(
                        DesktopStrings["desktop_song_recognition_matching_body", "The short fingerprint is being checked by Shazam. The captured PCM buffers have been cleared from memory."],
                        textAlign = TextAlign.Center,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    RecognitionDialogPhase.FOUND -> result?.let { recognized ->
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.Center,
                        ) {
                            AsyncImage(
                                model = recognized.artworkUrl,
                                contentDescription = null,
                                contentScale = ContentScale.Crop,
                                modifier = Modifier
                                    .size(64.dp)
                                    .clip(RoundedCornerShape(8.dp)),
                            )
                            Spacer(Modifier.width(12.dp))
                            Column(Modifier.weight(1f)) {
                                Text(
                                    recognized.title,
                                    style = MaterialTheme.typography.titleMedium,
                                    maxLines = 2,
                                    overflow = TextOverflow.Ellipsis,
                                )
                                Text(
                                    recognized.artist,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis,
                                )
                                recognized.album?.takeIf(String::isNotBlank)?.let { album ->
                                    Text(
                                        album,
                                        style = MaterialTheme.typography.bodySmall,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                                        maxLines = 1,
                                        overflow = TextOverflow.Ellipsis,
                                    )
                                }
                            }
                        }
                    }
                    RecognitionDialogPhase.NO_MATCH -> Text(
                        DesktopStrings["desktop_song_recognition_no_match_body", "No match was found. Try again with the music closer to the microphone and less background noise."],
                        textAlign = TextAlign.Center,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    RecognitionDialogPhase.ERROR -> Text(
                        error ?: DesktopStrings["desktop_song_recognition_failed", "Song recognition failed. Try again."],
                        textAlign = TextAlign.Center,
                        color = MaterialTheme.colorScheme.error,
                    )
                }
            }
        },
        confirmButton = {
            when (phase) {
                RecognitionDialogPhase.DISCLOSURE -> TextButton(onClick = onContinue) {
                    Text(DesktopStrings["desktop_song_recognition_continue", "Continue"])
                }
                RecognitionDialogPhase.FOUND -> result?.let { recognized ->
                    TextButton(onClick = { onFindInSearch(recognized) }) {
                        Text(DesktopStrings["desktop_song_recognition_find_in_search", "Find in Search"])
                    }
                }
                RecognitionDialogPhase.NO_MATCH, RecognitionDialogPhase.ERROR -> TextButton(onClick = onRetry) {
                    Text(DesktopStrings["try_again", "Try again"])
                }
                RecognitionDialogPhase.LISTENING, RecognitionDialogPhase.MATCHING -> Unit
            }
        },
        dismissButton = {
            when (phase) {
                RecognitionDialogPhase.FOUND -> TextButton(onClick = onRetry) {
                    Text(DesktopStrings["try_again", "Try again"])
                }
                else -> TextButton(onClick = onCancel) {
                    Text(DesktopStrings["cancel", "Cancel"])
                }
            }
        },
    )
}

@Composable
private fun RecognitionInputChoices(
    inputs: List<DesktopRecognitionInput>,
    selectedInput: DesktopRecognitionInput,
    onInputSelected: (DesktopRecognitionInput) -> Unit,
) {
    Column(
        modifier = Modifier.fillMaxWidth(),
        verticalArrangement = Arrangement.spacedBy(2.dp),
    ) {
        Text(
            DesktopStrings["desktop_song_recognition_input_heading", "Listen using"],
            style = MaterialTheme.typography.labelLarge,
            color = MaterialTheme.colorScheme.onSurface,
        )
        inputs.forEach { input ->
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .clickable { onInputSelected(input) },
                verticalAlignment = Alignment.CenterVertically,
            ) {
                RadioButton(
                    selected = input.id == selectedInput.id,
                    onClick = { onInputSelected(input) },
                )
                Column(Modifier.weight(1f)) {
                    Text(
                        if (input.systemAudio) {
                            DesktopStrings[
                                "desktop_song_recognition_system_audio",
                                "Current-device playback",
                            ]
                        } else {
                            DesktopStrings["desktop_song_recognition_microphone", "Nearby audio"]
                        },
                        style = MaterialTheme.typography.bodyMedium,
                    )
                    if (input.systemAudio) {
                        Text(
                            input.label,
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
            }
        }
        if (inputs.none(DesktopRecognitionInput::systemAudio)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                RadioButton(selected = false, onClick = null, enabled = false)
                Column {
                    Text(
                        DesktopStrings[
                            "desktop_song_recognition_system_audio_unavailable",
                            "Current-device playback is unavailable",
                        ],
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    Text(
                        DesktopStrings[
                            "desktop_song_recognition_system_audio_help",
                            "This device has not exposed a system audio monitor input.",
                        ],
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
        }
    }
}

@Composable
private fun RecognitionWaveform(phase: RecognitionDialogPhase) {
    val reducedMotion = LocalVyralReduceMotion.current
    if (reducedMotion || phase == RecognitionDialogPhase.MATCHING) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(4.dp)) {
            listOf(12.dp, 23.dp, 30.dp, 20.dp, 10.dp).forEach { height ->
                Box(
                    Modifier
                        .width(4.dp)
                        .height(height)
                        .clip(CircleShape)
                        .background(DesktopAccent),
                )
            }
        }
        return
    }
    val transition = rememberInfiniteTransition(label = "songRecognitionWaveform")
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(4.dp)) {
        repeat(5) { index ->
            val pulse by transition.animateFloat(
                initialValue = 0.25f,
                targetValue = 1f,
                animationSpec = infiniteRepeatable(
                    animation = tween(durationMillis = 420 + index * 65, delayMillis = index * 55),
                    repeatMode = RepeatMode.Reverse,
                ),
                label = "recognitionWave$index",
            )
            Box(
                Modifier
                    .width(4.dp)
                    .height(8.dp + 24.dp * pulse)
                    .clip(CircleShape)
                    .background(DesktopAccent),
            )
        }
    }
}
