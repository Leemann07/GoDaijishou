package kr.opencourse.godaijishou;

import android.content.Context;
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
 * The shade's window stays added but hidden. The window manager tells it about a rotation,
 * and a hidden window applies it only at its next traversal: the first pull-down after a
 * rotation. LG's panel then inflated itself again for the new orientation in the middle of
 * that swipe and dropped it (the pull-down "did nothing" after turning the screen). When the
 * main display turns, the hidden shade is relaid out once right away, so it is ready before
 * anyone pulls. One relayout per rotation; nothing otherwise.
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
                relayout();
            }
        }, main);
    }

    // The hidden shade's window relaid out now: it takes the new configuration and LG's panel
    // inflates itself for it here, not under the next finger.
    private static void relayout() {
        View v = shade;
        if (v == null || v.isShown()) return; // shown: it follows the rotation as it is
        try {
            Object root = XposedHelpers.callMethod(v, "getViewRootImpl");
            if (root == null) return;
            XposedHelpers.setBooleanField(root, "mForceNextWindowRelayout", true);
            v.requestLayout();
        } catch (Throwable t) {
            ModuleLog.w(t);
        }
    }
}
