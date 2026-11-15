package kr.opencourse.godaijishou;

import android.graphics.Bitmap;
import android.view.Surface;
import android.widget.ImageView;

import de.robv.android.xposed.XC_MethodHook;
import de.robv.android.xposed.XposedBridge;
import de.robv.android.xposed.XposedHelpers;

/**
 * Lock screen wallpaper on the second screen (runs in SystemUI).
 *
 * In swivel mode LG turns the lock wallpaper by the main display's rotation
 * (LockscreenWallpaper.rotateBitmapForSwivel): 0 -> 270 degrees, 90 -> 180, 180 -> 90,
 * 270 -> 0, so the picture lines up with a physically turned main screen. Here the main
 * display's rotation is not a physical one: the main panel is off (portrait 0) or rotation
 * forcing set it (landscape 90), and LG turned the picture sideways or upside down. The lock
 * window is laid out in the second screen's own frame, so the picture is never turned:
 * every value is answered as 270 (0 degrees).
 *
 * LG's swivel lock (CoverLock) also stretches the picture to its window, made for the
 * swivel's one landscape shape: a portrait picture on the landscape panel showed stretched.
 * It is scaled to cover the window instead, keeping its proportions.
 */
final class LockWallpaperHook {

    private LockWallpaperHook() {
    }

    static void install(ClassLoader cl) {
        XposedHelpers.findAndHookMethod("com.android.systemui.statusbar.phone.LockscreenWallpaper", cl,
                "rotateBitmapForSwivel", Bitmap.class, int.class, new XC_MethodHook() {
                    @Override
                    protected void beforeHookedMethod(MethodHookParam param) {
                        param.args[1] = Surface.ROTATION_270;
                    }
                });
        Class<?> coverLock = XposedHelpers.findClassIfExists("com.lge.systemui.coverpanel.CoverLock", cl);
        if (coverLock == null) return;
        XposedBridge.hookAllMethods(coverLock, "initInflate", new XC_MethodHook() {
            @Override
            protected void afterHookedMethod(MethodHookParam param) {
                Object view = XposedHelpers.getObjectField(param.thisObject, "mLockView");
                if (view instanceof ImageView) ((ImageView) view).setScaleType(ImageView.ScaleType.CENTER_CROP);
            }
        });
    }
}
