package gg.hcfrevival.revival.auth.combat;

import it.unimi.dsi.fastutil.ints.Int2ObjectOpenHashMap;
import it.unimi.dsi.fastutil.ints.IntList;
import it.unimi.dsi.fastutil.objects.ObjectArrayList;
import java.util.Arrays;
import java.util.UUID;
import net.minecraft.network.protocol.Packet;
import net.minecraft.network.protocol.game.ClientboundAddEntityPacket;
import net.minecraft.network.protocol.game.ClientboundBundlePacket;
import net.minecraft.network.protocol.game.ClientboundEntityPositionSyncPacket;
import net.minecraft.network.protocol.game.ClientboundLoginPacket;
import net.minecraft.network.protocol.game.ClientboundMoveEntityPacket;
import net.minecraft.network.protocol.game.ClientboundPlayerInfoUpdatePacket;
import net.minecraft.network.protocol.game.ClientboundRemoveEntitiesPacket;
import net.minecraft.network.protocol.game.ClientboundRespawnPacket;
import net.minecraft.network.protocol.game.ClientboundSetEntityDataPacket;
import net.minecraft.network.protocol.game.ClientboundSetPassengersPacket;
import net.minecraft.network.protocol.game.ClientboundTeleportEntityPacket;
import net.minecraft.network.protocol.game.ClientboundUpdateAttributesPacket;
import net.minecraft.world.entity.EntityTypes;
import net.minecraft.world.level.GameType;
import net.minecraft.world.phys.AABB;
import org.jspecify.annotations.Nullable;

/**
 * One viewer's mirrors of every remote player its client has (report 03 §7, incremental form). Events go in tagged with
 * the viewer's client tick they were applied before (the window whose pong acked them, design §3.2); each
 * {@code TickEnd(T)} from the viewer then runs {@link #onViewerTickEnd}: apply the events with arrival {@code T}, record
 * every target's box as the viewer's attack ray in tick {@code T} saw it ({@link #sample}), and run the client's
 * interpolation step for tick {@code T}.
 * <p>
 * Events must arrive in the order the server wrote the packets, and every event for tick {@code T} before
 * {@code onViewerTickEnd(T)}: true when pongs, attacks and tick ends are processed in client-stream order. An event that
 * arrives after its tick was already run is applied at once and marks its target inexact.
 * <p>
 * Each target keeps a ring of the last {@code historyTicks} samples, for the rewind cap and diagnostics. Owned by the
 * viewer's tick thread; {@link #onViewerTickEnd} allocates nothing.
 */
public final class MirrorBook {

    private static final byte PRESENT = 1;
    private static final byte PICKABLE = 2;
    private static final byte EXACT = 4;

    /** The box a target had at one viewer tick, as the attack ray saw it. Filled by {@link #sample}. */
    public static final class Sample {
        public int targetId;
        public int tick;
        /** On the viewer's client and pickable (not a spectator): only then is the box meaningful. */
        public boolean pickable;
        public boolean exact;
        public double minX;
        public double minY;
        public double minZ;
        public double maxX;
        public double maxY;
        public double maxZ;
        /** Server time (ms) of the newest position data behind the box, see {@link MirrorEvent}. */
        public long stamp;

        public AABB box() {
            return new AABB(this.minX, this.minY, this.minZ, this.maxX, this.maxY, this.maxZ);
        }
    }

    /** One target: its mirror and its box history. */
    private static final class Entry {
        final int id;
        final RemoteMirror mirror;
        final int[] ticks;
        final double[] boxes;
        final long[] stamps;
        final byte[] flags;
        int newest = -1;
        int count;
        int absentTicks;
        int vehicle = -1;
        @Nullable UUID uuid;

        Entry(final int id, final int steps, final int history) {
            this.id = id;
            this.mirror = new RemoteMirror(steps);
            this.ticks = new int[history];
            this.boxes = new double[history * 6];
            this.stamps = new long[history];
            this.flags = new byte[history];
        }

        void record(final int tick) {
            final RemoteMirror m = this.mirror;
            final int slot = this.newest = (this.newest + 1) % this.ticks.length;
            this.count = Math.min(this.count + 1, this.ticks.length);
            this.ticks[slot] = tick;
            byte flags = 0;
            if (m.present()) {
                flags |= PRESENT;
                if (m.pickable()) {
                    flags |= PICKABLE;
                }
                if (m.exact()) {
                    flags |= EXACT;
                }
                final int b = slot * 6;
                this.boxes[b] = m.minX();
                this.boxes[b + 1] = m.minY();
                this.boxes[b + 2] = m.minZ();
                this.boxes[b + 3] = m.maxX();
                this.boxes[b + 4] = m.maxY();
                this.boxes[b + 5] = m.maxZ();
            }
            this.flags[slot] = flags;
            this.stamps[slot] = m.displayStamp();
        }

        boolean read(final int tick, final Sample out) {
            for (int i = 0, slot = this.newest; i < this.count; i++, slot = slot == 0 ? this.ticks.length - 1 : slot - 1) {
                if (this.ticks[slot] != tick) {
                    continue;
                }
                final byte flags = this.flags[slot];
                out.targetId = this.id;
                out.tick = tick;
                out.pickable = (flags & PICKABLE) != 0;
                out.exact = (flags & EXACT) != 0;
                final int b = slot * 6;
                out.minX = this.boxes[b];
                out.minY = this.boxes[b + 1];
                out.minZ = this.boxes[b + 2];
                out.maxX = this.boxes[b + 3];
                out.maxY = this.boxes[b + 4];
                out.maxZ = this.boxes[b + 5];
                out.stamp = this.stamps[slot];
                return true;
            }
            return false;
        }
    }

    private final int steps;
    private final int historyTicks;
    private final Int2ObjectOpenHashMap<Entry> entries = new Int2ObjectOpenHashMap<>();
    private final ObjectArrayList<Entry> order = new ObjectArrayList<>();

    // Pending events, a FIFO ring in write order
    private int[] pendingTargets = new int[64];
    private int[] pendingArrivals = new int[64];
    private MirrorEvent[] pendingEvents = new MirrorEvent[64];
    private int pendingHead;
    private int pendingSize;

    private int lastTick;
    private boolean started;
    /** Set while {@link #onPacket(Packet, int, long, boolean)} converts a packet with an upper-bound arrival. */
    private boolean unbracketed;
    private float relativeTickSpeed = 1.0F;
    private long lateEvents;

    /**
     * @param interpolationSteps {@code EntityTypes.PLAYER.updateInterval()} on the client: {@link #playerInterpolationSteps()}
     * @param historyTicks how many viewer ticks of boxes each target keeps (at least 1)
     */
    public MirrorBook(final int interpolationSteps, final int historyTicks) {
        this.steps = interpolationSteps;
        this.historyTicks = Math.max(1, historyTicks);
    }

    /** {@code interpolationSteps} of a remote player: the player type's update interval (2 in 26.3). */
    public static int playerInterpolationSteps() {
        return EntityTypes.PLAYER.updateInterval();
    }

    // ------------------------------------------------------------------------------------------------------------------
    // Input
    // ------------------------------------------------------------------------------------------------------------------

    /**
     * Queues one event for {@code targetId}, applied before the viewer's client tick {@code arrivalTick}.
     * {@link MirrorEvent.Clear} and {@link MirrorEvent.Passengers} act on the whole book ({@code targetId} is ignored for
     * Clear and is the vehicle for Passengers).
     */
    public void onEvent(final int targetId, final MirrorEvent event, final int arrivalTick) {
        if (this.started && arrivalTick <= this.lastTick) {
            // Its tick already ran: the client applied it earlier than we can now model
            this.lateEvents++;
            this.applyNow(targetId, event);
            final Entry entry = this.entries.get(targetId);
            if (entry != null) {
                entry.mirror.markInexact();
            }
            return;
        }
        if (this.pendingSize == this.pendingTargets.length) {
            this.growPending();
        }
        final int slot = (this.pendingHead + this.pendingSize) & (this.pendingTargets.length - 1);
        this.pendingTargets[slot] = targetId;
        this.pendingArrivals[slot] = arrivalTick;
        this.pendingEvents[slot] = event;
        this.pendingSize++;
    }

    /**
     * {@link #onPacket(Packet, int, long)} for a packet whose arrival is only an upper bound (sent outside a pinged
     * bundle, transactions rule A): the client may have applied it ticks earlier, so every target it touches stops being
     * exact until its next spawn.
     */
    public boolean onPacket(final Packet<?> packet, final int arrivalTick, final long stamp, final boolean exact) {
        if (exact) {
            return this.onPacket(packet, arrivalTick, stamp);
        }
        this.unbracketed = true;
        try {
            return this.onPacket(packet, arrivalTick, stamp);
        } finally {
            this.unbracketed = false;
        }
    }

    /**
     * Turns one packet the server wrote to this viewer into events, for every remote player it touches. Packets that
     * don't touch a player the book knows are ignored. Returns whether anything was queued.
     *
     * @param stamp server time (ms) of the position the packet carries (see {@link MirrorEvent})
     */
    public boolean onPacket(final Packet<?> packet, final int arrivalTick, final long stamp) {
        switch (packet) {
            case final ClientboundBundlePacket bundle -> {
                boolean any = false;
                for (final Packet<?> sub : bundle.subPackets()) {
                    any |= this.onPacket(sub, arrivalTick, stamp);
                }
                return any;
            }
            case final ClientboundAddEntityPacket add -> {
                if (add.getType() != EntityTypes.PLAYER) {
                    return false;
                }
                this.entry(add.getId()).uuid = add.getUUID();
                return this.queue(add.getId(), MirrorEvent.of(add, stamp), arrivalTick);
            }
            case final ClientboundRemoveEntitiesPacket remove -> {
                boolean any = false;
                final IntList ids = remove.entityIds();
                for (int i = 0, size = ids.size(); i < size; i++) {
                    final int id = ids.getInt(i);
                    if (this.entries.containsKey(id)) {
                        any |= this.queue(id, MirrorEvent.REMOVE, arrivalTick);
                    }
                }
                return any;
            }
            case final ClientboundMoveEntityPacket move -> {
                final int id = MirrorEvent.entityId(move);
                return this.entries.containsKey(id) && this.queue(id, MirrorEvent.of(move, stamp), arrivalTick);
            }
            case final ClientboundEntityPositionSyncPacket sync -> {
                return this.entries.containsKey(sync.id()) && this.queue(sync.id(), MirrorEvent.of(sync, stamp), arrivalTick);
            }
            case final ClientboundTeleportEntityPacket teleport -> {
                return this.entries.containsKey(teleport.id()) && this.queue(teleport.id(), MirrorEvent.of(teleport, stamp), arrivalTick);
            }
            case final ClientboundSetEntityDataPacket data -> {
                if (!this.entries.containsKey(data.id())) {
                    return false;
                }
                final MirrorEvent.SetPose pose = MirrorEvent.pose(data);
                return pose != null && this.queue(data.id(), pose, arrivalTick);
            }
            case final ClientboundUpdateAttributesPacket attributes -> {
                if (!this.entries.containsKey(attributes.getEntityId())) {
                    return false;
                }
                final MirrorEvent.SetScale scale = MirrorEvent.scale(attributes);
                return scale != null && this.queue(attributes.getEntityId(), scale, arrivalTick);
            }
            case final ClientboundSetPassengersPacket passengers -> {
                return this.queue(passengers.getVehicle(), new MirrorEvent.Passengers(passengers.getPassengers()), arrivalTick);
            }
            case final ClientboundPlayerInfoUpdatePacket info -> {
                if (!info.actions().contains(ClientboundPlayerInfoUpdatePacket.Action.UPDATE_GAME_MODE)) {
                    return false;
                }
                boolean any = false;
                for (final ClientboundPlayerInfoUpdatePacket.Entry update : info.entries()) {
                    for (int i = 0, size = this.order.size(); i < size; i++) {
                        final Entry entry = this.order.get(i);
                        if (update.profileId().equals(entry.uuid)) {
                            any |= this.queue(entry.id, new MirrorEvent.Spectator(update.gameMode() == GameType.SPECTATOR), arrivalTick);
                        }
                    }
                }
                return any;
            }
            case final ClientboundRespawnPacket respawn -> {
                return this.queue(-1, MirrorEvent.CLEAR, arrivalTick);
            }
            case final ClientboundLoginPacket login -> {
                return this.queue(-1, MirrorEvent.CLEAR, arrivalTick);
            }
            default -> {
                return false;
            }
        }
    }

    private boolean queue(final int targetId, final MirrorEvent event, final int arrivalTick) {
        this.onEvent(targetId, event, arrivalTick);
        if (this.unbracketed) {
            // after the event itself, so a Spawn (which starts exact) is still marked
            this.onEvent(targetId, MirrorEvent.INEXACT, arrivalTick);
        }
        return true;
    }

    /** {@code ClientLevel.getRelativeTickSpeed()} as the viewer's client has it (from {@code ClientboundTickingStatePacket}). */
    public void setRelativeTickSpeed(final float relativeTickSpeed) {
        this.relativeTickSpeed = relativeTickSpeed;
    }

    /**
     * The viewer's {@code TickEnd(tick)}: applies the events with arrival {@code tick}, records each target's box for the
     * attack ray of client tick {@code tick}, then runs the client's interpolation for that tick. Ticks are expected to
     * count up by one; a gap is run tick by tick.
     */
    public void onViewerTickEnd(final int tick) {
        if (!this.started) {
            this.started = true;
            this.lastTick = tick - 1;
        }
        for (int t = this.lastTick + 1; t <= tick; t++) {
            this.runTick(t);
        }
    }

    private void runTick(final int tick) {
        // Events drained before this tick
        while (this.pendingSize > 0 && this.pendingArrivals[this.pendingHead] <= tick) {
            final int slot = this.pendingHead;
            final int targetId = this.pendingTargets[slot];
            final MirrorEvent event = this.pendingEvents[slot];
            this.pendingEvents[slot] = null;
            this.pendingHead = (slot + 1) & (this.pendingTargets.length - 1);
            this.pendingSize--;
            this.applyNow(targetId, event);
        }
        // Minecraft.tick: pick(1.0F) before level.tickEntities()
        final float speed = this.relativeTickSpeed;
        for (int i = this.order.size() - 1; i >= 0; i--) {
            final Entry entry = this.order.get(i);
            entry.record(tick);
            entry.mirror.tick(speed);
            if (entry.mirror.present()) {
                entry.absentTicks = 0;
            } else if (++entry.absentTicks > this.historyTicks) {
                this.entries.remove(entry.id);
                this.order.set(i, this.order.get(this.order.size() - 1));
                this.order.remove(this.order.size() - 1);
            }
        }
        this.lastTick = tick;
    }

    private void applyNow(final int targetId, final MirrorEvent event) {
        switch (event) {
            case final MirrorEvent.Clear clear -> {
                for (int i = 0, size = this.order.size(); i < size; i++) {
                    final Entry entry = this.order.get(i);
                    entry.mirror.remove();
                    entry.vehicle = -1;
                }
            }
            case final MirrorEvent.Passengers passengers -> this.passengers(targetId, passengers.passengers());
            case final MirrorEvent.Spawn spawn -> {
                final Entry entry = this.entry(targetId);
                entry.vehicle = -1;
                entry.mirror.apply(spawn);
            }
            default -> {
                final Entry entry = this.entries.get(targetId);
                if (entry != null) {
                    entry.mirror.apply(event);
                }
            }
        }
    }

    /** {@code ejectPassengers()}, then every listed entity the client has starts riding the vehicle. */
    private void passengers(final int vehicle, final int[] passengers) {
        for (int i = 0, size = this.order.size(); i < size; i++) {
            final Entry entry = this.order.get(i);
            if (!entry.mirror.present()) {
                continue;
            }
            boolean listed = false;
            for (final int passenger : passengers) {
                if (passenger == entry.id) {
                    listed = true;
                    break;
                }
            }
            if (listed) {
                entry.vehicle = vehicle;
                entry.mirror.riding(true);
            } else if (entry.vehicle == vehicle) {
                entry.vehicle = -1;
                entry.mirror.riding(false);
            }
        }
    }

    private Entry entry(final int targetId) {
        Entry entry = this.entries.get(targetId);
        if (entry == null) {
            entry = new Entry(targetId, this.steps, this.historyTicks);
            this.entries.put(targetId, entry);
            this.order.add(entry);
        }
        return entry;
    }

    private void growPending() {
        final int length = this.pendingTargets.length;
        final int[] targets = new int[length * 2];
        final int[] arrivals = new int[length * 2];
        final MirrorEvent[] events = new MirrorEvent[length * 2];
        for (int i = 0; i < this.pendingSize; i++) {
            final int slot = (this.pendingHead + i) & (length - 1);
            targets[i] = this.pendingTargets[slot];
            arrivals[i] = this.pendingArrivals[slot];
            events[i] = this.pendingEvents[slot];
        }
        this.pendingTargets = targets;
        this.pendingArrivals = arrivals;
        this.pendingEvents = events;
        this.pendingHead = 0;
    }

    // ------------------------------------------------------------------------------------------------------------------
    // Queries
    // ------------------------------------------------------------------------------------------------------------------

    /**
     * The box {@code targetId} had when the viewer's client tick {@code tick} cast its attack ray. False when the book never
     * saw the target, or the tick is not in its history (not run yet, or older than {@code historyTicks}).
     */
    public boolean sample(final int targetId, final int tick, final Sample out) {
        final Entry entry = this.entries.get(targetId);
        return entry != null && entry.read(tick, out);
    }

    /** {@link #sample} as an {@code AABB}, or null when there is none or the target wasn't pickable then. */
    public @Nullable AABB boxAtPick(final int targetId, final int tick) {
        final Sample sample = new Sample();
        return this.sample(targetId, tick, sample) && sample.pickable ? sample.box() : null;
    }

    /** Whether {@link #boxAtPick} for the same arguments is the client's box exactly. */
    public boolean exactAtPick(final int targetId, final int tick) {
        final Sample sample = new Sample();
        return this.sample(targetId, tick, sample) && sample.exact;
    }

    /** Whether the book has ever seen {@code targetId} (and not yet forgotten it). */
    public boolean knows(final int targetId) {
        return this.entries.containsKey(targetId);
    }

    /** The live mirror of {@code targetId} (state after the last tick run), or null. */
    public @Nullable RemoteMirror mirror(final int targetId) {
        final Entry entry = this.entries.get(targetId);
        return entry == null ? null : entry.mirror;
    }

    /** Number of targets, for {@link #sampleAt}. */
    public int size() {
        return this.order.size();
    }

    /** {@link #sample} for the {@code index}-th target (0 ≤ index &lt; {@link #size()}), in no particular order. */
    public boolean sampleAt(final int index, final int tick, final Sample out) {
        return this.order.get(index).read(tick, out);
    }

    /** The last viewer tick run, or {@code Integer.MIN_VALUE} before the first. */
    public int lastTick() {
        return this.started ? this.lastTick : Integer.MIN_VALUE;
    }

    /** Events that arrived after their tick had already run (each marked its target inexact). */
    public long lateEvents() {
        return this.lateEvents;
    }

    public int historyTicks() {
        return this.historyTicks;
    }

    /** Forgets everything (the viewer left). */
    public void reset() {
        this.entries.clear();
        this.order.clear();
        Arrays.fill(this.pendingEvents, null);
        this.pendingHead = 0;
        this.pendingSize = 0;
        this.started = false;
    }
}
