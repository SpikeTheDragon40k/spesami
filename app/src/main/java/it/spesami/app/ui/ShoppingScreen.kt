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
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material.icons.filled.KeyboardArrowUp
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.Share
import androidx.compose.material.icons.filled.ShoppingCart
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Checkbox
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExtendedFloatingActionButton
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.unit.dp
import it.spesami.app.AppViewModel
import it.spesami.app.data.Preferences
import it.spesami.app.pdf.PdfExporter
import it.spesami.app.pdf.PdfPalette
import it.spesami.core.AppData
import it.spesami.core.DefaultCategories
import it.spesami.core.ExportOptions
import it.spesami.core.ExportScope
import it.spesami.core.IngredientDictionary
import it.spesami.core.IngredientResolver
import it.spesami.core.ShoppingExport
import it.spesami.core.ShoppingGroup
import it.spesami.core.ShoppingItem
import it.spesami.core.ShoppingList
import it.spesami.core.ShoppingProgress
import it.spesami.core.Weeks
import java.time.LocalDate

/** Share, copy and PDF all use the saved export settings, so sharing stays one tap. */
class ShoppingExportActions(val text: String, val share: () -> Unit, val copy: () -> Unit, val pdf: PdfExport)

@Composable
fun rememberShoppingExport(vm: AppViewModel, data: AppData, week: LocalDate, options: ExportOptions, pdfPalette: PdfPalette): ShoppingExportActions {
    val context = LocalContext.current
    val sections = ShoppingExport.sections(data, week, options)
    val text = ShoppingExport.toPlainText(sections, options.includeCounts)
    val summary = ShoppingExport.summary(sections, options.scope)
    val markPurchased = options.scope == ExportScope.ALL && options.markPurchased
    val pdf = rememberPdfExport("lista-spesa-$week.pdf", vm::message) {
        PdfExporter.writeShoppingList(sections, options.includeCounts, markPurchased, week, summary, pdfPalette, it)
    }
    return ShoppingExportActions(
        text = text,
        share = { Exports.shareText(context, "Condividi lista della spesa", "Lista della spesa · ${Weeks.rangeLabel(week)}", text) },
        copy = { Exports.copyText(context, "Lista della spesa", text) },
        pdf = pdf,
    )
}

/** The two views of the checklist. */
enum class ChecklistView(val label: String) { PENDING("Da comprare"), PURCHASED("Acquistati") }

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ShoppingScreen(vm: AppViewModel, data: AppData, week: LocalDate, prefs: Preferences, pdfPalette: PdfPalette, onStartShoppingMode: () -> Unit) {
    val options = prefs.export
    val items = ShoppingList.build(data, week)
    val progress = ShoppingList.progress(items)
    var view by rememberSaveable { mutableStateOf(ChecklistView.PENDING) }
    val visible = items.filter { it.purchased == (view == ChecklistView.PURCHASED) }
    val groups = ShoppingList.grouped(data, visible)
    val allGroups = ShoppingList.grouped(data, items)
    val unclassified = allGroups.lastOrNull()?.takeIf { it.category == null }?.items?.size ?: 0
    val mealCount = data.slotsOfWeek(week).size
    val export = rememberShoppingExport(vm, data, week, options, pdfPalette)
    val hasExport = export.text.isNotEmpty()
    var showOptions by remember { mutableStateOf(false) }
    var showAdd by remember { mutableStateOf(false) }
    var confirmReset by remember { mutableStateOf(false) }
    var menu by remember { mutableStateOf(false) }
    // Collapsed category sections, by category id ("none" = Da classificare); kept across rotations.
    var collapsed by rememberSaveable { mutableStateOf(listOf<String>()) }
    fun sectionKey(group: ShoppingGroup) = group.categoryId?.toString() ?: "none"

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Lista della spesa") },
                actions = {
                    IconButton(onClick = { showOptions = true }) { Icon(Icons.Default.Settings, "Esporta lista della spesa") }
                    Box {
                        IconButton(onClick = { menu = true }) { Icon(Icons.Default.MoreVert, "Altre azioni") }
                        DropdownMenu(expanded = menu, onDismissRequest = { menu = false }) {
                            DropdownMenuItem(
                                text = { Text("Aggiungi prodotto") },
                                leadingIcon = { Icon(Icons.Default.Add, null) },
                                onClick = { menu = false; showAdd = true },
                            )
                            DropdownMenuItem(
                                text = { Text("Reimposta acquisti") },
                                leadingIcon = { Icon(Icons.Default.Refresh, null) },
                                enabled = progress.purchased > 0,
                                onClick = { menu = false; confirmReset = true },
                            )
                        }
                    }
                },
            )
        },
        floatingActionButton = {
            ExtendedFloatingActionButton(
                onClick = { showAdd = true },
                icon = { Icon(Icons.Default.Add, null) },
                text = { Text("Aggiungi prodotto") },
            )
        },
    ) { padding ->
        LazyColumn(Modifier.fillMaxSize().padding(padding), contentPadding = PaddingValues(bottom = 96.dp)) {
            item { WeekBar(week, vm::shiftWeek, vm::goToCurrentWeek, Modifier.padding(horizontal = 8.dp)) }
            item {
                Column(Modifier.padding(horizontal = 16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    if (items.isEmpty()) {
                        Text(
                            "Nessun prodotto: pianifica qualche pasto per questa settimana o aggiungi un prodotto.",
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    } else {
                        ShoppingProgressCard(progress, mealCount)
                        SingleChoiceSegmentedButtonRow(Modifier.fillMaxWidth()) {
                            ChecklistView.entries.forEachIndexed { i, v ->
                                val n = if (v == ChecklistView.PENDING) progress.pending else progress.purchased
                                SegmentedButton(
                                    selected = view == v,
                                    onClick = { view = v },
                                    shape = SegmentedButtonDefaults.itemShape(i, ChecklistView.entries.size),
                                ) { Text("${v.label} ($n)") }
                            }
                        }
                    }
                    Button(onClick = onStartShoppingMode, modifier = Modifier.fillMaxWidth().heightIn(min = 56.dp)) {
                        Icon(Icons.Default.ShoppingCart, null)
                        Spacer(Modifier.width(10.dp))
                        Text("Avvia modalità spesa", style = MaterialTheme.typography.titleSmall)
                    }
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                        FilledTonalButton(onClick = export.share, enabled = hasExport, modifier = Modifier.weight(1f)) {
                            Icon(Icons.Default.Share, null)
                            Spacer(Modifier.width(8.dp))
                            Text("Condividi")
                        }
                        OutlinedButton(onClick = export.copy, enabled = hasExport) { Text("Copia") }
                        PdfMenu(export.pdf) { open -> OutlinedButton(onClick = open, enabled = hasExport) { Text("PDF") } }
                    }
                    if (items.isNotEmpty()) {
                        Text(
                            exportSummary(options) + " · Modifica",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.primary,
                            modifier = Modifier.clickable { showOptions = true }.padding(vertical = 4.dp),
                        )
                        if (!hasExport) {
                            Text(
                                if (options.scope == ExportScope.PENDING && progress.pending == 0) "Hai acquistato tutto: non resta nulla da condividere."
                                else "Tutte le categorie di questa settimana sono escluse dall'esportazione.",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                    }
                    if (unclassified > 0) {
                        Text(
                            if (unclassified == 1) "1 prodotto da classificare: usa ⋮ accanto al prodotto per scegliere la categoria."
                            else "$unclassified prodotti da classificare: usa ⋮ accanto a un prodotto per scegliere la categoria.",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.error,
                        )
                    }
                }
            }
            if (items.isNotEmpty() && visible.isEmpty()) {
                item(key = "empty-${view.name}") {
                    if (view == ChecklistView.PENDING) {
                        EmptyState("Hai preso tutto", "Tutti i prodotti della settimana sono acquistati. Li trovi in \"Acquistati\".")
                    } else {
                        EmptyState("Nessun prodotto acquistato", "Spunta un prodotto quando lo metti nel carrello: lo ritroverai qui.")
                    }
                }
            }
            groups.forEach { group ->
                val key = sectionKey(group)
                val isCollapsed = key in collapsed
                item(key = "g-$key") {
                    GroupHeader(group, isCollapsed, Modifier.animateItem()) {
                        collapsed = if (isCollapsed) collapsed - key else collapsed + key
                    }
                }
                if (!isCollapsed) {
                    items(group.items, key = { it.ingredientId }) { item ->
                        ShoppingRow(
                            item = item,
                            data = data,
                            onToggle = { vm.setPurchased(item.ingredientId, it) },
                            onCategory = { vm.setIngredientCategory(item.ingredientId, it) },
                            onRemove = { vm.removeProduct(item.ingredientId) },
                            modifier = Modifier.animateItem(),
                        )
                    }
                }
            }
        }
    }

    if (showOptions) {
        ExportOptionsDialog(vm, data, week, prefs, allGroups, export) { showOptions = false }
    }
    if (showAdd) {
        AddProductDialog(data, onDismiss = { showAdd = false }) { name, categoryId, setCategory ->
            vm.addProduct(name, categoryId, setCategory)
            showAdd = false
        }
    }
    if (confirmReset) {
        AlertDialog(
            onDismissRequest = { confirmReset = false },
            title = { Text("Reimpostare gli acquisti?") },
            text = {
                Text(
                    "Tutti i prodotti di questa settimana tornano da comprare. Piano dei pasti, ricette, categorie, " +
                        "prodotti aggiunti e le altre settimane non cambiano."
                )
            },
            confirmButton = { TextButton(onClick = { confirmReset = false; vm.resetPurchases() }) { Text("Reimposta") } },
            dismissButton = { TextButton(onClick = { confirmReset = false }) { Text("Annulla") } },
        )
    }
}

private fun exportSummary(o: ExportOptions): String {
    val parts = buildList {
        add(o.scope.label.lowercase())
        add(if (o.groupByCategory) (if (o.showHeaders) "per categoria" else "per categoria, senza intestazioni") else "elenco semplice")
        add(if (o.includeCounts) "con conteggi" else "senza conteggi")
        if (o.excludedCategories.isNotEmpty()) {
            add(if (o.excludedCategories.size == 1) "1 categoria esclusa" else "${o.excludedCategories.size} categorie escluse")
        }
    }
    return "Esportazione: " + parts.joinToString(", ")
}

/** "8 di 15 prodotti acquistati", a bar and the percentage. Counts distinct products, not occurrences. */
@Composable
fun ShoppingProgressCard(progress: ShoppingProgress, mealCount: Int, modifier: Modifier = Modifier) {
    val colors = MaterialTheme.colorScheme
    Surface(color = colors.surfaceContainerHigh, shape = RoundedCornerShape(16.dp), modifier = modifier.fillMaxWidth()) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(progress.label, style = MaterialTheme.typography.titleSmall, modifier = Modifier.weight(1f))
                Text(
                    "${progress.percent}%",
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.Bold,
                    color = if (progress.total > 0 && progress.pending == 0) colors.tertiary else colors.primary,
                )
            }
            LinearProgressIndicator(
                progress = { progress.fraction },
                modifier = Modifier.fillMaxWidth().semantics { contentDescription = progress.label },
                color = if (progress.total > 0 && progress.pending == 0) colors.tertiary else colors.primary,
                trackColor = colors.surfaceVariant,
                drawStopIndicator = {},
            )
            Text(
                if (mealCount == 1) "Da 1 pasto pianificato" else "Da $mealCount pasti pianificati",
                style = MaterialTheme.typography.bodySmall,
                color = colors.onSurfaceVariant,
            )
        }
    }
}

@Composable
private fun GroupHeader(group: ShoppingGroup, collapsed: Boolean, modifier: Modifier = Modifier, onToggle: () -> Unit) {
    val color = if (group.category == null) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.primary
    Row(
        modifier
            .fillMaxWidth()
            .clickable(onClickLabel = if (collapsed) "Espandi" else "Comprimi", onClick = onToggle)
            .heightIn(min = 48.dp)
            .padding(start = 16.dp, end = 12.dp, top = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(group.label, style = MaterialTheme.typography.titleSmall, color = color, modifier = Modifier.weight(1f))
        Text("${group.items.size}", style = MaterialTheme.typography.labelMedium, color = color)
        Icon(
            if (collapsed) Icons.Default.KeyboardArrowDown else Icons.Default.KeyboardArrowUp,
            contentDescription = null,
            tint = color,
            modifier = Modifier.padding(start = 4.dp),
        )
    }
}

/**
 * One checklist row: the whole row toggles the purchase (large touch target, no confirmation); ⋮ changes the
 * category or removes a hand-added product. Purchased products are struck through and dimmed.
 */
@Composable
fun ShoppingRow(
    item: ShoppingItem,
    data: AppData,
    onToggle: (Boolean) -> Unit,
    onCategory: (Long?) -> Unit,
    onRemove: () -> Unit,
    modifier: Modifier = Modifier,
    /** Shopping mode: bigger text and taller rows for one-handed use in the shop. */
    large: Boolean = false,
) {
    val colors = MaterialTheme.colorScheme
    var menu by remember { mutableStateOf(false) }
    val detail = when {
        item.count == 0 -> "Aggiunto a mano"
        item.manual -> "Anche aggiunto a mano"
        else -> null
    }
    Column(modifier) {
        Row(
            Modifier
                .fillMaxWidth()
                .toggleable(value = item.purchased, role = Role.Checkbox, onValueChange = onToggle)
                .semantics { stateDescription = if (item.purchased) "Acquistato" else "Da comprare" }
                .heightIn(min = if (large) 68.dp else 56.dp)
                .padding(start = 4.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Checkbox(checked = item.purchased, onCheckedChange = null, modifier = Modifier.padding(12.dp))
            Column(Modifier.weight(1f).padding(vertical = 8.dp)) {
                Text(
                    item.name,
                    style = if (large) MaterialTheme.typography.titleLarge else MaterialTheme.typography.bodyLarge,
                    color = if (item.purchased) colors.onSurfaceVariant else colors.onSurface,
                    textDecoration = if (item.purchased) TextDecoration.LineThrough else null,
                )
                if (detail != null) {
                    Text(detail, style = MaterialTheme.typography.bodySmall, color = colors.onSurfaceVariant)
                }
            }
            if (item.count > 0) {
                Text(
                    "×${item.count}",
                    style = MaterialTheme.typography.labelLarge,
                    fontWeight = FontWeight.Bold,
                    color = if (item.purchased) colors.onSurfaceVariant else colors.onSecondaryContainer,
                    modifier = Modifier
                        .background(if (item.purchased) colors.surfaceVariant else colors.secondaryContainer, RoundedCornerShape(50))
                        .padding(horizontal = 10.dp, vertical = 3.dp),
                )
            }
            Box {
                IconButton(onClick = { menu = true }) { Icon(Icons.Default.MoreVert, "Opzioni per ${item.name}") }
                CategoryMenu(
                    expanded = menu,
                    data = data,
                    selected = item.categoryId,
                    onDismiss = { menu = false },
                    extra = {
                        if (item.manual) {
                            DropdownMenuItem(
                                text = { Text(if (item.count > 0) "Togli l'aggiunta manuale" else "Rimuovi dalla lista") },
                                leadingIcon = { Icon(Icons.Default.Delete, null) },
                                onClick = { menu = false; onRemove() },
                            )
                            HorizontalDivider()
                        }
                        Text(
                            "Categoria",
                            style = MaterialTheme.typography.labelMedium,
                            color = colors.onSurfaceVariant,
                            modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp),
                        )
                    },
                ) {
                    menu = false
                    onCategory(it)
                }
            }
        }
        HorizontalDivider(Modifier.padding(start = 64.dp, end = 16.dp), color = colors.outlineVariant.copy(alpha = 0.4f))
    }
}

/**
 * "Aggiungi prodotto": a product for this week only (detersivo, sapone…). The category is the existing one or
 * the dictionary's guess unless the user picks one; recipes and the plan are never touched.
 */
@Composable
fun AddProductDialog(data: AppData, onDismiss: () -> Unit, onAdd: (name: String, categoryId: Long?, setCategory: Boolean) -> Unit) {
    var name by remember { mutableStateOf("") }
    var chosen by remember { mutableStateOf<Long?>(null) }
    var picked by remember { mutableStateOf(false) }
    var menu by remember { mutableStateOf(false) }
    val resolver = remember(data) { IngredientResolver(data.ingredients, data.aliases) }
    val existing = resolver.resolve(name)?.let { data.ingredientsById[it] }
    val automatic = existing?.let { data.categoryOf(it)?.id }
        ?: IngredientDictionary.suggest(name)?.let { key -> data.categories.firstOrNull { it.builtinKey == key }?.id }
    val shown = if (picked) chosen else automatic
    val shownName = shown?.let { data.categoriesById[it]?.name } ?: DefaultCategories.UNCLASSIFIED_LABEL
    val submit = { if (name.isNotBlank()) onAdd(name, chosen, picked) }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Aggiungi prodotto") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                OutlinedTextField(
                    value = name,
                    onValueChange = { name = it },
                    label = { Text("Prodotto") },
                    placeholder = { Text("Es. Detersivo") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                )
                Box {
                    OutlinedButton(onClick = { menu = true }, modifier = Modifier.fillMaxWidth()) {
                        Text(
                            "Categoria: $shownName" + if (!picked && name.isNotBlank()) (if (existing != null) " (attuale)" else " (automatica)") else "",
                            modifier = Modifier.weight(1f),
                        )
                        Icon(Icons.Default.KeyboardArrowDown, null)
                    }
                    CategoryMenu(menu, data, shown, onDismiss = { menu = false }) {
                        chosen = it
                        picked = true
                        menu = false
                    }
                }
                Text(
                    if (existing != null) "\"${existing.name}\" è già tra gli ingredienti: verrà usato quello, senza doppioni."
                    else "Vale solo per questa settimana. Ricette e piano dei pasti non cambiano.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        },
        confirmButton = { TextButton(onClick = submit, enabled = name.isNotBlank()) { Text("Aggiungi") } },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Annulla") } },
    )
}
