package com.limelight.preferences;

import static org.junit.Assert.assertEquals;

import org.junit.Test;

public class PreferenceConfigurationKeyboardCaptureTest {
    @Test
    public void shizukuIsDefaultAndLegacyValueFallback() {
        assertEquals(PreferenceConfiguration.KeyboardCaptureMode.SHIZUKU,
                PreferenceConfiguration.parseKeyboardCaptureMode(null));
        assertEquals(PreferenceConfiguration.KeyboardCaptureMode.SHIZUKU,
                PreferenceConfiguration.parseKeyboardCaptureMode("unknown"));
        assertEquals(PreferenceConfiguration.KeyboardCaptureMode.SHIZUKU,
                PreferenceConfiguration.parseKeyboardCaptureMode("auto"));
        assertEquals(PreferenceConfiguration.KeyboardCaptureMode.SHIZUKU,
                PreferenceConfiguration.parseKeyboardCaptureMode("shizuku"));
        // Legacy "off" values fall back to shizuku after the mode was removed
        assertEquals(PreferenceConfiguration.KeyboardCaptureMode.SHIZUKU,
                PreferenceConfiguration.parseKeyboardCaptureMode("off"));
    }

    @Test
    public void accessibilityRemainsAvailable() {
        assertEquals(PreferenceConfiguration.KeyboardCaptureMode.ACCESSIBILITY,
                PreferenceConfiguration.parseKeyboardCaptureMode("accessibility"));
    }
}
