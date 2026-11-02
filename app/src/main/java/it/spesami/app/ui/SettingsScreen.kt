package it.spesami.app.ui

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.ListItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.collectAsState
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.unit.dp
import it.spesami.app.AppViewModel
import it.spesami.app.data.AppTheme
import it.spesami.app.data.PdfTheme
import it.spesami.app.data.Preferences
import it.spesami.core.AppData
import java.time.LocalDate

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SettingsScreen(vm: AppViewModel, data: AppData, prefs: Preferences, onOpenIngredients: () -> Unit) {
    val saveBackup = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("application/json")) { uri ->
        if (uri != null) vm.exportBackup(uri)
    }
    val openBackup = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) vm.readBackup(uri)
    }
    val restore by vm.restore.collectAsState()

    Scaffold(topBar = { TopAppBar(title = { Text("Altro") }) }) { padding ->
        Column(Modifier.fillMaxSize().padding(padding).verticalScroll(rememberScrollState())) {
            SectionTitle("Tema dell'app")
            AppTheme.entries.forEach { theme ->
                RadioRow(theme.label, prefs.theme == theme) { vm.setPreference(Preferences.THEME, theme.name) }
            }

            SectionTitle("Tema dei PDF")
            PdfTheme.entries.forEach { theme ->
                RadioRow(theme.label, prefs.pdfTheme == theme) { vm.setPreference(Preferences.PDF_THEME, theme.name) }
            }

            SectionTitle("Ingredienti")
            ListItem(
                headlineContent = { Text("Ingredienti e alias") },
                supportingContent = { Text("${data.ingredients.size} ingredienti, ${data.aliases.size} alias, categorie") },
                trailingContent = { Icon(Icons.AutoMirrored.Filled.KeyboardArrowRight, null) },
                modifier = Modifier.clickable(onClick = onOpenIngredients),
            )

            SectionTitle("Backup")
            ListItem(
                headlineContent = { Text("Esporta backup") },
                supportingContent = { Text("Salva ricette, ingredienti, alias, piani e preferenze in un file JSON") },
                modifier = Modifier.clickable { saveBackup.launch("spesami-backup-${LocalDate.now()}.json") },
            )
            ListItem(
                headlineContent = { Text("Ripristina backup") },
                supportingContent = { Text("Sostituisce tutti i dati di questo dispositivo con quelli di un file di backup") },
                modifier = Modifier.clickable { openBackup.launch(arrayOf("application/json", "text/plain", "application/octet-stream")) },
            )

            SectionTitle("Informazioni")
            Text(
                "Spesami funziona interamente su questo dispositivo: nessun account, nessuna sincronizzazione, " +
                    "nessuna connessione a Internet. I dati escono dal telefono solo quando li condividi o esporti tu.",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(horizontal = 16.dp, vertical = 4.dp),
            )
            Text(
                "Versione 1.0.0",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(16.dp),
            )
        }
    }

    when (val state = restore) {
        is AppViewModel.RestoreState.Confirm -> AlertDialog(
            onDismissRequest = vm::dismissRestore,
            title = { Text("Ripristinare il backup?") },
            text = {
                Text(
                    "Il file contiene ${state.summary}.\n\nTutti i dati attuali di questo dispositivo verranno " +
                        "sostituiti. L'operazione non si può annullare: se vuoi, esporta prima un backup dei dati attuali."
                )
            },
            confirmButton = { TextButton(onClick = vm::confirmRestore) { Text("Sostituisci i dati") } },
            dismissButton = { TextButton(onClick = vm::dismissRestore) { Text("Annulla") } },
        )
        is AppViewModel.RestoreState.Rejected -> AlertDialog(
            onDismissRequest = vm::dismissRestore,
            title = { Text("Backup non valido") },
            text = {
                val shown = state.errors.take(6)
                val more = state.errors.size - shown.size
                Text(
                    "Nessun dato è stato modificato.\n\n" + shown.joinToString("\n") { "• $it" } +
                        if (more > 0) "\n… e altri $more problemi." else ""
                )
            },
            confirmButton = { TextButton(onClick = vm::dismissRestore) { Text("OK") } },
        )
        null -> Unit
    }
}

@Composable
private fun RadioRow(label: String, selected: Boolean, onSelect: () -> Unit) {
    ListItem(
        headlineContent = { Text(label) },
        leadingContent = { RadioButton(selected = selected, onClick = null) },
        modifier = Modifier.fillMaxWidth().selectable(selected = selected, role = Role.RadioButton, onClick = onSelect),
    )
}
