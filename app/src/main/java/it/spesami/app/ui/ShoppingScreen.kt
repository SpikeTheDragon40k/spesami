package it.spesami.app.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.Share
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import it.spesami.app.AppViewModel
import it.spesami.app.data.Preferences
import it.spesami.app.pdf.PdfExporter
import it.spesami.app.pdf.PdfPalette
import it.spesami.core.AppData
import it.spesami.core.ExportOptions
import it.spesami.core.ShoppingExport
import it.spesami.core.ShoppingGroup
import it.spesami.core.ShoppingItem
import it.spesami.core.ShoppingList
import it.spesami.core.Weeks
import java.time.LocalDate

/** Share, copy and PDF all use the saved export settings, so sharing stays one tap. */
class ShoppingExportActions(val text: String, val share: () -> Unit, val copy: () -> Unit, val pdf: PdfExport)

@Composable
fun rememberShoppingExport(vm: AppViewModel, data: AppData, week: LocalDate, options: ExportOptions, pdfPalette: PdfPalette): ShoppingExportActions {
    val context = LocalContext.current
    val sections = ShoppingExport.sections(data, week, options)
    val text = ShoppingExport.toPlainText(sections, options.includeCounts)
    val mealCount = data.slotsOfWeek(week).size
    val pdf = rememberPdfExport("lista-spesa-$week.pdf", vm::message) {
        PdfExporter.writeShoppingList(sections, options.includeCounts, week, mealCount, pdfPalette, it)
    }
    return ShoppingExportActions(
        text = text,
        share = { Exports.shareText(context, "Condividi lista della spesa", "Lista della spesa · ${Weeks.rangeLabel(week)}", text) },
        copy = { Exports.copyText(context, "Lista della spesa", text) },
        pdf = pdf,
    )
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ShoppingScreen(vm: AppViewModel, data: AppData, week: LocalDate, prefs: Preferences, pdfPalette: PdfPalette) {
    val options = prefs.export
    val items = ShoppingList.build(data, week)
    val groups = ShoppingList.grouped(data, items)
    val unclassified = groups.lastOrNull()?.takeIf { it.category == null }?.items?.size ?: 0
    val mealCount = data.slotsOfWeek(week).size
    val export = rememberShoppingExport(vm, data, week, options, pdfPalette)
    val hasExport = export.text.isNotEmpty()
    var showOptions by remember { mutableStateOf(false) }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Lista della spesa") },
                actions = { IconButton(onClick = { showOptions = true }) { Icon(Icons.Default.Settings, "Esporta lista della spesa") } },
            )
        },
    ) { padding ->
        LazyColumn(Modifier.fillMaxSize().padding(padding), contentPadding = PaddingValues(bottom = 24.dp)) {
            item { WeekBar(week, vm::shiftWeek, vm::goToCurrentWeek, Modifier.padding(horizontal = 8.dp)) }
            item {
                Column(Modifier.padding(horizontal = 16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    Text(
                        if (items.isEmpty()) "Nessun ingrediente: pianifica qualche pasto per questa settimana."
                        else "${items.size} ingredienti da $mealCount pasti",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    Button(onClick = export.share, enabled = hasExport, modifier = Modifier.fillMaxWidth().height(56.dp)) {
                        Icon(Icons.Default.Share, null)
                        Spacer(Modifier.width(10.dp))
                        Text("Condividi lista della spesa", style = MaterialTheme.typography.titleSmall)
                    }
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        OutlinedButton(onClick = export.copy, enabled = hasExport, modifier = Modifier.weight(1f)) { Text("Copia lista") }
                        Box(Modifier.weight(1f)) {
                            PdfMenu(export.pdf) { open ->
                                OutlinedButton(onClick = open, enabled = hasExport, modifier = Modifier.fillMaxWidth()) { Text("Esporta PDF") }
                            }
                        }
                    }
                    if (items.isNotEmpty()) {
                        Text(
                            exportSummary(options),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                        Text(
                            "Modifica opzioni di esportazione",
                            style = MaterialTheme.typography.labelLarge,
                            color = MaterialTheme.colorScheme.primary,
                            modifier = Modifier.clickable { showOptions = true }.padding(vertical = 4.dp),
                        )
                        if (!hasExport) {
                            Text(
                                "Tutte le categorie di questa settimana sono escluse dall'esportazione.",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.error,
                            )
                        }
                    }
                    if (unclassified > 0) {
                        Text(
                            if (unclassified == 1) "1 ingrediente da classificare: toccalo per scegliere la categoria."
                            else "$unclassified ingredienti da classificare: tocca un ingrediente per scegliere la categoria.",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.error,
                        )
                    }
                }
            }
            groups.forEach { group ->
                item(key = "g-${group.categoryId}") { GroupHeader(group) }
                items(group.items, key = { it.ingredientId }) { item ->
                    ShoppingRow(item, data) { vm.setIngredientCategory(item.ingredientId, it) }
                }
            }
        }
    }

    if (showOptions) {
        ExportOptionsDialog(vm, data, week, prefs, groups, export) { showOptions = false }
    }
}

private fun exportSummary(o: ExportOptions): String {
    val parts = buildList {
        add(if (o.groupByCategory) (if (o.showHeaders) "per categoria, con intestazioni" else "per categoria, senza intestazioni") else "elenco semplice")
        add(if (o.includeCounts) "con conteggi" else "senza conteggi")
        if (o.excludedCategories.isNotEmpty()) {
            add(if (o.excludedCategories.size == 1) "1 categoria esclusa" else "${o.excludedCategories.size} categorie escluse")
        }
    }
    return "Esportazione: " + parts.joinToString(", ")
}

@Composable
private fun GroupHeader(group: ShoppingGroup) {
    val color = if (group.category == null) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.primary
    Row(Modifier.fillMaxWidth().padding(start = 16.dp, end = 16.dp, top = 20.dp, bottom = 4.dp), verticalAlignment = Alignment.CenterVertically) {
        Text(group.label, style = MaterialTheme.typography.titleSmall, color = color, modifier = Modifier.weight(1f))
        Text("${group.items.size}", style = MaterialTheme.typography.labelMedium, color = color)
    }
}

@Composable
private fun ShoppingRow(item: ShoppingItem, data: AppData, onCategory: (Long?) -> Unit) {
    val colors = MaterialTheme.colorScheme
    var menu by remember { mutableStateOf(false) }
    Box {
        Column(Modifier.clickable { menu = true }) {
            Row(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 12.dp), verticalAlignment = Alignment.CenterVertically) {
                Text(item.name, style = MaterialTheme.typography.bodyLarge, modifier = Modifier.weight(1f))
                Text(
                    "×${item.count}",
                    style = MaterialTheme.typography.labelLarge,
                    fontWeight = FontWeight.Bold,
                    color = colors.onSecondaryContainer,
                    modifier = Modifier
                        .background(colors.secondaryContainer, RoundedCornerShape(50))
                        .padding(horizontal = 10.dp, vertical = 3.dp),
                )
            }
            HorizontalDivider(Modifier.padding(horizontal = 16.dp), color = colors.outlineVariant.copy(alpha = 0.4f))
        }
        CategoryMenu(menu, data, item.categoryId, onDismiss = { menu = false }) {
            menu = false
            onCategory(it)
        }
    }
}
