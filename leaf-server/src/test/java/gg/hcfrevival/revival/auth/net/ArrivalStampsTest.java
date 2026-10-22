package gg.hcfrevival.revival.auth.net;

import static org.junit.jupiter.api.Assertions.assertEquals;

import gg.hcfrevival.revival.RevivalTest;
import org.junit.jupiter.api.Test;

/** Torture F4: TickEnd arrivals off the wire, taken by the main thread in the same order. */
@RevivalTest
class ArrivalStampsTest {

    @Test
    void stampsAreTakenInOrderAndNeverLaterThanNow() {
        final ArrivalStamps stamps = new ArrivalStamps();
        assertEquals(500L, stamps.take(500L), "nothing stamped: the main thread's own time");
        stamps.stamp(100L);
        stamps.stamp(150L);
        stamps.stamp(200L);
        assertEquals(100L, stamps.take(1000L));
        assertEquals(150L, stamps.take(1000L));
        assertEquals(180L, stamps.take(180L), "never after the main thread's now");
        assertEquals(1000L, stamps.take(1000L));
    }

    @Test
    void aFullRingDropsTheOldest() {
        final ArrivalStamps stamps = new ArrivalStamps();
        for (int i = 0; i < ArrivalStamps.RING + 3; i++) {
            stamps.stamp(i);
        }
        assertEquals(3L, stamps.take(Long.MAX_VALUE), "a later arrival than its own: less credit, never more");
    }
}
