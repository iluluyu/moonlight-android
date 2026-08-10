package com.limelight.binding.input.shizuku;

import android.content.pm.PackageManager;
import android.os.Build;

import com.limelight.LimeLog;

import rikka.shizuku.Shizuku;

/**
 * Keyboard capture backend backed by Shizuku/Sui.
 *
 * <p>Phase 1 (this class): runtime detection only. Shizuku runs an evdev-reading
 * UserService with shell/root privileges, bypassing ColorOS key remapping (this is
 * also what makes the physical Esc key work reliably). The full UserService binding
 * lands in a later phase; for now we only probe whether Shizuku is installed, alive,
 * recent enough, and permission-granted, so the caller can pick between this backend,
 * the {@code KeyboardAccessibilityService} fallback, and plain Activity dispatch.
 *
 * <p>Shizuku requires Android 6.0 (API 23). The library is force-merged via
 * {@code tools:overrideLibrary} and guarded here at runtime, so older devices degrade
 * gracefully.
 */
public final class ShizukuKeyCapture {

    public static final int PERMISSION_REQUEST_CODE = 0x5A1;

    public enum State {
        /** Device is below API 23 — Shizuku is unavailable. */
        UNSUPPORTED_TOO_OLD,
        /** Shizuku/Sui not installed or its binder not alive (service not started). */
        NOT_INSTALLED,
        /** Installed but older than API v11 — unsupported. */
        VERSION_TOO_OLD,
        /** Installed and alive, but the app has not been granted permission yet. */
        NOT_GRANTED,
        /** Ready to bind a UserService. */
        GRANTED
    }

    private ShizukuKeyCapture() {}

    /** Probe the current Shizuku availability/permission state. */
    public static State checkState() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.M) {
            return State.UNSUPPORTED_TOO_OLD;
        }
        try {
            if (!Shizuku.pingBinder()) {
                return State.NOT_INSTALLED;
            }
            if (Shizuku.isPreV11()) {
                return State.VERSION_TOO_OLD;
            }
            if (Shizuku.checkSelfPermission() == PackageManager.PERMISSION_GRANTED) {
                return State.GRANTED;
            }
            return State.NOT_GRANTED;
        } catch (SecurityException | IllegalStateException e) {
            LimeLog.warning("Shizuku state check failed: " + e);
            return State.NOT_INSTALLED;
        }
    }

    /** Convenience: true when a UserService can be bound right now. */
    public static boolean isReady() {
        return checkState() == State.GRANTED;
    }

    /**
     * Ask Shizuku for permission if it is available but not yet granted.
     *
     * @return true only if already granted (caller may bind a UserService immediately)
     */
    public static boolean ensurePermission() {
        if (isReady()) {
            return true;
        }
        try {
            if (Shizuku.pingBinder() && !Shizuku.isPreV11() &&
                    Shizuku.checkSelfPermission() != PackageManager.PERMISSION_GRANTED &&
                    !Shizuku.shouldShowRequestPermissionRationale()) {
                Shizuku.requestPermission(PERMISSION_REQUEST_CODE);
            }
        } catch (SecurityException | IllegalStateException e) {
            LimeLog.warning("Shizuku permission request failed: " + e);
        }
        return false;
    }
}
