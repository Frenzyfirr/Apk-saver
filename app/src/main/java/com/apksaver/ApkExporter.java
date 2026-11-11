package com.apksaver;

import android.content.pm.ApplicationInfo;
import android.content.pm.PackageInfo;
import android.content.pm.PackageManager;

import java.io.File;
import java.io.FileInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.util.ArrayList;
import java.util.List;
import java.util.zip.Deflater;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

/** Reads the installed APK file(s) of an app and writes them to a stream. */
final class ApkExporter {

    static final String APK_MIME = "application/vnd.android.package-archive";
    static final String BIN_MIME = "application/octet-stream";

    /** Receives progress in bytes. */
    interface Progress {
        void onBytes(long bytesJustWritten);
    }

    private ApkExporter() {}

    /** True if the app is installed as split APKs (App Bundle); those are exported as a .apks archive. */
    static boolean isSplit(ApplicationInfo ai) {
        return ai.splitSourceDirs != null && ai.splitSourceDirs.length > 0;
    }

    static List<File> files(ApplicationInfo ai) {
        List<File> out = new ArrayList<>();
        out.add(new File(ai.sourceDir));
        if (isSplit(ai)) {
            for (String s : ai.splitSourceDirs) out.add(new File(s));
        }
        return out;
    }

    static long totalSize(ApplicationInfo ai) {
        long sum = 0;
        for (File f : files(ai)) sum += f.length();
        return sum;
    }

    static String mime(ApplicationInfo ai) {
        return isSplit(ai) ? BIN_MIME : APK_MIME;
    }

    /** e.g. "Firefox_v132.0.apk" or "Some_Game_v2.1.apks". */
    static String fileName(PackageManager pm, ApplicationInfo ai) {
        String label;
        try {
            label = ai.loadLabel(pm).toString();
        } catch (Exception e) {
            label = ai.packageName;
        }
        String version = "";
        try {
            PackageInfo pi = pm.getPackageInfo(ai.packageName, 0);
            if (pi.versionName != null && !pi.versionName.isEmpty()) version = "_v" + pi.versionName;
        } catch (PackageManager.NameNotFoundException ignored) {
        }
        String base = sanitize(label + version);
        if (base.isEmpty()) base = sanitize(ai.packageName);
        return base + (isSplit(ai) ? ".apks" : ".apk");
    }

    static String sanitize(String s) {
        String r = s.replaceAll("[\\\\/:*?\"<>|\\p{Cntrl}]", "")
                .replaceAll("\\s+", "_")
                .replaceAll("_+", "_");
        if (r.length() > 100) r = r.substring(0, 100);
        return r;
    }

    /**
     * Writes the app to {@code out}. A single APK is copied as-is; split apps are written as a
     * .apks (zip of base.apk + split_*.apk) that installers such as SAI can install.
     * The caller closes {@code out}.
     */
    static void write(ApplicationInfo ai, OutputStream out, Progress progress) throws IOException {
        if (!isSplit(ai)) {
            copy(new File(ai.sourceDir), out, progress);
            out.flush();
            return;
        }
        ZipOutputStream zos = new ZipOutputStream(out);
        zos.setLevel(Deflater.NO_COMPRESSION); // APKs are already compressed; keep it fast.
        for (File f : files(ai)) {
            zos.putNextEntry(new ZipEntry(f.getName()));
            copy(f, zos, progress);
            zos.closeEntry();
        }
        zos.finish();
        zos.flush();
    }

    private static void copy(File f, OutputStream out, Progress progress) throws IOException {
        byte[] buf = new byte[256 * 1024];
        try (InputStream in = new FileInputStream(f)) {
            int n;
            while ((n = in.read(buf)) > 0) {
                if (Thread.currentThread().isInterrupted()) throw new IOException("Cancelled");
                out.write(buf, 0, n);
                if (progress != null) progress.onBytes(n);
            }
        }
    }
}
