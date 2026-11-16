package kr.opencourse.godaijishou;

import android.content.Context;
import android.content.SharedPreferences;
import android.os.Handler;
import android.os.Looper;

import org.json.JSONObject;

import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URL;

/**
 * Asks GitHub for the latest release and reports it when it is newer than this build. One
 * anonymous GET to the public releases API; nothing about the device is sent.
 *
 * The request carries the last ETag, so an unchanged release costs a 304, which GitHub does not
 * count against the 60 an hour an address gets. That lets the screen ask each time it opens
 * (at most every few minutes) and on a pull down, instead of trusting a result hours old.
 */
final class UpdateCheck {

    static final String RELEASES_PAGE = "https://github.com/jshsakura/GoDaijishou/releases/latest";
    private static final String API = "https://api.github.com/repos/jshsakura/GoDaijishou/releases/latest";
    private static final long MIN_INTERVAL_MS = 5L * 60 * 1000;
    private static final String PREFS = "update_check";

    interface Listener {
        void onNewer(String version);
    }

    private UpdateCheck() {
    }

    /**
     * Shows the stored result at once, then asks GitHub unless it was asked a moment ago.
     * {@code force} (pull down) always asks. {@code done} runs on the main thread when finished.
     */
    static void run(Context context, boolean force, Listener listener, Runnable done) {
        SharedPreferences prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE);
        String known = prefs.getString("latest", null);
        if (known != null && isNewer(known, BuildConfig.VERSION_NAME)) listener.onNewer(known);
        if (!force && System.currentTimeMillis() - prefs.getLong("checked_at", 0) < MIN_INTERVAL_MS) {
            if (done != null) done.run();
            return;
        }
        Handler main = new Handler(Looper.getMainLooper());
        String etag = known != null ? prefs.getString("etag", null) : null;
        new Thread(() -> {
            String[] r = fetch(etag); // {tag, etag}; tag null when unchanged (304)
            String latest = null;
            if (r != null) {
                latest = r[0] != null ? r[0] : known;
                SharedPreferences.Editor e = prefs.edit().putLong("checked_at", System.currentTimeMillis());
                if (r[0] != null) e.putString("latest", r[0]).putString("etag", r[1]);
                e.apply();
            }
            final String found = latest;
            main.post(() -> {
                if (found != null && isNewer(found, BuildConfig.VERSION_NAME)) listener.onNewer(found);
                if (done != null) done.run();
            });
        }, "GoDaijishou-update").start();
    }

    /** {tag, etag} on 200, {null, null} on 304, null when offline or refused. */
    private static String[] fetch(String etag) {
        HttpURLConnection c = null;
        try {
            c = (HttpURLConnection) new URL(API).openConnection();
            c.setConnectTimeout(8000);
            c.setReadTimeout(8000);
            c.setUseCaches(false);
            c.setRequestProperty("Accept", "application/vnd.github+json");
            c.setRequestProperty("User-Agent", "GoDaijishou");
            if (etag != null) c.setRequestProperty("If-None-Match", etag);
            int code = c.getResponseCode();
            if (code == HttpURLConnection.HTTP_NOT_MODIFIED) return new String[]{null, null};
            if (code != 200) return null;
            try (InputStream in = c.getInputStream()) {
                ByteArrayOutputStream out = new ByteArrayOutputStream();
                byte[] buf = new byte[8192];
                int n;
                while ((n = in.read(buf)) > 0 && out.size() < 512 * 1024) out.write(buf, 0, n);
                String tag = new JSONObject(out.toString("UTF-8")).optString("tag_name", "");
                tag = tag.startsWith("v") || tag.startsWith("V") ? tag.substring(1) : tag;
                return tag.isEmpty() ? null : new String[]{tag, c.getHeaderField("ETag")};
            }
        } catch (Throwable t) {
            return null; // offline or rate-limited: try again next time
        } finally {
            if (c != null) c.disconnect();
        }
    }

    // "1.10" > "1.9"; missing parts count as 0; anything non-numeric ends the comparison.
    static boolean isNewer(String a, String b) {
        String[] x = a.split("[.\\-]"), y = b.split("[.\\-]");
        for (int i = 0; i < Math.max(x.length, y.length); i++) {
            int p = part(x, i), q = part(y, i);
            if (p < 0 || q < 0) return false;
            if (p != q) return p > q;
        }
        return false;
    }

    private static int part(String[] s, int i) {
        if (i >= s.length) return 0;
        try {
            return Integer.parseInt(s[i].trim());
        } catch (NumberFormatException e) {
            return -1;
        }
    }
}
