package com.limelight.binding.input.capture;

import static org.junit.Assert.assertEquals;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import android.app.Activity;
import android.hardware.input.InputManager;
import android.view.InputDevice;
import android.view.MotionEvent;
import android.view.View;

import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.annotation.Config;

@RunWith(RobolectricTestRunner.class)
@Config(sdk = {33})
public class AndroidNativePointerCaptureProviderTest {
    @Test
    public void relativeAxisHistoryCanBeReadIndividuallyAndAsAggregate() {
        Activity activity = mock(Activity.class);
        when(activity.getSystemService(InputManager.class)).thenReturn(mock(InputManager.class));
        AndroidNativePointerCaptureProvider provider =
                new AndroidNativePointerCaptureProvider(activity, mock(View.class));

        MotionEvent.PointerProperties properties = new MotionEvent.PointerProperties();
        properties.id = 0;
        properties.toolType = MotionEvent.TOOL_TYPE_FINGER;
        MotionEvent.PointerCoords first = relativeCoords(1f, -1f);
        MotionEvent event = MotionEvent.obtain(1_000, 1_000, MotionEvent.ACTION_MOVE, 1,
                new MotionEvent.PointerProperties[] { properties },
                new MotionEvent.PointerCoords[] { first }, 0, 0, 1f, 1f, 1, 0,
                InputDevice.SOURCE_TOUCHPAD, 0);
        event.addBatch(1_010, new MotionEvent.PointerCoords[] { relativeCoords(2f, -2f) }, 0);
        event.addBatch(1_020, new MotionEvent.PointerCoords[] { relativeCoords(3f, -3f) }, 0);

        assertEquals(2, event.getHistorySize());
        assertEquals(1f, provider.getHistoricalRelativeAxisX(event, 0, 0), 0f);
        assertEquals(2f, provider.getHistoricalRelativeAxisX(event, 0, 1), 0f);
        assertEquals(3f, provider.getCurrentRelativeAxisX(event, 0), 0f);
        assertEquals(6f, provider.getRelativeAxisX(event, 0), 0f);
        assertEquals(-6f, provider.getRelativeAxisY(event, 0), 0f);

        event.recycle();
    }

    private static MotionEvent.PointerCoords relativeCoords(float x, float y) {
        MotionEvent.PointerCoords coordinates = new MotionEvent.PointerCoords();
        coordinates.setAxisValue(MotionEvent.AXIS_RELATIVE_X, x);
        coordinates.setAxisValue(MotionEvent.AXIS_RELATIVE_Y, y);
        return coordinates;
    }
}
