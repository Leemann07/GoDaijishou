package kr.opencourse.godaijishou;

/** Motion/ownership state only; the caller serializes access and supplies release velocity. */
final class HomeGestureTracker {
    enum Result { NONE, HOME, RECENTS }

    private static final long HOLD_MS = 200;
    private Object owner;
    // Keep this after UP/CANCEL: a delayed copy of DOWN must not restart a finished swipe.
    private long downTime = -1;
    private int deviceId;
    private float downX, downY, minRise, slop, flingSpeed;
    private float anchorX, anchorY, peakRise;
    private long stillSince = -1;
    private long lastEventTime;

    /** True for a new stream, including one outside the gesture start region. */
    boolean start(Object receiver, long down, int device, float x, float y,
                  float width, float height, float startZone, float rise, float touchSlop,
                  float flingThreshold) {
        if (down <= downTime) return false;
        downTime = down;
        lastEventTime = down;
        deviceId = device;
        owner = finite(x) && finite(y) && finite(width) && finite(height)
                && finite(startZone) && finite(rise) && finite(touchSlop) && finite(flingThreshold)
                && width > 0 && height > 0 && startZone > 0 && rise > 0
                && touchSlop >= 0 && flingThreshold > 0
                && x >= 0 && x < width && y >= Math.max(0, height - startZone) && y < height
                ? receiver : null;
        downX = x;
        downY = y;
        minRise = rise;
        slop = touchSlop;
        flingSpeed = flingThreshold;
        peakRise = 0;
        stillSince = -1;
        return true;
    }

    boolean owns(Object receiver, long down, int device) {
        return owner != null && owner == receiver && downTime == down && deviceId == device;
    }

    boolean isLatest(long down, int device) {
        return downTime == down && deviceId == device;
    }

    boolean accepts(Object receiver, long down, int device, long time) {
        return owns(receiver, down, device) && time >= lastEventTime;
    }

    boolean move(Object receiver, long down, int device, float x, float y, long time) {
        if (!accepts(receiver, down, device, time)) return false;
        if (!finite(x) || !finite(y)) {
            cancel(receiver, down, device);
            return false;
        }
        update(x, y, time);
        return true;
    }

    private void update(float x, float y, long time) {
        lastEventTime = time;
        float rise = downY - y;
        peakRise = Math.max(peakRise, rise);
        if (rise < minRise) {
            stillSince = -1;
        } else if (stillSince < 0 || Math.hypot(x - anchorX, y - anchorY) > slop) {
            // Crossing a distance threshold isn't a hold. The finger must actually settle.
            anchorX = x;
            anchorY = y;
            stillSince = time;
        }
    }

    Result finish(Object receiver, long down, int device,
                  float x, float y, long time, float upwardSpeed) {
        if (!accepts(receiver, down, device, time)) return Result.NONE;
        if (!finite(x) || !finite(y) || !finite(upwardSpeed)) {
            cancel(receiver, down, device);
            return Result.NONE;
        }
        update(x, y, time);
        owner = null;
        float rise = downY - y;
        if (rise < minRise || Math.abs(x - downX) > rise
                || peakRise - rise > Math.max(minRise / 2f, 2f * slop)
                || upwardSpeed <= -flingSpeed) return Result.NONE;
        boolean held = stillSince >= 0 && time - stillSince >= HOLD_MS;
        return upwardSpeed >= flingSpeed || !held ? Result.HOME : Result.RECENTS;
    }

    void cancel(Object receiver, long down, int device) {
        if (owns(receiver, down, device)) owner = null;
    }

    private static boolean finite(float value) {
        return !Float.isNaN(value) && !Float.isInfinite(value);
    }
}
