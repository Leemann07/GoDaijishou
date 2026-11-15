package kr.opencourse.godaijishou;

/** Unknown system state must not turn an ordinary touch/unlock into a navigation key. */
final class HomeGesturePolicy {
    private HomeGesturePolicy() { }

    static boolean allows(String homePackage, boolean gestural, Boolean locked, Boolean interactive) {
        return gestural && Boolean.FALSE.equals(locked) && Boolean.TRUE.equals(interactive)
                && homePackage != null && !homePackage.isEmpty() && !"?".equals(homePackage)
                && !"android".equals(homePackage) && !"com.lge.launcher3".equals(homePackage);
    }
}
