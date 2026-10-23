package gg.hcfrevival.revival.auth.sim;

import net.minecraft.core.Holder;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.effect.MobEffect;
import net.minecraft.world.effect.MobEffects;
import net.minecraft.world.entity.Pose;
import net.minecraft.world.entity.player.Input;
import net.minecraft.world.item.ItemStack;

/**
 * Everything one player's vanilla 26.3 client holds that its movement reads or writes (report 01 §11.2): the
 * {@code LocalPlayer} as a plain struct, advanced by {@link MovementSim}.
 * <p>
 * Fields are public so the netcode and reconciler layers can seed, inspect and fork the state; they hold the CLIENT's
 * view (its copy of attributes, effects, abilities, items, shared flags and pose), which changes only through
 * {@link MovementSim}'s actions and ticks. Names follow vanilla's fields so a reader can map them back:
 * {@code (vx, vy, vz)} is {@code deltaMovement}, {@code (xo, yo, zo)} is {@code oldPosition()}, {@code keys} is
 * {@code input.keyPresses}.
 * <p>
 * Rotations are the raw, unwrapped client floats (Paper wraps them, which moves the {@code Mth.sin} table index).
 * Owned by the player's tick thread.
 */
public final class SimPlayer {

    // ----------------------------------------------------------------------------------------- kinematics

    public double x;
    public double y;
    public double z;
    /** Position at the start of the tick ({@code Entity.setOldPosAndRot} in commonTick). */
    public double xo;
    public double yo;
    public double zo;
    /** {@code deltaMovement}. */
    public double vx;
    public double vy;
    public double vz;
    public float yRot;
    public float xRot;
    public float yRotO;
    public float xRotO;
    /** {@code getBoundingBox()}, kept in step with the position and dimensions by {@link #setPos} and {@link #refreshDimensions}. */
    public final SimBox bb = new SimBox();
    /** {@code blockPosition()}. */
    public int blockX;
    public int blockY;
    public int blockZ;

    // ----------------------------------------------------------------------------------------- pose and dimensions

    /** {@code DATA_POSE} as the client holds it (its own updatePlayerPose, or the server's entity-data echo). */
    public Pose pose = Pose.STANDING;
    /** {@code dimensions}: refreshed on a pose change and by a {@code SCALE} refresh, not continuously. */
    public float width = 0.6F;
    public float height = 1.8F;
    public float eyeHeight = 1.62F;
    /** {@code Entity.firstTick}: true until the end of the first baseTick (lava and splash rules). */
    public boolean firstTick = true;
    /** A {@code SCALE} change waits for {@code refreshDirtyAttributes} at the end of the next tick (LivingEntity.java:2868). */
    public boolean scaleDirty;

    // ----------------------------------------------------------------------------------------- contact

    public boolean onGround;
    public boolean horizontalCollision;
    public boolean verticalCollision;
    public boolean verticalCollisionBelow;
    /** The LocalPlayer angle test (LocalPlayer.java:1134-1149), not Entity's constant false. */
    public boolean minorHorizontalCollision;
    /** {@code mainSupportingBlockPos}: present when {@code hasSupportingBlock}. */
    public boolean hasSupportingBlock;
    public int supportX;
    public int supportY;
    public int supportZ;
    public boolean onGroundNoBlocks;
    /** {@code fallDistance} (a double in 26.3). */
    public double fallDistance;
    /** {@code stuckSpeedMultiplier} (cobweb, berry bush, powder snow), consumed by the next move. */
    public double stuckX;
    public double stuckY;
    public double stuckZ;

    // ----------------------------------------------------------------------------------------- fluids

    public boolean wasTouchingWater;
    public boolean wasEyeInWater;
    /** LocalPlayer's {@code wasUnderwater} (Player.updateIsUnderwater at the start of Player.tick). */
    public boolean wasUnderwater;
    public boolean isInPowderSnow;
    public boolean wasInPowderSnow;
    /** EntityFluidInteraction state; see {@link SimFluids}. */
    final SimFluids.State fluids = new SimFluids.State();

    // ----------------------------------------------------------------------------------------- input

    /** {@code input.keyPresses} of the last tick: the window's {@link Input}, or the previous one if none was sent. */
    public Input keys = Input.EMPTY;
    /** {@code input.getMoveVector()} of the last tick. */
    public float moveX;
    public float moveY;
    public float xxa;
    public float zza;
    public boolean jumping;

    // ----------------------------------------------------------------------------------------- client timers

    public int noJumpDelay;
    public int jumpTriggerTime;
    public int sprintTriggerTime;
    /** Set to 1 by a move that finds a step to auto-jump; the next tick's aiStep counts it down (LocalPlayer.java:804-809). */
    public int autoJumpTime;
    /** LocalPlayer's {@code crouching}, computed in aiStep from the previous tick's shift key. */
    public boolean crouching;
    public boolean wasFallFlying;
    /** The client option {@code sprintWindow} (0..10, default 7): only the double-tap sprint path reads it. */
    public int sprintWindow = 7;
    /**
     * LocalPlayer's {@code autoJumpEnabled}: true in a new LocalPlayer (join, respawn, dimension change) until the end
     * of its first tick, then the option ({@code sendPosition} copies it after every tick).
     */
    public boolean autoJumpEnabled = true;
    /**
     * The client's {@code autoJump} option as the server believes it. The server cannot read it, so the default assumes
     * it on: {@link MovementSim} then predicts every auto-jump like the client, and turns this off for good the first
     * time a predicted auto-jump does not show in the client's next Input (see MovementSim's aiStep).
     */
    public boolean autoJumpOption = true;
    public int autoSpinAttackTicks;

    // ----------------------------------------------------------------------------------------- shared flags

    /** Shared flag 3 and the {@code sprinting} modifier on movement speed; change it only through MovementSim. */
    public boolean sprinting;
    /** Shared flag 4. */
    public boolean swimming;
    /** Shared flag 7. */
    public boolean fallFlying;
    /** {@code DATA_NO_GRAVITY}. */
    public boolean noGravity;
    /** {@code noPhysics}: set from spectator in Player.tick. */
    public boolean noPhysics;

    // ----------------------------------------------------------------------------------------- abilities, mode, food

    public boolean flying;
    public boolean mayfly;
    public boolean instabuild;
    public float flyingSpeed = 0.05F;
    /** {@code Player.isSpectator()}: the tab-list game mode on the client. */
    public boolean spectator;
    /** {@code minecraft.gameMode.isSpectator()}: the local game mode, which forces flight. */
    public boolean localSpectator;
    public int foodLevel = 20;

    // ----------------------------------------------------------------------------------------- items

    /** The client's copies; for sword blocking, the stack as {@code LegacySwordBlocking.toClient} shows it. */
    public ItemStack mainHand = ItemStack.EMPTY;
    public ItemStack offHand = ItemStack.EMPTY;
    public ItemStack feet = ItemStack.EMPTY;
    /** {@code LivingEntity.canGlideUsing} holds for some equipment slot (glider component, matching slot, not breaking). */
    public boolean gliderAvailable;
    /** LocalPlayer's {@code startedUsingItem}. */
    public boolean usingItem;
    public InteractionHand usingHand = InteractionHand.MAIN_HAND;
    public ItemStack useItem = ItemStack.EMPTY;
    public int useItemRemaining;
    public int attackStrengthTicker;
    public int itemSwapTicker;
    public ItemStack lastItemInMainHand = ItemStack.EMPTY;
    /**
     * The ticker may be a tick off the client's: its last reset was an item change, whose tick the window can't pin (a
     * number key's SetCarriedItem goes out a window late, design H9), or the port hasn't reset it at all yet. An attack
     * or a dig's stop resets it at a known tick.
     */
    public boolean strengthUncertain = true;

    // ----------------------------------------------------------------------------------------- attributes and effects

    public final SimAttributes attributes = new SimAttributes();
    /** Amplifier of each movement effect the client holds, -1 when absent (the client never expires them itself). */
    public int jumpBoost = -1;
    public int slowFalling = -1;
    public int levitation = -1;
    public int dolphinsGrace = -1;
    public int blindness = -1;
    public int weaving = -1;

    // ----------------------------------------------------------------------------------------- states the port exempts

    public boolean passenger;
    public boolean sleeping;
    public boolean dead;
    /** {@code isControlledCamera()}: false while spectating through another entity. */
    public boolean cameraIsSelf = true;
    /**
     * Set by the caller while something outside the player's tick may move it in the client's level (pistons, shulker
     * boxes and shulkers, potent sulfur geysers, pushing entities): {@link MovementSim#tick} then exempts the tick.
     */
    public boolean externalMover;

    // ----------------------------------------------------------------------------------------- movementThisTick

    /** Entity.movementThisTick (from, to, optional axis-dependent original movement), at most 100 (Entity.java:963-972). */
    final Movements movements = new Movements();

    /** {@code Entity.setPos}: position, block position and the box from the current dimensions. */
    public void setPos(final double x, final double y, final double z) {
        this.x = x;
        this.y = y;
        this.z = z;
        this.blockX = (int) Math.floor(x);
        this.blockY = (int) Math.floor(y);
        this.blockZ = (int) Math.floor(z);
        this.makeBoundingBox(this.bb, x, y, z, this.width, this.height);
    }

    /** {@code EntityDimensions.makeBoundingBox(x, y, z)} (EntityDimensions.java:18-22). */
    void makeBoundingBox(final SimBox out, final double px, final double py, final double pz, final float w, final float h) {
        final float half = w / 2.0F;
        out.set(px - half, py, pz - half, px + half, py + h, pz + half);
    }

    /** Entity.refreshDimensions for a player (Entity.java:3534-3550): dimensions of the pose, then reapplyPosition. */
    public void refreshDimensions() {
        final float scale = this.scale();
        this.width = Dimensions.width(this.pose, scale);
        this.height = Dimensions.height(this.pose, scale);
        this.eyeHeight = Dimensions.eyeHeight(this.pose, scale);
        this.setPos(this.x, this.y, this.z);
    }

    /** {@code LivingEntity.getScale()}: the SCALE attribute as a float. */
    float scale() {
        return (float) this.attributes.scale.value();
    }

    /** Sets the pose the way {@code SynchedEntityData} does: dimensions refresh only on a change. */
    public void setPose(final Pose pose) {
        if (this.pose != pose) {
            this.pose = pose;
            this.refreshDimensions();
        }
    }

    // ----------------------------------------------------------------------------------------- effects

    /** Records an effect from {@code ClientboundUpdateMobEffectPacket}; effects movement does not read are ignored. */
    public void addEffect(final Holder<MobEffect> effect, final int amplifier) {
        this.setEffect(effect, amplifier);
    }

    /** {@code ClientboundRemoveMobEffectPacket}. */
    public void removeEffect(final Holder<MobEffect> effect) {
        this.setEffect(effect, -1);
    }

    private void setEffect(final Holder<MobEffect> effect, final int amplifier) {
        if (effect == MobEffects.JUMP_BOOST) {
            this.jumpBoost = amplifier;
        } else if (effect == MobEffects.SLOW_FALLING) {
            this.slowFalling = amplifier;
        } else if (effect == MobEffects.LEVITATION) {
            this.levitation = amplifier;
        } else if (effect == MobEffects.DOLPHINS_GRACE) {
            this.dolphinsGrace = amplifier;
        } else if (effect == MobEffects.BLINDNESS) {
            this.blindness = amplifier;
        } else if (effect == MobEffects.WEAVING) {
            this.weaving = amplifier;
        }
    }

    // ----------------------------------------------------------------------------------------- vanilla predicates

    /** {@code Entity.isInWater()} = {@code wasTouchingWater}. */
    public boolean isInWater() {
        return this.wasTouchingWater;
    }

    /** {@code Entity.isInLava()} (Entity.java:1803-1805). */
    public boolean isInLava() {
        return !this.firstTick && this.fluids.isInLava();
    }

    /** {@code LocalPlayer.isUnderWater()} = {@code wasUnderwater}. */
    public boolean isUnderWater() {
        return this.wasUnderwater;
    }

    /** {@code Player.isSwimming()} (Player.java:1649-1651). */
    public boolean isSwimming() {
        return !this.flying && !this.spectator && this.swimming;
    }

    /** {@code LocalPlayer.isShiftKeyDown()}: the current keys. */
    public boolean isShiftKeyDown() {
        return this.keys.shift();
    }

    /** {@code Entity.getEyeY()}. */
    public double eyeY() {
        return this.y + this.eyeHeight;
    }

    /** Copies every field (for forks of a simulation, e.g. the reconciler's belief of the client). */
    public void copyFrom(final SimPlayer o) {
        this.x = o.x;
        this.y = o.y;
        this.z = o.z;
        this.xo = o.xo;
        this.yo = o.yo;
        this.zo = o.zo;
        this.vx = o.vx;
        this.vy = o.vy;
        this.vz = o.vz;
        this.yRot = o.yRot;
        this.xRot = o.xRot;
        this.yRotO = o.yRotO;
        this.xRotO = o.xRotO;
        this.bb.set(o.bb);
        this.blockX = o.blockX;
        this.blockY = o.blockY;
        this.blockZ = o.blockZ;
        this.pose = o.pose;
        this.width = o.width;
        this.height = o.height;
        this.eyeHeight = o.eyeHeight;
        this.firstTick = o.firstTick;
        this.scaleDirty = o.scaleDirty;
        this.onGround = o.onGround;
        this.horizontalCollision = o.horizontalCollision;
        this.verticalCollision = o.verticalCollision;
        this.verticalCollisionBelow = o.verticalCollisionBelow;
        this.minorHorizontalCollision = o.minorHorizontalCollision;
        this.hasSupportingBlock = o.hasSupportingBlock;
        this.supportX = o.supportX;
        this.supportY = o.supportY;
        this.supportZ = o.supportZ;
        this.onGroundNoBlocks = o.onGroundNoBlocks;
        this.fallDistance = o.fallDistance;
        this.stuckX = o.stuckX;
        this.stuckY = o.stuckY;
        this.stuckZ = o.stuckZ;
        this.wasTouchingWater = o.wasTouchingWater;
        this.wasEyeInWater = o.wasEyeInWater;
        this.wasUnderwater = o.wasUnderwater;
        this.isInPowderSnow = o.isInPowderSnow;
        this.wasInPowderSnow = o.wasInPowderSnow;
        this.fluids.copyFrom(o.fluids);
        this.keys = o.keys;
        this.moveX = o.moveX;
        this.moveY = o.moveY;
        this.xxa = o.xxa;
        this.zza = o.zza;
        this.jumping = o.jumping;
        this.noJumpDelay = o.noJumpDelay;
        this.jumpTriggerTime = o.jumpTriggerTime;
        this.sprintTriggerTime = o.sprintTriggerTime;
        this.autoJumpTime = o.autoJumpTime;
        this.crouching = o.crouching;
        this.wasFallFlying = o.wasFallFlying;
        this.sprintWindow = o.sprintWindow;
        this.autoJumpEnabled = o.autoJumpEnabled;
        this.autoJumpOption = o.autoJumpOption;
        this.autoSpinAttackTicks = o.autoSpinAttackTicks;
        this.sprinting = o.sprinting;
        this.swimming = o.swimming;
        this.fallFlying = o.fallFlying;
        this.noGravity = o.noGravity;
        this.noPhysics = o.noPhysics;
        this.flying = o.flying;
        this.mayfly = o.mayfly;
        this.instabuild = o.instabuild;
        this.flyingSpeed = o.flyingSpeed;
        this.spectator = o.spectator;
        this.localSpectator = o.localSpectator;
        this.foodLevel = o.foodLevel;
        this.mainHand = o.mainHand;
        this.offHand = o.offHand;
        this.feet = o.feet;
        this.gliderAvailable = o.gliderAvailable;
        this.usingItem = o.usingItem;
        this.usingHand = o.usingHand;
        this.useItem = o.useItem;
        this.useItemRemaining = o.useItemRemaining;
        this.attackStrengthTicker = o.attackStrengthTicker;
        this.itemSwapTicker = o.itemSwapTicker;
        this.lastItemInMainHand = o.lastItemInMainHand;
        this.strengthUncertain = o.strengthUncertain;
        this.attributes.copyFrom(o.attributes);
        this.jumpBoost = o.jumpBoost;
        this.slowFalling = o.slowFalling;
        this.levitation = o.levitation;
        this.dolphinsGrace = o.dolphinsGrace;
        this.blindness = o.blindness;
        this.weaving = o.weaving;
        this.passenger = o.passenger;
        this.sleeping = o.sleeping;
        this.dead = o.dead;
        this.cameraIsSelf = o.cameraIsSelf;
        this.externalMover = o.externalMover;
        this.movements.copyFrom(o.movements);
    }

    /** Player pose dimensions (Avatar.POSES, LivingEntity.SLEEPING_DIMENSIONS) scaled like EntityDimensions.scale. */
    static final class Dimensions {

        private Dimensions() {
        }

        static float width(final Pose pose, final float scale) {
            if (pose == Pose.SLEEPING || pose == Pose.DYING) {
                return 0.2F; // fixed: never scaled
            }
            return scale != 1.0F ? 0.6F * scale : 0.6F;
        }

        static float height(final Pose pose, final float scale) {
            if (pose == Pose.SLEEPING || pose == Pose.DYING) {
                return 0.2F;
            }
            final float h = switch (pose) {
                case CROUCHING -> 1.5F;
                case SWIMMING, FALL_FLYING, SPIN_ATTACK -> 0.6F;
                default -> 1.8F;
            };
            return scale != 1.0F ? h * scale : h;
        }

        static float eyeHeight(final Pose pose, final float scale) {
            if (pose == Pose.SLEEPING) {
                return 0.2F;
            }
            if (pose == Pose.DYING) {
                return 1.62F;
            }
            final float e = switch (pose) {
                case CROUCHING -> 1.27F;
                case SWIMMING, FALL_FLYING, SPIN_ATTACK -> 0.4F;
                default -> 1.62F;
            };
            return scale != 1.0F ? e * scale : e;
        }
    }

    /** The movement list, as flat arrays. */
    static final class Movements {
        static final int MAX = 100;
        final double[] from = new double[MAX * 3];
        final double[] to = new double[MAX * 3];
        final double[] axis = new double[MAX * 3];
        final boolean[] hasAxis = new boolean[MAX];
        int size;

        void clear() {
            this.size = 0;
        }

        /** Entity.addMovementThisTick: at 100, the first two merge into one with no axis movement. */
        void add(final double fx, final double fy, final double fz, final double tx, final double ty, final double tz, final boolean hasAxis,
                 final double ax, final double ay, final double az) {
            if (this.size >= MAX) {
                // combined = Movement(first.from, second.to) replaces the first two
                this.to[0] = this.to[3];
                this.to[1] = this.to[4];
                this.to[2] = this.to[5];
                this.hasAxis[0] = false;
                System.arraycopy(this.from, 6, this.from, 3, (this.size - 2) * 3);
                System.arraycopy(this.to, 6, this.to, 3, (this.size - 2) * 3);
                System.arraycopy(this.axis, 6, this.axis, 3, (this.size - 2) * 3);
                System.arraycopy(this.hasAxis, 2, this.hasAxis, 1, this.size - 2);
                this.size--;
            }
            final int i = this.size;
            this.from[i * 3] = fx;
            this.from[i * 3 + 1] = fy;
            this.from[i * 3 + 2] = fz;
            this.to[i * 3] = tx;
            this.to[i * 3 + 1] = ty;
            this.to[i * 3 + 2] = tz;
            this.hasAxis[i] = hasAxis;
            this.axis[i * 3] = ax;
            this.axis[i * 3 + 1] = ay;
            this.axis[i * 3 + 2] = az;
            this.size++;
        }

        void copyFrom(final Movements o) {
            System.arraycopy(o.from, 0, this.from, 0, o.size * 3);
            System.arraycopy(o.to, 0, this.to, 0, o.size * 3);
            System.arraycopy(o.axis, 0, this.axis, 0, o.size * 3);
            System.arraycopy(o.hasAxis, 0, this.hasAxis, 0, o.size);
            this.size = o.size;
        }
    }
}
