package it.spesami.app.ui

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.InputChip
import androidx.compose.material3.InputChipDefaults
import androidx.compose.material3.ListItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SuggestionChip
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import it.spesami.core.AppData
import it.spesami.core.IngredientNames
import it.spesami.core.IngredientResolver
import it.spesami.core.MealType
import it.spesami.core.Recipe
import java.text.Collator
import java.util.Locale

/** Open the editor for [recipe] (null = new); a new recipe is put in [assignTo] of the current week when saved. */
data class RecipeEditRequest(val recipe: Recipe?, val assignTo: Pair<Int, MealType>?)

private val italian: Collator = Collator.getInstance(Locale.ITALIAN).apply { strength = Collator.PRIMARY }

fun List<Recipe>.sortedByName() = sortedWith { a, b -> italian.compare(a.name, b.name) }

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun RecipePickerSheet(
    title: String,
    data: AppData,
    current: Recipe?,
    onDismiss: () -> Unit,
    onPick: (Recipe) -> Unit,
    onRemove: () -> Unit,
    onNew: () -> Unit,
    onEdit: (Recipe) -> Unit,
) {
    var query by remember { mutableStateOf("") }
    val recipes = data.recipes
        .filter { query.isBlank() || it.name.contains(query.trim(), ignoreCase = true) }
        .sortedByName()
    ModalBottomSheet(onDismissRequest = onDismiss, sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)) {
        Column(Modifier.fillMaxWidth().padding(horizontal = 16.dp)) {
            Text(title, style = MaterialTheme.typography.titleLarge)
            if (current != null) {
                Text("Attuale: ${current.name}", style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                Row(Modifier.padding(top = 8.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedButton(onClick = { onEdit(current) }) {
                        Icon(Icons.Default.Edit, null)
                        Spacer(Modifier.padding(start = 6.dp))
                        Text("Modifica ricetta")
                    }
                    OutlinedButton(onClick = onRemove) {
                        Icon(Icons.Default.Delete, null)
                        Spacer(Modifier.padding(start = 6.dp))
                        Text("Rimuovi")
                    }
                }
            }
            Button(onClick = onNew, modifier = Modifier.fillMaxWidth().padding(top = 12.dp)) {
                Icon(Icons.Default.Add, null)
                Spacer(Modifier.padding(start = 6.dp))
                Text("Nuova ricetta")
            }
            if (data.recipes.isNotEmpty()) {
                OutlinedTextField(
                    value = query,
                    onValueChange = { query = it },
                    label = { Text("Cerca tra le ricette salvate") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth().padding(top = 12.dp),
                )
            }
        }
        LazyColumn(Modifier.fillMaxWidth().navigationBarsPadding()) {
            items(recipes, key = { it.id }) { recipe ->
                val ingredients = recipe.ingredientIds.mapNotNull { data.ingredientsById[it]?.name }.joinToString(", ")
                ListItem(
                    headlineContent = { Text(recipe.name) },
                    supportingContent = if (ingredients.isEmpty()) null else {
                        { Text(ingredients, maxLines = 1, overflow = TextOverflow.Ellipsis) }
                    },
                    modifier = Modifier.clickable { onPick(recipe) },
                )
            }
            if (data.recipes.isNotEmpty() && recipes.isEmpty()) {
                item { EmptyState("Nessuna ricetta trovata", "Prova un'altra ricerca o crea una nuova ricetta.") }
            }
            item { Spacer(Modifier.height(24.dp)) }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class)
@Composable
fun RecipeEditor(
    request: RecipeEditRequest,
    data: AppData,
    onSave: (name: String, notes: String, ingredients: List<String>) -> Unit,
    onDelete: (() -> Unit)?,
    onClose: () -> Unit,
) {
    val recipe = request.recipe
    var name by rememberSaveable { mutableStateOf(recipe?.name.orEmpty()) }
    var notes by rememberSaveable { mutableStateOf(recipe?.notes.orEmpty()) }
    val ingredients = remember {
        mutableStateListOf<String>().apply { recipe?.ingredientIds?.mapNotNullTo(this) { data.ingredientsById[it]?.name } }
    }
    var input by rememberSaveable { mutableStateOf("") }
    var confirmDelete by remember { mutableStateOf(false) }
    val resolver = remember(data) { IngredientResolver(data.ingredients, data.aliases) }

    // Typed names are shown in their canonical form ("patata" -> "Patate" when it is an alias), never twice.
    fun addTyped(text: String) {
        text.split(',', ';', '\n').map { it.trim() }.filter { it.isNotEmpty() }.forEach { typed ->
            val canonical = resolver.resolve(typed)?.let { data.ingredientsById[it]?.name } ?: IngredientNames.clean(typed)
            if (ingredients.none { IngredientNames.key(it) == IngredientNames.key(canonical) }) ingredients += canonical
        }
        input = ""
    }

    val suggestions = remember(input, data, ingredients.toList()) {
        val key = IngredientNames.key(input)
        if (key.isEmpty()) emptyList() else {
            val known = data.ingredients.map { it.name } + data.aliases.map { it.alias }
            known.filter { IngredientNames.key(it).contains(key) && IngredientNames.key(it) != key }
                .filter { s -> ingredients.none { IngredientNames.key(it) == IngredientNames.key(s) } }
                .sortedBy { !IngredientNames.key(it).startsWith(key) }
                .take(6)
        }
    }

    BackHandler(onBack = onClose)
    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(if (recipe == null) "Nuova ricetta" else "Modifica ricetta") },
                navigationIcon = { IconButton(onClick = onClose) { Icon(Icons.Default.Close, "Chiudi") } },
                actions = {
                    if (onDelete != null) IconButton(onClick = { confirmDelete = true }) { Icon(Icons.Default.Delete, "Elimina ricetta") }
                    TextButton(
                        enabled = name.isNotBlank(),
                        onClick = {
                            if (input.isNotBlank()) addTyped(input)
                            onSave(name, notes, ingredients.toList())
                        },
                    ) { Text("Salva") }
                },
            )
        },
    ) { padding ->
        Column(
            Modifier.fillMaxSize().padding(padding).imePadding().verticalScroll(rememberScrollState()).padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            OutlinedTextField(
                value = name,
                onValueChange = { name = it },
                label = { Text("Nome del pasto") },
                placeholder = { Text("Es. Frittata di spinaci") },
                singleLine = true,
                keyboardOptions = KeyboardOptions(capitalization = KeyboardCapitalization.Sentences, imeAction = ImeAction.Next),
                modifier = Modifier.fillMaxWidth(),
            )
            Text("Ingredienti", style = MaterialTheme.typography.titleSmall, color = MaterialTheme.colorScheme.primary)
            Row(verticalAlignment = Alignment.CenterVertically) {
                OutlinedTextField(
                    value = input,
                    onValueChange = { input = it },
                    label = { Text("Aggiungi ingrediente") },
                    supportingText = { Text("Solo il nome, senza quantità. Puoi separarne più di uno con la virgola.") },
                    singleLine = true,
                    keyboardOptions = KeyboardOptions(capitalization = KeyboardCapitalization.Sentences, imeAction = ImeAction.Done),
                    keyboardActions = KeyboardActions(onDone = { addTyped(input) }),
                    modifier = Modifier.weight(1f),
                )
                IconButton(onClick = { addTyped(input) }, enabled = input.isNotBlank()) { Icon(Icons.Default.Add, "Aggiungi") }
            }
            if (suggestions.isNotEmpty()) {
                FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    suggestions.forEach { s -> SuggestionChip(onClick = { addTyped(s) }, label = { Text(s) }) }
                }
            }
            if (ingredients.isEmpty()) {
                Text("Nessun ingrediente.", color = MaterialTheme.colorScheme.onSurfaceVariant)
            } else {
                FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    ingredients.forEach { ingredient ->
                        InputChip(
                            selected = false,
                            onClick = { ingredients.remove(ingredient) },
                            label = { Text(ingredient) },
                            trailingIcon = { Icon(Icons.Default.Close, "Rimuovi $ingredient", Modifier.size(InputChipDefaults.IconSize)) },
                            colors = InputChipDefaults.inputChipColors(containerColor = MaterialTheme.colorScheme.secondaryContainer),
                        )
                    }
                }
            }
            OutlinedTextField(
                value = notes,
                onValueChange = { notes = it },
                label = { Text("Note (facoltative)") },
                minLines = 3,
                modifier = Modifier.fillMaxWidth(),
            )
            if (request.assignTo != null) {
                Text(
                    "Al salvataggio la ricetta viene aggiunta a ${request.assignTo.second.label.lowercase()} del giorno scelto e resta disponibile per altri pasti.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            FilledTonalButton(
                enabled = name.isNotBlank(),
                onClick = {
                    if (input.isNotBlank()) addTyped(input)
                    onSave(name, notes, ingredients.toList())
                },
                modifier = Modifier.fillMaxWidth(),
            ) { Text("Salva ricetta") }
        }
    }

    if (confirmDelete && onDelete != null) {
        val uses = data.slots.count { it.recipeId == recipe?.id }
        AlertDialog(
            onDismissRequest = { confirmDelete = false },
            title = { Text("Eliminare la ricetta?") },
            text = {
                Text(
                    if (uses == 0) "\"${recipe?.name}\" verrà eliminata."
                    else "\"${recipe?.name}\" verrà eliminata e rimossa da $uses pasti del piano."
                )
            },
            confirmButton = { TextButton(onClick = { confirmDelete = false; onDelete() }) { Text("Elimina") } },
            dismissButton = { TextButton(onClick = { confirmDelete = false }) { Text("Annulla") } },
        )
    }
}
