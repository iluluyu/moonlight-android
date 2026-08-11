package com.limelight.binding.input.touch;

/** Distinguishes deliberate grid-row movement from curvature in a horizontal swipe. */
public final class TrackpadVerticalIntentGate {
    private final float minimumVerticalToHorizontalRatio;

    private boolean active;
    private float lastX;
    private float anchorY;
    private float horizontalTravel;

    public TrackpadVerticalIntentGate(float minimumVerticalToHorizontalRatio) {
        if (minimumVerticalToHorizontalRatio <= 0) {
            throw new IllegalArgumentException("intent ratio must be positive");
        }
        this.minimumVerticalToHorizontalRatio = minimumVerticalToHorizontalRatio;
    }

    public void start(float x, float y) {
        active = true;
        lastX = x;
        anchorY = y;
        horizontalTravel = 0;
    }

    /**
     * Records another centroid sample. Returns true when a horizontal selection step
     * should discard pending vertical distance because horizontal travel is dominant.
     */
    public boolean move(float x, float y, boolean horizontalStep) {
        if (!active) {
            return false;
        }

        horizontalTravel += Math.abs(x - lastX);
        lastX = x;
        if (horizontalStep && !hasVerticalIntent(y)) {
            rebase(y);
            return true;
        }
        return false;
    }

    /** Evaluates a proposed row step, then starts a fresh intent segment. */
    public boolean acceptVerticalStep(float y) {
        if (!active) {
            return false;
        }

        boolean accepted = hasVerticalIntent(y);
        rebase(y);
        return accepted;
    }

    public void finish() {
        active = false;
        horizontalTravel = 0;
    }

    private boolean hasVerticalIntent(float y) {
        float verticalDistance = Math.abs(y - anchorY);
        return verticalDistance > 0
                && verticalDistance >= horizontalTravel * minimumVerticalToHorizontalRatio;
    }

    private void rebase(float y) {
        anchorY = y;
        horizontalTravel = 0;
    }
}
