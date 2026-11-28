package app.vyral.desktop

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.awt.EventQueue
import java.time.Instant
import javax.swing.JFileChooser
import javax.swing.filechooser.FileNameExtensionFilter

@Composable
internal fun DesktopRustMigrationRow() {
    val scope = rememberCoroutineScope()
    var busy by remember { mutableStateOf(false) }
    var status by remember { mutableStateOf<String?>(null) }
    Column(Modifier.padding(vertical = 16.dp)) {
        Text(DesktopStrings["migration_title", "Move playlists to Rust desktop"], style = MaterialTheme.typography.titleMedium)
        Text(DesktopStrings["migration_description",
            "Export a copy, then import it in Rust: Settings → Your data → Import backup. Local files, network-source tracks, accounts and settings stay here."],
            style = MaterialTheme.typography.bodySmall)
        Button(enabled = !busy, onClick = {
            busy = true
            scope.launch {
                try {
                    // Snapshot the current library; do not change it or ask Rust to open it.
                    val export = withContext(Dispatchers.IO) {
                        DesktopRustMigration.encode(DesktopPersistence().playlists(), Instant.now().toString())
                    }
                    val chooser = JFileChooser().apply {
                        dialogTitle = DesktopStrings["migration_export", "Export for Rust desktop"]
                        selectedFile = java.io.File("VYRAL-KMP-playlists.json")
                        fileFilter = FileNameExtensionFilter("VYRAL JSON", "json")
                    }
                    var approved = false
                    val choose = Runnable { approved = chooser.showSaveDialog(null) == JFileChooser.APPROVE_OPTION }
                    if (EventQueue.isDispatchThread()) choose.run() else withContext(Dispatchers.IO) { EventQueue.invokeAndWait(choose) }
                    if (approved) {
                        withContext(Dispatchers.IO) { DesktopRustMigration.writeNew(chooser.selectedFile.toPath(), export) }
                        status = DesktopStrings.format("migration_saved", export.playlists, export.songs, export.unsupported,
                            fallback = "Exported %1\$d playlists and %2\$d songs. %3\$d unsupported tracks stayed in KMP.")
                    }
                } catch (error: Exception) {
                    status = DesktopStrings["migration_failed", "Export failed; the original library is unchanged."] +
                        " " + error.localizedMessage.orEmpty()
                } finally {
                    busy = false
                }
            }
        }) { Text(DesktopStrings["migration_export", "Export for Rust desktop"]) }
        status?.let { Text(it, style = MaterialTheme.typography.bodySmall) }
    }
}
