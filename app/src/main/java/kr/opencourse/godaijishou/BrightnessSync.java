package kr.opencourse.godaijishou;

import android.content.ContentResolver;
import android.content.Context;
import android.database.ContentObserver;
import android.net.Uri;
import android.os.Handler;
import android.provider.Settings;

/**
 * One brightness for both panels (system_server).
 *
 * The bottom panel has two writers: the main display's brightness (Settings.System
 * screen_brightness, put on the panel while it is display 0 in main screen mode) and LG's
 * second screen controller (SecondaryDisplayPowerController, Settings.Secure
 * screen_brightness_for_coverdisplay, which keeps driving that panel through its own HAL). With
 * two values the panel jumped between them. Whichever slider moves, the other setting is given
 * the same value (both 1..255), so both writers agree. Equal values are not written again, so
 * the two observers never chase each other.
 */
final class BrightnessSync {

    static final String MAIN = Settings.System.SCREEN_BRIGHTNESS;
    static final String COVER = "screen_brightness_for_coverdisplay";

    private static boolean watching;

    private BrightnessSync() {
    }

    static void watch() {
        if (watching) return;
        Context c = SystemSettings.context();
        Handler h = SystemSettings.handler();
        if (c == null || h == null) return;
        watching = true;
        ContentResolver cr = c.getContentResolver();
        try {
            Uri mainUri = Settings.System.getUriFor(MAIN);
            Uri coverUri = Settings.Secure.getUriFor(COVER);
            ContentObserver o = new ContentObserver(h) {
                @Override
                public void onChange(boolean selfChange, Uri uri) {
                    if (mainUri.equals(uri)) copy(cr, true);
                    else if (coverUri.equals(uri)) copy(cr, false);
                }
            };
            cr.registerContentObserver(mainUri, false, o);
            cr.registerContentObserver(coverUri, false, o);
            copy(cr, true); // the main slider's value from the start
        } catch (Throwable t) {
            ModuleLog.w(t);
            watching = false;
        }
    }

    private static void copy(ContentResolver cr, boolean fromMain) {
        try {
            int main = Settings.System.getInt(cr, MAIN, -1);
            int cover = Settings.Secure.getInt(cr, COVER, -1);
            int value = fromMain ? main : cover;
            if (value < 1 || value > 255 || main == cover) return;
            if (fromMain) Settings.Secure.putInt(cr, COVER, value);
            else Settings.System.putInt(cr, MAIN, value);
        } catch (Throwable t) {
            ModuleLog.w(t);
        }
    }
}
