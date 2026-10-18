package io.github.maulrin.kikucompanion;

import android.content.ContentResolver;
import android.database.Cursor;
import android.net.Uri;
import android.provider.DocumentsContract;
import android.provider.DocumentsContract.Document;
import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.Arrays;
import java.util.HashMap;
import java.util.Map;
import java.util.Set;

/** Uses the folder explicitly selected in Android's file picker. */
final class MediaFolder implements CachePublisher.Media {
    final ContentResolver resolver;
    final Uri tree, directory;
    final Map<String, Uri> files = new HashMap<>();

    MediaFolder(ContentResolver resolver, Uri tree) throws IOException {
        this.resolver = resolver;
        this.tree = tree;
        String id = DocumentsContract.getTreeDocumentId(tree);
        directory = DocumentsContract.buildDocumentUriUsingTree(tree, id);
        try (Cursor cursor = resolver.query(directory, new String[]{Document.COLUMN_DISPLAY_NAME}, null, null, null)) {
            if (cursor == null || !cursor.moveToFirst() || !"collection.media".equals(cursor.getString(0))) {
                throw new IOException("Choose AnkiDroid's collection.media folder, not its parent folder.");
            }
        }
        Uri children = DocumentsContract.buildChildDocumentsUriUsingTree(tree, id);
        try (Cursor cursor = resolver.query(children, new String[]{Document.COLUMN_DOCUMENT_ID, Document.COLUMN_DISPLAY_NAME}, null, null, null)) {
            if (cursor == null) throw new IOException("Cannot read this folder. Choose it again.");
            while (cursor.moveToNext()) {
                String name = cursor.getString(1);
                if (files.put(name, DocumentsContract.buildDocumentUriUsingTree(tree, cursor.getString(0))) != null) {
                    throw new IOException("Duplicate file names in media folder: " + name);
                }
            }
        }
        if (!files.containsKey("_kiku.js")) throw new IOException("Kiku is not installed in this media folder. Select the folder used by your Kiku cards.");
    }

    @Override public byte[] read(String name) throws IOException {
        Uri uri = files.get(name);
        if (uri == null) return null;
        try (InputStream input = input(uri); ByteArrayOutputStream output = new ByteArrayOutputStream()) {
            byte[] buffer = new byte[8192];
            int length;
            while ((length = input.read(buffer)) != -1) {
                if (output.size() + length > 1024 * 1024) throw new IOException("Cache manifest is unexpectedly large.");
                output.write(buffer, 0, length);
            }
            return output.toByteArray();
        }
    }

    @Override public void write(String name, byte[] data) throws IOException {
        Uri uri = destination(name);
        try (OutputStream output = output(uri)) { output.write(data); }
        if (!Arrays.equals(data, read(name))) throw new IOException("Could not verify " + name);
    }

    @Override public void copy(String name, File source) throws IOException {
        Uri uri = destination(name);
        MessageDigest expected = sha256();
        byte[] buffer = new byte[32768];
        try (InputStream input = new FileInputStream(source); OutputStream output = output(uri)) {
            int length;
            while ((length = input.read(buffer)) != -1) {
                output.write(buffer, 0, length);
                expected.update(buffer, 0, length);
            }
        }
        MessageDigest actual = sha256();
        try (InputStream input = input(uri)) {
            int length;
            while ((length = input.read(buffer)) != -1) actual.update(buffer, 0, length);
        }
        if (!Arrays.equals(expected.digest(), actual.digest())) throw new IOException("Could not verify " + name);
    }

    private Uri destination(String name) throws IOException {
        if (!name.matches("_kiku_notes_[A-Za-z0-9_-]+\\.(json|json\\.gz)")) throw new IOException("Unexpected cache filename.");
        Uri uri = files.get(name);
        if (uri == null) {
            uri = DocumentsContract.createDocument(resolver, directory, "application/octet-stream", name);
            if (uri == null) throw new IOException("Cannot create " + name);
            try (Cursor cursor = resolver.query(uri, new String[]{Document.COLUMN_DISPLAY_NAME}, null, null, null)) {
                if (cursor == null || !cursor.moveToFirst() || !name.equals(cursor.getString(0))) {
                    throw new IOException("This folder provider changed the filename. Use AnkiDroid's local media folder.");
                }
            }
            files.put(name, uri);
        }
        return uri;
    }

    private InputStream input(Uri uri) throws IOException {
        InputStream stream = resolver.openInputStream(uri);
        if (stream == null) throw new IOException("Cannot open media file.");
        return stream;
    }
    private OutputStream output(Uri uri) throws IOException {
        OutputStream stream = resolver.openOutputStream(uri, "rwt");
        if (stream == null) throw new IOException("Cannot save media file.");
        return stream;
    }
    static MessageDigest sha256() {
        try { return MessageDigest.getInstance("SHA-256"); }
        catch (NoSuchAlgorithmException e) { throw new AssertionError(e); }
    }
    @Override public void delete(String name) throws IOException {
        Uri uri = files.get(name);
        if (uri != null) {
            if (!DocumentsContract.deleteDocument(resolver, uri)) throw new IOException("Cannot remove incomplete cache manifest.");
            files.remove(name);
        }
    }
    @Override public void cleanup(Set<String> keep) {
        for (String name : files.keySet().toArray(new String[0])) {
            if (!keep.contains(name) && name.matches("_kiku_notes_phone_[0-9a-f]{32}_[0-9]\\.json\\.gz")) {
                try { delete(name); } catch (IOException | RuntimeException ignored) { /* Retry on the next refresh. */ }
            }
        }
    }
}
