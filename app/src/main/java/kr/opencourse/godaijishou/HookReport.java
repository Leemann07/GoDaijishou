package kr.opencourse.godaijishou;

import android.app.Application;
import android.app.Instrumentation;
import android.content.Context;
import android.content.Intent;

import java.util.Collections;
import java.util.HashSet;
import java.util.Set;

import de.robv.android.xposed.XC_MethodHook;
import de.robv.android.xposed.XposedHelpers;

/**
 * Tells the app "hook &lt;name&gt; is loaded in this boot" (SettingsProvider, selection
 * "hook:&lt;name&gt;"), once per process and name. The app uses it to skip the LSPosed scope notice
 * and to unlock features that need the hook.
 */
final class HookReport {

    /** Settings provider name for the keyboard hook (NavBarHook.installIme). */
    static final String IME = "ime";
    /** Settings provider name for the swivel warning hook (SwivelWarningHook). */
    static final String POPUP_CAMERA = "popupcamera";

    private static final int RETRIES = 60;
    private static final long RETRY_MS = 2_000;

    private static final Set<String> sent = Collections.synchronizedSet(new HashSet<>());

    private HookReport() {
    }

    /**
     * Reports once the hooked app has started. Instrumentation.callApplicationOnCreate runs for
     * every app start, whether or not the app's Application.onCreate calls super (LG home
     * 9.70.22 never reached an Application.onCreate hook).
     */
    static void onAppStart(String name) {
        onAppStart(name, false);
    }

    /** Same, but only from a process whose package is an input method (a keyboard). */
    static void onImeStart(String name) {
        onAppStart(name, true);
    }

    private static void onAppStart(String name, boolean imeOnly) {
        XposedHelpers.findAndHookMethod(Instrumentation.class, "callApplicationOnCreate",
                Application.class, new XC_MethodHook() {
                    @Override
                    protected void afterHookedMethod(MethodHookParam param) {
                        Context app = (Context) param.args[0];
                        if (imeOnly && !isInputMethod(app)) return;
                        report(app, name);
                    }
                });
    }

    static void report(Context context, String name) {
        if (context == null || !sent.add(name)) return;
        new Thread(() -> {
            // Apps started before the system is ready (persistent ones such as LG's popup
            // camera) cannot reach the provider yet: the query fails or answers null. Retry.
            Throwable last = null;
            for (int i = 0; i < RETRIES; i++) {
                try (android.database.Cursor c = context.getContentResolver().query(
                        SettingsProvider.CONTENT_URI, null, "hook:" + name, null, null)) {
                    if (c != null) return;
                } catch (Throwable t) {
                    last = t;
                }
                try {
                    Thread.sleep(RETRY_MS);
                } catch (InterruptedException e) {
                    return;
                }
            }
            if (last != null) ModuleLog.w("report " + name, last);
        }, "GoDaijishou-report").start();
    }

    private static boolean isInputMethod(Context app) {
        try {
            Intent ime = new Intent("android.view.InputMethod").setPackage(app.getPackageName());
            return !app.getPackageManager().queryIntentServices(ime, 0).isEmpty();
        } catch (Throwable t) {
            ModuleLog.w(t);
            return false;
        }
    }
}
