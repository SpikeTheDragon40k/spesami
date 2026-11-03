# Spesami

Pianificatore settimanale di pranzi e cene per Android, con lista della spesa generata automaticamente.

**Plan the meals. One tap generates the shopping list. One tap exports or shares it. Optionally, keep the family's
phones in step through your own Google Drive.**

## Principles

- Works fully offline: local SQLite database, no backend, no server of ours, no account of our own. Every feature
  works without a connection.
- Sync between phones is **optional and off until you tap *Connetti*** in Altro → Sincronizzazione. It uses Google
  Drive's hidden app folder (scope `drive.appdata`) of the Google account you choose: Spesami cannot see or change
  any other file in that Drive, and nothing goes through a server of ours.
  - **Goes to Drive:** meal plan, recipes, ingredients and aliases, shopping categories and their order, each week's
    checklist (purchased marks and hand-added products), the phone's name (e.g. "Pixel 8") and a random device id.
  - **Never goes to Drive:** theme, PDF style, export options, "keep screen on", JSON backups, the Google sign-in
    itself. Tokens are kept by Google Play services; the app never stores one.
- The `INTERNET` permission exists only for that sync. Android cannot limit a permission to some servers, so the
  guarantee is in the code: `DriveClient` refuses any URL outside `https://www.googleapis.com`, and the tests check
  every request.
- Android auto backup is disabled (`allowBackup="false"`, data extraction rules exclude everything).
- Otherwise data leaves the device only through actions the user takes: share sheet (`ACTION_SEND`), clipboard,
  PDF files and JSON backups saved through the Storage Access Framework.
- Privacy policy: [PRIVACY.md](PRIVACY.md).

## Features

- **Piano**: 7 days × lunch/dinner, week navigation, copy previous week, clear week, themed PDF export (A4 landscape).
- **Ricette**: reusable recipes, each with a name, notes, and a list of ingredient names (no quantities).
- **Spesa**: a weekly checklist. Ingredients are aggregated from the week's meals and deduplicated by canonical
  name and alias, with the number of meals each one appears in. The list is grouped by category (in the configured
  order, collapsible, still-to-buy before purchased and A–Z inside each category, "Da classificare" last).
  - Tap a row to tick it as purchased (no confirmation); *Da comprare* / *Acquistati* views; progress card
    ("8 di 15 prodotti acquistati", bar, percentage) counting distinct products, not occurrences.
  - Each week has its own state, kept across restarts and never reset automatically. Marks are keyed by the
    stable ingredient id, so changing meals, counts, names or categories keeps them, and an ingredient that leaves
    the plan and comes back is restored as it was.
  - *Aggiungi prodotto*: products outside the plan (Detersivo, Sapone…) for the selected week, with a category.
    They never touch recipes or the plan, and one that matches a planned ingredient is shown once.
  - *Reimposta acquisti* (⋮ menu): unchecks the selected week after confirmation; everything else stays.
  - *Avvia modalità spesa*: a full-screen, one-handed view of the same checklist for use in the shop. It shows
    remaining products by category ("Frutta e verdura — 3 da comprare", collapsible, with quick category chips),
    "Già acquistati" collapsed below, a search field, the progress, and *Aggiungi prodotto* / *Termina spesa*.
    Every tick is saved at once and offers *ANNULLA*, which writes the previous state back. While the mode is visible
    the screen stays on through the window's `FLAG_KEEP_SCREEN_ON` (no WakeLock, no permission, no service). It is
    released when you leave the mode or the app goes to the background, and it can be turned off with *Mantieni
    schermo acceso durante la spesa* in Altro. The mode survives rotation but never opens by itself on app start.
  - ⋮ on a row: change its category, or remove a hand-added product.
  - *Condividi lista della spesa*: `text/plain` through the Android share sheet, one tap, using the saved settings.
  - *Copia lista*: copies to the clipboard.
  - *Esporta PDF*: themed A4 list with tick boxes and category headings, saved to a file or shared.
  - *Esporta lista della spesa* (settings icon): *Solo da comprare* (default) or *Lista completa* (optionally
    with purchased products ticked in the PDF), group by category, include occurrence counts (`Patate ×4`),
    show category headings, choose which categories to include, and preview the exact text.
    Plain text: one item per line, `\n` line ends, UTF-8, upper-case headings with a blank line between categories.
- **Categorie della spesa**: 12 seeded Italian categories with stable ids; create, rename, reorder, delete.
  Deleting moves the category's ingredients to "Altro", which cannot be deleted.
- **Classification**: new ingredients are classified automatically by an offline dictionary
  (`core/.../IngredientDictionary.kt`, ~530 common Italian grocery names) when the match is unambiguous.
  A category the user picked is never changed automatically. Unknown ingredients show as "Da classificare".
- **Ingredienti e alias**: rename, set the category, add aliases. Adding an alias that matches another
  ingredient's name merges the two.
- **Sincronizzazione** (Altro): optional sync between phones through Google Drive, see
  [Google Drive sync](#google-drive-sync). Connect, status ("Ultima sincronizzazione: oggi alle 14:32", errors in
  plain words), *Sincronizza ora*, *Disconnetti*, and the devices seen with their last sync.
- **Altro**: app theme (system / light / Dracula dark), PDF style (same as the app / Chiaro / Dracula /
  Risparmio inchiostro), manual JSON backup and restore. Restore validates the file first, including category
  references, and asks for confirmation before replacing local data. Backups include the checklist state;
  version 1 and 2 backups are still accepted.

## Database

SQLite through `SQLiteOpenHelper` (`app/.../data/Database.kt`). Schema version 2 added `shopping_category`
and `ingredient.category_id` / `category_manual`. The migration from version 1 runs in one transaction, seeds the
default categories only into an empty table, and maps the old fixed categories to the new ones as manual choices.
Schema version 3 added the weekly checklist: `shopping_purchased` and `shopping_extra`, both keyed by
`(week_start, ingredient_id)`. The list itself is still computed from the meal plan; these tables hold only the
purchased marks and the hand-added products. The migration only creates tables, so existing data is untouched.
Schema version 4 prepared sync: a `uid` (global id, UUID; `builtin:<key>` for the seeded categories, the same on
every phone) on categories, ingredients, aliases and recipes, next to the local integer ids that all relationships
keep using; `ingredient_redirect` (ingredient merges by uid) and `sync_state` (this device's sync data, never in
backups). New databases run the same migration, so new and upgraded schemas are identical. Backups now carry the
uids (optional fields, format still 3), so a restore keeps each record's identity.

## Structure

- `core/`: plain Kotlin/JVM with no Android dependencies. Models, ingredient name normalisation and
  alias resolution, default categories and the classification dictionary, shopping list aggregation, grouping,
  filtering and text export, PDF list pagination, Italian week labels, and the versioned backup format with its
  validation, and `sync/`: the whole sync logic and the Drive REST client (see below). Future desktop builds
  (Windows portable, Linux AppImage) can reuse it as is.
- `app/`: Android with Jetpack Compose. `data/` (SQLiteOpenHelper + repository), `pdf/` (`PdfDocument`
  rendering), `platform/` (Android `ScreenAwakeController`), `sync/` (Google authorization, HTTP, and running the
  sync on the database thread), `ui/` (screens, theme, share and clipboard helpers).
  `core` defines `ScreenAwakeController` with a no-op default, so desktop builds need no Android API.

## Build

```
./gradlew :core:test :app:testDebugUnitTest   # unit tests (core + Robolectric repository tests)
./gradlew :app:assembleDebug                   # app/build/outputs/apk/debug/app-debug.apk
```

Requires JDK 17 and the Android SDK (compileSdk 35). Release builds are signed with the key described in
`keystore.properties` (copy `keystore.properties.example`; never committed). Google sign-in only works in builds
signed with a key whose SHA-1 is registered in Google Cloud (below).

## Google Drive sync

Optional. Every phone keeps working fully offline; sync catches up when it can.

### How it works

- **Same Google account on every phone.** The hidden app folder belongs to one Google account and cannot be
  shared with another one. For a family, create one Google account just for Spesami and add it on each phone
  (Android Settings → Accounts → Add account → Google); everyone keeps their own account as the main one.
- **One file per phone.** Each phone writes only `device-<its id>.json` in the hidden app folder and reads the
  others'. No two phones ever write the same file, so nothing can be overwritten or lost to a race.
- **Each change is stamped** when it is made, also offline, with a hybrid logical clock (time, counter, device), and
  each field merges separately: a rename on one phone and a new category on another both survive. Deletions are
  kept as tombstones and forgotten after 90 days of Google's server time.
- **The merge is deterministic, commutative and idempotent:** the same files in any order, any number of times,
  give the same data on every phone. The rules for every conflict (edit vs edit, edit vs delete, alias merges,
  deleted categories → "Altro", the same ingredient or recipe typed on two phones, clock skew…) are documented in
  `core/.../sync/SyncMaterialize.kt` and `SyncMerge.kt` and tested in `core/src/test/.../sync/`.
- **When:** when the app opens or comes back to the foreground, 5 seconds after a change, and on *Sincronizza
  ora*. There is no background service.
- **Safety:** the first sync on a phone that already has data merges, never replaces, and saves a JSON backup first
  (Altro → Sincronizzazione → *Backup automatico* to export it). *Disconnetti* keeps all local data. A file from a
  newer app version that this one cannot read is skipped whole, with a message asking to update. A restore from a
  JSON backup while connected reaches the other phones too (the dialog says so).

### One-time Google Cloud setup

Done once by whoever builds the app, from a computer. Nothing from it goes into the repository: Google recognises
the app by package name and signing key.

1. **Project.** Open <https://console.cloud.google.com>, project selector → *New project* → name `Spesami` →
   *Create*. Make sure it is selected.
2. **Drive API.** *APIs & Services → Library* → *Google Drive API* (by Google Enterprise API) → *Enable*
   (<https://console.cloud.google.com/apis/library/drive.googleapis.com>).
3. **Consent screen.** *Google Auth Platform* (<https://console.cloud.google.com/auth/overview>) → *Get started* if
   it is not configured yet:
   - *Branding:* app name `Spesami`, user support email, app homepage
     `https://github.com/SpikeTheDragon40k/spesami`, privacy policy
     `https://github.com/SpikeTheDragon40k/spesami/blob/main/PRIVACY.md`, developer contact email. No logo (a logo
     can require brand verification).
   - *Audience:* **External**.
   - *Data access → Add or remove scopes:* only `https://www.googleapis.com/auth/drive.appdata`.
4. **Publish.** *Audience → Publish app* → **In production**. `drive.appdata` is a non-sensitive scope, so Google
   does not review it, and logins do not expire after 7 days as they do while an app is in *Testing*.
5. **Android OAuth client.** Get the SHA-1 of the release key:
   ```
   keytool -list -v -keystore ~/.android-keys/spesami-release.jks -alias spesami | grep SHA1
   ```
   *Clients → Create client → Android*: package name `it.spesami.app`, the SHA-1 above. No client secret, no
   `google-services.json`, no Web client (that is only for apps with a server).
6. **Optional, debug builds:** a second Android client with the debug key's SHA-1, to test sync from Android
   Studio:
   ```
   keytool -list -v -keystore ~/.android/debug.keystore -alias androiddebugkey -storepass android | grep SHA1
   ```

### Connecting phones

1. Install a release build signed with the registered key, as an update over the existing app (same key), so the
   data stays and the database is migrated in place. Take a JSON backup first if the old build was signed with
   another key: Android then refuses the update, and you must uninstall, install and restore.
2. On the phone, add the shared Google account if it is not there yet.
3. Spesami → Altro → Sincronizzazione → **Connetti a Google Drive**, choose the shared account, allow access.
   The first sync backs up and merges this phone's data.
4. **A second phone:** the same three steps. After its first sync it shows the first phone's data merged with its
   own, and both appear under *Dispositivi*. Tap *Sincronizza ora* on the first phone to see the second one's at once.

**Removing a phone:** *Disconnetti* on that phone (it keeps its data). Disconnetti does not revoke Google access,
because that would cut off every phone on the account. To revoke for all phones: sign in to
<https://myaccount.google.com/permissions> with the shared account → Spesami → *Remove access*; to also delete the
synced files: Google Drive → Settings → Manage apps → Spesami → *Delete hidden app data*.

**Errors in the app:** "Nessuna connessione" (it retries by itself), "Google Drive non risponde" (same), "L'accesso a
Google Drive non è più valido" (access was removed: tap *Connetti di nuovo*). A sign-in that fails with code 10
(`DEVELOPER_ERROR`) means the build's signing key or package name does not match the Android client in step 5.
