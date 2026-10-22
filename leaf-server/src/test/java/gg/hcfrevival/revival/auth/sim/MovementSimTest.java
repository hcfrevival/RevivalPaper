package gg.hcfrevival.revival.auth.sim;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import gg.hcfrevival.revival.RevivalRegistries;
import gg.hcfrevival.revival.RevivalTest;
import java.util.EnumSet;
import java.util.List;
import net.minecraft.core.Direction;
import net.minecraft.util.Mth;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.effect.MobEffects;
import net.minecraft.world.entity.Pose;
import net.minecraft.world.entity.PositionMoveRotation;
import net.minecraft.world.entity.Relative;
import net.minecraft.world.entity.ai.attributes.Attributes;
import net.minecraft.world.entity.player.Input;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.LadderBlock;
import net.minecraft.world.phys.Vec3;
import org.junit.jupiter.api.Test;

/**
 * The movement port, one mechanic per test, against values worked out from the vanilla 26.3 formulas (source lines in
 * the comments). {@link MovementSimGoldenTest} checks whole scenes against vanilla's own code.
 */
@RevivalTest
@RevivalRegistries
class MovementSimTest {

    private static final Input NONE = Input.EMPTY;
    private static final Input W = new Input(true, false, false, false, false, false, false);
    private static final Input WR = new Input(true, false, false, false, false, false, true);
    private static final Input WJR = new Input(true, false, false, false, true, false, true);
    private static final Input J = new Input(false, false, false, false, true, false, false);
    private static final Input WC = new Input(true, false, false, false, false, true, false);
    private static final Input WA = new Input(true, false, true, false, false, false, false);

    private final MovementSim sim = new MovementSim();
    private final SimTickInput in = new SimTickInput();

    private static SimTestWorld stone() {
        return new SimTestWorld().floor(63, Blocks.STONE.defaultBlockState());
    }

    private static SimPlayer at(final double x, final double y, final double z) {
        final SimPlayer p = new SimPlayer();
        p.setPos(x, y, z);
        return p;
    }

    private void tick(final SimPlayer p, final SimWorld world, final Input keys, final float yaw) {
        assertEquals(ExemptReason.NONE, this.sim.tick(p, this.in.set(keys, yaw, 0.0F), world));
    }

    /** A player standing on the floor, settled: two still ticks. */
    private SimPlayer standing(final SimWorld world) {
        final SimPlayer p = at(0.5, 64.0, 0.5);
        this.tick(p, world, NONE, 0.0F);
        this.tick(p, world, NONE, 0.0F);
        assertTrue(p.onGround);
        return p;
    }

    /** LocalPlayer.modifyInputSpeedForSquareMovement (LocalPlayer.java:740-757): returns {x, y}. */
    private static float[] squareFix(final float x, final float y) {
        final float length = Mth.sqrt(x * x + y * y);
        if (length <= 0.0F) {
            return new float[] {x, y};
        }
        final float dx = x * (1.0F / length);
        final float dy = y * (1.0F / length);
        final float ax = Math.abs(dx);
        final float ay = Math.abs(dy);
        final float tan = ay > ax ? ax / ay : ay / ax;
        final float modified = Math.min(length * Mth.sqrt(1.0F + tan * tan), 1.0F);
        return new float[] {dx * modified, dy * modified};
    }

    // LivingEntity.computeModifiedFriction (LivingEntity.java:528-530)
    private static float modified(final float friction, final float modifier) {
        return Mth.clamp(1.0F - (1.0F - friction) * modifier, 0.0F, 1.0F);
    }

    @Test
    void standingPlayerSettlesAtMinus00784() {
        // travelInAir (LivingEntity.java:2464-2491): after the floor zeroes y, (0 - 0.08) × computeModifiedFriction(0.98F, 1)
        final SimPlayer p = this.standing(stone());
        assertEquals((0.0 - 0.08) * modified(0.98F, 1.0F), p.vy);
        assertEquals(-0.0784000015258789, p.vy);
        assertEquals(64.0, p.y);
        assertEquals(63, p.supportY);
    }

    @Test
    void aMissingChunkKeepsGravityAndHoldsNothing() {
        // travelInAir falls at -0.1 over a chunk only where !level.hasChunkAt(posBelow) (LivingEntity.java:2474), and
        // ClientLevel.hasChunk is always true (ClientLevel.java:519-522): a 26.3 client keeps normal gravity over a chunk it
        // does not have, and reads its blocks as void air (EmptyLevelChunk), so they hold nothing up. Seen live: after a
        // 218-block teleport the client fell at -0.0784 over void air until the chunk arrived two ticks later
        final SimTestWorld world = stone().unload(0, 0);
        final SimPlayer p = at(0.5, 64.0, 0.5);
        this.tick(p, world, NONE, 0.0F);
        assertEquals((0.0 - 0.08) * modified(0.98F, 1.0F), p.vy); // -0.0784, not -0.1 × 0.98F
        this.tick(p, world, NONE, 0.0F);
        assertFalse(p.onGround);
        assertEquals(64.0 + -0.0784000015258789, p.y); // through the floor the client does not have
        assertEquals((-0.0784000015258789 - 0.08) * modified(0.98F, 1.0F), p.vy);
    }

    @Test
    void firstWalkingTickOnStone() {
        final SimTestWorld world = stone();
        final SimPlayer p = this.standing(world);
        this.tick(p, world, W, 0.0F);
        // modifyInput: (0, 1) × 0.98F, then the square fix (LocalPlayer.java:722-757)
        final float zza = squareFix(0.0F, 0.98F)[1];
        assertEquals(zza, p.zza);
        // getFrictionInfluencedSpeed on stone (friction 0.6F): speed × 0.21600002F / f³ (LivingEntity.java:2718-2724)
        final float f = modified(0.6F, 1.0F);
        final float speed = 0.1F * (0.21600002F / (f * f * f));
        // moveRelative at yaw 0: z += zza × speed (Entity.java:1812-1822)
        final double moved = (double) zza * speed;
        assertEquals(0.5 + moved, p.z);
        // then x/z × friction × airDrag (0.6F × 0.91F)
        assertEquals(moved * (f * modified(0.91F, 1.0F)), p.vz);
        assertEquals(64.0, p.y);
    }

    @Test
    void sprintAddsTheSprintingModifier() {
        final SimTestWorld world = stone();
        final SimPlayer p = this.standing(world);
        this.tick(p, world, WR, 0.0F);
        assertTrue(p.sprinting);
        // LivingEntity.SPEED_MODIFIER_SPRINTING: ×(1 + 0.30000001192092896) on movement speed (LivingEntity.java:158-161)
        assertEquals(0.10000000149011612 * (1.0 + 0.30000001192092896), p.attributes.movementSpeed.value());
        this.tick(p, world, NONE, 0.0F);
        assertFalse(p.sprinting); // no forward impulse stops the run (LocalPlayer.java:937-941)
        assertEquals(0.10000000149011612, p.attributes.movementSpeed.value());
    }

    @Test
    void jumpUsesJumpStrength() {
        final SimTestWorld world = stone();
        final SimPlayer p = this.standing(world);
        this.tick(p, world, J, 0.0F);
        // jumpFromGround: y = max(jumpPower, y) with jumpPower = (float) 0.41999998688697815 (LivingEntity.java:2385-2397)
        assertEquals(64.0 + 0.41999998688697815, p.y);
        assertEquals((0.41999998688697815 - 0.08) * modified(0.98F, 1.0F), p.vy);
        assertEquals(10, p.noJumpDelay);
        assertFalse(p.onGround);
    }

    @Test
    void sprintJumpAddsTheBoostAlongTheYaw() {
        final SimTestWorld world = stone();
        final SimPlayer p = this.standing(world);
        this.tick(p, world, WR, 30.0F);
        final double vx = p.vx;
        final double vz = p.vz;
        final double x = p.x;
        final double z = p.z;
        this.tick(p, world, WJR, 30.0F);
        // velocity floor first, then the jump adds (-sin(yaw) × 0.2, cos(yaw) × 0.2) before moveRelative
        final float angle = 30.0F * 0.017453292F;
        final double boostX = -Mth.sin(angle) * 0.2;
        final double boostZ = Mth.cos(angle) * 0.2;
        final float f = modified(0.6F, 1.0F);
        final float speed = (float) (0.10000000149011612 * (1.0 + 0.30000001192092896)) * (0.21600002F / (f * f * f));
        final float sin = Mth.sin(30.0F * 0.017453292F);
        final float cos = Mth.cos(30.0F * 0.017453292F);
        final double mz = (double) squareFix(0.0F, 0.98F)[1] * speed;
        final double expectedX = x + (vx + boostX + (0.0 * cos - mz * sin));
        final double expectedZ = z + (vz + boostZ + (mz * cos + 0.0 * sin));
        assertEquals(expectedX, p.x);
        assertEquals(expectedZ, p.z);
        assertEquals(64.0 + 0.41999998688697815, p.y);
    }

    @Test
    void gravityAttributeSetsTheFall() {
        final SimTestWorld world = stone();
        final SimPlayer p = at(0.5, 64.0, 0.5);
        this.sim.applyAttributeUpdate(p, Attributes.GRAVITY, 0.1, List.of());
        this.tick(p, world, NONE, 0.0F);
        this.tick(p, world, NONE, 0.0F);
        assertEquals((0.0 - 0.1) * modified(0.98F, 1.0F), p.vy);
    }

    @Test
    void frictionAndAirDragModifiers() {
        final SimTestWorld world = stone();
        final SimPlayer p = this.standing(world);
        this.sim.applyAttributeUpdate(p, Attributes.FRICTION_MODIFIER, 0.5, List.of());
        this.sim.applyAttributeUpdate(p, Attributes.AIR_DRAG_MODIFIER, 2.0, List.of());
        this.tick(p, world, W, 0.0F);
        final float f = modified(0.6F, 0.5F); // 0.8F: slipperier ground
        final float speed = 0.1F * (0.21600002F / (f * f * f));
        final double moved = (double) squareFix(0.0F, 0.98F)[1] * speed;
        assertEquals(moved * (f * modified(0.91F, 2.0F)), p.vz);
        assertEquals((0.0 - 0.08) * modified(0.98F, 2.0F), p.vy);
    }

    @Test
    void soulSandSlowsAndHoneyHalvesTheJump() {
        final SimTestWorld world = new SimTestWorld().floor(63, Blocks.SOUL_SAND.defaultBlockState());
        final SimPlayer p = at(0.5, 63.875, 0.5);
        this.tick(p, world, NONE, 0.0F);
        this.tick(p, world, NONE, 0.0F);
        this.tick(p, world, W, 0.0F);
        // Entity.move: delta × getBlockSpeedFactor() (soul sand 0.4F) after the move (Entity.java:832-834)
        final float f = modified(0.6F, 1.0F);
        final double moved = (double) squareFix(0.0F, 0.98F)[1] * (0.1F * (0.21600002F / (f * f * f)));
        assertEquals(moved * 0.4F * (f * modified(0.91F, 1.0F)), p.vz);

        final SimTestWorld honey = new SimTestWorld().floor(63, Blocks.HONEY_BLOCK.defaultBlockState());
        final SimPlayer h = at(0.5, 63.9375, 0.5);
        this.tick(h, honey, NONE, 0.0F);
        this.tick(h, honey, NONE, 0.0F);
        assertTrue(h.onGround);
        this.tick(h, honey, J, 0.0F);
        // getJumpPower × getBlockJumpFactor (honey 0.5F)
        assertEquals(63.9375 + (double) (0.41999998688697815F * 0.5F), h.y);
    }

    @Test
    void jumpBoostAddsAPointOnePerLevel() {
        final SimTestWorld world = stone();
        final SimPlayer p = this.standing(world);
        this.sim.addEffect(p, MobEffects.JUMP_BOOST, 1);
        this.tick(p, world, J, 0.0F);
        // getJumpBoostPower: 0.1F × (amplifier + 1.0F)
        assertEquals(64.0 + (double) ((float) 0.41999998688697815 * 1.0F * 1.0F + 0.1F * (1 + 1.0F)), p.y);
    }

    @Test
    void tinyHorizontalVelocityFloorsToZero() {
        final SimTestWorld world = stone();
        final SimPlayer p = this.standing(world);
        this.sim.setVelocity(p, 0.002, -0.0784000015258789, 0.002);
        this.tick(p, world, NONE, 0.0F);
        // players: horizontalDistanceSqr < 9.0E-6 zeroes x and z (LivingEntity.java:3059-3078)
        assertEquals(0.5, p.x);
        assertEquals(0.5, p.z);
    }

    @Test
    void sprintingFullStrengthHitSlowsAndStopsSprint() {
        final SimTestWorld world = stone();
        final SimPlayer p = this.standing(world);
        for (int i = 0; i < 6; i++) {
            this.tick(p, world, WR, 0.0F);
        }
        final double vz = p.vz;
        this.sim.clientAttack(p, true);
        // Player.causeExtraKnockback: delta × (0.6, 1, 0.6), setSprinting(false) (Player.java:1104-1131)
        assertEquals(vz * 0.6, p.vz);
        assertFalse(p.sprinting);
        assertEquals(0.10000000149011612, p.attributes.movementSpeed.value());
        assertEquals(0, p.attackStrengthTicker);
        // a second hit at once is not full strength: no slowdown
        MovementSim.setSprinting(p, true);
        final double again = p.vz;
        this.sim.clientAttack(p, true);
        assertEquals(again, p.vz);
        assertTrue(p.sprinting);
        // a mob target is not hurt on the client: no slowdown either
        p.attackStrengthTicker = 20;
        this.sim.clientAttack(p, false);
        assertEquals(again, p.vz);
    }

    @Test
    void usingAnItemScalesTheInput() {
        final SimTestWorld world = stone();
        final SimPlayer p = this.standing(world);
        p.mainHand = new ItemStack(Items.BOW);
        this.sim.startUsingItem(p, InteractionHand.MAIN_HAND, 72000);
        this.tick(p, world, W, 0.0F);
        // modifyInput: × USE_EFFECTS.speedMultiplier (default 0.2F) after × 0.98F
        assertEquals(squareFix(0.0F, 0.98F * 0.2F)[1], p.zza);
        assertEquals(71999, p.useItemRemaining);
        this.sim.releaseUsingItem(p);
        assertFalse(p.usingItem);
    }

    @Test
    void sneakingScalesTheNextTicksInput() {
        final SimTestWorld world = stone();
        final SimPlayer p = this.standing(world);
        this.tick(p, world, WC, 0.0F);
        // crouching is decided from the previous tick's shift key (LocalPlayer.java:797-801)
        assertEquals(squareFix(0.0F, 0.98F)[1], p.zza);
        assertEquals(Pose.CROUCHING, p.pose);
        assertEquals(1.5F, p.height);
        assertEquals(1.27F, p.eyeHeight);
        this.tick(p, world, WC, 0.0F);
        assertEquals(squareFix(0.0F, 0.98F * (float) 0.3)[1], p.zza);
    }

    @Test
    void diagonalInputIsSquaredOut() {
        final SimTestWorld world = stone();
        final SimPlayer p = this.standing(world);
        this.tick(p, world, WA, 0.0F);
        // Vec2(1, 1).normalized() × 0.98F, then modifyInputSpeedForSquareMovement: min(length × sqrt(1 + tan²), 1)
        final float dist = Mth.sqrt(1.0F * 1.0F + 1.0F * 1.0F);
        final float[] fixed = squareFix(1.0F / dist * 0.98F, 1.0F / dist * 0.98F);
        assertEquals(fixed[0], p.xxa);
        assertEquals(fixed[1], p.zza);
    }

    @Test
    void sneakingStopsAtTheEdge() {
        final SimTestWorld world = new SimTestWorld().set(0, 63, 0, Blocks.STONE.defaultBlockState()).floor(50, Blocks.STONE.defaultBlockState());
        final SimPlayer p = at(0.5, 64.0, 0.5);
        this.tick(p, world, NONE, 0.0F);
        this.tick(p, world, NONE, 0.0F);
        for (int i = 0; i < 40; i++) {
            this.tick(p, world, WC, 0.0F);
        }
        // Player.maybeBackOffFromEdge keeps the box over the block (Player.java:864-911)
        assertEquals(64.0, p.y);
        assertTrue(p.z < 1.3 + 1.0E-7);
        assertTrue(p.onGround);
    }

    @Test
    void ladderClimbsAtPointTwo() {
        final SimTestWorld world = stone();
        for (int y = 64; y < 70; y++) {
            world.set(0, y, 1, Blocks.STONE.defaultBlockState());
            world.set(0, y, 0, Blocks.LADDER.defaultBlockState().setValue(LadderBlock.FACING, Direction.NORTH));
        }
        final SimPlayer p = at(0.5, 64.0, 0.5);
        this.tick(p, world, NONE, 0.0F);
        for (int i = 0; i < 6; i++) {
            this.tick(p, world, W, 0.0F);
        }
        // pushing into the wall on a ladder: y = 0.2, then (0.2 - 0.08) × 0.98F (LivingEntity.java:2678-2681)
        assertTrue(p.horizontalCollision);
        assertEquals((0.2 - 0.08) * modified(0.98F, 1.0F), p.vy);
        assertEquals(0.0, p.fallDistance);
    }

    @Test
    void fallDistanceAccumulatesAndLandingResetsIt() {
        final SimTestWorld world = stone();
        final SimPlayer p = at(0.5, 70.0, 0.5);
        double expected = 0.0;
        for (int i = 0; i < 5; i++) {
            // nothing is hit in the air, so the move is the velocity after the floor (LivingEntity.java:3059-3078)
            final double ya = Math.abs(p.vy) < 0.003 ? 0.0 : p.vy;
            this.tick(p, world, NONE, 0.0F);
            // Entity.checkFallDamage: fallDistance -= (float) ya (Entity.java:1605-1610)
            if (ya < 0.0) {
                expected -= (float) ya;
            }
        }
        assertEquals(expected, p.fallDistance);
        for (int i = 0; i < 30; i++) {
            this.tick(p, world, NONE, 0.0F);
        }
        assertTrue(p.onGround);
        assertEquals(0.0, p.fallDistance);
    }

    @Test
    void slimeBouncesUnlessSneaking() {
        final SimTestWorld world = new SimTestWorld().floor(63, Blocks.SLIME_BLOCK.defaultBlockState());
        final SimPlayer p = at(0.5, 66.0, 0.5);
        double maxUp = 0.0;
        for (int i = 0; i < 25; i++) {
            this.tick(p, world, NONE, 0.0F);
            maxUp = Math.max(maxUp, p.vy);
        }
        // restituteMovementAfterCollisions with the block's bounce restitution 1.0 (Entity.java:844-886)
        assertTrue(maxUp > 0.3);
        final SimPlayer sneaking = at(0.5, 66.0, 0.5);
        final Input c = new Input(false, false, false, false, false, true, false);
        double up = 0.0;
        for (int i = 0; i < 25; i++) {
            this.tick(sneaking, world, c, 0.0F);
            up = Math.max(up, sneaking.vy);
        }
        assertEquals(0.0, up);
    }

    @Test
    void cobwebSetsTheStuckMultiplier() {
        final SimTestWorld world = stone().set(0, 64, 0, Blocks.COBWEB.defaultBlockState());
        final SimPlayer p = at(0.5, 64.0, 0.5);
        this.tick(p, world, NONE, 0.0F);
        // WebBlock.entityInside → makeStuckInBlock((0.25, 0.05000000074505806, 0.25)) (WebBlock.java:19-28)
        assertEquals(0.25, p.stuckX);
        assertEquals(0.05000000074505806, p.stuckY);
        this.tick(p, world, NONE, 0.0F);
        assertEquals(0.25, p.stuckX); // consumed by the move, set again by the walk
    }

    @Test
    void waterSlowsAndCurrentsPush() {
        final SimTestWorld world = new SimTestWorld().floor(60, Blocks.STONE.defaultBlockState());
        for (int x = -8; x <= 8; x++) {
            for (int z = -8; z <= 8; z++) {
                for (int y = 61; y <= 64; y++) {
                    world.set(x, y, z, Blocks.WATER.defaultBlockState());
                }
            }
        }
        final SimPlayer p = at(0.5, 63.0, 0.5);
        this.tick(p, world, NONE, 0.0F);
        assertTrue(p.isInWater());
        assertTrue(p.fluids.isEyeInWater());
        // still water, falling from rest: travelInWater y × 0.8 then - g/16 (LivingEntity.java:2511-2538, :2686-2699)
        final double afterMove = (0.0 + 0.0) * 0.800000011920929;
        assertEquals(afterMove - 0.08 / 16.0, p.vy);
    }

    @Test
    void teleportAppliesRelativeChanges() {
        final SimPlayer p = at(1.0, 64.0, 2.0);
        p.vx = 0.1;
        p.yRot = 10.0F;
        final PositionMoveRotation change = new PositionMoveRotation(new Vec3(0.5, 1.0, -0.5), new Vec3(0.2, 0.0, 0.0), 5.0F, 0.0F);
        this.sim.teleport(p, change, EnumSet.of(Relative.X, Relative.Y, Relative.Y_ROT, Relative.DELTA_X));
        assertEquals(1.5, p.x);
        assertEquals(65.0, p.y);
        assertEquals(-0.5, p.z);
        assertEquals(0.1 + 0.2, p.vx);
        assertEquals(15.0F, p.yRot);
    }

    @Test
    void ridingAndSleepingAreExempt() {
        final SimPlayer p = at(0.5, 64.0, 0.5);
        p.passenger = true;
        assertEquals(ExemptReason.PASSENGER, this.sim.tick(p, this.in.set(W, 0.0F, 0.0F), stone()));
        assertEquals(64.0, p.y);
        p.passenger = false;
        p.sleeping = true;
        assertEquals(ExemptReason.SLEEPING, this.sim.tick(p, this.in.set(W, 0.0F, 0.0F), stone()));
    }
    // ------------------------------------------------------------------------------------------------ stage 2

    /** Stone floor at 63 and a row of one-block steps at z = 3. */
    private static SimTestWorld step() {
        final SimTestWorld world = stone();
        for (int x = -8; x <= 8; x++) {
            world.set(x, 64, 3, Blocks.STONE.defaultBlockState());
        }
        return world;
    }

    /** Walks a settled player north-south (yaw 0) into the steps until its move predicts an auto-jump. */
    private SimPlayer walkToAutoJump(final SimTestWorld world, final SimPlayer p) {
        for (int t = 0; t < 30 && p.autoJumpTime == 0; t++) {
            this.tick(p, world, WR, 0.0F);
        }
        assertEquals(1, p.autoJumpTime);
        assertTrue(p.onGround);
        return p;
    }

    private static Input withJump(final Input k) {
        return new Input(k.forward(), k.backward(), k.left(), k.right(), true, k.shift(), k.sprint());
    }

    @Test
    void autoJumpPredictsTheStepAndTheSentJumpClimbsIt() {
        // LocalPlayer.updateAutoJump (LocalPlayer.java:1036-1131): a one-block step within the look-ahead, head room
        // above, sets autoJumpTime = 1; the next aiStep's makeJump is the jump bit the client sends
        final SimTestWorld world = step();
        final SimPlayer p = this.walkToAutoJump(world, this.standing(world));
        assertTrue(p.z < 3.0 - 0.3);
        this.tick(p, world, withJump(WR), 0.0F);
        assertEquals(0, p.autoJumpTime);
        assertTrue(p.autoJumpOption);
        assertFalse(p.onGround);
        assertTrue(p.vy > 0.0);
    }

    @Test
    void aPredictedAutoJumpTheClientDidNotSendMeansTheOptionIsOff() {
        final SimTestWorld world = step();
        final SimPlayer p = this.walkToAutoJump(world, this.standing(world));
        final double y = p.y;
        this.tick(p, world, WR, 0.0F);
        // no jump forced: the client would have sent it
        assertEquals(y, p.y);
        assertTrue(p.onGround);
        assertFalse(p.autoJumpOption);
        assertFalse(p.autoJumpEnabled);
        for (int t = 0; t < 10; t++) {
            this.tick(p, world, WR, 0.0F);
            assertEquals(0, p.autoJumpTime);
        }
    }

    @Test
    void anAutoJumpIsNotACreativeDoubleTap() {
        // LocalPlayer.aiStep (LocalPlayer.java:854): wasAutoJump skips the mayfly double-tap window
        final SimTestWorld world = step();
        final SimPlayer auto = this.standing(world);
        auto.mayfly = true;
        this.walkToAutoJump(world, auto);
        this.tick(auto, world, withJump(WR), 0.0F);
        assertEquals(0, auto.jumpTriggerTime);
        final SimPlayer manual = this.standing(stone());
        manual.mayfly = true;
        this.tick(manual, stone(), withJump(WR), 0.0F);
        // set to 7, then Player.aiStep counts it down (Player.java:443-445)
        assertEquals(6, manual.jumpTriggerTime);
    }

    @Test
    void aNewPlayersFirstTickAutoJumpsEvenWithTheOptionOff() {
        // LocalPlayer.autoJumpEnabled starts true (LocalPlayer.java:160); sendPosition copies the option after the tick
        final SimTestWorld world = step();
        final SimPlayer p = at(0.5, 64.0, 2.5);
        p.autoJumpOption = false;
        p.onGround = true;
        p.vy = -0.0784000015258789; // settled on the floor, so the first move lands
        this.tick(p, world, W, 0.0F);
        assertEquals(1, p.autoJumpTime);
        assertFalse(p.autoJumpEnabled);
    }

    @Test
    void fireworkBoostPullsAlongTheLookWhileGliding() {
        // FireworkRocketEntity.tick (FireworkRocketEntity.java:128-140): v + look × 0.1 + (look × 1.5 - v) × 0.5
        final SimPlayer p = at(0.5, 80.0, 0.5);
        p.yRot = 30.0F;
        p.xRot = -20.0F;
        p.vx = 0.1;
        p.vy = -0.2;
        p.vz = 0.3;
        this.sim.fireworkBoost(p);
        assertEquals(0.1, p.vx); // not gliding: untouched
        p.fallFlying = true;
        this.sim.fireworkBoost(p);
        // Entity.calculateViewVector (Entity.java:2031-2039)
        final float rx = -20.0F * 0.017453292F;
        final float ry = -30.0F * 0.017453292F;
        final double lx = Mth.sin(ry) * Mth.cos(rx);
        final double ly = -Mth.sin(rx);
        final double lz = Mth.cos(ry) * Mth.cos(rx);
        assertEquals(0.1 + (lx * 0.1 + (lx * 1.5 - 0.1) * 0.5), p.vx);
        assertEquals(-0.2 + (ly * 0.1 + (ly * 1.5 - -0.2) * 0.5), p.vy);
        assertEquals(0.3 + (lz * 0.1 + (lz * 1.5 - 0.3) * 0.5), p.vz);
    }

    @Test
    void onlyAnotherItemTypeResetsTheAttackTicker() {
        // Player.tick (Player.java:274-281): !matches → reset only when !isSameItem
        final SimTestWorld world = stone();
        final SimPlayer p = this.standing(world);
        p.mainHand = new ItemStack(Items.DIAMOND_SWORD);
        this.tick(p, world, NONE, 0.0F);
        assertEquals(0, p.attackStrengthTicker);
        this.tick(p, world, NONE, 0.0F);
        this.tick(p, world, NONE, 0.0F);
        assertEquals(2, p.attackStrengthTicker);
        final ItemStack worn = new ItemStack(Items.DIAMOND_SWORD);
        worn.setDamageValue(40);
        p.mainHand = worn;
        this.tick(p, world, NONE, 0.0F);
        assertEquals(3, p.attackStrengthTicker);
        p.mainHand = new ItemStack(Items.IRON_SWORD);
        this.tick(p, world, NONE, 0.0F);
        assertEquals(0, p.attackStrengthTicker);
        assertEquals(0, p.itemSwapTicker);
    }

    @Test
    void anEffectUpdateReplacesTheAmplifier() {
        // ClientPacketListener.handleUpdateMobEffect builds the instance with no hidden effect, so the client never
        // downgrades on its own: a server-side downgrade arrives as an update with the lower amplifier
        final SimTestWorld world = stone();
        final SimPlayer p = this.standing(world);
        this.sim.addEffect(p, MobEffects.JUMP_BOOST, 2);
        this.sim.addEffect(p, MobEffects.JUMP_BOOST, 0);
        this.tick(p, world, J, 0.0F);
        // getJumpPower: jump strength + 0.1 × (amplifier + 1) (LivingEntity.java:2376-2382)
        assertEquals(64.0 + (double) ((float) 0.41999998688697815 + 0.1F), p.y);
    }

    @Test
    void noGravityKeepsOnlyTheDrag() {
        // DATA_NO_GRAVITY: getEffectiveGravity is 0 (LivingEntity.java:2421-2424), the vertical drag stays
        final SimPlayer p = at(0.5, 70.0, 0.5);
        p.noGravity = true;
        p.vy = 0.2;
        this.tick(p, stone(), NONE, 0.0F);
        assertEquals(0.2 * modified(0.98F, 1.0F), p.vy);
        assertEquals(70.2, p.y);
    }
}
