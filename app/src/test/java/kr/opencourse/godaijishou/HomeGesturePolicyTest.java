package kr.opencourse.godaijishou;

import org.junit.Test;

import static org.junit.Assert.*;

public class HomeGesturePolicyTest {
    @Test public void quickstepDownEnablesFallback() {
        assertTrue(HomeGesturePolicy.allows(false, true, false, true));
    }

    // Any home app: LG quickstep finishes the swipe itself (FallbackSwipeHandler for a
    // third-party home). A second navigation from us raced it (HOME injected mid-animation).
    @Test public void connectedQuickstepNeverEnablesFallback() {
        assertFalse(HomeGesturePolicy.allows(true, true, false, true));
    }

    @Test public void unknownQuickstepStateNeverEnablesFallback() {
        assertFalse(HomeGesturePolicy.allows(null, true, false, true));
    }

    @Test public void buttonNavigationNeverEnablesFallback() {
        assertFalse(HomeGesturePolicy.allows(false, false, false, true));
    }

    @Test public void lockScreenNeverEnablesFallback() {
        assertFalse(HomeGesturePolicy.allows(false, true, true, true));
    }

    @Test public void unknownLockStateNeverEnablesFallback() {
        assertFalse(HomeGesturePolicy.allows(false, true, null, true));
    }

    @Test public void sleepingDisplayNeverEnablesFallback() {
        assertFalse(HomeGesturePolicy.allows(false, true, false, false));
    }

    @Test public void unknownPowerStateNeverEnablesFallback() {
        assertFalse(HomeGesturePolicy.allows(false, true, false, null));
    }
}
