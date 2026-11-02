package it.spesami.app.ui

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material.icons.filled.KeyboardArrowUp
import androidx.compose.material.icons.filled.Search
import androidx.compose.material3.AssistChip
import androidx.compose.material3.Button
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarDuration
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.SnackbarResult
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import it.spesami.app.AppViewModel
import it.spesami.app.data.Preferences
import it.spesami.app.platform.KeepScreenOn
import it.spesami.core.AppData
import it.spesami.core.PurchaseToggle
import it.spesami.core.ShoppingItem
import it.spesami.core.ShoppingMode
import it.spesami.core.ShoppingModeSection
import it.spesami.core.ShoppingModeView
import it.spesami.core.Weeks
import kotlinx.coroutines.launch
import java.time.LocalDate

/** Rows of the shopping-mode list, flattened so the category selector can jump to a heading by index. */
private sealed interface ModeRow {
    val key: String

    data class Heading(val section: ShoppingModeSection, val collapsed: Boolean, val sectionKey: String) : ModeRow {
        override val key get() = "h-$sectionKey"
    }

    data class Item(val item: ShoppingItem) : ModeRow {
        override val key get() = "i-${item.ingredientId}"
    }

    data class PurchasedHeading(val count: Int, val expanded: Boolean) : ModeRow {
        override val key get() = "purchased"
    }
}

/**
 * "Modalità Spesa": the selected week's existing checklist (same data, same purchased marks, same hand-added
 * products) in a full-screen, one-handed layout. Keeps the screen on while visible, if the setting allows it.
 */
@Composable
fun ShoppingModeScreen(
    vm: AppViewModel,
    data: AppData,
    week: LocalDate,
    prefs: Preferences,
    snackbar: SnackbarHostState,
    onExit: () -> Unit,
) {
    KeepScreenOn(enabled = prefs.keepScreenOn)
    BackHandler(onBack = onExit)

    var query by rememberSaveable { mutableStateOf("") }
    var searching by rememberSaveable { mutableStateOf(false) }
    var collapsed by rememberSaveable { mutableStateOf(listOf<String>()) }
    var showPurchased by rememberSaveable { mutableStateOf(false) }
    var showAdd by remember { mutableStateOf(false) }
    val view = ShoppingMode.view(data, week, if (searching) query else "")
    val listState = rememberLazyListState()
    val scope = rememberCoroutineScope()

    fun toggle(item: ShoppingItem, purchased: Boolean) {
        val change = PurchaseToggle(item.ingredientId, item.name, purchased)
        vm.setPurchased(change.ingredientId, change.purchased)
        scope.launch {
            // A newer tap replaces the previous undo offer; nothing ever blocks the list.
            snackbar.currentSnackbarData?.dismiss()
            val result = snackbar.showSnackbar(change.message, PurchaseToggle.UNDO_LABEL, duration = SnackbarDuration.Short)
            if (result == SnackbarResult.ActionPerformed) change.undo().let { vm.setPurchased(it.ingredientId, it.purchased) }
        }
    }

    val purchasedExpanded = showPurchased || view.query.isNotBlank()
    val rows = buildList {
        view.pending.forEach { section ->
            val key = section.group.categoryId?.toString() ?: "none"
            val isCollapsed = key in collapsed && view.query.isBlank()
            add(ModeRow.Heading(section, isCollapsed, key))
            if (!isCollapsed) section.group.items.forEach { add(ModeRow.Item(it)) }
        }
        if (view.purchased.isNotEmpty()) {
            add(ModeRow.PurchasedHeading(view.purchased.size, purchasedExpanded))
            if (purchasedExpanded) view.purchased.forEach { add(ModeRow.Item(it)) }
        }
    }

    Scaffold(
        contentWindowInsets = WindowInsets(0),
        snackbarHost = { SnackbarHost(snackbar) },
        topBar = {
            ModeHeader(
                view = view,
                week = week,
                searching = searching,
                query = query,
                onQuery = { query = it },
                onSearch = { searching = !searching; if (!searching) query = "" },
                onExit = onExit,
            )
        },
        bottomBar = {
            Surface(tonalElevation = 3.dp) {
                Row(
                    Modifier.fillMaxWidth().navigationBarsPadding().padding(horizontal = 16.dp, vertical = 10.dp),
                    horizontalArrangement = Arrangement.spacedBy(10.dp),
                ) {
                    OutlinedButton(onClick = { showAdd = true }, modifier = Modifier.weight(1f).heightIn(min = 52.dp)) {
                        Icon(Icons.Default.Add, null)
                        Spacer(Modifier.width(6.dp))
                        Text("Aggiungi prodotto")
                    }
                    Button(onClick = onExit, modifier = Modifier.weight(1f).heightIn(min = 52.dp)) {
                        Icon(Icons.Default.Check, null)
                        Spacer(Modifier.width(6.dp))
                        Text("Termina spesa")
                    }
                }
            }
        },
    ) { padding ->
        Column(Modifier.fillMaxSize().padding(padding)) {
            // Quick category navigation: jumps (no animation) to a pending section.
            val headings = rows.withIndex().filter { it.value is ModeRow.Heading }
            if (headings.size > 1) {
                LazyRow(
                    contentPadding = PaddingValues(horizontal = 12.dp, vertical = 4.dp),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    items(headings, key = { it.value.key }) { (index, row) ->
                        val section = (row as ModeRow.Heading).section
                        AssistChip(
                            onClick = { scope.launch { listState.scrollToItem(index) } },
                            label = { Text("${section.group.label} ${section.pending}") },
                        )
                    }
                }
            }
            LazyColumn(state = listState, modifier = Modifier.fillMaxSize(), contentPadding = PaddingValues(bottom = 16.dp)) {
                when {
                    view.isEmpty -> item(key = "empty") {
                        EmptyState("La lista è vuota", "Pianifica qualche pasto per questa settimana o aggiungi un prodotto.")
                    }
                    view.noMatches -> item(key = "nomatch") {
                        EmptyState("Nessun prodotto trovato", "Nessun prodotto della lista contiene \"${view.query.trim()}\".")
                    }
                    view.isComplete && view.query.isBlank() -> item(key = "done") { CompletedCard(view) }
                }
                items(rows, key = { it.key }) { row ->
                    when (row) {
                        is ModeRow.Heading -> ModeSectionHeading(row.section.label, row.collapsed, error = row.section.group.category == null) {
                            collapsed = if (row.collapsed) collapsed - row.sectionKey else collapsed + row.sectionKey
                        }
                        is ModeRow.PurchasedHeading -> ModeSectionHeading("${ShoppingModeView.PURCHASED_SECTION} (${row.count})", !row.expanded, muted = true) {
                            showPurchased = !row.expanded
                        }
                        is ModeRow.Item -> ShoppingRow(
                            item = row.item,
                            data = data,
                            onToggle = { toggle(row.item, it) },
                            onCategory = { vm.setIngredientCategory(row.item.ingredientId, it) },
                            onRemove = { vm.removeProduct(row.item.ingredientId) },
                            large = true,
                        )
                    }
                }
            }
        }
    }

    if (showAdd) {
        AddProductDialog(data, onDismiss = { showAdd = false }) { name, categoryId, setCategory ->
            vm.addProduct(name, categoryId, setCategory)
            showAdd = false
        }
    }
}

@Composable
private fun ModeHeader(
    view: ShoppingModeView,
    week: LocalDate,
    searching: Boolean,
    query: String,
    onQuery: (String) -> Unit,
    onSearch: () -> Unit,
    onExit: () -> Unit,
) {
    val colors = MaterialTheme.colorScheme
    val done = view.isComplete
    Surface(color = colors.surfaceContainer) {
        Column(Modifier.fillMaxWidth().statusBarsPadding().padding(start = 16.dp, end = 4.dp, top = 4.dp, bottom = 12.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text("Modalità Spesa", style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold, modifier = Modifier.semantics { heading() })
                    Text(Weeks.rangeLabel(week), style = MaterialTheme.typography.labelMedium, color = colors.onSurfaceVariant)
                }
                IconButton(onClick = onSearch) {
                    Icon(if (searching) Icons.Default.Close else Icons.Default.Search, if (searching) "Chiudi la ricerca" else "Cerca un prodotto")
                }
                IconButton(onClick = onExit) { Icon(Icons.Default.Close, "Esci dalla modalità spesa") }
            }
            Column(Modifier.padding(end = 12.dp, top = 6.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                Text(
                    if (done) ShoppingModeView.COMPLETED else view.remainingLabel,
                    style = MaterialTheme.typography.headlineSmall,
                    fontWeight = FontWeight.SemiBold,
                    color = if (done) colors.tertiary else colors.onSurface,
                )
                LinearProgressIndicator(
                    progress = { view.progress.fraction },
                    modifier = Modifier.fillMaxWidth().semantics { contentDescription = view.progress.label },
                    color = if (done) colors.tertiary else colors.primary,
                    trackColor = colors.surfaceVariant,
                    drawStopIndicator = {},
                )
                Text(view.progress.label + " · ${view.progress.percent}%", style = MaterialTheme.typography.bodyMedium, color = colors.onSurfaceVariant)
                if (searching) {
                    OutlinedTextField(
                        value = query,
                        onValueChange = onQuery,
                        placeholder = { Text("Cerca nella lista") },
                        leadingIcon = { Icon(Icons.Default.Search, null) },
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth(),
                    )
                }
            }
        }
    }
}

@Composable
private fun ModeSectionHeading(label: String, collapsed: Boolean, error: Boolean = false, muted: Boolean = false, onToggle: () -> Unit) {
    val colors = MaterialTheme.colorScheme
    val color = when {
        error -> colors.error
        muted -> colors.onSurfaceVariant
        else -> colors.primary
    }
    Column {
        Row(
            Modifier
                .fillMaxWidth()
                .clickable(onClickLabel = if (collapsed) "Espandi" else "Comprimi", onClick = onToggle)
                .heightIn(min = 52.dp)
                .padding(start = 16.dp, end = 12.dp, top = 8.dp)
                .semantics { heading() },
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(label, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold, color = color, modifier = Modifier.weight(1f))
            Icon(if (collapsed) Icons.Default.KeyboardArrowDown else Icons.Default.KeyboardArrowUp, null, tint = color)
        }
        HorizontalDivider(Modifier.padding(horizontal = 16.dp), color = color.copy(alpha = 0.5f))
    }
}

/** Subtle: the list and its ticks stay as they are. */
@Composable
private fun CompletedCard(view: ShoppingModeView) {
    Surface(
        color = MaterialTheme.colorScheme.tertiaryContainer,
        shape = RoundedCornerShape(16.dp),
        modifier = Modifier.fillMaxWidth().padding(16.dp),
    ) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Text(ShoppingModeView.COMPLETED, style = MaterialTheme.typography.titleMedium, color = MaterialTheme.colorScheme.onTertiaryContainer)
            Text(
                (if (view.progress.total == 1) "Hai preso l'unico prodotto della lista." else "Hai preso tutti i ${view.progress.total} prodotti.") +
                    " Le spunte restano salvate: le trovi in \"Già acquistati\".",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onTertiaryContainer,
            )
        }
    }
}
