package gg.hcfrevival.revival.auth.input;

import static org.junit.jupiter.api.Assertions.assertEquals;

import gg.hcfrevival.revival.RevivalTest;
import org.junit.jupiter.api.Test;

/** neue-combat §12.2 detector A. */
@RevivalTest
class CadenceWatchTest {

    @Test
    void aRunOfOneClickTicksIsCountedAndResetByAnythingElse() {
        final CadenceWatch watch = new CadenceWatch();
        for (int i = 0; i < 99; i++) {
            watch.onWindow(true, 1);
        }
        assertEquals(99, watch.streak());
        assertEquals(100, watch.onWindow(true, 1));
        assertEquals(0, watch.onWindow(true, 2), "two clicks in a tick");
        watch.onWindow(true, 1);
        assertEquals(0, watch.onWindow(true, 0), "a tick without a click");
        watch.onWindow(true, 1);
        assertEquals(0, watch.onWindow(false, 1), "an ineligible window (mining, a screen, using an item)");
        assertEquals(100, watch.longest());
    }
}
