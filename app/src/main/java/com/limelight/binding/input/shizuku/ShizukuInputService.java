package com.limelight.binding.input.shizuku;

import android.os.ParcelFileDescriptor;
import android.util.Log;

import java.io.File;
import java.io.FileInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.util.ArrayList;
import java.util.List;

/**
 * Runs inside a Shizuku UserService process as shell (uid 2000) or root (uid 0).
 * For each keyboard-class input device it opens the node, performs an exclusive
 * evdev grab ({@code EVIOCGRAB}) via {@link #nativeOpenKeyboardGrabbed}, and reads
 * raw key events. The exclusive grab makes the kernel stop delivering those events
 * to the Android input framework, so OEM system shortcuts (e.g. OPPO's Win -> AI)
 * no longer fire during streaming while the host still receives every keypress.
 *
 * <p>On 64-bit, {@code struct input_event} is 24 bytes:
 * {@code timeval(16) + __u16 type + __u16 code + __s32 value}.
 *
 * <p>If the native grab helper fails to load, we fall back to a non-exclusive read
 * of all readable devices (keys still reach the host, but blocking is unavailable).
 */
public class ShizukuInputService extends IInputEventService.Stub {

    private static final String TAG = "ShizukuInputService";
    private static final int EV_KEY = 0x01;
    private static final int INPUT_EVENT_SIZE = 24;

    private static final boolean GRAB_NATIVE_AVAILABLE;
    static {
        boolean ok = false;
        try {
            System.loadLibrary("evdev_grab");
            ok = true;
        } catch (Throwable t) {
            Log.w(TAG, "evdev_grab native lib not loaded, grab unavailable: " + t);
        }
        GRAB_NATIVE_AVAILABLE = ok;
    }

    private static native int nativeOpenKeyboardGrabbed(String path);

    private final List<ParcelFileDescriptor> grabbedPfds = new ArrayList<>();
    private final List<FileInputStream> plainStreams = new ArrayList<>();
    private volatile boolean running = false;

    @Override
    public void start(IInputEventCallback cb) {
        if (running) {
            return;
        }
        running = true;

        File dir = new File("/dev/input");
        File[] devices = dir.listFiles();
        if (devices == null) {
            try {
                cb.onError("/dev/input is not listable in this process");
            } catch (Exception ignored) {
            }
            return;
        }

        int grabbed = 0;
        int readable = 0;
        for (File dev : devices) {
            if (!dev.getName().startsWith("event")) {
                continue;
            }
            final String path = dev.getAbsolutePath();
            try {
                if (GRAB_NATIVE_AVAILABLE) {
                    int fd = nativeOpenKeyboardGrabbed(path);
                    if (fd < 0) {
                        continue; // not a keyboard, or unreadable
                    }
                    ParcelFileDescriptor pfd = ParcelFileDescriptor.adoptFd(fd);
                    grabbedPfds.add(pfd);
                    grabbed++;
                    readable++;
                    startReader(new FileInputStream(pfd.getFileDescriptor()), path, cb);
                } else {
                    // Fallback: read everything non-exclusively.
                    FileInputStream fis = new FileInputStream(dev);
                    plainStreams.add(fis);
                    readable++;
                    startReader(fis, path, cb);
                }
            } catch (Exception e) {
                // Not readable under current uid — silently skip.
            }
        }

        Log.i(TAG, "started uid=" + android.os.Process.myUid()
                + " grabNative=" + GRAB_NATIVE_AVAILABLE
                + " grabbed=" + grabbed + " readable=" + readable);
    }

    private void startReader(InputStream in, String path, IInputEventCallback cb) {
        Thread t = new Thread(() -> {
            ByteBuffer bb = ByteBuffer.allocate(INPUT_EVENT_SIZE).order(ByteOrder.LITTLE_ENDIAN);
            byte[] buf = bb.array();
            try {
                while (running) {
                    int n = in.read(buf);
                    if (n < 0) {
                        break; // EOF / closed
                    }
                    if (n != INPUT_EVENT_SIZE) {
                        continue;
                    }
                    int type = bb.getShort(16) & 0xFFFF;
                    if (type != EV_KEY) {
                        continue;
                    }
                    int code = bb.getShort(18) & 0xFFFF;
                    // Skip mouse/touch buttons (BTN_* >= 0x100); keep keyboard KEY_*.
                    if (code >= 0x100) {
                        continue;
                    }
                    int value = bb.getInt(20);
                    try {
                        cb.onInputKeyEvent(code, value, path);
                    } catch (Throwable ignored) {
                        // App process may have died; keep reading.
                    }
                }
            } catch (IOException e) {
                // fd closed during stop() — expected shutdown path.
            }
        });
        t.setName("evdev-" + path.substring(path.lastIndexOf('/') + 1));
        t.setDaemon(true);
        t.start();
    }

    @Override
    public void stop() {
        running = false;
        for (ParcelFileDescriptor pfd : grabbedPfds) {
            try {
                pfd.close(); // closing the fd releases the EVIOCGRAB
            } catch (Exception ignored) {
            }
        }
        grabbedPfds.clear();
        for (FileInputStream fis : plainStreams) {
            try {
                fis.close();
            } catch (Exception ignored) {
            }
        }
        plainStreams.clear();
    }
}
