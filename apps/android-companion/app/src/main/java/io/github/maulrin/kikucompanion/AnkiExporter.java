package io.github.maulrin.kikucompanion;

import android.content.ContentResolver;
import android.database.Cursor;
import android.net.Uri;
import org.json.JSONObject;
import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

final class AnkiExporter {
    static final String AUTHORITY = "com.ichi2.anki.flashcards";
    static final String PERMISSION = "com.ichi2.anki.permission.READ_WRITE_DATABASE";
    private static final String[] NOTE_COLUMNS = {"_id", "mid", "mod", "flds", "tags"};
    interface Progress { void update(String message); }
    private final ContentResolver resolver;
    AnkiExporter(ContentResolver resolver) { this.resolver = resolver; }

    private record Model(String name, String[] fields) {}
    private record RawNote(long id, long model, long modified, String fields, String tags) {}

    int export(MediaFolder media, File temporaryRoot, File journal, Progress progress) throws Exception {
        CachePublisher publisher = new CachePublisher(journal);
        publisher.recover(media, media.tree.toString());
        String profile = "AnkiDroid";
        byte[] existing = media.read(NoteCache.MANIFEST);
        if (existing != null) profile = new JSONObject(new String(existing, StandardCharsets.UTF_8)).getString("profile");
        Map<Long, Model> models = models();
        if (models.isEmpty()) throw new IOException("No note type named Kiku or Lapis found in AnkiDroid.");
        String selection = "mid IN (" + String.join(",", models.keySet().stream().map(String::valueOf).toArray(String[]::new)) + ")";
        File work = new File(temporaryRoot, "export-" + System.nanoTime());
        try (NoteCache cache = new NoteCache(work, profile)) {
            MessageDigest snapshot = MediaFolder.sha256();
            List<RawNote> batch = new ArrayList<>();
            progress.update("Reading Kiku and Lapis notes…");
            try (Cursor cursor = notes(selection)) {
                while (cursor.moveToNext()) {
                    RawNote note = raw(cursor);
                    fingerprint(snapshot, note);
                    batch.add(note);
                    if (batch.size() == 100) {
                        append(cache, batch, models);
                        batch.clear();
                        progress.update("Prepared " + cache.total + " notes…");
                    }
                }
            }
            if (!batch.isEmpty()) append(cache, batch, models);
            JSONObject manifest = cache.finish();
            progress.update("Checking for changes…");
            MessageDigest check = MediaFolder.sha256();
            try (Cursor cursor = notes(selection)) {
                while (cursor.moveToNext()) fingerprint(check, raw(cursor));
            }
            if (!Arrays.equals(snapshot.digest(), check.digest())) {
                throw new IOException("Notes changed during refresh. Finish editing or syncing, then try again.");
            }
            progress.update("Saving and checking cache files…");
            publisher.publish(media, media.tree.toString(), cache, manifest);
            return cache.total;
        } finally {
            File[] files = work.listFiles();
            if (files != null) for (File file : files) if (!file.delete()) file.deleteOnExit();
            if (!work.delete()) work.deleteOnExit();
        }
    }

    private Map<Long, Model> models() throws IOException {
        Map<Long, Model> models = new LinkedHashMap<>();
        try (Cursor cursor = resolver.query(uri("models"), new String[]{"_id", "name", "field_names"}, null, null, null)) {
            if (cursor == null) throw new IOException("Cannot read AnkiDroid note types. Open AnkiDroid once, then try again.");
            while (cursor.moveToNext()) {
                String name = cursor.getString(1);
                if ("Kiku".equals(name) || "Lapis".equals(name)) {
                    models.put(cursor.getLong(0), new Model(name, cursor.getString(2).split("\u001f", -1)));
                }
            }
        }
        return models;
    }

    private Cursor notes(String selection) throws IOException {
        Cursor cursor = resolver.query(uri("notes_v2"), NOTE_COLUMNS, selection, null, "id ASC");
        if (cursor == null) throw new IOException("Cannot read notes. This prototype requires AnkiDroid 2.25 or newer.");
        return cursor;
    }
    private static RawNote raw(Cursor cursor) {
        return new RawNote(cursor.getLong(0), cursor.getLong(1), cursor.getLong(2), cursor.getString(3), cursor.getString(4));
    }
    private static void fingerprint(MessageDigest digest, RawNote note) {
        byte[] bytes = note.toString().getBytes(StandardCharsets.UTF_8);
        digest.update(Integer.toString(bytes.length).getBytes(StandardCharsets.US_ASCII));
        digest.update((byte) ':');
        digest.update(bytes);
    }
    private void append(NoteCache cache, List<RawNote> batch, Map<Long, Model> models) throws Exception {
        String ids = String.join(",", batch.stream().map(note -> Long.toString(note.id())).toArray(String[]::new));
        Map<Long, List<Long>> cards = new HashMap<>();
        // Batch the supported cards API so AnkiDroid need not materialize the entire collection.
        try (Cursor cursor = resolver.query(uri("cards"), new String[]{"_id", "note_id"}, "nid:" + ids, null, null)) {
            if (cursor == null) throw new IOException("Cannot read card IDs from AnkiDroid.");
            while (cursor.moveToNext()) cards.computeIfAbsent(cursor.getLong(1), unused -> new ArrayList<>()).add(cursor.getLong(0));
        }
        for (RawNote note : batch) {
            Model model = models.get(note.model());
            if (model == null) throw new IOException("Note type changed during refresh. Try again.");
            List<Long> cardIds = cards.getOrDefault(note.id(), Collections.emptyList());
            cache.add(NoteCache.note(note.id(), note.modified(), model.name(), cache.profile, model.fields(), note.fields(), note.tags(), cardIds));
        }
    }
    private static Uri uri(String path) { return Uri.parse("content://" + AUTHORITY + "/" + path); }
}
