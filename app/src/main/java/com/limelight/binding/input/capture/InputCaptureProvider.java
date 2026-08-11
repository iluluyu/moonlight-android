package com.limelight.binding.input.capture;

import android.view.MotionEvent;

public abstract class InputCaptureProvider {
    protected boolean isCapturing;
    protected boolean isCursorVisible;

    public void enableCapture() {
        isCapturing = true;
        hideCursor();
    }
    public void disableCapture() {
        isCapturing = false;
        showCursor();
    }

    public void destroy() {}

    public boolean isCapturingEnabled() {
        return isCapturing;
    }

    public boolean isCapturingActive() {
        return isCapturing;
    }

    public void showCursor() {
        isCursorVisible = true;
    }

    public void hideCursor() {
        isCursorVisible = false;
    }

    public boolean eventHasRelativeMouseAxes(MotionEvent event) {
        return false;
    }

    public float getRelativeAxisX(MotionEvent event, int pointerIndex) {
        return 0;
    }

    public float getRelativeAxisX(MotionEvent event) {
        return getRelativeAxisX(event, 0);
    }

    public float getRelativeAxisY(MotionEvent event, int pointerIndex) {
        return 0;
    }

    public float getRelativeAxisY(MotionEvent event) {
        return getRelativeAxisY(event, 0);
    }

    /** Whether this provider can expose captured relative-axis history sample by
     *  sample. Providers that return false continue using the aggregate getters. */
    public boolean supportsRelativeAxisHistory() {
        return false;
    }

    public float getCurrentRelativeAxisX(MotionEvent event, int pointerIndex) {
        return getRelativeAxisX(event, pointerIndex);
    }

    public float getCurrentRelativeAxisY(MotionEvent event, int pointerIndex) {
        return getRelativeAxisY(event, pointerIndex);
    }

    public float getHistoricalRelativeAxisX(MotionEvent event, int pointerIndex, int historyIndex) {
        return 0;
    }

    public float getHistoricalRelativeAxisY(MotionEvent event, int pointerIndex, int historyIndex) {
        return 0;
    }

    public void onWindowFocusChanged(boolean focusActive) {}
}
