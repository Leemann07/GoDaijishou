package kr.opencourse.godaijishou;

import android.content.Context;
import android.content.res.Resources;
import android.graphics.Color;
import android.graphics.PixelFormat;
import android.graphics.drawable.GradientDrawable;
import android.hardware.display.DisplayManager;
import android.os.Handler;
import android.provider.Settings;
import android.util.TypedValue;
import android.view.Display;
import android.view.Gravity;
import android.view.View;
import android.view.WindowManager;
import android.widget.LinearLayout;
import android.widget.TextView;

/**
 * Main screen mode: the second screen's panel is the phone's main display (DisplaySwap), so
 * the lock screen, status bar, shade, home, brightness and every app run on it natively
 * (system_server, settings thread only).
 *
 * Never a way to get stuck:
 * - A newly applied mode is a trial: the swapped screen asks to keep it, and without a tap
 *   within 20 seconds everything goes back (as when a display resolution is changed). Only a
 *   kept mode is swapped again at the next boots.
 * - Volume up + down (SafetyEscape) restores at once, also across reboots, until the mode is
 *   applied again in the app.
 * - A restore is checked (DisplaySwap): second screen touches back, or a reboot.
 * - Picked without the hooks it needs (SystemSettings.mainReady), a trial does not start.
 */
final class MainScreen {

    // Settings.Global: the mode epoch (AppPreferences.KEY_MODE_EPOCH) the user kept.
    private static final String KEY_KEPT = "godaijishou_main_kept";
    private static final int CONFIRM_SECONDS = 20;

    private static boolean trial;
    // The swap is this mode's own (not the adb test switch): only then does it undo it.
    private static boolean ownSwap;
    private static int trialEpoch;
    private static int secondsLeft;
    private static WindowManager confirmWm;
    private static LinearLayout confirmView;
    private static TextView countView;

    private MainScreen() {
    }

    /** Re-evaluates on every settings or display change. */
    static void apply() {
        boolean picked = SystemSettings.screenMode == AppPreferences.SCREEN_MODE_ORIGINAL;
        if (!picked || TopMirror.isEscaped()) {
            endTrial();
            if (ownSwap && DisplaySwap.isSwapped()) DisplaySwap.set(false);
            ownSwap = false;
            return;
        }
        int epoch = SystemSettings.modeEpoch;
        if (epoch < 0) return; // the app's settings not read yet this boot
        watchPresent();
        watchBrightness();
        // Locked or asleep: stays as it is. Main screen mode keeps the swap (SwivelPosture
        // makes LG's lock screen a plain phone lock screen on this panel).
        if (!present()) {
            if (trial) {
                escape(); // locked during the trial: not kept
            } else if (!DisplaySwap.isSwapped() && kept() >= 0 && SystemSettings.mainReady && interactive()) {
                // Main screen mode stays fixed through lock (SwivelPosture): from the first lock
                // after boot on the phone's own lock screen is on this panel.
                DisplaySwap.set(true);
                ownSwap = DisplaySwap.isSwapped();
                syncBrightness();
            }
            return;
        }
        // Kept once on this phone: from then on it switches without asking again.
        if (kept() >= 0) {
            if (!DisplaySwap.isSwapped()) {
                DisplaySwap.set(true);
                bringAppsLater();
            }
            syncBrightness();
            ownSwap = DisplaySwap.isSwapped();
            return;
        }
        if (trial || !SystemSettings.mainReady) return;
        startTrial(epoch);
    }

    // --- Brightness: swapped, the main display's brightness never reached the second
    // screen's panel (LG drives that panel's backlight on its own, and only as display 4).
    // While swapped the main display's brightness is put on the panel directly. ---

    private static final long BRIGHTNESS_SETTLE_MS = 150; // the display's own update first
    private static boolean brightnessWatched;

    private static void watchBrightness() {
        BrightnessSync.watch();
        if (brightnessWatched) return;
        Context c = SystemSettings.context();
        Handler h = SystemSettings.handler();
        if (c == null || h == null) return;
        // The display's own brightness events: also the slider while it is dragged (a
        // temporary brightness, written to the setting only when let go).
        DisplayManager.DisplayListener listener = new DisplayManager.DisplayListener() {
            @Override public void onDisplayAdded(int id) { }
            @Override public void onDisplayRemoved(int id) { }
            @Override public void onDisplayChanged(int id) {
                if (id == Display.DEFAULT_DISPLAY) syncBrightness();
            }
        };
        try {
            de.robv.android.xposed.XposedHelpers.callMethod(c.getSystemService(DisplayManager.class),
                    "registerDisplayListener", new Class<?>[] {DisplayManager.DisplayListener.class,
                            Handler.class, long.class},
                    listener, h, EVENT_FLAG_DISPLAY_BRIGHTNESS);
            brightnessWatched = true;
            return;
        } catch (Throwable t) {
            ModuleLog.w(t); // no brightness events here: the setting, when the slider is let go
        }
        try {
            c.getContentResolver().registerContentObserver(
                    Settings.System.getUriFor(Settings.System.SCREEN_BRIGHTNESS), false,
                    new android.database.ContentObserver(h) {
                        @Override
                        public void onChange(boolean selfChange) {
                            h.removeCallbacks(APPLY_BRIGHTNESS);
                            h.postDelayed(APPLY_BRIGHTNESS, BRIGHTNESS_SETTLE_MS);
                        }
                    });
            brightnessWatched = true;
        } catch (Throwable t) {
            ModuleLog.w(t);
        }
    }

    private static final long EVENT_FLAG_DISPLAY_BRIGHTNESS = 1L << 3; // DisplayManager, hidden

    private static final Runnable APPLY_BRIGHTNESS = MainScreen::syncBrightness;

    private static void syncBrightness() {
        if (!DisplaySwap.isSwapped()) return;
        try {
            Context c = SystemSettings.context();
            DisplayManager dm = c.getSystemService(DisplayManager.class);
            float b = (float) de.robv.android.xposed.XposedHelpers.callMethod(dm, "getBrightness",
                    Display.DEFAULT_DISPLAY);
            if (Float.isNaN(b) || b < 0f) return;
            // The backlight LG's second screen controller puts on this panel for the same
            // setting (BrightnessSync keeps both settings equal): one value, no jumps.
            float backlight = Math.min(1f, b);
            long id = SystemActions.physicalId(c, Display.DEFAULT_DISPLAY);
            if (id < 0) return;
            Class<?> sc = Class.forName("android.view.SurfaceControl");
            Object token = de.robv.android.xposed.XposedHelpers.callStaticMethod(sc, "getPhysicalDisplayToken", id);
            if (token == null) return;
            de.robv.android.xposed.XposedHelpers.callStaticMethod(sc, "setDisplayBrightness", token, backlight);
        } catch (Throwable t) {
            ModuleLog.w(t);
        }
    }

    private static void bringAppsLater() {
        Handler h = SystemSettings.handler();
        if (h != null) h.postDelayed(MainScreen::bringAppsToMain, 500); // once the layout settled
    }

    /**
     * Swapped, display 4 is the top panel and off: apps left there from the second screen
     * (opened again they came back there, unseen) move to the main display. Homes stay.
     */
    private static void bringAppsToMain() {
        try {
            Object atm = de.robv.android.xposed.XposedHelpers.callStaticMethod(
                    Class.forName("android.app.ActivityTaskManager"), "getService");
            java.util.List<?> tasks = (java.util.List<?>) de.robv.android.xposed.XposedHelpers.callMethod(
                    atm, "getAllRootTaskInfosOnDisplay", MainModule.SECOND_SCREEN_DISPLAY_ID);
            if (tasks == null) return;
            // Bottom first, so the app on top stays on top.
            java.util.List<Object> order = new java.util.ArrayList<>(tasks);
            java.util.Collections.reverse(order);
            movingApps = true;
            try {
                for (Object info : order) {
                    try {
                        Object config = de.robv.android.xposed.XposedHelpers.getObjectField(info, "configuration");
                        Object wc = de.robv.android.xposed.XposedHelpers.getObjectField(config, "windowConfiguration");
                        int type = (int) de.robv.android.xposed.XposedHelpers.callMethod(wc, "getActivityType");
                        if (type != 1 /* standard: not home, recents, assistant */) continue;
                        int id = de.robv.android.xposed.XposedHelpers.getIntField(info, "taskId");
                        de.robv.android.xposed.XposedHelpers.callMethod(atm, "moveRootTaskToDisplay", id,
                                Display.DEFAULT_DISPLAY);
                    } catch (Throwable t) {
                        ModuleLog.w(t);
                    }
                }
            } finally {
                movingApps = false;
            }
        } catch (Throwable t) {
            ModuleLog.w(t);
        }
    }

    // Set while bringAppsToMain moves tasks (synchronously, on this thread).
    private static volatile boolean movingApps;
    // Until then no transition either: the panel itself moving between displays.
    private static volatile long quietUntil;

    /**
     * The panel moves between display 0 and display 4 (DisplaySwap): LG's transition put a
     * screenshot of the display left behind on the panel, in the panel's own (portrait)
     * orientation, for a frame. None for a moment around the move.
     */
    static void quietTransitions(long ms) {
        quietUntil = android.os.SystemClock.uptimeMillis() + ms;
    }

    /**
     * LG animates every task moved between displays: a screenshot of the old display scaled
     * onto the new one. Ours move between the same panel's two display numbers, so that
     * picture came up stretched and squashed. No animation for our moves (LG's own transition
     * handles an empty set).
     */
    static void install(ClassLoader cl) {
        de.robv.android.xposed.XposedBridge.hookAllMethods(
                de.robv.android.xposed.XposedHelpers.findClass("com.android.server.wm.WindowManagerService", cl),
                "prepareMultiDisplayTransition", new de.robv.android.xposed.XC_MethodHook() {
                    @Override
                    protected void beforeHookedMethod(MethodHookParam param) {
                        if (movingApps || android.os.SystemClock.uptimeMillis() < quietUntil) param.setResult(null);
                    }
                });
        // Unlocking starts: cover the second screen until the swap takes over.
        de.robv.android.xposed.XposedBridge.hookAllMethods(
                de.robv.android.xposed.XposedHelpers.findClass("com.android.server.wm.ActivityTaskManagerService", cl),
                "keyguardGoingAway", new de.robv.android.xposed.XC_MethodHook() {
                    @Override
                    protected void afterHookedMethod(MethodHookParam param) {
                        Handler h = SystemSettings.handler();
                        if (h != null && swapsOnUnlock()) h.postAtFrontOfQueue(() -> showCover(COVER_MAX_MS, MainModule.SECOND_SCREEN_DISPLAY_ID));
                    }
                });
        try {
            installNoPartialWake(cl);
        } catch (Throwable t) {
            ModuleLog.w(t);
        }
        try {
            installBarsFromSecondScreen(cl);
        } catch (Throwable t) {
            ModuleLog.w(t);
        }
    }

    // --- Status bar hidden on the second screen, not on the main one ---

    private static final String FROM_SECOND = "wingcustom_from_second_screen";

    /**
     * An app on the second screen (no status bar there) has its status bar hidden, and keeps
     * it so when its window comes to display 0 in main screen mode: it showed full screen, and
     * the first swipe up only revealed the bars instead of going home. A window that moves from
     * the second screen to the main one is told to show the status bar (what the system tells
     * an app for the keyboard), once on the move and once more on its first request there
     * (it may still be sending the old one). Hidden again after that is the app's own choice
     * (a game, a video).
     */
    private static void installBarsFromSecondScreen(ClassLoader cl) {
        Class<?> window = de.robv.android.xposed.XposedHelpers.findClass("com.android.server.wm.WindowState", cl);
        de.robv.android.xposed.XposedBridge.hookAllMethods(window, "onDisplayChanged",
                new de.robv.android.xposed.XC_MethodHook() {
                    @Override
                    protected void beforeHookedMethod(MethodHookParam param) {
                        param.setObjectExtra("from", de.robv.android.xposed.XposedHelpers.callMethod(param.thisObject, "getDisplayId"));
                    }

                    @Override
                    protected void afterHookedMethod(MethodHookParam param) {
                        if (!DisplaySwap.isSwapped()) return;
                        Object from = param.getObjectExtra("from");
                        int to = (int) de.robv.android.xposed.XposedHelpers.callMethod(param.thisObject, "getDisplayId");
                        if (!Integer.valueOf(MainModule.SECOND_SCREEN_DISPLAY_ID).equals(from)
                                || to != android.view.Display.DEFAULT_DISPLAY) return;
                        de.robv.android.xposed.XposedHelpers.setAdditionalInstanceField(param.thisObject, FROM_SECOND, true);
                        showStatusBar(param.thisObject);
                    }
                });
        de.robv.android.xposed.XposedBridge.hookAllMethods(window, "setRequestedVisibilities",
                new de.robv.android.xposed.XC_MethodHook() {
                    @Override
                    protected void afterHookedMethod(MethodHookParam param) {
                        Object w = param.thisObject;
                        if (!Boolean.TRUE.equals(de.robv.android.xposed.XposedHelpers.getAdditionalInstanceField(w, FROM_SECOND))) return;
                        de.robv.android.xposed.XposedHelpers.removeAdditionalInstanceField(w, FROM_SECOND);
                        showStatusBar(w);
                    }
                });
    }

    private static void showStatusBar(Object window) {
        try {
            de.robv.android.xposed.XposedHelpers.callMethod(window, "showInsets",
                    android.view.WindowInsets.Type.statusBars(), false);
        } catch (Throwable t) {
            ModuleLog.w(t);
        }
    }

    // --- No half-awake second screen ---

    /**
     * LG's always-on display (its fingerprint guide, the AOD tap) "partially wakes" the phone a
     * second after it goes to sleep. With the swivel open LG's second screen power service then
     * lights the second screen's lock screen while the phone stays asleep (not interactive):
     * every touch on it is dropped, the screen looks frozen until it times out again.
     * In main screen mode no partial wake-up happens at all; the power key wakes the phone
     * fully as usual. LG's own method refuses the same way when the folder is closed, the
     * phone is in a pocket or a cover is closed (returns false, nothing changes).
     */
    private static void installNoPartialWake(ClassLoader cl) {
        Class<?> pms = de.robv.android.xposed.XposedHelpers.findClassIfExists(
                "com.android.server.power.PowerManagerServiceEx", cl);
        if (pms == null) return;
        de.robv.android.xposed.XposedBridge.hookAllMethods(pms, "partialWakeUpNoUpdateLocked",
                new de.robv.android.xposed.XC_MethodHook() {
                    @Override
                    protected void beforeHookedMethod(MethodHookParam param) {
                        if (!blocksPartialWake()) return;
                        param.setResult(false);
                        android.util.Log.i("GoDaijishou", "partial wake-up blocked (main screen mode)");
                    }
                });
    }

    static boolean blocksPartialWake() {
        return SystemSettings.original() && !TopMirror.isEscaped();
    }

    // --- Unlock cover ---
    // Locked, main screen mode sits unswapped (LG's lock screen is on the second screen), and
    // the swap follows the unlock: for about half a second the second screen's home showed in
    // between. A plain black window over the second screen hides it. It never stays: the swap
    // takes display 4 (and the window) away, and in any case it goes after COVER_MAX_MS.

    private static final long COVER_MAX_MS = 2500;
    // Covers up now, each with the window manager of its display.
    private static final java.util.List<View> covers = new java.util.ArrayList<>();
    private static final java.util.List<WindowManager> coverWms = new java.util.ArrayList<>();
    private static final Runnable HIDE_COVER = MainScreen::hideCover;

    private static boolean swapsOnUnlock() {
        return SystemSettings.screenMode == AppPreferences.SCREEN_MODE_ORIGINAL && SystemSettings.mainReady
                && SystemSettings.modeEpoch >= 0 && !TopMirror.isEscaped() && !trial && kept() >= 0
                && !DisplaySwap.isSwapped();
    }

    /** Black covers over the given displays (ones that do not exist are skipped). */
    private static void showCover(long maxMs, int... displayIds) {
        hideCover();
        Context system = SystemSettings.context();
        android.hardware.display.DisplayManager dm = system.getSystemService(android.hardware.display.DisplayManager.class);
        for (int displayId : displayIds) {
            try {
                Display target = dm.getDisplay(displayId);
                if (target == null) continue;
                Context ui = system.createDisplayContext(target);
                View v = new View(ui);
                v.setBackgroundColor(0xFF000000);
                // TYPE_VOLUME_OVERLAY (hidden), as the battery bar: a system error window
                // never showed on display 4.
                WindowManager.LayoutParams lp = new WindowManager.LayoutParams(
                        WindowManager.LayoutParams.MATCH_PARENT, WindowManager.LayoutParams.MATCH_PARENT,
                        2020,
                        WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE
                                | WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE
                                | WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN
                                | WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS,
                        android.graphics.PixelFormat.OPAQUE);
                lp.setTitle("WingCustom:UnlockCover");
                lp.layoutInDisplayCutoutMode = WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_ALWAYS;
                lp.windowAnimations = 0;
                WindowManager wm = ui.getSystemService(WindowManager.class);
                wm.addView(v, lp);
                covers.add(v);
                coverWms.add(wm);
            } catch (Throwable t) {
                ModuleLog.w(t);
            }
        }
        if (!covers.isEmpty()) SystemSettings.handler().postDelayed(HIDE_COVER, maxMs);
    }

    private static void hideCover() {
        Handler h = SystemSettings.handler();
        if (h != null) h.removeCallbacks(HIDE_COVER);
        for (int i = 0; i < covers.size(); i++) {
            try {
                coverWms.get(i).removeViewImmediate(covers.get(i));
            } catch (Throwable t) {
                ModuleLog.w(t); // gone with its display already
            }
        }
        covers.clear();
        coverWms.clear();
    }

    /**
     * Unswapped while asleep, the second screen's panel comes back as display 4 in a display
     * group of its own, and a new group starts awake: its lock screen shone while the phone
     * slept, so its touches were dropped. Once the layout settled, every group sleeps again
     * (goToSleep sleeps them all); the power key wakes the phone as usual.
     */
    private static boolean presentWatched;

    private static void watchPresent() {
        if (presentWatched) return;
        Context c = SystemSettings.context();
        Handler h = SystemSettings.handler();
        if (c == null || h == null) return;
        try {
            c.registerReceiver(new android.content.BroadcastReceiver() {
                @Override
                public void onReceive(Context context, android.content.Intent intent) {
                    apply();
                }
            }, filter(android.content.Intent.ACTION_USER_PRESENT, android.content.Intent.ACTION_SCREEN_OFF,
                    android.content.Intent.ACTION_SCREEN_ON),
                    null, h);
            presentWatched = true;
        } catch (Throwable t) {
            ModuleLog.w(t);
        }
    }

    private static android.content.IntentFilter filter(String... actions) {
        android.content.IntentFilter f = new android.content.IntentFilter();
        for (String a : actions) f.addAction(a);
        return f;
    }

    private static boolean interactive() {
        try {
            android.os.PowerManager pm = SystemSettings.context().getSystemService(android.os.PowerManager.class);
            return pm != null && pm.isInteractive();
        } catch (Throwable t) {
            return false;
        }
    }

    private static boolean present() {
        try {
            Context c = SystemSettings.context();
            android.app.KeyguardManager km = c.getSystemService(android.app.KeyguardManager.class);
            android.os.PowerManager pm = c.getSystemService(android.os.PowerManager.class);
            return km != null && !km.isKeyguardLocked() && pm != null && pm.isInteractive();
        } catch (Throwable t) {
            ModuleLog.w(t);
            return false;
        }
    }

    /**
     * Volume up + down: main screen <-> second screen. Swapped (or asking), back to the
     * second screen and kept there. Otherwise, with main screen mode applied in the app and
     * its hooks loaded, the main screen again: at once if it was kept before, else the
     * "keep?" card. Pressing again always goes back, so a switch can never strand anyone.
     */
    static void toggle() {
        if (DisplaySwap.isSwapped() || trial) {
            escape();
            return;
        }
        if (!present()) {
            // Asleep or locked the swap is always undone, so flip the picked mode instead; it
            // shows on unlock. A buzz tells which: one tick second screen, two main screen.
            boolean wantMain = SystemSettings.screenMode == AppPreferences.SCREEN_MODE_ORIGINAL
                    && !TopMirror.isEscaped();
            if (wantMain) {
                escape();
                buzz(1);
            } else if (SystemSettings.mainReady) {
                switchOn();
                buzz(2);
            }
            return;
        }
        if (SystemSettings.mainReady) {
            switchOn();
        }
    }

    // The switch waits for the cover's frame to reach the screen, at most COVER_WAIT_MS; the
    // new display's first frames are up by the time the cover goes (SWAP_COVER_MS).
    private static final long COVER_WAIT_MS = 300, SWAP_COVER_MS = 400;

    /**
     * The keys' switch. When the panel moves between display 0 and display 4 it shows a frame
     * or two in its own (portrait) orientation, cut off: of the display it leaves, or (to the
     * main screen) of display 0 as the top panel had it. A black cover goes over both
     * displays, and the switch waits until every cover is on screen, so those frames are
     * black. The covers go shortly after the switch (display 4's with it when swapped).
     * Locked or asleep it switches as before (no cover).
     */
    static void toggleCovered() {
        Handler h = SystemSettings.handler();
        if (h == null || !present()) {
            toggle();
            return;
        }
        boolean was = DisplaySwap.isSwapped();
        boolean[] done = {false};
        Runnable go = () -> {
            if (done[0]) return;
            done[0] = true;
            toggle();
            if (DisplaySwap.isSwapped() == was) {
                hideCover(); // nothing moved (the "keep?" card): no black
            } else {
                h.removeCallbacks(HIDE_COVER);
                h.postDelayed(HIDE_COVER, SWAP_COVER_MS);
            }
        };
        showCover(COVER_MAX_MS, Display.DEFAULT_DISPLAY, MainModule.SECOND_SCREEN_DISPLAY_ID);
        int[] waiting = {covers.size()};
        for (View v : covers) {
            // Called once that cover's first frame is presented (not just drawn).
            v.getViewTreeObserver().registerFrameCommitCallback(() -> h.post(() -> {
                if (--waiting[0] <= 0) go.run();
            }));
        }
        h.postDelayed(go, covers.isEmpty() ? 0 : COVER_WAIT_MS);
    }

    private static void switchOn() {
        if (SystemSettings.screenMode == AppPreferences.SCREEN_MODE_ORIGINAL) {
            TopMirror.clearEscape(); // picked but left with the keys: on again
            apply();
        } else {
            tellApp(AppPreferences.SCREEN_MODE_ORIGINAL); // its settings come back here
        }
    }

    // Pulses felt in a pocket: LG's vibrator plays its click and double click alike, so plain
    // pulses (80 ms each, 150 ms apart), as hardware feedback like the keys' own.
    private static void buzz(int pulses) {
        try {
            android.os.Vibrator v = SystemSettings.context().getSystemService(android.os.Vibrator.class);
            if (v == null) return;
            long[] timings = pulses == 1 ? new long[] {0, 80} : new long[] {0, 80, 150, 80};
            android.os.VibrationEffect effect = android.os.VibrationEffect.createWaveform(timings, -1);
            if (android.os.Build.VERSION.SDK_INT >= 33) {
                v.vibrate(effect, android.os.VibrationAttributes.createForUsage(
                        android.os.VibrationAttributes.USAGE_HARDWARE_FEEDBACK));
            } else {
                v.vibrate(effect);
            }
        } catch (Throwable t) {
            ModuleLog.w(t);
        }
    }

    /** Back to the second screen at once (keys, a trial nobody kept); the app follows. */
    static void escape() {
        endTrial();
        ownSwap = false;
        TopMirror.escape(); // holds even if the app never hears of it
        DisplaySwap.restoreNow();
        tellApp(AppPreferences.SCREEN_MODE_SECOND);
    }

    // The app keeps the mode (and shows it): it writes it and its provider tells us back.
    private static void tellApp(int mode) {
        try {
            Context c = SystemSettings.context();
            android.content.Intent i = new android.content.Intent(ModeSwitchReceiver.ACTION)
                    .setClassName(BuildConfig.APPLICATION_ID, ModeSwitchReceiver.class.getName())
                    .putExtra(ModeSwitchReceiver.EXTRA_MODE, mode)
                    .addFlags(android.content.Intent.FLAG_RECEIVER_FOREGROUND);
            c.sendBroadcastAsUser(i, android.os.Process.myUserHandle());
        } catch (Throwable t) {
            ModuleLog.w(t);
        }
    }

    // --- Trial ---

    private static void startTrial(int epoch) {
        Handler h = SystemSettings.handler();
        if (h == null) return;
        DisplaySwap.set(true);
        bringAppsLater();
        ownSwap = DisplaySwap.isSwapped();
        syncBrightness();
        if (!DisplaySwap.isSwapped()) { // could not swap: stay as it is, don't try again
            TopMirror.escape();
            return;
        }
        trial = true;
        trialEpoch = epoch;
        secondsLeft = CONFIRM_SECONDS;
        // Ask once the swapped display has settled.
        h.postDelayed(MainScreen::showConfirm, 1_000);
        h.postDelayed(TICK, 1_000);
    }

    private static final Runnable TICK = new Runnable() {
        @Override
        public void run() {
            if (!trial) return;
            if (--secondsLeft <= 0) {
                // Nobody kept it (the screen or its touches may not work): back as it was.
                escape();
                return;
            }
            updateCount();
            Handler h = SystemSettings.handler();
            if (h != null) h.postDelayed(this, 1_000);
        }
    };

    private static void keep() {
        if (!trial) return;
        try {
            Settings.Global.putInt(SystemSettings.context().getContentResolver(), KEY_KEPT, trialEpoch);
        } catch (Throwable t) {
            ModuleLog.w(t);
            return; // not stored: let the trial run out rather than swap at every boot unasked
        }
        // LG's home goes 4x4 once, the next time it shows (TopHomeHook); never again after.
        try {
            android.content.ContentResolver r = SystemSettings.context().getContentResolver();
            if (Settings.Global.getInt(r, TopHomeHook.KEY_GRID_ONCE, 0) == 0) {
                Settings.Global.putInt(r, TopHomeHook.KEY_GRID_ONCE, 1);
            }
        } catch (Throwable t) {
            ModuleLog.w(t);
        }
        endTrial();
    }

    private static void endTrial() {
        trial = false;
        Handler h = SystemSettings.handler();
        if (h != null) h.removeCallbacks(TICK);
        removeConfirm();
    }

    private static int kept() {
        try {
            return Settings.Global.getInt(SystemSettings.context().getContentResolver(), KEY_KEPT, -1);
        } catch (Throwable t) {
            ModuleLog.w(t);
            return -1;
        }
    }

    // --- The "keep this screen?" card, on the swapped main display ---

    private static void showConfirm() {
        if (!trial || confirmView != null) return;
        try {
            Context system = SystemSettings.context();
            Display main = system.getSystemService(DisplayManager.class).getDisplay(Display.DEFAULT_DISPLAY);
            if (main == null) return;
            Context ui = system.createDisplayContext(main);
            Resources app = system.createPackageContext(BuildConfig.APPLICATION_ID, 0).getResources();
            float dp = ui.getResources().getDisplayMetrics().density;

            LinearLayout card = new LinearLayout(ui);
            card.setOrientation(LinearLayout.VERTICAL);
            card.setGravity(Gravity.CENTER_HORIZONTAL);
            int pad = Math.round(24 * dp);
            card.setPadding(pad, pad, pad, pad);
            // Fixed width: the countdown changing its text never resizes (and redraws) the window.
            card.setMinimumWidth(Math.round(300 * dp));
            GradientDrawable bg = new GradientDrawable();
            bg.setColor(0xF0202024);
            bg.setCornerRadius(24 * dp);
            card.setBackground(bg);

            TextView title = new TextView(ui);
            title.setText(text(app, "main_confirm_title", "Keep this as the main screen?"));
            title.setTextColor(Color.WHITE);
            title.setTextSize(TypedValue.COMPLEX_UNIT_SP, 18);
            title.setGravity(Gravity.CENTER);
            card.addView(title);

            countView = new TextView(ui);
            countView.setTextColor(0xFFBBBBBB);
            countView.setTextSize(TypedValue.COMPLEX_UNIT_SP, 14);
            countView.setGravity(Gravity.CENTER);
            LinearLayout.LayoutParams cl = new LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT);
            cl.topMargin = Math.round(8 * dp);
            card.addView(countView, cl);

            TextView keep = new TextView(ui);
            keep.setText(text(app, "main_confirm_keep", "Keep"));
            keep.setTextColor(0xFF1A1A1A);
            keep.setTextSize(TypedValue.COMPLEX_UNIT_SP, 18);
            keep.setGravity(Gravity.CENTER);
            int kp = Math.round(14 * dp);
            keep.setPadding(kp * 3, kp, kp * 3, kp);
            GradientDrawable kb = new GradientDrawable();
            kb.setColor(0xFFFFC83D);
            kb.setCornerRadius(16 * dp);
            keep.setBackground(kb);
            keep.setOnClickListener(v -> keep());
            LinearLayout.LayoutParams kl = new LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT);
            kl.topMargin = Math.round(20 * dp);
            card.addView(keep, kl);

            WindowManager.LayoutParams lp = new WindowManager.LayoutParams(
                    WindowManager.LayoutParams.WRAP_CONTENT,
                    WindowManager.LayoutParams.WRAP_CONTENT,
                    // System error layer: above the lock screen and apps, and touchable (a secure
                    // system overlay is forced untouchable: taps went through to the app below).
                    2010 /* TYPE_SYSTEM_ERROR */,
                    WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE
                            | WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL,
                    PixelFormat.TRANSLUCENT);
            lp.gravity = Gravity.CENTER;
            lp.setTitle("GoDaijishou:MainConfirm");

            countText = text(app, "main_confirm_count", "Back to the second screen in %d s");
            confirmWm = ui.getSystemService(WindowManager.class);
            confirmView = card;
            updateCount();
            confirmWm.addView(card, lp);
        } catch (Throwable t) {
            ModuleLog.w(t);
            confirmView = null; // the trial still runs out and restores
        }
    }

    private static String countText = "Back to the second screen in %d s";

    private static void updateCount() {
        TextView v = countView;
        if (v == null) return;
        try {
            v.setText(String.format(countText, secondsLeft));
        } catch (RuntimeException e) {
            v.setText(String.valueOf(secondsLeft));
        }
    }

    private static void removeConfirm() {
        LinearLayout v = confirmView;
        confirmView = null;
        countView = null;
        if (v == null || confirmWm == null) return;
        try {
            confirmWm.removeView(v);
        } catch (RuntimeException ignored) {
        }
    }

    private static String text(Resources r, String name, String fallback) {
        try {
            int id = r.getIdentifier(name, "string", BuildConfig.APPLICATION_ID);
            return id == 0 ? fallback : r.getString(id);
        } catch (RuntimeException e) {
            return fallback;
        }
    }
}
