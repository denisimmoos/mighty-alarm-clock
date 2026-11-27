// SPDX-License-Identifier: GPL-3.0-only

package com.best.deskclock.mighty.backup;

import android.content.ContentResolver;
import android.content.Context;
import android.content.SharedPreferences;
import android.net.Uri;

import androidx.documentfile.provider.DocumentFile;

import com.best.deskclock.utils.LogUtils;

import org.json.JSONException;
import org.json.JSONObject;

import java.io.IOException;
import java.io.OutputStream;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Date;
import java.util.List;
import java.util.Locale;

/**
 * Writes full backups (the same ZIP as the manual backup in the settings: settings, timers,
 * calendar alarm profiles, fonts and images) into a user-selected folder (via
 * {@code ACTION_OPEN_DOCUMENT_TREE}), so they can be restored with the settings' restore.
 * <p>
 * Two kinds of files are written on every export:
 * <ul>
 *     <li>A dated file, e.g. {@code MightyCalendarAlarms-backup-Pixel-2026-07-22_16-30-00.zip},
 *     including the device name, rotated so that only the most recent
 *     {@value #MAX_DATED_BACKUPS} dated files are kept.</li>
 *     <li>{@code MightyCalendarAlarms-backup-latest.zip}, always overwritten with the most recent export.</li>
 * </ul>
 */
public final class JsonBackupManager {

    private static final String PREFS_NAME = "mighty_backup_prefs";
    public static final String KEY_TREE_URI = "backup_tree_uri";
    public static final String KEY_SCHEDULE_ENABLED = "backup_schedule_enabled";
    public static final String KEY_INTERVAL_HOURS = "backup_interval_hours";

    private static final int MAX_DATED_BACKUPS = 14;
    // Own prefix so other apps can share the folder without overwriting these files.
    private static final String FILE_PREFIX = "MightyCalendarAlarms-backup-";
    // Name used before the app was called "Mighty Calendar Alarms". Dated files with it still
    // count towards MAX_DATED_BACKUPS, so they are cleaned up over time.
    private static final String OLD_FILE_PREFIX = "MightyCalendarAlarm-backup-";
    private static final String FILE_SUFFIX = ".zip";
    private static final String LATEST_FILE_NAME = FILE_PREFIX + "latest" + FILE_SUFFIX;
    private static final String MIME_ZIP = "application/zip";

    private JsonBackupManager() {
    }

    private static SharedPreferences prefs(Context context) {
        return context.getApplicationContext().getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE);
    }

    public static void setTreeUri(Context context, Uri treeUri) {
        prefs(context).edit().putString(KEY_TREE_URI, treeUri == null ? null : treeUri.toString()).apply();
    }

    public static Uri getTreeUri(Context context) {
        final String value = prefs(context).getString(KEY_TREE_URI, null);
        return value == null ? null : Uri.parse(value);
    }

    public static void setScheduleEnabled(Context context, boolean enabled) {
        prefs(context).edit().putBoolean(KEY_SCHEDULE_ENABLED, enabled).apply();
    }

    public static boolean isScheduleEnabled(Context context) {
        return prefs(context).getBoolean(KEY_SCHEDULE_ENABLED, false);
    }

    public static void setIntervalHours(Context context, int hours) {
        prefs(context).edit().putInt(KEY_INTERVAL_HOURS, hours).apply();
    }

    public static int getIntervalHours(Context context) {
        return prefs(context).getInt(KEY_INTERVAL_HOURS, 24);
    }

    /**
     * @return the automatic backup settings for the full backup.
     */
    public static org.json.JSONObject exportSettings(Context context) throws org.json.JSONException {
        final org.json.JSONObject json = new org.json.JSONObject();
        json.put(KEY_SCHEDULE_ENABLED, isScheduleEnabled(context));
        json.put(KEY_INTERVAL_HOURS, getIntervalHours(context));
        final Uri treeUri = getTreeUri(context);
        if (treeUri != null) {
            json.put(KEY_TREE_URI, treeUri.toString());
        }
        return json;
    }

    /**
     * Restores the automatic backup settings and schedules the backup again. The backup folder
     * is only taken over if this phone still has access to it, which is the case on the phone
     * the backup was made on.
     */
    public static void restoreSettings(Context context, @androidx.annotation.Nullable org.json.JSONObject json) {
        if (json == null) {
            return;
        }
        setIntervalHours(context, json.optInt(KEY_INTERVAL_HOURS, getIntervalHours(context)));
        setScheduleEnabled(context, json.optBoolean(KEY_SCHEDULE_ENABLED, isScheduleEnabled(context)));
        final String tree = json.optString(KEY_TREE_URI, "");
        if (!tree.isEmpty() && hasFolderAccess(context, Uri.parse(tree))) {
            setTreeUri(context, Uri.parse(tree));
        }
        try {
            if (isScheduleEnabled(context)) {
                BackupWorker.schedulePeriodic(context, getIntervalHours(context));
            } else {
                BackupWorker.cancel(context);
            }
        } catch (IllegalStateException e) {
            // WorkManager is not ready; the app schedules it again on its next start.
            LogUtils.e("JsonBackupManager: could not schedule the backup", e);
        }
    }

    /**
     * @return {@code true} if the app may still write to the folder.
     */
    public static boolean hasFolderAccess(Context context, Uri treeUri) {
        for (android.content.UriPermission permission : context.getContentResolver().getPersistedUriPermissions()) {
            if (permission.getUri().equals(treeUri) && permission.isWritePermission()) {
                return true;
            }
        }
        return false;
    }

    /**
     * Performs an export right now. Safe to call from a background thread.
     *
     * @return {@code true} if the export succeeded.
     */
    public static boolean exportNow(Context context) {
        final Uri treeUri = getTreeUri(context);
        if (treeUri == null) {
            LogUtils.i("JsonBackupManager: no backup folder configured, skipping export");
            return false;
        }

        try {
            final DocumentFile dir = DocumentFile.fromTreeUri(context, treeUri);
            if (dir == null || !dir.canWrite()) {
                LogUtils.e("JsonBackupManager: backup folder is not writable");
                return false;
            }

            final String dateStamp = new SimpleDateFormat("yyyy-MM-dd_HH-mm-ss", Locale.US).format(new Date());
            final String devicePart = deviceNameForFile(context);
            writeBackupFile(context, dir, FILE_PREFIX + devicePart + "-" + dateStamp + FILE_SUFFIX);
            writeLatestBackupFile(context, dir);

            rotateOldBackups(dir);

            LogUtils.i("JsonBackupManager: export completed successfully");
            return true;
        } catch (Exception e) {
            LogUtils.e("JsonBackupManager: export failed", e);
            return false;
        }
    }

    /**
     * @return the device name (or model) made safe for a file name.
     */
    private static String deviceNameForFile(Context context) {
        String name = null;
        if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.N_MR1) {
            try {
                name = android.provider.Settings.Global.getString(context.getContentResolver(),
                    android.provider.Settings.Global.DEVICE_NAME);
            } catch (Exception ignored) {
                // Fall back to the model below.
            }
        }
        if (name == null || name.trim().isEmpty()) {
            name = android.os.Build.MODEL;
        }
        if (name == null) {
            return "Device";
        }
        final String sanitized = name.trim()
            .replaceAll("[\\\\/:*?\"<>|\\x00-\\x1F]", "_")
            .replaceAll("\\s+", " ")
            .trim();
        if (sanitized.isEmpty() || sanitized.equals(".") || sanitized.equals("..")) {
            return "Device";
        }
        return sanitized.length() > 80 ? sanitized.substring(0, 80).trim() : sanitized;
    }

    private static void writeBackupFile(Context context, DocumentFile dir, String name)
        throws IOException, JSONException {
        final DocumentFile existing = dir.findFile(name);
        if (existing != null) {
            existing.delete();
        }

        final DocumentFile file = dir.createFile(MIME_ZIP, name);
        if (file == null) {
            throw new IOException("Could not create backup file: " + name);
        }

        final ContentResolver cr = context.getContentResolver();
        try (OutputStream out = cr.openOutputStream(file.getUri())) {
            if (out == null) {
                throw new IOException("Could not open output stream for: " + name);
            }
            com.best.deskclock.utils.BackupAndRestoreUtils.writeFullBackup(context,
                com.best.deskclock.DeskClockApplication.getDefaultSharedPreferences(context), out);
        }
    }

    /**
     * Replaces the latest backup only once the new one is complete, so a failed write never
     * leaves the folder without it.
     */
    private static void writeLatestBackupFile(Context context, DocumentFile dir) throws IOException, JSONException {
        final String partName = LATEST_FILE_NAME + ".part";
        final DocumentFile stale = dir.findFile(partName);
        if (stale != null) {
            stale.delete();
        }
        // A type without a file extension, so the folder keeps the name exactly as given.
        final DocumentFile part = dir.createFile("application/octet-stream", partName);
        if (part == null) {
            writeBackupFile(context, dir, LATEST_FILE_NAME);
            return;
        }
        try (OutputStream out = context.getContentResolver().openOutputStream(part.getUri())) {
            if (out == null) {
                throw new IOException("Could not open output stream for: " + partName);
            }
            com.best.deskclock.utils.BackupAndRestoreUtils.writeFullBackup(context,
                com.best.deskclock.DeskClockApplication.getDefaultSharedPreferences(context), out);
        } catch (IOException | JSONException | RuntimeException e) {
            part.delete();
            throw e;
        }

        final DocumentFile old = dir.findFile(LATEST_FILE_NAME);
        if (old != null) {
            old.delete();
        }
        if (!part.renameTo(LATEST_FILE_NAME)) {
            // Some folders can't rename files; write the latest backup directly instead.
            part.delete();
            writeBackupFile(context, dir, LATEST_FILE_NAME);
        }
    }

    private static void rotateOldBackups(DocumentFile dir) {
        final List<DocumentFile> dated = new ArrayList<>();
        for (DocumentFile file : dir.listFiles()) {
            final String name = file.getName();
            if (name != null && (name.startsWith(FILE_PREFIX) || name.startsWith(OLD_FILE_PREFIX))
                && name.endsWith(FILE_SUFFIX) && !name.equals(LATEST_FILE_NAME)
                && !name.equals(OLD_FILE_PREFIX + "latest" + FILE_SUFFIX)) {
                dated.add(file);
            }
        }

        // Collections.sort: List.sort needs Android 7, and the app runs on Android 6.
        java.util.Collections.sort(dated, (a, b) -> Long.compare(a.lastModified(), b.lastModified()));

        while (dated.size() > MAX_DATED_BACKUPS) {
            final DocumentFile oldest = dated.remove(0);
            oldest.delete();
        }
    }
}
