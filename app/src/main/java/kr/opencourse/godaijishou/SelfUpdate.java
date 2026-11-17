package kr.opencourse.godaijishou;

import android.app.PendingIntent;
import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageInfo;
import android.content.pm.PackageInstaller;
import android.content.pm.PackageManager;
import android.content.pm.Signature;
import android.os.Build;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.security.MessageDigest;
import java.util.Arrays;

/**
 * The app updates itself from its GitHub release (no hook, no root): Android's own installer.
 *
 * The release's APK is downloaded into the app's cache, checked (the release's SHA-256 when
 * GitHub gives one; always: this package, a newer version, the same signing key as the app
 * installed now), and handed to PackageInstaller asking for no user action. Android 12+ lets
 * an app update itself without a prompt once it installed the version on the phone: the first
 * update from here (the phone's copy came from adb or a browser) shows the system's one-tap
 * prompt, the next ones install by themselves. The phone is never rebooted from here: the
 * hooks take the new code at the next reboot, which the person starts (a bad release must
 * never reboot the phone by itself).
 */
final class SelfUpdate {

    static final String ACTION_STATUS = BuildConfig.APPLICATION_ID + ".SELF_UPDATE_STATUS";

    interface Listener {
        void onProgress(int percent);

        void onInstalling();

        /** A step failed: R.string message id. */
        void onFailed(int message);
    }

    private static volatile boolean running;

    private SelfUpdate() {
    }

    /** Whether the person allowed this app to install apps ("unknown apps" setting). */
    static boolean canInstall(Context c) {
        return c.getPackageManager().canRequestPackageInstalls();
    }

    static Intent allowInstallsIntent(Context c) {
        return new Intent(android.provider.Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES,
                android.net.Uri.parse("package:" + c.getPackageName()));
    }

    /** Downloads, checks and installs. Listener calls come on a background thread. */
    static void start(Context context, String url, String sha256, Listener listener) {
        if (running) return;
        running = true;
        Context app = context.getApplicationContext();
        new Thread(() -> {
            try {
                File apk = new File(app.getCacheDir(), "update.apk");
                if (!download(url, apk, listener)) {
                    listener.onFailed(R.string.update_err_download);
                    return;
                }
                if (!verified(app, apk, sha256)) {
                    apk.delete();
                    listener.onFailed(R.string.update_err_verify);
                    return;
                }
                listener.onInstalling();
                if (!install(app, apk)) listener.onFailed(R.string.update_err_install);
            } catch (Throwable t) {
                listener.onFailed(R.string.update_err_install);
            } finally {
                running = false;
            }
        }, "GoDaijishou-self-update").start();
    }

    private static boolean download(String url, File to, Listener listener) {
        HttpURLConnection c = null;
        try {
            c = (HttpURLConnection) new URL(url).openConnection(); // GitHub redirects to its CDN (https)
            c.setConnectTimeout(10000);
            c.setReadTimeout(20000);
            c.setRequestProperty("User-Agent", "GoDaijishou");
            if (c.getResponseCode() != 200) return false;
            long total = c.getContentLengthLong();
            try (InputStream in = c.getInputStream(); OutputStream out = new FileOutputStream(to)) {
                byte[] buf = new byte[64 * 1024];
                long done = 0;
                int last = -1, n;
                while ((n = in.read(buf)) > 0) {
                    out.write(buf, 0, n);
                    done += n;
                    if (done > 64L * 1024 * 1024) return false; // not an APK of ours
                    int percent = total > 0 ? (int) (done * 100 / total) : 0;
                    if (percent != last) {
                        last = percent;
                        listener.onProgress(percent);
                    }
                }
            }
            return true;
        } catch (Throwable t) {
            return false;
        } finally {
            if (c != null) c.disconnect();
        }
    }

    // The release's checksum (when given), then: this app, newer, signed with the same key.
    private static boolean verified(Context c, File apk, String sha256) throws Exception {
        if (sha256 != null && !sha256.equalsIgnoreCase(sha256Of(apk))) return false;
        PackageManager pm = c.getPackageManager();
        PackageInfo next = pm.getPackageArchiveInfo(apk.getPath(), PackageManager.GET_SIGNING_CERTIFICATES);
        if (next == null || next.signingInfo == null || !c.getPackageName().equals(next.packageName)) return false;
        PackageInfo now = pm.getPackageInfo(c.getPackageName(), PackageManager.GET_SIGNING_CERTIFICATES);
        if (next.getLongVersionCode() <= now.getLongVersionCode()) return false;
        Signature[] a = next.signingInfo.getApkContentsSigners();
        Signature[] b = now.signingInfo.getApkContentsSigners();
        return a != null && b != null && a.length > 0 && Arrays.equals(a, b);
    }

    static String sha256Of(File f) throws Exception {
        MessageDigest md = MessageDigest.getInstance("SHA-256");
        try (InputStream in = new FileInputStream(f)) {
            byte[] buf = new byte[64 * 1024];
            int n;
            while ((n = in.read(buf)) > 0) md.update(buf, 0, n);
        }
        return hex(md.digest());
    }

    static String hex(byte[] bytes) {
        StringBuilder s = new StringBuilder(bytes.length * 2);
        for (byte b : bytes) s.append(String.format("%02x", b & 0xff));
        return s.toString();
    }

    private static boolean install(Context c, File apk) throws Exception {
        PackageInstaller installer = c.getPackageManager().getPackageInstaller();
        PackageInstaller.SessionParams params = new PackageInstaller.SessionParams(
                PackageInstaller.SessionParams.MODE_FULL_INSTALL);
        params.setAppPackageName(c.getPackageName());
        params.setSize(apk.length());
        if (Build.VERSION.SDK_INT >= 31) {
            params.setRequireUserAction(PackageInstaller.SessionParams.USER_ACTION_NOT_REQUIRED);
        }
        int id = installer.createSession(params);
        try (PackageInstaller.Session session = installer.openSession(id)) {
            try (OutputStream out = session.openWrite("base.apk", 0, apk.length());
                 InputStream in = new FileInputStream(apk)) {
                byte[] buf = new byte[64 * 1024];
                int n;
                while ((n = in.read(buf)) > 0) out.write(buf, 0, n);
                session.fsync(out);
            }
            Intent status = new Intent(c, SelfUpdateReceiver.class).setAction(ACTION_STATUS);
            int flags = PendingIntent.FLAG_UPDATE_CURRENT
                    | (Build.VERSION.SDK_INT >= 31 ? PendingIntent.FLAG_MUTABLE : 0);
            session.commit(PendingIntent.getBroadcast(c, id, status, flags).getIntentSender());
            return true;
        } catch (Throwable t) {
            installer.abandonSession(id);
            return false;
        }
    }
}
