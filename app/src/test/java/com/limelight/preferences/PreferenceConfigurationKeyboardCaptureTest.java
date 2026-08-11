package com.limelight.preferences;

import static org.junit.Assert.assertEquals;

import org.junit.Test;

public class PreferenceConfigurationKeyboardCaptureTest {
    @Test
    public void shizukuIsDefaultAndLegacyAutoMigrationTarget() {
        assertEquals(PreferenceConfiguration.KeyboardCaptureMode.SHIZUKU,
                PreferenceConfiguration.parseKeyboardCaptureMode(null));
        assertEquals(PreferenceConfiguration.KeyboardCaptureMode.SHIZUKU,
                PreferenceConfiguration.parseKeyboardCaptureMode("unknown"));
        assertEquals(PreferenceConfiguration.KeyboardCaptureMode.SHIZUKU,
                PreferenceConfiguration.parseKeyboardCaptureMode("auto"));
        assertEquals(PreferenceConfiguration.KeyboardCaptureMode.SHIZUKU,
                PreferenceConfiguration.parseKeyboardCaptureMode("shizuku"));
    }

    @Test
    public void explicitFallbackModesRemainAvailable() {
        assertEquals(PreferenceConfiguration.KeyboardCaptureMode.ACCESSIBILITY,
                PreferenceConfiguration.parseKeyboardCaptureMode("accessibility"));
        assertEquals(PreferenceConfiguration.KeyboardCaptureMode.OFF,
                PreferenceConfiguration.parseKeyboardCaptureMode("off"));
    }
}
