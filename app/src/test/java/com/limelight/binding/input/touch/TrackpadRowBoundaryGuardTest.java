package com.limelight.binding.input.touch;

import static org.junit.Assert.assertEquals;

import org.junit.Before;
import org.junit.Test;

public class TrackpadRowBoundaryGuardTest {
    private TrackpadRowBoundaryGuard guard;

    @Before
    public void setUp() {
        guard = new TrackpadRowBoundaryGuard();
    }

    @Test
    public void repeatedStepsInSameDirectionDoNotWrap() {
        assertEquals(-1, guard.filterSteps(-1));
        assertEquals(0, guard.filterSteps(-1));
        assertEquals(0, guard.filterSteps(-3));
    }

    @Test
    public void reversingDirectionMovesBackImmediately() {
        assertEquals(-1, guard.filterSteps(-1));
        assertEquals(1, guard.filterSteps(1));
        assertEquals(-1, guard.filterSteps(-1));
    }

    @Test
    public void oneSampleCannotCrossBothRows() {
        assertEquals(1, guard.filterSteps(4));
    }

    @Test
    public void horizontalSelectionAllowsFreshVerticalStep() {
        assertEquals(1, guard.filterSteps(1));
        guard.onHorizontalSelectionChanged();
        assertEquals(1, guard.filterSteps(1));
    }

    @Test
    public void resetClearsPreviousDirection() {
        assertEquals(-1, guard.filterSteps(-1));
        guard.reset();
        assertEquals(-1, guard.filterSteps(-1));
    }

    @Test
    public void zeroDoesNotChangeDirection() {
        assertEquals(0, guard.filterSteps(0));
        assertEquals(1, guard.filterSteps(1));
        assertEquals(0, guard.filterSteps(0));
        assertEquals(0, guard.filterSteps(1));
    }
}
