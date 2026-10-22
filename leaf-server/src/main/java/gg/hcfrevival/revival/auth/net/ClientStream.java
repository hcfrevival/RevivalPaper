package gg.hcfrevival.revival.auth.net;

import java.util.ArrayDeque;
import java.util.Arrays;
import net.minecraft.network.protocol.Packet;
import net.minecraft.network.protocol.game.ServerboundAcceptTeleportationPacket;
import net.minecraft.network.protocol.game.ServerboundAttackPacket;
import net.minecraft.network.protocol.game.ServerboundClientCommandPacket;
import net.minecraft.network.protocol.game.ServerboundClientTickEndPacket;
import net.minecraft.network.protocol.game.ServerboundContainerButtonClickPacket;
import net.minecraft.network.protocol.game.ServerboundContainerClickPacket;
import net.minecraft.network.protocol.game.ServerboundContainerClosePacket;
import net.minecraft.network.protocol.game.ServerboundMovePlayerPacket;
import net.minecraft.network.protocol.game.ServerboundPlaceRecipePacket;
import net.minecraft.network.protocol.game.ServerboundPlayerAbilitiesPacket;
import net.minecraft.network.protocol.game.ServerboundPlayerActionPacket;
import net.minecraft.network.protocol.game.ServerboundPlayerCommandPacket;
import net.minecraft.network.protocol.game.ServerboundPlayerInputPacket;
import net.minecraft.network.protocol.game.ServerboundSelectBundleItemPacket;
import net.minecraft.network.protocol.game.ServerboundSetCreativeModeSlotPacket;
import net.minecraft.network.protocol.game.ServerboundUseItemPacket;
import net.minecraft.world.entity.player.Input;
import org.jspecify.annotations.Nullable;

/**
 * The client's serverbound stream cut into client-tick windows (design §3.1, §15.1, §15.4; report 02 §5).
 * <p>
 * {@code W_T} is everything after {@code TickEnd(T-1)} up to {@code TickEnd(T)}, kept in arrival order as
 * {@link Window} items: gameplay packets (buffered, replayed through their original handlers when the window runs),
 * acks (the entries a pong released, applied in the drain before the tick), and teleport accepts. A vanilla window is
 * {@code [GUI | drain reply]* [keybind]* [aiStep]* [input] [sprint] [move] TickEnd}; {@link #violation} counts what a
 * vanilla client can't produce (the input grammar of §15.4).
 * <p>
 * Closed windows wait in a FIFO until the tick clock lets them run (a timer-limited client queues). This class also
 * keeps the client's sticky state as windows run: the last {@code PlayerInput}, the last rotation, and
 * {@code LocalPlayer.sendPosition}'s bookkeeping ({@code xLast} and the 20-tick position reminder).
 * Main thread only. Windows are pooled.
 */
public final class ClientStream {

    // Item kinds
    public static final byte PACKET = 1;
    /** A pong: entries {@code [a, b)} of the window's {@link Window#acked} list. */
    public static final byte ACK = 2;
    /** A teleport accept (vanilla server teleport or our correction). */
    public static final byte ACCEPT = 3;
    /** The {@code MovePlayer.Rot} a client sends back for a {@code PlayerRotation} packet (identified by adjacency). */
    public static final byte ROTATION_REPLY = 4;
    /** Entries force-applied at their deadline: {@code [a, b)} of {@link Window#acked}. */
    public static final byte FORCED = 5;
    /**
     * The {@code PlayerAction(ABORT_DESTROY_BLOCK)} a mining client sends right after a teleport accept
     * ({@code handleMovePlayer} calls {@code stopDestroyBlock}): a drain reply, not a keybind.
     */
    public static final byte DRAIN_ABORT = 6;

    // Packet categories, in the order a vanilla tick sends them
    public static final int GUI = 0;
    public static final int KEYBIND = 1;
    public static final int AISTEP = 2;
    public static final int INPUT = 3;
    public static final int SPRINT = 4;
    public static final int MOVE = 5;
    public static final int TICK_END = 6;
    public static final int DRAIN = 7;

    public static final int SPRINT_NONE = 0;
    public static final int SPRINT_START = 1;
    public static final int SPRINT_STOP = 2;

    /** More items than any vanilla client can put in one tick: a violation, and the window is cut. */
    public static final int MAX_ITEMS = 512;
    /**
     * Drain items (pongs, accepts) and the entries they released that one window may hold (review E7, PT-15): a client
     * answering pings but never ending its tick keeps one window open, holding every physics packet sent to it. A 1.5 s
     * stall in a crowded fight releases a few thousand; past these the window has overflowed.
     */
    public static final int MAX_DRAIN_ITEMS = 4096;
    public static final int MAX_ACKED = 65536;

    /** One client tick's worth of the stream. */
    public static final class Window {
        int tick;
        long openedNanos;
        long closedNanos;
        byte[] kinds = new byte[16];
        @Nullable Object[] refs = new Object[16];
        int[] argA = new int[16];
        int[] argB = new int[16];
        int items;
        public final Transactions.Acked acked = new Transactions.Acked();
        int lastCategory = GUI;
        boolean tickGroupStarted;
        int violations;
        int inputs;
        int sprints;
        int moves;
        int pongs;
        long timerAckNanos;
        long arrivalNanos;
        boolean paced;
        int pacedTick;

        /** The window's number: {@code T} of {@code TickEnd(T)}. */
        public int tick() {
            return this.tick;
        }

        public long closedNanos() {
            return this.closedNanos;
        }

        /** When its {@code TickEnd} came off the wire (the connection's read, or {@link #closedNanos} when not known). */
        public long arrivalNanos() {
            return this.arrivalNanos;
        }

        /**
         * Whether the client sent it at its own pace, not as part of a catch-up burst (torture F3): it came at least
         * {@code catch-up-gap-ms} after the window before it, or its drain answered a ping, or the burst already held a
         * frame's worth of catch-up ticks ({@code max-banked-ticks}, vanilla's 10).
         */
        public boolean paced() {
            return this.paced;
        }

        /** Paced windows so far, this one included if it is paced: the clock ack deadlines count in. */
        public int pacedTick() {
            return this.pacedTick;
        }

        public int items() {
            return this.items;
        }

        public byte kind(final int i) {
            return this.kinds[i];
        }

        public @Nullable Object ref(final int i) {
            return this.refs[i];
        }

        public int a(final int i) {
            return this.argA[i];
        }

        public int b(final int i) {
            return this.argB[i];
        }

        public int violations() {
            return this.violations;
        }

        /**
         * The ack clock the tick timer may hold this window to ({@link TickClock}): the newest ping the client had
         * answered before this window's frame, or long enough before its {@code TickEnd}; 0 if none.
         */
        public long timerAckNanos() {
            return this.timerAckNanos;
        }

        void add(final byte kind, final @Nullable Object ref, final int a, final int b) {
            if (this.items == 0) {
                this.openedNanos = System.nanoTime(); // drain replies open a window as much as packets do
            }
            if (this.items == this.kinds.length) {
                final int grown = this.items * 2;
                this.kinds = Arrays.copyOf(this.kinds, grown);
                this.refs = Arrays.copyOf(this.refs, grown);
                this.argA = Arrays.copyOf(this.argA, grown);
                this.argB = Arrays.copyOf(this.argB, grown);
            }
            this.kinds[this.items] = kind;
            this.refs[this.items] = ref;
            this.argA[this.items] = a;
            this.argB[this.items] = b;
            this.items++;
        }

        void clear() {
            Arrays.fill(this.refs, 0, this.items, null);
            this.items = 0;
            this.openedNanos = 0L;
            this.acked.clear();
            this.lastCategory = GUI;
            this.tickGroupStarted = false;
            this.violations = 0;
            this.inputs = 0;
            this.sprints = 0;
            this.moves = 0;
            this.pongs = 0;
            this.timerAckNanos = 0L;
            this.arrivalNanos = 0L;
            this.paced = false;
            this.pacedTick = 0;
        }
    }

    private Window current = new Window();
    private final ArrayDeque<Window> queue = new ArrayDeque<>();
    private final ArrayDeque<Window> pool = new ArrayDeque<>();
    private int closed;
    private long violations;

    // The tick timer's ack clock (review timer-backlog-permanent). A client back from a stall answers every ping it
    // queued, then runs its catch-up ticks in the same frame with no pongs between them: those ticks are owed for the
    // stall, so a drain's pongs only bind the windows of later frames. Pongs that arrived long before a window's TickEnd
    // (a client answering while it sends no ticks) bind it at once.
    static final int PONG_RING = 64;
    private final long[] pongArrivals = new long[PONG_RING];
    private final long[] pongAckClocks = new long[PONG_RING];
    private int pongRingNext;
    private long ackBeforeFrame;
    private long ackAfterLastDrain;
    private long lastTimerAck;
    private long ackSettleNanos = 100_000_000L;

    // Adjacency: the next main-thread packets are special. Each PlayerRotation a pong released is answered by a Rot, in
    // the same drain, after any accept (and its abort) of the same bundle
    private int rotationReplies;
    private boolean acceptJustRecorded;
    private int correctionAcceptNext;

    // Sticky client state, advanced as windows run
    private Input input = Input.EMPTY;
    private Input previousInput = Input.EMPTY;
    private float yRot;
    private float xRot;
    private boolean rotationKnown;
    private double lastSentX;
    private double lastSentY;
    private double lastSentZ;
    private boolean lastSentKnown;
    private int ticksSincePosition;

    /** The category of a serverbound packet in the vanilla tick order. */
    public static int category(final Packet<?> packet) {
        return switch (packet) {
            case final ServerboundPlayerInputPacket ignored -> INPUT;
            case final ServerboundMovePlayerPacket ignored -> MOVE;
            case final ServerboundClientTickEndPacket ignored -> TICK_END;
            case final ServerboundPlayerCommandPacket command -> switch (command.getAction()) {
                case START_SPRINTING, STOP_SPRINTING -> SPRINT;
                case START_FALL_FLYING, START_RIDING_JUMP, STOP_RIDING_JUMP -> AISTEP;
                case OPEN_INVENTORY -> KEYBIND;
                default -> GUI; // STOP_SLEEPING: the bed screen's button
            };
            case final ServerboundPlayerAbilitiesPacket ignored -> AISTEP;
            case final ServerboundAcceptTeleportationPacket ignored -> DRAIN;
            case final ServerboundContainerClickPacket ignored -> GUI;
            case final ServerboundContainerButtonClickPacket ignored -> GUI;
            case final ServerboundContainerClosePacket ignored -> GUI;
            case final ServerboundSetCreativeModeSlotPacket ignored -> GUI;
            case final ServerboundPlaceRecipePacket ignored -> GUI;
            case final ServerboundSelectBundleItemPacket ignored -> GUI;
            case final ServerboundClientCommandPacket ignored -> GUI;
            default -> KEYBIND; // carried item, loaded, player actions, use, use on, interact, attack, punch, pick
        };
    }

    // Recording (arrival order) -------------------------------------------------------------------------------------

    /**
     * A buffered gameplay packet. Returns false when it broke the window grammar (counted; still kept, in order, so the
     * server sees what the client sent).
     */
    public boolean record(final Packet<?> packet) {
        return this.recordChecked(packet) == RECORDED;
    }

    // recordChecked results
    public static final int RECORDED = 0;
    /** Out of the vanilla order: counted, kept. */
    public static final int OUT_OF_ORDER = 1;
    /** A second move claim in one window: a protocol violation (vanilla 26.3 kicks it too). */
    public static final int SECOND_CLAIM = 2;
    /** Past {@link #MAX_ITEMS} in one window (a client that never ends its tick): dropped, a protocol violation. */
    public static final int OVERFLOW = 3;
    /**
     * A second {@code PlayerInput} or sprint command in one window: counted (design §15.8, a frame-time key press on a
     * modded client), the last one before the move claim counts.
     */
    public static final int EXTRA_INPUT = 4;

    /** {@link #record}, saying how a packet broke the grammar ({@link #RECORDED} when it didn't). */
    public int recordChecked(final Packet<?> packet) {
        final Window window = this.current;
        if (window.items >= MAX_ITEMS) {
            window.violations++;
            this.violations++;
            return OVERFLOW;
        }
        if (this.rotationReplies > 0 && !window.tickGroupStarted && packet instanceof ServerboundMovePlayerPacket.Rot) {
            this.rotationReplies--;
            this.acceptJustRecorded = false;
            this.correctionAcceptNext = 0;
            window.add(ROTATION_REPLY, packet, 0, 0);
            return RECORDED;
        }
        if (this.acceptJustRecorded && !window.tickGroupStarted && packet instanceof final ServerboundPlayerActionPacket action
            && action.getAction() == ServerboundPlayerActionPacket.Action.ABORT_DESTROY_BLOCK) {
            this.acceptJustRecorded = false;
            this.correctionAcceptNext = 0;
            window.add(DRAIN_ABORT, packet, 0, 0);
            return RECORDED;
        }
        this.clearAdjacency();
        int category = category(packet);
        if (category == GUI && packet instanceof ServerboundContainerClosePacket && window.tickGroupStarted && window.lastCategory <= AISTEP) {
            // The portal close: LocalPlayer.aiStep closes a container screen while the player stands in a portal, after
            // the keybinds and before the input (report 02 §2.5, client-compat N8): vanilla, not a GUI packet out of place
            category = AISTEP;
        }
        boolean legal = category >= window.lastCategory || category == KEYBIND && window.lastCategory == KEYBIND;
        if (category != GUI) {
            window.tickGroupStarted = true;
        } else {
            legal = !window.tickGroupStarted;
        }
        boolean second = false;
        boolean extra = false;
        switch (category) {
            case INPUT -> extra = ++window.inputs > 1;
            case SPRINT -> extra = ++window.sprints > 1;
            case MOVE -> second = ++window.moves > 1;
            default -> {
            }
        }
        legal &= !second && !extra;
        if (packet instanceof final ServerboundUseItemPacket ignored && this.releasedEarlier(window)) {
            legal = false; // use after release in one window: a vanilla client discards every click of the release tick
        }
        window.lastCategory = Math.max(window.lastCategory, category);
        window.add(PACKET, packet, category, 0);
        if (!legal) {
            window.violations++;
            this.violations++;
            return second ? SECOND_CLAIM : extra ? EXTRA_INPUT : OUT_OF_ORDER;
        }
        return RECORDED;
    }

    /** Whether the window being filled holds more drain items or released entries than any client tick can (E7). */
    public boolean overflowing() {
        return this.current.items >= MAX_DRAIN_ITEMS || this.current.acked.size() >= MAX_ACKED;
    }

    /** How long the window still open has held packets without its {@code TickEnd}, or 0. */
    public long openWindowAgeNanos(final long nowNanos) {
        return this.current.items == 0 ? 0L : nowNanos - this.current.openedNanos;
    }

    private boolean releasedEarlier(final Window window) {
        for (int i = 0; i < window.items; i++) {
            if (window.kinds[i] == PACKET && window.refs[i] instanceof final ServerboundPlayerActionPacket action
                && action.getAction() == ServerboundPlayerActionPacket.Action.RELEASE_USE_ITEM) {
                return true;
            }
        }
        return false;
    }

    /**
     * A pong released entries {@code [from, to)} of {@link #currentAcked()}. Returns false when it came after the
     * window's tick group started (a violation: pongs belong to the drain).
     *
     * @param rotations how many {@code PlayerRotation} packets the released entries hold: the drain may answer each
     * @param correction the id of a correction among them, or 0: the next packet must be its accept
     */
    public boolean recordAck(final int from, final int to, final int rotations, final int correction) {
        final Window window = this.current;
        this.clearAdjacency();
        window.add(ACK, null, from, to);
        this.rotationReplies = rotations;
        this.correctionAcceptNext = correction;
        if (window.tickGroupStarted) {
            window.violations++;
            this.violations++;
            return false;
        }
        return true;
    }

    /**
     * A pong arrived at {@code arrivalNanos}; the client's ack clock is now {@code ackClockNanos} (the send time of the
     * newest ping it answered).
     */
    public void notePong(final long arrivalNanos, final long ackClockNanos) {
        final int slot = this.pongRingNext;
        this.pongArrivals[slot] = arrivalNanos;
        this.pongAckClocks[slot] = ackClockNanos;
        this.pongRingNext = (slot + 1) % PONG_RING;
        this.current.pongs++;
        this.ackAfterLastDrain = Math.max(this.ackAfterLastDrain, ackClockNanos);
    }

    /** How long before a window's {@code TickEnd} a pong must have arrived to bind that window's timer at once. */
    public void setAckSettle(final long settleNanos) {
        this.ackSettleNanos = settleNanos;
    }

    // The ack clock after the newest pong that arrived at or before atNanos, or 0 (ack clocks never go down, so it is
    // the newest such pong's; older pongs fell out of the ring only if 64 came since, all newer than any of them)
    private long ackClockAt(final long atNanos) {
        long best = 0L;
        long bestArrival = Long.MIN_VALUE;
        for (int i = 0; i < PONG_RING; i++) {
            final long arrival = this.pongArrivals[i];
            if (arrival != 0L && arrival <= atNanos && arrival > bestArrival) {
                bestArrival = arrival;
                best = this.pongAckClocks[i];
            }
        }
        return best;
    }

    /** Entries force-applied at their deadline, applied in this window's drain. */
    public void recordForced(final int from, final int to) {
        this.current.add(FORCED, null, from, to);
    }

    /**
     * A teleport accept. Returns whether it is in its legal place (the drain part; for a correction, right after the
     * pong that carried it).
     */
    public boolean recordAccept(final ServerboundAcceptTeleportationPacket packet) {
        final Window window = this.current;
        final int expected = this.correctionAcceptNext;
        this.correctionAcceptNext = 0; // the bundle's rotation replies may still follow
        this.acceptJustRecorded = true;
        window.add(ACCEPT, packet, 0, 0);
        boolean legal = !window.tickGroupStarted;
        if (expected != 0) {
            legal &= packet.id() == expected;
        }
        if (!legal) {
            window.violations++;
            this.violations++;
        }
        return legal;
    }

    /** The correction whose accept must be the next packet, or 0. */
    public int correctionAcceptExpected() {
        return this.correctionAcceptNext;
    }

    private void clearAdjacency() {
        this.rotationReplies = 0;
        this.acceptJustRecorded = false;
        this.correctionAcceptNext = 0;
    }

    /** The entries list pongs of the window being filled release into. */
    public Transactions.Acked currentAcked() {
        return this.current.acked;
    }

    /** {@code TickEnd}: closes the window being filled and queues it. Returns it. */
    public Window close(final Packet<?> tickEnd, final long nowNanos) {
        return this.close(tickEnd, nowNanos, nowNanos);
    }

    // Pacing (torture F3): a catch-up burst doesn't count towards ack deadlines
    private long catchUpGapNanos = 15_000_000L;
    private int maxCatchUp = 10;
    private int pacedCount;
    private int catchUpRun;
    private long lastArrivalNanos;

    /** Pacing: windows closer than {@code gapNanos} with no pong are catch-up ticks, at most {@code maxRun} in a row. */
    public void setPacing(final long gapNanos, final int maxRun) {
        this.catchUpGapNanos = gapNanos;
        this.maxCatchUp = maxRun;
    }

    /** Paced windows closed so far. */
    public int pacedTicks() {
        return this.pacedCount;
    }

    /** {@code TickEnd} that came off the wire at {@code arrivalNanos}: closes the window being filled and queues it. */
    public Window close(final Packet<?> tickEnd, final long nowNanos, final long arrivalNanos) {
        final Window window = this.current;
        this.clearAdjacency();
        window.add(PACKET, tickEnd, TICK_END, 0);
        window.tick = ++this.closed;
        window.closedNanos = nowNanos;
        window.arrivalNanos = arrivalNanos;
        final boolean burst = this.closed > 1 && arrivalNanos - this.lastArrivalNanos < this.catchUpGapNanos;
        window.paced = window.pongs > 0 || !burst || this.catchUpRun >= this.maxCatchUp;
        this.catchUpRun = window.paced ? 0 : this.catchUpRun + 1;
        if (window.paced) {
            this.pacedCount++;
        }
        window.pacedTick = this.pacedCount;
        this.lastArrivalNanos = arrivalNanos;
        if (window.pongs > 0) {
            // A new frame's drain: the previous drains bind now, and so do pongs long before this TickEnd
            window.timerAckNanos = Math.max(this.ackBeforeFrame, this.ackClockAt(nowNanos - this.ackSettleNanos));
            this.ackBeforeFrame = this.ackAfterLastDrain;
            this.lastTimerAck = window.timerAckNanos;
        } else {
            window.timerAckNanos = this.lastTimerAck; // a catch-up tick of the same frame, or a tick with no ping in its drain
        }
        this.queue.addLast(window);
        final Window next = this.pool.pollFirst();
        this.current = next != null ? next : new Window();
        return window;
    }

    /** The oldest closed window not run yet, or null. */
    public @Nullable Window peek() {
        return this.queue.peekFirst();
    }

    /** Removes the oldest window (it ran); call {@link #recycle} when done with it. */
    public Window poll() {
        return this.queue.removeFirst();
    }

    public int queued() {
        return this.queue.size();
    }

    public void recycle(final Window window) {
        window.clear();
        this.pool.addLast(window);
    }

    /** Drops every buffered window (authority turned off: the caller replays them first). */
    public void drainTo(final java.util.function.Consumer<Window> sink) {
        Window window;
        while ((window = this.queue.pollFirst()) != null) {
            sink.accept(window);
        }
        if (this.current.items > 0) {
            final Window partial = this.current;
            this.current = new Window();
            sink.accept(partial);
        }
    }

    /** Windows closed so far: the client's tick count since authority started. */
    public int closedTicks() {
        return this.closed;
    }

    /** The window number the client is in now (for ping deadlines): the next {@code TickEnd}. */
    public int currentTick() {
        return this.closed + 1;
    }

    public long violations() {
        return this.violations;
    }

    // Sticky state, advanced as windows run ---------------------------------------------------------------------------

    /** The window's {@code PlayerInput}, made sticky: the keys of this tick's movement. */
    public Input advanceInput(final @Nullable Input sent) {
        this.previousInput = this.input;
        if (sent != null) {
            this.input = sent;
        }
        return this.input;
    }

    public Input input() {
        return this.input;
    }

    /** Authority starts: the keys the client last sent before it, which it won't send again until they change. */
    public void seedInput(final Input keys) {
        this.input = keys;
        this.previousInput = keys;
    }

    /** The previous tick's keys (double-tap sprint and crouch read them, report 02 H14). */
    public Input previousInput() {
        return this.previousInput;
    }

    public void setRotation(final float yRot, final float xRot) {
        this.yRot = yRot;
        this.xRot = xRot;
        this.rotationKnown = true;
    }

    /** The rotation of the tick being run: its claim's, or the last one (rotation is sticky). */
    public float yRot() {
        return this.yRot;
    }

    public float xRot() {
        return this.xRot;
    }

    public boolean rotationKnown() {
        return this.rotationKnown;
    }

    /** {@code sendPosition} ran: a position was sent (or not) this tick. */
    public void advancePosition(final boolean sent, final double x, final double y, final double z) {
        this.ticksSincePosition++;
        if (sent) {
            this.lastSentX = x;
            this.lastSentY = y;
            this.lastSentZ = z;
            this.lastSentKnown = true;
            this.ticksSincePosition = 0;
        }
    }

    public boolean lastSentKnown() {
        return this.lastSentKnown;
    }

    public double lastSentX() {
        return this.lastSentX;
    }

    public double lastSentY() {
        return this.lastSentY;
    }

    public double lastSentZ() {
        return this.lastSentZ;
    }

    /** Whether the client had to send a position this tick whatever it moved (the 20-tick reminder). */
    public boolean reminderDue() {
        return this.ticksSincePosition + 1 >= 20;
    }

    /**
     * A new local player (login, respawn): {@code lastSentInput} and {@code wasSprinting} are kept only with
     * {@code KEEP_ENTITY_DATA}; {@code xLast/yLast/zLast} start at 0 and {@code positionReminder} at 0 (report 02 §3.14,
     * H13), so the first {@code sendPosition} almost always sends a position.
     */
    public void resetClient(final boolean keepEntityData) {
        if (!keepEntityData) {
            this.input = Input.EMPTY;
            this.previousInput = Input.EMPTY;
        }
        this.lastSentX = 0.0;
        this.lastSentY = 0.0;
        this.lastSentZ = 0.0;
        this.lastSentKnown = true;
        this.ticksSincePosition = 0;
    }

    /** Authority starts mid-session: what the client last sent is unknown until its next position. */
    public void forgetClient() {
        this.lastSentKnown = false;
        this.ticksSincePosition = 0;
        this.rotationKnown = false;
    }
}
