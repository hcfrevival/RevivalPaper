package gg.hcfrevival.revival.auth.view;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;

import gg.hcfrevival.revival.RevivalRegistries;
import gg.hcfrevival.revival.RevivalTest;
import java.util.List;
import net.minecraft.network.protocol.game.ClientboundSetEntityDataPacket;
import net.minecraft.network.syncher.EntityDataSerializers;
import net.minecraft.network.syncher.SynchedEntityData;
import net.minecraft.world.entity.Pose;
import org.junit.jupiter.api.Test;

/** Design §3.3: self echoes in enforce mode say what the client already has. */
@RevivalTest
@RevivalRegistries
class SelfEchoTest {

    private static SynchedEntityData.DataValue<Byte> flags(final int value) {
        return new SynchedEntityData.DataValue<>(ClientView.DATA_SHARED_FLAGS, EntityDataSerializers.BYTE, (byte) value);
    }

    @Test
    void poseAndUseFlagsAreDroppedAndClientBitsRewritten() {
        final SynchedEntityData.DataValue<Pose> pose = new SynchedEntityData.DataValue<>(ClientView.DATA_POSE, EntityDataSerializers.POSE, Pose.CROUCHING);
        final SynchedEntityData.DataValue<Byte> using = new SynchedEntityData.DataValue<>(ClientView.DATA_LIVING_ENTITY_FLAGS, EntityDataSerializers.BYTE, (byte) 1);
        // the server says "on fire (bit 0), not sprinting"; the client is sprinting
        final ClientboundSetEntityDataPacket echo = new ClientboundSetEntityDataPacket(5, List.of(flags(1), pose, using));
        final ClientboundSetEntityDataPacket rewritten = SelfEcho.entityData(echo, true, false, false, 0, -1);
        assertEquals(1, rewritten.packedItems().size());
        assertEquals((byte) (1 | 1 << 3), rewritten.packedItems().getFirst().value());
    }

    @Test
    void nothingToChangeKeepsThePacket() {
        final ClientboundSetEntityDataPacket echo = new ClientboundSetEntityDataPacket(5, List.of(flags(1 << 3)));
        assertSame(echo, SelfEcho.entityData(echo, true, false, false, 0, -1));
    }

    @Test
    void onlyClientOwnedFieldsMeansNoPacket() {
        final SynchedEntityData.DataValue<Pose> pose = new SynchedEntityData.DataValue<>(ClientView.DATA_POSE, EntityDataSerializers.POSE, Pose.SWIMMING);
        assertNull(SelfEcho.entityData(new ClientboundSetEntityDataPacket(5, List.of(pose)), false, false, false, 0, -1));
    }

    @Test
    void meantFieldsKeepTheServersValue() {
        final SynchedEntityData.DataValue<Pose> pose = new SynchedEntityData.DataValue<>(ClientView.DATA_POSE, EntityDataSerializers.POSE, Pose.STANDING);
        final SynchedEntityData.DataValue<Byte> using = new SynchedEntityData.DataValue<>(ClientView.DATA_LIVING_ENTITY_FLAGS, EntityDataSerializers.BYTE, (byte) 0);
        // a plugin stops the sprint and the shield use; the client sprints, swims and glides
        final ClientboundSetEntityDataPacket echo = new ClientboundSetEntityDataPacket(5, List.of(flags(0), pose, using));
        final ClientboundSetEntityDataPacket rewritten = SelfEcho.entityData(echo, true, true, true, SelfEcho.SPRINT | SelfEcho.USE, -1);
        assertEquals(2, rewritten.packedItems().size());
        // sprint (meant) stays off, swim and glide are the client's
        assertEquals((byte) (1 << 4 | 1 << 7), rewritten.packedItems().get(0).value());
        assertSame(using, rewritten.packedItems().get(1));
    }

    @Test
    void aByteWhoseServerBitsTheClientHasIsNotSent() {
        // the client sprinted and stopped; the echo of its stop arrives after its next sprint (300 ms) and would undo it
        final ClientboundSetEntityDataPacket stop = new ClientboundSetEntityDataPacket(5, List.of(flags(0)));
        assertNull(SelfEcho.entityData(stop, true, false, false, 0, 0));
        // the shift key (bit 1) is the client's too: LocalPlayer reads it from its input
        assertNull(SelfEcho.entityData(new ClientboundSetEntityDataPacket(5, List.of(flags(1 << 1 | 1 << 3))), false, false, false, 0, 0));
        // the rest of the packet still goes out
        final SynchedEntityData.DataValue<Boolean> noGravity = new SynchedEntityData.DataValue<>(ClientView.DATA_NO_GRAVITY, EntityDataSerializers.BOOLEAN, true);
        final ClientboundSetEntityDataPacket both = SelfEcho.entityData(new ClientboundSetEntityDataPacket(5, List.of(flags(1 << 3), noGravity)),
            false, false, false, 0, 0);
        assertEquals(List.of(noGravity), both.packedItems());
        assertEquals(-1, SelfEcho.sharedFlags(both));
    }

    @Test
    void aServerBitChangeOrAMeantSprintStillGoesOut() {
        // on fire, with the client's sprint in the byte
        final ClientboundSetEntityDataPacket fire = SelfEcho.entityData(new ClientboundSetEntityDataPacket(5, List.of(flags(1))), true, false, false, 0, 0);
        assertEquals(1 | 1 << 3, SelfEcho.sharedFlags(fire));
        // invisible and glowing are the server's; an unknown record sends whatever comes
        assertEquals(1 << 5, SelfEcho.sharedFlags(SelfEcho.entityData(new ClientboundSetEntityDataPacket(5, List.of(flags(1 << 5))), false, false, false, 0, 0)));
        assertEquals(1 << 6, SelfEcho.sharedFlags(SelfEcho.entityData(new ClientboundSetEntityDataPacket(5, List.of(flags(1 << 6))), false, false, false, 0, 1 << 5)));
        assertEquals(0, SelfEcho.sharedFlags(SelfEcho.entityData(new ClientboundSetEntityDataPacket(5, List.of(flags(0))), false, false, false, 0, -1)));
        // a sprint stop the server meant (stop-sprint-on-block) goes out with no server bit changed
        assertEquals(0, SelfEcho.sharedFlags(SelfEcho.entityData(new ClientboundSetEntityDataPacket(5, List.of(flags(0))), true, false, false, SelfEcho.SPRINT, 0)));
    }

    @Test
    void aMeantPoseGoesOut() {
        final SynchedEntityData.DataValue<Pose> pose = new SynchedEntityData.DataValue<>(ClientView.DATA_POSE, EntityDataSerializers.POSE, Pose.SLEEPING);
        final ClientboundSetEntityDataPacket echo = new ClientboundSetEntityDataPacket(5, List.of(pose));
        assertSame(echo, SelfEcho.entityData(echo, false, false, false, SelfEcho.POSE, -1));
    }
}
