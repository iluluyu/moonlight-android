package com.limelight.binding.input.shizuku;

import android.os.ParcelFileDescriptor;
import android.os.SystemClock;
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
    private static final int EV_SYN = 0x00;
    private static final int EV_ABS = 0x03;
    private static final int SYN_REPORT = 0x00;
    private static final int ABS_MT_SLOT = 0x2f;
    private static final int ABS_MT_POSITION_X = 0x35;
    private static final int ABS_MT_POSITION_Y = 0x36;
    private static final int ABS_MT_TRACKING_ID = 0x39;
    private static final int ABS_MT_TOUCH_MAJOR = 0x30;
    private static final int BTN_LEFT = 0x110;
    private static final int MT_SLOT_COUNT = 16;
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
    private static native int nativeOpenTouchpadGrabbed(String path);
    private static native int[] nativeTouchpadAbsInfo(int fd);

    // Gesture types reported via IInputEventCallback.onTrackpadGesture.
    public static final int GESTURE_SWIPE_LEFT = 1;
    public static final int GESTURE_SWIPE_RIGHT = 2;
    public static final int GESTURE_SWIPE_UP = 3;
    public static final int GESTURE_SWIPE_DOWN = 4;
    public static final int GESTURE_TAP = 5;
    // Lifecycle: 3 fingers landed / lifted (used to bind Alt+Tab to the physical
    // gesture like Windows does, instead of timers).
    public static final int GESTURE_START = 6;
    public static final int GESTURE_END = 7;

    private final List<ParcelFileDescriptor> grabbedPfds = new ArrayList<>();
    private final List<FileInputStream> plainStreams = new ArrayList<>();
    private volatile boolean running = false;
    private boolean touchpadCaptureStarted;
    private final java.util.Set<String> grabbedTouchpadPaths = new java.util.HashSet<>();
    /** Latest callback; readers read this field so a rebind (start() called again while
     *  running, which Shizuku does) just updates the callback instead of tearing down
     *  and restarting all reader threads (which previously dropped all key events). */
    private volatile IInputEventCallback cb;

    @Override
    public synchronized void start(IInputEventCallback cb, boolean captureTouchpad) {
        // Always remember the latest callback. If readers are already running (a rebind
        // across streams, or Shizuku firing onServiceConnected more than once per bind),
        // just update the callback and return — restarting readers on every call
        // previously made them thrash and silently drop all key events.
        this.cb = cb;
        // Kill any leftover UserService processes from previous streams. Shizuku does
        // not reap them (and our destroy() isn't reliably invoked), so they linger and
        // keep holding the exclusive keyboard grab — without this, a new stream's grab
        // fails and every key is silently lost (the "breaks on the Nth stream" bug).
        killSiblingUserServiceProcesses();
        if (running) {
            // A new stream reuses this process. Keep the readers alive but retry the
            // touchpad grab, which may have been skipped when the keyboard was docked
            // after the previous stream started.
            if (captureTouchpad && !touchpadCaptureStarted) {
                touchpadCaptureStarted = true;
                startTouchpadCapture();
            }
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
                    startReader(new FileInputStream(pfd.getFileDescriptor()), path);
                } else {
                    // Fallback: read everything non-exclusively.
                    FileInputStream fis = new FileInputStream(dev);
                    plainStreams.add(fis);
                    readable++;
                    startReader(fis, path);
                }
            } catch (Exception e) {
                // Not readable under current uid — silently skip.
            }
        }

        Log.i(TAG, "started uid=" + android.os.Process.myUid()
                + " grabNative=" + GRAB_NATIVE_AVAILABLE
                + " grabbed=" + grabbed + " readable=" + readable);

        if (captureTouchpad && !touchpadCaptureStarted) {
            touchpadCaptureStarted = true;
            startTouchpadCapture();
        }
    }

    /**
     * Exclusively grab the physical multitouch touchpad (if any) and forward raw
     * contact frames to the app.
     *
     * <p>Android 17 / ColorOS 17's {@code TouchpadInputMapper} stops delivering raw
     * multi-finger samples to apps even while pointer capture is active: apps get
     * {@code SOURCE_MOUSE_RELATIVE} with {@code pointerCount == 1}, taps become
     * {@code BUTTON_PRESS} and scroll becomes {@code AXIS_VSCROLL}. Finger-count
     * gestures can therefore only be recognised from the device node itself.</p>
     *
     * <p>The grab is released by {@link #stop()} or by process death (closing the fd
     * drops EVIOCGRAB), so a crash restores system touchpad input automatically.</p>
     */
    private void startTouchpadCapture() {
        if (!GRAB_NATIVE_AVAILABLE) {
            return;
        }

        File[] devices = new File("/dev/input").listFiles();
        if (devices == null) {
            return;
        }

        for (File dev : devices) {
            if (!dev.getName().startsWith("event")) {
                continue;
            }

            final String path = dev.getAbsolutePath();
            if (grabbedTouchpadPaths.contains(path)) {
                continue;
            }

            int fd = nativeOpenTouchpadGrabbed(path);
            if (fd < 0) {
                continue;
            }

            int[] absInfo = nativeTouchpadAbsInfo(fd);
            if (absInfo == null || absInfo.length != 3) {
                try {
                    ParcelFileDescriptor adopt = ParcelFileDescriptor.adoptFd(fd);
                    adopt.close();
                } catch (Exception ignored) {
                }
                continue;
            }

            ParcelFileDescriptor pfd = ParcelFileDescriptor.adoptFd(fd);
            grabbedPfds.add(pfd);
            grabbedTouchpadPaths.add(path);
            startTouchpadReader(new FileInputStream(pfd.getFileDescriptor()), path,
                    absInfo[0], absInfo[1], absInfo[2]);
            Log.i(TAG, "touchpad grabbed " + path + " xMax=" + absInfo[0]
                    + " yMax=" + absInfo[1] + " majorMax=" + absInfo[2]);
            // Keep going: OPPO exposes the same physical pad twice (pogo_touchpad and
            // the "Keyboard Touchpad" framework node). Both must be grabbed so the
            // system stops acting on either; the app de-duplicates identical frames.
        }
    }

    @Override
    public synchronized void refreshTouchpadCapture() {
        if (!touchpadCaptureStarted) {
            touchpadCaptureStarted = true;
        }
        startTouchpadCapture();
    }

    private void startTouchpadReader(InputStream in, String path, int xMax, int yMax,
            int touchMajorMax) {
        Thread t = new Thread(() -> {
            ByteBuffer bb = ByteBuffer.allocate(INPUT_EVENT_SIZE).order(ByteOrder.LITTLE_ENDIAN);
            byte[] buf = bb.array();
            int[] slotX = new int[MT_SLOT_COUNT];
            int[] slotY = new int[MT_SLOT_COUNT];
            int[] slotId = new int[MT_SLOT_COUNT];
            int[] slotSize = new int[MT_SLOT_COUNT];
            boolean[] active = new boolean[MT_SLOT_COUNT];
            final int majorMax = Math.max(1, touchMajorMax);
            int currentSlot = 0;
            int buttonState = 0;

            try {
                while (running) {
                    int n = in.read(buf);
                    if (n < 0) {
                        break;
                    }
                    if (n != INPUT_EVENT_SIZE) {
                        continue;
                    }

                    int type = bb.getShort(16) & 0xFFFF;
                    int code = bb.getShort(18) & 0xFFFF;
                    int value = bb.getInt(20);

                    if (type == EV_ABS) {
                        if (code == ABS_MT_SLOT) {
                            if (value >= 0 && value < MT_SLOT_COUNT) {
                                currentSlot = value;
                            }
                        } else if (code == ABS_MT_TRACKING_ID) {
                            active[currentSlot] = value >= 0;
                            slotId[currentSlot] = value + 1;
                        } else if (code == ABS_MT_POSITION_X) {
                            slotX[currentSlot] = value;
                        } else if (code == ABS_MT_POSITION_Y) {
                            slotY[currentSlot] = value;
                        } else if (code == ABS_MT_TOUCH_MAJOR) {
                            // Normalise to 0..1000 so the app does not depend on the
                            // device's own touch-major range.
                            slotSize[currentSlot] = value * 1000 / majorMax;
                        }
                    } else if (type == EV_KEY && code == BTN_LEFT) {
                        if (value != 0) {
                            buttonState |= 1;
                        } else {
                            buttonState &= ~1;
                        }
                    } else if (type == EV_SYN && code == SYN_REPORT) {
                        int count = 0;
                        for (int i = 0; i < MT_SLOT_COUNT; i++) {
                            if (active[i]) {
                                count++;
                            }
                        }

                        int[] ids = new int[count];
                        int[] xs = new int[count];
                        int[] ys = new int[count];
                        int[] sizes = new int[count];
                        int index = 0;
                        for (int i = 0; i < MT_SLOT_COUNT && index < count; i++) {
                            if (active[i]) {
                                ids[index] = slotId[i];
                                xs[index] = slotX[i];
                                ys[index] = slotY[i];
                                sizes[index] = slotSize[i];
                                index++;
                            }
                        }

                        IInputEventCallback callback = cb;
                        if (callback != null) {
                            try {
                                callback.onTouchpadFrame(ids, xs, ys, sizes,
                                        SystemClock.uptimeMillis(), xMax, yMax, buttonState);
                            } catch (Throwable ignored) {
                                // App process may have died; keep reading.
                            }
                        }
                    }
                }
            } catch (IOException e) {
                // fd closed during stop() — expected shutdown path.
            }
        });
        t.setName("evdev-touchpad");
        t.setDaemon(true);
        t.start();
    }


    private void startReader(InputStream in, String path) {
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
    public synchronized void stop() {
        running = false;
        for (ParcelFileDescriptor pfd : grabbedPfds) {
            try {
                pfd.close(); // closing the fd releases the EVIOCGRAB
            } catch (Exception ignored) {
            }
        }
        grabbedPfds.clear();
        grabbedTouchpadPaths.clear();
        touchpadCaptureStarted = false;
        for (FileInputStream fis : plainStreams) {
            try {
                fis.close();
            } catch (Exception ignored) {
            }
        }
        plainStreams.clear();
    }

    /** Kill leftover UserService processes from previous streams so this one becomes
     *  the sole holder of the keyboard grab. Runs as the same (shell) uid as those
     *  siblings, so killProcess is allowed. */
    private static void killSiblingUserServiceProcesses() {
        int myPid = android.os.Process.myPid();
        String[] pids = new File("/proc").list();
        if (pids == null) {
            return;
        }
        for (String s : pids) {
            int pid;
            try {
                pid = Integer.parseInt(s);
            } catch (NumberFormatException e) {
                continue;
            }
            if (pid == myPid || pid <= 1) {
                continue;
            }
            String cmdline = readCmdline(pid);
            if (cmdline.contains(":evdev")) {
                android.util.Log.i(TAG, "killing leftover UserService pid=" + pid + " (" + cmdline + ")");
                android.os.Process.killProcess(pid);
            }
        }
    }

    private static String readCmdline(int pid) {
        try (java.io.FileInputStream fis = new java.io.FileInputStream("/proc/" + pid + "/cmdline")) {
            byte[] buf = new byte[512];
            int n = fis.read(buf);
            if (n <= 0) {
                return "";
            }
            int end = 0;
            while (end < n && buf[end] != 0) {
                end++;
            }
            return new String(buf, 0, end);
        } catch (Exception e) {
            return "";
        }
    }

    /** Shizuku calls this on unbindUserService(remove=true). The UserService process
     *  is otherwise left running and keeps holding the exclusive keyboard grab, which
     *  made the next stream's fresh UserService fail to grab and silently drop all
     *  keys. Exiting here releases the grab (kernel closes the fds on process death). */
    public void destroy() {
        android.util.Log.i(TAG, "destroy: releasing grab and exiting process");
        stop();
        android.os.Process.killProcess(android.os.Process.myPid());
    }
}
