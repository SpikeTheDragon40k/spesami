package it.spesami.app.ui

import android.app.Activity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.IntentSenderRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ListItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import it.spesami.app.AppViewModel
import it.spesami.app.sync.SyncEngine
import java.time.LocalDate

/** Altro → Sincronizzazione: connect, status, "Sincronizza ora", "Disconnetti" and the devices seen. */
@Composable
fun SyncSection(vm: AppViewModel) {
    val status by vm.sync.status.collectAsState()
    val info = status.info
    var confirmDisconnect by remember { mutableStateOf(false) }
    val consent = rememberLauncherForActivityResult(ActivityResultContracts.StartIntentSenderForResult()) { result ->
        val ok = result.resultCode == Activity.RESULT_OK && vm.sync.finishConnect(result.data)
        if (!ok) vm.message("Accesso a Google Drive non concesso: la sincronizzazione resta spenta.")
    }
    val saveFirstBackup = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("application/json")) { uri ->
        if (uri != null) vm.exportFirstSyncBackup(uri)
    }
    fun connect() = vm.sync.connect(
        launchConsent = { consent.launch(IntentSenderRequest.Builder(it.intentSender).build()) },
        onFailure = { vm.message("Connessione a Google Drive non riuscita: $it") },
    )

    SectionTitle("Sincronizzazione")
    if (info == null) return
    val muted = MaterialTheme.colorScheme.onSurfaceVariant
    if (!info.enabled) {
        Text(
            "Spenta. Puoi tenere uguali piano, ricette, ingredienti, categorie e lista della spesa su più telefoni " +
                "tramite una cartella nascosta di Google Drive riservata a Spesami. Tema, stile dei PDF e schermo " +
                "acceso restano di ogni telefono. Tutti i telefoni devono usare lo stesso account Google.",
            style = MaterialTheme.typography.bodyMedium,
            color = muted,
            modifier = Modifier.padding(horizontal = 16.dp, vertical = 4.dp),
        )
        Button(onClick = ::connect, modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp)) { Text("Connetti a Google Drive") }
        return
    }

    val line = when {
        status.running -> "Sincronizzazione in corso…"
        info.lastSync != null -> "Ultima sincronizzazione: ${SyncEngine.describeTime(info.lastSync, LocalDate.now())}"
        else -> "Non ancora sincronizzato"
    }
    Text(line, style = MaterialTheme.typography.bodyLarge, modifier = Modifier.padding(horizontal = 16.dp, vertical = 4.dp))
    if (info.error != null && !status.running) {
        Text(info.error, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodyMedium, modifier = Modifier.padding(horizontal = 16.dp, vertical = 2.dp))
    }
    info.problems.forEach {
        Text(it, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodyMedium, modifier = Modifier.padding(horizontal = 16.dp, vertical = 2.dp))
    }
    Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp)) {
        if (info.needsReconnect) {
            Button(onClick = ::connect) { Text("Connetti di nuovo") }
        } else {
            Button(onClick = vm.sync::requestSync, enabled = !status.running) { Text("Sincronizza ora") }
        }
        OutlinedButton(onClick = { confirmDisconnect = true }) { Text("Disconnetti") }
    }

    if (info.devices.isNotEmpty()) {
        Text("Dispositivi", style = MaterialTheme.typography.titleSmall, modifier = Modifier.padding(start = 16.dp, top = 8.dp))
        info.devices.forEach { d ->
            ListItem(
                headlineContent = { Text(if (d.isThisDevice) "${d.name} (questo telefono)" else d.name) },
                supportingContent = { Text("Ultima sincronizzazione: ${SyncEngine.describeTime(d.lastSync, LocalDate.now())}") },
            )
        }
    }
    if (info.firstBackup != null) {
        ListItem(
            headlineContent = { Text("Backup automatico") },
            supportingContent = { Text("Prima della prima sincronizzazione è stato salvato un backup dei dati di questo telefono. Tocca per esportarlo.") },
            modifier = Modifier.clickable { saveFirstBackup.launch("spesami-prima-sincronizzazione-${LocalDate.now()}.json") },
        )
    }

    if (confirmDisconnect) {
        AlertDialog(
            onDismissRequest = { confirmDisconnect = false },
            title = { Text("Disconnettere questo telefono?") },
            text = {
                Column {
                    Text("Questo telefono smetterà di sincronizzarsi. Tutti i dati restano sul telefono e gli altri telefoni continuano a sincronizzarsi tra loro.")
                    Text(
                        "Per togliere a Spesami l'accesso a Google Drive su tutti i telefoni: myaccount.google.com/permissions → Spesami → Rimuovi accesso.",
                        style = MaterialTheme.typography.bodySmall,
                        color = muted,
                        modifier = Modifier.padding(top = 8.dp),
                    )
                }
            },
            confirmButton = {
                TextButton(onClick = {
                    confirmDisconnect = false
                    vm.sync.disconnect()
                }) { Text("Disconnetti") }
            },
            dismissButton = { TextButton(onClick = { confirmDisconnect = false }) { Text("Annulla") } },
        )
    }
}
