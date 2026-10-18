package io.github.maulrin.kikucompanion;

import android.app.Activity;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.pm.PackageManager;
import android.net.Uri;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.provider.DocumentsContract;
import android.view.View;
import android.view.WindowManager;
import android.widget.Button;
import android.widget.LinearLayout;
import android.widget.ProgressBar;
import android.widget.ScrollView;
import android.widget.TextView;
import java.io.File;
import java.util.concurrent.Executors;

public final class MainActivity extends Activity {
    private static final int ACCESS = 10, FOLDER = 11;
    private static final Job JOB = new Job();
    private SharedPreferences preferences;
    private TextView accessText, folderText, status;
    private Button connect, choose, refresh;
    private ProgressBar progress;
    private final Runnable render = this::render;

    @Override public void onCreate(Bundle state) {
        super.onCreate(state);
        preferences = getSharedPreferences("companion", MODE_PRIVATE);
        ScrollView scroll = new ScrollView(this);
        LinearLayout content = new LinearLayout(this);
        content.setOrientation(LinearLayout.VERTICAL);
        int padding = dp(24);
        content.setPadding(padding, padding, padding, padding);
        scroll.addView(content);
        // Respect Android 15's edge-to-edge window insets, including gesture navigation.
        scroll.setOnApplyWindowInsetsListener((view, insets) -> {
            view.setPadding(insets.getSystemWindowInsetLeft(), insets.getSystemWindowInsetTop(),
                insets.getSystemWindowInsetRight(), insets.getSystemWindowInsetBottom());
            return insets;
        });
        text(content, "Kiku Companion", 30);
        text(content, "Keep your related expressions up to date on your phone.", 16);
        text(content, "1  Connect to AnkiDroid", 20);
        accessText = text(content, "", 15);
        connect = button(content, "Allow AnkiDroid access", () -> {
            if (getPackageManager().resolveContentProvider(AnkiExporter.AUTHORITY, 0) == null) {
                JOB.message = "Install and open the main AnkiDroid app first. This prototype does not support parallel AnkiDroid installs.";
                render();
            } else requestPermissions(new String[]{AnkiExporter.PERMISSION}, ACCESS);
        });
        text(content, "2  Choose your media folder", 20);
        folderText = text(content, "", 15);
        choose = button(content, "Choose collection.media", () -> {
            Intent picker = new Intent(Intent.ACTION_OPEN_DOCUMENT_TREE)
                .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION | Intent.FLAG_GRANT_WRITE_URI_PERMISSION
                    | Intent.FLAG_GRANT_PERSISTABLE_URI_PERMISSION);
            picker.putExtra(DocumentsContract.EXTRA_INITIAL_URI,
                Uri.parse("content://com.android.externalstorage.documents/document/primary%3AAnkiDroid"));
            startActivityForResult(picker, FOLDER);
        });
        text(content, "3  Refresh Kiku's note cache", 20);
        text(content, "Leave the AnkiDroid reviewer and finish syncing first. Keep this app open until the refresh finishes.", 15);
        refresh = button(content, "Refresh note cache", this::startExport);
        progress = new ProgressBar(this);
        content.addView(progress);
        status = text(content, "", 16);
        status.setAccessibilityLiveRegion(View.ACCESSIBILITY_LIVE_REGION_POLITE);
        text(content, "Preview 0.1 · Settings and kanji editing still use your current helper.", 13);
        setContentView(scroll);
        render();
    }

    @Override protected void onStart() { super.onStart(); JOB.listener = render; render(); }
    @Override protected void onStop() { JOB.listener = null; super.onStop(); }

    private void startExport() {
        String tree = preferences.getString("folder", "");
        if (tree.isEmpty() || checkSelfPermission(AnkiExporter.PERMISSION) != PackageManager.PERMISSION_GRANTED) return;
        Context app = getApplicationContext();
        JOB.start(app, Uri.parse(tree));
        render();
    }

    @Override public void onRequestPermissionsResult(int request, String[] permissions, int[] results) {
        super.onRequestPermissionsResult(request, permissions, results);
        if (request == ACCESS && (results.length == 0 || results[0] != PackageManager.PERMISSION_GRANTED)) {
            JOB.message = "AnkiDroid access was not granted. You can allow it using the button above.";
        }
        render();
    }

    @Override protected void onActivityResult(int request, int result, Intent data) {
        super.onActivityResult(request, result, data);
        if (request != FOLDER || result != RESULT_OK || data == null || data.getData() == null) return;
        Uri uri = data.getData();
        int flags = data.getFlags() & (Intent.FLAG_GRANT_READ_URI_PERMISSION | Intent.FLAG_GRANT_WRITE_URI_PERMISSION);
        try {
            if ((flags & Intent.FLAG_GRANT_WRITE_URI_PERMISSION) == 0) throw new SecurityException("Folder is read-only.");
            getContentResolver().takePersistableUriPermission(uri, flags);
            preferences.edit().putString("folder", uri.toString()).apply();
            JOB.message = "Folder selected. Ready to refresh.";
        } catch (SecurityException e) { JOB.message = "Cannot keep access to this folder. Choose a local, writable collection.media folder."; }
        render();
    }

    private void render() {
        boolean granted = checkSelfPermission(AnkiExporter.PERMISSION) == PackageManager.PERMISSION_GRANTED;
        String folder = preferences.getString("folder", "");
        accessText.setText(granted ? "Connected. Notes will only be read." : "Allow access so Kiku can read its notes.");
        folderText.setText(folder.isEmpty() ? "Select AnkiDroid → collection.media in the folder picker."
            : DocumentsContract.getTreeDocumentId(Uri.parse(folder)));
        connect.setEnabled(!JOB.busy && !granted);
        choose.setEnabled(!JOB.busy);
        refresh.setEnabled(!JOB.busy && granted && !folder.isEmpty());
        progress.setVisibility(JOB.busy ? View.VISIBLE : View.GONE);
        status.setText(JOB.message);
        if (JOB.busy) getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
        else getWindow().clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
    }

    private TextView text(LinearLayout parent, String value, int size) {
        TextView view = new TextView(this);
        view.setText(value);
        view.setTextSize(size);
        view.setPadding(0, dp(size >= 20 ? 24 : 8), 0, dp(8));
        parent.addView(view);
        return view;
    }
    private Button button(LinearLayout parent, String label, Runnable action) {
        Button view = new Button(this);
        view.setText(label);
        view.setAllCaps(false);
        view.setOnClickListener(ignored -> action.run());
        parent.addView(view, new LinearLayout.LayoutParams(-1, -2));
        return view;
    }
    private int dp(int value) { return Math.round(value * getResources().getDisplayMetrics().density); }

    /** Retains progress across rotation without holding an Activity or keeping a server running. */
    private static final class Job {
        private final Handler main = new Handler(Looper.getMainLooper());
        private volatile boolean busy;
        private volatile String message = "Ready when you are.";
        private Runnable listener;

        void start(Context app, Uri tree) {
            if (busy) return;
            busy = true;
            update("Opening the media folder…");
            java.util.concurrent.ExecutorService executor = Executors.newSingleThreadExecutor();
            executor.execute(() -> {
                try {
                    MediaFolder media = new MediaFolder(app.getContentResolver(), tree);
                    int count = new AnkiExporter(app.getContentResolver()).export(media, app.getCacheDir(),
                        new File(app.getFilesDir(), "cache-recovery.json"), this::update);
                    update("Refreshed " + count + " notes. Close and reopen AnkiDroid before reviewing to load the new cache.");
                } catch (Exception e) {
                    String detail = e.getMessage();
                    update("Refresh did not finish. " + (detail == null ? "Open AnkiDroid and check folder access, then try again." : detail));
                } finally {
                    busy = false;
                    main.post(() -> { if (listener != null) listener.run(); });
                    executor.shutdown();
                }
            });
        }
        private void update(String value) {
            message = value;
            main.post(() -> { if (listener != null) listener.run(); });
        }
    }
}
