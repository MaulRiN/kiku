package io.github.maulrin.kikucompanion;

import static org.junit.Assert.*;
import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;
import java.io.File;
import java.io.FileInputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.zip.GZIPInputStream;

public class NoteCacheTest {
    @Rule public TemporaryFolder temporary = new TemporaryFolder();

    private JSONObject note(long id) throws Exception {
        return NoteCache.note(id, 123, "Kiku", "Kiku test", new String[]{"Expression", "Sentence", "Picture"},
            "猫\u001f<b>猫です。</b>\u001f", " marked 日本語 ", List.of(id + 1, id + 2));
    }
    private NoteCache cache(long id) throws Exception {
        NoteCache cache = new NoteCache(temporary.newFolder(), "Kiku test");
        cache.add(note(id));
        return cache;
    }
    private JSONObject manifest(String file) throws Exception {
        return new JSONObject().put("profile", "Kiku test").put("totalNotes", 1).put("generatedAt", 1)
            .put("chunks", new JSONArray().put(new JSONObject().put("file", file).put("count", 1)
                .put("range", new JSONArray().put(100).put(100))));
    }
    private CachePublisher publisher() throws IOException { return new CachePublisher(new File(temporary.newFolder(), "journal.json")); }

    @Test public void exportsDesktopCompatibleFieldsCardsTagsAndGzip() throws Exception {
        try (NoteCache cache = cache(100)) {
            cache.add(note(110));
            JSONObject manifest = cache.finish();
            assertEquals(2, manifest.getInt("totalNotes"));
            assertEquals(10, manifest.getInt("chunkCount"));
            JSONObject chunk = manifest.getJSONArray("chunks").getJSONObject(0);
            assertEquals(2, chunk.getInt("count"));
            assertEquals("[100,110]", chunk.getJSONArray("range").toString());
            File file = new File(cache.directory, chunk.getString("file"));
            try (GZIPInputStream input = new GZIPInputStream(new FileInputStream(file))) {
                JSONArray notes = new JSONArray(new String(input.readAllBytes(), StandardCharsets.UTF_8));
                JSONObject note = notes.getJSONObject(0);
                assertEquals(100L, note.getLong("noteId"));
                assertEquals("[101,102]", note.getJSONArray("cards").toString());
                assertEquals("[\"marked\",\"日本語\"]", note.getJSONArray("tags").toString());
                assertEquals("<b>猫です。</b>", note.getJSONObject("fields").getJSONObject("Sentence").getString("value"));
                assertEquals(2, note.getJSONObject("fields").getJSONObject("Picture").getInt("order"));
                assertEquals("", note.getJSONObject("fields").getJSONObject("Picture").getString("value"));
                assertEquals("Kiku test", note.getString("profile"));
            }
            assertTrue(CachePublisher.validManifest(manifest.toString()));
        }
    }

    @Test public void supportsLapisAndNotesWithoutCards() throws Exception {
        JSONObject note = NoteCache.note(200, 50, "Lapis", "phone", new String[]{"Expression"}, "犬", "", List.of());
        assertEquals("Lapis", note.getString("modelName"));
        assertEquals(0, note.getJSONArray("tags").length());
        assertEquals(0, note.getJSONArray("cards").length());
    }

    @Test(expected = JSONException.class) public void rejectsChangedFieldCount() throws Exception {
        NoteCache.note(100, 1, "Kiku", "phone", new String[]{"Expression", "Sentence"}, "猫", "", List.of());
    }

    @Test(expected = IOException.class) public void emptyExportKeepsOldCache() throws Exception {
        try (NoteCache cache = new NoteCache(temporary.newFolder(), "phone")) { cache.finish(); }
    }

    @Test public void publishesNewChunksBeforeManifestAndKeepsPreviousGeneration() throws Exception {
        FakeMedia media = new FakeMedia();
        String oldFile = "_kiku_notes_phone_aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa_0.json.gz";
        media.write(NoteCache.MANIFEST, manifest(oldFile).toString().getBytes(StandardCharsets.UTF_8));
        CachePublisher publisher = publisher();
        try (NoteCache cache = cache(100)) {
            JSONObject next = cache.finish();
            publisher.publish(media, "folder", cache, next);
            assertEquals(next.toString(), media.text(NoteCache.MANIFEST));
            assertTrue(media.kept.contains(oldFile));
            assertTrue(media.kept.contains(next.getJSONArray("chunks").getJSONObject(0).getString("file")));
            assertFalse(publisher.journal.exists());
        }
    }

    @Test public void failedChunkDoesNotTouchExistingManifest() throws Exception {
        FakeMedia media = new FakeMedia();
        String previous = manifest("_kiku_notes_0.json.gz").toString();
        media.write(NoteCache.MANIFEST, previous.getBytes(StandardCharsets.UTF_8));
        media.failChunk = true;
        CachePublisher publisher = publisher();
        try (NoteCache cache = cache(100)) {
            JSONObject next = cache.finish();
            assertThrows(IOException.class, () -> publisher.publish(media, "folder", cache, next));
            assertEquals(previous, media.text(NoteCache.MANIFEST));
            assertFalse(publisher.journal.exists());
        }
    }

    @Test public void partialManifestWriteRestoresPreviousManifest() throws Exception {
        FakeMedia media = new FakeMedia();
        String previous = manifest("_kiku_notes_0.json.gz").toString();
        media.write(NoteCache.MANIFEST, previous.getBytes(StandardCharsets.UTF_8));
        media.failManifest = true;
        CachePublisher publisher = publisher();
        try (NoteCache cache = cache(100)) {
            JSONObject next = cache.finish();
            assertThrows(IOException.class, () -> publisher.publish(media, "folder", cache, next));
            assertEquals(previous, media.text(NoteCache.MANIFEST));
            assertFalse(publisher.journal.exists());
        }
    }

    @Test public void interruptionRecoversOnNextLaunchAndRejectsWrongFolder() throws Exception {
        FakeMedia media = new FakeMedia();
        String previous = manifest("_kiku_notes_0.json.gz").toString();
        CachePublisher publisher = publisher();
        Files.write(publisher.journal.toPath(), new JSONObject().put("folder", "correct")
            .put("previous", previous).put("next", manifest("_kiku_notes_1.json.gz").toString()).toString().getBytes(StandardCharsets.UTF_8));
        media.write(NoteCache.MANIFEST, "{truncated".getBytes(StandardCharsets.UTF_8));
        assertThrows(IOException.class, () -> publisher.recover(media, "wrong"));
        assertTrue(publisher.journal.exists());
        publisher.recover(media, "correct");
        assertEquals(previous, media.text(NoteCache.MANIFEST));
        assertFalse(publisher.journal.exists());
    }

    @Test public void recoveryPreservesCompleteManifestFromAnotherWriter() throws Exception {
        FakeMedia media = new FakeMedia();
        CachePublisher publisher = publisher();
        String newer = manifest("_kiku_notes_9.json.gz").toString();
        media.write(NoteCache.MANIFEST, newer.getBytes(StandardCharsets.UTF_8));
        Files.write(publisher.journal.toPath(), new JSONObject().put("folder", "folder")
            .put("previous", manifest("_kiku_notes_0.json.gz").toString()).put("next", "unused").toString().getBytes(StandardCharsets.UTF_8));
        publisher.recover(media, "folder");
        assertEquals(newer, media.text(NoteCache.MANIFEST));
    }

    @Test public void firstExportFailureRemovesIncompleteManifest() throws Exception {
        FakeMedia media = new FakeMedia();
        media.failManifest = true;
        CachePublisher publisher = publisher();
        try (NoteCache cache = cache(100)) {
            JSONObject next = cache.finish();
            assertThrows(IOException.class, () -> publisher.publish(media, "folder", cache, next));
            assertNull(media.read(NoteCache.MANIFEST));
        }
    }

    private static class FakeMedia implements CachePublisher.Media {
        final Map<String, byte[]> files = new HashMap<>();
        boolean failChunk, failManifest;
        Set<String> kept;
        @Override public byte[] read(String name) { return files.get(name); }
        String text(String name) { return new String(files.get(name), StandardCharsets.UTF_8); }
        @Override public void write(String name, byte[] data) throws IOException {
            if (failManifest && name.equals(NoteCache.MANIFEST)) {
                failManifest = false;
                files.put(name, "{partial".getBytes(StandardCharsets.UTF_8));
                throw new IOException("simulated storage failure");
            }
            files.put(name, data);
        }
        @Override public void copy(String name, File source) throws IOException {
            if (failChunk) throw new IOException("simulated full storage");
            files.put(name, Files.readAllBytes(source.toPath()));
        }
        @Override public void delete(String name) { files.remove(name); }
        @Override public void cleanup(Set<String> keep) { kept = keep; }
    }
}
