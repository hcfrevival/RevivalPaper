package gg.hcfrevival.revival.auth.sim;

import static org.junit.jupiter.api.Assertions.assertEquals;

import gg.hcfrevival.revival.RevivalRegistries;
import gg.hcfrevival.revival.RevivalTest;
import java.util.List;
import net.minecraft.core.Direction;
import net.minecraft.world.entity.ai.attributes.Attributes;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.StairBlock;
import net.minecraft.world.phys.AABB;
import org.junit.jupiter.api.Test;

/**
 * The port's own vanilla collision ({@link SimCollision}): results the vanilla 26.3 server jar gives offline (report 07
 * §1.3, auth-rig {@code rig.Proof}), and the 1e-7 rules where RevivalPaper's Moonrise collision differs (report 07 §1.6).
 * The differential fuzzer (auth-rig/diff, mode 2) compares it with vanilla over 10^6 adversarial moves.
 */
@RevivalTest
@RevivalRegistries
class SimCollisionTest {

    private final MovementSim sim = new MovementSim();
    private final double[] out = new double[3];

    private static SimPlayer box(final double x, final double y, final double z) {
        final SimPlayer p = new SimPlayer();
        p.setPos(x, y, z);
        p.onGround = true;
        return p;
    }

    @Test
    void stepsOntoAStairLikeVanilla() {
        // rig.Proof: stone floor at y=63, a north-facing oak stair at (0, 64, 0), the player at (0.5, 64, 1.35) moving
        // (0, -0.0784000015258789, -0.3): vanilla gives (0.0, 0.5, -0.3) with the 0.6 step; without it the box stops at
        // the stair's face z = 1 (rig.Proof's -0.050000000000000044 is for a double 0.3 half-width, the player's is 0.3F)
        final SimTestWorld world = new SimTestWorld().floor(63, Blocks.STONE.defaultBlockState())
            .set(0, 64, 0, Blocks.OAK_STAIRS.defaultBlockState().setValue(StairBlock.FACING, Direction.NORTH));
        final SimPlayer p = box(0.5, 64.0, 1.35);
        this.sim.collide(p, world, 0.0, -0.0784000015258789, -0.3, this.out);
        assertEquals(0x0000000000000000L, Double.doubleToRawLongBits(this.out[0]));
        assertEquals(0x3fe0000000000000L, Double.doubleToRawLongBits(this.out[1]));
        assertEquals(0xbfd3333333333333L, Double.doubleToRawLongBits(this.out[2]));

        p.attributes.stepHeight.setBaseValue(0.0);
        this.sim.collide(p, world, 0.0, -0.0784000015258789, -0.3, this.out);
        assertEquals(0.0, this.out[0]);
        assertEquals(0.0, this.out[1]);
        assertEquals(Double.doubleToRawLongBits(1.0 - (1.35 - (double) (0.6F / 2.0F))), Double.doubleToRawLongBits(this.out[2]));
    }

    @Test
    void movesUnderOneTenMillionthAreZeroedNextToBlocks() {
        // Shapes.collide returns 0 once |distance| < 1e-7 (Shapes.java:223-233): the floor is always a collider, so a
        // 5.375e-8 sideways move is dropped; Moonrise keeps it (report 07 §1.6)
        final SimTestWorld world = new SimTestWorld().floor(63, Blocks.STONE.defaultBlockState());
        final SimPlayer p = box(0.5, 64.0, 0.5);
        this.sim.collide(p, world, 5.375e-8, -0.0784000015258789, 0.0, this.out);
        assertEquals(0.0, this.out[0]);
        assertEquals(0.0, this.out[1]);
        // with nothing near, the move is kept as is
        final SimPlayer free = box(0.5, 80.0, 0.5);
        this.sim.collide(free, world, 5.375e-8, 0.0, 0.0, this.out);
        assertEquals(5.375e-8, this.out[0]);
    }

    @Test
    void cardinalYawNoiseIsZeroedOnTheGround() {
        // at yaw 90 Mth.cos gives (float) sin(pi) = 1.2246469e-16, so walking adds ~1e-17 to z each tick: vanilla zeroes it
        final SimTestWorld world = new SimTestWorld().floor(63, Blocks.STONE.defaultBlockState());
        final SimPlayer p = box(0.5, 64.0, 0.5);
        this.sim.collide(p, world, -0.1, -0.0784000015258789, 1.2E-17, this.out);
        assertEquals(-0.1, this.out[0]);
        assertEquals(0.0, this.out[2]);
    }

    @Test
    void solidEntityBoxesCollide() {
        final SimTestWorld world = new SimTestWorld().floor(63, Blocks.STONE.defaultBlockState());
        world.entityBoxes.add(new AABB(-0.5, 64.0, 1.0, 1.5, 64.5625, 2.0));
        final SimPlayer p = box(0.5, 64.0, 0.5);
        // walking into a boat-height box steps up onto it (0.5625 < 0.6)
        this.sim.collide(p, world, 0.0, -0.0784000015258789, 0.5, this.out);
        assertEquals(0.5625, this.out[1]);
        assertEquals(0.5, this.out[2]);
    }

    @Test
    void worldBorderBlocks() {
        final SimTestWorld world = new SimTestWorld().floor(63, Blocks.STONE.defaultBlockState());
        world.borderMaxX = 1.0;
        final SimPlayer p = box(0.5, 64.0, 0.5);
        // inside and close to the border: its shape at ceil(maxX) = 1 stops the box (maxX 0.5 + 0.3F)
        this.sim.collide(p, world, 0.5, 0.0, 0.0, this.out);
        assertEquals(1.0 - (0.5 + (double) (0.6F / 2.0F)), this.out[0]);
    }

    @Test
    void stepHeightAttributeRaisesTheStep() {
        final SimTestWorld world = new SimTestWorld().floor(63, Blocks.STONE.defaultBlockState()).set(0, 64, 1, Blocks.STONE.defaultBlockState());
        final SimPlayer p = box(0.5, 64.0, 0.5);
        this.sim.collide(p, world, 0.0, -0.0784000015258789, 0.3, this.out);
        assertEquals(0.0, this.out[1]); // a full block is over the 0.6 step
        this.sim.applyAttributeUpdate(p, Attributes.STEP_HEIGHT, 1.0, List.of());
        this.sim.collide(p, world, 0.0, -0.0784000015258789, 0.3, this.out);
        assertEquals(1.0, this.out[1]);
    }
}
