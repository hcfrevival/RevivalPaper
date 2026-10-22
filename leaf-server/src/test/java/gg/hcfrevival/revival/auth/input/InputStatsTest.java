package gg.hcfrevival.revival.auth.input;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import gg.hcfrevival.revival.RevivalTest;
import java.util.Random;
import org.junit.jupiter.api.Test;

/** Design §10 aim statistic, client-compat N7: two sensitivities and a smoothed camera are no synthetic aim. */
@RevivalTest
class InputStatsTest {

    // MouseHandler.turnPlayer: degrees per mouse count at a sensitivity, (s * 0.6 + 0.2)^3 * 8 * 0.15
    private static double step(final double sensitivity) {
        final double f = sensitivity * 0.6 + 0.2;
        return f * f * f * 8.0 * 0.15;
    }

    @Test
    void oneMouseAtOneSensitivityNeverMisses() {
        final InputStats stats = new InputStats();
        final Random random = new Random(1);
        float yaw = 0.0F;
        final double step = step(0.5);
        for (int tick = 0; tick < 400; tick++) {
            yaw += (float) ((random.nextInt(40) - 20) * step);
            stats.onTick(tick, 0, true, yaw);
        }
        assertTrue(stats.turns() > 300, "turns " + stats.turns());
        assertEquals(0, stats.gcdMisses());
    }

    @Test
    void aZoomWithItsOwnSensitivityIsTheSecondStep() {
        final InputStats stats = new InputStats();
        final Random random = new Random(2);
        float yaw = 0.0F;
        for (int tick = 0; tick < 600; tick++) {
            final double step = (tick / 50) % 2 == 0 ? step(0.5) : step(0.287); // zoomed in and out in stretches
            yaw += (float) ((random.nextInt(30) + 1) * step * (random.nextBoolean() ? 1 : -1));
            stats.onTick(tick, 0, true, yaw);
        }
        assertTrue(stats.secondGcd() > 0.0, "a second step was learned");
        assertTrue(stats.gcdMisses() <= stats.turns() / 50, "misses " + stats.gcdMisses() + " of " + stats.turns());
    }

    @Test
    void aSmoothedCameraIsSkippedNotMissed() {
        final InputStats stats = new InputStats();
        float yaw = 0.0F;
        final double step = step(0.5);
        int tick = 0;
        for (int flick = 0; flick < 40; flick++) {
            yaw += (float) (12 * step); // the mouse moved
            stats.onTick(tick++, 0, true, yaw);
            // Smooth camera: the turn eases out over the next ticks in shrinking fractions
            double remaining = 6.3 * step;
            for (int i = 0; i < 8; i++) {
                final double part = remaining * 0.5;
                remaining -= part;
                yaw += (float) part;
                stats.onTick(tick++, 0, true, yaw);
            }
        }
        assertTrue(stats.smoothedTurns() > 100, "eased turns skipped: " + stats.smoothedTurns());
        assertTrue(stats.gcdMisses() <= 10, "misses " + stats.gcdMisses() + " of " + stats.turns());
    }

    @Test
    void syntheticAimStillMisses() {
        final InputStats stats = new InputStats();
        final Random random = new Random(3);
        float yaw = 0.0F;
        for (int tick = 0; tick < 400; tick++) {
            yaw += (float) ((random.nextDouble() - 0.5) * 40.0); // any angle at all: an aimbot's
            stats.onTick(tick, 0, true, yaw);
        }
        assertTrue(stats.gcdMisses() > stats.turns() / 3, "misses " + stats.gcdMisses() + " of " + stats.turns());
    }
}
