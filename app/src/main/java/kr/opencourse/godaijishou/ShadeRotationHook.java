package kr.opencourse.godaijishou;

import android.content.Context;
import android.content.res.Configuration;
import android.hardware.display.DisplayManager;
import android.os.Handler;
import android.os.Looper;
import android.view.Display;
import android.view.View;

import de.robv.android.xposed.XC_MethodHook;
import de.robv.android.xposed.XposedBridge;
import de.robv.android.xposed.XposedHelpers;

/**
 * The notification shade takes a rotation while it is hidden (scope: com.android.systemui).
 *
 * The shade's window stays added but hidden, and a hidden window gets its new configuration
 * only when it is next drawn: the first pull-down after a rotation. LG's panel rebuilds itself
 * when its orientation, smallest width or rotation changed, so it rebuilt in the middle of
 * that swipe and dropped it (the pull-down "did nothing" after turning the screen). When the
 * main display turns, the hidden shade's views get the process's new configuration at once
 * (it arrives right away, the window's does not): the panel rebuilds there, unseen, and finds
 * nothing changed when it is pulled. A few checks per rotation; nothing otherwise.
 */
final class ShadeRotationHook {

    private static final String CONTROLLER = "com.android.systemui.statusbar.phone.NotificationShadeWindowControllerImpl";

    private static volatile View shade;
    private static int lastRotation = -1;
    private static boolean listening;

    private ShadeRotationHook() {
    }

    static void install(ClassLoader cl) {
        Class<?> controller = XposedHelpers.findClass(CONTROLLER, cl);
        XposedHelpers.findField(controller, "mNotificationShadeView");
        XposedBridge.hookAllMethods(controller, "attach", new XC_MethodHook() {
            @Override
            protected void afterHookedMethod(MethodHookParam param) {
                Object v = XposedHelpers.getObjectField(param.thisObject, "mNotificationShadeView");
                if (!(v instanceof View)) return;
                shade = (View) v;
                ModuleLog.i("shade: attached");
                listen(shade.getContext());
            }
        });
    }

    private static void listen(Context context) {
        if (listening) return;
        listening = true;
        Handler main = new Handler(Looper.getMainLooper());
        DisplayManager dm = context.getSystemService(DisplayManager.class);
        Display d = dm.getDisplay(Display.DEFAULT_DISPLAY);
        if (d != null) lastRotation = d.getRotation();
        dm.registerDisplayListener(new DisplayManager.DisplayListener() {
            @Override
            public void onDisplayAdded(int displayId) {
            }

            @Override
            public void onDisplayRemoved(int displayId) {
            }

            @Override
            public void onDisplayChanged(int displayId) {
                if (displayId != Display.DEFAULT_DISPLAY) return;
                Display display = dm.getDisplay(Display.DEFAULT_DISPLAY);
                if (display == null) return;
                int rotation = display.getRotation();
                if (rotation == lastRotation) return;
                lastRotation = rotation;
                ModuleLog.i("shade: main display turned to " + rotation);
                relayout();
            }
        }, main);
    }

    // The hidden shade's views told about the new orientation now, once the process has it.
    private static int tries;

    private static void relayout() {
        View v = shade;
        if (v == null) return;
        v.removeCallbacks(APPLY);
        tries = 0;
        APPLY.run();
    }

    private static final Runnable APPLY = ShadeRotationHook::apply;

    private static void apply() {
        View v = shade;
        if (v == null || v.isShown()) return; // shown: it follows the rotation as it is
        try {
            Display d = v.getContext().getSystemService(DisplayManager.class).getDisplay(Display.DEFAULT_DISPLAY);
            if (d == null) return;
            android.graphics.Point size = new android.graphics.Point();
            d.getRealSize(size);
            int want = size.x > size.y ? Configuration.ORIENTATION_LANDSCAPE : Configuration.ORIENTATION_PORTRAIT;
            Configuration c = new Configuration(v.getContext().getResources().getConfiguration());
            if (c.orientation != want) {
                // The process's configuration is a moment behind the display: look again.
                if (++tries <= 8) v.postDelayed(APPLY, 100);
                return;
            }
            v.dispatchConfigurationChanged(c);
            ModuleLog.i("shade: configuration applied while hidden");
        } catch (Throwable t) {
            ModuleLog.w(t);
        }
    }
}
