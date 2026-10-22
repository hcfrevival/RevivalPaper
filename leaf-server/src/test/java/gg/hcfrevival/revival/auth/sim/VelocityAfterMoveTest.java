package gg.hcfrevival.revival.auth.sim;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import gg.hcfrevival.revival.RevivalRegistries;
import gg.hcfrevival.revival.RevivalTest;
import java.lang.management.ManagementFactory;
import java.lang.reflect.Field;
import java.lang.reflect.Modifier;
import java.util.Arrays;
import net.minecraft.world.entity.player.Input;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import org.junit.jupiter.api.Test;

/**
 * {@link MovementSim#velocityAfterMove} (auth-sim-requests.md §1): every tick here runs the port, then asks for its
 * velocity three ways from the state before it, whose position, velocity and rotation are poisoned with NaN: from the
 * positions, from the positions and the tick's keys, and from the exact move. Every axis reported determined must be
 * the tick's velocity bit for bit, and the state must come back untouched. The scenes then check which cases are
 * determined and why the others are not; the last test does the same on every tick of the fuzz script runs.
 */
@RevivalTest
@RevivalRegistries
class VelocityAfterMoveTest {

    private static final Input NONE = Input.EMPTY;
    private static final Input W = new Input(true, false, false, false, false, false, false);
    private static final Input WC = new Input(true, false, false, false, false, true, false);
    private static final Input WR = new Input(true, false, false, false, false, false, true);
    private static final BlockState STONE = Blocks.STONE.defaultBlockState();
    /** The reasons with no bound on the error. */
    private static final int HARD = MovementSim.VELOCITY_COLLIDED | MovementSim.VELOCITY_EDGE | MovementSim.VELOCITY_KEYS
        | MovementSim.VELOCITY_PRE_MOVE | MovementSim.VELOCITY_NO_TICK;

    private final MovementSim sim = new MovementSim();
    private final SimTickInput in = new SimTickInput();
    private final Probe probe = new Probe();

    /** Before, after: the three answers and their checks around one tick. */
    static final class Probe implements MovementSimFuzzReplayTest.TickCheck {
        private final SimPlayer before = new SimPlayer();
        private final SimPlayer untouched = new SimPlayer();
        private final double[] move = new double[3];
        private double fromX;
        private double fromY;
        private double fromZ;
        final double[] positions = new double[3];
        final double[] keyed = new double[3];
        final double[] exact = new double[3];
        int positionsFlags;
        int keyedFlags;
        int exactFlags;
        long wrong;
        long ticks;
        final long[] determined = new long[3];

        @Override
        public void before(final SimPlayer p) {
            this.before.copyFrom(p);
            this.fromX = p.x;
            this.fromY = p.y;
            this.fromZ = p.z;
            this.before.x = Double.NaN;
            this.before.y = Double.NaN;
            this.before.z = Double.NaN;
            this.before.vx = Double.NaN;
            this.before.vy = Double.NaN;
            this.before.vz = Double.NaN;
            this.before.yRot = Float.NaN;
            this.before.xRot = Float.NaN;
            this.untouched.copyFrom(this.before);
        }

        @Override
        public void after(final MovementSim sim, final SimPlayer p, final Input keys, final SimWorld world) {
            sim.lastMove(this.move);
            this.positionsFlags = sim.velocityAfterMove(this.before, this.fromX, this.fromY, this.fromZ, p.x, p.y, p.z, world, this.positions);
            this.keyedFlags = sim.velocityAfterMove(this.before, keys, this.fromX, this.fromY, this.fromZ, p.x, p.y, p.z, world, this.keyed);
            this.exactFlags = sim.velocityAfterExactMove(this.before, this.fromX, this.fromY, this.fromZ, this.move[0], this.move[1], this.move[2], world,
                this.exact);
            this.ticks++;
            this.check(this.positionsFlags, this.positions, p, 0);
            this.check(this.keyedFlags, this.keyed, p, 1);
            this.check(this.exactFlags, this.exact, p, 2);
            assertUnchanged(this.untouched, this.before);
            assertEquals(0, this.keyedFlags & MovementSim.VELOCITY_KEYS, "the keys were given");
            assertEquals(0, this.exactFlags & MovementSim.VELOCITY_ROUNDED, "the move was exact");
        }

        private void check(final int flags, final double[] out, final SimPlayer p, final int variant) {
            final double[] v = {p.vx, p.vy, p.vz};
            boolean all = true;
            for (int a = 0; a < 3; a++) {
                if ((flags & (1 << a)) == 0) {
                    if (Double.doubleToRawLongBits(out[a]) != Double.doubleToRawLongBits(v[a])) {
                        this.wrong++;
                    }
                } else {
                    all = false;
                }
            }
            if (all) {
                this.determined[variant]++;
            }
        }

        boolean determined(final int flags, final int axis) {
            return (flags & axis) == 0;
        }
    }

    private static SimPlayer at(final double x, final double y, final double z) {
        final SimPlayer p = new SimPlayer();
        p.setPos(x, y, z);
        return p;
    }

    /** One tick, the three answers from the state before it, and every determined axis checked. */
    private void tick(final SimPlayer p, final SimWorld world, final Input keys, final float yaw) {
        this.probe.before(p);
        assertEquals(ExemptReason.NONE, this.sim.tick(p, this.in.set(keys, yaw, 0.0F), world));
        this.probe.after(this.sim, p, keys, world);
        assertEquals(0L, this.probe.wrong, "an axis reported determined differs from the tick's velocity");
    }

    @Test
    void aFallFromRestIsExactFromItsMove() {
        // the seed case: a client joins in the air at y = 0 and falls. Every axis is exact from the exact move; from the
        // positions only the rounding of from + move hides anything (y = 0 + move is exact, so the first falling tick is)
        final SimTestWorld world = new SimTestWorld().floor(-60, STONE);
        final SimPlayer p = at(0.5, 0.0, 0.5);
        for (int t = 0; t < 80 && !p.onGround; t++) {
            this.tick(p, world, NONE, 0.0F);
            if (p.onGround) {
                break;
            }
            assertEquals(0, this.probe.exactFlags, "tick " + t);
            assertEquals(0, this.probe.positionsFlags & HARD, "tick " + t);
            assertEquals(p.vy, this.probe.positions[1], Math.ulp(p.y) * 2.0, "tick " + t);
            if (t <= 1) {
                assertTrue(this.probe.determined(this.probe.positionsFlags, MovementSim.VELOCITY_Y), "tick " + t);
                assertEquals(p.vy, this.probe.positions[1]);
            }
        }
        assertTrue(p.onGround);
        assertEquals(-59.0, p.y);
    }

    @Test
    void standingStillPinsYButNotWhatAStillAxisHides() {
        final SimTestWorld world = new SimTestWorld().floor(63, STONE);
        final SimPlayer p = at(0.5, 64.0, 0.5);
        for (int t = 0; t < 4; t++) {
            this.tick(p, world, NONE, 0.0F);
        }
        assertTrue(p.onGround);
        for (final int flags : new int[] {this.probe.positionsFlags, this.probe.keyedFlags, this.probe.exactFlags}) {
            assertTrue(this.probe.determined(flags, MovementSim.VELOCITY_Y));
            // x and z did not move: -0.0 or a velocity under collide's 1e-7 cut would not show, so they are only bounded
            assertFalse(this.probe.determined(flags, MovementSim.VELOCITY_X));
            assertNotEquals(0, flags & MovementSim.VELOCITY_UNMOVED);
            assertEquals(0, flags & HARD);
        }
        assertEquals(-0.0784000015258789, this.probe.exact[1]);
        assertEquals(0.0, this.probe.exact[0]);
    }

    @Test
    void aWallLeavesThatAxisToTheCollision() {
        // walking into a wall: once it stops the box, z holds -0.0 (Entity.restituteMovementAfterCollisions) whatever the
        // push was, but a still axis next to a wall may also have pushed nothing (0.0)
        final SimTestWorld world = new SimTestWorld().floor(63, STONE);
        for (int x = -8; x <= 8; x++) {
            world.set(x, 64, 2, STONE).set(x, 65, 2, STONE);
        }
        final SimPlayer p = at(0.5, 64.0, 0.5);
        this.tick(p, world, NONE, 0.0F);
        boolean pushed = false;
        for (int t = 0; t < 30; t++) {
            this.tick(p, world, W, 0.0F);
            if (p.horizontalCollision) {
                pushed = true;
                assertEquals(2.0 - 0.3F, p.z, 1.0E-9); // the box's half width is 0.3F
                assertFalse(this.probe.determined(this.probe.keyedFlags, MovementSim.VELOCITY_Z));
                assertNotEquals(0, this.probe.keyedFlags & MovementSim.VELOCITY_COLLIDED);
                assertTrue(this.probe.determined(this.probe.exactFlags, MovementSim.VELOCITY_Y));
                assertEquals(-0.0784000015258789, this.probe.exact[1]);
                assertEquals(-0.0, p.vz);
            }
        }
        assertTrue(pushed);
    }

    @Test
    void aLandingIsCollidedButMostLikelyACut() {
        // the landing tick: the move could also be a fall that ended exactly on the floor, so y is not determined; the
        // value returned is the cut (the player stays on the ground), which is the client's
        final SimTestWorld world = new SimTestWorld().floor(63, STONE);
        final SimPlayer p = at(0.5, 66.3, 0.5);
        boolean landed = false;
        for (int t = 0; t < 20 && !landed; t++) {
            this.tick(p, world, NONE, 0.0F);
            if (p.onGround) {
                landed = true;
                assertFalse(this.probe.determined(this.probe.exactFlags, MovementSim.VELOCITY_Y));
                assertNotEquals(0, this.probe.exactFlags & MovementSim.VELOCITY_COLLIDED);
                assertEquals(p.vy, this.probe.exact[1]);
            }
        }
        assertTrue(landed);
        // on slime the impact speed is the bounce, which the move cannot show
        final SimTestWorld slime = new SimTestWorld().floor(63, Blocks.SLIME_BLOCK.defaultBlockState());
        final SimPlayer q = at(0.5, 67.3, 0.5);
        landed = false;
        for (int t = 0; t < 20 && !landed; t++) {
            this.tick(q, slime, NONE, 0.0F);
            if (q.verticalCollisionBelow) {
                landed = true;
                assertFalse(this.probe.determined(this.probe.exactFlags, MovementSim.VELOCITY_Y));
                assertTrue(q.vy > 0.0); // bounced
            }
        }
        assertTrue(landed);
    }

    @Test
    void sprintingInWaterNeedsTheTicksKeys() {
        // travelInWater's slow-down and falling adjustment read the sprint flag the tick's keys set
        final SimTestWorld world = new SimTestWorld().floor(58, STONE);
        for (int x = -8; x <= 8; x++) {
            for (int z = -8; z <= 8; z++) {
                for (int y = 59; y <= 63; y++) {
                    world.set(x, y, z, Blocks.WATER.defaultBlockState());
                }
            }
        }
        final SimPlayer p = at(0.5, 60.0, 0.5);
        boolean needed = false;
        for (int t = 0; t < 20; t++) {
            this.tick(p, world, t < 10 ? WR : W, 0.0F);
            needed |= (this.probe.positionsFlags & MovementSim.VELOCITY_KEYS) != 0;
        }
        assertTrue(p.isInWater());
        assertTrue(needed);
    }

    @Test
    void sneakingToAnEdgeIsEdge() {
        // Player.maybeBackOffFromEdge stops the move at the edge while the velocity keeps pushing past it
        final SimTestWorld world = new SimTestWorld();
        for (int x = -8; x <= 8; x++) {
            for (int z = -8; z <= 0; z++) {
                world.set(x, 63, z, STONE);
            }
        }
        final SimPlayer p = at(0.5, 64.0, 0.5);
        this.tick(p, world, NONE, 0.0F);
        boolean edge = false;
        for (int t = 0; t < 30; t++) {
            this.tick(p, world, WC, 0.0F);
            edge |= (this.probe.keyedFlags & MovementSim.VELOCITY_EDGE) != 0;
        }
        assertTrue(p.onGround);
        assertTrue(p.z < 1.31);
        assertTrue(edge);
    }

    @Test
    void anExemptStateHasNoTick() {
        final SimTestWorld world = new SimTestWorld().floor(63, STONE);
        final SimPlayer p = at(0.5, 64.0, 0.5);
        p.passenger = true;
        final double[] out = new double[3];
        assertEquals(MovementSim.VELOCITY_AXES | MovementSim.VELOCITY_NO_TICK, this.sim.velocityAfterMove(p, 0.5, 64.0, 0.5, 0.5, 64.0, 0.5, world, out));
        assertTrue(Double.isNaN(out[1]));
        // a move no tick makes: half a block into the floor (any velocity, knockback included, stops on it)
        p.passenger = false;
        assertEquals(MovementSim.VELOCITY_AXES | MovementSim.VELOCITY_NO_TICK, this.sim.velocityAfterMove(p, 0.5, 64.0, 0.5, 0.5, 63.5, 0.5, world, out));
    }

    @Test
    void allocatesNothingOnceWarm() {
        final SimTestWorld world = new SimTestWorld().floor(63, STONE);
        final SimPlayer p = at(0.5, 64.0, 0.5);
        for (int t = 0; t < 3; t++) {
            this.tick(p, world, W, 30.0F);
        }
        final SimPlayer before = new SimPlayer();
        before.copyFrom(p);
        this.sim.tick(p, this.in.set(W, 30.0F, 0.0F), world);
        final double[] out = new double[3];
        for (int i = 0; i < 200; i++) {
            this.sim.velocityAfterMove(before, before.x, before.y, before.z, p.x, p.y, p.z, world, out);
            this.sim.velocityAfterMove(before, W, before.x, before.y, before.z, p.x, p.y, p.z, world, out);
        }
        final com.sun.management.ThreadMXBean threads = (com.sun.management.ThreadMXBean) ManagementFactory.getThreadMXBean();
        final long bytes0 = threads.getCurrentThreadAllocatedBytes();
        for (int i = 0; i < 200; i++) {
            this.sim.velocityAfterMove(before, before.x, before.y, before.z, p.x, p.y, p.z, world, out);
            this.sim.velocityAfterMove(before, W, before.x, before.y, before.z, p.x, p.y, p.z, world, out);
        }
        final long bytes = threads.getCurrentThreadAllocatedBytes() - bytes0;
        assertTrue(bytes < 400, bytes + " bytes in 400 calls");
    }

    @Test
    void everyDeterminedAxisIsTheTicksVelocityOnTheFuzzScript() {
        // every tick of the fuzz script runs (MovementSimFuzzReplayTest), each checked like the scenes above; and the runs
        // still hash like vanilla's, so asking between ticks leaves the simulation alone
        final Probe check = new Probe();
        for (int run = 0; run < FuzzData.HASHES.length; run++) {
            assertEquals(FuzzData.HASHES[run], MovementSimFuzzReplayTest.play(FuzzData.SEED, run, check), "run " + run);
        }
        assertEquals(0L, check.wrong, "axes reported determined that differ from the tick's velocity");
        assertEquals(FuzzData.HASHES.length * (long) FuzzScript.TICKS, check.ticks);
        System.out.println("velocityAfterMove on " + check.ticks + " fuzz-script ticks: determined on every axis from positions "
            + check.determined[0] + ", with the keys " + check.determined[1] + ", from the exact move " + check.determined[2] + "; wrong "
            + check.wrong);
    }

    // ------------------------------------------------------------------------------------------------ helpers

    /** Every field of the state, bit for bit (doubles and floats by raw bits, nested state by its own fields). */
    static void assertUnchanged(final SimPlayer expected, final SimPlayer actual) {
        try {
            compare(SimPlayer.class, expected, actual, "");
        } catch (final IllegalAccessException e) {
            throw new AssertionError(e);
        }
    }

    private static void compare(final Class<?> type, final Object a, final Object b, final String path) throws IllegalAccessException {
        for (final Field f : type.getDeclaredFields()) {
            if (Modifier.isStatic(f.getModifiers())) {
                continue;
            }
            f.setAccessible(true);
            final Object x = f.get(a);
            final Object y = f.get(b);
            final String name = path + f.getName();
            if (x instanceof Double dx) {
                assertEquals(Double.doubleToRawLongBits(dx), Double.doubleToRawLongBits((Double) y), name);
            } else if (x instanceof Float fx) {
                assertEquals(Float.floatToRawIntBits(fx), Float.floatToRawIntBits((Float) y), name);
            } else if (x instanceof double[] ax) {
                assertTrue(Arrays.equals(ax, (double[]) y), name);
            } else if (x instanceof boolean[] ax) {
                assertTrue(Arrays.equals(ax, (boolean[]) y), name);
            } else if (x instanceof int[] ax) {
                assertTrue(Arrays.equals(ax, (int[]) y), name);
            } else if (x instanceof SimPlayer.Movements mx) {
                // copyFrom copies the used part only
                final SimPlayer.Movements my = (SimPlayer.Movements) y;
                assertEquals(mx.size, my.size, name + ".size");
                assertTrue(Arrays.equals(mx.from, 0, mx.size * 3, my.from, 0, mx.size * 3), name + ".from");
                assertTrue(Arrays.equals(mx.to, 0, mx.size * 3, my.to, 0, mx.size * 3), name + ".to");
            } else if (x instanceof SimBox || x instanceof SimFluids.State) {
                compare(x.getClass(), x, y, name + ".");
            } else if (x instanceof SimAttributes ax) {
                final SimAttributes ay = (SimAttributes) y;
                for (final Field g : SimAttributes.class.getDeclaredFields()) {
                    if (g.getType() == SimAttributes.Instance.class && !Modifier.isStatic(g.getModifiers())) {
                        g.setAccessible(true);
                        final SimAttributes.Instance ix = (SimAttributes.Instance) g.get(ax);
                        final SimAttributes.Instance iy = (SimAttributes.Instance) g.get(ay);
                        assertEquals(Double.doubleToRawLongBits(ix.value()), Double.doubleToRawLongBits(iy.value()), name + "." + g.getName());
                    }
                }
            } else if (x instanceof Boolean || x instanceof Integer || x instanceof Enum<?>) {
                assertEquals(x, y, name);
            } else {
                assertTrue(x == y, name); // items, keys: the same objects
            }
        }
    }
}
