package com.limelight.binding.input.touch;

import android.view.InputDevice;
import android.view.MotionEvent;

/**
 * Source classification for physical touchpads.
 *
 * <p>The source reported on each {@link MotionEvent} is NOT stable across Android
 * versions or vendor builds:</p>
 *
 * <ul>
 *   <li>While pointer capture is active, AOSP's {@code CapturedTouchpadEventConverter}
 *       emits events with exactly {@link InputDevice#SOURCE_TOUCHPAD}.</li>
 *   <li>{@code TouchpadInputMapper} (the newer multi-touch touchpad path) declares the
 *       device itself as {@code SOURCE_MOUSE | SOURCE_TOUCHPAD}, and vendor builds may
 *       forward that composite value per event, or add {@code SOURCE_TOUCHSCREEN} for a
 *       keyboard whose pad shares an {@link InputDevice} with the panel.</li>
 * </ul>
 *
 * <p>Every historical {@code event.getSource() == InputDevice.SOURCE_TOUCHPAD} test in
 * this app silently stopped matching as soon as a build reported any extra bit, which
 * drops the whole finger-count gesture pipeline (3/4-finger swipes, pinch, scrolling,
 * tap-to-click) into the absolute-touchscreen path. All source checks must therefore be
 * bitmask tests.</p>
 */
public final class TrackpadInputSource {
    private TrackpadInputSource() {
    }

    /** True when the source value carries the touchpad bit (composite-safe). */
    public static boolean hasTouchpadSource(int source) {
        return (source & InputDevice.SOURCE_TOUCHPAD) == InputDevice.SOURCE_TOUCHPAD;
    }

    public static boolean hasTouchscreenSource(int source) {
        return (source & InputDevice.SOURCE_TOUCHSCREEN) == InputDevice.SOURCE_TOUCHSCREEN;
    }

    /** True for captured-mouse sources, whose deltas live in {@code AXIS_X}/{@code AXIS_Y}
     *  rather than the touchpad-only {@code AXIS_RELATIVE_X}/{@code AXIS_RELATIVE_Y}. */
    public static boolean isMouseRelativeSource(int source) {
        return hasTouchpadSource(source)
                ? false
                : (source & InputDevice.SOURCE_MOUSE_RELATIVE) == InputDevice.SOURCE_MOUSE_RELATIVE;
    }

    /**
     * True when the event belongs to the physical-touchpad pipeline (relative pointer,
     * finger-count gestures, pinch) rather than to a mouse or to the panel itself.
     *
     * <p>The device-declared fallback only applies when the event is explicitly not a
     * touchscreen event, so a real panel that happens to share its {@link InputDevice}
     * with a keyboard touchpad keeps absolute touch handling.</p>
     */
    public static boolean isTrackpadEvent(int eventSource, int deviceSources, int toolType) {
        if (hasTouchpadSource(eventSource)) {
            return true;
        }
        if (toolType == MotionEvent.TOOL_TYPE_MOUSE) {
            return false;
        }
        if (hasTouchscreenSource(eventSource)) {
            return false;
        }
        return hasTouchpadSource(deviceSources);
    }

    /** Whether this source should use {@code AXIS_RELATIVE_X}/{@code AXIS_RELATIVE_Y}
     *  instead of the raw {@code AXIS_X}/{@code AXIS_Y} delta. */
    public static boolean usesRelativeAxes(int source) {
        return !isMouseRelativeSource(source);
    }
}
