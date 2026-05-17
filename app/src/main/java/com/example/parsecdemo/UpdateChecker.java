package com.example.parsecdemo;

import android.app.Activity;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.net.Uri;
import android.os.Handler;
import android.os.Looper;
import android.util.Log;

import com.google.android.material.dialog.MaterialAlertDialogBuilder;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.BufferedReader;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.net.HttpURLConnection;
import java.net.URL;
import java.util.concurrent.TimeUnit;

/**
 * Polls the GitHub Releases API for newer versions and offers the user a
 * one-tap path to the download. Designed to be non-intrusive:
 *  • Runs on a background thread, no blocking work on UI.
 *  • Silently skips on network failure (no error dialog).
 *  • Throttled to once per 24h via {@link SharedPreferences}.
 *  • If the latest tag matches BuildConfig.VERSION_NAME, no dialog is shown.
 *
 * Configure the repo via {@link #REPO_OWNER} and {@link #REPO_NAME}.
 */
public final class UpdateChecker {

    private static final String TAG = "UpdateChecker";

    public static final String REPO_OWNER = "nomadsgalaxy";
    public static final String REPO_NAME  = "OpenParsec";

    private static final String LATEST_ENDPOINT =
            "https://api.github.com/repos/" + REPO_OWNER + "/" + REPO_NAME + "/releases/latest";

    private static final String PREFS_NAME = "openparsec_update";
    private static final String KEY_LAST_CHECK = "lastCheckMs";
    private static final String KEY_DISMISSED_TAG = "dismissedTag";

    private static final long MIN_INTERVAL_MS = TimeUnit.HOURS.toMillis(24);

    private UpdateChecker() {}

    /** Kick off a check from a foreground activity. Returns immediately. */
    public static void checkInBackground(final Activity activity) {
        if (activity == null) return;
        final Context appCtx = activity.getApplicationContext();
        SharedPreferences sp = appCtx.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE);
        long last = sp.getLong(KEY_LAST_CHECK, 0L);
        if (System.currentTimeMillis() - last < MIN_INTERVAL_MS) return;

        new Thread(() -> {
            try {
                Result r = fetchLatest();
                if (r == null) return;
                sp.edit().putLong(KEY_LAST_CHECK, System.currentTimeMillis()).apply();

                String current = BuildConfig.VERSION_NAME;
                if (!isNewer(r.tagName, current)) return;

                String dismissed = sp.getString(KEY_DISMISSED_TAG, "");
                if (r.tagName.equals(dismissed)) return;

                new Handler(Looper.getMainLooper()).post(() ->
                        showUpdateDialog(activity, r, current));
            } catch (Exception e) {
                Log.d(TAG, "update check failed: " + e.getMessage());
            }
        }, "OpenParsec-UpdateCheck").start();
    }

    private static void showUpdateDialog(final Activity activity, final Result r, String current) {
        if (activity.isFinishing() || activity.isDestroyed()) return;
        String body = "A newer version (" + r.tagName + ") is available.\n"
                + "You're on " + current + ".\n\n"
                + (r.body == null ? "" : (r.body.length() > 400
                        ? r.body.substring(0, 400) + "…" : r.body));
        new MaterialAlertDialogBuilder(activity)
                .setTitle("Update available")
                .setMessage(body)
                .setPositiveButton("Download", (d, w) -> {
                    String url = r.apkUrl != null ? r.apkUrl : r.htmlUrl;
                    if (url == null) return;
                    try {
                        activity.startActivity(
                                new Intent(Intent.ACTION_VIEW, Uri.parse(url)));
                    } catch (Exception ignored) {}
                })
                .setNegativeButton("Later", null)
                .setNeutralButton("Skip this version", (d, w) -> {
                    activity.getApplicationContext()
                            .getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
                            .edit().putString(KEY_DISMISSED_TAG, r.tagName).apply();
                })
                .show();
    }

    /** Compare two version strings. Tolerates a leading "v". Returns true iff
     *  {@code candidate} > {@code current} under semantic-ish ordering. */
    static boolean isNewer(String candidate, String current) {
        if (candidate == null) return false;
        if (current == null) return true;
        int[] a = parseVersion(candidate);
        int[] b = parseVersion(current);
        for (int i = 0; i < Math.max(a.length, b.length); i++) {
            int av = i < a.length ? a[i] : 0;
            int bv = i < b.length ? b[i] : 0;
            if (av > bv) return true;
            if (av < bv) return false;
        }
        return false;
    }

    private static int[] parseVersion(String s) {
        if (s == null) return new int[0];
        String t = s.trim();
        if (t.startsWith("v") || t.startsWith("V")) t = t.substring(1);
        // Strip any pre-release suffix like "-rc1"
        int dash = t.indexOf('-');
        if (dash >= 0) t = t.substring(0, dash);
        String[] parts = t.split("\\.");
        int[] out = new int[parts.length];
        for (int i = 0; i < parts.length; i++) {
            try { out[i] = Integer.parseInt(parts[i]); }
            catch (NumberFormatException e) { out[i] = 0; }
        }
        return out;
    }

    private static Result fetchLatest() throws Exception {
        HttpURLConnection conn = null;
        try {
            URL u = new URL(LATEST_ENDPOINT);
            conn = (HttpURLConnection) u.openConnection();
            conn.setConnectTimeout(5000);
            conn.setReadTimeout(5000);
            conn.setRequestProperty("Accept", "application/vnd.github+json");
            conn.setRequestProperty("User-Agent", "OpenParsec-Android");
            int code = conn.getResponseCode();
            if (code != 200) return null;
            try (InputStream is = conn.getInputStream();
                 BufferedReader br = new BufferedReader(new InputStreamReader(is, "UTF-8"))) {
                StringBuilder sb = new StringBuilder();
                String line;
                while ((line = br.readLine()) != null) sb.append(line);
                JSONObject json = new JSONObject(sb.toString());
                Result r = new Result();
                r.tagName = json.optString("tag_name", null);
                r.htmlUrl = json.optString("html_url", null);
                r.body = json.optString("body", null);
                // Find an APK asset
                JSONArray assets = json.optJSONArray("assets");
                if (assets != null) {
                    for (int i = 0; i < assets.length(); i++) {
                        JSONObject a = assets.getJSONObject(i);
                        String name = a.optString("name", "");
                        if (name.toLowerCase().endsWith(".apk")) {
                            r.apkUrl = a.optString("browser_download_url", null);
                            break;
                        }
                    }
                }
                return r;
            }
        } finally {
            if (conn != null) conn.disconnect();
        }
    }

    private static final class Result {
        String tagName;
        String htmlUrl;
        String apkUrl;
        String body;
    }
}
