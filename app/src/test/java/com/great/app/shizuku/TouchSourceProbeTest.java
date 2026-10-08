package com.great.app.shizuku;

import org.junit.Test;

import java.io.BufferedReader;
import java.io.StringReader;

import static org.junit.Assert.*;

public final class TouchSourceProbeTest {
    @Test public void doesNotMixFingerprintNameWithTouchscreenCapabilities() throws Exception {
        String dump = """
                add device 1: /dev/input/event2
                  name:     \"goodix_fp\"
                  events:
                    KEY (0001): BTN_TOUCH
                add device 2: /dev/input/event5
                  name:     \"NVTCapacitiveTouchScreen\"
                  events:
                    ABS (0003): ABS_MT_POSITION_X ABS_MT_POSITION_Y ABS_MT_TRACKING_ID
                  input props:
                    INPUT_PROP_DIRECT
                """;

        TouchSourceProbe.Result result = TouchSourceProbe.parse(
                new BufferedReader(new StringReader(dump)));

        assertTrue(result.found());
        assertEquals("/dev/input/event5", result.devicePath());
        assertEquals("NVTCapacitiveTouchScreen", result.name());
        assertFalse(result.detail().contains("goodix_fp"));
    }

    @Test public void rejectsFingerprintOnlyDevice() throws Exception {
        String dump = """
                add device 1: /dev/input/event2
                  name:     \"goodix_fp\"
                  events:
                    ABS (0003): ABS_X ABS_Y
                    KEY (0001): BTN_TOUCH
                """;

        TouchSourceProbe.Result result = TouchSourceProbe.parse(
                new BufferedReader(new StringReader(dump)));

        assertFalse(result.found());
    }
}
