package kr.opencourse.godaijishou;

import android.content.Context;
import android.database.ContentObserver;
import android.os.Handler;
import android.provider.Settings;

import de.robv.android.xposed.XC_MethodHook;
import de.robv.android.xposed.XposedBridge;
import de.robv.android.xposed.XposedHelpers;

/**
 * Main screen mode stays put while locked (system_server): the panel in front of the user is
 * display 0 the whole time, and its lock screen is the phone's own.
 *
 * - Swivel told "closed" while swapped (LG's PostureManagerService: getSwivelState 0, callbacks
 *   100/101 sent as 200/201): SystemUI then unlocks on display 0, not with its swivel lock.
 * - Display 0 in the default display group: LG marks this panel "own display group", so the
 *   power key slept group 0 only and display 0 stayed on with every touch dropped.
 * - Display 4 (the top panel, off) never holds input focus: SystemUI still adds its swivel lock
 *   window there, and focus followed it, away from the lock screen in front of the user.
 *
 * It simply follows the swap: main screen mode is swapped (fixed), second screen mode is LG's
 * own. Any restore (volume up + down, the adb escape, leaving the mode) unswaps, which ends all
 * of it.
 */
final class SwivelPosture {

    private static final String SERVICE = "com.lge.systemservice.service.PostureManagerService";
    private static final int STATE_OPEN = 2, STATE_CLOSED = 0;
    private static final int OPENING = 100, OPENED = 101, CLOSING = 200, CLOSED = 201;

    private static volatile Object service;
    private static volatile boolean announcedFolded;

    private SwivelPosture() {
    }

    static boolean folded() {
        return DisplaySwap.isSwapped();
    }

    static void install(ClassLoader cl) {
        Class<?> c = XposedHelpers.findClass(SERVICE, cl);
        XposedHelpers.findField(c, "mTransState");
        XposedBridge.hookAllConstructors(c, new XC_MethodHook() {
            @Override
            protected void afterHookedMethod(MethodHookParam param) {
                service = param.thisObject;
            }
        });
        XposedBridge.hookAllMethods(c, "getSwivelState", new XC_MethodHook() {
            @Override
            protected void afterHookedMethod(MethodHookParam param) {
                if (folded() && Integer.valueOf(STATE_OPEN).equals(param.getResult())) {
                    param.setResult(STATE_CLOSED);
                }
            }
        });
        XposedBridge.hookAllMethods(c, "callbackSwivelStateChanged", new XC_MethodHook() {
            @Override
            protected void beforeHookedMethod(MethodHookParam param) {
                if (folded() && param.args.length > 0 && param.args[0] instanceof Integer) {
                    param.args[0] = fold((Integer) param.args[0]);
                }
            }
        });
        XposedBridge.hookAllMethods(c, "registerPostureStateCallbackInternal", new XC_MethodHook() {
            @Override
            protected void beforeHookedMethod(MethodHookParam param) {
                if (!folded()) return;
                int real = XposedHelpers.getIntField(param.thisObject, "mTransState");
                int told = fold(real);
                if (told == real) return;
                param.setObjectExtra("gdj_trans", real);
                XposedHelpers.setIntField(param.thisObject, "mTransState", told);
            }

            @Override
            protected void afterHookedMethod(MethodHookParam param) {
                Object real = param.getObjectExtra("gdj_trans");
                if (!(real instanceof Integer)) return;
                if (XposedHelpers.getIntField(param.thisObject, "mTransState") == fold((Integer) real)) {
                    XposedHelpers.setIntField(param.thisObject, "mTransState", (Integer) real);
                }
            }
        });
    }

    @SuppressWarnings("unchecked")
    static void installDefaultGroup(ClassLoader cl) {
        Class<?> mapper = XposedHelpers.findClass("com.android.server.display.LogicalDisplayMapper", cl);
        XposedBridge.hookAllMethods(mapper, "assignDisplayGroupLocked", new XC_MethodHook() {
            @Override
            protected void afterHookedMethod(MethodHookParam param) {
                if (!folded() || param.args.length == 0 || param.args[0] == null) return;
                Object display = param.args[0];
                try {
                    if ((Integer) XposedHelpers.callMethod(display, "getDisplayIdLocked") != 0) return;
                    int group = (Integer) XposedHelpers.callMethod(param.thisObject,
                            "getDisplayGroupIdFromDisplayIdLocked", 0);
                    if (group == 0) return;
                    Object from = XposedHelpers.callMethod(param.thisObject, "getDisplayGroupLocked", group);
                    Object to = XposedHelpers.callMethod(param.thisObject, "getDisplayGroupLocked", 0);
                    if (to == null) {
                        // Emptied when display 0 left it at the swap: made again.
                        to = XposedHelpers.newInstance(from != null ? from.getClass()
                                : XposedHelpers.findClass("com.android.server.display.DisplayGroup",
                                        param.thisObject.getClass().getClassLoader()), 0);
                        ((android.util.SparseArray<Object>) XposedHelpers.getObjectField(param.thisObject,
                                "mDisplayGroups")).append(0, to);
                    }
                    if (from != null) XposedHelpers.callMethod(from, "removeDisplayLocked", display);
                    XposedHelpers.callMethod(to, "addDisplayLocked", display);
                    XposedHelpers.callMethod(display, "updateDisplayGroupIdLocked", 0);
                    ModuleLog.i("display 0 into the default display group (was " + group + ")");
                } catch (Throwable t) {
                    ModuleLog.w("display group", t);
                }
            }
        });
    }

    static void installNoFocusOnTop(ClassLoader cl) {
        Class<?> dc = XposedHelpers.findClass("com.android.server.wm.DisplayContent", cl);
        XC_MethodHook none = new XC_MethodHook() {
            @Override
            protected void beforeHookedMethod(MethodHookParam param) {
                if (!folded()) return;
                try {
                    if (XposedHelpers.getIntField(param.thisObject, "mDisplayId")
                            == MainModule.SECOND_SCREEN_DISPLAY_ID) {
                        param.setResult(null);
                    }
                } catch (Throwable t) {
                    ModuleLog.w(t);
                }
            }
        };
        XposedBridge.hookAllMethods(dc, "findFocusedWindowIfNeeded", none);
        XposedBridge.hookAllMethods(dc, "findFocusedWindow", none);
    }

    private static volatile boolean mainScreenOn = true;

    /**
     * The second screen's panel has its own touch controller (sub LPWG). LG tells it "screen
     * on" only when the second screen turns on (mWingScreenState); fixed, this panel turns on
     * as the main display, so its controller stayed in knock-on standby and dropped every
     * touch. While fixed it follows the main screen's on/off instead.
     */
    private static volatile Object controller; // LPWGControllerImpl

    // This is said as the screen starts to go off; the panel itself goes off a moment later,
    // and the panel turning off puts its touch controller in its plain standby (no knock). LG's
    // own second screen says it after its panel is off. Said again once the panel is off.
    private static void rearmLater(Object ctl, Object w) {
        Handler h = SystemSettings.handler();
        if (h == null) return;
        for (long ms : new long[] {700, 1500}) {
            h.postDelayed(() -> {
                if (!folded() || mainScreenOn) return;
                try {
                    synchronized (ctl) {
                        XposedHelpers.callMethod(w, "updateStateLocked", false, wingKnock(ctl), true);
                    }
                    ModuleLog.i("second panel touch: knock-on standby again");
                } catch (Throwable t) {
                    ModuleLog.w("knock-on standby", t);
                }
            }, ms);
        }
    }

    // The knock-on setting alone (LG's check also wants the swivel open).
    private static boolean wingKnock(Object ctl) {
        return !XposedHelpers.getBooleanField(ctl, "mScreenReserveSetting")
                && XposedHelpers.getBooleanField(ctl, "mKnockOnSetting");
    }

    /**
     * LG drives the second screen's panel (a "multi display" device) through its own second
     * screen power service, and drops display 0's on/off requests for it (brightness only).
     * Fixed, that panel is display 0 and LG's service leaves it alone: switched off it only
     * went dark, the panel stayed powered, so its touch chip never suspended and never sent a
     * knock (kernel: "WAKEUP is waiting"). Here display 0's on/off reaches it the normal way.
     */
    static void installPanelPower(ClassLoader cl) {
        Class<?> device = XposedHelpers.findClass("com.android.server.display.LocalDisplayAdapter$LocalDisplayDevice", cl);
        XposedHelpers.findField(device, "mIsMultiDisplayDevice");
        XposedHelpers.findMethodExact(device, "requestDisplayStateLockedInternal", int.class, float.class, float.class);
        XposedHelpers.findAndHookMethod(device, "requestDisplayStateLocked", int.class, float.class, float.class,
                new XC_MethodHook() {
                    @Override
                    protected void beforeHookedMethod(MethodHookParam param) {
                        if (!folded()) return;
                        try {
                            if (!XposedHelpers.getBooleanField(param.thisObject, "mIsMultiDisplayDevice")) return;
                            if (!DisplaySwap.isDisplayZeroDevice(param.thisObject)) return;
                            param.setResult(XposedHelpers.callMethod(param.thisObject,
                                    "requestDisplayStateLockedInternal", param.args[0], param.args[1], param.args[2]));
                        } catch (Throwable t) {
                            ModuleLog.w("panel power", t);
                        }
                    }
                });
    }

    static void installSubTouch(ClassLoader cl) {
        Class<?> ctl = XposedHelpers.findClass("com.android.server.power.features.lpwg.LPWGControllerImpl", cl);
        Class<?> wrapper = XposedHelpers.findClass("com.android.server.power.features.lpwg.WLPWGHalWrapper", cl);
        XposedHelpers.findField(ctl, "mWHalWrapper");
        XposedBridge.hookAllMethods(wrapper, "updateStateLocked", new XC_MethodHook() {
            @Override
            protected void beforeHookedMethod(MethodHookParam param) {
                if (!folded() || param.args.length != 3) return;
                param.args[0] = mainScreenOn;
                Object c = controller;
                if (c != null) param.args[1] = wingKnock(c);
            }
        });
        // Knock-on on this panel is LG's "wing" knock-on, which needs the swivel open (mSwiveled):
        // fixed, the swivel is told closed, so its standby mode was "none" and a knock never woke
        // the phone. While fixed only the knock-on settings decide, and a knock wakes the phone.
        XposedBridge.hookAllMethods(ctl, "checkWingKnockOnMode", new XC_MethodHook() {
            @Override
            protected void afterHookedMethod(MethodHookParam param) {
                if (folded()) param.setResult(wingKnock(param.thisObject));
            }
        });
        XposedBridge.hookAllMethods(ctl, "wakeUpByWKnockOn", new XC_MethodHook() {
            @Override
            protected void beforeHookedMethod(MethodHookParam param) {
                if (!folded() || XposedHelpers.getBooleanField(param.thisObject, "mSwiveled")) return;
                try {
                    XposedHelpers.callMethod(param.thisObject, "wakeUp", param.args[0]);
                    ModuleLog.i("knock-on on the main screen");
                } catch (Throwable t) {
                    ModuleLog.w("knock-on", t);
                }
                param.setResult(null);
            }
        });
        // wakeUpByWKnockOn is tiny and compiled into handleWLPWG (inlined): that caller runs
        // from its bytecode so the hook above is reached (LSPosed's deoptimize).
        try {
            java.lang.reflect.Method deopt = XposedBridge.class.getMethod("deoptimizeMethod",
                    java.lang.reflect.Member.class);
            for (java.lang.reflect.Method m : ctl.getDeclaredMethods()) {
                if (m.getName().equals("handleWLPWG")) deopt.invoke(null, m);
            }
        } catch (Throwable t) {
            ModuleLog.w("knock-on deoptimize", t);
        }
        XposedBridge.hookAllMethods(ctl, "updateScreenStateLocked", new XC_MethodHook() {
            @Override
            protected void afterHookedMethod(MethodHookParam param) {
                if (param.args.length != 1 || !(param.args[0] instanceof Boolean)) return;
                mainScreenOn = (Boolean) param.args[0];
                controller = param.thisObject;
                if (!folded()) return;
                try {
                    Object w = XposedHelpers.getObjectField(param.thisObject, "mWHalWrapper");
                    if (w == null) return;
                    boolean knock = wingKnock(param.thisObject);
                    XposedHelpers.callMethod(w, "updateStateLocked", mainScreenOn, knock, true);
                    ModuleLog.i("second panel touch: screen " + (mainScreenOn ? "on" : "off")
                            + ", knock-on " + knock);
                    if (!mainScreenOn) rearmLater(param.thisObject, w);
                } catch (Throwable t) {
                    ModuleLog.w("sub touch", t);
                }
            }
        });
    }

    private static int fold(int trans) {
        if (trans == OPENING) return CLOSING;
        if (trans == OPENED) return CLOSED;
        return trans;
    }

    static void swapChanged() {
        Handler h = SystemSettings.handler();
        if (h != null) h.post(SwivelPosture::announce);
    }

    private static void announce() {
        boolean want = folded();
        if (want == announcedFolded) return;
        Object s = service;
        if (s == null) return;
        try {
            int swivel = XposedHelpers.getIntField(s, "mSwivelState");
            announcedFolded = want;
            if (swivel != STATE_OPEN) return;
            XposedHelpers.callMethod(s, "callbackSwivelStateChanged", OPENING);
            XposedHelpers.callMethod(s, "callbackSwivelStateChanged", OPENED);
            ModuleLog.i("swivel announced " + (want ? "closed (main screen mode)" : "open"));
        } catch (Throwable t) {
            ModuleLog.w(t);
        }
    }
}
