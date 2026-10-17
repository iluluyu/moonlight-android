package com.limelight.binding.input.touch;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

/**
 * Tests for the raw-evdev contact filter. Reading the pad node bypasses the input
 * stack's noise/palm filters, so these rules are what keeps a resting thumb from
 * turning a two-finger scroll into a three-finger swipe.
 */
public class TrackpadContactFilterTest {
    private static final int BIG = 800;

    private static boolean[] keepFlags(int n) {
        return new boolean[n];
    }

    @Test
    public void singleContactIsNeverFiltered() {
        TrackpadContactFilter filter = new TrackpadContactFilter();
        boolean[] keep = keepFlags(1);

        // tiny and stationary for a long time: still the only finger, must survive
        filter.select(1000, new int[]{1}, new int[]{500}, new int[]{500}, new int[]{5}, keep);
        filter.select(3000, new int[]{1}, new int[]{500}, new int[]{500}, new int[]{5}, keep);

        assertTrue(keep[0]);
        assertEquals(1, filter.select(3000, new int[]{1}, new int[]{500}, new int[]{500},
                new int[]{5}, keep));
    }

    @Test
    public void noiseContactIsDroppedOnlyWhileAnotherContactExists() {
        TrackpadContactFilter filter = new TrackpadContactFilter();
        boolean[] keep = keepFlags(2);

        int kept = filter.select(1000, new int[]{1, 2}, new int[]{500, 800}, new int[]{500, 500},
                new int[]{BIG, 5}, keep);
        assertTrue(keep[0]);
        assertFalse(keep[1]);
        assertEquals(1, kept);
    }

    @Test
    public void restingThumbIsDroppedOnceThreeContactsAreDown() {
        TrackpadContactFilter filter = new TrackpadContactFilter();
        int[] ids = {1, 2, 3};
        int[] xs = {400, 900, 1400};
        int[] ys = {700, 700, 700};
        int[] sizes = {BIG, BIG, BIG};
        boolean[] keep = keepFlags(3);

        // first frames: thumb has not been stationary long enough yet
        filter.select(1000, ids, xs, ys, sizes, keep);
        assertTrue(keep[0]);

        // the two fingers keep scrolling while the thumb stays put
        int[] movingXs = {400, 900, 1400};
        int[] movingYs = {700, 760, 760};
        filter.select(1400, ids, movingXs, movingYs, sizes, keep);

        // after REST_MS the stationary first contact is treated as a resting thumb
        int kept = filter.select(1000 + TrackpadContactFilter.REST_MS + 10, ids, movingXs,
                movingYs, sizes, keep);
        assertFalse(keep[0]);
        assertTrue(keep[1]);
        assertTrue(keep[2]);
        assertEquals(2, kept);
    }

    @Test
    public void twoDeliberateFingersAreNeverDropped() {
        TrackpadContactFilter filter = new TrackpadContactFilter();
        int[] ids = {1, 2};
        int[] xs = {400, 900};
        int[] ys = {700, 700};
        int[] sizes = {BIG, BIG};
        boolean[] keep = keepFlags(2);

        // even a very slow two-finger scroll keeps both contacts
        filter.select(1000, ids, xs, ys, sizes, keep);
        int kept = filter.select(5000, ids, xs, ys, sizes, keep);

        assertTrue(keep[0]);
        assertTrue(keep[1]);
        assertEquals(2, kept);
    }

    @Test
    public void movingContactIsNeverDroppedAsResting() {
        TrackpadContactFilter filter = new TrackpadContactFilter();
        boolean[] keep = keepFlags(3);

        filter.select(1000, new int[]{1, 2, 3}, new int[]{400, 900, 1400},
                new int[]{700, 700, 700}, new int[]{BIG, BIG, BIG}, keep);
        // the first contact keeps travelling well past the rest radius while the other
        // two sit still: only a resting contact may be dropped, never the moving one
        int kept = filter.select(1800, new int[]{1, 2, 3}, new int[]{700, 900, 1400},
                new int[]{700, 700, 700}, new int[]{BIG, BIG, BIG}, keep);

        assertTrue(keep[0]);
        // and at most one contact is dropped per frame
        assertEquals(2, kept);
    }

    @Test
    public void threeFingersHeldStillAreNotFiltered() {
        TrackpadContactFilter filter = new TrackpadContactFilter();
        int[] ids = {1, 2, 3};
        int[] xs = {400, 900, 1400};
        int[] ys = {700, 700, 700};
        int[] sizes = {BIG, BIG, BIG};
        boolean[] keep = keepFlags(3);

        filter.select(1000, ids, xs, ys, sizes, keep);
        // nothing moves: the user is holding three fingers before starting a gesture
        int kept = filter.select(3000, ids, xs, ys, sizes, keep);

        assertTrue(keep[0]);
        assertTrue(keep[1]);
        assertTrue(keep[2]);
        assertEquals(3, kept);
    }

    @Test
    public void neverForwardsAnEmptyFrame() {
        TrackpadContactFilter filter = new TrackpadContactFilter();
        boolean[] keep = keepFlags(3);

        // all three are noise-sized at once; one contact must survive so the pipeline
        // does not see a spurious "everything lifted"
        int kept = filter.select(1000, new int[]{1, 2, 3}, new int[]{400, 900, 1400},
                new int[]{700, 700, 700}, new int[]{1, 2, 3}, keep);

        assertEquals(1, kept);
    }
}
