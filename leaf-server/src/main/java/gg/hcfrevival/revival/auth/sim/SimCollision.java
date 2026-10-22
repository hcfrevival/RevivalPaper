package gg.hcfrevival.revival.auth.sim;

import java.util.Arrays;
import net.minecraft.core.BlockPos;
import net.minecraft.util.Mth;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.LiquidBlock;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.shapes.VoxelShape;

/**
 * Vanilla 26.3 collision for one player, ported so it runs the client's algorithm on the server: collider gathering
 * ({@code BlockCollisions}, entity colliders, the world border), {@code Shapes.collide} with its 1e-7 early exit,
 * {@code Entity.collideWithShapes} axis order and {@code Entity.collide}'s step-up. Shapes are read only as data
 * ({@link SimShape}); nothing here calls the server's {@code Entity.move/collide}, {@code CollisionUtil},
 * {@code BlockCollisions}, {@code Shapes.collide} or {@code VoxelShape.collide}, which Paper/Moonrise patch
 * (report 07 §1.6).
 * <p>
 * Collider lists keep vanilla's order (entities, border, blocks in cursor order) because {@code Shapes.collide} returns
 * 0 as soon as the remaining distance is under 1e-7, so order matters at that scale.
 * <p>
 * Not thread-safe; one per {@link MovementSim}. Reuses its scratch state, so the hot path does not allocate (entity
 * colliders, rare, allocate their shapes).
 */
final class SimCollision {

    /** PowderSnowBlock.FALLING_COLLISION_SHAPE, {@code Shapes.box(0, 0, 0, 1, 0.8999999761581421, 1)}. */
    private static final SimShape POWDER_SNOW_FALLING = SimShape.create(0.0, 0.0, 0.0, 1.0, 0.8999999761581421, 1.0);

    final SimLevel level = new SimLevel();
    final ShapeCache shapes = new ShapeCache();
    final SimCollisionContext context = new SimCollisionContext();
    private final BlockPos.MutableBlockPos pos = new BlockPos.MutableBlockPos();

    // scratch collider lists: [0] the main sweep, [1] the step-up sweep
    private final Colliders main = new Colliders();
    private final Colliders step = new Colliders();
    private final Colliders entities = new Colliders();
    private final SimBoxList entityBoxes = new SimBoxList();
    private final SimBox area = new SimBox();
    private final SimBox stepBox = new SimBox();
    private final SimBox grounded = new SimBox();
    private final SimBox probe = new SimBox();
    private final ProbeShape entityShape = new ProbeShape();
    private final EntityShapes entityShapes = new EntityShapes();
    // updateAutoJump's scan: one shape's boxes (6 doubles per cell) and its cell flags
    private double[] scanBoxes = new double[6 * 64];
    private boolean[] scanCells = new boolean[64];
    private final Merger mergeX = new Merger();
    private final Merger mergeY = new Merger();
    private final Merger mergeZ = new Merger();
    private float[] candidates = new float[16];

    private SimShape border = SimShape.EMPTY;
    private double borderMinX = Double.NaN;
    private double borderMinZ = Double.NaN;
    private double borderMaxX = Double.NaN;
    private double borderMaxZ = Double.NaN;

    // what this query's CollisionContext.of(player) captured, plus the entity data powder snow reads
    private double fallDistance;
    private boolean leatherBoots;
    private double sourceX;
    private double sourceZ;

    /** The world the following queries read. */
    void world(final SimWorld world) {
        this.level.world(world);
    }

    /**
     * Snapshot of {@code CollisionContext.of(player)}: vanilla creates the context when a query starts, so every query
     * takes the player as it is at that moment.
     */
    private void begin(final SimPlayer player) {
        this.context.descending = player.keys.shift();
        this.context.entityBottom = player.y;
        this.context.heldItem = player.mainHand;
        this.fallDistance = player.fallDistance;
        this.leatherBoots = player.feet.getItem() == Items.LEATHER_BOOTS;
        this.sourceX = player.x;
        this.sourceZ = player.z;
    }

    // ------------------------------------------------------------------------------------------------ Entity.collide

    /**
     * {@code Entity.collide(movement)} (Entity.java:1182-1211) for the box {@code aabb}: writes the allowed movement to
     * {@code out[0..2]}.
     */
    void collide(final SimPlayer player, final double mx, final double my, final double mz, final float maxUpStep, final double[] out) {
        this.begin(player);
        final SimBox aabb = player.bb;
        final boolean onGround = player.onGround;
        final SimWorld world = this.level.world();
        // entity colliders once, over the move expanded by the step height (26.3)
        this.area.set(aabb).expandTowards(mx, my, mz).expandTowards(0.0, maxUpStep, 0.0);
        this.gatherEntities(world, this.area, this.entities);

        double sx = mx;
        double sy = my;
        double sz = mz;
        if (!(mx * mx + my * my + mz * mz == 0.0)) {
            // collideBoundingBox (Entity.java:1240-1245)
            this.area.set(aabb).expandTowards(mx, my, mz);
            this.gatherAll(world, this.entities, this.area, this.main);
            this.collideWithShapes(mx, my, mz, aabb, this.main, out);
            sx = out[0];
            sy = out[1];
            sz = out[2];
        }
        final boolean xCollision = mx != sx;
        final boolean yCollision = my != sy;
        final boolean zCollision = mz != sz;
        final boolean onGroundAfterCollision = yCollision && my < 0.0;
        if (maxUpStep > 0.0F && (onGroundAfterCollision || onGround) && (xCollision || zCollision)) {
            this.grounded.set(aabb);
            if (onGroundAfterCollision) {
                this.grounded.move(0.0, sy, 0.0);
            }
            this.stepBox.set(this.grounded).expandTowards(mx, maxUpStep, mz);
            if (!onGroundAfterCollision) {
                this.stepBox.expandTowards(0.0, -9.999999747378752E-6, 0.0);
            }
            this.gatherAll(world, this.entities, this.stepBox, this.step);
            final float stepHeightToSkip = (float) sy;
            final int count = this.collectCandidateStepUpHeights(this.grounded, this.step, maxUpStep, stepHeightToSkip);
            final double stepped = sx * sx + sz * sz;
            for (int i = 0; i < count; i++) {
                this.collideWithShapes(mx, this.candidates[i], mz, this.grounded, this.step, out);
                if (out[0] * out[0] + out[2] * out[2] > stepped) {
                    final double distanceToGround = aabb.minY - this.grounded.minY;
                    // Vec3.subtract(0, d, 0)
                    out[0] = out[0] - 0.0;
                    out[1] = out[1] - distanceToGround;
                    out[2] = out[2] - 0.0;
                    return;
                }
            }
        }
        out[0] = sx;
        out[1] = sy;
        out[2] = sz;
    }

    /** Entity.collectCandidateStepUpHeights (Entity.java:1213-1238): sorted distinct floats, FloatArraySet semantics. */
    private int collectCandidateStepUpHeights(final SimBox box, final Colliders colliders, final float maxStepHeight, final float stepHeightToSkip) {
        int count = 0;
        for (int i = 0; i < colliders.size; i++) {
            final SimShape shape = colliders.shapes[i];
            final double[] ys = shape.ys;
            final boolean moved = colliders.moved[i];
            final double oy = colliders.oy[i];
            for (int k = 0; k < ys.length; k++) {
                final double coord = moved ? ys[k] + oy : ys[k];
                final float relativeCoord = (float) (coord - box.minY);
                if (!(relativeCoord < 0.0F) && relativeCoord != stepHeightToSkip) {
                    if (relativeCoord > maxStepHeight) {
                        break;
                    }
                    count = this.addCandidate(relativeCoord, count);
                }
            }
        }
        // FloatArrays.unstableSort: Float.compare order; keys are distinct by raw bits, so any sort gives the same array
        for (int i = 1; i < count; i++) {
            final float v = this.candidates[i];
            int j = i - 1;
            while (j >= 0 && Float.compare(this.candidates[j], v) > 0) {
                this.candidates[j + 1] = this.candidates[j];
                j--;
            }
            this.candidates[j + 1] = v;
        }
        return count;
    }

    private int addCandidate(final float value, final int count) {
        final int bits = Float.floatToRawIntBits(value);
        for (int i = 0; i < count; i++) {
            if (Float.floatToRawIntBits(this.candidates[i]) == bits) {
                return count;
            }
        }
        if (count == this.candidates.length) {
            this.candidates = Arrays.copyOf(this.candidates, count * 2);
        }
        this.candidates[count] = value;
        return count + 1;
    }

    /**
     * Entity.collideWithShapes (Entity.java:1289-1307): per axis in {@code Direction.axisStepOrder} (YZX when
     * |x| < |z|, else YXZ), {@code Shapes.collide} against the box moved by what is resolved so far.
     */
    private void collideWithShapes(final double mx, final double my, final double mz, final SimBox box, final Colliders shapes, final double[] out) {
        if (shapes.size == 0) {
            out[0] = mx;
            out[1] = my;
            out[2] = mz;
            return;
        }
        double rx = 0.0;
        double ry = 0.0;
        double rz = 0.0;
        final boolean zFirst = Math.abs(mx) < Math.abs(mz);
        // Y first in both orders
        if (my != 0.0) {
            ry = this.collideAxis(1, this.probe.set(box).move(rx, ry, rz), shapes, my);
        }
        if (zFirst) {
            if (mz != 0.0) {
                rz = this.collideAxis(2, this.probe.set(box).move(rx, ry, rz), shapes, mz);
            }
            if (mx != 0.0) {
                rx = this.collideAxis(0, this.probe.set(box).move(rx, ry, rz), shapes, mx);
            }
        } else {
            if (mx != 0.0) {
                rx = this.collideAxis(0, this.probe.set(box).move(rx, ry, rz), shapes, mx);
            }
            if (mz != 0.0) {
                rz = this.collideAxis(2, this.probe.set(box).move(rx, ry, rz), shapes, mz);
            }
        }
        out[0] = rx;
        out[1] = ry;
        out[2] = rz;
    }

    /** Shapes.collide (Shapes.java:223-233): stop at |distance| < 1e-7, else each shape in list order. */
    private double collideAxis(final int axis, final SimBox moving, final Colliders shapes, double distance) {
        for (int i = 0; i < shapes.size; i++) {
            if (Math.abs(distance) < 1.0E-7) {
                return 0.0;
            }
            distance = shapes.shapes[i].collide(axis, moving, distance, shapes.moved[i], shapes.ox[i], shapes.oy[i], shapes.oz[i]);
        }
        return distance;
    }

    // ------------------------------------------------------------------------------------------------ gathering

    /** EntityGetter.getEntityCollisions (EntityGetter.java:54-72): nothing for a box under 1e-7, else the inflated query. */
    private void gatherEntities(final SimWorld world, final SimBox testArea, final Colliders out) {
        out.clear();
        if (!this.queryEntities(world, testArea)) {
            return;
        }
        final SimBoxList b = this.entityBoxes;
        for (int i = 0; i < b.size(); i++) {
            out.add(this.entityShapes.get(b.get(i, 0), b.get(i, 1), b.get(i, 2), b.get(i, 3), b.get(i, 4), b.get(i, 5)), false, 0, 0, 0);
        }
    }

    /** The entity query of getEntityCollisions into {@link #entityBoxes}; true when it found any. */
    private boolean queryEntities(final SimWorld world, final SimBox testArea) {
        this.entityBoxes.clear();
        if (testArea.size() < 1.0E-7) {
            return false;
        }
        this.probe.set(testArea).inflate(1.0E-7, 1.0E-7, 1.0E-7);
        world.collectEntityColliders(this.probe, this.entityBoxes);
        return this.entityBoxes.size() != 0;
    }

    /**
     * Entity.collectCollidersIgnoringWorldBorder(Entity, ...) (Entity.java:1259-1275), the name notwithstanding: entity
     * colliders, then the border when {@code isInsideCloseToBorder}, then the blocks of {@code BlockCollisions}.
     */
    private void gatherAll(final SimWorld world, final Colliders entityColliders, final SimBox box, final Colliders out) {
        out.clear();
        out.addAll(entityColliders);
        if (this.isInsideCloseToBorder(world, box)) {
            out.add(this.borderShape(world), false, 0, 0, 0);
        }
        this.gatherBlocks(box, false, out);
    }

    /**
     * BlockCollisions (BlockCollisions.java:42-111): cursor over {@code floor(min - 1e-7) - 1 .. floor(max + 1e-7) + 1},
     * x fastest then y then z (Cursor3D); corners never, edges only for moving pistons, faces only for large shapes; full
     * blocks tested with a box intersect, other shapes with {@code Shapes.joinIsNotEmpty(shape, Shapes.create(box), AND)}.
     * With {@code out == null} it stops at the first collider and returns true (noCollision, suffocation).
     */
    private boolean gatherBlocks(final SimBox box, final boolean onlySuffocating, final Colliders out) {
        final SimWorld world = this.level.world();
        this.entityShape.set(box);
        final int x0 = Mth.floor(box.minX - 1.0E-7) - 1;
        final int x1 = Mth.floor(box.maxX + 1.0E-7) + 1;
        final int y0 = Mth.floor(box.minY - 1.0E-7) - 1;
        final int y1 = Mth.floor(box.maxY + 1.0E-7) + 1;
        final int z0 = Mth.floor(box.minZ - 1.0E-7) - 1;
        final int z1 = Mth.floor(box.maxZ + 1.0E-7) + 1;
        for (int z = z0; z <= z1; z++) {
            final int zFace = z == z0 || z == z1 ? 1 : 0;
            for (int y = y0; y <= y1; y++) {
                final int yFace = y == y0 || y == y1 ? 1 : 0;
                for (int x = x0; x <= x1; x++) {
                    final int type = (x == x0 || x == x1 ? 1 : 0) + yFace + zFace;
                    if (type == 3 || !world.isChunkLoaded(x >> 4, z >> 4)) {
                        continue;
                    }
                    final BlockState state = world.blockState(x, y, z);
                    this.pos.set(x, y, z);
                    if (onlySuffocating && !state.isSuffocating(this.level, this.pos)) {
                        continue;
                    }
                    if (type == 1 && !state.hasLargeCollisionShape() || type == 2 && state.getBlock() != Blocks.MOVING_PISTON) {
                        continue;
                    }
                    final SimShape shape = this.blockShape(state, this.pos);
                    if (shape.block) {
                        if (box.intersects(x, y, z, x + 1.0, y + 1.0, z + 1.0)) {
                            if (out == null) {
                                return true;
                            }
                            out.add(shape, true, x, y, z);
                        }
                    } else if (!shape.empty && this.joinIsNotEmpty(shape, true, x, y, z)) {
                        if (out == null) {
                            return true;
                        }
                        out.add(shape, true, x, y, z);
                    }
                }
            }
        }
        return false;
    }

    /**
     * {@code context.getCollisionShape(state, level, pos)} with the context of the last query, except powder snow, whose
     * shape depends on the entity behind the context: PowderSnowBlock.getCollisionShape (PowderSnowBlock.java:116-133)
     * evaluated for this player.
     */
    SimShape blockShape(final BlockState state, final BlockPos at) {
        if (state.getBlock() == Blocks.POWDER_SNOW) {
            if (this.fallDistance > 2.5) {
                return POWDER_SNOW_FALLING;
            }
            // canEntityWalkOnPowderSnow: the player type is not in POWDER_SNOW_WALKABLE_MOBS, so leather boots decide
            if (this.leatherBoots && this.context.isAbove(net.minecraft.world.phys.shapes.Shapes.block(), at, false) && !this.context.descending) {
                // super.getCollisionShape: BlockBehaviour's default, the full block powder snow's getShape gives
                return SimShape.BLOCK;
            }
            return SimShape.EMPTY;
        }
        if (state.getBlock() instanceof LiquidBlock) {
            // LiquidBlock.getCollisionShape (LiquidBlock.java:61-77) is empty for a player: no fluid collision in this
            // context, and LivingEntity.canStandOnFluid is false. Answered here because vanilla's Optional chain allocates.
            return SimShape.EMPTY;
        }
        final VoxelShape shape = state.getCollisionShape(this.level, at, this.context);
        return this.shapes.get(shape);
    }

    /** PowderSnowBlock's shape for the player as it is now (getEntityInsideCollisionShape takes a fresh context). */
    SimShape powderSnowShape(final SimPlayer player, final BlockPos at) {
        this.begin(player);
        return this.blockShape(Blocks.POWDER_SNOW.defaultBlockState(), at);
    }

    /**
     * {@code Shapes.joinIsNotEmpty(first, Shapes.create(box), AND)} (Shapes.java:180-221) with {@code first} this block
     * shape (moved by the offsets when {@code moved}) and the probe box set by {@link ProbeShape#set}.
     */
    private boolean joinIsNotEmpty(final SimShape first, final boolean moved, final double ox, final double oy, final double oz) {
        final ProbeShape second = this.entityShape;
        if (first.empty || second.empty) {
            return false; // op.apply(!firstEmpty, !secondEmpty) for AND
        }
        // AxisCycle.AXIS_VALUES: X, Y, Z
        for (int axis = 0; axis < 3; axis++) {
            final double off = axis == 0 ? ox : axis == 1 ? oy : oz;
            if (first.max(axis, moved, off) < second.min(axis) - 1.0E-7) {
                return false;
            }
            if (second.max(axis) < first.min(axis, moved, off) - 1.0E-7) {
                return false;
            }
        }
        this.mergeX.merge(first.xs, moved, ox, second.xs, second.sizeX + 1);
        this.mergeY.merge(first.ys, moved, oy, second.ys, second.sizeY + 1);
        this.mergeZ.merge(first.zs, moved, oz, second.zs, second.sizeZ + 1);
        final Merger mx = this.mergeX;
        final Merger my = this.mergeY;
        final Merger mz = this.mergeZ;
        for (int i = 0; i < mx.length - 1; i++) {
            for (int j = 0; j < my.length - 1; j++) {
                for (int k = 0; k < mz.length - 1; k++) {
                    if (first.isFullWide(mx.first[i], my.first[j], mz.first[k]) && second.isFullWide(mx.second[i], my.second[j], mz.second[k])) {
                        return true;
                    }
                }
            }
        }
        return false;
    }

    // ------------------------------------------------------------------------------------------------ queries

    /**
     * CollisionGetter.noCollision(entity, box) (CollisionGetter.java:36-66): no block collider (entity context), no
     * entity collider, no border collision.
     */
    boolean noCollision(final SimPlayer player, final SimBox box) {
        this.begin(player);
        if (this.gatherBlocks(box, false, null)) {
            return false;
        }
        final SimWorld world = this.level.world();
        // !getEntityCollisions(...).isEmpty(): any entity counts, even one whose shape is empty
        if (this.queryEntities(world, box)) {
            return false;
        }
        // noBorderCollision: border shape only when inside close to it, then joinIsNotEmpty with Shapes.create(box)
        if (!this.isInsideCloseToBorder(world, box)) {
            return true;
        }
        this.entityShape.set(box);
        return !this.joinIsNotEmpty(this.borderShape(world), false, 0.0, 0.0, 0.0);
    }

    /** CollisionGetter.collidesWithSuffocatingBlock (CollisionGetter.java:117-127). */
    boolean collidesWithSuffocatingBlock(final SimPlayer player, final SimBox box) {
        this.begin(player);
        return this.gatherBlocks(box, true, null);
    }

    /**
     * CollisionGetter.findSupportingBlock (CollisionGetter.java:129-143): the colliding block whose centre is closest to
     * the player's position, ties to the greater {@code BlockPos.compareTo}. Returns false when none.
     */
    boolean findSupportingBlock(final SimPlayer player, final SimBox box, final BlockPos.MutableBlockPos result) {
        this.begin(player);
        final double px = player.x;
        final double py = player.y;
        final double pz = player.z;
        this.main.clear();
        this.gatherBlocks(box, false, this.main);
        boolean found = false;
        double best = Double.MAX_VALUE;
        for (int i = 0; i < this.main.size; i++) {
            final int x = (int) this.main.ox[i];
            final int y = (int) this.main.oy[i];
            final int z = (int) this.main.oz[i];
            final double dx = x + 0.5 - px;
            final double dy = y + 0.5 - py;
            final double dz = z + 0.5 - pz;
            final double distance = dx * dx + dy * dy + dz * dz;
            if (distance < best || distance == best && (!found || compare(result.getX(), result.getY(), result.getZ(), x, y, z) < 0)) {
                result.set(x, y, z);
                best = distance;
                found = true;
            }
        }
        return found;
    }

    /** Vec3i.compareTo (Vec3i.java:57-63), with its int subtraction. */
    private static int compare(final int ax, final int ay, final int az, final int bx, final int by, final int bz) {
        if (ay == by) {
            return az == bz ? ax - bx : az - bz;
        }
        return ay - by;
    }

    /** LevelReader.containsAnyLiquid (LevelReader.java:146-148): any block state with a fluid in the closed cell range. */
    boolean containsAnyLiquid(final SimBox box) {
        final SimWorld world = this.level.world();
        final int x0 = Mth.floor(box.minX);
        final int y0 = Mth.floor(box.minY);
        final int z0 = Mth.floor(box.minZ);
        final int x1 = Mth.floor(box.maxX);
        final int y1 = Mth.floor(box.maxY);
        final int z1 = Mth.floor(box.maxZ);
        for (int x = x0; x <= x1; x++) {
            for (int y = y0; y <= y1; y++) {
                for (int z = z0; z <= z1; z++) {
                    if (!world.blockState(x, y, z).getFluidState().isEmpty()) {
                        return true;
                    }
                }
            }
        }
        return false;
    }

    /**
     * {@code level.getBlockState(pos).getCollisionShape(level, pos, CollisionContext.of(player))}, with this player's
     * context taken now (air outside loaded chunks, like ClientLevel).
     */
    SimShape collisionShapeAt(final SimPlayer player, final int x, final int y, final int z) {
        this.begin(player);
        this.pos.set(x, y, z);
        return this.blockShape(this.level.world().blockState(x, y, z), this.pos);
    }

    /**
     * The obstacle scan of LocalPlayer.updateAutoJump (LocalPlayer.java:1083-1112): {@code level.getCollisions(player,
     * box)} (CollisionGetter.java:85-89: the entity colliders, then BlockCollisions in cursor order, no border), each
     * shape split by {@code toAabbs}, until the first box intersecting either segment ({@code AABB.intersects(Vec3,
     * Vec3)}, AABB.java:247-251). {@code seg} is left begin, left end, right begin, right end (12 doubles). Writes that
     * box to {@code out[0..5]} and returns true, or returns false.
     */
    boolean firstBoxCrossing(final SimPlayer player, final SimBox box, final double[] seg, final double[] out) {
        this.begin(player);
        final SimWorld world = this.level.world();
        this.gatherEntities(world, box, this.entities);
        if (this.firstBoxCrossing(this.entities, seg, out)) {
            return true;
        }
        this.step.clear();
        this.gatherBlocks(box, false, this.step);
        return this.firstBoxCrossing(this.step, seg, out);
    }

    private boolean firstBoxCrossing(final Colliders colliders, final double[] seg, final double[] out) {
        for (int i = 0; i < colliders.size; i++) {
            final SimShape shape = colliders.shapes[i];
            final int cells = shape.sizeX * shape.sizeY * shape.sizeZ;
            if (this.scanCells.length < cells) {
                this.scanCells = new boolean[cells];
                this.scanBoxes = new double[6 * cells];
            }
            final double[] boxes = this.scanBoxes;
            final int count = SimBlocks.toAabbs(shape, colliders.moved[i], colliders.ox[i], colliders.oy[i], colliders.oz[i], boxes, this.scanCells);
            for (int k = 0; k < count; k++) {
                final int at = k * 6;
                if (crosses(boxes, at, seg, 0) || crosses(boxes, at, seg, 6)) {
                    System.arraycopy(boxes, at, out, 0, 6);
                    return true;
                }
            }
        }
        return false;
    }

    /** {@code box.intersects(begin, end)} for the segment at {@code seg[from..from + 5]} (AABB.java:243-251). */
    private static boolean crosses(final double[] box, final int at, final double[] seg, final int from) {
        final double minX = Math.min(seg[from], seg[from + 3]);
        final double minY = Math.min(seg[from + 1], seg[from + 4]);
        final double minZ = Math.min(seg[from + 2], seg[from + 5]);
        final double maxX = Math.max(seg[from], seg[from + 3]);
        final double maxY = Math.max(seg[from + 1], seg[from + 4]);
        final double maxZ = Math.max(seg[from + 2], seg[from + 5]);
        return box[at] < maxX && box[at + 3] > minX && box[at + 1] < maxY && box[at + 4] > minY && box[at + 2] < maxZ && box[at + 5] > minZ;
    }

    // ------------------------------------------------------------------------------------------------ border

    /** WorldBorder.isInsideCloseToBorder (WorldBorder.java:117-120), with the source's position. */
    private boolean isInsideCloseToBorder(final SimWorld world, final SimBox box) {
        final double bbMax = Math.max(Math.max(Math.abs(box.maxX - box.minX), Math.abs(box.maxZ - box.minZ)), 1.0);
        final double minX = world.borderMinX();
        final double minZ = world.borderMinZ();
        final double maxX = world.borderMaxX();
        final double maxZ = world.borderMaxZ();
        // getDistanceToBorder (WorldBorder.java:107-115)
        final double fromNorth = this.sourceZ - minZ;
        final double fromSouth = maxZ - this.sourceZ;
        final double fromWest = this.sourceX - minX;
        final double fromEast = maxX - this.sourceX;
        double distance = Math.min(fromWest, fromEast);
        distance = Math.min(distance, fromNorth);
        distance = Math.min(distance, fromSouth);
        return distance < bbMax * 2.0
            && this.sourceX >= minX - bbMax && this.sourceX < maxX + bbMax && this.sourceZ >= minZ - bbMax && this.sourceZ < maxZ + bbMax;
    }

    private SimShape borderShape(final SimWorld world) {
        final double minX = world.borderMinX();
        final double minZ = world.borderMinZ();
        final double maxX = world.borderMaxX();
        final double maxZ = world.borderMaxZ();
        if (minX != this.borderMinX || minZ != this.borderMinZ || maxX != this.borderMaxX || maxZ != this.borderMaxZ) {
            this.border = SimShape.border(minX, minZ, maxX, maxZ);
            this.borderMinX = minX;
            this.borderMinZ = minZ;
            this.borderMaxX = maxX;
            this.borderMaxZ = maxZ;
        }
        return this.border;
    }

    // ------------------------------------------------------------------------------------------------ helpers

    /** A collider list: shape plus block offset, in vanilla list order. */
    static final class Colliders {
        SimShape[] shapes = new SimShape[32];
        double[] ox = new double[32];
        double[] oy = new double[32];
        double[] oz = new double[32];
        boolean[] moved = new boolean[32];
        int size;

        void clear() {
            this.size = 0;
        }

        void add(final SimShape shape, final boolean moved, final double ox, final double oy, final double oz) {
            if (this.size == this.shapes.length) {
                final int n = this.size * 2;
                this.shapes = Arrays.copyOf(this.shapes, n);
                this.ox = Arrays.copyOf(this.ox, n);
                this.oy = Arrays.copyOf(this.oy, n);
                this.oz = Arrays.copyOf(this.oz, n);
                this.moved = Arrays.copyOf(this.moved, n);
            }
            this.shapes[this.size] = shape;
            this.moved[this.size] = moved;
            this.ox[this.size] = ox;
            this.oy[this.size] = oy;
            this.oz[this.size] = oz;
            this.size++;
        }

        void addAll(final Colliders other) {
            for (int i = 0; i < other.size; i++) {
                this.add(other.shapes[i], other.moved[i], other.ox[i], other.oy[i], other.oz[i]);
            }
        }
    }

    /**
     * {@code Shapes.create(box)} of the entity colliders, remembered for the last few boxes: an entity standing still
     * (a parked boat, a shulker) gives the same box every tick, so its shape is built once. Shapes are immutable data,
     * so a hit is the same shape vanilla would build again.
     */
    static final class EntityShapes {
        private static final int SIZE = 8;
        private final long[] keys = new long[SIZE * 6];
        private final SimShape[] shapes = new SimShape[SIZE];
        private int next;

        SimShape get(final double minX, final double minY, final double minZ, final double maxX, final double maxY, final double maxZ) {
            final long a = Double.doubleToRawLongBits(minX);
            final long b = Double.doubleToRawLongBits(minY);
            final long c = Double.doubleToRawLongBits(minZ);
            final long d = Double.doubleToRawLongBits(maxX);
            final long e = Double.doubleToRawLongBits(maxY);
            final long f = Double.doubleToRawLongBits(maxZ);
            for (int i = 0; i < SIZE; i++) {
                final int k = i * 6;
                if (this.shapes[i] != null && this.keys[k] == a && this.keys[k + 1] == b && this.keys[k + 2] == c && this.keys[k + 3] == d
                    && this.keys[k + 4] == e && this.keys[k + 5] == f) {
                    return this.shapes[i];
                }
            }
            final SimShape shape = SimShape.create(minX, minY, minZ, maxX, maxY, maxZ);
            final int slot = this.next;
            this.next = (slot + 1) % SIZE;
            final int k = slot * 6;
            this.keys[k] = a;
            this.keys[k + 1] = b;
            this.keys[k + 2] = c;
            this.keys[k + 3] = d;
            this.keys[k + 4] = e;
            this.keys[k + 5] = f;
            this.shapes[slot] = shape;
            return shape;
        }
    }

    /**
     * {@code Shapes.create(box)} as the second operand of joinIsNotEmpty, without allocating: an ArrayVoxelShape over one
     * full cell, {@code Shapes.block()}, a CubeVoxelShape filled between rounded bounds, or empty (Shapes.java:56-88).
     */
    static final class ProbeShape {
        final double[] xs = new double[9];
        final double[] ys = new double[9];
        final double[] zs = new double[9];
        int sizeX;
        int sizeY;
        int sizeZ;
        final int[] from = new int[3];
        final int[] to = new int[3];
        boolean empty;

        void set(final SimBox box) {
            final double minX = box.minX;
            final double minY = box.minY;
            final double minZ = box.minZ;
            final double maxX = box.maxX;
            final double maxY = box.maxY;
            final double maxZ = box.maxZ;
            if (!(maxX - minX < 1.0E-7) && !(maxY - minY < 1.0E-7) && !(maxZ - minZ < 1.0E-7)) {
                final int xBits = SimShape.findBits(minX, maxX);
                final int yBits = SimShape.findBits(minY, maxY);
                final int zBits = SimShape.findBits(minZ, maxZ);
                if (xBits < 0 || yBits < 0 || zBits < 0) {
                    this.array(0, minX, maxX);
                    this.array(1, minY, maxY);
                    this.array(2, minZ, maxZ);
                } else {
                    // all zero bits is Shapes.block(): the same as a 1-part cube filled [0, 1)
                    this.cube(0, 1 << xBits, minX, maxX);
                    this.cube(1, 1 << yBits, minY, maxY);
                    this.cube(2, 1 << zBits, minZ, maxZ);
                }
                this.empty = this.from[0] >= this.to[0] || this.from[1] >= this.to[1] || this.from[2] >= this.to[2];
            } else {
                this.empty = true;
            }
        }

        private void array(final int axis, final double min, final double max) {
            final double[] c = this.coords(axis);
            c[0] = min;
            c[1] = max;
            this.setSize(axis, 1);
            this.from[axis] = 0;
            this.to[axis] = 1;
        }

        private void cube(final int axis, final int size, final double min, final double max) {
            final double[] c = this.coords(axis);
            for (int i = 0; i <= size; i++) {
                c[i] = (double) i / size;
            }
            this.setSize(axis, size);
            this.from[axis] = (int) Math.round(min * size);
            this.to[axis] = (int) Math.round(max * size);
        }

        private double[] coords(final int axis) {
            return axis == 0 ? this.xs : axis == 1 ? this.ys : this.zs;
        }

        private void setSize(final int axis, final int size) {
            if (axis == 0) {
                this.sizeX = size;
            } else if (axis == 1) {
                this.sizeY = size;
            } else {
                this.sizeZ = size;
            }
        }

        private int size(final int axis) {
            return axis == 0 ? this.sizeX : axis == 1 ? this.sizeY : this.sizeZ;
        }

        double min(final int axis) {
            final int i = this.from[axis];
            return i >= this.size(axis) ? Double.POSITIVE_INFINITY : this.coords(axis)[i];
        }

        double max(final int axis) {
            final int i = this.to[axis];
            return i <= 0 ? Double.NEGATIVE_INFINITY : this.coords(axis)[i];
        }

        boolean isFullWide(final int x, final int y, final int z) {
            if (x < 0 || y < 0 || z < 0) {
                return false;
            }
            return x < this.sizeX && y < this.sizeY && z < this.sizeZ
                && x >= this.from[0] && x < this.to[0] && y >= this.from[1] && y < this.to[1] && z >= this.from[2] && z < this.to[2];
        }
    }

    /**
     * Shapes.createIndexMerger for {@code joinIsNotEmpty(first, second, AND)} (Shapes.java:292-314): first's list is a
     * moved block shape's (never a CubePointRange), and the non-overlapping case cannot follow the bounds checks, so it
     * is IdenticalMerger when the lists are equal and IndirectMerger(first, second, false, false) otherwise
     * (IndirectMerger.java:14-62).
     */
    static final class Merger {
        int[] first = new int[32];
        int[] second = new int[32];
        int length;
        private double[] values = new double[32];

        void merge(final double[] firstBase, final boolean moved, final double offset, final double[] secondList, final int secondSize) {
            final int firstSize = firstBase.length;
            if (this.values.length < firstSize) {
                this.values = new double[firstSize * 2];
            }
            final double[] a = this.values;
            for (int i = 0; i < firstSize; i++) {
                a[i] = moved ? firstBase[i] + offset : firstBase[i];
            }
            final int capacity = firstSize + secondSize;
            if (this.first.length < capacity) {
                this.first = new int[capacity * 2];
                this.second = new int[capacity * 2];
            }
            if (firstSize == secondSize && equal(a, secondList, firstSize)) {
                // IdenticalMerger: (i, i) for every cell
                for (int i = 0; i < firstSize; i++) {
                    this.first[i] = i;
                    this.second[i] = i;
                }
                this.length = firstSize;
                return;
            }
            double lastValue = Double.NaN;
            int resultIndex = 0;
            int firstIndex = 0;
            int secondIndex = 0;
            while (true) {
                final boolean ranOutOfFirst = firstIndex >= firstSize;
                final boolean ranOutOfSecond = secondIndex >= secondSize;
                if (ranOutOfFirst && ranOutOfSecond) {
                    this.length = Math.max(1, resultIndex);
                    return;
                }
                final boolean choseFirst = !ranOutOfFirst && (ranOutOfSecond || a[firstIndex] < secondList[secondIndex] + 1.0E-7);
                if (choseFirst) {
                    firstIndex++;
                    if (secondIndex == 0 || ranOutOfSecond) {
                        continue; // canSkipFirst
                    }
                } else {
                    secondIndex++;
                    if (firstIndex == 0 || ranOutOfFirst) {
                        continue; // canSkipSecond
                    }
                }
                final int currentFirstIndex = firstIndex - 1;
                final int currentSecondIndex = secondIndex - 1;
                final double nextValue = choseFirst ? a[currentFirstIndex] : secondList[currentSecondIndex];
                if (!(lastValue >= nextValue - 1.0E-7)) {
                    this.first[resultIndex] = currentFirstIndex;
                    this.second[resultIndex] = currentSecondIndex;
                    resultIndex++;
                    lastValue = nextValue;
                } else {
                    this.first[resultIndex - 1] = currentFirstIndex;
                    this.second[resultIndex - 1] = currentSecondIndex;
                }
            }
        }

        /** AbstractDoubleList.equals: element-wise {@code ==}. */
        private static boolean equal(final double[] a, final double[] b, final int size) {
            for (int i = 0; i < size; i++) {
                if (a[i] != b[i]) {
                    return false;
                }
            }
            return true;
        }
    }
}
