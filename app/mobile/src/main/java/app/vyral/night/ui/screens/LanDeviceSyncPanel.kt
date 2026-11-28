package app.vyral.night.ui.screens

import android.content.pm.PackageManager
import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import app.vyral.night.data.sync.AndroidLanSyncClient
import app.vyral.night.data.sync.AndroidLanSyncDiscovery
import app.vyral.night.data.sync.AndroidLanSyncHost
import app.vyral.night.data.sync.LanSyncFileTransfer
import app.vyral.night.data.sync.LanSyncSession
import app.vyral.night.data.sync.normalizeLanAddress
import app.vyral.night.ui.components.QrCode
import com.google.mlkit.vision.barcode.common.Barcode
import com.google.mlkit.vision.codescanner.GmsBarcodeScannerOptions
import com.google.mlkit.vision.codescanner.GmsBarcodeScanning
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

private sealed interface FileDestination {
    data class HostPeer(val deviceId: String) : FileDestination
    data class JoinedHost(val hostId: String, val resumeTransferId: String? = null) : FileDestination
}

/** LAN sync controls shared by Android mobile and TV. Every permission starts off. */
@Composable
fun LanDeviceSyncPanel(modifier: Modifier = Modifier) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val host by AndroidLanSyncHost.state.collectAsStateWithLifecycle()
    var sessions by remember { mutableStateOf(emptyList<LanSyncSession>()) }
    var remoteTransfers by remember { mutableStateOf<Map<String, List<LanSyncFileTransfer>>>(emptyMap()) }
    var address by remember { mutableStateOf("") }
    var fingerprint by remember { mutableStateOf("") }
    var discoveryCode by remember { mutableStateOf("") }
    var discoveryMessage by remember { mutableStateOf<String?>(null) }
    var discovering by remember { mutableStateOf(false) }
    var deviceName by remember { mutableStateOf(android.os.Build.MODEL.orEmpty().take(80).ifBlank { "Android device" }) }
    var pairing by remember { mutableStateOf<AndroidLanSyncClient.Pairing?>(null) }
    var failure by remember { mutableStateOf<String?>(null) }
    var busy by remember { mutableStateOf(false) }
    var sending by remember { mutableStateOf(false) }
    var receiving by remember { mutableStateOf(false) }
    var sendJob by remember { mutableStateOf<Job?>(null) }
    var receiveJob by remember { mutableStateOf<Job?>(null) }
    var sendingId by remember { mutableStateOf<String?>(null) }
    var receivingId by remember { mutableStateOf<String?>(null) }
    var sendProgress by remember { mutableStateOf<Long?>(null) }
    var receiveProgress by remember { mutableStateOf<Long?>(null) }
    var destination by remember { mutableStateOf<FileDestination?>(null) }

    suspend fun refreshSessions() {
        val currentSessions = AndroidLanSyncClient.sessions(context)
        sessions = currentSessions
        remoteTransfers = currentSessions.associate { session ->
            session.hostId to runCatching { AndroidLanSyncClient.transfers(session) }.getOrDefault(emptyList())
        }
    }

    LaunchedEffect(Unit) {
        AndroidLanSyncHost.loadPreferences(context)
        AndroidLanSyncHost.refresh()
        runCatching { refreshSessions() }.onFailure { failure = it.message ?: "Could not open secure LAN pairing storage." }
        while (true) {
            delay(5_000)
            runCatching { refreshSessions() }.onFailure { failure = it.message ?: "Could not refresh paired devices." }
            AndroidLanSyncHost.refresh()
        }
    }
    DisposableEffect(pairing) {
        onDispose { pairing?.let(AndroidLanSyncClient::closePairing) }
    }

    val filePicker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri: Uri? ->
        val target = destination
        destination = null
        if (uri != null && target != null && !sending) {
            sending = true
            failure = null
            sendProgress = 0
            sendJob = scope.launch {
                try {
                    try {
                        when (target) {
                            is FileDestination.HostPeer -> AndroidLanSyncHost.offerLocalFile(context, target.deviceId, uri)
                                ?.also { sendingId = it }
                                ?: error("Could not prepare that audio file. Enable file sending and choose a supported audio file.")
                            is FileDestination.JoinedHost -> {
                                val session = sessions.firstOrNull { it.hostId == target.hostId }
                                    ?: error("This device is no longer paired.")
                                AndroidLanSyncClient.sendSelectedFile(
                                    context,
                                    session,
                                    uri,
                                    onTransferCreated = { id -> withContext(Dispatchers.Main.immediate) { sendingId = id } },
                                    onProgress = { bytes -> withContext(Dispatchers.Main.immediate) { sendProgress = bytes } },
                                    resumeTransferId = target.resumeTransferId,
                                )
                            }
                        }
                    } catch (e: CancellationException) {
                        throw e
                    } catch (e: Exception) {
                        failure = e.message ?: "Could not send the selected file."
                    }
                } finally {
                    sending = false
                    sendingId = null
                    sendProgress = null
                    sendJob = null
                    if (currentCoroutineContext().isActive) refreshSessions()
                    AndroidLanSyncHost.refresh()
                }
            }
        }
    }

    fun resumeReceive(session: LanSyncSession, transfer: LanSyncFileTransfer) {
        if (receiving) return
        receiving = true
        receivingId = transfer.id
        receiveProgress = transfer.received
        receiveJob = scope.launch {
            try {
                AndroidLanSyncClient.receiveSelectedOffer(
                    context,
                    session,
                    transfer,
                    onProgress = { bytes -> withContext(Dispatchers.Main.immediate) { receiveProgress = bytes } },
                )
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                failure = e.message ?: "Could not receive this file."
            } finally {
                receiving = false
                receivingId = null
                receiveProgress = null
                receiveJob = null
                if (currentCoroutineContext().isActive) refreshSessions()
            }
        }
    }

    Column(modifier = modifier, verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Text("Device sync", style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.SemiBold)
        Text(
            "Pair devices on the same local network. Playback control and file sharing are separately opt-in.",
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )

        ToggleRow("Host on this device", host.running) { enabled ->
            scope.launch(Dispatchers.IO) {
                if (enabled) AndroidLanSyncHost.start(context) else AndroidLanSyncHost.stop()
            }
        }
        ToggleRow("Allow remote playback control", host.remoteControlAllowed) {
            AndroidLanSyncHost.setRemoteControlAllowed(context, it)
        }
        ToggleRow("Allow sending files", host.allowSendFiles) { AndroidLanSyncHost.setAllowSendFiles(context, it) }
        ToggleRow("Allow receiving files", host.allowReceiveFiles) { AndroidLanSyncHost.setAllowReceiveFiles(context, it) }
        if (host.error != null) ErrorText(host.error!!)
        if (host.running) {
            host.address?.let { Text("Address: $it", style = MaterialTheme.typography.bodySmall) }
            host.findCode?.let { Text("Discovery code: $it", style = MaterialTheme.typography.bodySmall) }
            host.tlsFingerprint?.let { Text("TLS fingerprint: $it", style = MaterialTheme.typography.bodySmall) }
            val qrPayload = remember(host.address, host.tlsFingerprint) {
                val url = host.address
                val pin = host.tlsFingerprint
                if (url == null || pin == null) "" else """{"type":"vyral-lan-pairing","version":1,"url":"$url","tlsFingerprint":"$pin"}"""
            }
            if (qrPayload.isNotEmpty()) QrCode(qrPayload, modifier = Modifier.align(Alignment.CenterHorizontally), size = 176.dp)

            host.pairingRequests.forEach { request ->
                Card(Modifier.fillMaxWidth()) {
                    Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        Text("Pair ${request.name}?", fontWeight = FontWeight.SemiBold)
                        Text("Confirm code ${request.confirmationCode} on both devices.")
                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            Button(onClick = { AndroidLanSyncHost.approvePairing(request.id) }) { Text("Approve") }
                            OutlinedButton(onClick = { AndroidLanSyncHost.declinePairing(request.id) }) { Text("Decline") }
                        }
                    }
                }
            }

            host.pairedDevices.forEach { peer ->
                SyncActionRow(
                    title = peer.name,
                    subtitle = "Paired device",
                    action = "Send file",
                    enabled = !busy && host.allowSendFiles,
                    onClick = { destination = FileDestination.HostPeer(peer.id); filePicker.launch(arrayOf("audio/*")) },
                )
            }

            host.transfers.forEach { transfer ->
                TransferCard(
                    name = transfer.name,
                    detail = "${transfer.deviceName} · ${transfer.status} · ${transfer.received}/${transfer.size} bytes",
                    pendingUpload = transfer.direction == "upload" && transfer.status == "pending" && host.allowReceiveFiles,
                    onAccept = { AndroidLanSyncHost.acceptTransfer(transfer.id); AndroidLanSyncHost.refresh() },
                    onDecline = { AndroidLanSyncHost.declineTransfer(transfer.id); AndroidLanSyncHost.refresh() },
                    permissionHint = transfer.direction == "upload" && transfer.status == "pending" && !host.allowReceiveFiles,
                    showCancel = transfer.status in setOf("pending", "accepted", "receiving", "sending"),
                    onCancel = { AndroidLanSyncHost.cancelTransfer(transfer.id); AndroidLanSyncHost.refresh() },
                )
            }
        }

        Text("Join another device", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
        if (!context.packageManager.hasSystemFeature(PackageManager.FEATURE_LEANBACK)) {
            OutlinedButton(
                enabled = !busy && !discovering,
                onClick = {
                    failure = null
                    val options = GmsBarcodeScannerOptions.Builder()
                        .setBarcodeFormats(Barcode.FORMAT_QR_CODE)
                        .build()
                    GmsBarcodeScanning.getClient(context, options).startScan()
                        .addOnSuccessListener { barcode ->
                            parseLanPairingQr(barcode.rawValue.orEmpty())?.let { (url, pin) ->
                                address = url
                                fingerprint = pin
                            } ?: run { failure = "That code is not a VYRAL LAN pairing code. Enter its HTTPS address and fingerprint manually." }
                        }
                        .addOnFailureListener { failure = "QR scan unavailable. Enter the host address and fingerprint below." }
                },
            ) { Text("Scan host QR code") }
        }
        OutlinedTextField(
            value = discoveryCode,
            onValueChange = { discoveryCode = it.filter { char -> char in '0'..'9' }.take(4) },
            modifier = Modifier.fillMaxWidth(),
            label = { Text("Four-digit host code") },
            singleLine = true,
        )
        OutlinedButton(
            enabled = !busy && !discovering && discoveryCode.length == 4,
            onClick = {
                discovering = true
                discoveryMessage = null
                failure = null
                scope.launch {
                    try {
                        val found = AndroidLanSyncDiscovery.findByCode(context, discoveryCode)
                        if (found == null) {
                            failure = "No VYRAL host with that code was found on this local network."
                        } else {
                            address = found.address
                            fingerprint = found.tlsFingerprint
                            discoveryMessage = "Found ${found.name}. Review the fingerprint, then pair and confirm the security code on both devices."
                        }
                    } catch (e: CancellationException) {
                        throw e
                    } catch (e: Exception) {
                        failure = e.message ?: "Could not search this local network."
                    } finally {
                        discovering = false
                    }
                }
            },
        ) { Text(if (discovering) "Searching local network…" else "Find by code") }
        discoveryMessage?.let { Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant) }
        OutlinedTextField(address, { address = it }, Modifier.fillMaxWidth(), label = { Text("HTTPS address") }, singleLine = true)
        OutlinedTextField(fingerprint, { fingerprint = it.trim().lowercase() }, Modifier.fillMaxWidth(), label = { Text("TLS fingerprint (64 hex characters)") }, singleLine = true)
        OutlinedTextField(deviceName, { deviceName = it.take(80) }, Modifier.fillMaxWidth(), label = { Text("This device name") }, singleLine = true)
        Button(
            enabled = !busy && !discovering && pairing == null && address.isNotBlank() && fingerprint.length == 64,
            onClick = {
                busy = true
                failure = null
                scope.launch {
                    runCatching { AndroidLanSyncClient.beginPairing(address, deviceName, fingerprint) }
                        .onSuccess { pairing = it }
                        .onFailure { failure = it.message ?: "Could not pair with this device." }
                    busy = false
                }
            },
        ) { Text(if (busy) "Connecting…" else "Pair securely") }

        pairing?.let { pending ->
            Card(Modifier.fillMaxWidth()) {
                Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text("Compare this code on both devices", fontWeight = FontWeight.SemiBold)
                    Text(pending.confirmationCode, style = MaterialTheme.typography.headlineMedium)
                    Button(enabled = !busy, onClick = {
                        busy = true
                        scope.launch {
                            runCatching { AndroidLanSyncClient.pollApproval(context, pending) }
                                .onSuccess { session ->
                                    if (session == null) failure = "The host has not approved this device yet."
                                    else { pairing = null; refreshSessions() }
                                }
                                .onFailure { failure = it.message ?: "Could not check pairing approval." }
                            busy = false
                        }
                    }) { Text("Check host approval") }
                    OutlinedButton(onClick = { AndroidLanSyncClient.closePairing(pending); pairing = null }) { Text("Cancel pairing") }
                }
            }
        }

        sessions.forEach { session ->
            Text(session.hostName, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
            Text("Paired · ${session.baseUrl}", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                listOf("previous", "toggle", "next").forEach { action ->
                    OutlinedButton(enabled = !busy, onClick = {
                        scope.launch {
                            runCatching { AndroidLanSyncClient.sendControl(session, action) }
                                .onFailure { failure = it.message ?: "Remote playback control failed." }
                        }
                    }) { Text(action.replaceFirstChar(Char::uppercase)) }
                }
                OutlinedButton(enabled = !busy && host.allowSendFiles, onClick = { destination = FileDestination.JoinedHost(session.hostId); filePicker.launch(arrayOf("audio/*")) }) {
                    Text("Send file")
                }
            }
            remoteTransfers[session.hostId].orEmpty().forEach { transfer ->
                TransferCard(
                    name = transfer.name,
                    detail = "${transfer.status} · ${transfer.received}/${transfer.size} bytes",
                    pendingUpload = false,
                    onAccept = { resumeReceive(session, transfer) },
                    onDecline = { scope.launch { runCatching { AndroidLanSyncClient.decline(session, transfer.id) }; refreshSessions() } },
                    showReceive = transfer.direction == "download" && transfer.status == "pending" && host.allowReceiveFiles,
                    permissionHint = transfer.direction == "download" && transfer.status == "pending" && !host.allowReceiveFiles,
                    showResumeReceive = transfer.direction == "download" &&
                        transfer.status in setOf("accepted", "sending") && host.allowReceiveFiles && !receiving,
                    onResumeReceive = { resumeReceive(session, transfer) },
                    showCancel = transfer.status in setOf("pending", "accepted", "sending", "receiving"),
                    onCancel = {
                        if (receivingId == transfer.id) receiveJob?.cancel()
                        if (sendingId == transfer.id) sendJob?.cancel()
                        scope.launch {
                            runCatching { AndroidLanSyncClient.cancel(context, session, transfer.id) }
                                .onFailure { failure = it.message ?: "Could not cancel this transfer." }
                            refreshSessions()
                        }
                    },
                    showResumeSend = transfer.direction == "upload" &&
                        transfer.status in setOf("pending", "accepted", "receiving") && host.allowSendFiles && !sending && !receiving,
                    onResumeSend = {
                        destination = FileDestination.JoinedHost(session.hostId, transfer.id)
                        filePicker.launch(arrayOf("audio/*"))
                    },
                )
            }
            OutlinedButton(onClick = {
                AndroidLanSyncClient.forget(context, session.hostId)
                scope.launch { refreshSessions() }
            }) { Text("Unpair") }
        }

        if (busy) Row(verticalAlignment = Alignment.CenterVertically) {
            CircularProgressIndicator(Modifier.size(18.dp), strokeWidth = 2.dp)
            Spacer(Modifier.width(8.dp))
            Text("Pairing…", style = MaterialTheme.typography.bodySmall)
        }
        if (sending) TransferProgress("Sending", sendProgress, onCancel = null)
        if (receiving) TransferProgress("Receiving", receiveProgress, onCancel = null)
        failure?.let { ErrorText(it) }
    }
}

@Composable
fun LanDeviceSyncDialog(onDismiss: () -> Unit) {
    Dialog(onDismissRequest = onDismiss, properties = DialogProperties(usePlatformDefaultWidth = false)) {
        Surface(
            modifier = Modifier.fillMaxWidth(0.92f).heightIn(max = 680.dp),
            shape = MaterialTheme.shapes.extraLarge,
            color = MaterialTheme.colorScheme.surface,
        ) {
            Column(Modifier.verticalScroll(rememberScrollState()).padding(24.dp)) {
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
                    Text("Device sync", style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.SemiBold)
                    OutlinedButton(onClick = onDismiss) { Text("Close") }
                }
                Spacer(Modifier.height(12.dp))
                LanDeviceSyncPanel()
            }
        }
    }
}

@Composable
private fun ToggleRow(label: String, checked: Boolean, onChange: (Boolean) -> Unit) {
    Row(
        Modifier.fillMaxWidth().clickable { onChange(!checked) }.padding(vertical = 3.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.SpaceBetween,
    ) {
        Text(label, Modifier.weight(1f), style = MaterialTheme.typography.bodyLarge)
        Switch(checked = checked, onCheckedChange = onChange)
    }
}

@Composable
private fun SyncActionRow(title: String, subtitle: String, action: String, enabled: Boolean, onClick: () -> Unit) {
    Row(Modifier.fillMaxWidth().padding(vertical = 4.dp), verticalAlignment = Alignment.CenterVertically) {
        Column(Modifier.weight(1f)) {
            Text(title, maxLines = 1, overflow = TextOverflow.Ellipsis, fontWeight = FontWeight.Medium)
            Text(subtitle, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        OutlinedButton(enabled = enabled, onClick = onClick) { Text(action) }
    }
}

@Composable
private fun TransferCard(
    name: String,
    detail: String,
    pendingUpload: Boolean,
    onAccept: () -> Unit,
    onDecline: () -> Unit,
    showReceive: Boolean = false,
    permissionHint: Boolean = false,
    showResumeSend: Boolean = false,
    onResumeSend: () -> Unit = {},
    showResumeReceive: Boolean = false,
    onResumeReceive: () -> Unit = {},
    showCancel: Boolean = false,
    onCancel: () -> Unit = {},
) {
    Card(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Text(name, maxLines = 1, overflow = TextOverflow.Ellipsis, fontWeight = FontWeight.Medium)
            Text(detail, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            if (permissionHint) Text("Enable file receiving above to accept.", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            if (pendingUpload || showReceive) Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Button(onClick = onAccept) { Text(if (showReceive) "Accept and download" else "Accept") }
                OutlinedButton(onClick = onDecline) { Text("Decline") }
            }
            if (showResumeSend || showResumeReceive) {
                OutlinedButton(onClick = if (showResumeSend) onResumeSend else onResumeReceive) {
                    Text(if (showResumeSend) "Resume send" else "Resume download")
                }
            }
            if (showCancel) OutlinedButton(onClick = onCancel) { Text("Cancel transfer") }
        }
    }
}

@Composable
private fun TransferProgress(label: String, bytes: Long?, onCancel: (() -> Unit)?) {
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        CircularProgressIndicator(Modifier.size(18.dp), strokeWidth = 2.dp)
        Spacer(Modifier.width(8.dp))
        Text(bytes?.let { "$label · $it bytes" } ?: label, Modifier.weight(1f), style = MaterialTheme.typography.bodySmall)
        if (onCancel != null) OutlinedButton(onClick = onCancel) { Text("Cancel") }
    }
}

@Composable
private fun ErrorText(message: String) {
    Text(message, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall)
}

internal fun parseLanPairingQr(raw: String): Pair<String, String>? = runCatching {
    val value = Json.parseToJsonElement(raw).jsonObject
    if (value["type"]?.jsonPrimitive?.content != "vyral-lan-pairing" ||
        value["version"]?.jsonPrimitive?.content != "1"
    ) return null
    val url = value["url"]?.jsonPrimitive?.content ?: return null
    val pin = value["tlsFingerprint"]?.jsonPrimitive?.content?.lowercase() ?: return null
    normalizeLanAddress(url)
    if (!pin.matches(Regex("[0-9a-f]{64}"))) return null
    url to pin
}.getOrNull()

