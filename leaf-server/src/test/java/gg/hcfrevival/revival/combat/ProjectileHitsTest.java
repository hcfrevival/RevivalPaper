package gg.hcfrevival.revival.combat;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import gg.hcfrevival.revival.RevivalTest;
import java.util.Random;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;
import org.junit.jupiter.api.Test;

/**
 * 1.7.10's entity test for projectiles (docs/revival/projectiles.md §5): {@code AxisAlignedBB.calculateIntercept} on the
 * box grown by 0.3 (MCP 1.7.10 EntityThrowable/EntityArrow/EntityFishHook.onUpdate; the same algorithm as Intave's
 * {@code BoundingBox.calculateIntercept} port), against 26.3's {@code AABB.clip} on the box grown by
 * {@code ProjectileUtil.computeMargin}.
 */
@RevivalTest
class ProjectileHitsTest {

    private static final double MARGIN_17 = 0.3;

    // A standing player at the origin: 0.6 × 1.8
    private static final AABB PLAYER = new AABB(-0.3, 64.0, -0.3, 0.3, 65.8, 0.3);
    // The same with dyadic bounds, for exact boundary cases
    private static final AABB EXACT = new AABB(-0.25, 64.0, -0.25, 0.25, 65.75, 0.25);

    private static double intercept(final AABB box, final double margin, final Vec3 from, final Vec3 to, final double[] point) {
        return ProjectileHits.intercept(box.minX - margin, box.minY - margin, box.minZ - margin, box.maxX + margin, box.maxY + margin, box.maxZ + margin,
            from.x, from.y, from.z, to.x, to.y, to.z, point);
    }

    /** 26.3's computeMargin: {@code max(0, min(0.3, (tickCount - 2) / 20))}. */
    private static double margin26(final int tickCount) {
        return Math.max(0.0F, Math.min(0.3F, (tickCount - 2) / 20.0F));
    }

    @Test
    void anEntryFaceHitIsWhereTheSegmentCrossesIt() {
        final double[] point = new double[3];
        assertEquals(-1.0, intercept(EXACT, 0.25, new Vec3(0.0, 65.0, -3.0), new Vec3(0.0, 65.0, -1.0), point), 0.0); // stops short
        final double hit = intercept(EXACT, 0.25, new Vec3(0.0, 65.0, -3.0), new Vec3(0.0, 65.0, 1.0), point);
        assertEquals(2.5 * 2.5, hit, 0.0); // the face at z = -0.5
        assertEquals(0.0, point[0], 0.0);
        assertEquals(65.0, point[1], 0.0);
        assertEquals(-0.5, point[2], 0.0);
        final Vec3 clipped = EXACT.inflate(0.25).clip(new Vec3(0.0, 65.0, -3.0), new Vec3(0.0, 65.0, 1.0)).orElseThrow();
        assertEquals(-0.5, clipped.z, 0.0);
    }

    @Test
    void aSegmentStartingInsideTheBoxHitsItsExitFaceOn17AndMissesOn26() {
        final double[] point = new double[3];
        final Vec3 from = new Vec3(0.125, 65.0, -0.375); // inside the grown box, outside the bare one
        final Vec3 to = new Vec3(0.125, 65.0, 1.625);
        final double d = intercept(EXACT, 0.25, from, to, point);
        assertEquals(0.875 * 0.875, d, 0.0); // exit face z = 0.5
        assertEquals(0.5, point[2], 0.0);
        assertTrue(EXACT.inflate(0.25).clip(from, to).isEmpty()); // 26.3 clips entry faces only
    }

    @Test
    void bothEndsOfTheSegmentAndTheFaceEdgesAreInclusiveOn17() {
        final double[] point = new double[3];
        // The segment ends exactly on the face: t = 1 counts (getIntermediateWithZValue: d4 >= 0.0 && d4 <= 1.0)
        assertEquals(1.0, intercept(EXACT, 0.0, new Vec3(0.0, 65.0, -1.25), new Vec3(0.0, 65.0, -0.25), point), 0.0);
        // ... where 26.3's AABB.clip needs 0 < s < 1
        assertTrue(EXACT.clip(new Vec3(0.0, 65.0, -1.25), new Vec3(0.0, 65.0, -0.25)).isEmpty());
        // A segment starting on the face: t = 0 counts on 1.7, not on 26.3
        assertEquals(0.0, intercept(EXACT, 0.0, new Vec3(0.0, 65.0, -0.25), new Vec3(0.0, 65.0, 1.0), point), 0.0);
        // Grazing a face's edge: the face bounds are inclusive (>=, <=)
        assertTrue(intercept(EXACT, 0.0, new Vec3(0.25, 65.0, -1.0), new Vec3(0.25, 65.0, 1.0), point) >= 0.0);
        assertEquals(-1.0, intercept(EXACT, 0.0, new Vec3(0.25 + 1.0E-12, 65.0, -1.0), new Vec3(0.25 + 1.0E-12, 65.0, 1.0), point), 0.0);
    }

    @Test
    void anAxisWithATinyDeltaIsSkipped() {
        final double[] point = new double[3];
        // dz² = 1e-8 < 1.0000000116860974E-7: Vec3.getIntermediateWithZValue returns null, so a segment that only crosses a
        // z face misses on 1.7. 26.3 skips an axis only under 1e-7 and hits it.
        final Vec3 from = new Vec3(0.0, 65.0, -0.30005);
        final Vec3 to = new Vec3(0.0, 65.0, -0.29995);
        assertEquals(-1.0, intercept(PLAYER, 0.0, from, to, point), 0.0);
        assertTrue(PLAYER.clip(from, to).isPresent());
    }

    @Test
    void theNearestFaceAlongTheSegmentWins() {
        final double[] point = new double[3];
        // Enters through x = -0.5 at t = 0.5 (z = -0.25); the z = -0.5 plane is crossed at x = -0.75, outside the face
        final double d = intercept(EXACT, 0.25, new Vec3(-1.0, 65.0, -0.75), new Vec3(0.0, 65.0, 0.25), point);
        assertEquals(-0.5, point[0], 0.0);
        assertEquals(-0.25, point[2], 0.0);
        assertEquals(0.5, d, 0.0);
    }

    @Test
    void the17PickTakesTheFirstHitThenNearerOnesAndReplacesAZero() {
        assertTrue(ProjectileHits.nearer17(4.0, 0.0)); // nothing yet
        assertTrue(ProjectileHits.nearer17(1.0, 4.0));
        assertFalse(ProjectileHits.nearer17(4.0, 1.0));
        assertFalse(ProjectileHits.nearer17(1.0, 1.0)); // ties keep the first
        assertTrue(ProjectileHits.nearer17(9.0, 0.0)); // a hit at distance 0 is replaced by any later one
    }

    /**
     * A rod crossing the front of a standing player 0.46 from its centre line: 1.7's 0.3 margin hits it in any tick. 26.3
     * grows the box by 0, 0, 0.05, 0.1, 0.15 in ticks 1 to 5 and only reaches it from tick 6, when a rod cast at 1.5 is
     * 6.4 blocks out. At rod range (2 to 4 blocks, ticks 1 to 3) 26.3 needs the segment within 0.3 to 0.35 of the centre
     * line; 1.7 within 0.6.
     */
    @Test
    void rodRangeHitsNeed17sMarginFromTheFirstTick() {
        final double[] point = new double[3];
        final Vec3 from = new Vec3(0.46, 65.0, -1.0);
        final Vec3 to = new Vec3(0.46, 65.0, 0.5);
        for (int tick = 1; tick <= 10; tick++) {
            assertTrue(intercept(PLAYER, MARGIN_17, from, to, point) >= 0.0, "1.7 tick " + tick);
            assertEquals(tick >= 6, PLAYER.inflate(margin26(tick)).clip(from, to).isPresent(), "26.3 tick " + tick);
        }
    }

    /** On segments that start and end outside the box, 1.7's intercept equals 26.3's entry clip (away from the 1e-7 edges). */
    @Test
    void outsideToOutsideAgreesWithTheEntryClip() {
        final Random random = new Random(17L);
        final AABB box = PLAYER.inflate(MARGIN_17);
        final double[] point = new double[3];
        int hits = 0;
        for (int i = 0; i < 100_000; i++) {
            final Vec3 from = new Vec3(random.nextDouble() * 6 - 3, 62 + random.nextDouble() * 6, random.nextDouble() * 6 - 3);
            final Vec3 to = from.add(delta(random), delta(random), delta(random));
            if (box.contains(from) || box.contains(to)) {
                continue;
            }
            final double legacy = intercept(PLAYER, MARGIN_17, from, to, point);
            final boolean modern = box.clip(from, to).isPresent();
            assertEquals(modern, legacy >= 0.0, "from " + from + " to " + to);
            if (modern) {
                hits++;
                assertEquals(from.distanceToSqr(box.clip(from, to).orElseThrow()), legacy, 1.0E-9);
            }
        }
        assertTrue(hits > 1000, "hits " + hits);
    }

    // A delta component of 0.01 to 2 either way: away from 1.7's per-axis skip (|d| < 3.2e-4)
    private static double delta(final Random random) {
        final double magnitude = 0.01 + random.nextDouble() * 1.99;
        return random.nextBoolean() ? magnitude : -magnitude;
    }
}
