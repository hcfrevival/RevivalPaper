package gg.hcfrevival.revival.auth.combat;

import java.util.List;
import net.minecraft.core.BlockPos;
import net.minecraft.util.Mth;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.shapes.VoxelShape;

/**
 * The 26.3 client's attack ray, {@code Minecraft.pick(1.0F)} → {@code LocalPlayer.raycastHitResult} → {@code pick}
 * (LocalPlayer.java:1272-1322) for an item without {@code ATTACK_RANGE} (every sword), replayed bit for bit (report 02
 * §2.2):
 * <ul>
 * <li>eye and view vector: {@code Entity.getEyePosition(1.0F)}, {@code calculateViewVector} with the {@code Mth} sine
 * table (Entity.java:2031-2062);</li>
 * <li>block pass: {@code Entity.pick} → {@code BlockGetter.clip} with OUTLINE shapes and {@code Fluid.NONE}
 * ({@code traverseBlocks} BlockGetter.java:131-185, {@code VoxelShape.clip} :160-179, {@code AABB.clip} :292-404);</li>
 * <li>entity pass: {@code ProjectileUtil.getEntityHitResult} (ProjectileUtil.java:110-147) over the candidates whose box
 * intersects the search box (EntitySection.java:31-39), inside-box hits at distance 0, strict comparisons;</li>
 * <li>{@code filterHitResult} with the block and entity interaction ranges (LocalPlayer.java:1313-1322).</li>
 * </ul>
 * The client walks entities in its section storage order, which the server can't know. That order matters only when
 * the eye is inside a candidate's box or two candidates tie exactly, so {@link #canPick} answers the order-free question
 * "is there an order in which the client picks this candidate", and {@link #resolveInOrder} replays one given order (the
 * verification rig compares it with vanilla's own code).
 * <p>
 * Use: {@link #clear}, {@link #add} each candidate, {@link #cast}, then query. Reusable; allocates nothing but what
 * {@code VoxelShape.toAabbs()} returns. One instance per thread.
 */
public final class PickRay {

    /** The kind of the final hit result. */
    public enum Kind {
        MISS,
        BLOCK,
        ENTITY
    }

    private static final byte OUTSIDE = 0; // not in the search box, or neither inside nor clipped
    private static final byte INSIDE = 1;  // bb.contains(from)
    private static final byte CLIPPED = 2; // bb.clip(from, to) present
    /** {@code AABB.EPSILON} and the {@code getDirection} thresholds (AABB.java:14, 353-371). */
    private static final double EPSILON = 1.0E-7;
    /** {@code BlockGetter.traverseBlocks} pulls both ends in by this fraction (:136-141). */
    private static final double TRAVERSE_EPSILON = -1.0E-7;

    // ---- candidates
    private int count;
    private int[] ids = new int[16];
    /** The pick box: {@code getBoundingBox().inflate(getPickRadius())}. */
    private double[] boxes = new double[16 * 6];
    /** The bounding box as given, for the search-box test. */
    private double[] raw = new double[16 * 6];
    private boolean[] fromInside = new boolean[16];
    private boolean[] exact = new boolean[16];
    private byte[] kinds = new byte[16];
    private double[] distances = new double[16];
    private double[] hits = new double[16 * 3];

    // ---- the last cast
    private double fromX;
    private double fromY;
    private double fromZ;
    private double viewX;
    private double viewY;
    private double viewZ;
    private double blockRange;
    private double entityRange;
    /** {@code maxDistanceSq} after the block pass: the entity pass's starting {@code nearest}. */
    private double maxDistanceSq;
    private double maxDistance;
    private double toX;
    private double toY;
    private double toZ;
    private boolean blockHit;
    private double blockX;
    private double blockY;
    private double blockZ;
    private int blockPosX;
    private int blockPosY;
    private int blockPosZ;
    private double blockDistanceSq;
    private final BlockPos.MutableBlockPos cursor = new BlockPos.MutableBlockPos();

    // ---- AABB.clip's scaleReference and hit flag
    private double clipScale;
    private boolean clipHit;

    // ---- resolveInOrder result
    private Kind kind = Kind.MISS;
    private int hovered = -1;
    private double hitX;
    private double hitY;
    private double hitZ;

    // ------------------------------------------------------------------------------------------------------------------
    // Candidates
    // ------------------------------------------------------------------------------------------------------------------

    public void clear() {
        this.count = 0;
    }

    /**
     * Adds an entity the attacker's client could pick: its {@code getBoundingBox()} and {@code getPickRadius()} (0 for
     * players), {@code canBePickedFromInside()} (true for players), and whether the box is the client's exactly. The
     * caller filters out the attacker and anything not pickable ({@code EntitySelector.CAN_BE_PICKED}).
     *
     * @return the candidate's index
     */
    public int add(final int id, final double minX, final double minY, final double minZ, final double maxX, final double maxY, final double maxZ,
                   final float pickRadius, final boolean pickableFromInside, final boolean exact) {
        if (this.count == this.ids.length) {
            this.grow();
        }
        final int i = this.count++;
        this.ids[i] = id;
        // getBoundingBox().inflate(getPickRadius()): new AABB(min - r, max + r) (AABB.java:187-195)
        final double r = pickRadius;
        final int b = i * 6;
        final double x1 = minX - r;
        final double y1 = minY - r;
        final double z1 = minZ - r;
        final double x2 = maxX + r;
        final double y2 = maxY + r;
        final double z2 = maxZ + r;
        this.boxes[b] = Math.min(x1, x2);
        this.boxes[b + 1] = Math.min(y1, y2);
        this.boxes[b + 2] = Math.min(z1, z2);
        this.boxes[b + 3] = Math.max(x1, x2);
        this.boxes[b + 4] = Math.max(y1, y2);
        this.boxes[b + 5] = Math.max(z1, z2);
        // EntitySection.getEntities tests the un-inflated box against the search box
        this.raw[b] = minX;
        this.raw[b + 1] = minY;
        this.raw[b + 2] = minZ;
        this.raw[b + 3] = maxX;
        this.raw[b + 4] = maxY;
        this.raw[b + 5] = maxZ;
        this.fromInside[i] = pickableFromInside;
        this.exact[i] = exact;
        return i;
    }

    public int add(final int id, final AABB box, final float pickRadius, final boolean pickableFromInside, final boolean exact) {
        return this.add(id, box.minX, box.minY, box.minZ, box.maxX, box.maxY, box.maxZ, pickRadius, pickableFromInside, exact);
    }

    private void grow() {
        final int n = this.ids.length * 2;
        this.ids = java.util.Arrays.copyOf(this.ids, n);
        this.boxes = java.util.Arrays.copyOf(this.boxes, n * 6);
        this.raw = java.util.Arrays.copyOf(this.raw, n * 6);
        this.fromInside = java.util.Arrays.copyOf(this.fromInside, n);
        this.exact = java.util.Arrays.copyOf(this.exact, n);
        this.kinds = java.util.Arrays.copyOf(this.kinds, n);
        this.distances = java.util.Arrays.copyOf(this.distances, n);
        this.hits = java.util.Arrays.copyOf(this.hits, n * 3);
    }

    public int count() {
        return this.count;
    }

    public int id(final int index) {
        return this.ids[index];
    }

    /** The index of the candidate with this id, or -1. */
    public int indexOf(final int id) {
        for (int i = 0; i < this.count; i++) {
            if (this.ids[i] == id) {
                return i;
            }
        }
        return -1;
    }

    // ------------------------------------------------------------------------------------------------------------------
    // The cast
    // ------------------------------------------------------------------------------------------------------------------

    /**
     * A line of sight: {@code BlockGetter.clip} with OUTLINE shapes from {@code from} to {@code to}, as {@link #cast}'s block
     * pass runs it. The first block hit, or the miss at {@code to}, is left in {@code block*} ({@link #blockHit},
     * {@link #blockPosX}...): pvp-issues WD-4, a block worked through another.
     */
    public void clipTo(final PickWorld world, final double fromX, final double fromY, final double fromZ, final double toX, final double toY,
                       final double toZ) {
        this.fromX = fromX;
        this.fromY = fromY;
        this.fromZ = fromZ;
        this.clipBlocks(world, toX, toY, toZ);
    }

    /** {@code LocalPlayer.pick(camera, blockRange, entityRange, 1.0F)} (:1292-1311), up to the entity scan. */
    public void cast(final AttackerSnapshot attacker, final PickWorld world) {
        this.blockRange = attacker.blockRange();
        this.entityRange = attacker.entityRange();
        double maxDistance = Math.max(this.blockRange, this.entityRange);
        double maxDistanceSq = maxDistance * maxDistance; // Mth.square
        this.fromX = attacker.eyeX();
        this.fromY = attacker.eyeY();
        this.fromZ = attacker.eyeZ();
        // getViewVector(1.0F) = calculateViewVector(getXRot(), getYRot()): partialTicks 1.0F reads the raw rotation
        final float realXRot = attacker.xRot() * 0.017453292F;
        final float realYRot = -attacker.yRot() * 0.017453292F;
        final float yCos = Mth.cos(realYRot);
        final float ySin = Mth.sin(realYRot);
        final float xCos = Mth.cos(realXRot);
        final float xSin = Mth.sin(realXRot);
        this.viewX = ySin * xCos;
        this.viewY = -xSin;
        this.viewZ = yCos * xCos;

        // Block pass: Entity.pick(maxDistance, 1.0F, false) (Entity.java:2075-2080)
        final double blockToX = this.fromX + this.viewX * maxDistance;
        final double blockToY = this.fromY + this.viewY * maxDistance;
        final double blockToZ = this.fromZ + this.viewZ * maxDistance;
        this.clipBlocks(world, blockToX, blockToY, blockToZ);
        this.blockDistanceSq = RemoteMirror.distanceToSqr(this.fromX, this.fromY, this.fromZ, this.blockX, this.blockY, this.blockZ);
        if (this.blockHit) {
            maxDistanceSq = this.blockDistanceSq;
            maxDistance = Math.sqrt(this.blockDistanceSq);
        }
        this.maxDistance = maxDistance;
        this.maxDistanceSq = maxDistanceSq;

        // Entity pass ray and search box: camera box .expandTowards(direction.scale(maxDistance)).inflate(1.0)
        final double reachX = this.viewX * maxDistance;
        final double reachY = this.viewY * maxDistance;
        final double reachZ = this.viewZ * maxDistance;
        this.toX = this.fromX + reachX;
        this.toY = this.fromY + reachY;
        this.toZ = this.fromZ + reachZ;
        final AABB own = attacker.box();
        double sMinX = own.minX;
        double sMinY = own.minY;
        double sMinZ = own.minZ;
        double sMaxX = own.maxX;
        double sMaxY = own.maxY;
        double sMaxZ = own.maxZ;
        if (reachX < 0.0) {
            sMinX += reachX;
        } else if (reachX > 0.0) {
            sMaxX += reachX;
        }
        if (reachY < 0.0) {
            sMinY += reachY;
        } else if (reachY > 0.0) {
            sMaxY += reachY;
        }
        if (reachZ < 0.0) {
            sMinZ += reachZ;
        } else if (reachZ > 0.0) {
            sMaxZ += reachZ;
        }
        // new AABB(...) after expandTowards, then inflate(1.0, 1.0, 1.0) and new AABB again
        final double eMinX = Math.min(sMinX, sMaxX) - 1.0;
        final double eMinY = Math.min(sMinY, sMaxY) - 1.0;
        final double eMinZ = Math.min(sMinZ, sMaxZ) - 1.0;
        final double eMaxX = Math.max(sMinX, sMaxX) + 1.0;
        final double eMaxY = Math.max(sMinY, sMaxY) + 1.0;
        final double eMaxZ = Math.max(sMinZ, sMaxZ) + 1.0;
        final double searchMinX = Math.min(eMinX, eMaxX);
        final double searchMinY = Math.min(eMinY, eMaxY);
        final double searchMinZ = Math.min(eMinZ, eMaxZ);
        final double searchMaxX = Math.max(eMinX, eMaxX);
        final double searchMaxY = Math.max(eMinY, eMaxY);
        final double searchMaxZ = Math.max(eMinZ, eMaxZ);

        for (int i = 0; i < this.count; i++) {
            final int r = i * 6;
            // entity.getBoundingBox().intersects(searchBox): strict on every axis (AABB.java:243-245)
            if (!(this.raw[r] < searchMaxX && this.raw[r + 3] > searchMinX && this.raw[r + 1] < searchMaxY && this.raw[r + 4] > searchMinY
                && this.raw[r + 2] < searchMaxZ && this.raw[r + 5] > searchMinZ)) {
                this.kinds[i] = OUTSIDE;
                continue;
            }
            final double minX = this.boxes[r];
            final double minY = this.boxes[r + 1];
            final double minZ = this.boxes[r + 2];
            final double maxX = this.boxes[r + 3];
            final double maxY = this.boxes[r + 4];
            final double maxZ = this.boxes[r + 5];
            this.clipScale = 1.0;
            this.clipHit = false;
            this.clipBox(minX, minY, minZ, maxX, maxY, maxZ, this.toX - this.fromX, this.toY - this.fromY, this.toZ - this.fromZ);
            final int h = i * 3;
            if (this.clipHit) {
                this.hits[h] = this.fromX + this.clipScale * (this.toX - this.fromX);
                this.hits[h + 1] = this.fromY + this.clipScale * (this.toY - this.fromY);
                this.hits[h + 2] = this.fromZ + this.clipScale * (this.toZ - this.fromZ);
            }
            // bb.contains(from): half-open (AABB.java:261-263)
            if (this.fromX >= minX && this.fromX < maxX && this.fromY >= minY && this.fromY < maxY && this.fromZ >= minZ && this.fromZ < maxZ) {
                this.kinds[i] = INSIDE;
                if (!this.clipHit) {
                    this.hits[h] = this.fromX; // clipPoint.orElse(from)
                    this.hits[h + 1] = this.fromY;
                    this.hits[h + 2] = this.fromZ;
                }
                this.distances[i] = RemoteMirror.distanceToSqr(this.fromX, this.fromY, this.fromZ, this.hits[h], this.hits[h + 1], this.hits[h + 2]);
            } else if (this.clipHit) {
                this.kinds[i] = CLIPPED;
                this.distances[i] = RemoteMirror.distanceToSqr(this.fromX, this.fromY, this.fromZ, this.hits[h], this.hits[h + 1], this.hits[h + 2]);
            } else {
                this.kinds[i] = OUTSIDE;
            }
        }
        this.hovered = -1;
        this.kind = Kind.MISS;
    }

    // ------------------------------------------------------------------------------------------------------------------
    // Block pass
    // ------------------------------------------------------------------------------------------------------------------

    /**
     * {@code BlockGetter.clip(new ClipContext(from, to, OUTLINE, NONE, camera))} (BlockGetter.java:63-81) through
     * {@code traverseBlocks} (:131-185). Leaves the hit, or the miss at {@code to}, in {@code block*}.
     */
    private void clipBlocks(final PickWorld world, final double toX, final double toY, final double toZ) {
        final double fromX = this.fromX;
        final double fromY = this.fromY;
        final double fromZ = this.fromZ;
        this.blockHit = false;
        this.blockX = toX; // the miss result's location is context.getTo()
        this.blockY = toY;
        this.blockZ = toZ;
        if (Double.compare(fromX, toX) == 0 && Double.compare(fromY, toY) == 0 && Double.compare(fromZ, toZ) == 0) {
            return; // from.equals(to)
        }
        // Mth.lerp(-1.0E-7, to, from) and Mth.lerp(-1.0E-7, from, to): p0 + a * (p1 - p0)
        final double endX = toX + TRAVERSE_EPSILON * (fromX - toX);
        final double endY = toY + TRAVERSE_EPSILON * (fromY - toY);
        final double endZ = toZ + TRAVERSE_EPSILON * (fromZ - toZ);
        final double startX = fromX + TRAVERSE_EPSILON * (toX - fromX);
        final double startY = fromY + TRAVERSE_EPSILON * (toY - fromY);
        final double startZ = fromZ + TRAVERSE_EPSILON * (toZ - fromZ);
        int currentX = Mth.floor(startX);
        int currentY = Mth.floor(startY);
        int currentZ = Mth.floor(startZ);
        if (this.clipBlock(world, currentX, currentY, currentZ, toX, toY, toZ)) {
            return;
        }
        final double dx = endX - startX;
        final double dy = endY - startY;
        final double dz = endZ - startZ;
        final int signX = Mth.sign(dx);
        final int signY = Mth.sign(dy);
        final int signZ = Mth.sign(dz);
        final double tDeltaX = signX == 0 ? Double.MAX_VALUE : signX / dx;
        final double tDeltaY = signY == 0 ? Double.MAX_VALUE : signY / dy;
        final double tDeltaZ = signZ == 0 ? Double.MAX_VALUE : signZ / dz;
        double tX = tDeltaX * (signX > 0 ? 1.0 - Mth.frac(startX) : Mth.frac(startX));
        double tY = tDeltaY * (signY > 0 ? 1.0 - Mth.frac(startY) : Mth.frac(startY));
        double tZ = tDeltaZ * (signZ > 0 ? 1.0 - Mth.frac(startZ) : Mth.frac(startZ));
        while (tX <= 1.0 || tY <= 1.0 || tZ <= 1.0) {
            if (tX < tY) {
                if (tX < tZ) {
                    currentX += signX;
                    tX += tDeltaX;
                } else {
                    currentZ += signZ;
                    tZ += tDeltaZ;
                }
            } else if (tY < tZ) {
                currentY += signY;
                tY += tDeltaY;
            } else {
                currentZ += signZ;
                tZ += tDeltaZ;
            }
            if (this.clipBlock(world, currentX, currentY, currentZ, toX, toY, toZ)) {
                return;
            }
        }
    }

    /**
     * One block of the traversal: its OUTLINE shape's {@code VoxelShape.clip(from, to, pos)} (VoxelShape.java:160-179).
     * {@code clipWithInteractionOverride} only changes the hit's face, never its location, and the fluid shape is empty.
     */
    private boolean clipBlock(final PickWorld world, final int bx, final int by, final int bz, final double toX, final double toY, final double toZ) {
        final BlockState state = world.blockState(bx, by, bz);
        if (state.isAir()) {
            return false;
        }
        final VoxelShape shape = world.outlineShape(state, this.cursor.set(bx, by, bz));
        if (shape.isEmpty()) {
            return false;
        }
        final double fromX = this.fromX;
        final double fromY = this.fromY;
        final double fromZ = this.fromZ;
        // to.subtract(from) = to.add(-from): the same double as to - from
        final double diffX = toX - fromX;
        final double diffY = toY - fromY;
        final double diffZ = toZ - fromZ;
        if (diffX * diffX + diffY * diffY + diffZ * diffZ < EPSILON) {
            return false;
        }
        final double testX = fromX + diffX * 0.001;
        final double testY = fromY + diffY * 0.001;
        final double testZ = fromZ + diffZ * 0.001;
        final List<AABB> parts = shape.toAabbs();
        // shape.isFullWide(findIndex(axis, test - pos)...): the test point in a full cell. The full cells are exactly the
        // union of toAabbs(), whose edges are the shape's own coordinates, and findIndex's cells are half-open, so this is
        // AABB.contains on the block-local point.
        final double localX = testX - bx;
        final double localY = testY - by;
        final double localZ = testZ - bz;
        for (int i = 0, size = parts.size(); i < size; i++) {
            final AABB part = parts.get(i);
            if (localX >= part.minX && localX < part.maxX && localY >= part.minY && localY < part.maxY && localZ >= part.minZ && localZ < part.maxZ) {
                this.blockHit = true;
                this.blockX = testX;
                this.blockY = testY;
                this.blockZ = testZ;
                this.blockPosX = bx;
                this.blockPosY = by;
                this.blockPosZ = bz;
                return true;
            }
        }
        // AABB.clip(toAabbs(), from, to, pos) (:313-331): every part moved to the block, one shared scale
        this.clipScale = 1.0;
        this.clipHit = false;
        for (int i = 0, size = parts.size(); i < size; i++) {
            final AABB part = parts.get(i);
            // aabb.move(pos): new AABB(min + pos, max + pos)
            final double x1 = part.minX + bx;
            final double y1 = part.minY + by;
            final double z1 = part.minZ + bz;
            final double x2 = part.maxX + bx;
            final double y2 = part.maxY + by;
            final double z2 = part.maxZ + bz;
            this.clipBox(Math.min(x1, x2), Math.min(y1, y2), Math.min(z1, z2), Math.max(x1, x2), Math.max(y1, y2), Math.max(z1, z2), diffX, diffY, diffZ);
        }
        if (!this.clipHit) {
            return false;
        }
        this.blockHit = true;
        this.blockX = fromX + this.clipScale * diffX;
        this.blockY = fromY + this.clipScale * diffY;
        this.blockZ = fromZ + this.clipScale * diffZ;
        this.blockPosX = bx;
        this.blockPosY = by;
        this.blockPosZ = bz;
        return true;
    }

    /** {@code AABB.getDirection} (:340-374) from {@link #fromX}: updates {@link #clipScale}/{@link #clipHit}. */
    private void clipBox(final double minX, final double minY, final double minZ, final double maxX, final double maxY, final double maxZ,
                         final double dx, final double dy, final double dz) {
        final double fx = this.fromX;
        final double fy = this.fromY;
        final double fz = this.fromZ;
        if (dx > EPSILON) {
            this.clipPoint(dx, dy, dz, minX, minY, maxY, minZ, maxZ, fx, fy, fz);
        } else if (dx < -EPSILON) {
            this.clipPoint(dx, dy, dz, maxX, minY, maxY, minZ, maxZ, fx, fy, fz);
        }
        if (dy > EPSILON) {
            this.clipPoint(dy, dz, dx, minY, minZ, maxZ, minX, maxX, fy, fz, fx);
        } else if (dy < -EPSILON) {
            this.clipPoint(dy, dz, dx, maxY, minZ, maxZ, minX, maxX, fy, fz, fx);
        }
        if (dz > EPSILON) {
            this.clipPoint(dz, dx, dy, minZ, minX, maxX, minY, maxY, fz, fx, fy);
        } else if (dz < -EPSILON) {
            this.clipPoint(dz, dx, dy, maxZ, minX, maxX, minY, maxY, fz, fx, fy);
        }
    }

    /** {@code AABB.clipPoint} (:376-404). */
    private void clipPoint(final double da, final double db, final double dc, final double point, final double minB, final double maxB, final double minC,
                           final double maxC, final double fromA, final double fromB, final double fromC) {
        final double s = (point - fromA) / da;
        final double pb = fromB + s * db;
        final double pc = fromC + s * dc;
        if (0.0 < s && s < this.clipScale && minB - EPSILON < pb && pb < maxB + EPSILON && minC - EPSILON < pc && pc < maxC + EPSILON) {
            this.clipScale = s;
            this.clipHit = true;
        }
    }

    // ------------------------------------------------------------------------------------------------------------------
    // Resolution
    // ------------------------------------------------------------------------------------------------------------------

    /**
     * Vanilla's entity loop over the candidates in index order, then the block/entity choice and {@code filterHitResult}.
     * Exactly the client's result when the client's entity order is the index order. Read the outcome with {@link #kind},
     * {@link #hoveredIndex}, {@link #hitX()}...
     */
    public Kind resolveInOrder() {
        double nearest = this.maxDistanceSq;
        int hovered = -1;
        for (int i = 0; i < this.count; i++) {
            final byte kind = this.kinds[i];
            if (kind == INSIDE) {
                if (nearest >= 0.0 && this.fromInside[i]) {
                    hovered = i;
                    nearest = 0.0;
                }
            } else if (kind == CLIPPED) {
                final double dd = this.distances[i];
                if (dd < nearest || nearest == 0.0) {
                    // shortcut: no candidate shares the attacker's root vehicle (riding attackers are not replayed)
                    hovered = i;
                    nearest = dd;
                }
            }
        }
        if (hovered >= 0 && this.distances[hovered] < this.blockDistanceSq) {
            final int h = hovered * 3;
            this.hitX = this.hits[h];
            this.hitY = this.hits[h + 1];
            this.hitZ = this.hits[h + 2];
            this.hovered = hovered;
            this.kind = this.distances[hovered] < this.entityRange * this.entityRange ? Kind.ENTITY : Kind.MISS;
            return this.kind;
        }
        this.hovered = -1;
        this.hitX = this.blockX;
        this.hitY = this.blockY;
        this.hitZ = this.blockZ;
        this.kind = this.blockHit && this.blockDistanceSq < this.blockRange * this.blockRange ? Kind.BLOCK : Kind.MISS;
        return this.kind;
    }

    /**
     * Whether some order of the candidates makes the client's {@code pick} return candidate {@code index} as an entity hit.
     * Unrolled from {@code getEntityHitResult}'s loop: an inside candidate wins when it comes last; a clipped one wins when
     * it comes right after any candidate that resets {@code nearest} to 0 (one with the eye inside, or a clip at distance
     * exactly 0), or else when it is strictly nearer than every other clipped candidate and than {@code maxDistanceSq}.
     * The winner must then be nearer than the block hit and within entity interaction range.
     *
     * @param withInexact count candidates added with {@code exact = false}; the candidate asked about always counts
     */
    public boolean canPick(final int index, final boolean withInexact) {
        return this.reachable(index, withInexact) && this.accepted(index);
    }

    private boolean reachable(final int index, final boolean withInexact) {
        final byte kind = this.kinds[index];
        if (kind == INSIDE) {
            return this.fromInside[index];
        }
        if (kind != CLIPPED) {
            return false;
        }
        final double dd = this.distances[index];
        if (dd == 0.0) {
            return true; // taken whatever nearest is (it is never negative), and nothing need follow it
        }
        boolean nearestOther = false;
        for (int i = 0; i < this.count; i++) {
            if (i == index || !withInexact && !this.exact[i]) {
                continue;
            }
            final byte other = this.kinds[i];
            if (other == INSIDE && this.fromInside[i] || other == CLIPPED && this.distances[i] == 0.0) {
                return true; // [everything else, the resetter, this one]
            }
            if (other == CLIPPED && this.distances[i] < dd) {
                nearestOther = true;
            }
        }
        return !nearestOther && dd < this.maxDistanceSq;
    }

    private boolean accepted(final int index) {
        final double dd = this.distances[index];
        return dd < this.blockDistanceSq && dd < this.entityRange * this.entityRange;
    }

    // ------------------------------------------------------------------------------------------------------------------
    // Results
    // ------------------------------------------------------------------------------------------------------------------

    /** The ray met the candidate's pick box at all (inside, or clipped within the entity pass's reach). */
    public boolean touched(final int index) {
        return this.kinds[index] != OUTSIDE;
    }

    public boolean inside(final int index) {
        return this.kinds[index] == INSIDE;
    }

    /** Squared distance from the eye to where the ray meets the candidate (0 when the eye is inside). */
    public double distanceSq(final int index) {
        return this.distances[index];
    }

    /**
     * Whether the full-length ray (block pass length) would meet the candidate's box, ignoring blocks: tells "behind a
     * wall" from "not aimed at" for a candidate the entity pass never touched.
     */
    public boolean onFullRay(final int index) {
        final int b = index * 6;
        final double max = Math.max(this.blockRange, this.entityRange);
        this.clipScale = 1.0;
        this.clipHit = false;
        this.clipBox(this.boxes[b], this.boxes[b + 1], this.boxes[b + 2], this.boxes[b + 3], this.boxes[b + 4], this.boxes[b + 5],
            this.viewX * max, this.viewY * max, this.viewZ * max);
        return this.clipHit;
    }

    public Kind kind() {
        return this.kind;
    }

    /** The candidate {@link #resolveInOrder} picked as an entity (also when {@code filterHitResult} turned it into a miss), or -1. */
    public int hoveredIndex() {
        return this.hovered;
    }

    public double hitX() {
        return this.hitX;
    }

    public double hitY() {
        return this.hitY;
    }

    public double hitZ() {
        return this.hitZ;
    }

    public boolean blockHit() {
        return this.blockHit;
    }

    /** The block pass's result location: the hit, or the ray's end on a miss. */
    public double blockX() {
        return this.blockX;
    }

    public double blockY() {
        return this.blockY;
    }

    public double blockZ() {
        return this.blockZ;
    }

    public double blockDistanceSq() {
        return this.blockDistanceSq;
    }

    public int blockPosX() {
        return this.blockPosX;
    }

    public int blockPosY() {
        return this.blockPosY;
    }

    public int blockPosZ() {
        return this.blockPosZ;
    }

    public double fromX() {
        return this.fromX;
    }

    public double fromY() {
        return this.fromY;
    }

    public double fromZ() {
        return this.fromZ;
    }

    public double viewX() {
        return this.viewX;
    }

    public double viewY() {
        return this.viewY;
    }

    public double viewZ() {
        return this.viewZ;
    }

    /** The entity pass's {@code maxDistanceSq}: the block hit's distance², or the larger range² on a block miss. */
    public double maxDistanceSq() {
        return this.maxDistanceSq;
    }

    public double maxDistance() {
        return this.maxDistance;
    }
}
