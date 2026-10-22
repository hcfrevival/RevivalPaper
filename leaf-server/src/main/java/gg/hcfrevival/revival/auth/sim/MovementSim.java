package gg.hcfrevival.revival.auth.sim;

import java.util.List;
import java.util.Set;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.Holder;
import net.minecraft.core.component.DataComponents;
import net.minecraft.tags.FluidTags;
import net.minecraft.util.Mth;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.effect.MobEffect;
import net.minecraft.world.entity.Pose;
import net.minecraft.world.entity.PositionMoveRotation;
import net.minecraft.world.entity.Relative;
import net.minecraft.world.entity.ai.attributes.Attribute;
import net.minecraft.world.entity.ai.attributes.AttributeModifier;
import net.minecraft.world.entity.ai.attributes.Attributes;
import net.minecraft.world.entity.player.Input;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.item.component.UseEffects;
import net.minecraft.world.level.GameType;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.Vec3;

/**
 * The vanilla 26.3 local-player movement tick, ported line by line from the decompiled client so the server can run
 * exactly what a player's client runs (report 01). {@link #tick} is one {@code Minecraft.tick()} of the local player:
 * {@code ClientLevel.tickNonPassenger} → {@code Entity.commonTick} + {@code LocalPlayer.tick} (Player.tick,
 * LivingEntity.tick/baseTick, LocalPlayer.aiStep, travel, LocalPlayer.move with auto-jump, applyEffectsFromBlocks,
 * updatePlayerPose); the other methods are the client's own actions, the server packets it applies between ticks, called
 * by the netcode layer in client-stream order (report 02), and {@link #fireworkBoost} for the rockets that tick after it.
 * <p>
 * Exactness: same float/double types and operation order as vanilla, the {@code Mth} sine table, raw yaw floats, and
 * every {@code Vec3} operation kept as written (including {@code add(0, y, 0)}, which turns a {@code -0.0} into
 * {@code 0.0}). Every port names the vanilla method and 26.3 line it mirrors, so a Minecraft update can be re-diffed.
 * <p>
 * Not thread-safe: holds scratch state. Use one instance per thread (all players ticked on the main thread can share
 * one).
 */
public final class MovementSim {

    final SimCollision collision = new SimCollision();
    private final SimFluids fluids = new SimFluids(this.collision.level);
    final SimBlocks blocks = new SimBlocks(this.collision);
    private final double[] out = new double[3];
    private final SimBox box = new SimBox();
    private final BlockPos.MutableBlockPos pos = new BlockPos.MutableBlockPos();
    private final BlockPos.MutableBlockPos support = new BlockPos.MutableBlockPos();
    // updateAutoJump's two segments (left begin, left end, right begin, right end) and the obstacle box it found
    private final double[] segments = new double[12];
    private final double[] obstacle = new double[6];
    // the travel branch of the tick so far, recorded before its move (see Travel)
    private final Travel travel = new Travel();
    // Entity.collide's result in the last move, for tools (lastMove)
    private double lastMoveX;
    private double lastMoveY;
    private double lastMoveZ;
    // velocityAfterMove: when set, the next move copies the player here and moves nothing
    private SimPlayer probe;
    final Travel probeTravel = new Travel();
    private MoveVelocity moveVelocity;

    /**
     * What the travel branch of a tick worked out before its move and reads after it: the branch, Player.travel's flight
     * rule, travelFallFlying's climbable exit, and the branch's own pre-move values (travelInAir's friction and the column
     * of the block it came from, travelInWater's slow-down). Recorded right before the move, so velocityAfterMove can run
     * the after-move half by itself.
     */
    static final class Travel {
        static final int AIR = 0;
        static final int WATER = 1;
        static final int LAVA = 2;
        static final int GLIDE = 3;

        int kind;
        /** Player.travel while flying: afterwards y is the y before the branch × 0.6 ({@link #afterFlyingTravel}). */
        boolean flying;
        /** travelFallFlying on a climbable: travelInAir, then stopFallFlying. */
        boolean stopsGliding;
        float blockFriction;
        int belowX;
        int belowZ;
        float slowDown;

        void copyFrom(final Travel o) {
            this.kind = o.kind;
            this.flying = o.flying;
            this.stopsGliding = o.stopsGliding;
            this.blockFriction = o.blockFriction;
            this.belowX = o.belowX;
            this.belowZ = o.belowZ;
            this.slowDown = o.slowDown;
        }

        boolean same(final Travel o) {
            return this.kind == o.kind && this.flying == o.flying && this.stopsGliding == o.stopsGliding
                && Float.floatToRawIntBits(this.blockFriction) == Float.floatToRawIntBits(o.blockFriction) && this.belowX == o.belowX
                && this.belowZ == o.belowZ && Float.floatToRawIntBits(this.slowDown) == Float.floatToRawIntBits(o.slowDown);
        }
    }

    // ================================================================================================ the tick

    /**
     * Runs one client tick. Call it on {@code ServerboundClientTickEndPacket}, after the window's pre-physics actions
     * (attacks, item use) and the server changes the client applied before this tick. Returns {@link ExemptReason#NONE}
     * when simulated, otherwise why not (the state is left untouched).
     */
    public ExemptReason tick(final SimPlayer p, final SimTickInput in, final SimWorld world) {
        final ExemptReason exempt = exemptReason(p);
        if (exempt != ExemptReason.NONE) {
            return exempt;
        }
        this.collision.world(world);
        // the rotation the tick uses (mouse turns happen between ticks, Minecraft.java:1236)
        p.yRot = in.yRot;
        p.xRot = in.xRot;
        this.startTick(p, world);
        this.localAiStep(p, in, world);
        if (p.scaleDirty) {
            // refreshDirtyAttributes → onAttributeUpdated(SCALE) → refreshDimensions (LivingEntity.java:1134-1158)
            p.scaleDirty = false;
            p.refreshDimensions();
        }

        // Player.tick tail (Player.java:261-284)
        final double nx = Mth.clamp(p.x, -2.9999999E7, 2.9999999E7);
        final double nz = Mth.clamp(p.z, -2.9999999E7, 2.9999999E7);
        if (nx != p.x || nz != p.z) {
            p.setPos(nx, p.y, nz);
        }
        p.attackStrengthTicker++;
        p.itemSwapTicker++;
        // vanilla: if (!matches(last, main)) { if (!isSameItem(last, main)) reset; last = main.copy(); }. Only the item
        // type of the copy is ever read (isSameItem), so comparing types is the same test without the per-tick
        // component-map equality
        if (p.lastItemInMainHand.getItem() != p.mainHand.getItem()) {
            p.attackStrengthTicker = 0;
            p.itemSwapTicker = 0;
            p.lastItemInMainHand = p.mainHand.copy();
        }
        this.updatePlayerPose(p);
        // LocalPlayer.sendPosition (LocalPlayer.java:330), after the tick: the option is read from here on
        p.autoJumpEnabled = p.autoJumpOption;
        return ExemptReason.NONE;
    }

    /**
     * The tick from {@code Entity.commonTick} up to {@code LocalPlayer.aiStep}: the old position, Player.tick's head,
     * baseTick's fluid update at the tick's start and LivingEntity.tick's item countdown. It reads no keys, and the
     * velocity only to scale a current's push into it ({@link #velocityAfterMove} runs it without knowing either).
     */
    void startTick(final SimPlayer p, final SimWorld world) {
        // Entity.commonTick (Entity.java:527-538): setOldPosAndRot
        p.xo = p.x;
        p.yo = p.y;
        p.zo = p.z;
        p.yRotO = p.yRot;
        p.xRotO = p.xRot;

        // Player.tick (Player.java:231-285)
        p.noPhysics = p.spectator;
        if (p.spectator || p.passenger) {
            p.onGround = false;
            this.checkSupportingBlock(p, false, false, 0.0, 0.0);
        }
        // Player.updateIsUnderwater via LocalPlayer (LocalPlayer.java:1212-1229, Player.java:304-307)
        p.wasUnderwater = p.fluids.isEyeInWater();

        // LivingEntity.tick → LivingEntity.baseTick → Entity.baseTick (Entity.java:540-594)
        p.wasInPowderSnow = p.isInPowderSnow;
        p.isInPowderSnow = false;
        p.wasEyeInWater = p.fluids.isEyeInWater();
        this.fluids.updateFluidInteraction(p, world);
        this.updateSwimming(p, world);
        if (p.isInLava()) {
            p.fallDistance *= 0.5;
        }
        p.firstTick = false;

        // LivingEntity.tick (LivingEntity.java:2758-2873)
        this.updatingUsingItem(p);
    }

    /** States the port leaves to vanilla handling (authoritative.md §5). */
    public static ExemptReason exemptReason(final SimPlayer p) {
        if (p.passenger) {
            return ExemptReason.PASSENGER;
        } else if (p.sleeping) {
            return ExemptReason.SLEEPING;
        } else if (p.dead) {
            return ExemptReason.DEAD;
        } else if (!p.cameraIsSelf) {
            return ExemptReason.SPECTATOR_CAMERA;
        } else if (p.autoSpinAttackTicks > 0) {
            return ExemptReason.RIPTIDE;
        } else if (p.externalMover) {
            return ExemptReason.EXTERNAL_MOVER;
        }
        return ExemptReason.NONE;
    }

    /** Player.updateSwimming / Entity.updateSwimming (Player.java:1400-1406, Entity.java:1689-1697). */
    private void updateSwimming(final SimPlayer p, final SimWorld world) {
        if (p.flying) {
            p.swimming = false;
        } else if (p.isSwimming()) {
            p.swimming = p.sprinting && p.isInWater() && !p.passenger;
        } else {
            p.swimming = p.sprinting && p.isUnderWater() && !p.passenger && world.fluidState(p.blockX, p.blockY, p.blockZ).is(FluidTags.WATER);
        }
    }

    /** LivingEntity.updatingUsingItem (LivingEntity.java:3437-3446): the client counts down and never completes. */
    private void updatingUsingItem(final SimPlayer p) {
        if (p.usingItem) {
            final ItemStack inHand = hand(p, p.usingHand);
            if (ItemStack.isSameItem(inHand, p.useItem)) {
                p.useItem = inHand;
                p.useItemRemaining--;
            } else {
                this.stopUsingItem(p);
            }
        }
    }

    // ------------------------------------------------------------------------------------------------ LocalPlayer.aiStep

    /** LocalPlayer.aiStep (LocalPlayer.java:782-935). */
    void localAiStep(final SimPlayer p, final SimTickInput in, final SimWorld world) {
        if (p.sprintTriggerTime > 0) {
            p.sprintTriggerTime--;
        }
        // the previous tick's input (the input is ticked below)
        final boolean wasJumping = p.keys.jump();
        final boolean wasShiftKeyDown = p.keys.shift();
        final boolean hadForwardImpulse = p.moveY > 1.0E-5F;
        p.crouching = !p.flying && !p.isSwimming() && !p.passenger && this.canPlayerFitWithinBlocksAndEntitiesWhen(p, Pose.CROUCHING)
            && (p.isShiftKeyDown() || !p.sleeping && !this.canPlayerFitWithinBlocksAndEntitiesWhen(p, Pose.STANDING));
        // input.tick(): KeyboardInput.tick (KeyboardInput.java:22-36)
        p.keys = in.keys;
        final float forwardImpulse = impulse(p.keys.forward(), p.keys.backward());
        final float leftImpulse = impulse(p.keys.left(), p.keys.right());
        // Vec2(left, forward).normalized() (Vec2.java:59-62)
        final float dist = Mth.sqrt(leftImpulse * leftImpulse + forwardImpulse * forwardImpulse);
        if (dist < 1.0E-4F) {
            p.moveX = 0.0F;
            p.moveY = 0.0F;
        } else {
            p.moveX = leftImpulse / dist;
            p.moveY = forwardImpulse / dist;
        }
        // the auto-jump the last move predicted (LocalPlayer.java:804-809). makeJump sets the jump key, and the client
        // sends its Input after the tick (Minecraft.tick → sendChanges), so a client that auto-jumped sent the jump bit
        boolean wasAutoJump = false;
        if (p.autoJumpTime > 0) {
            p.autoJumpTime--;
            if (p.keys.jump()) {
                wasAutoJump = true;
            } else {
                // shortcut: the client would have sent the jump it made, so its auto-jump option is off; learn that
                // instead of forcing a jump it did not make (only wasAutoJump read the belief so far)
                p.autoJumpOption = false;
                p.autoJumpEnabled = false;
            }
        }
        if (!p.noPhysics && !p.passenger) {
            this.moveTowardsClosestSpace(p, p.x - p.width * 0.35, p.z + p.width * 0.35);
            this.moveTowardsClosestSpace(p, p.x - p.width * 0.35, p.z - p.width * 0.35);
            this.moveTowardsClosestSpace(p, p.x + p.width * 0.35, p.z - p.width * 0.35);
            this.moveTowardsClosestSpace(p, p.x + p.width * 0.35, p.z + p.width * 0.35);
        }
        if (wasShiftKeyDown || isSlowDueToUsingItem(p) && !p.passenger || p.keys.backward()) {
            p.sprintTriggerTime = 0;
        }
        if (this.canStartSprinting(p)) {
            if (!hadForwardImpulse) {
                if (p.sprintTriggerTime > 0) {
                    setSprinting(p, true);
                } else {
                    p.sprintTriggerTime = p.sprintWindow;
                }
            }
            if (p.keys.sprint()) {
                setSprinting(p, true);
            }
        }
        if (p.sprinting) {
            if (p.isSwimming()) {
                if (!isSprintingPossible(p, true) || !p.isInWater() || !hasForwardImpulse(p) && !p.onGround && !p.keys.shift()) {
                    setSprinting(p, false);
                }
            } else if (!isSprintingPossible(p, p.flying) || !hasForwardImpulse(p) || p.horizontalCollision && !p.minorHorizontalCollision) {
                setSprinting(p, false);
            }
        }
        boolean justToggledCreativeFlight = false;
        if (p.mayfly) {
            if (p.localSpectator) {
                if (!p.flying) {
                    p.flying = true;
                    justToggledCreativeFlight = true;
                }
            } else if (!wasJumping && p.keys.jump() && !wasAutoJump) {
                if (p.jumpTriggerTime == 0) {
                    p.jumpTriggerTime = 7;
                } else if (!p.isSwimming()) {
                    p.flying = !p.flying;
                    if (p.flying && p.onGround) {
                        this.jumpFromGround(p, world);
                    }
                    justToggledCreativeFlight = true;
                    p.jumpTriggerTime = 0;
                }
            }
        }
        if (p.keys.jump() && !justToggledCreativeFlight && !wasJumping && !this.blocks.onClimbable(p, world)) {
            // Player.tryToStartFallFlying (Player.java:1430-1437)
            if (!p.fallFlying && canGlide(p) && !(p.isInWater() || p.isInLava())) {
                p.fallFlying = true;
            }
        }
        p.wasFallFlying = p.fallFlying;
        if (p.isInWater() && p.keys.shift() && !p.flying) {
            // goDownInWater (LivingEntity.java:2399-2401)
            setDelta(p, p.vx + 0.0, p.vy + -0.03999999910593033, p.vz + 0.0);
        }
        if (p.flying && p.cameraIsSelf) {
            int inputYa = 0;
            if (p.keys.shift()) {
                inputYa--;
            }
            if (p.keys.jump()) {
                inputYa++;
            }
            if (inputYa != 0) {
                setDelta(p, p.vx + 0.0, p.vy + inputYa * p.flyingSpeed * 3.0F, p.vz + 0.0);
            }
        }
        // Player.aiStep (Player.java:442-481)
        if (p.jumpTriggerTime > 0) {
            p.jumpTriggerTime--;
        }
        if (p.flying && !p.passenger) {
            p.fallDistance = 0.0;
        }
        this.livingAiStep(p, world);
        // landing ends creative flight (LocalPlayer.java:931-934)
        if (p.onGround && p.flying && !p.localSpectator) {
            p.flying = false;
        }
    }

    private static float impulse(final boolean positive, final boolean negative) {
        if (positive == negative) {
            return 0.0F;
        }
        return positive ? 1.0F : -1.0F;
    }

    /** {@code input.hasForwardImpulse()} (ClientInput.java:17-19). */
    private static boolean hasForwardImpulse(final SimPlayer p) {
        return p.moveY > 1.0E-5F;
    }

    /** LocalPlayer.canStartSprinting (LocalPlayer.java:1171-1178). */
    private boolean canStartSprinting(final SimPlayer p) {
        return !p.sprinting
            && hasForwardImpulse(p)
            && isSprintingPossible(p, p.flying)
            && !isSlowDueToUsingItem(p)
            && (!p.fallFlying || p.isUnderWater())
            && (!isMovingSlowly(p) || p.isUnderWater());
    }

    /** LocalPlayer.isSprintingPossible (LocalPlayer.java:1163-1167), not riding. */
    private static boolean isSprintingPossible(final SimPlayer p, final boolean allowedInShallowWater) {
        return p.blindness < 0
            && (p.foodLevel > 6 || p.mayfly)
            && (allowedInShallowWater || !(p.isInWater() && !p.isUnderWater()));
    }

    /**
     * LocalPlayer.isMovingSlowly (LocalPlayer.java:702-704): crouching, or visually crawling (Entity.isVisuallyCrawling:
     * visually swimming out of water), where LivingEntity.isVisuallySwimming (LivingEntity.java:3672-3674) also counts
     * the FALL_FLYING pose without the gliding flag, e.g. right after landing.
     */
    private static boolean isMovingSlowly(final SimPlayer p) {
        final boolean visuallySwimming = p.pose == Pose.SWIMMING || !p.fallFlying && p.pose == Pose.FALL_FLYING;
        return p.crouching || visuallySwimming && !p.isInWater();
    }

    private static UseEffects useEffects(final SimPlayer p) {
        return p.useItem.getOrDefault(DataComponents.USE_EFFECTS, UseEffects.DEFAULT);
    }

    /** LocalPlayer.isSlowDueToUsingItem (LocalPlayer.java:577-579). */
    private static boolean isSlowDueToUsingItem(final SimPlayer p) {
        return p.usingItem && !useEffects(p).canSprint();
    }

    /** LivingEntity.canGlide with Player's flying check (Player.java:1395-1398, LivingEntity.java:3210-3222). */
    private static boolean canGlide(final SimPlayer p) {
        return !p.flying && !p.onGround && !p.passenger && p.levitation < 0 && p.gliderAvailable;
    }

    /** LocalPlayer.moveTowardsClosestSpace (LocalPlayer.java:465-492). */
    private void moveTowardsClosestSpace(final SimPlayer p, final double x, final double z) {
        // BlockPos.containing(x, getY(), z): only x and z matter, suffocatesAt spans the box's height
        final int px = Mth.floor(x);
        final int pz = Mth.floor(z);
        if (this.suffocatesAt(p, px, pz)) {
            final double xd = x - px;
            final double zd = z - pz;
            Direction dir = null;
            double closest = Double.MAX_VALUE;
            for (final Direction direction : CLOSEST_SPACE_ORDER) {
                final double axisDistance = direction.getAxis() == Direction.Axis.X ? xd : direction.getAxis() == Direction.Axis.Y ? 0.0 : zd;
                final double distanceToEdge = direction.getAxisDirection() == Direction.AxisDirection.POSITIVE ? 1.0 - axisDistance : axisDistance;
                if (distanceToEdge < closest && !this.suffocatesAt(p, px + direction.getStepX(), pz + direction.getStepZ())) {
                    closest = distanceToEdge;
                    dir = direction;
                }
            }
            if (dir != null) {
                if (dir.getAxis() == Direction.Axis.X) {
                    setDelta(p, 0.1 * dir.getStepX(), p.vy, p.vz);
                } else {
                    setDelta(p, p.vx, p.vy, 0.1 * dir.getStepZ());
                }
            }
        }
    }

    private static final Direction[] CLOSEST_SPACE_ORDER = {Direction.WEST, Direction.EAST, Direction.NORTH, Direction.SOUTH};

    /** LocalPlayer.suffocatesAt (LocalPlayer.java:494-498): the column cell over the box's height, deflated. */
    private boolean suffocatesAt(final SimPlayer p, final int bx, final int bz) {
        this.box.set(bx, p.bb.minY, bz, bx + 1.0, p.bb.maxY, bz + 1.0).deflate(1.0E-7);
        return this.collision.collidesWithSuffocatingBlock(p, this.box);
    }

    /** Player.canPlayerFitWithinBlocksAndEntitiesWhen (Player.java:373-375): dimensions at the current scale. */
    private boolean canPlayerFitWithinBlocksAndEntitiesWhen(final SimPlayer p, final Pose pose) {
        final float scale = p.scale();
        p.makeBoundingBox(this.box, p.x, p.y, p.z, SimPlayer.Dimensions.width(pose, scale), SimPlayer.Dimensions.height(pose, scale));
        this.box.deflate(1.0E-7);
        return this.collision.noCollision(p, this.box);
    }

    // ------------------------------------------------------------------------------------------------ LivingEntity.aiStep

    /** LivingEntity.aiStep (LivingEntity.java:3040-3173) for the local player. */
    private void livingAiStep(final SimPlayer p, final SimWorld world) {
        if (p.noJumpDelay > 0) {
            p.noJumpDelay--;
        }
        // players: horizontal floor on the squared horizontal speed (LivingEntity.java:3059-3078)
        double dx = p.vx;
        double dy = p.vy;
        double dz = p.vz;
        if (p.vx * p.vx + p.vz * p.vz < 9.0E-6) {
            dx = 0.0;
            dz = 0.0;
        }
        if (Math.abs(p.vy) < 0.003) {
            dy = 0.0;
        }
        setDelta(p, dx, dy, dz);
        this.applyInput(p);
        // jump (LivingEntity.java:3094-3118); Player.isAffectedByFluids = !flying
        if (p.jumping && !p.flying) {
            final double fluidHeight = p.isInLava() ? p.fluids.fluidHeight(1) : p.fluids.fluidHeight(0);
            final boolean inWaterAndHasFluidHeight = p.isInWater() && fluidHeight > 0.0;
            final double fluidJumpThreshold = fluidJumpThreshold(p);
            if (!inWaterAndHasFluidHeight || p.onGround && !(fluidHeight > fluidJumpThreshold)) {
                if (!p.isInLava() || p.onGround && p.fluids.fluidHeight(1) <= fluidJumpThreshold) {
                    if ((p.onGround || inWaterAndHasFluidHeight && fluidHeight <= fluidJumpThreshold) && p.noJumpDelay == 0) {
                        this.jumpFromGround(p, world);
                        p.noJumpDelay = 10;
                    }
                } else {
                    setDelta(p, p.vx + 0.0, p.vy + 0.03999999910593033, p.vz + 0.0); // jumpInLiquid(LAVA)
                }
            } else {
                setDelta(p, p.vx + 0.0, p.vy + 0.03999999910593033, p.vz + 0.0); // jumpInLiquid(WATER)
            }
        } else {
            p.noJumpDelay = 0;
        }
        if (p.fallFlying) {
            // updateFallFlying, client: checkFallDistanceAccumulation (Entity.java:3021-3025)
            if (p.vy > -0.5 && p.fallDistance > 1.0) {
                p.fallDistance = 1.0;
            }
        }
        final double inputX = p.xxa;
        final double inputZ = p.zza;
        if (p.slowFalling >= 0 || p.levitation >= 0) {
            p.fallDistance = 0.0;
        }
        this.playerTravel(p, world, inputX, inputZ);
        this.blocks.applyEffectsFromBlocks(p, world);
    }

    /** Entity.getFluidJumpThreshold (Entity.java:3845-3847). */
    private static double fluidJumpThreshold(final SimPlayer p) {
        return p.eyeHeight < 0.4 ? 0.0 : 0.4;
    }

    /** LocalPlayer.applyInput (LocalPlayer.java:707-720) and modifyInput (:722-757). */
    private void applyInput(final SimPlayer p) {
        float x = p.moveX;
        float y = p.moveY;
        if (x * x + y * y != 0.0F) {
            x = x * 0.98F;
            y = y * 0.98F;
            if (p.usingItem && !p.passenger) {
                final float multiplier = useEffects(p).speedMultiplier();
                x = x * multiplier;
                y = y * multiplier;
            }
            if (isMovingSlowly(p)) {
                final float sneaking = (float) p.attributes.sneakingSpeed.value();
                x = x * sneaking;
                y = y * sneaking;
            }
            // modifyInputSpeedForSquareMovement
            final float length = Mth.sqrt(x * x + y * y);
            if (length > 0.0F) {
                final float inv = 1.0F / length;
                final float dirX = x * inv;
                final float dirY = y * inv;
                final float ax = Math.abs(dirX);
                final float ay = Math.abs(dirY);
                final float tan = ay > ax ? ax / ay : ay / ax;
                final float distanceToUnitSquare = Mth.sqrt(1.0F + tan * tan);
                final float modifiedLength = Math.min(length * distanceToUnitSquare, 1.0F);
                x = dirX * modifiedLength;
                y = dirY * modifiedLength;
            }
        }
        p.xxa = x;
        p.zza = y;
        p.jumping = p.keys.jump();
    }

    /** LivingEntity.jumpFromGround (LivingEntity.java:2385-2397) with getJumpPower (:2376-2382). */
    private void jumpFromGround(final SimPlayer p, final SimWorld world) {
        final float boost = p.jumpBoost >= 0 ? 0.1F * (p.jumpBoost + 1.0F) : 0.0F;
        final float jumpPower = (float) p.attributes.jumpStrength.value() * 1.0F * this.blocks.jumpFactor(p, world) + boost;
        if (!(jumpPower <= 1.0E-5F)) {
            setDelta(p, p.vx, Math.max((double) jumpPower, p.vy), p.vz);
            if (p.sprinting) {
                final float angle = p.yRot * 0.017453292F;
                addDelta(p, -Mth.sin(angle) * 0.2, 0.0, Mth.cos(angle) * 0.2);
            }
        }
    }

    // ------------------------------------------------------------------------------------------------ travel

    /** Player.travel (Player.java:1369-1393). */
    private void playerTravel(final SimPlayer p, final SimWorld world, final double inputX, final double inputZ) {
        if (p.isSwimming()) {
            // getLookAngle().y = -Mth.sin(xRot * 0.017453292F) (Entity.calculateViewVector)
            final double lookAngleY = -Mth.sin(p.xRot * 0.017453292F);
            final double multiplier = lookAngleY < -0.2 ? 0.085 : 0.06;
            if (lookAngleY <= 0.0 || p.jumping
                || !world.fluidState(Mth.floor(p.x), Mth.floor(p.y + 1.0 - 0.1), Mth.floor(p.z)).isEmpty()) {
                setDelta(p, p.vx + 0.0, p.vy + (lookAngleY - p.vy) * multiplier, p.vz + 0.0);
            }
        }
        this.travel.flying = p.flying;
        this.travel.stopsGliding = false;
        if (p.flying) {
            final double originalMovementY = p.vy;
            this.livingTravel(p, world, inputX, inputZ);
            afterFlyingTravel(p, originalMovementY);
        } else {
            this.livingTravel(p, world, inputX, inputZ);
        }
    }

    /** Player.travel's flight rule after the branch (Player.java:1384-1387): y = the y before the branch × 0.6. */
    static void afterFlyingTravel(final SimPlayer p, final double originalMovementY) {
        setDelta(p, p.vx, originalMovementY * 0.6, p.vz);
    }

    /** LivingEntity.travel (LivingEntity.java:2426-2434). */
    private void livingTravel(final SimPlayer p, final SimWorld world, final double inputX, final double inputZ) {
        // shouldTravelInFluid: isInLiquid() && isAffectedByFluids() && !canStandOnFluid (false for players)
        if ((p.isInWater() || p.isInLava()) && !p.flying) {
            this.travelInFluid(p, world, inputX, inputZ);
        } else if (p.fallFlying) {
            this.travelFallFlying(p, world, inputX, inputZ);
        } else {
            this.travelInAir(p, world, inputX, inputZ);
        }
    }

    /** LivingEntity.travelInAir (LivingEntity.java:2464-2491). */
    private void travelInAir(final SimPlayer p, final SimWorld world, final double inputX, final double inputZ) {
        this.blocks.onPos(p, world, 0.500001F, this.support);
        final int belowX = this.support.getX();
        final int belowY = this.support.getY();
        final int belowZ = this.support.getZ();
        final float blockFriction = p.onGround
            ? computeModifiedFriction(world.blockState(belowX, belowY, belowZ).getBlock().getFriction(), (float) p.attributes.frictionModifier.value())
            : 1.0F;
        // handleRelativeFrictionAndCalculateMovement (LivingEntity.java:2674-2684)
        this.moveRelative(p, this.frictionInfluencedSpeed(p, blockFriction), inputX, 0.0, inputZ);
        this.handleOnClimbable(p, world);
        this.travel.kind = Travel.AIR;
        this.travel.blockFriction = blockFriction;
        this.travel.belowX = belowX;
        this.travel.belowZ = belowZ;
        this.move(p, world, p.vx, p.vy, p.vz);
        this.afterAirMove(p, world, blockFriction, belowX, belowZ);
    }

    /**
     * travelInAir after its move: handleRelativeFrictionAndCalculateMovement's climb (LivingEntity.java:2678-2681), then
     * levitation, gravity or the unloaded-chunk fall, then drag and the block's friction (:2470-2490). {@code belowX/Z}
     * is the column of the block under the start position the friction came from.
     */
    void afterAirMove(final SimPlayer p, final SimWorld world, final float blockFriction, final int belowX, final int belowZ) {
        final double movementX = p.vx;
        double movementY = p.vy;
        final double movementZ = p.vz;
        if ((p.horizontalCollision || p.jumping)
            && (this.blocks.onClimbable(p, world) || p.wasInPowderSnow && p.feet.getItem() == Items.LEATHER_BOOTS)) {
            movementY = 0.2;
        }
        final double movementYBase = movementY;
        if (p.levitation >= 0) {
            movementY += (0.05 * (p.levitation + 1) - movementYBase) * 0.2;
        } else if (world.isChunkLoaded(belowX >> 4, belowZ >> 4)) {
            movementY -= effectiveGravity(p);
        } else if (p.y > world.minY()) {
            movementY = -0.1;
        } else {
            movementY = 0.0;
        }
        // shouldDiscardFriction is false for players
        final float entityAirDragModifier = (float) p.attributes.airDragModifier.value();
        final float airDrag = computeModifiedFriction(0.91F, entityAirDragModifier);
        final float friction = blockFriction * airDrag;
        final float verticalFriction = computeModifiedFriction(0.98F, entityAirDragModifier);
        setDelta(p, movementX * friction, movementY * verticalFriction, movementZ * friction);
    }

    /** LivingEntity.computeModifiedFriction (LivingEntity.java:528-530). */
    private static float computeModifiedFriction(final float friction, final float modifier) {
        return Mth.clamp(1.0F - (1.0F - friction) * modifier, 0.0F, 1.0F);
    }

    /** LivingEntity.getFrictionInfluencedSpeed (LivingEntity.java:2718-2724) with Player.getFlyingSpeed. */
    private float frictionInfluencedSpeed(final SimPlayer p, final float blockFriction) {
        if (p.onGround) {
            final float speed = (float) p.attributes.movementSpeed.value();
            return blockFriction > 0.6 ? speed * (0.21600002F / (blockFriction * blockFriction * blockFriction)) : speed;
        }
        if (p.flying && !p.passenger) {
            return p.sprinting ? p.flyingSpeed * 2.0F : p.flyingSpeed;
        }
        return p.sprinting ? 0.025999999F : 0.02F;
    }

    /** LivingEntity.getEffectiveGravity (LivingEntity.java:2421-2424), reading the current velocity. */
    static double effectiveGravity(final SimPlayer p) {
        final double gravity = p.noGravity ? 0.0 : p.attributes.gravity.value();
        final boolean isFalling = p.vy <= 0.0;
        return isFalling && p.slowFalling >= 0 ? Math.min(gravity, 0.01) : gravity;
    }

    /** LivingEntity.handleOnClimbable (LivingEntity.java:2701-2716) on the current velocity. */
    private void handleOnClimbable(final SimPlayer p, final SimWorld world) {
        if (this.blocks.onClimbable(p, world)) {
            p.fallDistance = 0.0;
            final double xd = Mth.clamp(p.vx, -0.15000000596046448, 0.15000000596046448);
            final double zd = Mth.clamp(p.vz, -0.15000000596046448, 0.15000000596046448);
            double yd = Math.max(p.vy, -0.15000000596046448);
            // LocalPlayer.isSuppressingSlidingDownLadder: !flying && shift
            if (yd < 0.0 && world.blockState(p.blockX, p.blockY, p.blockZ).getBlock() != Blocks.SCAFFOLDING && !p.flying && p.isShiftKeyDown()) {
                yd = 0.0;
            }
            setDelta(p, xd, yd, zd);
        }
    }

    /** Entity.moveRelative + getInputVector (Entity.java:1807-1822). */
    private void moveRelative(final SimPlayer p, final float speed, final double inputX, final double inputY, final double inputZ) {
        final double length = inputX * inputX + inputY * inputY + inputZ * inputZ;
        double ax = 0.0;
        double ay = 0.0;
        double az = 0.0;
        if (!(length < 1.0E-7)) {
            double mx = inputX;
            double my = inputY;
            double mz = inputZ;
            if (length > 1.0) {
                final double dist = Math.sqrt(inputX * inputX + inputY * inputY + inputZ * inputZ);
                if (dist < 9.999999747378752E-6) {
                    mx = 0.0;
                    my = 0.0;
                    mz = 0.0;
                } else {
                    mx = inputX / dist;
                    my = inputY / dist;
                    mz = inputZ / dist;
                }
            }
            mx = mx * speed;
            my = my * speed;
            mz = mz * speed;
            final float sin = Mth.sin(p.yRot * 0.017453292F);
            final float cos = Mth.cos(p.yRot * 0.017453292F);
            ax = mx * cos - mz * sin;
            ay = my;
            az = mz * cos + mx * sin;
        }
        setDelta(p, p.vx + ax, p.vy + ay, p.vz + az);
    }

    /** LivingEntity.travelInFluid (LivingEntity.java:2498-2509) with travelInWater and travelInLava. */
    private void travelInFluid(final SimPlayer p, final SimWorld world, final double inputX, final double inputZ) {
        final boolean isFalling = p.vy <= 0.0;
        final double oldY = p.y;
        final double baseGravity = effectiveGravity(p);
        if (p.isInWater()) {
            // travelInWater (LivingEntity.java:2511-2538)
            float slowDown = p.sprinting ? 0.9F : 0.8F;
            float speed = 0.02F;
            float waterWalker = (float) p.attributes.waterMovementEfficiency.value();
            if (!p.onGround) {
                waterWalker *= 0.5F;
            }
            if (waterWalker > 0.0F) {
                slowDown += (0.54600006F - slowDown) * waterWalker;
                speed += ((float) p.attributes.movementSpeed.value() - speed) * waterWalker;
            }
            if (p.dolphinsGrace >= 0) {
                slowDown = 0.96F;
            }
            this.moveRelative(p, speed, inputX, 0.0, inputZ);
            this.travel.kind = Travel.WATER;
            this.travel.slowDown = slowDown;
            this.move(p, world, p.vx, p.vy, p.vz);
            this.afterWaterMove(p, world, slowDown, baseGravity, isFalling, oldY);
        } else {
            // travelInLava (LivingEntity.java:2544-2560)
            this.moveRelative(p, 0.02F, inputX, 0.0, inputZ);
            this.travel.kind = Travel.LAVA;
            this.move(p, world, p.vx, p.vy, p.vz);
            this.afterLavaMove(p, baseGravity, isFalling, oldY);
        }
    }

    /**
     * travelInWater after its move (LivingEntity.java:2529-2537): the climb, the slow-down, the falling adjustment and
     * jumpOutOfFluid. {@code baseGravity} and {@code isFalling} are travelInFluid's, from the velocity before the move.
     */
    void afterWaterMove(final SimPlayer p, final SimWorld world, final float slowDown, final double baseGravity, final boolean isFalling,
                        final double oldY) {
        double mx = p.vx;
        double my = p.vy;
        double mz = p.vz;
        if (p.horizontalCollision && this.blocks.onClimbable(p, world)) {
            my = 0.2;
        }
        mx = mx * slowDown;
        my = my * 0.800000011920929;
        mz = mz * slowDown;
        this.setFluidFallingAdjusted(p, baseGravity, isFalling, mx, my, mz);
        this.jumpOutOfFluid(p, oldY);
    }

    /** travelInLava after its move (LivingEntity.java:2546-2559): shallow or deep drag, a quarter of gravity, jumpOutOfFluid. */
    void afterLavaMove(final SimPlayer p, final double baseGravity, final boolean isFalling, final double oldY) {
        if (p.fluids.fluidHeight(1) <= fluidJumpThreshold(p)) {
            setDelta(p, p.vx * 0.5, p.vy * 0.800000011920929, p.vz * 0.5);
            this.setFluidFallingAdjusted(p, baseGravity, isFalling, p.vx, p.vy, p.vz);
        } else {
            setDelta(p, p.vx * 0.5, p.vy * 0.5, p.vz * 0.5);
        }
        if (baseGravity != 0.0) {
            setDelta(p, p.vx + 0.0, p.vy + -baseGravity / 4.0, p.vz + 0.0);
        }
        this.jumpOutOfFluid(p, oldY);
    }

    /** LivingEntity.getFluidFallingAdjustedMovement (LivingEntity.java:2686-2699), then setDeltaMovement. */
    private void setFluidFallingAdjusted(final SimPlayer p, final double baseGravity, final boolean isFalling, final double mx, final double my, final double mz) {
        if (baseGravity != 0.0 && !p.sprinting) {
            final double yd;
            if (isFalling && Math.abs(my - 0.005) >= 0.003 && Math.abs(my - baseGravity / 16.0) < 0.003) {
                yd = -0.003;
            } else {
                yd = my - baseGravity / 16.0;
            }
            setDelta(p, mx, yd, mz);
        } else {
            setDelta(p, mx, my, mz);
        }
    }

    /** LivingEntity.jumpOutOfFluid (LivingEntity.java:2562-2567) with Entity.isFree (Entity.java:688-694). */
    private void jumpOutOfFluid(final SimPlayer p, final double oldY) {
        if (p.horizontalCollision) {
            this.box.set(p.bb).move(p.vx, p.vy + 0.6000000238418579 - p.y + oldY, p.vz);
            if (this.collision.noCollision(p, this.box) && !this.collision.containsAnyLiquid(this.box)) {
                setDelta(p, p.vx, 0.30000001192092896, p.vz);
            }
        }
    }

    /** LivingEntity.travelFallFlying (LivingEntity.java:2579-2593) with updateFallFlyingMovement (:2600-2625). */
    private void travelFallFlying(final SimPlayer p, final SimWorld world, final double inputX, final double inputZ) {
        if (this.blocks.onClimbable(p, world)) {
            this.travel.stopsGliding = true;
            this.travelInAir(p, world, inputX, inputZ);
            p.fallFlying = false; // stopFallFlying: flag 7 set then cleared
            return;
        }
        // getLookAngle() = calculateViewVector(xRot, yRot) (Entity.java:2031-2039)
        final float realXRot = p.xRot * 0.017453292F;
        final float realYRot = -p.yRot * 0.017453292F;
        final float yCos = Mth.cos(realYRot);
        final float ySin = Mth.sin(realYRot);
        final float xCos = Mth.cos(realXRot);
        final float xSin = Mth.sin(realXRot);
        final double lookX = ySin * xCos;
        final double lookY = -xSin;
        final double lookZ = yCos * xCos;
        final float leanAngle = p.xRot * 0.017453292F;
        final double lookHorLength = Math.sqrt(lookX * lookX + lookZ * lookZ);
        double mx = p.vx;
        double my = p.vy;
        double mz = p.vz;
        final double moveHorLength = Math.sqrt(mx * mx + mz * mz);
        final double gravity = effectiveGravity(p);
        final double liftForce = Mth.square(Math.cos(leanAngle));
        mx = mx + 0.0;
        my = my + gravity * (-1.0 + liftForce * 0.75);
        mz = mz + 0.0;
        if (my < 0.0 && lookHorLength > 0.0) {
            final double convert = my * -0.1 * liftForce;
            mx = mx + lookX * convert / lookHorLength;
            my = my + convert;
            mz = mz + lookZ * convert / lookHorLength;
        }
        if (leanAngle < 0.0F && lookHorLength > 0.0) {
            final double convert = moveHorLength * -Mth.sin(leanAngle) * 0.04;
            mx = mx + -lookX * convert / lookHorLength;
            my = my + convert * 3.2;
            mz = mz + -lookZ * convert / lookHorLength;
        }
        if (lookHorLength > 0.0) {
            mx = mx + (lookX / lookHorLength * moveHorLength - mx) * 0.1;
            my = my + 0.0;
            mz = mz + (lookZ / lookHorLength * moveHorLength - mz) * 0.1;
        }
        setDelta(p, mx * 0.9900000095367432, my * 0.9800000190734863, mz * 0.9900000095367432);
        this.travel.kind = Travel.GLIDE;
        this.move(p, world, p.vx, p.vy, p.vz);
    }

    // ------------------------------------------------------------------------------------------------ Entity.move

    /**
     * LocalPlayer.move (LocalPlayer.java:1017-1025): Entity.move, then the auto-jump check on the horizontal distance
     * moved, as floats. The walked distance it also adds only drives sounds and the view bob.
     */
    private void move(final SimPlayer p, final SimWorld world, final double dx, final double dy, final double dz) {
        if (this.probe != null) {
            // velocityAfterMove runs the tick up to here only: keep the state the move starts from and the branch's
            // pre-move values, and move nothing (the rest of this tick runs on a throwaway copy)
            this.probe.copyFrom(p);
            this.probeTravel.copyFrom(this.travel);
            this.probe = null;
            return;
        }
        final double prevX = p.x;
        final double prevZ = p.z;
        this.entityMove(p, world, dx, dy, dz);
        final float deltaX = (float) (p.x - prevX);
        final float deltaZ = (float) (p.z - prevZ);
        if (this.canAutoJump(p, world)) {
            this.updateAutoJump(p, world, deltaX, deltaZ);
        }
    }

    /** LocalPlayer.canAutoJump (LocalPlayer.java:1151-1159), with Player.isStayingOnGroundSurface = shift. */
    private boolean canAutoJump(final SimPlayer p, final SimWorld world) {
        return p.autoJumpEnabled
            && p.autoJumpTime <= 0
            && p.onGround
            && !p.isShiftKeyDown()
            && !p.passenger
            && p.moveX * p.moveX + p.moveY * p.moveY > 0.0F // isMoving: input.getMoveVector().lengthSquared()
            && this.blocks.jumpFactor(p, world) >= 1.0;
    }

    /**
     * LocalPlayer.updateAutoJump (LocalPlayer.java:1036-1131): looks about a block ahead along the move (or the input
     * when the player did not move) for a step between 0.5 and the jump height with head room, and if it finds one,
     * makes the next tick jump ({@code autoJumpTime = 1}). Vec3 arithmetic kept operation by operation.
     */
    private void updateAutoJump(final SimPlayer p, final SimWorld world, final float xa, final float za) {
        // moveBegin = position(); moveEnd = moveBegin.add(xa, 0, za); moveDiff = new Vec3(xa, 0, za)
        final double beginX = p.x;
        final double beginY = p.y;
        final double beginZ = p.z;
        final double endX = beginX + xa;
        final double endY = beginY + 0.0;
        final double endZ = beginZ + za;
        double diffX = xa;
        final double diffY = 0.0;
        double diffZ = za;
        final float currentSpeed = (float) p.attributes.movementSpeed.value(); // Player.getSpeed
        float moveDistSq = (float) (diffX * diffX + diffY * diffY + diffZ * diffZ);
        if (moveDistSq <= 0.001F) {
            final float inputXa = currentSpeed * p.moveX;
            final float inputZa = currentSpeed * p.moveY;
            final float sin = Mth.sin(p.yRot * 0.017453292F);
            final float cos = Mth.cos(p.yRot * 0.017453292F);
            // new Vec3(float expression, moveDiff.y, float expression)
            final float fx = inputXa * cos - inputZa * sin;
            final float fz = inputZa * cos + inputXa * sin;
            diffX = fx;
            diffZ = fz;
            moveDistSq = (float) (diffX * diffX + diffY * diffY + diffZ * diffZ);
            if (moveDistSq <= 0.001F) {
                return;
            }
        }
        final float moveDistInverted = Mth.invSqrt(moveDistSq);
        // moveDir = moveDiff.scale(moveDistInverted)
        final double dirX = diffX * moveDistInverted;
        final double dirY = diffY * moveDistInverted;
        final double dirZ = diffZ * moveDistInverted;
        // getForward() = Vec3.directionFromRotation(xRot, yRot) (Vec3.java:269-275); its y is not read
        final float yCos = Mth.cos(-p.yRot * 0.017453292F - 3.1415927F);
        final float ySin = Mth.sin(-p.yRot * 0.017453292F - 3.1415927F);
        final float xCos = -Mth.cos(-p.xRot * 0.017453292F);
        final double forwardX = ySin * xCos;
        final double forwardZ = yCos * xCos;
        final float facingVsMoving = (float) (forwardX * dirX + forwardZ * dirZ);
        if (facingVsMoving < -0.15F) {
            return;
        }
        // ceilingPos = BlockPos.containing(getX(), getBoundingBox().maxY, getZ()), then up: both must be empty
        final int ceilingX = Mth.floor(p.x);
        int ceilingY = Mth.floor(p.bb.maxY);
        final int ceilingZ = Mth.floor(p.z);
        if (!this.collision.collisionShapeAt(p, ceilingX, ceilingY, ceilingZ).empty) {
            return;
        }
        ceilingY++;
        if (!this.collision.collisionShapeAt(p, ceilingX, ceilingY, ceilingZ).empty) {
            return;
        }
        float jumpHeight = 1.2F;
        if (p.jumpBoost >= 0) {
            jumpHeight += (p.jumpBoost + 1) * 0.75F;
        }
        final float lookAheadDist = Math.max(currentSpeed * 7.0F, 1.0F / moveDistInverted);
        // segEnd = moveEnd.add(moveDir.scale(lookAheadDist))
        double segEndX = endX + dirX * lookAheadDist;
        double segEndY = endY + dirY * lookAheadDist;
        double segEndZ = endZ + dirZ * lookAheadDist;
        final float playerWidth = p.width;
        final float playerHeight = p.height;
        // testBox = new AABB(moveBegin, segEnd.add(0, playerHeight, 0)).inflate(playerWidth, 0, playerWidth)
        this.box.set(beginX, beginY, beginZ, segEndX + 0.0, segEndY + playerHeight, segEndZ + 0.0).inflate(playerWidth, 0.0, playerWidth);
        // segBegin = moveBegin.add(0, 0.51, 0); segEnd = segEnd.add(0, 0.51, 0)
        final double segBeginX = beginX + 0.0;
        final double segBeginY = beginY + 0.5099999904632568;
        final double segBeginZ = beginZ + 0.0;
        segEndX = segEndX + 0.0;
        segEndY = segEndY + 0.5099999904632568;
        segEndZ = segEndZ + 0.0;
        // rightDir = moveDir.cross(new Vec3(0, 1, 0)) (Vec3.java:96-98); rightOffset = rightDir.scale(playerWidth * 0.5F)
        final double rightX = dirY * 0.0 - dirZ * 1.0;
        final double rightY = dirZ * 0.0 - dirX * 0.0;
        final double rightZ = dirX * 1.0 - dirY * 0.0;
        final double half = playerWidth * 0.5F;
        final double offX = rightX * half;
        final double offY = rightY * half;
        final double offZ = rightZ * half;
        // the two segments, as AABB.intersects(Vec3, Vec3) takes them: subtract(v) = add(-v)
        final double[] seg = this.segments;
        seg[0] = segBeginX + -offX;
        seg[1] = segBeginY + -offY;
        seg[2] = segBeginZ + -offZ;
        seg[3] = segEndX + -offX;
        seg[4] = segEndY + -offY;
        seg[5] = segEndZ + -offZ;
        seg[6] = segBeginX + offX;
        seg[7] = segBeginY + offY;
        seg[8] = segBeginZ + offZ;
        seg[9] = segEndX + offX;
        seg[10] = segEndY + offY;
        seg[11] = segEndZ + offZ;
        float obstacleHeight = 1.0E-45F;
        final double[] hit = this.obstacle;
        if (this.collision.firstBoxCrossing(p, this.box, seg, hit)) {
            obstacleHeight = (float) hit[4];
            // BlockPos.containing(box.getCenter())
            final int obstacleX = Mth.floor(Mth.lerp(0.5, hit[0], hit[3]));
            final int obstacleY = Mth.floor(Mth.lerp(0.5, hit[1], hit[4]));
            final int obstacleZ = Mth.floor(Mth.lerp(0.5, hit[2], hit[5]));
            for (int steps = 1; steps < jumpHeight; steps++) {
                final int aboveY = obstacleY + steps;
                final SimShape blockShape = this.collision.collisionShapeAt(p, obstacleX, aboveY, obstacleZ);
                if (!blockShape.empty) {
                    obstacleHeight = (float) blockShape.max(1, false, 0.0) + aboveY;
                    if (obstacleHeight - p.y > jumpHeight) {
                        return;
                    }
                }
                if (steps > 1) {
                    ceilingY++;
                    if (!this.collision.collisionShapeAt(p, ceilingX, ceilingY, ceilingZ).empty) {
                        return;
                    }
                }
            }
        }
        if (obstacleHeight != 1.0E-45F) {
            final float ydelta = (float) (obstacleHeight - p.y);
            if (!(ydelta <= 0.5F) && !(ydelta > jumpHeight)) {
                p.autoJumpTime = 1;
            }
        }
    }

    /** Entity.move(SELF, delta) (Entity.java:741-836). */
    private void entityMove(final SimPlayer p, final SimWorld world, double dx, double dy, double dz) {
        if (p.noPhysics) {
            this.lastMoveX = dx;
            this.lastMoveY = dy;
            this.lastMoveZ = dz;
            noPhysicsMove(p, dx, dy, dz);
            return;
        }
        if (isStuck(p)) {
            dx = dx * p.stuckX;
            dy = dy * p.stuckY;
            dz = dz * p.stuckZ;
            clearStuck(p);
        }
        // Player.maybeBackOffFromEdge (Player.java:864-931)
        final float maxDownStep = (float) p.attributes.stepHeight.value();
        if (!p.flying && !(dy > 0.0) && p.isShiftKeyDown() && this.isAboveGround(p, maxDownStep)) {
            double deltaX = dx;
            double deltaZ = dz;
            final double stepX = Math.signum(deltaX) * 0.05;
            final double stepZ = Math.signum(deltaZ) * 0.05;
            while (deltaX != 0.0 && this.canFallAtLeast(p, deltaX, 0.0, maxDownStep)) {
                if (Math.abs(deltaX) <= 0.05) {
                    deltaX = 0.0;
                    break;
                }
                deltaX -= stepX;
            }
            while (deltaZ != 0.0 && this.canFallAtLeast(p, 0.0, deltaZ, maxDownStep)) {
                if (Math.abs(deltaZ) <= 0.05) {
                    deltaZ = 0.0;
                    break;
                }
                deltaZ -= stepZ;
            }
            while (deltaX != 0.0 && deltaZ != 0.0 && this.canFallAtLeast(p, deltaX, deltaZ, maxDownStep)) {
                if (Math.abs(deltaX) <= 0.05) {
                    deltaX = 0.0;
                } else {
                    deltaX -= stepX;
                }
                if (Math.abs(deltaZ) <= 0.05) {
                    deltaZ = 0.0;
                } else {
                    deltaZ -= stepZ;
                }
            }
            dx = deltaX;
            dz = deltaZ;
        }
        // collide (Entity.java:1182-1211); maxUpStep() = (float) STEP_HEIGHT for an unridden player
        this.collision.collide(p, dx, dy, dz, maxDownStep, this.out);
        this.lastMoveX = this.out[0];
        this.lastMoveY = this.out[1];
        this.lastMoveZ = this.out[2];
        this.afterCollide(p, world, dx, dy, dz, this.out[0], this.out[1], this.out[2]);
    }

    /** Entity.move with noPhysics (Entity.java:746-752): the delta added to the position, no collision flags. */
    static void noPhysicsMove(final SimPlayer p, final double dx, final double dy, final double dz) {
        p.setPos(p.x + dx, p.y + dy, p.z + dz);
        p.horizontalCollision = false;
        p.verticalCollision = false;
        p.verticalCollisionBelow = false;
        p.minorHorizontalCollision = false;
    }

    /** Entity.move's stuck test (Entity.java:763): a multiplier left by a cobweb, berry bush or powder snow. */
    static boolean isStuck(final SimPlayer p) {
        return p.stuckX * p.stuckX + p.stuckY * p.stuckY + p.stuckZ * p.stuckZ > 1.0E-7;
    }

    /** Entity.move consuming the stuck multiplier (Entity.java:768-769): it and the velocity are zeroed. */
    static void clearStuck(final SimPlayer p) {
        p.stuckX = 0.0;
        p.stuckY = 0.0;
        p.stuckZ = 0.0;
        setDelta(p, 0.0, 0.0, 0.0);
    }

    /**
     * Entity.move after collide (Entity.java:774-836): the long-fall clip, the position, the collision flags (Mth.equal
     * against {@code dx/dy/dz}, what collide was given), the support, fall distance, restitution and the block speed
     * factor, on the current velocity. {@code mx/my/mz} is collide's result.
     */
    void afterCollide(final SimPlayer p, final SimWorld world, final double dx, final double dy, final double dz,
                      final double mx, final double my, final double mz) {
        final double movementLength = mx * mx + my * my + mz * mz;
        if (movementLength > 1.0E-7 || dx * dx + dy * dy + dz * dz - movementLength < 1.0E-7) {
            if (p.fallDistance != 0.0 && movementLength >= 1.0) {
                // a long move resets the fall when it crosses water or a fall-damage-resetting block
                final double length = Math.sqrt(movementLength);
                final double checkDistance = Math.min(length, 8.0);
                double nx = mx;
                double ny = my;
                double nz = mz;
                final double d = Math.sqrt(mx * mx + my * my + mz * mz);
                if (d < 9.999999747378752E-6) {
                    nx = 0.0;
                    ny = 0.0;
                    nz = 0.0;
                } else {
                    nx = mx / d;
                    ny = my / d;
                    nz = mz / d;
                }
                final double toX = p.x + nx * checkDistance;
                final double toY = p.y + ny * checkDistance;
                final double toZ = p.z + nz * checkDistance;
                if (SimClip.fallResetHit(world, p.x, p.y, p.z, toX, toY, toZ)) {
                    p.fallDistance = 0.0;
                }
            }
            final double fromX = p.x;
            final double fromY = p.y;
            final double fromZ = p.z;
            final double newX = fromX + mx;
            final double newY = fromY + my;
            final double newZ = fromZ + mz;
            p.movements.add(fromX, fromY, fromZ, newX, newY, newZ, true, dx, dy, dz);
            p.setPos(newX, newY, newZ);
        }
        // Mth.equal: |b - a| < 9.999999747378752E-6
        final boolean xCollision = !(Math.abs(mx - dx) < 9.999999747378752E-6);
        final boolean zCollision = !(Math.abs(mz - dz) < 9.999999747378752E-6);
        p.horizontalCollision = xCollision || zCollision;
        // the local player always updates the vertical flags (isLocalInstanceAuthoritative)
        p.verticalCollision = dy != my;
        p.verticalCollisionBelow = p.verticalCollision && dy < 0.0;
        p.onGround = p.verticalCollisionBelow;
        this.checkSupportingBlock(p, p.onGround, true, mx, mz);
        if (p.horizontalCollision) {
            p.minorHorizontalCollision = this.isHorizontalCollisionMinor(p, mx, mz);
        } else {
            p.minorHorizontalCollision = false;
        }
        this.blocks.onPos(p, world, 0.2F, this.pos);
        final int effectX = this.pos.getX();
        final int effectY = this.pos.getY();
        final int effectZ = this.pos.getZ();
        final BlockState effectState = world.blockState(effectX, effectY, effectZ);
        this.checkFallDamage(p, world, my);
        final boolean movedVertically = Math.abs(dy) > 0.0;
        if (movedVertically && p.verticalCollision || p.horizontalCollision) {
            this.restituteMovementAfterCollisions(p, effectState, xCollision, zCollision, my);
        }
        // Player.getBlockSpeedFactor (Player.java:1827-1830) → LivingEntity's lerp (LivingEntity.java:524-526)
        final float blockSpeedFactor;
        if (!p.flying && !p.fallFlying) {
            blockSpeedFactor = Mth.lerp((float) p.attributes.movementEfficiency.value(), this.blocks.entitySpeedFactor(p, world), 1.0F);
        } else {
            blockSpeedFactor = 1.0F;
        }
        setDelta(p, p.vx * blockSpeedFactor, p.vy * 1.0, p.vz * blockSpeedFactor);
    }

    /** Player.isAboveGround (Player.java:913-915). */
    boolean isAboveGround(final SimPlayer p, final float maxDownStep) {
        return p.onGround || p.fallDistance < maxDownStep && !this.canFallAtLeast(p, 0.0, 0.0, maxDownStep - p.fallDistance);
    }

    /** Player.canFallAtLeast (Player.java:917-931). */
    boolean canFallAtLeast(final SimPlayer p, final double deltaX, final double deltaZ, final double minHeight) {
        final SimBox bb = p.bb;
        this.box.set(bb.minX + 1.0E-7 + deltaX, bb.minY - minHeight - 1.0E-7, bb.minZ + 1.0E-7 + deltaZ,
            bb.maxX - 1.0E-7 + deltaX, bb.minY, bb.maxZ - 1.0E-7 + deltaZ);
        return this.collision.noCollision(p, this.box);
    }

    /**
     * Entity.checkSupportingBlock (Entity.java:715-735): the support search 1e-6 under the box, retried once against
     * the box before the horizontal move when nothing supports it and the last search did find something.
     */
    private void checkSupportingBlock(final SimPlayer p, final boolean onGround, final boolean hasMovement, final double mx, final double mz) {
        if (onGround) {
            final SimBox bb = p.bb;
            this.box.set(bb.minX, bb.minY - 1.0E-6, bb.minZ, bb.maxX, bb.minY, bb.maxZ);
            boolean found = this.collision.findSupportingBlock(p, this.box, this.support);
            if (found || p.onGroundNoBlocks) {
                setSupport(p, found);
            } else if (hasMovement) {
                this.box.move(-mx, 0.0, -mz);
                found = this.collision.findSupportingBlock(p, this.box, this.support);
                setSupport(p, found);
            }
            p.onGroundNoBlocks = !found;
        } else {
            p.onGroundNoBlocks = false;
            p.hasSupportingBlock = false;
        }
    }

    private void setSupport(final SimPlayer p, final boolean found) {
        p.hasSupportingBlock = found;
        if (found) {
            p.supportX = this.support.getX();
            p.supportY = this.support.getY();
            p.supportZ = this.support.getZ();
        }
    }

    /** LocalPlayer.isHorizontalCollisionMinor (LocalPlayer.java:1134-1149). */
    private boolean isHorizontalCollisionMinor(final SimPlayer p, final double mx, final double mz) {
        final float yRotInRadians = p.yRot * 0.017453292F;
        final double yRotSin = Mth.sin(yRotInRadians);
        final double yRotCos = Mth.cos(yRotInRadians);
        final double globalXA = p.xxa * yRotCos - p.zza * yRotSin;
        final double globalZA = p.zza * yRotCos + p.xxa * yRotSin;
        final double aLengthSquared = globalXA * globalXA + globalZA * globalZA;
        final double movementLengthSquared = mx * mx + mz * mz;
        if (!(aLengthSquared < 9.999999747378752E-6) && !(movementLengthSquared < 9.999999747378752E-6)) {
            final double dotProduct = globalXA * mx + globalZA * mz;
            final double angle = Math.acos(dotProduct / Math.sqrt(aLengthSquared * movementLengthSquared));
            return angle < 0.13962633907794952;
        }
        return false;
    }

    /**
     * LivingEntity.checkFallDamage (LivingEntity.java:374-405) → Entity.checkFallDamage (Entity.java:1605-1623): the
     * mid-move fluid update when not already in water, then fall distance; landing resets it ({@code fallOn} only
     * damages, on the server).
     */
    private void checkFallDamage(final SimPlayer p, final SimWorld world, final double ya) {
        if (!p.isInWater()) {
            this.fluids.updateFluidInteraction(p, world);
        }
        if (!p.isInWater() && ya < 0.0) {
            p.fallDistance -= (float) ya;
        }
        if (p.onGround) {
            p.fallDistance = 0.0;
        }
    }

    /** Entity.restituteMovementAfterCollisions (Entity.java:844-886), with LivingEntity's bounciness and air drag. */
    private void restituteMovementAfterCollisions(final SimPlayer p, final BlockState effectState, final boolean xCollision, final boolean zCollision,
                                                  final double movementY) {
        final boolean suppressingBounce = p.isShiftKeyDown();
        double restitution = suppressingBounce ? 0.0 : p.attributes.bounciness.value();
        final double curX = p.vx;
        final double curY = p.vy;
        final double curZ = p.vz;
        double outX = curX;
        double outY = curY;
        double outZ = curZ;
        if (xCollision) {
            outX = -curX * restitution;
        }
        if (zCollision) {
            outZ = -curZ * restitution;
        }
        if (p.verticalCollision) {
            if (p.verticalCollisionBelow) {
                restitution = !(-curY <= effectiveGravity(p)) && !suppressingBounce && !effectState.is(net.minecraft.tags.BlockTags.SUPPRESSES_BOUNCE)
                    ? Math.max(restitution, (double) effectState.getBlock().getBounceRestitution())
                    : 0.0;
            }
            final double gravityCompensation;
            final double effectiveDrag;
            if (restitution > 0.0) {
                final double portionWithMovement = movementY / curY;
                gravityCompensation = portionWithMovement * effectiveGravity(p);
                // getAirDrag: computeModifiedFriction(0.98F, AIR_DRAG_MODIFIER) (LivingEntity.java:2494-2496)
                final double airDrag = computeModifiedFriction(0.98F, (float) p.attributes.airDragModifier.value());
                effectiveDrag = 1.0 + portionWithMovement * (airDrag - 1.0);
            } else {
                gravityCompensation = 0.0;
                effectiveDrag = 1.0;
            }
            outY = (gravityCompensation - curY) * effectiveDrag * restitution;
        }
        setDelta(p, outX, outY, outZ);
    }

    // ------------------------------------------------------------------------------------------------ pose

    /** Player.updatePlayerPose (Player.java:343-357) with getDesiredPose (:359-371). */
    private void updatePlayerPose(final SimPlayer p) {
        if (this.canPlayerFitWithinBlocksAndEntitiesWhen(p, Pose.SWIMMING)) {
            final Pose desiredPose;
            if (p.sleeping) {
                desiredPose = Pose.SLEEPING;
            } else if (p.isSwimming()) {
                desiredPose = Pose.SWIMMING;
            } else if (p.fallFlying) {
                desiredPose = Pose.FALL_FLYING;
            } else if (p.autoSpinAttackTicks > 0) {
                desiredPose = Pose.SPIN_ATTACK;
            } else {
                desiredPose = p.isShiftKeyDown() && !p.flying ? Pose.CROUCHING : Pose.STANDING;
            }
            final Pose actualPose;
            if (p.spectator || p.passenger || this.canPlayerFitWithinBlocksAndEntitiesWhen(p, desiredPose)) {
                actualPose = desiredPose;
            } else if (this.canPlayerFitWithinBlocksAndEntitiesWhen(p, Pose.CROUCHING)) {
                actualPose = Pose.CROUCHING;
            } else {
                actualPose = Pose.SWIMMING;
            }
            p.setPose(actualPose);
        }
    }

    /**
     * {@code Entity.collide(movement)} for the player's current box, step height and ground state: the allowed movement
     * into {@code out[0..2]}. For tools and tests (the collision fuzzer runs it against vanilla); {@link #tick} uses the
     * same code.
     */
    public void collide(final SimPlayer p, final SimWorld world, final double mx, final double my, final double mz, final double[] out) {
        this.collision.world(world);
        this.collision.collide(p, mx, my, mz, (float) p.attributes.stepHeight.value(), out);
    }

    /**
     * The movement the last tick's {@code Entity.move} added to the position ({@code Entity.collide}'s result, or the
     * delta itself with no physics) into {@code out[0..2]}: the exact move, which {@code to - from} only gives up to the
     * position's rounding. For tools and tests ({@link #velocityAfterExactMove}).
     */
    public void lastMove(final double[] out) {
        out[0] = this.lastMoveX;
        out[1] = this.lastMoveY;
        out[2] = this.lastMoveZ;
    }

    // ================================================================================================ velocity after a move

    /** {@link #velocityAfterMove}: the x velocity is not determined (it may differ from {@code out[0]}). */
    public static final int VELOCITY_X = 1;
    /** {@link #velocityAfterMove}: the y velocity is not determined. */
    public static final int VELOCITY_Y = 1 << 1;
    /** {@link #velocityAfterMove}: the z velocity is not determined. */
    public static final int VELOCITY_Z = 1 << 2;
    public static final int VELOCITY_AXES = VELOCITY_X | VELOCITY_Y | VELOCITY_Z;
    /**
     * Why: the move only reaches the server as {@code to - from}, and {@code from + move} rounded to the position's ulp,
     * so the move's last bits (and the velocity that carries them) are lost. Off by at most half an ulp of {@code to}
     * per axis, times the after-move drag. Never set by {@link #velocityAfterExactMove}.
     */
    public static final int VELOCITY_ROUNDED = 1 << 3;
    /**
     * Why: an axis that did not move can still hold a velocity the move cannot show: {@code -0.0}, under 1e-7 (collide
     * returns 0 for it once anything is in reach), or, when another axis collided, anything under 3.2e-4 (Entity.move
     * does not apply a move that short, so the position stays). Off by less than 3.2e-4.
     */
    public static final int VELOCITY_UNMOVED = 1 << 4;
    /**
     * Why: a collision may have cut the move (a wall, the floor, a ceiling, a step), and what collide was given, the
     * velocity before the move, then stays unknown: whether the axis collided at all (velocity 0 or the move), a bounce
     * off slime or a bed, a cut under Mth.equal's 1e-5, or a stepped move.
     */
    public static final int VELOCITY_COLLIDED = 1 << 5;
    /** Why: sneaking at an edge may have shortened the move (Player.maybeBackOffFromEdge), not the velocity. */
    public static final int VELOCITY_EDGE = 1 << 6;
    /**
     * Why: the tick's own keys decide, and they are not part of the state the tick started from: sprinting in water or
     * lava (slow-down, falling adjustment), jumping on a climbable, sneaking on a bounce, a double-tap flight or a glide
     * started this tick.
     */
    public static final int VELOCITY_KEYS = 1 << 7;
    /** Why: a rule after the move reads a velocity from before it that the move does not carry: creative flight while gliding or stuck. */
    public static final int VELOCITY_PRE_MOVE = 1 << 8;
    /** Why: no tick from this state makes this move here (an exempt state, a position this tick cannot reach), so no value. */
    public static final int VELOCITY_NO_TICK = 1 << 9;

    /**
     * The velocity ({@code deltaMovement}) a vanilla 26.3 client holds at the end of a tick whose move went from
     * {@code from} to {@code to}, worked out from the rest of the state the tick started from: the belief velocity of
     * authoritative.md §6.2, and the seed's velocity after one claimed move (auth-sim-requests.md §1).
     * <p>
     * It runs the port's own rules, nothing restated: the tick's start from {@code from} ({@link #startTick}), its aiStep
     * up to the move for every combination of the keys that can change the state the move starts from, then the
     * after-move half of the tick ({@link #afterCollide}: collision flags with Mth.equal, the support, fall, restitution
     * and the block speed factor; the travel branch's climb, gravity or levitation, drag and friction, the fluid slow-downs
     * and jumpOutOfFluid; Player.travel's flight rule; applyEffectsFromBlocks). The one unknown it cannot run is what
     * collide was given, the velocity before the move: it tries every reading the world allows (the move itself, cut by
     * a collision near or far, stepped up, a hidden signed zero or a sub-threshold move), keeps the ones the port's own
     * collide turns into exactly this move, and returns determined only for axes on which all of them, under every key
     * combination, give the same bits. {@link MoveVelocity} has the details.
     * <p>
     * {@code p} is the client's state at the start of the tick, as {@link #tick} would get it (after the between-tick
     * changes the client applied); its position, velocity and rotation are never read, and it is not modified. The
     * result is the velocity at the end of the player's own tick, before any attached rocket boosts it
     * ({@link #fireworkBoost}, once per rocket, as after {@link #tick}).
     * <p>
     * {@code from} and {@code to} must be the two ends of one client tick, as the client holds them. A tick the client
     * sent no position for (a move under 2.0E-4 from the last position it sent, without the 20-tick reminder;
     * LocalPlayer.sendPosition) has no {@code to}, and the tick after it then has no exact {@code from}: neither has an
     * answer here, the caller has to skip them.
     * <p>
     * Returns 0 when {@code out[0..2]} is the client's velocity bit for bit. Otherwise {@link #VELOCITY_X}/{@code Y}/
     * {@code Z} mark the axes that are not, and the {@code VELOCITY_*} reason bits say why; {@code out} then holds the
     * most likely value (the keys held as before the tick; a move that ended against something was cut, one that did not
     * move stood still, a player on the ground pressed down on it), or NaN with {@link #VELOCITY_NO_TICK}.
     * {@link #VELOCITY_ROUNDED} and {@link #VELOCITY_UNMOVED} are bounded (see each); the others are not.
     * {@link #VELOCITY_KEYS} only comes from not knowing the tick's keys: the overload that takes them has none.
     * Allocation-free after the first call (which allocates its scratch players).
     */
    public int velocityAfterMove(final SimPlayer p, final double fromX, final double fromY, final double fromZ, final double toX, final double toY,
                                 final double toZ, final SimWorld world, final double[] out) {
        return this.moveVelocity().after(p, null, fromX, fromY, fromZ, toX, toY, toZ, false, world, out);
    }

    /**
     * {@link #velocityAfterMove} knowing the keys the client sent for the tick ({@code ServerboundPlayerInputPacket}, as
     * {@link #tick} takes them): the state the move starts from is then the one the client had, so
     * {@link #VELOCITY_KEYS} never shows (water and lava sprint, a glide or a flight started this tick, jumping on a
     * climbable, sneaking on a bounce all become exact).
     */
    public int velocityAfterMove(final SimPlayer p, final Input keys, final double fromX, final double fromY, final double fromZ, final double toX,
                                 final double toY, final double toZ, final SimWorld world, final double[] out) {
        return this.moveVelocity().after(p, keys, fromX, fromY, fromZ, toX, toY, toZ, false, world, out);
    }

    /**
     * {@link #velocityAfterMove} for a move known exactly ({@code Entity.collide}'s result, as {@link #lastMove} gives
     * it), so the position's rounding hides nothing ({@link #VELOCITY_ROUNDED} never set). For tools and tests: it shows
     * the rules are exact where the positions alone cannot be.
     */
    public int velocityAfterExactMove(final SimPlayer p, final double fromX, final double fromY, final double fromZ, final double moveX,
                                      final double moveY, final double moveZ, final SimWorld world, final double[] out) {
        return this.moveVelocity().after(p, null, fromX, fromY, fromZ, moveX, moveY, moveZ, true, world, out);
    }

    private MoveVelocity moveVelocity() {
        if (this.moveVelocity == null) {
            this.moveVelocity = new MoveVelocity(this);
        }
        return this.moveVelocity;
    }

    /**
     * Runs {@code p}'s aiStep with these keys up to its move and copies the state the move starts from into {@code into}
     * (the travel branch's pre-move values into {@link #probeTravel}). Returns false when the tick made no move;
     * {@code p} is left mid-tick.
     */
    boolean probeMove(final SimPlayer p, final SimTickInput in, final SimWorld world, final SimPlayer into) {
        this.probe = into;
        this.localAiStep(p, in, world);
        final boolean moved = this.probe == null;
        this.probe = null;
        return moved;
    }

    // ================================================================================================ client actions

    /**
     * The client's own prediction of its attack (MultiPlayerGameMode.attack → Player.attack, Player.java:933-989, and
     * causeExtraKnockback, :1104-1131): a sprinting, full-strength hit on a target the client counts as hurt (only remote
     * players: {@code RemotePlayer.hurtClient} is true, mobs false) multiplies the horizontal velocity by 0.6 and stops
     * sprinting. The client's knockback attribute is unsynced (0), so the sprint bonus is the only knockback. Then the
     * attack strength ticker resets. Call once per {@code ServerboundAttackPacket}, before the tick.
     */
    public void clientAttack(final SimPlayer p, final boolean targetIsPlayer) {
        // getAttackStrengthScale(0.5F) (Player.java:1799-1801) before onAttack resets the ticker
        final float delay = (float) (1.0 / p.attributes.attackSpeed.value() * 20.0);
        final float attackStrengthScale = Mth.clamp((p.attackStrengthTicker + 0.5F) / delay, 0.0F, 1.0F);
        p.attackStrengthTicker = 0; // onAttack → resetOnlyAttackStrengthTicker
        final boolean fullStrengthAttack = attackStrengthScale > 0.9F;
        final boolean knockbackAttack = p.sprinting && fullStrengthAttack;
        if (targetIsPlayer) {
            final float knockback = (float) p.attributes.attackKnockback.value() + (knockbackAttack ? 0.5F : 0.0F);
            if (knockback > 0.0F) {
                setDelta(p, p.vx * 0.6, p.vy * 1.0, p.vz * 0.6);
                setSprinting(p, false);
            }
        }
        // MultiPlayerGameMode.attack: resetAttackStrengthTicker
        p.attackStrengthTicker = 0;
        p.itemSwapTicker = 0;
    }

    /**
     * LocalPlayer.startUsingItem (LocalPlayer.java:563-570): the client starts using the stack in {@code hand} when it is
     * not empty and it is not using one already ({@code useDuration} is {@code stack.getUseDuration(player)}, which only
     * counts down here).
     */
    public void startUsingItem(final SimPlayer p, final InteractionHand hand, final int useDuration) {
        final ItemStack stack = hand(p, hand);
        if (!stack.isEmpty() && !p.usingItem) {
            p.useItem = stack;
            p.useItemRemaining = useDuration;
            p.usingItem = true;
            p.usingHand = hand;
        }
    }

    /** MultiPlayerGameMode.releaseUsingItem → LivingEntity.releaseUsingItem (LivingEntity.java:3615-3626): stops. */
    public void releaseUsingItem(final SimPlayer p) {
        final ItemStack inHand = hand(p, p.usingHand);
        if (!p.useItem.isEmpty() && ItemStack.isSameItem(inHand, p.useItem)) {
            p.useItem = inHand;
        }
        this.stopUsingItem(p);
    }

    /**
     * One client tick of a firework rocket attached to this player (FireworkRocketEntity.tick, FireworkRocketEntity.java:
     * 126-148): while the player glides, its velocity is pulled towards 1.5 blocks per tick along the look vector. The
     * rocket is another entity of the client's level, ticked after the player in the same client tick (ClientLevel's
     * entity list keeps insertion order, and the rocket is added after the player), so call this right after
     * {@link #tick}, once per attached rocket the client holds (from the tick its AddEntity is applied until the tick its
     * RemoveEntities is), in the order they were added. A rocket only reads and writes the velocity, the glide flag and
     * the rotation the tick just used.
     */
    public void fireworkBoost(final SimPlayer p) {
        if (p.fallFlying) {
            // getLookAngle() = calculateViewVector(xRot, yRot) (Entity.java:2031-2039)
            final float rx = p.xRot * 0.017453292F;
            final float ry = -p.yRot * 0.017453292F;
            final float yCos = Mth.cos(ry);
            final float ySin = Mth.sin(ry);
            final float xCos = Mth.cos(rx);
            final float xSin = Mth.sin(rx);
            final double lookX = ySin * xCos;
            final double lookY = -xSin;
            final double lookZ = yCos * xCos;
            // movement.add(look * 0.1 + (look * 1.5 - movement) * 0.5)
            setDelta(p, p.vx + (lookX * 0.1 + (lookX * 1.5 - p.vx) * 0.5), p.vy + (lookY * 0.1 + (lookY * 1.5 - p.vy) * 0.5),
                p.vz + (lookZ * 0.1 + (lookZ * 1.5 - p.vz) * 0.5));
        }
    }

    /** LocalPlayer.stopUsingItem (LocalPlayer.java:586-589): also what the using-item flag echo and entity event 9 do. */
    public void stopUsingItem(final SimPlayer p) {
        p.useItem = ItemStack.EMPTY;
        p.useItemRemaining = 0;
        p.usingItem = false;
    }

    // ================================================================================================ server → client

    /** ClientboundSetEntityMotionPacket → Entity.lerpMotion = setDeltaMovement (only finite values). */
    public void setVelocity(final SimPlayer p, final double x, final double y, final double z) {
        setDelta(p, x, y, z);
    }

    /** Explosion knockback → Entity.push(x, y, z) (Entity.java:1978-1983): adds a finite impulse. */
    public void addVelocity(final SimPlayer p, final double x, final double y, final double z) {
        if (Double.isFinite(x) && Double.isFinite(y) && Double.isFinite(z)) {
            setDelta(p, p.vx + x, p.vy + y, p.vz + z);
        }
    }

    /**
     * ClientboundPlayerPositionPacket as ClientPacketListener.handleMovePlayer applies it (ClientPacketListener.java:
     * 794-829): absolute values from {@code PositionMoveRotation.calculateAbsolute}, then the old position and rotation
     * moved by the same change (setOldPosAndRot). Ignored while riding.
     */
    public void teleport(final SimPlayer p, final PositionMoveRotation change, final Set<Relative> relatives) {
        if (p.passenger) {
            return;
        }
        final PositionMoveRotation current = new PositionMoveRotation(new Vec3(p.x, p.y, p.z), new Vec3(p.vx, p.vy, p.vz), p.yRot, p.xRot);
        final PositionMoveRotation absolute = PositionMoveRotation.calculateAbsolute(current, change, relatives);
        p.setPos(absolute.position().x, absolute.position().y, absolute.position().z);
        setDelta(p, absolute.deltaMovement().x, absolute.deltaMovement().y, absolute.deltaMovement().z);
        if (Float.isFinite(absolute.yRot())) {
            p.yRot = absolute.yRot();
        }
        if (Float.isFinite(absolute.xRot())) {
            p.xRot = Math.clamp(absolute.xRot() % 360.0F, -90.0F, 90.0F); // Entity.setXRot
        }
        final PositionMoveRotation old = new PositionMoveRotation(new Vec3(p.xo, p.yo, p.zo), Vec3.ZERO, p.yRotO, p.xRotO);
        final PositionMoveRotation interpolation = PositionMoveRotation.calculateAbsolute(old, change, relatives);
        p.xo = interpolation.position().x;
        p.yo = interpolation.position().y;
        p.zo = interpolation.position().z;
        p.yRotO = interpolation.yRot();
        p.xRotO = interpolation.xRot();
    }

    /**
     * ClientboundPlayerRotationPacket (ClientPacketListener.java:832-844): the rotation, absolute or relative, then
     * {@code setOldRot}. Position and velocity are untouched.
     */
    public void rotation(final SimPlayer p, final float yRot, final boolean relativeY, final float xRot, final boolean relativeX) {
        final float absoluteY = relativeY ? p.yRot + yRot : yRot;
        final float absoluteX = Mth.clamp((relativeX ? p.xRot : 0.0F) + xRot, -90.0F, 90.0F); // calculateAbsolute
        if (Float.isFinite(absoluteY)) {
            p.yRot = absoluteY;
        }
        if (Float.isFinite(absoluteX)) {
            p.xRot = Math.clamp(absoluteX % 360.0F, -90.0F, 90.0F);
        }
        p.yRotO = p.yRot;
        p.xRotO = p.xRot;
    }

    /**
     * Entity.absSnapTo(x, y, z) (Entity.java:1843-1850): the client's block-prediction revert snap
     * (ClientLevel.syncBlockState). Position and old position; velocity kept.
     */
    public void snapTo(final SimPlayer p, final double x, final double y, final double z) {
        final double cx = Mth.clamp(x, -3.0E7, 3.0E7);
        final double cz = Mth.clamp(z, -3.0E7, 3.0E7);
        p.xo = cx;
        p.yo = y;
        p.zo = cz;
        p.setPos(cx, y, cz);
    }

    /**
     * The using-item flags of a self entity-data echo, as LocalPlayer.onSyncedDataUpdated applies them
     * (LocalPlayer.java:597-607): the server says using and the client is not → it starts with that hand; the server
     * says not using and the client is → it stops.
     */
    public void echoUsingItem(final SimPlayer p, final boolean using, final InteractionHand hand, final int useDuration) {
        if (using && !p.usingItem) {
            this.startUsingItem(p, hand, useDuration);
        } else if (!using && p.usingItem) {
            this.stopUsingItem(p);
        }
    }

    /**
     * Entity event 9 for self → LivingEntity.completeUsingItem on the client (LivingEntity.java:3578-3594): when using,
     * either branch ends in {@code stopUsingItem}.
     */
    public void completeUsingItem(final SimPlayer p) {
        if (p.usingItem) {
            this.stopUsingItem(p);
        }
    }

    /**
     * One attribute of {@code ClientboundUpdateAttributesPacket} for the player itself (ClientPacketListener.java:
     * 2368-2392). A {@code SCALE} update refreshes the dimensions at the end of the next tick.
     */
    public void applyAttributeUpdate(final SimPlayer p, final Holder<Attribute> attribute, final double base, final List<AttributeModifier> modifiers) {
        p.attributes.applyUpdate(attribute, base, modifiers);
        if (attribute == Attributes.SCALE) {
            p.scaleDirty = true;
        }
    }

    /** ClientboundUpdateMobEffectPacket: the client replaces the effect; only presence and amplifier matter. */
    public void addEffect(final SimPlayer p, final Holder<MobEffect> effect, final int amplifier) {
        p.addEffect(effect, amplifier);
    }

    /** ClientboundRemoveMobEffectPacket. */
    public void removeEffect(final SimPlayer p, final Holder<MobEffect> effect) {
        p.removeEffect(effect);
    }

    /** ClientboundPlayerAbilitiesPacket (ClientPacketListener.java:2102-2111). */
    public void setAbilities(final SimPlayer p, final boolean flying, final boolean mayfly, final boolean instabuild, final float flyingSpeed) {
        p.flying = flying;
        p.mayfly = mayfly;
        p.instabuild = instabuild;
        p.flyingSpeed = flyingSpeed;
    }

    /**
     * The tab-list game mode of the player itself ({@code ClientboundPlayerInfoUpdatePacket} UPDATE_GAME_MODE,
     * ClientPacketListener.applyPlayerInfoUpdate): what {@code Player.isSpectator()} reads (no physics, no climbing).
     * A change calls LocalPlayer.onGameModeChanged (LocalPlayer.java:1200-1204), which zeroes the vertical velocity
     * when the new mode is spectator.
     */
    public void setTabListGameMode(final SimPlayer p, final GameType mode) {
        final boolean spectator = mode == GameType.SPECTATOR;
        if (spectator != p.spectator && spectator) {
            setDelta(p, p.vx, 0.0, p.vz);
        }
        p.spectator = spectator;
    }

    /**
     * The local game mode ({@code ClientboundGameEventPacket} CHANGE_GAME_MODE → MultiPlayerGameMode.setLocalMode):
     * {@code GameType.updatePlayerAbilities} (GameType.java:68-86), and a spectator local mode forces flight in aiStep.
     */
    public void setLocalGameMode(final SimPlayer p, final GameType mode) {
        p.localSpectator = mode == GameType.SPECTATOR;
        if (mode == GameType.CREATIVE) {
            p.mayfly = true;
            p.instabuild = true;
        } else if (mode == GameType.SPECTATOR) {
            p.mayfly = true;
            p.instabuild = false;
            p.flying = true;
        } else {
            p.mayfly = false;
            p.instabuild = false;
            p.flying = false;
        }
    }

    /**
     * Compatibility shim for callers that only see the local mode change: marks the local spectator mode and, when the
     * player was not a (tab-list) spectator yet, zeroes the vertical velocity the way the accompanying tab-list update
     * does (onGameModeChanged). Prefer {@link #setLocalGameMode} and {@link #setTabListGameMode}, each at its own ack.
     */
    public void setLocalGameModeSpectator(final SimPlayer p, final boolean spectator) {
        if (spectator && !p.spectator) {
            setDelta(p, p.vx, 0.0, p.vz);
        }
        p.localSpectator = spectator;
    }

    /**
     * The shared flags byte as the client takes it from a self {@code SetEntityData} echo: sprint (3), swim (4) and
     * glide (7) are replaced (the shift bit is ignored: LocalPlayer reads its keys). The echo goes through
     * {@code SynchedEntityData}, not {@code setSprinting}, so the movement-speed modifier is left as it was.
     */
    public void applySharedFlags(final SimPlayer p, final byte flags) {
        p.sprinting = (flags & 1 << 3) != 0;
        p.swimming = (flags & 1 << 4) != 0;
        p.fallFlying = (flags & 1 << 7) != 0;
    }

    /** A self {@code DATA_POSE} echo: the pose, and the dimensions refreshed at once (Entity.onSyncedDataUpdated). */
    public void applyPose(final SimPlayer p, final Pose pose) {
        p.setPose(pose);
    }

    // ================================================================================================ helpers

    /**
     * LivingEntity.setSprinting (LivingEntity.java:2313-2320): the flag, and the {@code sprinting} modifier removed and
     * re-added, which is the client's own attribute history.
     */
    public static void setSprinting(final SimPlayer p, final boolean sprinting) {
        p.sprinting = sprinting;
        p.attributes.movementSpeed.removeModifier(SimAttributes.SPRINTING.id());
        if (sprinting) {
            p.attributes.movementSpeed.addTransientModifier(SimAttributes.SPRINTING);
        }
    }

    /** Entity.setDeltaMovement: non-finite vectors are dropped (Entity.java:3899-3903). */
    static void setDelta(final SimPlayer p, final double x, final double y, final double z) {
        if (Double.isFinite(x) && Double.isFinite(y) && Double.isFinite(z)) {
            p.vx = x;
            p.vy = y;
            p.vz = z;
        }
    }

    /** Entity.addDeltaMovement: a finite momentum added with Vec3.add. */
    static void addDelta(final SimPlayer p, final double x, final double y, final double z) {
        if (Double.isFinite(x) && Double.isFinite(y) && Double.isFinite(z)) {
            setDelta(p, p.vx + x, p.vy + y, p.vz + z);
        }
    }

    private static ItemStack hand(final SimPlayer p, final InteractionHand hand) {
        return hand == InteractionHand.MAIN_HAND ? p.mainHand : p.offHand;
    }
}
