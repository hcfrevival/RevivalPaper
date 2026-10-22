package gg.hcfrevival.revival.auth.replicate;

import java.util.List;
import net.minecraft.network.protocol.Packet;
import net.minecraft.network.protocol.game.ClientGamePacketListener;
import net.minecraft.network.protocol.game.ClientboundEntityPositionSyncPacket;
import net.minecraft.network.protocol.game.ClientboundMoveEntityPacket;
import net.minecraft.network.protocol.game.VecDelta;
import net.minecraft.util.Mth;
import net.minecraft.world.entity.PositionPath;
import net.minecraft.world.entity.PositionStep;
import net.minecraft.world.phys.Vec3;

/**
 * One player's authoritative path, replicated to each viewer so the viewer's 26.3 stepped interpolation never runs dry
 * (report 03 §6, rules R1-R6, K = 2). Replaces the vanilla tracker output for players ({@code ServerEntity.sendChanges},
 * which sends a 2-tick step every 2 ticks and leaves the client's queue empty whenever the next packet is due:
 * MC-311974).
 * <ul>
 * <li>R1: {@link #append} one step per simulated client tick of the player; two ticks simulated in one server tick give
 * two steps, none give none (a stalled player freezes for everyone).</li>
 * <li>R2/R3: each step goes to each viewer as its own {@code MoveEntity.PosRot} with a one-step
 * {@code VecDelta.Stepped} of {@code stepTicks} (2) ticks, so every step keeps its exact rotation bytes. A delta past
 * ±32767 units (8 blocks) goes as an {@code EntityPositionSync} with a one-step stepped path instead.</li>
 * <li>R4: a step the client would ignore (same decoded end and rotation as the target it holds,
 * AbstractInterpolationHandler.java:42) is nudged one codec unit (1/4096 block) on x, so a still player keeps the queue
 * fed and never restarts it.</li>
 * <li>R5: the tracker sends every server tick, in one bundle per viewer ending in a ping (the hook's job).</li>
 * <li>R6: a jump the viewer's client would snap instead of interpolating is reported as {@link Result#RESPAWN}: the hook
 * re-pairs the player on that viewer ({@code RemoveEntities} + {@code AddEntity}) and calls {@link Viewer#spawned}.</li>
 * </ul>
 * Each {@link Viewer} tracks its client's {@code VecDeltaCodec} base exactly (VecDeltaCodec.java:14-40, VecDelta.java:
 * 179-222): the base is the decoded end of every move and the exact end of every sync.
 * <p>
 * Owned by the main thread.
 */
public final class PathFeed {

    /** Where a viewer's packets go, in order, each with the server time (ms) of the step it carries. */
    @FunctionalInterface
    public interface Sink {
        void send(Packet<ClientGamePacketListener> packet, long stamp);
    }

    /**
     * {@code authoritative.replication} settings.
     *
     * @param stepTicks {@code path-step-ticks}: the tick offset of every step, K (2; report 03 §6.2)
     * @param keepAlive {@code keep-alive}: nudge steps the client would ignore (R4)
     * @param preRoll {@code pre-roll}: after a spawn, first send a hold step at the spawn position, so the client's
     *                first rendered tick shows the spawn point rather than half a step (report 03 §6.3, M3)
     * @param respawnDistance {@code respawn-distance}: re-pair instead of an {@code EntityPositionSync} whose end is
     *                        farther than this from the viewer's display or from the last end sent. The client snaps past
     *                        64 blocks from its display; the margin covers the display moving while the sync is in flight
     */
    public record Settings(int stepTicks, boolean keepAlive, boolean preRoll, double respawnDistance) {
        public static final Settings DEFAULT = new Settings(2, true, true, 48.0);
    }

    public enum Result {
        /** Nothing new for this viewer. */
        IDLE,
        /** Packets went to the sink. */
        SENT,
        /** The next step needs a re-pair (big jump, or the viewer fell behind the ring): nothing was sent. */
        RESPAWN
    }

    /** {@code VecDeltaCodec.TRUNCATION_STEPS}. */
    private static final double TRUNCATION_STEPS = 4096.0;

    private final int entityId;
    private final Settings settings;
    private final int mask;
    private final double[] xs;
    private final double[] ys;
    private final double[] zs;
    private final float[] yRots;
    private final float[] xRots;
    private final boolean[] onGrounds;
    private final long[] stamps;
    private long appended;
    // The last two step packets built (review PT-16): a packet is immutable, so every viewer whose codec base gives the same
    // delta gets the same object, as the vanilla tracker fans one packet out to all its viewers. Two, because a still
    // player's keep-alive alternates +1 and -1 codec units
    private final ClientboundMoveEntityPacket.PosRot[] cached = new ClientboundMoveEntityPacket.PosRot[2];
    private int cacheNext;
    private long cacheBuilt;
    private long cacheShared;

    /**
     * @param entityId the player's entity id
     * @param capacity steps kept for viewers that drain late (rounded up to a power of two); a viewer more than this many
     *                 steps behind is re-paired
     */
    public PathFeed(final int entityId, final int capacity, final Settings settings) {
        this.entityId = entityId;
        this.settings = settings;
        final int size = Integer.highestOneBit(Math.max(2, capacity) - 1) << 1;
        this.mask = size - 1;
        this.xs = new double[size];
        this.ys = new double[size];
        this.zs = new double[size];
        this.yRots = new float[size];
        this.xRots = new float[size];
        this.onGrounds = new boolean[size];
        this.stamps = new long[size];
    }

    /** One authoritative client tick of the player: its position, rotation and ground flag at the end of the tick. */
    public void append(final double x, final double y, final double z, final float yRot, final float xRot, final boolean onGround, final long stamp) {
        final int slot = (int) (this.appended & this.mask);
        this.xs[slot] = x;
        this.ys[slot] = y;
        this.zs[slot] = z;
        this.yRots[slot] = yRot;
        this.xRots[slot] = xRot;
        this.onGrounds[slot] = onGround;
        this.stamps[slot] = stamp;
        this.appended++;
    }

    /** Steps appended so far. */
    public long appended() {
        return this.appended;
    }

    /**
     * The oldest step the ring still holds that is stamped at or after {@code sinceMillis}: its position goes to
     * {@code out[0..2]} and its stamp is returned; {@code Long.MIN_VALUE} when even the newest step is older. When the
     * ring no longer reaches back to {@code sinceMillis}, its oldest step is the answer (newer, never older).
     */
    public long positionSince(final long sinceMillis, final double[] out) {
        final long held = Math.min(this.appended, (long) this.mask + 1L);
        int found = -1;
        // Newest first; stamps count up (System.currentTimeMillis per step), so stop at the first older one
        for (long i = 1L; i <= held; i++) {
            final int slot = (int) ((this.appended - i) & this.mask);
            if (this.stamps[slot] < sinceMillis) {
                break;
            }
            found = slot;
        }
        if (found < 0) {
            return Long.MIN_VALUE;
        }
        out[0] = this.xs[found];
        out[1] = this.ys[found];
        out[2] = this.zs[found];
        return this.stamps[found];
    }

    /** The newest step: its position to {@code out[0..2]}, its stamp returned; {@code Long.MIN_VALUE} when there is none. */
    public long newest(final double[] out) {
        if (this.appended == 0L) {
            return Long.MIN_VALUE;
        }
        final int slot = (int) ((this.appended - 1L) & this.mask);
        out[0] = this.xs[slot];
        out[1] = this.ys[slot];
        out[2] = this.zs[slot];
        return this.stamps[slot];
    }

    /** Step packets built so far; every other move sent was a shared one ({@link #sharedMoves}). */
    public long builtMoves() {
        return this.cacheBuilt;
    }

    /** Moves sent as a packet another viewer (or step) already had built. */
    public long sharedMoves() {
        return this.cacheShared;
    }

    // The PosRot for one step, shared when an identical one was just built: no delta, list, step or packet per viewer
    private ClientboundMoveEntityPacket.PosRot move(final long xa, final long ya, final long za, final byte yRot, final byte xRot,
                                                    final boolean onGround, final int ticks) {
        for (final ClientboundMoveEntityPacket.PosRot packet : this.cached) {
            if (packet != null && packet.getYRot() == Mth.unpackDegrees(yRot) && packet.getXRot() == Mth.unpackDegrees(xRot)
                && packet.isOnGround() == onGround && sameDelta(packet.getPositionDelta(), xa, ya, za, ticks)) {
                this.cacheShared++;
                return packet;
            }
        }
        final VecDelta delta = new VecDelta.Stepped(List.of(new VecDelta.Stepped.DeltaStep((short) xa, (short) ya, (short) za, ticks)));
        final ClientboundMoveEntityPacket.PosRot packet = new ClientboundMoveEntityPacket.PosRot(this.entityId, delta, yRot, xRot, onGround);
        this.cached[this.cacheNext] = packet;
        this.cacheNext ^= 1;
        this.cacheBuilt++;
        return packet;
    }

    private static boolean sameDelta(final VecDelta delta, final long xa, final long ya, final long za, final int ticks) {
        if (!(delta instanceof final VecDelta.Stepped stepped) || stepped.steps().size() != 1) {
            return false;
        }
        final VecDelta.Stepped.DeltaStep step = stepped.steps().getFirst();
        return step.xa() == xa && step.ya() == ya && step.za() == za && step.ticks() == ticks;
    }

    public int entityId() {
        return this.entityId;
    }

    public Settings settings() {
        return this.settings;
    }

    /** A new viewer of this player, not yet spawned, with the feed's K. */
    public Viewer viewer() {
        return new Viewer(this.settings.stepTicks());
    }

    /** A new viewer of this player with its own K ({@code stepTicks}: the viewer's jitter decides, torture K1). */
    public Viewer viewer(final int stepTicks) {
        return new Viewer(stepTicks);
    }

    /** {@code VecDeltaCodec.encode} (:14-16). */
    static long encode(final double value) {
        return Math.round(value * TRUNCATION_STEPS);
    }

    /** {@code VecDeltaCodec.decode(xa, ya, za)} for one axis (:31-40): a zero delta keeps the base's exact double. */
    static double decode(final double base, final long delta) {
        return delta == 0L ? base : (encode(base) + delta) / TRUNCATION_STEPS;
    }

    /** {@code VecDeltaCodec.isDeltaTooBig} (:27-29). */
    static boolean tooBig(final long delta) {
        return delta < -32768L || delta > 32767L;
    }

    /** One viewer's copy of the feed: what its client holds (codec base, interpolation target) and what it still needs. */
    public final class Viewer {

        private boolean spawned;
        private long next;
        // The viewer's entity.getPositionCodec().getBase()
        private double baseX;
        private double baseY;
        private double baseZ;
        // The interpolation target the viewer's client will hold after everything sent so far
        private boolean hasTarget;
        private double targetX;
        private double targetY;
        private double targetZ;
        private float targetYRot;
        private float targetXRot;
        private boolean preRollPending;
        private byte spawnYRot;
        private byte spawnXRot;
        private boolean spawnOnGround;
        private long spawnStamp;
        private long sentMoves;
        private long sentSyncs;
        private long keepAlives;
        private long respawns;

        private int stepTicks;

        private Viewer(final int stepTicks) {
            this.stepTicks = stepTicks;
        }

        /** This viewer's K: the tick offset of every step it is sent. */
        public int stepTicks() {
            return this.stepTicks;
        }

        /**
         * A new K from the next step on (real-client run N4, review PT2-06): every step carries its own tick count, which
         * the client's stepped interpolation and the viewer's mirror both take per step, so the path just continues at
         * the new pace. A re-pair froze every remote player in view for two ticks at each switch.
         */
        public void setStepTicks(final int stepTicks) {
            this.stepTicks = stepTicks;
        }

        /**
         * The hook sent {@code AddEntity} for this player to the viewer. {@code x, y, z} are the packet's doubles (the
         * client's codec base, {@code LivingEntity.recreateFromPacket}); the rotation and ground flag are the player's
         * then. Steps appended before this are not sent.
         */
        public void spawned(final double x, final double y, final double z, final float yRot, final float xRot, final boolean onGround, final long stamp) {
            this.spawned = true;
            this.next = PathFeed.this.appended;
            this.baseX = x;
            this.baseY = y;
            this.baseZ = z;
            // A fresh entity is not interpolating: its first packet is never ignored
            this.hasTarget = false;
            this.preRollPending = PathFeed.this.settings.preRoll();
            this.spawnYRot = Mth.packDegrees(yRot);
            this.spawnXRot = Mth.packDegrees(xRot);
            this.spawnOnGround = onGround;
            this.spawnStamp = stamp;
        }

        /** The player was removed from the viewer. */
        public void despawned() {
            this.spawned = false;
            this.preRollPending = false;
        }

        public boolean isSpawned() {
            return this.spawned;
        }

        /**
         * Builds this server tick's packets for the viewer: the pre-roll after a spawn, then one packet per new step.
         *
         * @param displayX the player's displayed position on the viewer's client as its mirror last knew it
         *                 ({@code RemoteMirror.x()}), or NaN when unknown
         */
        public Result drain(final Sink sink, final double displayX, final double displayY, final double displayZ) {
            if (!this.spawned) {
                return Result.IDLE;
            }
            final PathFeed feed = PathFeed.this;
            final int ticks = this.stepTicks;
            boolean sent = false;
            if (this.preRollPending) {
                // [(P0, K)]: the client starts interpolating with currentStepTicks = 1 inside a hold, not half-way into P1
                this.preRollPending = false;
                this.sendMove(sink, 0L, 0L, 0L, this.spawnYRot, this.spawnXRot, this.spawnOnGround, ticks, this.spawnStamp);
                sent = true;
            }
            final long appended = feed.appended;
            if (appended - this.next > feed.mask + 1L) {
                this.respawns++;
                return Result.RESPAWN; // the ring overwrote steps this viewer never got
            }
            for (; this.next < appended; this.next++) {
                final int slot = (int) (this.next & feed.mask);
                final double x = feed.xs[slot];
                final double y = feed.ys[slot];
                final double z = feed.zs[slot];
                final float yRot = feed.yRots[slot];
                final float xRot = feed.xRots[slot];
                final boolean onGround = feed.onGrounds[slot];
                final long stamp = feed.stamps[slot];
                // VecDeltaCodec.encodeX/Y/Z: encode(pos) - encode(base)
                long xa = encode(x) - encode(this.baseX);
                final long ya = encode(y) - encode(this.baseY);
                final long za = encode(z) - encode(this.baseZ);
                if (tooBig(xa) || tooBig(ya) || tooBig(za)) {
                    if (this.wouldSnap(x, y, z, displayX, displayY, displayZ)) {
                        this.respawns++;
                        return Result.RESPAWN;
                    }
                    this.sendSync(sink, x, y, z, yRot, xRot, onGround, ticks, stamp);
                    sent = true;
                    continue;
                }
                final byte yByte = Mth.packDegrees(yRot);
                final byte xByte = Mth.packDegrees(xRot);
                if (feed.settings.keepAlive() && this.hasTarget && xa == 0L && ya == 0L && za == 0L
                    && Mth.unpackDegrees(yByte) == this.targetYRot && Mth.unpackDegrees(xByte) == this.targetXRot
                    && this.baseIsTarget()) {
                    // The client would see target.is(end, yRot, xRot) and drop it: move the end one unit instead
                    xa = 1L;
                    this.keepAlives++;
                }
                this.sendMove(sink, xa, ya, za, yByte, xByte, onGround, ticks, stamp);
                sent = true;
            }
            return sent ? Result.SENT : Result.IDLE;
        }

        // A zero delta decodes to the base itself; it is the held target unless something else moved the target
        private boolean baseIsTarget() {
            return Double.compare(this.baseX, this.targetX) == 0 && Double.compare(this.baseY, this.targetY) == 0
                && Double.compare(this.baseZ, this.targetZ) == 0;
        }

        private boolean wouldSnap(final double x, final double y, final double z, final double displayX, final double displayY, final double displayZ) {
            final double limit = PathFeed.this.settings.respawnDistance();
            final double limitSq = limit * limit;
            if (!Double.isNaN(displayX) && distanceSq(displayX, displayY, displayZ, x, y, z) > limitSq) {
                return true;
            }
            return this.hasTarget && distanceSq(this.targetX, this.targetY, this.targetZ, x, y, z) > limitSq;
        }

        private void sendMove(final Sink sink, final long xa, final long ya, final long za, final byte yRot, final byte xRot, final boolean onGround,
                              final int ticks, final long stamp) {
            sink.send(PathFeed.this.move(xa, ya, za, yRot, xRot, onGround, ticks), stamp);
            // handleMoveEntity: positionCodec.setBase(path.end); the target becomes (end, unpacked rotation)
            this.baseX = decode(this.baseX, xa);
            this.baseY = decode(this.baseY, ya);
            this.baseZ = decode(this.baseZ, za);
            this.target(this.baseX, this.baseY, this.baseZ, Mth.unpackDegrees(yRot), Mth.unpackDegrees(xRot));
            this.sentMoves++;
        }

        private void sendSync(final Sink sink, final double x, final double y, final double z, final float yRot, final float xRot, final boolean onGround,
                              final int ticks, final long stamp) {
            // Never an empty Stepped: the client's codec calls steps.getLast() (PositionPath.java:41-43)
            final PositionPath path = PositionPath.stepped(List.of(new PositionStep(new Vec3(x, y, z), ticks)));
            sink.send(new ClientboundEntityPositionSyncPacket(PathFeed.this.entityId, path, yRot, xRot, onGround), stamp);
            // handleEntityPositionSync: positionCodec.setBase(end), exact doubles
            this.baseX = x;
            this.baseY = y;
            this.baseZ = z;
            this.target(x, y, z, yRot, xRot);
            this.sentSyncs++;
        }

        private void target(final double x, final double y, final double z, final float yRot, final float xRot) {
            this.hasTarget = true;
            this.targetX = x;
            this.targetY = y;
            this.targetZ = z;
            this.targetYRot = yRot;
            this.targetXRot = xRot;
        }

        /** Steps not yet sent. */
        public long pending() {
            return PathFeed.this.appended - this.next;
        }

        public double baseX() {
            return this.baseX;
        }

        public double baseY() {
            return this.baseY;
        }

        public double baseZ() {
            return this.baseZ;
        }

        public long sentMoves() {
            return this.sentMoves;
        }

        public long sentSyncs() {
            return this.sentSyncs;
        }

        public long keepAlives() {
            return this.keepAlives;
        }

        public long respawns() {
            return this.respawns;
        }
    }

    static double distanceSq(final double ax, final double ay, final double az, final double bx, final double by, final double bz) {
        final double dx = bx - ax;
        final double dy = by - ay;
        final double dz = bz - az;
        return dx * dx + dy * dy + dz * dz;
    }
}
