// AIDL interface implemented by the UserService that runs with shell/root
// privileges (via Shizuku) and reads /dev/input/event* directly.
package com.limelight.binding.input.shizuku;

import com.limelight.binding.input.shizuku.IInputEventCallback;

interface IInputEventService {
    // Begin reading all readable keyboard event devices, forwarding key events to cb.
    void start(IInputEventCallback cb);
    // Stop reading and release all file descriptors.
    void stop();
}
