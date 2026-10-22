package gg.hcfrevival.revival.combat;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import gg.hcfrevival.revival.RevivalTest;
import gg.hcfrevival.revival.config.CombatRules;
import org.junit.jupiter.api.Test;

/**
 * Combo's {@code PlayerSim.ParryWindow} in 20 Hz ticks (docs/revival/neue-combat.md §8.2):
 * {@code W = T_B − T_sw < 6 ? 0 : round(3 × clamp((T_B − T_E − 7) / 13, 0, 1))}, from ParryWindowSeconds 0.15,
 * BlockHitSeconds 0.3, ParryRearmSeconds 0.35 and ParryRestSeconds 1.0.
 */
@RevivalTest
class ParryWindowTest {

    private static final CombatRules NEUE = CombatRules.NEUE;
    private static final long NEVER = NeueState.NEVER;

    @Test
    void windowGrowsWithRestSinceTheLastBlock() {
        // §8.2 table: ticks since the last block came down -> W
        for (int since = 0; since <= 40; since++) {
            final int expected = since <= 9 ? 0 : since <= 13 ? 1 : since <= 17 ? 2 : 3;
            assertEquals(expected, NeueState.parryWindow(1000L, 1000L - since, NEVER, NEUE), "since " + since);
        }
    }

    @Test
    void aFirstBlockGetsTheWholeWindow() {
        assertEquals(3, NeueState.parryWindow(1000L, NEVER, NEVER, NEUE));
    }

    @Test
    void aBlockHitNeverParries() {
        // A swing at T, the block at T+5: still a block-hit. At T+6 the sword has rested (BlockHitSeconds 0.3)
        assertEquals(0, NeueState.parryWindow(105L, NEVER, 100L, NEUE));
        assertEquals(3, NeueState.parryWindow(106L, NEVER, 100L, NEUE));
        // An attack and a use in the same window (the grammar allows that order): T_B − T_sw = 0
        assertEquals(0, NeueState.parryWindow(100L, NEVER, 100L, NEUE));
        // Rested from swinging but not from blocking: the rest rule still applies
        assertEquals(1, NeueState.parryWindow(120L, 108L, 100L, NEUE));
    }

    @Test
    void stateArmsTheWindowAtBlockStart() {
        final NeueState state = new NeueState();
        state.swung(90L, true);
        state.blockStarted(100L, 5000L, true, NEUE); // 10 ticks after the swing, first block
        assertEquals(3, state.parryWindow());
        assertTrue(state.mayParry());
        state.blockEnded(104L, true);
        assertFalse(state.mayParry());
        state.blockStarted(116L, 5016L, true, NEUE); // 12 ticks of rest
        assertEquals(1, state.parryWindow());
        state.blockEnded(117L, true);
        state.blockStarted(119L, 5019L, true, NEUE); // spamming the block gets nothing
        assertEquals(0, state.parryWindow());
        assertFalse(state.mayParry());
    }

    @Test
    void classicNeverArmsAWindow() {
        final NeueState state = new NeueState();
        state.blockStarted(100L, 5000L, true, CombatRules.CLASSIC);
        assertEquals(0, state.parryWindow());
        assertFalse(state.mayParry());
        assertFalse(state.parries(100L, 5000L, 2));
    }

    @Test
    void switchingClocksForgetsTheOldTicks() {
        final NeueState state = new NeueState();
        state.blockStarted(100L, 5000L, true, NEUE);
        state.blockEnded(101L, true);
        // Out of authority now: a server-tick block can't be compared with client-tick ones, so it counts as a first block
        state.blockStarted(5003L, 5003L, false, NEUE);
        assertEquals(3, state.parryWindow());
    }
}
