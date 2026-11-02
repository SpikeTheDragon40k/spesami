package it.spesami.app.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
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
import androidx.compose.material.icons.filled.Share
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
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
import it.spesami.core.ShoppingGroup
import it.spesami.core.ShoppingItem
import it.spesami.core.ShoppingList
import it.spesami.core.ShoppingSort
import it.spesami.core.Weeks
import java.time.LocalDate

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ShoppingScreen(vm: AppViewModel, data: AppData, week: LocalDate, prefs: Preferences, pdfPalette: PdfPalette) {
    val context = LocalContext.current
    val items = ShoppingList.build(data, week, prefs.shoppingSort)
    val groups = if (prefs.groupByCategory) ShoppingList.grouped(items) else null
    // Shared text follows what is on screen: same order (category order when grouped), one name per line.
    val ordered = groups?.flatMap { it.items } ?: items
    val text = ShoppingList.toPlainText(ordered, prefs.shareWithCounts)
    val mealCount = data.slotsOfWeek(week).size
    val pdf = rememberPdfExport("lista-spesa-$week.pdf", vm::message) {
        PdfExporter.writeShoppingList(groups ?: listOf(ShoppingGroup(null, items)), groups != null, week, mealCount, pdfPalette, it)
    }

    Scaffold(topBar = { TopAppBar(title = { Text("Lista della spesa") }) }) { padding ->
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
                    Button(
                        onClick = { Exports.shareText(context, "Condividi lista della spesa", "Lista della spesa · ${Weeks.rangeLabel(week)}", text) },
                        enabled = items.isNotEmpty(),
                        modifier = Modifier.fillMaxWidth().height(56.dp),
                    ) {
                        Icon(Icons.Default.Share, null)
                        Spacer(Modifier.width(10.dp))
                        Text("Condividi lista della spesa", style = MaterialTheme.typography.titleSmall)
                    }
                    Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                        OutlinedButton(
                            onClick = { Exports.copyText(context, "Lista della spesa", text) },
                            enabled = items.isNotEmpty(),
                            modifier = Modifier.weight(1f),
                        ) { Text("Copia lista") }
                        PdfMenu(pdf) { open ->
                            OutlinedButton(onClick = open, enabled = items.isNotEmpty()) { Text("Esporta PDF") }
                        }
                    }
                    Text("Formato del testo condiviso", style = MaterialTheme.typography.labelLarge)
                    SingleChoiceSegmentedButtonRow(Modifier.fillMaxWidth()) {
                        SegmentedButton(
                            selected = !prefs.shareWithCounts,
                            onClick = { vm.setPreference(Preferences.SHARE_WITH_COUNTS, "false") },
                            shape = SegmentedButtonDefaults.itemShape(0, 2),
                        ) { Text("Lista semplice") }
                        SegmentedButton(
                            selected = prefs.shareWithCounts,
                            onClick = { vm.setPreference(Preferences.SHARE_WITH_COUNTS, "true") },
                            shape = SegmentedButtonDefaults.itemShape(1, 2),
                        ) { Text("Con conteggi") }
                    }
                    Text(
                        if (prefs.shareWithCounts) "Es. \"Patate ×4\": quanti pasti usano ogni ingrediente."
                        else "Un ingrediente per riga: il formato più adatto da incollare nelle app per la spesa.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        FilterChip(
                            selected = prefs.shoppingSort == ShoppingSort.COUNT,
                            onClick = { vm.setPreference(Preferences.SHOPPING_SORT, ShoppingSort.COUNT.name) },
                            label = { Text("Più usati") },
                        )
                        FilterChip(
                            selected = prefs.shoppingSort == ShoppingSort.ALPHABETICAL,
                            onClick = { vm.setPreference(Preferences.SHOPPING_SORT, ShoppingSort.ALPHABETICAL.name) },
                            label = { Text("A–Z") },
                        )
                        Spacer(Modifier.weight(1f))
                        Text("Categorie", style = MaterialTheme.typography.labelLarge)
                        Switch(
                            checked = prefs.groupByCategory,
                            onCheckedChange = { vm.setPreference(Preferences.GROUP_BY_CATEGORY, it.toString()) },
                        )
                    }
                }
            }
            if (groups != null) {
                groups.forEach { group ->
                    item(key = "g-${group.category}") { SectionTitle(group.label) }
                    items(group.items, key = { it.ingredientId }) { ShoppingRow(it) }
                }
            } else {
                item { Spacer(Modifier.height(8.dp)) }
                items(items, key = { it.ingredientId }) { ShoppingRow(it) }
            }
        }
    }
}

@Composable
private fun ShoppingRow(item: ShoppingItem) {
    val colors = MaterialTheme.colorScheme
    Column {
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
}
