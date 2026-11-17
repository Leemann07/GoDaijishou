package kr.opencourse.godaijishou;

import android.content.Context;
import android.os.SystemClock;
import android.util.Log;

import java.util.Collections;
import java.util.HashSet;
import java.util.Set;

import de.robv.android.xposed.XposedBridge;
import de.robv.android.xposed.XposedHelpers;

/**
 * Diagnostic log to the LSPosed log, switched in the app ("Diagnostic log").
 *
 * Every hook swallows its failures so a broken firmware detail never takes an app down.
 * With the switch on, those failures are written out: each distinct place and exception
 * once per process, so a failure in a per-frame path doesn't flood the log.
 * With the switch off nothing at all is written, not even a hook that failed to install.
 *
 * The switch reaches every process through Settings.Global (written by system_server's
 * SystemSettings), the same way the bottom-bar options do.
 */
final class ModuleLog {

    static final String KEY = "godaijishou_log";
    private static final String TAG = "GoDaijishou";
    private static final long REREAD_MS = 5000;

    private static final Set<String> seen = Collections.synchronizedSet(new HashSet<>());
    private static volatile boolean enabled = false;
    private static volatile long readAt = -REREAD_MS;

    private ModuleLog() {
    }

    /** A swallowed failure: written only while the switch is on. */
    static void w(Throwable t) {
        if (!enabled()) return;
        write(null, t);
    }

    /** A swallowed failure with a note: written only while the switch is on. */
    static void w(String note, Throwable t) {
        if (!enabled()) return;
        write(note, t);
    }

    /** A hook that could not be installed: written only while the switch is on. */
    static void install(Throwable t) {
        if (!enabled()) return;
        write(null, t);
    }

    /** A state note: written only while the switch is on, every time. */
    static void i(String note) {
        if (!enabled()) return;
        try {
            XposedBridge.log(TAG + " " + note);
        } catch (Throwable ignored) {
            // Logging must never fail the caller (and outside a hooked process there is no log).
        }
    }

    private static void write(String note, Throwable t) {
        try {
            StackTraceElement[] stack = new Throwable().getStackTrace();
            // [0] write, [1] w/install, [2] the catch site.
            String site = stack.length > 2
                    ? stack[2].getClassName().replace("kr.opencourse.godaijishou.", "")
                    + "." + stack[2].getMethodName() + ":" + stack[2].getLineNumber()
                    : "?";
            if (!seen.add(site + " " + t.getClass().getName())) return;
            XposedBridge.log(TAG + " " + site + " in " + processName() + (note == null ? "" : " (" + note + ")")
                    + "\n" + Log.getStackTraceString(t));
        } catch (Throwable ignored) {
            // Logging must never fail the caller.
        }
    }

    private static boolean enabled() {
        long now = SystemClock.uptimeMillis();
        if (now - readAt < REREAD_MS) return enabled;
        readAt = now;
        try {
            Context context = (Context) XposedHelpers.callStaticMethod(
                    Class.forName("android.app.ActivityThread"), "currentApplication");
            if (context == null) context = SystemSettings.context();
            if (context != null) {
                enabled = "1".equals(android.provider.Settings.Global.getString(
                        context.getContentResolver(), KEY));
            }
        } catch (Throwable ignored) {
            // Settings not reachable yet: keep the last value.
        }
        return enabled;
    }

    private static String processName() {
        try {
            return (String) XposedHelpers.callStaticMethod(
                    Class.forName("android.app.ActivityThread"), "currentProcessName");
        } catch (Throwable t) {
            return "?";
        }
    }
}
