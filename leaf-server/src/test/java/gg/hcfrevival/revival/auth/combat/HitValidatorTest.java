package gg.hcfrevival.revival.auth.combat;

import static gg.hcfrevival.revival.auth.combat.CombatScenes.attacker;
import static gg.hcfrevival.revival.auth.combat.CombatScenes.player;
import static org.junit.jupiter.api.Assertions.assertEquals;

import gg.hcfrevival.revival.RevivalRegistries;
import gg.hcfrevival.revival.RevivalTest;
import java.util.List;
import net.minecraft.network.protocol.game.VecDelta;
import net.minecraft.world.entity.PositionPath;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.phys.Vec3;
import org.junit.jupiter.api.Test;

/**
 * Lag-compensated hit validation (design §7.1): the attacker at (0.5, 64, 0.5) looks south (+z) with 1.7 ranges
 * (block 4.5, entity 3.0); targets are mirrored players spawned on the attacker's client before tick 1.
 */
@RevivalTest
@RevivalRegistries
class HitValidatorTest {

    private static final int TARGET = 7;
    private static final int OTHER = 8;
    private static final long SENT = 1_000L;
    private static final long CAP = 300L;
    private static final AttackerSnapshot ATTACKER = attacker(0.5, 64.0, 0.5, 0.0F, 0.0F, 4.5, 3.0);

    private static MirrorBook book(final double targetZ) {
        final MirrorBook book = new MirrorBook(2, 20);
        book.onEvent(TARGET, new MirrorEvent.Spawn(0.5, 64.0, targetZ, 0.0F, 0.0F, SENT), 1);
        return book;
    }

    private static HitCheck check(final MirrorBook book, final PickWorld world, final List<HitValidator.Candidate> others, final long now) {
        book.onViewerTickEnd(1);
        return new HitValidator().validate(ATTACKER, TARGET, book, 1, others, world, now, CAP);
    }

    private static HitCheck check(final MirrorBook book) {
        return check(book, new CombatScenes.Blocks2(), List.of(), SENT + 100L);
    }

    @Test
    void aPlayerUnderTheCrosshairWithinReachIsValid() {
        final HitCheck check = check(book(3.0));
        assertEquals(HitCheck.Verdict.VALID, check.verdict());
        assertEquals(HitCheck.Reason.PICKED, check.reason());
        assertEquals(2.2, check.distance(), 1.0E-6); // to the box face at 3.0 - 0.3
        assertEquals(100L, check.rewindMillis());
    }

    @Test
    void reachIsTheClientsEntityInteractionRange() {
        final HitCheck check = check(book(4.5)); // face at 4.2, 3.7 from the eye
        assertEquals(HitCheck.Verdict.INVALID, check.verdict());
        assertEquals(HitCheck.Reason.OUT_OF_REACH, check.reason());
        assertEquals(3.7, check.distance(), 1.0E-6);
    }

    @Test
    void aPlayerBeyondTheRayIsMissed() {
        assertEquals(HitCheck.Reason.MISSED, check(book(9.0)).reason()); // past the 4.5-block ray
        final MirrorBook aside = new MirrorBook(2, 20);
        aside.onEvent(TARGET, new MirrorEvent.Spawn(2.0, 64.0, 2.0, 0.0F, 0.0F, SENT), 1);
        assertEquals(HitCheck.Reason.MISSED, check(aside).reason()); // not under the crosshair
    }

    @Test
    void aBlockBetweenThemBlocksTheHit() {
        final CombatScenes.Blocks2 wall = new CombatScenes.Blocks2().set(0, 65, 2, Blocks.STONE.defaultBlockState());
        final HitCheck check = check(book(3.0), wall, List.of(), SENT);
        assertEquals(HitCheck.Verdict.INVALID, check.verdict());
        assertEquals(HitCheck.Reason.BLOCKED, check.reason());
    }

    @Test
    void anotherPlayerInFrontTakesTheHit() {
        final MirrorBook book = book(3.0);
        book.onEvent(OTHER, new MirrorEvent.Spawn(0.5, 64.0, 2.0, 0.0F, 0.0F, SENT), 1);
        final HitCheck check = check(book);
        assertEquals(HitCheck.Verdict.INVALID, check.verdict());
        assertEquals(HitCheck.Reason.OCCLUDED, check.reason());
    }

    @Test
    void anEyeInsideTheTargetsBoxHitsIt() {
        final HitCheck check = check(book(0.6));
        assertEquals(HitCheck.Verdict.VALID, check.verdict());
        assertEquals(0.0, check.distance());
    }

    @Test
    void anEyeInsideAnotherPlayersBoxCanStillHitTheTargetBehind() {
        // ProjectileUtil.getEntityHitResult: a clipped entity after an inside one replaces it; the client's order decides
        final MirrorBook book = book(3.0);
        book.onEvent(OTHER, new MirrorEvent.Spawn(0.6, 64.0, 0.6, 0.0F, 0.0F, SENT), 1);
        assertEquals(HitCheck.Verdict.VALID, check(book).verdict());
    }

    @Test
    void aTargetTheAttackersClientDoesNotHaveIsInvalid() {
        final MirrorBook book = new MirrorBook(2, 20);
        book.onViewerTickEnd(1);
        assertEquals(HitCheck.Reason.NOT_PRESENT, new HitValidator().validate(ATTACKER, TARGET, book, 1, List.of(), new CombatScenes.Blocks2(), SENT, CAP).reason());
        final MirrorBook removed = book(3.0);
        removed.onEvent(TARGET, MirrorEvent.REMOVE, 1);
        assertEquals(HitCheck.Reason.NOT_PRESENT, check(removed).reason());
    }

    @Test
    void theRewindCapIsInclusive() {
        assertEquals(HitCheck.Verdict.VALID, check(book(3.0), new CombatScenes.Blocks2(), List.of(), SENT + CAP).verdict());
        final HitCheck old = check(book(3.0), new CombatScenes.Blocks2(), List.of(), SENT + CAP + 1L);
        assertEquals(HitCheck.Verdict.INVALID, old.verdict());
        assertEquals(HitCheck.Reason.REWIND, old.reason());
        assertEquals(CAP + 1L, old.rewindMillis());
    }

    @Test
    void theRewindIsMeasuredFromTheDisplayedStep() {
        // The display is half-way into a step sent at SENT + 50, so the rewind runs from there
        final MirrorBook book = book(3.0);
        book.onEvent(TARGET, new MirrorEvent.Move(true, true, new VecDelta.Stepped(List.of(new VecDelta.Stepped.DeltaStep((short) 0, (short) 0, (short) 41, 2))),
            0.0F, 0.0F, SENT + 50L), 1);
        book.onViewerTickEnd(1);
        book.onViewerTickEnd(2);
        final HitCheck check = new HitValidator().validate(ATTACKER, TARGET, book, 2, List.of(), new CombatScenes.Blocks2(), SENT + 350L, CAP);
        assertEquals(HitCheck.Verdict.VALID, check.verdict());
        assertEquals(300L, check.rewindMillis());
    }

    @Test
    void anInexactTargetIsUnknownNotGuessed() {
        final MirrorBook book = book(3.0);
        book.onEvent(TARGET, new MirrorEvent.Move(true, true, new VecDelta.Stepped(List.of(new VecDelta.Stepped.DeltaStep((short) 0, (short) 0, (short) 41, 2))),
            0.0F, 0.0F, SENT), 1);
        // a far snap while the queue is active: the client shifts its queue only if its world is free there
        book.onEvent(TARGET, new MirrorEvent.Sync(PositionPath.of(new Vec3(0.5, 64.0, 300.0)), 0.0F, 0.0F, SENT), 1);
        book.onViewerTickEnd(1);
        book.onViewerTickEnd(2);
        final HitCheck check = new HitValidator().validate(ATTACKER, TARGET, book, 2, List.of(), new CombatScenes.Blocks2(), SENT, CAP);
        assertEquals(HitCheck.Verdict.UNKNOWN, check.verdict());
        assertEquals(HitCheck.Reason.INEXACT_TARGET, check.reason());
    }

    @Test
    void anAttackTickNotRunYetIsUnknown() {
        final MirrorBook book = book(3.0);
        book.onViewerTickEnd(1);
        assertEquals(HitCheck.Reason.NO_SAMPLE, new HitValidator().validate(ATTACKER, TARGET, book, 5, List.of(), new CombatScenes.Blocks2(), SENT, CAP).reason());
    }

    @Test
    void spearsAndRidingAttackersAreNotReplayed() {
        final AttackerSnapshot spear = new AttackerSnapshot(0.5, 64.0, 0.5, 0.5, 64.0, 0.5, 1.62F, 0.0F, 0.0F, player(0.5, 64.0, 0.5), 4.5, 3.0, true, false);
        final MirrorBook book = book(3.0);
        book.onViewerTickEnd(1);
        assertEquals(HitCheck.Reason.UNSUPPORTED, new HitValidator().validate(spear, TARGET, book, 1, List.of(), new CombatScenes.Blocks2(), SENT, CAP).reason());
    }

    @Test
    void aNonPlayerInFrontDecidesOnlyWhenItsBoxIsExact() {
        final HitValidator.Candidate exactBlocker = new HitValidator.Candidate(100, player(0.5, 64.0, 2.0), 0.0F, true, true);
        final HitCheck occluded = check(book(3.0), new CombatScenes.Blocks2(), List.of(exactBlocker), SENT);
        assertEquals(HitCheck.Reason.OCCLUDED, occluded.reason());
        final HitValidator.Candidate guessedBlocker = new HitValidator.Candidate(100, player(0.5, 64.0, 2.0), 0.0F, true, false);
        final HitCheck unknown = check(book(3.0), new CombatScenes.Blocks2(), List.of(guessedBlocker), SENT);
        assertEquals(HitCheck.Verdict.UNKNOWN, unknown.verdict());
        assertEquals(HitCheck.Reason.INEXACT_BLOCKER, unknown.reason());
        // An inexact candidate that can't change the outcome doesn't matter
        final HitValidator.Candidate behind = new HitValidator.Candidate(100, player(0.5, 64.0, 4.0), 0.0F, true, false);
        assertEquals(HitCheck.Verdict.VALID, check(book(3.0), new CombatScenes.Blocks2(), List.of(behind), SENT).verdict());
    }
}
