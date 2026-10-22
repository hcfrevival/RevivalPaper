package gg.hcfrevival.revival.auth;

import gg.hcfrevival.revival.Revival;
import gg.hcfrevival.revival.auth.combat.AttackerSnapshot;
import gg.hcfrevival.revival.auth.combat.HitCheck;
import gg.hcfrevival.revival.auth.combat.HitValidator;
import gg.hcfrevival.revival.auth.combat.MirrorBook;
import gg.hcfrevival.revival.config.AuthoritativeSettings;
import java.util.List;
import net.minecraft.core.component.DataComponents;
import net.minecraft.network.protocol.Packet;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.ai.attributes.Attributes;

/**
 * Connects the combat package to the netcode: each viewer's {@link MirrorBook} (kept in {@link AuthPlayer#combatState})
 * is fed the other-player packets its client applied, at their arrival ticks, and stepped once per window; attacks are
 * judged by {@link HitValidator} from the attacker's authoritative eye, its tick rotation, its mirrors and its world
 * view (design §7.1, §15.7).
 * <p>
 * Non-player targets never veto a hit in v1 (§15.7): they get {@link AttackJudge#UNKNOWN} and the vanilla/Paper checks.
 * The rewind cap is {@code min(max-rewind-ms, rttBase + ack jitter + K × 50 ms)}.
 */
public final class CombatWiring implements ViewerMirrors, AttackJudge {

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
        final long rttMillis = attacker.transactions().rttBaseNanos() / 1_000_000L;
        final long cap = Math.min(settings.maxRewindMs(), rttMillis + settings.ackJitterTicks() * 50L + settings.pathStepTicks() * 50L);
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
