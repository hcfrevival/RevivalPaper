package gg.hcfrevival.revival.auth;

import gg.hcfrevival.revival.Revival;
import gg.hcfrevival.revival.auth.combat.AttackerSnapshot;
import gg.hcfrevival.revival.auth.combat.HitCheck;
import gg.hcfrevival.revival.auth.combat.HitValidator;
import gg.hcfrevival.revival.auth.combat.MirrorBook;
import gg.hcfrevival.revival.combat.ProjectileBlocks;
import gg.hcfrevival.revival.combat.ProjectileViews;
import gg.hcfrevival.revival.config.AuthoritativeSettings;
import java.util.List;
import net.minecraft.core.component.DataComponents;
import net.minecraft.network.protocol.Packet;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.ai.attributes.Attributes;
import org.jspecify.annotations.Nullable;

/**
 * Connects the combat package to the netcode: each viewer's {@link MirrorBook} (kept in {@link AuthPlayer#combatState})
 * is fed the other-player packets its client applied, at their arrival ticks, and stepped once per window; attacks are
 * judged by {@link HitValidator} from the attacker's authoritative eye, its tick rotation, its mirrors and its world
 * view (design §7.1, §15.7).
 * <p>
 * Non-player targets never veto a hit in v1 (§15.7): they get {@link AttackJudge#UNKNOWN} and the vanilla/Paper checks.
 * The rewind cap is {@code min(max-rewind-ms, rttBase + ack jitter + K × 50 ms)}.
 */
public final class CombatWiring implements ViewerMirrors, AttackJudge, ProjectileViews {

    public static final CombatWiring INSTANCE = new CombatWiring();

    private final HitValidator validator = new HitValidator();

    private CombatWiring() {
    }

    /** The viewer's mirrors, created on first use. */
    public static MirrorBook book(final AuthPlayer viewer) {
        if (viewer.combatState instanceof final MirrorBook book) {
            return book;
        }
        final MirrorBook book = new MirrorBook(MirrorBook.playerInterpolationSteps(), Revival.settings().authoritative().mirrorHistoryTicks());
        viewer.combatState = book;
        return book;
    }

    @Override
    public void onApplied(final AuthPlayer viewer, final Packet<?> packet, final int arrivalTick, final boolean exact, final long sentMillis) {
        book(viewer).onPacket(packet, arrivalTick, sentMillis, exact);
        final int target = positionTarget(packet);
        if (target != Integer.MIN_VALUE) {
            viewer.noteStamp(target, sentMillis); // the newest position of that player this client has acked
        }
    }

    @Override
    public void onTickEnd(final AuthPlayer viewer, final int tick) {
        book(viewer).onViewerTickEnd(tick);
    }

    @Override
    public void onTickRate(final AuthPlayer viewer, final float rate, final boolean frozen) {
        // ClientLevel.getRelativeTickSpeed: remote entities interpolate faster only when the server runs above 20 TPS
        book(viewer).setRelativeTickSpeed(rate > 20.0F ? rate / 20.0F : 1.0F);
    }

    @Override
    public void onReset(final AuthPlayer viewer) {
        if (viewer.combatState instanceof final MirrorBook book) {
            book.reset();
        }
    }

    /**
     * The client's pick origin at its keybinds: the truth's eye after the last tick (or the client's own while a
     * correction is in flight, bounded by its Δ, §15.3), the tick's rotation, the client's interaction ranges.
     */
    public static AttackerSnapshot snapshot(final AuthPlayer attacker) {
        final Simulation sim = attacker.simulation();
        final double ox = attacker.eyeOffset(0);
        final double oy = attacker.eyeOffset(1);
        final double oz = attacker.eyeOffset(2);
        return new AttackerSnapshot(sim.xo() + ox, sim.yo() + oy, sim.zo() + oz, sim.x() + ox, sim.y() + oy, sim.z() + oz,
            sim.eyeHeight(), attacker.tickYRot(), attacker.tickXRot(), ox == 0.0 && oy == 0.0 && oz == 0.0 ? sim.box() : sim.box().move(ox, oy, oz),
            attacker.view().attributes.value(Attributes.BLOCK_INTERACTION_RANGE), attacker.view().attributes.value(Attributes.ENTITY_INTERACTION_RANGE),
            attacker.view().items.mainHand().has(DataComponents.ATTACK_RANGE), attacker.view().passenger());
    }

    private final MirrorBook.Sample capSample = new MirrorBook.Sample();

    /**
     * The attacker's own rewind cap (pvp-issues HR-2, Overwatch's policy): the attacker's ping compensated in full up to
     * {@code rtt-ceiling-ms}, plus the display delay its client really has, plus its jitter allowance and the tick waits:
     * {@code min(rttBase, rtt-ceiling-ms) + display + jitter + slack}. {@code display} is measured from the attacker's own
     * mirror of the target: how far the box its client shows at this tick (interpolating, K2) lags the newest position of
     * the target it has acked. A rewind is then honest when {@code now - newest acked position <= rtt + jitter + slack}:
     * what the client could have seen is bounded by what it acked, so a client that holds pongs to keep an old view only
     * makes its own view older. {@code max-rewind-ms} (600) is only a sanity bound on top. Past the cap the judge
     * ({@code HitValidator}) tests the hit against the target's truth at the capped age, not the box the client drew: a
     * high-ping shooter has to lead moving players by what its ping exceeds the cap.
     */
    long rewindCap(final AuthPlayer attacker, final int targetId, final int tick, final AuthoritativeSettings settings) {
        final long rttBase = attacker.transactions().rttBaseNanos() / 1_000_000L;
        final long rttHigh = attacker.transactions().rttHighNanos() / 1_000_000L;
        final long newest = attacker.newestStamp(targetId);
        final long shown = newest != Long.MIN_VALUE && book(attacker).sample(targetId, tick, this.capSample) ? this.capSample.stamp : Long.MIN_VALUE;
        final long display = displayDelay(newest, shown);
        // The jitter spread is the client's own pongs too: no wider than the ceiling, or erratic pongs would buy a backtrack
        final long spreadHigh = Math.min(rttHigh, rttBase + settings.rttCeilingMs());
        final long cap = cap(Math.min(rttBase, settings.rttCeilingMs()), rttBase, spreadHigh, display, settings.rewindJitterFactor(), settings.rewindSlackMs());
        final long bounded = Math.min(settings.maxRewindMs(), cap);
        attacker.recordRewindCap(bounded, display);
        return bounded;
    }

    /**
     * The one rewind cap every lag-compensated rule shares (melee judge, Neue's held-click landing, projectile hits): the
     * attacker's own {@link #rewindCap}, at most {@code max-rewind-ms}. Main thread (the shooter's or attacker's tick).
     */
    public static long sharedRewindCap(final AuthPlayer attacker, final int targetId, final int tick) {
        return INSTANCE.rewindCap(attacker, targetId, tick, Revival.settings().authoritative());
    }

    // ProjectileViews (docs/revival/projectile-hooks.md N1) --------------------------------------------------------------

    @Override
    public boolean compensates(final ServerPlayer shooter) {
        final AuthPlayer auth = shooter.revivalAuth;
        return auth != null && auth.mode() == AuthMode.ENFORCE && auth.simulation().seeded();
    }

    @Override
    public boolean viewBox(final ServerPlayer shooter, final int targetId, final double[] out) {
        final AuthPlayer auth = shooter.revivalAuth;
        if (!this.compensates(shooter)) {
            return false;
        }
        final MirrorBook book = book(auth);
        return gg.hcfrevival.revival.combat.ProjectileRewind.viewBox(book, targetId, System.currentTimeMillis(),
            sharedRewindCap(auth, targetId, book.lastTick()), out);
    }

    @Override
    public @Nullable ProjectileBlocks clientBlocks(final ServerPlayer player) {
        final AuthPlayer auth = player.revivalAuth;
        return auth != null && auth.mode() == AuthMode.ENFORCE ? auth.world() : null; // the view the simulation reads
    }

    /** How far the box a client shows lags the newest position it has acked (its interpolation), never negative. */
    static long displayDelay(final long newestAcked, final long shownStamp) {
        if (newestAcked == Long.MIN_VALUE || shownStamp == Long.MIN_VALUE || shownStamp <= 0L) {
            return 0L;
        }
        return Math.max(0L, newestAcked - shownStamp);
    }

    /**
     * {@code compensated + display + factor × (rttHigh − rttBase) + slack}, all in milliseconds, before the sanity bound.
     * {@code compensated} is the round trip made good ({@code min(rttBase, rtt-ceiling-ms)}); the jitter is the
     * attacker's own spread (p90 − p10 of its ack round trips).
     */
    static long cap(final long compensated, final long rttBase, final long rttHigh, final long display, final double jitterFactor, final long slack) {
        return compensated + display + Math.round(Math.max(0L, rttHigh - rttBase) * jitterFactor) + slack;
    }

    // The player a position-carrying tracker packet is about, or MIN_VALUE
    static int positionTarget(final Packet<?> packet) {
        return switch (packet) {
            case final net.minecraft.network.protocol.game.ClientboundMoveEntityPacket move -> move.hasPosition() ? move.revivalEntityId() : Integer.MIN_VALUE;
            case final net.minecraft.network.protocol.game.ClientboundEntityPositionSyncPacket sync -> sync.id();
            case final net.minecraft.network.protocol.game.ClientboundTeleportEntityPacket teleport -> teleport.id();
            case final net.minecraft.network.protocol.game.ClientboundAddEntityPacket add -> add.getId();
            default -> Integer.MIN_VALUE;
        };
    }

    @Override
    public int judge(final AuthPlayer attacker, final Entity target, final int tick) {
        if (!(target instanceof ServerPlayer)) {
            attacker.recordHit("UNKNOWN non-player", -1L, Double.NaN);
            return UNKNOWN;
        }
        final Simulation sim = attacker.simulation();
        if (!sim.seeded()) {
            attacker.recordHit("UNKNOWN unseeded", -1L, Double.NaN);
            return UNKNOWN;
        }
        final AuthoritativeSettings settings = Revival.settings().authoritative();
        final AttackerSnapshot snapshot = snapshot(attacker);
        final long cap = rewindCap(attacker, target.getId(), tick, settings);
        final HitCheck check = this.validator.validate(snapshot, target.getId(), book(attacker), tick, List.of(), attacker.world(),
            System.currentTimeMillis(), cap);
        attacker.recordHit(check.verdict() == HitCheck.Verdict.VALID ? "VALID" : check.verdict().name() + " " + check.reason().name(),
            check.rewindMillis(), check.distance());
        return switch (check.verdict()) {
            case VALID -> VALID;
            case INVALID -> INVALID;
            default -> UNKNOWN;
        };
    }
}
