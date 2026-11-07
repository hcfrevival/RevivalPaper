package gg.hcfrevival.revival.obfuscator;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

import gg.hcfrevival.revival.RevivalTest;
import org.junit.jupiter.api.Test;

@RevivalTest
class ObfuscatorStateTest {

    private static final int DELAY = 10;

    @Test
    void hidesAfterTheDelayAndRevealsAtOnce() {
        final ObfuscatorState state = new ObfuscatorState();
        state.record(7, true, 100);
        state.publish(100, DELAY);
        assertFalse(state.hides(7), "occluded for 0 ticks");
        state.publish(109, DELAY);
        assertFalse(state.hides(7), "occluded for 9 ticks");
        state.record(7, true, 110); // keeps the first occluded tick
        state.publish(110, DELAY);
        assertTrue(state.hides(7), "occluded for 10 ticks");
        assertTrue(state.occluded(7));

        state.record(7, false, 111);
        state.publish(111, DELAY);
        assertFalse(state.hides(7), "one clear test reveals");
        assertFalse(state.occluded(7));
    }

    @Test
    void publishKeepsTheArrayWhenNothingChanged() {
        final ObfuscatorState state = new ObfuscatorState();
        state.record(7, true, 0);
        state.publish(DELAY, DELAY);
        final int[] hidden = state.hidden();
        assertEquals(1, hidden.length);
        state.publish(DELAY + 1, DELAY);
        assertSame(hidden, state.hidden());
        state.record(8, true, DELAY + 1);
        state.publish(DELAY + 1, DELAY);
        assertSame(hidden, state.hidden(), "a viewer still inside the delay changes nothing");
    }

    @Test
    void lookupIsSortedAndExact() {
        final ObfuscatorState state = new ObfuscatorState();
        for (int i = 0; i < 50; i++) {
            state.record(1000 - 7 * i, true, 0);
        }
        state.publish(DELAY, DELAY);
        for (int i = 0; i < 50; i++) {
            assertTrue(state.hides(1000 - 7 * i));
        }
        assertFalse(state.hides(999));
        assertFalse(state.hides(-1));
        state.record(1000, false, DELAY);
        state.publish(DELAY, DELAY);
        assertFalse(state.hides(1000));
        assertTrue(state.hides(993));
    }

    @Test
    void bypassRefreshesOnTheFirstTick() {
        assertTrue(0 - new ObfuscatorState().bypassTick >= 20, "the first tick is already 20 ticks past the initial stamp");
    }

    @Test
    void clearWithholdsFromNobody() {
        final ObfuscatorState state = new ObfuscatorState();
        state.record(7, true, 0);
        state.publish(DELAY, DELAY);
        assertTrue(state.hides(7));
        state.clear();
        assertFalse(state.hides(7));
        assertEquals(0, state.hidden().length);
        assertFalse(state.occluded(7));
    }
}
