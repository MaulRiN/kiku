package io.github.maulrin.kikucompanion;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;
import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.HashSet;
import java.util.Set;

/** New chunks are verified first. A durable private journal protects manifest replacement. */
final class CachePublisher {
    interface Media {
        byte[] read(String name) throws IOException;
        void write(String name, byte[] data) throws IOException;
        void copy(String name, File source) throws IOException;
        void delete(String name) throws IOException;
        void cleanup(Set<String> keep);
    }
    final File journal;
    CachePublisher(File journal) { this.journal = journal; }

    void recover(Media media, String folder) throws IOException, JSONException {
        if (!journal.exists()) return;
        JSONObject pending = new JSONObject(new String(Files.readAllBytes(journal.toPath()), StandardCharsets.UTF_8));
        if (!folder.equals(pending.getString("folder"))) {
            throw new IOException("An interrupted save belongs to another folder. Choose that media folder first.");
        }
        byte[] current = media.read(NoteCache.MANIFEST);
        String text = current == null ? null : new String(current, StandardCharsets.UTF_8);
        String previous = pending.isNull("previous") ? null : pending.getString("previous");
        // Preserve a complete newer manifest, including one written by another app/device.
        if (text == null || !validManifest(text)) {
            if (previous == null) media.delete(NoteCache.MANIFEST);
            else media.write(NoteCache.MANIFEST, previous.getBytes(StandardCharsets.UTF_8));
        }
        clearJournal();
    }

    void publish(Media media, String folder, NoteCache cache, JSONObject manifest) throws IOException, JSONException {
        recover(media, folder);
        byte[] previousBytes = media.read(NoteCache.MANIFEST);
        String previous = previousBytes == null ? null : new String(previousBytes, StandardCharsets.UTF_8);
        if (previous != null && !validManifest(previous)) {
            throw new IOException("The existing cache manifest is damaged. Back it up before replacing it.");
        }
        for (File file : cache.files()) media.copy(file.getName(), file);
        String next = manifest.toString();
        JSONObject pending = new JSONObject().put("folder", folder)
            .put("previous", previous == null ? JSONObject.NULL : previous).put("next", next);
        File temporary = new File(journal.getParentFile(), journal.getName() + ".tmp");
        try (FileOutputStream output = new FileOutputStream(temporary)) {
            output.write(pending.toString().getBytes(StandardCharsets.UTF_8));
            output.getFD().sync();
        }
        if (!temporary.renameTo(journal)) throw new IOException("Cannot save recovery record.");
        try {
            media.write(NoteCache.MANIFEST, next.getBytes(StandardCharsets.UTF_8));
            byte[] saved = media.read(NoteCache.MANIFEST);
            if (saved == null || !next.equals(new String(saved, StandardCharsets.UTF_8))) {
                throw new IOException("Saved manifest could not be verified.");
            }
            clearJournal();
        } catch (IOException e) {
            try { recover(media, folder); }
            catch (Exception recovery) { e.addSuppressed(recovery); }
            throw e;
        }
        Set<String> keep = filenames(manifest);
        if (previous != null) keep.addAll(filenames(new JSONObject(previous)));
        // Retain the previous generation for cards already open in the reviewer.
        media.cleanup(keep);
    }

    private void clearJournal() throws IOException {
        if (journal.exists() && !journal.delete()) throw new IOException("Cannot clear recovery record. Open the app again before refreshing.");
    }

    static boolean validManifest(String text) {
        try {
            JSONObject data = new JSONObject(text);
            JSONArray chunks = data.getJSONArray("chunks");
            long total = 0;
            for (int i = 0; i < chunks.length(); i++) {
                JSONObject chunk = chunks.getJSONObject(i);
                String file = chunk.getString("file");
                if (!file.matches("_kiku_notes_[A-Za-z0-9_-]+\\.json\\.gz")) return false;
                long count = chunk.getLong("count");
                if (count <= 0) return false;
                total += count;
            }
            return total == data.getLong("totalNotes") && data.has("profile") && data.has("generatedAt");
        } catch (JSONException e) { return false; }
    }

    static Set<String> filenames(JSONObject manifest) throws JSONException {
        Set<String> result = new HashSet<>();
        JSONArray chunks = manifest.getJSONArray("chunks");
        for (int i = 0; i < chunks.length(); i++) result.add(chunks.getJSONObject(i).getString("file"));
        return result;
    }
}
