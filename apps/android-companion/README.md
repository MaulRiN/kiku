# Kiku Companion for Android — preview 0.1

An Android companion that refreshes Kiku's related-expression note cache using
AnkiDroid's supported content-provider API. The APK builds on GitHub; you do not
need Android Studio, Java, Gradle, or the Android SDK on your laptop or phone.

This first preview exports **Kiku** and **Lapis** notes. Settings, kanji editing,
and Merge Context still use the existing helper. It is not a full replacement
for the desktop Kiku add-on or Termux helper yet.

## Build online

Only commit this directory and `.github/workflows/android-companion.yml` when
publishing the prototype; the rest of your local Kiku edits are separate.

In Git Bash, from your existing repository:

```bash
cd ~/kiku/kiku
git add -- apps/android-companion .github/workflows/android-companion.yml
git diff --cached --stat
```

Check the list contains only the companion app and its workflow. If other files
were already staged, leave them out of this commit using the explicit paths:

```bash
git commit --only -m "Add Android companion note-cache preview" -- apps/android-companion .github/workflows/android-companion.yml
git push -u origin my-kiku-changes
```

Pushing also uploads any earlier commits on this branch. Review your branch
before publishing if you have made other commits.

1. Open your fork's **Actions** tab in your phone browser:
   [MaulRiN/kiku Actions](https://github.com/MaulRiN/kiku/actions).
2. If GitHub asks you to enable workflows on this fork, enable them. If the first
   push was skipped, rerun it or push another companion change. Once the workflow
   exists on the default branch, **Run workflow** is available too.
3. Open **Build Android companion** for your branch. Wait for its green check.
4. Under **Artifacts**, download **app-debug.apk** while signed into GitHub.
   The workflow uploads a single APK directly. If your browser gives you a ZIP,
   extract it and open `app-debug.apk`.
5. Allow APK installation for that browser/file manager when Android asks.

Cloud builds use a temporary debug signing key. A later build may require
uninstalling the previous **Kiku Companion** preview first. This clears the
companion's folder permission, but does not delete AnkiDroid or its media. Do not
uninstall during an unfinished cache save; open the existing app and refresh
successfully first. A stable signing key can be set up for later releases.

## First use on your phone

Requires Android 8+ and the main AnkiDroid app, version 2.25+. This preview targets
the API verified in AnkiDroid 2.25.0. Parallel AnkiDroid packages are not supported.

1. Open AnkiDroid once, then leave the reviewer and finish syncing/editing.
2. Open **Kiku Companion → Allow AnkiDroid access** and grant access. Android's API
   permission says read/write; this version only reads notes and cards.
3. Tap **Choose collection.media**. Select the existing folder:
   `/storage/emulated/0/AnkiDroid/collection.media`, then **Use this folder**.
   The app checks that the selected folder contains Kiku before exporting.
4. Tap **Refresh note cache**. Keep the app open until it confirms the note count.
5. Fully close and reopen AnkiDroid to reload Kiku's cache, then check Related
   Expressions on a card whose related notes are in your collection.

Refresh again after adding notes or merging context. Automatic refresh and a
reviewer connection are future steps. For this preview, verify locally on the
phone first; automatic media-sync detection is not implemented here.

## Files and recovery

- The app reads AnkiDroid through its API and never opens `collection.anki2`.
- All note fields, field order, tags, modification times, and actual card IDs
  are exported in the desktop add-on's format. Empty trailing fields are kept.
- Notes are streamed into ten compressed buckets in the app's temporary storage.
  A second pass aborts if note data changed during export.
- Each generation gets unique `_kiku_notes_phone_<id>_<bucket>.json.gz` files.
  All chunks are copied and read back for verification before replacing
  `_kiku_notes_manifest.json`.
- Android's folder API does not guarantee an atomic replacement. Before replacing
  the manifest, a private recovery record is flushed to disk. If the app is killed
  during the save, use **Refresh note cache** in the same folder again to recover.
- The previous cache generation is kept for an already-open reviewer. Older
  companion chunks are cleaned up after successful saves. Other media, desktop
  chunks, settings, and kanji databases are left alone.
- The app has no Internet permission and does not run a background HTTP server.

## Development

The workflow installs Java 17, Gradle 8.13, and Android SDK 35 on a GitHub runner,
then runs `testDebugUnitTest`, `lintDebug`, and `assembleDebug`. Tests cover the
export schema, gzip chunks, empty fields, card IDs, failed writes, recovery after
interruption, and preserving a newer manifest from another writer.

For an already-configured Android development machine:

```bash
gradle -p apps/android-companion :app:testDebugUnitTest :app:lintDebug :app:assembleDebug
```

The hosted build installs Gradle explicitly; no wrapper JAR is required in this
preview. Phone permissions, folder-picker behavior, and rendering still need an
actual device test after the first successful build.

References:

- [AnkiDroid API](https://github.com/ankidroid/Anki-Android/wiki/AnkiDroid-API)
- [AnkiDroid 2.25.0 provider implementation](https://github.com/ankidroid/Anki-Android/blob/v2.25.0/AnkiDroid/src/main/java/com/ichi2/anki/provider/CardContentProvider.kt)
- [Android Gradle plugin 8.13 compatibility](https://developer.android.com/build/releases/agp-8-13-0-release-notes)
- [GitHub APK artifact upload](https://github.com/actions/upload-artifact)

Uses the repository's MIT license. Kiku Companion is an independent preview;
it is not an official AnkiDroid application.
