package gg.hcfrevival.revival.auth.sim;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import gg.hcfrevival.revival.RevivalRegistries;
import gg.hcfrevival.revival.RevivalTest;
import java.lang.management.ManagementFactory;
import net.minecraft.core.Direction;
import net.minecraft.world.entity.EntityTypes;
import net.minecraft.world.entity.Pose;
import net.minecraft.world.entity.monster.Shulker;
import net.minecraft.world.entity.player.Input;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.material.FluidState;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;
import org.junit.jupiter.api.Test;

/**
 * Solid entities ({@link SimWorld#collectEntityColliders}) the way a 26.3 client collides with them: boxes from
 * {@code Shapes.create(entity.getBoundingBox())} found through ClientLevel's section lookup, a happy ghast solid only for
 * a player at or above its top, the same boxes for collide, noCollision (pose, edge back-off) and auto-jump, extra
 * candidates ignored, and nothing allocated for a moving one. The oracle fuzz and the golden scenes check the same
 * against real vanilla entities (auth-sim.md §4).
 */
@RevivalTest
@RevivalRegistries
class SimEntityCollisionTest {

    private static final BlockState STONE = Blocks.STONE.defaultBlockState();
    private static final Input NONE = Input.EMPTY;

    private final MovementSim sim = new MovementSim();
    private final SimTickInput in = new SimTickInput();
    private final double[] out = new double[3];

    private static SimPlayer at(final double x, final double y, final double z, final boolean onGround) {
        final SimPlayer p = new SimPlayer();
        p.setPos(x, y, z);
        p.onGround = onGround;
        return p;
    }

    private static AABB ghast(final double x, final double y, final double z) {
        return EntityTypes.HAPPY_GHAST.getDimensions().makeBoundingBox(x, y, z);
    }

    private static AABB boat(final double x, final double y, final double z) {
        return EntityTypes.OAK_BOAT.getDimensions().makeBoundingBox(x, y, z);
    }

    @Test
    void aHappyGhastIsSolidOnlyFromAbove() {
        // HappyGhast.canBeCollidedWith on a client: other.position().y >= getBoundingBox().maxY (HappyGhast.java:578-589)
        final AABB box = ghast(0.5, 64.0, 3.0); // [-1.5, 64, 1] .. [2.5, 68, 5]
        final SimTestWorld walking = new SimTestWorld().floor(63, STONE).solid(box, 0.5, 64.0, 3.0, SimWorld.ENTITY_SOLID_FROM_ABOVE);
        final SimPlayer p = at(0.5, 64.0, 0.5, true);
        this.sim.collide(p, walking, 0.0, -0.0784000015258789, 0.5, this.out);
        assertEquals(0.5, this.out[2], "walks into its side");
        // on its still timeout it is solid from every side: the box stops at the face z = 1
        final SimTestWorld still = new SimTestWorld().floor(63, STONE).solid(box, 0.5, 64.0, 3.0, SimWorld.ENTITY_SOLID);
        this.sim.collide(p, still, 0.0, -0.0784000015258789, 0.5, this.out);
        assertEquals(1.0 - (0.5 + (double) (0.6F / 2.0F)), this.out[2]);
        // standing on top (y == maxY): solid, the fall stops on it
        final SimPlayer top = at(0.5, 68.0, 3.0, true);
        this.sim.collide(top, walking, 0.0, -0.0784000015258789, 0.0, this.out);
        assertEquals(0.0, this.out[1]);
        // a hair under the top (inside it): not solid, the player falls through
        final SimPlayer inside = at(0.5, 67.99, 3.0, false);
        this.sim.collide(inside, walking, 0.0, -0.0784000015258789, 0.0, this.out);
        assertEquals(-0.0784000015258789, this.out[1]);
    }

    @Test
    void poseChecksUseTheSameEntityBoxes() {
        // Player.updatePlayerPose: canPlayerFitWithinBlocksAndEntitiesWhen is noCollision, entities included. A boat at
        // head height leaves room only to crouch; a happy ghast there is not solid for a player under its top
        final SimTestWorld boat = new SimTestWorld().floor(63, STONE).solid(boat(0.5, 65.6, 0.5));
        final SimPlayer p = at(0.5, 64.0, 0.5, true);
        this.sim.tick(p, this.in.set(NONE, 0.0F, 0.0F), boat);
        assertEquals(Pose.CROUCHING, p.pose);
        final SimTestWorld ghast = new SimTestWorld().floor(63, STONE).solid(ghast(0.5, 65.6, 0.5), 0.5, 65.6, 0.5, SimWorld.ENTITY_SOLID_FROM_ABOVE);
        final SimPlayer q = at(0.5, 64.0, 0.5, true);
        this.sim.tick(q, this.in.set(NONE, 0.0F, 0.0F), ghast);
        assertEquals(Pose.STANDING, q.pose);
    }

    @Test
    void sneakingKeepsThePlayerOnABoat() {
        // Player.maybeBackOffFromEdge tests the ground with noCollision, so a boat is ground: sneaking off its edge stops
        final SimTestWorld world = new SimTestWorld().floor(60, STONE).solid(boat(0.5, 64.0, 0.5));
        final SimPlayer p = at(0.5, 64.5625, 0.5, true);
        final Input sneakForward = new Input(true, false, false, false, false, true, false);
        for (int t = 0; t < 40; t++) {
            this.sim.tick(p, this.in.set(sneakForward, 0.0F, 0.0F), world);
        }
        assertTrue(p.onGround);
        assertEquals(64.5625, p.y);
        assertTrue(p.z < 0.5 + 0.6875 + 0.3F + 0.05, "stays over the boat: " + p.z);
    }

    @Test
    void theSectionLookupMissesABoxFromAnotherSection() {
        // ClientLevel finds entities by their section around the query, minY - 4 .. maxY + 0 in y
        // (EntitySectionStorage.forEachAccessibleNonEmptySection): a shulker hanging from y = 80 (section 5) with its lid
        // open to y = 79 is not found by a rise whose box stays under y = 80, so the head goes into the lid
        final AABB lid = Shulker.getProgressAabb(1.0F, Direction.DOWN, 1.0F, new Vec3(0.5, 80.0, 0.5)); // [0, 79, 0] .. [1, 81, 1]
        assertEquals(79.0, lid.minY);
        final SimTestWorld hanging = new SimTestWorld().solid(lid, 0.5, 80.0, 0.5, SimWorld.ENTITY_SOLID);
        final SimPlayer p = at(0.5, 77.0, 0.5, false); // head at 78.8
        this.sim.collide(p, hanging, 0.0, 0.4, 0.0, this.out);
        assertEquals(0.4, this.out[1], "missed: the query reaches 79.8 (with the step height), section 4");
        // the same box held by an entity in section 4 is found and stops the head at 79
        final SimTestWorld lower = new SimTestWorld().solid(lid, 0.5, 79.9, 0.5, SimWorld.ENTITY_SOLID);
        this.sim.collide(p, lower, 0.0, 0.4, 0.0, this.out);
        assertEquals(79.0 - (77.0 + (double) 1.8F), this.out[1], 1.0E-12);
    }

    @Test
    void extraCandidatesChangeNothing() {
        // the world may hand over more than the area: the port keeps only what each query finds
        final SimTestWorld exact = new SimTestWorld().floor(63, STONE).solid(boat(0.5, 64.0, 2.5));
        final AllSolids generous = new AllSolids(new SimTestWorld().floor(63, STONE));
        generous.add(boat(0.5, 64.0, 2.5));
        generous.add(boat(6.5, 63.7, 6.5)); // its top would be a step candidate of 0.2625
        generous.add(boat(0.5, 70.0, 0.5)); // high over the head: crouching if the pose check took it
        final double[] a = new double[3];
        for (final double dz : new double[] {0.3, 0.8, 1.6}) {
            final SimPlayer p = at(0.5, 64.0, 0.5, true);
            this.sim.collide(p, exact, 0.0, -0.0784000015258789, dz, a);
            this.sim.collide(p, generous, 0.0, -0.0784000015258789, dz, this.out);
            assertEquals(a[0], this.out[0]);
            assertEquals(a[1], this.out[1]);
            assertEquals(a[2], this.out[2]);
        }
        final SimPlayer q = at(0.5, 64.0, 0.5, true);
        this.sim.tick(q, this.in.set(NONE, 0.0F, 0.0F), generous);
        assertEquals(Pose.STANDING, q.pose);
    }

    @Test
    void aMovingBoatAllocatesNothing() {
        final MovingBoat world = new MovingBoat(new SimTestWorld().floor(63, STONE));
        final SimPlayer p = at(0.5, 64.0, 0.5, true);
        final Input forward = new Input(true, false, false, false, false, false, false);
        for (int t = 0; t < 400; t++) {
            world.step(t);
            this.sim.tick(p, this.in.set(forward, (t * 7) % 360, 0.0F), world);
        }
        final com.sun.management.ThreadMXBean threads = (com.sun.management.ThreadMXBean) ManagementFactory.getThreadMXBean();
        final long bytes0 = threads.getCurrentThreadAllocatedBytes();
        for (int t = 400; t < 800; t++) {
            world.step(t);
            this.sim.tick(p, this.in.set(forward, (t * 7) % 360, 0.0F), world);
        }
        final long bytes = threads.getCurrentThreadAllocatedBytes() - bytes0;
        assertTrue(bytes < 400, bytes + " bytes in 400 ticks");
        assertTrue(world.touched > 0, "the boat met the player");
    }

    // ------------------------------------------------------------------------------------------------ worlds

    /** Blocks from a test world; every solid it holds is handed over whatever the area. */
    private static final class AllSolids implements SimWorld {
        private final SimTestWorld blocks;
        private final java.util.List<AABB> boxes = new java.util.ArrayList<>();

        AllSolids(final SimTestWorld blocks) {
            this.blocks = blocks;
        }

        void add(final AABB box) {
            this.boxes.add(box);
        }

        @Override
        public void collectEntityColliders(final SimBox area, final SimBoxList out) {
            for (int i = 0; i < this.boxes.size(); i++) {
                final AABB b = this.boxes.get(i);
                out.add(b.minX, b.minY, b.minZ, b.maxX, b.maxY, b.maxZ);
            }
        }

        @Override
        public BlockState blockState(final int x, final int y, final int z) {
            return this.blocks.blockState(x, y, z);
        }

        @Override
        public FluidState fluidState(final int x, final int y, final int z) {
            return this.blocks.fluidState(x, y, z);
        }

        @Override
        public boolean isChunkLoaded(final int chunkX, final int chunkZ) {
            return true;
        }

        @Override
        public boolean sectionHasFluid(final int chunkX, final int sectionY, final int chunkZ) {
            return false;
        }

        @Override
        public int minY() {
            return -64;
        }

        @Override
        public int height() {
            return 384;
        }

        @Override
        public double borderMinX() {
            return -2.9999984E7;
        }

        @Override
        public double borderMinZ() {
            return -2.9999984E7;
        }

        @Override
        public double borderMaxX() {
            return 2.9999984E7;
        }

        @Override
        public double borderMaxZ() {
            return 2.9999984E7;
        }

        @Override
        public boolean fastLava() {
            return false;
        }
    }

    /** A boat circling around and through the spawn, its box moved in place each tick (nothing allocated). */
    private static final class MovingBoat implements SimWorld {
        private final SimTestWorld blocks;
        private double x;
        private double z;
        int touched;

        MovingBoat(final SimTestWorld blocks) {
            this.blocks = blocks;
        }

        void step(final int t) {
            this.x = 0.5 + 2.0 * Math.sin(t * 0.05);
            this.z = 0.5 + 2.0 * Math.cos(t * 0.031);
        }

        @Override
        public void collectEntityColliders(final SimBox area, final SimBoxList out) {
            final double w = (double) (1.375F / 2.0F);
            final double minX = this.x - w;
            final double maxX = this.x + w;
            final double minZ = this.z - w;
            final double maxZ = this.z + w;
            if (minX < area.maxX && maxX > area.minX && 64.0 < area.maxY && 64.5625 > area.minY && minZ < area.maxZ && maxZ > area.minZ) {
                out.add(minX, 64.0, minZ, maxX, 64.5625, maxZ, this.x, 64.0, this.z, SimWorld.ENTITY_SOLID);
                this.touched++;
            }
        }

        @Override
        public BlockState blockState(final int x, final int y, final int z) {
            return this.blocks.blockState(x, y, z);
        }

        @Override
        public FluidState fluidState(final int x, final int y, final int z) {
            return this.blocks.fluidState(x, y, z);
        }

        @Override
        public boolean isChunkLoaded(final int chunkX, final int chunkZ) {
            return true;
        }

        @Override
        public boolean sectionHasFluid(final int chunkX, final int sectionY, final int chunkZ) {
            return false;
        }

        @Override
        public int minY() {
            return -64;
        }

        @Override
        public int height() {
            return 384;
        }

        @Override
        public double borderMinX() {
            return -2.9999984E7;
        }

        @Override
        public double borderMinZ() {
            return -2.9999984E7;
        }

        @Override
        public double borderMaxX() {
            return 2.9999984E7;
        }

        @Override
        public double borderMaxZ() {
            return 2.9999984E7;
        }

        @Override
        public boolean fastLava() {
            return false;
        }
    }
}
