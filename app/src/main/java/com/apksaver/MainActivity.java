package com.apksaver;

import android.Manifest;
import android.app.Activity;
import android.app.AlertDialog;
import android.content.ActivityNotFoundException;
import android.content.ClipData;
import android.content.ContentResolver;
import android.content.ContentValues;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.pm.ApplicationInfo;
import android.content.pm.PackageInfo;
import android.content.pm.PackageManager;
import android.graphics.drawable.Drawable;
import android.media.MediaScannerConnection;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.os.Environment;
import android.os.Handler;
import android.os.Looper;
import android.provider.DocumentsContract;
import android.provider.MediaStore;
import android.provider.Settings;
import android.text.Editable;
import android.text.TextWatcher;
import android.text.format.Formatter;
import android.view.LayoutInflater;
import android.view.Menu;
import android.view.MenuItem;
import android.view.View;
import android.view.ViewGroup;
import android.widget.BaseAdapter;
import android.widget.Button;
import android.widget.CheckBox;
import android.widget.EditText;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.ListView;
import android.widget.ProgressBar;
import android.widget.TextView;
import android.widget.Toast;

import java.io.File;
import java.io.FileOutputStream;
import java.io.OutputStream;
import java.text.Collator;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;

public class MainActivity extends Activity {

    private static final int REQ_SAVE_AS = 1;
    private static final int REQ_SAVE_TREE = 2;
    private static final int REQ_STORAGE_PERM = 3;

    private static final String DOWNLOAD_SUBFOLDER = "APK Saver";
    private static final int SORT_NAME = 0, SORT_SIZE = 1, SORT_UPDATED = 2;

    /** One row in the list. */
    static final class AppItem {
        String label, labelLower, pkg, version;
        long size, updated;
        boolean system, split;
        ApplicationInfo ai;
    }

    private final Handler main = new Handler(Looper.getMainLooper());
    private final ExecutorService worker = Executors.newSingleThreadExecutor();
    private final ExecutorService iconLoader = Executors.newFixedThreadPool(2);

    private final List<AppItem> all = new ArrayList<>();
    private final List<AppItem> shown = new ArrayList<>();
    private final Set<String> selected = new LinkedHashSet<>();
    private final Map<String, Drawable> icons = new HashMap<>();
    private final Set<String> iconsLoading = new HashSet<>();

    private SharedPreferences prefs;
    private PackageManager pm;
    private boolean showSystem;
    private int sortMode;
    private String query = "";

    /** Packages waiting for a picker / permission result. */
    private ArrayList<String> pending = new ArrayList<>();

    private ListView list;
    private ProgressBar loading;
    private TextView empty, count, selectionCount;
    private LinearLayout selectionBar;
    private Adapter adapter;
    private Drawable defaultIcon;
    private Future<?> loadTask;

    // ---------------------------------------------------------------- lifecycle

    @Override
    protected void onCreate(Bundle state) {
        super.onCreate(state);
        setContentView(R.layout.activity_main);
        pm = getPackageManager();
        prefs = getSharedPreferences("prefs", MODE_PRIVATE);
        showSystem = prefs.getBoolean("showSystem", false);
        sortMode = prefs.getInt("sort", SORT_NAME);
        defaultIcon = pm.getDefaultActivityIcon();

        list = findViewById(R.id.list);
        loading = findViewById(R.id.loading);
        empty = findViewById(R.id.empty);
        count = findViewById(R.id.count);
        selectionBar = findViewById(R.id.selection_bar);
        selectionCount = findViewById(R.id.selection_count);
        EditText search = findViewById(R.id.search);

        if (state != null) {
            ArrayList<String> sel = state.getStringArrayList("selected");
            if (sel != null) selected.addAll(sel);
            ArrayList<String> p = state.getStringArrayList("pending");
            if (p != null) pending = p;
            query = state.getString("query", "");
        }

        adapter = new Adapter();
        list.setAdapter(adapter);
        list.setOnItemClickListener((parent, view, position, id) -> {
            AppItem item = shown.get(position);
            if (!selected.isEmpty()) toggle(item);
            else showActions(item);
        });
        list.setOnItemLongClickListener((parent, view, position, id) -> {
            toggle(shown.get(position));
            return true;
        });

        search.addTextChangedListener(new TextWatcher() {
            @Override public void beforeTextChanged(CharSequence s, int a, int b, int c) {}
            @Override public void onTextChanged(CharSequence s, int a, int b, int c) {}
            @Override public void afterTextChanged(Editable s) {
                query = s.toString().trim().toLowerCase(Locale.ROOT);
                applyFilter();
            }
        });

        Button clear = findViewById(R.id.btn_clear);
        Button save = findViewById(R.id.btn_save);
        Button share = findViewById(R.id.btn_share);
        clear.setOnClickListener(v -> { selected.clear(); refreshSelection(); });
        save.setOnClickListener(v -> askSaveDestination(new ArrayList<>(selected)));
        share.setOnClickListener(v -> share(new ArrayList<>(selected)));

        loadApps();
    }

    @Override
    protected void onSaveInstanceState(Bundle out) {
        super.onSaveInstanceState(out);
        out.putStringArrayList("selected", new ArrayList<>(selected));
        out.putStringArrayList("pending", pending);
        out.putString("query", query);
    }

    @Override
    protected void onDestroy() {
        worker.shutdownNow();
        iconLoader.shutdownNow();
        super.onDestroy();
    }

    @Override
    public void onBackPressed() {
        if (!selected.isEmpty()) {
            selected.clear();
            refreshSelection();
        } else {
            super.onBackPressed();
        }
    }

    // ---------------------------------------------------------------- menu

    @Override
    public boolean onCreateOptionsMenu(Menu menu) {
        getMenuInflater().inflate(R.menu.main, menu);
        return true;
    }

    @Override
    public boolean onPrepareOptionsMenu(Menu menu) {
        menu.findItem(R.id.action_system).setChecked(showSystem);
        int sortId = sortMode == SORT_SIZE ? R.id.sort_size
                : sortMode == SORT_UPDATED ? R.id.sort_updated : R.id.sort_name;
        menu.findItem(sortId).setChecked(true);
        return super.onPrepareOptionsMenu(menu);
    }

    @Override
    public boolean onOptionsItemSelected(MenuItem item) {
        int id = item.getItemId();
        if (id == R.id.action_select_all) {
            boolean allSelected = !shown.isEmpty();
            for (AppItem a : shown) if (!selected.contains(a.pkg)) { allSelected = false; break; }
            for (AppItem a : shown) {
                if (allSelected) selected.remove(a.pkg); else selected.add(a.pkg);
            }
            refreshSelection();
        } else if (id == R.id.action_system) {
            showSystem = !showSystem;
            prefs.edit().putBoolean("showSystem", showSystem).apply();
            applyFilter();
        } else if (id == R.id.sort_name || id == R.id.sort_size || id == R.id.sort_updated) {
            sortMode = id == R.id.sort_size ? SORT_SIZE : id == R.id.sort_updated ? SORT_UPDATED : SORT_NAME;
            prefs.edit().putInt("sort", sortMode).apply();
            applyFilter();
        } else if (id == R.id.action_refresh) {
            loadApps();
        } else if (id == R.id.action_about) {
            new AlertDialog.Builder(this)
                    .setTitle(R.string.app_name)
                    .setMessage(R.string.about_text)
                    .setPositiveButton(android.R.string.ok, null)
                    .show();
        } else {
            return super.onOptionsItemSelected(item);
        }
        invalidateOptionsMenu();
        return true;
    }

    // ---------------------------------------------------------------- loading

    private void loadApps() {
        loading.setVisibility(View.VISIBLE);
        empty.setVisibility(View.GONE);
        if (loadTask != null) loadTask.cancel(true);
        loadTask = worker.submit(() -> {
            List<AppItem> items = new ArrayList<>();
            List<PackageInfo> pkgs = pm.getInstalledPackages(0);
            for (PackageInfo pi : pkgs) {
                ApplicationInfo ai = pi.applicationInfo;
                if (ai == null || ai.sourceDir == null) continue;
                AppItem it = new AppItem();
                it.ai = ai;
                it.pkg = pi.packageName;
                it.label = String.valueOf(ai.loadLabel(pm));
                it.labelLower = it.label.toLowerCase(Locale.ROOT);
                it.version = pi.versionName == null ? "" : pi.versionName;
                it.updated = pi.lastUpdateTime;
                // Updated system apps (e.g. Chrome) count as user apps so they show by default.
                it.system = (ai.flags & ApplicationInfo.FLAG_SYSTEM) != 0
                        && (ai.flags & ApplicationInfo.FLAG_UPDATED_SYSTEM_APP) == 0;
                it.split = ApkExporter.isSplit(ai);
                it.size = ApkExporter.totalSize(ai);
                items.add(it);
            }
            main.post(() -> {
                all.clear();
                all.addAll(items);
                // Drop selections for apps that are gone.
                Set<String> present = new HashSet<>();
                for (AppItem a : all) present.add(a.pkg);
                selected.retainAll(present);
                loading.setVisibility(View.GONE);
                applyFilter();
            });
        });
    }

    private void applyFilter() {
        shown.clear();
        for (AppItem a : all) {
            if (!showSystem && a.system) continue;
            if (!query.isEmpty() && !a.labelLower.contains(query) && !a.pkg.toLowerCase(Locale.ROOT).contains(query))
                continue;
            shown.add(a);
        }
        final Collator collator = Collator.getInstance();
        Comparator<AppItem> byName = (x, y) -> collator.compare(x.label, y.label);
        Comparator<AppItem> cmp;
        if (sortMode == SORT_SIZE) cmp = (x, y) -> Long.compare(y.size, x.size);
        else if (sortMode == SORT_UPDATED) cmp = (x, y) -> Long.compare(y.updated, x.updated);
        else cmp = byName;
        Collections.sort(shown, cmp);

        count.setText(getResources().getQuantityString(R.plurals.app_count, shown.size(), shown.size()));
        empty.setVisibility(shown.isEmpty() && loading.getVisibility() != View.VISIBLE ? View.VISIBLE : View.GONE);
        adapter.notifyDataSetChanged();
        refreshSelection();
    }

    // ---------------------------------------------------------------- selection

    private void toggle(AppItem item) {
        if (!selected.remove(item.pkg)) selected.add(item.pkg);
        refreshSelection();
    }

    private void refreshSelection() {
        boolean active = !selected.isEmpty();
        selectionBar.setVisibility(active ? View.VISIBLE : View.GONE);
        if (active) selectionCount.setText(getString(R.string.n_selected, selected.size()));
        adapter.notifyDataSetChanged();
    }

    // ---------------------------------------------------------------- actions

    private void showActions(AppItem item) {
        String[] options = {
                getString(R.string.action_save_downloads),
                getString(R.string.action_save_as),
                getString(R.string.action_share),
                getString(R.string.action_select),
                getString(R.string.action_app_info)
        };
        String details = item.pkg + "\n" + summary(item);
        View header = LayoutInflater.from(this).inflate(R.layout.dialog_header, null);
        ((ImageView) header.findViewById(R.id.icon)).setImageDrawable(iconFor(item));
        ((TextView) header.findViewById(R.id.title)).setText(item.label);
        ((TextView) header.findViewById(R.id.subtitle)).setText(details);

        ArrayList<String> one = new ArrayList<>();
        one.add(item.pkg);
        new AlertDialog.Builder(this)
                .setCustomTitle(header)
                .setItems(options, (d, which) -> {
                    switch (which) {
                        case 0: saveToDownloads(one); break;
                        case 1: saveAs(item); break;
                        case 2: share(one); break;
                        case 3: toggle(item); break;
                        case 4: openAppInfo(item.pkg); break;
                    }
                })
                .show();
    }

    private void askSaveDestination(ArrayList<String> pkgs) {
        if (pkgs.isEmpty()) return;
        String[] options = {
                getString(R.string.dest_downloads, DOWNLOAD_SUBFOLDER),
                getString(R.string.dest_choose_folder)
        };
        new AlertDialog.Builder(this)
                .setTitle(getString(R.string.save_n_apps, pkgs.size()))
                .setItems(options, (d, which) -> {
                    if (which == 0) saveToDownloads(pkgs);
                    else chooseFolder(pkgs);
                })
                .show();
    }

    private void openAppInfo(String pkg) {
        try {
            startActivity(new Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.fromParts("package", pkg, null)));
        } catch (ActivityNotFoundException e) {
            toast(getString(R.string.error_no_handler));
        }
    }

    // --- Save to Downloads/APK Saver (no picker)

    private void saveToDownloads(ArrayList<String> pkgs) {
        if (Build.VERSION.SDK_INT >= 23 && Build.VERSION.SDK_INT < 29
                && checkSelfPermission(Manifest.permission.WRITE_EXTERNAL_STORAGE) != PackageManager.PERMISSION_GRANTED) {
            pending = pkgs;
            requestPermissions(new String[]{Manifest.permission.WRITE_EXTERNAL_STORAGE}, REQ_STORAGE_PERM);
            return;
        }
        runExport(pkgs, (ai, name, progress) -> {
            ContentResolver cr = getContentResolver();
            if (Build.VERSION.SDK_INT >= 29) {
                ContentValues v = new ContentValues();
                v.put(MediaStore.MediaColumns.DISPLAY_NAME, name);
                v.put(MediaStore.MediaColumns.MIME_TYPE, ApkExporter.mime(ai));
                v.put(MediaStore.MediaColumns.RELATIVE_PATH, Environment.DIRECTORY_DOWNLOADS + "/" + DOWNLOAD_SUBFOLDER);
                v.put(MediaStore.MediaColumns.IS_PENDING, 1);
                Uri uri = cr.insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, v);
                if (uri == null) throw new java.io.IOException(getString(R.string.error_create_file));
                try (OutputStream out = cr.openOutputStream(uri)) {
                    if (out == null) throw new java.io.IOException(getString(R.string.error_create_file));
                    ApkExporter.write(ai, out, progress);
                } catch (Exception e) {
                    cr.delete(uri, null, null);
                    throw e;
                }
                v.clear();
                v.put(MediaStore.MediaColumns.IS_PENDING, 0);
                cr.update(uri, v, null, null);
            } else {
                @SuppressWarnings("deprecation")
                File dir = new File(Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS), DOWNLOAD_SUBFOLDER);
                if (!dir.isDirectory() && !dir.mkdirs()) throw new java.io.IOException(getString(R.string.error_create_file));
                File f = uniqueFile(dir, name);
                try (OutputStream out = new FileOutputStream(f)) {
                    ApkExporter.write(ai, out, progress);
                } catch (Exception e) {
                    //noinspection ResultOfMethodCallIgnored
                    f.delete();
                    throw e;
                }
                MediaScannerConnection.scanFile(this, new String[]{f.getAbsolutePath()}, null, null);
            }
        }, getString(R.string.saved_to_downloads, "Download/" + DOWNLOAD_SUBFOLDER));
    }

    private static File uniqueFile(File dir, String name) {
        File f = new File(dir, name);
        if (!f.exists()) return f;
        int dot = name.lastIndexOf('.');
        String stem = dot > 0 ? name.substring(0, dot) : name;
        String ext = dot > 0 ? name.substring(dot) : "";
        for (int i = 1; ; i++) {
            f = new File(dir, stem + " (" + i + ")" + ext);
            if (!f.exists()) return f;
        }
    }

    @Override
    public void onRequestPermissionsResult(int requestCode, String[] permissions, int[] results) {
        if (requestCode == REQ_STORAGE_PERM) {
            if (results.length > 0 && results[0] == PackageManager.PERMISSION_GRANTED) {
                saveToDownloads(pending);
            } else {
                toast(getString(R.string.error_permission));
            }
        }
    }

    // --- Save As… (system file picker, one app)

    private void saveAs(AppItem item) {
        pending = new ArrayList<>();
        pending.add(item.pkg);
        Intent i = new Intent(Intent.ACTION_CREATE_DOCUMENT)
                .addCategory(Intent.CATEGORY_OPENABLE)
                .setType(ApkExporter.mime(item.ai))
                .putExtra(Intent.EXTRA_TITLE, ApkExporter.fileName(pm, item.ai));
        try {
            startActivityForResult(i, REQ_SAVE_AS);
        } catch (ActivityNotFoundException e) {
            toast(getString(R.string.error_no_picker));
        }
    }

    // --- Save to a chosen folder (system folder picker, many apps)

    private void chooseFolder(ArrayList<String> pkgs) {
        pending = pkgs;
        try {
            startActivityForResult(new Intent(Intent.ACTION_OPEN_DOCUMENT_TREE), REQ_SAVE_TREE);
        } catch (ActivityNotFoundException e) {
            toast(getString(R.string.error_no_picker));
        }
    }

    @Override
    protected void onActivityResult(int requestCode, int resultCode, Intent data) {
        super.onActivityResult(requestCode, resultCode, data);
        if (resultCode != RESULT_OK || data == null || data.getData() == null) return;
        final Uri uri = data.getData();
        final ContentResolver cr = getContentResolver();

        if (requestCode == REQ_SAVE_AS) {
            runExport(pending, (ai, name, progress) -> {
                try (OutputStream out = cr.openOutputStream(uri, "w")) {
                    if (out == null) throw new java.io.IOException(getString(R.string.error_create_file));
                    ApkExporter.write(ai, out, progress);
                }
            }, getString(R.string.saved));
        } else if (requestCode == REQ_SAVE_TREE) {
            final Uri parent = DocumentsContract.buildDocumentUriUsingTree(uri, DocumentsContract.getTreeDocumentId(uri));
            runExport(pending, (ai, name, progress) -> {
                Uri doc = DocumentsContract.createDocument(cr, parent, ApkExporter.mime(ai), name);
                if (doc == null) throw new java.io.IOException(getString(R.string.error_create_file));
                try (OutputStream out = cr.openOutputStream(doc, "w")) {
                    if (out == null) throw new java.io.IOException(getString(R.string.error_create_file));
                    ApkExporter.write(ai, out, progress);
                } catch (Exception e) {
                    try { DocumentsContract.deleteDocument(cr, doc); } catch (Exception ignored) {}
                    throw e;
                }
            }, getString(R.string.saved));
        }
    }

    // --- Share (Bluetooth, Quick Share / Nearby, Files, etc.)

    private void share(ArrayList<String> pkgs) {
        if (pkgs.isEmpty()) return;
        final ArrayList<Uri> uris = new ArrayList<>();
        final boolean[] allApk = {true};
        ProgressDialog dlg = new ProgressDialog(getString(R.string.preparing));
        dlg.future = worker.submit(() -> {
            try {
                ShareProvider.clearShareDir(this);
                long total = 0;
                List<ApplicationInfo> infos = new ArrayList<>();
                for (String p : pkgs) {
                    ApplicationInfo ai = pm.getApplicationInfo(p, 0);
                    infos.add(ai);
                    if (ApkExporter.isSplit(ai)) total += ApkExporter.totalSize(ai);
                }
                final long grand = total;
                final long[] done = {0};
                int i = 0;
                for (ApplicationInfo ai : infos) {
                    i++;
                    String name = ApkExporter.fileName(pm, ai);
                    if (!ApkExporter.isSplit(ai)) {
                        // Single APK: serve the installed file directly, no copy needed.
                        uris.add(ShareProvider.apkUri(this, ai.packageName, name));
                    } else {
                        allApk[0] = false;
                        File f = new File(ShareProvider.shareDir(this), name);
                        String label = name + "  (" + i + "/" + infos.size() + ")";
                        try (OutputStream out = new FileOutputStream(f)) {
                            ApkExporter.write(ai, out, n -> { done[0] += n; dlg.post(label, done[0], grand); });
                        }
                        uris.add(ShareProvider.tmpUri(this, name));
                    }
                }
                main.post(() -> {
                    dlg.dismiss();
                    launchShare(uris, allApk[0]);
                });
            } catch (Exception e) {
                final boolean cancelled = Thread.currentThread().isInterrupted();
                main.post(() -> {
                    dlg.dismiss();
                    if (!cancelled) toast(getString(R.string.error_generic, e.getMessage()));
                });
            }
        });
    }

    private void launchShare(ArrayList<Uri> uris, boolean allApk) {
        Intent send;
        if (uris.size() == 1) {
            send = new Intent(Intent.ACTION_SEND).putExtra(Intent.EXTRA_STREAM, uris.get(0));
        } else {
            send = new Intent(Intent.ACTION_SEND_MULTIPLE).putParcelableArrayListExtra(Intent.EXTRA_STREAM, uris);
        }
        send.setType(allApk ? ApkExporter.APK_MIME : ApkExporter.BIN_MIME);
        ClipData clip = ClipData.newRawUri("", uris.get(0));
        for (int i = 1; i < uris.size(); i++) clip.addItem(new ClipData.Item(uris.get(i)));
        send.setClipData(clip);
        send.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION);
        try {
            startActivity(Intent.createChooser(send, getString(R.string.share_via)));
        } catch (ActivityNotFoundException e) {
            toast(getString(R.string.error_no_handler));
        }
    }

    // ---------------------------------------------------------------- export runner

    interface Writer {
        void write(ApplicationInfo ai, String fileName, ApkExporter.Progress progress) throws Exception;
    }

    private void runExport(List<String> pkgs, Writer writer, String doneMessage) {
        if (pkgs == null || pkgs.isEmpty()) return;
        final List<String> todo = new ArrayList<>(pkgs);
        ProgressDialog dlg = new ProgressDialog(getString(R.string.saving));
        dlg.future = worker.submit(() -> {
            int ok = 0;
            List<String> failed = new ArrayList<>();
            List<ApplicationInfo> infos = new ArrayList<>();
            long total = 0;
            for (String p : todo) {
                try {
                    ApplicationInfo ai = pm.getApplicationInfo(p, 0);
                    infos.add(ai);
                    total += ApkExporter.totalSize(ai);
                } catch (PackageManager.NameNotFoundException e) {
                    failed.add(p);
                }
            }
            final long grand = total;
            final long[] done = {0};
            int i = 0;
            for (ApplicationInfo ai : infos) {
                if (Thread.currentThread().isInterrupted()) break;
                i++;
                String name = ApkExporter.fileName(pm, ai);
                String label = name + "  (" + i + "/" + infos.size() + ")";
                dlg.post(label, done[0], grand);
                try {
                    writer.write(ai, name, n -> { done[0] += n; dlg.post(label, done[0], grand); });
                    ok++;
                } catch (Exception e) {
                    failed.add(name + ": " + e.getMessage());
                }
            }
            final int okCount = ok;
            final boolean cancelled = Thread.currentThread().isInterrupted();
            main.post(() -> {
                dlg.dismiss();
                if (cancelled) {
                    toast(getString(R.string.cancelled));
                } else if (failed.isEmpty()) {
                    toast(doneMessage + (todo.size() > 1 ? " (" + okCount + ")" : ""));
                } else {
                    StringBuilder sb = new StringBuilder();
                    sb.append(getString(R.string.saved_partial, okCount, todo.size())).append("\n\n");
                    for (String f : failed) sb.append("• ").append(f).append('\n');
                    new AlertDialog.Builder(this)
                            .setTitle(R.string.some_failed)
                            .setMessage(sb.toString())
                            .setPositiveButton(android.R.string.ok, null)
                            .show();
                }
                if (todo.size() > 1) { selected.clear(); refreshSelection(); }
            });
        });
    }

    /** Small cancellable progress dialog driven from a background thread. */
    private final class ProgressDialog {
        final AlertDialog dialog;
        final ProgressBar bar;
        final TextView text;
        Future<?> future;
        private long lastPost;

        ProgressDialog(String title) {
            View v = LayoutInflater.from(MainActivity.this).inflate(R.layout.dialog_progress, null);
            bar = v.findViewById(R.id.progress);
            text = v.findViewById(R.id.progress_text);
            bar.setMax(1000);
            dialog = new AlertDialog.Builder(MainActivity.this)
                    .setTitle(title)
                    .setView(v)
                    .setCancelable(false)
                    .setNegativeButton(android.R.string.cancel, (d, w) -> {
                        if (future != null) future.cancel(true);
                    })
                    .create();
            dialog.show();
        }

        /** Throttled to ~10 updates per second. */
        void post(String label, long done, long total) {
            long now = System.currentTimeMillis();
            if (now - lastPost < 100 && done < total) return;
            lastPost = now;
            main.post(() -> {
                if (!dialog.isShowing()) return;
                bar.setProgress(total > 0 ? (int) (done * 1000 / total) : 0);
                text.setText(label + "\n" + Formatter.formatShortFileSize(MainActivity.this, done)
                        + " / " + Formatter.formatShortFileSize(MainActivity.this, total));
            });
        }

        void dismiss() {
            if (dialog.isShowing() && !isFinishing()) dialog.dismiss();
        }
    }

    // ---------------------------------------------------------------- helpers

    private String summary(AppItem a) {
        StringBuilder sb = new StringBuilder();
        if (!a.version.isEmpty()) sb.append('v').append(a.version).append("  •  ");
        sb.append(Formatter.formatShortFileSize(this, a.size));
        if (a.split) sb.append("  •  ").append(getString(R.string.split_label));
        if (a.system) sb.append("  •  ").append(getString(R.string.system_label));
        return sb.toString();
    }

    private Drawable iconFor(AppItem a) {
        Drawable d = icons.get(a.pkg);
        return d != null ? d : defaultIcon;
    }

    private void toast(String msg) {
        Toast.makeText(this, msg, Toast.LENGTH_LONG).show();
    }

    // ---------------------------------------------------------------- adapter

    private final class Adapter extends BaseAdapter {
        @Override public int getCount() { return shown.size(); }
        @Override public Object getItem(int position) { return shown.get(position); }
        @Override public long getItemId(int position) { return position; }

        @Override
        public View getView(int position, View convertView, ViewGroup parent) {
            Holder h;
            if (convertView == null) {
                convertView = LayoutInflater.from(MainActivity.this).inflate(R.layout.item_app, parent, false);
                h = new Holder();
                h.icon = convertView.findViewById(R.id.icon);
                h.label = convertView.findViewById(R.id.label);
                h.pkg = convertView.findViewById(R.id.pkg);
                h.details = convertView.findViewById(R.id.details);
                h.check = convertView.findViewById(R.id.check);
                convertView.setTag(h);
            } else {
                h = (Holder) convertView.getTag();
            }
            final AppItem a = shown.get(position);
            h.label.setText(a.label);
            h.pkg.setText(a.pkg);
            h.details.setText(summary(a));
            boolean selecting = !selected.isEmpty();
            h.check.setVisibility(selecting ? View.VISIBLE : View.GONE);
            h.check.setChecked(selected.contains(a.pkg));

            h.icon.setTag(a.pkg);
            Drawable d = icons.get(a.pkg);
            h.icon.setImageDrawable(d != null ? d : defaultIcon);
            if (d == null && iconsLoading.add(a.pkg)) {
                final ImageView target = h.icon;
                iconLoader.submit(() -> {
                    Drawable loaded;
                    try { loaded = a.ai.loadIcon(pm); } catch (Exception e) { loaded = defaultIcon; }
                    final Drawable icon = loaded;
                    main.post(() -> {
                        icons.put(a.pkg, icon);
                        iconsLoading.remove(a.pkg);
                        if (a.pkg.equals(target.getTag())) target.setImageDrawable(icon);
                    });
                });
            }
            return convertView;
        }
    }

    private static final class Holder {
        ImageView icon;
        TextView label, pkg, details;
        CheckBox check;
    }
}
