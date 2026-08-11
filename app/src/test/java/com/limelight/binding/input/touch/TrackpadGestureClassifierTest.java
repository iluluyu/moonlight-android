package com.limelight.binding.input.touch;

import static org.junit.Assert.assertEquals;

import com.limelight.binding.input.touch.TrackpadGestureClassifier.TwoFingerGesture;

import org.junit.Test;

public class TrackpadGestureClassifierTest {
    @Test
    public void sameDirectionMovementConfirmsScrollEarly() {
        TwoFingerGesture decision = TrackpadGestureClassifier.classifyTwoFingerGesture(
                300f, 0f, 16f, 64f, 16f, 16f, 20, 3);

        assertEquals(TwoFingerGesture.SCROLL, decision);
    }

    @Test
    public void opposingEngagedFingersConfirmPinch() {
        TwoFingerGesture decision = TrackpadGestureClassifier.classifyTwoFingerGesture(
                300f, 24f, 1f, -144f, 12f, 12f, 20, 3);

        assertEquals(TwoFingerGesture.PINCH, decision);
    }

    @Test
    public void restingFingerDoesNotConfirmPinch() {
        TwoFingerGesture decision = TrackpadGestureClassifier.classifyTwoFingerGesture(
                300f, 30f, 15f, 0f, 30f, 0f, 40, 5);

        assertEquals(TwoFingerGesture.UNDECIDED, decision);
    }

    @Test
    public void alternatingWalkingFingersResolveToScrollWhenSeparationCloses() {
        TwoFingerGesture decision = TrackpadGestureClassifier.classifyTwoFingerGesture(
                300f, 0f, 24f, 0f, 24f, 24f, 60, 8);

        assertEquals(TwoFingerGesture.SCROLL, decision);
    }

    @Test
    public void liftWithOnlyTapJitterRemainsATwoFingerTap() {
        TwoFingerGesture decision = TrackpadGestureClassifier.classifyTwoFingerGestureAtEnd(
                300f, 2f, 3f, 0f, 4f, 4f, 80, 4);

        assertEquals(TwoFingerGesture.UNDECIDED, decision);
    }

    @Test
    public void shortOpposingMotionDoesNotTurnIntoRightClick() {
        TwoFingerGesture decision = TrackpadGestureClassifier.classifyTwoFingerGestureAtEnd(
                300f, 15f, 2f, -20f, 9f, 9f, 80, 4);

        assertEquals(TwoFingerGesture.PINCH, decision);
    }

    @Test
    public void boundedAmbiguousMotionFallsBackToScroll() {
        TwoFingerGesture decision = TrackpadGestureClassifier.classifyTwoFingerGesture(
                300f, 20f, 12f, 0f, 20f, 20f, 300, 20);

        assertEquals(TwoFingerGesture.SCROLL, decision);
    }

    @Test
    public void pinchThresholdScalesWithinBounds() {
        assertEquals(20f,
                TrackpadGestureClassifier.calculatePinchConfirmationDistance(100f), 0.001f);
        assertEquals(24f,
                TrackpadGestureClassifier.calculatePinchConfirmationDistance(400f), 0.001f);
        assertEquals(36f,
                TrackpadGestureClassifier.calculatePinchConfirmationDistance(1_000f), 0.001f);
    }
}
