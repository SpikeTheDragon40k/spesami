package it.spesami.app.data

import it.spesami.core.ShoppingSort

enum class AppTheme(val label: String) { SYSTEM("Automatico (sistema)"), LIGHT("Chiaro"), DRACULA("Dracula scuro") }

enum class PdfTheme(val label: String) { APP("Come l'app"), LIGHT("Chiaro"), DRACULA("Dracula scuro") }

/** Typed view of the preference table. These are the "relevant app preferences" included in backups. */
data class Preferences(
    val theme: AppTheme = AppTheme.SYSTEM,
    val pdfTheme: PdfTheme = PdfTheme.APP,
    val shareWithCounts: Boolean = false,
    val shoppingSort: ShoppingSort = ShoppingSort.COUNT,
    val groupByCategory: Boolean = false,
) {
    companion object {
        const val THEME = "theme"
        const val PDF_THEME = "pdf_theme"
        const val SHARE_WITH_COUNTS = "share_with_counts"
        const val SHOPPING_SORT = "shopping_sort"
        const val GROUP_BY_CATEGORY = "group_by_category"

        fun from(map: Map<String, String>) = Preferences(
            theme = AppTheme.entries.firstOrNull { it.name == map[THEME] } ?: AppTheme.SYSTEM,
            pdfTheme = PdfTheme.entries.firstOrNull { it.name == map[PDF_THEME] } ?: PdfTheme.APP,
            shareWithCounts = map[SHARE_WITH_COUNTS] == "true",
            shoppingSort = ShoppingSort.entries.firstOrNull { it.name == map[SHOPPING_SORT] } ?: ShoppingSort.COUNT,
            groupByCategory = map[GROUP_BY_CATEGORY] == "true",
        )
    }
}
