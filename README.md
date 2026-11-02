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
  with the number of meals each one appears in. Sort by frequency or A–Z, optional grouping by category.
  - *Condividi lista della spesa*: `text/plain` through the Android share sheet.
  - *Copia lista*: copies to the clipboard.
  - *Esporta PDF*: themed A4 list with tick boxes, saved to a file or shared.
  - Text format: simple (`Patate`, the default) or with counts (`Patate ×4`); one item per line, `\n`, UTF-8.
- **Ingredienti e alias**: rename, set a category, add aliases. Adding an alias that matches another
  ingredient's name merges the two.
- **Altro**: app theme (system / light / Dracula dark), PDF theme (same as the app / light / Dracula),
  manual JSON backup and restore. Restore validates the file first and asks for confirmation before
  replacing local data.

## Structure

- `core/`: plain Kotlin/JVM with no Android dependencies. Models, ingredient name normalisation and
  alias resolution, shopping list aggregation and text export, Italian week labels, and the versioned
  backup format with its validation. Future desktop builds (Windows portable, Linux AppImage) can reuse it as is.
- `app/`: Android with Jetpack Compose. `data/` (SQLiteOpenHelper + repository), `pdf/` (`PdfDocument`
  rendering), `ui/` (screens, theme, share and clipboard helpers).

## Build

```
./gradlew :core:test :app:testDebugUnitTest   # unit tests (core + Robolectric repository tests)
./gradlew :app:assembleDebug                   # app/build/outputs/apk/debug/app-debug.apk
```

Requires JDK 17 and the Android SDK (compileSdk 35).
