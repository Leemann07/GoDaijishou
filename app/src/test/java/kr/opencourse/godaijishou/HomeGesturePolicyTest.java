package kr.opencourse.godaijishou;

import org.junit.Test;

import static org.junit.Assert.*;

public class HomeGesturePolicyTest {
    @Test public void thirdPartyHomeCanUseFallback() {
        for (String home : new String[]{"com.teslacoilsw.launcher", "com.magneticchen.daijishou",
                "org.es_de.frontend", "org.pegasus_frontend.android"}) {
            assertTrue(home, HomeGesturePolicy.allows(home, true, false, true));
        }
    }

    @Test public void lgHomeKeepsNativeHandler() {
        assertFalse(HomeGesturePolicy.allows("com.lge.launcher3", true, false, true));
    }

    @Test public void unknownOrUnselectedHomeNeverEnablesFallback() {
        for (String home : new String[]{null, "", "?", "android"}) {
            assertFalse(HomeGesturePolicy.allows(home, true, false, true));
        }
    }

    @Test public void buttonNavigationNeverEnablesFallback() {
        assertFalse(HomeGesturePolicy.allows("launcher", false, false, true));
    }

    @Test public void lockScreenNeverEnablesFallback() {
        assertFalse(HomeGesturePolicy.allows("launcher", true, true, true));
    }

    @Test public void unknownLockStateNeverEnablesFallback() {
        assertFalse(HomeGesturePolicy.allows("launcher", true, null, true));
    }

    @Test public void sleepingDisplayNeverEnablesFallback() {
        assertFalse(HomeGesturePolicy.allows("launcher", true, false, false));
    }

    @Test public void unknownPowerStateNeverEnablesFallback() {
        assertFalse(HomeGesturePolicy.allows("launcher", true, false, null));
    }
}
