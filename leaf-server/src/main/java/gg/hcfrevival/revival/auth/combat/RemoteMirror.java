package gg.hcfrevival.revival.auth.combat;

import java.util.List;
import java.util.Set;
import net.minecraft.network.protocol.game.VecDelta;
import net.minecraft.util.Mth;
import net.minecraft.world.entity.Pose;
import net.minecraft.world.entity.PositionMoveRotation;
import net.minecraft.world.entity.PositionPath;
import net.minecraft.world.entity.PositionStep;
import net.minecraft.world.entity.Relative;
import net.minecraft.world.phys.Vec3;

/**
 * One viewer's client copy of one remote player, rebuilt bit for bit from the packets that viewer applied and the client
 * tick it applied each one before (report 03 §7). The box the viewer's attack ray tested in client tick {@code n} is
 * {@link #minX()}..{@link #maxZ()} after every event with arrival {@code n} and before {@link #tick} for {@code n}.
 * <p>
 * A port of the 26.3 client, line for line, with primitives instead of {@code Vec3}/{@code LinkedList} so the per-tick
 * step allocates nothing:
 * <ul>
 * <li>{@code AbstractInterpolationHandler} (world/entity/AbstractInterpolationHandler.java:30-87):
 * {@code interpolateTo}, {@code interpolate}, {@code adjustInterpolationTargetFromDeltas};</li>
 * <li>{@code SteppedInterpolationHandler} and its {@code InterpolationData} (SteppedInterpolationHandler.java:27-201):
 * {@code startInterpolating}, {@code doInterpolate}, {@code advance}, {@code addSteps}, {@code getNewPositionAndRotation};
 * appendix A of report 03, extended with the rotation deltas and the codec;</li>
 * <li>{@code Entity.setPos/setRot/setYRot/setXRot/snapTo/absSnapTo} (Entity.java:479-499, 1831-1874, 4057-4075) and
 * {@code EntityDimensions.makeBoundingBox} (EntityDimensions.java:15-23);</li>
 * <li>{@code VecDeltaCodec.decode} and {@code VecDelta.Stepped.decode} (VecDeltaCodec.java:14-40, VecDelta.java:179-195);</li>
 * <li>{@code ClientPacketListener.handleAddEntity/handleMoveEntity/handleEntityPositionSync/handleTeleportEntity}
 * (ClientPacketListener.java:574-596, 649-698, 728-755, 809-829).</li>
 * </ul>
 * Exact while the target is a non-riding remote player in a chunk the viewer ticks, at a relative tick speed the book
 * knows, with no piston or shulker pushing it on the viewer's client. A snap while steps are queued makes the client test
 * its own world for collision ({@code AbstractInterpolationHandler.adjustInterpolationPosition}, :66-71); the mirror then
 * assumes free space and turns {@link #exact()} off. The replication feed never snaps (it re-spawns instead, report 03 R6).
 * <p>
 * Owned by the viewer's tick thread.
 */
public final class RemoteMirror {

    /** {@code distanceToSqr > 4096.0}: the client snaps instead of interpolating (ClientPacketListener.java:658, 812). */
    static final double SNAP_DISTANCE_SQR = 4096.0;
    /** {@code AbstractInterpolationHandler.adjustInterpolationTargetFromDeltas} (:74): smaller jumps are ignored. */
    static final double ADJUST_DISTANCE_SQR = 9.999999747378752E-6;
    /** {@code Entity.absSnapTo} clamps x and z to the world limit (Entity.java:1844-1845). */
    static final double WORLD_LIMIT = 3.0E7;
    /** {@code VecDeltaCodec.TRUNCATION_STEPS}: 1/4096 block (VecDeltaCodec.java:11). */
    static final double TRUNCATION_STEPS = 4096.0;

    /** {@code interpolationSteps} = {@code EntityType.updateInterval()}, 2 for players (SteppedInterpolationHandler.java:13-15). */
    private final int steps;

    // ---- the entity on the viewer's client
    private boolean present;
    private boolean passenger;
    private boolean spectator;
    private boolean exact = true;
    private double x;
    private double y;
    private double z;
    private float yRot;
    private float xRot;
    private Pose pose = Pose.STANDING;
    /** The {@code minecraft:scale} attribute's live value ({@code getScale()} reads it on demand). */
    private float scale = 1.0F;
    /** An attribute update waits for the entity tick's {@code refreshDirtyAttributes} to reach the dimensions. */
    private boolean scaleDirty;
    /** {@code Entity.dimensions}: what {@code setPos} builds the box from. */
    private float width = 0.6F;
    private float height = 1.8F;

    // ---- entity.getPositionCodec().getBase()
    private double baseX;
    private double baseY;
    private double baseZ;

    // ---- AbstractInterpolationHandler.lastPositionAndRotation
    private double lastX;
    private double lastY;
    private double lastZ;
    private float lastYRot;
    private float lastXRot;

    // ---- InterpolationData itself (the target)
    private double targetX;
    private double targetY;
    private double targetZ;
    private float targetYRot;
    private float targetXRot;

    // ---- InterpolationData.lastStepPosRot
    private double stepX;
    private double stepY;
    private double stepZ;
    private float stepYRot;
    private float stepXRot;

    private float currentStepTicks;
    private float remainingTicks;
    private float interpolationSpeed = 1.0F;

    // ---- InterpolationData.remainingSteps: a ring of primitives
    private double[] queueX = new double[8];
    private double[] queueY = new double[8];
    private double[] queueZ = new double[8];
    private float[] queueYRot = new float[8];
    private float[] queueXRot = new float[8];
    private int[] queueTicks = new int[8];
    private long[] queueStamp = new long[8];
    private int queueHead;
    private int queueSize;

    // ---- source times: the server time of the position each piece of state came from
    private long targetStamp;
    private long displayStamp;
    /** Stamp of the event being applied, for the steps and target it creates. */
    private long eventStamp;

    // ---- scratch for a decoded path (one packet), reused
    private double[] pathX = new double[4];
    private double[] pathY = new double[4];
    private double[] pathZ = new double[4];
    private int[] pathTicks = new int[4];
    /** Steps in the scratch path; 0 means {@code PositionPath.Linear} to {@link #endX}. */
    private int pathCount;
    private double endX;
    private double endY;
    private double endZ;

    public RemoteMirror(final int interpolationSteps) {
        this.steps = interpolationSteps;
    }

    // ------------------------------------------------------------------------------------------------------------------
    // Events, applied in write order at arrival (before the client tick they arrived before)
    // ------------------------------------------------------------------------------------------------------------------

    /** Applies one event. */
    public void apply(final MirrorEvent event) {
        switch (event) {
            case final MirrorEvent.Spawn s -> this.spawn(s.x(), s.y(), s.z(), s.yRot(), s.xRot(), s.stamp());
            case final MirrorEvent.Remove r -> this.remove();
            case final MirrorEvent.Move m -> this.move(m.hasPosition(), m.hasRotation(), m.delta(), m.yRot(), m.xRot(), m.stamp());
            case final MirrorEvent.Sync s -> this.sync(s.path(), s.yRot(), s.xRot(), s.stamp());
            case final MirrorEvent.Teleport t -> this.teleport(t.change(), t.relatives(), t.stamp());
            case final MirrorEvent.SetPose p -> this.pose(p.pose());
            case final MirrorEvent.SetScale s -> this.scale(s.scale());
            case final MirrorEvent.Riding r -> this.riding(r.riding());
            case final MirrorEvent.Spectator s -> this.spectator = s.spectator();
            case final MirrorEvent.Inexact i -> this.exact = false;
            case final MirrorEvent.Clear c -> this.remove();
            case final MirrorEvent.Passengers p -> {
                // book-level: MirrorBook resolves who rides what and calls riding(...)
            }
        }
    }

    /**
     * {@code ClientboundAddEntityPacket}: a new {@code RemotePlayer} (fresh interpolation handler, standing, scale 1), then
     * {@code LivingEntity.recreateFromPacket} (:4016-4031): the codec base takes the packet's doubles,
     * {@code absSnapTo} clamps x and z, {@code absSnapRotationTo} (Entity.java:1836-1841) sets the rotation.
     */
    public void spawn(final double x, final double y, final double z, final float yRot, final float xRot, final long stamp) {
        this.present = true;
        this.passenger = false;
        this.exact = true;
        this.pose = Pose.STANDING;
        this.scale = 1.0F;
        this.scaleDirty = false;
        this.width = 0.6F; // EntityTypes.PLAYER sized(0.6F, 1.8F) (EntityTypes.java:1173-1184)
        this.height = 1.8F;
        this.baseX = x;
        this.baseY = y;
        this.baseZ = z;
        // A fresh SteppedInterpolationHandler: PositionAndRotation.Mutable starts at Vec3.ZERO, 0, 0
        this.lastX = 0.0;
        this.lastY = 0.0;
        this.lastZ = 0.0;
        this.lastYRot = 0.0F;
        this.lastXRot = 0.0F;
        this.targetX = 0.0;
        this.targetY = 0.0;
        this.targetZ = 0.0;
        this.targetYRot = 0.0F;
        this.targetXRot = 0.0F;
        this.stepX = 0.0;
        this.stepY = 0.0;
        this.stepZ = 0.0;
        this.stepYRot = 0.0F;
        this.stepXRot = 0.0F;
        this.currentStepTicks = 0.0F;
        this.remainingTicks = 0.0F;
        this.interpolationSpeed = 1.0F;
        this.queueHead = 0;
        this.queueSize = 0;
        // absSnapTo(x, y, z) then absSnapRotationTo(yRot, xRot) on an entity whose rotation starts at 0
        this.x = Mth.clamp(x, -WORLD_LIMIT, WORLD_LIMIT);
        this.y = y;
        this.z = Mth.clamp(z, -WORLD_LIMIT, WORLD_LIMIT);
        this.yRot = 0.0F;
        this.xRot = 0.0F;
        this.setYRot(yRot % 360.0F);
        this.setXRot(Mth.clamp(xRot, -90.0F, 90.0F) % 360.0F);
        this.targetStamp = stamp;
        this.displayStamp = stamp;
    }

    /** {@code ClientboundRemoveEntitiesPacket}: the client discards the entity; later packets for its id are ignored. */
    public void remove() {
        this.present = false;
        this.queueSize = 0;
    }

    /** {@code ClientPacketListener.handleMoveEntity} (:738-752) for a remote (not locally authoritative) entity. */
    public void move(final boolean hasPosition, final boolean hasRotation, final VecDelta delta, final float yRot, final float xRot, final long stamp) {
        if (!this.present) {
            return;
        }
        this.eventStamp = stamp;
        if (hasPosition) {
            this.decode(delta);
            this.baseX = this.endX; // positionCodec.setBase(pos.endPosition())
            this.baseY = this.endY;
            this.baseZ = this.endZ;
            if (hasRotation) {
                this.interpolateTo(true, yRot, xRot, true);
            } else {
                this.interpolateTo(true, 0.0F, 0.0F, false);
            }
        } else if (hasRotation) {
            this.interpolateTo(false, yRot, xRot, true);
        }
    }

    /** {@code ClientPacketListener.handleEntityPositionSync} (:649-674), the target in a chunk the viewer ticks. */
    public void sync(final PositionPath path, final float yRot, final float xRot, final long stamp) {
        if (!this.present) {
            return;
        }
        this.eventStamp = stamp;
        if (!this.load(path)) {
            this.exact = false; // an empty Stepped throws in the client's codec: the client disconnects
            return;
        }
        this.baseX = this.endX;
        this.baseY = this.endY;
        this.baseZ = this.endZ;
        if (distanceToSqr(this.x, this.y, this.z, this.endX, this.endY, this.endZ) > SNAP_DISTANCE_SQR) {
            this.snapTo(this.endX, this.endY, this.endZ, yRot, xRot);
        } else {
            this.interpolateTo(true, yRot, xRot, true);
        }
    }

    /**
     * {@code ClientPacketListener.handleTeleportEntity} (:677-698) → {@code setValuesFromPositionPacket} (:809-829). A
     * remote player always takes the interpolate branch unless the move is over 64 blocks. The codec base is untouched.
     */
    public void teleport(final PositionMoveRotation change, final Set<Relative> relatives, final long stamp) {
        if (!this.present) {
            return;
        }
        this.eventStamp = stamp;
        // PositionMoveRotation.of(entity) reads getClientPositionAndRotation(): the target while one is active
        final boolean active = this.queueSize > 0;
        final double currentX = active ? this.targetX : this.x;
        final double currentY = active ? this.targetY : this.y;
        final double currentZ = active ? this.targetZ : this.z;
        final float currentYRot = active ? this.targetYRot : this.yRot;
        final float currentXRot = active ? this.targetXRot : this.xRot;
        // Vanilla's own arithmetic; the delta movement only feeds the result's delta, which a remote player ignores
        final PositionMoveRotation absolute = PositionMoveRotation.calculateAbsolute(
            new PositionMoveRotation(new Vec3(currentX, currentY, currentZ), Vec3.ZERO, currentYRot, currentXRot), change, relatives);
        final Vec3 position = absolute.position();
        if (distanceToSqr(currentX, currentY, currentZ, position.x, position.y, position.z) > SNAP_DISTANCE_SQR) {
            // entity.setPos, setYRot, setXRot (no modulo on yaw); the queue stays
            this.x = position.x;
            this.y = position.y;
            this.z = position.z;
            this.setYRot(absolute.yRot());
            this.setXRot(absolute.xRot());
            this.displayStamp = stamp;
        } else {
            // moveOrInterpolateTo(Vec3, yRot, xRot): PositionPath.of(position), one Linear step
            this.pathCount = 0;
            this.endX = position.x;
            this.endY = position.y;
            this.endZ = position.z;
            this.interpolateTo(true, absolute.yRot(), absolute.xRot(), true);
        }
    }

    /**
     * {@code DATA_POSE}: {@code Entity.onSyncedDataUpdated} → {@code refreshDimensions} (Entity.java:3520-3550) with the
     * scale attribute's live value. A sleeping player is also moved to its bed ({@code LivingEntity.setPosToBed}), which
     * this model doesn't follow.
     */
    public void pose(final Pose pose) {
        this.pose = pose;
        this.refreshDimensions();
        if (pose == Pose.SLEEPING) {
            this.exact = false;
        }
    }

    /** {@code minecraft:scale} changed: the dimensions follow in the entity's next tick. */
    public void scale(final float scale) {
        this.scale = scale;
        this.scaleDirty = true;
    }

    /** Riding: the vehicle positions the player every tick ({@code ClientLevel.tickPassenger}); dismounting moves it too. */
    public void riding(final boolean riding) {
        this.passenger = riding;
        this.exact = false;
    }

    public void spectator(final boolean spectator) {
        this.spectator = spectator;
    }

    public void markInexact() {
        this.exact = false;
    }

    // ------------------------------------------------------------------------------------------------------------------
    // The client tick
    // ------------------------------------------------------------------------------------------------------------------

    /**
     * The viewer's client tick for this entity: {@code Entity.commonTick} → {@code getInterpolation().interpolate()}
     * (Entity.java:527-537), then the entity tick's {@code refreshDirtyAttributes} (LivingEntity.java:2868). Passengers get
     * the same {@code commonTick} through {@code ClientLevel.tickPassenger} (:482-494).
     *
     * @param tickSpeed {@code ClientLevel.getRelativeTickSpeed()}: 1 unless the server tick rate is above 20 (:804-807)
     */
    public void tick(final float tickSpeed) {
        if (!this.present) {
            return;
        }
        this.interpolate(tickSpeed);
        if (this.scaleDirty) {
            this.scaleDirty = false;
            this.refreshDimensions();
        }
    }

    /** {@code AbstractInterpolationHandler.interpolate} (:56-64). */
    private void interpolate(final float tickSpeed) {
        if (this.queueSize == 0) {
            // cancel() → InterpolationData.reset(): currentStepTicks is NOT reset (SteppedInterpolationHandler.java:82-86)
            this.remainingTicks = 0.0F;
            this.interpolationSpeed = 1.0F;
            return;
        }
        this.adjustInterpolationTargetFromDeltas();
        this.doInterpolate(tickSpeed);
        this.lastX = this.x;
        this.lastY = this.y;
        this.lastZ = this.z;
        this.lastYRot = this.yRot;
        this.lastXRot = this.xRot;
    }

    /** AbstractInterpolationHandler.java:73-82, with {@code InterpolationData.addDelta/addRotation} (:89-109). */
    private void adjustInterpolationTargetFromDeltas() {
        // entity.position().subtract(last.position()): x + -lastX, the same double as x - lastX
        final double dx = this.x - this.lastX;
        final double dy = this.y - this.lastY;
        final double dz = this.z - this.lastZ;
        if (dx * dx + dy * dy + dz * dz > ADJUST_DISTANCE_SQR) {
            // adjustInterpolationPosition: the client shifts the path only if its own world has no collision for the box
            // at target + delta. shortcut: assume free space (a snapped player in the open) and stop claiming exactness.
            this.exact = false;
            this.targetX += dx;
            this.targetY += dy;
            this.targetZ += dz;
            for (int i = 0, slot = this.queueHead; i < this.queueSize; i++, slot = (slot + 1) & (this.queueX.length - 1)) {
                this.queueX[slot] += dx;
                this.queueY[slot] += dy;
                this.queueZ[slot] += dz;
            }
            this.stepX += dx;
            this.stepY += dy;
            this.stepZ += dz;
        }
        // Rotation deltas are always added, even zero ones (-0.0F + 0.0F = 0.0F, which the client does too)
        final float dYRot = this.yRot - this.lastYRot;
        final float dXRot = this.xRot - this.lastXRot;
        this.targetYRot += dYRot;
        this.targetXRot += dXRot;
        for (int i = 0, slot = this.queueHead; i < this.queueSize; i++, slot = (slot + 1) & (this.queueX.length - 1)) {
            this.queueYRot[slot] += dYRot;
            this.queueXRot[slot] += dXRot;
        }
        this.stepYRot += dYRot;
        this.stepXRot += dXRot;
    }

    /** {@code SteppedInterpolationHandler.doInterpolate} (:43-50) with {@code getNewPositionAndRotation} (:180-199). */
    private void doInterpolate(final float tickSpeed) {
        double newX = this.targetX;
        double newY = this.targetY;
        double newZ = this.targetZ;
        float newYRot = this.targetYRot;
        float newXRot = this.targetXRot;
        long stamp = this.targetStamp;
        while (this.queueSize > 0) {
            final int slot = this.queueHead;
            final int offset = this.queueTicks[slot];
            if (this.currentStepTicks < offset) {
                final float a = this.currentStepTicks / offset;
                // Vec3.lerp(vec, (double) a): Mth.lerp(a, this, vec) = this + a * (vec - this) (Vec3.java:230-232)
                newX = this.stepX + (double) a * (this.queueX[slot] - this.stepX);
                newY = this.stepY + (double) a * (this.queueY[slot] - this.stepY);
                newZ = this.stepZ + (double) a * (this.queueZ[slot] - this.stepZ);
                newYRot = Mth.rotLerp(a, this.stepYRot, this.queueYRot[slot]);
                newXRot = Mth.lerp(a, this.stepXRot, this.queueXRot[slot]);
                stamp = this.queueStamp[slot];
                break;
            }
            this.currentStepTicks -= offset;
            this.stepX = this.queueX[slot];
            this.stepY = this.queueY[slot];
            this.stepZ = this.queueZ[slot];
            this.stepYRot = this.queueYRot[slot];
            this.stepXRot = this.queueXRot[slot];
            this.queueHead = (slot + 1) & (this.queueX.length - 1);
            this.queueSize--;
        }
        // entity.setPos (the box follows) and entity.setRot (Entity.java:479-482)
        this.x = newX;
        this.y = newY;
        this.z = newZ;
        this.setYRot(newYRot % 360.0F);
        this.setXRot(newXRot % 360.0F);
        this.displayStamp = stamp;
        this.advance(tickSpeed);
    }

    /** {@code InterpolationData.advance} (:68-80). */
    private void advance(final float tickSpeed) {
        final float targetSpeed = Math.max(this.remainingTicks / this.steps, 1.0F);
        this.interpolationSpeed = Mth.lerp(1.0F / this.steps, this.interpolationSpeed, targetSpeed);
        float ticks = tickSpeed;
        if (ticks * this.interpolationSpeed < this.remainingTicks) {
            ticks *= this.interpolationSpeed;
        } else {
            ticks = this.remainingTicks;
            this.interpolationSpeed = 1.0F;
        }
        this.currentStepTicks += ticks;
        this.remainingTicks -= ticks;
    }

    // ------------------------------------------------------------------------------------------------------------------
    // Packet → interpolation (the path to apply is in the scratch: pathCount steps, or a Linear end)
    // ------------------------------------------------------------------------------------------------------------------

    /**
     * {@code AbstractInterpolationHandler.interpolateTo(path, yRot, xRot, hasRotation)} (:30-47). {@code hasPath} false is
     * a rotation-only packet: the path is a Linear one to the current position.
     */
    private void interpolateTo(final boolean hasPath, final float yRot, final float xRot, final boolean hasRotation) {
        final boolean active = this.queueSize > 0;
        // current = active ? interpolationData : entity.storePositionAndRotation()
        if (!hasPath) {
            this.pathCount = 0;
            this.endX = active ? this.targetX : this.x;
            this.endY = active ? this.targetY : this.y;
            this.endZ = active ? this.targetZ : this.z;
        }
        final float newYRot = hasRotation ? yRot : active ? this.targetYRot : this.yRot;
        final float newXRot = hasRotation ? xRot : active ? this.targetXRot : this.xRot;
        if (this.steps == 0) {
            // never for players: entity.snapTo(end, yRot, xRot); cancel()
            this.snapTo(this.endX, this.endY, this.endZ, newYRot, newXRot);
            this.queueSize = 0;
            this.remainingTicks = 0.0F;
            this.interpolationSpeed = 1.0F;
            return;
        }
        // interpolationData().is(end, yRot, xRot): float ==, then Vec3.equals (Double.compare per axis)
        final boolean same = this.targetYRot == newYRot && this.targetXRot == newXRot
            && same(this.targetX, this.endX) && same(this.targetY, this.endY) && same(this.targetZ, this.endZ);
        if (active && same) {
            // Identical to the active target: the client ignores it, nothing is queued. But it says the server still had
            // the target there at this stamp, so the target is that fresh (review stale-stamp-stationary-targets): a still
            // player's keep-alive keeps the rewind age of its box from growing past the cap
            this.refreshTargetStamp(this.eventStamp);
            return;
        }
        this.startInterpolating(newYRot, newXRot);
        // setLastPositionAndRotation(); entity.onInterpolationStart is a no-op for players
        this.lastX = this.x;
        this.lastY = this.y;
        this.lastZ = this.z;
        this.lastYRot = this.yRot;
        this.lastXRot = this.xRot;
    }

    /** {@code SteppedInterpolationHandler.startInterpolating} (:27-40) and {@code InterpolationData.addSteps} (:115-174). */
    private void startInterpolating(final float yRot, final float xRot) {
        if (this.queueSize == 0) {
            // setStartingPoint: currentStepTicks = 1.0F, the "starts one tick in" rule (:110-113); R and s untouched
            this.stepX = this.x;
            this.stepY = this.y;
            this.stepZ = this.z;
            this.stepYRot = this.yRot;
            this.stepXRot = this.xRot;
            this.currentStepTicks = 1.0F;
        }
        final long stamp = this.eventStamp;
        if (same(this.endX, this.targetX) && same(this.endY, this.targetY) && same(this.endZ, this.targetZ)) {
            // Objects.equals(endPosition, target position): the whole path collapses into one step of N ticks
            this.addStep(this.endX, this.endY, this.endZ, yRot, xRot, this.steps, stamp);
        } else if (this.pathCount == 0) {
            this.addStep(this.endX, this.endY, this.endZ, yRot, xRot, this.steps, stamp);
        } else if (yRot == this.targetYRot && xRot == this.targetXRot) {
            for (int i = 0; i < this.pathCount; i++) {
                this.addStep(this.pathX[i], this.pathY[i], this.pathZ[i], yRot, xRot, this.pathTicks[i], stamp);
            }
        } else {
            int total = 0;
            for (int i = 0; i < this.pathCount; i++) {
                total += this.pathTicks[i];
            }
            int offset = 0;
            for (int i = 0; i < this.pathCount; i++) {
                offset += this.pathTicks[i];
                final float a = (float) offset / total;
                this.addStep(this.pathX[i], this.pathY[i], this.pathZ[i], Mth.rotLerp(a, this.targetYRot, yRot), Mth.lerp(a, this.targetXRot, xRot),
                    this.pathTicks[i], stamp);
            }
        }
        this.targetX = this.endX;
        this.targetY = this.endY;
        this.targetZ = this.endZ;
        this.targetYRot = yRot;
        this.targetXRot = xRot;
        this.targetStamp = stamp;
    }

    /**
     * The target the queue ends at is confirmed as of {@code stamp}: the last queued step (which ends at it) and the target
     * take the newer stamp; earlier steps, and the display while it is still on its way, keep theirs. Once the display
     * reaches the target it carries the confirmation.
     */
    private void refreshTargetStamp(final long stamp) {
        if (stamp <= this.targetStamp) {
            return;
        }
        this.targetStamp = stamp;
        if (this.queueSize > 0) {
            final int last = (this.queueHead + this.queueSize - 1) & (this.queueX.length - 1);
            this.queueStamp[last] = stamp;
        }
    }

    /** {@code InterpolationData.addStep} (:115-118). */
    private void addStep(final double x, final double y, final double z, final float yRot, final float xRot, final int ticks, final long stamp) {
        if (this.queueSize == this.queueX.length) {
            this.growQueue();
        }
        final int slot = (this.queueHead + this.queueSize) & (this.queueX.length - 1);
        this.queueX[slot] = x;
        this.queueY[slot] = y;
        this.queueZ[slot] = z;
        this.queueYRot[slot] = yRot;
        this.queueXRot[slot] = xRot;
        this.queueTicks[slot] = ticks;
        this.queueStamp[slot] = stamp;
        this.queueSize++;
        this.remainingTicks += ticks;
    }

    private void growQueue() {
        final int length = this.queueX.length;
        final int grown = length * 2;
        final double[] nx = new double[grown];
        final double[] ny = new double[grown];
        final double[] nz = new double[grown];
        final float[] nyRot = new float[grown];
        final float[] nxRot = new float[grown];
        final int[] nTicks = new int[grown];
        final long[] nStamp = new long[grown];
        for (int i = 0; i < this.queueSize; i++) {
            final int slot = (this.queueHead + i) & (length - 1);
            nx[i] = this.queueX[slot];
            ny[i] = this.queueY[slot];
            nz[i] = this.queueZ[slot];
            nyRot[i] = this.queueYRot[slot];
            nxRot[i] = this.queueXRot[slot];
            nTicks[i] = this.queueTicks[slot];
            nStamp[i] = this.queueStamp[slot];
        }
        this.queueX = nx;
        this.queueY = ny;
        this.queueZ = nz;
        this.queueYRot = nyRot;
        this.queueXRot = nxRot;
        this.queueTicks = nTicks;
        this.queueStamp = nStamp;
        this.queueHead = 0;
    }

    /** {@code Entity.snapTo(x, y, z, yRot, xRot)} (:1868-1874): the queue is NOT cleared. */
    private void snapTo(final double x, final double y, final double z, final float yRot, final float xRot) {
        this.x = x;
        this.y = y;
        this.z = z;
        this.setYRot(yRot);
        this.setXRot(xRot);
        this.displayStamp = this.eventStamp;
    }

    /** {@code Entity.setYRot} (:4057-4063): a non-finite value is discarded. */
    private void setYRot(final float yRot) {
        if (Float.isFinite(yRot)) {
            this.yRot = yRot;
        }
    }

    /** {@code Entity.setXRot} (:4069-4075). */
    private void setXRot(final float xRot) {
        if (Float.isFinite(xRot)) {
            this.xRot = Math.clamp(xRot % 360.0F, -90.0F, 90.0F);
        }
    }

    /** {@code refreshDimensions}: {@code LivingEntity.getDimensions(pose)} (:3762-3764) with the live scale; the box follows. */
    private void refreshDimensions() {
        final Pose pose = this.pose;
        if (pose == Pose.SLEEPING) {
            this.width = 0.2F; // LivingEntity.SLEEPING_DIMENSIONS, fixed (:196)
            this.height = 0.2F;
            return;
        }
        // Avatar.POSES (Avatar.java:18-38); anything else is STANDING_DIMENSIONS
        final float baseWidth;
        final float baseHeight;
        final boolean fixed;
        switch (pose) {
            case FALL_FLYING, SWIMMING, SPIN_ATTACK -> {
                baseWidth = 0.6F;
                baseHeight = 0.6F;
                fixed = false;
            }
            case CROUCHING -> {
                baseWidth = 0.6F;
                baseHeight = 1.5F;
                fixed = false;
            }
            case DYING -> {
                baseWidth = 0.2F;
                baseHeight = 0.2F;
                fixed = true;
            }
            default -> {
                baseWidth = 0.6F;
                baseHeight = 1.8F;
                fixed = false;
            }
        }
        // EntityDimensions.scale(f, f) (EntityDimensions.java:29-41)
        if (!fixed && this.scale != 1.0F) {
            this.width = baseWidth * this.scale;
            this.height = baseHeight * this.scale;
        } else {
            this.width = baseWidth;
            this.height = baseHeight;
        }
    }

    // ------------------------------------------------------------------------------------------------------------------
    // Decoding into the scratch path
    // ------------------------------------------------------------------------------------------------------------------

    /** {@code VecDelta.decode(positionCodec)} against the current base, without touching it. */
    private void decode(final VecDelta delta) {
        switch (delta) {
            case final VecDelta.Linear linear -> {
                this.pathCount = 0;
                this.endX = decode(this.baseX, linear.xa());
                this.endY = decode(this.baseY, linear.ya());
                this.endZ = decode(this.baseZ, linear.za());
            }
            case final VecDelta.Stepped stepped -> {
                final List<VecDelta.Stepped.DeltaStep> list = stepped.steps();
                final int count = list.size();
                if (count == 0) {
                    // PositionPath.of(positionCodec.getBase()) (VecDelta.java:181-182)
                    this.pathCount = 0;
                    this.endX = this.baseX;
                    this.endY = this.baseY;
                    this.endZ = this.baseZ;
                    return;
                }
                this.ensurePath(count);
                double px = this.baseX;
                double py = this.baseY;
                double pz = this.baseZ;
                for (int i = 0; i < count; i++) {
                    // each step is relative to the previous step's decoded position (positionCodec.setBase(pos), :188)
                    final VecDelta.Stepped.DeltaStep step = list.get(i);
                    px = decode(px, step.xa());
                    py = decode(py, step.ya());
                    pz = decode(pz, step.za());
                    this.pathX[i] = px;
                    this.pathY[i] = py;
                    this.pathZ[i] = pz;
                    this.pathTicks[i] = step.ticks();
                }
                this.pathCount = count;
                this.endX = px;
                this.endY = py;
                this.endZ = pz;
            }
        }
    }

    /** Loads a full-precision {@code PositionPath} into the scratch; false for an empty {@code Stepped}. */
    private boolean load(final PositionPath path) {
        switch (path) {
            case final PositionPath.Linear linear -> {
                final Vec3 end = linear.endPosition();
                this.pathCount = 0;
                this.endX = end.x;
                this.endY = end.y;
                this.endZ = end.z;
            }
            case final PositionPath.Stepped stepped -> {
                final List<PositionStep> list = stepped.steps();
                final int count = list.size();
                if (count == 0) {
                    return false;
                }
                this.ensurePath(count);
                for (int i = 0; i < count; i++) {
                    final PositionStep step = list.get(i);
                    this.pathX[i] = step.position().x;
                    this.pathY[i] = step.position().y;
                    this.pathZ[i] = step.position().z;
                    this.pathTicks[i] = step.tickOffset();
                }
                this.pathCount = count;
                final Vec3 end = stepped.endPosition();
                this.endX = end.x;
                this.endY = end.y;
                this.endZ = end.z;
            }
        }
        return true;
    }

    private void ensurePath(final int count) {
        if (this.pathX.length < count) {
            final int size = Math.max(4, Integer.highestOneBit(count - 1) << 1);
            this.pathX = new double[size];
            this.pathY = new double[size];
            this.pathZ = new double[size];
            this.pathTicks = new int[size];
        }
    }

    /** {@code VecDeltaCodec.decode} for one axis (:31-40): a zero delta keeps the base's exact double. */
    static double decode(final double base, final long delta) {
        return delta == 0L ? base : (encode(base) + delta) / TRUNCATION_STEPS;
    }

    /** {@code VecDeltaCodec.encode} (:14-16). */
    static long encode(final double value) {
        return Math.round(value * TRUNCATION_STEPS);
    }

    /** {@code Vec3.equals} per axis: {@code Double.compare == 0} (Vec3.java:201-209), so -0.0 differs from 0.0. */
    private static boolean same(final double a, final double b) {
        return Double.compare(a, b) == 0;
    }

    /** {@code Vec3.distanceToSqr} (Vec3.java:135-140): the operand order is irrelevant to the squares. */
    static double distanceToSqr(final double ax, final double ay, final double az, final double bx, final double by, final double bz) {
        final double dx = bx - ax;
        final double dy = by - ay;
        final double dz = bz - az;
        return dx * dx + dy * dy + dz * dz;
    }

    // ------------------------------------------------------------------------------------------------------------------
    // Queries
    // ------------------------------------------------------------------------------------------------------------------

    /** On the viewer's client at all. */
    public boolean present() {
        return this.present;
    }

    /** {@code EntitySelector.CAN_BE_PICKED} for a remote player: present and not a spectator (Player.java:1644-1646). */
    public boolean pickable() {
        return this.present && !this.spectator;
    }

    /** Whether the position and box are the client's exactly (see the class comment for what breaks it). */
    public boolean exact() {
        return this.exact && !this.passenger;
    }

    public boolean passenger() {
        return this.passenger;
    }

    /** Server time (ms) of the newest position data the displayed position is built from. */
    public long displayStamp() {
        return this.displayStamp;
    }

    public double x() {
        return this.x;
    }

    public double y() {
        return this.y;
    }

    public double z() {
        return this.z;
    }

    public float yRot() {
        return this.yRot;
    }

    public float xRot() {
        return this.xRot;
    }

    public Pose pose() {
        return this.pose;
    }

    /** {@code Entity.dimensions.width()}. */
    public float width() {
        return this.width;
    }

    public float height() {
        return this.height;
    }

    // The box: EntityDimensions.makeBoundingBox(x, y, z) = new AABB(x - w, y, z - w, x + w, y + h, z + w), w = width / 2.0F

    public double minX() {
        final float w = this.width / 2.0F;
        return Math.min(this.x - w, this.x + w);
    }

    public double minY() {
        return Math.min(this.y, this.y + this.height);
    }

    public double minZ() {
        final float w = this.width / 2.0F;
        return Math.min(this.z - w, this.z + w);
    }

    public double maxX() {
        final float w = this.width / 2.0F;
        return Math.max(this.x - w, this.x + w);
    }

    public double maxY() {
        return Math.max(this.y, this.y + this.height);
    }

    public double maxZ() {
        final float w = this.width / 2.0F;
        return Math.max(this.z - w, this.z + w);
    }

    // Internal state, for the verification harness and diagnostics

    public double baseX() {
        return this.baseX;
    }

    public double baseY() {
        return this.baseY;
    }

    public double baseZ() {
        return this.baseZ;
    }

    public double targetX() {
        return this.targetX;
    }

    public double targetY() {
        return this.targetY;
    }

    public double targetZ() {
        return this.targetZ;
    }

    public float targetYRot() {
        return this.targetYRot;
    }

    public float targetXRot() {
        return this.targetXRot;
    }

    public float currentStepTicks() {
        return this.currentStepTicks;
    }

    public float remainingTicks() {
        return this.remainingTicks;
    }

    public float interpolationSpeed() {
        return this.interpolationSpeed;
    }

    /** {@code remainingSteps.size()}; non-zero means {@code hasActiveInterpolation()}. */
    public int queuedSteps() {
        return this.queueSize;
    }
}
