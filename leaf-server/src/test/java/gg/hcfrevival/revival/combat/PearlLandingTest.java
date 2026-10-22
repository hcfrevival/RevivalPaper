package gg.hcfrevival.revival.combat;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import gg.hcfrevival.revival.RevivalRegistries;
import gg.hcfrevival.revival.RevivalTest;
import gg.hcfrevival.revival.config.ProjectileRules;
import it.unimi.dsi.fastutil.longs.Long2ObjectOpenHashMap;
import java.util.List;
import java.util.Set;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.world.level.EmptyBlockGetter;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.DoorBlock;
import net.minecraft.world.level.block.FenceGateBlock;
import net.minecraft.world.level.block.SlabBlock;
import net.minecraft.world.level.block.StairBlock;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.properties.SlabType;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.Vec3;
import net.minecraft.world.phys.shapes.CollisionContext;
import org.junit.jupiter.api.Test;

/**
 * The pearl glitch spots of pvp-issues PR-7 (docs/revival/projectiles.md §6). Each pearl lands its thrower's feet at
 * the pearl's start-of-tick position in its impact tick (1.7.10 EntityEnderPearl.onImpact, 26.3 ThrownEnderpearl.onHit
 * {@code oldPosition()}); there the standing box (0.6 × 1.8) overlaps the block, which a 26.x client can then walk
 * through. The safe landing moves back along the flight, 1/16 at a time, to the first spot where the box fits.
 */
@RevivalTest
@RevivalRegistries
class PearlLandingTest {

    private static final double WIDTH = 0.6F;
    private static final double HEIGHT = 1.8F;
    private static final double STEP = 0.0625;
    private static final double BACKTRACK = 1.5;

    /** A map world: a stone floor under y = 64 from -8 to 8, plus whatever the test puts in. */
    private static final class Blocks2 implements ProjectileBlocks {
        final Long2ObjectOpenHashMap<BlockState> blocks = new Long2ObjectOpenHashMap<>();

        Blocks2 floor() {
            for (int x = -8; x <= 8; x++) {
                for (int z = -8; z <= 8; z++) {
                    this.set(x, 63, z, Blocks.STONE.defaultBlockState());
                }
            }
            return this;
        }

        Blocks2 set(final int x, final int y, final int z, final BlockState state) {
            this.blocks.put(BlockPos.asLong(x, y, z), state);
            return this;
        }

        @Override
        public BlockState blockState(final int x, final int y, final int z) {
            final BlockState state = this.blocks.get(BlockPos.asLong(x, y, z));
            return state == null ? Blocks.AIR.defaultBlockState() : state;
        }
    }

    private static ProjectileRules.Pearls rules(final Set<net.minecraft.resources.Identifier> passThrough) {
        return new ProjectileRules.Pearls(true, true, true, BACKTRACK, STEP, passThrough, List.of(), true);
    }

    private static final ProjectileRules.Pearls RULES = rules(Set.of());

    private static int resolve(final ProjectileBlocks server, final ProjectileBlocks client, final Vec3 landing, final Vec3 flight, final double[] out) {
        return PearlLanding.resolve(server, client, landing.x, landing.y, landing.z, flight.x, flight.y, flight.z, WIDTH, HEIGHT, BACKTRACK, STEP, RULES, out);
    }

    private static boolean fits(final ProjectileBlocks blocks, final double x, final double y, final double z) {
        return PearlLanding.fits(blocks, x - WIDTH / 2, y, z - WIDTH / 2, x + WIDTH / 2, y + HEIGHT, z + WIDTH / 2, RULES);
    }

    // The block's collision box (one box for these blocks), in world coordinates
    private static AABB collision(final BlockState state, final int x, final int y, final int z) {
        final BlockPos pos = new BlockPos(x, y, z);
        return state.getCollisionShape(EmptyBlockGetter.INSTANCE, pos, CollisionContext.empty()).bounds().move(pos);
    }

    /** A pearl flying +x at y = 64.5 that hit {@code face}: 1.7 lands the feet 0.1 short of it, overlapping it by 0.2. */
    private static void movesBackOutOf(final Blocks2 world, final double face) {
        final Vec3 landing = new Vec3(face - 0.1, 64.0, 0.5);
        final double[] out = new double[3];
        assertFalse(fits(world, landing.x, landing.y, landing.z), "1.7 lands inside the block");
        assertEquals(PearlLanding.MOVED, resolve(world, null, landing, new Vec3(1.4, -0.3, 0.0), out));
        assertTrue(fits(world, out[0], out[1], out[2]));
        assertTrue(out[0] + WIDTH / 2 <= face + PearlLanding.SKIN, "never past the face the pearl hit");
        // The first fitting step back along the flight
        final Vec3 back = new Vec3(1.4, -0.3, 0.0).normalize();
        final int steps = (int) Math.round((landing.x - out[0]) / (back.x * STEP));
        assertFalse(fits(world, landing.x - back.x * STEP * (steps - 1), landing.y - back.y * STEP * (steps - 1), 0.5));
    }

    @Test
    void anHonestLandingOnOpenGroundIsVanillas() {
        final Blocks2 world = new Blocks2().floor();
        final double[] out = new double[3];
        assertEquals(PearlLanding.AS_IS, resolve(world, null, new Vec3(0.5, 64.3, 0.5), new Vec3(1.0, -0.4, 0.2), out));
        // Resting on the floor and against a wall are not collisions (Entity.noCollision's 1e-7 skin)
        world.set(1, 64, 0, Blocks.STONE.defaultBlockState());
        assertEquals(PearlLanding.AS_IS, resolve(world, null, new Vec3(0.7, 64.0, 0.5), new Vec3(1.0, 0.0, 0.0), out));
    }

    @Test
    void aOneBlockWallIsNotWalkedThrough() {
        final Blocks2 world = new Blocks2().floor();
        for (int y = 64; y <= 66; y++) {
            world.set(3, y, 0, Blocks.STONE.defaultBlockState());
        }
        movesBackOutOf(world, 3.0);
    }

    @Test
    void aClosedFenceGate() {
        final BlockState gate = Blocks.OAK_FENCE_GATE.defaultBlockState().setValue(FenceGateBlock.FACING, Direction.EAST);
        final Blocks2 world = new Blocks2().floor().set(3, 64, 0, gate);
        movesBackOutOf(world, collision(gate, 3, 64, 0).minX);
        // Open, it has no collision: a pearl flies through and lands in it
        final BlockState open = gate.setValue(FenceGateBlock.OPEN, true);
        assertTrue(fits(new Blocks2().floor().set(3, 64, 0, open), 3.5, 64.0, 0.5));
    }

    @Test
    void aClosedDoor() {
        final BlockState door = Blocks.OAK_DOOR.defaultBlockState().setValue(DoorBlock.FACING, Direction.WEST);
        final Blocks2 world = new Blocks2().floor().set(3, 64, 0, door).set(3, 65, 0, door.setValue(DoorBlock.HALF,
            net.minecraft.world.level.block.state.properties.DoubleBlockHalf.UPPER));
        movesBackOutOf(world, collision(door, 3, 64, 0).minX);
    }

    @Test
    void aSlabAndAStairStep() {
        final BlockState slab = Blocks.STONE_SLAB.defaultBlockState().setValue(SlabBlock.TYPE, SlabType.BOTTOM);
        movesBackOutOf(new Blocks2().floor().set(3, 64, 0, slab), 3.0);
        final BlockState stair = Blocks.STONE_STAIRS.defaultBlockState().setValue(StairBlock.FACING, Direction.EAST);
        movesBackOutOf(new Blocks2().floor().set(3, 64, 0, stair), 3.0);
    }

    @Test
    void aGlassPaneEndOn() {
        // An unconnected pane is a 2/16 post in the middle of its block; joined to a wall it spans the block
        final BlockState pane = Blocks.GLASS_PANE.defaultBlockState().setValue(net.minecraft.world.level.block.IronBarsBlock.NORTH, true)
            .setValue(net.minecraft.world.level.block.IronBarsBlock.SOUTH, true);
        movesBackOutOf(new Blocks2().floor().set(3, 64, 0, pane).set(3, 65, 0, pane), collision(pane, 3, 64, 0).minX);
    }

    @Test
    void aPearlUpIntoACeilingLandsBelowIt() {
        final Blocks2 world = new Blocks2().floor();
        for (int x = -2; x <= 2; x++) {
            for (int z = -2; z <= 2; z++) {
                world.set(x, 67, z, Blocks.STONE.defaultBlockState());
            }
        }
        final double[] out = new double[3];
        // Thrown straight up, it hits y = 67 from below; 1.7 puts the feet at 66.5 and the head in the stone
        assertEquals(PearlLanding.MOVED, resolve(world, null, new Vec3(0.5, 66.5, 0.5), new Vec3(0.0, 0.8, 0.0), out));
        assertEquals(66.5 - 21 * STEP, out[1], 1.0E-12); // the first step with the head under 67: 1.3 back, rounded up to 1/16
        assertTrue(out[1] + HEIGHT <= 67.0);
    }

    @Test
    void noFitWithinTheBacktrackDropsTheTeleport() {
        // A 1-high crawl space: no standing box fits anywhere along the flight
        final Blocks2 world = new Blocks2().floor();
        for (int x = -8; x <= 8; x++) {
            for (int z = -8; z <= 8; z++) {
                world.set(x, 65, z, Blocks.STONE.defaultBlockState());
            }
        }
        assertEquals(PearlLanding.NONE, resolve(world, null, new Vec3(0.5, 64.1, 0.5), new Vec3(1.5, -0.1, 0.0), new double[3]));
    }

    @Test
    void aFakeWallOnlyTheClientHasStopsThePearlAndTheLanding() {
        final Blocks2 server = new Blocks2().floor();
        final Blocks2 client = new Blocks2().floor();
        for (int y = 64; y <= 66; y++) {
            client.set(3, y, 0, Blocks.GLASS.defaultBlockState()); // a combat wall sent with sendBlockChange
        }
        final Vec3 from = new Vec3(0.5, 64.5, 0.5);
        final Vec3 to = new Vec3(5.5, 64.5, 0.5);
        assertNull(PearlLanding.clip(server, from, to, RULES));
        final BlockHitResult wall = PearlLanding.clip(client, from, to, RULES);
        assertNotNull(wall);
        assertEquals(3.0, wall.getLocation().x, 1.0E-9);
        assertEquals(new BlockPos(3, 64, 0), wall.getBlockPos());
        assertEquals(Direction.WEST, wall.getDirection());
        // Landing 0.1 short of the glass: fine for the server, inside the wall for the client
        final double[] out = new double[3];
        assertEquals(PearlLanding.AS_IS, resolve(server, null, new Vec3(2.9, 64.0, 0.5), new Vec3(1.5, 0.0, 0.0), out));
        assertEquals(PearlLanding.MOVED, resolve(server, client, new Vec3(2.9, 64.0, 0.5), new Vec3(1.5, 0.0, 0.0), out));
        assertEquals(2.9 - 4 * STEP, out[0], 1.0E-12);
    }

    @Test
    void passThroughBlocksDontStopThePearlOrTheLanding() {
        final BlockState gate = Blocks.OAK_FENCE_GATE.defaultBlockState().setValue(FenceGateBlock.FACING, Direction.EAST);
        final Blocks2 world = new Blocks2().floor().set(3, 64, 0, gate);
        final ProjectileRules.Pearls through = rules(Set.of(BuiltInRegistries.BLOCK.getKey(Blocks.OAK_FENCE_GATE)));
        assertTrue(through.passesThrough(gate));
        assertFalse(through.passesThrough(Blocks.STONE.defaultBlockState()));
        assertNotNull(PearlLanding.clip(world, new Vec3(0.5, 64.5, 0.5), new Vec3(5.5, 64.5, 0.5), RULES));
        assertNull(PearlLanding.clip(world, new Vec3(0.5, 64.5, 0.5), new Vec3(5.5, 64.5, 0.5), through));
        final double[] out = new double[3];
        assertEquals(PearlLanding.AS_IS, PearlLanding.resolve(world, null, 3.5, 64.0, 0.5, 1.5, 0.0, 0.0, WIDTH, HEIGHT, BACKTRACK, STEP, through, out));
    }

    @Test
    void theClipFindsTheSameFaceAsTheBlocksOwnShapeClip() {
        final BlockState slab = Blocks.STONE_SLAB.defaultBlockState().setValue(SlabBlock.TYPE, SlabType.BOTTOM);
        final Blocks2 world = new Blocks2().set(2, 64, 0, slab);
        // Downward onto the slab top from above, across two blocks
        final Vec3 from = new Vec3(0.3, 66.0, 0.5);
        final Vec3 to = new Vec3(2.9, 64.2, 0.5);
        final BlockHitResult hit = PearlLanding.clip(world, from, to, RULES);
        assertNotNull(hit);
        final BlockHitResult reference = slab.getCollisionShape(EmptyBlockGetter.INSTANCE, new BlockPos(2, 64, 0)).clip(from, to, new BlockPos(2, 64, 0));
        assertNotNull(reference);
        assertEquals(reference.getLocation(), hit.getLocation());
        assertEquals(Direction.UP, hit.getDirection());
    }
}
