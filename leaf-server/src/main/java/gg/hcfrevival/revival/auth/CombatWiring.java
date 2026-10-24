package gg.hcfrevival.revival.auth;

import gg.hcfrevival.revival.Revival;
import gg.hcfrevival.revival.auth.combat.AttackerSnapshot;
import gg.hcfrevival.revival.auth.combat.HitCheck;
import gg.hcfrevival.revival.auth.combat.HitValidator;
import gg.hcfrevival.revival.auth.combat.MirrorBook;
import gg.hcfrevival.revival.auth.combat.SentTrail;
import gg.hcfrevival.revival.auth.net.Transactions;
import gg.hcfrevival.revival.combat.ProjectileBlocks;
import gg.hcfrevival.revival.combat.ProjectileViews;
import gg.hcfrevival.revival.config.AuthoritativeSettings;
import java.util.List;
import net.minecraft.core.component.DataComponents;
import net.minecraft.network.protocol.Packet;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.ai.attributes.Attributes;
import net.minecraft.world.phys.AABB;
import org.jspecify.annotations.Nullable;

/**
 * Connects the combat package to the netcode: each viewer's {@link MirrorBook} (kept in {@link AuthPlayer#combatState})
 * is fed the other-player packets its client applied, at their arrival ticks, and stepped once per window; attacks are
 * judged by {@link HitValidator} from the attacker's authoritative eye, its tick rotation, its mirrors and its world
 * view (design §7.1, §15.7).
 * <p>
 * A living non-player target (design §7.1, 2026-10-24) gets the block clip only ({@link HitValidator#blockClip}): INVALID
 * when a block the server has stands between the eye and everywhere the client may have drawn it, else
 * {@link AttackJudge#UNKNOWN} and the vanilla/Paper checks, as before. Non-player entities still never block a pick
 * (§15.7). The rewind cap is {@link #rewindCap}'s (HR-2).
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
            attacker.view().items.mainHand().has(DataComponents.ATTACK_RANGE), attacker.view().passenger(),
            reach(attacker, sim.vx(), sim.vy(), sim.vz()));
    }

    // An attack-range item's pick inputs (LocalPlayer.raycastHitResult reads getActiveItem: the main hand unless using)
    private static AttackerSnapshot.@Nullable Reach reach(final AuthPlayer attacker, final double vx, final double vy, final double vz) {
        final net.minecraft.world.item.component.AttackRange range = attacker.view().items.mainHand().get(DataComponents.ATTACK_RANGE);
        if (range == null) {
            return null;
        }
        final boolean creative = attacker.view().creative();
        return new AttackerSnapshot.Reach(attacker.headYRot(), vx, vy, vz, creative ? range.minCreativeReach() : range.minReach(),
            creative ? range.maxCreativeReach() : range.maxReach(), range.hitboxMargin());
    }

    /**
     * The same origin from the server player, for a tick the port isn't stepped on (an exempt tick, or the first one after
     * it, before the re-seed): vanilla's handler moved the player to the client's claims, so its eye is where the client
     * picked from.
     */
    public static AttackerSnapshot serverSnapshot(final AuthPlayer attacker) {
        final ServerPlayer p = attacker.player();
        return new AttackerSnapshot(p.getX(), p.getY(), p.getZ(), p.getX(), p.getY(), p.getZ(), p.getEyeHeight(), attacker.tickYRot(), attacker.tickXRot(),
            p.getBoundingBox(), attacker.view().attributes.value(Attributes.BLOCK_INTERACTION_RANGE),
            attacker.view().attributes.value(Attributes.ENTITY_INTERACTION_RANGE), attacker.view().items.mainHand().has(DataComponents.ATTACK_RANGE),
            attacker.view().passenger(), reach(attacker, p.getDeltaMovement().x, p.getDeltaMovement().y, p.getDeltaMovement().z));
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
        return gg.hcfrevival.revival.combat.ProjectileRewind.viewBox(book, targetId, Authority.clockMillis(),
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
            return this.judgeNonPlayer(attacker, target);
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
            Authority.clockMillis(), cap);
        attacker.recordHit(check.verdict() == HitCheck.Verdict.VALID ? "VALID" : check.verdict().name() + " " + check.reason().name(),
            check.rewindMillis(), check.distance());
        if (check.verdict() == HitCheck.Verdict.VALID) {
            attacker.noteValidRewind(check.rewindMillis(), attacker.lastDisplayDelay(), settings);
        } else if (check.invalid()) {
            attacker.stats().rejected(check.reason(), false);
        }
        return switch (check.verdict()) {
            case VALID -> VALID;
            case INVALID -> INVALID;
            default -> UNKNOWN;
        };
    }

    // Non-player targets: the block clip (design §7.1, 2026-10-24) ---------------------------------------------------------

    /**
     * 26.3 picks the bare box (pvp-issues HR-7); PvP clients put 1.7's 0.1 back, and the position a client decodes is up to
     * 1/8192 off the server's. Grows the region a target may have been drawn in, never the blocks.
     */
    static final double NON_PLAYER_MARGIN = 0.1;

    private final HitValidator.Region region = new HitValidator.Region();

    /**
     * The block clip for a non-player target: the eye the player pick uses ({@link #snapshot}: the truth's, or the claim's
     * for a gap the server caused), the attacker's world view as the truth reads it (a denied break the client still shows
     * open is the block there; a block the server changed and this client hasn't acked yet is still the client's old
     * state, as for the player pick), and the region of {@link #sweep}. INVALID only when it is BLOCKED.
     * <p>
     * Left to vanilla's checks alone, as before (UNKNOWN): the key off, a target that isn't living (item frames, boats, end
     * crystals) or is a mannequin (REDLINE's dummies and NPC plugins stand in for players with it), a spectator or riding
     * attacker, a window whose keybinds ran exempt (never delivered under enforce, and the truth's eye isn't where the
     * client picked from), a truth not seeded yet.
     */
    private int judgeNonPlayer(final AuthPlayer attacker, final Entity target) {
        final AuthoritativeSettings settings = Revival.settings().authoritative();
        if (!settings.blockClipNonPlayerTargets() || !(target instanceof final LivingEntity living) || !clipped(living)
            || attacker.player().isSpectator() || attacker.view().passenger() || attacker.attackExempt() || !attacker.simulation().seeded()) {
            attacker.recordHit("UNKNOWN non-player", -1L, Double.NaN);
            return UNKNOWN;
        }
        final AttackerSnapshot snapshot = snapshot(attacker);
        final Transactions tx = attacker.transactions();
        final int span = nonPlayerSpanTicks(tx.rttBaseNanos() / 1_000_000L, tx.rttHighNanos() / 1_000_000L, living.getType().updateInterval(), settings);
        final AttackerSnapshot.Reach reach = snapshot.reach();
        final double margin = NON_PLAYER_MARGIN + living.getPickRadius() + (reach != null ? Math.max(0.0F, reach.margin()) : 0.0);
        final HitCheck check = this.validator.blockClip(snapshot, target.getId(),
            sweep(living, net.minecraft.server.MinecraftServer.currentTick, span, margin, this.region.clear()), attacker.world());
        attacker.recordHit(check.verdict().name() + " " + check.reason().name(), -1L, check.distance());
        if (check.invalid()) {
            attacker.stats().rejected(check.reason(), true);
            return INVALID;
        }
        return UNKNOWN;
    }

    /**
     * Living non-player targets the block clip judges, and whose sends feed the trail: every one that isn't a player. A
     * mannequin (REDLINE's dummies, NPC plugins) is one (2026-10-24): it stands in for a player but has no truth path,
     * and the block the server has between the eye and it is as real as for a mob; players are judged on their paths.
     */
    static boolean clipped(final LivingEntity living) {
        return !(living instanceof net.minecraft.world.entity.player.Player);
    }

    /**
     * Where the attacker's client may have drawn {@code living}: its server box now and a tick ago ({@code xo/yo/zo}), the
     * positions its tracker sent it to over the last {@code spanTicks} server ticks ({@link SentTrail}), all in its size
     * now, grown by {@code margin}.
     */
    static HitValidator.Region sweep(final LivingEntity living, final int nowTick, final int spanTicks, final double margin,
                                     final HitValidator.Region region) {
        final AABB box = living.getBoundingBox();
        region.include(box);
        final double dx = living.xo - living.getX();
        final double dy = living.yo - living.getY();
        final double dz = living.zo - living.getZ();
        region.include(box.minX + dx, box.minY + dy, box.minZ + dz, box.maxX + dx, box.maxY + dy, box.maxZ + dz);
        final SentTrail trail = living.revivalSentTrail;
        if (trail != null) {
            trail.sweep(nowTick, spanTicks, living.getX(), living.getY(), living.getZ(), box.minX, box.minY, box.minZ, box.maxX, box.maxY, box.maxZ, region);
        }
        return region.inflate(margin);
    }

    /**
     * How far back, in server ticks, a client may be drawing a non-player target when its attack is judged. As the player
     * cap (HR-2): the round trip made good ({@code min(rttBase, rtt-ceiling-ms)}), the jitter allowance and the tick waits
     * ({@code rewind-slack-ms}); with no mirror to measure the display delay, the mob's own: 26.3 steps a mob one send at a
     * time, a send every {@code updateInterval} ticks, so it shows a position up to two intervals old. At most
     * {@code max-rewind-ms}, rounded up to whole ticks.
     */
    static int nonPlayerSpanTicks(final long rttBaseMs, final long rttHighMs, final int updateInterval, final AuthoritativeSettings settings) {
        final long spreadHigh = Math.min(rttHighMs, rttBaseMs + settings.rttCeilingMs());
        final long display = 2L * Math.clamp(updateInterval, 1, 20) * 50L;
        final long span = Math.min(settings.maxRewindMs(),
            cap(Math.min(rttBaseMs, settings.rttCeilingMs()), rttBaseMs, spreadHigh, display, settings.rewindJitterFactor(), settings.rewindSlackMs()));
        return (int) ((span + 49L) / 50L);
    }

    /**
     * {@code ServerEntity.sendChanges} sent a living non-player's movement ({@link Authority#sendMovement}): with a position,
     * that is where every client tracking it steps it to next, so its {@link SentTrail} takes it. Main thread (authority
     * refuses Leaf's async tracker).
     */
    static void sent(final LivingEntity living, final Packet<?> packet) {
        if (!(packet instanceof final net.minecraft.network.protocol.game.MovementPacket<?> movement) || !movement.hasPosition()
            || !Revival.settings().authoritative().blockClipNonPlayerTargets()) {
            return;
        }
        SentTrail trail = living.revivalSentTrail;
        if (trail == null) {
            trail = new SentTrail();
            living.revivalSentTrail = trail;
        }
        trail.record(living.getX(), living.getY(), living.getZ(), net.minecraft.server.MinecraftServer.currentTick);
    }
}
