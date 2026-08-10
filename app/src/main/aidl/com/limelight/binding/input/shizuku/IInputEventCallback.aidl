// AIDL callback used by the Shizuku UserService to push raw evdev key events
// back to the app process.
package com.limelight.binding.input.shizuku;

interface IInputEventCallback {
    // linuxKeyCode: the evdev key code (e.g. 1 = KEY_ESC, 30 = KEY_A, 158 = KEY_BACK)
    // value: 0 = key up, 1 = key down, 2 = key repeat
    oneway void onInputKeyEvent(int linuxKeyCode, int value, String devicePath);
    oneway void onError(String message);
}
