package it.spesami.app.ui

import androidx.activity.ComponentActivity
import androidx.activity.SystemBarStyle
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.consumeWindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.List
import androidx.compose.material.icons.filled.DateRange
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.ShoppingCart
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import it.spesami.app.AppViewModel
import it.spesami.app.data.AppTheme
import it.spesami.app.data.PdfTheme
import it.spesami.app.data.Preferences
import it.spesami.app.pdf.PdfPalette

private enum class Tab(val label: String, val icon: ImageVector) {
    PLAN("Piano", Icons.Default.DateRange),
    RECIPES("Ricette", Icons.AutoMirrored.Filled.List),
    SHOPPING("Spesa", Icons.Default.ShoppingCart),
    MORE("Altro", Icons.Default.Settings),
}

@Composable
fun SpesamiApp(vm: AppViewModel) {
    val data by vm.data.collectAsStateWithLifecycle()
    val week by vm.week.collectAsStateWithLifecycle()
    val prefs = Preferences.from(data?.preferences.orEmpty())
    val dark = when (prefs.theme) {
        AppTheme.SYSTEM -> isSystemInDarkTheme()
        AppTheme.LIGHT -> false
        AppTheme.DRACULA -> true
    }
    val pdfPalette = when (prefs.pdfTheme) {
        PdfTheme.APP -> if (dark) PdfPalette.Dracula else PdfPalette.Light
        PdfTheme.LIGHT -> PdfPalette.Light
        PdfTheme.DRACULA -> PdfPalette.Dracula
        PdfTheme.INK_SAVER -> PdfPalette.InkSaver
    }

    val activity = LocalContext.current as ComponentActivity
    LaunchedEffect(dark) {
        val transparent = Color.Transparent.toArgb()
        val style = if (dark) SystemBarStyle.dark(transparent) else SystemBarStyle.light(transparent, transparent)
        activity.enableEdgeToEdge(statusBarStyle = style, navigationBarStyle = style)
    }

    SpesamiTheme(dark) {
        Surface(Modifier.fillMaxSize()) {
            val current = data
            if (current == null) {
                Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) { CircularProgressIndicator() }
                return@Surface
            }

            var tab by rememberSaveable { mutableStateOf(Tab.PLAN) }
            var showIngredients by rememberSaveable { mutableStateOf(false) }
            var showCategories by rememberSaveable { mutableStateOf(false) }
            // Survives rotation / activity recreation, never a fresh app start.
            var shoppingMode by rememberSaveable { mutableStateOf(false) }
            var editing by remember { mutableStateOf<RecipeEditRequest?>(null) }
            val snackbar = remember { SnackbarHostState() }
            LaunchedEffect(Unit) { vm.messages.collect { snackbar.showSnackbar(it) } }

            val editRequest = editing
            when {
                editRequest != null -> RecipeEditor(
                    request = editRequest,
                    data = current,
                    onSave = { name, notes, ingredients ->
                        vm.saveRecipe(editRequest.recipe?.id, name, notes, ingredients, editRequest.assignTo)
                        vm.message("Ricetta \"${name.trim()}\" salvata.")
                        editing = null
                    },
                    onDelete = editRequest.recipe?.let { r -> { vm.deleteRecipe(r.id); editing = null } },
                    onClose = { editing = null },
                )
                shoppingMode -> ShoppingModeScreen(vm, current, week, prefs, snackbar) { shoppingMode = false }
                showCategories -> CategoriesScreen(vm, current) { showCategories = false }
                showIngredients -> IngredientsScreen(vm, current, onBack = { showIngredients = false }, onOpenCategories = { showCategories = true })
                else -> Scaffold(
                    snackbarHost = { SnackbarHost(snackbar) },
                    bottomBar = {
                        NavigationBar {
                            Tab.entries.forEach { t ->
                                NavigationBarItem(
                                    selected = tab == t,
                                    onClick = { tab = t },
                                    icon = { Icon(t.icon, null) },
                                    label = { Text(t.label) },
                                )
                            }
                        }
                    },
                ) { padding ->
                    Box(Modifier.padding(padding).consumeWindowInsets(padding)) {
                        when (tab) {
                            Tab.PLAN -> PlanScreen(vm, current, week, pdfPalette) { editing = it }
                            Tab.RECIPES -> RecipesScreen(current, { editing = it }, { showIngredients = true })
                            Tab.SHOPPING -> ShoppingScreen(vm, current, week, prefs, pdfPalette) { shoppingMode = true }
                            Tab.MORE -> SettingsScreen(vm, current, prefs, { showIngredients = true }, { showCategories = true })
                        }
                    }
                }
            }
        }
    }
}
