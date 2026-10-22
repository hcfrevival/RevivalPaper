package gg.hcfrevival.revival.combat;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;

import gg.hcfrevival.revival.RevivalTest;
import gg.hcfrevival.revival.config.KnockbackProfile;
import net.minecraft.util.RandomSource;
import net.minecraft.world.phys.Vec3;
import org.junit.jupiter.api.Test;

/**
 * Golden values from the 1.7.10 server jar: EntityFishHook reels a hooked entity by {@code d × 0.1} plus
 * {@code √distance × 0.08} up and costs 3 durability, casts at 1.5 along the look with {@code 0.0075} Gaussian
 * spread, and falls 0.04 per tick; EntityArrow's Punch adds {@code 0.6 × level} along the flight and 0.1 up.
 */
@RevivalTest
class LegacyProjectilesTest {

    private static final double EPSILON = 1.0E-6;

    @Test
    void reelPull() {
        final Vec3 pull = LegacyProjectiles.reelPull(3.0, 0.0, 4.0); // owner 5 blocks away
        assertEquals(0.3, pull.x, EPSILON);
        assertEquals(Math.sqrt(5.0) * 0.08, pull.y, 1.0E-5);
        assertEquals(0.4, pull.z, EPSILON);
        assertEquals(0.1 * 2.0 + Math.sqrt(2.0) * 0.08, LegacyProjectiles.reelPull(0.0, 2.0, 0.0).y, 1.0E-5);
    }

    @Test
    void castFollowsTheLookAt15() {
        final Vec3 forward = LegacyProjectiles.castMotion(0.0F, 0.0F, 1.5, 0.0075, 0.0, 0.0, 0.0);
        assertEquals(0.0, forward.x, 1.0E-4);
        assertEquals(0.0, forward.y, 1.0E-4);
        assertEquals(1.5, forward.z, 1.0E-4);
        final Vec3 up = LegacyProjectiles.castMotion(0.0F, -90.0F, 1.5, 0.0075, 0.0, 0.0, 0.0);
        assertEquals(1.5, up.y, 1.0E-4);
        final Vec3 east = LegacyProjectiles.castMotion(-90.0F, 0.0F, 1.5, 0.0075, 0.0, 0.0, 0.0);
        assertEquals(1.5, east.x, 1.0E-4);
        assertEquals(1.5 * (1.0 + 0.0075), LegacyProjectiles.castMotion(0.0F, 0.0F, 1.5, 0.0075, 0.0, 0.0, 1.0).z, 1.0E-4);
    }

    @Test
    void punchPushesAlongTheFlight() {
        final Vec3 knocked = new Vec3(0.4, 0.3608, 0.0);
        final Vec3 punched = KnockbackEngine.punch(knocked, 2.0, 0.0, 2, 0.6, 0.1, KnockbackProfile.LEGACY_1_7);
        assertEquals(0.4 + 1.2, punched.x, EPSILON);
        assertEquals(0.4608, punched.y, EPSILON);
        assertEquals(0.0, punched.z, EPSILON);
        assertSame(knocked, KnockbackEngine.punch(knocked, 2.0, 0.0, 0, 0.6, 0.1, KnockbackProfile.LEGACY_1_7));
    }

    /**
     * Parity with prod's arrow knock: core's SwordProfile.projectileVelocity with mechanics.yml (friction 2,
     * projectile-horizontal 0.2, projectile-vertical 0.25, vertical-limit 0.36, projectile-punch 0.6), for an arrow
     * flying straight from the shooter to the victim.
     */
    @Test
    void hcfrProjectileMatchesCore() {
        final KnockbackProfile profile = KnockbackProfile.PRESETS.get("hcfr-projectile");
        final Vec3 current = new Vec3(0.1, -0.0784, -0.05);
        for (int punch = 0; punch <= 2; punch++) {
            // Shooter 3 blocks toward -x of the victim, arrow flying +x
            final Vec3 base = KnockbackEngine.knockback(current, -3.0, 0.0, 0.0F, 0.0, profile, 0.0, RandomSource.create(0L));
            final Vec3 ours = KnockbackEngine.punch(base, 3.0, 0.0, punch, 0.6, 0.1, profile);

            double x = current.x / 2.0 + 0.2;
            double y = Math.min(current.y / 2.0 + 0.25, 0.36);
            final double z = current.z / 2.0;
            if (punch > 0) {
                x += punch * 0.6;
                y += 0.1;
            }
            assertEquals(x, ours.x, EPSILON, "punch " + punch);
            assertEquals(y, ours.y, EPSILON, "punch " + punch);
            assertEquals(z, ours.z, EPSILON, "punch " + punch);
        }
    }
}
