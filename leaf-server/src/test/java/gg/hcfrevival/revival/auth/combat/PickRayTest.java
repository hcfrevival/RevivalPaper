package gg.hcfrevival.revival.auth.combat;

import static gg.hcfrevival.revival.auth.combat.CombatScenes.attacker;
import static gg.hcfrevival.revival.auth.combat.CombatScenes.player;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import gg.hcfrevival.revival.RevivalRegistries;
import gg.hcfrevival.revival.RevivalTest;
import java.util.Random;
import net.minecraft.core.BlockPos;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.level.EmptyBlockGetter;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.SlabBlock;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.properties.SlabType;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.Vec3;
import net.minecraft.world.phys.shapes.CollisionContext;
import net.minecraft.world.phys.shapes.VoxelShape;
import org.junit.jupiter.api.Test;

/**
 * The client's attack ray with hand-computed geometry (LocalPlayer.pick, report 02 §2.2). Exactness against the vanilla
 * client's own {@code LocalPlayer.pick} over 10^6 random scenes is the rig's job (PickVerify); here the boundaries are
 * spelled out, and the block pass is checked against the server's (Moonrise-patched) {@code VoxelShape.clip}.
 */
@RevivalTest
@RevivalRegistries
class PickRayTest {

    // Attacker at (0.5, 64, 0.5) looking south (+z): eye (0.5, 64 + 1.62F, 0.5), view (0.0, -0.0, 1.0)
    private static final double EYE_Y = 64.0 + 1.62F;

    private static PickRay cast(final AttackerSnapshot attacker, final PickWorld world, final AABB... boxes) {
        final PickRay ray = new PickRay();
        for (int i = 0; i < boxes.length; i++) {
            ray.add(i, boxes[i], 0.0F, true, true);
        }
        ray.cast(attacker, world);
        ray.resolveInOrder();
        return ray;
    }

    // A spear (AttackRange.java via Item.java:556: reach 2.0-4.5, margin 0.125) at (0.5, 64, 0.5), pitch 0
    private static AttackerSnapshot spear(final float yRot, final float headYRot, final double knownZ) {
        final AttackerSnapshot base = attacker(0.5, 64.0, 0.5, yRot, 0.0F, 4.5, 3.0);
        return new AttackerSnapshot(base.xo(), base.yo(), base.zo(), base.x(), base.y(), base.z(), base.eyeHeight(), base.yRot(), base.xRot(), base.box(),
            base.blockRange(), base.entityRange(), true, false, new AttackerSnapshot.Reach(headYRot, 0.0, 0.0, knownZ, 2.0F, 4.5F, 0.125F));
    }

    private static PickRay castAlong(final AttackerSnapshot attacker, final PickWorld world, final AABB... boxes) {
        final PickRay ray = new PickRay();
        for (int i = 0; i < boxes.length; i++) {
            ray.add(i, boxes[i], 0.0F, true, true);
        }
        ray.castAttackRange(attacker, world);
        return ray;
    }

    @Test
    void aSpearReachesPastASwordsReach() {
        // Its face 3.8 from the eye: past the 3.0 entity range, inside the spear's 4.5 (pvp-issues HR-7)
        final AABB target = player(0.5, 64.0, 4.6);
        final PickRay along = castAlong(spear(0.0F, 0.0F, 0.0), new CombatScenes.Blocks2(), target);
        assertEquals(PickRay.Kind.ENTITY, along.rangeKind());
        assertTrue(along.canPickAlong(0, false));
        assertEquals(3.8 * 3.8, along.distanceSq(0), 1.0E-6); // the box is float-wide (0.3F)
        assertFalse(cast(attacker(0.5, 64.0, 0.5, 0.0F, 0.0F, 4.5, 3.0), new CombatScenes.Blocks2(), target).kind() == PickRay.Kind.ENTITY);
    }

    @Test
    void underTheMinimumReachTheClientPicksAsUsual() {
        // The line starts 2.0 out: a box 0.8 from the eye is behind its start, a miss, and the client's usual pick takes it
        final AABB target = player(0.5, 64.0, 1.6);
        final PickRay along = castAlong(spear(0.0F, 0.0F, 0.0), new CombatScenes.Blocks2(), target);
        assertEquals(PickRay.Kind.MISS, along.rangeKind());
        assertEquals(PickRay.Kind.ENTITY, cast(spear(0.0F, 0.0F, 0.0), new CombatScenes.Blocks2(), target).kind());
    }

    @Test
    void theMarginCatchesABoxTheLineJustMisses() {
        // The line runs at x 0.5; the box starts at x 0.6, within the 0.125 margin, and nothing stands before its centre
        final AABB target = new AABB(0.6, 64.0, 3.7, 1.2, 65.8F, 4.3);
        final PickRay along = castAlong(spear(0.0F, 0.0F, 0.0), new CombatScenes.Blocks2(), target);
        assertEquals(PickRay.Kind.ENTITY, along.rangeKind());
        assertTrue(along.canPickAlong(0, false));
        final AABB beyond = new AABB(0.7, 64.0, 3.7, 1.3, 65.8F, 4.3); // 0.2 off: outside the margin
        assertEquals(PickRay.Kind.MISS, castAlong(spear(0.0F, 0.0F, 0.0), new CombatScenes.Blocks2(), beyond).rangeKind());
    }

    @Test
    void theNearestHitTakesIt() {
        final PickRay along = castAlong(spear(0.0F, 0.0F, 0.0), new CombatScenes.Blocks2(), player(0.5, 64.0, 4.6), player(0.5, 64.0, 3.6));
        assertTrue(along.canPickAlong(1, false));
        assertFalse(along.canPickAlong(0, false), "the nearer box takes the hit");
    }

    @Test
    void movingTowardTheTargetReachesFurther() {
        // getKnownMovement · look adds to the far end: a face 4.7 out is past 4.5 standing, inside it moving at 0.3
        final AABB target = player(0.5, 64.0, 5.5);
        assertEquals(PickRay.Kind.MISS, castAlong(spear(0.0F, 0.0F, 0.0), new CombatScenes.Blocks2(), target).rangeKind());
        assertEquals(PickRay.Kind.ENTITY, castAlong(spear(0.0F, 0.0F, 0.3), new CombatScenes.Blocks2(), target).rangeKind());
        assertEquals(PickRay.Kind.MISS, castAlong(spear(0.0F, 0.0F, -0.3), new CombatScenes.Blocks2(), target).rangeKind(), "moving away adds nothing");
    }

    @Test
    void aBlockBeforeTheMinimumReachEndsIt() {
        // A block face 0.5 ahead, nearer than the line's start: the client's hit is that block, no entity
        final CombatScenes.Blocks2 world = new CombatScenes.Blocks2().set(0, 65, 1, Blocks.STONE.defaultBlockState());
        final PickRay along = castAlong(spear(0.0F, 0.0F, 0.0), world, player(0.5, 64.0, 4.6));
        assertEquals(PickRay.Kind.BLOCK, along.rangeKind());
    }

    @Test
    void theHeadsYawSteersItNotTheTicks() {
        // At the keybinds the head still has the last tick's yaw (Player.aiStep sets it after the move): south here, while
        // this tick's yaw turned 30 degrees away
        final AABB target = player(0.5, 64.0, 4.6);
        assertEquals(PickRay.Kind.ENTITY, castAlong(spear(30.0F, 0.0F, 0.0), new CombatScenes.Blocks2(), target).rangeKind());
        assertEquals(PickRay.Kind.MISS, castAlong(spear(0.0F, 30.0F, 0.0), new CombatScenes.Blocks2(), target).rangeKind());
    }

    @Test
    void viewVectorIsTheClientsTableSine() {
        final Random random = new Random(3L);
        for (int i = 0; i < 10_000; i++) {
            final float yRot = random.nextFloat() * 720.0F - 360.0F;
            final float xRot = random.nextFloat() * 180.0F - 90.0F;
            final PickRay ray = cast(attacker(0.0, 0.0, 0.0, yRot, xRot, 4.5, 3.0), new CombatScenes.Blocks2());
            final Vec3 view = Entity.calculateViewVector(xRot, yRot);
            assertEquals(view.x, ray.viewX());
            assertEquals(view.y, ray.viewY());
            assertEquals(view.z, ray.viewZ());
        }
    }

    @Test
    void eyeIsTheLerpOfTheOldAndNewPositionNotTheNewPosition() {
        // Mth.lerp(1.0, xo, x) = xo + (x - xo): 1e-17 off the grid it differs from x by an ulp
        final AttackerSnapshot moving = new AttackerSnapshot(0.1, 64.0, 0.0, 0.30000000000000004, 64.0, 0.0, 1.62F, 0.0F, 0.0F, player(0.3, 64.0, 0.0), 4.5,
            3.0, false, false);
        assertEquals(0.1 + (0.30000000000000004 - 0.1), moving.eyeX());
        assertEquals(64.0 + 1.62F, moving.eyeY());
    }

    @Test
    void aPlayerInFrontWithinReachIsPicked() {
        final PickRay ray = cast(attacker(0.5, 64.0, 0.5, 0.0F, 0.0F, 4.5, 3.0), new CombatScenes.Blocks2(), player(0.5, 64.0, 3.0));
        assertEquals(PickRay.Kind.ENTITY, ray.kind());
        assertEquals(0, ray.hoveredIndex());
        assertEquals(3.0 - 0.3F, ray.hitZ(), 1.0E-12);
        assertEquals(EYE_Y, ray.hitY());
        assertTrue(ray.canPick(0, false));
    }

    @Test
    void reachIsStrictlyLessThanTheRange() {
        // Block range 4.0 makes the ray end exactly 4.0 along +z: the clip point lands on exact doubles
        final AABB atThree = new AABB(0.0, 64.0, 3.5, 1.0, 66.0, 4.1); // near face 3.0 from the eye
        final PickRay at = cast(attacker(0.5, 64.0, 0.5, 0.0F, 0.0F, 4.0, 3.0), new CombatScenes.Blocks2(), atThree);
        assertEquals(9.0, at.distanceSq(0));
        assertEquals(PickRay.Kind.MISS, at.kind()); // filterHitResult: distSq < range * range fails
        assertFalse(at.canPick(0, false));
        final AABB justInside = new AABB(0.0, 64.0, Math.nextDown(3.5), 1.0, 66.0, 4.1);
        final PickRay in = cast(attacker(0.5, 64.0, 0.5, 0.0F, 0.0F, 4.0, 3.0), new CombatScenes.Blocks2(), justInside);
        assertEquals(PickRay.Kind.ENTITY, in.kind());
        assertTrue(in.canPick(0, false));
    }

    @Test
    void aBlockInFrontStopsTheRayAndHidesThePlayerBehindIt() {
        // Block range 4.0: the clip fraction 1.5 / 4.0 is exact, so the hit lands on z = 2.0
        final CombatScenes.Blocks2 world = new CombatScenes.Blocks2().set(0, 65, 2, Blocks.STONE.defaultBlockState());
        final PickRay ray = cast(attacker(0.5, 64.0, 0.5, 0.0F, 0.0F, 4.0, 3.0), world, player(0.5, 64.0, 3.0));
        assertEquals(PickRay.Kind.BLOCK, ray.kind());
        assertEquals(2.0, ray.hitZ());
        assertEquals(new BlockPos(0, 65, 2), new BlockPos(ray.blockPosX(), ray.blockPosY(), ray.blockPosZ()));
        assertEquals(2.25, ray.blockDistanceSq()); // 1.5 blocks
        assertFalse(ray.touched(0)); // the entity pass ends at the block
        assertTrue(ray.onFullRay(0));
        assertFalse(ray.canPick(0, true));
    }

    @Test
    void anEntityFlushWithTheBlocksFaceIsNotReached() {
        // The entity pass ray ends exactly on the block face: AABB.clipPoint needs s < 1, strictly
        final CombatScenes.Blocks2 world = new CombatScenes.Blocks2().set(0, 65, 2, Blocks.STONE.defaultBlockState());
        final PickRay ray = cast(attacker(0.5, 64.0, 0.5, 0.0F, 0.0F, 4.0, 3.0), world, new AABB(0.0, 64.0, 2.0, 1.0, 66.0, 2.6));
        assertFalse(ray.touched(0));
        assertEquals(PickRay.Kind.BLOCK, ray.kind());
        assertFalse(ray.canPick(0, true));
    }

    @Test
    void slabsOnlyStopTheRayWhereTheyAre() {
        final BlockState bottom = Blocks.STONE_SLAB.defaultBlockState().setValue(SlabBlock.TYPE, SlabType.BOTTOM);
        final BlockState top = Blocks.STONE_SLAB.defaultBlockState().setValue(SlabBlock.TYPE, SlabType.TOP);
        final PickRay over = cast(attacker(0.5, 64.0, 0.5, 0.0F, 0.0F, 4.5, 3.0), new CombatScenes.Blocks2().set(0, 65, 1, bottom), player(0.5, 64.0, 2.5));
        assertEquals(PickRay.Kind.ENTITY, over.kind()); // eye at y 65.62 passes over the bottom half
        final PickRay into = cast(attacker(0.5, 64.0, 0.5, 0.0F, 0.0F, 4.0, 3.0), new CombatScenes.Blocks2().set(0, 65, 1, top), player(0.5, 64.0, 2.5));
        assertEquals(PickRay.Kind.BLOCK, into.kind());
        assertEquals(1.0, into.hitZ());
    }

    @Test
    void theNearerOfTwoPlayersOnTheRayIsPicked() {
        final PickRay ray = cast(attacker(0.5, 64.0, 0.5, 0.0F, 0.0F, 4.5, 3.0), new CombatScenes.Blocks2(), player(0.5, 64.0, 2.8), player(0.5, 64.0, 2.0));
        assertEquals(1, ray.hoveredIndex());
        assertTrue(ray.canPick(1, false));
        assertFalse(ray.canPick(0, false)); // behind another player in every order
    }

    @Test
    void anEyeInsideTheTargetPicksItAtDistanceZero() {
        final PickRay ray = cast(attacker(0.5, 64.0, 0.5, 0.0F, 0.0F, 4.5, 3.0), new CombatScenes.Blocks2(), player(0.6, 64.0, 0.6), player(0.5, 64.0, 2.0));
        assertTrue(ray.inside(0));
        assertEquals(0.0, ray.distanceSq(0));
        assertTrue(ray.canPick(0, false));
        // vanilla quirk (ProjectileUtil.getEntityHitResult): a clipped entity after an inside one replaces it, so with the
        // eye inside a box the client can pick either, depending on its entity order
        assertTrue(ray.canPick(1, false));
        assertEquals(1, ray.hoveredIndex()); // in this order the second one wins
    }

    @Test
    void theRayGrazesWithinTheAabbClipEpsilon() {
        // AABB.clipPoint accepts a crossing within 1e-7 outside the face's edges
        final PickRay graze = cast(attacker(0.5, 64.0, 0.5, 0.0F, 0.0F, 4.5, 3.0), new CombatScenes.Blocks2(), new AABB(-0.1, 64.0, 2.0, 0.49999995, 66.0, 2.6));
        assertEquals(PickRay.Kind.ENTITY, graze.kind());
        final PickRay miss = cast(attacker(0.5, 64.0, 0.5, 0.0F, 0.0F, 4.5, 3.0), new CombatScenes.Blocks2(), new AABB(-0.1, 64.0, 2.0, 0.4999998, 66.0, 2.6));
        assertEquals(PickRay.Kind.MISS, miss.kind());
        assertFalse(miss.touched(0));
    }

    @Test
    void blockClipMatchesTheServersVoxelShapeClip() {
        // Every 5th block state with an outline, 40 rays each through its cell, against VoxelShape.clip on this server's
        // shapes (Moonrise's patched clip and toAabbs): the port reads only toAabbs() and must agree with both
        final Random random = new Random(9L);
        int checked = 0;
        int hits = 0;
        int index = 0;
        for (final BlockState state : Block.BLOCK_STATE_REGISTRY) {
            if (index++ % 5 != 0) {
                continue;
            }
            final VoxelShape shape = state.getShape(EmptyBlockGetter.INSTANCE, BlockPos.ZERO, CollisionContext.empty());
            if (shape.isEmpty()) {
                continue;
            }
            final CombatScenes.Blocks2 world = new CombatScenes.Blocks2().set(0, 0, 0, state);
            for (int i = 0; i < 40; i++) {
                final double fx = random.nextDouble() * 6.0 - 3.0;
                final double fy = random.nextDouble() * 6.0 - 3.0;
                final double fz = random.nextDouble() * 6.0 - 3.0;
                // aim at a random point of the cell
                final double dx = random.nextDouble() - fx;
                final double dy = random.nextDouble() - fy;
                final double dz = random.nextDouble() - fz;
                final float yRot = (float) Math.toDegrees(Math.atan2(-dx, dz));
                final float xRot = (float) Math.toDegrees(-Math.atan2(dy, Math.sqrt(dx * dx + dz * dz)));
                final AttackerSnapshot attacker = new AttackerSnapshot(fx, fy - 1.62F, fz, fx, fy - 1.62F, fz, 1.62F, yRot, xRot, player(fx, fy - 1.62F, fz),
                    6.0, 3.0, false, false);
                final PickRay ray = new PickRay();
                ray.cast(attacker, world);
                final Vec3 from = new Vec3(ray.fromX(), ray.fromY(), ray.fromZ());
                final Vec3 to = from.add(ray.viewX() * 6.0, ray.viewY() * 6.0, ray.viewZ() * 6.0);
                final BlockHitResult server = shape.clip(from, to, BlockPos.ZERO);
                checked++;
                if (server == null) {
                    assertFalse(ray.blockHit(), state::toString);
                    continue;
                }
                if (!ray.blockHit()) {
                    // Only when the shape pokes out of a cell the traversal never enters (BlockGetter.traverseBlocks)
                    assertFalse(new AABB(0.001, 0.001, 0.001, 0.999, 0.999, 0.999).clip(from, to).isPresent(), state::toString);
                    continue;
                }
                hits++;
                assertEquals(server.getLocation().x, ray.blockX(), state::toString);
                assertEquals(server.getLocation().y, ray.blockY(), state::toString);
                assertEquals(server.getLocation().z, ray.blockZ(), state::toString);
            }
        }
        final int total = checked;
        final int hit = hits;
        assertTrue(total > 10_000, () -> "checked " + total);
        assertTrue(hit > 1_000, () -> "hits " + hit);
    }
}
