package gg.hcfrevival.revival.auth.combat;

import static gg.hcfrevival.revival.auth.combat.CombatScenes.attacker;
import static gg.hcfrevival.revival.auth.combat.CombatScenes.player;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import gg.hcfrevival.revival.RevivalRegistries;
import gg.hcfrevival.revival.RevivalTest;
import gg.hcfrevival.revival.auth.replicate.PathFeed;
import java.util.List;
import net.minecraft.network.protocol.game.VecDelta;
import net.minecraft.world.entity.PositionPath;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.phys.Vec3;
import org.junit.jupiter.api.Test;

/**
 * Lag-compensated hit validation (design §7.1): the attacker at (0.5, 64, 0.5) looks south (+z) with 1.7 ranges
 * (block 4.5, entity 3.0); targets are mirrored players spawned on the attacker's client before tick 1. Past the rewind
 * cap the target's truth comes from a real {@link PathFeed}'s steps (Overwatch's rule).
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
        return bookAt(0.5, targetZ);
    }

    private static MirrorBook bookAt(final double targetX, final double targetZ) {
        final MirrorBook book = new MirrorBook(2, 20);
        book.onEvent(TARGET, new MirrorEvent.Spawn(targetX, 64.0, targetZ, 0.0F, 0.0F, SENT), 1);
        return book;
    }

    // No truth to fall back on: a view past the cap is REWIND
    private static HitValidator validator() {
        return new HitValidator(TruthHistory.NONE);
    }

    private static HitCheck check(final MirrorBook book, final PickWorld world, final List<HitValidator.Candidate> others, final long now) {
        book.onViewerTickEnd(1);
        return validator().validate(ATTACKER, TARGET, book, 1, others, world, now, CAP);
    }

    /** The target's truth: one path step per {@code (stamp, x)} pair, at y 64 and z 3.0, through a real feed. */
    private static TruthHistory truth(final long... stampsAndXs) {
        final PathFeed feed = new PathFeed(TARGET, 16, PathFeed.Settings.DEFAULT);
        for (int i = 0; i < stampsAndXs.length; i += 2) {
            feed.append(stampsAndXs[i + 1] / 1000.0, 64.0, 3.0, 0.0F, 0.0F, true, stampsAndXs[i]);
        }
        return (targetId, sinceMillis, out) -> targetId == TARGET ? feed.positionSince(sinceMillis, out) : Long.MIN_VALUE;
    }

    // A view 100 ms past the cap, drawn at (drawnX, 64, 3.0), judged on `history`
    private static HitCheck pastTheCap(final double drawnX, final TruthHistory history) {
        final MirrorBook book = bookAt(drawnX, 3.0);
        book.onViewerTickEnd(1);
        return new HitValidator(history).validate(ATTACKER, TARGET, book, 1, List.of(), new CombatScenes.Blocks2(), SENT + CAP + 100L, CAP);
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
        assertEquals(HitCheck.Reason.NOT_PRESENT, validator().validate(ATTACKER, TARGET, book, 1, List.of(), new CombatScenes.Blocks2(), SENT, CAP).reason());
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
        assertFalse(old.capped());
    }

    @Test
    void pastTheCapALeadingAttackerHitsTheTruthAtTheCappedAge() {
        // Crossing +x at 0.15 a step. The client drew the step at SENT (x 0.25, box -0.05..0.55) and aimed at its leading
        // edge; the cap reaches back to SENT + 100, where the truth was at 0.55 (box 0.25..0.85): the crosshair is in both
        final HitCheck led = pastTheCap(0.25, truth(SENT, 250L, SENT + 50L, 400L, SENT + 100L, 550L, SENT + 150L, 700L));
        assertEquals(HitCheck.Verdict.VALID, led.verdict());
        assertTrue(led.capped());
        assertEquals(CAP + 100L, led.rewindMillis()); // the age of the view, which is what the cap was measured on
        assertEquals(2.2, led.distance(), 1.0E-6); // to the truth's face at 3.0 - 0.3
    }

    @Test
    void pastTheCapAimingAtTheStaleBoxMisses() {
        // Crossing at 0.25 a step: drawn at 0.5 (centred on the crosshair), the truth at the capped age at 1.0 (0.7..1.3)
        final HitCheck stale = pastTheCap(0.5, truth(SENT, 500L, SENT + 50L, 750L, SENT + 100L, 1000L, SENT + 150L, 1250L));
        assertEquals(HitCheck.Verdict.INVALID, stale.verdict());
        assertEquals(HitCheck.Reason.MISSED, stale.reason());
        assertTrue(stale.capped());
    }

    @Test
    void theTruthPastTheCapIsTheFirstStepAtTheCappedAgeNeverOlder() {
        // The cap reaches back to SENT + 100. Only the step at SENT + 150 is under the crosshair: the one before it is older
        // than the cap, the one after it newer than it needs to be
        final HitCheck check = pastTheCap(0.5, truth(SENT + 90L, 5000L, SENT + 150L, 500L, SENT + 200L, 5000L));
        assertEquals(HitCheck.Verdict.VALID, check.verdict());
        assertTrue(check.capped());
    }

    @Test
    void pastTheCapWithNoTruthThatNewItIsRewind() {
        // The newest step is older than the capped age (a target whose path stopped), or there is no path (off authority)
        final HitCheck stopped = pastTheCap(0.5, truth(SENT, 500L, SENT + 50L, 500L));
        assertEquals(HitCheck.Reason.REWIND, stopped.reason());
        assertEquals(CAP + 100L, stopped.rewindMillis());
        assertFalse(stopped.capped());
        assertEquals(HitCheck.Reason.REWIND, pastTheCap(0.5, TruthHistory.NONE).reason());
    }

    @Test
    void withinTheCapTheTruthIsNeverAsked() {
        final TruthHistory refuses = (targetId, sinceMillis, out) -> {
            throw new AssertionError("asked for the truth inside the cap");
        };
        final MirrorBook book = book(3.0);
        book.onViewerTickEnd(1);
        assertEquals(HitCheck.Verdict.VALID, new HitValidator(refuses).validate(ATTACKER, TARGET, book, 1, List.of(), new CombatScenes.Blocks2(), SENT + CAP, CAP).verdict());
    }

    @Test
    void pastTheCapAnInexactMirrorIsJudgedOnTheTruth() {
        // The truth is the server's own, so the mirror's exactness no longer matters: VALID, not UNKNOWN
        final MirrorBook book = book(3.0);
        book.onEvent(TARGET, new MirrorEvent.Move(true, true, new VecDelta.Stepped(List.of(new VecDelta.Stepped.DeltaStep((short) 0, (short) 0, (short) 41, 2))),
            0.0F, 0.0F, SENT), 1);
        book.onEvent(TARGET, new MirrorEvent.Sync(PositionPath.of(new Vec3(0.5, 64.0, 300.0)), 0.0F, 0.0F, SENT), 1);
        book.onViewerTickEnd(1);
        book.onViewerTickEnd(2);
        final HitCheck check = new HitValidator(truth(SENT + CAP, 500L)).validate(ATTACKER, TARGET, book, 2, List.of(), new CombatScenes.Blocks2(),
            SENT + 2L * CAP, CAP);
        assertEquals(HitCheck.Verdict.VALID, check.verdict());
        assertTrue(check.capped());
    }

    @Test
    void theRewindIsMeasuredFromTheDisplayedStep() {
        // The display is half-way into a step sent at SENT + 50, so the rewind runs from there
        final MirrorBook book = book(3.0);
        book.onEvent(TARGET, new MirrorEvent.Move(true, true, new VecDelta.Stepped(List.of(new VecDelta.Stepped.DeltaStep((short) 0, (short) 0, (short) 41, 2))),
            0.0F, 0.0F, SENT + 50L), 1);
        book.onViewerTickEnd(1);
        book.onViewerTickEnd(2);
        final HitCheck check = validator().validate(ATTACKER, TARGET, book, 2, List.of(), new CombatScenes.Blocks2(), SENT + 350L, CAP);
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
        final HitCheck check = validator().validate(ATTACKER, TARGET, book, 2, List.of(), new CombatScenes.Blocks2(), SENT, CAP);
        assertEquals(HitCheck.Verdict.UNKNOWN, check.verdict());
        assertEquals(HitCheck.Reason.INEXACT_TARGET, check.reason());
    }

    @Test
    void anAttackTickNotRunYetIsUnknown() {
        final MirrorBook book = book(3.0);
        book.onViewerTickEnd(1);
        assertEquals(HitCheck.Reason.NO_SAMPLE, validator().validate(ATTACKER, TARGET, book, 5, List.of(), new CombatScenes.Blocks2(), SENT, CAP).reason());
    }

    @Test
    void spearsAndRidingAttackersAreNotReplayed() {
        final AttackerSnapshot spear = new AttackerSnapshot(0.5, 64.0, 0.5, 0.5, 64.0, 0.5, 1.62F, 0.0F, 0.0F, player(0.5, 64.0, 0.5), 4.5, 3.0, true, false);
        final MirrorBook book = book(3.0);
        book.onViewerTickEnd(1);
        assertEquals(HitCheck.Reason.UNSUPPORTED, validator().validate(spear, TARGET, book, 1, List.of(), new CombatScenes.Blocks2(), SENT, CAP).reason());
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
