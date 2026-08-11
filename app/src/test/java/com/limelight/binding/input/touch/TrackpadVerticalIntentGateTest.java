package com.limelight.binding.input.touch;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import org.junit.Before;
import org.junit.Test;

public class TrackpadVerticalIntentGateTest {
    private TrackpadVerticalIntentGate gate;

    @Before
    public void setUp() {
        gate = new TrackpadVerticalIntentGate(0.8f);
        gate.start(0, 0);
    }

    @Test
    public void broadHorizontalArcRebasesPendingVerticalDistance() {
        assertTrue(gate.move(90, 30, true));
        assertTrue(gate.move(180, 60, true));
        assertFalse(gate.move(400, 135, false));
        assertFalse(gate.acceptVerticalStep(135));
    }

    @Test
    public void diagonalMovementKeepsBothGridAxesAvailable() {
        assertFalse(gate.move(90, 90, true));
        assertFalse(gate.move(135, 135, false));
        assertTrue(gate.acceptVerticalStep(135));
    }

    @Test
    public void longCurvedSegmentIsRejectedAtVerticalThreshold() {
        assertFalse(gate.move(400, 135, false));
        assertFalse(gate.acceptVerticalStep(135));
    }

    @Test
    public void verticalTurnAfterHorizontalSelectionStartsFreshIntent() {
        assertTrue(gate.move(180, 20, true));
        assertFalse(gate.move(184, 155, false));
        assertTrue(gate.acceptVerticalStep(155));
    }

    @Test
    public void repeatedHorizontalArcSegmentsNeverReachRowAccumulatorThreshold() {
        TrackpadSelectionAccumulator columns = new TrackpadSelectionAccumulator(90, 4);
        TrackpadSelectionAccumulator rows = new TrackpadSelectionAccumulator(135, 90, 4);
        columns.start(0);
        rows.start(0);

        for (int i = 1; i <= 5; i++) {
            float x = i * 90;
            float y = i * 30;
            int columnSteps = columns.move(x);
            if (gate.move(x, y, columnSteps != 0)) {
                rows.rebase(y);
            }
            assertEquals(0, rows.move(y));
        }
    }

    @Test
    public void diagonalSegmentCanProduceColumnAndRowStepsTogether() {
        TrackpadSelectionAccumulator columns = new TrackpadSelectionAccumulator(90, 4);
        TrackpadSelectionAccumulator rows = new TrackpadSelectionAccumulator(135, 90, 4);
        columns.start(0);
        rows.start(0);

        int columnSteps = columns.move(90);
        assertEquals(1, columnSteps);
        assertFalse(gate.move(90, 90, true));
        assertEquals(0, rows.move(90));

        columnSteps = columns.move(135);
        assertEquals(0, columnSteps);
        assertFalse(gate.move(135, 135, false));
        assertEquals(1, rows.move(135));
        assertTrue(gate.acceptVerticalStep(135));
    }

    @Test
    public void finishRejectsFurtherVerticalSteps() {
        gate.finish();

        assertFalse(gate.move(0, 135, false));
        assertFalse(gate.acceptVerticalStep(135));
    }

    @Test(expected = IllegalArgumentException.class)
    public void ratioMustBePositive() {
        new TrackpadVerticalIntentGate(0);
    }
}
