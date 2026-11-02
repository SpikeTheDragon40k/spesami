package it.spesami.app

import android.app.Application
import android.net.Uri
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import it.spesami.app.data.Repository
import it.spesami.app.data.SpesamiDatabase
import it.spesami.core.AppData
import it.spesami.core.Backup
import it.spesami.core.BackupReadResult
import it.spesami.core.MealType
import it.spesami.core.ShoppingList
import it.spesami.core.WeekItem
import it.spesami.core.Weeks
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.temporal.ChronoUnit
import java.util.concurrent.atomic.AtomicInteger

class AppViewModel(app: Application) : AndroidViewModel(app) {
    private val database = SpesamiDatabase(app)
    private val repository = Repository(database)

    @OptIn(ExperimentalCoroutinesApi::class)
    private val io = Dispatchers.IO.limitedParallelism(1) // one writer at a time, reloads see every write

    /** Writes queued on [io]; declared before `init`, which already writes. */
    private val pendingWrites = AtomicInteger()

    private val _data = MutableStateFlow<AppData?>(null)
    /** The whole database, reloaded after every change; null until the first load. */
    val data: StateFlow<AppData?> = _data.asStateFlow()

    private val _week = MutableStateFlow(Weeks.startOf(LocalDate.now()))
    val week: StateFlow<LocalDate> = _week.asStateFlow()

    private val _messages = MutableSharedFlow<String>(extraBufferCapacity = 8)
    val messages: SharedFlow<String> = _messages

    sealed interface RestoreState {
        data class Confirm(val data: AppData, val summary: String) : RestoreState
        data class Rejected(val errors: List<String>) : RestoreState
    }

    private val _restore = MutableStateFlow<RestoreState?>(null)
    val restore: StateFlow<RestoreState?> = _restore.asStateFlow()

    init {
        write { }
    }

    fun message(text: String) {
        _messages.tryEmit(text)
    }

    fun shiftWeek(weeks: Long) {
        _week.value = _week.value.plusWeeks(weeks)
    }

    fun goToCurrentWeek() {
        _week.value = Weeks.startOf(LocalDate.now())
    }

    /**
     * Runs [block] on the single writer thread and then publishes a fresh load. While more writes are queued the
     * reload waits for the last one, so quick taps (optimistically shown already) never flicker back.
     */
    private fun write(block: Repository.() -> Unit) {
        pendingWrites.incrementAndGet()
        viewModelScope.launch {
            withContext(io) {
                try {
                    repository.block()
                } finally {
                    if (pendingWrites.decrementAndGet() == 0) _data.value = repository.load()
                }
            }
        }
    }

    // --- Plan ---

    fun setMeal(day: Int, meal: MealType, recipeId: Long?) {
        val week = _week.value
        write { setMeal(week, day, meal, recipeId) }
    }

    fun clearWeek() {
        val week = _week.value
        write { clearWeek(week) }
    }

    fun copyPreviousWeek() {
        val week = _week.value
        val previous = week.minusWeeks(1)
        if (_data.value?.slotsOfWeek(previous).isNullOrEmpty()) {
            message("La settimana precedente è vuota.")
            return
        }
        write { copyWeek(previous, week) }
        message("Piano copiato dalla settimana precedente.")
    }

    // --- Shopping checklist ---

    /** Checkbox tap: shown immediately, then saved. */
    fun setPurchased(ingredientId: Long, purchased: Boolean) {
        val week = _week.value
        val item = WeekItem(week, ingredientId)
        _data.update { d -> d?.copy(purchases = if (purchased) d.purchases + item else d.purchases - item) }
        write { setPurchased(week, ingredientId, purchased) }
    }

    fun resetPurchases() {
        val week = _week.value
        _data.update { d -> d?.copy(purchases = d.purchases.filter { it.weekStart != week }) }
        write { resetPurchases(week) }
        message("Acquisti reimpostati: tutti i prodotti sono di nuovo da comprare.")
    }

    /** [categoryId] is applied only when [setCategory] (the user picked one in the dialog). */
    fun addProduct(name: String, categoryId: Long?, setCategory: Boolean) {
        val week = _week.value
        val planned = _data.value?.let { d -> ShoppingList.build(d, week).filter { it.count > 0 }.map { it.name }.toSet() }.orEmpty()
        write {
            when (val change = addProduct(week, name, categoryId, setCategory)) {
                is Repository.ProductChange.Added -> {
                    message(if (change.name in planned) "\"${change.name}\" era già nella lista: segnato anche come aggiunto." else "\"${change.name}\" aggiunto alla lista.")
                }
                is Repository.ProductChange.AlreadyListed -> message("\"${change.name}\" è già nella lista.")
                Repository.ProductChange.Empty -> Unit
            }
        }
    }

    fun removeProduct(ingredientId: Long) {
        val week = _week.value
        write { removeProduct(week, ingredientId) }
    }

    // --- Recipes ---

    /** Saves the recipe and, if [assignTo] is given, puts it in that slot of the current week. */
    fun saveRecipe(id: Long?, name: String, notes: String, ingredients: List<String>, assignTo: Pair<Int, MealType>?) {
        val week = _week.value
        write {
            val recipeId = saveRecipe(id, name, notes, ingredients)
            if (assignTo != null) setMeal(week, assignTo.first, assignTo.second, recipeId)
        }
    }

    fun deleteRecipe(id: Long) = write { deleteRecipe(id) }

    // --- Ingredients ---

    private fun report(change: Repository.CategoryChange) {
        if (change is Repository.CategoryChange.Conflict) message(change.message)
    }

    private fun report(change: Repository.IngredientChange) {
        when (change) {
            Repository.IngredientChange.Done -> Unit
            is Repository.IngredientChange.Merged -> message("Ingredienti uniti in \"${change.into}\".")
            is Repository.IngredientChange.Conflict -> message(change.message)
        }
    }

    fun renameIngredient(id: Long, name: String) = write { report(renameIngredient(id, name)) }

    fun setIngredientCategory(id: Long, categoryId: Long?) = write { setIngredientCategory(id, categoryId) }

    fun addAlias(id: Long, alias: String) = write { report(addAlias(id, alias)) }

    fun removeAlias(aliasId: Long) = write { removeAlias(aliasId) }

    fun mergeIngredient(sourceId: Long, targetId: Long) = write { mergeIngredient(sourceId, targetId) }

    fun deleteIngredient(id: Long) = write { deleteIngredient(id) }

    // --- Shopping categories ---

    fun createCategory(name: String) = write { report(createCategory(name)) }

    fun renameCategory(id: Long, name: String) = write { report(renameCategory(id, name)) }

    fun moveCategory(id: Long, delta: Int) = write { moveCategory(id, delta) }

    fun deleteCategory(id: Long) = write { report(deleteCategory(id)) }

    // --- Preferences ---

    fun setPreference(key: String, value: String) = write { setPreference(key, value) }

    // --- Backup (Storage Access Framework URIs chosen by the user) ---

    fun exportBackup(uri: Uri) {
        viewModelScope.launch {
            val ok = withContext(io) {
                runCatching {
                    val text = Backup.encode(repository.load(), LocalDateTime.now().truncatedTo(ChronoUnit.SECONDS).toString())
                    getApplication<Application>().contentResolver.openOutputStream(uri, "wt")!!.use {
                        it.write(text.toByteArray(Charsets.UTF_8))
                    }
                }.isSuccess
            }
            message(if (ok) "Backup salvato." else "Impossibile salvare il backup.")
        }
    }

    fun readBackup(uri: Uri) {
        viewModelScope.launch {
            val result = withContext(io) {
                runCatching {
                    val text = getApplication<Application>().contentResolver.openInputStream(uri)!!.use {
                        // A real backup is a few hundred KB at most; refuse anything absurdly large.
                        val bytes = it.readNBytesCompat(20 * 1024 * 1024)
                        bytes.toString(Charsets.UTF_8)
                    }
                    Backup.decode(text)
                }.getOrElse { BackupReadResult.Invalid(listOf("Impossibile leggere il file.")) }
            }
            _restore.value = when (result) {
                is BackupReadResult.Valid -> RestoreState.Confirm(result.data, result.summary)
                is BackupReadResult.Invalid -> RestoreState.Rejected(result.errors)
            }
        }
    }

    fun confirmRestore() {
        val pending = _restore.value as? RestoreState.Confirm ?: return
        _restore.value = null
        viewModelScope.launch {
            val ok = withContext(io) {
                runCatching { repository.replaceAll(pending.data) }.isSuccess.also { _data.value = repository.load() }
            }
            message(if (ok) "Backup ripristinato." else "Ripristino non riuscito: i dati locali non sono stati modificati.")
        }
    }

    fun dismissRestore() {
        _restore.value = null
    }

    override fun onCleared() {
        database.close()
    }
}

private fun java.io.InputStream.readNBytesCompat(limit: Int): ByteArray {
    val out = java.io.ByteArrayOutputStream()
    val buffer = ByteArray(64 * 1024)
    while (true) {
        val n = read(buffer)
        if (n < 0) break
        out.write(buffer, 0, n)
        require(out.size() <= limit) { "File too large" }
    }
    return out.toByteArray()
}
