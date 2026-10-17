// AIDL interface implemented by the UserService that runs with shell/root
// privileges (via Shizuku) and reads /dev/input/event* directly.
package com.limelight.binding.input.shizuku;

import com.limelight.binding.input.shizuku.IInputEventCallback;

interface IInputEventService {
    // Begin reading all readable keyboard event devices, forwarding key events to cb.
    // When captureTouchpad is set, physical multitouch touchpads are exclusively
    // grabbed as well and their raw contact frames are forwarded to cb.
    void start(IInputEventCallback cb, boolean captureTouchpad);
    // (Re)try grabbing touchpads that are not grabbed yet. Idempotent: already grabbed
    // device nodes are skipped. Used after a transient EBUSY while the previous
    // UserService process was still releasing its grabs, and when a keyboard with a
    // touchpad is docked during a stream.
    void refreshTouchpadCapture();
    // Stop reading and release all file descriptors.
    void stop();
}
