// AIDL callback used by the Shizuku UserService to push raw evdev input back to the
// app process.
package com.limelight.binding.input.shizuku;

interface IInputEventCallback {
    // linuxKeyCode: the evdev key code (e.g. 1 = KEY_ESC, 30 = KEY_A, 158 = KEY_BACK)
    // value: 0 = key up, 1 = key down, 2 = key repeat
    oneway void onInputKeyEvent(int linuxKeyCode, int value, String devicePath);

    // One physical multitouch touchpad frame (emitted per SYN_REPORT).
    //
    // Android 17's TouchpadInputMapper no longer forwards raw multi-finger samples to
    // apps even with pointer capture active: it reports SOURCE_MOUSE_RELATIVE with
    // pointerCount == 1 and synthesizes taps as BUTTON_PRESS plus scroll as
    // AXIS_VSCROLL. Reading the device node directly is then the only way to keep
    // finger-count gestures (two-finger scroll feel, pinch, three/four-finger swipes).
    //
    // ids: parallel array of stable contact ids (evdev tracking ids + 1, so 0 stays
    //      available for "unknown").
    // xs/ys: parallel arrays of active contact positions in the pad's own coordinate
    //        space (0..xMax, 0..yMax), ordered by evdev slot.
    // sizes: parallel array of normalized contact area (0..1000) from
    //        ABS_MT_TOUCH_MAJOR, used to recognise noise and resting thumbs.
    // eventTimeMs: CLOCK_MONOTONIC millis, comparable with SystemClock.uptimeMillis().
    // buttonState: bit 0 set while the physical (button-pad) left button is held.
    oneway void onTouchpadFrame(in int[] ids, in int[] xs, in int[] ys, in int[] sizes,
            long eventTimeMs, int xMax, int yMax, int buttonState);

    oneway void onError(String message);
}
