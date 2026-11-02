package it.spesami.app.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material3.Button
import androidx.compose.material3.Checkbox
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.ListItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import it.spesami.app.AppViewModel
import it.spesami.app.data.Preferences
import it.spesami.core.AppData
import it.spesami.core.DefaultCategories
import it.spesami.core.ShoppingGroup
import it.spesami.core.ShoppingSort
import java.time.LocalDate

/**
 * "Esporta lista della spesa": format switches, category filter and an exact preview of the shared text.
 * Settings are saved as they change and only affect exports, never the plan or the ingredients.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ExportOptionsDialog(
    vm: AppViewModel,
    data: AppData,
    week: LocalDate,
    prefs: Preferences,
    groups: List<ShoppingGroup>,
    export: ShoppingExportActions,
    onDismiss: () -> Unit,
) {
    val o = prefs.export
    val countByCategory = groups.associate { it.categoryId to it.items.size }
    val filterRows: List<Pair<Long?, String>> =
        data.sortedCategories.map { it.id to it.name } + (null to DefaultCategories.UNCLASSIFIED_LABEL)

    fun setExcluded(ids: Set<Long?>) = vm.setPreference(Preferences.EXCLUDED_CATEGORIES, Preferences.encodeExcluded(ids))

    Dialog(onDismissRequest = onDismiss, properties = DialogProperties(usePlatformDefaultWidth = false)) {
        Surface(Modifier.fillMaxSize()) {
            Scaffold(
                topBar = {
                    TopAppBar(
                        title = { Text("Esporta lista della spesa") },
                        navigationIcon = { IconButton(onClick = onDismiss) { Icon(Icons.Default.Close, "Chiudi") } },
                    )
                },
                bottomBar = {
                    Row(
                        Modifier.fillMaxWidth().navigationBarsPadding().padding(12.dp),
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                    ) {
                        val enabled = export.text.isNotEmpty()
                        OutlinedButton(onClick = export.copy, enabled = enabled) { Text("Copia") }
                        Box {
                            PdfMenu(export.pdf) { open -> OutlinedButton(onClick = open, enabled = enabled) { Text("PDF") } }
                        }
                        Button(onClick = export.share, enabled = enabled, modifier = Modifier.weight(1f)) { Text("Condividi lista") }
                    }
                },
            ) { padding ->
                Column(Modifier.fillMaxSize().padding(padding).verticalScroll(rememberScrollState())) {
                    SwitchRow("Raggruppa per categoria", o.groupByCategory) {
                        vm.setPreference(Preferences.GROUP_BY_CATEGORY, it.toString())
                    }
                    SwitchRow("Includi numero di occorrenze", o.includeCounts) {
                        vm.setPreference(Preferences.SHARE_WITH_COUNTS, it.toString())
                    }
                    SwitchRow("Mostra intestazioni delle categorie", o.showHeaders && o.groupByCategory, enabled = o.groupByCategory) {
                        vm.setPreference(Preferences.SHOW_CATEGORY_HEADERS, it.toString())
                    }
                    if (!o.groupByCategory) {
                        Row(Modifier.padding(horizontal = 16.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            FilterChip(
                                selected = o.sort == ShoppingSort.COUNT,
                                onClick = { vm.setPreference(Preferences.SHOPPING_SORT, ShoppingSort.COUNT.name) },
                                label = { Text("Più usati prima") },
                            )
                            FilterChip(
                                selected = o.sort == ShoppingSort.ALPHABETICAL,
                                onClick = { vm.setPreference(Preferences.SHOPPING_SORT, ShoppingSort.ALPHABETICAL.name) },
                                label = { Text("A–Z") },
                            )
                        }
                    }

                    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                        SectionTitle("Categorie incluse", Modifier.weight(1f))
                        TextButton(onClick = { setExcluded(emptySet()) }, modifier = Modifier.padding(top = 12.dp)) { Text("Tutte") }
                        TextButton(onClick = { setExcluded(filterRows.map { it.first }.toSet()) }, modifier = Modifier.padding(top = 12.dp, end = 8.dp)) {
                            Text("Nessuna")
                        }
                    }
                    filterRows.forEach { (id, name) ->
                        val included = id !in o.excludedCategories
                        val count = countByCategory[id] ?: 0
                        ListItem(
                            headlineContent = { Text(name) },
                            supportingContent = {
                                Text(if (count == 0) "Nessun ingrediente questa settimana" else if (count == 1) "1 ingrediente" else "$count ingredienti")
                            },
                            leadingContent = { Checkbox(checked = included, onCheckedChange = null) },
                            modifier = Modifier.toggleable(value = included, role = Role.Checkbox) {
                                setExcluded(if (it) o.excludedCategories - id else o.excludedCategories + id)
                            },
                        )
                    }

                    SectionTitle("Anteprima")
                    Surface(
                        color = MaterialTheme.colorScheme.surfaceContainerHigh,
                        shape = RoundedCornerShape(12.dp),
                        modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 4.dp),
                    ) {
                        Text(
                            export.text.ifEmpty { "Nessun ingrediente da esportare." },
                            fontFamily = FontFamily.Monospace,
                            style = MaterialTheme.typography.bodyMedium,
                            color = if (export.text.isEmpty()) MaterialTheme.colorScheme.onSurfaceVariant else MaterialTheme.colorScheme.onSurface,
                            modifier = Modifier.padding(14.dp),
                        )
                    }
                    Text(
                        "Il testo viene condiviso così com'è, un ingrediente per riga: le app per la spesa che accettano testo incollato o condiviso lo importano come elenco.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(16.dp),
                    )
                }
            }
        }
    }
}

@Composable
private fun SwitchRow(label: String, checked: Boolean, enabled: Boolean = true, onChange: (Boolean) -> Unit) {
    ListItem(
        headlineContent = { Text(label, color = if (enabled) MaterialTheme.colorScheme.onSurface else MaterialTheme.colorScheme.onSurfaceVariant) },
        trailingContent = { Switch(checked = checked, onCheckedChange = null, enabled = enabled) },
        modifier = Modifier.toggleable(value = checked, enabled = enabled, role = Role.Switch, onValueChange = onChange),
    )
}
