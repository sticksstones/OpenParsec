package com.example.parsecdemo;

import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.os.Handler;
import android.os.Looper;
import android.widget.ImageView;

import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * Tiny async image loader for Parsec user avatars. In-memory LRU-ish cache
 * keyed on URL, single-threaded worker so we don't open a flood of sockets
 * when the friends list renders. Drop-in for ImageView via
 * {@link #into(ImageView, String, int)}.
 *
 * Not a general-purpose image library — fine for the small set of 96px
 * avatars we render in the Friends tab.
 */
public final class AvatarLoader {

    private static final int MAX_CACHE = 64;
    private static final Map<String, Bitmap> CACHE =
            new java.util.LinkedHashMap<String, Bitmap>(16, 0.75f, true) {
                @Override protected boolean removeEldestEntry(
                        Map.Entry<String, Bitmap> e) {
                    return size() > MAX_CACHE;
                }
            };

    private static final ExecutorService POOL = Executors.newFixedThreadPool(2);
    private static final Handler MAIN = new Handler(Looper.getMainLooper());

    private AvatarLoader() {}

    /** Load {@code url} into {@code iv} on a worker thread. If the bitmap is
     *  cached it's applied synchronously. While loading the {@code placeholder}
     *  resource is shown. View-recycling-safe via setTag. */
    public static void into(final ImageView iv, final String url, final int placeholder) {
        iv.setTag(url);
        Bitmap cached;
        synchronized (CACHE) { cached = CACHE.get(url); }
        if (cached != null) {
            iv.setImageBitmap(cached);
            return;
        }
        iv.setImageResource(placeholder);
        POOL.execute(() -> {
            Bitmap bmp = fetch(url);
            if (bmp == null) return;
            synchronized (CACHE) { CACHE.put(url, bmp); }
            MAIN.post(() -> {
                // Only apply if the ImageView wasn't reassigned to a different
                // URL while we were fetching.
                if (url.equals(iv.getTag())) iv.setImageBitmap(bmp);
            });
        });
    }

    private static Bitmap fetch(String url) {
        HttpURLConnection c = null;
        try {
            c = (HttpURLConnection) new URL(url).openConnection();
            c.setConnectTimeout(5000);
            c.setReadTimeout(5000);
            c.setRequestProperty("User-Agent", "OpenParsec-Android");
            int code = c.getResponseCode();
            if (code != 200) return null;
            try (InputStream is = c.getInputStream()) {
                return BitmapFactory.decodeStream(is);
            }
        } catch (Exception e) {
            return null;
        } finally {
            if (c != null) c.disconnect();
        }
    }
}
