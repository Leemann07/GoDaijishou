package kr.opencourse.godaijishou;

import android.content.Context;
import android.database.Cursor;

import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

/**
 * A hook in another process tells the app "loaded this boot" (SettingsProvider, "hook:<name>").
 *
 * Done once per process, but only once it got through: a refused query (the app being
 * replaced, its provider not up yet early in the boot, a null cursor when the provider can't
 * be seen) leaves it open, and the caller's next event tries again. No timer, no polling:
 * the callers are events the hooked app goes through anyway.
 */
final class HookReport {

    private static final Set<String> done = ConcurrentHashMap.newKeySet();
    private static final Set<String> sending = ConcurrentHashMap.newKeySet();

    private HookReport() {
    }

    static void report(Context context, String name) {
        if (context == null || done.contains(name) || !sending.add(name)) return;
        Context app = context.getApplicationContext() != null ? context.getApplicationContext() : context;
        try {
            new Thread(() -> {
                try (Cursor c = app.getContentResolver().query(
                        SettingsProvider.CONTENT_URI, null, "hook:" + name, null, null)) {
                    if (c != null) {
                        done.add(name);
                    } else {
                        ModuleLog.i("hook:" + name + " not reported: provider not reachable");
                    }
                } catch (Throwable t) {
                    ModuleLog.w("hook:" + name + " not reported", t);
                } finally {
                    sending.remove(name);
                }
            }, "GoDaijishou-report").start();
        } catch (Throwable t) {
            sending.remove(name);
            ModuleLog.w(t);
        }
    }
}
