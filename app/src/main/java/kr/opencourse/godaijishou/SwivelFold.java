package kr.opencourse.godaijishou;

import android.app.AndroidAppHelper;
import android.app.Application;
import android.database.ContentObserver;
import android.os.Handler;
import android.os.Looper;
import android.provider.Settings;

import java.util.Collections;
import java.util.Set;

import de.robv.android.xposed.XC_MethodHook;
import de.robv.android.xposed.XposedBridge;
import de.robv.android.xposed.XposedHelpers;

/**
 * An LG app as the folded phone's while the second screen's panel is the main display (main
 * screen mode, DisplaySwap). Installed in LG's camera (scope: com.lge.camera): with the
 * swivel open it lays itself out for the swivel (RatioCalcFullVisionSwivel) and crashed on
 * the swapped 1080x1240 main display. Not in SystemUI: a folded SystemUI over LG's swivel lock
 * left the phone locked.
 *
 * LG's apps learn the swivel state from PostureManager and DisplayManagerHelper only:
 * getSwivelState() (2 = open) and state callbacks (100/101 opening/open, 200/201
 * closing/closed). A camera process started while swapped is told "closed" for its whole
 * life; one started unswapped (second screen mode) sees LG's answers unchanged. When the
 * mode changes under a running camera, its process ends and the next start follows the mode.
 */
final class SwivelFold {

    static final String CAMERA = "com.lge.camera";

    /** Settings.Global, 1 while swapped (written by DisplaySwap in system_server). */
    static final String KEY_SWAPPED = "godaijishou_swapped";

    private static final String POSTURE = "com.lge.systemservice.core.PostureManager";
    private static final String DISPLAY_HELPER = "com.lge.display.DisplayManagerHelper";
    private static final int STATE_OPEN = 2, STATE_CLOSED = 0;
    private static final int OPENING = 100, OPENED = 101, CLOSING = 200, CLOSED = 201;

    private static final Set<Class<?>> hookedCallbacks = Collections.synchronizedSet(new java.util.HashSet<>());
    private static volatile boolean folded;
    private static boolean observing;

    private SwivelFold() {
    }

    static void install(ClassLoader cl) {
        // Latch at process start, not at the first swivel question: LG restarts the camera in
        // the background (BoostService) on the lock screen, unswapped, and builds parts from
        // that state before asking. Unlocking swapped again; that process must end too.
        XposedBridge.hookAllMethods(Application.class, "onCreate", new XC_MethodHook() {
            @Override
            protected void afterHookedMethod(MethodHookParam param) {
                observe();
            }
        });
        // The camera also builds swivel-only parts (its drum UI, sized from the second screen)
        // just for being a swivel model: while folded it is not one, so none are built.
        Class<?> model = XposedHelpers.findClassIfExists("com.lge.camera.constants.ModelProperties", cl);
        if (model != null) {
            XposedBridge.hookAllMethods(model, "isSwivelModel", new XC_MethodHook() {
                @Override
                protected void afterHookedMethod(MethodHookParam param) {
                    observe();
                    if (folded) param.setResult(false);
                }
            });
        }
        for (String name : new String[] {POSTURE, DISPLAY_HELPER}) {
            Class<?> c = XposedHelpers.findClassIfExists(name, cl);
            if (c == null) continue;
            try {
                hookSource(c);
            } catch (Throwable t) {
                ModuleLog.w(t);
            }
        }
    }

    private static void hookSource(Class<?> source) {
        XposedBridge.hookAllMethods(source, "getSwivelState", new XC_MethodHook() {
            @Override
            protected void afterHookedMethod(MethodHookParam param) {
                observe();
                if (folded && Integer.valueOf(STATE_OPEN).equals(param.getResult())) param.setResult(STATE_CLOSED);
            }
        });
        XC_MethodHook register = new XC_MethodHook() {
            @Override
            protected void beforeHookedMethod(MethodHookParam param) {
                if (param.args.length > 0 && param.args[0] != null) rewrite(param.args[0].getClass());
                observe();
            }
        };
        XposedBridge.hookAllMethods(source, "registerPostureStateCallback", register);
        XposedBridge.hookAllMethods(source, "registerSwivelStateCallback", register);
        XposedBridge.hookAllConstructors(source, new XC_MethodHook() {
            @Override
            protected void afterHookedMethod(MethodHookParam param) {
                observe();
            }
        });
    }

    // While folded, LG's own "opening/open" (sent at registration too) arrives as "closing/closed".
    private static void rewrite(Class<?> callback) {
        for (Class<?> c = callback; c != null && c != Object.class; c = c.getSuperclass()) {
            if (!hookedCallbacks.add(c)) return;
            try {
                XposedBridge.hookAllMethods(c, "onSwivelStateChanged", new XC_MethodHook() {
                    @Override
                    protected void beforeHookedMethod(MethodHookParam param) {
                        if (!folded) return;
                        if (param.args.length == 0 || !(param.args[0] instanceof Integer)) return;
                        int s = (Integer) param.args[0];
                        if (s == OPENING) param.args[0] = CLOSING;
                        else if (s == OPENED) param.args[0] = CLOSED;
                    }
                });
            } catch (Throwable t) {
                ModuleLog.w(t);
            }
        }
    }

    private static synchronized void observe() {
        if (observing) return;
        Application app = AndroidAppHelper.currentApplication();
        if (app == null) return;
        observing = true;
        // Latched for the life of the process: the camera builds its swivel or plain parts
        // once, and flipping the answer under a running camera mixed the two (a plain ShotCtx
        // cast to SwivelShotCtx, swivel sizes read from arrays never built) and crashed it.
        folded = Settings.Global.getInt(app.getContentResolver(), KEY_SWAPPED, 0) == 1;
        try {
            app.getContentResolver().registerContentObserver(Settings.Global.getUriFor(KEY_SWAPPED), false,
                    new ContentObserver(new Handler(Looper.getMainLooper())) {
                        @Override
                        public void onChange(boolean selfChange) {
                            boolean want = Settings.Global.getInt(app.getContentResolver(), KEY_SWAPPED, 0) == 1;
                            // The mode changed under this process: end it quietly instead of
                            // letting it crash. The next start follows the new mode.
                            if (want != folded) android.os.Process.killProcess(android.os.Process.myPid());
                        }
                    });
        } catch (Throwable t) {
            ModuleLog.w(t);
        }
    }
}
