package kr.opencourse.godaijishou;

import android.content.Context;
import android.database.ContentObserver;
import android.graphics.Canvas;
import android.os.Handler;
import android.os.Looper;
import android.view.View;

import java.util.Collections;
import java.util.Set;
import java.util.WeakHashMap;

import de.robv.android.xposed.XC_MethodHook;
import de.robv.android.xposed.XposedBridge;
import de.robv.android.xposed.XposedHelpers;

/**
 * Bottom of the screen, two independent options.
 *
 * Slim gesture pill (scope: com.android.systemui)
 *   The home pill is drawn thinner (2/3) and narrower (2/3), in the stock color and spot
 *   (only the drawing canvas is scaled; LG's size and motion fields stay untouched).
 *   Only the drawing changes; the gesture area and the gestures themselves are the system's.
 *
 * Keyboard bottom strip off (scope: com.android.systemui + the keyboard app)
 *   With gesture navigation the keyboard sits on a strip as tall as the old 3-button bar,
 *   holding the "hide keyboard" and switcher buttons. The strip's height is the framework
 *   keyboard's own NavigationBarController: it is told there is no bottom inset and its frame
 *   is hidden. SystemUI's navigation frame and input area keep their stock heights.
 *   Takes effect when the keyboard restarts (or after a reboot).
 */
final class NavBarHook {

    static final String SYSTEMUI = "com.android.systemui";
    private static final String HANDLE = "com.android.systemui.navigationbar.gestural.NavigationHandle";

    private static final float THICKNESS = 2f / 3f;
    private static final float WIDTH = 2f / 3f;

    private static final Set<View> handles = Collections.newSetFromMap(new WeakHashMap<>());
    private static final Handler main = new Handler(Looper.getMainLooper());

    // Settings, read from our provider (null = not read yet).
    private static volatile Boolean slimPill;
    private static volatile Boolean imeStripOff;
    private static boolean observing = false;

    private NavBarHook() {
    }

    // --- SystemUI ---

    static void installSystemUi(ClassLoader cl) {
        Class<?> handle = XposedHelpers.findClass(HANDLE, cl);
        XposedBridge.hookAllConstructors(handle, new XC_MethodHook() {
            @Override
            protected void afterHookedMethod(MethodHookParam param) {
                View v = (View) param.thisObject;
                handles.add(v);
                observe(v.getContext());
                reportLoaded(v.getContext());
            }
        });
        // LG also uses this view from input-monitor threads. Never put scaled sizes or a
        // clamped mDy into the view, even temporarily: drawing must not change gesture state.
        XposedHelpers.findAndHookMethod(handle, "onDraw", Canvas.class, new XC_MethodHook() {
            @Override
            protected void beforeHookedMethod(MethodHookParam param) {
                View v = (View) param.thisObject;
                boolean slim = slimPill(v.getContext());
                int h = v.getHeight();
                if (h <= 0) return;
                try {
                    int full = XposedHelpers.getIntField(v, "mFullgestureNavHeight");
                    boolean shortBar = imeStripOff() && h < full;
                    if (!slim && !shortBar) return;
                    float dy = XposedHelpers.getFloatField(v, "mDy");
                    float pillHeight = XposedHelpers.getFloatField(v, "mHandleHeight");
                    // LG's resting center is full/2 above the bottom, then rises by mDy.
                    // Keep the visible pill in a shortened keyboard bar, using a canvas
                    // translation instead of changing either of LG's motion/height fields.
                    float originalCenter = h - full / 2f - dy;
                    float half = Math.min(h / 2f, pillHeight * (slim ? THICKNESS : 1f) / 2f);
                    float center = h - (shortBar ? h : full) / 2f - dy;
                    center = Math.max(half, Math.min(h - half, center));
                    Canvas canvas = (Canvas) param.args[0];
                    int saved = canvas.save();
                    param.setObjectExtra("gdj_canvas_save", saved);
                    canvas.translate(0f, center - originalCenter);
                    if (slim) canvas.scale(WIDTH, THICKNESS, v.getWidth() / 2f, originalCenter);
                } catch (Throwable t) {
                    ModuleLog.w(t);
                }
            }

            @Override
            protected void afterHookedMethod(MethodHookParam param) {
                Object saved = param.getObjectExtra("gdj_canvas_save");
                if (saved instanceof Integer) {
                    ((Canvas) param.args[0]).restoreToCount((Integer) saved);
                }
            }
        });
    }

    // Tells the app "the SystemUI hook is loaded this boot" (the app warns when it isn't).
    private static boolean reported = false;

    private static void reportLoaded(Context context) {
        if (reported) return;
        reported = true;
        new Thread(() -> {
            try (android.database.Cursor c = context.getContentResolver().query(
                    SettingsProvider.CONTENT_URI, null, "hook:systemui", null, null)) {
                // nothing to read
            } catch (Throwable ignored) {
                ModuleLog.w(ignored);
            }
        }, "GoDaijishou-report").start();
    }

    // --- Keyboard strip (SystemUI and keyboard processes) ---

    // Keyboard process: the keyboard draws that strip itself (NavigationBarController$Impl,
    // a NavigationBarFrame sized to the system's bottom inset). Answer "no bottom inset" and
    // hide the frame: no strip, no buttons, keys down to the gesture area. Back still closes
    // the keyboard.
    static void installIme() {
        if (android.os.Build.VERSION.SDK_INT < 29) return;
        Class<?> impl;
        try {
            impl = Class.forName("android.inputmethodservice.NavigationBarController$Impl");
        } catch (Throwable t) {
            ModuleLog.w(t);
            return;
        }
        XposedBridge.hookAllMethods(impl, "getSystemInsets", new XC_MethodHook() {
            @Override
            protected void beforeHookedMethod(MethodHookParam param) {
                if (android.os.Build.VERSION.SDK_INT >= 29 && imeStripOff()) {
                    param.setResult(android.graphics.Insets.NONE);
                }
            }
        });
        XC_MethodHook hideFrame = new XC_MethodHook() {
            @Override
            protected void afterHookedMethod(MethodHookParam param) {
                if (!imeStripOff()) return;
                try {
                    Object frame = XposedHelpers.getObjectField(param.thisObject, "mNavigationBarFrame");
                    if (frame instanceof View) ((View) frame).setVisibility(View.GONE);
                } catch (Throwable ignored) {
                    ModuleLog.w(ignored);
                }
            }
        };
        XposedBridge.hookAllMethods(impl, "installNavigationBarFrameIfNecessary", hideFrame);
        XposedBridge.hookAllMethods(impl, "onViewInitialized", hideFrame);
        XposedBridge.hookAllMethods(impl, "onWindowShown", hideFrame);
    }

    // --- Settings ---

    private static boolean slimPill(Context context) {
        Boolean v = slimPill;
        if (v == null) {
            read(context);
            v = slimPill;
        }
        return v != null && v;
    }

    private static boolean imeStripOff() {
        Boolean v = imeStripOff;
        if (v == null) {
            Context c = currentContext();
            if (c != null) read(c);
            v = imeStripOff;
        }
        return v != null && v;
    }

    private static Context currentContext() {
        try {
            Object thread = XposedHelpers.callStaticMethod(
                    Class.forName("android.app.ActivityThread"), "currentApplication");
            return (Context) thread;
        } catch (Throwable t) {
            ModuleLog.w(t);
            return null;
        }
    }

    // Written by our system_server side (SystemSettings) into Settings.Global: readable from
    // any process, from the first frame of boot (it survives reboots).
    private static void read(Context context) {
        try {
            String v = android.provider.Settings.Global.getString(context.getContentResolver(),
                    SystemSettings.NAVBAR_KEY);
            slimPill = v != null && v.length() > 0 && v.charAt(0) == '1';
            imeStripOff = v != null && v.length() > 1 && v.charAt(1) == '1';
        } catch (Throwable ignored) {
            ModuleLog.w(ignored);
            // Settings not reachable yet: ask again next time.
        }
    }

    // The pill option follows the switch at once (redraw); the strip needs a restart.
    private static void observe(Context context) {
        if (observing) return;
        observing = true;
        try {
            context.getContentResolver().registerContentObserver(
                    android.provider.Settings.Global.getUriFor(SystemSettings.NAVBAR_KEY), false,
                    new ContentObserver(main) {
                        @Override
                        public void onChange(boolean selfChange) {
                            new Thread(() -> {
                                read(context);
                                main.post(() -> {
                                    for (View v : handles.toArray(new View[0])) {
                                        if (v == null) continue;
                                        v.invalidate();
                                    }
                                });
                            }, "GoDaijishou-navbar").start();
                        }
                    });
        } catch (Throwable ignored) {
            ModuleLog.w(ignored);
            observing = false;
        }
    }
}
