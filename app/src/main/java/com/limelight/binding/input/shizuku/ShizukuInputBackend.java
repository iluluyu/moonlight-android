package com.limelight.binding.input.shizuku;

import android.content.ComponentName;
import android.content.ServiceConnection;
import android.os.IBinder;

import com.limelight.BuildConfig;
import com.limelight.LimeLog;
import com.limelight.binding.input.KeyboardTranslator;
import com.limelight.nvstream.NvConnection;
import com.limelight.nvstream.input.KeyboardPacket;
import com.limelight.utils.KeyMapper;

import rikka.shizuku.Shizuku;

/**
 * App-side manager for the Shizuku keyboard-capture backend.
 *
 * <p><b>Lifecycle:</b> the UserService is bound <i>once</i> (persistent singleton) and
 * kept alive; each stream simply calls {@link #startReading}/{@link #stopReading} to
 * toggle the exclusive keyboard grab and reader threads on/off in the SAME process.
 * Earlier designs unbound/rebound per stream, which churned the UserService process,
 * left stale processes holding the keyboard grab, and silently dropped keys on the
 * Nth stream.
 *
 * <p>Raw evdev key events are translated to host keyboard packets via
 * {@link KeyMapper} (Linux keycode -> Windows VK), with modifier tracking and
 * KEY_BACK (158) -> VK_ESCAPE for the OPPO Esc key. App-internal combos
 * (Ctrl+Alt+Shift+Q/Z/C) are detected here and forwarded via
 * {@link SpecialComboListener}, since the exclusive grab stops them from reaching
 * {@code Game.handleKeyDown}.
 */
public class ShizukuInputBackend extends IInputEventCallback.Stub {

    /** Notified (main thread) when capture becomes active/inactive. */
    public interface StateListener {
        void onActiveChanged(boolean active);
    }

    /** Notified when an app-internal combo (Ctrl+Alt+Shift+Q/Z/C) is pressed. */
    public interface SpecialComboListener {
        void onSpecialCombo(int linuxKeyCode);
    }


    private static ShizukuInputBackend instance;

    public static synchronized ShizukuInputBackend getInstance() {
        if (instance == null) {
            instance = new ShizukuInputBackend();
        }
        return instance;
    }

    // Linux evdev key codes used for modifier tracking.
    private static final int KEY_LEFTCTRL = 29,  KEY_RIGHTCTRL = 97;
    private static final int KEY_LEFTSHIFT = 42, KEY_RIGHTSHIFT = 54;
    private static final int KEY_LEFTALT = 56,   KEY_RIGHTALT = 100;
    private static final int KEY_LEFTMETA = 125, KEY_RIGHTMETA = 126;
    private static final int KEY_BACK = 158; // OPPO keyboard wires the physical Esc key here
    private static final short VK_ESCAPE = 27;
    // Artemis app-internal combos: Ctrl+Alt+Shift + Q(quit)/Z(grab)/C(cursor).
    private static final int KEY_Q = 16, KEY_Z = 44, KEY_C = 46;
    private static final byte SPECIAL_COMBO_MODS =
            KeyboardPacket.MODIFIER_CTRL | KeyboardPacket.MODIFIER_ALT | KeyboardPacket.MODIFIER_SHIFT;

    private NvConnection conn;
    private volatile byte modifierState = 0;

    // Windows VK codes currently held down (in press order), for keys we
    // actually forwarded to the host. Tracked so the client can force-release
    // them before tearing the connection down: the host does NOT release
    // client-held keys when the client disappears, so a key that is down when
    // the session ends would stay stuck on the host (e.g. Win/Ctrl/Alt).
    private final java.util.ArrayDeque<Short> heldKeys = new java.util.ArrayDeque<>();

    private IInputEventService service;
    private boolean bound = false;
    /** Bumped whenever the UserService AIDL or its behaviour changes. Shizuku only
     *  recreates a UserService process when {@code UserServiceArgs.version} changes, so
     *  without this a developer build (same versionCode) kept running the previous
     *  process - which then held the touchpad grab with old code and silently ignored
     *  new AIDL calls. */
    private static final int SERVICE_PROTOCOL_VERSION = 6;

    private volatile boolean started = false;
    private volatile boolean active = false;
    private Shizuku.UserServiceArgs userServiceArgs;
    private StateListener stateListener;
    private SpecialComboListener specialComboListener;

    private ShizukuInputBackend() {
    }
    private final ServiceConnection serviceConnection = new ServiceConnection() {
        @Override
        public void onServiceConnected(ComponentName name, IBinder binder) {
            service = IInputEventService.Stub.asInterface(binder);
            if (started) {
                // Shizuku can deliver this callback more than once per bind; ignore
                // duplicates. Real reconnects reset started in onServiceDisconnected.
                return;
            }
            // If a stream is already active, begin reading right away.
            if (conn != null) {
                startServiceIfNeeded();
            }
        }

        @Override
        public void onServiceDisconnected(ComponentName name) {
            service = null;
            started = false;
            active = false;
            // The session this state belonged to is gone; don't leak it into
            // the next one.
            modifierState = 0;
            heldKeys.clear();
            LimeLog.warning("ShizukuInputBackend: UserService disconnected (no longer active)");
            notifyState();
        }
    };

    public void setStateListener(StateListener listener) {
        this.stateListener = listener;
    }

    public void setSpecialComboListener(SpecialComboListener listener) {
        this.specialComboListener = listener;
    }

    public interface TouchpadFrameSink {
        /** One raw multitouch frame from the Shizuku evdev reader. Called on a binder
         *  thread; implementations must hop to the main thread themselves.
         *
         *  @param ids stable contact ids, @param sizes normalized contact area 0..1000 */
        void onTouchpadFrame(int[] ids, int[] xs, int[] ys, int[] sizes, long eventTimeMs,
                int xMax, int yMax, int buttonState);
    }

    private volatile TouchpadFrameSink touchpadFrameSink;
    private volatile boolean captureTouchpad;

    /** Sets the receiver for raw touchpad frames (null detaches). */
    public void setTouchpadFrameSink(TouchpadFrameSink sink) {
        this.touchpadFrameSink = sink;
    }

    /** Begin capturing for a stream. Binds the UserService once (persistent).
     *
     *  @param captureTouchpad also grab the physical multitouch touchpad and forward
     *         raw contact frames, for platforms (Android 17 / ColorOS 17) that no
     *         longer deliver multi-finger samples to apps.
     */
    public synchronized void startReading(NvConnection conn, boolean captureTouchpad) {
        this.conn = conn;
        this.captureTouchpad = captureTouchpad;
        if (bound && service != null) {
            startServiceIfNeeded();
        } else {
            ensureBound();
        }
    }

    public synchronized void startReading(NvConnection conn) {
        startReading(conn, false);
    }

    /** Retry grabbing touchpads that are not grabbed yet (hotplug, transient EBUSY). */
    public synchronized void refreshTouchpadCapture() {
        if (!started || service == null) {
            LimeLog.info("ShizukuInputBackend: refresh skipped started=" + started
                    + " service=" + (service != null));
            return;
        }
        LimeLog.info("ShizukuInputBackend: refreshTouchpadCapture()");
        try {
            service.refreshTouchpadCapture();
        } catch (Exception e) {
            LimeLog.warning("ShizukuInputBackend: refreshTouchpadCapture failed: " + e);
        }
    }

    /** Stop capturing for the stream: release the grab and stop readers, keep binding. */
    public synchronized void stopReading() {
        // Release any keys we still report as held down on the host before we
        // detach from the session; after this point their key-up events can no
        // longer be forwarded, and the host keeps client-held keys pressed when
        // the client disappears. This is usually a no-op because Game flushes
        // earlier (while the control stream is definitely alive); it exists as
        // a safety net for other callers.
        flushHeldKeys();

        this.conn = null;
        if (service != null) {
            try {
                service.stop();
            } catch (Exception ignored) {
            }
        }
        started = false;
        if (active) {
            active = false;
            notifyState();
        }
    }

    /** Full teardown (app shutdown). */
    public synchronized void shutdown() {
        stopReading();
        if (bound) {
            try {
                Shizuku.unbindUserService(userServiceArgs, serviceConnection, true);
            } catch (Exception ignored) {
            }
            bound = false;
        }
    }

    private void ensureBound() {
        if (bound) {
            return;
        }
        if (!ShizukuKeyCapture.isReady()) {
            LimeLog.info("ShizukuInputBackend: not ready, skipping bind");
            return;
        }
        try {
            ComponentName cn = new ComponentName(BuildConfig.APPLICATION_ID,
                    ShizukuInputService.class.getName());
            userServiceArgs = new Shizuku.UserServiceArgs(cn)
                    .processNameSuffix("evdev")
                    .version(BuildConfig.VERSION_CODE * 100 + SERVICE_PROTOCOL_VERSION)
                    .tag("shizuku-input-evdev");
            Shizuku.bindUserService(userServiceArgs, serviceConnection);
            bound = true;
            LimeLog.info("ShizukuInputBackend: bindUserService called (persistent)");
        } catch (Exception e) {
            LimeLog.warning("ShizukuInputBackend: bind failed: " + e);
        }
    }

    private void startServiceIfNeeded() {
        if (started || service == null) {
            return;
        }
        try {
            service.start(this, captureTouchpad);
            started = true;
            active = true;
            LimeLog.info("ShizukuInputBackend: UserService started (active)");
            notifyState();
        } catch (Exception e) {
            LimeLog.warning("ShizukuInputBackend: start() failed: " + e);
            active = false;
            notifyState();
        }
    }

    private void notifyState() {
        final StateListener l = stateListener;
        if (l != null) {
            l.onActiveChanged(active);
        }
    }

    /** Sends KEY_UP to the host for every key we currently believe is held
     *  down (most recently pressed first), then clears the tracking state.
     *  Call this while the connection is still alive, before stopping it. */
    public synchronized void flushHeldKeys() {
        NvConnection c = conn;

        while (!heldKeys.isEmpty()) {
            short vk = heldKeys.removeLast();

            // Drop this key's modifier before releasing it, mirroring how
            // Game.sendKeys() sequences modifier releases.
            modifierState &= ~KeyboardTranslator.getModifier(vk);

            if (c != null) {
                c.sendKeyboardInput(vk, KeyboardPacket.KEY_UP, modifierState, (byte) 0);
            }
        }

        // All held keys (and thus all modifiers) are up now.
        modifierState = 0;
    }

    @Override
    public synchronized void onInputKeyEvent(int linuxKeyCode, int value, String devicePath) {
        boolean down = (value != 0);

        byte modFlag = modifierFlag(linuxKeyCode);
        if (modFlag != 0) {
            if (down) {
                modifierState |= modFlag;
            } else {
                modifierState &= ~modFlag;
            }
        }

        // App-internal special combos (Ctrl+Alt+Shift+Q/Z/C) trigger Artemis actions
        // (quit / toggle grab / toggle cursor) instead of being forwarded to the host.
        if (down && specialComboListener != null
                && (modifierState & SPECIAL_COMBO_MODS) == SPECIAL_COMBO_MODS
                && (linuxKeyCode == KEY_Q || linuxKeyCode == KEY_Z || linuxKeyCode == KEY_C)) {
            specialComboListener.onSpecialCombo(linuxKeyCode);
            return;
        }

        NvConnection c = conn;
        if (c == null) {
            return;
        }

        short vk;
        if (linuxKeyCode == KEY_BACK) {
            vk = VK_ESCAPE;
        } else {
            int k = KeyMapper.getWindowsKeyCode(linuxKeyCode);
            if (k <= 0) {
                return;
            }
            vk = (short) k;
        }

        c.sendKeyboardInput(vk, down ? KeyboardPacket.KEY_DOWN : KeyboardPacket.KEY_UP,
                modifierState, (byte) 0);

        // Track held state for the force-release on teardown. A key-down that
        // was never forwarded (special combo, unmapped) is never tracked, so
        // its key-up below only removes a non-existent entry.
        if (down) {
            if (!heldKeys.contains(vk)) {
                heldKeys.addLast(vk);
            }
        }
        else {
            heldKeys.remove(vk);
        }
    }

    @Override
    public void onTouchpadFrame(int[] ids, int[] xs, int[] ys, int[] sizes,
            long eventTimeMs, int xMax, int yMax, int buttonState) {
        TouchpadFrameSink sink = touchpadFrameSink;
        if (sink != null) {
            sink.onTouchpadFrame(ids, xs, ys, sizes, eventTimeMs, xMax, yMax, buttonState);
        }
    }

    @Override
    public void onError(String message) {
        LimeLog.warning("ShizukuInputBackend UserService error: " + message);
    }

    private static byte modifierFlag(int linuxKeyCode) {
        switch (linuxKeyCode) {
            case KEY_LEFTSHIFT:
            case KEY_RIGHTSHIFT:
                return KeyboardPacket.MODIFIER_SHIFT;
            case KEY_LEFTCTRL:
            case KEY_RIGHTCTRL:
                return KeyboardPacket.MODIFIER_CTRL;
            case KEY_LEFTALT:
            case KEY_RIGHTALT:
                return KeyboardPacket.MODIFIER_ALT;
            case KEY_LEFTMETA:
            case KEY_RIGHTMETA:
                return KeyboardPacket.MODIFIER_META;
            default:
                return 0;
        }
    }
}
