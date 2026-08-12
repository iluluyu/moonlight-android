package com.limelight.binding.input.shizuku;

import android.content.ComponentName;
import android.content.ServiceConnection;
import android.os.IBinder;

import com.limelight.BuildConfig;
import com.limelight.LimeLog;
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
    private IInputEventService service;
    private boolean bound = false;
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

    /** Begin capturing for a stream. Binds the UserService once (persistent). */
    public synchronized void startReading(NvConnection conn) {
        this.conn = conn;
        if (bound && service != null) {
            startServiceIfNeeded();
        } else {
            ensureBound();
        }
    }

    /** Stop capturing for the stream: release the grab and stop readers, keep binding. */
    public synchronized void stopReading() {
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
                    .version(2)
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
            service.start(this);
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

    @Override
    public void onInputKeyEvent(int linuxKeyCode, int value, String devicePath) {
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
