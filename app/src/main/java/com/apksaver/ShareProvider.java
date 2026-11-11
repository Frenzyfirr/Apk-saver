package com.apksaver;

import android.content.ContentProvider;
import android.content.ContentValues;
import android.content.Context;
import android.content.pm.ApplicationInfo;
import android.content.pm.PackageManager;
import android.database.Cursor;
import android.database.MatrixCursor;
import android.net.Uri;
import android.os.ParcelFileDescriptor;
import android.provider.OpenableColumns;

import java.io.File;
import java.io.FileNotFoundException;
import java.io.IOException;
import java.util.List;

/**
 * Minimal read-only provider used for sharing (no AndroidX needed).
 *  content://AUTH/apk/PACKAGE/NAME.apk -> the installed base.apk, served directly (no copy)
 *  content://AUTH/tmp/NAME.apks        -> a bundle built in the cache folder
 * Not exported; other apps can only read URIs we explicitly grant.
 */
public class ShareProvider extends ContentProvider {

    static String authority(Context c) {
        return c.getPackageName() + ".share";
    }

    static Uri apkUri(Context c, String pkg, String displayName) {
        return new Uri.Builder().scheme("content").authority(authority(c))
                .appendPath("apk").appendPath(pkg).appendPath(displayName).build();
    }

    static Uri tmpUri(Context c, String displayName) {
        return new Uri.Builder().scheme("content").authority(authority(c))
                .appendPath("tmp").appendPath(displayName).build();
    }

    static File shareDir(Context c) {
        File d = new File(c.getCacheDir(), "share");
        //noinspection ResultOfMethodCallIgnored
        d.mkdirs();
        return d;
    }

    static void clearShareDir(Context c) {
        File[] files = shareDir(c).listFiles();
        if (files == null) return;
        for (File f : files) //noinspection ResultOfMethodCallIgnored
            f.delete();
    }

    private File resolve(Uri uri) throws FileNotFoundException {
        Context c = getContext();
        if (c == null) throw new FileNotFoundException();
        List<String> s = uri.getPathSegments();
        if (s.size() == 3 && "apk".equals(s.get(0))) {
            try {
                ApplicationInfo ai = c.getPackageManager().getApplicationInfo(s.get(1), 0);
                return new File(ai.sourceDir);
            } catch (PackageManager.NameNotFoundException e) {
                throw new FileNotFoundException("App not installed: " + s.get(1));
            }
        }
        if (s.size() == 2 && "tmp".equals(s.get(0))) {
            try {
                File dir = shareDir(c).getCanonicalFile();
                File f = new File(dir, s.get(1)).getCanonicalFile();
                if (!dir.equals(f.getParentFile())) throw new FileNotFoundException();
                return f;
            } catch (IOException e) {
                throw new FileNotFoundException(e.getMessage());
            }
        }
        throw new FileNotFoundException(uri.toString());
    }

    @Override
    public boolean onCreate() {
        return true;
    }

    @Override
    public ParcelFileDescriptor openFile(Uri uri, String mode) throws FileNotFoundException {
        if (mode != null && !mode.equals("r")) throw new SecurityException("Read-only");
        return ParcelFileDescriptor.open(resolve(uri), ParcelFileDescriptor.MODE_READ_ONLY);
    }

    @Override
    public Cursor query(Uri uri, String[] projection, String selection, String[] selectionArgs, String sortOrder) {
        if (projection == null) projection = new String[]{OpenableColumns.DISPLAY_NAME, OpenableColumns.SIZE};
        long size = 0;
        try {
            size = resolve(uri).length();
        } catch (FileNotFoundException ignored) {
        }
        MatrixCursor cursor = new MatrixCursor(projection, 1);
        Object[] row = new Object[projection.length];
        for (int i = 0; i < projection.length; i++) {
            if (OpenableColumns.DISPLAY_NAME.equals(projection[i])) row[i] = uri.getLastPathSegment();
            else if (OpenableColumns.SIZE.equals(projection[i])) row[i] = size;
        }
        cursor.addRow(row);
        return cursor;
    }

    @Override
    public String getType(Uri uri) {
        String name = uri.getLastPathSegment();
        return name != null && name.endsWith(".apk") ? ApkExporter.APK_MIME : ApkExporter.BIN_MIME;
    }

    @Override
    public Uri insert(Uri uri, ContentValues values) {
        throw new UnsupportedOperationException();
    }

    @Override
    public int delete(Uri uri, String selection, String[] selectionArgs) {
        throw new UnsupportedOperationException();
    }

    @Override
    public int update(Uri uri, ContentValues values, String selection, String[] selectionArgs) {
        throw new UnsupportedOperationException();
    }
}
