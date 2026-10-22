package gg.hcfrevival.revival.auth;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import gg.hcfrevival.revival.RevivalTest;
import org.junit.jupiter.api.Test;

/** The playtest HUD's last-10-seconds figures. */
@RevivalTest
class RollingStatsTest {

    @Test
    void countsOnlyTheLastTenSeconds() {
        final RollingStats stats = new RollingStats();
        final long t0 = 1_000_000_000L;
        assertTrue(Double.isNaN(stats.exactShare(t0)));
        for (int i = 0; i < 20; i++) {
            stats.compared(i % 4 != 0, t0 + i * 50L); // one second of ticks, 3 in 4 exact
        }
        stats.correction(t0 + 500L);
        assertEquals(20, stats.compared(t0 + 999L));
        assertEquals(0.75, stats.exactShare(t0 + 999L));
        assertEquals(1, stats.corrections(t0 + 999L));
        assertEquals(1, stats.corrections(t0 + 9_999L), "still inside the window");
        assertEquals(0, stats.corrections(t0 + 10_000L), "ten seconds later it has dropped out");
        stats.compared(true, t0 + 20_000L);
        assertEquals(1, stats.compared(t0 + 20_000L), "an old bucket reused for a new second starts from zero");
    }
}
