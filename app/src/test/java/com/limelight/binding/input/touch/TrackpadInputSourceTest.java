package com.limelight.binding.input.touch;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import android.view.InputDevice;
import android.view.MotionEvent;

import org.junit.Test;

/**
 * Regression tests for the touchpad source classification.
 *
 * <p>These exist because the app used to compare {@code event.getSource()} with
 * {@code SOURCE_TOUCHPAD} for equality. That holds only for AOSP's captured touchpad
 * converter; {@code TouchpadInputMapper} declares and can forward
 * {@code SOURCE_MOUSE | SOURCE_TOUCHPAD}, and a keyboard touchpad may share its node
 * with the panel ({@code SOURCE_TOUCHSCREEN} included). Any extra bit silently dropped
 * the entire finger-count gesture pipeline.</p>
 */
public class TrackpadInputSourceTest {
    private static final int TOUCHPAD = InputDevice.SOURCE_TOUCHPAD;
    private static final int MOUSE = InputDevice.SOURCE_MOUSE;
    private static final int MOUSE_RELATIVE = InputDevice.SOURCE_MOUSE_RELATIVE;
    private static final int TOUCHSCREEN = InputDevice.SOURCE_TOUCHSCREEN;
    private static final int KEYBOARD = InputDevice.SOURCE_KEYBOARD;
    private static final int FINGER = MotionEvent.TOOL_TYPE_FINGER;
    private static final int MOUSE_TOOL = MotionEvent.TOOL_TYPE_MOUSE;

    @Test
    public void capturedTouchpadEventIsTrackpad() {
        assertTrue(TrackpadInputSource.isTrackpadEvent(TOUCHPAD, 0, FINGER));
        // AOSPs captured converter always reports exactly SOURCE_TOUCHPAD.
        assertTrue(TrackpadInputSource.isTrackpadEvent(TOUCHPAD, TOUCHPAD, MOUSE_TOOL));
    }

    @Test
    public void compositeTouchpadSourceIsTrackpad() {
        int composite = MOUSE | TOUCHPAD;
        assertTrue(TrackpadInputSource.hasTouchpadSource(composite));
        assertTrue(TrackpadInputSource.isTrackpadEvent(composite, composite, FINGER));

        int withTouchscreen = TOUCHSCREEN | TOUCHPAD;
        assertTrue(TrackpadInputSource.isTrackpadEvent(withTouchscreen, withTouchscreen, FINGER));
    }

    @Test
    public void deviceDeclaredTouchpadIsTrackpadWhenEventIsNotThePanel() {
        assertTrue(TrackpadInputSource.isTrackpadEvent(0, TOUCHPAD, FINGER));
        assertTrue(TrackpadInputSource.isTrackpadEvent(KEYBOARD, TOUCHPAD, FINGER));
    }

    @Test
    public void panelEventOnSharedDeviceStaysAbsoluteTouch() {
        // A keyboard touchpad sharing its InputDevice with the panel must not turn real
        // panel touches into trackpad gestures.
        assertFalse(TrackpadInputSource.isTrackpadEvent(TOUCHSCREEN, TOUCHPAD, FINGER));
        assertFalse(TrackpadInputSource.isTrackpadEvent(
                TOUCHSCREEN | KEYBOARD, TOUCHPAD | TOUCHSCREEN, FINGER));
    }

    @Test
    public void mouseAndPlainTouchscreenAreNotTrackpads() {
        assertFalse(TrackpadInputSource.isTrackpadEvent(MOUSE, MOUSE, MOUSE_TOOL));
        assertFalse(TrackpadInputSource.isTrackpadEvent(MOUSE_RELATIVE, MOUSE_RELATIVE, MOUSE_TOOL));
        assertFalse(TrackpadInputSource.isTrackpadEvent(TOUCHSCREEN, TOUCHSCREEN, FINGER));
        assertFalse(TrackpadInputSource.isTrackpadEvent(MOUSE, 0, MOUSE_TOOL));
    }

    @Test
    public void relativeAxisSelectionFollowsSourceFamily() {
        assertTrue(TrackpadInputSource.usesRelativeAxes(TOUCHPAD));
        assertTrue(TrackpadInputSource.usesRelativeAxes(MOUSE | TOUCHPAD));
        // Captured mice put their deltas into the absolute axis slots.
        assertFalse(TrackpadInputSource.usesRelativeAxes(MOUSE_RELATIVE));
        assertTrue(TrackpadInputSource.isMouseRelativeSource(MOUSE_RELATIVE));
        assertFalse(TrackpadInputSource.isMouseRelativeSource(MOUSE | TOUCHPAD));
    }
}
