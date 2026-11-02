# Spesami

Pianificatore settimanale di pranzi e cene per Android, con lista della spesa generata automaticamente.

**One device plans the meals. One tap generates the shopping list. One tap exports or shares it.**

## Principles

- Single device, offline only: local SQLite database, no accounts, no sync, no backend, no `INTERNET` permission.
- Android auto backup is disabled (`allowBackup="false"`, data extraction rules exclude everything).
- Data leaves the device only through actions the user takes: share sheet (`ACTION_SEND`), clipboard,
  PDF files and JSON backups saved through the Storage Access Framework.

## Features

- **Piano**: 7 days × lunch/dinner, week navigation, copy previous week, clear week, themed PDF export (A4 landscape).
- **Ricette**: reusable recipes, each with a name, notes, and a list of ingredient names (no quantities).
- **Spesa**: ingredients aggregated from the week's meals and deduplicated by canonical name and alias,
  with the number of meals each one appears in. The list is grouped by category (in the configured order,
  A–Z inside each category, "Da classificare" last); tap an ingredient to change its category.
  - *Condividi lista della spesa*: `text/plain` through the Android share sheet, one tap, using the saved settings.
  - *Copia lista*: copies to the clipboard.
  - *Esporta PDF*: themed A4 list with tick boxes and category headings, saved to a file or shared.
  - *Esporta lista della spesa* (settings icon): group by category, include occurrence counts (`Patate ×4`),
    show category headings, choose which categories to include, and preview the exact text.
    Plain text: one item per line, `\n` line ends, UTF-8, upper-case headings with a blank line between categories.
- **Categorie della spesa**: 12 seeded Italian categories with stable ids; create, rename, reorder, delete.
  Deleting moves the category's ingredients to "Altro", which cannot be deleted.
- **Classification**: new ingredients are classified automatically by an offline dictionary
  (`core/.../IngredientDictionary.kt`, ~530 common Italian grocery names) when the match is unambiguous.
  A category the user picked is never changed automatically. Unknown ingredients show as "Da classificare".
- **Ingredienti e alias**: rename, set the category, add aliases. Adding an alias that matches another
  ingredient's name merges the two.
- **Altro**: app theme (system / light / Dracula dark), PDF style (same as the app / Chiaro / Dracula /
  Risparmio inchiostro), manual JSON backup and restore. Restore validates the file first, including category
  references, and asks for confirmation before replacing local data. Version 1 backups are still accepted.

## Database

SQLite through `SQLiteOpenHelper` (`app/.../data/Database.kt`). Schema version 2 added `shopping_category`
and `ingredient.category_id` / `category_manual`. The migration from version 1 runs in one transaction, seeds the
default categories only into an empty table, and maps the old fixed categories to the new ones as manual choices.

## Structure

- `core/`: plain Kotlin/JVM with no Android dependencies. Models, ingredient name normalisation and
  alias resolution, default categories and the classification dictionary, shopping list aggregation, grouping,
  filtering and text export, PDF list pagination, Italian week labels, and the versioned backup format with its
  validation. Future desktop builds (Windows portable, Linux AppImage) can reuse it as is.
- `app/`: Android with Jetpack Compose. `data/` (SQLiteOpenHelper + repository), `pdf/` (`PdfDocument`
  rendering), `ui/` (screens, theme, share and clipboard helpers).

## Build

```
./gradlew :core:test :app:testDebugUnitTest   # unit tests (core + Robolectric repository tests)
./gradlew :app:assembleDebug                   # app/build/outputs/apk/debug/app-debug.apk
```

Requires JDK 17 and the Android SDK (compileSdk 35).
