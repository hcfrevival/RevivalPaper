package gg.hcfrevival.revival.auth;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import gg.hcfrevival.revival.RevivalTest;
import org.junit.jupiter.api.Test;

/** pvp-issues HR-2: the rewind cap follows the attacker's own connection and display. */
@RevivalTest
class RewindCapTest {

    // An honest hit's rewind: the box shown (stamp) is `display` older than the newest acked position, and the attack
    // reaches the server about one round trip plus tick waits after that newest position was produced
    private static long honestRewind(final long rtt, final long display, final long waits) {
        return display + rtt + waits;
    }

    @Test
    void honestHitsFitAtEveryPing() {
        for (final long rtt : new long[] {20, 110, 150, 250}) {
            final long display = 100; // K2: two 50 ms steps behind the newest
            final long cap = CombatWiring.cap(Math.min(rtt, 250), rtt, rtt + 20, display, 1.0, 150);
            assertTrue(honestRewind(rtt, display, 140) <= cap, "rtt " + rtt + ": rewind " + honestRewind(rtt, display, 140) + " within " + cap);
        }
    }

    @Test
    void theOldFlatCapRefusedThemAbove190() {
        assertTrue(honestRewind(250, 100, 40) > 300, "250 ms of ping needs a 390 ms rewind");
    }

    @Test
    void displayIsMeasuredFromTheMirrorNotAssumed() {
        assertEquals(100, CombatWiring.displayDelay(10_100L, 10_000L));
        assertEquals(0, CombatWiring.displayDelay(Long.MIN_VALUE, 10_000L), "no acked position yet: no display allowance");
        assertEquals(0, CombatWiring.displayDelay(10_000L, 10_100L), "never negative");
    }

    @Test
    void withheldPongsDontWidenIt() {
        // A fake-lag client holds its pongs 500 ms: the newest acked position is 500 ms older, so its shown box is too;
        // the display allowance doesn't grow (it is newest - shown) and the cap stays its real round trip
        final long cap = CombatWiring.cap(20, 20, 30, CombatWiring.displayDelay(9_500L, 9_400L), 1.0, 150);
        final long rewind = 10_000L - 9_400L + 20; // now - shown at the attack
        assertTrue(rewind > cap, "rewind " + rewind + " over the cap " + cap);
    }

    @Test
    void theGateCase250MsWithItsRttBaseStillLowFits() {
        // Deploy gate 2026-10-22: latency 250 ms, rttBase still 45 (the p10 trails), p90 300, display 99, rewind 408. The
        // old ceiling (min with max-rewind-ms 400) refused it; the cap itself was 549
        final long cap = CombatWiring.cap(Math.min(45, 250), 45, Math.min(300, 45 + 250), 99, 1.0, 150);
        assertTrue(408 <= cap && cap <= 600, "cap " + cap);
    }

    @Test
    void pastTheCeilingThePingIsNotMadeGood() {
        // A 600 ms shooter: 250 compensated; past the cap its hits are judged at the target's capped-age truth, so it leads
        final long cap = CombatWiring.cap(Math.min(600, 250), 600, 620, 100, 1.0, 150);
        assertTrue(honestRewind(600, 100, 60) > cap, "rewind " + honestRewind(600, 100, 60) + " over " + cap);
        // A 300 ms one still fits: the ceiling plus the tick-wait slack covers 300 ms and its waits
        final long fits = CombatWiring.cap(Math.min(300, 250), 300, 320, 100, 1.0, 150);
        assertTrue(honestRewind(300, 100, 60) <= fits, "rewind " + honestRewind(300, 100, 60) + " within " + fits);
    }
}
