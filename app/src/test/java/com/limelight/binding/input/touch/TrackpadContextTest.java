package com.limelight.binding.input.touch;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;
import static org.mockito.ArgumentMatchers.anyShort;
import static org.mockito.Mockito.atLeastOnce;
import static org.mockito.Mockito.clearInvocations;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;

import java.util.concurrent.TimeUnit;

import com.limelight.nvstream.NvConnection;
import com.limelight.nvstream.input.MouseButtonPacket;

import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.annotation.Config;
import org.robolectric.shadows.ShadowLooper;

@RunWith(RobolectricTestRunner.class)
@Config(sdk = {33})
public class TrackpadContextTest {
    @Before
    public void resetPrimaryButtonState() {
        TrackpadContext.primaryButtonDown = false;
    }

    @Test
    public void accelerationDependsOnVelocityNotReportingInterval() {
        double at120Hz = TrackpadContext.calculateAccelerationMultiplier(16.0, 8);
        double at60Hz = TrackpadContext.calculateAccelerationMultiplier(32.0, 16);

        assertEquals(at120Hz, at60Hz, 0.0001);
    }

    @Test
    public void accelerationPreservesPrecisionAndCapsFastMotion() {
        double precise = TrackpadContext.calculateAccelerationMultiplier(4.0, 10);
        double medium = TrackpadContext.calculateAccelerationMultiplier(20.0, 10);
        double fast = TrackpadContext.calculateAccelerationMultiplier(100.0, 10);

        assertEquals(1.0, precise, 0.0001);
        assertTrue(medium > precise);
        assertEquals(2.5, fast, 0.0001);
    }

    @Test
    public void scrollMomentumFrictionIsStableAcrossDelayedFrames() {
        double twoNominalFrames = TrackpadContext.calculateScrollFlickFriction(1.0, 10)
                * TrackpadContext.calculateScrollFlickFriction(1.0, 10);
        double oneDelayedFrame = TrackpadContext.calculateScrollFlickFriction(1.0, 20);

        assertEquals(twoNominalFrames, oneDelayedFrame, 0.0001);
    }

    @Test
    public void slowScrollMomentumDecaysFasterThanFastScrollMomentum() {
        double slowFriction = TrackpadContext.calculateScrollFlickFriction(0.3, 10);
        double fastFriction = TrackpadContext.calculateScrollFlickFriction(1.0, 10);

        assertTrue(slowFriction < fastFriction);
        assertEquals(0.98, fastFriction, 0.0001);
    }

    @Test
    public void velocitySmoothingIsStableAcrossReportingIntervals() {
        double oneSixteenMillisecondStep = TrackpadContext.calculateVelocityBlendWeight(16);
        double oneEightMillisecondStep = TrackpadContext.calculateVelocityBlendWeight(8);
        double twoEightMillisecondSteps = 1.0
                - (1.0 - oneEightMillisecondStep) * (1.0 - oneEightMillisecondStep);

        assertEquals(oneSixteenMillisecondStep, twoEightMillisecondSteps, 0.0001);
    }

    @Test
    public void twoFingerScrollDoesNotUsePointerAcceleration() {
        NvConnection connection = mock(NvConnection.class);
        TrackpadContext context = new TrackpadContext(connection, 1);
        context.setPointerCount(2);

        context.touchDownEvent(0, 0, 0, true);
        context.touchMoveEvent(20, 0, 10);

        verify(connection).sendMouseHighResHScroll((short) -30);
    }

    @Test
    public void diagonalScrollKeepsAnIntentionalSecondaryAxis() {
        NvConnection connection = mock(NvConnection.class);
        TrackpadContext context = new TrackpadContext(connection, 1);
        context.setPointerCount(2);

        context.touchDownEvent(0, 0, 0, true);
        context.touchMoveEvent(20, 10, 10);

        verify(connection).sendMouseHighResHScroll((short) -30);
        verify(connection).sendMouseHighResScroll((short) 20);
    }

    @Test
    public void stoppingMomentumDoesNotBreakPendingDoubleTap() {
        NvConnection connection = mock(NvConnection.class);
        TrackpadContext context = new TrackpadContext(connection, 0);
        context.setPointerCount(1);

        context.touchDownEvent(100, 100, 1_000, true);
        context.touchUpEvent(100, 100, 1_060);
        context.stopFling();
        context.touchDownEvent(100, 100, 1_080, true);
        context.touchUpEvent(100, 100, 1_140);

        verify(connection, times(2)).sendMouseButtonDown(MouseButtonPacket.BUTTON_LEFT);
        verify(connection, times(2)).sendMouseButtonUp(MouseButtonPacket.BUTTON_LEFT);
    }

    @Test
    public void cancelReleasesTheExactPendingRightButton() {
        NvConnection connection = mock(NvConnection.class);
        TrackpadContext context = new TrackpadContext(connection, 1);
        context.setPointerCount(2);

        context.touchDownEvent(100, 100, 1_000, true);
        context.touchUpEvent(100, 100, 1_060);
        context.cancelTouch();

        verify(connection).sendMouseButtonDown(MouseButtonPacket.BUTTON_RIGHT);
        verify(connection).sendMouseButtonUp(MouseButtonPacket.BUTTON_RIGHT);
        verify(connection, never()).sendMouseButtonUp(MouseButtonPacket.BUTTON_LEFT);
    }

    @Test
    public void pointerCountChangeReleasesTheButtonThatStartedDrag() {
        NvConnection connection = mock(NvConnection.class);
        TrackpadContext context = new TrackpadContext(connection, 1);
        context.setPointerCount(2);

        context.touchDownEvent(100, 100, 1_000, true);
        context.touchUpEvent(100, 100, 1_060);
        context.touchDownEvent(100, 100, 1_080, true);
        context.touchMoveEvent(120, 100, 1_220);
        context.setPointerCount(1);

        verify(connection).sendMouseButtonDown(MouseButtonPacket.BUTTON_RIGHT);
        verify(connection).sendMouseButtonUp(MouseButtonPacket.BUTTON_RIGHT);
        verify(connection, never()).sendMouseButtonUp(MouseButtonPacket.BUTTON_LEFT);
    }

    @Test
    public void capturedRelativeMovementPreservesFractionalDeltas() {
        NvConnection connection = mock(NvConnection.class);
        TrackpadContext context = new TrackpadContext(connection, 0);
        context.setPointerCount(1);
        context.touchDownEvent(500, 500, 1_000, true);

        context.touchMoveRelativeEvent(2.5f, -1.5f, 1_010);
        context.touchMoveRelativeEvent(0.5f, -0.5f, 1_020);

        verify(connection).sendMouseMove((short) 2, (short) -1);
        verify(connection).sendMouseMove((short) 1, (short) -1);
    }

    @Test
    public void oneFingerLiftDoesNotStartCursorMomentum() {
        NvConnection connection = mock(NvConnection.class);
        TrackpadContext context = new TrackpadContext(connection, 0);
        context.setPointerCount(1);
        context.touchDownEvent(0, 0, 1_000, true);
        context.touchMoveEvent(100, 0, 1_010);
        clearInvocations(connection);

        context.touchUpEvent(100, 0, 1_010);
        ShadowLooper.idleMainLooper(1, TimeUnit.SECONDS);

        verify(connection, never()).sendMouseMove(anyShort(), anyShort());
    }

    @Test
    public void primaryPointerLiftStartsMomentumOwnedBySecondaryContext() {
        NvConnection connection = mock(NvConnection.class);
        TrackpadContext context = new TrackpadContext(connection, 1);
        context.setPointerCount(2);
        context.touchDownEvent(0, 0, 1_000, true);
        context.touchMoveEvent(0, 100, 1_010);
        clearInvocations(connection);

        assertTrue(context.finishScrollGesture(1_010));
        ShadowLooper.idleMainLooper(11, TimeUnit.MILLISECONDS);

        verify(connection, atLeastOnce()).sendMouseHighResScroll(anyShort());
        context.stopFling();
    }

    @Test
    public void deliberateSlowScrollStillStartsMomentum() {
        NvConnection connection = mock(NvConnection.class);
        TrackpadContext context = new TrackpadContext(connection, 1);
        context.setPointerCount(2);
        context.touchDownEvent(0, 0, 1_000, true);
        context.touchMoveEvent(0, 3, 1_010);
        context.touchMoveEvent(0, 6, 1_020);
        context.touchMoveEvent(0, 9, 1_030);
        context.touchMoveEvent(0, 12, 1_040);
        clearInvocations(connection);

        assertTrue(context.finishScrollGesture(1_040));
        ShadowLooper.idleMainLooper(11, TimeUnit.MILLISECONDS);

        verify(connection, atLeastOnce()).sendMouseHighResScroll(anyShort());
        context.stopFling();
    }
}
