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

    /** One client tick's worth of the stream. */
    public static final class Window {
        int tick;
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

        /** The window's number: {@code T} of {@code TickEnd(T)}. */
        public int tick() {
            return this.tick;
        }

        public long closedNanos() {
            return this.closedNanos;
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

        void add(final byte kind, final @Nullable Object ref, final int a, final int b) {
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
            this.acked.clear();
            this.lastCategory = GUI;
            this.tickGroupStarted = false;
            this.violations = 0;
            this.inputs = 0;
            this.sprints = 0;
            this.moves = 0;
        }
    }

    private Window current = new Window();
    private final ArrayDeque<Window> queue = new ArrayDeque<>();
    private final ArrayDeque<Window> pool = new ArrayDeque<>();
    private int closed;
    private long violations;

    // Adjacency flags: the next main-thread packet is special
    private boolean rotationReplyNext;
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
        final Window window = this.current;
        final boolean reply = this.rotationReplyNext && packet instanceof ServerboundMovePlayerPacket.Rot;
        this.clearAdjacency();
        if (reply) {
            window.add(ROTATION_REPLY, packet, 0, 0);
            return true;
        }
        final int category = category(packet);
        boolean legal = category >= window.lastCategory || category == KEYBIND && window.lastCategory == KEYBIND;
        if (category != GUI) {
            window.tickGroupStarted = true;
        } else {
            legal = !window.tickGroupStarted;
        }
        switch (category) {
            case INPUT -> legal &= ++window.inputs == 1;
            case SPRINT -> legal &= ++window.sprints == 1;
            case MOVE -> legal &= ++window.moves == 1;
            default -> {
            }
        }
        if (packet instanceof final ServerboundUseItemPacket ignored && this.releasedEarlier(window)) {
            legal = false; // use after release in one window: a vanilla client discards every click of the release tick
        }
        window.lastCategory = Math.max(window.lastCategory, category);
        window.add(PACKET, packet, category, 0);
        if (window.items > MAX_ITEMS || !legal) {
            window.violations++;
            this.violations++;
            return false;
        }
        return true;
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
     * @param rotation the released entries hold a {@code PlayerRotation}: the next packet may be its reply
     * @param correction the id of a correction among them, or 0: the next packet must be its accept
     */
    public boolean recordAck(final int from, final int to, final boolean rotation, final int correction) {
        final Window window = this.current;
        this.clearAdjacency();
        window.add(ACK, null, from, to);
        this.rotationReplyNext = rotation;
        this.correctionAcceptNext = correction;
        if (window.tickGroupStarted) {
            window.violations++;
            this.violations++;
            return false;
        }
        return true;
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
        this.clearAdjacency();
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
        this.rotationReplyNext = false;
        this.correctionAcceptNext = 0;
    }

    /** The entries list pongs of the window being filled release into. */
    public Transactions.Acked currentAcked() {
        return this.current.acked;
    }

    /** {@code TickEnd}: closes the window being filled and queues it. Returns it. */
    public Window close(final Packet<?> tickEnd, final long nowNanos) {
        final Window window = this.current;
        this.clearAdjacency();
        window.add(PACKET, tickEnd, TICK_END, 0);
        window.tick = ++this.closed;
        window.closedNanos = nowNanos;
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
