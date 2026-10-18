package io.github.maulrin.kikucompanion;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;
import java.io.BufferedWriter;
import java.io.Closeable;
import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.OutputStreamWriter;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.zip.GZIPOutputStream;

/** Matches packages/addon/src/export_notes.py, without retaining the collection in RAM. */
final class NoteCache implements Closeable {
    static final String MANIFEST = "_kiku_notes_manifest.json";
    static final int BUCKETS = 10;
    final File directory;
    final String profile;
    final String generation = UUID.randomUUID().toString().replace("-", "");
    final BufferedWriter[] writers = new BufferedWriter[BUCKETS];
    final long[] count = new long[BUCKETS], min = new long[BUCKETS], max = new long[BUCKETS];
    int total;

    NoteCache(File directory, String profile) throws IOException {
        this.directory = directory;
        this.profile = profile;
        if (!directory.isDirectory() && !directory.mkdirs()) throw new IOException("Cannot prepare cache files.");
    }

    static JSONObject note(long id, long modified, String model, String profile,
            String[] names, String encodedFields, String tags, List<Long> cards) throws JSONException {
        String[] values = encodedFields.split("\u001f", -1);
        if (values.length != names.length) throw new JSONException("Field count changed. Finish editing, then try again.");
        JSONObject fields = new JSONObject();
        for (int i = 0; i < names.length; i++) {
            fields.put(names[i], new JSONObject().put("order", i).put("value", values[i]));
        }
        JSONArray tagList = new JSONArray();
        if (!tags.trim().isEmpty()) for (String tag : tags.trim().split("\\s+")) tagList.put(tag);
        return new JSONObject().put("noteId", id).put("mod", modified).put("modelName", model)
            .put("profile", profile).put("fields", fields).put("tags", tagList)
            .put("cards", new JSONArray(cards));
    }

    String filename(int bucket) { return "_kiku_notes_phone_" + generation + "_" + bucket + ".json.gz"; }

    void add(JSONObject note) throws IOException, JSONException {
        long id = note.getLong("noteId");
        if (id <= 0) throw new JSONException("Invalid note ID.");
        int bucket = (int) (id % BUCKETS);
        if (writers[bucket] == null) {
            writers[bucket] = new BufferedWriter(new OutputStreamWriter(
                new GZIPOutputStream(new FileOutputStream(new File(directory, filename(bucket)))), StandardCharsets.UTF_8));
            writers[bucket].write('[');
            min[bucket] = max[bucket] = id;
        } else writers[bucket].write(',');
        writers[bucket].write(note.toString());
        min[bucket] = Math.min(min[bucket], id);
        max[bucket] = Math.max(max[bucket], id);
        count[bucket]++;
        total++;
    }

    JSONObject finish() throws IOException, JSONException {
        close();
        if (total == 0) throw new IOException("No Kiku or Lapis notes found. Your existing cache was kept.");
        JSONArray chunks = new JSONArray();
        for (int i = 0; i < BUCKETS; i++) if (count[i] > 0) {
            chunks.put(new JSONObject().put("file", filename(i)).put("count", count[i])
                .put("range", new JSONArray().put(min[i]).put(max[i])));
        }
        return new JSONObject().put("profile", profile).put("totalNotes", total)
            .put("chunkCount", BUCKETS).put("chunks", chunks).put("generatedAt", System.currentTimeMillis());
    }

    List<File> files() {
        List<File> result = new ArrayList<>();
        for (int i = 0; i < BUCKETS; i++) if (count[i] > 0) result.add(new File(directory, filename(i)));
        return result;
    }

    @Override public void close() throws IOException {
        IOException failure = null;
        for (int i = 0; i < BUCKETS; i++) if (writers[i] != null) {
            try { writers[i].write(']'); writers[i].close(); }
            catch (IOException e) { failure = e; }
            finally { writers[i] = null; }
        }
        if (failure != null) throw failure;
    }
}
