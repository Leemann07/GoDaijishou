package kr.opencourse.godaijishou;

/**
 * The system's gesture engine (LG quickstep, in com.lge.launcher3) owns every bottom swipe
 * whatever the home app is. Ours may only act while that engine is not connected to SystemUI
 * (its process dead or restarting); otherwise both would navigate and race. Unknown state of
 * any kind leaves the gesture to the system.
 */
final class HomeGesturePolicy {
    private HomeGesturePolicy() { }

    static boolean allows(Boolean quickstepConnected, boolean gestural, Boolean locked, Boolean interactive) {
        return Boolean.FALSE.equals(quickstepConnected)
                && gestural && Boolean.FALSE.equals(locked) && Boolean.TRUE.equals(interactive);
    }
}
