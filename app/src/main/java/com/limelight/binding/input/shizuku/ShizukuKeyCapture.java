package com.limelight.binding.input.shizuku;

import android.content.pm.PackageManager;
import android.os.Build;

import com.limelight.LimeLog;

import rikka.shizuku.Shizuku;

/**
 * Keyboard capture backend backed by Shizuku/Sui.
 *
 * <p>Shizuku runs an evdev-reading UserService with shell/root privileges,
 * bypassing ColorOS key remapping (this is also what makes the physical Esc key
 * work reliably). This class handles runtime probing (is Shizuku installed,
 * alive, recent enough, permission-granted?) and permission management
 * (request, guided re-grant). The actual UserService binding and evdev reading
 * live in {@link ShizukuInputBackend} and {@link ShizukuInputService}.
 *
 * <p>Shizuku requires Android 6.0 (API 23). The library is force-merged via
 * {@code tools:overrideLibrary} and guarded here at runtime, so older devices
 * degrade gracefully.
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
            if (checkPermissionGrantedUncached()) {
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
     * Check if Shizuku permission is granted, bypassing the library's sticky
     * in-process cache. {@link Shizuku#checkSelfPermission()} caches
     * permissionGranted=true for the entire process lifetime after first grant,
     * so external revocation inside the Shizuku app is never noticed. This method
     * clears that cache via reflection before each check, forcing a fresh server
     * round-trip.
     */
    private static boolean checkPermissionGrantedUncached() {
        try {
            java.lang.reflect.Field f = Shizuku.class.getDeclaredField("permissionGranted");
            f.setAccessible(true);
            f.setBoolean(null, false);
        } catch (NoSuchFieldException | IllegalAccessException e) {
            // Cache field not accessible (e.g. renamed by an optimizer) — fall back
            // to the potentially-stale checkSelfPermission() result.
            LimeLog.warning("Could not reset Shizuku permission cache: " + e);
        }
        return Shizuku.checkSelfPermission() == PackageManager.PERMISSION_GRANTED;
    }
    /**
     * Ask Shizuku for permission if it is available but not yet granted.
     *
     * @return true only if already granted (caller may bind a UserService immediately)
     */
    public static boolean ensurePermission() {
        if (checkPermissionGrantedUncached()) {
            return true;
        }
        try {
            if (Shizuku.pingBinder() && !Shizuku.isPreV11()
                    && !Shizuku.shouldShowRequestPermissionRationale()) {
                Shizuku.requestPermission(PERMISSION_REQUEST_CODE);
            }
        } catch (SecurityException | IllegalStateException e) {
            LimeLog.warning("Shizuku permission request failed: " + e);
        }
        return false;
    }

    /**
     * Returns true when the user permanently denied permission (checked "Don't ask
     * again"). In this state {@link #ensurePermission()} cannot show the system dialog;
     * the caller must guide the user into the Shizuku app to re-grant.
     */
    public static boolean needsManualGrant() {
        try {
            return checkState() == State.NOT_GRANTED
                    && Shizuku.shouldShowRequestPermissionRationale();
        } catch (SecurityException | IllegalStateException e) {
            return false;
        }
    }

    /**
     * Intent to launch the Shizuku app's main UI (manager/permission screen).
     * Returns null when the Shizuku package is not installed.
     */
    public static android.content.Intent getShizukuSettingsIntent(android.content.Context ctx) {
        android.content.pm.PackageManager pm = ctx.getPackageManager();
        android.content.Intent intent = pm.getLaunchIntentForPackage("moe.shizuku.privileged.api");
        if (intent != null) {
            intent.addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK);
        }
        return intent;
    }
}
