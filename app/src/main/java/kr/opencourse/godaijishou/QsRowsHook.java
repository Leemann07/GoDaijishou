package kr.opencourse.godaijishou;

import android.content.res.Configuration;
import android.view.View;
import android.view.ViewGroup;

import de.robv.android.xposed.XC_MethodHook;
import de.robv.android.xposed.XposedBridge;
import de.robv.android.xposed.XposedHelpers;

/**
 * Quick settings fitted to a short screen (scope: com.android.systemui).
 *
 * LG's quick settings take their row and column counts from resources made for the tall main
 * screen (TileLayout.updateMaxRows, updateResources). On the second screen's panel as the main
 * display (main screen mode) those did not fit:
 * - Portrait (about 520 dp tall): the rows, the brightness slider and the media row ran into
 *   each other. One row of full-height tiles; the other tiles go on the next pages.
 * - Landscape (1240 px, about 520 dp wide): the top screen's eight columns left each label a
 *   sliver ("Bluetoo/th"). Six columns; LG fits the rows to the height itself. The brightness
 *   and volume sliders keep the top screen's side margins too, about 300 px of each slider's
 *   width, and their bars were a finger wide: the margins are cut down.
 */
final class QsRowsHook {

    private static final int SHORT_SCREEN_DP = 600;
    private static final int NARROW_LANDSCAPE_DP = 700;
    private static final int SHORT_ROWS = 1;
    private static final int LANDSCAPE_COLUMNS = 6;

    /** Slider margins on the narrow landscape panel, in px. */
    private static final int SLIDER_OUTER_MARGIN = 24;
    private static final int SLIDER_INNER_MARGIN = 10;
    private static final int KEEP = Integer.MIN_VALUE;

    private static final String[] SLIDERS = {"brightness_slider", "volumeslider"};
    private static final String[] INNER_STARTS = {"brightness_icon_info_container", "volume_icon"};
    private static final String[] INNER_ENDS = {"autocheck_container", "volume_settings_button"};

    private QsRowsHook() {
    }

    static void install(ClassLoader cl) {
        Class<?> tileLayout = XposedHelpers.findClass("com.android.systemui.qs.TileLayout", cl);
        XposedBridge.hookAllMethods(tileLayout, "updateMaxRows", new XC_MethodHook() {
            @Override
            protected void afterHookedMethod(MethodHookParam param) {
                try {
                    View v = (View) param.thisObject;
                    if (!isShort(v) || isLandscape(v)) return;
                    int rows = XposedHelpers.getIntField(v, "mRows");
                    if (rows <= SHORT_ROWS) return;
                    XposedHelpers.setIntField(v, "mRows", SHORT_ROWS);
                    param.setResult(true); // the row count changed: LG lays the pages out again
                } catch (Throwable t) {
                    ModuleLog.w(t);
                }
            }
        });
        XposedBridge.hookAllMethods(tileLayout, "updateResources", new XC_MethodHook() {
            @Override
            protected void afterHookedMethod(MethodHookParam param) {
                try {
                    View v = (View) param.thisObject;
                    if (!isNarrowLandscape(v)) return;
                    if (XposedHelpers.getIntField(v, "mResourceColumns") <= LANDSCAPE_COLUMNS
                            && XposedHelpers.getIntField(v, "mColumns") <= LANDSCAPE_COLUMNS) return;
                    XposedHelpers.setIntField(v, "mResourceColumns", LANDSCAPE_COLUMNS);
                    XposedHelpers.callMethod(v, "updateColumns");
                    v.requestLayout();
                    param.setResult(true);
                } catch (Throwable t) {
                    ModuleLog.w(t);
                }
            }
        });
        // LG grows the tiles while the panel opens (changeTileHeight); on a short screen it
        // never opens all the way, so the cells stayed short and the second row sat on the
        // first one's labels. Never shorter than a tile is.
        XposedBridge.hookAllMethods(tileLayout, "changeTileHeight", new XC_MethodHook() {
            @Override
            protected void beforeHookedMethod(MethodHookParam param) {
                try {
                    View v = (View) param.thisObject;
                    if (!isShort(v) || param.args.length == 0 || !(param.args[0] instanceof Integer)) return;
                    int resId = XposedHelpers.getIntField(v, "mCellHeightResId");
                    if (resId == 0) return;
                    int natural = v.getResources().getDimensionPixelSize(resId);
                    if ((Integer) param.args[0] < natural) param.args[0] = natural;
                } catch (Throwable t) {
                    ModuleLog.w(t);
                }
            }
        });
        // Slider margins, set before the panel measures them (fields only: no extra layout).
        Class<?> container = XposedHelpers.findClassIfExists("com.android.systemui.qs.QSContainerImpl", cl);
        if (container != null) {
            XposedBridge.hookAllMethods(container, "onMeasure", new XC_MethodHook() {
                @Override
                protected void beforeHookedMethod(MethodHookParam param) {
                    try {
                        fitSliders((View) param.thisObject);
                    } catch (Throwable t) {
                        ModuleLog.w(t);
                    }
                }
            });
        }
    }

    private static void fitSliders(View root) {
        boolean narrow = isNarrowLandscape(root);
        for (int i = 0; i < SLIDERS.length; i++) {
            margins(root, SLIDERS[i], narrow, SLIDER_OUTER_MARGIN, SLIDER_OUTER_MARGIN);
            margins(root, INNER_STARTS[i], narrow, SLIDER_INNER_MARGIN, KEEP);
            margins(root, INNER_ENDS[i], narrow, KEEP, SLIDER_INNER_MARGIN);
        }
        // The "auto" label is centered under the brightness checkbox by its own width and the
        // parent's end edge: it follows the checkbox's new margin.
        View box = find(root, "autocheck_container"), label = find(root, "autocheck_text");
        if (box != null && label != null && box.getLayoutParams().width > 0 && label.getLayoutParams().width > 0) {
            int offset = SLIDER_INNER_MARGIN + (box.getLayoutParams().width - label.getLayoutParams().width) / 2;
            margins(root, "autocheck_text", narrow, KEEP, offset);
        }
    }

    private static View find(View root, String name) {
        int id = root.getResources().getIdentifier(name, "id", "com.android.systemui");
        return id == 0 ? null : root.findViewById(id);
    }

    // Narrow: the given margins (KEEP keeps that side); otherwise LG's own, saved the first time.
    private static void margins(View root, String name, boolean narrow, int start, int end) {
        View v = find(root, name);
        if (v == null || !(v.getLayoutParams() instanceof ViewGroup.MarginLayoutParams)) return;
        ViewGroup.MarginLayoutParams lp = (ViewGroup.MarginLayoutParams) v.getLayoutParams();
        int[] own = (int[]) XposedHelpers.getAdditionalInstanceField(v, "lgMargins");
        if (own == null) {
            own = new int[] {lp.leftMargin, lp.rightMargin};
            XposedHelpers.setAdditionalInstanceField(v, "lgMargins", own);
        }
        int left = narrow && start != KEEP ? start : own[0];
        int right = narrow && end != KEEP ? end : own[1];
        if (lp.leftMargin == left && lp.rightMargin == right) return;
        lp.leftMargin = left;
        lp.rightMargin = right;
        lp.setMarginStart(left);
        lp.setMarginEnd(right);
    }

    private static boolean isShort(View v) {
        int h = v.getResources().getConfiguration().screenHeightDp;
        return h > 0 && h < SHORT_SCREEN_DP;
    }

    private static boolean isLandscape(View v) {
        return v.getResources().getConfiguration().orientation == Configuration.ORIENTATION_LANDSCAPE;
    }

    // The second screen's panel as the main display, turned: 1240 px wide.
    private static boolean isNarrowLandscape(View v) {
        Configuration c = v.getResources().getConfiguration();
        return c.orientation == Configuration.ORIENTATION_LANDSCAPE
                && c.screenWidthDp > 0 && c.screenWidthDp < NARROW_LANDSCAPE_DP;
    }
}
