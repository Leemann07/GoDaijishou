package kr.opencourse.godaijishou;

import org.junit.Test;

import java.util.HashSet;
import java.util.Random;
import java.util.Set;

import static kr.opencourse.godaijishou.HomeGestureTracker.Result.*;
import static org.junit.Assert.*;

public class HomeGestureTrackerTest {
    private final HomeGestureTracker tracker = new HomeGestureTracker();
    private final Object first = new Object(), peer = new Object();
    private long gestureDown = 100;

    private void start(long down, float x, float y) {
        gestureDown = down;
        assertTrue(tracker.start(first, down, 1, x, y,
                1080, 1240, 72, 130, 24, 1050));
    }

    private void move(float x, float y, long time) {
        tracker.move(first, gestureDown, 1, x, y, time);
    }

    private HomeGestureTracker.Result finish(float x, float y, long time, float speed) {
        return tracker.finish(first, gestureDown, 1, x, y, time, speed);
    }

    @Test public void peerCannotOwnOrTerminateSwipe() {
        start(100, 540, 1230);
        assertFalse(tracker.start(peer, 100, 1, 540, 1230,
                1080, 1240, 72, 130, 24, 1050));
        assertFalse(tracker.owns(peer, 100, 1));
        assertTrue(tracker.owns(first, 100, 1));
        move(540, 900, 200);
        assertEquals(HOME, finish(540, 700, 300, 1600));
        assertFalse(tracker.owns(first, 100, 1));
    }

    @Test public void delayedDownCannotReplayFinishedGesture() {
        start(100, 540, 1230);
        assertEquals(HOME, finish(540, 900, 200, 1500));
        assertFalse(tracker.start(peer, 100, 1, 540, 1230,
                1080, 1240, 72, 130, 24, 1050));
    }

    @Test public void staleEventsCannotTouchNewSwipe() {
        start(100, 540, 1230);
        start(500, 540, 1230);
        assertFalse(tracker.owns(first, 100, 1));
        assertFalse(tracker.owns(peer, 500, 1));
        assertFalse(tracker.owns(first, 500, 2));
        assertTrue(tracker.owns(first, 500, 1));
        assertFalse(tracker.start(peer, 100, 1, 540, 1230,
                1080, 1240, 72, 130, 24, 1050));
        assertTrue(tracker.owns(first, 500, 1));
    }

    @Test public void cancelAllowsNextSwipeButNotDelayedDuplicate() {
        start(100, 540, 1230);
        tracker.cancel(first, gestureDown, 1);
        assertFalse(tracker.owns(first, 100, 1));
        assertFalse(tracker.start(peer, 100, 1, 540, 1230,
                1080, 1240, 72, 130, 24, 1050));
        start(500, 540, 1230);
        assertEquals(HOME, finish(540, 900, 600, 1500));
    }

    @Test public void contentScrollInBottomTenPercentIsNotSystemGesture() {
        start(100, 540, 1120); // old 10% start zone would accept this app scroll
        assertFalse(tracker.owns(first, 100, 1));
    }

    @Test public void offscreenTouchIsIgnored() {
        start(100, 1100, 1230);
        assertFalse(tracker.owns(first, 100, 1));
        start(500, 540, 1250);
        assertFalse(tracker.owns(first, 500, 1));
    }

    @Test public void stationaryReleaseAfterFastSwipeStillGoesHome() {
        start(100, 540, 1230);
        move(540, 900, 180);
        // UP often repeats MOVE's coordinate: release velocity comes from the whole stream.
        assertEquals(HOME, finish(540, 900, 190, 1700));
    }

    @Test public void settlingAboveThresholdOpensRecents() {
        start(100, 540, 1230);
        move(540, 900, 200);
        move(546, 896, 300);
        assertEquals(RECENTS, finish(542, 898, 450, 0));
    }

    @Test public void heldFingerNeedsNoExtraMoveEvents() {
        start(100, 540, 1230);
        move(540, 900, 200);
        assertEquals(RECENTS, finish(540, 900, 600, 0));
    }

    @Test public void slowContinuousSwipeIsNotMistakenForHold() {
        start(100, 540, 1230);
        move(540, 1000, 300);
        move(540, 950, 450);
        move(540, 900, 600);
        assertEquals(HOME, finish(540, 850, 750, 300));
    }

    @Test public void flingAfterHoldGoesHome() {
        start(100, 540, 1230);
        move(540, 900, 200);
        move(540, 900, 500);
        assertEquals(HOME, finish(540, 700, 600, 2000));
    }

    @Test public void horizontalSwipeDoesNotGoHome() {
        start(100, 100, 1230);
        move(700, 1000, 200);
        assertEquals(NONE, finish(800, 1000, 300, 0));
    }

    @Test public void shortSwipeDoesNothing() {
        start(100, 540, 1230);
        assertEquals(NONE, finish(540, 1150, 300, 2000));
    }

    @Test public void draggingBackDownCancelsNavigation() {
        start(100, 540, 1230);
        move(540, 800, 200);
        assertEquals(NONE, finish(540, 1080, 400, 0));
    }

    @Test public void downwardFlingCancelsEvenAboveThreshold() {
        start(100, 540, 1230);
        move(540, 800, 200);
        assertEquals(NONE, finish(540, 850, 220, -1400));
    }

    @Test public void rotatedPanelUsesItsOwnBounds() {
        assertTrue(tracker.start(first, 100, 1, 600, 1070,
                1240, 1080, 72, 130, 24, 1050));
        assertTrue(tracker.owns(first, 100, 1));
        assertEquals(HOME, finish(600, 850, 200, 1600));
    }

    @Test public void finishedGestureCannotFireAgain() {
        start(100, 540, 1230);
        assertEquals(HOME, finish(540, 900, 200, 1500));
        assertEquals(NONE, finish(540, 900, 201, 1500));
    }

    @Test public void canceledGestureCannotFireOnLateUp() {
        start(100, 540, 1230);
        tracker.cancel(first, gestureDown, 1);
        assertEquals(NONE, finish(540, 900, 200, 1500));
    }

    @Test public void nonFiniteCoordinatesMustNotNavigate() {
        start(100, 540, 1230);
        assertEquals(NONE, finish(Float.NaN, Float.NaN, 200, 1500));
    }

    @Test public void staleMoveCannotTurnHomeIntoRecents() {
        start(100, 540, 1230);
        move(540, 900, 500);
        move(540, 800, 200);
        assertEquals(HOME, finish(540, 800, 550, 0));
    }

    @Test public void peerFinishAndCancelCannotTerminateOwner() {
        start(100, 540, 1230);
        tracker.cancel(peer, 100, 1);
        assertEquals(NONE, tracker.finish(peer, 100, 1, 540, 900, 200, 1500));
        assertFalse(tracker.move(peer, 100, 1, 540, 500, 180));
        assertEquals(HOME, finish(540, 900, 200, 1500));
    }

    @Test public void wrongDeviceOrStreamCannotFinishCurrentSwipe() {
        start(100, 540, 1230);
        start(500, 540, 1230);
        assertEquals(NONE, tracker.finish(first, 100, 1, 540, 900, 600, 1500));
        assertEquals(NONE, tracker.finish(first, 500, 2, 540, 900, 600, 1500));
        assertTrue(tracker.isLatest(500, 1));
        assertFalse(tracker.isLatest(100, 1));
        assertEquals(HOME, finish(540, 900, 600, 1500));
    }

    @Test public void invalidMoveCancelsUntilNextDown() {
        start(100, 540, 1230);
        move(Float.POSITIVE_INFINITY, 900, 200);
        assertEquals(NONE, finish(540, 900, 300, 1500));
        start(500, 540, 1230);
        assertEquals(HOME, finish(540, 900, 600, 1500));
    }

    @Test public void invalidVelocityDoesNotNavigate() {
        start(100, 540, 1230);
        assertEquals(NONE, finish(540, 900, 200, Float.NaN));
    }

    @Test public void staleUpIsIgnoredWithoutConsumingValidUp() {
        start(100, 540, 1230);
        move(540, 900, 300);
        assertEquals(NONE, finish(540, 900, 200, 1500));
        assertEquals(HOME, finish(540, 850, 350, 1500));
    }

    @Test public void invalidBoundsNeverCaptureInput() {
        assertTrue(tracker.start(first, 100, 1, 540, 1230,
                Float.POSITIVE_INFINITY, 1240, 72, 130, 24, 1050));
        assertFalse(tracker.owns(first, 100, 1));
    }

    @Test public void chaoticStreamsNeverDispatchSameGestureTwice() {
        Random random = new Random(0x6c6757696e67L);
        Set<String> completed = new HashSet<>();
        long newestDown = 100;
        int fired = 0;
        for (int i = 0; i < 20_000; i++) {
            if (random.nextInt(8) == 0) newestDown += 1000;
            long down = newestDown - (random.nextInt(4) == 0 ? 1000 : 0);
            Object receiver = random.nextBoolean() ? first : peer;
            int device = random.nextInt(4) == 0 ? 2 : 1;
            long eventTime = down + 100 + random.nextInt(800);
            switch (random.nextInt(4)) {
                case 0:
                    tracker.start(receiver, down, device, 540, 1230,
                            1080, 1240, 72, 130, 24, 1050);
                    break;
                case 1:
                    tracker.move(receiver, down, device, 540, 900, eventTime);
                    break;
                case 2:
                    HomeGestureTracker.Result result = tracker.finish(receiver, down, device,
                            540, 900, eventTime, 1500);
                    if (result != NONE) {
                        assertTrue("duplicate navigation for " + down,
                                completed.add(down + ":" + device));
                        fired++;
                    }
                    break;
                default:
                    tracker.cancel(receiver, down, device);
            }
        }
        assertTrue("must exercise successful navigation too", fired > 100);
    }
}
