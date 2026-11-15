package kr.opencourse.godaijishou;

import android.graphics.Bitmap;
import android.view.Surface;

import de.robv.android.xposed.XC_MethodHook;
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
    }
}
