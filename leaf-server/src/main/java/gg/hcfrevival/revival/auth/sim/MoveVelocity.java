package gg.hcfrevival.revival.auth.sim;

import net.minecraft.util.Mth;
import net.minecraft.world.entity.player.Input;
import org.jspecify.annotations.Nullable;

/**
 * {@link MovementSim#velocityAfterMove}: the velocity a vanilla 26.3 client holds at the end of a tick, worked out from
 * the tick's move and the state the tick started from, with the port's own code: the tick's start
 * ({@link MovementSim#startTick}), its aiStep up to the move ({@link MovementSim#probeMove}), then the after-move half
 * ({@link MovementSim#afterCollide}, the travel branch's {@code after*Move}, {@link MovementSim#afterFlyingTravel},
 * {@link SimBlocks#applyEffectsFromBlocks}). Nothing here restates a movement formula.
 * <p>
 * The after-move half needs three things the state before the tick does not hold, so it runs once per possibility and
 * an axis counts as determined only when every run gives the same bits:
 * <ul>
 *   <li><b>The tick's keys</b>: forward, jump, shift and sprint can change the state the move starts from (sprint,
 *       jumping, the shift that the bounce and scaffolding read, a double-tap flight, a glide started this tick, and the
 *       branch's own values, like the water slow-down that sprint picks). All 16 combinations run the port's aiStep up
 *       to the move; the distinct states are kept ({@link MovementSim#VELOCITY_KEYS} when they disagree). Backward,
 *       left and right change none of these.
 *   <li><b>What collide was given</b>, the velocity before the move (Entity.move: {@code xCollision} is
 *       {@code !Mth.equal(given, moved)}, restitution reads the velocity that was cut). Per axis: the move itself
 *       (free); when a collider touches the face the box ended on, cuts under Mth.equal's 1e-5 (no collision flag but a
 *       velocity that is not the move; one only ulps deep, since a longer one can swap collide's x/z order), just past
 *       it and far past it, and where a bounce or the flight rule can carry the depth into the velocity (a slime or bed
 *       under the box, the bounciness attribute, creative flight) 39 depths from 1e-5 to 3.6; for y also a step-up,
 *       from a fall or from a rise of several heights (what it ran into sideways decides the step), and from a fall of
 *       those 39 depths when nothing holds up a state on the ground any more (a boat that drove off or rose around its
 *       feet: the fall is not cut, and its depth decides what the box meets sideways before the step). The world
 *       border counts at any distance (collide gathers it only for a move long enough to reach it). Each combination
 *       goes through the port's own {@link SimCollision#collide} and is kept only if it lands exactly on the observed
 *       move ({@link MovementSim#VELOCITY_COLLIDED} when kept ones disagree).
 *   <li><b>What a zero or a rounded move hides</b>: an axis that did not move may hold the other signed zero, a
 *       velocity under collide's 1e-7 cut, or, from positions, one under Entity.move's 3.2e-4 threshold when another axis
 *       collided ({@link MovementSim#VELOCITY_UNMOVED}); and {@code to - from} is only one of the moves that
 *       {@code from + move} rounds to {@code to}, so the two ends of that range run too
 *       ({@link MovementSim#VELOCITY_ROUNDED}). Each is tried around every reading that lands, in every state.
 * </ul>
 * Plus two rules that cannot run from the move: Player.maybeBackOffFromEdge may have shortened a sneaking move, which
 * leaves the velocity longer than the move (checked by whether ground lies under every box the back-off could have
 * tested, {@link MovementSim#VELOCITY_EDGE}); and Player.travel's flight rule keeps a y from before the branch that a
 * glide or a consumed stuck multiplier changed before the move, so that y runs over a range and every axis it reaches
 * (honey and slime read it) is open ({@link MovementSim#VELOCITY_PRE_MOVE}).
 * <p>
 * shortcut: the depths of a cut, the rises and the zero cases are samples, not every value. The rules after a move read
 * what collide was given only through restitution (a bounce: linear in it, after a gravity threshold), the flight rule
 * (linear), travelInFluid's falling test (its sign) and the order of the inside-block walk (which of x and z is larger,
 * which the far depth flips). Without a bounce or flight only the cut's sign counts, so a few depths do; with one, the
 * bounced velocity meets more thresholds (slime scales x and z under |vy| 0.1), so the depths are dense. The oracle fuzz
 * checks the claim.
 * <p>
 * Not thread-safe; one per {@link MovementSim}, created on its first use (that allocates the scratch players).
 */
final class MoveVelocity {

    /** A cut this far past the face: under Mth.equal's 9.999999747378752E-6, so no collision flag. */
    private static final double NEAR = 5.0E-6;
    /** A cut just past Mth.equal's threshold (9.999999747378752E-6), and one a little further. */
    private static final double JUST_CUT = 1.0E-5;
    private static final double SHORT_CUT = 2.0E-5;
    /** A cut far past it: past any gravity (the attribute is at most 1), so a bounce that depends on it shows. */
    private static final double LONG_CUT = 2.0;
    /** Rises above a stepped move, from just over it to a jump (0.42) and past the step height. */
    private static final double[] RISES = {SHORT_CUT, 0.1, 0.25, 0.41999998688697815, LONG_CUT};
    /** Player.travel's y before a glide or a stuck multiplier changed it: 0 and ±1e-3 to ±3.3 in steps of x1.5. */
    private static final double[] BEFORE_GLIDE = new double[43];

    static {
        for (int i = 0; i < 21; i++) {
            BEFORE_GLIDE[2 * i] = 1.0E-3 * Math.pow(1.5, i);
            BEFORE_GLIDE[2 * i + 1] = -BEFORE_GLIDE[2 * i];
        }
    }

    /** Depths of a cut where the depth shows in the velocity: 1e-5 to 3.6 in steps of x1.4. */
    private static final double[] DEPTHS = new double[39];

    static {
        for (int i = 0; i < DEPTHS.length; i++) {
            DEPTHS[i] = 1.0E-5 * Math.pow(1.4, i);
        }
    }
    /** Under Shapes.collide's 1e-7 early exit (Shapes.java:223-233): collide returns 0 once anything is in reach. */
    private static final double UNDER_CUT = 5.0E-8;
    /** Under Entity.move's 1e-7 squared length (Entity.java:775): not applied when another axis lost more. */
    private static final double UNAPPLIED = 1.0E-4;
    /** A collider this close to the face the box ended on may have stopped it there (collideX allows 1e-7 of overlap). */
    private static final double FACE = 1.0E-6;
    /**
     * From positions, past the face too: a move Entity.move did not apply (under sqrt(1e-7) = 3.2e-4) left the box at
     * {@code from}, short of the collider that cut it.
     */
    private static final double UNAPPLIED_FACE = 4.0E-4;
    /** Player.maybeBackOffFromEdge's step (Player.java:873-875), and a margin for its repeated subtraction. */
    private static final double BACK_OFF = 0.05 + 1.0E-7;

    /** The keys that can change the state a move starts from: forward (bit 0), jump (1), shift (2), sprint (3). */
    private static final Input[] KEYS = new Input[16];

    static {
        for (int c = 0; c < KEYS.length; c++) {
            KEYS[c] = new Input((c & 1) != 0, false, false, false, (c & 2) != 0, (c & 4) != 0, (c & 8) != 0);
        }
    }

    private final MovementSim sim;
    private final SimPlayer start = new SimPlayer();
    private final SimPlayer scrap = new SimPlayer();
    private final SimPlayer work = new SimPlayer();
    private final SimTickInput input = new SimTickInput();
    private final SimBox after = new SimBox();
    private final SimBox box = new SimBox();
    // the distinct states a move can start from, and their travel branches
    private SimPlayer[] states = new SimPlayer[0];
    private MovementSim.Travel[] travels = new MovementSim.Travel[0];
    // a state only the glide clamp's second pass gave: it differs by what the clamp read, not by the keys
    private boolean[] unclamped = new boolean[0];
    private int stateCount;
    // per axis (x, y, z): what collide may have been given
    private final double[][] given = new double[3][256];
    private final int[] counts = new int[3];
    // this state's readings that land, as candidate indices (x, y, z), and the velocity each gave
    private int[] landing = new int[3 * 16];
    private double[] readingVelocity = new double[3 * 16];
    private int landingCount;
    private SimWorld world;
    private boolean exact;
    private final double[] from = new double[3];
    private final double[] to = new double[3];
    private final double[] move = new double[3];
    private final double[] low = new double[3];
    private final double[] high = new double[3];
    private final double[] landed = new double[3];
    private final double[] best = new double[3];
    private final double[] bestGiven = new double[3];
    private final double[] velocity = new double[3];
    private final double[] trial = new double[3];
    private final double[] probe = new double[3];

    MoveVelocity(final MovementSim sim) {
        this.sim = sim;
    }

    /**
     * {@link MovementSim#velocityAfterMove} ({@code a, b, c} is {@code to}), or with {@code exact} the move itself
     * ({@link MovementSim#velocityAfterExactMove}). {@code keys}: the tick's sent keys, or null when unknown (every
     * combination that matters runs).
     */
    int after(final SimPlayer p, final @Nullable Input keys, final double fromX, final double fromY, final double fromZ, final double a,
              final double b, final double c, final boolean exact, final SimWorld world, final double[] out) {
        out[0] = Double.NaN;
        out[1] = Double.NaN;
        out[2] = Double.NaN;
        if (MovementSim.exemptReason(p) != ExemptReason.NONE) {
            return MovementSim.VELOCITY_AXES | MovementSim.VELOCITY_NO_TICK;
        }
        this.world = world;
        this.exact = exact;
        this.from[0] = fromX;
        this.from[1] = fromY;
        this.from[2] = fromZ;
        if (exact) {
            this.move[0] = a;
            this.move[1] = b;
            this.move[2] = c;
        } else {
            this.to[0] = a;
            this.to[1] = b;
            this.to[2] = c;
            for (int i = 0; i < 3; i++) {
                this.move[i] = between(this.from[i], this.to[i]);
                if (Double.isNaN(this.move[i])) {
                    return MovementSim.VELOCITY_AXES | MovementSim.VELOCITY_NO_TICK;
                }
                this.low[i] = bound(this.from[i], this.to[i], this.move[i], -1.0);
                this.high[i] = bound(this.from[i], this.to[i], this.move[i], 1.0);
            }
        }
        this.sim.collision.world(world);

        // the tick's start at `from`, without the velocity (it only scales a current's push, which lands in the velocity
        // the move was given) or the keys (startTick reads none)
        final SimPlayer start = this.start;
        start.copyFrom(p);
        start.setPos(fromX, fromY, fromZ);
        start.vx = 0.0;
        start.vy = 0.0;
        start.vz = 0.0;
        this.sim.startTick(start, world);

        // every state the move can start from: the tick's keys, or every combination. updateFallFlying's fall clamp
        // (LivingEntity.java:3188-3189, Entity.java:3021-3025) reads the velocity: with a fall over 1 and a glide possible,
        // run the keys again falling faster than 0.5 (no clamp)
        this.stateCount = 0;
        int held = 0;
        final int heldKeys = keys(p.keys.forward() && !p.keys.backward(), p.keys.jump(), p.keys.shift(), p.keys.sprint());
        final int passes = start.fallDistance > 1.0 && (start.fallFlying || start.gliderAvailable) ? 2 : 1;
        final int combinations = keys == null ? KEYS.length : 1;
        for (int pass = 0; pass < passes; pass++) {
            for (int k = 0; k < combinations; k++) {
                this.scrap.copyFrom(start);
                if (pass == 1) {
                    this.scrap.vy = -1.0;
                }
                this.room(this.stateCount + 1);
                final SimPlayer into = this.states[this.stateCount];
                if (!this.sim.probeMove(this.scrap, this.input.set(keys == null ? KEYS[k] : keys, p.yRot, p.xRot), world, into)) {
                    continue;
                }
                this.travels[this.stateCount].copyFrom(this.sim.probeTravel);
                int same = -1;
                for (int i = 0; i < this.stateCount && same < 0; i++) {
                    if (sameStart(this.states[i], this.travels[i], into, this.travels[this.stateCount])) {
                        same = i;
                    }
                }
                if (same < 0) {
                    this.unclamped[this.stateCount] = pass == 1;
                    same = this.stateCount++;
                }
                if (pass == 0 && (keys != null || k == heldKeys)) {
                    held = same;
                }
            }
        }

        // the state the held keys give first: its first reading that lands is the most likely value
        int result = 0;
        int first = -1;
        for (int n = -1; n < this.stateCount; n++) {
            final int i = n < 0 ? held : n;
            if (n >= 0 && i == held) {
                continue;
            }
            final SimPlayer s = this.states[i];
            final MovementSim.Travel t = this.travels[i];
            this.candidates(s, t);
            this.landingCount = 0;
            for (int iy = 0; iy < this.counts[1]; iy++) {
                for (int ix = 0; ix < this.counts[0]; ix++) {
                    for (int iz = 0; iz < this.counts[2]; iz++) {
                        final double dx = this.given[0][ix];
                        final double dy = this.given[1][iy];
                        final double dz = this.given[2][iz];
                        if (!this.lands(s, dx, dy, dz)) {
                            continue;
                        }
                        this.run(s, t, dx, dy, dz);
                        if (first < 0) {
                            first = i;
                            System.arraycopy(this.velocity, 0, this.best, 0, 3);
                            this.bestGiven[0] = dx;
                            this.bestGiven[1] = dy;
                            this.bestGiven[2] = dz;
                        } else {
                            final int differ = this.differ(this.best, 0);
                            if (differ != 0) {
                                result |= differ;
                                if (i != first) {
                                    result |= this.unclamped[i] ? MovementSim.VELOCITY_PRE_MOVE : MovementSim.VELOCITY_KEYS;
                                }
                                if (!same(dx, this.bestGiven[0]) || !same(dy, this.bestGiven[1]) || !same(dz, this.bestGiven[2])) {
                                    result |= MovementSim.VELOCITY_COLLIDED;
                                }
                            }
                        }
                        this.landing(ix, iy, iz);
                        if (t.flying && (t.kind == MovementSim.Travel.GLIDE || MovementSim.isStuck(s))) {
                            // Player.travel's y before the branch is not the move's: a glide or a stuck multiplier changed
                            // it first. Every axis it reaches (the flight rule, then honey or slime reading that y) is open
                            final int at = (this.landingCount - 1) * 3;
                            for (final double before : BEFORE_GLIDE) {
                                this.run(s, t, dx, dy, dz, before);
                                final int differ = this.differ(this.readingVelocity, at);
                                if (differ != 0) {
                                    result |= differ | MovementSim.VELOCITY_PRE_MOVE;
                                }
                            }
                        }
                        if (this.backedOff(s, dx, dy, dz)) {
                            // the velocity kept what the back-off took off the move; in a fluid, jumpOutOfFluid's isFree
                            // box is moved by that x and z (LivingEntity.java:2562-2567), so y may depend on it too
                            final boolean fluid = t.kind == MovementSim.Travel.WATER || t.kind == MovementSim.Travel.LAVA;
                            result |= MovementSim.VELOCITY_X | MovementSim.VELOCITY_Z | MovementSim.VELOCITY_EDGE | (fluid ? MovementSim.VELOCITY_Y : 0);
                        }
                    }
                }
            }
            result |= this.hidden(s, t);
        }
        if (first < 0) {
            return MovementSim.VELOCITY_AXES | MovementSim.VELOCITY_NO_TICK;
        }
        out[0] = this.best[0];
        out[1] = this.best[1];
        out[2] = this.best[2];
        return result;
    }

    /**
     * What a move of zero or a rounded move hides, around this state's readings that land (its candidates are current),
     * each against the reading's own velocity: the other signed zero and the sub-threshold velocities on an axis that did
     * not move, and the two ends of the rounding range of each free axis of its first reading.
     */
    private int hidden(final SimPlayer s, final MovementSim.Travel t) {
        int result = 0;
        for (int h = 0; h < this.landingCount; h++) {
            for (int a = 0; a < 3; a++) {
                if (!same(this.given[a][this.landing[h * 3 + a]], this.move[a]) || this.move[a] != 0.0) {
                    continue; // only where collide was given the move itself, and it is zero
                }
                for (int v = 0; v < (this.exact ? 3 : 5); v++) {
                    final double value = switch (v) {
                        case 0 -> -this.move[a];
                        case 1 -> UNDER_CUT;
                        case 2 -> -UNDER_CUT;
                        case 3 -> UNAPPLIED;
                        default -> -UNAPPLIED;
                    };
                    this.reading(h);
                    this.trial[a] = value;
                    if (this.lands(s, this.trial[0], this.trial[1], this.trial[2])) {
                        this.run(s, t, this.trial[0], this.trial[1], this.trial[2]);
                        final int differ = this.differ(this.readingVelocity, h * 3);
                        if (differ != 0) {
                            result |= differ | MovementSim.VELOCITY_UNMOVED;
                        }
                    }
                }
            }
        }
        // each free axis at both ends of its rounding range, alone (the velocity is monotonic in it, so the ends show any
        // dependence); an end that does not land cannot be checked, so the axis stays rounded
        if (!this.exact && this.landingCount > 0) {
            for (int a = 0; a < 3; a++) {
                if (!same(this.given[a][this.landing[a]], this.move[a]) || this.low[a] == this.high[a]) {
                    continue;
                }
                for (int end = 0; end < 2; end++) {
                    this.reading(0);
                    this.trial[a] = end == 0 ? this.low[a] : this.high[a];
                    if (this.lands(s, this.trial[0], this.trial[1], this.trial[2])) {
                        this.run(s, t, this.trial[0], this.trial[1], this.trial[2]);
                        final int differ = this.differ(this.readingVelocity, 0);
                        if (differ != 0) {
                            result |= differ | MovementSim.VELOCITY_ROUNDED;
                        }
                    } else {
                        result |= (1 << a) | MovementSim.VELOCITY_ROUNDED;
                    }
                }
            }
        }
        return result;
    }

    // ------------------------------------------------------------------------------------------------ the readings

    /**
     * What collide may have been given on each axis for state {@code s}: the move (free); and when a collider touches
     * the face the box ended on (in the move's direction, or either way for no move), a cut near, just past and far past
     * Mth.equal's threshold (y, compared with {@code !=}, has no near cut); for a sideways move that ends at most the step
     * height above the start also a step (see {@link #steps}). Most likely first, which picks the value
     * returned when they disagree: a move that ended against something was cut (arriving there exactly is rare), a
     * player on the ground presses down on it (gravity) or stepped up, and one that did not move on an axis stood still.
     */
    private void candidates(final SimPlayer s, final MovementSim.Travel t) {
        this.after.set(s.bb).move(this.move[0], this.move[1], this.move[2]);
        // a cut's depth shows in the velocity only through a bounce (Entity.restituteMovementAfterCollisions) or the
        // flight rule; then thresholds after it (the bounce's gravity test, slime's |vy| < 0.1) need many depths
        final boolean bouncy = s.attributes.bounciness.value() > 0.0;
        for (int a = 0; a < 3; a += 2) {
            this.counts[a] = 0;
            final double m = this.move[a];
            if (Math.abs(m) < 1.0E-7) {
                this.add(a, m);
                for (int side = 1; side >= -1; side -= 2) {
                    if (this.touches(s, a, side)) {
                        this.cuts(a, m, side, bouncy);
                    }
                }
            } else if (this.touches(s, a, m > 0.0 ? 1 : -1)) {
                this.cuts(a, m, m > 0.0 ? 1 : -1, bouncy);
                this.add(a, m);
            } else {
                this.add(a, m);
            }
        }
        final double my = this.move[1];
        final boolean still = Math.abs(my) < 1.0E-7;
        final boolean down = (still || my < 0.0) && this.touches(s, 1, -1);
        final boolean up = (still || my > 0.0) && this.touches(s, 1, 1);
        final boolean deepDown = bouncy || t.flying || this.bouncesBelow(s) || this.unsupported(s);
        final boolean deepUp = bouncy || t.flying;
        this.counts[1] = 0;
        if (down) {
            this.cuts(1, my, -1, deepDown);
        }
        if (up && !still) {
            this.cuts(1, my, 1, deepUp);
        }
        // Entity.collide's step (Entity.java:1190-1207) needs a step height and a horizontal cut; it lifts the box by at most
        // the step height from where a fall was cut, which may still be below the start
        final float stepHeight = (float) s.attributes.stepHeight.value();
        final boolean steppable = stepHeight > 0.0F && (this.move[0] != 0.0 || this.move[2] != 0.0) && my <= stepHeight;
        if (steppable && s.onGround) {
            this.steps(s, my, deepDown, deepUp);
        }
        this.add(1, my);
        if (steppable && !s.onGround) {
            this.steps(s, my, deepDown, deepUp);
        }
        if (up && still) {
            this.cuts(1, my, 1, deepUp);
        }
    }

    /**
     * What a stepped y move may have been given: a fall cut at once or far below (deeper than the move, which the step
     * lifted from there); or, from the ground, a rise lower than the step, or higher (the box rose into something
     * sideways, so collide stepped at a lower candidate, even 0). Whether a fall or a rise hits that depends on how far it
     * went, so several depths; all of them where the depth shows in the velocity.
     */
    private void steps(final SimPlayer s, final double my, final boolean deepDown, final boolean deepUp) {
        this.add(1, -SHORT_CUT);
        this.add(1, -LONG_CUT);
        if (my < 0.0) {
            this.add(1, my - (Math.abs(my) + LONG_CUT));
        }
        if (deepDown) {
            for (final double depth : DEPTHS) {
                this.add(1, Math.min(my, 0.0) - depth);
            }
        }
        if (s.onGround) {
            if (my > 0.0) {
                this.add(1, my * 0.25);
                this.add(1, my * 0.5);
                this.add(1, my * 0.75);
            }
            for (final double rise : RISES) {
                this.add(1, my + rise);
            }
            this.add(1, my + (float) s.attributes.stepHeight.value());
            if (deepUp) {
                for (final double depth : DEPTHS) {
                    this.add(1, Math.max(my, 0.0) + depth);
                }
            }
        }
    }

    /**
     * Cuts of {@code m} towards {@code dir}: just past Mth.equal's threshold, a little further, far past it, and under it
     * (by 5e-6, and by a few ulps of the position, which keeps collide's axis order where a longer cut would swap it: the
     * larger of x and z goes first). The box swept by a cut must overlap the collider to gather it (BlockCollisions), so
     * the cut goes past the face by more than the position's rounding. With {@code deep}, every depth of {@link #DEPTHS}.
     */
    private void cuts(final int axis, final double m, final int dir, final boolean deep) {
        this.add(axis, m + dir * JUST_CUT);
        this.add(axis, m + dir * SHORT_CUT);
        this.add(axis, m + dir * (Math.abs(m) + LONG_CUT));
        if (axis != 1) {
            this.add(axis, m + dir * NEAR);
            // the face is within 16 of from + m (half the widest box: 0.6 x a scale of 16 is 9.6)
            this.add(axis, m + dir * 4.0 * Math.ulp(Math.abs(this.from[axis]) + Math.abs(m) + 16.0));
        }
        if (deep) {
            for (final double depth : DEPTHS) {
                this.add(axis, m + dir * depth);
            }
        }
    }

    /**
     * Whether the box may have landed on a block that bounces (slime, beds): restitution reads the one 0.2 under the box
     * (Entity.getOnPosLegacy, by the supporting block) or the supporting block itself, somewhere under the start or the end.
     */
    private boolean bouncesBelow(final SimPlayer s) {
        final SimBox fb = s.bb;
        final SimBox tb = this.after;
        final int x0 = Mth.floor(Math.min(fb.minX, tb.minX));
        final int x1 = Mth.floor(Math.max(fb.maxX, tb.maxX));
        final int z0 = Mth.floor(Math.min(fb.minZ, tb.minZ));
        final int z1 = Mth.floor(Math.max(fb.maxZ, tb.maxZ));
        final int y0 = Mth.floor(tb.minY - 0.20000000298023224);
        final int y1 = Mth.floor(tb.minY - 1.0E-6);
        for (int x = x0; x <= x1; x++) {
            for (int z = z0; z <= z1; z++) {
                for (int y = y0; y <= y1; y++) {
                    if (this.world.blockState(x, y, z).getBlock().getBounceRestitution() > 0.0F) {
                        return true;
                    }
                }
            }
        }
        return false;
    }

    /**
     * Whether the state is on the ground but nothing holds its box up any more: what held it moved away between ticks (a
     * boat or a ghast driving off, a block the client lost), or moved into it (a boat rising around the feet, which
     * collide ignores once overlapped). Its fall is then not cut at once (the port's own collide of a short fall says so),
     * and how deep it went decides what a step meets on the way (Entity.collide steps from the ground without a vertical
     * collision), so the fall depths are dense.
     */
    private boolean unsupported(final SimPlayer s) {
        if (!s.onGround || s.noPhysics) {
            return false;
        }
        this.sim.collision.collide(s, 0.0, -SHORT_CUT, 0.0, (float) s.attributes.stepHeight.value(), this.probe);
        return this.probe[1] == -SHORT_CUT;
    }

    /** Adds a reading for {@code axis}, unless it already has the same one. */
    private void add(final int axis, final double value) {
        for (int i = 0; i < this.counts[axis]; i++) {
            if (same(this.given[axis][i], value)) {
                return;
            }
        }
        this.given[axis][this.counts[axis]++] = value;
    }

    /**
     * Whether a collider sits at the face the box ended on, on {@code axis} towards {@code dir}: only then can the axis
     * have been cut there. Shapes.collide cuts at a collider's face (overlapping by up to 1e-7), and resolves y first
     * from the start's x and z, then x and z in either order at the final y, so the slab spans where the box was when the
     * axis was resolved (for y, also where a step carried it). The border counts however far: collide gathers it for a
     * long move that a short one would not reach.
     */
    private boolean touches(final SimPlayer s, final int axis, final int dir) {
        final SimBox fb = s.bb;
        final SimBox tb = this.after;
        // the slab from just inside the face to past it, in the direction of the cut
        final double in = dir * -FACE;
        final double out = dir * (this.exact ? FACE : UNAPPLIED_FACE);
        if (axis == 1) {
            // y is resolved first, under the start; a step then carries the box sideways onto what it stands on
            final double face = dir > 0 ? tb.maxY : tb.minY;
            this.box.set(Math.min(fb.minX, tb.minX), face + in, Math.min(fb.minZ, tb.minZ), Math.max(fb.maxX, tb.maxX), face + out,
                Math.max(fb.maxZ, tb.maxZ));
        } else if (axis == 0) {
            final double face = dir > 0 ? tb.maxX : tb.minX;
            this.box.set(face + in, tb.minY, Math.min(fb.minZ, tb.minZ), face + out, tb.maxY, Math.max(fb.maxZ, tb.maxZ));
        } else {
            final double face = dir > 0 ? tb.maxZ : tb.minZ;
            this.box.set(Math.min(fb.minX, tb.minX), tb.minY, face + in, Math.max(fb.maxX, tb.maxX), tb.maxY, face + out);
        }
        return !this.sim.collision.noCollision(s, this.box) || this.sim.collision.touchesBorder(this.box);
    }

    /**
     * Whether collide, given {@code dx/dy/dz} from state {@code s}, makes the observed move: exactly the move, or, from
     * positions, a move Entity.move applies (or not) to land on {@code to} after Player.tick's clamp (Player.java:262-266).
     * Leaves collide's result in {@code landed}.
     */
    private boolean lands(final SimPlayer s, final double dx, final double dy, final double dz) {
        final double[] m = this.landed;
        if (s.noPhysics) {
            m[0] = dx;
            m[1] = dy;
            m[2] = dz;
        } else {
            this.sim.collision.collide(s, dx, dy, dz, (float) s.attributes.stepHeight.value(), m);
        }
        if (this.exact) {
            return same(m[0], this.move[0]) && same(m[1], this.move[1]) && same(m[2], this.move[2]);
        }
        // Entity.move (Entity.java:775): a move that lost more than it kept is not applied at all
        final double length = m[0] * m[0] + m[1] * m[1] + m[2] * m[2];
        double px = this.from[0];
        double py = this.from[1];
        double pz = this.from[2];
        if (s.noPhysics || length > 1.0E-7 || dx * dx + dy * dy + dz * dz - length < 1.0E-7) {
            px = px + m[0];
            py = py + m[1];
            pz = pz + m[2];
        }
        return same(Mth.clamp(px, -2.9999999E7, 2.9999999E7), this.to[0]) && same(py, this.to[1])
            && same(Mth.clamp(pz, -2.9999999E7, 2.9999999E7), this.to[2]);
    }

    /**
     * The tick from its move on, for one reading: collide was given {@code dx/dy/dz} (the velocity the move started with,
     * scaled by a stuck multiplier when one was consumed, which keeps its signs) and returned {@code landed}. Into
     * {@code velocity}.
     */
    private void run(final SimPlayer s, final MovementSim.Travel t, final double dx, final double dy, final double dz) {
        this.run(s, t, dx, dy, dz, Double.NaN);
    }

    /** {@link #run}, with Player.travel's y before the branch given ({@code beforeBranchY}), or NaN for the move's. */
    private void run(final SimPlayer s, final MovementSim.Travel t, final double dx, final double dy, final double dz, final double beforeBranchY) {
        final SimPlayer w = this.work;
        w.copyFrom(s);
        MovementSim.setDelta(w, dx, dy, dz);
        // read before the move: travelInFluid's falling test and gravity (LivingEntity.java:2499-2501, its moveRelative adds
        // nothing to y) and Player.travel's y (Player.java:1385, no climbing or y input while flying)
        final boolean isFalling = w.vy <= 0.0;
        final double baseGravity = MovementSim.effectiveGravity(w);
        final double originalMovementY = Double.isNaN(beforeBranchY) ? w.vy : beforeBranchY;
        if (w.noPhysics) {
            MovementSim.noPhysicsMove(w, dx, dy, dz);
        } else {
            if (MovementSim.isStuck(w)) {
                MovementSim.clearStuck(w);
            }
            this.sim.afterCollide(w, this.world, dx, dy, dz, this.landed[0], this.landed[1], this.landed[2]);
        }
        switch (t.kind) {
            case MovementSim.Travel.AIR -> this.sim.afterAirMove(w, this.world, t.blockFriction);
            case MovementSim.Travel.WATER -> this.sim.afterWaterMove(w, this.world, t.slowDown, baseGravity, isFalling, s.y);
            case MovementSim.Travel.LAVA -> this.sim.afterLavaMove(w, baseGravity, isFalling, s.y);
            default -> {
                // GLIDE: the move is travelFallFlying's last step
            }
        }
        if (t.stopsGliding) {
            w.fallFlying = false;
        }
        if (t.flying) {
            MovementSim.afterFlyingTravel(w, originalMovementY);
        }
        // LivingEntity.aiStep, after travel (LivingEntity.java:3139)
        this.sim.blocks.applyEffectsFromBlocks(w, this.world);
        this.velocity[0] = w.vx;
        this.velocity[1] = w.vy;
        this.velocity[2] = w.vz;
    }

    /**
     * Whether Player.maybeBackOffFromEdge (Player.java:864-911) may have shortened the movement collide was given, so the
     * velocity is longer than it. It runs for a sneaking, non-flying player near the ground not moving up; it shortens x,
     * z or both only after finding no ground under the box shifted by a value within 0.05 of where it stops (or of 0 for
     * an axis it zeroes), so ground under every such box, which is ground under their common part, rules it out. A
     * consumed stuck multiplier zeroes the velocity anyway.
     */
    private boolean backedOff(final SimPlayer s, final double dx, final double dy, final double dz) {
        if (s.noPhysics || MovementSim.isStuck(s) || s.flying || dy > 0.0 || !s.isShiftKeyDown()) {
            return false;
        }
        final float maxDownStep = (float) s.attributes.stepHeight.value();
        if (!this.sim.isAboveGround(s, maxDownStep)) {
            return false;
        }
        // the part common to canFallAtLeast's boxes (Player.java:917-931) for shifts within BACK_OFF of [0, dx] x [0, dz]
        final SimBox bb = s.bb;
        final double minX = bb.minX + 1.0E-7 + Math.max(0.0, dx) + BACK_OFF;
        final double maxX = bb.maxX - 1.0E-7 + Math.min(0.0, dx) - BACK_OFF;
        final double minZ = bb.minZ + 1.0E-7 + Math.max(0.0, dz) + BACK_OFF;
        final double maxZ = bb.maxZ - 1.0E-7 + Math.min(0.0, dz) - BACK_OFF;
        if (!(minX < maxX) || !(minZ < maxZ)) {
            return true;
        }
        this.box.set(minX, bb.minY - maxDownStep - 1.0E-7, minZ, maxX, bb.minY, maxZ);
        return this.sim.collision.noCollision(s, this.box);
    }

    // ------------------------------------------------------------------------------------------------ helpers

    /** The axes on which the last run differs, bit for bit, from the velocity at {@code from[at..at + 2]}. */
    private int differ(final double[] from, final int at) {
        int axes = 0;
        if (!same(this.velocity[0], from[at])) {
            axes |= MovementSim.VELOCITY_X;
        }
        if (!same(this.velocity[1], from[at + 1])) {
            axes |= MovementSim.VELOCITY_Y;
        }
        if (!same(this.velocity[2], from[at + 2])) {
            axes |= MovementSim.VELOCITY_Z;
        }
        return axes;
    }

    /** {@code trial} = what this state's {@code h}-th landing reading gave collide. */
    private void reading(final int h) {
        for (int a = 0; a < 3; a++) {
            this.trial[a] = this.given[a][this.landing[h * 3 + a]];
        }
    }

    /** Records a reading that landed, with the velocity its run just gave. */
    private void landing(final int ix, final int iy, final int iz) {
        if (this.landingCount * 3 + 3 > this.landing.length) {
            this.landing = java.util.Arrays.copyOf(this.landing, this.landing.length * 2);
            this.readingVelocity = java.util.Arrays.copyOf(this.readingVelocity, this.landing.length);
        }
        final int at = this.landingCount++ * 3;
        this.landing[at] = ix;
        this.landing[at + 1] = iy;
        this.landing[at + 2] = iz;
        System.arraycopy(this.velocity, 0, this.readingVelocity, at, 3);
    }

    /** Room for {@code n} states (grows once, on the first calls). */
    private void room(final int n) {
        if (this.states.length < n) {
            final int size = Math.max(n, this.states.length * 2);
            final SimPlayer[] states = java.util.Arrays.copyOf(this.states, size);
            final MovementSim.Travel[] travels = java.util.Arrays.copyOf(this.travels, size);
            this.unclamped = java.util.Arrays.copyOf(this.unclamped, size);
            for (int i = this.states.length; i < size; i++) {
                states[i] = new SimPlayer();
                travels[i] = new MovementSim.Travel();
            }
            this.states = states;
            this.travels = travels;
        }
    }

    /**
     * Whether two probed states start the move alike for everything the after-move half reads that the keys can change:
     * sprint, jumping, shift (bounce, scaffolding), flight, glide, the fall distance (back-off, powder snow) and the
     * branch with its pre-move values.
     */
    private static boolean sameStart(final SimPlayer a, final MovementSim.Travel ta, final SimPlayer b, final MovementSim.Travel tb) {
        return a.sprinting == b.sprinting && a.jumping == b.jumping && a.keys.shift() == b.keys.shift() && a.flying == b.flying
            && a.fallFlying == b.fallFlying && same(a.fallDistance, b.fallDistance) && ta.same(tb);
    }

    private static int keys(final boolean forward, final boolean jump, final boolean shift, final boolean sprint) {
        return (forward ? 1 : 0) | (jump ? 2 : 0) | (shift ? 4 : 0) | (sprint ? 8 : 0);
    }

    private static boolean same(final double a, final double b) {
        return Double.doubleToRawLongBits(a) == Double.doubleToRawLongBits(b);
    }

    /**
     * The move the client's {@code from + move} (Entity.move's {@code pos.add(movement)}) turns into {@code to}: the
     * difference, nudged by an ulp where the subtraction rounded; NaN when none does.
     */
    static double between(final double from, final double to) {
        double m = to - from;
        if (!same(from + m, to) && m == 0.0) {
            m = -m; // -0.0 + -0.0 is the only sum giving -0.0
        }
        for (int i = 0; i < 64 && !same(from + m, to); i++) {
            m = from + m < to ? Math.nextUp(m) : Math.nextDown(m);
        }
        return same(from + m, to) ? m : Double.NaN;
    }

    /**
     * The end, towards {@code dir}, of the moves {@code m'} for which {@code from + m'} still rounds to {@code to}
     * (a range, since the sum is monotonic in {@code m'}): {@code m} itself when the move is exact.
     */
    static double bound(final double from, final double to, final double m, final double dir) {
        double good = m;
        double reach = 4.0 * Math.max(Math.ulp(to), Math.ulp(from));
        double bad = m + dir * reach;
        while (same(from + bad, to)) {
            good = bad;
            reach *= 2.0;
            bad = m + dir * reach;
        }
        while (true) {
            final double mid = good + (bad - good) * 0.5;
            if (mid == good || mid == bad) {
                return good;
            }
            if (same(from + mid, to)) {
                good = mid;
            } else {
                bad = mid;
            }
        }
    }
}
