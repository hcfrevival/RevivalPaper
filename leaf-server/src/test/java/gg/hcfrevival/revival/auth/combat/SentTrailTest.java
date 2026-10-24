package gg.hcfrevival.revival.auth.combat;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import gg.hcfrevival.revival.RevivalTest;
import net.minecraft.world.phys.AABB;
import org.junit.jupiter.api.Test;

/**
 * Where a client may be drawing a mob (design §7.1, 2026-10-24): the positions its tracker sent over the view's lag, and
 * the one before them, which the client may still be stepping from.
 */
@RevivalTest
class SentTrailTest {

    // A box 0.6 wide around (x, 64, 0), as the entity's own now
    private static AABB box(final double x) {
        return new AABB(x - 0.3, 64.0, -0.3, x + 0.3, 65.95, 0.3);
    }

    private static HitValidator.Region sweep(final SentTrail trail, final int now, final int span, final double x, final int[] added) {
        final HitValidator.Region region = new HitValidator.Region();
        final AABB box = box(x);
        added[0] = trail.sweep(now, span, x, 64.0, 0.0, box.minX, box.minY, box.minZ, box.maxX, box.maxY, box.maxZ, region);
        return region;
    }

    @Test
    void theSpanAndTheSendBeforeIt() {
        // A mob walking +x, sent every 3 ticks; at tick 112 a view at most 7 ticks old is stepping from the send at 103 at
        // the earliest, never from 100
        final SentTrail trail = new SentTrail();
        for (int i = 0; i <= 4; i++) {
            trail.record(0.6 * i, 64.0, 0.0, 100 + 3 * i);
        }
        final int[] added = new int[1];
        final HitValidator.Region region = sweep(trail, 112, 7, 2.4, added);
        assertEquals(4, added[0], "112, 109, 106 and 103");
        assertEquals(0.6 - 0.3, region.box().minX, 1.0E-9);
        assertEquals(2.4 + 0.3, region.box().maxX, 1.0E-9);
        assertEquals(64.0, region.box().minY, 1.0E-9);
    }

    @Test
    void noSendInTheSpanAddsNothing() {
        // It hasn't moved since: every client shows it where it stands
        final SentTrail trail = new SentTrail();
        trail.record(5.0, 64.0, 0.0, 100);
        final int[] added = new int[1];
        assertTrue(sweep(trail, 120, 12, 5.0, added).empty());
        assertEquals(0, added[0]);
    }

    @Test
    void aGapForgetsTheSendsBeforeIt() {
        // Untracked for longer than the 60-tick resync: whoever tracks it now was paired where it stood since
        final SentTrail trail = new SentTrail();
        trail.record(40.0, 64.0, 0.0, 100);
        trail.record(0.0, 64.0, 0.0, 100 + SentTrail.GAP_TICKS + 1);
        assertEquals(1, trail.size());
        final int[] added = new int[1];
        final HitValidator.Region region = sweep(trail, 100 + SentTrail.GAP_TICKS + 3, 12, 0.0, added);
        assertEquals(1, added[0]);
        assertEquals(0.3, region.box().maxX, 1.0E-9, "not stretched back 40 blocks");
    }

    @Test
    void theRingKeepsTheNewestSends() {
        final SentTrail trail = new SentTrail();
        for (int t = 0; t < 40; t++) {
            trail.record(t, 64.0, 0.0, t);
        }
        assertEquals(SentTrail.SIZE, trail.size());
        final int[] added = new int[1];
        final HitValidator.Region region = sweep(trail, 39, 1000, 39.0, added);
        assertEquals(SentTrail.SIZE, added[0]);
        assertEquals(40 - SentTrail.SIZE - 0.3, region.box().minX, 1.0E-9);
    }
}
