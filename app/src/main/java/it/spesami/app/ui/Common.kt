package it.spesami.app.ui

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowLeft
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import it.spesami.core.Weeks
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.OutputStream
import java.time.LocalDate

@Composable
fun WeekBar(week: LocalDate, onShift: (Long) -> Unit, onToday: () -> Unit, modifier: Modifier = Modifier) {
    val isCurrent = week == Weeks.startOf(LocalDate.now())
    Row(modifier.fillMaxWidth().padding(horizontal = 4.dp), verticalAlignment = Alignment.CenterVertically) {
        IconButton(onClick = { onShift(-1) }) {
            Icon(Icons.AutoMirrored.Filled.KeyboardArrowLeft, contentDescription = "Settimana precedente")
        }
        Column(Modifier.weight(1f), horizontalAlignment = Alignment.CenterHorizontally) {
            Text(Weeks.rangeLabel(week), style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
            if (isCurrent) {
                Text("Questa settimana", style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.primary)
            } else {
                TextButton(onClick = onToday, modifier = Modifier.padding(0.dp)) { Text("Torna a questa settimana") }
            }
        }
        IconButton(onClick = { onShift(1) }) {
            Icon(Icons.AutoMirrored.Filled.KeyboardArrowRight, contentDescription = "Settimana successiva")
        }
    }
}

class PdfExport(val save: () -> Unit, val share: () -> Unit)

/** "Save as file" goes through the Storage Access Framework; "share" through the share sheet. */
@Composable
fun rememberPdfExport(fileName: String, onMessage: (String) -> Unit, write: (OutputStream) -> Unit): PdfExport {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val currentWrite by rememberUpdatedState(write)
    val currentName by rememberUpdatedState(fileName)
    val launcher = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("application/pdf")) { uri ->
        if (uri != null) scope.launch {
            val ok = withContext(Dispatchers.IO) {
                runCatching { context.contentResolver.openOutputStream(uri, "wt")!!.use { currentWrite(it) } }.isSuccess
            }
            onMessage(if (ok) "PDF salvato." else "Impossibile salvare il PDF.")
        }
    }
    return remember(launcher) {
        PdfExport(
            save = { launcher.launch(currentName) },
            share = {
                scope.launch {
                    runCatching {
                        withContext(Dispatchers.IO) { Exports.sharePdfPrepare(context, currentName, currentWrite) }
                    }.onSuccess { Exports.sharePdfLaunch(context, it) }
                        .onFailure { onMessage("Impossibile creare il PDF.") }
                }
            },
        )
    }
}

/** Anchors the "save / share" choice for a PDF export to whatever button [anchor] draws. */
@Composable
fun PdfMenu(export: PdfExport, anchor: @Composable (open: () -> Unit) -> Unit) {
    var expanded by remember { mutableStateOf(false) }
    Box {
        anchor { expanded = true }
        DropdownMenu(expanded = expanded, onDismissRequest = { expanded = false }) {
            DropdownMenuItem(text = { Text("Salva PDF su file…") }, onClick = { expanded = false; export.save() })
            DropdownMenuItem(text = { Text("Condividi PDF…") }, onClick = { expanded = false; export.share() })
        }
    }
}

@Composable
fun SectionTitle(text: String, modifier: Modifier = Modifier) {
    Text(
        text,
        style = MaterialTheme.typography.titleSmall,
        color = MaterialTheme.colorScheme.primary,
        modifier = modifier.padding(start = 16.dp, end = 16.dp, top = 20.dp, bottom = 6.dp),
    )
}

@Composable
fun EmptyState(title: String, body: String, modifier: Modifier = Modifier) {
    Column(
        modifier.fillMaxWidth().padding(32.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Text(title, style = MaterialTheme.typography.titleMedium)
        Text(body, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}
