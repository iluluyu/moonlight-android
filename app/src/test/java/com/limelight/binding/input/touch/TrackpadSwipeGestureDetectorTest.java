package com.limelight.binding.input.touch;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import org.junit.Before;
import org.junit.Test;

public class TrackpadSwipeGestureDetectorTest {
    private TrackpadSwipeGestureDetector detector;

    @Before
    public void setUp() {
        detector = new TrackpadSwipeGestureDetector(150, 120, 60, 45, 50, 200);
    }

    @Test
    public void fastHorizontalSwipeFiresOnce() {
        detector.start(0, 0, 1_000);

        assertEquals(TrackpadGestureConfiguration.Direction.LEFT,
                detector.move(-70, 2, 1_080));
        assertNull(detector.move(-180, 3, 1_120));
        assertTrue(detector.isTracking());
    }

    @Test
    public void slowSwipeRequiresFullThreshold() {
        detector.start(0, 0, 1_000);

        assertNull(detector.move(-100, 0, 1_250));
        assertEquals(TrackpadGestureConfiguration.Direction.LEFT,
                detector.move(-151, 0, 1_300));
    }

    @Test
    public void midStrokeReversalRearmsOppositeDirection() {
        detector.start(0, 0, 1_000);

        assertEquals(TrackpadGestureConfiguration.Direction.RIGHT,
                detector.move(70, 0, 1_080));
        assertEquals(TrackpadGestureConfiguration.Direction.LEFT,
                detector.move(9, 0, 1_120));
    }

    @Test
    public void reversalUsesFarthestPointAfterRightSwipe() {
        detector.start(0, 0, 1_000);

        assertEquals(TrackpadGestureConfiguration.Direction.RIGHT,
                detector.move(70, 0, 1_080));
        assertNull(detector.move(400, 0, 1_120));
        assertEquals(TrackpadGestureConfiguration.Direction.LEFT,
                detector.move(339, 0, 1_180));
    }

    @Test
    public void reversalUsesFarthestPointAfterLeftSwipe() {
        detector.start(500, 0, 1_000);

        assertEquals(TrackpadGestureConfiguration.Direction.LEFT,
                detector.move(430, 0, 1_080));
        assertNull(detector.move(100, 0, 1_120));
        assertEquals(TrackpadGestureConfiguration.Direction.RIGHT,
                detector.move(161, 0, 1_180));
    }

    @Test
    public void diagonalMotionDoesNotFireWithoutDominantAxis() {
        detector.start(0, 0, 1_000);

        assertNull(detector.move(100, 100, 1_080));
    }

    @Test
    public void shortStationaryStrokeIsTap() {
        detector.start(100, 100, 1_000);
        assertNull(detector.move(105, 104, 1_080));

        assertTrue(detector.isTap(106, 105, 1_160, 250, 30));
    }

    @Test
    public void swipeAndLongPressAreNotTaps() {
        detector.start(0, 0, 1_000);
        assertEquals(TrackpadGestureConfiguration.Direction.RIGHT,
                detector.move(70, 0, 1_080));
        assertFalse(detector.isTap(70, 0, 1_100, 250, 30));

        detector.start(0, 0, 2_000);
        assertFalse(detector.isTap(0, 0, 2_300, 250, 30));
    }

    @Test
    public void finishClearsTrackingState() {
        detector.start(0, 0, 1_000);
        detector.finish();

        assertNull(detector.move(200, 0, 1_050));
    }
}
