package gg.hcfrevival.revival.combat;

import gg.hcfrevival.revival.Revival;
import gg.hcfrevival.revival.config.LegacyProjectileSettings;
import gg.hcfrevival.revival.config.ProjectileRules;
import net.minecraft.util.Mth;
import net.minecraft.util.RandomSource;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.entity.projectile.Projectile;
import net.minecraft.world.entity.projectile.ThrowableProjectile;
import net.minecraft.world.entity.projectile.throwableitemprojectile.ThrownSplashPotion;
import net.minecraft.world.phys.Vec3;

/**
 * How a legacy projectile leaves the thrower and flies (docs/revival/projectiles.md §3, §4).
 * <p>
 * <b>Throw</b> ({@code throw: legacy-1.7}). 1.7.10's EntityThrowable and EntityArrow constructors started the projectile at
 * the thrower's feet + 1.62 (EntityPlayerMP's constant eye height), 0.1 lower and 0.16 to the side
 * ({@code posX -= cos(yaw) × 0.16F}, {@code posZ -= sin(yaw) × 0.16F}), aimed it along {@code (-sin yaw cos pitch,
 * -sin(pitch + offset), cos yaw cos pitch)}, normalised it with a float square root, added a Gaussian of 0.0075 × the
 * inaccuracy per axis and scaled it to the speed ({@code setThrowableHeading}). Nothing of the thrower's motion was added.
 * 26.x's {@code Projectile.shootFromRotation} adds the thrower's known movement (y only while airborne), starts at the eye
 * of the current pose, uses a triangle spread and drops the side offset.
 * <p>
 * <b>Flight</b> ({@code flight: legacy-1.7}). 1.7 moved a thrown item by its motion and then applied drag and gravity
 * ({@code motion *= 0.99F; motionY -= gravity}); 26.x's {@code ThrowableProjectile.tick} applies gravity and drag first,
 * so every displacement is one tick of drag and gravity behind 1.7's and a pearl lands 1.4 to 2.1 blocks short. The
 * legacy flight keeps 26.x's tick and changes two numbers so it moves 1.7's path: the launch velocity is
 * {@code (v0 + g ŷ) / d} and the gravity per tick is {@code g / d} ({@code d} is the drag that tick, 0.99 in air and 0.8
 * in water). Then 26.x's displacement {@code (w - g/d) × d} equals 1.7's {@code v}, tick for tick. The client still runs
 * 26.x's physics on the velocity it was sent, so it draws the same path to within 3e-4 blocks per tick.
 * <p>
 * The rod is the same with the hook's own order (gravity, move, drag 0.92): launch {@code v0 + 0.92 g ŷ}, gravity
 * {@code 0.92 g} ({@link LegacyProjectiles#cast}).
 */
public final class LegacyThrow {

    /** 1.7's {@code new EntityThrowable(world, thrower)}: {@code float f = 0.4F} before the heading is normalised. */
    static final float THROWABLE_LENGTH = 0.4F;
    /** 1.7's {@code new EntityArrow(world, shooter, velocity)}: the look vector is used at length 1. */
    static final float ARROW_LENGTH = 1.0F;
    /** 1.7's EntityFishHook drag ({@code float f6 = 0.92F}), the same as 26.x's FishingHook inertia. */
    static final double ROD_DRAG = 0.92;
    /** 26.x's {@code ThrowableProjectile} inertia in water ({@code 0.8F}, as 1.7's). */
    static final double WATER_DRAG = 0.8F;

    private static final float[] SIN_TABLE = new float[65536];

    static {
        for (int i = 0; i < SIN_TABLE.length; i++) {
            SIN_TABLE[i] = (float) Math.sin(i * Math.PI * 2.0 / 65536.0); // 1.7.10 MathHelper.SIN_TABLE
        }
    }

    private LegacyThrow() {
    }

    // ------------------------------------------------------------------------------------------------------------------
    // Hooks
    // ------------------------------------------------------------------------------------------------------------------

    /**
     * {@code Projectile.shootFromRotation}, before anything else. Returns true when this shot the projectile (1.7's throw,
     * or 26.x's with the legacy flight's launch velocity) and vanilla must skip its own body; false leaves the shot to
     * vanilla. Only a player's throw of a legacy type is changed.
     */
    public static boolean shoot(final Projectile projectile, final Entity source, final float xRot, final float yRot, final float yOffset,
                                final float pow, final float uncertainty) {
        if (!(source instanceof Player) || projectile.level().isClientSide()) {
            return false;
        }
        final LegacyProjectileSettings settings = Revival.settings().legacyCombat(projectile.level().getWorld().getName()).projectiles();
        if (!settings.enabled() || !thrownLegacy(projectile, settings)) {
            return false;
        }
        final ProjectileRules rules = settings.rules();
        final boolean flight = rules.legacyFlight() && projectile instanceof ThrowableProjectile;
        if (!rules.legacyThrow() && !flight) {
            return false;
        }
        Vec3 heading;
        Vec3 motion;
        if (rules.legacyThrow()) {
            final RandomSource random = projectile.getRandom();
            final float length = projectile instanceof ThrowableProjectile ? THROWABLE_LENGTH : ARROW_LENGTH;
            heading = heading(yRot, xRot, yOffset, length, pow, rules.throwSpread() * uncertainty, random.nextGaussian(), random.nextGaussian(),
                random.nextGaussian());
            motion = heading;
            projectile.setPos(originX(source.getX(), yRot, rules.throwSide()), originY(source.getY(), (float) rules.throwEyeHeight(), rules.throwDrop()),
                originZ(source.getZ(), yRot, rules.throwSide()));
        } else {
            // 26.x's shootFromRotation as Paper has it, including misc.disable-relative-projectile-velocity
            final float xd = -Mth.sin(yRot * Mth.DEG_TO_RAD) * Mth.cos(xRot * Mth.DEG_TO_RAD);
            final float yd = -Mth.sin((xRot + yOffset) * Mth.DEG_TO_RAD);
            final float zd = Mth.cos(yRot * Mth.DEG_TO_RAD) * Mth.cos(xRot * Mth.DEG_TO_RAD);
            heading = projectile.getMovementToShoot(xd, yd, zd, pow, uncertainty);
            Vec3 known = source.getKnownMovement();
            if (!known.isFinite()) {
                known = Vec3.ZERO;
            }
            motion = source.level().paperConfig().misc.disableRelativeProjectileVelocity ? heading
                : heading.add(known.x, source.onGround() ? 0.0 : known.y, known.z);
        }
        if (flight) {
            motion = launch(motion, legacyGravity(projectile.getGravity()), projectile.getAirDrag());
        }
        projectile.setDeltaMovement(motion);
        projectile.needsSync = true;
        final double horizontal = heading.horizontalDistance();
        projectile.setYRot((float) (Mth.atan2(heading.x, heading.z) * 57.2957763671875));
        projectile.setXRot((float) (Mth.atan2(heading.y, horizontal) * 57.2957763671875));
        projectile.yRotO = projectile.getYRot();
        projectile.xRotO = projectile.getXRot();
        return true;
    }

    /**
     * {@code ThrowableProjectile.tick}, in place of {@code applyGravity()}. Returns true when this applied the legacy
     * flight's gravity ({@code g / d}) and vanilla must skip its own.
     */
    public static boolean gravity(final ThrowableProjectile projectile) {
        if (!legacyFlight(projectile)) {
            return false;
        }
        final double gravity = projectile.getGravity();
        if (gravity != 0.0) {
            final double drag = projectile.isInWater() ? WATER_DRAG : projectile.getAirDrag();
            projectile.setDeltaMovement(projectile.getDeltaMovement().add(0.0, -legacyGravity(gravity) / drag, 0.0));
        }
        return true;
    }

    /** Whether this projectile flies 1.7's path: a legacy type thrown by a player, with {@code flight: legacy-1.7}. */
    static boolean legacyFlight(final Projectile projectile) {
        if (!(projectile.getOwner() instanceof Player)) {
            return false;
        }
        final LegacyProjectileSettings settings = Revival.settings().legacyCombat(projectile.level().getWorld().getName()).projectiles();
        return settings.enabled() && settings.rules().legacyFlight() && thrownLegacy(projectile, settings);
    }

    /** The legacy projectile types, splash potions included (they hit nothing with damage, so they aren't in the knock rules). */
    static boolean thrownLegacy(final Projectile projectile, final LegacyProjectileSettings settings) {
        if (projectile instanceof ThrownSplashPotion) {
            return settings.rules().splash().enabled();
        }
        return LegacyProjectiles.legacyType(projectile, settings);
    }

    // ------------------------------------------------------------------------------------------------------------------
    // 1.7.10 math
    // ------------------------------------------------------------------------------------------------------------------

    /** 1.7.10 {@code MathHelper.sin(float)}: the 65536-entry table, indexed with a float multiply. */
    static float sin(final float radians) {
        return SIN_TABLE[(int) (radians * 10430.378F) & 65535];
    }

    /** 1.7.10 {@code MathHelper.cos(float)}. */
    static float cos(final float radians) {
        return SIN_TABLE[(int) (radians * 10430.378F + 16384.0F) & 65535];
    }

    /**
     * The 1.7.10 launch motion: the constructor's look vector at {@code length} ({@code 0.4F} for EntityThrowable and
     * EntityFishHook, 1 for EntityArrow), then {@code setThrowableHeading(x, y, z, speed, inaccuracy)}: normalised with a
     * float square root ({@code MathHelper.sqrt_double}), plus {@code gaussian × spread} per axis
     * ({@code 0.007499999832361937D × inaccuracy} in 1.7), times the speed.
     */
    static Vec3 heading(final float yaw, final float pitch, final float pitchOffset, final float length, final double speed, final double spread,
                        final double gaussianX, final double gaussianY, final double gaussianZ) {
        final float yawRad = yaw / 180.0F * (float) Math.PI;
        final float pitchRad = pitch / 180.0F * (float) Math.PI;
        double x = -sin(yawRad) * cos(pitchRad) * length;
        double z = cos(yawRad) * cos(pitchRad) * length;
        double y = -sin((pitch + pitchOffset) / 180.0F * (float) Math.PI) * length;
        final float norm = (float) Math.sqrt(x * x + y * y + z * z);
        x /= norm;
        y /= norm;
        z /= norm;
        final double spreadF = (float) spread; // 1.7 multiplies by (double) 0.0075F
        x += gaussianX * spreadF;
        y += gaussianY * spreadF;
        z += gaussianZ * spreadF;
        final double speedF = (float) speed;
        return new Vec3(x * speedF, y * speedF, z * speedF);
    }

    /** 1.7.10 throw origin x: {@code posX - cos(yaw / 180 × π) × side}, the product in float as 1.7 wrote it. */
    static double originX(final double feetX, final float yaw, final double side) {
        return feetX - (double) (cos(yaw / 180.0F * (float) Math.PI) * (float) side);
    }

    /** 1.7.10 throw origin y: {@code posY + (double) getEyeHeight() - (double) 0.1F}. */
    static double originY(final double feetY, final float eyeHeight, final double drop) {
        return feetY + (double) eyeHeight - (double) (float) drop;
    }

    /** 1.7.10 throw origin z: {@code posZ - sin(yaw / 180 × π) × side}. */
    static double originZ(final double feetZ, final float yaw, final double side) {
        return feetZ - (double) (sin(yaw / 180.0F * (float) Math.PI) * (float) side);
    }

    /** 1.7 gravity constants were floats ({@code 0.03F}, {@code 0.05F}); 26.x's are the doubles 0.03 and 0.05. */
    static double legacyGravity(final double gravity) {
        return (float) gravity;
    }

    /**
     * The launch velocity that makes 26.x's thrown-item tick (gravity, then drag, then move) move 1.7's path from the
     * first tick: {@code (v0 + g ŷ) / d}, with {@code g / d} as the gravity per tick after it.
     */
    static Vec3 launch(final Vec3 motion, final double gravity, final double drag) {
        return new Vec3(motion.x / drag, (motion.y + gravity) / drag, motion.z / drag);
    }

    /**
     * The same for the rod hook's tick (gravity, then move, then drag 0.92): launch {@code v0 + 0.92 g ŷ} with
     * {@code 0.92 g} as the gravity per tick.
     */
    static Vec3 rodLaunch(final Vec3 motion, final double gravity) {
        return new Vec3(motion.x, motion.y + rodGravity(gravity), motion.z);
    }

    /** The rod's gravity per tick in 26.x's order for 1.7's path. */
    static double rodGravity(final double gravity) {
        return legacyGravity(gravity) * ROD_DRAG;
    }
}
