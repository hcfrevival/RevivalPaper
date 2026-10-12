package gg.hcfrevival.revival.combat;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;

import gg.hcfrevival.revival.RevivalTest;
import gg.hcfrevival.revival.config.KnockbackProfile;
import gg.hcfrevival.revival.config.KnockbackProfile.ResistanceMode;
import java.util.List;
import net.minecraft.util.RandomSource;
import net.minecraft.world.phys.Vec3;
import org.junit.jupiter.api.Test;

/**
 * Golden values for 1.7.10 knockback. EntityLivingBase.knockBack: motion /= 2, minus 0.4 × the unit vector toward
 * the attacker, motY += 0.4, capped at 0.4000000059604645. EntityPlayer.attackTargetEntityWithCurrentItem then adds
 * (-sin(yaw) × level × 0.5, 0.1, cos(yaw) × level × 0.5). A player standing still on the ground has motY -0.0784
 * (gravity 0.08 × drag 0.98).
 */
@RevivalTest
class KnockbackEngineTest {

    private static final double STANDING_Y = -0.0784000015258789;
    private static final Vec3 STANDING = new Vec3(0.0, STANDING_Y, 0.0);
    private static final double EPSILON = 1.0E-4;

    // Attacker 1 block toward -z of the victim, facing +z (yaw 0), so base and extra knock both push +z
    private static Vec3 hit(final KnockbackProfile profile, final double level) {
        return KnockbackEngine.knockback(STANDING, 0.0, -1.0, 0.0F, level, profile, 0.0, RandomSource.create(0L));
    }

    private static void assertKnock(final String preset, final double standingH, final double standingV, final double sprintH, final double sprintV) {
        final KnockbackProfile profile = KnockbackProfile.PRESETS.get(preset);
        final Vec3 standing = hit(profile, 0.0);
        final Vec3 sprint = hit(profile, 1.0);
        assertEquals(standingH, standing.z, EPSILON, preset + " standing horizontal");
        assertEquals(standingV, standing.y, EPSILON, preset + " standing vertical");
        assertEquals(sprintH, sprint.z, EPSILON, preset + " sprint horizontal");
        assertEquals(sprintV, sprint.y, EPSILON, preset + " sprint vertical");
        assertEquals(0.0, sprint.x, EPSILON, preset + " sideways");
    }

    @Test
    void presetsMatchThePlanTable() {
        assertKnock("legacy-1.7", 0.4, 0.3608, 0.9, 0.4608);
        assertKnock("legacy-1.8", 0.4, 0.3608, 0.9, 0.4608);
        assertKnock("kohi", 0.35, 0.3108, 0.775, 0.3958);
        assertKnock("minehq", 0.36, 0.3208, 0.81, 0.4108);
        assertKnock("badlion", 0.34, 0.3008, 0.82, 0.3858);
        assertKnock("mmc", 0.32, 0.2764, 0.82, 0.3764);
        assertKnock("lunar", 0.54, 0.3617, 0.92, 0.3617);
        assertKnock("velt", 0.325, 0.3522, 0.825, 0.3522);
    }

    @Test
    void verticalIsCappedBeforeTheExtraKnock() {
        final Vec3 rising = KnockbackEngine.knockback(new Vec3(0.0, 0.5, 0.0), 0.0, -1.0, 0.0F, 1.0, KnockbackProfile.LEGACY_1_7, 0.0, RandomSource.create(0L));
        assertEquals(0.4000000059604645 + 0.1, rising.y, 1.0E-9);
    }

    @Test
    void airborneVictimsStillGetLift() {
        // 26.x keeps airborne motY; 1.7.10 always halves it and adds 0.4
        final Vec3 falling = KnockbackEngine.knockback(new Vec3(0.0, -0.5, 0.0), 0.0, -1.0, 0.0F, 0.0, KnockbackProfile.LEGACY_1_7, 0.0, RandomSource.create(0L));
        assertEquals(0.15, falling.y, 1.0E-9);
    }

    @Test
    void victimMotionIsHalvedAndPushedAwayFromTheAttacker() {
        final Vec3 moving = KnockbackEngine.knockback(new Vec3(0.2, 0.0, 0.0), 3.0, 4.0, 0.0F, 0.0, KnockbackProfile.LEGACY_1_7, 0.0, RandomSource.create(0L));
        assertEquals(0.1 - 0.6 * 0.4, moving.x, 1.0E-9);
        assertEquals(-0.8 * 0.4, moving.z, 1.0E-9);
    }

    @Test
    void resultIsClampedToTheVelocityPacketLimit() {
        final Vec3 fast = KnockbackEngine.knockback(new Vec3(20.0, 0.0, 0.0), 0.0, -1.0, 0.0F, 0.0, KnockbackProfile.LEGACY_1_7, 0.0, RandomSource.create(0L));
        assertEquals(3.9, fast.x, 1.0E-9);
    }

    @Test
    void resistanceModes() {
        final KnockbackProfile none = KnockbackProfile.LEGACY_1_7;
        final KnockbackProfile roll = withResistance(ResistanceMode.ROLL);
        final KnockbackProfile scale = withResistance(ResistanceMode.SCALE);

        assertEquals(0.4, KnockbackEngine.knockback(STANDING, 0.0, -1.0, 0.0F, 0.0, none, 1.0, RandomSource.create(0L)).z, EPSILON);
        assertSame(STANDING, KnockbackEngine.knockback(STANDING, 0.0, -1.0, 0.0F, 0.0, roll, 1.0, RandomSource.create(0L)));
        assertEquals(0.4, KnockbackEngine.knockback(STANDING, 0.0, -1.0, 0.0F, 0.0, roll, 0.0, RandomSource.create(0L)).z, EPSILON);
        assertEquals(0.2, KnockbackEngine.knockback(STANDING, 0.0, -1.0, 0.0F, 0.0, scale, 0.5, RandomSource.create(0L)).z, EPSILON);
    }

    @Test
    void coincidingPositionsStillKnock() {
        final Vec3 knocked = KnockbackEngine.knockback(STANDING, 0.0, 0.0, 0.0F, 0.0, KnockbackProfile.LEGACY_1_7, 0.0, RandomSource.create(0L));
        assertEquals(0.4, Math.sqrt(knocked.x * knocked.x + knocked.z * knocked.z), EPSILON);
    }

    /**
     * Parity with today's prod knockback: core's essentials SwordProfile.velocity with mechanics.yml's values
     * (hcfrevival/core, essentials/.../combat/sword/SwordProfile.java). Core's sprint level is 0.8, or 1.3 for a
     * sprint reset; that arrives with the sprint phase, so the levels are passed in directly here.
     */
    @Test
    void hcfrMatchesCoreSwordProfile() {
        final KnockbackProfile hcfr = KnockbackProfile.PRESETS.get("hcfr");
        final Vec3[] currents = {STANDING, new Vec3(0.3, 0.2, -0.1), new Vec3(-0.5, -0.6, 0.25)};
        final double[][] offsets = {{0.0, -1.0}, {2.0, 1.5}, {-0.7, 0.3}};
        final float[] yaws = {0.0F, 90.0F, -135.5F};
        final double[] levels = {0.0, 0.8, 1.3};
        for (final Vec3 current : currents) {
            for (final double[] offset : offsets) {
                for (final float yaw : yaws) {
                    for (final double level : levels) {
                        final Vec3 ours = KnockbackEngine.knockback(current, offset[0], offset[1], yaw, level, hcfr, 0.0, RandomSource.create(0L));
                        final Vec3 core = coreVelocity(current, -offset[0], -offset[1], yaw, level);
                        final String inputs = current + " " + offset[0] + "," + offset[1] + " yaw " + yaw + " level " + level;
                        // Core uses Math.sin; the engine uses Mth.sin's 1.7 lookup table, which differs in the 5th decimal
                        assertEquals(core.x, ours.x, 1.0E-4, inputs);
                        assertEquals(core.y, ours.y, 1.0E-9, inputs);
                        assertEquals(core.z, ours.z, 1.0E-4, inputs);
                    }
                }
            }
        }
    }

    // Core's SwordProfile.velocity and its helpers, with mechanics.yml combat.knockback (friction 2, horizontal 0.32,
    // vertical 0.38, vertical-limit 0.36, extra-horizontal 0.5, extra-vertical 0); direction is victim minus attacker
    private static Vec3 coreVelocity(final Vec3 current, final double dx, final double dz, final float yaw, final double level) {
        final double length = Math.sqrt(dx * dx + dz * dz);
        double x = current.x / 2.0 + dx / length * 0.32;
        double y = Math.min(current.y / 2.0 + 0.38, 0.36);
        double z = current.z / 2.0 + dz / length * 0.32;
        if (level > 0) {
            final double extra = 0.5 * level;
            x += -Math.sin(Math.toRadians(yaw)) * extra;
            z += Math.cos(Math.toRadians(yaw)) * extra;
        }
        return new Vec3(x, y, z);
    }

    private static KnockbackProfile withResistance(final ResistanceMode mode) {
        final KnockbackProfile p = KnockbackProfile.LEGACY_1_7;
        return new KnockbackProfile(p.friction(), p.frictionVertical(), p.horizontal(), p.vertical(), p.verticalCap(), p.extraHorizontal(), p.extraVertical(), p.attackerSlowdown(), p.velocityClamp(), mode, p.motionModel(), p.groundPredictionDistance(), p.groundPredictionPingOffset());
    }

    @Test
    void everyPresetIsListed() {
        assertEquals(List.of("legacy-1.7", "legacy-1.8", "kohi", "minehq", "badlion", "mmc", "lunar", "velt", "hcfr"), List.copyOf(KnockbackProfile.PRESETS.keySet()));
    }
}
