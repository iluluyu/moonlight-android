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
 * App-side manager for the Shizuku keyboard-capture backend. Binds a
 * {@link ShizukuInputService} UserService, receives raw evdev key events, and
 * translates them into host keyboard packets via {@link KeyMapper} (Linux keycode
 * -> Windows VK), tracking modifier state along the way.
 *
 * <p>This bypasses ColorOS framework-level key interception (the value over the
 * AccessibilityService path). KEY_BACK (158) is remapped to VK_ESCAPE so the OPPO
 * keyboard's physical Esc key works, matching the fix in {@code Game.handleKeyDown}.
 *
 * <p><b>Important:</b> reading evdev via Shizuku is a <i>parallel</i> capture — it
 * does NOT block the framework from also acting on a key (so OPPO's global Win→AI
 * shortcut still fires; that must be disabled in OPPO settings). It only guarantees
 * the raw keypress reaches the host even when ColorOS would otherwise eat it.
 */
public class ShizukuInputBackend extends IInputEventCallback.Stub {

    /** Notified (on the main thread) when the UserService actually connects/disconnects. */
    public interface StateListener {
        void onActiveChanged(boolean active);
    }

    // Linux evdev key codes used for modifier tracking.
    private static final int KEY_LEFTCTRL = 29,  KEY_RIGHTCTRL = 97;
    private static final int KEY_LEFTSHIFT = 42, KEY_RIGHTSHIFT = 54;
    private static final int KEY_LEFTALT = 56,   KEY_RIGHTALT = 100;
    private static final int KEY_LEFTMETA = 125, KEY_RIGHTMETA = 126;
    private static final int KEY_BACK = 158; // OPPO keyboard wires the physical Esc key here
    private static final short VK_ESCAPE = 27;

    // TEMP(diag): log every forwarded key event. Flip to true to debug capture.
    private static final boolean LOG_SENDS = false;

    private NvConnection conn;
    private volatile byte modifierState = 0;
    private IInputEventService service;
    private boolean bound = false;
    private volatile boolean active = false;
    private Shizuku.UserServiceArgs userServiceArgs;
    private StateListener stateListener;
    private final boolean logRawEvents;

    private final ServiceConnection serviceConnection = new ServiceConnection() {
        @Override
        public void onServiceConnected(ComponentName name, IBinder binder) {
            service = IInputEventService.Stub.asInterface(binder);
            try {
                service.start(ShizukuInputBackend.this);
                active = true;
                LimeLog.info("ShizukuInputBackend: UserService connected & started (now active)");
                notifyState();
            } catch (Exception e) {
                LimeLog.warning("ShizukuInputBackend: start() failed: " + e);
                active = false;
                notifyState();
            }
        }

        @Override
        public void onServiceDisconnected(ComponentName name) {
            service = null;
            active = false;
            LimeLog.warning("ShizukuInputBackend: UserService disconnected (no longer active)");
            notifyState();
        }
    };

    public ShizukuInputBackend() {
        this(false);
    }

    /** @param logRawEvents TEMP diagnostic: log every raw evdev event (no conn needed). */
    public ShizukuInputBackend(boolean logRawEvents) {
        this.logRawEvents = logRawEvents;
    }

    public void setStateListener(StateListener listener) {
        this.stateListener = listener;
    }

    /** True only once the UserService is bound and forwarding events. */
    public boolean isActive() {
        return active;
    }

    private void notifyState() {
        final StateListener l = stateListener;
        if (l != null) {
            l.onActiveChanged(active);
        }
    }

    /** Begin capturing. Requires {@link ShizukuKeyCapture#isReady()}. */
    public synchronized void start(NvConnection conn) {
        if (bound) {
            return;
        }
        if (!ShizukuKeyCapture.isReady()) {
            LimeLog.info("ShizukuInputBackend: not ready, skipping");
            return;
        }
        this.conn = conn;
        try {
            ComponentName cn = new ComponentName(BuildConfig.APPLICATION_ID,
                    ShizukuInputService.class.getName());
            userServiceArgs = new Shizuku.UserServiceArgs(cn)
                    .processNameSuffix("evdev")
                    .version(2)
                    .tag("shizuku-input-evdev");
            Shizuku.bindUserService(userServiceArgs, serviceConnection);
            bound = true;
            LimeLog.info("ShizukuInputBackend: bindUserService called");
        } catch (Exception e) {
            LimeLog.warning("ShizukuInputBackend: bind failed: " + e);
        }
    }

    public synchronized void stop() {
        if (service != null) {
            try {
                service.stop();
            } catch (Exception ignored) {
            }
        }
        if (bound && userServiceArgs != null) {
            try {
                Shizuku.unbindUserService(userServiceArgs, serviceConnection, true);
            } catch (Exception ignored) {
            }
            bound = false;
        }
        service = null;
        conn = null;
        modifierState = 0;
        if (active) {
            active = false;
            notifyState();
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

        if (LOG_SENDS || logRawEvents) {
            LimeLog.info("ShizukuEvdev send vk=" + (vk & 0xFFFF) + " code=" + linuxKeyCode
                    + " down=" + down + " mod=0x" + Integer.toHexString(modifierState));
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
