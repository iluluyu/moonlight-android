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

    // ABS_MT multitouch codes.
    private static final int EV_SYN = 0x00;
    private static final int SYN_REPORT = 0x00;
    private static final int EV_ABS = 0x03;
    private static final int ABS_MT_SLOT = 0x2f;
    private static final int ABS_MT_POSITION_X = 0x35;
    private static final int ABS_MT_POSITION_Y = 0x36;
    private static final int ABS_MT_TRACKING_ID = 0x39;
    private static final int MAX_SLOTS = 20;
    private static final int GESTURE_FINGERS = 3;
    private static final int SWIPE_X_THRESHOLD = 150; // raw ABS units
    private static final int SWIPE_Y_THRESHOLD = 120;
    private static final long TAP_MAX_DURATION_MS = 180; // libinput-style tap window
    private static final int TAP_MAX_MOVE = 30;
    private static final long GESTURE_COOLDOWN_MS = 400;

    private static native int nativeOpenTrackpadObserver(String path);

    private final List<ParcelFileDescriptor> grabbedPfds = new ArrayList<>();
    private final List<ParcelFileDescriptor> trackpadPfds = new ArrayList<>();
    private final List<FileInputStream> plainStreams = new ArrayList<>();
    private volatile boolean running = false;
    /** Latest callback; readers read this field so a rebind (start() called again while
     *  running, which Shizuku does) just updates the callback instead of tearing down
     *  and restarting all reader threads (which previously dropped all key events). */
    private volatile IInputEventCallback cb;

    @Override
    public synchronized void start(IInputEventCallback cb) {
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
    }

    /** Reads ABS_MT multitouch from a trackpad (no grab) and detects 3-finger
     *  swipes/taps that ColorOS swallows at the framework level. Reports gestures via
     *  {@link #report(int)}. */
    private void startTrackpadReader(InputStream in) {
        Thread t = new Thread(() -> {
            int[] slotId = new int[MAX_SLOTS];
            int[] slotX = new int[MAX_SLOTS];
            int[] slotY = new int[MAX_SLOTS];
            java.util.Arrays.fill(slotId, -1);
            int currentSlot = 0;
            boolean gestureActive = false;
            long gestureStartMs = 0;
            long lastFireMs = 0;
            int prevFingerCount = 0;
            float startX = 0, startY = 0;
            float lastX = 0, lastY = 0;
            float cumDX = 0, cumDY = 0;

            ByteBuffer bb = ByteBuffer.allocate(INPUT_EVENT_SIZE).order(ByteOrder.LITTLE_ENDIAN);
            byte[] buf = bb.array();
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
                            currentSlot = value;
                        } else if (code == ABS_MT_TRACKING_ID) {
                            if (currentSlot >= 0 && currentSlot < MAX_SLOTS) {
                                slotId[currentSlot] = value;
                            }
                        } else if (code == ABS_MT_POSITION_X) {
                            if (currentSlot >= 0 && currentSlot < MAX_SLOTS) {
                                slotX[currentSlot] = value;
                            }
                        } else if (code == ABS_MT_POSITION_Y) {
                            if (currentSlot >= 0 && currentSlot < MAX_SLOTS) {
                                slotY[currentSlot] = value;
                            }
                        }
                    } else if (type == EV_SYN && code == SYN_REPORT) {
                        int count = 0;
                        float cx = 0, cy = 0;
                        for (int i = 0; i < MAX_SLOTS; i++) {
                            if (slotId[i] >= 0) {
                                count++;
                                cx += slotX[i];
                                cy += slotY[i];
                            }
                        }
                        if (count > 0) {
                            cx /= count;
                            cy /= count;
                        }
                        long now = System.currentTimeMillis();

                        // Report the physical gesture lifecycle (3 fingers down / up)
                        // so the app can bind Alt+Tab to it natively.
                        if (prevFingerCount < GESTURE_FINGERS && count >= GESTURE_FINGERS) {
                            report(GESTURE_START);
                        } else if (prevFingerCount >= GESTURE_FINGERS && count < GESTURE_FINGERS) {
                            report(GESTURE_END);
                        }
                        prevFingerCount = count;

                        if (count >= GESTURE_FINGERS) {
                            if (!gestureActive) {
                                // Only start a new gesture after a cooldown so a long
                                // swipe doesn't re-trigger from the same hand motion.
                                if (now - lastFireMs > GESTURE_COOLDOWN_MS) {
                                    gestureActive = true;
                                    gestureStartMs = now;
                                    startX = lastX = cx;
                                    startY = lastY = cy;
                                    cumDX = cumDY = 0;
                                }
                            } else {
                                cumDX += cx - lastX;
                                cumDY += cy - lastY;
                                lastX = cx;
                                lastY = cy;
                                if (Math.abs(cumDX) > SWIPE_X_THRESHOLD
                                        && Math.abs(cumDX) > Math.abs(cumDY) * 1.5f) {
                                    report(cumDX > 0 ? GESTURE_SWIPE_RIGHT : GESTURE_SWIPE_LEFT);
                                    lastFireMs = now;
                                    gestureActive = false;
                                } else if (Math.abs(cumDY) > SWIPE_Y_THRESHOLD
                                        && Math.abs(cumDY) > Math.abs(cumDX) * 1.5f) {
                                    report(cumDY > 0 ? GESTURE_SWIPE_DOWN : GESTURE_SWIPE_UP);
                                    lastFireMs = now;
                                    gestureActive = false;
                                }
                            }
                        } else if (gestureActive) {
                            // Fingers lifted (or dropped below 3): was it a tap?
                            long dur = now - gestureStartMs;
                            float move = Math.abs(lastX - startX) + Math.abs(lastY - startY);
                            if (dur < TAP_MAX_DURATION_MS && move < TAP_MAX_MOVE) {
                                report(GESTURE_TAP);
                            }
                            gestureActive = false;
                        }
                    }
                }
            } catch (IOException e) {
                // fd closed during stop() — expected shutdown path.
            }
        });
        t.setName("trackpad-gesture");
        t.setDaemon(true);
        t.start();
    }

    private void report(int gestureType) {
        final IInputEventCallback c = cb;
        if (c != null) {
            try {
                c.onTrackpadGesture(gestureType);
                Log.i(TAG, "gesture=" + gestureType);
            } catch (Throwable ignored) {
            }
        }
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
        for (ParcelFileDescriptor pfd : trackpadPfds) {
            try {
                pfd.close();
            } catch (Exception ignored) {
            }
        }
        trackpadPfds.clear();
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
