package gg.hcfrevival.revival.auth.net;

import static org.junit.jupiter.api.Assertions.assertEquals;

import gg.hcfrevival.revival.RevivalRegistries;
import gg.hcfrevival.revival.RevivalTest;
import it.unimi.dsi.fastutil.ints.IntOpenHashSet;
import java.util.UUID;
import net.minecraft.network.protocol.game.ClientboundGameEventPacket;
import net.minecraft.network.protocol.game.ClientboundSetEntityMotionPacket;
import net.minecraft.network.protocol.game.ClientboundTeleportEntityPacket;
import net.minecraft.world.entity.PositionMoveRotation;
import net.minecraft.world.phys.Vec3;
import org.junit.jupiter.api.Test;

/** Report 04 §7 and design §15.6: which packets change the client's physics. */
@RevivalTest
@RevivalRegistries
class OutboundClassifierTest {

    private static final UUID SELF = new UUID(3L, 4L);

    @Test
    void selfAndOthers() {
        final IntOpenHashSet players = new IntOpenHashSet();
        assertEquals(OutboundClassifier.SELF_MOTION, OutboundClassifier.classify(new ClientboundSetEntityMotionPacket(7, Vec3.ZERO), 7, SELF, players));
        assertEquals(OutboundClassifier.NONE, OutboundClassifier.classify(new ClientboundSetEntityMotionPacket(8, Vec3.ZERO), 7, SELF, players));
        final ClientboundTeleportEntityPacket teleport = new ClientboundTeleportEntityPacket(8, new PositionMoveRotation(Vec3.ZERO, Vec3.ZERO, 0.0F, 0.0F),
            java.util.Set.of(), false);
        assertEquals(OutboundClassifier.NONE, OutboundClassifier.classify(teleport, 7, SELF, players), "not a player this client was sent");
        players.add(8);
        assertEquals(OutboundClassifier.REMOTE_PLAYER, OutboundClassifier.classify(teleport, 7, SELF, players));
    }

    @Test
    void onlyGameModeGameEventsMatter() {
        final IntOpenHashSet players = new IntOpenHashSet();
        assertEquals(OutboundClassifier.GAME_MODE, OutboundClassifier.classify(new ClientboundGameEventPacket(ClientboundGameEventPacket.CHANGE_GAME_MODE, 1.0F), 7, SELF, players));
        assertEquals(OutboundClassifier.NONE, OutboundClassifier.classify(new ClientboundGameEventPacket(ClientboundGameEventPacket.START_RAINING, 0.0F), 7, SELF, players));
    }

    @Test
    void attachedRocketsAreFollowed() {
        final IntOpenHashSet players = new IntOpenHashSet();
        final IntOpenHashSet rockets = new IntOpenHashSet();
        final net.minecraft.network.protocol.game.ClientboundAddEntityPacket add = new net.minecraft.network.protocol.game.ClientboundAddEntityPacket(40,
            new UUID(5L, 6L), 0.0, 64.0, 0.0, 0.0F, 0.0F, net.minecraft.world.entity.EntityTypes.FIREWORK_ROCKET, 0, Vec3.ZERO, 0.0);
        assertEquals(OutboundClassifier.ROCKET, OutboundClassifier.classify(add, 7, SELF, players, rockets));
        rockets.add(40);
        final net.minecraft.network.protocol.game.ClientboundSetEntityDataPacket data = new net.minecraft.network.protocol.game.ClientboundSetEntityDataPacket(40,
            java.util.List.of(new net.minecraft.network.syncher.SynchedEntityData.DataValue<>(
                net.minecraft.world.entity.projectile.FireworkRocketEntity.DATA_ATTACHED_TO_TARGET.id(),
                net.minecraft.network.syncher.EntityDataSerializers.OPTIONAL_UNSIGNED_INT, java.util.OptionalInt.of(7))));
        assertEquals(OutboundClassifier.ROCKET, OutboundClassifier.classify(data, 7, SELF, players, rockets), "the attachment comes as entity data");
        assertEquals(OutboundClassifier.ROCKET, OutboundClassifier.classify(new net.minecraft.network.protocol.game.ClientboundRemoveEntitiesPacket(40), 7, SELF, players, rockets));
        players.add(41);
        assertEquals(OutboundClassifier.REMOTE_PLAYER, OutboundClassifier.classify(new net.minecraft.network.protocol.game.ClientboundRemoveEntitiesPacket(40, 41), 7, SELF, players, rockets),
            "a removal of a player and a rocket goes to the mirrors, which drop the rocket too");
        assertEquals(OutboundClassifier.NONE, OutboundClassifier.classify(new net.minecraft.network.protocol.game.ClientboundRemoveEntitiesPacket(42), 7, SELF, players, rockets));
    }

    @Test
    void classInfoIsWorkedOutOncePerClass() {
        // auth-load §9: the class decides the family, whether the tracked sets look at it and whether it is always bundleable
        final OutboundClassifier.ClassInfo motion = OutboundClassifier.info(new ClientboundSetEntityMotionPacket(7, Vec3.ZERO));
        org.junit.jupiter.api.Assertions.assertSame(motion, OutboundClassifier.info(new ClientboundSetEntityMotionPacket(8, Vec3.Y_AXIS)), "one info per class");
        org.junit.jupiter.api.Assertions.assertTrue(motion.plain && !motion.tracked);
        org.junit.jupiter.api.Assertions.assertTrue(OutboundClassifier.info(new net.minecraft.network.protocol.game.ClientboundRemoveEntitiesPacket(40)).tracked);
        org.junit.jupiter.api.Assertions.assertTrue(OutboundClassifier.info(new net.minecraft.network.protocol.game.ClientboundAddEntityPacket(40, new UUID(5L, 6L),
            0.0, 64.0, 0.0, 0.0F, 0.0F, net.minecraft.world.entity.EntityTypes.PLAYER, 0, Vec3.ZERO, 0.0)).tracked);
        // A class that overrides Paper's readiness (anti-xray chunks) or extra packets is asked per packet, never assumed bundleable
        final net.minecraft.network.protocol.Packet<?> held = new net.minecraft.network.protocol.game.ClientboundBlockUpdatePacket(net.minecraft.core.BlockPos.ZERO,
            net.minecraft.world.level.block.Blocks.STONE.defaultBlockState()) {
            @Override
            public boolean isReady() {
                return false;
            }
        };
        org.junit.jupiter.api.Assertions.assertFalse(OutboundClassifier.info(held).plain);
        assertEquals(OutboundClassifier.BLOCK_UPDATE, OutboundClassifier.info(held).family, "the family is still the type's");
    }

    @Test
    void pistonsAndShulkerBoxesMoveThePlayer() {
        final IntOpenHashSet players = new IntOpenHashSet();
        final net.minecraft.core.BlockPos pos = new net.minecraft.core.BlockPos(1, 2, 3);
        assertEquals(OutboundClassifier.PISTON, OutboundClassifier.classify(new net.minecraft.network.protocol.game.ClientboundBlockEventPacket(pos,
            net.minecraft.world.level.block.Blocks.PISTON, 0, 1), 7, SELF, players));
        assertEquals(OutboundClassifier.PISTON, OutboundClassifier.classify(new net.minecraft.network.protocol.game.ClientboundBlockEventPacket(pos,
            net.minecraft.world.level.block.Blocks.SHULKER_BOX, 1, 1), 7, SELF, players));
        assertEquals(OutboundClassifier.NONE, OutboundClassifier.classify(new net.minecraft.network.protocol.game.ClientboundBlockEventPacket(pos,
            net.minecraft.world.level.block.Blocks.NOTE_BLOCK, 0, 0), 7, SELF, players));
    }
    @Test
    void solidEntitiesAreFollowed() {
        final IntOpenHashSet players = new IntOpenHashSet();
        final IntOpenHashSet rockets = new IntOpenHashSet();
        final IntOpenHashSet solids = new IntOpenHashSet();
        final net.minecraft.network.protocol.game.ClientboundAddEntityPacket boat = new net.minecraft.network.protocol.game.ClientboundAddEntityPacket(50,
            new UUID(5L, 7L), 0.0, 64.0, 0.0, 0.0F, 0.0F, net.minecraft.world.entity.EntityTypes.OAK_BOAT, 0, Vec3.ZERO, 0.0);
        assertEquals(OutboundClassifier.SOLID_ENTITY, OutboundClassifier.classify(boat, 7, SELF, players, rockets, solids));
        final net.minecraft.network.protocol.game.ClientboundAddEntityPacket cart = new net.minecraft.network.protocol.game.ClientboundAddEntityPacket(51,
            new UUID(5L, 8L), 0.0, 64.0, 0.0, 0.0F, 0.0F, net.minecraft.world.entity.EntityTypes.MINECART, 0, Vec3.ZERO, 0.0);
        assertEquals(OutboundClassifier.NONE, OutboundClassifier.classify(cart, 7, SELF, players, rockets, solids), "a minecart is no collider");
        final ClientboundTeleportEntityPacket teleport = new ClientboundTeleportEntityPacket(50, new PositionMoveRotation(Vec3.ZERO, Vec3.ZERO, 0.0F, 0.0F),
            java.util.Set.of(), false);
        assertEquals(OutboundClassifier.NONE, OutboundClassifier.classify(teleport, 7, SELF, players, rockets, solids), "not one this client was sent");
        solids.add(50);
        assertEquals(OutboundClassifier.SOLID_ENTITY, OutboundClassifier.classify(teleport, 7, SELF, players, rockets, solids));
        assertEquals(OutboundClassifier.SOLID_ENTITY, OutboundClassifier.classify(new net.minecraft.network.protocol.game.ClientboundMoveEntityPacket.Pos(50,
            new net.minecraft.network.protocol.game.VecDelta.Linear((short) 1, (short) 0, (short) 0), true), 7, SELF, players, rockets, solids));
        assertEquals(OutboundClassifier.SELF_PASSENGERS, OutboundClassifier.classify(gg.hcfrevival.revival.auth.combat.SolidStreams.passengers(60, 50), 7, SELF, players, rockets, solids),
            "riding something: its vehicle places it (the passengers kind hands it to the solids)");
        assertEquals(OutboundClassifier.SELF_PASSENGERS, OutboundClassifier.classify(gg.hcfrevival.revival.auth.combat.SolidStreams.passengers(70), 7, SELF, players, rockets, solids),
            "a horse's empty list: the client ejects its player if it rode it (review 3 #2: the view stayed riding after a dismount)");
        assertEquals(OutboundClassifier.SOLID_ENTITY, OutboundClassifier.classify(new net.minecraft.network.protocol.game.ClientboundRemoveEntitiesPacket(50), 7, SELF, players, rockets, solids));
        rockets.add(40);
        assertEquals(OutboundClassifier.ROCKET, OutboundClassifier.classify(new net.minecraft.network.protocol.game.ClientboundRemoveEntitiesPacket(50, 40), 7, SELF, players, rockets, solids),
            "a removal of a rocket and a boat goes to the rockets, which hand it on");
        assertEquals(OutboundClassifier.SELF_PASSENGERS, OutboundClassifier.classify(gg.hcfrevival.revival.auth.combat.SolidStreams.passengers(50, 7), 7, SELF, players, rockets, solids), "driving the boat: the player's own vehicle");
    }
}
