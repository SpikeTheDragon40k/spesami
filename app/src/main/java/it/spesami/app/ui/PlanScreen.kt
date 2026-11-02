package it.spesami.app.ui

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.Share
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import it.spesami.app.AppViewModel
import it.spesami.app.pdf.PdfExporter
import it.spesami.app.pdf.PdfPalette
import it.spesami.core.AppData
import it.spesami.core.MealType
import it.spesami.core.Recipe
import it.spesami.core.Weeks
import java.time.LocalDate

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun PlanScreen(vm: AppViewModel, data: AppData, week: LocalDate, pdfPalette: PdfPalette, onEditRecipe: (RecipeEditRequest) -> Unit) {
    val slots = data.slotsOfWeek(week).associateBy { it.day to it.meal }
    var picking by remember { mutableStateOf<Pair<Int, MealType>?>(null) }
    var menu by remember { mutableStateOf(false) }
    var confirmClear by remember { mutableStateOf(false) }
    val pdf = rememberPdfExport("piano-settimanale-$week.pdf", vm::message) {
        PdfExporter.writeWeekPlan(data, week, pdfPalette, it)
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Piano settimanale") },
                actions = {
                    PdfMenu(pdf) { open -> IconButton(onClick = open) { Icon(Icons.Default.Share, "Esporta il piano in PDF") } }
                    IconButton(onClick = { menu = true }) { Icon(Icons.Default.MoreVert, "Altre azioni") }
                    DropdownMenu(expanded = menu, onDismissRequest = { menu = false }) {
                        DropdownMenuItem(text = { Text("Esporta PDF del piano…") }, onClick = { menu = false; pdf.save() })
                        DropdownMenuItem(text = { Text("Copia settimana precedente") }, onClick = { menu = false; vm.copyPreviousWeek() })
                        DropdownMenuItem(
                            text = { Text("Svuota settimana") },
                            enabled = slots.isNotEmpty(),
                            onClick = { menu = false; confirmClear = true },
                        )
                    }
                },
            )
        },
    ) { padding ->
        LazyColumn(
            Modifier.fillMaxSize().padding(padding),
            contentPadding = PaddingValues(start = 12.dp, end = 12.dp, bottom = 16.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            item { WeekBar(week, vm::shiftWeek, vm::goToCurrentWeek) }
            items((0..6).toList()) { day ->
                DayCard(data, week, day, isToday = week.plusDays(day.toLong()) == LocalDate.now(), slots = { slots[day to it]?.recipeId }) {
                    picking = day to it
                }
            }
        }
    }

    picking?.let { (day, meal) ->
        val current = slots[day to meal]?.let { data.recipesById[it.recipeId] }
        RecipePickerSheet(
            title = "${meal.label} · ${Weeks.dayLabel(week, day)}",
            data = data,
            current = current,
            onDismiss = { picking = null },
            onPick = { vm.setMeal(day, meal, it.id); picking = null },
            onRemove = { vm.setMeal(day, meal, null); picking = null },
            onNew = { picking = null; onEditRecipe(RecipeEditRequest(null, assignTo = day to meal)) },
            onEdit = { picking = null; onEditRecipe(RecipeEditRequest(it, assignTo = null)) },
        )
    }

    if (confirmClear) {
        AlertDialog(
            onDismissRequest = { confirmClear = false },
            title = { Text("Svuotare la settimana?") },
            text = { Text("Tutti i pasti di ${Weeks.rangeLabel(week)} verranno rimossi dal piano. Le ricette restano salvate.") },
            confirmButton = { TextButton(onClick = { confirmClear = false; vm.clearWeek() }) { Text("Svuota") } },
            dismissButton = { TextButton(onClick = { confirmClear = false }) { Text("Annulla") } },
        )
    }
}

@Composable
private fun DayCard(data: AppData, week: LocalDate, day: Int, isToday: Boolean, slots: (MealType) -> Long?, onPick: (MealType) -> Unit) {
    val colors = MaterialTheme.colorScheme
    Card(
        colors = CardDefaults.cardColors(containerColor = colors.surfaceContainerLow),
        border = if (isToday) BorderStroke(1.5.dp, colors.primary) else null,
    ) {
        Column(Modifier.padding(vertical = 8.dp)) {
            Row(Modifier.padding(horizontal = 16.dp, vertical = 4.dp), verticalAlignment = Alignment.CenterVertically) {
                Text(
                    Weeks.dayLabel(week, day),
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.SemiBold,
                    color = if (isToday) colors.primary else colors.onSurface,
                )
                if (isToday) {
                    Spacer(Modifier.width(8.dp))
                    Text("oggi", style = MaterialTheme.typography.labelMedium, color = colors.primary)
                }
            }
            MealType.entries.forEachIndexed { i, meal ->
                if (i > 0) HorizontalDivider(Modifier.padding(horizontal = 16.dp), color = colors.outlineVariant.copy(alpha = 0.5f))
                MealRow(meal, slots(meal)?.let { data.recipesById[it] }, data) { onPick(meal) }
            }
        }
    }
}

@Composable
private fun MealRow(meal: MealType, recipe: Recipe?, data: AppData, onClick: () -> Unit) {
    val colors = MaterialTheme.colorScheme
    Row(
        Modifier.fillMaxWidth().clickable(onClick = onClick).padding(horizontal = 16.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            meal.label,
            style = MaterialTheme.typography.labelLarge,
            color = if (meal == MealType.LUNCH) colors.secondary else colors.tertiary,
            modifier = Modifier.width(64.dp),
        )
        if (recipe == null) {
            Icon(Icons.Default.Add, null, tint = colors.primary, modifier = Modifier.size(18.dp))
            Spacer(Modifier.width(6.dp))
            Text("Aggiungi pasto", color = colors.primary, style = MaterialTheme.typography.bodyMedium)
        } else {
            Column(Modifier.weight(1f)) {
                Text(recipe.name, style = MaterialTheme.typography.bodyLarge, maxLines = 1, overflow = TextOverflow.Ellipsis)
                val ingredients = recipe.ingredientIds.mapNotNull { data.ingredientsById[it]?.name }
                if (ingredients.isNotEmpty()) {
                    Text(
                        ingredients.joinToString(", "),
                        style = MaterialTheme.typography.bodySmall,
                        color = colors.onSurfaceVariant,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
            }
        }
    }
}
