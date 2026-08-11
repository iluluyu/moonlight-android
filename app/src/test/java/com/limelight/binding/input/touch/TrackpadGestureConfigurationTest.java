package com.limelight.binding.input.touch;

import static org.junit.Assert.assertEquals;

import org.junit.Test;

public class TrackpadGestureConfigurationTest {
    @Test
    public void threeFingerMappingKeepsCurrentWindowsBehavior() {
        assertEquals(TrackpadGestureConfiguration.Action.PREVIOUS_APP,
                TrackpadGestureConfiguration.resolve(
                        3, TrackpadGestureConfiguration.Direction.LEFT));
        assertEquals(TrackpadGestureConfiguration.Action.NEXT_APP,
                TrackpadGestureConfiguration.resolve(
                        3, TrackpadGestureConfiguration.Direction.RIGHT));
        assertEquals(TrackpadGestureConfiguration.Action.OPEN_TASK_VIEW,
                TrackpadGestureConfiguration.resolve(
                        3, TrackpadGestureConfiguration.Direction.UP));
        assertEquals(TrackpadGestureConfiguration.Action.SHOW_DESKTOP,
                TrackpadGestureConfiguration.resolve(
                        3, TrackpadGestureConfiguration.Direction.DOWN));
    }

    @Test
    public void fourFingerMappingUsesDirectHorizontalDirection() {
        assertEquals(TrackpadGestureConfiguration.Action.PREVIOUS_DESKTOP,
                TrackpadGestureConfiguration.resolve(
                        4, TrackpadGestureConfiguration.Direction.LEFT));
        assertEquals(TrackpadGestureConfiguration.Action.NEXT_DESKTOP,
                TrackpadGestureConfiguration.resolve(
                        4, TrackpadGestureConfiguration.Direction.RIGHT));
        assertEquals(TrackpadGestureConfiguration.Action.OPEN_TASK_VIEW,
                TrackpadGestureConfiguration.resolve(
                        4, TrackpadGestureConfiguration.Direction.UP));
        assertEquals(TrackpadGestureConfiguration.Action.SHOW_DESKTOP,
                TrackpadGestureConfiguration.resolve(
                        4, TrackpadGestureConfiguration.Direction.DOWN));
    }

    @Test
    public void fourFingerTapOpensNotificationCenter() {
        assertEquals(TrackpadGestureConfiguration.Action.OPEN_NOTIFICATION_CENTER,
                TrackpadGestureConfiguration.resolveTap(4));
        assertEquals(TrackpadGestureConfiguration.Action.NONE,
                TrackpadGestureConfiguration.resolveTap(3));
    }

    @Test
    public void fourFingerUpRestoresWindowsAfterShowDesktop() {
        assertEquals(TrackpadGestureConfiguration.Action.RESTORE_WINDOWS,
                TrackpadGestureConfiguration.resolveFourFingerSwipe(
                        TrackpadGestureConfiguration.Direction.UP, true));
        assertEquals(TrackpadGestureConfiguration.Action.NONE,
                TrackpadGestureConfiguration.resolveFourFingerSwipe(
                        TrackpadGestureConfiguration.Direction.DOWN, true));
        assertEquals(TrackpadGestureConfiguration.Action.OPEN_TASK_VIEW,
                TrackpadGestureConfiguration.resolveFourFingerSwipe(
                        TrackpadGestureConfiguration.Direction.UP, false));
    }
}
