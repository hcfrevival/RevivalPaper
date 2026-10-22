package gg.hcfrevival.revival.auth.combat;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import gg.hcfrevival.revival.RevivalTest;
import java.util.List;
import java.util.Set;
import net.minecraft.network.protocol.game.VecDelta;
import net.minecraft.world.entity.Pose;
import net.minecraft.world.entity.PositionMoveRotation;
import net.minecraft.world.entity.PositionPath;
import net.minecraft.world.entity.PositionStep;
import net.minecraft.world.entity.Relative;
import net.minecraft.world.phys.Vec3;
import org.junit.jupiter.api.Test;

/**
 * Hand-computed traces of the 26.3 client's remote-player interpolation (report 03 §2.1, §5.1), against
 * {@link RemoteMirror}. Bit-exactness against the real client classes is proven by the rig (MirrorVerify) and pinned
 * here by {@code MirrorFingerprintTest}.
 */
@RevivalTest
class RemoteMirrorTest {

    private static final float W = 0.6F / 2.0F; // EntityDimensions.makeBoundingBox: width / 2.0F as a float

    private static RemoteMirror spawned() {
        final RemoteMirror mirror = new RemoteMirror(2);
        mirror.spawn(0.0, 64.0, 0.0, 0.0F, 0.0F, 0L);
        return mirror;
    }

    /** One {@code PosRot} with a one-step stepped delta of {@code dx} codec units on x, lasting {@code ticks}. */
    private static void stepX(final RemoteMirror mirror, final int dx, final int ticks, final long stamp) {
        mirror.move(true, true, new VecDelta.Stepped(List.of(new VecDelta.Stepped.DeltaStep((short) dx, (short) 0, (short) 0, ticks))), 0.0F, 0.0F, stamp);
    }

    @Test
    void vanillaFeedStartsHalfWayInThenFreezesWhenThePacketIsLate() {
        // Report 03 §5.1: a 2-tick step every 2 ticks. Restart sets currentStepTicks = 1, so the first tick shows half.
        final RemoteMirror mirror = spawned();
        stepX(mirror, 4096, 2, 1L); // to x = 1.0
        assertEquals(1, mirror.queuedSteps());
        assertEquals(1.0F, mirror.currentStepTicks());
        assertEquals(2.0F, mirror.remainingTicks());
        mirror.tick(1.0F);
        assertEquals(0.5, mirror.x()); // a = 1/2
        assertEquals(2.0F, mirror.currentStepTicks());
        assertEquals(1.0F, mirror.remainingTicks());
        mirror.tick(1.0F);
        assertEquals(1.0, mirror.x()); // step consumed, the target shown exactly
        assertEquals(0, mirror.queuedSteps());
        assertEquals(0.0F, mirror.remainingTicks());
        // The next packet is late: interpolate() finds the queue empty and cancels; the player freezes
        mirror.tick(1.0F);
        assertEquals(1.0, mirror.x());
        assertEquals(1.0F, mirror.interpolationSpeed());
    }

    @Test
    void twoTickStepsEveryTickSettleAtTheMidpointWithSpeedTwo() {
        // Report 03 §6.2, K = 2: backlog R- -> 4, speed -> 2, display at a -> 0.5 of a 2-tick step
        final RemoteMirror mirror = spawned();
        for (int tick = 0; tick < 60; tick++) {
            stepX(mirror, 4096, 2, tick); // one block per tick
            mirror.tick(1.0F);
        }
        assertEquals(2.0F, mirror.interpolationSpeed(), 1.0E-3F);
        assertEquals(2.0F, mirror.remainingTicks(), 1.0E-3F);
        // newest delivered end is x = 60; the display trails it by 1.5 path ticks of 1 block
        assertEquals(58.5, mirror.x(), 1.0E-2);
    }

    @Test
    void aPacketIdenticalToTheActiveTargetIsIgnored() {
        final RemoteMirror mirror = spawned();
        stepX(mirror, 4096, 2, 1L);
        final float remaining = mirror.remainingTicks();
        // Same end (zero delta from the new base) and same rotation: target.is(...) while active
        stepX(mirror, 0, 2, 2L);
        assertEquals(1, mirror.queuedSteps());
        assertEquals(remaining, mirror.remainingTicks());
    }

    @Test
    void rotationOnlyPacketHoldsThePositionForOneStepOfNTicks() {
        final RemoteMirror mirror = spawned();
        mirror.move(false, true, VecDelta.ZERO, 90.0F, 10.0F, 1L);
        assertEquals(1, mirror.queuedSteps());
        assertEquals(2.0F, mirror.remainingTicks()); // N = 2
        mirror.tick(1.0F);
        assertEquals(0.0, mirror.x());
        assertEquals(45.0F, mirror.yRot()); // rotLerp(0.5, 0, 90)
        assertEquals(5.0F, mirror.xRot());
    }

    @Test
    void steppedPathLerpsRotationAcrossItsStepsWhenTheRotationChanges() {
        // SteppedInterpolationHandler.addSteps: a = offset / total, rotLerp from the old target rotation
        final RemoteMirror mirror = spawned();
        mirror.move(true, true, new VecDelta.Stepped(List.of(
            new VecDelta.Stepped.DeltaStep((short) 4096, (short) 0, (short) 0, 1),
            new VecDelta.Stepped.DeltaStep((short) 4096, (short) 0, (short) 0, 3))), 80.0F, 0.0F, 1L);
        assertEquals(2, mirror.queuedSteps());
        assertEquals(4.0F, mirror.remainingTicks());
        assertEquals(2.0, mirror.targetX());
        mirror.tick(1.0F); // c = 1 >= 1: first step (x = 1, yaw 20) consumed, then a = 0/3 into the second
        assertEquals(1.0, mirror.x());
        assertEquals(20.0F, mirror.yRot());
    }

    @Test
    void endEqualToTheOldTargetCollapsesThePathIntoOneStepOfN() {
        final RemoteMirror mirror = spawned();
        stepX(mirror, 4096, 2, 1L);
        mirror.tick(1.0F);
        mirror.tick(1.0F); // drained, target x = 1.0
        // A 3-step path ending where the old target was: one step of N = 2 ticks (SteppedInterpolationHandler.java:33-34)
        mirror.move(true, true, new VecDelta.Stepped(List.of(
            new VecDelta.Stepped.DeltaStep((short) 100, (short) 0, (short) 0, 5),
            new VecDelta.Stepped.DeltaStep((short) -100, (short) 0, (short) 0, 5))), 30.0F, 0.0F, 2L);
        assertEquals(1, mirror.queuedSteps());
        assertEquals(2.0F, mirror.remainingTicks());
    }

    @Test
    void poseChangesTheBoxAtPacketTime() {
        final RemoteMirror mirror = spawned();
        assertEquals(64.0 + 1.7999999523162842, mirror.maxY());
        mirror.pose(Pose.CROUCHING);
        assertEquals(64.0 + 1.5F, mirror.maxY()); // refreshDimensions -> reapplyPosition, before the next tick
        mirror.pose(Pose.SWIMMING);
        assertEquals(64.0 + 0.6F, mirror.maxY());
        mirror.pose(Pose.DYING);
        assertEquals(-0.1F, mirror.minX(), 0.0); // fixed 0.2 x 0.2
        assertTrue(mirror.exact());
        mirror.pose(Pose.SLEEPING);
        assertFalse(mirror.exact()); // setPosToBed moves it: not modelled
    }

    @Test
    void scaleReachesTheBoxOnlyAfterTheEntityTick() {
        // LivingEntity.tick -> refreshDirtyAttributes -> onAttributeUpdated(SCALE) -> refreshDimensions, after interpolate
        final RemoteMirror mirror = spawned();
        mirror.scale(2.0F);
        assertEquals(-W, mirror.minX(), 0.0);
        mirror.tick(1.0F);
        assertEquals(-(0.6F * 2.0F / 2.0F), mirror.minX(), 0.0);
        assertEquals(64.0 + 1.8F * 2.0F, mirror.maxY());
        // A pose change reads the live scale at once
        mirror.scale(0.5F);
        mirror.pose(Pose.CROUCHING);
        assertEquals(64.0 + 1.5F * 0.5F, mirror.maxY());
    }

    @Test
    void farSyncWhileInterpolatingSnapsKeepsTheQueueAndIsNoLongerExact() {
        final RemoteMirror mirror = spawned();
        stepX(mirror, 4096, 2, 1L);
        mirror.sync(PositionPath.of(new Vec3(100.0, 64.0, 0.0)), 0.0F, 0.0F, 2L); // > 64 blocks from the display
        assertEquals(100.0, mirror.x()); // snapTo
        assertEquals(1, mirror.queuedSteps()); // queue NOT cleared
        assertTrue(mirror.exact());
        mirror.tick(1.0F); // adjustInterpolationTargetFromDeltas: the client tests its own world for collision
        assertFalse(mirror.exact());
    }

    @Test
    void farSyncWhileIdleSnapsExactly() {
        final RemoteMirror mirror = spawned();
        mirror.sync(PositionPath.of(new Vec3(100.0, 64.0, 0.0)), 0.0F, 0.0F, 2L);
        mirror.tick(1.0F);
        assertEquals(100.0, mirror.x());
        assertTrue(mirror.exact());
        assertEquals(100.0, mirror.baseX()); // the codec base takes the exact end
    }

    @Test
    void nearSyncInterpolatesItsSteppedPath() {
        final RemoteMirror mirror = spawned();
        mirror.sync(PositionPath.stepped(List.of(new PositionStep(new Vec3(2.0, 64.0, 0.0), 2), new PositionStep(new Vec3(4.0, 64.0, 0.0), 2))), 0.0F, 0.0F, 1L);
        assertEquals(2, mirror.queuedSteps());
        mirror.tick(1.0F);
        assertEquals(1.0, mirror.x());
        assertEquals(4.0, mirror.baseX());
    }

    @Test
    void teleportInterpolatesFromTheTargetUnlessFarAndLeavesTheCodecBase() {
        final RemoteMirror mirror = spawned();
        stepX(mirror, 4096, 2, 1L); // target x = 1
        mirror.teleport(new PositionMoveRotation(new Vec3(2.0, 0.0, 0.0), Vec3.ZERO, 0.0F, 0.0F), Set.of(Relative.X, Relative.Y, Relative.Z), 2L);
        assertEquals(3.0, mirror.targetX()); // relative to the active target, not the display
        assertEquals(1.0, mirror.baseX());
        mirror.teleport(new PositionMoveRotation(new Vec3(500.0, 64.0, 0.0), Vec3.ZERO, 0.0F, 0.0F), Set.of(), 3L);
        assertEquals(500.0, mirror.x()); // > 64 blocks: set directly
    }

    @Test
    void spawnClampsToTheWorldLimitButTheCodecBaseKeepsThePacketsDoubles() {
        final RemoteMirror mirror = new RemoteMirror(2);
        mirror.spawn(3.1E7, 64.0, -3.2E7, 190.0F, 120.0F, 0L);
        assertEquals(3.0E7, mirror.x());
        assertEquals(-3.0E7, mirror.z());
        assertEquals(3.1E7, mirror.baseX());
        assertEquals(190.0F, mirror.yRot()); // absSnapRotationTo: yRot % 360
        assertEquals(90.0F, mirror.xRot()); // clamp(xRot, -90, 90) % 360, clamped again
    }

    @Test
    void decodeKeepsTheBasesExactDoubleOnAZeroAxisAndLandsOnTheGridOtherwise() {
        // VecDeltaCodec.decode: (encode(base) + delta) / 4096 for a moving axis, the base itself for a still one
        assertEquals(0.30000001192092896, RemoteMirror.decode(0.30000001192092896, 0L));
        assertEquals((Math.round(0.3 * 4096.0) + 7) / 4096.0, RemoteMirror.decode(0.3, 7L));
    }

    @Test
    void displayStampFollowsTheStepBeingShown() {
        final RemoteMirror mirror = spawned();
        stepX(mirror, 4096, 2, 10L);
        stepX(mirror, 4096, 2, 11L);
        mirror.tick(1.0F);
        assertEquals(10L, mirror.displayStamp());
        mirror.tick(1.0F);
        mirror.tick(1.0F);
        assertEquals(11L, mirror.displayStamp());
    }
}
