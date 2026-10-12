package gg.hcfrevival.revival.combat;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import gg.hcfrevival.revival.RevivalTest;
import gg.hcfrevival.revival.config.KnockbackProfile;
import net.minecraft.util.RandomSource;
import net.minecraft.world.phys.Vec3;
import org.junit.jupiter.api.Test;

/**
 * 1.7.10 server-side player motion with no input (EntityLivingBase.onLivingUpdate and moveEntityWithHeading):
 * motion under 0.005 snaps to zero, the floor zeroes downward motion, then motY = (motY - 0.08) × 0.98 and
 * horizontal motion × slipperiness × 0.91 on the ground (0.6 for most blocks) or × 0.91 in the air.
 */
@RevivalTest
class MotionShadowTest {

    private static final double GRAVITY = 0.08;
    private static final double EPSILON = 1.0E-9;

    @Test
    void standingPlayerSettlesAtMinus00784() {
        final MotionShadow shadow = new MotionShadow();
        for (int tick = 0; tick < 5; tick++) {
            shadow.step(true, GRAVITY, 0.6);
            assertEquals(-0.0784, shadow.motion().y, EPSILON);
        }
    }

    @Test
    void standingHitAlwaysLifts03608WhateverTheTicksBefore() {
        // SportBukkit: the knock never enters the shadow, so the next hit starts from the same steady state
        final MotionShadow shadow = new MotionShadow();
        for (int ticks = 1; ticks <= 3; ticks++) {
            for (int tick = 0; tick < ticks; tick++) {
                shadow.step(true, GRAVITY, 0.6);
            }
            final Vec3 knock = KnockbackEngine.knockback(shadow.motion(), 0.0, -1.0, 0.0F, 0.0, KnockbackProfile.LEGACY_1_7, 0.0, RandomSource.create(0L));
            assertEquals(0.3608, knock.y, 1.0E-4);
        }
    }

    @Test
    void gravityAttributeChangesTheSteadyState() {
        final MotionShadow shadow = new MotionShadow();
        shadow.step(true, 0.1, 0.6); // prod's Guardians
        assertEquals(-0.098, shadow.motion().y, EPSILON);
        assertEquals(new Vec3(0.0, -0.098, 0.0), MotionShadow.settled(Vec3.ZERO, 0.1));
    }

    @Test
    void airborneMotionDecays() {
        final MotionShadow shadow = new MotionShadow();
        shadow.set(new Vec3(0.4, 0.3608, 0.0));
        shadow.step(false, GRAVITY, 0.6);
        assertEquals((0.3608 - 0.08) * 0.98, shadow.motion().y, EPSILON);
        assertEquals(0.4 * 0.91, shadow.motion().x, EPSILON);
        shadow.step(true, GRAVITY, 0.6);
        assertEquals(0.4 * 0.91 * 0.6F * 0.91F, shadow.motion().x, 1.0E-7);
    }

    @Test
    void comboHitsCompoundOnlyWhenTheKnockStaysInTheShadow() {
        final Vec3 first = KnockbackEngine.knockback(new Vec3(0.0, -0.0784, 0.0), 0.0, -1.0, 0.0F, 0.0, KnockbackProfile.LEGACY_1_7, 0.0, RandomSource.create(0L));

        final MotionShadow vanilla = new MotionShadow();
        vanilla.set(first);
        vanilla.step(false, GRAVITY, 0.6);
        final Vec3 compounded = KnockbackEngine.knockback(vanilla.motion(), 0.0, -1.0, 0.0F, 0.0, KnockbackProfile.LEGACY_1_7, 0.0, RandomSource.create(0L));
        assertEquals(first.z * 0.91 / 2.0 + 0.4, compounded.z, EPSILON);
    }

    @Test
    void tinyMotionSnapsToZero() {
        final MotionShadow shadow = new MotionShadow();
        shadow.set(new Vec3(0.004, 0.0, -0.004));
        shadow.step(true, GRAVITY, 0.6);
        assertEquals(0.0, shadow.motion().x);
        assertEquals(0.0, shadow.motion().z);
    }

    @Test
    void landingTicksMatchCoreClientGround() {
        assertEquals(0, MotionShadow.landingTicks(0.0, 0.0, GRAVITY));
        // From rest 0.5 above the ground the drop is 0.08, 0.1584, 0.2352, 0.3105: past 0.5 on tick 4
        assertEquals(4, MotionShadow.landingTicks(0.0, 0.5, GRAVITY));
        assertEquals(-1, MotionShadow.landingTicks(10.0, 1.0, GRAVITY));
    }

    @Test
    void landedForClientNeedsPingToCoverTheFall() {
        assertTrue(MotionShadow.landedForClient(200, 0.0, 0.5, GRAVITY, 1.3)); // 4 ticks
        assertFalse(MotionShadow.landedForClient(150, 0.0, 0.5, GRAVITY, 1.3));
        assertFalse(MotionShadow.landedForClient(1000, 0.0, 2.0, GRAVITY, 1.3)); // too far to trust
        assertFalse(MotionShadow.landedForClient(1000, 0.0, 0.5, GRAVITY, 0.0)); // turned off
        assertFalse(MotionShadow.landedForClient(0, 0.0, 0.5, GRAVITY, 1.3));
    }
}
