package com.limelight.binding.input.touch;

/** Detects one directional centroid swipe per stroke, with mid-stroke reversal.
 *  MotionEvent finger-count/lifecycle routing remains in Game so this class can be
 *  tested independently from Android input dispatch. */
public final class TrackpadSwipeGestureDetector {
    private final float slowThresholdX;
    private final float slowThresholdY;
    private final float fastThresholdX;
    private final float fastThresholdY;
    private final float reversalThreshold;
    private final long fastSwipeMaxMs;

    private boolean tracking;
    private boolean armed;
    private long startTimeMs;
    private float startX;
    private float startY;
    private float lastX;
    private float lastY;
    private float maxDistanceFromStart;
    private float cumulativeX;
    private float cumulativeY;
    private float fireX;
    private float fireY;
    private TrackpadGestureConfiguration.Direction strokeDirection;

    public TrackpadSwipeGestureDetector(float slowThresholdX, float slowThresholdY,
            float fastThresholdX, float fastThresholdY, float reversalThreshold,
            long fastSwipeMaxMs) {
        this.slowThresholdX = slowThresholdX;
        this.slowThresholdY = slowThresholdY;
        this.fastThresholdX = fastThresholdX;
        this.fastThresholdY = fastThresholdY;
        this.reversalThreshold = reversalThreshold;
        this.fastSwipeMaxMs = fastSwipeMaxMs;
    }

    public void start(float x, float y, long eventTimeMs) {
        tracking = true;
        armed = true;
        startTimeMs = eventTimeMs;
        startX = x;
        startY = y;
        lastX = x;
        lastY = y;
        maxDistanceFromStart = 0;
        cumulativeX = 0;
        cumulativeY = 0;
        fireX = x;
        fireY = y;
        strokeDirection = null;
    }

    public TrackpadGestureConfiguration.Direction move(float x, float y, long eventTimeMs) {
        if (!tracking) {
            return null;
        }

        updateMaxDistanceFromStart(x, y);

        if (armed) {
            cumulativeX += x - lastX;
            cumulativeY += y - lastY;
            lastX = x;
            lastY = y;

            TrackpadGestureConfiguration.Direction direction = detectDirection(eventTimeMs);
            if (direction != null) {
                recordFire(direction, x, y);
                return direction;
            }
        } else {
            // Use the farthest point reached after the previous fire as the turn
            // point. Otherwise a user who keeps moving after the shortcut fires
            // must travel all the way back past the old fire coordinate before a
            // reversal can even begin, which feels especially poor near an edge.
            updateReversalAnchor(x, y);
            if (hasReversed(x, y)) {
                armed = true;
                startTimeMs = eventTimeMs;
                lastX = x;
                lastY = y;

                // Retain the motion already used to establish the reversal. The old
                // implementation discarded these first reversalThreshold pixels and
                // then required another full swipe threshold, making direction changes
                // unnecessarily long and dependent on which edge had more room.
                cumulativeX = x - fireX;
                cumulativeY = y - fireY;

                TrackpadGestureConfiguration.Direction direction =
                        detectDirection(eventTimeMs);
                if (direction != null) {
                    recordFire(direction, x, y);
                    return direction;
                }
            }
        }

        return null;
    }

    public boolean isTap(float x, float y, long eventTimeMs,
            long maxDurationMs, float maxMovement) {
        if (!tracking || strokeDirection != null) {
            return false;
        }

        updateMaxDistanceFromStart(x, y);
        return eventTimeMs - startTimeMs < maxDurationMs
                && maxDistanceFromStart < maxMovement;
    }

    private void updateMaxDistanceFromStart(float x, float y) {
        maxDistanceFromStart = Math.max(maxDistanceFromStart,
                Math.abs(x - startX) + Math.abs(y - startY));
    }

    private TrackpadGestureConfiguration.Direction detectDirection(long eventTimeMs) {
        long elapsedMs = eventTimeMs - startTimeMs;
        boolean fast = elapsedMs < fastSwipeMaxMs;
        float thresholdX = fast ? fastThresholdX : slowThresholdX;
        float thresholdY = fast ? fastThresholdY : slowThresholdY;
        float dominantRatio = fast ? 1.3f : 1.5f;

        if (Math.abs(cumulativeX) > thresholdX
                && Math.abs(cumulativeX) > Math.abs(cumulativeY) * dominantRatio) {
            return cumulativeX > 0
                    ? TrackpadGestureConfiguration.Direction.RIGHT
                    : TrackpadGestureConfiguration.Direction.LEFT;
        }
        if (Math.abs(cumulativeY) > thresholdY
                && Math.abs(cumulativeY) > Math.abs(cumulativeX) * dominantRatio) {
            return cumulativeY > 0
                    ? TrackpadGestureConfiguration.Direction.DOWN
                    : TrackpadGestureConfiguration.Direction.UP;
        }
        return null;
    }

    private void recordFire(TrackpadGestureConfiguration.Direction direction,
            float x, float y) {
        armed = false;
        strokeDirection = direction;
        fireX = x;
        fireY = y;
    }

    private void updateReversalAnchor(float x, float y) {
        if (strokeDirection == null) {
            return;
        }

        switch (strokeDirection) {
            case LEFT:
                if (x < fireX) {
                    fireX = x;
                    fireY = y;
                }
                break;
            case RIGHT:
                if (x > fireX) {
                    fireX = x;
                    fireY = y;
                }
                break;
            case UP:
                if (y < fireY) {
                    fireX = x;
                    fireY = y;
                }
                break;
            case DOWN:
                if (y > fireY) {
                    fireX = x;
                    fireY = y;
                }
                break;
            default:
                break;
        }
    }

    private boolean hasReversed(float x, float y) {
        if (strokeDirection == null) {
            return false;
        }

        switch (strokeDirection) {
            case LEFT:
                return x - fireX > reversalThreshold;
            case RIGHT:
                return fireX - x > reversalThreshold;
            case UP:
                return y - fireY > reversalThreshold;
            case DOWN:
                return fireY - y > reversalThreshold;
            default:
                return false;
        }
    }

    public boolean isTracking() {
        return tracking;
    }

    public void finish() {
        tracking = false;
        armed = false;
        strokeDirection = null;
        cumulativeX = 0;
        cumulativeY = 0;
        maxDistanceFromStart = 0;
    }
}
