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
            final long cap = CombatWiring.cap(rtt, rtt + 20, display, 1.0, 150);
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
        final long cap = CombatWiring.cap(20, 30, CombatWiring.displayDelay(9_500L, 9_400L), 1.0, 150);
        final long rewind = 10_000L - 9_400L + 20; // now - shown at the attack
        assertTrue(rewind > cap, "rewind " + rewind + " over the cap " + cap);
    }
}
