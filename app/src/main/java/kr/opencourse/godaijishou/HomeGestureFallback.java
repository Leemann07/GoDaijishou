package kr.opencourse.godaijishou;

import android.app.ActivityManager;
import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.content.pm.ResolveInfo;
import android.os.SystemClock;
import android.view.InputEvent;
import android.view.KeyEvent;
import android.view.MotionEvent;
import android.view.View;

import de.robv.android.xposed.XC_MethodHook;
import de.robv.android.xposed.XposedBridge;
import de.robv.android.xposed.XposedHelpers;

/**
 * Gesture navigation whatever the home app is (scope: com.android.systemui).
 *
 * LG runs the action of a swipe-up in the home app: LG's own home plays it, a
 * third-party home (Nova, a game front-end, anything) does not, and with one set the
 * pill rises to nothing. So while the default home is not LG's, the pill's own swipe
 * watcher finishes the gesture here: a fling up injects HOME, a slow drag held up
 * injects APP_SWITCH (recents). With LG's home default this stays out of the way
 * entirely; the lock screen and the home screen itself keep their own swipes.
 *
 * The NavigationHandle watcher that animates the pill already decides what looks like
 * a bottom swipe, so this rides on its verdict (the pill must have risen): no second
 * detection to disagree with the system.
 */
final class HomeGestureFallback {
    private static final String GESTURAL = "com.android.systemui.navigationbar.gestural.";
    private static final String RECEIVER = GESTURAL + "NavigationHandle$SysUiInputEventReceiver";
    private static final String LG_HOME = "com.lge.launcher3";

    // The swipe must travel at least this part of the gesture bar (the pill's own rise).
    private static final float RISE_FRACTION = 0.6f;
    // A fling faster than this (px per ms upward) is HOME; slower than half of it, held
    // at the top for a beat, is RECENTS.
    private static final float FLING_DP_PER_MS = 0.35f;
    private static final long HOLD_MS = 200;

    // LG keeps several pill watchers per display, each seeing every touch: the first to
    // see a gesture's down owns it (the others only let it go by), so the action fires once.
    private static final Object lock = new Object();
    private static Object owner;
    private static long ownerDownTime = -1;

    // The gesture being watched.
    private static float lastY, maxY;
    private static long lastMoveAt, heldSince = -1;

    // The resolved home package ("?" until read): only LG's own runs the gesture itself.
    private static volatile String homePkg = "?";
    private static volatile long homeResolvedAt;

    private HomeGestureFallback() {
    }

    static void install(ClassLoader cl) {
        Class<?> receiver = XposedHelpers.findClass(RECEIVER, cl);
        XposedBridge.hookAllMethods(receiver, "onInputEvent", new XC_MethodHook() {
            @Override
            protected void beforeHookedMethod(MethodHookParam param) {
                try {
                    watch(param.thisObject, (InputEvent) param.args[0]);
                } catch (Throwable t) {
                    ModuleLog.w(t); // never take the system's input down with us
                }
            }
        });
        trace("installed");
    }

    private static void watch(Object receiver, InputEvent event) {
        if (!(event instanceof MotionEvent)) return;
        MotionEvent ev = (MotionEvent) event;
        View handle = (View) XposedHelpers.getObjectField(receiver, "this$0");
        Context context = handle.getContext();
        if (!onMainDisplay(context)) return;
        int action = ev.getActionMasked();
        if (action == MotionEvent.ACTION_DOWN) {
            synchronized (lock) {
                if (ownerDownTime == ev.getDownTime()) return; // another watcher owns it
                ownerDownTime = ev.getDownTime();
                owner = receiver;
                lastY = ev.getY();
                maxY = 0;
                lastMoveAt = ev.getEventTime();
                heldSince = -1;
            }
            return;
        }
        if (action == MotionEvent.ACTION_CANCEL
                || action == MotionEvent.ACTION_UP || action == MotionEvent.ACTION_POINTER_UP) {
            boolean complete;
            synchronized (lock) {
                complete = receiver == owner;
                if (action != MotionEvent.ACTION_CANCEL || complete) {
                    owner = null;
                    ownerDownTime = -1;
                }
            }
            if (complete && action == MotionEvent.ACTION_UP && ev.getPointerCount() <= 1) {
                finished(handle, context, ev);
            }
            return;
        }
        if (action != MotionEvent.ACTION_MOVE) return;
        synchronized (lock) {
            if (receiver != owner) return;
            if (ev.getPointerCount() > 1) { // two fingers: not a system gesture
                owner = null;
                ownerDownTime = -1;
                return;
            }
            lastY = ev.getY();
            lastMoveAt = ev.getEventTime();
            float dy = rise(handle);
            if (dy > maxY) {
                // Held near the top of its travel, waiting: the recents pose.
                if (dy >= riseLimit(handle) * RISE_FRACTION && heldSince < 0) heldSince = ev.getEventTime();
                maxY = dy;
            }
        }
    }

    private static void finished(View handle, Context context, MotionEvent up) {
        float dy = rise(handle);
        float limit = riseLimit(handle);
        if (dy < limit * RISE_FRACTION) {
            trace("short rise: dy=" + dy + " limit=" + limit);
            return; // the pill never really rose
        }
        String why = enabledWhy(context);
        if (why != null) {
            trace("suppressed: " + why + " dy=" + dy);
            return;
        }
        long now = up.getEventTime();
        long dt = Math.max(1, now - lastMoveAt);
        float speed = Math.max(0f, lastY - up.getY()) / dt; // px per ms upward at the release
        boolean fling = speed >= FLING_DP_PER_MS * density(handle);
        boolean held = heldSince > 0 && now - heldSince >= HOLD_MS;
        int code = fling || !held ? KeyEvent.KEYCODE_HOME : KeyEvent.KEYCODE_APP_SWITCH;
        trace("fire " + (code == KeyEvent.KEYCODE_HOME ? "HOME" : "RECENTS")
                + " dy=" + dy + " speed=" + speed + " held=" + held);
        inject(code);
    }

    private static void trace(String message) {
        // One line per finished swipe: quiet in the log, and enough to see a dead gesture.
        android.util.Log.v("GoDaijishouGesture", message);
    }

    // --- Is this gesture ours to finish? ---

    /** null: yes; a reason: no. */
    private static String enabledWhy(Context context) {
        // LG's own home runs the gesture itself: adding ours would fire twice.
        String home = homePackage(context);
        if (LG_HOME.equals(home)) return "LG home";
        // The lock screen's swipe-up is its unlock, not a home press.
        try {
            android.app.KeyguardManager km = context.getSystemService(android.app.KeyguardManager.class);
            if (km != null && km.isKeyguardLocked()) return "keyguard";
        } catch (Throwable ignored) {
        }
        // On the home screen the launcher's own swipe comes first (Nova opens its drawer).
        Boolean onHome = onHome(context);
        if (onHome != null && onHome) return "on home";
        return null;
    }

    private static boolean onMainDisplay(Context context) {
        if (context == null) return false;
        try {
            return (int) XposedHelpers.callMethod(context, "getDisplayId") == android.view.Display.DEFAULT_DISPLAY;
        } catch (Throwable t) {
            return false;
        }
    }

    private static String homePackage(Context context) {
        String h = homePkg;
        long now = SystemClock.elapsedRealtime();
        if ("?".equals(h) || now - homeResolvedAt > 3000) {
            try {
                Intent intent = new Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_HOME);
                PackageManager pm = context.getPackageManager();
                ResolveInfo r = pm.resolveActivity(intent, 0);
                h = r != null && r.activityInfo != null ? r.activityInfo.packageName : "";
                homePkg = h;
                homeResolvedAt = now;
            } catch (Throwable t) {
                ModuleLog.w(t);
            }
        }
        return h;
    }

    private static Boolean onHome(Context context) {
        try {
            ActivityManager am = context.getSystemService(ActivityManager.class);
            if (am == null) return null;
            for (ActivityManager.RunningTaskInfo task : am.getRunningTasks(1)) {
                Intent base = task.baseIntent;
                return base != null && base.hasCategory(Intent.CATEGORY_HOME);
            }
        } catch (Throwable t) {
            ModuleLog.w(t);
        }
        return null;
    }

    // --- LG's own pill measurements ---

    private static float rise(View handle) {
        try {
            return XposedHelpers.getFloatField(handle, "mDy");
        } catch (Throwable t) {
            return 0;
        }
    }

    private static float riseLimit(View handle) {
        try {
            return XposedHelpers.getIntField(handle, "mFullgestureNavHeight");
        } catch (Throwable t) {
            return handle.getHeight() * 2; // unknown firmware: a rough bar and a half
        }
    }

    private static float density(View handle) {
        return handle.getResources().getDisplayMetrics().density;
    }

    // --- The action ---

    private static void inject(int code) {
        try {
            long t = SystemClock.uptimeMillis();
            Object im = XposedHelpers.callStaticMethod(
                    Class.forName("android.hardware.input.InputManager"), "getInstance");
            KeyEvent down = KeyEvent.changeTimeRepeat(new KeyEvent(KeyEvent.ACTION_DOWN, code), t, 0);
            KeyEvent up = KeyEvent.changeTimeRepeat(new KeyEvent(KeyEvent.ACTION_UP, code), t + 10, 0);
            XposedHelpers.callMethod(im, "injectInputEvent", down, 0 /* async */);
            XposedHelpers.callMethod(im, "injectInputEvent", up, 0 /* async */);
        } catch (Throwable t) {
            ModuleLog.w(t);
        }
    }
}
