package gg.hcfrevival.revival.auth.net;

import gg.hcfrevival.revival.auth.trace.TraceRecorder;
import io.netty.channel.ChannelFutureListener;
import it.unimi.dsi.fastutil.ints.IntOpenHashSet;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.concurrent.ThreadLocalRandom;
import net.minecraft.network.Connection;
import net.minecraft.network.protocol.Packet;
import net.minecraft.network.protocol.common.ClientboundPingPacket;
import net.minecraft.network.protocol.game.ClientGamePacketListener;
import net.minecraft.network.protocol.game.ClientboundAddEntityPacket;
import net.minecraft.network.protocol.game.ClientboundBundlePacket;
import net.minecraft.network.protocol.game.ClientboundLoginPacket;
import net.minecraft.network.protocol.game.ClientboundRemoveEntitiesPacket;
import net.minecraft.network.protocol.game.ClientboundRespawnPacket;
import net.minecraft.world.entity.EntityType;
import org.jspecify.annotations.Nullable;

/**
 * One connection's transactions (design §3.2 and §15.1, report 02 §6): our ping ids, the log of physics packets sent
 * under each ping, the acks, and the ack deadlines.
 * <p>
 * <b>Wrapping.</b> A physics packet (see {@link OutboundClassifier}) goes out inside a bundle that starts with a ping.
 * The client applies a bundle in one drain and answers the ping from that drain, so {@code Pong(id)} in window
 * {@code W_T} means the packet was applied before client tick {@code T} exactly. While the server tick holds the
 * connection's flushes ({@code suspendFlushing}), every packet from the first classified one on is gathered into one
 * open bundle with one ping, sent when flushing resumes: one pong per connection per server tick in steady state, far
 * under Paper's packet limiter. Between ticks each classified packet gets its own bundle. Packets that can't be
 * bundled (chunk data: Paper's extra packets and anti-xray readiness; the login packet; terminal packets) go out on
 * their own with a ping right after them: an upper bound (rule A). A heartbeat ping goes out every server tick that sent
 * no other, so the tick clock and the deadlines always have acks.
 * <p>
 * <b>Ids</b> (§15.1). Ours are in {@code [PING_MIN, PING_MAX]}, below -65536 and so disjoint from GrimAC's
 * {@code -(n & 0x7FFF)}, starting at a random point and counting down. Outstanding ids are a FIFO: a pong must match the
 * oldest. A pong for a later outstanding id (the client skipped some) is a violation; it still releases everything up
 * to it so the model keeps moving. An id in our band that was never sent or is already acked is a violation, except a
 * duplicate of a recent ack, which is ignored. Ids outside the band are foreign.
 * <p>
 * <b>Deadlines.</b> Each ping remembers the client tick (window) it was sent after. Entries of a ping still unacked
 * {@code deadline} windows later are released to the owner to force-apply ({@link #forceDue}); the ping stays in the
 * FIFO and its pong, when it comes, releases nothing.
 * <p>
 * <b>Threading.</b> {@link #send} runs on whatever thread sends (plugins send block changes async). The id, the log and
 * {@code connection.send} happen under this object's lock, so log order is wire order. Acks run on the main thread.
 */
public final class Transactions {

    /** Our ping ids: below GrimAC's short range, above the correction-teleport band. */
    public static final int PING_MAX = -65537;
    public static final int PING_MIN = -0x40000000;

    static final int MAX_PINGS = 1 << 14;
    static final int MAX_ENTRIES = 1 << 17;
    static final int RECENT_ACKS = 64;
    /** Sent per-tick bundles whose lists wait for their ping's ack, at most; older ones are left to the GC. */
    static final int SENT_BUNDLES = 32;
    /** Acked bundle lists kept for the next ticks, at most. */
    static final int SPARE_BUNDLES = 4;
    /** A list that held more packets than this is not kept (a burst shouldn't pin a 4096-slot array). */
    static final int SPARE_BUNDLE_MAX = 1024;

    public static boolean ours(final int id) {
        return id >= PING_MIN && id <= PING_MAX;
    }

    /** Context the sender passes in: who the packet is for and where the client is in its stream. */
    public interface Context {
        /** The receiving player's entity id. */
        int selfId();

        java.util.UUID selfUuid();

        /** The window the client is in (the last {@code TickEnd} seen, plus one): stamped on pings for deadlines. */
        int windowTick();

        /** {@code MinecraftServer.currentTick}. */
        int serverTick();

        /** Whether this send happens on the main thread while the server tick holds this connection's flushes. */
        boolean coalesce();

        /**
         * The server time the packet's content stands for (a replicated path step's own stamp), or 0 for "now". Lag
         * compensation measures rewind from it.
         */
        default long stampMillis() {
            return 0L;
        }

        /** The client's copy of an inventory packet's stacks, taken when it is logged (§15.6), or null. */
        default @Nullable Object snapshot(final Packet<?> packet) {
            return null;
        }

        @Nullable TraceRecorder trace();
    }

    private int nextId = PING_MAX - ThreadLocalRandom.current().nextInt(1 << 24);
    private long nextSeq = 1;

    // Outstanding pings, a FIFO ring in send order
    private int[] pingIds = new int[64];
    private long[] pingSeqs = new long[64];
    private long[] pingSentNanos = new long[64];
    private int[] pingServerTicks = new int[64];
    private int[] pingWindows = new int[64];
    private int pingHead;
    private int pingCount;

    // Logged entries, a ring in send order; an entry is released when the ping with its seq is acked or forced
    private Packet<?>[] entryPackets = new Packet<?>[256];
    private byte[] entryKinds = new byte[256];
    private long[] entrySeqs = new long[256];
    private boolean[] entryUpper = new boolean[256];
    private long[] entryMillis = new long[256];
    private int entryHead;
    private int entryCount;
    private long releasedSeq;

    // The open per-tick bundle, and the seq of its ping
    private @Nullable ArrayList<Packet<? super ClientGamePacketListener>> open;
    private long openSeq;
    private boolean pingedThisTick;
    // Bundle lists are reused (review PT-16: one list per viewer per tick otherwise). A sent one is reusable once a ping
    // sent with or after it is acked: the client has read the bundle, so Netty's unbundler (PacketBundleUnpacker, on the
    // event loop) iterated the list before the bundle's first byte was written. Pings go out in seq order with no ping
    // sent while a bundle is open, so "seq <= the acked seq" is exactly "written before the acked ping". A forced ping
    // proves nothing and reuses nothing. shortcut: a client pong-guessing a ping it was not sent yet can get a list
    // reused under its own encoder; only its own connection reads that list
    @SuppressWarnings("unchecked")
    private final ArrayList<Packet<? super ClientGamePacketListener>>[] sentBundles = new ArrayList[SENT_BUNDLES];
    private final long[] sentBundleSeqs = new long[SENT_BUNDLES];
    private int sentBundleHead;
    private int sentBundleCount;
    @SuppressWarnings("unchecked")
    private final ArrayList<Packet<? super ClientGamePacketListener>>[] spareBundles = new ArrayList[SPARE_BUNDLES];
    private int spareBundleCount;

    // Other players this client has been sent, for REMOTE_PLAYER classification (guarded by the lock)
    private final IntOpenHashSet trackedPlayers = new IntOpenHashSet();
    private final IntOpenHashSet trackedRockets = new IntOpenHashSet();
    // §15.6 inventory snapshots by entry packet (guarded by the lock), and the bypass audit's expectations
    private final java.util.IdentityHashMap<Packet<?>, Object> snapshots = new java.util.IdentityHashMap<>();
    private final java.util.concurrent.ConcurrentLinkedQueue<Packet<?>> auditExpected = new java.util.concurrent.ConcurrentLinkedQueue<>();
    private volatile boolean auditing;

    private volatile boolean lostSync;

    // Main-thread state
    private final int[] recentAcks = new int[RECENT_ACKS];
    private int recentAckIndex;
    private final RttBaseline rtt = new RttBaseline(100_000_000L);
    private long lastAckedSentNanos;
    // The ack clock as the timer may use it (review timer-backlog-permanent): a drain's pongs count only once they have
    // settled, so the catch-up ticks that follow them in the same frame keep the credit the stall left
    private long settledAckSentNanos;
    private long pendingAckSentNanos;
    private long pendingAckArrivalNanos;
    // Pings dropped by a reset or overflow whose pongs may still come back: tolerated as duplicates, not unknown
    private int droppedNewestId;
    private int droppedCount;
    private long lastRttNanos;
    private int lastRttTicks;
    private long acked;
    private long duplicates;
    private long violations;
    private long skipped;
    private long foreign;
    private long overflows;
    private long forcedPings;
    private long sent;

    /** Released entries handed to the main thread, in send order. Owned by the main thread. */
    public static final class Acked {
        Packet<?>[] packets = new Packet<?>[64];
        byte[] kinds = new byte[64];
        boolean[] upper = new boolean[64];
        long[] millis = new long[64];
        int size;

        public int size() {
            return this.size;
        }

        public Packet<?> packet(final int i) {
            return this.packets[i];
        }

        public int kind(final int i) {
            return this.kinds[i];
        }

        /** Sent unbundled before its ping: applied at or before the ack tick, not exactly at it. */
        public boolean upperBound(final int i) {
            return this.upper[i];
        }

        /** Server time ({@code System.currentTimeMillis()}) the entry was sent at. */
        public long sentMillis(final int i) {
            return this.millis[i];
        }

        public void clear() {
            Arrays.fill(this.packets, 0, this.size, null);
            this.size = 0;
        }

        public void add(final Packet<?> packet, final int kind, final boolean upper, final long sentMillis) {
            if (this.size == this.packets.length) {
                final int grown = this.size * 2;
                this.packets = Arrays.copyOf(this.packets, grown);
                this.kinds = Arrays.copyOf(this.kinds, grown);
                this.upper = Arrays.copyOf(this.upper, grown);
                this.millis = Arrays.copyOf(this.millis, grown);
            }
            this.packets[this.size] = packet;
            this.kinds[this.size] = (byte) kind;
            this.upper[this.size] = upper;
            this.millis[this.size] = sentMillis;
            this.size++;
        }
    }

    // Sending ---------------------------------------------------------------------------------------------------------

    /** Sends {@code packet} on {@code connection}, logged and pinged when it changes the client's physics. */
    @SuppressWarnings("unchecked")
    public void send(final Connection connection, final Packet<?> packet, final @Nullable ChannelFutureListener listener, final boolean flush, final Context context) {
        synchronized (this) {
            final boolean coalesce = context.coalesce();
            if (packet instanceof final ClientboundBundlePacket bundle) {
                this.sendBundle(connection, bundle, listener, flush, context, coalesce);
                return;
            }
            final int kind = OutboundClassifier.classify(packet, context.selfId(), context.selfUuid(), this.trackedPlayers, this.trackedRockets);
            this.track(packet);
            final boolean bundleable = bundleable(packet);
            if (this.open != null) {
                if (bundleable && listener == null) {
                    this.append(connection, packet, kind, context);
                    return;
                }
                this.closeOpen(connection, context, false);
            }
            if (kind == OutboundClassifier.NONE) {
                connection.send(packet, listener, flush);
                return;
            }
            if (!bundleable) {
                // Logged before it is written, so the bypass audit expects it; the ping goes right after (upper bound)
                final ClientboundPingPacket ping = this.newPing(context);
                this.log(packet, kind, true, context);
                this.traceState(context, packet);
                connection.send(packet, listener, false);
                this.tracePing(context, ping.getId());
                connection.send(ping, null, flush);
                return;
            }
            if (coalesce && listener == null) {
                this.openBundle(context);
                this.append(connection, packet, kind, context);
                return;
            }
            final ClientboundPingPacket ping = this.newPing(context);
            this.tracePing(context, ping.getId());
            this.log(packet, kind, false, context);
            this.traceState(context, packet);
            connection.send(new ClientboundBundlePacket(List.of(ping, (Packet<? super ClientGamePacketListener>) packet)), listener, flush);
        }
    }

    // A bundle someone else built (tracker pairing): merged into the open bundle, or given a ping of its own
    private void sendBundle(final Connection connection, final ClientboundBundlePacket bundle, final @Nullable ChannelFutureListener listener,
                            final boolean flush, final Context context, final boolean coalesce) {
        int count = 0;
        for (final Packet<? super ClientGamePacketListener> ignored : bundle.subPackets()) {
            count++;
        }
        final int[] kinds = new int[count];
        boolean matters = false;
        int i = 0;
        for (final Packet<? super ClientGamePacketListener> sub : bundle.subPackets()) {
            final int kind = OutboundClassifier.mayMatter(sub) ? OutboundClassifier.classify(sub, context.selfId(), context.selfUuid(), this.trackedPlayers, this.trackedRockets) : OutboundClassifier.NONE;
            kinds[i++] = kind;
            matters |= kind != OutboundClassifier.NONE;
            this.track(sub);
        }
        if (this.open != null && listener == null && this.open.size() + count < net.minecraft.network.protocol.BundlerInfo.BUNDLE_SIZE_LIMIT) {
            i = 0;
            for (final Packet<? super ClientGamePacketListener> sub : bundle.subPackets()) {
                this.append(connection, sub, kinds[i++], context);
            }
            return;
        }
        if (this.open != null) {
            this.closeOpen(connection, context, false);
        }
        if (!matters) {
            connection.send(bundle, listener, flush);
            return;
        }
        if (coalesce && listener == null && count + 1 < net.minecraft.network.protocol.BundlerInfo.BUNDLE_SIZE_LIMIT) {
            this.openBundle(context);
            i = 0;
            for (final Packet<? super ClientGamePacketListener> sub : bundle.subPackets()) {
                this.append(connection, sub, kinds[i++], context);
            }
            return;
        }
        final boolean fits = count + 1 < net.minecraft.network.protocol.BundlerInfo.BUNDLE_SIZE_LIMIT;
        final ClientboundPingPacket ping = this.newPing(context);
        if (fits) {
            this.tracePing(context, ping.getId());
        }
        final List<Packet<? super ClientGamePacketListener>> packets = fits ? new ArrayList<>(count + 1) : List.of();
        if (fits) {
            packets.add(ping);
        }
        i = 0;
        for (final Packet<? super ClientGamePacketListener> sub : bundle.subPackets()) {
            if (fits) {
                packets.add(sub);
            }
            final int kind = kinds[i++];
            if (kind != OutboundClassifier.NONE) {
                this.log(sub, kind, !fits, context);
                this.traceState(context, sub);
            }
        }
        if (fits) {
            connection.send(new ClientboundBundlePacket(packets), listener, flush);
        } else {
            // A full bundle can't take a ping: it goes right after (upper bound). Logged first, so the audit expects it
            connection.send(bundle, listener, false);
            this.tracePing(context, ping.getId());
            connection.send(ping, null, flush);
        }
    }

    private void openBundle(final Context context) {
        final ClientboundPingPacket ping = this.newPing(context);
        this.tracePing(context, ping.getId());
        final ArrayList<Packet<? super ClientGamePacketListener>> bundle = this.spareBundleCount > 0
            ? this.takeSpare()
            : new ArrayList<>(64);
        bundle.add(ping);
        this.open = bundle;
        this.openSeq = this.nextSeq - 1; // newPing's
    }

    private ArrayList<Packet<? super ClientGamePacketListener>> takeSpare() {
        final ArrayList<Packet<? super ClientGamePacketListener>> spare = this.spareBundles[--this.spareBundleCount];
        this.spareBundles[this.spareBundleCount] = null;
        return spare;
    }

    // A list nothing reads any more: cleared and kept for a later bundle, or dropped
    private void spare(final ArrayList<Packet<? super ClientGamePacketListener>> bundle) {
        final boolean keep = bundle.size() <= SPARE_BUNDLE_MAX && this.spareBundleCount < SPARE_BUNDLES;
        bundle.clear();
        if (keep) {
            this.spareBundles[this.spareBundleCount++] = bundle;
        }
    }

    // The bundle just went to the connection: its list waits for an ack of its ping (or a later one)
    private void keepSent(final ArrayList<Packet<? super ClientGamePacketListener>> bundle, final long seq) {
        if (this.sentBundleCount == SENT_BUNDLES) {
            this.sentBundles[this.sentBundleHead] = null; // the oldest, unacked this long: the GC's
            this.sentBundleHead = (this.sentBundleHead + 1) % SENT_BUNDLES;
            this.sentBundleCount--;
        }
        final int slot = (this.sentBundleHead + this.sentBundleCount) % SENT_BUNDLES;
        this.sentBundles[slot] = bundle;
        this.sentBundleSeqs[slot] = seq;
        this.sentBundleCount++;
    }

    // An ack of the ping with this seq: every bundle sent with or before it was read by the client
    private void reuseSent(final long ackedSeq) {
        while (this.sentBundleCount > 0 && this.sentBundleSeqs[this.sentBundleHead] <= ackedSeq) {
            final ArrayList<Packet<? super ClientGamePacketListener>> bundle = this.sentBundles[this.sentBundleHead];
            this.sentBundles[this.sentBundleHead] = null;
            this.sentBundleHead = (this.sentBundleHead + 1) % SENT_BUNDLES;
            this.sentBundleCount--;
            this.spare(bundle);
        }
    }

    @SuppressWarnings("unchecked")
    private void append(final Connection connection, final Packet<?> packet, final int kind, final Context context) {
        final List<Packet<? super ClientGamePacketListener>> bundle = this.open;
        if (bundle == null) {
            return;
        }
        bundle.add((Packet<? super ClientGamePacketListener>) packet);
        if (kind != OutboundClassifier.NONE) {
            this.log(packet, kind, false, context);
            this.traceState(context, packet);
        }
        if (bundle.size() >= net.minecraft.network.protocol.BundlerInfo.BUNDLE_SIZE_LIMIT - 1) {
            this.closeOpen(connection, context, false); // the next classified packet opens a new one
        }
    }

    /**
     * Flushes are about to resume (end of the server tick for this connection): sends the open bundle, or a heartbeat
     * ping if this tick sent none. Main thread.
     */
    public void endOfTick(final Connection connection, final Context context, final boolean heartbeat) {
        synchronized (this) {
            if (this.open != null) {
                this.closeOpen(connection, context, false);
            } else if (heartbeat && !this.pingedThisTick) {
                final ClientboundPingPacket ping = this.newPing(context);
                this.tracePing(context, ping.getId());
                connection.send(ping, null, false);
            }
            this.pingedThisTick = false;
        }
    }

    /**
     * Sends and flushes the bundle a coalescing scope outside the server tick gathered (review PT-02: windows replayed
     * between ticks share one ping), if one is open.
     */
    public void flushOpen(final Connection connection) {
        synchronized (this) {
            this.closeOpen(connection, null, true);
        }
    }

    /** Sends the open bundle now (a packet that can't join it is next, or the open bundle is full). */
    private void closeOpen(final Connection connection, final @Nullable Context context, final boolean flush) {
        final ArrayList<Packet<? super ClientGamePacketListener>> bundle = this.open;
        this.open = null;
        if (bundle == null) {
            return;
        }
        connection.send(new ClientboundBundlePacket(bundle), null, flush);
        this.keepSent(bundle, this.openSeq);
    }

    private static boolean bundleable(final Packet<?> packet) {
        if (packet.isTerminal() || !packet.isReady() || packet instanceof ClientboundLoginPacket) {
            return false;
        }
        final List<Packet<?>> extras = packet.getExtraPackets();
        return extras == null || extras.isEmpty();
    }

    // Keeps the set of other players this client has been sent, after the packet itself was classified; under the lock
    private void track(final Packet<?> packet) {
        if (packet instanceof final ClientboundAddEntityPacket add) {
            if (add.getType() == net.minecraft.world.entity.EntityTypes.PLAYER) {
                this.trackedPlayers.add(add.getId());
            } else if (add.getType() == net.minecraft.world.entity.EntityTypes.FIREWORK_ROCKET) {
                this.trackedRockets.add(add.getId());
            }
        } else if (packet instanceof final ClientboundRemoveEntitiesPacket remove) {
            if (!this.trackedPlayers.isEmpty() || !this.trackedRockets.isEmpty()) {
                for (int i = 0, n = remove.entityIds().size(); i < n; i++) {
                    this.trackedPlayers.remove(remove.entityIds().getInt(i));
                    this.trackedRockets.remove(remove.entityIds().getInt(i));
                }
            }
        } else if (packet instanceof ClientboundRespawnPacket || packet instanceof ClientboundLoginPacket) {
            this.trackedPlayers.clear(); // the client drops every entity with its level
            this.trackedRockets.clear();
        }
    }

    private ClientboundPingPacket newPing(final Context context) {
        final int id = this.nextId;
        this.nextId = id == PING_MIN ? PING_MAX : id - 1;
        if (this.pingCount == this.pingIds.length) {
            if (this.pingCount >= MAX_PINGS) {
                this.overflow();
            } else {
                this.growPings();
            }
        }
        final int slot = (this.pingHead + this.pingCount) & (this.pingIds.length - 1);
        this.pingIds[slot] = id;
        this.pingSeqs[slot] = this.nextSeq++;
        this.pingSentNanos[slot] = System.nanoTime();
        this.pingServerTicks[slot] = context.serverTick();
        this.pingWindows[slot] = context.windowTick();
        this.pingCount++;
        this.pingedThisTick = true;
        this.sent++;
        return new ClientboundPingPacket(id);
    }

    // The entry belongs to the newest ping: the one at the front of its bundle, or the one sent right after it
    private void log(final Packet<?> packet, final int kind, final boolean upper, final Context context) {
        if (this.entryCount == this.entryPackets.length) {
            if (this.entryCount >= MAX_ENTRIES) {
                this.overflow();
            } else {
                this.growEntries();
            }
        }
        final int slot = (this.entryHead + this.entryCount) & (this.entryPackets.length - 1);
        this.entryPackets[slot] = packet;
        this.entryKinds[slot] = (byte) kind;
        this.entrySeqs[slot] = this.nextSeq - 1;
        this.entryUpper[slot] = upper;
        final long stamp = context.stampMillis();
        this.entryMillis[slot] = stamp != 0L ? stamp : System.currentTimeMillis();
        this.entryCount++;
        if (kind == OutboundClassifier.INVENTORY) {
            // §15.6: the client's copy of the stacks as they are encoded now, not as they would convert at the ack
            final Object snapshot = context.snapshot(packet);
            if (snapshot != null) {
                this.snapshots.put(packet, snapshot);
            }
        }
        if (this.auditing && audited(kind)) {
            this.auditExpected.add(packet);
        }
    }

    private static boolean audited(final int kind) {
        return kind == OutboundClassifier.SELF_MOTION || kind == OutboundClassifier.SELF_POSITION || kind == OutboundClassifier.SELF_ROTATION
            || kind == OutboundClassifier.EXPLODE || kind == OutboundClassifier.BLOCK_UPDATE || kind == OutboundClassifier.SECTION_UPDATE;
    }

    // Bypass audit (§15.1) --------------------------------------------------------------------------------------------

    /** Packets logged here that the bypass auditor should see written, in send order. */
    public java.util.concurrent.ConcurrentLinkedQueue<Packet<?>> auditQueue() {
        return this.auditExpected;
    }

    /** Whether a {@link BypassAuditor} is installed on the channel (otherwise nothing is queued for it). */
    public void setAuditing(final boolean auditing) {
        this.auditing = auditing;
        if (!auditing) {
            this.auditExpected.clear();
        }
    }

    /**
     * A physics packet that reached the client without passing here (the audit saw it written): logged now with
     * upper-bound timing, at the next ping, which the client answers after applying it.
     */
    public void logBypass(final Connection connection, final Packet<?> packet, final int kind, final Context context) {
        synchronized (this) {
            this.track(packet);
            if (this.open != null) {
                // The open bundle's ping goes out after the bypassed packet was written: its ack bounds it
                this.logUnaudited(packet, kind, context);
                return;
            }
            final ClientboundPingPacket ping = this.newPing(context);
            this.logUnaudited(packet, kind, context);
            this.tracePing(context, ping.getId());
            connection.send(ping, null, true);
        }
    }

    private void logUnaudited(final Packet<?> packet, final int kind, final Context context) {
        final boolean wasAuditing = this.auditing;
        this.auditing = false;
        try {
            this.log(packet, kind, true, context);
        } finally {
            this.auditing = wasAuditing;
        }
    }

    /** The client's stacks of an inventory entry, taken when it was logged (§15.6), or null. Main thread. */
    public @Nullable Object takeSnapshot(final Packet<?> packet) {
        synchronized (this) {
            return this.snapshots.isEmpty() ? null : this.snapshots.remove(packet);
        }
    }

    private void tracePing(final Context context, final int id) {
        final TraceRecorder trace = context.trace();
        if (trace != null) {
            trace.ping(id);
        }
    }

    private void traceState(final Context context, final Packet<?> packet) {
        final TraceRecorder trace = context.trace();
        if (trace != null) {
            trace.state(packet);
        }
    }

    private void overflow() {
        this.dropOutstanding();
        this.snapshots.clear();
        this.auditExpected.clear();
        Arrays.fill(this.entryPackets, null);
        this.entryCount = 0;
        this.entryHead = 0;
        this.pingCount = 0;
        this.pingHead = 0;
        this.overflows++;
        this.lostSync = true;
    }

    // Remembers the outstanding ids being dropped (consecutive, decreasing, wrapping at PING_MIN)
    private void dropOutstanding() {
        if (this.pingCount == 0) {
            return;
        }
        final int newest = this.pingIds[(this.pingHead + this.pingCount - 1) & (this.pingIds.length - 1)];
        this.droppedCount = this.droppedCount == 0 ? this.pingCount : this.droppedCount + this.pingCount;
        this.droppedNewestId = newest;
    }

    // Whether id is one of the droppedCount ids at or before droppedNewestId (older ids are larger)
    private boolean dropped(final int id) {
        if (this.droppedCount == 0 || !ours(id)) {
            return false;
        }
        long distance = (long) id - this.droppedNewestId;
        if (distance < 0) {
            distance += (long) PING_MAX - PING_MIN + 1; // the range wrapped from PING_MIN back to PING_MAX
        }
        return distance < this.droppedCount;
    }

    private void growPings() {
        final int grown = this.pingIds.length * 2;
        this.pingIds = unwrap(this.pingIds, this.pingHead, this.pingCount, grown);
        this.pingSeqs = unwrap(this.pingSeqs, this.pingHead, this.pingCount, grown);
        this.pingSentNanos = unwrap(this.pingSentNanos, this.pingHead, this.pingCount, grown);
        this.pingServerTicks = unwrap(this.pingServerTicks, this.pingHead, this.pingCount, grown);
        this.pingWindows = unwrap(this.pingWindows, this.pingHead, this.pingCount, grown);
        this.pingHead = 0;
    }

    private void growEntries() {
        final int grown = this.entryPackets.length * 2;
        final Packet<?>[] packets = new Packet<?>[grown];
        final byte[] kinds = new byte[grown];
        final long[] seqs = new long[grown];
        final boolean[] upper = new boolean[grown];
        final long[] millis = new long[grown];
        final int mask = this.entryPackets.length - 1;
        for (int i = 0; i < this.entryCount; i++) {
            final int from = (this.entryHead + i) & mask;
            packets[i] = this.entryPackets[from];
            kinds[i] = this.entryKinds[from];
            seqs[i] = this.entrySeqs[from];
            upper[i] = this.entryUpper[from];
            millis[i] = this.entryMillis[from];
        }
        this.entryPackets = packets;
        this.entryKinds = kinds;
        this.entrySeqs = seqs;
        this.entryUpper = upper;
        this.entryMillis = millis;
        this.entryHead = 0;
    }

    private static int[] unwrap(final int[] ring, final int head, final int count, final int length) {
        final int[] out = new int[length];
        for (int i = 0; i < count; i++) {
            out[i] = ring[(head + i) & (ring.length - 1)];
        }
        return out;
    }

    private static long[] unwrap(final long[] ring, final int head, final int count, final int length) {
        final long[] out = new long[length];
        for (int i = 0; i < count; i++) {
            out[i] = ring[(head + i) & (ring.length - 1)];
        }
        return out;
    }

    // Acks (main thread) ----------------------------------------------------------------------------------------------

    /** The pong matched the oldest outstanding ping. */
    public static final int ACKED = 1;
    /** Not our id: passes through untouched. */
    public static final int FOREIGN = 0;
    /** A recent ack seen again: ignored. */
    public static final int DUPLICATE = 2;
    /** A later outstanding id: the client skipped pings. Released up to it anyway; a protocol violation. */
    public static final int SKIPPED = 3;
    /** Our band, but never sent or long acked: a protocol violation. */
    public static final int UNKNOWN = 4;

    /**
     * Acks {@code id}: every entry logged up to that ping, not already force-applied, moves into {@code out} in send
     * order.
     */
    public int ack(final int id, final int serverTick, final Acked out) {
        if (!ours(id)) {
            this.foreign++;
            return FOREIGN;
        }
        synchronized (this) {
            final int mask = this.pingIds.length - 1;
            int found = -1;
            for (int i = 0; i < this.pingCount; i++) {
                if (this.pingIds[(this.pingHead + i) & mask] == id) {
                    found = i;
                    break;
                }
            }
            if (found < 0) {
                for (final int recent : this.recentAcks) {
                    if (recent == id) {
                        this.duplicates++;
                        return DUPLICATE;
                    }
                }
                if (this.dropped(id)) {
                    this.duplicates++;
                    return DUPLICATE; // a ping a reset or overflow dropped: answered, but nothing of it is left to apply
                }
                this.violations++;
                return UNKNOWN;
            }
            this.droppedCount = 0; // a pong for a newer ping: every dropped one was answered or skipped by now
            final int slot = (this.pingHead + found) & mask;
            final long seq = this.pingSeqs[slot];
            final long sentNanos = this.pingSentNanos[slot];
            final int sentTick = this.pingServerTicks[slot];
            for (int i = 0; i <= found; i++) {
                this.recentAcks[this.recentAckIndex] = this.pingIds[(this.pingHead + i) & mask];
                this.recentAckIndex = (this.recentAckIndex + 1) % RECENT_ACKS;
            }
            this.pingHead = (this.pingHead + found + 1) & mask;
            this.pingCount -= found + 1;
            this.release(seq, out);
            this.reuseSent(seq);
            this.acked++;
            final long now = System.nanoTime();
            this.lastAckedSentNanos = Math.max(this.lastAckedSentNanos, sentNanos);
            if (this.pendingAckArrivalNanos == 0L) {
                this.pendingAckArrivalNanos = now;
            }
            this.pendingAckSentNanos = Math.max(this.pendingAckSentNanos, sentNanos);
            this.lastRttNanos = now - sentNanos;
            this.lastRttTicks = serverTick - sentTick;
            this.rtt.sample(this.lastRttNanos); // rttBase: never the latest pong (§15.1)
            if (found > 0) {
                this.skipped += found;
                this.violations++;
                return SKIPPED;
            }
            return ACKED;
        }
    }

    /**
     * Releases the entries of pings sent after window {@code window - deadlineWindows} or earlier that are still
     * unacked: the owner force-applies them at this tick (§15.1). Returns how many pings were forced.
     */
    public int forceDue(final int window, final int deadlineWindows, final Acked out) {
        synchronized (this) {
            final int mask = this.pingIds.length - 1;
            long seq = -1;
            int forced = 0;
            for (int i = 0; i < this.pingCount; i++) {
                final int slot = (this.pingHead + i) & mask;
                if (window - this.pingWindows[slot] < deadlineWindows) {
                    break;
                }
                if (this.pingSeqs[slot] > this.releasedSeq) {
                    seq = this.pingSeqs[slot];
                    forced++;
                }
            }
            if (seq > 0) {
                this.release(seq, out);
                this.forcedPings += forced;
            }
            return forced;
        }
    }

    private void release(final long seq, final Acked out) {
        final int entryMask = this.entryPackets.length - 1;
        while (this.entryCount > 0) {
            final int head = this.entryHead;
            if (this.entrySeqs[head] > seq) {
                break;
            }
            out.add(this.entryPackets[head], this.entryKinds[head], this.entryUpper[head], this.entryMillis[head]);
            this.entryPackets[head] = null;
            this.entryHead = (head + 1) & entryMask;
            this.entryCount--;
        }
        this.releasedSeq = Math.max(this.releasedSeq, seq);
    }

    /** The p90 of the last 256 ack round trips (at least {@link #rttBaseNanos}): with the base, the player's own jitter. */
    public long rttHighNanos() {
        return this.rtt.high();
    }

    /** Age of the oldest unanswered ping, or 0. */
    public synchronized long oldestOutstandingNanos(final long now) {
        return this.pingCount == 0 ? 0L : now - this.pingSentNanos[this.pingHead];
    }

    /** True once after the log overflowed and was dropped: the owner resyncs its client view from the server. */
    public boolean takeLostSync() {
        if (this.lostSync) {
            this.lostSync = false;
            return true;
        }
        return false;
    }

    /** Drops every outstanding ping and entry (mode turned off, resync). */
    public void reset() {
        synchronized (this) {
            this.dropOutstanding();
            this.snapshots.clear();
            this.auditExpected.clear();
            Arrays.fill(this.entryPackets, null);
            this.entryCount = 0;
            this.entryHead = 0;
            this.pingCount = 0;
            this.pingHead = 0;
            if (this.open != null) {
                this.spare(this.open); // never sent
                this.open = null;
            }
            // Sent lists go with their pings (the GC's): rare, and no ack of a dropped ping may reuse them
            Arrays.fill(this.sentBundles, null);
            this.sentBundleHead = 0;
            this.sentBundleCount = 0;
            this.trackedPlayers.clear();
            this.trackedRockets.clear();
        }
    }

    // Statistics ------------------------------------------------------------------------------------------------------

    /** {@code System.nanoTime()} at which the newest acked ping was sent: the client's clock is at least this. */
    public long lastAckedSentNanos() {
        return this.lastAckedSentNanos;
    }

    /**
     * The ack clock for the tick timer: like {@link #lastAckedSentNanos}, but pongs count only once {@code settleNanos}
     * has passed since the first of them arrived. A client back from a stall answers every ping it queued and then runs
     * its catch-up ticks in the same frame; those ticks are owed for the stall, which the pongs just before them must
     * not erase. A client that keeps answering while it sends no ticks still has its idle time forfeited, a settle later.
     */
    public long settledAckSentNanos(final long nowNanos, final long settleNanos) {
        if (this.pendingAckArrivalNanos != 0L && nowNanos - this.pendingAckArrivalNanos >= settleNanos) {
            this.settledAckSentNanos = Math.max(this.settledAckSentNanos, this.pendingAckSentNanos);
            this.pendingAckArrivalNanos = 0L;
            this.pendingAckSentNanos = 0L;
        }
        return this.settledAckSentNanos;
    }

    public long lastRttNanos() {
        return this.lastRttNanos;
    }

    /**
     * The connection's base round trip (§15.1, {@link RttBaseline}): the 10th percentile of the last 256 ack round trips,
     * or of the last 32 when that is higher, so a ping that went up is followed within about 1.5 s.
     */
    public long rttBaseNanos() {
        return this.rtt.base();
    }

    public int lastRttTicks() {
        return this.lastRttTicks;
    }

    public long ackedCount() {
        return this.acked;
    }

    public long duplicateCount() {
        return this.duplicates;
    }

    public long violationCount() {
        return this.violations;
    }

    public long skippedCount() {
        return this.skipped;
    }

    public long foreignCount() {
        return this.foreign;
    }

    public long overflowCount() {
        return this.overflows;
    }

    public long forcedCount() {
        return this.forcedPings;
    }

    public long sentCount() {
        return this.sent;
    }

    public synchronized int outstandingPings() {
        return this.pingCount;
    }

    public synchronized int outstandingEntries() {
        return this.entryCount;
    }

    public synchronized boolean bundleOpen() {
        return this.open != null;
    }
}
