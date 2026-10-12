package gg.hcfrevival.revival.combat;

import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.ai.attributes.Attributes;
import net.minecraft.world.phys.Vec3;

/**
 * A player's server-side motion as a 1.7.10 server kept it. The 1.7 server stepped each player's motion with no
 * input (EntityLivingBase.onLivingUpdate and moveEntityWithHeading), so a player standing on the ground settles at
 * {@code y = -0.0784} and a standing hit always lifts to 0.3608, whatever order the victim's movement packets arrive
 * in. 26.x has no equivalent, so legacy knockback reads this instead of the player's delta movement.
 * <p>
 * Owned by the player's tick thread: stepped in {@code ServerPlayer.tick}, read and written by hits on that player.
 */
public final class MotionShadow {

    static final double DRAG = 0.98;
    static final double AIR_FRICTION = 0.91;
    static final double MIN_MOTION = 0.005;
    static final int MAX_LANDING_TICKS = 20;

    private double x;
    private double y;
    private double z;

    public Vec3 motion() {
        return new Vec3(this.x, this.y, this.z);
    }

    /** Replaces the motion, for the vanilla-1.7 motion model's knocks and for plugin {@code setVelocity}. */
    public void set(final Vec3 motion) {
        this.x = motion.x;
        this.y = motion.y;
        this.z = motion.z;
    }

    /** The attacker slowdown after a sprint or Knockback hit (EntityPlayer.attackTargetEntityWithCurrentItem). */
    public void scaleHorizontal(final double factor) {
        this.x *= factor;
        this.z *= factor;
    }

    /** One server tick for this player, using the onGround flag their client last sent. */
    public void tick(final ServerPlayer player) {
        final boolean onGround = player.onGround();
        // The block lookup only matters while there is horizontal motion to slow down
        final double blockFriction = onGround && (this.x != 0.0 || this.z != 0.0)
            ? player.level().getBlockState(player.getBlockPosBelowThatAffectsMyMovement()).getBlock().getFriction()
            : 0.6;
        this.step(onGround, player.getAttributeValue(Attributes.GRAVITY), blockFriction);
    }

    /**
     * One tick of 1.7.10 player motion with no input: tiny motion snaps to zero, the floor stops downward motion,
     * then gravity, drag and friction apply.
     * shortcut: water, lava, ladders, flight and walls use the land and air rules; add them if a profile needs them.
     */
    void step(final boolean onGround, final double gravity, final double blockFriction) {
        if (Math.abs(this.x) < MIN_MOTION) {
            this.x = 0.0;
        }
        if (Math.abs(this.y) < MIN_MOTION) {
            this.y = 0.0;
        }
        if (Math.abs(this.z) < MIN_MOTION) {
            this.z = 0.0;
        }
        if (onGround && this.y < 0.0) {
            this.y = 0.0; // moveEntity collides with the floor
        }
        this.y = (this.y - gravity) * DRAG;
        final double friction = onGround ? (float) blockFriction * 0.91F : AIR_FRICTION;
        this.x *= friction;
        this.z *= friction;
    }

    /** Motion of a player who is already standing: the 1.7.10 steady state for this gravity. */
    static Vec3 settled(final Vec3 motion, final double gravity) {
        return new Vec3(motion.x, -gravity * DRAG, motion.z);
    }

    /**
     * Ticks until a falling player at {@code height} above the ground with vertical speed {@code velocity} lands, or
     * -1 if not within {@value #MAX_LANDING_TICKS}. Ported from core's ClientGround.landingTicks.
     */
    static int landingTicks(double velocity, final double height, final double gravity) {
        if (height <= 0.0) {
            return 0;
        }
        double remaining = height;
        for (int tick = 1; tick <= MAX_LANDING_TICKS; tick++) {
            velocity -= gravity;
            remaining += velocity;
            if (remaining <= 0.0) {
                return tick;
            }
            velocity *= DRAG;
        }
        return -1;
    }

    /**
     * Whether a player in the air on the server has already landed on their own screen: within {@code maxDistance}
     * of the ground, and their ping covers the ticks it takes to fall there. Ported from core's
     * ClientGround.landed.
     */
    static boolean landedForClient(final long pingMillis, final double velocity, final double distanceToGround, final double gravity, final double maxDistance) {
        if (pingMillis <= 0 || maxDistance <= 0.0 || distanceToGround <= 0.0 || distanceToGround > maxDistance) {
            return false;
        }
        final int ticks = landingTicks(velocity, distanceToGround, gravity);
        return ticks >= 0 && pingMillis >= ticks * 50L;
    }
}
