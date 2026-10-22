package gg.hcfrevival.revival.combat;

import gg.hcfrevival.revival.config.CombatRules;
import org.jspecify.annotations.Nullable;

/**
 * One player's Neue combat state (docs/revival/neue-combat.md §5, §8): the held click, the parry clock of their sword
 * block, their riposte, their stagger, and the context of the attack being run. Pure: every tick is passed in, so the
 * state machines are tested without a server ({@link NeueCombat} feeds it from the live game).
 * <p>
 * Two clocks: block, swing and stagger-ack ticks are the player's client ticks ({@code W_T}) while the authoritative
 * simulation owns them, and server ticks otherwise (§11); deadlines (the buffer, the riposte, the lockout's floor) are
 * always server ticks. A tick recorded on one clock means nothing on the other, so switching clocks forgets the old
 * block and swing ticks: the next block gets a fresh window, as a first block does.
 * <p>
 * Owned by the player's tick thread, like {@link LegacyHitRegistration}, which holds it.
 */
public final class NeueState {

    /** A tick that never happened: long before any other. */
    public static final long NEVER = Long.MIN_VALUE;
    /** A degraded attack's target before a hook names it (the hit-registration hook doesn't see the target). */
    public static final int UNKNOWN_TARGET = Integer.MIN_VALUE;

    // What became of the attack being run
    /** Delivered, but turned away for some other reason (an event cancelled it, Paper's checks): the held click goes. */
    public static final int TURNED_AWAY = 0;
    /** Turned away by the victim's shut damage window and no harder: held (§5.2). */
    public static final int HELD = 1;
    /** Landed fresh or as an overflow. */
    public static final int LANDED = 2;
    /** Met a fresh block's parry window. */
    public static final int PARRIED = 3;
    /** Never delivered (stagger lockout, click cap): the held click stays as it is. */
    public static final int REFUSED = 4;

    // One server tick of the landing pass
    public static final int IDLE = 0;
    /** The target's window is still shut. */
    public static final int WAIT = 1;
    /** The window opened: land the click now, after the reach check. One attempt only. */
    public static final int DUE = 2;
    public static final int EXPIRED = 3;
    /** The target is gone (removed, dead, another level, a spectator). */
    public static final int GONE = 4;
    /** The attacker can't swing now (blocking, staggered, a container open, another weapon...). */
    public static final int NOT_FREE = 5;

    /** A stagger whose ack never comes stops locking after this many server ticks past its length (a client that stalls is kicked long before). */
    static final int LOCKOUT_CAP_TICKS = 100;

    private boolean clientClock;

    // The parry clock (§8.2)
    private boolean blocking;
    private long blockStart = NEVER;
    private long blockStartServer = NEVER;
    private long blockEnd = NEVER;
    private long swing = NEVER;
    private int parryWindow;
    private boolean parryUsed;

    private long riposteUntil = NEVER;

    // The stagger (§8.4)
    private boolean staggered;
    private long staggerServer = NEVER;
    private @Nullable Object staggerPacket;
    private long staggerAck = NEVER;
    private boolean staggerAckable;
    private int staggerTicks;
    private int staggerJitter;
    private int staggerRtt;
    // Under authority, the real time (System.nanoTime, the clock TickClock budgets client windows with) before which the
    // lockout holds whatever the client's windows say (L3), and when the recoil went out, which anchors it
    private long staggerFloorNanos = NEVER;
    private long staggerEarliestAckNanos = NEVER;
    private boolean slowed;
    // The stagger's slow as its own packet (it can reach the client apart from the recoil), and the window that acked it
    private @Nullable Object slowPacket;
    private long slowAck = NEVER;

    // The held click (§5.2)
    private boolean pending;
    private int pendingTarget;
    private @Nullable Object pendingPacket;
    private long pendingUntil;
    private int pendingSlot;
    private @Nullable Object pendingWeapon;

    // The attack being run
    private boolean active;
    private boolean entered;
    private int target;
    private @Nullable Object packet;
    private double distance = Double.NaN;
    private boolean buffered;
    private int outcome;
    private int spot;

    // The click cap of a player outside authority (§11); under authority the netcode's bucket charges
    private double tokens;
    private long tokensTick = NEVER;

    // Per-player counters for the playtest HUD (§14)
    private long tippers;
    private long sours;
    private long parries;
    private long staggers;
    private long ripostes;
    private long bufferedLanded;
    private long bufferExpired;
    private long bufferDropped;
    private long bufferOutOfReach;

    // ------------------------------------------------------------------------------------------------------------------
    // Parry
    // ------------------------------------------------------------------------------------------------------------------

    /**
     * {@code PlayerSim.ParryWindow} in ticks: 0 for a block-hit (raised under {@code parry-block-hit} after a swing), else
     * {@code round(window × clamp((T_B − T_E − rearm) / (rest − rearm), 0, 1))}. A first block (no earlier one, no
     * swing) gets the whole window.
     */
    public static int parryWindow(final long blockStart, final long blockEnd, final long swing, final CombatRules rules) {
        if (swing != NEVER && blockStart - swing < rules.parryBlockHitTicks()) {
            return 0;
        }
        final double rest;
        if (blockEnd == NEVER) {
            rest = 1.0;
        } else {
            final long since = blockStart - blockEnd;
            final int span = rules.parryRestTicks() - rules.parryRearmTicks();
            rest = span <= 0
                ? (since >= rules.parryRearmTicks() ? 1.0 : 0.0)
                : Math.clamp((double) (since - rules.parryRearmTicks()) / span, 0.0, 1.0);
        }
        return (int) Math.round(rules.parryWindowTicks() * rest);
    }

    /** A sword block went up at {@code tick} (server tick {@code serverTick}): its parry window, armed once. */
    public void blockStarted(final long tick, final long serverTick, final boolean clientClock, final CombatRules rules) {
        this.clock(clientClock);
        this.blocking = true;
        this.blockStart = tick;
        this.blockStartServer = serverTick;
        this.parryWindow = rules.parry() ? parryWindow(tick, this.blockEnd, this.swing, rules) : 0;
        this.parryUsed = false;
    }

    /** The block came down at {@code tick}. */
    public void blockEnded(final long tick, final boolean clientClock) {
        if (!this.blocking) {
            return;
        }
        this.clock(clientClock);
        this.blocking = false;
        this.blockEnd = tick;
        this.parryWindow = 0;
    }

    /** The player swung (a {@code Punch}: every attack click sends one) at {@code tick}. */
    public void swung(final long tick, final boolean clientClock) {
        this.clock(clientClock);
        this.swing = tick;
    }

    /** Whether a parry is possible at all: a block up with its window left. Field reads only, the hurt hook's early exit. */
    public boolean mayParry() {
        return this.blocking && this.parryWindow > 0 && !this.parryUsed;
    }

    /**
     * Whether a hit now meets the block's window (§8.2): {@code max(T_now − T_B, S_now − S_B − jitter) < W}. The server
     * floor stops a blink: a blocker who stalls their windows after the block-start one gains at most {@code jitter}.
     */
    public boolean parries(final long tickNow, final long serverNow, final int jitterTicks) {
        if (!this.mayParry()) {
            return false;
        }
        final long byServer = serverNow - this.blockStartServer - jitterTicks;
        final long ticks = this.blockStart == NEVER ? byServer : Math.max(tickNow - this.blockStart, byServer);
        return ticks < this.parryWindow;
    }

    /** The block parried a hit: it's a plain block from now on, and the parrier has a riposte. */
    public void parried(final long serverNow, final int riposteTicks) {
        this.parryUsed = true;
        this.riposteUntil = serverNow + riposteTicks;
        this.parries++;
    }

    public boolean blocking() {
        return this.blocking;
    }

    public int parryWindow() {
        return this.parryWindow;
    }

    public boolean parryUsed() {
        return this.parryUsed;
    }

    // ------------------------------------------------------------------------------------------------------------------
    // Riposte
    // ------------------------------------------------------------------------------------------------------------------

    /** Whether the next hit is a riposte crit: within {@code riposte} ticks of the parry. */
    public boolean riposteReady(final long serverNow) {
        return serverNow < this.riposteUntil;
    }

    /** A hit landed (fresh or overflow): a ready riposte is used up. Returns whether it was. */
    public boolean consumeRiposte(final long serverNow) {
        if (!this.riposteReady(serverNow)) {
            return false;
        }
        this.riposteUntil = NEVER;
        this.ripostes++;
        return true;
    }

    // ------------------------------------------------------------------------------------------------------------------
    // Stagger
    // ------------------------------------------------------------------------------------------------------------------

    /**
     * Parried: locked out from now until the recoil is acked plus {@code staggerTicks} (§8.4). The held click goes, and
     * {@code packet} is the recoil, whose ack ({@link #motionApplied}) starts the clock; without one (a plugin cancelled
     * the velocity event), and outside authority, {@code rttTicks} (the round trip now, in whole ticks rounded up) stand
     * in for the ack. It is taken once, here: a round trip read again each tick would move the end of a stagger already
     * running whenever a keep-alive or pong lands. {@code jitterTicks} is the slack on the real-time floor the ack sets;
     * {@code sentNanos} is when the recoil went out ({@code System.nanoTime}) and {@code ackRttNanos} the round trip its
     * ack is expected to take ({@link #ackRtt}), taken now, before the recoil's own ack can move it: together the
     * earliest an honest ack could come, the floor's anchor.
     */
    public void stagger(final long serverNow, final @Nullable Object packet, final int staggerTicks, final int jitterTicks, final int rttTicks,
                        final boolean slowed, final long sentNanos, final long ackRttNanos) {
        this.clearPending();
        this.staggers++;
        if (staggerTicks <= 0) {
            return;
        }
        this.staggered = true;
        this.staggerServer = serverNow;
        this.staggerPacket = packet;
        this.staggerAckable = packet != null;
        this.staggerAck = NEVER;
        this.staggerTicks = staggerTicks;
        this.staggerJitter = jitterTicks;
        this.staggerRtt = rttTicks;
        this.staggerFloorNanos = NEVER;
        this.staggerEarliestAckNanos = sentNanos == NEVER ? NEVER : sentNanos + Math.max(0L, ackRttNanos);
        this.slowed |= slowed;
        this.slowPacket = null; // NeueCombat.stagger records the slow's own packet right after, when it sends one
        this.slowAck = NEVER;
    }

    /**
     * A logged packet was applied to the truth at client tick {@code tick}: when it is the recoil, that tick is
     * {@code T_s}, and the lockout also lasts until {@code anchor + (stagger − jitter) × periodNanos} of real time
     * ({@code System.nanoTime}), so running the client's ticks fast can't shorten it (cheat suite 2, L3). The anchor is
     * the earliest an honest ack could have come, {@code min(nowNanos, sent + ackRtt)}: an ack window that came in late
     * (jitter, a retransmit, a slow frame) moves no floor, and a client can't move the send time.
     */
    public boolean motionApplied(final Object packet, final long tick, final long nowNanos, final long periodNanos) {
        if (packet != this.staggerPacket || this.staggerPacket == null) {
            return false;
        }
        this.staggerAck = tick;
        final long anchor = this.staggerEarliestAckNanos == NEVER ? nowNanos : Math.min(nowNanos, this.staggerEarliestAckNanos);
        this.staggerFloorNanos = anchor + Math.max(0, this.staggerTicks - this.staggerJitter) * periodNanos;
        this.staggerPacket = null;
        return true;
    }

    /**
     * The round trip a recoil's ack is expected to take, read when the recoil goes out: the connection's baseline (a p10
     * that takes about 1.5 s to follow a ping that rose), or the last ack's round trip when that is longer. With the
     * baseline alone a client whose ping just rose would have the floor start that much early, and running its windows
     * fast would buy it the difference (200 ms set 1.3 s before the parry: 3 ticks).
     */
    public static long ackRtt(final long rttBaseNanos, final long lastRttNanos) {
        return Math.max(rttBaseNanos, lastRttNanos);
    }

    /**
     * Whether an attack now is refused. Under authority ({@code clientClock}): every attack in windows up to
     * {@code T_s + stagger − 1}, all of them until {@code T_s} is known (clicks made before the client could see the
     * parry are part of the recovery), and all of them until the real-time floor the ack set ({@code nowNanos} on the
     * monotonic clock the client's windows are budgeted with, so server lag or a hitch never lengthens it), as the parry's
     * age has one: a client that runs its windows fast gains at most the jitter and its recoil ack's round trip above the
     * one expected when the recoil went out.
     * Outside it: the round trip taken at the stagger stands in for the ack, so the first attack delivered is at server
     * tick {@code S_parry + rtt + stagger}. Ends the stagger once it is over.
     */
    public boolean lockedOut(final boolean clientClock, final long tick, final long serverNow, final long nowNanos) {
        if (!this.staggered) {
            return false;
        }
        final boolean locked;
        if (serverNow - this.staggerServer > this.staggerTicks + LOCKOUT_CAP_TICKS) {
            locked = false;
        } else if (clientClock && this.staggerAckable) {
            locked = this.staggerAck == NEVER || tick <= this.staggerAck + this.staggerTicks - 1 || nowNanos < this.staggerFloorNanos;
        } else {
            locked = serverNow < this.staggerServer + this.staggerRtt + this.staggerTicks;
        }
        if (!locked) {
            this.staggered = false;
        }
        return locked;
    }

    /** The slow went out as {@code packet} (sent with the recoil); its ack, not the recoil's, starts its clock. */
    public void slowSent(final Object packet) {
        this.slowPacket = packet;
        this.slowAck = NEVER;
    }

    /** A logged packet was applied at client tick {@code tick}: when it is the slow, the client is slowed from that tick. */
    public boolean slowApplied(final Object packet, final long tick) {
        if (packet != this.slowPacket || this.slowPacket == null) {
            return false;
        }
        this.slowAck = tick;
        this.slowPacket = null;
        return true;
    }

    /**
     * Whether the stagger's slow comes off now: under authority when the window reaches {@code start + stagger − rtt}
     * ({@code start} is the window that acked the slow, or {@code T_s} when no slow packet of its own was recorded;
     * {@code rttTicks} rounded), so the removal lands on the client near {@code start + stagger}; outside it after
     * {@code stagger} server ticks.
     */
    public boolean slowEnds(final boolean clientClock, final long tick, final long serverNow, final int rttTicks) {
        if (!this.slowed) {
            return false;
        }
        final boolean ends;
        if (serverNow - this.staggerServer > this.staggerTicks + LOCKOUT_CAP_TICKS) {
            ends = true;
        } else if (clientClock && this.staggerAckable) {
            final long start = this.slowAck != NEVER ? this.slowAck : this.slowPacket == null ? this.staggerAck : NEVER;
            ends = start != NEVER && tick >= start + this.staggerTicks - rttTicks;
        } else {
            ends = serverNow >= this.staggerServer + this.staggerTicks;
        }
        if (ends) {
            this.slowed = false;
        }
        return ends;
    }

    public boolean staggered() {
        return this.staggered;
    }

    public boolean slowed() {
        return this.slowed;
    }

    public long staggerAck() {
        return this.staggerAck;
    }

    // ------------------------------------------------------------------------------------------------------------------
    // The held click
    // ------------------------------------------------------------------------------------------------------------------

    /** Holds a click on {@code targetId} until server tick {@code serverNow + bufferTicks}, replacing any held one. */
    public void hold(final int targetId, final @Nullable Object packet, final long serverNow, final int bufferTicks, final int slot, final @Nullable Object weapon) {
        this.pending = true;
        this.pendingTarget = targetId;
        this.pendingPacket = packet;
        this.pendingUntil = serverNow + bufferTicks;
        this.pendingSlot = slot;
        this.pendingWeapon = weapon;
    }

    public void clearPending() {
        this.pending = false;
        this.pendingPacket = null;
        this.pendingWeapon = null;
    }

    /**
     * One server tick of the landing pass (§5.2 steps 1-5), the way Combo's {@code GameServer.Buffered} runs it: drop
     * an expired click, one whose target is gone, or one whose attacker isn't free; wait while the window is shut; on the
     * first open tick, let go of it (one attempt) and say {@link #DUE}. The packet and weapon stay readable until the
     * next {@link #begin} or {@link #hold}.
     */
    public int landingStep(final long serverNow, final boolean targetValid, final boolean free, final boolean windowOpen) {
        if (!this.pending) {
            return IDLE;
        }
        if (serverNow > this.pendingUntil) {
            this.clearPending();
            this.bufferExpired++;
            return EXPIRED;
        }
        if (!targetValid) {
            this.clearPending();
            this.bufferDropped++;
            return GONE;
        }
        if (!free) {
            this.clearPending();
            this.bufferDropped++;
            return NOT_FREE;
        }
        if (!windowOpen) {
            return WAIT;
        }
        this.pending = false;
        return DUE;
    }

    /** A due click failed the reach check at landing: it is gone, and doesn't land later even back in reach. */
    public void outOfReach() {
        this.pendingPacket = null;
        this.pendingWeapon = null;
        this.bufferOutOfReach++;
    }

    public boolean pending() {
        return this.pending;
    }

    public int pendingTarget() {
        return this.pendingTarget;
    }

    public @Nullable Object pendingPacket() {
        return this.pendingPacket;
    }

    public long pendingUntil() {
        return this.pendingUntil;
    }

    public int pendingSlot() {
        return this.pendingSlot;
    }

    public @Nullable Object pendingWeapon() {
        return this.pendingWeapon;
    }

    // ------------------------------------------------------------------------------------------------------------------
    // The attack being run
    // ------------------------------------------------------------------------------------------------------------------

    /**
     * An attack is about to run through {@code Player.attack}: what the hooks inside it mark, and the distance the
     * tipper reads. {@code distance} is NaN when unknown (the knock then measures server positions).
     */
    public void begin(final int targetId, final @Nullable Object packet, final double distance, final boolean buffered) {
        this.active = true;
        this.entered = false;
        this.target = targetId;
        this.packet = packet;
        this.distance = distance;
        this.buffered = buffered;
        this.outcome = TURNED_AWAY;
        this.spot = NeueShape.PLAIN;
        if (!this.pending) {
            this.pendingPacket = null; // a due click's, now this attack's
            this.pendingWeapon = null;
        }
    }

    /**
     * {@code Player.attack} started: true when it is the attack {@link #begin} opened and nothing entered yet (the
     * netcode's replay, a landing), false when it runs on its own (outside authority, a plugin's call).
     */
    public boolean enter() {
        if (!this.active || this.entered) {
            return false;
        }
        this.entered = true;
        return true;
    }

    /** Whether the attack being run is on {@code targetId}; a degraded attack's unknown target adopts it. */
    public boolean on(final int targetId) {
        if (!this.active) {
            return false;
        }
        if (this.target == UNKNOWN_TARGET) {
            this.target = targetId;
        }
        return this.target == targetId;
    }

    public void mark(final int outcome) {
        if (this.active) {
            this.outcome = outcome;
        }
    }

    /**
     * The attack is over: the held click follows its outcome (§5.2). Held by the window: this click is held (a buffered
     * landing turned away is not held again). Refused: the held click stays. Anything else (landed, parried, turned
     * away): it goes. Returns the outcome, or -1 when no attack was running.
     */
    public int settle(final long serverNow, final int bufferTicks, final int slot, final @Nullable Object weapon) {
        if (!this.active) {
            return -1;
        }
        this.active = false;
        final int result = this.outcome;
        switch (result) {
            case HELD -> {
                if (!this.buffered && bufferTicks > 0 && this.target != UNKNOWN_TARGET) {
                    this.hold(this.target, this.packet, serverNow, bufferTicks, slot, weapon);
                } else {
                    this.clearPending();
                }
            }
            case REFUSED -> {
            }
            default -> this.clearPending();
        }
        if (this.buffered && result == LANDED) {
            this.bufferedLanded++;
        }
        this.packet = null;
        return result;
    }

    public boolean active() {
        return this.active;
    }

    public int target() {
        return this.target;
    }

    public double distance() {
        return this.distance;
    }

    public boolean buffered() {
        return this.buffered;
    }

    public int outcome() {
        return this.outcome;
    }

    /** The spot ({@link NeueShape}) of the attack being run's knock, for {@code /revival kb debug}. */
    public int spot() {
        return this.spot;
    }

    void spot(final int spot) {
        this.spot = spot;
        if (spot == NeueShape.TIPPER) {
            this.tippers++;
        } else if (spot == NeueShape.SOUR) {
            this.sours++;
        }
    }

    // ------------------------------------------------------------------------------------------------------------------
    // The click cap outside authority
    // ------------------------------------------------------------------------------------------------------------------

    /**
     * Spends a click if there is one: {@code min(burst, tokens + refill)} per server tick since the last, starting full
     * ({@code PlayerSim.Mouse} and {@code SpendClick}, refilled lazily, which is the same sum).
     */
    public boolean takeClick(final long serverNow, final double refill, final double burst) {
        this.tokens = this.tokensTick == NEVER ? burst : Math.min(burst, this.tokens + (serverNow - this.tokensTick) * refill);
        this.tokensTick = serverNow;
        if (this.tokens < 1.0 - 1.0E-9) {
            return false;
        }
        this.tokens -= 1.0;
        return true;
    }

    // ------------------------------------------------------------------------------------------------------------------
    // Counters
    // ------------------------------------------------------------------------------------------------------------------

    public long tippers() {
        return this.tippers;
    }

    public long sours() {
        return this.sours;
    }

    public long parries() {
        return this.parries;
    }

    public long staggers() {
        return this.staggers;
    }

    public long ripostes() {
        return this.ripostes;
    }

    public long bufferedLanded() {
        return this.bufferedLanded;
    }

    public long bufferExpired() {
        return this.bufferExpired;
    }

    /** Held clicks dropped before their window opened: target gone, or the attacker not free. */
    public long bufferDropped() {
        return this.bufferDropped;
    }

    public long bufferOutOfReach() {
        return this.bufferOutOfReach;
    }

    // A tick on the other clock means nothing on this one
    private void clock(final boolean clientClock) {
        if (clientClock != this.clientClock) {
            this.clientClock = clientClock;
            this.blockStart = NEVER;
            this.blockEnd = NEVER;
            this.swing = NEVER;
        }
    }
}
