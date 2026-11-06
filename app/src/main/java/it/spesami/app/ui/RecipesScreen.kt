package it.spesami.app.ui

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Search
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExtendedFloatingActionButton
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.ListItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import it.spesami.core.AppData

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun RecipesScreen(data: AppData, onEdit: (RecipeEditRequest) -> Unit, onOpenIngredients: () -> Unit) {
    var query by rememberSaveable { mutableStateOf("") }
    val q = query.trim()
    val recipes = data.recipes.filter { r ->
        q.isEmpty() || r.name.contains(q, ignoreCase = true) ||
            r.ingredientIds.any { data.ingredientsById[it]?.name?.contains(q, ignoreCase = true) == true }
    }.sortedByName()
    val uses = data.slots.groupingBy { it.recipeId }.eachCount()

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Ricette") },
                actions = { TextButton(onClick = onOpenIngredients) { Text("Ingredienti") } },
            )
        },
        floatingActionButton = {
            ExtendedFloatingActionButton(
                onClick = { onEdit(RecipeEditRequest(null, null)) },
                icon = { Icon(Icons.Default.Add, null) },
                text = { Text("Nuova ricetta") },
            )
        },
    ) { padding ->
        Column(Modifier.fillMaxSize().padding(padding)) {
            if (data.recipes.isNotEmpty()) {
                OutlinedTextField(
                    value = query,
                    onValueChange = { query = it },
                    leadingIcon = { Icon(Icons.Default.Search, null) },
                    placeholder = { Text("Cerca per nome o ingrediente") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp),
                )
            }
            LazyColumn(contentPadding = PaddingValues(bottom = 88.dp)) {
                if (data.recipes.isEmpty()) {
                    item {
                        EmptyState(
                            "Nessuna ricetta salvata",
                            "Crea una ricetta con il suo elenco di ingredienti: potrai riusarla in qualsiasi pranzo o cena.",
                        )
                    }
                } else if (recipes.isEmpty()) {
                    item { EmptyState("Nessun risultato", "Nessuna ricetta corrisponde a \"$q\".") }
                }
                items(recipes, key = { it.id }) { recipe ->
                    val ingredients = recipe.ingredientIds.mapNotNull { data.ingredientsById[it]?.name }
                    val used = uses[recipe.id] ?: 0
                    ListItem(
                        leadingContent = { RecipeColorDot(recipe.color) },
                        headlineContent = { Text(recipe.name) },
                        supportingContent = {
                            Text(
                                if (ingredients.isEmpty()) "Nessun ingrediente" else ingredients.joinToString(", "),
                                maxLines = 2,
                                overflow = TextOverflow.Ellipsis,
                            )
                        },
                        trailingContent = if (used > 0) {
                            { Text("${used}×", style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.primary) }
                        } else null,
                        modifier = Modifier.clickable { onEdit(RecipeEditRequest(recipe, null)) },
                    )
                    HorizontalDivider(Modifier.padding(horizontal = 16.dp), color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.4f))
                }
            }
        }
    }
}
