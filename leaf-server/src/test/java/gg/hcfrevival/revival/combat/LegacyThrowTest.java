package gg.hcfrevival.revival.combat;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import gg.hcfrevival.revival.RevivalTest;
import net.minecraft.world.phys.Vec3;
import org.junit.jupiter.api.Test;

/**
 * Golden values for 1.7.10 throws and flight (docs/revival/projectiles.md §3). The 1.7 reference paths are transcribed
 * from MCP 1.7.10: EntityThrowable(World, EntityLivingBase) and setThrowableHeading for the launch; EntityThrowable.onUpdate
 * for the flight ({@code posX += motionX ...; motion *= 0.99F; motionY -= getGravityVelocity()}); EntityFishHook.onUpdate
 * for the hook ({@code moveEntity(motion); motionY += 0.04 × (2 × water - 1); motion *= 0.92F}). The legacy path is the
 * 26.3 tick order (ThrowableProjectile.tick: applyGravity, applyInertia, move; FishingHook.tick: gravity, move, × 0.92)
 * fed the numbers LegacyThrow chooses.
 */
@RevivalTest
class LegacyThrowTest {

    private static final double DRAG = 0.99F;
    private static final double PEARL_GRAVITY = 0.03F;
    private static final double POTION_GRAVITY = 0.05F;

    @Test
    void tableSineIs17sAndCloseToTheRealOne() {
        for (int i = -720; i <= 720; i++) {
            final float radians = i / 180.0F * (float) Math.PI;
            assertEquals(Math.sin(radians), LegacyThrow.sin(radians), 1.0E-4, "sin " + i);
            assertEquals(Math.cos(radians), LegacyThrow.cos(radians), 1.0E-4, "cos " + i);
        }
        assertEquals(0.0F, LegacyThrow.sin(0.0F));
        assertEquals(1.0F, LegacyThrow.cos(0.0F));
    }

    @Test
    void headingsFollowTheLookAndTheItemsPitchOffset() {
        // A potion thrown straight down: -20 offset, speed 0.5. Nothing of the thrower's motion is in it.
        final Vec3 down = LegacyThrow.heading(0.0F, 90.0F, -20.0F, LegacyThrow.THROWABLE_LENGTH, 0.5, 0.0, 0.0, 0.0, 0.0);
        assertEquals(0.0, down.x, 1.0E-9);
        assertEquals(-0.5, down.y, 1.0E-9);
        assertEquals(0.0, down.z, 1.0E-9);
        // A potion thrown level: the -20 offset tilts only y; normalised (0, 0.13681, 0.4) × 0.5
        final Vec3 level = LegacyThrow.heading(0.0F, 0.0F, -20.0F, LegacyThrow.THROWABLE_LENGTH, 0.5, 0.0, 0.0, 0.0, 0.0);
        assertEquals(0.0, level.x, 1.0E-9);
        assertEquals(0.16177387, level.y, 1.0E-7);
        assertEquals(0.47310593, level.z, 1.0E-7);
        // A pearl at yaw 90 (facing -x), 45 up, speed 1.5
        final Vec3 pearl = LegacyThrow.heading(90.0F, -45.0F, 0.0F, LegacyThrow.THROWABLE_LENGTH, 1.5, 0.0, 0.0, 0.0, 0.0);
        assertEquals(-1.06066020, pearl.x, 1.0E-7);
        assertEquals(1.06066020, pearl.y, 1.0E-7);
        assertEquals(0.0, pearl.z, 1.0E-9);
        // setThrowableHeading's spread: + gaussian × (double) 0.0075F × inaccuracy, before the speed
        final Vec3 spread = LegacyThrow.heading(0.0F, 0.0F, 0.0F, LegacyThrow.THROWABLE_LENGTH, 1.5, 0.0075, 1.0, -2.0, 0.5);
        assertEquals(1.0 * 0.007499999832361937 * 1.5, spread.x, 1.0E-9);
        assertEquals(-2.0 * 0.007499999832361937 * 1.5, spread.y, 1.0E-9);
        assertEquals((1.0 + 0.5 * 0.007499999832361937) * 1.5, spread.z, 1.0E-9);
    }

    @Test
    void originIsTheServersFixedEyeLessATenthAndSixteenHundredthsToTheSide() {
        // posY + (double) 1.62F - (double) 0.1F; posX - cos(yaw) × 0.16F; posZ - sin(yaw) × 0.16F
        assertEquals(64.0 + 1.6200000047683716 - 0.10000000149011612, LegacyThrow.originY(64.0, 1.62F, 0.1), 0.0);
        assertEquals(10.5 - 0.1599999964237213, LegacyThrow.originX(10.5, 0.0F, 0.16), 0.0);
        assertEquals(-3.5, LegacyThrow.originZ(-3.5, 0.0F, 0.16), 0.0);
        assertEquals(10.5, LegacyThrow.originX(10.5, 90.0F, 0.16), 1.0E-7);
        assertEquals(-3.5 - 0.16, LegacyThrow.originZ(-3.5, 90.0F, 0.16), 1.0E-7);
    }

    @Test
    void legacyFlightMoves17sPathInThe26xTickOrder() {
        for (final float pitch : new float[] {-90.0F, -45.0F, -20.0F, 0.0F, 30.0F, 89.0F}) {
            for (final double gravity : new double[] {PEARL_GRAVITY, POTION_GRAVITY}) {
                final Vec3 v0 = LegacyThrow.heading(37.0F, pitch, 0.0F, LegacyThrow.THROWABLE_LENGTH, 1.5, 0.0, 0.0, 0.0, 0.0);
                // 1.7: move, then drag, then gravity
                double x17 = 0.0;
                double y17 = 0.0;
                double z17 = 0.0;
                double vx17 = v0.x;
                double vy17 = v0.y;
                double vz17 = v0.z;
                // 26.x: gravity (g / d), inertia (d), move, from the legacy launch velocity
                final Vec3 launch = LegacyThrow.launch(v0, gravity, DRAG);
                double x = 0.0;
                double y = 0.0;
                double z = 0.0;
                double vx = launch.x;
                double vy = launch.y;
                double vz = launch.z;
                for (int tick = 1; tick <= 200; tick++) {
                    x17 += vx17;
                    y17 += vy17;
                    z17 += vz17;
                    vx17 *= DRAG;
                    vy17 = vy17 * DRAG - gravity;
                    vz17 *= DRAG;

                    vy -= gravity / DRAG;
                    vx *= DRAG;
                    vy *= DRAG;
                    vz *= DRAG;
                    x += vx;
                    y += vy;
                    z += vz;
                    assertEquals(x17, x, 1.0E-9, "x pitch " + pitch + " tick " + tick);
                    assertEquals(y17, y, 1.0E-9, "y pitch " + pitch + " tick " + tick);
                    assertEquals(z17, z, 1.0E-9, "z pitch " + pitch + " tick " + tick);
                }
            }
        }
    }

    /** The landing distance, 1.52 above flat ground, of a pearl thrown at each pitch: 1.7 and 26.x, as in projectiles.md §3.2. */
    @Test
    void pearlsFly17sDistanceAnd26xFallsShort() {
        final float[] pitches = {0.0F, -10.0F, -20.0F, -30.0F, -45.0F};
        final double[] legacy = {15.31, 29.91, 43.09, 50.91, 51.67};
        final double[] modern = {13.89, 27.83, 41.05, 49.10, 50.29};
        for (int i = 0; i < pitches.length; i++) {
            final Vec3 v0 = LegacyThrow.heading(0.0F, pitches[i], 0.0F, LegacyThrow.THROWABLE_LENGTH, 1.5, 0.0, 0.0, 0.0, 0.0);
            assertEquals(legacy[i], landing(LegacyThrow.launch(v0, PEARL_GRAVITY, DRAG), PEARL_GRAVITY / DRAG), 0.01, "legacy pitch " + pitches[i]);
            assertEquals(modern[i], landing(v0, PEARL_GRAVITY), 0.01, "26.x pitch " + pitches[i]);
        }
    }

    // 26.x's ThrowableProjectile order from 1.52 above flat ground: where the segment crosses y = 0
    private static double landing(final Vec3 launch, final double gravity) {
        double z = 0.0;
        double y = 1.52;
        double vy = launch.y;
        double vz = launch.z;
        for (int tick = 0; tick < 400; tick++) {
            vy = (vy - gravity) * DRAG;
            vz *= DRAG;
            final double ny = y + vy;
            final double nz = z + vz;
            if (ny < 0.0) {
                return z + (nz - z) * (y / (y - ny));
            }
            y = ny;
            z = nz;
        }
        return Double.NaN;
    }

    @Test
    void legacyRodMoves17sHookPath() {
        final double gravity = 0.04;
        final Vec3 v0 = LegacyProjectiles.castMotion(0.0F, -10.0F, 1.5, 0.0075, 0.0, 0.0, 0.0);
        // 1.7 EntityFishHook: moveEntity(motion); motionY -= 0.04F; motion *= 0.92F
        double y17 = 0.0;
        double vy17 = v0.y;
        // 26.x FishingHook: deltaMovement.y -= gravity; move; × 0.92 (with LegacyThrow's launch and gravity)
        final double g = LegacyThrow.rodGravity(gravity);
        double y = 0.0;
        double vy = LegacyThrow.rodLaunch(v0, gravity).y;
        // What the rod did before: 0.04 per tick in 26.x's order
        double yOld = 0.0;
        double vyOld = v0.y;
        for (int tick = 1; tick <= 20; tick++) {
            y17 += vy17;
            vy17 = (vy17 - (double) 0.04F) * LegacyThrow.ROD_DRAG;
            vy -= g;
            y += vy;
            vy *= LegacyThrow.ROD_DRAG;
            vyOld -= gravity;
            yOld += vyOld;
            vyOld *= LegacyThrow.ROD_DRAG;
            assertEquals(y17, y, 1.0E-9, "tick " + tick);
            assertEquals(y17 - gravity * tick, yOld, 1.0E-6, "the old path sinks 0.04 more every tick, tick " + tick);
        }
    }

    /**
     * A potion thrown straight down by a player standing at (0, 64, 0) facing south (yaw 0): 1.7 starts it at
     * (-0.16, 65.52, 0), moves it 0.5, 0.545 and then hits the ground in tick 3 from y = 64.475, 0.501 from the feet.
     */
    @Test
    void aStandingSelfPotBurstsHalfABlockUpOn17() {
        final Vec3 v0 = LegacyThrow.heading(0.0F, 90.0F, -20.0F, LegacyThrow.THROWABLE_LENGTH, 0.5, 0.0, 0.0, 0.0, 0.0);
        final Vec3 launch = LegacyThrow.launch(v0, POTION_GRAVITY, DRAG);
        double y = LegacyThrow.originY(64.0, 1.62F, 0.1);
        double vy = launch.y;
        double start = y;
        int ticks = 0;
        while (true) {
            ticks++;
            vy -= POTION_GRAVITY / DRAG;
            vy *= DRAG;
            start = y;
            if (y + vy < 64.0) {
                break;
            }
            y += vy;
        }
        assertEquals(3, ticks);
        assertEquals(64.475, start, 1.0E-6); // the potion's oldPosition() in its impact tick: 1.7's splash centre
        final double x = LegacyThrow.originX(0.0, 0.0F, 0.16);
        final double scale = LegacySplash.legacyScale(x, start, 0.0, 0.0, 64.0, 0.0, false);
        assertEquals(0.87469, scale, 1.0E-5);
        assertEquals(7, LegacySplash.heal(scale, 1));
        assertTrue(LegacySplash.legacyScale(x, start, 0.0, 0.8418, 64.0, 0.0, false) < 0.73); // sprinting three ticks away: heals 6
        assertEquals(6, LegacySplash.heal(LegacySplash.legacyScale(x, start, 0.0, 0.8418, 64.0, 0.0, false), 1));
    }
}
