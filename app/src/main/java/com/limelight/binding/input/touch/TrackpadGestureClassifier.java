package com.limelight.binding.input.touch;

/**
 * Pure decision helpers for physical trackpad gestures.
 *
 * <p>The Android framework may leave touchpad events unclassified. In that case we
 * keep a short ambiguous phase and decide from the motion of both contacts before
 * emitting an irreversible remote scroll or zoom action.</p>
 */
public final class TrackpadGestureClassifier {
    public enum TwoFingerGesture {
        UNDECIDED,
        SCROLL,
        PINCH
    }

    /** Bounds MotionEvent copies retained while a two-finger gesture is ambiguous. */
    public static final int MAX_PENDING_EVENT_COUNT = 48;

    private static final float PINCH_CONFIRM_MIN_SEPARATION = 20f;
    private static final float PINCH_CONFIRM_MAX_SEPARATION = 36f;
    private static final float PINCH_CONFIRM_INITIAL_DISTANCE_RATIO = 0.06f;
    private static final float PINCH_SEPARATION_CENTROID_RATIO = 1.25f;
    private static final float PINCH_FINGER_MIN_TRAVEL = 10f;

    private static final float SCROLL_CONFIRM_CENTROID_TRAVEL = 14f;
    private static final float SCROLL_CONFIRM_SEPARATION_RATIO = 0.55f;
    private static final float SCROLL_FALLBACK_CENTROID_TRAVEL = 70f;
    private static final float SCROLL_FALLBACK_SEPARATION_RATIO = 0.35f;

    // libinput keeps a pinch-correction window for up to 300ms. This is only a
    // bounded fallback; strong same/opposite-direction evidence resolves earlier.
    private static final long DECISION_TIMEOUT_MS = 300;
    private static final float TIMEOUT_PINCH_MIN_SEPARATION = 12f;
    private static final float TIMEOUT_PINCH_CENTROID_RATIO = 1.1f;
    private static final float TIMEOUT_FINGER_MIN_TRAVEL = 8f;
    private static final float TAP_JITTER_LIMIT = 10f;

    private TrackpadGestureClassifier() {
    }

    public static TwoFingerGesture classifyTwoFingerGesture(float initialDistance,
            float cumulativeSeparation, float cumulativeCentroid, float cumulativeDot,
            float finger0Travel, float finger1Travel, long elapsedMs, int pendingEventCount) {
        float separation = Math.abs(cumulativeSeparation);
        float pinchConfirmationDistance = calculatePinchConfirmationDistance(initialDistance);

        if (separation >= pinchConfirmationDistance
                && separation > cumulativeCentroid * PINCH_SEPARATION_CENTROID_RATIO
                && cumulativeDot < 0f
                && finger0Travel >= PINCH_FINGER_MIN_TRAVEL
                && finger1Travel >= PINCH_FINGER_MIN_TRAVEL) {
            return TwoFingerGesture.PINCH;
        }

        // Same-direction movement with stable separation is a scroll. Resolve this
        // early so the ambiguous buffer adds only a few frames of normal latency.
        if (cumulativeCentroid >= SCROLL_CONFIRM_CENTROID_TRAVEL
                && separation <= cumulativeCentroid * SCROLL_CONFIRM_SEPARATION_RATIO
                && cumulativeDot >= 0f) {
            return TwoFingerGesture.SCROLL;
        }

        // A long centroid path is decisive even if alternating "walking" fingers
        // produced a noisy/negative dot-product sum.
        if (cumulativeCentroid >= SCROLL_FALLBACK_CENTROID_TRAVEL
                && separation <= cumulativeCentroid * SCROLL_FALLBACK_SEPARATION_RATIO) {
            return TwoFingerGesture.SCROLL;
        }

        if (elapsedMs >= DECISION_TIMEOUT_MS
                || pendingEventCount >= MAX_PENDING_EVENT_COUNT) {
            // Permit a deliberately slow pinch after the bounded decision window,
            // but retain the opposing-direction and two-engaged-fingers guards.
            if (separation >= TIMEOUT_PINCH_MIN_SEPARATION
                    && separation > cumulativeCentroid * TIMEOUT_PINCH_CENTROID_RATIO
                    && cumulativeDot < 0f
                    && finger0Travel >= TIMEOUT_FINGER_MIN_TRAVEL
                    && finger1Travel >= TIMEOUT_FINGER_MIN_TRAVEL) {
                return TwoFingerGesture.PINCH;
            }

            if (!isTapJitter(cumulativeSeparation, cumulativeCentroid,
                    finger0Travel, finger1Travel)) {
                return TwoFingerGesture.SCROLL;
            }
        }

        return TwoFingerGesture.UNDECIDED;
    }

    /** Resolves buffered motion when the contacts lift before the normal classifier. */
    public static TwoFingerGesture classifyTwoFingerGestureAtEnd(float initialDistance,
            float cumulativeSeparation, float cumulativeCentroid, float cumulativeDot,
            float finger0Travel, float finger1Travel, long elapsedMs, int pendingEventCount) {
        TwoFingerGesture decision = classifyTwoFingerGesture(initialDistance,
                cumulativeSeparation, cumulativeCentroid, cumulativeDot,
                finger0Travel, finger1Travel, elapsedMs, pendingEventCount);
        if (decision != TwoFingerGesture.UNDECIDED) {
            return decision;
        }

        if (isTapJitter(cumulativeSeparation, cumulativeCentroid,
                finger0Travel, finger1Travel)) {
            return TwoFingerGesture.UNDECIDED;
        }

        // A short opposing movement that did not reach the live zoom threshold is
        // an aborted pinch, not a right click or a tiny scroll.
        float separation = Math.abs(cumulativeSeparation);
        if (separation > cumulativeCentroid
                && cumulativeDot <= 0f
                && finger0Travel >= TIMEOUT_FINGER_MIN_TRAVEL
                && finger1Travel >= TIMEOUT_FINGER_MIN_TRAVEL) {
            return TwoFingerGesture.PINCH;
        }

        return TwoFingerGesture.SCROLL;
    }

    static float calculatePinchConfirmationDistance(float initialDistance) {
        float scaledDistance = initialDistance * PINCH_CONFIRM_INITIAL_DISTANCE_RATIO;
        return Math.max(PINCH_CONFIRM_MIN_SEPARATION,
                Math.min(PINCH_CONFIRM_MAX_SEPARATION, scaledDistance));
    }

    static boolean isTapJitter(float cumulativeSeparation, float cumulativeCentroid,
            float finger0Travel, float finger1Travel) {
        return Math.abs(cumulativeSeparation) <= TAP_JITTER_LIMIT
                && cumulativeCentroid <= TAP_JITTER_LIMIT
                && finger0Travel <= TAP_JITTER_LIMIT
                && finger1Travel <= TAP_JITTER_LIMIT;
    }
}
