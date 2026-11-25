package com.licht.nuvx.ui.components

import android.content.Intent
import android.net.Uri
import android.content.Context
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import com.licht.nuvx.R
import com.licht.nuvx.data.scrobbling.LastFM
import com.licht.nuvx.data.settings.AppSettings
import dev.chrisbanes.haze.HazeState
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch

@Composable
internal fun ScrobbleLoginDialogs(
    hazeState: HazeState,
    showListenBrainzLogin: Boolean,
    listenBrainzToken: String,
    onListenBrainzSave: (String) -> Unit,
    onListenBrainzDismiss: () -> Unit,
    showLastfmLogin: Boolean,
    onLastfmSession: (String, String) -> Unit,
    onLastfmDismiss: () -> Unit,
    scope: CoroutineScope,
    context: Context,
) {
    if (showListenBrainzLogin) {
        var tokenInput by remember { mutableStateOf(listenBrainzToken) }
        ListenBrainzTokenAlert(
            hazeState = hazeState,
            tokenInput = tokenInput,
            onTokenInputChange = { tokenInput = it },
            onSave = { onListenBrainzSave(tokenInput.trim()) },
            onDismiss = onListenBrainzDismiss,
        )
    }

    if (showLastfmLogin) {
        var lastfmToken by remember { mutableStateOf<String?>(null) }
        var lastfmError by remember { mutableStateOf<String?>(null) }
        var lastfmLoading by remember { mutableStateOf(false) }
        LastfmLoginAlert(
            hazeState = hazeState,
            authorizationPending = lastfmToken != null,
            error = lastfmError,
            loading = lastfmLoading,
            onAuthorize = {
                lastfmLoading = true
                lastfmError = null
                scope.launch {
                    try {
                        LastFM.initialize(
                            apiKey = AppSettings.lastfmApiKey.value,
                            secret = AppSettings.lastfmSecret.value,
                        )
                        val token = LastFM.getToken().getOrThrow().token
                        lastfmToken = token
                        context.startActivity(
                            Intent(Intent.ACTION_VIEW, Uri.parse(LastFM.getAuthUrl(token)))
                                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
                        )
                    } catch (e: Exception) {
                        lastfmError = e.message ?: context.getString(R.string.login_failed)
                    } finally {
                        lastfmLoading = false
                    }
                }
            },
            onComplete = {
                val token = lastfmToken ?: return@LastfmLoginAlert
                lastfmLoading = true
                lastfmError = null
                scope.launch {
                    try {
                        LastFM.initialize(
                            apiKey = AppSettings.lastfmApiKey.value,
                            secret = AppSettings.lastfmSecret.value,
                        )
                        LastFM.getSession(token)
                            .onSuccess { auth ->
                                onLastfmSession(auth.session.key, auth.session.name)
                            }
                            .onFailure { e ->
                                lastfmError = if (e is LastFM.LastFmException && (e.code == 14 || e.code == 4)) {
                                    context.getString(R.string.lastfm_oauth_error_pending)
                                } else {
                                    e.message ?: context.getString(R.string.login_failed)
                                }
                            }
                    } catch (e: Exception) {
                        lastfmError = e.message ?: context.getString(R.string.login_failed)
                    } finally {
                        lastfmLoading = false
                    }
                }
            },
            onDismiss = { if (!lastfmLoading) onLastfmDismiss() },
        )
    }
}
