package com.limelight.binding.input.touch;

/**
 * Prevents repeated vertical selection steps from wrapping around a two-row grid.
 * The remote input protocol does not expose the grid row count or current row, so
 * this guard constrains input intent instead of attempting to mirror remote state.
 */
public final class TrackpadRowBoundaryGuard {
    private int lastDirection;

    /**
     * Returns at most one vertical step, suppressing another step in the same direction.
     * A direction reversal is always allowed.
     */
    public int filterSteps(int steps) {
        int direction = Integer.compare(steps, 0);
        if (direction == 0 || direction == lastDirection) {
            return 0;
        }

        lastDirection = direction;
        return direction;
    }

    /** A horizontal selection may have crossed into a different row. */
    public void onHorizontalSelectionChanged() {
        lastDirection = 0;
    }

    public void reset() {
        lastDirection = 0;
    }
}
