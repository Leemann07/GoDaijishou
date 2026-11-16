package kr.opencourse.godaijishou;

import android.content.Context;
import android.graphics.Point;
import android.os.PowerManager;
import android.os.SystemClock;
import android.util.DisplayMetrics;
import android.view.Display;
import android.view.InputDevice;
import android.view.InputEvent;
import android.view.KeyCharacterMap;
import android.view.KeyEvent;
import android.view.MotionEvent;
import android.view.VelocityTracker;
import android.view.View;
import android.view.ViewConfiguration;
import android.view.WindowInsets;

import de.robv.android.xposed.XC_MethodHook;
import de.robv.android.xposed.XposedBridge;
import de.robv.android.xposed.XposedHelpers;

/**
 * Bottom swipes while the system's gesture engine is gone (scope: com.android.systemui).
 *
 * The engine is LG quickstep (TouchInteractionService in com.lge.launcher3). It handles home
 * and recents whatever the home app is: LG's home directly, a third-party home (Nova,
 * Daijishou, ES-DE, Pegasus) through its FallbackSwipeHandler and RecentsActivity. Verified on
 * the Wing (A13): fling = the chosen home, hold = recents, from apps and from the home.
 * Its process can die (crash, restart after a display change); SystemUI then has no engine
 * until it binds again, and LG's pill still rises under the finger with nothing behind it.
 *
 * Only then do we finish the swipe (HOME / APP_SWITCH keys). While the engine is connected we
 * do nothing at all: an earlier version also acted for third-party homes, and its HOME key
 * landed in the middle of quickstep's own recents animation (two navigations per swipe,
 * broken recents). LG's drawing and motion fields are never changed. Locked gestures are
 * never completed here.
 */
final class HomeGestureFallback {
    private static final String GESTURAL = "com.android.systemui.navigationbar.gestural.";
    private static final String RECEIVER = GESTURAL + "NavigationHandle$SysUiInputEventReceiver";
    private static final String OVERVIEW_PROXY = "com.android.systemui.recents.OverviewProxyService";

    // SystemUI's connection to quickstep (one per SystemUI process); null until it is built.
    private static volatile Object overviewProxyService;

    private static final float START_ZONE_DP = 24f;
    private static final float FLING_DP_PER_SECOND = 350f;
    private static final Object lock = new Object();
    private static final HomeGestureTracker tracker = new HomeGestureTracker();
    private static VelocityTracker velocity;
    private static int gestureWidth, gestureHeight, gestureRotation;
    private static float gestureDensity;

    private HomeGestureFallback() {
    }

    static void install(ClassLoader cl) {
        // Without a view of the connection the fallback stays off: the system keeps the gesture.
        Class<?> proxy = XposedHelpers.findClass(OVERVIEW_PROXY, cl);
        XposedHelpers.findField(proxy, "mOverviewProxy");
        XposedBridge.hookAllConstructors(proxy, new XC_MethodHook() {
            @Override
            protected void afterHookedMethod(MethodHookParam param) {
                overviewProxyService = param.thisObject;
            }
        });
        Class<?> receiver = XposedHelpers.findClass(RECEIVER, cl);
        XposedBridge.hookAllMethods(receiver, "onInputEvent", new XC_MethodHook() {
            @Override
            protected void beforeHookedMethod(MethodHookParam param) {
                try {
                    watch(param.thisObject, (InputEvent) param.args[0]);
                } catch (Throwable t) {
                    synchronized (lock) {
                        if (param.args[0] instanceof MotionEvent) {
                            MotionEvent ev = (MotionEvent) param.args[0];
                            if (tracker.owns(param.thisObject, ev.getDownTime(), ev.getDeviceId())) {
                                tracker.cancel(param.thisObject, ev.getDownTime(), ev.getDeviceId());
                                recycleVelocity();
                            }
                        }
                    }
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
        HomeGestureTracker.Result result = HomeGestureTracker.Result.NONE;
        synchronized (lock) {
            int action = ev.getActionMasked();
            long down = ev.getDownTime();
            int device = ev.getDeviceId();
            if (!ev.isFromSource(InputDevice.SOURCE_TOUCHSCREEN)
                    || ev.getToolType(0) != MotionEvent.TOOL_TYPE_FINGER
                    || (android.os.Build.VERSION.SDK_INT >= 33
                        && (ev.getFlags() & MotionEvent.FLAG_CANCELED) != 0)) {
                if (tracker.owns(receiver, down, device)) {
                    tracker.cancel(receiver, down, device);
                    recycleVelocity();
                }
                return;
            }
            if (action == MotionEvent.ACTION_DOWN) {
                Display display = handle.getDisplay();
                if (display == null) return;
                // The view's resources can still describe the previous panel after a swap.
                DisplayMetrics metrics = new DisplayMetrics();
                display.getRealMetrics(metrics);
                Point size = new Point(metrics.widthPixels, metrics.heightPixels);
                float density = metrics.density;
                float zone = START_ZONE_DP * density;
                WindowInsets insets = handle.getRootWindowInsets();
                if (android.os.Build.VERSION.SDK_INT >= 29 && insets != null) {
                    zone = Math.max(zone, Math.min(48f * density,
                            insets.getMandatorySystemGestureInsets().bottom));
                }
                float slop = ViewConfiguration.get(context).getScaledTouchSlop();
                float resourceDensity = handle.getResources().getDisplayMetrics().density;
                if (resourceDensity > 0) slop *= density / resourceDensity;
                float rise = Math.max(2f * slop, Math.min(72f * density,
                        Math.min(size.x, size.y) * 0.12f));
                if (!tracker.start(receiver, down, device, ev.getX(), ev.getY(),
                        size.x, size.y, zone, rise, slop, FLING_DP_PER_SECOND * density)) return;
                recycleVelocity();
                if (!tracker.owns(receiver, down, device)) return;
                if (ev.getPointerCount() != 1 || !eligible(context)) {
                    tracker.cancel(receiver, down, device);
                    return;
                }
                gestureWidth = size.x;
                gestureHeight = size.y;
                gestureRotation = display.getRotation();
                gestureDensity = density;
                velocity = VelocityTracker.obtain();
                velocity.addMovement(ev);
                return;
            }
            // A peer's UP/CANCEL must never clear the owner's state, nor may an old stream
            // finish a newer one. Keep recognition and velocity access under the same lock.
            if (!tracker.owns(receiver, down, device)) return;
            if (action == MotionEvent.ACTION_CANCEL || action == MotionEvent.ACTION_POINTER_DOWN
                    || action == MotionEvent.ACTION_POINTER_UP || ev.getPointerCount() != 1) {
                tracker.cancel(receiver, down, device);
                recycleVelocity();
                return;
            }
            if (action != MotionEvent.ACTION_MOVE && action != MotionEvent.ACTION_UP) return;
            if (!tracker.accepts(receiver, down, device, ev.getEventTime())) return;
            velocity.addMovement(ev);
            for (int i = 0; i < ev.getHistorySize(); i++) {
                tracker.move(receiver, down, device, ev.getHistoricalX(i), ev.getHistoricalY(i),
                        ev.getHistoricalEventTime(i));
            }
            if (!tracker.owns(receiver, down, device)) {
                recycleVelocity();
                return;
            }
            if (action == MotionEvent.ACTION_MOVE) {
                if (!tracker.move(receiver, down, device, ev.getX(), ev.getY(), ev.getEventTime())) {
                    recycleVelocity();
                }
                return;
            }
            try {
                Display display = handle.getDisplay();
                DisplayMetrics metrics = new DisplayMetrics();
                if (display != null) display.getRealMetrics(metrics);
                if (display == null || display.getRotation() != gestureRotation
                        || metrics.widthPixels != gestureWidth || metrics.heightPixels != gestureHeight
                        || metrics.density != gestureDensity) {
                    tracker.cancel(receiver, down, device); // rotation/panel switch during a swipe
                    return;
                }
                velocity.computeCurrentVelocity(1000);
                float speed = -velocity.getYVelocity(ev.getPointerId(0));
                result = tracker.finish(receiver, down, device, ev.getX(), ev.getY(), ev.getEventTime(), speed);
            } finally {
                recycleVelocity();
            }
        }
        if (result == HomeGestureTracker.Result.NONE) return;
        // Fresh state: quickstep may have come back (or the phone locked) during the swipe.
        if (!eligible(context)) {
            trace("suppressed at finish: quickstep back or state changed");
            return;
        }
        synchronized (lock) {
            // Resolving policy can cross a newer DOWN on a different monitor thread.
            if (!tracker.isLatest(ev.getDownTime(), ev.getDeviceId())) return;
            int code = result == HomeGestureTracker.Result.HOME
                    ? KeyEvent.KEYCODE_HOME : KeyEvent.KEYCODE_APP_SWITCH;
            trace("inject " + result + " accepted=" + inject(code));
        }
    }

    private static void recycleVelocity() {
        if (velocity != null) {
            velocity.recycle();
            velocity = null;
        }
    }

    private static void trace(String message) {
        // Only edge candidates and completed swipes, so a suppressed gesture is diagnosable.
        android.util.Log.v("GoDaijishouGesture", message);
    }

    // --- Is this gesture ours to finish? ---

    // This framework overlay has no public R constant; read its value on every candidate
    // so switching between gesture/button navigation cannot leave a stale policy enabled.
    @android.annotation.SuppressLint("DiscouragedApi")
    private static boolean eligible(Context context) {
        try {
            int mode = context.getResources().getIdentifier("config_navBarInteractionMode", "integer", "android");
            boolean gestural = mode != 0 && context.getResources().getInteger(mode) == 2;
            android.app.KeyguardManager km = context.getSystemService(android.app.KeyguardManager.class);
            PowerManager power = context.getSystemService(PowerManager.class);
            return HomeGesturePolicy.allows(quickstepConnected(), gestural,
                    km == null ? null : km.isKeyguardLocked(),
                    power == null ? null : power.isInteractive());
        } catch (Throwable t) {
            ModuleLog.w(t);
            return false;
        }
    }

    // What SystemUI's own dump prints as isConnected; null when it cannot be read.
    private static Boolean quickstepConnected() {
        Object service = overviewProxyService;
        if (service == null) return null;
        try {
            return XposedHelpers.getObjectField(service, "mOverviewProxy") != null;
        } catch (Throwable t) {
            ModuleLog.w(t);
            return null;
        }
    }

    private static boolean onMainDisplay(Context context) {
        if (context == null) return false;
        try {
            return (int) XposedHelpers.callMethod(context, "getDisplayId") == Display.DEFAULT_DISPLAY;
        } catch (Throwable t) {
            return false;
        }
    }

    // --- The action ---

    private static boolean inject(int code) {
        try {
            long t = SystemClock.uptimeMillis();
            Object im = XposedHelpers.callStaticMethod(
                    Class.forName("android.hardware.input.InputManager"), "getInstance");
            KeyEvent down = new KeyEvent(t, t, KeyEvent.ACTION_DOWN, code, 0, 0,
                    KeyCharacterMap.VIRTUAL_KEYBOARD, 0,
                    KeyEvent.FLAG_FROM_SYSTEM | KeyEvent.FLAG_VIRTUAL_HARD_KEY, InputDevice.SOURCE_KEYBOARD);
            KeyEvent up = KeyEvent.changeAction(down, KeyEvent.ACTION_UP);
            XposedHelpers.callMethod(down, "setDisplayId", Display.DEFAULT_DISPLAY);
            XposedHelpers.callMethod(up, "setDisplayId", Display.DEFAULT_DISPLAY);
            boolean pressed;
            boolean released;
            try {
                pressed = Boolean.TRUE.equals(XposedHelpers.callMethod(im, "injectInputEvent", down, 0));
            } finally {
                // Try to release even when the press was rejected or threw.
                released = Boolean.TRUE.equals(XposedHelpers.callMethod(im, "injectInputEvent", up, 0));
            }
            return pressed && released;
        } catch (Throwable t) {
            ModuleLog.w(t);
            return false;
        }
    }
}
