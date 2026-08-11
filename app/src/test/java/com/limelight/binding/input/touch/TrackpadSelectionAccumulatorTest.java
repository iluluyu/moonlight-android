package com.limelight.binding.input.touch;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import org.junit.Before;
import org.junit.Test;

public class TrackpadSelectionAccumulatorTest {
    private TrackpadSelectionAccumulator accumulator;

    @Before
    public void setUp() {
        accumulator = new TrackpadSelectionAccumulator(90, 4);
        accumulator.start(100);
    }

    @Test
    public void continuousTravelProducesOneStepPerDistance() {
        assertEquals(0, accumulator.move(50));
        assertEquals(-1, accumulator.move(10));
        assertEquals(-2, accumulator.move(-170));
    }

    @Test
    public void reversingTravelStepsBackThroughSelection() {
        assertEquals(-1, accumulator.move(10));
        assertEquals(0, accumulator.move(60));
        assertEquals(1, accumulator.move(100));
        assertEquals(1, accumulator.move(190));
    }

    @Test
    public void firstStepCanRequireMoreTravelThanRepeats() {
        TrackpadSelectionAccumulator guarded =
                new TrackpadSelectionAccumulator(135, 90, 4);
        guarded.start(100);

        assertEquals(0, guarded.move(230));
        assertEquals(1, guarded.move(235));
        assertEquals(0, guarded.move(320));
        assertEquals(1, guarded.move(325));
        assertEquals(-1, guarded.move(235));
    }

    @Test
    public void largeSampleIsBoundedAndRemainderIsRetained() {
        assertEquals(4, accumulator.move(600));
        assertEquals(1, accumulator.move(600));
    }

    @Test
    public void independentAxesCanProduceDiagonalGridSteps() {
        TrackpadSelectionAccumulator columns = new TrackpadSelectionAccumulator(90, 4);
        TrackpadSelectionAccumulator rows = new TrackpadSelectionAccumulator(90, 4);
        columns.start(100);
        rows.start(100);

        assertEquals(1, columns.move(195));
        assertEquals(-1, rows.move(5));
        assertEquals(0, columns.move(210));
        assertEquals(1, rows.move(100));
    }

    @Test
    public void rebaseDiscardsDriftButKeepsFirstStepHysteresisState() {
        TrackpadSelectionAccumulator guarded =
                new TrackpadSelectionAccumulator(135, 90, 4);
        guarded.start(0);

        assertEquals(0, guarded.move(120));
        guarded.rebase(120);
        assertEquals(0, guarded.move(250));
        assertEquals(1, guarded.move(255));

        guarded.rebase(255);
        assertEquals(0, guarded.move(340));
        assertEquals(1, guarded.move(345));
    }

    @Test
    public void finishStopsAccumulation() {
        assertTrue(accumulator.isActive());
        accumulator.finish();

        assertFalse(accumulator.isActive());
        assertEquals(0, accumulator.move(500));
    }
}
