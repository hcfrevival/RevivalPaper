package gg.hcfrevival.revival.auth.input;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import gg.hcfrevival.revival.RevivalTest;
import org.junit.jupiter.api.Test;

/** AuthInputAnomalyEvent fires once per crossing. */
@RevivalTest
class AnomalyWatchTest {

    @Test
    void oncePerCrossing() {
        final AnomalyWatch watch = new AnomalyWatch();
        assertFalse(watch.crossed(AnomalyWatch.DOUBLE_CLICKS, 0.5, 0.3, false), "not enough samples yet");
        assertTrue(watch.crossed(AnomalyWatch.DOUBLE_CLICKS, 0.5, 0.3, true));
        assertFalse(watch.crossed(AnomalyWatch.DOUBLE_CLICKS, 0.6, 0.3, true), "still over: no second event");
        assertFalse(watch.crossed(AnomalyWatch.DOUBLE_CLICKS, 0.2, 0.3, true), "back under re-arms");
        assertTrue(watch.crossed(AnomalyWatch.DOUBLE_CLICKS, 0.31, 0.3, true));
        assertTrue(watch.crossed(AnomalyWatch.INPUT_GRAMMAR, 3, 3, true), "kinds are independent");
    }
}
