package gg.hcfrevival.revival.combat;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

import gg.hcfrevival.revival.RevivalRegistries;
import gg.hcfrevival.revival.RevivalTest;
import gg.hcfrevival.revival.config.CombatRules;
import gg.hcfrevival.revival.config.KnockbackProfile;
import java.lang.reflect.Method;
import java.util.Random;
import net.minecraft.util.RandomSource;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.phys.Vec3;
import org.junit.jupiter.api.Test;

/**
 * Neue's tipper and DI (docs/revival/neue-combat.md §6.2, §7.2), ported from Combo's {@code Combat.Shape} and
 * {@code Combat.Heading} ({@code Combo.Core/Combat/Combat.cs}). The knocks come from {@link KnockbackEngine} with a
 * standing victim (shadow y −0.0784) and the attacker 1 block toward −Z facing +Z (yaw 0), as in KnockbackEngineTest.
 */
@RevivalTest
@RevivalRegistries
class NeueShapeTest {

    private static final Vec3 STANDING = new Vec3(0.0, -0.0784000015258789, 0.0);
    private static final CombatRules NEUE = CombatRules.NEUE;
    private static final double EXACT = 1.0E-12;

    private static Vec3 knock(final KnockbackProfile profile, final double level) {
        return KnockbackEngine.knockback(STANDING, 0.0, -1.0, 0.0F, level, profile, 0.0, RandomSource.create(0L));
    }

    // A fresh knock at the given distance, no keys held
    private static Vec3 shaped(final Vec3 knock, final double distance, final KnockbackProfile profile) {
        return NeueShape.shape(knock, NeueShape.spot(distance, NEUE), NEUE, 0, 0, 0.0F, profile.velocityClamp());
    }

    private static void assertKnock(final double x, final double y, final double z, final Vec3 actual, final String what) {
        assertEquals(x, actual.x, EXACT, what + " x");
        assertEquals(y, actual.y, 1.0E-4, what + " y");
        assertEquals(z, actual.z, EXACT, what + " z");
    }

    @Test
    void tipperAndSourOnLegacy17() {
        final KnockbackProfile legacy = KnockbackProfile.LEGACY_1_7;
        final Vec3 standing = knock(legacy, 0.0);
        final Vec3 sprint = knock(legacy, 1.0);
        // §6.2 table: plain, tipper (d >= 2.6), sour (d < 1.4)
        assertKnock(0.0, 0.3608, 0.4, shaped(standing, 2.0, legacy), "no sprint plain");
        assertKnock(0.0, 0.3608, 0.44, shaped(standing, 2.8, legacy), "no sprint tipper");
        assertKnock(0.0, 0.3608, 0.36, shaped(standing, 1.0, legacy), "no sprint sour");
        assertKnock(0.0, 0.4608, 0.9, shaped(sprint, 2.0, legacy), "sprint armed plain");
        assertKnock(0.0, 0.4608, 0.99, shaped(sprint, 2.8, legacy), "sprint armed tipper");
        assertKnock(0.0, 0.4608, 0.81, shaped(sprint, 1.0, legacy), "sprint armed sour");
    }

    @Test
    void tipperAndSourOnHcfr() {
        final KnockbackProfile hcfr = KnockbackProfile.PRESETS.get("hcfr");
        final Vec3 armed = knock(hcfr, 1.3); // sprint-reset: armed ground start
        final Vec3 held = knock(hcfr, 0.8); // sprint held
        assertKnock(0.0, 0.3408, 0.97, shaped(armed, 2.0, hcfr), "armed plain");
        assertKnock(0.0, 0.3408, 1.067, shaped(armed, 2.6, hcfr), "armed tipper");
        assertKnock(0.0, 0.3408, 0.873, shaped(armed, 0.5, hcfr), "armed sour");
        assertKnock(0.0, 0.3408, 0.72, shaped(held, 2.0, hcfr), "held plain");
        assertKnock(0.0, 0.3408, 0.792, shaped(held, 2.99, hcfr), "held tipper");
        assertKnock(0.0, 0.3408, 0.648, shaped(held, 1.39, hcfr), "held sour");
    }

    @Test
    void bandBoundaries() {
        assertEquals(NeueShape.TIPPER, NeueShape.spot(2.6, NEUE)); // Combat.Shape: distance >= TipperFrom
        assertEquals(NeueShape.PLAIN, NeueShape.spot(2.5999999, NEUE));
        assertEquals(NeueShape.PLAIN, NeueShape.spot(1.4, NEUE)); // SourUnder is strict
        assertEquals(NeueShape.SOUR, NeueShape.spot(1.3999999, NEUE));
        assertEquals(NeueShape.SOUR, NeueShape.spot(0.0, NEUE)); // the eye inside the box: sour here (Combo: plain)
        assertEquals(NeueShape.PLAIN, NeueShape.spot(Double.NaN, NEUE)); // no distance
        assertEquals(NeueShape.PLAIN, NeueShape.spot(-1.0, NEUE));
        assertEquals(1.1, NeueShape.scale(NeueShape.TIPPER, NEUE));
        assertEquals(0.9, NeueShape.scale(NeueShape.SOUR, NEUE));
        assertEquals(1.0, NeueShape.scale(NeueShape.PLAIN, NEUE));
    }

    /** Classic must leave 1.7 exactly as it is: the engine's own vector, for any knock, distance and keys. */
    @Test
    void classicIsTheIdentity() {
        final CombatRules classic = CombatRules.CLASSIC;
        final Random random = new Random(7L);
        for (int i = 0; i < 10_000; i++) {
            final Vec3 knock = new Vec3(random.nextGaussian(), random.nextGaussian(), random.nextGaussian());
            final double distance = random.nextDouble() * 3.0;
            final int forward = random.nextInt(3) - 1;
            final int left = random.nextInt(3) - 1;
            final float yaw = random.nextFloat() * 720.0F - 360.0F;
            assertSame(knock, NeueShape.shape(knock, NeueShape.spot(distance, classic), classic, forward, left, yaw, 3.9));
        }
        assertTrue(!classic.shapesKnock()); // so LegacyKnockback never even calls it
    }

    @Test
    void yIsNeverTouchedAndTheResultIsClampedAgain() {
        final Vec3 knock = new Vec3(0.0, 0.3608, 3.8);
        final Vec3 tipper = NeueShape.shape(knock, NeueShape.TIPPER, NEUE, 0, 1, 0.0F, 3.9);
        assertEquals(0.3608, tipper.y);
        assertEquals(3.9, tipper.z); // 4.18 cos 15 = 4.04, clamped
        assertEquals(4.18 * Math.sin(Math.toRadians(15.0)), tipper.x, 1.0E-9);
        final Vec3 straight = NeueShape.shape(knock, NeueShape.TIPPER, NEUE, 0, 0, 0.0F, 3.9);
        assertEquals(3.9, straight.z); // 4.18 re-clamped: a tipper can't pass what a velocity packet carries
        assertEquals(0.3608, straight.y);
    }

    @Test
    void influenceGoldens() {
        final Vec3 k = new Vec3(0.0, 0.3608, 0.4);
        // §7.2: held +X (facing +Z, holding A) turns the knock the full 15 degrees toward +X
        assertDi(0.103527618041008, 0.386370330515627, NeueShape.shape(k, NeueShape.PLAIN, NEUE, 0, 1, 0.0F, 3.9), 15.0);
        // W+A at yaw 0: the diagonal is 45 degrees off the knock, so sin 45 of the 15
        assertDi(0.073625841953992, 0.393165658974135, NeueShape.shape(k, NeueShape.PLAIN, NEUE, 1, 1, 0.0F, 3.9), 10.6066017178);
        // Held along the knock (W) or straight against it (S): no bend
        assertDi(0.0, 0.4, NeueShape.shape(k, NeueShape.PLAIN, NEUE, 1, 0, 0.0F, 3.9), 0.0);
        assertDi(0.0, 0.4, NeueShape.shape(k, NeueShape.PLAIN, NEUE, -1, 0, 0.0F, 3.9), 0.0);
        // No keys: the knock as it is
        assertSame(k, NeueShape.shape(k, NeueShape.PLAIN, NEUE, 0, 0, 0.0F, 3.9));
        // The armed sprint tipper (0, 0.4608, 0.99), held +X
        assertDi(0.256230854651496, 0.956266568026178, NeueShape.shape(new Vec3(0.0, 0.4608, 0.9), NeueShape.TIPPER, NEUE, 0, 1, 0.0F, 3.9), 15.0);
        // Held D (−X) bends the other way
        assertDi(-0.103527618041008, 0.386370330515627, NeueShape.shape(k, NeueShape.PLAIN, NEUE, 0, -1, 0.0F, 3.9), -15.0);
    }

    private static void assertDi(final double x, final double z, final Vec3 actual, final double degrees) {
        assertEquals(x, actual.x, EXACT, "x");
        assertEquals(z, actual.z, EXACT, "z");
        assertEquals(degrees, Math.toDegrees(Math.atan2(actual.x, actual.z)), 1.0E-9, "bend");
    }

    @Test
    void influencePreservesLengthAndHeight() {
        final Random random = new Random(11L);
        for (int i = 0; i < 10_000; i++) {
            final Vec3 knock = new Vec3(random.nextDouble() * 2.0 - 1.0, random.nextDouble(), random.nextDouble() * 2.0 - 1.0);
            final int forward = random.nextInt(3) - 1;
            final int left = random.nextInt(3) - 1;
            final Vec3 bent = NeueShape.shape(knock, 1.0, 15.0, forward, left, random.nextFloat() * 360.0F, 3.9);
            assertEquals(Math.hypot(knock.x, knock.z), Math.hypot(bent.x, bent.z), EXACT);
            assertEquals(knock.y, bent.y);
            // never more than 15 degrees
            final double turn = Math.abs(Math.toDegrees(Math.atan2(knock.x * bent.z - knock.z * bent.x, knock.x * bent.x + knock.z * bent.z)));
            assertTrue(turn <= 15.0 + 1.0E-9, "turned " + turn);
        }
    }

    /**
     * The held direction is 26.3's Entity.getInputVector for the client's (left, 0, forward): 10,000 random keys and yaws,
     * the same direction within 1e-6 (both read the Mth tables). Diagonals come out unit length once normalized.
     */
    @Test
    void headingMatchesGetInputVector() throws ReflectiveOperationException {
        final Method inputVector = Entity.class.getDeclaredMethod("getInputVector", Vec3.class, float.class, float.class);
        inputVector.setAccessible(true);
        final Random random = new Random(3L);
        for (int i = 0; i < 10_000; i++) {
            final int forward = random.nextInt(3) - 1;
            final int left = random.nextInt(3) - 1;
            final float yaw = random.nextFloat() * 720.0F - 360.0F;
            final Vec3 vanilla = (Vec3) inputVector.invoke(null, new Vec3(left, 0.0, forward), 1.0F, yaw);
            final double hx = NeueShape.headingX(forward, left, yaw);
            final double hz = NeueShape.headingZ(forward, left, yaw);
            if (forward == 0 && left == 0) {
                assertEquals(Vec3.ZERO, vanilla);
                assertEquals(0.0, Math.hypot(hx, hz));
                continue;
            }
            final double ours = Math.hypot(hx, hz);
            final double theirs = Math.hypot(vanilla.x, vanilla.z);
            assertEquals(vanilla.x / theirs, hx / ours, 1.0E-6, "x at yaw " + yaw);
            assertEquals(vanilla.z / theirs, hz / ours, 1.0E-6, "z at yaw " + yaw);
        }
    }
}
