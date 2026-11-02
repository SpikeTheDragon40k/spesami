package it.spesami.app.ui

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.ArrowDropDown
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Search
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.InputChip
import androidx.compose.material3.InputChipDefaults
import androidx.compose.material3.ListItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
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
import androidx.compose.ui.unit.dp
import it.spesami.app.AppViewModel
import it.spesami.core.AppData
import it.spesami.core.Category
import it.spesami.core.Ingredient
import java.text.Collator
import java.util.Locale

private val italian: Collator = Collator.getInstance(Locale.ITALIAN).apply { strength = Collator.PRIMARY }

/** Canonical ingredients, their categories and aliases: what makes "patata" and "Patate" one shopping item. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun IngredientsScreen(vm: AppViewModel, data: AppData, onBack: () -> Unit) {
    var query by rememberSaveable { mutableStateOf("") }
    var editing by remember { mutableStateOf<Long?>(null) }
    val aliasesById = data.aliases.groupBy { it.ingredientId }
    val usage = data.recipes.flatMap { it.ingredientIds }.groupingBy { it }.eachCount()
    val q = query.trim()
    val list = data.ingredients
        .filter { i -> q.isEmpty() || i.name.contains(q, true) || aliasesById[i.id].orEmpty().any { it.alias.contains(q, true) } }
        .sortedWith { a, b -> italian.compare(a.name, b.name) }

    BackHandler(onBack = onBack)
    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Ingredienti e alias") },
                navigationIcon = { IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, "Indietro") } },
            )
        },
    ) { padding ->
        Column(Modifier.fillMaxSize().padding(padding)) {
            OutlinedTextField(
                value = query,
                onValueChange = { query = it },
                leadingIcon = { Icon(Icons.Default.Search, null) },
                placeholder = { Text("Cerca ingrediente o alias") },
                singleLine = true,
                modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp),
            )
            LazyColumn(contentPadding = PaddingValues(bottom = 24.dp)) {
                if (data.ingredients.isEmpty()) {
                    item { EmptyState("Nessun ingrediente", "Gli ingredienti vengono creati automaticamente quando li aggiungi a una ricetta.") }
                }
                items(list, key = { it.id }) { ingredient ->
                    val aliases = aliasesById[ingredient.id].orEmpty()
                    val details = listOfNotNull(
                        ingredient.category?.label,
                        aliases.takeIf { it.isNotEmpty() }?.joinToString(", ", prefix = "anche: ") { it.alias },
                    ).joinToString(" · ")
                    ListItem(
                        headlineContent = { Text(ingredient.name) },
                        supportingContent = if (details.isEmpty()) null else { { Text(details) } },
                        trailingContent = {
                            val n = usage[ingredient.id] ?: 0
                            Text(if (n == 1) "1 ricetta" else "$n ricette", style = MaterialTheme.typography.labelMedium)
                        },
                        modifier = Modifier.clickable { editing = ingredient.id },
                    )
                    HorizontalDivider(Modifier.padding(horizontal = 16.dp), color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.4f))
                }
            }
        }
    }

    // Looked up on every recomposition so alias changes and merges show up immediately.
    editing?.let { id ->
        val ingredient = data.ingredientsById[id]
        if (ingredient == null) editing = null else IngredientDialog(vm, data, ingredient, usage[id] ?: 0) { editing = null }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun IngredientDialog(vm: AppViewModel, data: AppData, ingredient: Ingredient, usedBy: Int, onClose: () -> Unit) {
    var name by remember(ingredient.id) { mutableStateOf(ingredient.name) }
    var category by remember(ingredient.id) { mutableStateOf(ingredient.category) }
    var alias by remember(ingredient.id) { mutableStateOf("") }
    var categoryMenu by remember { mutableStateOf(false) }
    var merging by remember { mutableStateOf(false) }
    var confirmDelete by remember { mutableStateOf(false) }
    val aliases = data.aliases.filter { it.ingredientId == ingredient.id }

    AlertDialog(
        onDismissRequest = onClose,
        title = { Text(ingredient.name) },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                OutlinedTextField(value = name, onValueChange = { name = it }, label = { Text("Nome") }, singleLine = true)
                Box {
                    OutlinedButton(onClick = { categoryMenu = true }, modifier = Modifier.fillMaxWidth()) {
                        Text(category?.label ?: "Nessuna categoria", Modifier.weight(1f))
                        Icon(Icons.Default.ArrowDropDown, null)
                    }
                    DropdownMenu(expanded = categoryMenu, onDismissRequest = { categoryMenu = false }) {
                        DropdownMenuItem(text = { Text("Nessuna categoria") }, onClick = { category = null; categoryMenu = false })
                        Category.entries.forEach { c ->
                            DropdownMenuItem(text = { Text(c.label) }, onClick = { category = c; categoryMenu = false })
                        }
                    }
                }
                Text("Alias", style = MaterialTheme.typography.titleSmall, color = MaterialTheme.colorScheme.primary)
                Text(
                    "Nomi alternativi che indicano lo stesso ingrediente (es. \"Patata\" per \"Patate\"). " +
                        "Se l'alias è il nome di un altro ingrediente, i due vengono uniti.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                if (aliases.isNotEmpty()) {
                    FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        aliases.forEach { a ->
                            InputChip(
                                selected = false,
                                onClick = { vm.removeAlias(a.id) },
                                label = { Text(a.alias) },
                                trailingIcon = { Icon(Icons.Default.Close, "Rimuovi alias ${a.alias}", Modifier.size(InputChipDefaults.IconSize)) },
                            )
                        }
                    }
                }
                Row(verticalAlignment = Alignment.CenterVertically) {
                    OutlinedTextField(
                        value = alias,
                        onValueChange = { alias = it },
                        label = { Text("Nuovo alias") },
                        singleLine = true,
                        modifier = Modifier.weight(1f),
                    )
                    IconButton(enabled = alias.isNotBlank(), onClick = { vm.addAlias(ingredient.id, alias); alias = "" }) {
                        Icon(Icons.Default.Add, "Aggiungi alias")
                    }
                }
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedButton(onClick = { merging = true }, enabled = data.ingredients.size > 1) { Text("Unisci in…") }
                    TextButton(onClick = { confirmDelete = true }) { Text("Elimina", color = MaterialTheme.colorScheme.error) }
                }
            }
        },
        confirmButton = {
            TextButton(
                enabled = name.isNotBlank(),
                onClick = {
                    if (name != ingredient.name || category != ingredient.category) vm.updateIngredient(ingredient.id, name, category)
                    onClose()
                },
            ) { Text("Salva") }
        },
        dismissButton = { TextButton(onClick = onClose) { Text("Annulla") } },
    )

    if (merging) {
        var query by remember { mutableStateOf("") }
        val targets = data.ingredients
            .filter { it.id != ingredient.id && (query.isBlank() || it.name.contains(query.trim(), true)) }
            .sortedWith { a, b -> italian.compare(a.name, b.name) }
        AlertDialog(
            onDismissRequest = { merging = false },
            title = { Text("Unisci \"${ingredient.name}\" in…") },
            text = {
                Column {
                    Text(
                        "\"${ingredient.name}\" diventerà un alias dell'ingrediente scelto, in tutte le ricette.",
                        style = MaterialTheme.typography.bodySmall,
                    )
                    OutlinedTextField(value = query, onValueChange = { query = it }, singleLine = true, placeholder = { Text("Cerca") })
                    LazyColumn(Modifier.heightIn(max = 320.dp)) {
                        items(targets, key = { it.id }) { target ->
                            ListItem(
                                headlineContent = { Text(target.name) },
                                modifier = Modifier.clickable {
                                    vm.mergeIngredient(ingredient.id, target.id)
                                    vm.message("\"${ingredient.name}\" unito in \"${target.name}\".")
                                    merging = false
                                    onClose()
                                },
                            )
                        }
                    }
                }
            },
            confirmButton = {},
            dismissButton = { TextButton(onClick = { merging = false }) { Text("Annulla") } },
        )
    }

    if (confirmDelete) {
        AlertDialog(
            onDismissRequest = { confirmDelete = false },
            title = { Text("Eliminare \"${ingredient.name}\"?") },
            text = {
                Text(
                    if (usedBy == 0) "L'ingrediente e i suoi alias verranno eliminati."
                    else "L'ingrediente verrà tolto da $usedBy ricette e non comparirà più nella lista della spesa."
                )
            },
            confirmButton = {
                TextButton(onClick = { confirmDelete = false; vm.deleteIngredient(ingredient.id); onClose() }) { Text("Elimina") }
            },
            dismissButton = { TextButton(onClick = { confirmDelete = false }) { Text("Annulla") } },
        )
    }
}
