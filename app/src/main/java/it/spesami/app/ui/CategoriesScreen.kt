package it.spesami.app.ui

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material.icons.filled.KeyboardArrowUp
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExtendedFloatingActionButton
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
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
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import it.spesami.app.AppViewModel
import it.spesami.core.AppData
import it.spesami.core.DefaultCategories
import it.spesami.core.ShoppingCategory

/** Create, rename, reorder and delete shopping categories. The order here is the order of the list and PDFs. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun CategoriesScreen(vm: AppViewModel, data: AppData, onBack: () -> Unit) {
    val categories = data.sortedCategories
    val usage = data.ingredients.groupingBy { it.categoryId }.eachCount()
    var creating by remember { mutableStateOf(false) }
    var renaming by remember { mutableStateOf<ShoppingCategory?>(null) }
    var deleting by remember { mutableStateOf<ShoppingCategory?>(null) }

    BackHandler(onBack = onBack)
    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Categorie della spesa") },
                navigationIcon = { IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, "Indietro") } },
            )
        },
        floatingActionButton = {
            ExtendedFloatingActionButton(onClick = { creating = true }, icon = { Icon(Icons.Default.Add, null) }, text = { Text("Nuova categoria") })
        },
    ) { padding ->
        LazyColumn(Modifier.fillMaxSize().padding(padding), contentPadding = PaddingValues(bottom = 88.dp)) {
            item {
                Text(
                    "L'ordine delle categorie è quello della lista della spesa e dei PDF: sistemale come il percorso nel tuo supermercato. Tocca una categoria per rinominarla.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(16.dp),
                )
            }
            itemsIndexed(categories, key = { _, c -> c.id }) { index, category ->
                val n = usage[category.id] ?: 0
                val isOther = category.builtinKey == DefaultCategories.OTHER
                ListItem(
                    headlineContent = { Text(category.name) },
                    supportingContent = {
                        Text(
                            (if (n == 1) "1 ingrediente" else "$n ingredienti") +
                                if (isOther) " · riceve gli ingredienti delle categorie eliminate" else ""
                        )
                    },
                    trailingContent = {
                        Row {
                            IconButton(onClick = { vm.moveCategory(category.id, -1) }, enabled = index > 0) {
                                Icon(Icons.Default.KeyboardArrowUp, "Sposta su ${category.name}")
                            }
                            IconButton(onClick = { vm.moveCategory(category.id, 1) }, enabled = index < categories.lastIndex) {
                                Icon(Icons.Default.KeyboardArrowDown, "Sposta giù ${category.name}")
                            }
                            IconButton(onClick = { deleting = category }, enabled = !isOther) {
                                Icon(Icons.Default.Delete, "Elimina ${category.name}")
                            }
                        }
                    },
                    modifier = Modifier.clickable { renaming = category },
                )
                HorizontalDivider(Modifier.padding(horizontal = 16.dp), color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.4f))
            }
        }
    }

    if (creating) {
        NameDialog("Nuova categoria", "", "Crea", onDismiss = { creating = false }) {
            vm.createCategory(it)
            creating = false
        }
    }
    renaming?.let { category ->
        NameDialog("Rinomina categoria", category.name, "Salva", onDismiss = { renaming = null }) {
            vm.renameCategory(category.id, it)
            renaming = null
        }
    }
    deleting?.let { category ->
        val n = usage[category.id] ?: 0
        val other = categories.firstOrNull { it.builtinKey == DefaultCategories.OTHER }?.name ?: "Altro"
        AlertDialog(
            onDismissRequest = { deleting = null },
            title = { Text("Eliminare \"${category.name}\"?") },
            text = {
                Text(
                    when (n) {
                        0 -> "La categoria non contiene ingredienti."
                        1 -> "L'ingrediente di questa categoria passerà a \"$other\"."
                        else -> "Gli $n ingredienti di questa categoria passeranno a \"$other\"."
                    } + " Ricette e piani non cambiano."
                )
            },
            confirmButton = { TextButton(onClick = { vm.deleteCategory(category.id); deleting = null }) { Text("Elimina") } },
            dismissButton = { TextButton(onClick = { deleting = null }) { Text("Annulla") } },
        )
    }
}

@Composable
private fun NameDialog(title: String, initial: String, confirm: String, onDismiss: () -> Unit, onConfirm: (String) -> Unit) {
    var name by remember { mutableStateOf(initial) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(title) },
        text = { OutlinedTextField(value = name, onValueChange = { name = it }, label = { Text("Nome") }, singleLine = true) },
        confirmButton = { TextButton(onClick = { onConfirm(name) }, enabled = name.isNotBlank()) { Text(confirm) } },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Annulla") } },
    )
}
